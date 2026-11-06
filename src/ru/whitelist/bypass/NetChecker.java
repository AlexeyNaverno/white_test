package ru.whitelist.bypass;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;

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

    /** Результат замера: объём + время. */
    public static final class SpeedResult {
        public final boolean ok;
        public final long ms;
        public final long bytes;
        public final int code;

        SpeedResult(boolean ok, long ms, long bytes, int code) {
            this.ok = ok;
            this.ms = ms;
            this.bytes = bytes;
            this.code = code;
        }

        /** КБ/с. -1, если данных слишком мало для честной оценки. */
        public long kbps() {
            if (bytes < Probe.MIN_SAMPLE_BYTES) return -1;
            long t = ms <= 0 ? 1 : ms;
            return bytes * 1000 / t / 1024;
        }
    }

    /**
     * Скачать ДО maxBytes через локальный SOCKS и вернуть фактическое время и объём.
     * Это и есть замер скорости канала.
     */
    public static SpeedResult downloadThroughSocks(String urlStr, String socksHost, int socksPort,
                                                   int timeoutMs, long maxBytes) {
        long start = System.nanoTime();
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(socksHost, socksPort));
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WhitelistBypass/0.0.1");
            int code = conn.getResponseCode();
            if (code >= 400) {
                conn.disconnect();
                return new SpeedResult(false, elapsed(start), 0, code);
            }
            long total = 0;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while (total < maxBytes && (n = in.read(buf)) != -1) {
                    total += n;
                }
            }
            conn.disconnect();
            return new SpeedResult(total > 0, elapsed(start), total, code);
        } catch (Exception e) {
            return new SpeedResult(false, elapsed(start), 0, -1);
        }
    }

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
            conn.setRequestProperty("User-Agent", "WhitelistBypass/0.0.1");
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

    private static long elapsed(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return ms <= 0 ? 1 : ms;
    }
}
