package ru.whitelist.bypass;

import java.util.ArrayList;
import java.util.List;

/**
 * Целевой регион подключения: Европа, Азия или Америка.
 *
 * Зачем. Скорость через VPN зависит не только от сервера, но и от того, куда он стоит:
 * конфиг с европейской точкой входа и конфиг с азиатской меряются по разным маршрутам,
 * поэтому «одна и та же» проверка даёт разный результат. Регион задаёт ДВЕ вещи:
 *
 *   • цель TCP-пинга — один адрес (host:port), по которому видно, что интернет вообще есть;
 *   • адрес для замера скорости — ОДНА ссылка, по которой качается файл через туннель.
 *
 * Раньше и там, и там был набор из четырёх-пяти адресов: сначала GitHub, потом
 * Cloudflare, потом ещё что-то. Это маскировало проблему — «работает» могло означать
 * «сработал один из пяти», а скорость при этом мерилась по другому хосту, чем пинг.
 * Теперь на регион ровно одна пара адресов, и они видны в настройках.
 *
 * Почему такие адреса:
 *   Европа и Америка — GitHub (raw.githubusercontent.com). Он отдаётся краем Fastly,
 *   у которого есть и европейские, и американские узлы; файл списка конфигов маленький
 *   (десятки КБ), и его нельзя «разрешить точечно» под один сервис — значит, он честно
 *   показывает, что канал открывает интернет, а не только YouTube.
 *   Азия — региональные сайты Lenovo (Япония и Индия). GitHub в азиатских сетях часто
 *   медленнее и хуже достижим, а крупный корпоративный сайт отдаётся локальным краем CDN,
 *   не входит в белые списки и даёт достаточно байт для замера.
 *
 * Все значения можно переопределить в настройках: «Поиск и пинг» → «Целевой регион».
 */
public final class Regions {

    private Regions() {}

    public static final String EU = "eu";
    public static final String ASIA = "asia";
    public static final String US = "us";

    /** По умолчанию — Европа: ближайший к пользователю регион и самый ходовой набор серверов. */
    public static final String DEFAULT = EU;

    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        out.add(EU);
        out.add(ASIA);
        out.add(US);
        return out;
    }

    public static String normalize(String id) {
        if (id == null) return DEFAULT;
        String v = id.trim().toLowerCase();
        if (ASIA.equals(v) || US.equals(v) || EU.equals(v)) return v;
        return DEFAULT;
    }

    public static String label(String id) {
        String v = normalize(id);
        if (ASIA.equals(v)) return "Азия";
        if (US.equals(v)) return "Америка";
        return "Европа";
    }

    /** Короткое объяснение «почему этот сайт» для настроек и подсказок. */
    public static String note(String id) {
        String v = normalize(id);
        if (ASIA.equals(v)) {
            return "Азия: пинг — Lenovo (Япония), загрузка — его же главная страница. "
                    + "GitHub в азиатских сетях часто медленнее и хуже достижим, а этот "
                    + "сайт отдаётся локальным краем CDN и не входит в белые списки.";
        }
        if (US.equals(v)) {
            return "Америка: пинг и загрузка — GitHub (американский край Fastly). Тот же "
                    + "принцип, что и для Европы: адрес нельзя разрешить точечно, поэтому "
                    + "он показывает настоящий канал.";
        }
        return "Европа: пинг и загрузка — GitHub (европейский край Fastly). Файл списка "
                + "конфигов маленький, но его нельзя «разрешить точечно» под один сервис, "
                + "поэтому по нему видно, что канал открывает интернет целиком.";
    }

    /** Цель TCP-пинга (host:port). Ровно одна на регион. */
    public static String pingTarget(String id) {
        String v = normalize(id);
        if (ASIA.equals(v)) return "www.lenovo.co.jp:443";
        return "raw.githubusercontent.com:443";     // Европа и Америка
    }

    /** Ссылка для замера скорости. Ровно одна на регион. */
    public static String speedUrl(String id) {
        String v = normalize(id);
        if (ASIA.equals(v)) {
            return "https://www.lenovo.co.jp/";
        }
        if (US.equals(v)) {
            return "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia"
                    + "/main/data/githubmirror/ru-sni-local/vless.txt";
        }
        return "https://raw.githubusercontent.com/luxxuria/harvester/main/ping_tested.txt";
    }

    /** Адрес проверки канала через туннель (монитор, «есть ли связь»). */
    public static String healthUrl(String id) {
        return speedUrl(id);
    }
}
