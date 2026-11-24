package ru.whitelist.bypass;

import android.content.Context;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Замер пачки конфигов в ОДНОМ ядре — вторая ступень после быстрого TCP-отсева.
 *
 * Как это работает. {@link XrayEngine#buildBatchConfigJson} собирает одну конфигурацию:
 * на каждый конфиг пачки свой SOCKS-inbound (`in-N`, порт basePort+N), свой outbound
 * (`out-N`) и правило «in-N → out-N». Ядро поднимается один раз на всю пачку, дальше —
 * обычные HTTP-запросы по локальным портам в {@code threads} потоков.
 *
 * Почему это в разы быстрее прежнего «ядро на конфиг»:
 *   • старт ядра — самое дорогое в замере (сотни миллисекунд и десятки мегабайт), а
 *     платим мы за него ОДИН раз на сто конфигов, а не сто раз;
 *   • потолок одновременных проверок — 100 (как у Throne), а не 6 дорожек;
 *   • проба дешёвая: 64 КБ вместо 512 КБ. Этого хватает, чтобы отличить «данные идут» от
 *     «ответ есть, тела нет», а полный замер делается только для финалистов.
 *
 * Время замера и «сколько скачали» остаются честными: скорость считается по фактически
 * прочитанным байтам, а не по обещанию сервера. С 0.1.20 мерится HTML-страница
 * поисковика (см. {@link Regions#SPEED_HTML}) — популярный сайт без проверки на ботов,
 * у которого тело есть всегда; а в рабочие попадает только конфиг, через который файл
 * реально скачался (минимум {@link Probe#MIN_SAMPLE_BYTES}).
 */
public final class BatchProbe {

    private BatchProbe() {}

    /** Сколько читаем в пробе. Три «пакета» — достаточно, чтобы увидеть, что данные идут. */
    public static final int SAMPLE_BYTES = 64 * 1024;
    /** Больше сотни одновременных проб не имеет смысла: упираемся в сеть и в TLS. */
    public static final int MAX_BATCH = 100;

    public interface Log {
        void line(String message);
    }

    /** Вердикт по одному конфигу пачки. */
    public static final class Result {
        public final ProxyConfig cfg;
        /** Данные шли: конфиг рабочий. */
        public final boolean ok;
        /** КБ/с по прочитанным байтам; -1 — данных не хватило для честной оценки. */
        public final long kbps;
        /** Время до ответа, мс; -1 — ответа не было. */
        public final long ms;
        public final long bytes;
        /** Связность была, а данных нет (204/пустое тело) — это приговор, а не «не успели». */
        public final boolean hard;
        /** Причина для журнала. */
        public final String reason;

        Result(ProxyConfig cfg, boolean ok, long kbps, long ms, long bytes,
               boolean hard, String reason) {
            this.cfg = cfg;
            this.ok = ok;
            this.kbps = kbps;
            this.ms = ms;
            this.bytes = bytes;
            this.hard = hard;
            this.reason = reason == null ? "" : reason;
        }

        public String text() {
            if (ok) {
                return (kbps > 0 ? Prefs.speedText(kbps) : "данные идут")
                        + " · " + ms + " мс";
            }
            return reason.isEmpty() ? "не поднялся" : reason;
        }
    }

    /** Итог партии: результаты в порядке конфигов + сколько стоило. */
    public static final class Outcome {
        public final List<Result> results;
        public final int readyPorts;
        public final long ms;
        public final boolean cancelled;

        Outcome(List<Result> results, int readyPorts, long ms, boolean cancelled) {
            this.results = results;
            this.readyPorts = readyPorts;
            this.ms = ms;
            this.cancelled = cancelled;
        }

        public int okCount() {
            int n = 0;
            for (Result r : results) if (r.ok) n++;
            return n;
        }

        /** «партия 100: поднялось 12, ядро на 96 портах за 4.1 с». */
        public String summary() {
            return "партия " + results.size() + ": рабочих " + okCount()
                    + ", портов открылось " + readyPorts + " за " + seconds();
        }

        private String seconds() {
            if (ms < 1000) return ms + " мс";
            return String.format(java.util.Locale.US, "%.1f с", ms / 1000.0);
        }
    }

    /**
     * Проверить партию. Возвращает результат для КАЖДОГО конфига (в том же порядке):
     * даже если порт не открылся, у конфига есть причина, а не пустота.
     */
    public static Outcome run(Context c, List<ProxyConfig> cfgs, Log log) {
        long started = System.currentTimeMillis();
        List<ProxyConfig> batch = new ArrayList<>();
        if (cfgs != null) {
            for (ProxyConfig cfg : cfgs) {
                if (cfg == null) continue;
                batch.add(cfg);
                if (batch.size() >= MAX_BATCH) break;
            }
        }
        List<Result> results = new ArrayList<>();
        if (batch.isEmpty()) return new Outcome(results, 0, 0, false);

        // Конфиги без TLS с ретранслятором в батч не встают: у них своя линия на
        // dokodemo-door. Вызывающий их отфильтровывает (Probe.usable), но страхуемся.
        List<ProxyConfig> work = new ArrayList<>();
        for (ProxyConfig cfg : batch) {
            if (XrayEngine.needsRelay(cfg)) {
                results.add(new Result(cfg, false, -1, -1, 0, false,
                        "нужен ретранслятор — проверяется отдельно"));
            } else {
                work.add(cfg);
            }
        }
        if (work.isEmpty()) {
            return new Outcome(results, 0, System.currentTimeMillis() - started, false);
        }

        int basePort = freeRange(work.size());
        if (basePort <= 0) {
            for (ProxyConfig cfg : work) {
                results.add(new Result(cfg, false, -1, -1, 0, false, "нет свободных портов"));
            }
            return new Outcome(results, 0, System.currentTimeMillis() - started, false);
        }

        String json;
        try {
            json = XrayEngine.buildBatchConfigJson(work, basePort,
                    Prefs.allowNoTls(c), Prefs.vpnDns(c));
        } catch (Throwable t) {
            for (ProxyConfig cfg : work) {
                results.add(new Result(cfg, false, -1, -1, 0, false,
                        "партия не собралась: " + t.getMessage()));
            }
            return new Outcome(results, 0, System.currentTimeMillis() - started, false);
        }

        libv2ray.CoreController controller = null;
        List<Result> workResults = new ArrayList<>();
        int openedPorts = 0;
        try {
            controller = XrayEngine.startInstance(c, json);
            // Ждём порты все сразу: у одной партии общий срок, а не срок на конфиг.
            long waitMs = Probe.SOCKS_WAIT_MS + Math.min(4000L, work.size() * 20L);
            int ready = waitPorts(basePort, work.size(), waitMs);
            openedPorts = ready;
            if (log != null) {
                log.line("  ядро открыло " + ready + " из " + work.size() + " портов");
            }
            if (ready == 0) {
                for (ProxyConfig cfg : work) {
                    results.add(new Result(cfg, false, -1, -1, 0, false,
                            "ядро не открыло SOCKS"));
                }
                return new Outcome(results, 0, System.currentTimeMillis() - started,
                        ConfigRepository.isCancelled());
            }

            final int threads = Math.min(Prefs.batchThreads(c), work.size());
            final List<String> urls = Prefs.speedUrls(c);
            final int timeout = Prefs.gateTimeoutMs(c);
            final Result[] slot = new Result[work.size()];
            final AtomicInteger cursor = new AtomicInteger();
            final AtomicInteger done = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
            for (int i = 0; i < threads; i++) {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        for (;;) {
                            if (ConfigRepository.isCancelled()) return;
                            int idx = cursor.getAndIncrement();
                            if (idx >= slot.length) return;
                            slot[idx] = measureOne(urls, "127.0.0.1", basePort + idx,
                                    timeout, work.get(idx));
                            int n = done.incrementAndGet();
                            if (log != null && (n % 25 == 0 || n == slot.length)) {
                                int ok = 0;
                                for (Result r : slot) if (r != null && r.ok) ok++;
                                log.line("  замер " + n + "/" + slot.length + " → рабочих " + ok);
                            }
                        }
                    }
                });
            }
            pool.shutdown();
            try {
                // Срок с запасом: раундов × окно замера + холодный старт ядра.
                long hard = (long) Math.ceil(work.size() / (double) Math.max(1, threads))
                        * (timeout + 4000L);
                pool.awaitTermination(hard + 10_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < slot.length; i++) {
                Result r = slot[i];
                if (r == null) {
                    r = new Result(work.get(i), false, -1, -1, 0, false, "не успели проверить");
                }
                workResults.add(r);
            }
        } catch (Throwable t) {
            for (ProxyConfig cfg : work) {
                results.add(new Result(cfg, false, -1, -1, 0, false,
                        "ядро партии упало: " + t.getMessage()));
            }
            return new Outcome(results, 0, System.currentTimeMillis() - started,
                    ConfigRepository.isCancelled());
        } finally {
            XrayEngine.stopInstance(controller);
        }

        // Порядок результатов — порядок переданных конфигов.
        results.addAll(workResults);
        List<Result> ordered = new ArrayList<>();
        for (ProxyConfig cfg : batch) {
            for (Result r : results) {
                if (r.cfg == cfg) {
                    ordered.add(r);
                    break;
                }
            }
        }
        if (ordered.size() != batch.size()) {
            // На всякий случай: у каждого конфига должен быть вердикт.
            for (ProxyConfig cfg : batch) {
                boolean found = false;
                for (Result r : ordered) if (r.cfg == cfg) { found = true; break; }
                if (!found) {
                    ordered.add(new Result(cfg, false, -1, -1, 0, false, "нет вердикта"));
                }
            }
        }
        return new Outcome(ordered, openedPorts, System.currentTimeMillis() - started,
                ConfigRepository.isCancelled());
    }

    /**
     * Одна проба: первый URL из настроек, при отказе — следующий (их обычно один: правило
     * «одна ссылка под выбранный регион»).
     */
    private static Result measureOne(List<String> urls, String socksHost, int port,
                                     int timeoutMs, ProxyConfig cfg) {
        String lastError = "";
        boolean emptyAnswer = false;
        boolean gotSome = false;
        long bestMs = -1;
        long bestBytes = 0;
        for (int i = 0; i < urls.size() && i < 3; i++) {
            String url = urls.get(i);
            if (url == null || url.trim().isEmpty()) continue;
            NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(
                    url, socksHost, port, timeoutMs, SAMPLE_BYTES);
            if (!r.error.isEmpty()) lastError = r.error;
            if (r.ok && r.bytes > 0) {
                long kbps = r.kbps();     // -1, если байтов меньше Probe.MIN_SAMPLE_BYTES
                if (r.bytes > bestBytes) {
                    bestBytes = r.bytes;
                    bestMs = r.ms;
                }
                if (kbps > 0) {
                    // Файл реально скачался, скорость честная — только такие конфиги
                    // годятся в рабочие (0.1.20).
                    return new Result(cfg, true, kbps, r.ms, r.bytes, false, "");
                }
                // Данные были, но для честного замера их мало: пробуем следующую ссылку,
                // а если и она не дала объёма — конфиг получит вердикт «мало данных»
                // и вернётся в следующем прогоне («нерешённые»), а не в чёрный список.
                gotSome = true;
                continue;
            }
            // Ответ без тела (204) — связность есть, данных нет: жёсткий отказ.
            if (r.code >= 200 && r.code < 400 && r.bytes == 0) emptyAnswer = true;
        }
        if (gotSome) {
            return new Result(cfg, false, -1, bestMs, bestBytes, false,
                    "скачано " + Math.max(1, bestBytes / 1024) + " КБ — мало для замера");
        }
        if (emptyAnswer) {
            return new Result(cfg, false, -1, bestMs, bestBytes, true,
                    "связность есть, скорость не измерена");
        }
        if (lastError.isEmpty()) lastError = "нет данных";
        return new Result(cfg, false, -1, bestMs, bestBytes, false, lastError);
    }

    /** Ждём, пока откроется SOCKS на всех портах диапазона; возвращаем сколько открылось. */
    private static int waitPorts(int basePort, int count, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean[] ready = new boolean[count];
        int opened = 0;
        while (System.currentTimeMillis() < deadline) {
            if (ConfigRepository.isCancelled()) break;
            boolean all = true;
            for (int i = 0; i < count; i++) {
                if (ready[i]) continue;
                if (socksAlive(basePort + i)) {
                    ready[i] = true;
                    opened++;
                } else {
                    all = false;
                }
            }
            if (all) break;
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                return opened;
            }
        }
        return opened;
    }

    /** SOCKS5-приветствие: «порт открыт» и «SOCKS работает» — разные вещи. */
    private static boolean socksAlive(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            socket.setSoTimeout(400);
            socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
            java.io.InputStream in = socket.getInputStream();
            int a = in.read();
            int b = in.read();
            return a == 5 && b == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Свободный ДИАПАЗОН портов: ядру нужно n подряд идущих. Просто «свободный порт» не
     * годится — соседние могут быть заняты, и тогда часть батча не поднимется.
     */
    private static int freeRange(int count) {
        for (int attempt = 0; attempt < 40; attempt++) {
            int base = Probe.freePort();
            if (base <= 0 || base + count >= 65000) continue;
            boolean ok = true;
            List<ServerSocket> holders = new ArrayList<>();
            try {
                for (int i = 0; i < count; i++) {
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", base + i));
                    holders.add(ss);
                }
            } catch (IOException e) {
                ok = false;
            } finally {
                for (ServerSocket ss : holders) {
                    try {
                        ss.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (ok) return base;
        }
        return 0;
    }
}
