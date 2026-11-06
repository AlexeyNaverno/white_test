package ru.whitelist.bypass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

/** Автоподключение при загрузке (если включено в настройках). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        if (!Prefs.bootConnect(context)) return;
        // Стартовать VPN без диалога можно только если разрешение уже выдано
        if (VpnService.prepare(context) != null) return;
        try {
            Intent i = new Intent(context, BypassVpnService.class);
            i.setAction(BypassVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception ignored) {
            // не вышло — подключимся вручную
        }
    }
}
