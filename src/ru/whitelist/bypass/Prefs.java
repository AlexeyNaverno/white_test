package ru.whitelist.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки приложения (SharedPreferences) с дефолтами.
 * Принцип: каждая настройка здесь реально влияет на работу. Никаких «на будущее».
 */
public final class Prefs {
    private Prefs() {}

    private static final String FILE = "bypass_prefs";

    // --- ключи ---
    public static final String SOURCES = "sources";
    public static final String MIN_SPEED_KBPS = "min_speed_kbps";
    public static final String SPEED_URLS = "speed_urls_v2";
    public static final String PING_THREADS = "ping_threads";
    public static final String PING_TIMEOUT_MS = "ping_timeout_ms";
    public static final String GATE_TIMEOUT_MS = "gate_timeout_ms";
    public static final String DESYNC_LINE = "desync_line";
    public static final String DPI_STRATEGY = "dpi_strategy_v3";
    public static final String VPN_DNS = "vpn_dns";
    public static final String IPV6 = "ipv6";
    public static final String AUTO_SWITCH = "auto_switch";
    public static final String MONITOR_INTERVAL_SEC = "monitor_interval_sec";
    public static final String SWITCH_TIMEOUT_SEC = "switch_timeout_sec";
    public static final String BOOT_CONNECT = "boot_connect";
    public static final String EMBEDDED = "embedded_enabled";
    public static final String TOR_BRIDGES = "tor_bridges";
    public static final String TOR_EXTRA_TORRC = "tor_extra_torrc";
    public static final String CURRENT_RAW = "current_raw";
    public static final String CURRENT_NAME = "current_name";
    public static final String CURRENT_HOST = "current_host";
    public static final String CURRENT_PORT = "current_port";
    public static final String POOL_AT = "pool_at";

    /** Одна рабочая стратегия DPI — та, что подставляется в линию до VPN-сервера. */
    public static final String DEFAULT_STRATEGY = "--disorder 1 --auto=torst --tlsrec 1+s";

    /** Источники списков VLESS (по одному на строку). */
    public static final String[] DEFAULT_SOURCES = {
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
            "https://raw.githubusercontent.com/wlunlocker/vpn-configs/main/whitelist_all.txt",
            "https://raw.githubusercontent.com/ByeWhiteLists/ByeWhiteLists2/refs/heads/main/ByeWhiteLists2.txt"
    };

    /** Файлы по 1 МБ с иностранных CDN — по ним измеряется скорость канала. */
    public static final String[] DEFAULT_SPEED_URLS = {
            "https://speed.cloudflare.com/__down?bytes=1048576",
            "https://proof.ovh.net/files/1Mb.dat",
            "https://cachefly.cachefly.net/1mb.test"
    };

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // --- скорость ---

    /** Нижняя граница скорости для «рабочего» конфига, КБ/с. 128 по умолчанию. */
    public static int minSpeedKbps(Context c) {
        return clamp(get(c).getInt(MIN_SPEED_KBPS, 128), 8, 20000);
    }

    public static List<String> speedUrls(Context c) {
        String raw = get(c).getString(SPEED_URLS, join(DEFAULT_SPEED_URLS));
        List<String> out = lines(raw);
        if (out.isEmpty()) {
            for (String u : DEFAULT_SPEED_URLS) out.add(u);
        }
        return out;
    }

    /** Сколько ждём на один замер, мс. */
    public static int gateTimeoutMs(Context c) {
        return clamp(get(c).getInt(GATE_TIMEOUT_MS, 6000), 2000, 20000);
    }

    // --- источники ---

    public static List<String> sources(Context c) {
        String joined = get(c).getString(SOURCES, null);
        if (joined == null) joined = join(DEFAULT_SOURCES);
        return lines(joined);
    }

    // --- проверки ---

    public static int pingThreads(Context c) {
        return clamp(get(c).getInt(PING_THREADS, 60), 1, 200);
    }

    public static int pingTimeoutMs(Context c) {
        return clamp(get(c).getInt(PING_TIMEOUT_MS, 2000), 300, 15000);
    }

    // --- DPI на линии ---

    /** Пробовать DPI-обход линии (ciadpi) для конфигов, не вышедших напрямую. */
    public static boolean desyncLine(Context c) {
        return get(c).getBoolean(DESYNC_LINE, true);
    }

    public static String dpiStrategy(Context c) {
        String s = get(c).getString(DPI_STRATEGY, DEFAULT_STRATEGY);
        return s == null || s.trim().isEmpty() ? DEFAULT_STRATEGY : s.trim();
    }

    public static void setDpiStrategy(Context c, String s) {
        get(c).edit().putString(DPI_STRATEGY, s == null ? "" : s.trim()).apply();
    }

    // --- сеть ---

    public static String vpnDns(Context c) {
        return get(c).getString(VPN_DNS, "1.1.1.1").trim();
    }

    public static boolean ipv6(Context c) {
        return get(c).getBoolean(IPV6, false);
    }

    public static boolean autoSwitch(Context c) {
        return get(c).getBoolean(AUTO_SWITCH, true);
    }

    public static int monitorIntervalSec(Context c) {
        return clamp(get(c).getInt(MONITOR_INTERVAL_SEC, 15), 5, 300);
    }

    public static int switchTimeoutSec(Context c) {
        return clamp(get(c).getInt(SWITCH_TIMEOUT_SEC, 45), 5, 3600);
    }

    public static boolean bootConnect(Context c) {
        return get(c).getBoolean(BOOT_CONNECT, false);
    }

    // --- данные ---

    public static boolean embeddedEnabled(Context c) {
        return get(c).getBoolean(EMBEDDED, true);
    }

    // --- Tor (только как канал скачивания списков) ---

    public static String torBridges(Context c) {
        return get(c).getString(TOR_BRIDGES, "");
    }

    public static void setTorBridges(Context c, String v) {
        get(c).edit().putString(TOR_BRIDGES, v == null ? "" : v).apply();
    }

    public static String torExtraTorrc(Context c) {
        return get(c).getString(TOR_EXTRA_TORRC, "");
    }

    // --- текущий конфиг ---

    public static void setCurrentConfig(Context c, String raw, String name, String host, int port) {
        get(c).edit()
                .putString(CURRENT_RAW, raw)
                .putString(CURRENT_NAME, name)
                .putString(CURRENT_HOST, host)
                .putInt(CURRENT_PORT, port)
                .apply();
    }

    public static String currentRaw(Context c) { return get(c).getString(CURRENT_RAW, ""); }
    public static String currentName(Context c) { return get(c).getString(CURRENT_NAME, ""); }
    public static String currentHost(Context c) { return get(c).getString(CURRENT_HOST, ""); }
    public static int currentPort(Context c) { return get(c).getInt(CURRENT_PORT, 443); }

    // --- пул ---

    public static long poolBuiltAt(Context c) { return get(c).getLong(POOL_AT, 0L); }
    public static void setPoolBuiltAt(Context c, long t) { get(c).edit().putLong(POOL_AT, t).apply(); }

    // --- утилиты ---

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static List<String> lines(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null) return out;
        for (String line : joined.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    public static String join(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (String s : arr) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    public static String join(List<String> arr) {
        StringBuilder sb = new StringBuilder();
        for (String s : arr) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    public static void putInt(Context c, String key, int value) {
        get(c).edit().putInt(key, value).apply();
    }

    public static void putString(Context c, String key, String value) {
        get(c).edit().putString(key, value == null ? "" : value).apply();
    }

    public static void putBoolean(Context c, String key, boolean value) {
        get(c).edit().putBoolean(key, value).apply();
    }
}
