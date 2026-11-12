package ru.whitelist.bypass;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ru.whitelist.bypass.jni.ByeDpiProxy;

/**
 * Стратегии DPI-обхода (аргументы ciadpi / ByeDPI).
 *
 * Зачем отдельный класс: рабочие стратегии зависят от провайдера и от того, что именно
 * блокируют, поэтому «одна на всех» живёт недолго. Здесь список стратегий
 * 1) обновляется из открытых репозиториев, где авторы сами гоняют стратегии по блокировкам;
 * 2) проверяется вживую — поднимаем ciadpi и идём через него на заблокированные адреса;
 * 3) первая прошедшая сохраняется в настройках и используется линией до VPN-сервера.
 *
 * Формат аргументов поддерживается любой из тех, что пишут в этих репозиториях:
 * короткий (`-d1 -s3+sm -a1`) и длинный (`--disorder 1 --auto=torst`).
 * Плейсхолдеры {sni} и {port} подставляет {@link DpiArgs}.
 */
public final class DpiStrategies {
    private DpiStrategies() {}

    public interface Log {
        void line(String message);
    }

    private static final String FILE = "strategies.txt";
    /** Больше держать смысла нет: проверять всё равно будем первые несколько. */
    private static final int MAX_STRATEGIES = 60;
    /** Сколько ждём ответа при проверке одной стратегии. */
    private static final int PROBE_TIMEOUT_MS = 3000;
    /** Сколько стратегий максимум перебираем при автоподборе. */
    private static final int AUTOTUNE_MAX = 5;

    /** Минимум, который работает без сети: если ни один репозиторий не ответил. */
    public static final String[] BUILT_IN = {
            Prefs.DEFAULT_STRATEGY,
            "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -s15+s -s20+s -s30+s -a1",
            "-o1 -d1 -a1 -At,r,s,c -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1"
    };

    private static volatile ByeDpiProxy active;

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** Все известные стратегии: текущая → скачанные → встроенные. */
    public static synchronized List<String> list(Context c) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        String current = Prefs.dpiStrategyFor(c);
        if (current != null && !current.trim().isEmpty()) {
            if (seen.add(current.trim())) out.add(current.trim());
        }
        for (String s : read(c)) {
            if (seen.add(s)) out.add(s);
        }
        for (String s : BUILT_IN) {
            if (seen.add(s)) out.add(s);
        }
        return out;
    }

    /**
     * Обновить список стратегий из репозиториев (см. Prefs.DEFAULT_DPI_SOURCES).
     * Возвращает, сколько стратегий сохранено.
     */
    public static int refresh(Context c, Log log) {
        Set<String> all = new LinkedHashSet<>();
        int sourcesOk = 0;
        for (String url : Prefs.dpiSources(c)) {
            if (ConfigRepository.isCancelled()) break;
            try {
                List<String> lines = ConfigRepository.fetchSource(url, false);
                int before = all.size();
                for (String raw : lines) addStrategy(all, raw);
                sourcesOk++;
                if (log != null) {
                    log.line("DPI-стратегии: " + ConfigRepository.shortSource(url)
                            + " → " + (all.size() - before) + " шт.");
                }
            } catch (ConfigRepository.SourceError e) {
                if (log != null) {
                    log.line("DPI-стратегии: пропускаю " + ConfigRepository.shortSource(url)
                            + " (" + e.getMessage() + ")");
                }
            } catch (Exception e) {
                if (log != null) {
                    log.line("DPI-стратегии: не ответил " + ConfigRepository.shortSource(url));
                }
            }
        }
        if (all.isEmpty()) {
            if (log != null) log.line("DPI-стратегии: новых нет, остаются встроенные и текущая");
            return read(c).size();
        }
        write(c, new ArrayList<>(all));
        int n = read(c).size();
        if (log != null) {
            log.line("DPI-стратегии: сохранено " + n + " (источников OK " + sourcesOk + ")");
        }
        return n;
    }

    /** Флаги, которых нет в нашем ciadpi, или они про UDP, а линия у нас TCP/TLS. */
    private static final String[] SKIP_FLAGS = {
            "--proto", "--oob", "--oob-data", "--udp-fake", "--auto"
    };

    /** `-s 1+s` → `-s1+s`: в этих списках пробел после флага встречается, ciadpi его не ждёт. */

    private static void addStrategy(Set<String> out, String raw) {
        if (out.size() >= MAX_STRATEGIES || raw == null) return;
        String t = normalize(raw.trim());
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) return;
        if (!t.contains("-")) return;
        // комментарии и мусор вида "Format: ..." отсекаются отсутствием флага
        if (t.startsWith("Format") || t.startsWith("http")) return;
        for (String bad : SKIP_FLAGS) {
            if (t.contains(bad)) return;
        }
        out.add(t);
    }

    /** Убирает пробел между однобуквенным флагом и его значением, обрезает \r. */
    private static String normalize(String t) {
        String s = t.replace("\r", "").replace("\t", " ").trim();
        s = s.replaceAll("(^|\\s)-([sdrfqtona])\\s+([0-9+\\-]\\S*)", "$1-$2$3");
        return s.trim();
    }

    /**
     * Проверить стратегию вживую: поднять ciadpi с этими аргументами и сходить
     * через него на заблокированные адреса. true — блокировка обходится.
     */
    public static boolean probe(Context c, String strategy, String sni, int timeoutMs) {
        int port = startOnce(strategy, sni);
        if (port <= 0) return false;
        try {
            List<String> urls = Prefs.dpiTestUrls(c);
            // Первый адрес — основной; если он молчит, второй даём с остатком общего лимита,
            // чтобы одна «мёртвая» стратегия не съедала полминуты.
            long deadline = System.currentTimeMillis() + (long) (timeoutMs * 1.8);
            for (String url : urls) {
                if (ConfigRepository.isCancelled()) return false;
                long left = deadline - System.currentTimeMillis();
                if (left <= 500) break;
                int t = (int) Math.min(timeoutMs, left);
                if (NetChecker.httpCheckThroughSocks(url, "127.0.0.1", port, t)) return true;
            }
            return false;
        } finally {
            stop();
        }
    }

    /**
     * Подобрать рабочую стратегию и запомнить её. Возвращает выбранную.
     * Ограничено AUTOTUNE_MAX попытками, чтобы подключение не висело.
     */
    public static String autotune(Context c, String sni, Log log) {
        String current = Prefs.dpiStrategyFor(c);
        int tried = 0;
        // Сначала те, что уже приносили рабочий конфиг на этой сети: их проверять дешевле,
        // чем перебирать весь список заново.
        List<String> order = new ArrayList<>(working(c));
        for (String st : list(c)) {
            if (!order.contains(st)) order.add(st);
        }
        for (String st : order) {
            if (ConfigRepository.isCancelled()) break;
            if (tried >= AUTOTUNE_MAX) break;
            tried++;
            if (log != null) log.line("DPI: проверяю " + shorten(st));
            if (probe(c, st, sni, PROBE_TIMEOUT_MS)) {
                if (!st.equals(current)) Prefs.setDpiStrategyFor(c, Net.key(c), st);
                if (log != null) log.line("DPI: подошла — " + shorten(st));
                return st;
            }
        }
        if (log != null) {
            log.line("DPI: из " + tried + " стратегий ни одна не пробилась — остаётся текущая");
        }
        return current;
    }

    // --- рабочие стратегии: те, с которыми конфиги реально заработали ---

    /** Отдельный файл: это результат проверок, а не настройка (в .json не экспортируется). */
    private static final String WORKING_FILE = "bypass_dpi_working";
    private static final String WORKING_KEY = "list";
    private static final int WORKING_MAX = 8;

    /** Стратегии, которые уже приносили рабочий конфиг. Свежие — первыми. */
    public static synchronized List<String> working(Context c) {
        List<String> out = new ArrayList<>();
        String data = c.getSharedPreferences(WORKING_FILE, Context.MODE_PRIVATE)
                .getString(WORKING_KEY, "");
        if (data == null || data.isEmpty()) return out;
        for (String line : data.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Запомнить стратегию, с которой конфиг прошёл замер (с сохранением порядка). */
    public static synchronized void rememberWorking(Context c, String strategy) {
        if (strategy == null || strategy.trim().isEmpty()) return;
        List<String> list = working(c);
        String st = strategy.trim();
        list.remove(st);
        list.add(0, st);
        while (list.size() > WORKING_MAX) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        c.getSharedPreferences(WORKING_FILE, Context.MODE_PRIVATE)
                .edit().putString(WORKING_KEY, sb.toString()).apply();
    }

    /** Забыть все рабочие стратегии (например, после смены оператора). */
    public static synchronized void clearWorking(Context c) {
        c.getSharedPreferences(WORKING_FILE, Context.MODE_PRIVATE)
                .edit().remove(WORKING_KEY).apply();
    }

    /** Короткая строка для лога. */
    public static String shorten(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= 46 ? t : t.substring(0, 46) + "…";
    }

    /** Поднять ciadpi с данной стратегией; вернуть порт SOCKS (0 — не поднялся). */
    private static int startOnce(String strategy, String sni) {
        stop();
        try {
            int port = Probe.freePort();
            if (port <= 0) return 0;
            final String[] args = DpiArgs.build(strategy, port, sni);
            final ByeDpiProxy proxy = new ByeDpiProxy();
            active = proxy;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        proxy.startProxy(args);
                    } catch (Throwable ignored) {
                        // код выхода не важен: проверяем по факту SOCKS
                    }
                }
            }, "dpi-probe");
            t.setDaemon(true);
            t.start();
            if (Probe.waitSocks(port, 3000)) return port;
            // нативный мост может игнорировать {port} и слушать дефолтный 1080
            if (Probe.waitSocks(1080, 700)) return 1080;
            stop();
            return 0;
        } catch (Throwable t) {
            stop();
            return 0;
        }
    }

    private static void stop() {
        ByeDpiProxy p = active;
        active = null;
        if (p != null) {
            try {
                p.stopProxy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<String> read(Context c) {
        List<String> out = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void write(Context c, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        try (FileOutputStream fos = new FileOutputStream(file(c), false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
