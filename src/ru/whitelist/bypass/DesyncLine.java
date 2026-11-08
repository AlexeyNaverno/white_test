package ru.whitelist.bypass;

import ru.whitelist.bypass.jni.ByeDpiProxy;

/**
 * Локальная линия DPI-обхода (ciadpi) как отдельный объект, а не часть сервиса.
 *
 * Нужна там, где сервис ещё не поднят: при замере скорости в сборе пула и в быстром
 * поиске. Именно так появляется связка «конфиг ↔ стратегия»: пробуем напрямую, не
 * вышло — поднимаем ciadpi с текущей стратегией и повторяем, а в пул пишем ту стратегию,
 * с которой конфиг реально заработал ({@link Pool.Entry#dpi}).
 *
 * Один экземпляр на процесс: ciadpi слушает один порт, поэтому повторные замеры через
 * обход идут по очереди, а не параллельно.
 */
public final class DesyncLine {
    private DesyncLine() {}

    private static volatile ByeDpiProxy proxy;
    private static volatile int port;

    public static synchronized boolean running() {
        return proxy != null && port > 0;
    }

    public static synchronized int port() {
        return port;
    }

    /**
     * Поднять ciadpi со стратегией {@code strategy} (аргументы ByeDPI, плейсхолдеры
     * {sni}/{port} подставит {@link DpiArgs}).
     *
     * @return порт локального SOCKS или 0, если линия не поднялась
     */
    public static synchronized int start(String strategy, String sni) {
        stop();
        if (strategy == null || strategy.trim().isEmpty()) return 0;
        String host = sni == null || sni.isEmpty() ? "www.youtube.com" : sni;
        try {
            int p = Probe.freePort();
            if (p <= 0) return 0;
            final ByeDpiProxy px = new ByeDpiProxy();
            final String[] args = DpiArgs.build(strategy, p, host);
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        px.startProxy(args);
                    } catch (Throwable ignored) {
                    }
                }
            }, "byedpi-probe");
            t.setDaemon(true);
            t.start();
            int ready = 0;
            if (Probe.waitSocks(p, 4000)) {
                ready = p;
            } else if (Probe.waitSocks(1080, 1000)) {
                // Нативный мост может игнорировать {port} и слушать дефолтный 1080.
                ready = 1080;
            }
            if (ready <= 0) {
                try {
                    px.stopProxy();
                } catch (Throwable ignored) {
                }
                return 0;
            }
            proxy = px;
            port = ready;
            return ready;
        } catch (Throwable t) {
            stop();
            return 0;
        }
    }

    public static synchronized void stop() {
        ByeDpiProxy p = proxy;
        proxy = null;
        port = 0;
        if (p != null) {
            try {
                p.stopProxy();
            } catch (Throwable ignored) {
            }
        }
    }
}
