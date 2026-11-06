package ru.whitelist.bypass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Разрешения для жизни приложения в фоне:
 *  - POST_NOTIFICATIONS (Android 13+) запрашивается в MainActivity;
 *  - REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — чтобы ОС не выгружала приложение
 *    и VPN-туннель не рвался в фоне.
 */
public final class PermissionsHelper {

    private static final String PROMPT_SHOWN_KEY = "battery_exempt_prompt_shown";

    private PermissionsHelper() {}

    public static boolean isBatteryOptimizationIgnored(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** Реально запросить исключение (системный диалог). */
    public static void requestBatteryExemption(Activity act) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(act, "На этой версии Android оптимизации нет — всё работает", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isBatteryOptimizationIgnored(act)) {
            Toast.makeText(act, "Уже разрешено — приложение защищено от выгрузки", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Throwable t) {
            // часть прошивок режет этот экшен — открываем общий раздел батареи
            try {
                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                Toast.makeText(act, "Откройте: Настройки → Батарея → не оптимизировать", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Одноразовый автоматический запрос (при первом запуске сборки). Дальше — через Настройки. */
    public static void requestOnce(Activity act) {
        SharedPreferences p = Prefs.get(act);
        if (p.getBoolean(PROMPT_SHOWN_KEY, false)) return;
        p.edit().putBoolean(PROMPT_SHOWN_KEY, true).apply();
        requestBatteryExemption(act);
    }
}
