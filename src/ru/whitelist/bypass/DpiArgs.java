package ru.whitelist.bypass;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Общие helpers для запуска ciadpi (ByeDPI) из сервиса и из UI-проб. */
public final class DpiArgs {
    private DpiArgs() {}

    public static String hostOf(String url) {
        try {
            String host = new java.net.URL(url).getHost();
            return host == null || host.isEmpty() ? "www.youtube.com" : host;
        } catch (Exception e) {
            return "www.youtube.com";
        }
    }

    public static String sniFromUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return "www.youtube.com";
        return hostOf(urls.get(0));
    }

    /** {sni} → SNI первой проверочной ссылки, {port} → порт; аргументы по пробелам. */
    public static String[] build(String strategy, int port, String sni) {
        String replaced = strategy.replace("{sni}", sni != null ? sni : "www.youtube.com")
                .replace("{port}", String.valueOf(port));
        List<String> out = new ArrayList<>();
        for (String tok : replaced.split("\\s+")) {
            if (!tok.isEmpty()) out.add(tok);
        }
        return out.toArray(new String[0]);
    }
}
