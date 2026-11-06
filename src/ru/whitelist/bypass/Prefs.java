package ru.whitelist.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Все настройки приложения (SharedPreferences) с дефолтами. */
public final class Prefs {
    private Prefs() {}

    private static final String FILE = "bypass_prefs";

    public static final String MODE = "mode";                       // dpi | vpn | tor
    // v2: прежние сохранённые значения содержали недопустимые для бинарника опции (-Qr и т.п.)
    public static final String BYEDPI_STRATEGY = "byedpi_strategy_v2";
    public static final String BYEDPI_DNS = "byedpi_dns";
    public static final String VPN_DNS = "vpn_dns";
    public static final String IPV6 = "ipv6";
    public static final String AUTO_SWITCH = "auto_switch";
    public static final String SWITCH_TIMEOUT_SEC = "switch_timeout_sec";
    public static final String MONITOR_INTERVAL_SEC = "monitor_interval_sec";
    public static final String BATCH_SIZE = "batch_size";
    public static final String PING_THREADS = "ping_threads";
    public static final String PING_TIMEOUT_MS = "ping_timeout_ms";
    public static final String SPEED_TEST_URL = "speed_test_url";
    public static final String SPEED_MIN_KBPS = "speed_min_kbps";
    public static final String QUIET_UPDATE = "quiet_update";
    /** Эксперимент: DPI-обход на линии до VPN-сервера. */
    public static final String VPN_DESYNC = "vpn_desync";
    /** Гонка топ-3 при подключении (кто первый прошёл — тот и туннель). */
    public static final String VPN_RACE = "vpn_race";
    /** Миграция 0.017: дописать белые списки в сохранённые источники (один раз). */
    public static final String SOURCE_MIG_017 = "source_mig_017";
    public static final String AUTO_UPDATE_ON_START = "auto_update_on_start";
    public static final String EMBEDDED_ENABLED = "embedded_enabled";
    public static final String SOURCES = "sources";
    // ключ обновлён: прежние (русские) адреса из v1 больше не используются
    public static final String PING_TARGETS = "ping_targets_v2";
    public static final String DPI_TEST_URLS = "dpi_test_urls";
    public static final String DPI_AUTO_SELECT = "dpi_auto_select";
    public static final String SPEED_GATE_URLS = "speed_gate_urls";
    public static final String TOR_BRIDGES_AUTO_AT = "tor_bridges_auto_at";
    public static final String DPI_STRATS_URL = "dpi_strats_url";
    public static final String DPI_STRATS_AUTO_AT = "dpi_strats_auto_at";
    public static final String GITHUB_HOST = "github_host";
    public static final String GITHUB_FALLBACK_IPS = "github_fallback_ips";
    public static final String VPN_INSECURE = "vpn_insecure";
    public static final String BOOT_CONNECT = "boot_connect";
    public static final String TOR_BRIDGES = "tor_bridges";
    public static final String TOR_EXTRA_TORRC = "tor_extra_torrc";
    public static final String TOR_BUILTIN = "tor_builtin";
    public static final String LAST_SOURCE_INDEX = "last_source_index";
    public static final String CURRENT_CONFIG_RAW = "current_config_raw";
    public static final String CURRENT_CONFIG_NAME = "current_config_name";
    public static final String CURRENT_CONFIG_HOST = "current_config_host";
    public static final String CURRENT_CONFIG_PORT = "current_config_port";
    public static final String DPI_STRATEGY_INDEX = "dpi_strategy_index";

    public static final String DEFAULT_STRATEGY = "--disorder 1 --auto=torst --tlsrec 1+s";
    public static final String DEFAULT_SPEED_URL = "https://speed.cloudflare.com/__down?bytes=524288";

    /** Источники подписок (по одному на строку в настройках). Сверено 05.11.2026. */
    public static final String[] DEFAULT_SOURCES = {
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-SNI-RU-all.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_SS%2BAll_RUS.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
            "https://raw.githubusercontent.com/wlunlocker/vpn-configs/main/whitelist_all.txt",
            "https://raw.githubusercontent.com/wlunlocker/vpn-configs/main/blacklist_vpn1.txt",
            "https://raw.githubusercontent.com/ByeWhiteLists/ByeWhiteLists2/refs/heads/main/ByeWhiteLists2.txt",
    };

    /** Только иностранные ресурсы (проверка канала); российские не пингуем. */
    public static final String[] DEFAULT_PING_TARGETS = {
            "github.com:443",
            "www.google.com:443",
            "1.1.1.1:443",
            "8.8.8.8:53",
            "www.youtube.com:443",
            "www.cloudflare.com:443",
    };

    /** Ссылки для автовыбора стратегии DPI перед подключением (по умолчанию — только YouTube). */
    public static final String DEFAULT_DPI_TEST_URLS = "https://www.youtube.com";

    /** Свежий список ciadpi-стратегий (проверено 06.11.2026, обновлён 29–30.10.2026). */
    public static final String DEFAULT_DPI_STRATS_URL =
            "https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list";

    /** Допроверка запуска: загрузка 1 МБ за ≤ 2 с. Проверено 05.11.2026 (все — иностранные CDN). */
    public static final String[] DEFAULT_SPEED_GATE_URLS = {
            "https://speed.cloudflare.com/__down?bytes=1048576",
            "https://proof.ovh.net/files/1Mb.dat",
            "https://cachefly.cachefly.net/1mb.test",
    };

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String mode(Context c) { return get(c).getString(MODE, "dpi"); }
    public static void setMode(Context c, String m) { get(c).edit().putString(MODE, m).apply(); }

    public static String strategy(Context c) { return get(c).getString(BYEDPI_STRATEGY, DEFAULT_STRATEGY); }
    public static String byedpiDns(Context c) { return get(c).getString(BYEDPI_DNS, "1.1.1.1"); }
    public static String vpnDns(Context c) { return get(c).getString(VPN_DNS, "1.1.1.1"); }
    public static boolean ipv6(Context c) { return get(c).getBoolean(IPV6, false); }
    public static boolean autoSwitch(Context c) { return get(c).getBoolean(AUTO_SWITCH, true); }
    public static int switchTimeoutSec(Context c) { return get(c).getInt(SWITCH_TIMEOUT_SEC, 45); }
    public static int monitorIntervalSec(Context c) { return get(c).getInt(MONITOR_INTERVAL_SEC, 15); }
    public static int batchSize(Context c) { return clamp(get(c).getInt(BATCH_SIZE, 100), 10, 500); }
    public static int pingThreads(Context c) { return clamp(get(c).getInt(PING_THREADS, 100), 1, 200); }
    public static int pingTimeoutMs(Context c) { return clamp(get(c).getInt(PING_TIMEOUT_MS, 2500), 300, 15000); }
    public static String speedTestUrl(Context c) { return get(c).getString(SPEED_TEST_URL, DEFAULT_SPEED_URL); }
    public static int speedMinKbps(Context c) { return Math.max(0, get(c).getInt(SPEED_MIN_KBPS, 256)); }
    public static boolean quietUpdate(Context c) { return get(c).getBoolean(QUIET_UPDATE, false); }
    public static boolean vpnDesync(Context c) { return get(c).getBoolean(VPN_DESYNC, true); }
    public static void setVpnDesync(Context c, boolean v) { get(c).edit().putBoolean(VPN_DESYNC, v).apply(); }
    public static boolean vpnRace(Context c) { return get(c).getBoolean(VPN_RACE, true); }
    public static void setVpnRace(Context c, boolean v) { get(c).edit().putBoolean(VPN_RACE, v).apply(); }
    public static boolean autoUpdateOnStart(Context c) { return get(c).getBoolean(AUTO_UPDATE_ON_START, false); }
    public static void setAutoUpdateOnStart(Context c, boolean v) { get(c).edit().putBoolean(AUTO_UPDATE_ON_START, v).apply(); }
    public static boolean embeddedEnabled(Context c) { return get(c).getBoolean(EMBEDDED_ENABLED, true); }
    public static boolean vpnInsecure(Context c) { return get(c).getBoolean(VPN_INSECURE, false); }
    public static boolean bootConnect(Context c) { return get(c).getBoolean(BOOT_CONNECT, false); }

    public static List<String> sources(Context c) {
        String joined = get(c).getString(SOURCES, null);
        if (joined == null) joined = join(DEFAULT_SOURCES);
        else if (!get(c).getBoolean(SOURCE_MIG_017, false)) {
            // один раз: заменяем пустой WHITE-CIDR-RU-checked и дописываем белые списки
            List<String> lines = lines(joined);
            final String CIDR_ALL = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt";
            final String SNI_ALL = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-SNI-RU-all.txt";
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("WHITE-CIDR-RU-checked")) { lines.set(i, CIDR_ALL); changed = true; }
            }
            if (!joined.contains("WHITE-CIDR-RU-all") && !lines.contains(CIDR_ALL)) { lines.add(CIDR_ALL); changed = true; }
            if (!joined.contains("WHITE-SNI-RU-all")) { lines.add(SNI_ALL); changed = true; }
            if (changed) joined = join(lines);
            get(c).edit().putString(SOURCES, joined).putBoolean(SOURCE_MIG_017, true).apply();
        }
        return lines(joined);
    }

    public static List<String> pingTargets(Context c) {
        String joined = get(c).getString(PING_TARGETS, null);
        if (joined == null) joined = join(DEFAULT_PING_TARGETS);
        return lines(joined);
    }

    public static String githubHost(Context c) { return get(c).getString(GITHUB_HOST, "github.com").trim(); }

    public static List<String> githubFallbackIps(Context c) {
        String s = get(c).getString(GITHUB_FALLBACK_IPS, "140.82.112.3,140.82.113.3,140.82.114.4");
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static String torBridges(Context c) { return get(c).getString(TOR_BRIDGES, ""); }
    public static void setTorBridges(Context c, String v) { get(c).edit().putString(TOR_BRIDGES, v == null ? "" : v).apply(); }
    public static String torExtraTorrc(Context c) { return get(c).getString(TOR_EXTRA_TORRC, ""); }
    public static boolean torBuiltin(Context c) { return get(c).getBoolean(TOR_BUILTIN, true); }

    public static int lastSourceIndex(Context c) { return get(c).getInt(LAST_SOURCE_INDEX, 0); }
    public static void setLastSourceIndex(Context c, int i) { get(c).edit().putInt(LAST_SOURCE_INDEX, i).apply(); }

    public static int dpiStrategyIndex(Context c) { return get(c).getInt(DPI_STRATEGY_INDEX, -1); }
    public static void setDpiStrategyIndex(Context c, int i) { get(c).edit().putInt(DPI_STRATEGY_INDEX, i).apply(); }

    public static boolean dpiAutoSelect(Context c) { return get(c).getBoolean(DPI_AUTO_SELECT, true); }

    public static List<String> dpiTestUrls(Context c) {
        String raw = get(c).getString(DPI_TEST_URLS, DEFAULT_DPI_TEST_URLS);
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String line : raw.split("\\r?\\n|,")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (!t.contains("://")) t = "https://" + t;
                out.add(t);
            }
        }
        return out;
    }

    public static void setStrategy(Context c, String s2) { get(c).edit().putString(BYEDPI_STRATEGY, s2).apply(); }

    /** Источники допроверки соединения (по одному на строку). */
    public static List<String> speedGateUrls(Context c) {
        String raw = get(c).getString(SPEED_GATE_URLS, join(DEFAULT_SPEED_GATE_URLS));
        List<String> out = lines(raw);
        if (out.isEmpty()) {
            for (String u : DEFAULT_SPEED_GATE_URLS) out.add(u);
        }
        return out;
    }

    public static long torBridgesAutoAt(Context c) { return get(c).getLong(TOR_BRIDGES_AUTO_AT, 0L); }
    public static void setTorBridgesAutoAt(Context c, long t) { get(c).edit().putLong(TOR_BRIDGES_AUTO_AT, t).apply(); }

    public static String dpiStrategiesUrl(Context c) {
        String v = get(c).getString(DPI_STRATS_URL, DEFAULT_DPI_STRATS_URL);
        return v == null ? DEFAULT_DPI_STRATS_URL : v.trim();
    }
    public static void setDpiStrategiesUrl(Context c, String u) { get(c).edit().putString(DPI_STRATS_URL, u == null ? "" : u.trim()).apply(); }
    public static long dpiStrategiesAutoAt(Context c) { return get(c).getLong(DPI_STRATS_AUTO_AT, 0L); }
    public static void setDpiStrategiesAutoAt(Context c, long t) { get(c).edit().putLong(DPI_STRATS_AUTO_AT, t).apply(); }

    public static void setCurrentConfig(Context c, String raw, String name, String host, int port) {
        get(c).edit()
                .putString(CURRENT_CONFIG_RAW, raw)
                .putString(CURRENT_CONFIG_NAME, name)
                .putString(CURRENT_CONFIG_HOST, host)
                .putInt(CURRENT_CONFIG_PORT, port)
                .apply();
    }

    public static String currentConfigRaw(Context c) { return get(c).getString(CURRENT_CONFIG_RAW, ""); }
    public static String currentConfigName(Context c) { return get(c).getString(CURRENT_CONFIG_NAME, ""); }
    public static String currentConfigHost(Context c) { return get(c).getString(CURRENT_CONFIG_HOST, ""); }
    public static int currentConfigPort(Context c) { return get(c).getInt(CURRENT_CONFIG_PORT, 443); }

    public static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

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
}
