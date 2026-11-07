package ru.whitelist.bypass;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Tor — ТОЛЬКО как канал для скачивания списков конфигов, когда прямые источники
 * (и jsDelivr) недоступны. Как режим обхода не используется.
 *
 * Движок: org.torproject.jni.TorService из tor-android. Пишем torrc с мостами
 * (свои из настроек либо встроенные Vanilla), ждём SOCKS на 127.0.0.1:9050.
 */
public final class TorEngine {
    private TorEngine() {}

    public static final int SOCKS_PORT = 9050;
    /**
     * HTTP-туннель поверх Tor (torrc: HTTPTunnelPort). Держим включённым как запасной
     * путь для инструментов, которым нужен именно HTTP-прокси: WebView в приложении
     * работает через SOCKS-перехват (ProxyController — системный API и обычным
     * приложениям недоступен).
     */
    public static final int HTTP_TUNNEL_PORT = 8118;

    private static final String TOR_SERVICE_CLASS = "org.torproject.jni.TorService";
    private static final String ACTION_START = "org.torproject.android.intent.action.START";
    private static final String ACTION_STOP = "org.torproject.android.intent.action.STOP";
    private static final String EXTRA_SERVICE_PACKAGE_NAME =
            "org.torproject.android.intent.extra.SERVICE_PACKAGE_NAME";

    /** Последняя причина, по которой Tor не поднялся (для логов и интерфейса). */
    private static volatile String lastError = "";

    public static String lastError() {
        return lastError;
    }

    private static File torServiceDir(Context ctx) {
        return ctx.getDir("TorService", Context.MODE_PRIVATE);
    }

    /** torrc: SOCKS 9050, HTTP-туннель 8118, мосты + дополнительные строки из настроек. */
    private static void writeTorrc(Context ctx) throws IOException {
        writeTorrc(ctx, bridges(ctx));
    }

    /** То же, но со своим списком мостов (для проверки конкретного моста). */
    private static void writeTorrc(Context ctx, List<String> bridges) throws IOException {
        File dir = torServiceDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("# Обход Белых Списков: torrc для скачивания списков\n");
        sb.append("SocksPort ").append(SOCKS_PORT).append('\n');
        sb.append("HTTPTunnelPort ").append(HTTP_TUNNEL_PORT).append('\n');
        sb.append("ClientOnly 1\n");
        sb.append("AvoidDiskWrites 1\n");
        // Мосты включены всегда: без них Tor — это просто «ещё один медленный интернет»,
        // а для этого есть обычный браузер. Нет мостов — резервный канал не поднимаем.
        if (bridges == null || bridges.isEmpty()) {
            throw new IOException("нет мостов: добавьте их в настройках Tor");
        }
        sb.append("UseBridges 1\n");
        for (String b : bridges) sb.append("Bridge ").append(b).append('\n');
        String extra = Prefs.torExtraTorrc(ctx);
        if (extra != null && !extra.trim().isEmpty()) sb.append(extra.trim()).append('\n');
        try (OutputStream out = new FileOutputStream(new File(dir, "torrc"))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Свои мосты из настроек, иначе встроенные Vanilla, — но если есть замеры скорости
     * (кнопка «Проверить мосты»), в torrc сначала идут самые быстрые.
     */
    private static List<String> bridges(Context ctx) {
        List<String> base = candidateBridges(ctx);
        // ВАЖНО: ranked() возвращает null, когда замеров мостов ещё нет (или рабочих
        // меньше двух). Раньше здесь стояло ranked.isEmpty() — и первый же запуск Tor
        // до кнопки «Проверить мосты» падал с NullPointerException
        // («Attempt to invoke interface method List.isEmpty() on a null object reference»),
        // из-за чего сбор пула обрывался с ошибкой.
        List<String> ranked = ranked(base, Prefs.torBridgeRank(ctx));
        if (ranked == null || ranked.isEmpty()) return trimmed(base, 30);
        return ranked;
    }

    /** Мосты как они заведены: свои из настроек, иначе встроенные. Без сортировки. */
    public static List<String> candidateBridges(Context ctx) {
        List<String> user = Prefs.lines(Prefs.torBridges(ctx));
        if (!user.isEmpty()) return trimmed(user, 30);
        List<String> builtin = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("fallback/tor_bridges_vanilla.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && builtin.size() < 30) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.regionMatches(true, 0, "Bridge ", 0, 7)) t = t.substring(7).trim();
                if (t.matches("^(\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}$") || t.matches("^[^\\s:]+:\\d{1,5}$")) {
                    builtin.add(t);
                }
            }
        } catch (IOException ignored) {
        }
        return builtin;
    }

    /**
     * Порядок по замерам: измеренные и работающие — первыми (быстрые выше),
     * неизмеренные — следом. Если рабочих меньше двух, сортировка не применяется:
     * лучше пробовать всё, чем остаться без резерва.
     */
    private static List<String> ranked(List<String> base, String rankText) {
        List<String> rankLines = Prefs.lines(rankText);
        if (rankLines.isEmpty()) return null;
        final Map<String, Long> kbps = new HashMap<>();
        for (String line : rankLines) {
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            try {
                kbps.put(parts[0].trim(), Long.parseLong(parts[1].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        int alive = 0;
        for (String b : base) {
            Long k = kbps.get(b);
            if (k != null && k > 0) alive++;
        }
        if (alive < 2) return null;
        List<String> sorted = new ArrayList<>(base);
        Collections.sort(sorted, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                Long ka = kbps.get(a);
                Long kb = kbps.get(b);
                long va = ka == null ? -1 : ka;
                long vb = kb == null ? -1 : kb;
                return Long.compare(vb, va);
            }
        });
        List<String> out = new ArrayList<>();
        for (String b : sorted) {
            Long k = kbps.get(b);
            if (k != null && k > 0) out.add(b);
        }
        return out.size() >= 2 ? trimmed(out, 6) : null;
    }

    /** Поднять Tor с конкретным списком мостов (проверка моста). */
    public static boolean startWith(Context ctx, List<String> bridges, long timeoutMs) {
        try {
            writeTorrc(ctx, bridges);
        } catch (IOException e) {
            lastError = e.getMessage();
            return false;
        }
        boolean ok = startServiceAndWait(ctx, timeoutMs);
        if (!ok) lastError = "мост не поднялся за " + (timeoutMs / 1000) + " с";
        return ok;
    }

    /** Перезапуск Tor: дождаться, пока старый процесс отпустит порт, и поднять заново. */
    public static boolean restart(Context ctx, long timeoutMs) {
        stopAndWait(ctx, 8000);
        return ensureStarted(ctx, timeoutMs);
    }

    public static void stopAndWait(Context ctx, long timeoutMs) {
        stop(ctx);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!socksOpen()) return;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** HTTP-туннель для браузера (8118) открыт? */
    public static boolean httpTunnelOpen() {
        return NetChecker.tcpPing("127.0.0.1", HTTP_TUNNEL_PORT, 1500) >= 0;
    }

    private static List<String> trimmed(List<String> in, int max) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (out.size() >= max) break;
            out.add(s.trim());
        }
        return out;
    }

    /**
     * Поднять Tor и дождаться SOCKS. Блокирующий вызов — только из фонового потока.
     * true — SOCKS открыт, false — не успели/не вышло.
     */
    public static boolean ensureStarted(Context ctx, long timeoutMs) {
        if (socksOpen()) return true;
        try {
            writeTorrc(ctx);
        } catch (IOException e) {
            lastError = e.getMessage();
            return false;
        }
        boolean ok = startServiceAndWait(ctx, timeoutMs);
        if (!ok) lastError = "Tor не поднялся с мостами за " + (timeoutMs / 1000) + " с";
        return ok;
    }

    private static boolean startServiceAndWait(Context ctx, long timeoutMs) {
        try {
            Intent intent = new Intent().setClassName(ctx, TOR_SERVICE_CLASS);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_SERVICE_PACKAGE_NAME, ctx.getPackageName());
            ctx.startService(intent);
        } catch (Exception e) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (socksOpen()) return true;
            if (ConfigRepository.isCancelled()) return false;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean socksOpen() {
        return NetChecker.tcpPing("127.0.0.1", SOCKS_PORT, 1500) >= 0;
    }

    public static void stop(Context ctx) {
        try {
            Intent intent = new Intent().setClassName(ctx, TOR_SERVICE_CLASS);
            intent.setAction(ACTION_STOP);
            intent.putExtra(EXTRA_SERVICE_PACKAGE_NAME, ctx.getPackageName());
            ctx.startService(intent);
        } catch (Exception ignored) {
        }
    }
}
