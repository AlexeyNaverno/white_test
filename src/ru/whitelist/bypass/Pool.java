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
 * Пул рабочих конфигов с ИЗМЕРЕННОЙ скоростью — отдельно для Wi-Fi и для мобильного.
 *
 * Почему два пула. У оператора и у домашнего Wi-Fi разные блокировки, разные рабочие
 * серверы и разные стратегии обхода. Общий пул означал, что «собранный в метро» список
 * подсовывался дома, где половина серверов не отвечает, а хорошие домашние конфиги
 * вытеснялись по скорости. Теперь:
 *
 *   files/pool_wifi.txt — замеры, сделанные в Wi-Fi;
 *   files/pool_lte.txt  — замеры, сделанные в мобильном интернете.
 *
 * Методы без явной сети работают с ТЕКУЩЕЙ сетью ({@link Net#key}), поэтому поиск, сбор
 * пула и подключение сами попадают в нужный файл. Экран «Рабочие конфиги» показывает оба
 * пула и позволяет переключаться.
 *
 * Формат строки: <КБ/с> TAB <пинг, мс> TAB <время замера, мс> TAB <конфиг>
 * Если конфиг заработал только через DPI-обход, появляется пятое поле:
 * <КБ/с> TAB <пинг> TAB <время> TAB <стратегия> TAB <конфиг>.
 */
public final class Pool {
    private Pool() {}

    /** Старый единый файл: при первом обращении переносим его в мобильный пул. */
    private static final String LEGACY_FILE = "pool.txt";
    private static final String PREFIX = "pool_";
    private static final String SUFFIX = ".txt";
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

    // ------------------------------------------------------------------ файлы

    private static String netKey(Context c) {
        return Net.key(c);
    }

    private static File file(Context c, String net) {
        migrateLegacy(c);
        return new File(c.getFilesDir(), PREFIX + Net.normalize(net) + SUFFIX);
    }

    /**
     * Старый pool.txt → pool_lte.txt. Ровно один раз: раньше пул был один и собирался
     * в основном в мобильном интернете, поэтому мобильному его и отдаём.
     */
    private static void migrateLegacy(Context c) {
        try {
            File legacy = new File(c.getFilesDir(), LEGACY_FILE);
            File lte = new File(c.getFilesDir(), PREFIX + Net.MOBILE + SUFFIX);
            File wifi = new File(c.getFilesDir(), PREFIX + Net.WIFI + SUFFIX);
            if (!legacy.isFile()) return;
            if (!lte.exists()) {
                if (!legacy.renameTo(lte)) {
                    copy(legacy, lte);
                    legacy.delete();
                }
            } else {
                legacy.delete();
            }
            if (!wifi.exists()) {
                // Файл-пустышка: так «пул пуст» отличается от «пул ещё не заводили».
                new FileOutputStream(wifi, false).close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copy(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ чтение

    /**
     * Канонические ключи конфигов, которые уже лежат в пуле текущей сети.
     * Быстрому поиску они не нужны: их проверяют только при «Собрать пул».
     */
    public static java.util.Set<String> keySet(Context c) {
        return keySet(c, netKey(c));
    }

    public static java.util.Set<String> keySet(Context c, String net) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Entry e : load(c, net)) {
            String key = ConfigRepository.canonicalKey(e.raw);
            if (key != null && !key.isEmpty()) out.add(key);
        }
        return out;
    }

    /** Все записи пула текущей сети, по убыванию скорости. */
    public static synchronized List<Entry> load(Context c) {
        return load(c, netKey(c));
    }

    /** Все записи указанной сети ({@link Net#WIFI} или {@link Net#MOBILE}). */
    public static synchronized List<Entry> load(Context c, String net) {
        List<Entry> out = new ArrayList<>();
        File f = file(c, net);
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

    // ------------------------------------------------------------------ запись

    public static synchronized void save(Context c, List<Entry> entries) {
        save(c, netKey(c), entries);
    }

    public static synchronized void save(Context c, String net, List<Entry> entries) {
        write(c, net, entries);
        Prefs.setPoolBuiltAt(c, net, System.currentTimeMillis());
    }

    /** Запись файла пула без отметки «пул собран» (удаление конфига — не сбор). */
    private static synchronized void write(Context c, String net, List<Entry> entries) {
        List<Entry> copy = new ArrayList<>(entries);
        sortBySpeed(copy);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Entry e : copy) {
            if (n >= MAX_ENTRIES) break;
            sb.append(e.kbps).append('\t')
              .append(e.pingMs).append('\t')
              .append(e.at).append('\t');
            if (e.viaDpi()) sb.append(e.dpi).append('\t');
            sb.append(e.raw).append('\n');
            n++;
        }
        try (FileOutputStream fos = new FileOutputStream(file(c, net), false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /**
     * Убрать конфиг из пула текущей сети — «в урну». Вызывается, когда конфиг перестал
     * работать: держать в пуле то, что не поднимается, бессмысленно.
     */
    public static synchronized boolean remove(Context c, String raw) {
        return remove(c, netKey(c), raw);
    }

    public static synchronized boolean remove(Context c, String net, String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String key = ConfigRepository.canonicalKey(raw);
        if (key == null || key.isEmpty()) return false;
        List<Entry> list = load(c, net);
        List<Entry> out = new ArrayList<>();
        boolean removed = false;
        for (Entry e : list) {
            if (key.equals(ConfigRepository.canonicalKey(e.raw))) {
                removed = true;
                continue;
            }
            out.add(e);
        }
        if (removed) write(c, net, out);
        return removed;
    }

    /** Добавить/обновить один конфиг в пуле текущей сети. */
    public static synchronized void remember(Context c, String raw, long kbps, long pingMs) {
        remember(c, raw, kbps, pingMs, null);
    }

    public static synchronized void remember(Context c, String raw, long kbps, long pingMs,
                                             String dpi) {
        remember(c, netKey(c), raw, kbps, pingMs, dpi);
    }

    /** То же, но в явно указанную сеть (экран «Рабочие конфиги» правит оба пула). */
    public static synchronized void remember(Context c, String net, String raw, long kbps,
                                             long pingMs, String dpi) {
        if (raw == null || raw.trim().isEmpty() || kbps <= 0) return;
        String key = ConfigRepository.canonicalKey(raw);
        List<Entry> list = load(c, net);
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) {
            if (ConfigRepository.canonicalKey(e.raw).equals(key)) continue;
            out.add(e);
        }
        out.add(new Entry(raw.trim(), kbps, pingMs < 0 ? -1 : pingMs,
                System.currentTimeMillis(), dpi));
        save(c, net, out);
    }

    public static synchronized void clear(Context c) {
        clear(c, netKey(c));
    }

    public static synchronized void clear(Context c, String net) {
        File f = file(c, net);
        if (f.isFile()) f.delete();
        Prefs.setPoolBuiltAt(c, net, 0L);
    }

    /** Стратегия DPI-обхода, с которой конфиг замерен ("" — напрямую или его нет в пуле). */
    public static String dpiFor(Context c, String raw) {
        return dpiFor(c, netKey(c), raw);
    }

    public static String dpiFor(Context c, String net, String raw) {
        String key = ConfigRepository.canonicalKey(raw);
        if (key == null) return "";
        for (Entry e : load(c, net)) {
            if (key.equals(ConfigRepository.canonicalKey(e.raw))) return e.dpi;
        }
        return "";
    }

    public static int count(Context c) {
        return load(c).size();
    }

    public static int count(Context c, String net) {
        return load(c, net).size();
    }

    public static long bestKbps(Context c) {
        return bestKbps(c, netKey(c));
    }

    public static long bestKbps(Context c, String net) {
        List<Entry> list = load(c, net);
        return list.isEmpty() ? -1 : list.get(0).kbps;
    }
}
