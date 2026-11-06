package ru.whitelist.bypass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

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
        // VPN можно стартовать без диалога только если разрешение уже выдано
        if (VpnService.prepare(context) != null) return;
        try {
            Intent i = new Intent(context, BypassVpnService.class);
            i.setAction(BypassVpnService.ACTION_START);
            i.putExtra(BypassVpnService.EXTRA_MODE, Prefs.mode(context));
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception ignored) {
            // не смогли — подождём ручного запуска
        }
    }
}
