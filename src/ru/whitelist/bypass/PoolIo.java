package ru.whitelist.bypass;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Импорт и экспорт рабочих конфигов в обычный .txt.
 *
 * Импорт не доверяет файлу: строки дедуплицируются (и между собой, и с тем, что уже в пуле),
 * затем пингуются, а потом у живых замеряется скорость — в пул попадают только прошедшие
 * порог. Так файл с чужими конфигами не засоряет пул мёртвыми строками.
 */
public final class PoolIo {
    private PoolIo() {}

    public interface Log {
        void line(String message);
    }

    public static final class Result {
        public int inFile;
        public int duplicates;
        public int unsupported;
        public int alive;
        public int added;
        public boolean cancelled;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("в файле ").append(inFile);
            if (duplicates > 0) sb.append(", дублей ").append(duplicates);
            if (unsupported > 0) sb.append(", несовместимых ").append(unsupported);
            sb.append(", прошли пинг ").append(alive);
            sb.append(", добавлено в пул ").append(added);
            if (cancelled) sb.append(" (остановлено)");
            return sb.toString();
        }
    }

    /** Текст для экспорта: по одному конфигу на строку, по убыванию скорости. */
    public static String exportText(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Обход Белых Списков: рабочие конфиги (")
                .append(Pool.count(c)).append(" шт.)\n");
        sb.append("# Формат: одна ссылка на строку. Импорт: Настройки → Пул → Импорт .txt\n");
        for (Pool.Entry e : Pool.load(c)) {
            sb.append(e.raw).append('\n');
        }
        return sb.toString();
    }

    /** Импорт целиком: дедупликация → пинг → скорость → только рабочие в пул. */
    public static Result importText(Context c, String text, Log log) {
        final Result res = new Result();
        java.util.Set<String> inPool = Pool.keySet(c);
        Map<String, ProxyConfig> unique = new LinkedHashMap<>();
        for (String line : (text == null ? "" : text).split("\\r?\\n")) {
            if (ConfigRepository.isCancelled() || res.cancelled) break;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;
            ProxyConfig p = ProxyConfig.parse(t, "импорт", false);
            if (p == null || p.host == null || p.host.isEmpty() || p.port <= 0) continue;
            res.inFile++;
            String bad = p.supported
                    ? XrayEngine.compatProblem(p, Prefs.allowNoTls(c))
                    : p.unsupportedReason;
            if (bad != null) {
                res.unsupported++;
                continue;
            }
            String key = ConfigRepository.canonicalKey(p.raw);
            if (inPool.contains(key) || unique.containsKey(key)) {
                res.duplicates++;
                continue;
            }
            unique.put(key, p);
        }
        say(log, "Файл: " + res.inFile + " строк, дублей " + res.duplicates
                + ", несовместимых " + res.unsupported + ", к проверке " + unique.size());
        if (unique.isEmpty()) {
            say(log, "Проверять нечего");
            return res;
        }

        // --- 1) пинг: отсекает мёртвое до запуска ядра
        List<ProxyConfig> candidates = new ArrayList<>(unique.values());
        say(log, "Пингую " + candidates.size() + " конфигов…");
        ConfigTester.Outcome ping = ConfigTester.test(candidates,
                Prefs.pingThreads(c), Prefs.pingTimeoutMs(c), new ConfigTester.Listener() {
                    @Override public void onProgress(int done, int total, int ok, int failed) {
                        if (done % 50 == 0 || done == total) {
                            say(log, "  пинг " + done + "/" + total + " → живых " + ok);
                        }
                    }
                });
        if (ping.cancelled) {
            res.cancelled = true;
            say(log, "Импорт остановлен на пинге");
            return res;
        }
        res.alive = ping.alive.size();
        say(log, "Пинг: " + ping.summary());
        if (ping.alive.isEmpty()) return res;

        // --- 2) скорость: параллельно, порог как в «Собрать пул»
        final int min = Prefs.minSpeedKbps(c);
        final int lanes = Prefs.speedLanes(c);
        final int gate = Prefs.gateTimeoutMs(c);
        final List<Pool.Entry> passed =
                java.util.Collections.synchronizedList(new ArrayList<Pool.Entry>());
        final AtomicInteger cursor = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final int total = ping.alive.size();
        say(log, "Меряю скорость: " + total + " конфигов, " + lanes + " ядра, порог "
                + Prefs.minSpeedText(c));
        ExecutorService pool = Executors.newFixedThreadPool(lanes);
        for (int i = 0; i < lanes; i++) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    for (;;) {
                        if (ConfigRepository.isCancelled()) break;
                        int idx = cursor.getAndIncrement();
                        if (idx >= total) break;
                        ProxyConfig cfg = ping.alive.get(idx);
                        Probe.Result r = Probe.measureConfig(c, cfg, gate);
                        if (r.ok && r.kbps >= min) {
                            passed.add(new Pool.Entry(cfg.raw, r.kbps, cfg.latency,
                                    System.currentTimeMillis()));
                        }
                        int d = done.incrementAndGet();
                        if (d % 5 == 0 || d == total) {
                            say(log, "  скорость " + d + "/" + total + " · рабочих "
                                    + passed.size());
                        }
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(2, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            res.cancelled = true;
        }

        if (passed.isEmpty() && !ping.alive.isEmpty() && !ConfigRepository.isCancelled()) {
            say(log, "Параллельный замер пуст — проверяю первые 20 по одному");
            List<Pool.Entry> again = Probe.measureSequential(c, ping.alive, 20);
            say(log, "По одному: рабочих " + again.size());
            synchronized (passed) {
                passed.addAll(again);
            }
        }

        // --- 3) в пул: старое + новое, без дублей
        List<Pool.Entry> merged = new ArrayList<>(Pool.load(c));
        java.util.Set<String> mergedKeys = new java.util.HashSet<>();
        for (Pool.Entry e : merged) mergedKeys.add(ConfigRepository.canonicalKey(e.raw));
        synchronized (passed) {
            for (Pool.Entry e : passed) {
                String key = ConfigRepository.canonicalKey(e.raw);
                if (mergedKeys.add(key)) {
                    merged.add(e);
                    res.added++;
                }
            }
        }
        Pool.save(c, merged);
        say(log, "В пул добавлено " + res.added + ", всего в пуле " + Pool.count(c)
                + (Pool.bestKbps(c) > 0 ? ", лучший " + Prefs.speedText(Pool.bestKbps(c)) : ""));
        return res;
    }

    private static void say(Log log, String message) {
        if (log != null) log.line(message);
    }
}
