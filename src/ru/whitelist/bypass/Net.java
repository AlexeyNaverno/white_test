package ru.whitelist.bypass;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Как телефон сейчас подключён к интернету — Wi-Fi или мобильный оператор.
 *
 * Зачем это нужно приложению. У мобильного оператора и у домашнего Wi-Fi разные
 * блокировки и разные рабочие конфиги: дома DPI-обход часто не нужен вовсе, а в LTE без
 * него не поднимается ничего. Поэтому пул ведётся отдельно для каждой сети (см. {@link Pool}),
 * и стратегия DPI-обхода по умолчанию тоже своя: у Wi-Fi — «домашняя», у мобильного —
 * «белые списки» (см. {@link Prefs#dpiStrategyFor}).
 *
 * Значения ключей попадают в имена файлов пула, поэтому они короткие и латиницей.
 */
public final class Net {
    private Net() {}

    /** Wi-Fi (и Ethernet — на телефоне это тоже «домашняя» сеть без оператора). */
    public static final String WIFI = "wifi";
    /**
     * Мобильный интернет. Сюда же попадает «неизвестно»: если тип сети определить не
     * удалось, безопаснее вести себя как у оператора — там блокировки жёстче.
     */
    public static final String MOBILE = "lte";

    /** Ключ текущей сети: {@link #WIFI} или {@link #MOBILE}. */
    public static String key(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return MOBILE;
            Network net = cm.getActiveNetwork();
            if (net == null) return MOBILE;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return MOBILE;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return WIFI;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return MOBILE;
            return MOBILE;
        } catch (Throwable t) {
            return MOBILE;
        }
    }

    public static boolean isWifi(Context c) {
        return WIFI.equals(key(c));
    }

    /** Человеческое название сети — для заголовков, логов и уведомлений. */
    public static String label(String key) {
        return WIFI.equals(key) ? "Wi-Fi" : "Мобильный интернет";
    }

    public static String label(Context c) {
        return label(key(c));
    }

    /** Короткая метка для вкладок и чипов. */
    public static String shortLabel(String key) {
        return WIFI.equals(key) ? "Wi-Fi" : "LTE";
    }

    /** Обратный ключ: всё, что не wifi, — мобильный пул. */
    public static String normalize(String key) {
        return WIFI.equals(key) ? WIFI : MOBILE;
    }
}
