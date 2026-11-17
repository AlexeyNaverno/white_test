package ru.whitelist.bypass;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Раздельное туннелирование: российские сайты и приложения — мимо VPN.
 *
 * Зачем. Через VPN не нужно гонять то, что и так открывается: банк, госуслуги, маркетплейсы,
 * карты, такси. И дело не только в скорости: банки и госсервисы часто отказывают в работе
 * с «иностранного» IP или требуют подтверждения, а часть из них блокирует запросы из-за
 * границы. Поэтому российский трафик идёт напрямую, а через туннель — только то, что без
 * него не открывается.
 *
 * Два независимых механизма:
 *
 *  1) САЙТЫ (домены). Ядро Xray получает правила маршрутизации: домены из списка уходят
 *     в outbound «direct» (freedom), то есть открываются с телефона напрямую. Домены
 *     приходят из sniffing: ядро видит имя из TLS/HTTP, поэтому правило работает и для
 *     браузера, и для приложений, которые ходят по доменным именам.
 *     Список не «гео-база»: geoip.dat/geosite.dat в APK нет и он весил бы десятки
 *     мегабайт. Вместо этого — компактный встроенный список: зона .ru/.рф целиком плюс
 *     крупные сервисы на других зонах (vk.com, yandex.com, ozon.com и т.п.).
 *
 *  2) ПРИЛОЖЕНИЯ (пакеты). В VpnService.Builder есть addDisallowedApplication: трафик
 *     такого приложения вообще не попадает в tun — оно ходит как без VPN. Это надёжнее
 *     доменных правил для приложений, которые лезут на CDN и по IP: банк, госуслуги,
 *     маркетплейсы, такси, оператор.
 *
 * Оба механизма включаются отдельно (настройки → «Обход» → «Российское — мимо VPN»),
 * и оба можно расширить своим списком. Трафик в режиме «только DPI» не затрагивается:
 * там нет VPN-сервера, и разделять нечего.
 */
public final class SplitTunnel {

    private SplitTunnel() {}

    /**
     * Домены, которые открываются напрямую. Без префиксов: Xray понимает строку как
     * «этот домен и все поддомены».
     */
    private static final String[] RU_DOMAINS = {
            // Зоны целиком: .ru и .рф (punycode).
            "ru",
            "xn--p1ai",
            // Яндекс (в т.ч. CDN и метрика).
            "yandex.ru", "yandex.com", "yandex.net", "yandexcloud.net", "ya.ru",
            "yastatic.net", "yandex-team.ru", "kinopoisk.ru", "dzen.ru", "market.yandex.ru",
            // VK / Mail.ru.
            "vk.com", "vk.ru", "vk.me", "vk-cdn.net", "userapi.com", "mycdn.me",
            "ok.ru", "okcdn.ru", "mail.ru", "imgsmail.ru", "list.ru", "bk.ru", "inbox.ru",
            "max.ru", "oneme.ru",
            // Банки и платежи.
            "sber.ru", "sberbank.com", "sberbank.ru", "sbol.ru",
            "tbank.ru", "tinkoff.ru", "tinkoff.com",
            "vtb.ru", "vtb24.ru", "alfabank.ru", "alfa-bank.ru", "gazprombank.ru",
            "psbank.ru", "open.ru", "rosbank.ru", "sovcombank.ru", "mtsbank.ru",
            "pochtabank.ru", "homecredit.ru", "halvacard.ru", "raiffeisen.ru",
            "nspk.ru", "mironline.ru", "pay.mts.ru",
            // Государство.
            "gosuslugi.ru", "gu-st.ru", "esia.gosuslugi.ru", "nalog.ru", "nalog.gov.ru",
            "mos.ru", "mos-gorsud.ru", "pfr.gov.ru", "sfr.gov.ru", "mvd.ru", "gibdd.ru",
            // Маркетплейсы и магазины.
            "ozon.ru", "ozon.com", "ozone.ru", "wildberries.ru", "wb.ru", "wbbasket.ru",
            "wbstatic.net", "avito.ru", "avito.com", "avito.st", "dns-shop.ru",
            "mvideo.ru", "eldorado.ru", "citilink.ru", "technopark.ru", "lamoda.ru",
            "aliexpress.ru", "megamarket.ru", "sbermegamarket.ru",
            // Медиа и развлечения.
            "rutube.ru", "rutubelist.ru", "ivi.ru", "okko.tv", "more.tv", "wink.ru",
            "start.ru", "premier.one", "zvuk.com", "litres.ru", "bookmate.com",
            // Связь и инфраструктура.
            "mts.ru", "beeline.ru", "megafon.ru", "t2.ru", "tele2.ru", "yota.ru",
            "rt.ru", "rostelecom.ru", "ertelecom.ru", "ttk.ru", "mgts.ru",
            // Транспорт, карты, путешествия.
            "2gis.ru", "2gis.com", "2gis-cdn.ru", "rzd.ru", "tutu.ru", "aviasales.ru",
            "aeroflot.ru", "s7.ru", "mosmetro.ru", "transport.mos.ru", "citymapper.com",
            // Работа, учёба, софт.
            "hh.ru", "rabota.ru", "superjob.ru", "1c.ru", "kontur.ru", "sbis.ru",
            "kaspersky.ru", "drweb.ru", "consultant.ru", "garant.ru",
            // Новости и погода.
            "rbc.ru", "lenta.ru", "ria.ru", "tass.ru", "kommersant.ru", "vedomosti.ru",
            "forbes.ru", "gismeteo.ru", "sports.ru", "championat.com",
            // Еда и доставка.
            "dodopizza.ru", "dominos.ru", "delivery-club.ru", "samokat.ru",
            "vkusvill.ru", "perekrestok.ru", "magnit.ru", "pyaterochka.ru"
    };

    /**
     * Пакеты российских приложений, которые не пускаем в туннель.
     *
     * ВАЖНО: список — только подсказка. Реально применяются те пакеты, что установлены:
     * addDisallowedApplication на отсутствующий пакет бросает исключение.
     */
    private static final String[] RU_PACKAGES = {
            // Банки и платежи.
            "ru.sberbankmobile",
            "ru.sberbank.sberbank",
            "com.idamob.tinkoff.android",
            "ru.tinkoff.investing",
            "ru.vtb24.mobilebanking.android",
            "ru.vtb.invest",
            "ru.alfabank.mobile.android",
            "ru.alfabank.oavdo.amc",
            "ru.gazprombank.android.mobilebank",
            "ru.psbank.mobile",
            "ru.openbank.mobile",
            "ru.rosbank.android",
            "com.sovcombank.mobile",
            "ru.mts.mymts",
            "ru.mtsbank.mobile",
            "ru.pochtabank.mobile",
            "ru.homecredit.mobile",
            "ru.halva.app",
            "ru.raiffeisen.mobile",
            "ru.nspk.mirpay",
            // Государство.
            "ru.gosuslugi.gosuslugi",
            "ru.mos.app",
            "ru.nalog.mobile",
            "ru.mvd.mobile",
            // Яндекс.
            "ru.yandex.searchplugin",
            "ru.yandex.yandexmaps",
            "ru.yandex.yandexnavi",
            "ru.yandex.taxi",
            "ru.yandex.market",
            "ru.yandex.music",
            "ru.yandex.disk",
            "ru.yandex.mail",
            "ru.yandex.kinopoisk",
            "ru.yandex.rasp",
            "com.yandex.browser",
            "com.yandex.launcher",
            // VK / Mail.ru / MAX.
            "com.vkontakte.android",
            "ru.ok.android",
            "ru.mail.mailapp",
            "ru.mail.cloud",
            "ru.max.messenger",
            "com.my.mail",
            // Маркетплейсы и магазины.
            "ru.ozon.app.android",
            "com.wildberries.ru",
            "com.avito.android",
            "ru.dns.shop.android",
            "ru.mvideo.android",
            "ru.citilink.app",
            "ru.lamoda.app",
            "ru.eldorado.app",
            // Медиа.
            "ru.rutube",
            "ru.ivi.client",
            "ru.more.tv",
            "ru.wink",
            "ru.zvuk",
            "ru.litres.android",
            // Связь.
            "ru.beeline.services",
            "ru.megafon.lk",
            "com.tele2.mobile",
            "ru.yota.app",
            // Такси, карты, транспорт.
            "ru.taxi.mobile",
            "ru.dublgis.dgismobile",
            "ru.rzd.passenger",
            "ru.mosmetro.metro",
            // Еда и доставка.
            "ru.dodopizza.app",
            "ru.samokat",
            "ru.vkusvill",
            "ru.perekrestok.app",
            "ru.magnit.app"
    };

    /** Включено ли разделение по сайтам. */
    public static boolean domainsEnabled(Context c) {
        return Prefs.splitRuDomains(c);
    }

    /** Включено ли разделение по приложениям. */
    public static boolean appsEnabled(Context c) {
        return Prefs.splitRuApps(c);
    }

    /** Домены для прямого выхода: встроенные + добавленные пользователем. */
    public static List<String> domains(Context c) {
        Set<String> out = new LinkedHashSet<>();
        for (String d : RU_DOMAINS) out.add(d);
        for (String d : Prefs.splitExtraDomains(c)) {
            String v = d.trim().toLowerCase();
            if (v.isEmpty()) continue;
            if (v.startsWith("full:") || v.startsWith("domain:") || v.startsWith("regexp:")) {
                out.add(v);
            } else {
                out.add(v);
            }
        }
        List<String> list = new ArrayList<>(out);
        list.remove("");
        return list;
    }

    /** Встроенный список доменов (для показа в настройках). */
    public static String[] builtinDomains() {
        return RU_DOMAINS.clone();
    }

    /** Встроенный список пакетов (для показа в настройках). */
    public static String[] builtinPackages() {
        return RU_PACKAGES.clone();
    }

    /**
     * Пакеты, которые реально можно исключить на этом телефоне: установленные сейчас
     * (встроенные + добавленные пользователем).
     */
    public static List<String> installedPackages(Context c) {
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        if (pm == null) return out;
        Set<String> candidates = new LinkedHashSet<>();
        for (String p : RU_PACKAGES) candidates.add(p);
        for (String p : Prefs.splitExtraPackages(c)) {
            String v = p.trim();
            if (!v.isEmpty()) candidates.add(v);
        }
        for (String pkg : candidates) {
            try {
                pm.getPackageInfo(pkg, 0);
                out.add(pkg);
            } catch (Throwable ignored) {
                // не установлено — просто не исключаем
            }
        }
        return out;
    }

    /** «Найдено 12 из 63 установленных» — для строки в настройках. */
    public static String summary(Context c) {
        int total = 0;
        PackageManager pm = c.getPackageManager();
        if (pm != null) {
            for (String p : RU_PACKAGES) {
                try {
                    pm.getPackageInfo(p, 0);
                    total++;
                } catch (Throwable ignored) {
                }
            }
        }
        return total + " установленных из " + RU_PACKAGES.length + " в списке";
    }

    /** Короткое имя приложения по пакету (для лога и настроек). */
    public static String appLabel(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            if (pm == null) return pkg;
            return String.valueOf(pm.getApplicationLabel(pm.getPackageInfo(pkg, 0).applicationInfo));
        } catch (Throwable t) {
            return pkg;
        }
    }
}
