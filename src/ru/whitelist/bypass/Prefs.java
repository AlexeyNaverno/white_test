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
    /** Порог «рабочего» конфига в КИЛОБИТАХ в секунду (128 по умолчанию = 16 КБ/с). */
    public static final String MIN_SPEED_KBIT = "min_speed_kbit";
    public static final String SPEED_URLS = "speed_urls_v4";
    public static final String PING_THREADS = "ping_threads";
    public static final String PING_TIMEOUT_MS = "ping_timeout_ms";
    /** Цели TCP-пинга «есть ли вообще интернет» (host:port, по одной на строку). */
    public static final String PING_TARGETS = "ping_targets";
    /** URL, по которым проверяется живой канал через туннель (5 мест). */
    public static final String HEALTH_URLS = "health_urls";
    /** Сколько экземпляров ядра мерить скорость одновременно. */
    public static final String SPEED_LANES = "speed_lanes";
    public static final String GATE_TIMEOUT_MS = "gate_timeout_ms";
    public static final String DESYNC_LINE = "desync_line";
    public static final String DPI_STRATEGY = "dpi_strategy_v3";
    /** Откуда брать список стратегий ciadpi (по одной на строку). */
    public static final String DPI_SOURCES = "dpi_sources";
    /** Подбирать рабочую стратегию автоматически (пробовать список до первой успешной). */
    public static final String DPI_AUTOTUNE = "dpi_autotune";
    /** Ссылки, по которым проверяется эффективность стратегии DPI. */
    public static final String DPI_TEST_URLS = "dpi_test_urls";
    public static final String VPN_DNS = "vpn_dns";
    public static final String IPV6 = "ipv6";
    public static final String AUTO_SWITCH = "auto_switch";
    public static final String MONITOR_INTERVAL_SEC = "monitor_interval_sec";
    public static final String SWITCH_TIMEOUT_SEC = "switch_timeout_sec";
    public static final String BOOT_CONNECT = "boot_connect";
    public static final String EMBEDDED = "embedded_enabled";
    public static final String TOR_BRIDGES = "tor_bridges";
    /** Замеры мостов: «мост<TAB>КБ/с<TAB>мс», лучшие сверху. */
    public static final String TOR_BRIDGE_RANK = "tor_bridge_rank";
    /**
     * Сколько секунд ждём подъёма Tor с ОДНИМ мостом при проверке мостов.
     * По умолчанию 10 с: живой мост поднимается за считанные секунды, а 45 с на мост
     * превращали «проверить мосты» в получасовое ожидание без результата.
     */
    public static final String TOR_BRIDGE_WAIT_SEC = "tor_bridge_wait_sec";
    /** Человеческий отчёт последней проверки мостов и источников через Tor. */
    public static final String TOR_REPORT = "tor_report";
    public static final String TOR_EXTRA_TORRC = "tor_extra_torrc";
    /** Если VPN не отвечает — пускать трафик через Tor-мосты (по умолчанию включено). */
    public static final String TOR_FALLBACK = "tor_fallback";
    public static final String CURRENT_RAW = "current_raw";
    public static final String CURRENT_NAME = "current_name";
    public static final String CURRENT_HOST = "current_host";
    public static final String CURRENT_PORT = "current_port";
    public static final String POOL_AT = "pool_at";

    /** Одна рабочая стратегия DPI — та, что подставляется в линию до VPN-сервера. */
    public static final String DEFAULT_STRATEGY = "--disorder 1 --auto=torst --tlsrec 1+s";

    /**
     * Списки проверенных аргументов ciadpi (ByeDPI). Берём из открытых репозиториев,
     * где авторы сами гоняют стратегии по блокировкам; поддерживаются оба формата —
     * короткий (`-d1 -s3+sm`) и длинный (`--disorder 1`).
     */
    public static final String[] DEFAULT_DPI_SOURCES = {
            "https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list",
            "https://raw.githubusercontent.com/alimali54/DPIscord/main/linux/byedpi/strategies.txt"
    };

    /** Через эти адреса проверяется, пробивает ли стратегия DPI блокировку. */
    public static final String[] DEFAULT_DPI_TEST_URLS = {
            "https://discord.com/",
            "https://www.youtube.com/generate_204",
            "https://www.instagram.com/"
    };

    /**
     * Источники списков (по одному на строку). Порядок важен: сначала идут небольшие
     * списки, которые уже проверены на пинг/скорость их авторами, потом массовые сборники,
     * и только в конце — самый большой файл (на телефоне до него обычно не доходит:
     * хранилище ограничено MAX_LINES).
     */
    public static final String[] DEFAULT_SOURCES = {
            // авторы уже проверили пинг/скорость — самые «плотные» списки
            "https://raw.githubusercontent.com/luxxuria/harvester/main/speed_tested.txt",
            "https://raw.githubusercontent.com/luxxuria/harvester/main/ping_tested.txt",
            "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/ru-sni-local/vless.txt",
            // массовые сборники под РФ
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
            "https://raw.githubusercontent.com/wlunlocker/vpn-configs/main/whitelist_all.txt",
            "https://raw.githubusercontent.com/ByeWhiteLists/ByeWhiteLists2/refs/heads/main/ByeWhiteLists2.txt",
            // JSON с результатами автопроверки ключей: ссылки вытаскиваются из него как есть
            "https://raw.githubusercontent.com/tiagorrg/vless-checker/main/docs/keys.json",
            // небольшой список «рабочих» прокси
            "https://raw.githubusercontent.com/26info/vless-proxy-list/main/working-proxies.txt",
            // общий большой сборник — последним
            "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/clean/vless.txt"
    };

    /**
     * Пять мест для TCP-пинга: если ответило хотя бы одно — сеть жива.
     * Проверяются параллельно, поэтому «медленный, но живой» канал виден сразу.
     */
    public static final String[] DEFAULT_PING_TARGETS = {
            "discord.com:443",
            "www.instagram.com:443",
            "x.com:443",
            "www.facebook.com:443",
            "www.youtube.com:443"
    };

    /**
     * Пять мест для проверки КАНАЛА через туннель (HTTP через локальный SOCKS).
     * Достаточно ответа любого — один сайт может быть недоступен по своим причинам.
     */
    public static final String[] DEFAULT_HEALTH_URLS = {
            "https://discord.com/",
            "https://www.instagram.com/",
            "https://x.com/",
            "https://www.youtube.com/",
            "https://speed.cloudflare.com/__down?bytes=65536"
    };

    /** Несколько резервных DNS: первый — основной, остальные — на случай перехвата. */
    public static final String DEFAULT_DNS = "1.1.1.1, 8.8.8.8, 9.9.9.9";

    /** Файлы по 1 МБ с иностранных CDN — по ним измеряется скорость канала. */
    /**
     * Чем меряем скорость: сначала настоящий файл со списком конфигов с GitHub — так замер
     * заодно доказывает, что через этот конфиг реально достаётся зарубежный источник
     * (именно за ним люди и идут). Если GitHub не отдался — небольшие файлы Cloudflare и
     * CacheFly, чтобы замер не зависел от одного сервиса.
     */
    public static final String[] DEFAULT_SPEED_URLS = {
            "https://raw.githubusercontent.com/luxxuria/harvester/main/speed_tested.txt",
            "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/clean/vless.txt",
            "https://speed.cloudflare.com/__down?bytes=131072",
            "http://cachefly.cachefly.net/100kb.test"
    };

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // --- скорость ---

    /** Нижняя граница скорости для «рабочего» конфига, КБ/с. 16 КБ/с (128 кбит/с) по умолчанию. */
    public static int minSpeedKbps(Context c) {
        long kbit = minSpeedKbit(c);
        return Math.max(1, Math.round(kbit / 8f));
    }

    /** Порог в кбит/с — как он задаётся в настройках. 128 кбит/с = 16 КБ/с. */
    public static int minSpeedKbit(Context c) {
        return clamp(get(c).getInt(MIN_SPEED_KBIT, 128), 8, 160000);
    }

    /** «128 кбит/с (16 КБ/с)» — порог в тех единицах, в которых он задан. */
    public static String minSpeedText(Context c) {
        int kbit = minSpeedKbit(c);
        return kbit + " кбит/с (" + Math.max(1, Math.round(kbit / 8f)) + " КБ/с)";
    }

    public static List<String> speedUrls(Context c) {
        String raw = get(c).getString(SPEED_URLS, join(DEFAULT_SPEED_URLS));
        List<String> out = lines(raw);
        if (out.isEmpty()) {
            for (String u : DEFAULT_SPEED_URLS) out.add(u);
        }
        return out;
    }

    /**
     * Сколько ждём на один замер, мс. По умолчанию 1500: рабочий конфиг отдаёт первые
     * килобайты почти сразу, а ожидание «вдруг разгонится» только растягивает сбор пула.
     */
    public static int gateTimeoutMs(Context c) {
        return clamp(get(c).getInt(GATE_TIMEOUT_MS, 1500), 800, 20000);
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

    /** Таймаут пинга, мс. 1000 по умолчанию: нас интересуют только «более-менее рабочие». */
    public static int pingTimeoutMs(Context c) {
        return clamp(get(c).getInt(PING_TIMEOUT_MS, 1000), 300, 15000);
    }

    /** Цели TCP-пинга (host:port). Пусто/битые строки отбрасываются. */
    public static List<String> pingTargets(Context c) {
        List<String> out = lines(get(c).getString(PING_TARGETS, join(DEFAULT_PING_TARGETS)));
        if (out.isEmpty()) {
            for (String t : DEFAULT_PING_TARGETS) out.add(t);
        }
        return out;
    }

    /** URL проверки канала через туннель. */
    public static List<String> healthUrls(Context c) {
        List<String> out = lines(get(c).getString(HEALTH_URLS, join(DEFAULT_HEALTH_URLS)));
        if (out.isEmpty()) {
            for (String u : DEFAULT_HEALTH_URLS) out.add(u);
        }
        return out;
    }

    /** Сколько экземпляров ядра мерить скорость параллельно. */
    public static int speedLanes(Context c) {
        return clamp(get(c).getInt(SPEED_LANES, 4), 1, 4);
    }

    /** «512 КБ/с (4096 кбит/с)» — чтобы единицы не путались. */
    public static String speedText(long kbps) {
        if (kbps <= 0) return "—";
        return kbps + " КБ/с (" + (kbps * 8) + " кбит/с)";
    }

    /** «128 кбит/с (16 КБ/с)» из значения в кбит/с. */
    public static String kbitText(long kbit) {
        if (kbit <= 0) return "—";
        return kbit + " кбит/с (" + Math.max(1, Math.round(kbit / 8f)) + " КБ/с)";
    }

    // --- DPI на линии ---

    /** Пробовать DPI-обход линии (ciadpi) для конфигов, не вышедших напрямую. */
    /** DPI-обход линии по умолчанию выключен: включается галочкой в настройках. */
    public static boolean desyncLine(Context c) {
        return get(c).getBoolean(DESYNC_LINE, false);
    }

    public static String dpiStrategy(Context c) {
        String s = get(c).getString(DPI_STRATEGY, DEFAULT_STRATEGY);
        return s == null || s.trim().isEmpty() ? DEFAULT_STRATEGY : s.trim();
    }

    public static List<String> dpiSources(Context c) {
        List<String> out = lines(get(c).getString(DPI_SOURCES, join(DEFAULT_DPI_SOURCES)));
        if (out.isEmpty()) {
            for (String u : DEFAULT_DPI_SOURCES) out.add(u);
        }
        return out;
    }

    /** Автоподбор стратегий тоже только вместе с DPI-обходом: без него он не запускается. */
    public static boolean dpiAutotune(Context c) {
        return get(c).getBoolean(DPI_AUTOTUNE, false);
    }

    /** Адреса, по которым проверяется, пробивает ли стратегия DPI блокировку. */
    public static List<String> dpiTestUrls(Context c) {
        List<String> out = lines(get(c).getString(DPI_TEST_URLS, join(DEFAULT_DPI_TEST_URLS)));
        if (out.isEmpty()) {
            for (String u : DEFAULT_DPI_TEST_URLS) out.add(u);
        }
        return out;
    }

    public static void setDpiStrategy(Context c, String s) {
        get(c).edit().putString(DPI_STRATEGY, s == null ? "" : s.trim()).apply();
    }

    // --- сеть ---

    public static String vpnDns(Context c) {
        String v = get(c).getString(VPN_DNS, DEFAULT_DNS);
        v = v == null ? "" : v.trim();
        return v.isEmpty() ? DEFAULT_DNS : v;
    }

    /** DNS-серверы списком (запятая/точка с запятой/пробел/перевод строки). */
    public static List<String> vpnDnsServers(Context c) {
        List<String> out = new ArrayList<>();
        for (String p : vpnDns(c).split("[,;\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        if (out.isEmpty()) out.add("1.1.1.1");
        return out;
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

    public static String torBridgeRank(Context c) {
        return get(c).getString(TOR_BRIDGE_RANK, "");
    }

    public static void setTorBridgeRank(Context c, String v) {
        get(c).edit().putString(TOR_BRIDGE_RANK, v == null ? "" : v).apply();
    }

    public static String torReport(Context c) {
        return get(c).getString(TOR_REPORT, "");
    }

    public static void setTorReport(Context c, String v) {
        get(c).edit().putString(TOR_REPORT, v == null ? "" : v).apply();
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

    /** Лимит ожидания одного моста, мс (10 с по умолчанию, настраивается). */
    public static int torBridgeWaitMs(Context c) {
        return clamp(get(c).getInt(TOR_BRIDGE_WAIT_SEC, 10), 5, 120) * 1000;
    }

    /** Лимит ожидания одного моста в секундах — как он задан в настройках. */
    public static int torBridgeWaitSec(Context c) {
        return clamp(get(c).getInt(TOR_BRIDGE_WAIT_SEC, 10), 5, 120);
    }

    // --- резервный режим через Tor ---

    /**
     * Если VPN-конфиги не отвечают — пробовать пустить трафик через Tor-мосты.
     * По умолчанию включено: лучше медленный, но работающий канал, чем «нет связи».
     */
    public static boolean torFallback(Context c) {
        return get(c).getBoolean(TOR_FALLBACK, true);
    }

    /**
     * Разовая миграция значений, которые раньше записывались как «дефолт».
     * Настройки сохраняют ВСЕ поля при нажатии «Сохранить», поэтому у тех, кто хоть раз
     * открывал настройки, в файле лежали старые 4000/2500 мс — без миграции новые
     * значения по умолчанию до них бы не доехали.
     */
    public static void migrate(Context c) {
        SharedPreferences p = get(c);
        if (p.getBoolean("migrated_timeouts_v2", false)) return;
        SharedPreferences.Editor e = p.edit();
        if (p.getInt(GATE_TIMEOUT_MS, 0) == 4000) e.putInt(GATE_TIMEOUT_MS, 1500);
        if (p.getInt(PING_TIMEOUT_MS, 0) == 2500) e.putInt(PING_TIMEOUT_MS, 1000);
        e.putBoolean("migrated_timeouts_v2", true);
        e.apply();
    }

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
