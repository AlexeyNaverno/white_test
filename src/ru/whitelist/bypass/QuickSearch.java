package ru.whitelist.bypass;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «Быстрый поиск»: найти ОДИН рабочий конфиг с минимальными затратами.
 *
 * Логика (как договорились):
 *   1) проверяем сеть: параллельный пинг до 5 целей — видно, живой ли вообще интернет;
 *   2) сначала лучшие из пула (они уже замерены — обычно хватает одной проверки);
 *   3) дальше «кругами»: берём партию до {@link #PING_BATCH} непроверенных конфигов,
 *      пингуем ВСЮ партию параллельно, живых сортируем по пингу и проверяем подряд
 *      ДО ПЕРВОГО рабочего;
 *   4) если в круге рабочего нет — следующий круг;
 *   5) когда кандидаты кончились — подгружаем источники ПО ОДНОМУ файлу и ищем среди
 *      новых строк;
 *   6) если и это не дало ничего — один раз пробуем Tor (пользователь может остановить).
 *
 * В лог пишется каждая проверка с результатом: «OK · 512 КБ/с» или «не прошёл · SOCKS
 * не открылся за 6 с».
 */
public final class QuickSearch {
    private QuickSearch() {}

    public interface Log {
        void line(String message);
    }

    /** Сколько конфигов пингуем за один круг. */
    public static final int PING_BATCH = 150;
    /** Потолок реальных проверок за круг; остальных ЖИВЫХ переносим в следующий круг. */
    public static final int PROBES_PER_ROUND = 25;
    /**
     * Сколько конфигов из пула разрешено проверить в самом конце, когда новых не осталось.
     * Пул обычно и есть ответ: конфиги там уже замерены, берём самые быстрые.
     */
    private static final int POOL_FALLBACK = 5;
    /** Сколько живых показываем в логе для наглядности. */
    private static final int SHOW_ALIVE = 8;

    public static final class Found {
        public final ProxyConfig cfg;
        public final long kbps;

        Found(ProxyConfig cfg, long kbps) {
            this.cfg = cfg;
            this.kbps = kbps;
        }
    }

    /** Убирает из очереди то, что уже в пуле: такие конфиги проверяет только «Собрать пул». */
    private static List<ProxyConfig> withoutPool(List<ProxyConfig> queue,
                                                 java.util.Set<String> inPool) {
        if (inPool.isEmpty()) return queue;
        List<ProxyConfig> out = new ArrayList<>();
        for (ProxyConfig p : queue) {
            String key = ConfigRepository.canonicalKey(p.raw);
            if (key != null && inPool.contains(key)) continue;
            out.add(p);
        }
        return out;
    }

    public static Found find(Context c, Log log) {
        final int min = Prefs.minSpeedKbps(c);
        final String minText = Prefs.minSpeedText(c);
        final int threads = Prefs.pingThreads(c);
        final int pingTimeout = Prefs.pingTimeoutMs(c);
        final int gateTimeout = Prefs.gateTimeoutMs(c);

        // --- 1) сеть вообще жива?
        NetChecker.PingStats net = NetChecker.pingAll(Prefs.pingTargets(c), pingTimeout);
        log.line("Сеть: " + net.text());
        if (net.ok > 0) {
            StringBuilder sb = new StringBuilder("Отвечают: ");
            for (int i = 0; i < Math.min(5, net.alive.size()); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(net.alive.get(i));
            }
            log.line(sb.toString());
        } else {
            log.line("Ни одна цель не ответила — похоже, интернета нет. Всё равно пробую.");
        }
        if (ConfigRepository.isCancelled()) return null;

        // --- 2) пул НЕ перепроверяем: это делает «Собрать пул» (и выкидывает мёртвое).
        // Быстрый поиск нужен, чтобы добавить ещё один вариант — значит, ищем только новое.
        java.util.Set<String> inPool = Pool.keySet(c);
        log.line(inPool.isEmpty()
                ? "Пул пуст — ищу среди всех конфигов"
                : "В пуле уже " + inPool.size() + " конфигов: их не перепроверяю, ищу новые");

        // --- 3) круги по спискам с ленивой подгрузкой источников
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<ProxyConfig> queue = new ArrayList<>();
        addAll(queue, seen, ConfigRepository.downloaded(c));
        addAll(queue, seen, ConfigRepository.embedded(c));
        int beforeFilter = queue.size();
        queue = withoutPool(queue, inPool);
        if (beforeFilter > queue.size()) {
            log.line("Уже в пуле: " + (beforeFilter - queue.size()) + " — пропускаю");
        }
        log.line("Сначала перебираю локальный список: " + queue.size()
                + " конфигов (источники — только когда он кончится)");

        List<String> sources = Prefs.sources(c);
        List<String> pending = new ArrayList<>(sources);
        List<String> failed = new ArrayList<>();
        int nextIdx = 0;
        int round = 0;
        boolean torTried = false;
        boolean poolTried = false;
        List<ProxyConfig> carry = new ArrayList<>();

        for (;;) {
            if (ConfigRepository.isCancelled()) {
                log.line("Поиск остановлен");
                return null;
            }

            // уже пропингованные в прошлом круге — проверяем их первыми
            if (!carry.isEmpty()) {
                List<ProxyConfig> rest = new ArrayList<>(carry);
                carry.clear();
                log.line("До-проверяю живых из прошлого круга: " + rest.size());
                Found hit = probeList(c, rest, log, min, minText, gateTimeout, rest.size());
                if (hit != null) return hit;
                if (ConfigRepository.isCancelled()) {
                    log.line("Поиск остановлен");
                    return null;
                }
                continue;
            }

            // набираем партию непроверенных
            List<ProxyConfig> batch = new ArrayList<>();
            while (nextIdx < queue.size() && batch.size() < PING_BATCH) {
                ProxyConfig cfg = queue.get(nextIdx++);
                if (cfg != null) batch.add(cfg);
            }

            if (batch.isEmpty()) {
                // кандидаты кончились — подгружаем ОДИН источник
                if (!pending.isEmpty()) {
                    String src = pending.remove(0);
                    log.line("Подгружаю источник: " + ConfigRepository.shortSource(src));
                    int added = pullSource(c, src, false, log);
                    if (added < 0) {
                        failed.add(src);
                    } else {
                        List<ProxyConfig> fresh = new ArrayList<>();
                        addAll(fresh, seen, ConfigRepository.downloaded(c));
                        fresh = withoutPool(fresh, inPool);
                        queue.addAll(fresh);   // seen уже отфильтровал дубли, withoutPool — пул
                        log.line("  новых конфигов: " + added + " (всего в хранилище "
                                + ConfigRepository.countDownloaded(c) + ")");
                    }
                    continue;
                }
                // Новых кандидатов не осталось. Прежде чем идти в Tor, смотрим в пул:
                // там уже замеренные конфиги, и «быстрый поиск», который не находит
                // ничего при непустом пуле, пользователю бесполезен.
                if (!poolTried) {
                    poolTried = true;
                    List<ProxyConfig> fromPool = poolCandidates(c, inPool, POOL_FALLBACK);
                    if (!fromPool.isEmpty()) {
                        log.line("Новых конфигов нет — проверяю лучшие из пула: "
                                + fromPool.size());
                        Found hit = probeList(c, fromPool, log, min, minText, gateTimeout,
                                fromPool.size());
                        if (hit != null) return hit;
                        if (ConfigRepository.isCancelled()) {
                            log.line("Поиск остановлен");
                            return null;
                        }
                    }
                }
                // источники кончились — один раз пробуем Tor
                if (!torTried && !failed.isEmpty()) {
                    torTried = true;
                    log.line("Прямые источники не отдали данные — поднимаю Tor (можно остановить поиск)…");
                    if (ConfigRepository.startTor(c)) {
                        log.line("Tor поднят, качаю источники через него");
                        for (String src : new ArrayList<>(failed)) {
                            if (ConfigRepository.isCancelled()) break;
                            int added = pullSource(c, src, true, log);
                            if (added > 0) {
                                List<ProxyConfig> fresh = new ArrayList<>();
                                addAll(fresh, seen, ConfigRepository.downloaded(c));
                                fresh = withoutPool(fresh, inPool);
                                queue.addAll(fresh);
                            }
                        }
                        ConfigRepository.stopTor(c);
                        if (nextIdx < queue.size()) continue;
                    } else {
                        log.line("Tor не поднялся — продолжаю без него");
                    }
                }
                log.line("Кандидаты закончились: проверено " + nextIdx + ", рабочих нет. "
                        + "Соберите пул заново — источники не отдают новые списки");
                return null;
            }

            round++;
            log.line("Круг " + round + ": пингую " + batch.size() + " конфигов…");
            ConfigTester.Outcome outcome = ConfigTester.test(batch, threads, pingTimeout,
                    new ConfigTester.Listener() {
                        @Override public void onProgress(int done, int total, int ok, int failed) {
                            if (done % 25 == 0 || done == total) {
                                log.line("  пинг " + done + "/" + total
                                        + " → живых " + ok + ", мёртвых " + failed);
                            }
                        }
                    });
            if (outcome.cancelled) {
                log.line("Поиск остановлен");
                return null;
            }
            log.line("Круг " + round + ": " + outcome.summary());
            if (outcome.alive.isEmpty()) continue;

            StringBuilder aliveLine = new StringBuilder("Живые: ");
            for (int i = 0; i < Math.min(SHOW_ALIVE, outcome.alive.size()); i++) {
                ProxyConfig a = outcome.alive.get(i);
                if (i > 0) aliveLine.append(" · ");
                aliveLine.append(truncate(a.name, 22)).append(' ').append(a.latency).append(" мс");
            }
            if (outcome.alive.size() > SHOW_ALIVE) {
                aliveLine.append(" · ещё ").append(outcome.alive.size() - SHOW_ALIVE);
            }
            log.line(aliveLine.toString());

            int probes = Math.min(outcome.alive.size(), PROBES_PER_ROUND);
            if (outcome.alive.size() > probes) {
                // не теряем уже пропингованное — вернёмся к нему в следующем круге
                carry.addAll(outcome.alive.subList(probes, outcome.alive.size()));
                log.line("Живых " + outcome.alive.size() + ", за круг проверяю " + probes
                        + ", остальные " + carry.size() + " — в следующем круге");
            }
            log.line("Проверяю до первого рабочего (порог " + minText + "): " + probes);
            Found hit = probeList(c, outcome.alive.subList(0, probes), log, min, minText,
                    gateTimeout, probes);
            if (hit != null) return hit;
            if (ConfigRepository.isCancelled()) {
                log.line("Поиск остановлен");
                return null;
            }
            log.line("Круг " + round + " без результата — дальше");
        }
    }

    /**
     * Лучшие конфиги из пула: он отсортирован по скорости, поэтому просто берём первые
     * limit штук, которые действительно лежат в пуле (ключи совпадают с inPool).
     */
    private static List<ProxyConfig> poolCandidates(Context c, java.util.Set<String> inPool,
                                                    int limit) {
        List<ProxyConfig> out = new ArrayList<>();
        for (Pool.Entry e : Pool.load(c)) {
            if (out.size() >= limit) break;
            String key = ConfigRepository.canonicalKey(e.raw);
            if (key == null || !inPool.contains(key)) continue;
            ProxyConfig p = e.cfg();
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Проверить конфиги подряд (до первого рабочего). Пишет результат каждой проверки. */
    private static Found probeList(Context c, List<ProxyConfig> list, Log log, int min,
                                   String minText, int gateTimeout, int total) {
        for (int i = 0; i < list.size(); i++) {
            if (ConfigRepository.isCancelled()) return null;
            ProxyConfig cfg = list.get(i);
            log.line("  проверка " + (i + 1) + "/" + list.size() + ": "
                    + truncate(cfg.name, 45) + " · " + cfg.latency + " мс");
            Probe.Result r = Probe.measureConfig(c, cfg, gateTimeout);
            if (r.ok && r.kbps >= min) {
                log.line("  → " + r.text());
                return new Found(cfg, r.kbps);
            }
            String why = r.ok
                    ? Prefs.speedText(r.kbps) + " — ниже порога " + minText
                    : r.reason;
            log.line("  → не прошёл · " + why);
        }
        return null;
    }

    /** Скачать источник и добавить новое. -1 — источник не отдался. */
    private static int pullSource(Context c, String src, boolean viaTor, Log log) {
        long t0 = System.currentTimeMillis();
        try {
            List<String> lines = ConfigRepository.fetchSource(src, viaTor);
            int added = ConfigRepository.appendNew(c, lines);
            log.line("  " + lines.size() + " строк за "
                    + Math.max(1, (System.currentTimeMillis() - t0) / 1000) + " с → +" + added
                    + " новых");
            return added;
        } catch (ConfigRepository.SourceError e) {
            if (e.missing) {
                // репозиторий удалён/переименован: это не сетевой сбой, Tor не поможет
                log.line("  пропускаю " + ConfigRepository.shortSource(src) + ": " + e.getMessage());
                return 0;
            }
            log.line("  не ответил: " + ConfigRepository.shortSource(src) + " (" + e.getMessage() + ")");
            return -1;
        } catch (Exception e) {
            log.line("  не ответил: " + ConfigRepository.shortSource(src) + " (" + e.getMessage() + ")");
            return -1;
        }
    }

    private static void addAll(List<ProxyConfig> queue, Map<String, Boolean> seen, List<ProxyConfig> list) {
        for (ProxyConfig cfg : list) {
            String key = ConfigRepository.canonicalKey(cfg.raw);
            if (seen.containsKey(key)) continue;
            seen.put(key, Boolean.TRUE);
            queue.add(cfg);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
