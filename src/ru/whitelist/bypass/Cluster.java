package ru.whitelist.bypass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Кластеры конфигов: «кто с кем живёт одной судьбой».
 *
 * Зачем. В публичных сборниках строки идут не случайно: рядом стоят конфиги одного
 * провайдера — тот же домен в SNI (`…my-fade.cloud`), тот же порт, та же схема. Если
 * сервер такого провайдера жив, то и соседние строки, скорее всего, живые; если лёг —
 * проверять сотню его строк бессмысленно. Отсюда две вещи:
 *
 *   1) обход идёт не «по порядку строк», а «по кластерам» — первая партия состоит из
 *      РАЗНЫХ провайдеров, поэтому рабочий конфиг находится на первых секундах, а не
 *      после того, как переберём три сотни строк одного упавшего сервиса;
 *   2) вердикт кластера запоминается ({@link ClusterStore}) и живёт с TTL: упавшего
 *      провайдера в следующий раз пропускаем целиком.
 *
 * Группировка идёт по инфраструктуре, а не по позиции в файле: позиция — это лотерея
 * (список может быть отсортирован по эмодзи в имени), а домен из SNI и подсеть — факт.
 *
 * Ключи (в порядке устойчивости):
 *   • провайдер — регистрируемый домен от SNI/host (`my-fade.cloud`);
 *   • линия — `host:port` (её же использует {@link FastPing});
 *   • протокол+порт — `hysteria2:443`: сборники часто склеены блоками по схеме.
 */
public final class Cluster {
    private Cluster() {}

    /**
     * Домены, у которых регистрируемая часть — три метки, а не две.
     * Список короткий намеренно: он нужен только для группировки, ошибка на одну метку
     * в худшем случае разложит провайдера на два кластера, а не сломает проверку.
     */
    private static final String[] TWO_LEVEL_SUFFIXES = {
            "co.uk", "org.uk", "ac.uk", "com.ru", "net.ru", "org.ru", "msk.ru", "spb.ru",
            "com.ua", "co.de", "com.tr", "com.br", "co.jp", "or.jp", "ne.jp",
    };

    private static boolean isIp(String host) {
        if (host == null || host.isEmpty()) return true;
        boolean v4 = true;
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch == '.') {
                dots++;
            } else if (ch != ':' && !((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                v4 = false;
            }
        }
        // IPv6 приходит в квадратных скобках, IPv4 — четыре группы цифр.
        if (host.startsWith("[") || host.indexOf(':') >= 0) return true;
        return v4 && dots == 3;
    }

    /** Регистрируемый домен: `piodr-ru-74sel.my-fade.cloud` → `my-fade.cloud`. */
    private static String registrable(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase();
        if (h.isEmpty()) return "";
        if (isIp(h)) return "";
        while (h.startsWith(".")) h = h.substring(1);
        String[] parts = h.split("\\.");
        if (parts.length <= 2) return h;
        String lastTwo = parts[parts.length - 2] + "." + parts[parts.length - 1];
        for (String two : TWO_LEVEL_SUFFIXES) {
            if (lastTwo.equals(two)) {
                return parts.length >= 3
                        ? parts[parts.length - 3] + "." + lastTwo : h;
            }
        }
        return lastTwo;
    }

    /** /24 для IPv4 — «та же подсеть провайдера». */
    private static String subnet(String host) {
        if (host == null) return "";
        String h = host.trim();
        if (h.isEmpty()) return "";
        if (h.startsWith("[")) return h;                 // IPv6 — берём как есть
        String[] parts = h.split("\\.");
        if (parts.length == 4) return parts[0] + "." + parts[1] + "." + parts[2] + ".0/24";
        return h;
    }

    /**
     * Ключ кластера: провайдер (домен из SNI), иначе подсеть, иначе сам хост.
     * SNI важнее host: у разных поддоменов одного провайдера хост может быть разным,
     * а SNI — общий.
     */
    public static String key(ProxyConfig cfg) {
        if (cfg == null) return "";
        String sni = cfg.tlsServerName();
        String fromSni = registrable(sni);
        if (!fromSni.isEmpty()) return "d:" + fromSni;
        String fromHost = registrable(cfg.host);
        if (!fromHost.isEmpty()) return "d:" + fromHost;
        String sub = subnet(cfg.host);
        if (!sub.isEmpty()) return "i:" + sub;
        return "h:" + (cfg.host == null ? "" : cfg.host.toLowerCase());
    }

    /** Линия: одна TCP-судьба на всех (`host:port`). */
    public static String line(ProxyConfig cfg) {
        if (cfg == null || cfg.host == null) return "";
        return cfg.host.toLowerCase() + ":" + cfg.port;
    }

    /** Протокол и порт: сборники часто склеены блоками по схеме. */
    public static String proto(ProxyConfig cfg) {
        if (cfg == null) return "";
        return (cfg.scheme == null ? "" : cfg.scheme.toLowerCase()) + ":" + cfg.port;
    }

    /** Человеческое имя кластера — для журнала и экрана «Рабочие». */
    public static String label(String key) {
        if (key == null || key.length() < 3) return "неизвестный";
        if (key.startsWith("d:")) return key.substring(2);
        if (key.startsWith("i:")) return key.substring(2);
        if (key.startsWith("h:")) return key.substring(2);
        return key;
    }

    /**
     * Разложить конфиги по кластерам, сохраняя порядок первого появления.
     * Внутри кластера — исходный порядок строк.
     */
    public static Map<String, List<ProxyConfig>> group(List<ProxyConfig> in) {
        Map<String, List<ProxyConfig>> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (ProxyConfig cfg : in) {
            if (cfg == null) continue;
            String key = key(cfg);
            List<ProxyConfig> list = out.get(key);
            if (list == null) {
                list = new ArrayList<>();
                out.put(key, list);
            }
            list.add(cfg);
        }
        return out;
    }

    /**
     * «Чересстрочная» раскладка: берём по одному конфигу из каждого кластера, потом по
     * второму и так далее (round-robin). Первые N строк такого списка — N РАЗНЫХ
     * провайдеров, поэтому партия из 100 сразу проверяет 100 независимых кластеров,
     * а не 100 строк одной упавшей сети.
     *
     * @param clusters     сгруппированные конфиги (порядок кластеров сохраняется)
     * @param skipClusters кластеры, которые уже признаны мёртвыми (проверяются последними)
     */
    public static List<ProxyConfig> interleave(Map<String, List<ProxyConfig>> clusters,
                                               Set<String> skipClusters) {
        List<ProxyConfig> out = new ArrayList<>();
        if (clusters == null || clusters.isEmpty()) return out;
        Set<String> skip = skipClusters == null ? Collections.<String>emptySet() : skipClusters;

        List<List<ProxyConfig>> live = new ArrayList<>();
        List<List<ProxyConfig>> dead = new ArrayList<>();
        for (Map.Entry<String, List<ProxyConfig>> e : clusters.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (skip.contains(e.getKey())) dead.add(e.getValue());
            else live.add(e.getValue());
        }
        interleaveInto(out, live);
        interleaveInto(out, dead);
        return out;
    }

    private static void interleaveInto(List<ProxyConfig> out, List<List<ProxyConfig>> lists) {
        int max = 0;
        for (List<ProxyConfig> l : lists) max = Math.max(max, l.size());
        for (int i = 0; i < max; i++) {
            for (List<ProxyConfig> l : lists) {
                if (i < l.size()) out.add(l.get(i));
            }
        }
    }

    /**
     * Дедупликация: одна строка — один ключ. Плюс подсказка, сколько строк было
     * «тем же конфигом» (canonicalKey совпал) и сколько — «тем же сервером с другими
     * кредами» (это НЕ дубликат, такие оставляем).
     */
    public static List<ProxyConfig> dedupe(List<ProxyConfig> in) {
        List<ProxyConfig> out = new ArrayList<>();
        if (in == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (ProxyConfig cfg : in) {
            if (cfg == null) continue;
            String key = ConfigRepository.canonicalKey(cfg.raw);
            if (key == null || key.isEmpty()) key = cfg.raw;
            if (!seen.add(key)) continue;
            out.add(cfg);
        }
        return out;
    }

    /** Кластеры, у которых в наборе больше одной строки — то есть где выборка что-то решает. */
    public static int multiLineClusters(Map<String, List<ProxyConfig>> clusters) {
        int n = 0;
        for (List<ProxyConfig> l : clusters.values()) if (l != null && l.size() > 1) n++;
        return n;
    }

    /** Сравнение по «дешевизне» для журнала: сначала большие кластеры. */
    public static Comparator<Map.Entry<String, List<ProxyConfig>>> bySize() {
        return new Comparator<Map.Entry<String, List<ProxyConfig>>>() {
            @Override public int compare(Map.Entry<String, List<ProxyConfig>> a,
                                         Map.Entry<String, List<ProxyConfig>> b) {
                return b.getValue().size() - a.getValue().size();
            }
        };
    }
}
