package ru.whitelist.bypass;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Пул рабочих конфигов с ИЗМЕРЕННОЙ скоростью.
 *
 * Пул — единственный источник кандидатов для подключения. Сортировка — по скорости
 * по убыванию (приоритет скорости, а не пинга), поэтому «Подключить» всегда начинает
 * с самого быстрого живого конфига.
 *
 * Формат строки: <КБ/с> TAB <пинг, мс> TAB <время замера, мс> TAB <конфиг>
 * Если конфиг заработал только через DPI-обход, в строке появляется пятое поле:
 * <КБ/с> TAB <пинг> TAB <время> TAB <стратегия> TAB <конфиг> — тогда при подключении
 * берётся именно эта стратегия, а не «текущая из настроек».
 */
public final class Pool {
    private Pool() {}

    private static final String FILE = "pool.txt";
    /** Больше не держим: пул — это «десятки лучших», а не склад. */
    private static final int MAX_ENTRIES = 200;

    public static final class Entry {
        public final String raw;
        public final long kbps;
        public final long pingMs;
        public final long at;
        /** Стратегия DPI-обхода, с которой конфиг замерен; "" — напрямую. */
        public final String dpi;

        public Entry(String raw, long kbps, long pingMs, long at) {
            this(raw, kbps, pingMs, at, "");
        }

        public Entry(String raw, long kbps, long pingMs, long at, String dpi) {
            this.raw = raw;
            this.kbps = kbps;
            this.pingMs = pingMs;
            this.at = at;
            this.dpi = dpi == null ? "" : dpi.trim();
        }

        /** Конфиг замерен через DPI-обход (нужна стратегия, чтобы повторить). */
        public boolean viaDpi() {
            return !dpi.isEmpty();
        }

        /** Разобранный конфиг (null, если строка испорчена). */
        public ProxyConfig cfg() {
            ProxyConfig p = ProxyConfig.parse(raw, "пул", false);
            if (p == null || !p.supported || p.host == null || p.host.isEmpty() || p.port <= 0) return null;
            p.latency = pingMs;
            return p;
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /**
     * Канонические ключи конфигов, которые уже лежат в пуле.
     * Быстрому поиску они не нужны: их проверяют только при «Собрать пул» — на случай,
     * если конфиг перестал работать.
     */
    public static java.util.Set<String> keySet(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Entry e : load(c)) {
            String key = ConfigRepository.canonicalKey(e.raw);
            if (key != null && !key.isEmpty()) out.add(key);
        }
        return out;
    }

    /** Все записи пула, по убыванию скорости. */
    public static synchronized List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Entry e = parseLine(line);
                if (e != null) out.add(e);
            }
        } catch (Exception ignored) {
        }
        sortBySpeed(out);
        return out;
    }

    private static Entry parseLine(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty()) return null;
        String[] parts = t.split("\t", 5);
        if (parts.length < 4) return null;
        try {
            long kbps = Long.parseLong(parts[0].trim());
            long ping = Long.parseLong(parts[1].trim());
            long at = Long.parseLong(parts[2].trim());
            String dpi = "";
            String raw;
            if (parts.length >= 5) {
                dpi = parts[3].trim();
                raw = parts[4].trim();
            } else {
                raw = parts[3].trim();
            }
            if (raw.isEmpty() || kbps <= 0) return null;
            return new Entry(raw, kbps, ping, at, dpi);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sortBySpeed(List<Entry> list) {
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.kbps != b.kbps) return Long.compare(b.kbps, a.kbps);   // быстрее — выше
                return Long.compare(a.pingMs, b.pingMs);                     // при равной скорости — меньше пинг
            }
        });
    }

    public static synchronized void save(Context c, List<Entry> entries) {
        List<Entry> copy = new ArrayList<>(entries);
        sortBySpeed(copy);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Entry e : copy) {
            if (n >= MAX_ENTRIES) break;
            sb.append(e.kbps).append('\t')
              .append(e.pingMs).append('\t')
              .append(e.at).append('\t');
            if (e.viaDpi()) sb.append(e.dpi).append('\t');   // пятое поле только когда нужно
            sb.append(e.raw).append('\n');
            n++;
        }
        try (FileOutputStream fos = new FileOutputStream(file(c), false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        Prefs.setPoolBuiltAt(c, System.currentTimeMillis());
    }

    /** Добавить/обновить один конфиг (используется после удачного подключения и быстрого поиска). */
    public static synchronized void remember(Context c, String raw, long kbps, long pingMs) {
        remember(c, raw, kbps, pingMs, null);
    }

    /** То же, но с записью стратегии DPI-обхода, с которой конфиг заработал. */
    public static synchronized void remember(Context c, String raw, long kbps, long pingMs,
                                             String dpi) {
        if (raw == null || raw.trim().isEmpty() || kbps <= 0) return;
        String key = ConfigRepository.canonicalKey(raw);
        List<Entry> list = load(c);
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) {
            if (ConfigRepository.canonicalKey(e.raw).equals(key)) continue;
            out.add(e);
        }
        out.add(new Entry(raw.trim(), kbps, pingMs < 0 ? -1 : pingMs,
                System.currentTimeMillis(), dpi));
        save(c, out);
    }

    public static synchronized void clear(Context c) {
        File f = file(c);
        if (f.isFile()) f.delete();
        Prefs.setPoolBuiltAt(c, 0L);
    }

    /** Стратегия DPI-обхода, с которой этот конфиг замерен ("" — напрямую или его нет в пуле). */
    public static String dpiFor(Context c, String raw) {
        String key = ConfigRepository.canonicalKey(raw);
        if (key == null) return "";
        for (Entry e : load(c)) {
            if (key.equals(ConfigRepository.canonicalKey(e.raw))) return e.dpi;
        }
        return "";
    }

    public static int count(Context c) {
        return load(c).size();
    }

    public static long bestKbps(Context c) {
        List<Entry> list = load(c);
        return list.isEmpty() ? -1 : list.get(0).kbps;
    }
}
