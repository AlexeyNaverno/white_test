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
    public static final int PROBES_PER_ROUND = 40;
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

    public static Found find(Context c, Log log) {
        final int min = Prefs.minSpeedKbps(c);
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

        // --- 2) лучшие из пула: обычно одной проверки достаточно
        List<Pool.Entry> pool = Pool.load(c);
        for (int i = 0; i < Math.min(3, pool.size()); i++) {
            if (ConfigRepository.isCancelled()) return null;
            ProxyConfig cfg = pool.get(i).cfg();
            if (cfg == null) continue;
            log.line("Проверка пула " + (i + 1) + "/" + Math.min(3, pool.size())
                    + ": " + truncate(cfg.name, 45) + " (был " + Prefs.speedText(pool.get(i).kbps) + ")");
            Probe.Result r = Probe.measureConfig(c, cfg, gateTimeout);
            log.line("  → " + r.text());
            if (r.ok && r.kbps >= min) return new Found(cfg, r.kbps);
        }

        // --- 3) круги по спискам с ленивой подгрузкой источников
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<ProxyConfig> queue = new ArrayList<>();
        addAll(queue, seen, ConfigRepository.downloaded(c));
        addAll(queue, seen, ConfigRepository.embedded(c));
        log.line("Кандидатов в хранилище: " + queue.size());

        List<String> sources = Prefs.sources(c);
        List<String> pending = new ArrayList<>(sources);
        List<String> failed = new ArrayList<>();
        int nextIdx = 0;
        int round = 0;
        boolean torTried = false;
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
                Found hit = probeList(c, rest, log, min, gateTimeout, rest.size());
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
                        queue.addAll(fresh);   // seen уже отфильтровал дубли
                        log.line("  новых конфигов: " + added + " (всего в хранилище "
                                + ConfigRepository.countDownloaded(c) + ")");
                    }
                    continue;
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
                                queue.addAll(fresh);
                            }
                        }
                        ConfigRepository.stopTor(c);
                        if (nextIdx < queue.size()) continue;
                    } else {
                        log.line("Tor не поднялся — продолжаю без него");
                    }
                }
                log.line("Кандидаты закончились: проверено " + nextIdx + ", рабочих нет");
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
            log.line("Проверяю до первого рабочего (порог " + Prefs.speedText(min) + "): " + probes);
            Found hit = probeList(c, outcome.alive.subList(0, probes), log, min, gateTimeout, probes);
            if (hit != null) return hit;
            if (ConfigRepository.isCancelled()) {
                log.line("Поиск остановлен");
                return null;
            }
            log.line("Круг " + round + " без результата — дальше");
        }
    }

    /** Проверить конфиги подряд (до первого рабочего). Пишет результат каждой проверки. */
    private static Found probeList(Context c, List<ProxyConfig> list, Log log, int min,
                                   int gateTimeout, int total) {
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
                    ? Prefs.speedText(r.kbps) + " — ниже порога " + Prefs.speedText(min)
                    : r.reason;
            log.line("  → не прошёл · " + why);
        }
        return null;
    }

    /** Скачать источник и добавить новое. -1 — источник не отдался. */
    private static int pullSource(Context c, String src, boolean viaTor, Log log) {
        try {
            List<String> lines = ConfigRepository.fetchSource(src, viaTor);
            int added = ConfigRepository.appendNew(c, lines);
            return added;
        } catch (Exception e) {
            log.line("  не ответил: " + ConfigRepository.shortSource(src)
                    + " (" + e.getMessage() + ")");
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
