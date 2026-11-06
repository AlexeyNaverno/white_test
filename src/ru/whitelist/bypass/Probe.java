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
 * Каждый результат несёт ПРИЧИНУ (для лога): «SOCKS не открылся», «нет данных»,
 * «скачано 96 КБ за 1 400 мс» — чтобы в логе было видно не только «проверка N/M»,
 * но и чем она закончилась.
 */
public final class Probe {
    private Probe() {}

    /** Меньше этого объёма — оценка скорости недостоверна. */
    public static final int MIN_SAMPLE_BYTES = 64 * 1024;
    /** Сколько качаем за один замер (хватает, чтобы оценить и быстрый, и медленный канал). */
    public static final long MAX_SAMPLE_BYTES = 512 * 1024;
    /** Сколько ждём, пока Xray откроет локальный SOCKS. */
    public static final long SOCKS_WAIT_MS = 6000;

    /** Замер скорости: значение + чем именно он получен. */
    public static final class Speed {
        public final long kbps;
        public final String url;
        public final long bytes;
        public final long ms;
        public final int attempts;

        Speed(long kbps, String url, long bytes, long ms, int attempts) {
            this.kbps = kbps;
            this.url = url == null ? "" : url;
            this.bytes = bytes;
            this.ms = ms;
            this.attempts = attempts;
        }

        public boolean ok() {
            return kbps > 0;
        }

        /** «512 КБ/с (4096 кбит/с) · 512 КБ за 1 000 мс» или «нет данных (3 URL)». */
        public String text() {
            if (kbps <= 0) return "нет данных (" + attempts + " URL)";
            return Prefs.speedText(kbps) + " · " + (bytes / 1024) + " КБ за " + ms + " мс";
        }
    }

    public static final class Result {
        public final boolean ok;
        public final long kbps;
        public final long socksPort;
        /** Человеческая причина: чем закончилась проверка. */
        public final String reason;
        public final Speed speed;

        public Result(boolean ok, long kbps, long socksPort, String reason, Speed speed) {
            this.ok = ok;
            this.kbps = kbps;
            this.socksPort = socksPort;
            this.reason = reason == null ? "" : reason;
            this.speed = speed;
        }

        static Result fail(String reason) {
            return new Result(false, -1, -1, reason, null);
        }

        /** Короткая строка для лога. */
        public String text() {
            if (ok) return "OK · " + (speed != null ? speed.text() : Prefs.speedText(kbps));
            return "не прошёл · " + reason;
        }
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
     * Замер через уже поднятый SOCKS: перебираем URL замера, пока какой-нибудь не отдаст
     * данные. Возвращаем лучший результат вместе с деталями («какой URL, сколько, за сколько»).
     */
    public static Speed measureSpeed(Context ctx, int socksPort, int timeoutMs) {
        return measureSpeed(ctx, socksPort, timeoutMs, Integer.MAX_VALUE);
    }

    /**
     * То же, но не более maxUrls попыток: для отбраковки в поиске хватает двух URL,
     * иначе каждый мёртвый конфиг стоит три таймаута.
     */
    public static Speed measureSpeed(Context ctx, int socksPort, int timeoutMs, int maxUrls) {
        long best = -1;
        String bestUrl = "";
        long bestBytes = 0;
        long bestMs = 0;
        int attempts = 0;
        for (String url : Prefs.speedUrls(ctx)) {
            if (ConfigRepository.isCancelled()) break;
            if (attempts >= maxUrls) break;
            attempts++;
            NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(
                    url, "127.0.0.1", socksPort, timeoutMs, MAX_SAMPLE_BYTES);
            long kbps = r.kbps();
            if (kbps > best) {
                best = kbps;
                bestUrl = url;
                bestBytes = r.bytes;
                bestMs = r.ms;
            }
            if (best > 0) break; // первого успешного замера достаточно
        }
        return new Speed(best, bestUrl, bestBytes, bestMs, attempts);
    }

    /** Скорость канала через уже поднятый SOCKS, КБ/с. -1 — данных не получили. */
    public static long speedKbps(Context ctx, int socksPort, int timeoutMs) {
        return measureSpeed(ctx, socksPort, timeoutMs).kbps;
    }

    /** Проверить конфиг «с нуля»: поднять, замерить, погасить. */
    public static Result measureConfig(Context ctx, ProxyConfig cfg, int timeoutMs) {
        if (cfg == null) return Result.fail("пустой конфиг");
        if (!cfg.supported) return Result.fail("схема " + cfg.scheme + " не поддерживается");
        if (ConfigRepository.isCancelled()) return Result.fail("поиск остановлен");
        int port = freePort();
        if (port <= 0) return Result.fail("нет свободного порта");
        CoreController controller = null;
        try {
            String json = XrayEngine.buildConfigJson(cfg, port, false, Prefs.vpnDns(ctx), 0);
            controller = XrayEngine.startInstance(ctx, json);
        } catch (Throwable t) {
            String m = t.getMessage();
            return Result.fail("ядро не запустилось" + (m == null || m.isEmpty() ? "" : ": " + m));
        }
        try {
            if (!waitSocks(port, SOCKS_WAIT_MS)) {
                return new Result(false, -1, port,
                        "SOCKS не открылся за " + (SOCKS_WAIT_MS / 1000) + " с", null);
            }
            Speed s = measureSpeed(ctx, port, timeoutMs, 2);
            if (!s.ok()) {
                return new Result(false, -1, port, s.text(), s);
            }
            return new Result(true, s.kbps, port, "", s);
        } finally {
            XrayEngine.stopInstance(controller);
        }
    }
}
