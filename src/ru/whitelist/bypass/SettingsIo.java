package ru.whitelist.bypass;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Экспорт и импорт ВСЕХ настроек одним .json, плюс сохранение консоли в .json.
 *
 * Зачем: настройки можно выгрузить, поправить руками (или через LLM), проверить глазами
 * и загрузить обратно на любом устройстве — без перещёлкивания десятков полей.
 * Импорт осторожный: применяются только известные ключи и только с совпадающим типом,
 * поэтому испорченный файл не может сломать приложение — максимум часть полей не применится.
 */
public final class SettingsIo {

    /** Только настоящие настройки. Служебное состояние (текущий конфиг, пул) не выгружаем. */
    private static final Set<String> SETTINGS = new HashSet<>(Arrays.asList(
            Prefs.SOURCES,
            Prefs.MIN_SPEED_KBIT,
            Prefs.SPEED_URLS,
            Prefs.PING_THREADS,
            Prefs.PING_TIMEOUT_MS,
            Prefs.PING_TARGETS,
            Prefs.HEALTH_URLS,
            Prefs.SPEED_LANES,
            Prefs.GATE_TIMEOUT_MS,
            Prefs.DESYNC_LINE,
            Prefs.DPI_STRATEGY,
            Prefs.DPI_SOURCES,
            Prefs.DPI_AUTOTUNE,
            Prefs.DPI_TEST_URLS,
            Prefs.VPN_DNS,
            Prefs.IPV6,
            Prefs.AUTO_SWITCH,
            Prefs.MONITOR_INTERVAL_SEC,
            Prefs.SWITCH_TIMEOUT_SEC,
            Prefs.BOOT_CONNECT,
            Prefs.EMBEDDED,
            Prefs.TOR_BRIDGES,
            Prefs.TOR_BRIDGE_WAIT_SEC,
            Prefs.TOR_EXTRA_TORRC,
            Prefs.TOR_FALLBACK));

    private SettingsIo() {
    }

    /** Имя версии приложения (для шапки файла), с запасным значением. */
    public static String appVersion(Context c) {
        try {
            return String.valueOf(c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            return "н/д";
        }
    }

    /** Все настройки одним JSON-объектом (ключи отсортированы — удобно сравнивать файлы). */
    public static String exportJson(Context c) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "Обход Белых Списков");
            root.put("kind", "settings");
            root.put("format", 1);
            root.put("version", appVersion(c));
            root.put("exportedAt", Console.stamp());

            Map<String, ?> all = new TreeMap<>(Prefs.get(c).getAll());
            JSONObject prefs = new JSONObject();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (!SETTINGS.contains(key)) continue;
                Object v = e.getValue();
                if (v instanceof String || v instanceof Integer
                        || v instanceof Long || v instanceof Boolean) {
                    prefs.put(key, v);
                }
            }
            root.put("prefs", prefs);

            JSONArray notes = new JSONArray();
            notes.put("Значения в «prefs» — то, что видно в настройках. Многие списки "
                    + "хранятся строками через перевод строки; это нормально.");
            notes.put("Импорт применяет только известные ключи и только с тем же типом "
                    + "значения. Лишние ключи игнорируются, ошибок в приложении не будет.");
            notes.put("Диапазоны: min_speed_kbit 8…160000; ping_timeout_ms 300…15000; "
                    + "gate_timeout_ms 800…20000; speed_lanes 1…4; tor_bridge_wait_sec 5…120; "
                    + "monitor_interval_sec 5…300; switch_timeout_sec 5…3600.");
            root.put("notes", notes);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** Человеческий итог импорта: что применили, что пропустили. */
    public static String importJson(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return "Файл пустой";
        JSONObject prefs;
        try {
            JSONObject root = new JSONObject(text);
            prefs = root.optJSONObject("prefs");
            if (prefs == null) prefs = root;  // допускаем «голый» список настроек
        } catch (Exception e) {
            return "Это не JSON: " + e.getMessage();
        }

        SharedPreferences sp = Prefs.get(c);
        SharedPreferences.Editor e = sp.edit();
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int wrongType = 0;

        for (java.util.Iterator<String> it = prefs.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value;
            try {
                value = prefs.get(key);
            } catch (Exception ex) {
                continue;
            }
            if ("error".equals(key) || "app".equals(key) || "kind".equals(key)
                    || "format".equals(key) || "version".equals(key)
                    || "exportedAt".equals(key) || "notes".equals(key)) {
                continue;
            }
            if (!SETTINGS.contains(key)) {
                skipped.add(key);
                continue;
            }
            Object old = sp.getAll().get(key);
            if (old != null && !sameKind(old, value)) {
                wrongType++;
                continue;
            }
            if (value instanceof String) {
                e.putString(key, (String) value);
            } else if (value instanceof Integer) {
                e.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                e.putLong(key, (Long) value);
            } else if (value instanceof Boolean) {
                e.putBoolean(key, (Boolean) value);
            } else {
                // числа из JSON бывают Double — приводим к int, если это целое
                if (value instanceof Number) {
                    e.putInt(key, (int) Math.round(((Number) value).doubleValue()));
                } else {
                    wrongType++;
                    continue;
                }
            }
            applied.add(key);
        }
        e.apply();

        StringBuilder sb = new StringBuilder();
        sb.append("Применено настроек: ").append(applied.size());
        if (!skipped.isEmpty()) {
            sb.append(" · пропущено неизвестных ключей: ").append(skipped.size());
        }
        if (wrongType > 0) {
            sb.append(" · не совпал тип: ").append(wrongType);
        }
        if (applied.isEmpty()) {
            sb.append(". Ничего не изменилось — проверьте, что это файл настроек "
                    + "приложения (ключ «prefs»).");
        } else {
            sb.append(". Перезапустите приложение, если что-то выглядит по-старому.");
        }
        return sb.toString();
    }

    private static boolean sameKind(Object a, Object b) {
        if (a instanceof Integer || a instanceof Long) {
            return b instanceof Number;
        }
        return a.getClass().isInstance(b);
    }

    /**
     * Консоль целиком в JSON: строки + склеенный текст. Такой файл удобно и читать,
     * и скармливать модели — ничего не режется и не теряется.
     */
    public static String consoleJson(Context c) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "Обход Белых Списков");
            root.put("kind", "console");
            root.put("format", 1);
            root.put("version", appVersion(c));
            root.put("savedAt", Console.stamp());
            root.put("lines", new JSONArray(Console.linesList()));
            root.put("text", Console.text());
            root.put("pool", Pool.count(c));
            return root.toString(2);
        } catch (Exception e) {
            return "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }
}
