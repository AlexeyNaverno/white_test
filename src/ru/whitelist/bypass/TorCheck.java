package ru.whitelist.bypass;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Проверка резервного канала: мосты Tor и доступность источников через Tor.
 *
 * Зачем: Tor в приложении — не режим обхода, а последняя дорога к спискам конфигов,
 * когда прямое подключение и зеркало не отдали ни одного файла. Поэтому резерв нужно
 * не «предполагать», а проверять: поднимаем Tor с конкретным мостом, качаем тестовую
 * страницу, записываем скорость. Дальше и в приложении, и в браузере мосты идут
 * начиная с самого быстрого.
 */
public final class TorCheck {
    private TorCheck() {}

    public interface Log {
        void line(String message);
    }

    /** Больше шести мостов за один прогон — это уже полчаса ожидания. */
    private static final int MAX_BRIDGES = 6;
    /** Сколько ждём, пока Tor поднимется с одним мостом. */
    private static final int BRIDGE_WAIT_MS = 45000;
    /** Таймаут на тестовую страницу через Tor. */
    private static final int SPEED_TIMEOUT_MS = 20000;
    /** Сколько источников проверяем через Tor (это долго, поэтому немного). */
    private static final int MAX_SOURCES = 4;
    /** Небольшая страница: показывает именно «интернет через мост работает». */
    private static final String TEST_URL = "https://duckduckgo.com/";

    /**
     * Прогнать проверку целиком. Возвращает текстовый отчёт (он же сохраняется в настройках).
     * Блокирующий вызов — только из фонового потока.
     */
    public static String run(Context c, Log log) {
        ConfigRepository.clearCancel();
        StringBuilder report = new StringBuilder();
        List<String> candidates = TorEngine.candidateBridges(c);
        if (candidates.isEmpty()) {
            String msg = "Мостов нет: ни своих в настройках, ни встроенных.";
            say(log, report, msg);
            Prefs.setTorReport(c, report.toString());
            return report.toString();
        }

        // --- 1) по одному мосту: поднимается ли Tor и с какой скоростью отдаёт страницу
        List<String> tested = new ArrayList<>();
        for (String b : candidates) {
            if (tested.size() >= MAX_BRIDGES) break;
            tested.add(b);
        }
        say(log, report, "Проверяю мосты: " + tested.size() + " из " + candidates.size()
                + " (по одному, до " + (BRIDGE_WAIT_MS / 1000) + " с на мост)");

        final Map<String, Long> kbps = new LinkedHashMap<>();
        final Map<String, Long> ms = new LinkedHashMap<>();
        int index = 0;
        for (String bridge : tested) {
            if (ConfigRepository.isCancelled()) break;
            index++;
            say(log, report, index + "/" + tested.size() + " " + shortBridge(bridge));
            TorEngine.stopAndWait(c, 6000);
            boolean up = TorEngine.startWith(c, Collections.singletonList(bridge), BRIDGE_WAIT_MS);
            if (!up) {
                say(log, report, "  Tor с этим мостом не поднялся — пропускаю");
                continue;
            }
            NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(
                    TEST_URL, "127.0.0.1", TorEngine.SOCKS_PORT, SPEED_TIMEOUT_MS, 131072);
            if (!r.ok || r.bytes <= 0) {
                say(log, report, "  Tor поднялся, но страница не открылась — мост не годится");
                continue;
            }
            long speed = r.ms <= 0 ? 0 : Math.round(r.bytes * 1000.0 / r.ms / 1024.0);
            kbps.put(bridge, Math.max(1, speed));
            ms.put(bridge, r.ms);
            say(log, report, "  ок: " + r.bytes / 1024 + " КБ за " + (r.ms / 1000.0) + " с → "
                    + Math.max(1, speed) + " КБ/с");
        }
        TorEngine.stopAndWait(c, 6000);

        if (kbps.isEmpty()) {
            say(log, report, "Ни один мост не дал доступа к сети. Проверьте мосты в настройках Tor.");
            Prefs.setTorReport(c, report.toString());
            return report.toString();
        }

        // --- 2) запомнить порядок: самые быстрые мосты — первыми
        List<String> ordered = new ArrayList<>(kbps.keySet());
        Collections.sort(ordered, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return Long.compare(kbps.get(b), kbps.get(a));
            }
        });
        StringBuilder rank = new StringBuilder();
        for (String b : ordered) {
            rank.append(b).append('\t').append(kbps.get(b)).append('\t').append(ms.get(b)).append('\n');
        }
        Prefs.setTorBridgeRank(c, rank.toString());
        say(log, report, "Порядок сохранён, первый мост: " + shortBridge(ordered.get(0))
                + " (" + kbps.get(ordered.get(0)) + " КБ/с)");

        // --- 3) главное: реально достать списки VLESS через Tor
        say(log, report, "Проверяю резервный путь к спискам: поднимаю Tor с быстрыми мостами…");
        boolean up = TorEngine.startWith(c, ordered, BRIDGE_WAIT_MS);
        if (!up) {
            say(log, report, "Tor с рабочими мостами не поднялся — резерв недоступен");
            Prefs.setTorReport(c, report.toString());
            return report.toString();
        }
        int okSources = 0;
        try {
            List<String> sources = Prefs.sources(c);
            int checked = 0;
            for (String src : sources) {
                if (checked >= MAX_SOURCES || ConfigRepository.isCancelled()) break;
                checked++;
                long t0 = System.currentTimeMillis();
                try {
                    List<String> lines = ConfigRepository.fetchSource(src, true);
                    long spent = Math.max(1, System.currentTimeMillis() - t0);
                    okSources++;
                    say(log, report, "  " + ConfigRepository.shortSource(src) + ": ок, "
                            + lines.size() + " строк за " + (spent / 1000.0) + " с");
                } catch (Exception e) {
                    say(log, report, "  " + ConfigRepository.shortSource(src) + ": не вышло ("
                            + e.getMessage() + ")");
                }
            }
        } finally {
            TorEngine.stop(c);
        }

        say(log, report, okSources > 0
                ? "Итог: резерв работает — " + okSources + " источник(ов) доступны через Tor."
                : "Итог: через Tor ни один источник не открылся.");
        Prefs.setTorReport(c, report.toString());
        return report.toString();
    }

    private static void say(Log log, StringBuilder report, String message) {
        report.append(message).append('\n');
        if (log != null) log.line(message);
    }

    /** Мост в виде «host:port», без лишних подробностей — их видно в настройках. */
    public static String shortBridge(String bridge) {
        if (bridge == null) return "";
        String t = bridge.trim();
        int space = t.indexOf(' ');
        int end = t.indexOf(' ', space + 1);
        String core = end > 0 ? t.substring(space + 1, end) : (space > 0 ? t.substring(space + 1) : t);
        return core.length() > 40 ? core.substring(0, 40) + "…" : core;
    }
}
