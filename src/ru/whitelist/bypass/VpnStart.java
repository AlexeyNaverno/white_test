package ru.whitelist.bypass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/**
 * Единая точка запуска туннеля из интерфейса.
 *
 * Четыре режима:
 *   • обычный VPN — сервис сам берёт пул текущей сети (Wi-Fi или мобильный);
 *   • конкретный конфиг — из списка рабочих, без перебора остальных;
 *   • «только DPI» — без VPN-сервера, силами локального ciadpi (YouTube, Discord);
 *   • «Tor как VPN» — сразу через мосты, когда конфигов нет или они не работают.
 *
 * Раньше каждый экран сам собирал Intent и сам просил разрешение VPN, из-за чего
 * обработка результата дублировалась. Теперь это здесь: экран говорит, ЧТО поднять,
 * а разрешение и Intent — общая забота.
 */
public final class VpnStart {
    private VpnStart() {}

    private static final int REQ_VPN = 300;
    /** Что запустить после того, как пользователь разрешит VPN. */
    private static String pendingRaw;
    /** Режим запуска: null — обычный VPN, MODE_DPI — «только DPI», MODE_TOR — Tor как VPN. */
    private static String pendingMode;

    /** Обычное подключение: пул текущей сети. */
    public static void connect(Activity a) {
        launch(a, null, null);
    }

    /** Подключиться ровно к этому конфигу. */
    public static void connectRaw(Activity a, String raw) {
        launch(a, raw, null);
    }

    /** Режим «только DPI»: без VPN-сервера. */
    public static void connectDpiOnly(Activity a) {
        launch(a, null, BypassVpnService.MODE_DPI);
    }

    /** Tor-мосты как обычный VPN: канал сразу через Tor, без перебора конфигов. */
    public static void connectTor(Activity a) {
        launch(a, null, BypassVpnService.MODE_TOR);
    }

    public static void stop(Context c) {
        try {
            Intent i = new Intent(c, BypassVpnService.class);
            i.setAction(BypassVpnService.ACTION_STOP);
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Результат запроса разрешения VPN. Возвращает true, если код запроса «наш» —
     * тогда экран не должен обрабатывать его сам.
     */
    public static boolean handleResult(Activity a, int requestCode, int resultCode) {
        if (requestCode != REQ_VPN) return false;
        String raw = pendingRaw;
        String mode = pendingMode;
        pendingRaw = null;
        pendingMode = null;
        if (resultCode == Activity.RESULT_OK) startNow(a, raw, mode);
        return true;
    }

    private static void launch(Activity a, String raw, String mode) {
        try {
            Intent prepare = VpnService.prepare(a);
            if (prepare != null) {
                pendingRaw = raw;
                pendingMode = mode;
                a.startActivityForResult(prepare, REQ_VPN);
                return;
            }
            startNow(a, raw, mode);
        } catch (Throwable t) {
            android.widget.Toast.makeText(a, "Не удалось запустить VPN: " + t.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static void startNow(Context c, String raw, String mode) {
        Intent i = new Intent(c, BypassVpnService.class);
        if (raw != null && !raw.trim().isEmpty()) {
            i.setAction(BypassVpnService.ACTION_CONNECT_RAW);
            i.putExtra(BypassVpnService.EXTRA_FORCE_RAW, raw.trim());
        } else {
            i.setAction(BypassVpnService.ACTION_START);
            if (mode != null && !mode.isEmpty()) {
                i.putExtra(BypassVpnService.EXTRA_START_MODE, mode);
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }
}
