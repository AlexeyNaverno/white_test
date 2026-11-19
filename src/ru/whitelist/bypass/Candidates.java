package ru.whitelist.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * «Проверявшиеся без вердикта» — третий список рядом с пулом и чёрным списком.
 *
 * Зачем третий. Пул — это доказанно рабочие. Чёрный список — доказанно нерабочие.
 * А есть третья категория: конфиг проверили, но результат не является приговором —
 * не уложились в окно замера, оборвалась закачка, ответ 204 без тела, пользователь
 * остановил сбор в середине. Раньше такие уходили либо в пул (и подводили при
 * подключении), либо в ЧС (и терялись живые). Теперь они лежат отдельно:
 *
 *   • в пул не попадают, пока не докажут скорость;
 *   • в ЧС не попадают вообще;
 *   • в следующем прогоне проверяются ПЕРВЫМИ — они уже отсеяны по TCP, значит дешёвые.
 *
 * Формат: <время, мс> TAB <причина> TAB <конфиг в одну строку>
 * Ключи: `bypass_candidates`, поля `cand_wifi` / `cand_lte`. TTL — 6 часов: позже это
 * уже не «не успели», а «устарело».
 */
public final class Candidates {
    private Candidates() {}

    private static final String PREFS = "bypass_candidates";
    private static final String PREFIX = "cand_";
    public static final long TTL_MS = 6L * 60 * 60 * 1000;
    private static final int MAX_ENTRIES = 400;

    public static final class Entry {
        public final String raw;
        public final String reason;
        public final long at;

        Entry(String raw, String reason, long at) {
            this.raw = raw;
            this.reason = reason;
            this.at = at;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String field(String net) {
        return PREFIX + Net.normalize(net);
    }

    public static synchronized List<Entry> all(Context c, String net) {
        List<Entry> out = new ArrayList<>();
        String store = prefs(c).getString(field(net), "");
        if (store == null || store.isEmpty()) return out;
        long now = System.currentTimeMillis();
        for (String line : store.split("\n")) {
            if (line == null || line.trim().isEmpty()) continue;
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            long at;
            try {
                at = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (now - at > TTL_MS) continue;   // протухло — это уже не «не успели»
            out.add(new Entry(p[2], p[1], at));
        }
        return out;
    }

    /** Сколько «нерешённых» лежит для сети. */
    public static int size(Context c, String net) {
        return all(c, net).size();
    }

    public static int size(Context c) {
        return size(c, Net.key(c));
    }

    /** Запомнить строку как «проверяли, вердикта нет». */
    public static synchronized void add(Context c, String net, String raw, String reason) {
        if (raw == null || raw.trim().isEmpty()) return;
        String clean = raw.replace('\n', ' ').replace('\r', ' ').trim();
        List<Entry> list = all(c, net);
        List<String> lines = new ArrayList<>();
        for (Entry e : list) {
            if (clean.equals(e.raw)) continue;
            lines.add(e.at + "\t" + (e.reason == null ? "" : e.reason) + "\t" + e.raw);
        }
        lines.add(System.currentTimeMillis() + "\t"
                + (reason == null ? "" : reason.replace('\t', ' ')) + "\t" + clean);
        if (lines.size() > MAX_ENTRIES) {
            lines = new ArrayList<>(lines.subList(lines.size() - MAX_ENTRIES, lines.size()));
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        prefs(c).edit().putString(field(net), sb.toString()).apply();
    }

    /** Убрать строку из «нерешённых» текущей сети. */
    public static void remove(Context c, String raw) {
        remove(c, Net.key(c), raw);
    }

    /** Убрать строку: она получила вердикт (в пул или в ЧС). */
    public static synchronized void remove(Context c, String net, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String clean = raw.trim();
        List<Entry> list = all(c, net);
        List<String> lines = new ArrayList<>();
        boolean changed = false;
        for (Entry e : list) {
            if (clean.equals(e.raw)) {
                changed = true;
                continue;
            }
            lines.add(e.at + "\t" + (e.reason == null ? "" : e.reason) + "\t" + e.raw);
        }
        if (!changed) return;
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
}
