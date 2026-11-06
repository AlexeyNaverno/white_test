package ru.whitelist.bypass;

import android.content.Context;

import libv2ray.CoreController;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Разовая проверка конфига: поднять Xray на свободном порту → дождаться SOCKS →
 * скачать файл и посчитать КБ/с. Используется поиском и замером пула.
 *
 * Подключение туннеля (BypassVpnService) меряет так же — через {@link #speedKbps}.
 */
public final class Probe {
    private Probe() {}

    /** Меньше этого объёма — оценка скорости недостоверна. */
    public static final int MIN_SAMPLE_BYTES = 64 * 1024;
    /** Сколько качаем за один замер (хватает, чтобы оценить и быстрый, и медленный канал). */
    public static final long MAX_SAMPLE_BYTES = 512 * 1024;
    /** Сколько ждём, пока Xray откроет локальный SOCKS. */
    public static final long SOCKS_WAIT_MS = 6000;

    public static final class Result {
        public final boolean ok;
        public final long kbps;
        public final long socksPort;

        public Result(boolean ok, long kbps, long socksPort) {
            this.ok = ok;
            this.kbps = kbps;
            this.socksPort = socksPort;
        }

        static Result fail() { return new Result(false, -1, -1); }
    }

    /** Свободный локальный порт (0 — если не нашлось). */
    public static int freePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ждём, пока на порту ответит SOCKS5 (не просто «порт открыт»). */
    public static boolean waitSocks(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ConfigRepository.isCancelled()) return false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                socket.setSoTimeout(500);
                socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
                InputStream in = socket.getInputStream();
                int a = in.read();
                int b = in.read();
                if (a == 5 && b == 0) return true;
            } catch (Exception ignored) {
                // ещё не готов
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Скорость канала через уже поднятый SOCKS, КБ/с.
     * -1 — ни один файл не отдался (конфиг не рабочий или канал мёртв).
     */
    public static long speedKbps(Context ctx, int socksPort, int timeoutMs) {
        long best = -1;
        for (String url : Prefs.speedUrls(ctx)) {
            if (ConfigRepository.isCancelled()) break;
            NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(
                    url, "127.0.0.1", socksPort, timeoutMs, MAX_SAMPLE_BYTES);
            long kbps = r.kbps();
            if (kbps > best) best = kbps;
            if (best > 0) break; // первого успешного замера достаточно
        }
        return best;
    }

    /** Проверить конфиг «с нуля»: поднять, замерить, погасить. */
    public static Result measureConfig(Context ctx, ProxyConfig cfg, int timeoutMs) {
        if (cfg == null || !cfg.supported) return Result.fail();
        if (ConfigRepository.isCancelled()) return Result.fail();
        int port = freePort();
        if (port <= 0) return Result.fail();
        CoreController controller = null;
        try {
            String json = XrayEngine.buildConfigJson(cfg, port, false, Prefs.vpnDns(ctx), 0);
            controller = XrayEngine.startInstance(ctx, json);
        } catch (Throwable t) {
            return Result.fail();
        }
        try {
            if (!waitSocks(port, SOCKS_WAIT_MS)) return new Result(false, -1, port);
            long kbps = speedKbps(ctx, port, timeoutMs);
            return new Result(kbps > 0, kbps, port);
        } finally {
            XrayEngine.stopInstance(controller);
        }
    }
}
