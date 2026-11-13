package ru.whitelist.bypass;

import android.content.Context;

import libv2ray.CoreController;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Разовая проверка конфига: поднять Xray на свободном порту → дождаться SOCKS →
 * скачать файл и посчитать КБ/с. Используется поиском и замером пула.
 *
 * Каждый результат несёт ПРИЧИНУ (для лога): «SOCKS не открылся», «нет данных»,
 * «скачано 96 КБ за 1 400 мс» — чтобы в логе было видно не только «проверка N/M»,
 * но и чем она закончилась.
 */
public final class Probe {
    private Probe() {}

    /** Меньше этого объёма — оценка скорости недостоверна. */
    public static final int MIN_SAMPLE_BYTES = 8 * 1024;
    /**
     * Сколько качаем за один замер. 512 КБ: на быстром канале 128 КБ уходили за десятки
     * миллисекунд, и скорость получалась случайной; ограничивает замер всё равно бюджет
     * времени, так что медленный канал этот объём никогда не выберет.
     */
    public static final long MAX_SAMPLE_BYTES = 512 * 1024;
    /**
     * Сколько ждём, пока Xray откроет локальный SOCKS. 4000 мс: холодный старт ядра
     * (в несколько дорожек, на слабом телефоне) иногда не укладывается в 3 с — и рабочий
     * конфиг получал «SOCKS не открылся». Мёртвому конфигу разница не важна.
     */
    public static final long SOCKS_WAIT_MS = 4000;

    /** Замер скорости: значение + чем именно он получен. */
    public static final class Speed {
        public final long kbps;
        public final String url;
        public final long bytes;
        public final long ms;
        public final int attempts;
        /** Чем закончилась последняя неудачная попытка (для лога). */
        public final String error;
        /**
         * Хотя бы один адрес ответил 2xx/3xx без тела (generate_204 и подобные).
         *
         * Это ДОКАЗАТЕЛЬСТВО, что туннель работает, просто мерить в пустом ответе
         * нечего. Раньше такой ответ считался провалом и конфиг уходил в чёрный
         * список — а «белые» конфиги, которые пускают только Cloudflare/Google,
         * отвечают ровно так. Отсюда и бралось «рабочие конфиги помечаются
         * неактивными».
         */
        public final boolean emptyAnswer;

        Speed(long kbps, String url, long bytes, long ms, int attempts) {
            this(kbps, url, bytes, ms, attempts, "");
        }

        Speed(long kbps, String url, long bytes, long ms, int attempts, String error) {
            this(kbps, url, bytes, ms, attempts, error, false);
        }

        Speed(long kbps, String url, long bytes, long ms, int attempts, String error,
              boolean emptyAnswer) {
            this.kbps = kbps;
            this.url = url == null ? "" : url;
            this.bytes = bytes;
            this.ms = ms;
            this.attempts = attempts;
            this.error = error == null ? "" : error;
            this.emptyAnswer = emptyAnswer;
        }

        public boolean ok() {
            return kbps > 0;
        }

        /** Туннель точно работает: либо скорость измерена, либо пришёл пустой 204. */
        public boolean alive() {
            return kbps > 0 || emptyAnswer;
        }

        /**
         * «512 КБ/с (4096 кбит/с) · 512 КБ за 1 000 мс» или «нет данных (2 URL): таймаут ответа».
         * Причина обязательна: без неё в логе не видно, что именно сломалось.
         */
        public String text() {
            if (kbps <= 0) {
                return "нет данных (" + attempts + " URL)"
                        + (error.isEmpty() ? "" : ": " + error);
            }
            return Prefs.speedText(kbps) + " · " + (bytes / 1024) + " КБ за " + ms + " мс";
        }
    }

    public static final class Result {
        public final boolean ok;
        public final long kbps;
        public final long socksPort;
        /** Человеческая причина: чем закончилась проверка. */
        public final String reason;
        public final Speed speed;
        /**
         * Отказ «жёсткий»: ядро не поднялось, SOCKS не открылся, данных нет. Такой
         * конфиг можно сразу отправлять в чёрный список. «Ниже порога» и «поиск
         * остановлен» — нет: конфиг рабочий, просто медленный/не успели проверить.
         */
        public final boolean hard;

        public Result(boolean ok, long kbps, long socksPort, String reason, Speed speed) {
            this(ok, kbps, socksPort, reason, speed, false);
        }

        public Result(boolean ok, long kbps, long socksPort, String reason, Speed speed,
                      boolean hard) {
            this.ok = ok;
            this.kbps = kbps;
            this.socksPort = socksPort;
            this.reason = reason == null ? "" : reason;
            this.speed = speed;
            this.hard = hard;
        }

        static Result fail(String reason) {
            return new Result(false, -1, -1, reason, null);
        }

        /** Отказ, после которого конфиг можно не проверять неделю. */
        static Result dead(String reason) {
            return new Result(false, -1, -1, reason, null, true);
        }

        /** Короткая строка для лога. */
        public String text() {
            if (ok) return "OK · " + (speed != null ? speed.text() : Prefs.speedText(kbps));
            return "не прошёл · " + reason;
        }
    }

    /** Свободный локальный порт (0 — если не нашлось). */
    public static int freePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ждём, пока на порту ответит SOCKS5 (не просто «порт открыт»). */
    public static boolean waitSocks(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ConfigRepository.isCancelled()) return false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                socket.setSoTimeout(500);
                socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
                InputStream in = socket.getInputStream();
                int a = in.read();
                int b = in.read();
                if (a == 5 && b == 0) return true;
            } catch (Exception ignored) {
                // ещё не готов
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Замер через уже поднятый SOCKS: перебираем URL замера, пока какой-нибудь не отдаст
     * данные. Возвращаем лучший результат вместе с деталями («какой URL, сколько, за сколько»).
     */
    public static Speed measureSpeed(Context ctx, int socksPort, int timeoutMs) {
        return measureSpeed(ctx, socksPort, timeoutMs, Integer.MAX_VALUE);
    }

    /**
     * То же, но не более maxUrls попыток: для отбраковки в поиске хватает двух URL,
     * иначе каждый мёртвый конфиг стоит три таймаута.
     */
    public static Speed measureSpeed(Context ctx, int socksPort, int timeoutMs, int maxUrls) {
        long best = -1;
        String bestUrl = "";
        long bestBytes = 0;
        long bestMs = 0;
        int attempts = 0;
        String lastError = "";
        boolean emptyAnswer = false;
        for (String url : speedPlan(Prefs.speedUrls(ctx), maxUrls)) {
            if (ConfigRepository.isCancelled()) break;
            attempts++;
            NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(
                    url, "127.0.0.1", socksPort, timeoutMs, MAX_SAMPLE_BYTES);
            long kbps = r.kbps();
            if (kbps > best) {
                best = kbps;
                bestUrl = url;
                bestBytes = r.bytes;
                bestMs = r.ms;
            }
            // 204/пустой ответ доказывает, что канал живой, но мерить в нём нечего —
            // такой URL не должен считаться «нет данных», идём к следующему.
            if (r.bytes == 0 && r.code >= 200 && r.code < 400) {
                emptyAnswer = true;
                continue;
            }
            if (best <= 0) lastError = r.reason();
            if (best > 0) break; // первого успешного замера достаточно
        }
        if (best <= 0 && emptyAnswer && lastError.isEmpty()) {
            lastError = "серверы ответили 204 без тела — мерить нечего";
        }
        return new Speed(best, bestUrl, bestBytes, bestMs, attempts, lastError, emptyAnswer);
    }

    /**
     * Какие URL пробовать, когда попыток мало (отбраковка в поиске).
     *
     * Тонкость: первым в списке идёт список конфигов с GitHub — он заодно доказывает,
     * что через конфиг доступен зарубежный источник. Но многие «белые» конфиги пускают
     * только Cloudflare/CacheFly, а GitHub — нет. Если пробовать ровно первые N URL
     * подряд, такие рабочие конфиги отбраковывались как мёртвые (это и было причиной
     * «быстрый поиск ничего не находит»). Поэтому в план всегда попадает один
     * «дружелюбный» источник с CDN.
     */
    /**
     * План замера: какие URL пробовать и в каком порядке.
     *
     * Отдельно стоят ответы `generate_204`: они доказывают, что канал живой, но тела в них
     * нет — мерить нечего. Поэтому они идут в самом конце, только если ничего с телом не
     * отдалось. В короткой проверке первым берётся небольшой файл с CDN (он отвечает почти
     * везде), а «настоящий» источник с GitHub измеряет уже реальную скорость.
     */
    static java.util.List<String> speedPlan(java.util.List<String> urls, int maxUrls) {
        java.util.List<String> plan = new java.util.ArrayList<>();
        if (urls == null || urls.isEmpty() || maxUrls <= 0) return plan;
        if (maxUrls <= 3) {
            for (String u : urls) {
                if (isCdnFriendly(u) && !isBodyless(u) && !plan.contains(u)) { plan.add(u); break; }
            }
        }
        // В коротком плане (2 попытки: поиск, подключение) последний слот отдаём ответу
        // generate_204. Иначе «белый» конфиг, который пускает только Cloudflare/Google,
        // проверялся двумя адресами GitHub, не отвечал ни на один — и считался мёртвым,
        // хотя канал работает. Скорость по 204 не считается, но связность доказана.
        int bodySlots = maxUrls <= 3 ? Math.max(1, maxUrls - 1) : maxUrls;
        // сначала всё, в чём есть тело — по нему можно посчитать скорость
        for (String u : urls) {
            if (plan.size() >= bodySlots) break;
            if (!plan.contains(u) && !isBodyless(u)) plan.add(u);
        }
        // generate_204 — в конце: связность подтвердят, скорость — нет
        for (String u : urls) {
            if (plan.size() >= maxUrls) break;
            if (!plan.contains(u)) plan.add(u);
        }
        return plan;
    }

    /** Ответ без тела: `generate_204` и подобные — для скорости не годятся. */
    private static boolean isBodyless(String url) {
        return url != null && url.toLowerCase().contains("generate_204");
    }

    /**
     * Небольшие файлы с CDN доступны там, где GitHub уже закрыт — их и пробуем первыми
     * в короткой проверке. Ответы generate_204 сюда не годятся: у них нет тела, а значит
     * и скорости, которую можно измерить.
     */
    private static boolean isCdnFriendly(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        if (isBodyless(u)) return false;
        return u.contains("cloudflare") || u.contains("cachefly");
    }

    /** Скорость канала через уже поднятый SOCKS, КБ/с. -1 — данных не получили. */
    public static long speedKbps(Context ctx, int socksPort, int timeoutMs) {
        return measureSpeed(ctx, socksPort, timeoutMs).kbps;
    }

    /**
     * То же, но с ограничением числа URL: подключению хватает двух попыток, иначе
     * перебор всех источников растягивает каждую попытку подключения.
     */
    public static long speedKbps(Context ctx, int socksPort, int timeoutMs, int maxUrls) {
        return measureSpeed(ctx, socksPort, timeoutMs, maxUrls).kbps;
    }

    /**
     * Запасной заход по одному. Нужен, когда параллельный замер не дал ни одного рабочего:
     * на медленном канале скорость делится между всеми дорожками, и каждый конфиг получает
     * лишь часть — при пороге 16 КБ/с пул может остаться пустым при живых серверах.
     */
    public static java.util.List<Pool.Entry> measureSequential(Context ctx,
                                                              java.util.List<ProxyConfig> alive,
                                                              int limit) {
        java.util.List<Pool.Entry> out = new java.util.ArrayList<>();
        if (alive == null || alive.isEmpty()) return out;
        int min = Prefs.minSpeedKbps(ctx);
        int gate = Prefs.gateTimeoutMs(ctx);
        int n = Math.min(limit, alive.size());
        for (int i = 0; i < n; i++) {
            if (ConfigRepository.isCancelled()) break;
            ProxyConfig cfg = alive.get(i);
            Result r = measureConfig(ctx, cfg, gate);
            if (r.ok && r.kbps >= min) {
                out.add(new Pool.Entry(cfg.raw, r.kbps, cfg.latency, System.currentTimeMillis()));
            }
        }
        return out;
    }

    /**
     * Отбросить то, что ядро не поднимет НИКОГДА (например, VLESS без TLS к публичному
     * адресу — Xray 26 такое запрещает на этапе разбора конфига).
     *
     * Это важно для скорости: у таких конфигов TCP-пинг проходит, они считаются
     * «живыми» и занимают дорожку замера, а потом падают с одной и той же ошибкой.
     * В отчёте из 90 замеров так уходило 61.
     *
     * @param rejects сюда складываются причины (причина → сколько конфигов)
     * @return только те конфиги, которые ядро в принципе примет
     */
    public static java.util.List<ProxyConfig> usable(java.util.List<ProxyConfig> in,
                                                     java.util.Map<String, Integer> rejects,
                                                     boolean allowRelay) {
        java.util.List<ProxyConfig> out = new java.util.ArrayList<>();
        if (in == null) return out;
        for (ProxyConfig cfg : in) {
            String bad = cfg == null ? "пустой конфиг" : XrayEngine.compatProblem(cfg, allowRelay);
            if (bad == null) {
                out.add(cfg);
            } else if (rejects != null) {
                Integer n = rejects.get(bad);
                rejects.put(bad, n == null ? 1 : n + 1);
            }
        }
        return out;
    }

    /** «183 (ядро Xray 26 не пускает без TLS — нужен security=tls или reality — 175, …)». */
    public static String rejectsText(java.util.Map<String, Integer> rejects) {
        if (rejects == null || rejects.isEmpty()) return "";
        int total = 0;
        java.util.List<java.util.Map.Entry<String, Integer>> list =
                new java.util.ArrayList<>(rejects.entrySet());
        for (java.util.Map.Entry<String, Integer> e : list) total += e.getValue();
        java.util.Collections.sort(list, new java.util.Comparator<java.util.Map.Entry<String, Integer>>() {
            @Override public int compare(java.util.Map.Entry<String, Integer> a,
                                         java.util.Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        StringBuilder sb = new StringBuilder("Несовместимы с ядром: ").append(total).append(" (");
        int shown = 0;
        for (java.util.Map.Entry<String, Integer> e : list) {
            if (shown++ >= 3) break;
            if (shown > 1) sb.append("; ");
            String reason = e.getKey();
            if (reason.length() > 60) reason = reason.substring(0, 60) + "…";
            sb.append(reason).append(" — ").append(e.getValue());
        }
        int rest = list.size() - Math.min(3, list.size());
        if (rest > 0) sb.append("; ещё причин ").append(rest);
        return sb.append(')').toString();
    }

    /** Проверить конфиг «с нуля»: поднять, замерить, погасить. */
    public static Result measureConfig(Context ctx, ProxyConfig cfg, int timeoutMs) {
        return measureConfig(ctx, cfg, timeoutMs, null);
    }

    /**
     * То же, но линия до сервера идёт через локальный ciadpi со стратегией
     * {@code dpiStrategy}. Так находится связка «конфиг ↔ стратегия»: конфиг, который
     * напрямую не отдаёт данных, часто работает через фрагментацию TLS.
     *
     * @param dpiStrategy null/"" — мерить напрямую
     */
    public static Result measureConfig(Context ctx, ProxyConfig cfg, int timeoutMs,
                                       String dpiStrategy) {
        if (cfg == null) return Result.fail("пустой конфиг");
        if (!cfg.supported) return Result.fail("схема " + cfg.scheme + " не поддерживается");
        String bad = XrayEngine.compatProblem(cfg, Prefs.allowNoTls(ctx));
        if (bad != null) return Result.fail("несовместим с ядром: " + bad);
        if (ConfigRepository.isCancelled()) return Result.fail("поиск остановлен");
        int port = freePort();
        if (port <= 0) return Result.fail("нет свободного порта");

        boolean viaDpi = dpiStrategy != null && !dpiStrategy.trim().isEmpty();
        int desyncPort = 0;
        if (viaDpi) {
            String sni = cfg.tlsServerName();
            if (sni == null || sni.isEmpty()) sni = cfg.host;
            desyncPort = DesyncLine.start(dpiStrategy, sni);
            if (desyncPort <= 0) return Result.fail("DPI-обход не поднялся");
        }

        CoreController controller = null;
        try {
            String json = XrayEngine.buildConfigJson(cfg, port, false, Prefs.vpnDns(ctx),
                    desyncPort);
            controller = XrayEngine.startInstance(ctx, json);
        } catch (Throwable t) {
            String m = XrayEngine.explainCoreError(t.getMessage());
            if (viaDpi) DesyncLine.stop();
            return Result.dead(m.startsWith("ядро") ? m : "ядро не запустилось: " + m);
        }
        try {
            if (!waitSocks(port, SOCKS_WAIT_MS)) {
                return new Result(false, -1, port,
                        "SOCKS не открылся за " + (SOCKS_WAIT_MS / 1000) + " с", null, true);
            }
            // Три попытки: GitHub может быть закрыт, Cloudflare — открыт (и наоборот).
            Speed s = measureSpeed(ctx, port, timeoutMs, 3);
            if (s.ok()) return new Result(true, s.kbps, port, "", s);
            // Ответ 204 доказывает только связность. Скорость по нему не измеряется, а
            // конфиг без скорости бесполезен для подключения: раньше такой замер
            // засчитывался «рабочим» с фиктивной минимальной скоростью и попадал в пул,
            // где занимал место и время. С 0.1.15 это провал — и «жёсткий»: конфиг уходит
            // в чёрный список на неделю, как и любой другой, который не дал данных.
            if (s.alive()) {
                return new Result(false, -1, port,
                        "связность есть, скорость не измерена", s, true);
            }
            return new Result(false, -1, port, s.text(), s, true);
        } finally {
            XrayEngine.stopInstance(controller);
            if (viaDpi) DesyncLine.stop();
        }
    }
}
