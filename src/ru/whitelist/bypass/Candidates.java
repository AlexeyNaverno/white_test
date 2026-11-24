package ru.whitelist.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Массовая запись «нерешённых» — ОДНИМ обращением к хранилищу (0.1.21).
     *
     * Зачем: в партии из 100 конфигов вердикта без приговора может не быть у десятков,
     * а прежний {@link #add} на каждую строку перечитывал и перезаписывал весь список —
     * на тысячах строк это давало квадратичное время и тормозило сбор.
     *
     * @param raws    строки конфигов
     * @param reasons причины в том же порядке (может быть null — тогда пустые)
     */
    public static synchronized void addAll(Context c, String net, List<String> raws,
                                           List<String> reasons) {
        if (raws == null || raws.isEmpty()) return;
        List<Entry> list = all(c, net);
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (Entry e : list) {
            seen.put(e.raw, lines.size());
            lines.add(e.at + "\t" + (e.reason == null ? "" : e.reason) + "\t" + e.raw);
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < raws.size(); i++) {
            String raw = raws.get(i);
            if (raw == null || raw.trim().isEmpty()) continue;
            String clean = raw.replace('\n', ' ').replace('\r', ' ').trim();
            String reason = reasons != null && i < reasons.size() && reasons.get(i) != null
                    ? reasons.get(i).replace('\t', ' ') : "";
            String line = now + "\t" + reason + "\t" + clean;
            Integer at = seen.get(clean);
            if (at != null) lines.set(at, line);
            else {
                seen.put(clean, lines.size());
                lines.add(line);
            }
        }
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

    /** Массово убрать строки (получили вердикт) — тоже одним обращением. */
    public static synchronized void removeAll(Context c, String net, List<String> raws) {
        if (raws == null || raws.isEmpty()) return;
        List<Entry> list = all(c, net);
        if (list.isEmpty()) return;
        Set<String> drop = new HashSet<>();
        for (String raw : raws) {
            if (raw != null && !raw.trim().isEmpty()) drop.add(raw.trim());
        }
        if (drop.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (Entry e : list) {
            if (drop.contains(e.raw)) {
                changed = true;
                continue;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.at).append('\t').append(e.reason == null ? "" : e.reason)
              .append('\t').append(e.raw);
        }
        if (changed) prefs(c).edit().putString(field(net), sb.toString()).apply();
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
