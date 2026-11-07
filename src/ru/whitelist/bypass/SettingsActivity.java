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

    private EditText editSources, editSpeedUrls, editMinSpeed, editThreads, editTimeout,
            editGateTimeout, editStrategy, editVpnDns, editMonitor, editSwitchTimeout,
            editTorBridges, editTorExtra, editTorBridgeWait, editPingTargets, editHealthUrls,
            editLanes, editDpiSources;
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
        scroll.setBackgroundColor(0xFF000000);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);
        Ui.applyInsets(scroll);

        // --- скорость ---
        section("Скорость");
        editMinSpeed = numberField(String.valueOf(Prefs.minSpeedKbit(this)));
        label("Порог «рабочего» конфига, кбит/с (32 по умолчанию = 4 КБ/с)");
        root.addView(editMinSpeed);
        note("Единицы: 1 КБ/с = 8 кбит/с. По умолчанию 32 кбит/с — это 4 КБ/с: конфиг "
                + "засчитывается, если качает быстрее 4 КБ/с. Порог низкий намеренно: живой, "
                + "но медленный конфиг лучше, чем пустой пул. На быстром Wi-Fi можно поднять "
                + "до 512 или 1024.");

        editLanes = numberField(String.valueOf(Prefs.speedLanes(this)));
        label("Сколько ядер мерить скорость одновременно (1–4, по умолчанию 4)");
        root.addView(editLanes);

        editSpeedUrls = multiField(Prefs.join(Prefs.speedUrls(this)), 3);
        label("Файлы для замера скорости (по одному на строку)");
        root.addView(editSpeedUrls);

        editGateTimeout = numberField(String.valueOf(Prefs.gateTimeoutMs(this)));
        label("Сколько ждём на один замер, мс (3000 по умолчанию)");
        root.addView(editGateTimeout);

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
        label("Потоков пинга одновременно");
        root.addView(editThreads);
        editTimeout = numberField(String.valueOf(Prefs.pingTimeoutMs(this)));
        label("Таймаут пинга, мс (2000 по умолчанию)");
        root.addView(editTimeout);

        editPingTargets = multiField(Prefs.join(Prefs.pingTargets(this)), 5);
        label("5 целей TCP-пинга «есть ли интернет» (host:port, по одной на строку)");
        root.addView(editPingTargets);

        editHealthUrls = multiField(Prefs.join(Prefs.healthUrls(this)), 5);
        label("5 мест проверки канала через туннель (URL, по одному на строку)");
        root.addView(editHealthUrls);

        note("1000 мс — это фильтр «более-менее работающих»: то, что пингуется дольше, "
                + "всё равно не годится для обхода. Пинг не решает «рабочий/не рабочий»: "
                + "он только отсекает мёртвое. Конфиг, "
                + "прошедший TLS-handshake, дополнительно проверяется обычным TCP — "
                + "поэтому Reality и чужие SNI не отбрасываются зря. Рабочесть и скорость "
                + "подтверждаются реальной загрузкой через поднятое ядро Xray.");

        // --- источники ---
        section("Источники списков (по одному на строку)");
        editSources = multiField(Prefs.join(Prefs.sources(this)), 7);
        root.addView(editSources);
        Button resetSources = smallButton("Вернуть источники по умолчанию");
        resetSources.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editSources.setText(Prefs.join(Prefs.DEFAULT_SOURCES));
                toast("Применится после «Сохранить»");
            }
        });
        root.addView(resetSources);

        // --- DPI на линии ---
        section("DPI-обход");
        swDesync = switchRow("DPI-обход линии до VPN-сервера (ciadpi) — по умолчанию включён",
                Prefs.desyncLine(this));
        editStrategy = singleField(Prefs.dpiStrategy(this));
        label("Стратегия ciadpi (аргументы командной строки)");
        root.addView(editStrategy);
        Button recommended = smallButton("Вставить: " + Prefs.DEFAULT_STRATEGY);
        recommended.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editStrategy.setText(Prefs.DEFAULT_STRATEGY);
            }
        });
        root.addView(recommended);
        note("Сначала всегда пробуется прямое подключение. Через ciadpi — только если оно "
                + "не прошло (первые 3 конфига). TLS к серверу при этом фрагментируется, "
                + "поэтому для Reality этот путь может не сработать.");

        swDpiAutotune = switchRow("Подбирать стратегию автоматически (только вместе с DPI-обходом)",
                Prefs.dpiAutotune(this));
        note("При подключении приложение по очереди поднимает ciadpi с разными стратегиями "
                + "и проверяет, открываются ли заблокированные адреса. Первая подошедшая "
                + "сохраняется и используется дальше. Это занимает до ~30 секунд один раз "
                + "за подключение.");

        editDpiSources = multiField(Prefs.join(Prefs.dpiSources(this)), 3);
        label("Откуда брать стратегии (по одной ссылке на строку)");
        root.addView(editDpiSources);

        Button refreshDpi = smallButton("Обновить стратегии с GitHub");
        refreshDpi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                toast("Скачиваю списки стратегий…");
                final TextView out = new TextView(SettingsActivity.this);
                out.setTextColor(TEXT_DIM);
                out.setTextSize(12);
                root.addView(out);
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
                                out.append("\nВсего стратегий: " + n);
                                toast("Стратегий сохранено: " + n);
                            }
                        });
                    }
                }, "dpi-refresh");
                t.setDaemon(true);
                t.start();
            }
        });
        root.addView(refreshDpi);

        Button dpiProbe = smallButton("Проверить стратегии сейчас");
        dpiProbe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                final TextView out = new TextView(SettingsActivity.this);
                out.setTextColor(TEXT_DIM);
                out.setTextSize(12);
                root.addView(out);
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
                                editStrategy.setText(chosen);
                                toast("Рабочая стратегия сохранена в поле выше");
                            }
                        });
                    }
                }, "dpi-probe-all");
                t.setDaemon(true);
                t.start();
            }
        });
        root.addView(dpiProbe);

        // --- ядро ---
        section("Ядро Xray");
        swNoTls = switchRow("Разрешать конфиги без TLS (через локальный ретранслятор)",
                Prefs.allowNoTls(this));
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
        label("DNS для туннеля");
        root.addView(editVpnDns);
        swIpv6 = switchRow("Маршрутизировать IPv6", Prefs.ipv6(this));
        swAutoSwitch = switchRow("Переключать конфиг, если канал не отвечает", Prefs.autoSwitch(this));
        editMonitor = numberField(String.valueOf(Prefs.monitorIntervalSec(this)));
        label("Проверка канала каждые, сек");
        root.addView(editMonitor);
        editSwitchTimeout = numberField(String.valueOf(Prefs.switchTimeoutSec(this)));
        label("Секунд без ответа до переключения");
        root.addView(editSwitchTimeout);
        swBoot = switchRow("Подключаться при загрузке", Prefs.bootConnect(this));

        // --- Tor ---
        section("Tor: мосты, резервный канал и списки");
        note("Tor нужен в двух случаях: (1) прямые источники и jsDelivr не отдали списки — "
                + "тогда Tor единственная дорога к ним; (2) ни один VPN-конфиг не отвечает — "
                + "тогда трафик идёт через мосты (медленно, но связь есть). Обычным режимом "
                + "Tor не включается.");
        swTorFallback = switchRow("Если VPN не отвечает — пускать трафик через Tor-мосты",
                Prefs.torFallback(this));
        editTorBridges = multiField(Prefs.torBridges(this), 4);
        label("Мосты (пусто — встроенные Vanilla)");
        root.addView(editTorBridges);
        Button fillBridges = smallButton("Вставить встроенные Vanilla-мосты");
        fillBridges.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editTorBridges.setText(readAssetFirstN("fallback/tor_bridges_vanilla.txt", 20));
            }
        });
        root.addView(fillBridges);
        editTorExtra = multiField(Prefs.torExtraTorrc(this), 2);
        label("Дополнительные строки torrc");
        root.addView(editTorExtra);

        label("Лимит ожидания одного моста, секунд (5–120)");
        editTorBridgeWait = numberField(String.valueOf(Prefs.torBridgeWaitSec(this)));
        editTorBridgeWait.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(editTorBridgeWait);
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
        root.addView(torOut);

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
        root.addView(checkTor);

        // --- пул: импорт и экспорт ---
        section("Пул: импорт и экспорт");
        note("Экспорт — обычный .txt, по одной ссылке на строку. Импорт файлу не доверяет: "
                + "дубликаты отбрасываются (и внутри файла, и уже лежащие в пуле), остальные "
                + "пингуются, затем у живых замеряется скорость — в пул попадают только "
                + "прошедшие порог.");

        final TextView poolOut = new TextView(this);
        poolOut.setTextColor(TEXT_DIM);
        poolOut.setTextSize(12);
        poolOut.setText("В пуле " + Pool.count(this) + " конфигов.");
        root.addView(poolOut);
        importLog = poolOut;

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
        root.addView(exportPool);

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
        root.addView(importPool);

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
        root.addView(exportSettings);

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
        root.addView(importSettings);

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
        root.addView(presetSpinner);

        presetNote = new TextView(this);
        presetNote.setTextColor(TEXT_DIM);
        presetNote.setTextSize(12);
        presetNote.setPadding(0, dp(6), 0, dp(6));
        root.addView(presetNote);
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
        root.addView(applyPreset);

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
        root.addView(deletePreset);

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
        root.addView(saveConsole);
        note("Консоль — это лог с главного экрана: пинг, замер, причины отказов, мосты. "
                + "Файл содержит строки и склеенный текст, версию приложения и число конфигов "
                + "в пуле.");

        // --- данные ---
        section("Данные");
        swEmbedded = switchRow("Встроенный резерв конфигов", Prefs.embeddedEnabled(this));
        Button clearAll = smallButton("Удалить скачанные списки и пул");
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ConfigRepository.clearDownloaded(SettingsActivity.this);
                Pool.clear(SettingsActivity.this);
                toast("Скачанные списки и пул удалены (встроенный резерв остался)");
            }
        });
        root.addView(clearAll);

        Button battery = smallButton("Разрешить работу в фоне (без выгрузки)");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PermissionsHelper.requestBatteryExemption(SettingsActivity.this);
            }
        });
        root.addView(battery);

        Button save = smallButton("Сохранить");
        save.setTextColor(0xFF3DDC84);
        save.setTextSize(16);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                toast("Сохранено");
                finish();
            }
        });
        root.addView(save);

        Button browser = smallButton("Браузер (только Tor с мостами, поиск DuckDuckGo)");
        browser.setTextColor(0xFFB388FF);
        browser.setTextSize(15);
        browser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePrefs();
                startActivity(new android.content.Intent(SettingsActivity.this, BrowserActivity.class));
            }
        });
        root.addView(browser);

        TextView footer = new TextView(this);
        footer.setText("Обход Белых Списков 0.1.6\n"
                + versionLine() + "\n"
                + "Ядра (Xray, ByeDPI, Tor) работают локально; сторонних серверов у приложения нет.");
        footer.setTextColor(TEXT_DIM);
        footer.setTextSize(11);
        footer.setPadding(0, dp(24), 0, 0);
        root.addView(footer);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (skipSaveOnPause) return;  // только что импортировали файл: не затирать его экраном
        savePrefs();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        e.putInt(Prefs.PING_THREADS, Prefs.clamp(parseInt(editThreads, 60), 1, 200));
        e.putInt(Prefs.PING_TIMEOUT_MS,
                Prefs.clamp(parseInt(editTimeout, Prefs.DEFAULT_PING_TIMEOUT_MS), 300, 15000));
        e.putInt(Prefs.SPEED_LANES, Prefs.clamp(parseInt(editLanes, 3), 1, 4));
        e.putString(Prefs.PING_TARGETS, normalizeLines(editPingTargets.getText().toString()));
        e.putString(Prefs.HEALTH_URLS, normalizeLines(editHealthUrls.getText().toString()));
        e.putString(Prefs.DPI_STRATEGY, editStrategy.getText().toString().trim());
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

    // ------------------------------------------------------------------ строительные блоки

    private void section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(12);
        tv.setAllCaps(true);
        tv.setPadding(0, dp(18), 0, dp(6));
        root.addView(tv);
    }

    private void label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(11);
        tv.setPadding(0, dp(2), 0, dp(10));
        root.addView(tv);
    }

    private void note(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(11);
        tv.setPadding(dp(4), 0, dp(4), dp(6));
        root.addView(tv);
    }

    private EditText baseField() {
        EditText et = new EditText(this);
        et.setBackgroundResource(R.drawable.bg_input);
        et.setTextColor(TEXT);
        et.setHintTextColor(TEXT_DIM);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setTextSize(13);
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
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT);
        tv.setTextSize(13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        row.addView(sw);
        root.addView(row);
        return sw;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setBackgroundResource(R.drawable.bg_button);
        b.setTextSize(13);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
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
