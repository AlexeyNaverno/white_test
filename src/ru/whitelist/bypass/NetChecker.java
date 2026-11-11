package ru.whitelist.bypass;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Сетевые примитивы: пинг (TCP / TLS-handshake), замер скорости через локальный SOCKS,
 * быстрый HTTP-отклик для монитора канала.
 */
public final class NetChecker {
    private NetChecker() {}

    /** TCP connect-пинг; -1 при неудаче. */
    public static long tcpPing(String host, int port, int timeoutMs) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return (System.nanoTime() - start) / 1_000_000;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * TCP + TLS-handshake без проверки сертификата: отсекает заглушки провайдера,
     * но пропускает Reality/самоподписанные серверы.
     */
    public static long tlsPing(String host, int port, String sni, int timeoutMs) {
        long start = System.nanoTime();
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            javax.net.ssl.X509TrustManager tm = new javax.net.ssl.X509TrustManager() {
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
            };
            ctx.init(null, new javax.net.ssl.TrustManager[]{tm}, new java.security.SecureRandom());
            javax.net.ssl.SSLSocketFactory factory = ctx.getSocketFactory();
            try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) factory.createSocket()) {
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                socket.setSoTimeout(timeoutMs);
                String name = (sni != null && !sni.isEmpty()) ? sni : host;
                try {
                    javax.net.ssl.SSLParameters params = socket.getSSLParameters();
                    params.setServerNames(java.util.Collections.singletonList(
                            new javax.net.ssl.SNIHostName(name)));
                    socket.setSSLParameters(params);
                } catch (Exception ignored) {
                    // старые прошивки могут не знать SNI-параметр — не критично
                }
                socket.startHandshake();
                return (System.nanoTime() - start) / 1_000_000;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** Результат замера: объём + время + чем именно закончилось (для лога). */
    public static final class SpeedResult {
        public final boolean ok;
        public final long ms;
        public final long bytes;
        public final int code;
        /** Почему не получилось: «таймаут ответа», «SOCKS: connection refused» и т.п. */
        public final String error;

        SpeedResult(boolean ok, long ms, long bytes, int code) {
            this(ok, ms, bytes, code, null);
        }

        SpeedResult(boolean ok, long ms, long bytes, int code, String error) {
            this.ok = ok;
            this.ms = ms;
            this.bytes = bytes;
            this.code = code;
            this.error = error == null ? "" : error;
        }

        /** КБ/с. -1, если данных слишком мало для честной оценки. */
        public long kbps() {
            if (bytes < Probe.MIN_SAMPLE_BYTES) return -1;
            long t = ms <= 0 ? 1 : ms;
            // округление, а не отбрасывание: при пороге 128 кбит/с (16 КБ/с) единица решает
            return Math.round(bytes * 1000.0 / t / 1024.0);
        }

        /** Короткая причина для лога: «нет данных: таймаут ответа» и т.п. */
        public String reason() {
            if (ok && kbps() > 0) return "";
            if (bytes > 0) return "мало данных (" + (bytes / 1024) + " КБ за " + ms + " мс)";
            return error.isEmpty() ? "нет данных" : error;
        }
    }

    /**
     * Скачать ДО maxBytes через локальный SOCKS и вернуть фактическое время и объём.
     * Это и есть замер скорости канала.
     *
     * ВАЖНО (почему раньше «ничего не проходило»): время считалось от старта запроса,
     * то есть в него входили CONNECT через SOCKS, TLS-hello и ответ сервера. При лимите
     * замера 1500 мс на всё это уходила почти вся минута ожидания — и рабочий конфиг
     * получал «нет данных», хотя качал нормально. Теперь окно замера — от ПЕРВОГО
     * полученного байта, а на установку соединения даётся отдельный запас.
     */
    public static SpeedResult downloadThroughSocks(String urlStr, String socksHost, int socksPort,
                                                   int timeoutMs, long maxBytes) {
        long start = System.nanoTime();
        long firstByte = -1;
        HttpURLConnection conn = null;
        int code = -1;
        long total = 0;
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(socksHost, socksPort));
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WhitelistBypass/0.1.11");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Cache-Control", "no-cache");
            code = conn.getResponseCode();
            if (code >= 400) {
                return new SpeedResult(false, elapsed(start), 0, code, "HTTP " + code);
            }
            // Соединение + TLS + первый байт получают отдельный запас поверх окна замера.
            long budgetEnd = System.currentTimeMillis() + timeoutMs + HTTP_OPEN_GRACE_MS;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while (total < maxBytes) {
                    if (ConfigRepository.isCancelled()) break;
                    if (System.currentTimeMillis() > budgetEnd) break;
                    n = in.read(buf);
                    if (n < 0) break;
                    if (firstByte < 0) firstByte = System.nanoTime();
                    total += n;
                }
            }
            long ms = firstByte < 0
                    ? elapsed(start)
                    : Math.max(1L, (System.nanoTime() - firstByte) / 1_000_000L);
            return new SpeedResult(total > 0, ms, total, code,
                    total > 0 ? "" : "сервер не отдал данные");
        } catch (java.net.SocketTimeoutException e) {
            return new SpeedResult(false, elapsed(start), total, code,
                    total > 0 ? "оборвалось на " + (total / 1024) + " КБ" : "таймаут ответа");
        } catch (java.net.ConnectException e) {
            return new SpeedResult(false, elapsed(start), total, code, "SOCKS не соединил");
        } catch (java.net.UnknownHostException e) {
            return new SpeedResult(false, elapsed(start), total, code, "DNS не разрешился");
        } catch (javax.net.ssl.SSLException e) {
            return new SpeedResult(false, elapsed(start), total, code, "TLS не прошёл");
        } catch (Exception e) {
            String m = e.getMessage();
            return new SpeedResult(false, elapsed(start), total, code,
                    m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Запас на CONNECT/TLS/первый байт — он не должен отнимать окно замера. */
    private static final long HTTP_OPEN_GRACE_MS = 2500;

    /** Быстрый отклик канала: HTTP 2xx/3xx считается «жив». */
    public static boolean httpCheckThroughSocks(String urlStr, String socksHost, int socksPort,
                                                int timeoutMs) {
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(socksHost, socksPort));
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WhitelistBypass/0.1.11");
            int code = conn.getResponseCode();
            try {
                InputStream in = conn.getInputStream();
                in.read(new byte[256]);
                in.close();
            } catch (Exception ignored) {
                // важен код ответа, а не тело
            }
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }


    // ------------------------------------------------------------------ наборы целей

    /** "host:port" / "https://host/path" → {host, port}; порт по умолчанию 443. */
    public static String[] splitTarget(String spec) {
        if (spec == null) return null;
        String t = spec.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("http://")) t = t.substring(7);
        else if (t.startsWith("https://")) t = t.substring(8);
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        int colon = t.lastIndexOf(':');
        String host = colon > 0 ? t.substring(0, colon) : t;
        int port = 443;
        if (colon > 0) {
            try {
                port = Integer.parseInt(t.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        host = host.trim();
        if (host.isEmpty() || port <= 0 || port > 65535) return null;
        return new String[]{host, String.valueOf(port)};
    }

    /** TCP-пинг одной цели вида "host:port". -1 — не ответила. */
    public static long pingTarget(String spec, int timeoutMs) {
        String[] hp = splitTarget(spec);
        if (hp == null) return -1;
        try {
            return tcpPing(hp[0], Integer.parseInt(hp[1]), timeoutMs);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Итог параллельного пинга набора целей. */
    public static final class PingStats {
        public final int ok;
        public final int total;
        public final long bestMs;
        public final long medianMs;
        public final List<String> alive = new ArrayList<>();

        PingStats(int ok, int total, long bestMs, long medianMs) {
            this.ok = ok;
            this.total = total;
            this.bestMs = bestMs;
            this.medianMs = medianMs;
        }

        /** Короткая строка для лога. */
        public String text() {
            if (total == 0) return "целей нет";
            if (ok == 0) return "0/" + total + " целей (сеть не отвечает)";
            return ok + "/" + total + " целей, лучший " + bestMs + " мс, медиана " + medianMs + " мс";
        }
    }

    /**
     * Пинговать все цели ПАРАЛЛЕЛЬНО и вернуть статистику.
     * Так «медленный, но живой» канал (пинг до 1000 мс) не отбрасывается.
     */
    public static PingStats pingAll(List<String> targets, int timeoutMs) {
        List<String> list = new ArrayList<>();
        if (targets != null) {
            for (String t : targets) {
                if (splitTarget(t) != null) list.add(t);
            }
        }
        final long[] lat = new long[list.size()];
        for (int i = 0; i < lat.length; i++) lat[i] = -1;
        if (!list.isEmpty()) {
            int threads = Math.max(1, Math.min(8, list.size()));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                futures.add(pool.submit(new Callable<Object>() {
                    @Override public Object call() {
                        lat[idx] = pingTarget(list.get(idx), timeoutMs);
                        return null;
                    }
                }));
            }
            pool.shutdown();
            long deadline = System.currentTimeMillis() + timeoutMs + 1500L;
            for (Future<?> f : futures) {
                long left = deadline - System.currentTimeMillis();
                try {
                    f.get(Math.max(1L, left), TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // не дождались — считаем цель мёртвой
                }
            }
        }
        List<Long> okList = new ArrayList<>();
        for (long l : lat) {
            if (l >= 0) okList.add(l);
        }
        Collections.sort(okList);
        long best = okList.isEmpty() ? -1 : okList.get(0);
        long median = okList.isEmpty() ? -1 : okList.get(okList.size() / 2);
        PingStats stats = new PingStats(okList.size(), lat.length, best, median);
        for (int i = 0; i < lat.length; i++) {
            if (lat[i] >= 0) stats.alive.add(list.get(i) + " " + lat[i] + " мс");
        }
        return stats;
    }

    /**
     * Проверка канала ЧЕРЕЗ туннель: сколько из URL ответили 2xx/3xx.
     * Возвращает первый ответивший URL (пустая строка — канал мёртв).
     */
    public static String aliveThroughSocks(List<String> urls, String socksHost, int socksPort,
                                           int timeoutMs) {
        if (urls == null || urls.isEmpty()) return "";
        List<String> trimmed = new ArrayList<>(urls);
        if (trimmed.size() > 5) trimmed = new ArrayList<>(trimmed.subList(0, 5));
        final List<String> list = trimmed;
        final String[] answered = new String[list.size()];
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(5, list.size())));
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            final String url = list.get(i);
            futures.add(pool.submit(new Callable<Object>() {
                @Override public Object call() {
                    if (httpCheckThroughSocks(url, socksHost, socksPort, timeoutMs)) {
                        answered[idx] = url;
                    }
                    return null;
                }
            }));
        }
        pool.shutdown();
        long deadline = System.currentTimeMillis() + timeoutMs + 1500L;
        for (Future<?> f : futures) {
            long left = deadline - System.currentTimeMillis();
            try {
                f.get(Math.max(1L, left), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // не ответил — не считается
            }
        }
        for (String a : answered) {
            if (a != null && !a.isEmpty()) return a;
        }
        return "";
    }

    /** Сколько из URL ответили через туннель (параллельно). */
    public static int countAliveThroughSocks(List<String> urls, String socksHost, int socksPort,
                                             int timeoutMs) {
        if (urls == null || urls.isEmpty()) return 0;
        List<String> list = new ArrayList<>(urls);
        if (list.size() > 5) list = new ArrayList<>(list.subList(0, 5));
        final boolean[] okFlags = new boolean[list.size()];
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(5, list.size())));
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            final String url = list.get(i);
            futures.add(pool.submit(new Callable<Object>() {
                @Override public Object call() {
                    okFlags[idx] = httpCheckThroughSocks(url, socksHost, socksPort, timeoutMs);
                    return null;
                }
            }));
        }
        pool.shutdown();
        long deadline = System.currentTimeMillis() + timeoutMs + 1500L;
        for (Future<?> f : futures) {
            long left = deadline - System.currentTimeMillis();
            try {
                f.get(Math.max(1L, left), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // не ответил
            }
        }
        int n = 0;
        for (boolean ok : okFlags) {
            if (ok) n++;
        }
        return n;
    }

    private static long elapsed(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return ms <= 0 ? 1 : ms;
    }
}
