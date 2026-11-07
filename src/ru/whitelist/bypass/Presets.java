package ru.whitelist.bypass;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Наборы настроек: готовые пресеты и те, что пользователь импортировал из .json.
 *
 * Зачем: одни и те же значения подходят не всем. Дома по Wi-Fi нужен быстрый замер без
 * DPI-возни; у мобильного оператора с DPI без обхода линии вообще ничего не поднимается;
 * а там, где провайдер пускает только по белому списку, важнее низкий порог скорости и
 * «белые» источники. Поэтому три готовых набора + любые свои, сохранённые из .json.
 *
 * Хранилище своих наборов — обычные .json в files/presets: тот же формат, что у экспорта
 * настроек, поэтому файл можно выгрузить, поправить и загрузить назад.
 */
public final class Presets {

    public static final String ID_STANDARD = "standard";
    public static final String ID_WIFI = "wifi";
    public static final String ID_WHITE = "whitelist";

    /** Сколько своих наборов держим: больше — уже свалка, а не выбор. */
    private static final int MAX_CUSTOM = 12;

    private Presets() {
    }

    /** Набор настроек. prefs — те же ключи и типы, что в экспорте .json. */
    public static final class Preset {
        public final String id;
        public final String title;
        public final String note;
        public final JSONObject prefs;
        public final boolean builtin;

        Preset(String id, String title, String note, JSONObject prefs, boolean builtin) {
            this.id = id;
            this.title = title;
            this.note = note;
            this.prefs = prefs == null ? new JSONObject() : prefs;
            this.builtin = builtin;
        }

        /** Заголовок строкой: «Стандарт — с DPI-обходом (рекомендуется)». */
        @Override public String toString() {
            return title;
        }
    }

    // ---------------------------------------------------------------- готовые наборы

    /**
     * «Стандарт» — значения по умолчанию приложения: DPI-обход линии включён, автоподбор
     * стратегий включён, порог 32 кбит/с, окно замера 3000 мс, пинг 2000 мс, лёгкие
     * generate_204 вместо тяжёлых страниц.
     */
    public static Preset standard() {
        JSONObject p = new JSONObject();
        try {
            p.put(Prefs.DESYNC_LINE, true);
            p.put(Prefs.DPI_AUTOTUNE, true);
            p.put(Prefs.DPI_STRATEGY, Prefs.DEFAULT_STRATEGY);
            p.put(Prefs.DPI_SOURCES, Prefs.join(Prefs.DEFAULT_DPI_SOURCES));
            p.put(Prefs.DPI_TEST_URLS, Prefs.join(Prefs.DEFAULT_DPI_TEST_URLS));
            p.put(Prefs.SOURCES, Prefs.join(Prefs.DEFAULT_SOURCES));
            p.put(Prefs.SPEED_URLS, Prefs.join(Prefs.DEFAULT_SPEED_URLS));
            p.put(Prefs.HEALTH_URLS, Prefs.join(Prefs.DEFAULT_HEALTH_URLS));
            p.put(Prefs.PING_TARGETS, Prefs.join(Prefs.DEFAULT_PING_TARGETS));
            p.put(Prefs.MIN_SPEED_KBIT, Prefs.DEFAULT_MIN_SPEED_KBIT);
            p.put(Prefs.GATE_TIMEOUT_MS, Prefs.DEFAULT_GATE_TIMEOUT_MS);
            p.put(Prefs.PING_TIMEOUT_MS, Prefs.DEFAULT_PING_TIMEOUT_MS);
            p.put(Prefs.SWITCH_TIMEOUT_SEC, Prefs.DEFAULT_SWITCH_TIMEOUT_SEC);
            p.put(Prefs.PING_THREADS, 60);
            p.put(Prefs.SPEED_LANES, 4);
            p.put(Prefs.MONITOR_INTERVAL_SEC, 15);
            p.put(Prefs.AUTO_SWITCH, true);
            p.put(Prefs.BOOT_CONNECT, false);
            p.put(Prefs.EMBEDDED, true);
            p.put(Prefs.IPV6, false);
            p.put(Prefs.VPN_DNS, Prefs.DEFAULT_DNS);
            p.put(Prefs.TOR_FALLBACK, true);
            p.put(Prefs.TOR_BRIDGE_WAIT_SEC, 10);
            p.put(Prefs.TOR_BRIDGES, "");
            p.put(Prefs.TOR_EXTRA_TORRC, "");
        } catch (Exception ignored) {
        }
        return new Preset(ID_STANDARD, "Стандарт — с DPI-обходом (рекомендуется)",
                "Всё как «из коробки» 0.1.4: DPI-обход линии и автоподбор стратегий включены, "
                        + "порог 32 кбит/с, окно замера 3 с, пинг 2 с, лёгкие generate_204. "
                        + "Дома по Wi-Fi тоже работает, просто с лишним обходом.",
                p, true);
    }

    /** Старые значения 0.1.3: без DPI, порог 128 кбит/с, быстрый замер. */
    private static final String[] WIFI_SOURCES = {
            "https://raw.githubusercontent.com/luxxuria/harvester/main/speed_tested.txt",
            "https://raw.githubusercontent.com/luxxuria/harvester/main/ping_tested.txt",
            "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/ru-sni-local/vless.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
            "https://raw.githubusercontent.com/wlunlocker/vpn-configs/main/whitelist_all.txt",
            "https://raw.githubusercontent.com/ByeWhiteLists/ByeWhiteLists2/refs/heads/main/ByeWhiteLists2.txt",
            "https://raw.githubusercontent.com/tiagorrg/vless-checker/main/docs/keys.json",
            "https://raw.githubusercontent.com/26info/vless-proxy-list/main/working-proxies.txt",
            "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/clean/vless.txt"
    };

    private static final String WIFI_STRATEGY = "--disorder 1 --auto=torst --tlsrec 1+s";

    private static final String[] WIFI_SPEED_URLS = {
            "https://raw.githubusercontent.com/luxxuria/harvester/main/speed_tested.txt",
            "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/clean/vless.txt",
            "https://speed.cloudflare.com/__down?bytes=131072",
            "http://cachefly.cachefly.net/100kb.test"
    };

    private static final String[] WIFI_HEALTH_URLS = {
            "https://discord.com/",
            "https://www.instagram.com/",
            "https://x.com/",
            "https://www.youtube.com/",
            "https://speed.cloudflare.com/__down?bytes=65536"
    };

    /**
     * «Wi-Fi» — поведение 0.1.3: без DPI-обхода и автоподбора, порог 128 кбит/с, окно замера
     * 1500 мс, пинг 1000 мс, «тяжёлые» адреса проверки. Это самый быстрый сбор пула, но на
     * мобильном операторе с DPI он часто не поднимает ничего.
     */
    public static Preset wifi() {
        JSONObject p = new JSONObject();
        try {
            p.put(Prefs.DESYNC_LINE, false);
            p.put(Prefs.DPI_AUTOTUNE, false);
            p.put(Prefs.DPI_STRATEGY, WIFI_STRATEGY);
            p.put(Prefs.DPI_SOURCES, Prefs.join(Prefs.DEFAULT_DPI_SOURCES));
            p.put(Prefs.DPI_TEST_URLS, Prefs.join(Prefs.DEFAULT_DPI_TEST_URLS));
            p.put(Prefs.SOURCES, Prefs.join(WIFI_SOURCES));
            p.put(Prefs.SPEED_URLS, Prefs.join(WIFI_SPEED_URLS));
            p.put(Prefs.HEALTH_URLS, Prefs.join(WIFI_HEALTH_URLS));
            p.put(Prefs.PING_TARGETS, Prefs.join(Prefs.DEFAULT_PING_TARGETS));
            p.put(Prefs.MIN_SPEED_KBIT, 128);
            p.put(Prefs.GATE_TIMEOUT_MS, 1500);
            p.put(Prefs.PING_TIMEOUT_MS, 1000);
            p.put(Prefs.SWITCH_TIMEOUT_SEC, 45);
            p.put(Prefs.PING_THREADS, 60);
            p.put(Prefs.SPEED_LANES, 4);
            p.put(Prefs.MONITOR_INTERVAL_SEC, 15);
            p.put(Prefs.AUTO_SWITCH, true);
            p.put(Prefs.BOOT_CONNECT, false);
            p.put(Prefs.EMBEDDED, true);
            p.put(Prefs.IPV6, false);
            p.put(Prefs.VPN_DNS, Prefs.DEFAULT_DNS);
            p.put(Prefs.TOR_FALLBACK, true);
            p.put(Prefs.TOR_BRIDGE_WAIT_SEC, 10);
            p.put(Prefs.TOR_BRIDGES, "");
            p.put(Prefs.TOR_EXTRA_TORRC, "");
        } catch (Exception ignored) {
        }
        return new Preset(ID_WIFI, "Wi-Fi — без DPI, самый быстрый замер",
                "Поведение 0.1.3: DPI-обход и автоподбор выключены, порог 128 кбит/с, замер "
                        + "1500 мс, пинг 1000 мс. Сбор пула идёт быстрее всего, но у оператора "
                        + "с DPI конфиги часто не поднимаются.", p, true);
    }

    /**
     * «Белые списки» — для провайдеров, где наружу пускают только разрешённые адреса:
     * источники только «белые» (в адресе есть white/whitelist), порог скорости ещё ниже,
     * дорожек меньше (канал узкий, делить его на четыре смысла нет), DPI-обход выключен —
     * здесь он обычно только мешает.
     */
    public static Preset whitelist() {
        List<String> white = new ArrayList<>();
        for (String u : Prefs.DEFAULT_SOURCES) {
            String l = u.toLowerCase();
            if (l.contains("white")) white.add(u);
        }
        String[] speed = white.isEmpty()
                ? new String[]{"https://raw.githubusercontent.com/wlunlocker/vpn-configs/main/whitelist_all.txt"}
                : white.toArray(new String[0]);

        JSONObject p = new JSONObject();
        try {
            p.put(Prefs.DESYNC_LINE, false);
            p.put(Prefs.DPI_AUTOTUNE, false);
            p.put(Prefs.DPI_STRATEGY, Prefs.DEFAULT_STRATEGY);
            p.put(Prefs.DPI_SOURCES, Prefs.join(Prefs.DEFAULT_DPI_SOURCES));
            p.put(Prefs.DPI_TEST_URLS, Prefs.join(Prefs.DEFAULT_DPI_TEST_URLS));
            p.put(Prefs.SOURCES, Prefs.join(white));
            p.put(Prefs.SPEED_URLS, Prefs.join(speed));
            p.put(Prefs.HEALTH_URLS, Prefs.join(speed));
            p.put(Prefs.PING_TARGETS, Prefs.join(Prefs.DEFAULT_PING_TARGETS));
            p.put(Prefs.MIN_SPEED_KBIT, 16);
            p.put(Prefs.GATE_TIMEOUT_MS, 4000);
            p.put(Prefs.PING_TIMEOUT_MS, 2000);
            p.put(Prefs.SWITCH_TIMEOUT_SEC, 30);
            p.put(Prefs.PING_THREADS, 40);
            p.put(Prefs.SPEED_LANES, 2);
            p.put(Prefs.MONITOR_INTERVAL_SEC, 20);
            p.put(Prefs.AUTO_SWITCH, true);
            p.put(Prefs.BOOT_CONNECT, false);
            p.put(Prefs.EMBEDDED, true);
            p.put(Prefs.IPV6, false);
            p.put(Prefs.VPN_DNS, Prefs.DEFAULT_DNS);
            p.put(Prefs.TOR_FALLBACK, true);
            p.put(Prefs.TOR_BRIDGE_WAIT_SEC, 10);
            p.put(Prefs.TOR_BRIDGES, "");
            p.put(Prefs.TOR_EXTRA_TORRC, "");
        } catch (Exception ignored) {
        }
        return new Preset(ID_WHITE, "Белые списки — низкий порог, только white-источники",
                "Источники только «белые» (в адресе есть white), порог 16 кбит/с, дорожек 2, "
                        + "окно замера 4 с, монитор 20 с. DPI-обход выключен: при белых списках "
                        + "он обычно мешает, а не помогает.", p, true);
    }

    public static List<Preset> builtin() {
        List<Preset> out = new ArrayList<>();
        out.add(standard());
        out.add(wifi());
        out.add(whitelist());
        return out;
    }

    /** Всё, что можно выбрать: три готовых набора, затем свои из .json (свежие первыми). */
    public static List<Preset> all(Context c) {
        List<Preset> out = builtin();
        out.addAll(custom(c));
        return out;
    }

    public static Preset byId(Context c, String id) {
        for (Preset p : all(c)) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- применение

    /** Применить набор: значения пишутся в настройки, как при импорте .json. */
    public static String apply(Context c, Preset p) {
        if (p == null) return "Набор не выбран";
        SettingsIo.Result r = SettingsIo.applyPrefs(c, p.prefs);
        return "Применён набор «" + p.title + "»: " + r.text();
    }

    /** Применить набор «Стандарт» (используется миграцией и кнопкой «Сбросить»). */
    public static String applyStandard(Context c) {
        SettingsIo.Result r = SettingsIo.applyPrefs(c, standard().prefs);
        return r.text();
    }

    /**
     * Похожи ли настройки на «нетронутые» значения 0.1.3. Нужно миграции: если человек ничего
     * не настраивал, ему можно смело поставить новый набор «Стандарт», а если настраивал —
     * его значения не трогаем и просто показываем наборы в списке.
     */
    static boolean looksUntouched(android.content.SharedPreferences p) {
        if (p.getBoolean(Prefs.DESYNC_LINE, false)) return false;
        if (p.getBoolean(Prefs.DPI_AUTOTUNE, false)) return false;
        if (p.getInt(Prefs.MIN_SPEED_KBIT, 0) != 128) return false;
        if (p.getInt(Prefs.GATE_TIMEOUT_MS, 0) != 1500) return false;
        if (p.getInt(Prefs.PING_TIMEOUT_MS, 0) != 1000) return false;
        if (p.getInt(Prefs.SWITCH_TIMEOUT_SEC, 0) != 45) return false;
        String sources = p.getString(Prefs.SOURCES, Prefs.join(WIFI_SOURCES));
        if (!Prefs.lines(sources).equals(Prefs.lines(Prefs.join(WIFI_SOURCES)))) return false;
        String speed = p.getString(Prefs.SPEED_URLS, Prefs.join(WIFI_SPEED_URLS));
        if (!Prefs.lines(speed).equals(Prefs.lines(Prefs.join(WIFI_SPEED_URLS)))) return false;
        String health = p.getString(Prefs.HEALTH_URLS, Prefs.join(WIFI_HEALTH_URLS));
        if (!Prefs.lines(health).equals(Prefs.lines(Prefs.join(WIFI_HEALTH_URLS)))) return false;
        String strategy = p.getString(Prefs.DPI_STRATEGY, WIFI_STRATEGY);
        return WIFI_STRATEGY.equals(strategy == null ? "" : strategy.trim());
    }

    // ---------------------------------------------------------------- свои наборы

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "presets");
        if (!d.exists() && !d.mkdirs()) return d;
        return d;
    }

    /** Свои наборы из files/presets (тот же формат, что экспорт настроек). */
    public static List<Preset> custom(Context c) {
        List<Preset> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                String text = read(f);
                JSONObject root = new JSONObject(text);
                JSONObject prefs = root.optJSONObject("prefs");
                if (prefs == null) prefs = root;
                String title = titleFor(root);
                out.add(new Preset("file:" + f.getName(), title,
                        "Сохранён при импорте .json. " + f.getName(), prefs, false));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * Сохранить импортированный .json как дополнительный набор (его видно в списке рядом
     * с готовыми). Возвращает заголовок набора или null, если сохранять нечего.
     */
    public static String saveCustom(Context c, String jsonText) {
        if (jsonText == null || jsonText.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(jsonText);
            if (root.optJSONObject("prefs") == null && root.length() == 0) return null;
        } catch (Exception e) {
            return null;
        }
        // Тот же файл второй раз не дублируем: список должен оставаться списком.
        File[] existing = dir(c).listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    if (read(f).trim().equals(jsonText.trim())) {
                        return titleFor(new JSONObject(jsonText));
                    }
                } catch (Exception ignored) {
                }
            }
        }

        List<Preset> have = custom(c);
        if (have.size() >= MAX_CUSTOM) {
            // самый старый убираем: список должен оставаться списком, а не архивом
            Preset oldest = have.get(have.size() - 1);
            if (oldest.id.startsWith("file:")) {
                delete(c, oldest);
            }
        }
        String title;
        try {
            title = titleFor(new JSONObject(jsonText));
        } catch (Exception e) {
            title = "Мой из .json";
        }
        String name = "preset-" + System.currentTimeMillis() + ".json";
        try (OutputStream out = new FileOutputStream(new File(dir(c), name))) {
            out.write(jsonText.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        return title;
    }

    /** Заголовок своего набора: из файла (presetTitle/exportedAt), иначе «Мой из .json». */
    private static String titleFor(JSONObject root) {
        String title = root.optString("presetTitle", "");
        if (!title.isEmpty()) return title;
        String stamp = root.optString("exportedAt", "");
        if (!stamp.isEmpty()) return "Мой из .json · " + stamp.replace('T', ' ');
        String now = new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date());
        return "Мой из .json · " + now;
    }

    /** Удалить свой набор (готовые удалить нельзя). */
    public static boolean delete(Context c, Preset p) {
        if (p == null || p.builtin || !p.id.startsWith("file:")) return false;
        File f = new File(dir(c), p.id.substring("file:".length()));
        return f.delete();
    }

    /** Свои наборы в виде JSON-массива — для экспорта настроек (перенос на другое устройство). */
    public static JSONArray customAsJson(Context c) {
        JSONArray arr = new JSONArray();
        for (Preset p : custom(c)) {
            try {
                JSONObject o = new JSONObject();
                o.put("presetTitle", p.title);
                o.put("prefs", p.prefs);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static String read(File f) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
