package ru.whitelist.bypass;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Настройки. Только то, что реально влияет на работу: источники, порог скорости,
 * DPI-обход линии, Tor-мосты для скачивания, сеть и данные.
 */
public class SettingsActivity extends Activity {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final int REQ_EXPORT_SETTINGS = 1003;
    private static final int REQ_IMPORT_SETTINGS = 1004;
    private static final int REQ_SAVE_CONSOLE = 1005;
    private static final int REQ_KNOWLEDGE = 1006;
    /** После импорта настроек нельзя сохранять то, что ещё нарисовано на экране. */
    private boolean skipSaveOnPause;
    /** Текст пула для экспорта: пишем в файл после того, как пользователь выбрал место. */
    private String pendingExport;
    private TextView importLog;
    /** Наборы настроек: готовые + сохранённые из .json. */
    private List<Presets.Preset> presets;
    private Spinner presetSpinner;
    private TextView presetNote;

    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;

    private LinearLayout root;
    /** Вкладки-разделы: содержимое каждой страницы лежит в своём контейнере. */
    private LinearLayout tabBar;
    private LinearLayout pageHost;
    private LinearLayout pageBox;
    /** Карточка текущей секции: всё, что «добавляется», попадает в неё. */
    private LinearLayout block;
    private ScrollView pageScroll;
    private final java.util.List<LinearLayout> pageViews = new java.util.ArrayList<>();
    private final java.util.List<TextView> pageTabs = new java.util.ArrayList<>();
    private int currentPage = 0;
    /** Куда писать вывод проверки DPI (живёт на странице «Обход»). */
    private TextView dpiOut;

    private EditText editSources, editSpeedUrls, editMinSpeed, editThreads, editTimeout,
            editGateTimeout, editStrategyWifi, editStrategyLte, editVpnDns, editMonitor,
            editSwitchTimeout, editTorBridges, editTorExtra, editTorBridgeWait, editPingTargets,
            editHealthUrls, editLanes, editDpiSources, editDpiOnlyStrategy, editDpiOnlyUrls;
    /** Панель рабочих конфигов на первой вкладке (та же, что на отдельном экране). */
    private PoolPanel poolPanel;
    private Switch swDesync, swIpv6, swAutoSwitch, swBoot, swEmbedded, swDpiAutotune,
            swTorFallback, swNoTls;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Prefs.migrate(this);
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setBackgroundColor(Ui.BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(Ui.S4);
        root.setPadding(pad, pad, pad, dp(Ui.S6));
        scroll.addView(root);
        setContentView(scroll);
        Ui.applyInsets(scroll);

        // Шапка: возврат, название, версия. Возврат нужен потому, что «Сохранить» есть
        // на каждой странице, а «назад» на телефоне не всегда очевидно.
        TextView back = Ui.iconButton(this, "←", Ui.TEXT);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                finish();
            }
        });
        root.addView(Ui.headerLeft(this, "Настройки", versionLine(), back));

        // Разделы вместо одной длинной простыни: настроек стало слишком много, и найти
        // в них «мосты» или «импорт пула» можно было только прокруткой.
        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        tabs.setClipToPadding(false);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.topMargin = dp(Ui.S4);
        tabs.setLayoutParams(tabsLp);
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(tabBar);
        root.addView(tabs);
        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hostLp.topMargin = dp(Ui.S4);
        pageHost.setLayoutParams(hostLp);
        root.addView(pageHost);
        // --- «Рабочие» — ПЕРВАЯ вкладка: раньше это был отдельный экран, до которого
        // ещё надо было дойти. Пул у каждой сети свой, поэтому здесь же переключатель.
        newPage("Рабочие");
        note("Пул ведётся отдельно для Wi-Fi и для мобильного интернета: блокировки у них "
                + "разные, и «собранный в метро» список дома только мешает. Переключатель "
                + "сверху показывает, какой пул открыт; для подключения берётся пул той сети, "
                + "в которой сейчас телефон.");
        LinearLayout poolHost = new LinearLayout(this);
        poolHost.setOrientation(LinearLayout.VERTICAL);
        pageBox.addView(poolHost);
        poolPanel = new PoolPanel(this, poolHost);
        poolPanel.build();

        // --- «Только DPI»: обход без VPN-сервера, ради YouTube и Discord
        newPage("Только DPI");
        section("Обход без VPN-сервера");
        note("Режим для случаев, когда нужен не VPN, а именно обход DPI: YouTube, Discord, "
                + "Instagram. Локальный ciadpi фрагментирует TLS прямо на телефоне — трафик "
                + "идёт напрямую, без чужого сервера, и не тратит подписку на VPN. "
                + "DNS при этом поднимается только на время работы режима.");
        editDpiOnlyStrategy = singleField(Prefs.dpiOnlyStrategy(this));
        label("Стратегия для режима «только DPI» (аргументы ciadpi)", Help.DPI_STRATEGY);
        add(editDpiOnlyStrategy);
        Button dpiOnlyReset = smallButton("Встроенная стратегия: "
                + DpiStrategies.shorten(Presets.dpiOnlyStrategy()));
        dpiOnlyReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editDpiOnlyStrategy.setText(Presets.dpiOnlyStrategy());
            }
        });
        addAction(dpiOnlyReset, "Стратегия «только DPI»", Help.DPI_STRATEGY);

        editDpiOnlyUrls = multiField(Prefs.join(Prefs.dpiOnlyUrls(this)), 4);
        label("Адреса, которые проверяет режим (по одному на строку)", Help.DPI_ONLY_URLS);
        add(editDpiOnlyUrls);
        note("Перед поднятием туннеля приложение проверяет эти адреса: если стратегия не "
                + "пробивает ни один, режим не включается — иначе было бы «подключено», а "
                + "YouTube закрыт. Через локальный обход идёт весь трафик, но не через чужой "
                + "сервер: пакеты уходят с телефона на нужный сайт напрямую.");

        Button dpiOnlyConnect = smallButton("Подключить «только DPI»");
        dpiOnlyConnect.setTextColor(Ui.ACCENT);
        dpiOnlyConnect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                VpnStart.connectDpiOnly(SettingsActivity.this);
                toast("Запускаю режим «только DPI»");
            }
        });
        addAction(dpiOnlyConnect, "Только DPI", Help.DPI_ONLY);

        Button dpiOnlyStop = smallButton("Остановить");
        dpiOnlyStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                VpnStart.stop(SettingsActivity.this);
                toast("Останавливаю");
            }
        });
        addAction(dpiOnlyStop, "Остановить", Help.DPI_ONLY_STOP);

        newPage("Списки");

        // --- скорость ---
        section("Скорость");
        editMinSpeed = numberField(String.valueOf(Prefs.minSpeedKbit(this)));
        label("Порог «рабочего» конфига, кбит/с (32 по умолчанию = 4 КБ/с)", Help.MIN_SPEED);
        add(editMinSpeed);
        note("Единицы: 1 КБ/с = 8 кбит/с. По умолчанию 32 кбит/с — это 4 КБ/с: конфиг "
                + "засчитывается, если качает быстрее 4 КБ/с. Порог низкий намеренно: живой, "
                + "но медленный конфиг лучше, чем пустой пул. На быстром Wi-Fi можно поднять "
                + "до 512 или 1024.");

        editLanes = numberField(String.valueOf(Prefs.speedLanes(this)));
        label("Сколько ядер мерить скорость одновременно (1–"
                + Prefs.MAX_SPEED_LANES + ", по умолчанию " + Prefs.defaultSpeedLanes()
                + " на этом телефоне)", Help.SPEED_LANES);
        add(editLanes);

        editSpeedUrls = multiField(Prefs.join(Prefs.speedUrls(this)), 3);
        label("Файлы для замера скорости (по одному на строку)", Help.SPEED_URLS);
        add(editSpeedUrls);

        editGateTimeout = numberField(String.valueOf(Prefs.gateTimeoutMs(this)));
        label("Сколько ждём на один замер, мс (3000 по умолчанию)", Help.GATE_TIMEOUT);
        add(editGateTimeout);

        note("Замер идёт на маленьких файлах (32 и 128 КБ) и в несколько дорожек параллельно: "
                + "канал видно по первым килобайтам, а ждать мегабайты незачем. Лимит — это окно "
                + "самого замера: оно отсчитывается от ПЕРВОГО полученного байта, а на "
                + "соединение и TLS даётся отдельный запас сверху. Поэтому 1500 мс не "
                + "превращаются в «нет данных» на медленном старте.");
        note("Скорость важнее пинга: пул сортируется по КБ/с, подключение начинается "
                + "с самого быстрого. Конфиги медленнее порога не попадают в пул вообще.");

        // --- поиск ---
        section("Поиск и пинг");
        editThreads = numberField(String.valueOf(Prefs.pingThreads(this)));
        label("Потоков пинга одновременно (до " + Prefs.MAX_PING_THREADS
                + ", по умолчанию " + Prefs.defaultPingThreads()
                + " на этом телефоне)", Help.PING_THREADS);
        add(editThreads);
        editTimeout = numberField(String.valueOf(Prefs.pingTimeoutMs(this)));
        label("Таймаут пинга, мс (2000 по умолчанию)", Help.PING_TIMEOUT_MS);
        add(editTimeout);

        editPingTargets = multiField(Prefs.join(Prefs.pingTargets(this)), 5);
        label("5 целей TCP-пинга «есть ли интернет» (host:port, по одной на строку)", Help.PING_TARGETS);
        add(editPingTargets);

        editHealthUrls = multiField(Prefs.join(Prefs.healthUrls(this)), 5);
        label("5 мест проверки канала через туннель (URL, по одному на строку)", Help.HEALTH_URLS);
        add(editHealthUrls);

        note("1000 мс — это фильтр «более-менее работающих»: то, что пингуется дольше, "
                + "всё равно не годится для обхода. Пинг не решает «рабочий/не рабочий»: "
                + "он только отсекает мёртвое. Конфиг, "
                + "прошедший TLS-handshake, дополнительно проверяется обычным TCP — "
                + "поэтому Reality и чужие SNI не отбрасываются зря. Рабочесть и скорость "
                + "подтверждаются реальной загрузкой через поднятое ядро Xray.");

        // --- источники ---
        section("Источники списков (по одному на строку)");
        editSources = multiField(Prefs.join(Prefs.sources(this)), 7);
        label("По одной ссылке на строку; порядок = приоритет", Help.SOURCES);
        add(editSources);
        Button resetSources = smallButton("Вернуть источники по умолчанию");
        resetSources.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editSources.setText(Prefs.join(Prefs.DEFAULT_SOURCES));
                toast("Применится после «Сохранить»");
            }
        });
        addAction(resetSources, "Источники по умолчанию", Help.RESET_SOURCES);

        newPage("Обход");

        // --- DPI на линии ---
        section("DPI-обход");
        swDesync = switchRow("DPI-обход линии до VPN-сервера (ciadpi) — по умолчанию включён",
                Prefs.desyncLine(this), Help.DESYNC_LINE);
        note("Стратегии у Wi-Fi и мобильного разные: дома обход обычно не нужен, а в "
                + "мобильной сети нужен «белый список». Обе можно переопределить — пустое "
                + "поле означает «встроенная для этой сети».");
        editStrategyWifi = singleField(Prefs.dpiStrategyFor(this, Net.WIFI));
        label("Стратегия ciadpi для Wi-Fi (аргументы командной строки)", Help.DPI_STRATEGY);
        add(editStrategyWifi);
        Button recommendedWifi = smallButton("Встроенная для Wi-Fi: "
                + DpiStrategies.shorten(Presets.wifiStrategy()));
        recommendedWifi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editStrategyWifi.setText(Presets.wifiStrategy());
            }
        });
        addAction(recommendedWifi, "Wi-Fi по умолчанию", Help.RECOMMENDED_STRATEGY);

        editStrategyLte = singleField(Prefs.dpiStrategyFor(this, Net.MOBILE));
        label("Стратегия ciadpi для мобильного интернета (белые списки)", Help.DPI_STRATEGY);
        add(editStrategyLte);
        Button recommendedLte = smallButton("Встроенная для LTE: "
                + DpiStrategies.shorten(Prefs.DEFAULT_STRATEGY));
        recommendedLte.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editStrategyLte.setText(Prefs.DEFAULT_STRATEGY);
            }
        });
        addAction(recommendedLte, "LTE по умолчанию", Help.RECOMMENDED_STRATEGY);
        note("Сначала всегда пробуется прямое подключение. Через ciadpi — только если оно "
                + "не прошло (первые 3 конфига). TLS к серверу при этом фрагментируется, "
                + "поэтому для Reality этот путь может не сработать.");

        swDpiAutotune = switchRow("Подбирать стратегию автоматически (только вместе с DPI-обходом)",
                Prefs.dpiAutotune(this), Help.DPI_AUTOTUNE);
        note("При подключении приложение по очереди поднимает ciadpi с разными стратегиями "
                + "и проверяет, открываются ли заблокированные адреса. Первая подошедшая "
                + "сохраняется и используется дальше. Это занимает до ~30 секунд один раз "
                + "за подключение.");

        editDpiSources = multiField(Prefs.join(Prefs.dpiSources(this)), 3);
        label("Откуда брать стратегии (по одной ссылке на строку)", Help.DPI_SOURCES);
        add(editDpiSources);

        // Рабочие стратегии: те, с которыми конфиги реально заработали (их же пробуем
        // первыми при автоподборе). Хранятся отдельно от «текущей стратегии».
        final java.util.List<String> working = DpiStrategies.working(this);
        dpiOut = new TextView(this);
        dpiOut.setTextColor(TEXT_DIM);
        dpiOut.setTextSize(12);
        dpiOut.setText(working.isEmpty()
                ? "Рабочих стратегий пока нет: они появятся после первого удачного замера."
                : "Рабочих стратегий: " + working.size() + " · последняя — "
                        + DpiStrategies.shorten(working.get(0)));
        add(dpiOut);

        Button forgetStrategies = smallButton("Забыть рабочие стратегии");
        forgetStrategies.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                DpiStrategies.clearWorking(SettingsActivity.this);
                toast("Рабочие стратегии забыты");
                recreate();
            }
        });
        addAction(forgetStrategies, "Забыть рабочие стратегии", Help.FORGET_STRATEGIES);

        Button refreshDpi = smallButton("Обновить стратегии с GitHub");
        refreshDpi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                toast("Скачиваю списки стратегий…");
                final TextView out = dpiOut;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        ConfigRepository.clearCancel();
                        final StringBuilder sb = new StringBuilder();
                        final int n = DpiStrategies.refresh(SettingsActivity.this, new DpiStrategies.Log() {
                            @Override public void line(String message) {
                                sb.append(message).append('\n');
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { out.setText(sb.toString()); }
                                });
                            }
                        });
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                out.setText(sb.toString() + "\nВсего стратегий: " + n);
                                toast("Стратегий сохранено: " + n);
                            }
                        });
                    }
                }, "dpi-refresh");
                t.setDaemon(true);
                t.start();
            }
        });
        addAction(refreshDpi, "Обновить стратегии", Help.REFRESH_DPI);

        Button dpiProbe = smallButton("Проверить стратегии сейчас");
        dpiProbe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                final TextView out = dpiOut;
                toast("Проверяю стратегии…");
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        ConfigRepository.clearCancel();
                        final StringBuilder sb = new StringBuilder();
                        String chosen = DpiStrategies.autotune(SettingsActivity.this, null,
                                new DpiStrategies.Log() {
                                    @Override public void line(String message) {
                                        sb.append(message).append('\n');
                                        runOnUiThread(new Runnable() {
                                            @Override public void run() { out.setText(sb.toString()); }
                                        });
                                    }
                                });
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                (Net.isWifi(SettingsActivity.this)
                                        ? editStrategyWifi : editStrategyLte).setText(chosen);
                                toast("Рабочая стратегия сохранена в поле выше");
                            }
                        });
                    }
                }, "dpi-probe-all");
                t.setDaemon(true);
                t.start();
            }
        });
        addAction(dpiProbe, "Проверить стратегии", Help.DPI_PROBE);

        // --- ядро ---
        section("Ядро Xray");
        swNoTls = switchRow("Разрешать конфиги без TLS (через локальный ретранслятор)",
                Prefs.allowNoTls(this), Help.ALLOW_NO_TLS);
        note("Xray 26 отказывается поднимать VLESS/Trojan без TLS к публичному адресу: "
                + "разрешены только приватные IP и локальные домены. Под это правило попадает "
                + "больше половины публичных списков (ws за CDN, обычный tcp без tls). "
                + "С включённой опцией такие конфиги тоже работают: ядро подключается к "
                + "локальному ретранслятору (127.0.0.1), а тот отдаёт соединение настоящему "
                + "серверу. Стоит одного лишнего локального хопа и только на таких конфигах. "
                + "Выключено — их отсекает сразу, в логе одной строкой «Несовместимы с ядром».");

        // --- сеть ---
        section("Сеть");
        editVpnDns = singleField(Prefs.vpnDns(this));
        label("DNS для туннеля", Help.VPN_DNS);
        add(editVpnDns);
        swIpv6 = switchRow("Маршрутизировать IPv6", Prefs.ipv6(this), Help.IPV6);
        swAutoSwitch = switchRow("Переключать конфиг, если канал не отвечает", Prefs.autoSwitch(this), Help.AUTO_SWITCH);
        editMonitor = numberField(String.valueOf(Prefs.monitorIntervalSec(this)));
        label("Проверка канала каждые, сек", Help.MONITOR_INTERVAL);
        add(editMonitor);
        editSwitchTimeout = numberField(String.valueOf(Prefs.switchTimeoutSec(this)));
        label("Секунд без ответа до переключения", Help.SWITCH_TIMEOUT);
        add(editSwitchTimeout);
        swBoot = switchRow("Подключаться при загрузке", Prefs.bootConnect(this), Help.BOOT_CONNECT);

        newPage("Tor");

        // --- Tor ---
        section("Tor: мосты, резервный канал и списки");
        note("Tor нужен в трёх случаях: (1) прямые источники и jsDelivr не отдали списки — "
                + "тогда Tor единственная дорога к ним; (2) ни один VPN-конфиг не отвечает — "
                + "тогда трафик идёт через мосты (медленно, но связь есть); (3) вы сами "
                + "включили «Tor как VPN» кнопкой ниже — без конфигов и без ожидания отказа.");
        swTorFallback = switchRow("Если VPN не отвечает — пускать трафик через Tor-мосты",
                Prefs.torFallback(this), Help.TOR_FALLBACK);

        // --- Tor как самостоятельный VPN: отдельный способ включения, не резерв
        Button torAsVpn = smallButton("Включить Tor-мост как VPN");
        torAsVpn.setTextColor(0xFFB388FF);
        torAsVpn.setTextSize(15);
        torAsVpn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                VpnStart.connectTor(SettingsActivity.this);
                toast("Поднимаю Tor-мосты как VPN — это может занять до минуты");
            }
        });
        addAction(torAsVpn, "Tor как VPN", Help.TOR_AS_VPN);

        Button torStop = smallButton("Остановить");
        torStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                VpnStart.stop(SettingsActivity.this);
                toast("Останавливаю");
            }
        });
        addAction(torStop, "Остановить Tor", Help.TOR_AS_VPN);
        note("Режим не зависит от переключателя выше: тот — автоматический резерв, а эта "
                + "кнопка поднимает мосты сразу. Используются мосты из списка ниже (пусто — "
                + "встроенные Vanilla), перед созданием туннеля скорость через Tor "
                + "проверяется по тому же порогу. «Переключить» в уведомлении вернёт на "
                + "обычный конфиг из пула, если он там есть.");
        editTorBridges = multiField(Prefs.torBridges(this), 4);
        label("Мосты (пусто — встроенные Vanilla)", Help.TOR_BRIDGES);
        add(editTorBridges);
        Button fillBridges = smallButton("Вставить встроенные Vanilla-мосты");
        fillBridges.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editTorBridges.setText(readAssetFirstN("fallback/tor_bridges_vanilla.txt", 20));
            }
        });
        add(fillBridges);
        editTorExtra = multiField(Prefs.torExtraTorrc(this), 2);
        label("Дополнительные строки torrc", Help.TOR_EXTRA_TORRC);
        add(editTorExtra);

        label("Лимит ожидания одного моста, секунд (5–120)", Help.TOR_BRIDGE_WAIT);
        editTorBridgeWait = numberField(String.valueOf(Prefs.torBridgeWaitSec(this)));
        editTorBridgeWait.setInputType(InputType.TYPE_CLASS_NUMBER);
        add(editTorBridgeWait);
        note("По умолчанию 10 секунд. Живой мост поднимается за секунды; если за лимит "
                + "не поднялся — проверка идёт к следующему, а не висит на нём. "
                + "Больше 30 с ставить смысла нет: это уже признак заблокированного моста.");

        note("Проверка мостов идёт по одному мосту: поднимаем Tor, качаем тестовую страницу, "
                + "запоминаем скорость. Дальше и в приложении, и в браузере мосты "
                + "используются начиная с самого быстрого. Время = мостов × лимит ожидания.");

        final TextView torOut = new TextView(this);
        torOut.setTextColor(TEXT_DIM);
        torOut.setTextSize(12);
        String savedReport = Prefs.torReport(this);
        torOut.setText(savedReport.isEmpty() ? "Проверка ещё не запускалась." : savedReport);
        add(torOut);

        Button checkTor = smallButton("Проверить мосты и источники через Tor");
        checkTor.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                ConfigRepository.clearCancel();
                toast("Проверяю мосты — это может занять несколько минут");
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        final StringBuilder sb = new StringBuilder();
                        final String report = TorCheck.run(SettingsActivity.this, new TorCheck.Log() {
                            @Override public void line(String message) {
                                sb.append(message).append('\n');
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { torOut.setText(sb.toString()); }
                                });
                            }
                        });
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                torOut.setText(report);
                                toast("Проверка мостов завершена");
                            }
                        });
                    }
                }, "tor-check");
                t.setDaemon(true);
                t.start();
            }
        });
        addAction(checkTor, "Проверка мостов", Help.CHECK_TOR);

        Button browser = smallButton("Браузер (только Tor с мостами, поиск DuckDuckGo)");
        browser.setTextColor(0xFFB388FF);
        browser.setTextSize(15);
        browser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                startActivity(new android.content.Intent(SettingsActivity.this, BrowserActivity.class));
            }
        });
        addAction(browser, "Встроенный браузер", Help.BROWSER);
        note("Браузер — отдельный экран: одна вкладка, поиск через GET-формы, и ходит он "
                + "только через Tor с мостами. Если мостов нет или VPN включён, он скажет об этом.");

        newPage("Данные");

        // --- пул: импорт и экспорт ---
        section("Пул: импорт и экспорт");
        note("Экспорт — обычный .txt, по одной ссылке на строку. Импорт файлу не доверяет: "
                + "дубликаты отбрасываются (и внутри файла, и уже лежащие в пуле), остальные "
                + "пингуются, затем у живых замеряется скорость — в пул попадают только "
                + "прошедшие порог.");

        final TextView poolOut = new TextView(this);
        poolOut.setTextColor(TEXT_DIM);
        poolOut.setTextSize(12);
        poolOut.setText("В рабочих: Wi-Fi " + Pool.count(this, Net.WIFI)
                + ", мобильный " + Pool.count(this, Net.MOBILE)
                + ". Полный список и подключение — на вкладке «Рабочие».");
        add(poolOut);
        importLog = poolOut;

        Button openPool = smallButton("Открыть рабочие конфиги: пинг, перемер, удаление");
        openPool.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Pool.count(SettingsActivity.this) == 0) {
                    toast("Пул пуст — сначала «Собрать пул» на главном экране");
                    return;
                }
                startActivity(new Intent(SettingsActivity.this, PoolActivity.class));
            }
        });
        addAction(openPool, "Рабочие конфиги", Help.POOL_CONFIGS);

        Button exportPool = smallButton("Экспорт рабочих конфигов в .txt");
        exportPool.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Pool.count(SettingsActivity.this) == 0) {
                    toast("Пул пуст — экспортировать нечего");
                    return;
                }
                pendingExport = PoolIo.exportText(SettingsActivity.this);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, "whitelist-pool.txt");
                try {
                    startActivityForResult(intent, REQ_EXPORT);
                } catch (Exception e) {
                    toast("Нет приложения для сохранения файла");
                }
            }
        });
        addAction(exportPool, "Экспорт пула", Help.EXPORT_POOL);

        Button importPool = smallButton("Импорт .txt: дубликаты → пинг → скорость");
        importPool.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/*");
                try {
                    startActivityForResult(intent, REQ_IMPORT);
                } catch (Exception e) {
                    toast("Нет приложения для выбора файла");
                }
            }
        });
        addAction(importPool, "Импорт пула", Help.IMPORT_POOL);

        // --- настройки файлом ---
        section("Настройки файлом: .json");
        note("Экспорт выгружает ВСЕ настройки одним .json: список источников, пороги, таймауты, "
                + "мосты, DPI-стратегии. Такой файл удобно править текстом (в том числе через "
                + "LLM) и загружать обратно. Импорт применяет только известные ключи и только "
                + "с тем же типом значения — испорченный файл приложение не сломает.");
        Button exportSettings = smallButton("Экспорт всех настроек в .json");
        exportSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                pendingExport = SettingsIo.exportJson(SettingsActivity.this);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "whitelist-settings.json");
                try {
                    startActivityForResult(intent, REQ_EXPORT_SETTINGS);
                } catch (Exception e) {
                    toast("Нет приложения для сохранения файла");
                }
            }
        });
        addAction(exportSettings, "Экспорт настроек", Help.EXPORT_SETTINGS);

        Button importSettings = smallButton("Импорт настроек из .json");
        importSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(intent, REQ_IMPORT_SETTINGS);
                } catch (Exception e) {
                    toast("Нет приложения для выбора файла");
                }
            }
        });
        addAction(importSettings, "Импорт настроек", Help.IMPORT_SETTINGS);

        // --- наборы настроек: выбираются из списка, импортированные добавляются сюда же ---
        section("Наборы настроек");
        note("Готовые наборы: «Стандарт» (с DPI-обходом — он же по умолчанию), «Wi-Fi» "
                + "(без DPI, самый быстрый замер) и «Белые списки» (низкий порог, только "
                + "white-источники). Всё, что вы импортируете из .json, остаётся в этом же "
                + "списке отдельным набором — можно вернуться одной кнопкой.");

        presets = Presets.all(this);
        ArrayAdapter<String> presetAdapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        for (Presets.Preset p : presets) presetAdapter.add(p.title);
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        presetSpinner = new Spinner(this);
        presetSpinner.setAdapter(presetAdapter);
        add(presetSpinner);

        presetNote = new TextView(this);
        presetNote.setTextColor(TEXT_DIM);
        presetNote.setTextSize(12);
        presetNote.setPadding(0, dp(6), 0, dp(6));
        add(presetNote);
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                showPresetNote(pos);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        showPresetNote(0);

        Button applyPreset = smallButton("Применить выбранный набор");
        applyPreset.setTextColor(0xFF3DDC84);
        applyPreset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int pos = presetSpinner.getSelectedItemPosition();
                if (pos < 0 || pos >= presets.size()) {
                    toast("Набор не выбран");
                    return;
                }
                final Presets.Preset p = presets.get(pos);
                String msg = Presets.apply(SettingsActivity.this, p);
                skipSaveOnPause = true;   // экран не должен затереть только что применённое
                toast(msg);
                recreate();
            }
        });
        addAction(applyPreset, "Применить набор", Help.PRESETS);

        Button deletePreset = smallButton("Удалить выбранный набор (только свой из .json)");
        deletePreset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int pos = presetSpinner.getSelectedItemPosition();
                if (pos < 0 || pos >= presets.size()) {
                    toast("Набор не выбран");
                    return;
                }
                Presets.Preset p = presets.get(pos);
                if (!Presets.delete(SettingsActivity.this, p)) {
                    toast("Готовый набор удалить нельзя — можно только применить");
                    return;
                }
                toast("Набор удалён: " + p.title);
                skipSaveOnPause = true;
                recreate();
            }
        });
        addAction(deletePreset, "Удалить набор", Help.PRESETS);

        Button saveConsole = smallButton("Сохранить консоль в .json");
        saveConsole.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendingExport = SettingsIo.consoleJson(SettingsActivity.this);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "whitelist-console.json");
                try {
                    startActivityForResult(intent, REQ_SAVE_CONSOLE);
                } catch (Exception e) {
                    toast("Нет приложения для сохранения файла");
                }
            }
        });
        addAction(saveConsole, "Сохранить консоль", Help.SAVE_CONSOLE);
        note("Консоль — это лог с главного экрана: пинг, замер, причины отказов, мосты. "
                + "Файл содержит строки и склеенный текст, версию приложения и число конфигов "
                + "в пуле.");

        // --- данные ---
        section("Данные");
        swEmbedded = switchRow("Встроенный резерв конфигов", Prefs.embeddedEnabled(this), Help.EMBEDDED);
        // Чёрный список: конфиги, которые уже проверяли и они не работали. Их не пингуем
        // неделю — иначе каждый сбор пула заново тратит время на одно и то же.
        final int blacklisted = Blacklist.size(this);
        final TextView blackOut = new TextView(this);
        blackOut.setTextColor(TEXT_DIM);
        blackOut.setTextSize(12);
        blackOut.setText(blacklisted == 0
                ? "Нерабочих конфигов в списке нет."
                : "Нерабочих конфигов в списке: " + blacklisted
                        + ". Они не пингуются и не меряются; запись живёт "
                        + (Blacklist.TTL_MS / (24 * 3600 * 1000)) + " дней.");
        add(blackOut);

        Button clearBlacklist = smallButton("Очистить список нерабочих конфигураций");
        clearBlacklist.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = Blacklist.size(SettingsActivity.this);
                Blacklist.clear(SettingsActivity.this);
                blackOut.setText("Список очищен (было " + n + "). Следующий сбор пула "
                        + "перепроверит всё заново.");
                toast("Список нерабочих конфигураций очищен");
            }
        });
        addAction(clearBlacklist, "Чёрный список", Help.CLEAR_BLACKLIST);

        Button clearAll = smallButton("Удалить скачанные списки и пул");
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ConfigRepository.clearDownloaded(SettingsActivity.this);
                Pool.clear(SettingsActivity.this);
                toast("Скачанные списки и пул удалены (встроенный резерв остался)");
            }
        });
        addAction(clearAll, "Удалить списки и пул", Help.CLEAR_ALL);

        Button battery = smallButton("Разрешить работу в фоне (без выгрузки)");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PermissionsHelper.requestBatteryExemption(SettingsActivity.this);
            }
        });
        addAction(battery, "Работа в фоне", Help.BATTERY);

        // --- инфо: о проекте, донат, идеи ---
        newPage("Инфо");
        section("О проекте");
        note("«Обход Белых Списков» — открытый проект. У него нет ни автора, ни владельца: "
                + "код под GPL-3.0, и любой может взять его, форкнуть, переписать или "
                + "выпустить своими руками. Приложение ничего не обещает и не продаёт: "
                + "ядра Xray, ByeDPI и Tor работают локально, серверов у проекта нет, "
                + "аккаунтов нет, телеметрии нет.");
        note("Пул и черновик метаданных подготовлены так, чтобы приложение можно было "
                + "публиковать в F-Droid: сборка на Gradle, нативные библиотеки собираются "
                + "из исходников, ни одного предсобранного бинарника в репозитории.");

        section("Поддержка");
        note("Поддержка — только деньгами и только на инфраструктуру (домены, тестовые "
                + "серверы, железо для проверок). Ничего взамен не обещается: приложение "
                + "останется бесплатным и без рекламы. Все три адреса равнозначны — "
                + "выбирайте сеть, под которую у вас есть кошелёк.");
        note("USDT (TON) — основной адрес проекта. USDT (TRON) и Bitcoin добавлены для тех, "
                + "у кого нет TON-кошелька. Перед переводом проверяйте сеть: USDT в TON и "
                + "USDT в TRON — разные сети, перевод не в ту сеть не вернуть.");
        donateCard("USDT (TON)", "UQBYs3wqrpoUsGoU2edVR7z6_1ctSH4HU2ZZf5DXPToQ3wgi", null);
        donateCard("USDT (TRON)", "TUZeuyvA9dALj1ee78NSMXvnJeEXxYwZ1a", null);
        donateCard("Bitcoin", "bc1qat0txl9ttmsz2le58yvfw9zvhg7pncj9mmr49h", "bitcoin");

        section("База знаний");
        note("Подробное описание каждой настройки и каждой функции — в файле "
                + "docs/KNOWLEDGE.txt (он же внутри приложения). Он написан так, чтобы его "
                + "можно было отдать LLM: ключи настроек, диапазоны, значения по умолчанию, "
                + "что именно приложение делает со значением и какие крайности всё ломают. "
                + "Порядок работы: экспорт настроек → правка вместе с базой знаний → импорт обратно.");
        Button showKnowledge = smallButton("Открыть базу знаний");
        showKnowledge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = readAsset("KNOWLEDGE.txt");
                if (text.isEmpty()) {
                    toast("База знаний не найдена в приложении");
                    return;
                }
                TextView tv = new TextView(SettingsActivity.this);
                int pad = dp(16);
                tv.setPadding(pad, pad, pad, pad);
                tv.setText(text);
                tv.setTextSize(12);
                tv.setTextColor(TEXT);
                tv.setTextIsSelectable(true);
                ScrollView holder = new ScrollView(SettingsActivity.this);
                holder.addView(tv);
                Help.showView(SettingsActivity.this, "База знаний", holder);
            }
        });
        addAction(showKnowledge, "База знаний", Help.KNOWLEDGE);

        Button exportKnowledge = smallButton("Экспорт базы знаний в .txt");
        exportKnowledge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendingExport = readAsset("KNOWLEDGE.txt");
                if (pendingExport.isEmpty()) {
                    toast("База знаний не найдена в приложении");
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, "whitelist-knowledge.txt");
                try {
                    startActivityForResult(intent, REQ_KNOWLEDGE);
                } catch (Exception e) {
                    toast("Нет приложения для сохранения файла");
                }
            }
        });
        addAction(exportKnowledge, "Экспорт базы знаний", Help.KNOWLEDGE);

        section("Проекты, которым нужна поддержка");
        note("1. Медленный канал до внешнего интернета, который стабильно работает при белых "
                + "списках, бесплатный для всех пользователей рунета — и приложение для его "
                + "эффективного использования. Это ровно то, чего не хватает этому приложению: "
                + "список конфигов ищет выход, но сам выход никому не принадлежит.");
        note("2. Под чебурнет пригодятся системы ретрансляции в ру-сеть, коротковолновое радио, "
                + "просто радио, старлинки с границы, спутниковые зарубежные каналы. Всё это — "
                + "каналы с маленькой пропускной способностью, но с недоступной цензуре физикой.");
        note("3. Под чебурнет при белых списках — повсеместный Meshtastic (LoRa-меш) или "
                + "аналогичная сеть плюс ключевая технология: отправить сообщение LLM, которая "
                + "может просмотреть внешнюю сеть и прислать ответ коротким текстом. Узкий канал "
                + "и текстовый ответ сочетаются идеально: меш даёт связь без оператора, а LLM — "
                + "сжатый смысл вместо мегабайт.");
        note("Эти идеи открыты: если делать — то в открытом виде и без владельца, как и это "
                + "приложение.");

        // Сохранение — на каждой странице: иначе после правки настроек приходилось бы
        // возвращаться на вкладку «Инфо».
        for (LinearLayout page : pageViews) {
            Button save = Ui.primary(this, "Сохранить настройки");
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveLp.topMargin = dp(Ui.S5);
            save.setLayoutParams(saveLp);
            save.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    savePrefs();
                    toast("Сохранено");
                    finish();
                }
            });
            page.addView(save);
        }

        TextView footer = Ui.caption(this, "Обход Белых Списков " + appVersion() + " · "
                + versionLine() + "\n"
                + "Открытый проект без разработчика: код под GPL-3.0, форкайте и переписывайте.\n"
                + "Ядра (Xray, ByeDPI, Tor) работают локально; сторонних серверов у приложения нет.");
        block = null;                       // подвал — под карточками, а не внутри
        footer.setPadding(dp(Ui.S4), dp(Ui.S5), dp(Ui.S4), 0);
        (pageBox != null ? pageBox : root).addView(footer);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (skipSaveOnPause) return;  // только что импортировали файл: не затирать его экраном
        savePrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (poolPanel != null) poolPanel.refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Разрешение VPN и выбор файла из панели рабочих приходят сюда же.
        if (VpnStart.handleResult(this, requestCode, resultCode)) return;
        if (poolPanel != null && poolPanel.handleActivityResult(requestCode, resultCode, data)) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            final String text = pendingExport;
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = writeTo(uri, text);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast(ok ? "Пул сохранён в файл" : "Не удалось сохранить файл");
                        }
                    });
                }
            }, "pool-export").start();
        } else if (requestCode == REQ_IMPORT) {
            startImport(uri);
        } else if (requestCode == REQ_KNOWLEDGE) {
            final String text = pendingExport;
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = writeTo(uri, text);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast(ok ? "База знаний сохранена в .txt" : "Не удалось сохранить файл");
                        }
                    });
                }
            }, "knowledge-export").start();
        } else if (requestCode == REQ_EXPORT_SETTINGS || requestCode == REQ_SAVE_CONSOLE) {
            final boolean settings = requestCode == REQ_EXPORT_SETTINGS;
            final String text = pendingExport;
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = writeTo(uri, text);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast(ok ? (settings ? "Настройки сохранены в .json"
                                                 : "Консоль сохранена в .json")
                                     : "Не удалось сохранить файл");
                        }
                    });
                }
            }, "json-export").start();
        } else if (requestCode == REQ_IMPORT_SETTINGS) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final String text = readAll(uri);
                    final String result = text == null
                            ? "Не удалось прочитать файл"
                            : SettingsIo.importJson(SettingsActivity.this, text);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast(result);
                            if (text != null) {
                                skipSaveOnPause = true;
                                recreate();  // показать уже импортированные значения
                            }
                        }
                    });
                }
            }, "json-import").start();
        }
    }

    /** Описание выбранного набора под списком. */
    private void showPresetNote(int pos) {
        if (presetNote == null) return;
        if (pos < 0 || pos >= presets.size()) {
            presetNote.setText("");
            return;
        }
        Presets.Preset p = presets.get(pos);
        presetNote.setText((p.builtin ? "" : "СВОЙ НАБОР · ") + p.note);
    }

    private boolean writeTo(Uri uri, String text) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) return false;
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String readAll(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Импорт в фоне: чтение файла → дедупликация → пинг → скорость → пул. */
    private void startImport(final Uri uri) {
        if (importLog != null) importLog.setText("Читаю файл…");
        toast("Импорт: пинг и скорость займут время");
        new Thread(new Runnable() {
            @Override public void run() {
                final String text = readAll(uri);
                if (text == null) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { toast("Не удалось прочитать файл"); }
                    });
                    return;
                }
                ConfigRepository.clearCancel();
                final StringBuilder sb = new StringBuilder();
                final PoolIo.Result res = PoolIo.importText(SettingsActivity.this, text,
                        new PoolIo.Log() {
                            @Override public void line(String message) {
                                sb.append(message).append('\n');
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (importLog != null) importLog.setText(sb.toString());
                                    }
                                });
                            }
                        });
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (importLog != null) importLog.setText(sb.toString()
                                + "\nИтог: " + res.summary() + "\nВ пуле " + Pool.count(SettingsActivity.this));
                        toast("Импорт: добавлено " + res.added);
                    }
                });
            }
        }, "pool-import").start();
    }

    private void savePrefs() {
        android.content.SharedPreferences.Editor e = Prefs.get(this).edit();
        e.putString(Prefs.SOURCES, normalizeLines(editSources.getText().toString()));
        e.putString(Prefs.SPEED_URLS, normalizeLines(editSpeedUrls.getText().toString()));
        e.putInt(Prefs.MIN_SPEED_KBIT,
                Prefs.clamp(parseInt(editMinSpeed, Prefs.DEFAULT_MIN_SPEED_KBIT), 8, 160000));
        e.putInt(Prefs.GATE_TIMEOUT_MS,
                Prefs.clamp(parseInt(editGateTimeout, Prefs.DEFAULT_GATE_TIMEOUT_MS), 800, 20000));
        e.putInt(Prefs.PING_THREADS,
                Prefs.clamp(parseInt(editThreads, Prefs.defaultPingThreads()), 1,
                        Prefs.MAX_PING_THREADS));
        e.putInt(Prefs.PING_TIMEOUT_MS,
                Prefs.clamp(parseInt(editTimeout, Prefs.DEFAULT_PING_TIMEOUT_MS), 300, 15000));
        e.putInt(Prefs.SPEED_LANES,
                Prefs.clamp(parseInt(editLanes, Prefs.defaultSpeedLanes()), 1,
                        Prefs.MAX_SPEED_LANES));
        e.putString(Prefs.PING_TARGETS, normalizeLines(editPingTargets.getText().toString()));
        e.putString(Prefs.HEALTH_URLS, normalizeLines(editHealthUrls.getText().toString()));
        e.putString(Prefs.DPI_STRATEGY, editStrategyLte.getText().toString().trim());
        e.putString(Prefs.DPI_STRATEGY_WIFI, editStrategyWifi.getText().toString().trim());
        e.putString(Prefs.DPI_ONLY_STRATEGY, editDpiOnlyStrategy.getText().toString().trim());
        e.putString(Prefs.DPI_ONLY_URLS, normalizeLines(editDpiOnlyUrls.getText().toString()));
        e.putString(Prefs.DPI_SOURCES, normalizeLines(editDpiSources.getText().toString()));
        e.putBoolean(Prefs.DPI_AUTOTUNE, swDpiAutotune.isChecked());
        e.putString(Prefs.VPN_DNS, editVpnDns.getText().toString().trim());
        e.putInt(Prefs.MONITOR_INTERVAL_SEC, Prefs.clamp(parseInt(editMonitor, 15), 5, 300));
        e.putInt(Prefs.SWITCH_TIMEOUT_SEC,
                Prefs.clamp(parseInt(editSwitchTimeout, Prefs.DEFAULT_SWITCH_TIMEOUT_SEC), 5, 3600));
        e.putString(Prefs.TOR_BRIDGES, normalizeLines(editTorBridges.getText().toString()));
        e.putString(Prefs.TOR_EXTRA_TORRC, editTorExtra.getText().toString().trim());
        e.putBoolean(Prefs.DESYNC_LINE, swDesync.isChecked());
        e.putBoolean(Prefs.ALLOW_NO_TLS, swNoTls.isChecked());
        e.putBoolean(Prefs.IPV6, swIpv6.isChecked());
        e.putBoolean(Prefs.AUTO_SWITCH, swAutoSwitch.isChecked());
        e.putBoolean(Prefs.BOOT_CONNECT, swBoot.isChecked());
        e.putBoolean(Prefs.EMBEDDED, swEmbedded.isChecked());
        e.putInt(Prefs.TOR_BRIDGE_WAIT_SEC,
                Prefs.clamp(parseInt(editTorBridgeWait, 10), 5, 120));
        e.putBoolean(Prefs.TOR_FALLBACK, swTorFallback.isChecked());
        e.apply();
    }

    // ------------------------------------------------------------------ страницы/вкладки

    /** Начать новую страницу: дальше всё содержимое уходит в неё. */
    private void newPage(String title) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setVisibility(pageViews.isEmpty() ? View.VISIBLE : View.GONE);
        pageViews.add(page);
        pageHost.addView(page);
        pageBox = page;
        block = null;

        final int index = pageViews.size() - 1;
        TextView tab = Ui.tab(this, title);
        if (index == 0) Ui.paintChip(tab, Ui.ACCENT, true, this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(12), 0);
        tab.setLayoutParams(lp);
        tab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selectPage(index);
            }
        });
        tabBar.addView(tab);
        pageTabs.add(tab);
    }

    /** Показать страницу по номеру (остальные прячем). */
    private void selectPage(int index) {
        if (index < 0 || index >= pageViews.size()) return;
        currentPage = index;
        for (int i = 0; i < pageViews.size(); i++) {
            boolean on = i == index;
            pageViews.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
            if (i < pageTabs.size()) {
                Ui.paintChip(pageTabs.get(i), on ? Ui.ACCENT : Ui.TEXT_DIM, on, this);
            }
        }
        if (pageScroll != null) pageScroll.scrollTo(0, 0);
    }

    /** Добавить view в текущую секцию, иначе — на страницу (или в корень). */
    private void add(View v) {
        if (block != null) block.addView(v);
        else if (pageBox != null) pageBox.addView(v);
        else root.addView(v);
    }

    // ------------------------------------------------------------------ строительные блоки

    /**
     * Начать секцию: заголовок + карточка, в которую попадает всё дальнейшее.
     *
     * Раньше секции были просто серыми подписями, и страница выглядела как один поток
     * полей. Теперь каждый смысловой блок — отдельная поверхность: видно, где кончается
     * «скорость» и начинается «источники», и по чему можно попасть пальцем.
     */
    private void section(String text) {
        if (pageBox == null) {                       // до первой страницы
            TextView tv = Ui.overline(this, text);
            tv.setPadding(0, dp(Ui.S5), 0, dp(Ui.S2));
            root.addView(tv);
            block = null;
            return;
        }
        LinearLayout card = Ui.card(this);
        TextView head = Ui.overline(this, text);
        head.setPadding(0, 0, 0, dp(Ui.S2));
        card.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(Ui.S3);
        card.setLayoutParams(lp);
        pageBox.addView(card);
        block = card;
    }

    private void label(String text) {
        label(text, null);
    }

    /**
     * Подпись к полю + «ⓘ» с подробной справкой. Справка — то же, что в базе знаний
     * (docs/KNOWLEDGE.txt), только коротко: где значение применяется и что будет,
     * если поставить крайность.
     */
    private void label(String text, String help) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(Ui.S3), 0, dp(6));
        TextView tv = Ui.caption(this, text);
        tv.setTextSize(13);
        tv.setTextColor(TEXT);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        if (help != null) row.addView(infoButton(text, help));
        add(row);
    }

    /** Кнопка «ⓘ» — открывает справку по пункту. */
    private View infoButton(final String title, final String help) {
        TextView b = new TextView(this);
        b.setText("ⓘ");
        b.setTextColor(Ui.ACCENT);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        int size = dp(28);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        b.setBackground(Ui.shape(Ui.SURFACE_2, Ui.STROKE, 1, Ui.R_PILL, this));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Help.show(SettingsActivity.this, title, help);
            }
        });
        return b;
    }

    /** Кнопка-действие в паре с «ⓘ»: что именно произойдёт по нажатию. */
    private void addAction(Button button, String title, String help) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        button.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button);
        row.addView(infoButton(title, help));
        add(row);
    }

    private void note(String text) {
        TextView tv = Ui.caption(this, text);
        tv.setPadding(0, dp(Ui.S2), 0, dp(Ui.S2));
        add(tv);
    }

    private EditText baseField() {
        EditText et = new EditText(this);
        et.setBackground(Ui.shape(Ui.BG, Ui.STROKE, 1, Ui.R_MD, this));
        et.setTextColor(TEXT);
        et.setHintTextColor(TEXT_DIM);
        et.setPadding(dp(Ui.S3), dp(10), dp(Ui.S3), dp(10));
        et.setTextSize(14);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        return et;
    }

    private EditText singleField(String value) {
        EditText et = baseField();
        et.setSingleLine(false);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setText(value != null ? value : "");
        return et;
    }

    private EditText numberField(String value) {
        EditText et = baseField();
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(value);
        return et;
    }

    private EditText multiField(String value, int minLines) {
        EditText et = baseField();
        et.setMinLines(minLines);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setText(value != null ? value : "");
        return et;
    }

    private Switch switchRow(String text, boolean checked) {
        return switchRow(text, checked, null);
    }

    private Switch switchRow(String text, boolean checked, String help) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(Ui.S2), 0, dp(Ui.S2));
        row.setMinimumHeight(dp(Ui.TOUCH));
        TextView tv = Ui.body(this, text);
        tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        if (help != null) row.addView(infoButton(text, help));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        row.addView(sw);
        add(row);
        return sw;
    }

    private Button smallButton(String text) {
        Button b = Ui.secondary(this, text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * Один способ поддержки: название сети, подпись, адрес и кнопки.
     *
     * Адрес показывается моноширинным и разбивается на строки сам — так его можно
     * сверить глазами, не копируя. Копирование — отдельной кнопкой, потому что
     * выборочное копирование длинной строки на телефоне почти всегда с ошибкой.
     *
     * @param walletUri "bitcoin" — добавить кнопку «Открыть в кошельке» (URI-схема есть
     *                  только у BTC; для USDT в TON/TRON универсальной ссылки нет).
     */
    private void donateCard(String title, final String address, final String walletUri) {
        LinearLayout box = Ui.inner(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(Ui.S2);
        box.setLayoutParams(lp);

        TextView head = Ui.h2(this, title);
        head.setTextSize(14);
        head.setPadding(0, 0, 0, dp(Ui.S2));
        box.addView(head);

        TextView addr = Ui.mono(this, address, 12, Ui.ACCENT);
        addr.setPadding(0, 0, 0, dp(Ui.S2));
        box.addView(addr);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = Ui.small(this, "Скопировать адрес");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyToClipboard(title, address);
            }
        });
        buttons.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (walletUri != null) {
            Button open = Ui.small(this, "Открыть в кошельке");
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            openLp.leftMargin = dp(Ui.S2);
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(walletUri + ":" + address)));
                    } catch (Exception e) {
                        toast("Кошелёк не найден — скопируйте адрес");
                    }
                }
            });
            buttons.addView(open, openLp);
        }
        box.addView(buttons);
        add(box);
    }

    /** Копирование в буфер: одна точка, чтобы подпись буфера всегда была осмысленной. */
    private void copyToClipboard(String label, String value) {
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cb == null) return;
            cb.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
            toast("Скопировано");
        } catch (Throwable t) {
            toast("Не удалось скопировать");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static int parseInt(EditText et, int fallback) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String normalizeLines(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }

    /** Прочитать asset целиком (нужно для базы знаний). */
    private String readAsset(String asset) {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getAssets().open(asset);
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            return "";
        }
        return sb.toString();
    }

    private String readAssetFirstN(String asset, int n) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        try (InputStream in = getAssets().open(asset);
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && count < n) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.regionMatches(true, 0, "Bridge ", 0, 7)) t = t.substring(7).trim();
                sb.append(t).append('\n');
                count++;
            }
        } catch (Exception ignored) {
        }
        return sb.toString().trim();
    }

    /** Версия приложения из манифеста (не путать с версией Xray). */
    private String appVersion() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String versionLine() {
        String v;
        try {
            v = XrayEngine.version();
        } catch (Throwable t) {
            v = "н/д";
        }
        return "Xray-core: " + v;
    }
}
