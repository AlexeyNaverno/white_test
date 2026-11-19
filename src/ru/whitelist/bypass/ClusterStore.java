package ru.whitelist.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Вердикты кластеров (провайдеров) — отдельно для Wi-Fi и мобильного, как пул и ЧС.
 *
 * Зачем отдельное хранилище. Провалился не конфиг, а ПРОВАЙДЕР: сервер выключен, домен
 * протух, оператор режет подсеть. Держать в чёрном списке сотню строк одного такого
 * провайдера бессмысленно и вредно: они всплывают в каждом прогоне. Здесь живёт один
 * вердикт на кластер и TTL к нему:
 *
 *   • alive — 24 часа: провайдер отвечал, его строки проверяем первыми;
 *   • dead  — 6 часов: ни одна проверенная строка не поднялась, в следующий раз
 *             пропускаем кластер целиком (но НЕ навсегда — провайдеры оживают).
 *
 * Формат записи: <время, мс> TAB <alive|dead> TAB <живых> TAB <проверено>
 * Ключи: `bypass_clusters`, поля `clusters_wifi` / `clusters_lte`.
 */
public final class ClusterStore {
    private ClusterStore() {}

    private static final String PREFS = "bypass_clusters";
    private static final String PREFIX = "clusters_";
    /** Провайдер отвечал: вердикт живёт сутки. */
    public static final long TTL_ALIVE_MS = 24L * 60 * 60 * 1000;
    /** Провайдер не ответил: полсуток не трогаем. */
    public static final long TTL_DEAD_MS = 6L * 60 * 60 * 1000;

    public static final String ALIVE = "alive";
    public static final String DEAD = "dead";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String field(String net) {
        return PREFIX + Net.normalize(net);
    }

    /** Разобранный вердикт. */
    public static final class Entry {
        public final String key;
        public final String state;
        public final long at;
        public final int hits;
        public final int tested;

        Entry(String key, String state, long at, int hits, int tested) {
            this.key = key;
            this.state = state;
            this.at = at;
            this.hits = hits;
            this.tested = tested;
        }

        public boolean expired(long now) {
            return now - at > (ALIVE.equals(state) ? TTL_ALIVE_MS : TTL_DEAD_MS);
        }
    }

    public static synchronized List<Entry> all(Context c, String net) {
        List<Entry> out = new ArrayList<>();
        String store = prefs(c).getString(field(net), "");
        if (store == null || store.isEmpty()) return out;
        for (String line : store.split("\n")) {
            if (line == null || line.trim().isEmpty()) continue;
            String[] p = line.split("\t");
            if (p.length < 2) continue;
            long at;
            try {
                at = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            int hits = 0;
            int tested = 0;
            try {
                if (p.length > 2) hits = Integer.parseInt(p[2]);
                if (p.length > 3) tested = Integer.parseInt(p[3]);
            } catch (NumberFormatException ignored) {
            }
            // Формат записи: <at> TAB <state> TAB <hits> TAB <tested> TAB <key>
            out.add(new Entry(p.length > 4 ? p[4] : "", p[1], at, hits, tested));
        }
        return out;
    }

    /** Состояние кластера: ALIVE / DEAD / "" (неизвестно или вердикт протух). */
    public static String state(Context c, String net, String key) {
        if (key == null || key.isEmpty()) return "";
        long now = System.currentTimeMillis();
        for (Entry e : all(c, net)) {
            if (!key.equals(e.key)) continue;
            if (e.expired(now)) return "";
            return e.state;
        }
        return "";
    }

    public static java.util.Set<String> dead(Context c, String net) {
        java.util.Set<String> out = new java.util.HashSet<>();
        long now = System.currentTimeMillis();
        for (Entry e : all(c, net)) {
            if (!DEAD.equals(e.state)) continue;
            if (e.expired(now)) continue;
            out.add(e.key);
        }
        return out;
    }

    /**
     * Записать вердикт. hits > 0 — кластер живой; hits == 0 при tested >= 2 — мёртвый.
     * Меньше двух проверенных строк вердикта не даёт: одна строка могла не подняться
     * из-за своих кредов, а не из-за провайдера.
     */
    public static synchronized void mark(Context c, String net, String key,
                                         int hits, int tested) {
        if (key == null || key.isEmpty()) return;
        String state;
        if (hits > 0) state = ALIVE;
        else if (tested >= 2) state = DEAD;
        else return;
        List<Entry> list = all(c, net);
        List<String> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Entry e : list) {
            if (key.equals(e.key)) continue;
            if (e.expired(now)) continue;      // протухшие не переписываем
            lines.add(e.at + "\t" + e.state + "\t" + e.hits + "\t" + e.tested + "\t" + e.key);
        }
        lines.add(now + "\t" + state + "\t" + hits + "\t" + tested + "\t" + key);
        // Больше 400 кластеров в одном хранилище держать незачем: это уже не выборка.
        if (lines.size() > 400) {
            lines = new ArrayList<>(lines.subList(lines.size() - 400, lines.size()));
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        prefs(c).edit().putString(field(net), sb.toString()).apply();
    }

    public static synchronized void clear(Context c, String net) {
        prefs(c).edit().remove(field(net)).apply();
    }

    public static synchronized void clearAll(Context c) {
        prefs(c).edit().clear().apply();
    }

    public static int size(Context c, String net) {
        return all(c, net).size();
    }

    /** «живых 12, мёртвых 30» — для журнала и настроек. */
    public static String summary(Context c, String net) {
        int alive = 0;
        int dead = 0;
        long now = System.currentTimeMillis();
        for (Entry e : all(c, net)) {
            if (e.expired(now)) continue;
            if (ALIVE.equals(e.state)) alive++;
            else if (DEAD.equals(e.state)) dead++;
        }
        if (alive == 0 && dead == 0) return "вердиктов нет";
        return "живых " + alive + ", мёртвых " + dead;
    }
}
