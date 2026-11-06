package ru.whitelist.bypass;

/**
 * Этап отладочной сборки (для поэтапного поиска причины вылетов БЕЗ logcat).
 * Значение LEVEL перезаписывается при сборке: ./build.sh ... TAINT=N (или SET TAINT=N).
 *
 *   1 — ТОЛЬКО интерфейс: ни VPN-сервиса, ни BootReceiver, ни нативных .so.
 *   2 — интерфейс + DPI-обход (byedpi / hev). Tor и Xray заблокированы.
 *   3 — интерфейс + VPN-обход (Xray / libgojni / hev). Tor заблокирован.
 *   4 — полная сборка (все три режима: dpi, vpn, tor).
 */
public final class TestFlags {
    public static final int LEVEL = 4;

    private TestFlags() {}

    /** Строка для Toast при старте тестовой сборки ("1".."3"), в полной сборке null. */
    public static String stageToast() {
        switch (LEVEL) {
            case 1: return "Тестовый этап 1/3: только интерфейс (подключение заблокировано)";
            case 2: return "Тестовый этап 2/3: интерфейс + DPI-обход";
            case 3: return "Тестовый этап 3/3: интерфейс + VPN-обход (Xray)";
            default: return null;
        }
    }
}
