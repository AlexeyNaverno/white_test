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
 * Правила:
 *  - в список попадает только «жёсткий» отказ (ядро/данные), но не «ниже порога»:
 *    медленный конфиг рабочий, просто медленный;
 *  - запись живёт {@link #TTL_MS} — через неделю конфиг перепроверяется: сервер мог
 *    ожить, а провайдер — перестать блокировать;
 *  - как только конфиг снова прошёл замер, запись снимается;
 *  - в настройках есть кнопка «Очистить список нерабочих конфигураций».
 *
 * Хранится отдельно от настроек (это данные, а не настройка) и в экспорт .json не попадает.
 */
public final class Blacklist {
    private Blacklist() {}

    private static final String FILE = "bypass_blacklist";
    private static final String KEY = "entries";

    /** Сколько запись считается приговором. Неделя: за это время многое меняется. */
    public static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    /** Потолок, чтобы файл не рос бесконечно. */
    private static final int MAX = 20000;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String keyOf(String raw) {
        if (raw == null) return null;
        String key = ConfigRepository.canonicalKey(raw);
        return key == null || key.isEmpty() ? null : key;
    }

    /** Живые записи: ключ → время, когда конфиг признали нерабочим. */
    private static Map<String, Long> load(Context c) {
        Map<String, Long> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        String data = prefs(c).getString(KEY, "");
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
            String key = line.substring(tab + 1).trim();
            if (!key.isEmpty()) out.put(key, at);
        }
        return out;
    }

    private static void store(Context c, Map<String, Long> entries) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Long> e : entries.entrySet()) {
            if (n++ >= MAX) break;
            sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        }
        prefs(c).edit().putString(KEY, sb.toString()).apply();
    }

    /** Запомнить, что конфиг не работает. Повод — короткая строка для отчёта. */
    public static synchronized void add(Context c, String raw, String reason) {
        String key = keyOf(raw);
        if (key == null) return;
        Map<String, Long> entries = load(c);
        entries.remove(key);
        entries.put(key, System.currentTimeMillis());
        store(c, entries);
    }

    public static synchronized void add(Context c, ProxyConfig cfg, String reason) {
        if (cfg != null) add(c, cfg.raw, reason);
    }

    /** Забыть конфиг (он снова заработал). */
    public static synchronized void forget(Context c, String raw) {
        String key = keyOf(raw);
        if (key == null) return;
        Map<String, Long> entries = load(c);
        if (entries.remove(key) != null) store(c, entries);
    }

    /** Ключи всех «приговорённых» конфигов — их не пингуем и не меряем. */
    public static Set<String> keys(Context c) {
        return new LinkedHashSet<>(load(c).keySet());
    }

    public static boolean contains(Context c, ProxyConfig cfg) {
        String key = cfg == null ? null : keyOf(cfg.raw);
        return key != null && load(c).containsKey(key);
    }

    public static int size(Context c) {
        return load(c).size();
    }

    /** Очистить целиком: «вдруг что-то из этого уже работает». */
    public static synchronized void clear(Context c) {
        prefs(c).edit().remove(KEY).apply();
    }

    /** Убрать из списка кандидатов всё, что уже признано нерабочим. */
    public static java.util.List<ProxyConfig> filter(Context c, java.util.List<ProxyConfig> in) {
        if (in == null) return new java.util.ArrayList<>();
        Set<String> keys = keys(c);
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
}
