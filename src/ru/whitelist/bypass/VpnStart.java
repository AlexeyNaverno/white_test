package ru.whitelist.bypass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/**
 * Единая точка запуска туннеля из интерфейса.
 *
 * Три режима:
 *   • обычный VPN — сервис сам берёт пул текущей сети (Wi-Fi или мобильный);
 *   • конкретный конфиг — из списка рабочих, без перебора остальных;
 *   • «только DPI» — без VPN-сервера, силами локального ciadpi (YouTube, Discord).
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
    private static boolean pendingDpi;

    /** Обычное подключение: пул текущей сети. */
    public static void connect(Activity a) {
        launch(a, null, false);
    }

    /** Подключиться ровно к этому конфигу. */
    public static void connectRaw(Activity a, String raw) {
        launch(a, raw, false);
    }

    /** Режим «только DPI»: без VPN-сервера. */
    public static void connectDpiOnly(Activity a) {
        launch(a, null, true);
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
        boolean dpi = pendingDpi;
        pendingRaw = null;
        pendingDpi = false;
        if (resultCode == Activity.RESULT_OK) startNow(a, raw, dpi);
        return true;
    }

    private static void launch(Activity a, String raw, boolean dpi) {
        try {
            Intent prepare = VpnService.prepare(a);
            if (prepare != null) {
                pendingRaw = raw;
                pendingDpi = dpi;
                a.startActivityForResult(prepare, REQ_VPN);
                return;
            }
            startNow(a, raw, dpi);
        } catch (Throwable t) {
            android.widget.Toast.makeText(a, "Не удалось запустить VPN: " + t.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static void startNow(Context c, String raw, boolean dpi) {
        Intent i = new Intent(c, BypassVpnService.class);
        if (raw != null && !raw.trim().isEmpty()) {
            i.setAction(BypassVpnService.ACTION_CONNECT_RAW);
            i.putExtra(BypassVpnService.EXTRA_FORCE_RAW, raw.trim());
        } else {
            i.setAction(BypassVpnService.ACTION_START);
            if (dpi) i.putExtra(BypassVpnService.EXTRA_START_MODE, BypassVpnService.MODE_DPI);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }
}
