package ru.whitelist.bypass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Параллельная проверка «конфиг вообще отвечает» (по умолчанию 60 потоков).
 *
 * Проверка нарочно простая и снисходительная:
 *   1) TLS-конфиги — TCP + TLS-handshake (отсекает заглушку провайдера на 443);
 *   2) если handshake не прошёл — пробуем обычный TCP: сервер может быть жив,
 *      а SNI/cert — «не тот» (Reality, чужие SNI, самоподпись);
 *   3) живым считается тот, кто ответил хоть на одном из шагов.
 *
 * Отсюда и берётся кандидат на реальную проверку скорости: лучше проверить лишний
 * конфиг, чем отбросить рабочий.
 */
public class ConfigTester {

    public interface Listener {
        /** done/total — прогресс, ok/failed — накопленный счёт. */
        void onProgress(int done, int total, int ok, int failed);
    }

    /** Итог проверки: живые + статистика для лога. */
    public static final class Outcome {
        public final boolean cancelled;
        public final List<ProxyConfig> alive;
        public final int total;
        public final int ok;
        public final int failed;
        /** Из живых — сколько прошли только TCP (TLS-handshake не удался). */
        public final int tcpOnly;
        public final long bestMs;
        public final long medianMs;

        Outcome(boolean cancelled, List<ProxyConfig> alive, int total, int ok, int failed,
                int tcpOnly, long bestMs, long medianMs) {
            this.cancelled = cancelled;
            this.alive = alive;
            this.total = total;
            this.ok = ok;
            this.failed = failed;
            this.tcpOnly = tcpOnly;
            this.bestMs = bestMs;
            this.medianMs = medianMs;
        }

        /** Человеческая сводка для лога. */
        public String summary() {
            if (total == 0) return "нечего проверять";
            if (ok == 0) return "живых 0 из " + total + " — ни один не ответил";
            String s = "живых " + ok + " из " + total
                    + " (лучший " + bestMs + " мс, медиана " + medianMs + " мс";
            if (tcpOnly > 0) s += ", из них только TCP: " + tcpOnly;
            return s + "), мёртвых " + failed;
        }
    }

    /**
     * Пропинговать список (проставляет config.latency) и отдать Outcome.
     * cancelled=true — была нажата «остановить»: результаты применять не нужно.
     */
    public static Outcome test(List<ProxyConfig> configs, int threads, int timeoutMs,
                               final Listener listener) {
        List<ProxyConfig> list = configs == null ? new ArrayList<ProxyConfig>() : configs;
        int total = list.size();
        if (ConfigRepository.isCancelled()) {
            return new Outcome(true, new ArrayList<ProxyConfig>(), total, 0, 0, 0, -1, -1);
        }
        if (total == 0) {
            return new Outcome(false, new ArrayList<ProxyConfig>(), 0, 0, 0, 0, -1, -1);
        }

        int lanes = Math.max(1, Math.min(200, threads));
        ExecutorService pool = Executors.newFixedThreadPool(lanes);
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger okCount = new AtomicInteger();
        final AtomicInteger failCount = new AtomicInteger();
        final AtomicInteger tcpOnlyCount = new AtomicInteger();
        List<Future<ProxyConfig>> futures = new ArrayList<>();
        for (final ProxyConfig cfg : list) {
            futures.add(pool.submit(new Callable<ProxyConfig>() {
                @Override
                public ProxyConfig call() {
                    if (ConfigRepository.isCancelled()) {
                        cfg.latency = -1;
                        return cfg;
                    }
                    if (!cfg.supported || cfg.host == null || cfg.host.isEmpty() || cfg.port <= 0) {
                        cfg.latency = -1;
                    } else if (cfg.hasTls()) {
                        String sni = cfg.tlsServerName();
                        if (sni == null || sni.isEmpty()) sni = cfg.host;
                        long tls = NetChecker.tlsPing(cfg.host, cfg.port, sni, timeoutMs);
                        if (tls >= 0) {
                            cfg.latency = tls;
                        } else {
                            // TLS не прошёл — не приговор: Reality/чужой SNI часто так и выглядит
                            long tcp = NetChecker.tcpPing(cfg.host, cfg.port, timeoutMs);
                            cfg.latency = tcp;
                            if (tcp >= 0) tcpOnlyCount.incrementAndGet();
                        }
                    } else {
                        cfg.latency = NetChecker.tcpPing(cfg.host, cfg.port, timeoutMs);
                    }
                    int d = done.incrementAndGet();
                    if (cfg.latency >= 0) okCount.incrementAndGet(); else failCount.incrementAndGet();
                    if (listener != null) {
                        listener.onProgress(d, total, okCount.get(), failCount.get());
                    }
                    return cfg;
                }
            }));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(timeoutMs * (long) Math.ceil((double) total / lanes) + 5000,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (ConfigRepository.isCancelled()) {
            pool.shutdownNow();
            return new Outcome(true, new ArrayList<ProxyConfig>(), total,
                    okCount.get(), failCount.get(), tcpOnlyCount.get(), -1, -1);
        }

        List<ProxyConfig> alive = new ArrayList<>();
        int failed = 0;
        for (Future<ProxyConfig> f : futures) {
            try {
                ProxyConfig cfg = f.get(1, TimeUnit.SECONDS);
                if (cfg.latency >= 0) alive.add(cfg); else failed++;
            } catch (Exception e) {
                failed++;
            }
        }
        Collections.sort(alive, new Comparator<ProxyConfig>() {
            @Override
            public int compare(ProxyConfig a, ProxyConfig b) {
                return Long.compare(a.latency, b.latency);
            }
        });
        List<Long> lat = new ArrayList<>();
        for (ProxyConfig c : alive) lat.add(c.latency);
        long best = lat.isEmpty() ? -1 : lat.get(0);
        long median = lat.isEmpty() ? -1 : lat.get(lat.size() / 2);
        return new Outcome(false, alive, total, alive.size(), failed, tcpOnlyCount.get(), best, median);
    }
}
