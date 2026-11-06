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

    private static final String TOR_SERVICE_CLASS = "org.torproject.jni.TorService";
    private static final String ACTION_START = "org.torproject.android.intent.action.START";
    private static final String ACTION_STOP = "org.torproject.android.intent.action.STOP";
    private static final String EXTRA_SERVICE_PACKAGE_NAME =
            "org.torproject.android.intent.extra.SERVICE_PACKAGE_NAME";

    private static File torServiceDir(Context ctx) {
        return ctx.getDir("TorService", Context.MODE_PRIVATE);
    }

    /** torrc: SOCKS на 9050 + мосты + дополнительные строки из настроек. */
    private static void writeTorrc(Context ctx) throws IOException {
        File dir = torServiceDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("# Обход Белых Списков: torrc для скачивания списков\n");
        sb.append("SocksPort ").append(SOCKS_PORT).append('\n');
        sb.append("ClientOnly 1\n");
        sb.append("AvoidDiskWrites 1\n");
        List<String> bridges = bridges(ctx);
        if (bridges.isEmpty()) {
            sb.append("UseBridges 0\n");
        } else {
            sb.append("UseBridges 1\n");
            for (String b : bridges) sb.append("Bridge ").append(b).append('\n');
        }
        String extra = Prefs.torExtraTorrc(ctx);
        if (extra != null && !extra.trim().isEmpty()) sb.append(extra.trim()).append('\n');
        try (OutputStream out = new FileOutputStream(new File(dir, "torrc"))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Свои мосты из настроек, иначе встроенные Vanilla (до 30 строк). */
    private static List<String> bridges(Context ctx) {
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
            return false;
        }
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
