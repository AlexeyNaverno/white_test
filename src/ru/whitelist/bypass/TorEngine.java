package ru.whitelist.bypass;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Движок Tor на базе info.guardianproject:tor-android (org.torproject.jni.TorService).
 * Пишет torrc с мостами (по умолчанию — Vanilla из встроенного резерва),
 * ждёт подъёма SOCKS на 127.0.0.1:9050.
 */
public final class TorEngine {
    private TorEngine() {}

    public static final int SOCKS_PORT = 9050;

    // Константы org.torproject.jni.TorService (значения стабильны с 0.4.x)
    private static final String TOR_SERVICE_CLASS = "org.torproject.jni.TorService";
    private static final String ACTION_START = "org.torproject.android.intent.action.START";
    private static final String ACTION_STOP = "org.torproject.android.intent.action.STOP";
    private static final String EXTRA_SERVICE_PACKAGE_NAME = "org.torproject.android.intent.extra.SERVICE_PACKAGE_NAME";

    private static File torServiceDir(Context ctx) {
        // то же, что context.getDir(TorService.class.getSimpleName(), MODE_PRIVATE)
        return ctx.getDir("TorService", Context.MODE_PRIVATE);
    }

    public interface Callback {
        void onReady(int socksPort);
        void onError(String message);
        void onLog(String line);
    }

    /** torrc: SocksPort 9050 + мосты + пользовательские строки. */
    public static void writeTorrc(Context ctx) throws IOException {
        File dir = torServiceDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        File torrc = new File(dir, "torrc");

        StringBuilder sb = new StringBuilder();
        sb.append("# Сгенерировано «Обход Белых Списков» ").append(System.currentTimeMillis()).append('\n');
        sb.append("SocksPort ").append(SOCKS_PORT).append('\n');
        sb.append("ClientOnly 1\n");
        sb.append("AvoidDiskWrites 1\n");

        List<String> bridges = bridges(ctx);
        if (!bridges.isEmpty()) {
            sb.append("UseBridges 1\n");
            for (String b : bridges) {
                sb.append("Bridge ").append(b).append('\n');
            }
        } else {
            sb.append("UseBridges 0\n");
        }

        String extra = Prefs.torExtraTorrc(ctx);
        if (extra != null && !extra.trim().isEmpty()) {
            sb.append(extra.trim()).append('\n');
        }

        try (OutputStream out = new FileOutputStream(torrc)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Мосты из настроек; если пусто и включён встроенный список — из assets (Vanilla). */
    public static List<String> bridges(Context ctx) {
        String userBridges = Prefs.torBridges(ctx);
        List<String> lines = Prefs.lines(userBridges);
        if (!lines.isEmpty()) {
            java.util.Set<String> deadUser = ConfigRepository.loadTorDead(ctx);
            java.util.ArrayList<String> alive = new java.util.ArrayList<>();
            for (String l : lines) {
                String key = ConfigRepository.bridgeKey(l);
                if (key != null && deadUser.contains(key)) continue;
                alive.add(l);
            }
            return alive;
        }
        if (!Prefs.torBuiltin(ctx)) return lines;
        java.util.Set<String> dead = ConfigRepository.loadTorDead(ctx);
        // 1) «проверенные» мосты (прошли массовый TLS-тест) — приоритет, до 60 в torrc
        java.util.ArrayList<String> combined = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> usedKeys = new java.util.LinkedHashSet<>();
        for (String t : ConfigRepository.loadTorGood(ctx)) {
            String key = ConfigRepository.bridgeKey(t);
            if (key == null || dead.contains(key) || !usedKeys.add(key)) continue;
            combined.add(t);
            if (combined.size() >= 60) break;
        }
        // 2) автоскачанный резерв (фоновая догрузка при быстром канале), если свежий
        java.util.ArrayList<String> auto = new java.util.ArrayList<>();
        long autoAt = Prefs.torBridgesAutoAt(ctx);
        if (autoAt > 0 && System.currentTimeMillis() - autoAt < 14L * 24 * 3600 * 1000) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(
                            new java.io.File(ctx.getFilesDir(), "tor_bridges_auto.txt")),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && auto.size() < 60) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    if (t.regionMatches(true, 0, "Bridge ", 0, 7)) t = t.substring(7).trim();
                    if (t.matches("^(\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}(\\s+[0-9A-Fa-f]{40,})?$")) {
                        String key = ConfigRepository.bridgeKey(t);
                        if (key != null && dead.contains(key)) continue;
                        auto.add(t);
                    }
                }
            } catch (java.io.IOException ignored) {
            }
        }
        for (String t : auto) {
            if (combined.size() >= 60) break;
            String key = ConfigRepository.bridgeKey(t);
            if (key != null && !usedKeys.add(key)) continue;
            combined.add(t);
        }
        if (combined.size() >= 10) return combined;
        // встроенный резерв: vanilla-мосты (работают без внешних плагинов)
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try (java.io.InputStream in = ctx.getAssets().open("fallback/tor_bridges_vanilla.txt")) {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                // формат: "Bridge <ip:port>" или просто "<ip:port>"
                if (t.regionMatches(true, 0, "Bridge ", 0, 7)) t = t.substring(7).trim();
                if (t.matches("^(\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}$") || t.matches("^[^\\s:]+:\\d{1,5}$")) {
                    String key = ConfigRepository.bridgeKey(t);
                    if (key != null && dead.contains(key)) continue; // не пробуем мёртвый встроенный
                    out.add(t);
                }
                if (out.size() >= 60) break; // не перегружаем torrc
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static void start(Context ctx, Callback callback) {
        try {
            writeTorrc(ctx);
        } catch (IOException e) {
            callback.onError("не удалось записать torrc: " + e.getMessage());
            return;
        }
        Intent intent = new Intent().setClassName(ctx, TOR_SERVICE_CLASS);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_SERVICE_PACKAGE_NAME, ctx.getPackageName());
        try {
            ctx.startService(intent);
        } catch (Exception e) {
            callback.onError("не удалось запустить TorService: " + e.getMessage());
            return;
        }
        if (callback != null) callback.onLog("[tor] TorService запущен, ждём цепочку…");
        waitForSocks(callback, 150_000);
    }

    private static void waitForSocks(final Callback callback, long timeoutMs) {
        // Блокирующее ожидание: вызывается из фонового потока VPN-сервиса
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long ms = NetChecker.tcpPing("127.0.0.1", SOCKS_PORT, 1500);
            if (ms >= 0) {
                if (callback != null) callback.onReady(SOCKS_PORT);
                return;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        if (callback != null) callback.onError("Tor не поднялся за " + (timeoutMs / 1000)
                + " c. Проверьте мосты и сеть.");
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
