package ru.whitelist.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Чёрный список конфигов: те, что уже проверяли и они не работали.
 *
 * Зачем: публичные списки на 2000+ строк, из них заведомо мёртвых — половина. Каждый
 * сбор пула и каждый быстрый поиск заново пингует их все, и время уходит на то, что
 * в прошлый раз не сработало по одной и той же причине («ядро не запустилось»,
 * «SOCKS не открылся», «нет данных»).
 *
 * С 0.1.15 список РАЗДЕЛЁН ПО СЕТИ, как и пул: у оператора и у домашнего Wi-Fi разные
 * блокировки, и конфиг, который не поднялся в мобильной сети, дома часто работает —
 * держать его в одном общем «приговоре» значило терять рабочие серверы.
 *   bypass_blacklist / entries_wifi — нерабочие в Wi-Fi;
 *   bypass_blacklist / entries_lte  — нерабочие в мобильном интернете.
 * Старый общий ключ entries при первом обращении переносится в мобильный список:
 * сбор пула исторически шёл в основном через мобильный интернет.
 *
 * Правила:
 *  - в список попадает только «жёсткий» отказ (ядро/данные/нет скорости), но не
 *    «ниже порога»: медленный конфиг рабочий, просто медленный;
 *  - запись живёт {@link #TTL_MS} — через неделю конфиг перепроверяется: сервер мог
 *    ожить, а провайдер — перестать блокировать;
 *  - как только конфиг снова прошёл замер, запись снимается;
 *  - в настройках есть кнопка «Очистить список нерабочих конфигураций» (оба списка).
 *
 * Хранится отдельно от настроек (это данные, а не настройка) и в экспорт .json не попадает.
 */
public final class Blacklist {
    private Blacklist() {}

    private static final String FILE = "bypass_blacklist";
    private static final String KEY_LEGACY = "entries";
    private static final String KEY_WIFI = "entries_wifi";
    private static final String KEY_LTE = "entries_lte";

    /** Сколько запись считается приговором. Неделя: за это время многое меняется. */
    public static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    /** Потолок на сеть, чтобы файл не рос бесконечно. */
    private static final int MAX = 20000;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String keyFor(String net) {
        return Net.WIFI.equals(Net.normalize(net)) ? KEY_WIFI : KEY_LTE;
    }

    private static String keyOf(String raw) {
        if (raw == null) return null;
        String key = ConfigRepository.canonicalKey(raw);
        return key == null || key.isEmpty() ? null : key;
    }

    /**
     * Ключи, нерабочие в ОБЕИХ сетях.
     *
     * Нужны для скачанного файла списков: он один на Wi-Fi и мобильный. Вычёркивать из
     * него конфиг, не поднявшийся только у оператора, нельзя — дома он может работать,
     * и следующая сборка пула в Wi-Fi его уже не увидит. Поэтому файл чистится только от
     * того, что доказанно мёртвое в обеих сетях; остальное отсекается при сборке пула
     * через {@link #filter(Context, String, java.util.List)}.
     */
    public static Set<String> keysBoth(Context c) {
        Set<String> out = new LinkedHashSet<>(load(c, Net.WIFI).keySet());
        out.retainAll(load(c, Net.MOBILE).keySet());
        return out;
    }

    /**
     * Первый заход после 0.1.15: старый общий список отдаём мобильному, а не Wi-Fi —
     * прежние проверки шли в основном через мобильный интернет, и приписывать их дому
     * было бы враньём.
     */
    private static void migrateLegacy(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("migrated_net_v1", false)) return;
        SharedPreferences.Editor e = p.edit();
        String legacy = p.getString(KEY_LEGACY, null);
        if (legacy != null && !legacy.isEmpty()) {
            if (!p.contains(KEY_LTE)) e.putString(KEY_LTE, legacy);
            e.remove(KEY_LEGACY);
        }
        e.putBoolean("migrated_net_v1", true);
        e.apply();
    }

    /** Живые записи указанной сети: ключ → время, когда конфиг признали нерабочим. */
    private static Map<String, Long> load(Context c, String net) {
        migrateLegacy(c);
        SharedPreferences p = prefs(c);
        Map<String, Long> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        String data = p.getString(keyFor(net), "");
        if (data == null || data.isEmpty()) return out;
        for (String line : data.split("\n")) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            long at;
            try {
                at = Long.parseLong(line.substring(0, tab).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (now - at > TTL_MS) continue;   // пора перепроверить
            String entry = line.substring(tab + 1).trim();
            if (!entry.isEmpty()) out.put(entry, at);
        }
        return out;
    }

    private static void store(Context c, String net, Map<String, Long> entries) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Long> e : entries.entrySet()) {
            if (n++ >= MAX) break;
            sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        }
        prefs(c).edit().putString(keyFor(net), sb.toString()).apply();
    }

    // ------------------------------------------------------------------ запись

    /** Запомнить, что конфиг не работает в текущей сети. */
    public static void add(Context c, String raw, String reason) {
        add(c, Net.key(c), raw, reason);
    }

    public static void add(Context c, ProxyConfig cfg, String reason) {
        if (cfg != null) add(c, Net.key(c), cfg.raw, reason);
    }

    /** То же, но явно для пула/сети: Wi-Fi и мобильный судят конфиги по отдельности. */
    public static synchronized void add(Context c, String net, String raw, String reason) {
        String key = keyOf(raw);
        if (key == null) return;
        Map<String, Long> entries = load(c, net);
        entries.remove(key);
        entries.put(key, System.currentTimeMillis());
        store(c, net, entries);
    }

    public static void add(Context c, String net, ProxyConfig cfg, String reason) {
        if (cfg != null) add(c, net, cfg.raw, reason);
    }

    /** Забыть конфиг (он снова заработал) в текущей сети. */
    public static void forget(Context c, String raw) {
        forget(c, Net.key(c), raw);
    }

    public static synchronized void forget(Context c, String net, String raw) {
        String key = keyOf(raw);
        if (key == null) return;
        Map<String, Long> entries = load(c, net);
        if (entries.remove(key) != null) store(c, net, entries);
    }

    // ------------------------------------------------------------------ чтение

    /** Ключи «приговорённых» конфигов в текущей сети. */
    public static Set<String> keys(Context c) {
        return keys(c, Net.key(c));
    }

    public static Set<String> keys(Context c, String net) {
        return new LinkedHashSet<>(load(c, net).keySet());
    }

    public static boolean contains(Context c, ProxyConfig cfg) {
        String key = cfg == null ? null : keyOf(cfg.raw);
        return key != null && load(c, Net.key(c)).containsKey(key);
    }

    public static int size(Context c) {
        return size(c, Net.key(c));
    }

    public static int size(Context c, String net) {
        return load(c, net).size();
    }

    /** Убрать из списка кандидатов всё, что уже признано нерабочим (текущая сеть). */
    public static java.util.List<ProxyConfig> filter(Context c, java.util.List<ProxyConfig> in) {
        return filter(c, Net.key(c), in);
    }

    public static java.util.List<ProxyConfig> filter(Context c, String net,
                                                     java.util.List<ProxyConfig> in) {
        if (in == null) return new java.util.ArrayList<>();
        Set<String> keys = keys(c, net);
        if (keys.isEmpty()) return in;
        java.util.List<ProxyConfig> out = new java.util.ArrayList<>(in.size());
        for (ProxyConfig cfg : in) {
            if (cfg == null) continue;
            String key = keyOf(cfg.raw);
            if (key != null && keys.contains(key)) continue;
            out.add(cfg);
        }
        return out;
    }

    // ------------------------------------------------------------------ очистка

    /** Очистить список текущей сети. */
    public static synchronized void clear(Context c) {
        clear(c, Net.key(c));
    }

    public static synchronized void clear(Context c, String net) {
        prefs(c).edit().remove(keyFor(net)).apply();
    }

    /** Очистить оба списка сразу — то, что делает кнопка в настройках. */
    public static synchronized void clearAll(Context c) {
        prefs(c).edit().remove(KEY_WIFI).remove(KEY_LTE).remove(KEY_LEGACY).apply();
    }

    /** Сколько записей всего по обеим сетям. */
    public static int sizeAll(Context c) {
        return load(c, Net.WIFI).size() + load(c, Net.MOBILE).size();
    }

    /** «Wi-Fi 12 · мобильный 30» — для строки в настройках. */
    public static String summary(Context c) {
        return "Wi-Fi " + load(c, Net.WIFI).size() + " · мобильный " + load(c, Net.MOBILE).size();
    }
}
