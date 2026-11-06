package ru.whitelist.bypass;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.List;

/** Сетевые проверки: TCP-пинг, доступность GitHub, замер скорости. */
public final class NetChecker {
    private NetChecker() {}

    /**
     * TLS-допроверка: TCP + TLS-handshake (с SNI, БЕЗ проверки сертификата — проверяем не цепочку,
     * а что порт говорит на TLS, а не отдаёт заглушку/редирект).
     * Мосты/Reality/самоподписанные — проходят; HTTP-заглушка РКН по 443 — нет.
     */
    public static long tlsPing(String host, int port, String sni, int timeoutMs) {
        long start = System.nanoTime();
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            javax.net.ssl.X509TrustManager tm = new javax.net.ssl.X509TrustManager() {
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
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
                    // SNI — best effort
                }
                socket.startHandshake();
                return (System.nanoTime() - start) / 1_000_000;
            }
        } catch (Exception e) {
            return -1;
        }
    }

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

    public static long tcpPing(String hostPort, int timeoutMs) {
        int idx = hostPort.lastIndexOf(':');
        if (idx <= 0) return -1;
        try {
            return tcpPing(hostPort.substring(0, idx), Integer.parseInt(hostPort.substring(idx + 1)), timeoutMs);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Пинг списка host:port (по очереди, быстро). */
    public static boolean anyReachable(List<String> hostPorts, int timeoutMs) {
        for (String hp : hostPorts) {
            if (tcpPing(hp, timeoutMs) >= 0) return true;
        }
        return false;
    }

    /**
     * Доступен ли GitHub: сначала githubHost:443, затем резервные IP (порт 443).
     * Возвращает true, если удалось установить TCP-соединение хотя бы к одному.
     */
    public static boolean githubReachable(android.content.Context ctx) {
        int t = 4000;
        String host = Prefs.githubHost(ctx);
        if (tcpPing(host, 443, t) >= 0) return true;
        List<String> ips = Prefs.githubFallbackIps(ctx);
        for (String ip : ips) {
            if (tcpPing(ip, 443, t) >= 0) return true;
        }
        return false;
    }

    /**
     * Замер скорости загрузки, КБ/с. Скачивает не более maxBytes.
     * При любой ошибке возвращает -1.
     */
    public static long measureSpeedKbps(String urlStr, int timeoutMs, int maxBytes) {
        long start = System.nanoTime();
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WhitelistBypass/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return -1;
            long total = 0;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while (total < maxBytes && (n = in.read(buf)) != -1) {
                    total += n;
                }
            }
            conn.disconnect();
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms <= 0) ms = 1;
            return total * 1000 / ms / 1024;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Замер скорости ЧЕРЕЗ локальный SOCKS5-прокси (канал, который реально даёт VPN).
     * Используется для UI «скорость через канал».
     */
    public static long measureSpeedThroughSocks(String urlStr, String socksHost, int socksPort,
                                                int timeoutMs, int maxBytes) {
        long start = System.nanoTime();
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(socksHost, socksPort));
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WhitelistBypass/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return -1;
            long total = 0;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while (total < maxBytes && (n = in.read(buf)) != -1) {
                    total += n;
                }
            }
            conn.disconnect();
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms <= 0) ms = 1;
            return total * 1000 / ms / 1024;
        } catch (Exception e) {
            return -1;
        }
    }

    public static final class SpeedResult {
        public final boolean ok;
        public final long ms;
        public final long bytes;
        public final int code;
        public final String body;

        SpeedResult(boolean ok, long ms, long bytes, int code) {
            this(ok, ms, bytes, code, null);
        }

        SpeedResult(boolean ok, long ms, long bytes, int code, String body) {
            this.ok = ok;
            this.ms = ms;
            this.bytes = bytes;
            this.code = code;
            this.body = body;
        }

        public static SpeedResult fail() { return new SpeedResult(false, -1, 0, -1); }
    }

    /**
     * Допроверка запуска через локальный SOCKS: GET url, чтение не более maxBytes,
     * возврат фактического времени и объёма. ok=true при ответе <400 и ms>0.
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
            conn.setRequestProperty("User-Agent", "WhitelistBypass/1.0");
            int code = conn.getResponseCode();
            if (code >= 400) {
                conn.disconnect();
                return new SpeedResult(false, elapsedMs(start), 0, code);
            }
            long total = 0;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            boolean keep = maxBytes <= 512 * 1024; // мелкие текстовые файлы держим в памяти
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while (total < maxBytes && (n = in.read(buf)) != -1) {
                    total += n;
                    if (keep) bos.write(buf, 0, n);
                }
            }
            conn.disconnect();
            long ms = elapsedMs(start);
            String body = keep && bos.size() > 0 ? bos.toString("UTF-8") : null;
            return new SpeedResult(total > 0, ms, total, code, body);
        } catch (Exception e) {
            return new SpeedResult(false, elapsedMs(start), 0, -1);
        }
    }

    private static long elapsedMs(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return ms <= 0 ? 1 : ms;
    }

    /** Быстрая проверка «жив ли канал» через локальный SOCKS5: HTTP HEAD к checkUrl. */
    public static boolean httpCheckThroughSocks(String urlStr, String socksHost, int socksPort,
                                                int timeoutMs) {
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(socksHost, socksPort));
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "WhitelistBypass/1.0");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            try {
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[256];
                in.read(buf);
                in.close();
            } catch (Exception ignored) {
                // код ответа главнее тела
            }
            conn.disconnect();
            return code >= 200 && code < 400; // 4xx/5xx — это заглушка провайдера, а не рабочий канал
        } catch (Exception e) {
            return false;
        }
    }
}
