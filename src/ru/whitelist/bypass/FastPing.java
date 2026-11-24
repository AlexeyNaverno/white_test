package ru.whitelist.bypass;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Быстрый TCP-отсев: первая, самая дешёвая ступень проверки.
 *
 * Зачем третья ступень вообще понадобилась. Публичные списки — это тысячи строк, из
 * которых большинство мёртвые: сервер выключен, порт закрыт, домен не резолвится. Полный
 * пинг ({@link ConfigTester}) для каждого конфига делает TLS-handshake и ждёт до 2 секунд —
 * это правильная, но дорогая проверка. На 1200 конфигах она занимает минуты, и всё это
 * время телефон греется, а пользователь смотрит на «пинг 0/1200».
 *
 * Здесь только открытие TCP-соединения:
 *
 *   1) конфиги группируются по ПАРЕ host:port — на тысячу строк обычно 150–300 разных
 *      серверов, и проверять нужно каждый сервер один раз, а не каждую строку;
 *   2) каждая пара проверяется простым connect с коротким таймаутом (900 мс) —
 *      без TLS, без SOCKS, без ядра;
 *   3) потоков втрое больше, чем у полного пинга (по умолчанию 384): отсев почти не
 *      грузит процессор, он весь в ожидании сети.
 *
 * Кого отсеяли — тот в этом проходе не проверяется дальше. В чёрный список НЕ пишем:
 * «не открылся TCP сейчас» — это не приговор конфигу (мобильная сеть могла моргнуть,
 * сервер мог быть перегружен, провайдер мог резать именно connect). Приговор выносит
 * только полный замер, у которого есть доказательство «данных нет».
 *
 * Итог: до полного пинга доходит в 3–10 раз меньше конфигов, а до замера скорости —
 * единицы. Это и есть «сначала грубо, потом точно», только наоборот по стоимости.
 */
public final class FastPing {

    private FastPing() {}

    public interface Log {
        void line(String message);
    }

    /** Итог отсева: что осталось и сколько это стоило. */
    public static final class Outcome {
        public final boolean cancelled;
        public final List<ProxyConfig> alive;
        public final int total;
        public final int endpoints;
        public final int aliveEndpoints;
        public final long ms;

        Outcome(boolean cancelled, List<ProxyConfig> alive, int total, int endpoints,
                int aliveEndpoints, long ms) {
            this.cancelled = cancelled;
            this.alive = alive;
            this.total = total;
            this.endpoints = endpoints;
            this.aliveEndpoints = aliveEndpoints;
            this.ms = ms;
        }

        /** «1000 → 240 конфигов за 3.1 с (178 серверов, живых 64)». */
        public String summary() {
            if (total == 0) return "нечего отсеивать";
            return total + " → " + alive.size() + " конфигов за " + seconds()
                    + " (" + endpoints + " серверов, живых " + aliveEndpoints + ")";
        }

        private String seconds() {
            if (ms < 1000) return ms + " мс";
            return String.format(java.util.Locale.US, "%.1f с", ms / 1000.0);
        }
    }

    /**
     * Прогнать список через быстрый отсев.
     *
     * @param in  кандидаты (уже без чёрного списка и без несовместимых с ядром)
     * @param log куда писать строку прогресса; null — молча
     */
    public static Outcome filter(Context c, List<ProxyConfig> in, Log log) {
        long started = System.currentTimeMillis();
        List<ProxyConfig> source = in == null ? new ArrayList<ProxyConfig>() : in;
        if (source.isEmpty()) {
            return new Outcome(false, new ArrayList<ProxyConfig>(), 0, 0, 0, 0);
        }

        // 1) группировка по серверу: один connect на host:port, а не на строку
        final Map<String, List<ProxyConfig>> byEndpoint = new LinkedHashMap<>();
        final List<String> endpoints = new ArrayList<>();
        for (ProxyConfig cfg : source) {
            if (cfg == null || cfg.host == null || cfg.host.isEmpty() || cfg.port <= 0) continue;
            String key = cfg.host.toLowerCase() + ":" + cfg.port;
            List<ProxyConfig> list = byEndpoint.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byEndpoint.put(key, list);
                endpoints.add(key);
            }
            list.add(cfg);
        }
        if (endpoints.isEmpty()) {
            return new Outcome(false, new ArrayList<ProxyConfig>(), source.size(), 0, 0,
                    System.currentTimeMillis() - started);
        }

        final int threads = Math.min(Prefs.fastPingThreads(c), Math.max(1, endpoints.size()));
        final int timeout = Prefs.fastPingTimeoutMs(c);
        // С 0.1.21 прогресс пишется десятью шагами, а не каждые 50 серверов: при 8000
        // серверах прежний лог забивал консоль сотнями строк.
        final int progressStep = Math.max(1, endpoints.size() / 10);
        final AtomicInteger cursor = new AtomicInteger();
        final AtomicInteger checked = new AtomicInteger();
        final java.util.Set<String> aliveEndpoints =
                java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    for (;;) {
                        if (ConfigRepository.isCancelled()) return;
                        int idx = cursor.getAndIncrement();
                        if (idx >= endpoints.size()) return;
                        String key = endpoints.get(idx);
                        String[] hp = NetChecker.splitTarget(key);
                        if (hp == null || hp.length < 2) {
                            checked.incrementAndGet();
                            continue;
                        }
                        String host = hp[0];
                        int port;
                        try {
                            port = Integer.parseInt(hp[1]);
                        } catch (NumberFormatException e) {
                            port = 443;
                        }
                        if (!host.isEmpty() && NetChecker.tcpPing(host, port, timeout) > 0) {
                            aliveEndpoints.add(key);
                        }
                        int done = checked.incrementAndGet();
                        if (log != null && (done % progressStep == 0 || done == endpoints.size())) {
                            log.line("  быстрый отсев " + done + "/" + endpoints.size()
                                    + " серверов → живых " + aliveEndpoints.size());
                        }
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean cancelled = ConfigRepository.isCancelled();

        // 2) оставляем конфиги живых серверов, сохраняя исходный порядок
        List<ProxyConfig> alive = new ArrayList<>();
        for (ProxyConfig cfg : source) {
            if (cfg == null || cfg.host == null || cfg.host.isEmpty()) continue;
            if (aliveEndpoints.contains(cfg.host.toLowerCase() + ":" + cfg.port)) alive.add(cfg);
        }
        return new Outcome(cancelled, alive, source.size(), endpoints.size(),
                aliveEndpoints.size(), System.currentTimeMillis() - started);
    }
}
