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

/** Параллельная проверка задержки (по умолчанию 100 потоков) + отбраковка. */
public class ConfigTester {

    public interface Listener {
        void onProgress(int done, int total);
        void onDone(List<ProxyConfig> aliveSorted, int total, int failed);
    }

    /**
     * Пропинговать список (изменяет config.latency) и отдать живые (latency >= 0),
     * отсортированные по возрастанию задержки. Мёртвые остаются в списке с latency=-1.
     * Возвращает null, если вызвана глобальная отмена (кнопка «СТОП») — тогда
     * вызывающий код не должен применять никакие результаты (ни удаление, ни записи).
     */
    public static List<ProxyConfig> test(List<ProxyConfig> configs, int threads, int timeoutMs,
                                         Listener listener) {
        if (ConfigRepository.isCancelled()) return null;
        ExecutorService pool = Executors.newFixedThreadPool(clamp(threads, 1, 200));
        int total = configs.size();
        final int[] done = {0};
        List<Future<ProxyConfig>> futures = new ArrayList<>();
        for (final ProxyConfig cfg : configs) {
            futures.add(pool.submit(new Callable<ProxyConfig>() {
                @Override
                public ProxyConfig call() {
                    if (ConfigRepository.isCancelled()) {
                        cfg.latency = -1;
                        return cfg;
                    }
                    if (!cfg.supported || cfg.host.isEmpty() || cfg.port <= 0) {
                        cfg.latency = -1;
                    } else if (cfg.hasTls()) {
                        // TLS-конфиги: честнее TCP+handshake (иначе заглушка РКН на 443 «пингуется» в 3 мс)
                        String sni = cfg.tlsServerName();
                        if (sni == null) sni = cfg.host;
                        cfg.latency = NetChecker.tlsPing(cfg.host, cfg.port, sni, timeoutMs);
                    } else {
                        cfg.latency = NetChecker.tcpPing(cfg.host, cfg.port, timeoutMs);
                    }
                    synchronized (done) {
                        done[0]++;
                        if (listener != null) listener.onProgress(done[0], total);
                    }
                    return cfg;
                }
            }));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(timeoutMs * (long) Math.ceil((double) total / clamp(threads, 1, 200)) + 5000,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (ConfigRepository.isCancelled()) {
            pool.shutdownNow();
            return null;
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
        if (listener != null) listener.onDone(alive, total, failed);
        return alive;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
