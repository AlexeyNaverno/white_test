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
 *   1) дедупликация — одна строка на конфиг (дубли из разных сборников не занимают пробы);
 *   2) кластеры: конфиги группируются по провайдеру ({@link Cluster}), внутри кластера они
 *      с одной судьбой;
 *   3) «нерешённые» из прошлого прогона ({@link Candidates}) идут первыми — они уже
 *      отсеяны по TCP, значит дешёвые;
 *   4) очередь «чересстрочная»: партия из 100 — это 100 РАЗНЫХ провайдеров, а кластеры,
 *      признанные мёртвыми по TTL ({@link ClusterStore}), уходят в конец;
 *   5) каждая партия проверяется в ОДНОМ ядре ({@link BatchProbe}): сто проб
 *      одновременно, окно 3 с, 64 КБ на пробу;
 *   6) результат раскладывается сразу: рабочие — в пул, «ответ без данных» — в чёрный
 *      список, «не успели/оборвалось» — в «нерешённые». Пул сохраняется после каждой
 *      партии, поэтому подключаться можно, не дожидаясь конца обхода.
 *
 * Итог: первые рабочие конфиги появляются на первых секундах, полное покрытие — за время
 * в разы меньшее, а вердикты не теряются между прогонами.
 */
public final class PoolScan {
    private PoolScan() {}

    /** Сколько свежий замер считается годным и не перемеряется. */
    public static final long REUSE_MS = 24L * 60 * 60 * 1000;

    public interface Log {
        void line(String message);
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
        public int reused;
        public long ms;
        public boolean cancelled;
        /** Кого имеет смысл добить через DPI-обход (не «данные не идут», а «не успели»). */
        public final List<ProxyConfig> failed = new ArrayList<>();

        public String summary() {
            return "проверено " + tested + " из " + unique + " (дублей " + duplicates
                    + "), рабочих " + ok + ", в ЧС " + hard + ", нерешённых " + undecided
                    + ", кластеров " + clusters + " (живых " + clustersAlive
                    + ", мёртвых " + clustersDead + ") за " + seconds();
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

        // 1) дедупликация
        List<ProxyConfig> unique = Cluster.dedupe(all);
        out.unique = unique.size();
        out.duplicates = Math.max(0, out.total - out.unique);
        if (out.duplicates > 0 && log != null) {
            log.line("Дубликаты: " + out.total + " → " + out.unique
                    + " (вычеркнуто " + out.duplicates + ")");
        }
        if (unique.isEmpty()) {
            out.ms = System.currentTimeMillis() - started;
            return out;
        }

        // 2) кластеры + вердикты прошлых прогонов
        String net = Net.key(c);
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

        // 3) «нерешённые» — первыми
        Set<String> known = new HashSet<>();
        for (Candidates.Entry e : Candidates.all(c, net)) {
            String k = ConfigRepository.canonicalKey(e.raw);
            if (k != null && !k.isEmpty()) known.add(k);
        }
        List<ProxyConfig> order = new ArrayList<>();
        List<ProxyConfig> undecided = new ArrayList<>();
        List<ProxyConfig> rest = Cluster.interleave(clusters, dead);
        for (ProxyConfig cfg : rest) {
            String k = ConfigRepository.canonicalKey(cfg.raw);
            if (k != null && known.contains(k)) undecided.add(cfg);
            else order.add(cfg);
        }
        int undecidedFirst = undecided.size();
        order.addAll(0, undecided);
        if (undecidedFirst > 0 && log != null) {
            log.line("Нерешённых с прошлого раза: " + undecidedFirst + " — проверяю первыми");
        }

        // 4) что уже есть в пуле и не протухло — не перемеряем
        Map<String, Pool.Entry> pool = new LinkedHashMap<>();
        for (Pool.Entry e : Pool.load(c)) {
            String key = ConfigRepository.canonicalKey(e.raw);
            if (key != null) pool.put(key, e);
        }
        long now = System.currentTimeMillis();
        List<ProxyConfig> queue = new ArrayList<>();
        for (ProxyConfig cfg : order) {
            String key = ConfigRepository.canonicalKey(cfg.raw);
            Pool.Entry old = key == null ? null : pool.get(key);
            if (old != null && old.at > 0 && now - old.at < REUSE_MS) {
                out.reused++;
                continue;
            }
            queue.add(cfg);
        }
        if (out.reused > 0 && log != null) {
            log.line("Свежих замеров (моложе " + (REUSE_MS / 3600000) + " ч): " + out.reused
                    + " — не перемеряю");
        }
        if (queue.isEmpty()) {
            out.ms = System.currentTimeMillis() - started;
            return out;
        }

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
            for (BatchProbe.Result r : probe.results) {
                ProxyConfig cfg = r.cfg;
                String clusterKey = Cluster.key(cfg);
                int[] cnt = perCluster.get(clusterKey);
                if (cnt == null) {
                    cnt = new int[2];      // [hits, tested]
                    perCluster.put(clusterKey, cnt);
                }
                cnt[1]++;
                if (r.ok) {
                    cnt[0]++;
                    out.ok++;
                    long kbps = r.kbps > 0 ? r.kbps : Prefs.minSpeedKbps(c);
                    Pool.remember(c, cfg.raw, kbps, r.ms, "");
                    Blacklist.forget(c, cfg.raw);
                    Candidates.remove(c, net, cfg.raw);
                    if (log != null) {
                        log.line("  ✓ " + cfg.name + " · " + r.text());
                    }
                } else if (r.hard) {
                    out.hard++;
                    Blacklist.add(c, net, cfg, r.reason);
                    Candidates.remove(c, net, cfg.raw);
                    if (log != null) {
                        log.line("  ✗ " + cfg.name + " — " + r.reason + " → в ЧС");
                    }
                } else if (r.reason != null && r.reason.contains("ретранслятор")) {
                    relay.add(cfg);
                } else {
                    out.undecided++;
                    Candidates.add(c, net, cfg.raw, r.reason);
                    out.failed.add(cfg);
                }
            }
            // Прогресс: пул уже сохранён, подключаться можно в любой момент.
            if (log != null) {
                log.line("  " + probe.summary() + " · всего рабочих " + out.ok
                        + " · в пуле " + Pool.count(c));
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
                if (r.ok && r.kbps > 0) {
                    out.ok++;
                    Pool.remember(c, cfg.raw, r.kbps, cfg.latency, "");
                    Blacklist.forget(c, cfg.raw);
                    Candidates.remove(c, net, cfg.raw);
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
        return out;
    }
}
