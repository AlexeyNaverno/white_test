package ru.whitelist.bypass;

import android.content.Context;

import java.util.List;

/**
 * Резервный режим: весь трафик устройства идёт через Tor-мосты.
 *
 * Зачем: когда ни один VPN-конфиг не отвечает (или канал умер, а других рабочих
 * конфигов в пуле нет), лучше медленный Tor, чем «нет связи». Включается только по
 * факту отказа — как обычный режим Tor не используется.
 *
 * Как устроено:
 *   1) поднимаем Tor с мостами (теми же, что и для скачивания списков; если мосты уже
 *      замерены кнопкой «Проверить мосты» — с самых быстрых);
 *   2) замеряем скорость через локальный SOCKS 9050 тем же способом, что и «проверить
 *      мосты»: качаем небольшой файл и считаем КБ/с. Ниже порога — резерв не включаем:
 *      «работает, но ничего не открывает» пользователю не нужно;
 *   3) только после этого создаём TUN и пускаем его в SOCKS Tor (hev-socks5-tunnel).
 *
 * DNS решаем без своей сети: в конфиге hev включаем mapdns — запросы к {@link #DNS}
 * перехватываются на месте и превращаются в «fake IP», а имена резолвит уже сам Tor
 * при подключении к SOCKS. Так DNS не течёт мимо Tor и не требует UDP (которого у Tor
 * нет).
 */
public final class TorTunnel {
    private TorTunnel() {}

    /** Локальный SOCKS Tor (torrc: SocksPort). */
    public static final int SOCKS_PORT = TorEngine.SOCKS_PORT;

    /** Адрес «DNS», который перехватывает hev (mapdns) — его же отдаём в TUN. */
    public static final String DNS = "198.18.0.2";
    /** Сеть fake-IP для mapdns: 198.18.0.0/15 (диапазон для тестовых сетей, ни с чем не пересекается). */
    public static final String NETWORK = "198.18.0.0";
    public static final String NETMASK = "255.254.0.0";

    /** Сколько ждём, пока Tor поднимется с мостами. */
    public static final long START_TIMEOUT_MS = 90_000;
    /** Сколько ждём на замер скорости через Tor: он медленный, спешить некуда. */
    public static final int SPEED_TIMEOUT_MS = 20_000;
    /** Сколько максимум качаем для замера. */
    private static final long SPEED_BYTES = 128 * 1024;
    /** Если файлы замера не отдались — этот адрес почти всегда открывается через Tor. */
    private static final String FALLBACK_URL = "https://duckduckgo.com/";

    /** Поднять Tor с мостами и дождаться SOCKS. */
    public static boolean start(Context c) {
        return TorEngine.ensureStarted(c, START_TIMEOUT_MS);
    }

    /** Погасить Tor (только когда он был поднят нами ради туннеля). */
    public static void stop(Context c) {
        TorEngine.stop(c);
    }

    /**
     * Скорость резервного канала, КБ/с. -1 — измерить не удалось
     * (или данных пришло меньше {@link Probe#MIN_SAMPLE_BYTES} — такую скорость
     * считать нельзя).
     */
    public static long measureKbps(Context c, int timeoutMs) {
        long best = -1;
        for (String url : probeUrls(c)) {
            if (ConfigRepository.isCancelled()) break;
            NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(
                    url, "127.0.0.1", SOCKS_PORT, timeoutMs, SPEED_BYTES);
            long kbps = r.kbps();
            if (kbps > best) best = kbps;
            if (best > 0) break;
        }
        return best;
    }

    /** Порог для резерва: тот же, что и у пула, но с допуском «сеть дышит». */
    public static boolean fastEnough(Context c, long kbps) {
        long need = Math.max(1L, (long) (Prefs.minSpeedKbps(c) * 0.8));
        return kbps >= need;
    }

    /**
     * Чем мерить Tor: сначала небольшой файл с CDN (он открывается и там, где GitHub
     * закрыт), потом обычная страница. Списки конфигов с GitHub через Tor качать
     * бессмысленно — они большие, а нам нужна лишь оценка скорости.
     */
    private static List<String> probeUrls(Context c) {
        List<String> urls = new java.util.ArrayList<>();
        for (String u : Prefs.speedUrls(c)) {
            if (u != null && (u.toLowerCase().contains("cloudflare")
                    || u.toLowerCase().contains("cachefly"))) {
                urls.add(u);
                break;
            }
        }
        urls.add(FALLBACK_URL);
        return urls;
    }
}
