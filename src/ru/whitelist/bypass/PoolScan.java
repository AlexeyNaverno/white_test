package ru.whitelist.bypass;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сбор пула по кластерам и партиями — конвейер 0.1.18.
 *
 * Раньше: список перемешивался, пакетами по 200 шёл полный пинг, а потом каждый выживший
 * конфиг поднимался ОТДЕЛЬНЫМ ядром (6 дорожек). На тысяче строк это минуты, а на пяти
 * тысячах — десятки минут, при этом первые рабочие конфиги находились в самом конце.
 *
 * Теперь:
 *
 *   0) рабочие из пула (0.1.20) ставятся в САМОЕ НАЧАЛО очереди и вычищаются из рабочего
 *      списка: они перемеряются первыми, и в пул возвращаются только те, что реально
 *      скачали файл. Не прошёл — вылетает молча, без «мёртвого» балласта в рабочих;
 *   1) дедупликация — одна строка на конфиг (дубли из разных сборников не занимают пробы);
 *   2) кластеры: конфиги группируются по провайдеру ({@link Cluster}), внутри кластера они
 *      с одной судьбой;
 *   3) «нерешённые» из прошлого прогона ({@link Candidates}) идут следующими — они уже
 *      отсеяны по TCP, значит дешёвые;
 *   4) очередь «чересстрочная»: партия из 100 — это 100 РАЗНЫХ провайдеров, а кластеры,
 *      признанные мёртвыми по TTL ({@link ClusterStore}), уходят в конец;
 *   5) каждая партия проверяется в ОДНОМ ядре ({@link BatchProbe}): сто проб
 *      одновременно, окно 3 с, 64 КБ на пробу;
 *   6) результат раскладывается сразу: в пул — ТОЛЬКО конфиги с измеренной скоростью
 *      (файл реально скачался и прошёл порог), «ответ без данных» — в чёрный список,
 *      «не успели/оборвалось/мало данных» — в «нерешённые». Пул сохраняется после каждой
 *      партии, поэтому подключаться можно, не дожидаясь конца обхода.
 *
 * Итог: первые рабочие конфиги появляются на первых секундах, полное покрытие — за время
 * в разы меньшее, а вердикты не теряются между прогонами.
 */
public final class PoolScan {
    private PoolScan() {}

    public interface Log {
        void line(String message);

        /**
         * Живой счётчик для главного экрана: сколько рабочих уже найдено и сколько
         * проверено из всей очереди. Нужен, чтобы человек видел результат, не открывая
         * лог, и мог остановить сбор, когда найденного достаточно.
         */
        void progress(int ok, int tested, int total);
    }

    /** Итог прогона. */
    public static final class Outcome {
        public int total;
        public int unique;
        public int duplicates;
        public int clusters;
        public int clustersAlive;
        public int clustersDead;
        public int clustersNewlyDead;
        public int tested;
        public int ok;
        public int hard;
        public int undecided;
        /** Сколько рабочих из пула перемерялось первыми (0.1.20). */
        public int poolFirst;
        /** Из них вернулись в пул (подтвердили скорость). */
        public int poolBack;
        public long ms;
        public boolean cancelled;
        /** Кого имеет смысл добить через DPI-обход (не «данные не идут», а «не успели»). */
        public final List<ProxyConfig> failed = new ArrayList<>();

        public String summary() {
            return "проверено " + tested + " из " + unique + " (дублей " + duplicates
                    + "), рабочих " + ok + ", в ЧС " + hard + ", нерешённых " + undecided
                    + (poolFirst > 0 ? ", из пула подтвердили " + poolBack + " из " + poolFirst : "")
                    + ", кластеров " + clusters + " (живых сейчас " + clustersAlive
                    + ", новых мёртвых " + clustersNewlyDead
                    + ", в памяти мёртвых " + clustersDead + ") за " + seconds();
        }

        private String seconds() {
            if (ms < 1000) return ms + " мс";
            return String.format(java.util.Locale.US, "%.1f с", ms / 1000.0);
        }
    }

    /**
     * Прогнать список через весь конвейер.
     *
     * @param all кандидаты (уже без чёрного списка, без несовместимых с ядром и после
     *            быстрого TCP-отсева — то есть те, чей сервер сейчас открывается)
     */
    public static Outcome run(Context c, List<ProxyConfig> all, Log log) {
        long started = System.currentTimeMillis();
        Outcome out = new Outcome();
        if (all != null) out.total = all.size();

        // 0) рабочие из пула — в САМОЕ НАЧАЛО очереди и из пула вон (0.1.20).
        // Пул очищается до перезамера: в рабочие вернутся только те, кто подтвердит
        // измеренную скорость. Не прошёл — вылетает молча, без балласта в «рабочих».
        String net = Net.key(c);
        List<Pool.Entry> poolBefore = Pool.load(c);
        List<ProxyConfig> poolCfgs = new ArrayList<>();
        Set<String> poolKeys = new HashSet<>();
        for (Pool.Entry e : poolBefore) {
            ProxyConfig p = ProxyConfig.parse(e.raw, "пул", false);
            if (p == null || !p.supported || p.host == null || p.host.isEmpty() || p.port <= 0) {
                continue;
            }
            p.latency = e.pingMs;
            String k = ConfigRepository.canonicalKey(p.raw);
            if (k == null || !poolKeys.add(k)) continue;
            poolCfgs.add(p);
        }
        out.poolFirst = poolCfgs.size();
        if (out.poolFirst > 0 && log != null) {
            log.line("Рабочие из пула: " + out.poolFirst + " — в начало очереди; "
                    + "из пула убираются до подтверждения скорости");
        }

        // 1) дедупликация: сначала рабочие из пула, потом весь корпус
        List<ProxyConfig> union = new ArrayList<>(poolCfgs);
        if (all != null) union.addAll(all);
        List<ProxyConfig> unique = Cluster.dedupe(union);
        out.unique = unique.size();
        out.duplicates = Math.max(0, union.size() - out.unique);
        if (out.duplicates > 0 && log != null) {
            log.line("Дубликаты: " + union.size() + " → " + out.unique
                    + " (вычеркнуто " + out.duplicates + ")");
        }
        if (unique.isEmpty()) {
            out.ms = System.currentTimeMillis() - started;
            return out;
        }
        if (log != null) log.progress(0, 0, out.unique);

        // 2) кластеры + вердикты прошлых прогонов
        Map<String, List<ProxyConfig>> clusters = Cluster.group(unique);
        Set<String> dead = ClusterStore.dead(c, net);
        out.clusters = clusters.size();
        out.clustersDead = dead.size();
        if (log != null) {
            log.line("Кластеры: " + clusters.size() + " провайдеров, из них с несколькими "
                    + "строками " + Cluster.multiLineClusters(clusters)
                    + (dead.isEmpty() ? "" : ", помечено мёртвыми " + dead.size())
                    + " · состояние: " + ClusterStore.summary(c, net));
        }

        // 3) очередь: рабочие из пула → «нерешённые» → остальные (чересстрочно)
        Set<String> known = new HashSet<>();
        for (Candidates.Entry e : Candidates.all(c, net)) {
            String k = ConfigRepository.canonicalKey(e.raw);
            if (k != null && !k.isEmpty()) known.add(k);
        }
        List<ProxyConfig> undecided = new ArrayList<>();
        List<ProxyConfig> rest = Cluster.interleave(clusters, dead);
        List<ProxyConfig> tail = new ArrayList<>();
        for (ProxyConfig cfg : rest) {
            String k = ConfigRepository.canonicalKey(cfg.raw);
            if (k != null && poolKeys.contains(k)) continue;     // уже в голове очереди
            if (k != null && known.contains(k)) undecided.add(cfg);
            else tail.add(cfg);
        }
        List<ProxyConfig> queue = new ArrayList<>();
        queue.addAll(poolCfgs);
        queue.addAll(undecided);
        queue.addAll(tail);
        if (!undecided.isEmpty() && log != null) {
            log.line("Нерешённых с прошлого раза: " + undecided.size() + " — идут сразу после пула");
        }
        if (queue.isEmpty()) {
            out.ms = System.currentTimeMillis() - started;
            return out;
        }

        // Пул чистим только здесь — когда очередь собрана и прогон действительно начнётся.
        // Так отмена «на входе» не оставит человека без рабочих конфигов.
        if (out.poolFirst > 0) Pool.clear(c);

        // 5) партии в одном ядре
        final int size = Prefs.batchSize(c);
        final int batches = (queue.size() + size - 1) / size;
        if (log != null) {
            log.line("К батч-замеру " + queue.size() + " конфигов: " + batches
                    + " партий по " + size + ", проб одновременно " + Prefs.batchThreads(c)
                    + ", окно " + Prefs.gateTimeoutMs(c) + " мс");
        }

        // Счётчики вердиктов по кластерам этого прогона.
        Map<String, int[]> perCluster = new LinkedHashMap<>();
        // Конфиги без TLS с ретранслятором в батч не встают — их добираем отдельно.
        List<ProxyConfig> relay = new ArrayList<>();

        // Интерфейсы логов разные по имени, поэтому передаём адаптер, а не сам Log.
        final Log sink = log;
        BatchProbe.Log probeLog = sink == null ? null : new BatchProbe.Log() {
            @Override public void line(String message) {
                sink.line(message);
            }
        };

        for (int from = 0; from < queue.size(); from += size) {
            if (ConfigRepository.isCancelled()) {
                out.cancelled = true;
                break;
            }
            int batchNo = from / size + 1;
            List<ProxyConfig> batch = new ArrayList<>(
                    queue.subList(from, Math.min(queue.size(), from + size)));
            if (log != null) {
                log.line("Партия " + batchNo + "/" + batches + ": " + batch.size()
                        + " конфигов · в пуле " + out.ok + " новых");
            }
            BatchProbe.Outcome probe = BatchProbe.run(c, batch, probeLog);
            out.tested += probe.results.size();
            if (probe.cancelled) {
                out.cancelled = true;
            }
            // Записи в списки — пачкой на партию: поштучная запись в SharedPreferences
            // на тысячах строк была квадратичной и тормозила сбор (0.1.21).
            List<String> okRaws = new ArrayList<>();
            List<String> hardRaws = new ArrayList<>();
            List<String> candRaws = new ArrayList<>();
            List<String> candReasons = new ArrayList<>();
            for (BatchProbe.Result r : probe.results) {
                ProxyConfig cfg = r.cfg;
                if (r.configBroken) {
                    // Ядро не принимает саму строку: приговор строке, но не провайдеру.
                    out.hard++;
                    hardRaws.add(cfg.raw);
                    if (log != null) {
                        log.line("  ✗ " + cfg.name + " — " + r.reason + " → в ЧС");
                    }
                    continue;
                }
                String clusterKey = Cluster.key(cfg);
                int[] cnt = perCluster.get(clusterKey);
                if (cnt == null) {
                    cnt = new int[2];      // [hits, tested]
                    perCluster.put(clusterKey, cnt);
                }
                cnt[1]++;
                if (r.ok) {
                    // В рабочие — ТОЛЬКО измеренная скорость: файл реально скачался
                    // (минимум Probe.MIN_SAMPLE_BYTES) и прошёл порог из настроек.
                    long kbps = r.kbps;
                    int minKbps = Prefs.minSpeedKbps(c);
                    if (kbps <= 0 || r.bytes < Probe.MIN_SAMPLE_BYTES) {
                        out.undecided++;
                        candRaws.add(cfg.raw);
                        candReasons.add("скорость не измерена");
                        out.failed.add(cfg);      // файл не скачался — кандидат на DPI-добор
                        if (log != null) {
                            log.line("  ~ " + cfg.name + " — файл не скачался целиком, "
                                    + "скорость не измерена → в «нерешённые»");
                        }
                        continue;
                    }
                    if (kbps < minKbps) {
                        out.undecided++;
                        candRaws.add(cfg.raw);
                        candReasons.add("медленнее порога: " + kbps + " КБ/с");
                        out.failed.add(cfg);      // канал есть, но бутылочное горлышко — пробуем обход
                        if (log != null) {
                            log.line("  ~ " + cfg.name + " · " + Prefs.speedText(kbps)
                                    + " — медленнее порога " + Prefs.minSpeedText(c)
                                    + " → в «нерешённые»");
                        }
                        continue;
                    }
                    cnt[0]++;
                    out.ok++;
                    String key = ConfigRepository.canonicalKey(cfg.raw);
                    if (key != null && poolKeys.contains(key)) out.poolBack++;
                    Pool.remember(c, cfg.raw, kbps, r.ms, "");
                    Blacklist.forget(c, cfg.raw);
                    okRaws.add(cfg.raw);
                    if (log != null) {
                        log.line("  ✓ " + cfg.name + " · " + r.text());
                    }
                } else if (r.hard) {
                    out.hard++;
                    hardRaws.add(cfg.raw);
                    if (log != null) {
                        log.line("  ✗ " + cfg.name + " — " + r.reason + " → в ЧС");
                    }
                } else if (r.reason != null && r.reason.contains("ретранслятор")) {
                    relay.add(cfg);
                } else {
                    out.undecided++;
                    candRaws.add(cfg.raw);
                    candReasons.add(r.reason);
                    out.failed.add(cfg);
                }
            }
            if (!okRaws.isEmpty()) Candidates.removeAll(c, net, okRaws);
            if (!hardRaws.isEmpty()) {
                Candidates.removeAll(c, net, hardRaws);
                Blacklist.addAll(c, net, hardRaws);
            }
            if (!candRaws.isEmpty()) Candidates.addAll(c, net, candRaws, candReasons);
            // Прогресс: пул уже сохранён, подключаться можно в любой момент.
            if (log != null) {
                log.line("  " + probe.summary() + " · всего рабочих " + out.ok
                        + " · в пуле " + Pool.count(c));
            }
            if (log != null) {
                log.progress(out.ok, out.tested, out.unique);
            }
            if (out.ok > 0 && out.ok <= 3) {
                if (log != null) {
                    log.line("Уже можно подключаться: в пуле есть рабочие конфиги ("
                            + out.ok + "). Сбор продолжится в фоне.");
                }
            }
        }

        // 5.1) отдельный путь для конфигов без TLS: у них своя линия через
        // dokodemo-door, в общий батч они не встают. Их обычно единицы, поэтому
        // проверяем по одному, но не больше восьми за прогон.
        if (!relay.isEmpty() && !out.cancelled) {
            int limit = Math.min(8, relay.size());
            if (log != null) {
                log.line("Отдельно (нужен ретранслятор, без TLS): " + limit
                        + " из " + relay.size());
            }
            for (int i = 0; i < limit; i++) {
                if (ConfigRepository.isCancelled()) break;
                ProxyConfig cfg = relay.get(i);
                Probe.Result r = Probe.measureConfig(c, cfg, Prefs.gateTimeoutMs(c));
                int minKbps = Prefs.minSpeedKbps(c);
                if (r.ok && r.kbps > 0 && r.kbps >= minKbps) {
                    out.ok++;
                    String key = ConfigRepository.canonicalKey(cfg.raw);
                    if (key != null && poolKeys.contains(key)) out.poolBack++;
                    Pool.remember(c, cfg.raw, r.kbps, cfg.latency, "");
                    Blacklist.forget(c, cfg.raw);
                    Candidates.remove(c, net, cfg.raw);
                } else if (r.ok && r.kbps > 0) {
                    // Файл скачался, но медленнее порога — не приговор, а «перезже».
                    out.undecided++;
                    Candidates.add(c, net, cfg.raw, "медленнее порога: " + r.kbps + " КБ/с");
                } else if (r.hard) {
                    out.hard++;
                    Blacklist.add(c, net, cfg, r.reason);
                    Candidates.remove(c, net, cfg.raw);
                } else {
                    out.undecided++;
                    Candidates.add(c, net, cfg.raw, r.reason);
                }
            }
        }

        // 6) вердикты кластеров: живой — если хоть одна строка поднялась; мёртвый — если
        // проверено минимум две и ни одна не поднялась (одна могла упасть из-за кредов).
        int newlyDead = 0;
        int newlyAlive = 0;
        for (Map.Entry<String, int[]> e : perCluster.entrySet()) {
            int hits = e.getValue()[0];
            int tested = e.getValue()[1];
            if (hits > 0) newlyAlive++;
            else if (tested >= 2) newlyDead++;
            ClusterStore.mark(c, net, e.getKey(), hits, tested);
        }
        out.clustersAlive = newlyAlive;
        out.clustersNewlyDead = newlyDead;
        if (log != null && (newlyAlive > 0 || newlyDead > 0)) {
            log.line("Вердикты кластеров: живых " + newlyAlive + ", мёртвых " + newlyDead
                    + " (мёртвые не трогаем " + (ClusterStore.TTL_DEAD_MS / 3600000) + " ч)");
        }

        out.ms = System.currentTimeMillis() - started;
        if (log != null) log.progress(out.ok, out.tested, out.unique);
        return out;
    }
}
