package ru.whitelist.bypass;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Настройки: источники, пинги, скорость, потоки, стратегии, тор, резерв. */
public class SettingsActivity extends Activity {

    private static final int ACCENT = 0xFF3DDC84;
    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;
    private static final int SURFACE = 0xFF161F2A;

    private LinearLayout root;
    private EditText editSources, editPingTargets, editGithubHost, editGithubIps,
            editSpeedUrl, editSpeedMin, editBatch, editThreads, editTimeout,
            editSwitchTimeout, editMonitor, editStrategy, editByedpiDns, editVpnDns,
            editTorBridges, editTorExtra, editDpiTestUrls, editSpeedGateUrls, editDpiStratsUrl;
    private Switch swAutoSwitch, swQuietUpdate, swAutoStart, swEmbedded, swInsecure, swIpv6, swBoot, swVpnDesync, swVpnRace, swTorBuiltin,
            swDpiAuto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0E141B);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(32));
        scroll.addView(root);
        setContentView(scroll);

        section("Источники конфигов (по одному на строку)");
        note("Скачивание — только кнопкой «Обновить конфиги» или тихо при достаточной скорости. "
                + "GitHub пингуется перед загрузкой. Партия — не более «Максимум за партию» строк, "
                + "каждая партия пингуется, мёртвые отбраковываются.");
        editSources = multiField(joinLines(Prefs.sources(this)), 7);
        root.addView(editSources);
        Button resetSources = smallButton("Сбросить источники по умолчанию");
        resetSources.setOnClickListener(v -> {
            editSources.setText(Prefs.join(Prefs.DEFAULT_SOURCES));
            Toast.makeText(this, "Источники сброшены (применится после сохранения)", Toast.LENGTH_SHORT).show();
        });
        root.addView(resetSources);

        section("Адреса для проверки связи (host:port)");
        editPingTargets = multiField(joinLines(Prefs.pingTargets(this)), 5);
        root.addView(editPingTargets);

        section("GitHub (для загрузки конфигов)");
        editGithubHost = singleField(Prefs.githubHost(this), "хост, напр. github.com");
        label("Хост для пинга GitHub");
        root.addView(editGithubHost);
        editGithubIps = singleField(getStringFromPrefs(Prefs.GITHUB_FALLBACK_IPS,
                "140.82.112.3,140.82.113.3,140.82.114.4"), "резервные IP через запятую");
        label("Резервные IP GitHub (если DNS врёт)");
        root.addView(editGithubIps);

        section("Замер скорости текущего соединения");
        editSpeedUrl = singleField(Prefs.speedTestUrl(this), "URL до ~512 КБ");
        label("URL для замера скорости");
        root.addView(editSpeedUrl);
        editSpeedMin = numberField(String.valueOf(Prefs.speedMinKbps(this)), "КБ/с");
        label("Минимальная скорость для тихого скачивания, КБ/с");
        root.addView(editSpeedMin);

        section("Допроверка запуска (VPN/Tor/DPI)");
        editSpeedGateUrls = multiField(joinLines(Prefs.speedGateUrls(this)), 3);
        label("Файлы по 1 МБ для допроверки канала (по одному на строку)");
        root.addView(editSpeedGateUrls);
        note("Перед поднятием туннеля движок обязан скачать 1 МБ за ≤ 2 с, иначе конфиг / стратегия / "
                + "мосты считаются нерабочими и пробуется следующий вариант. Для DPI проверка идёт по ссылкам "
                + "выше (YouTube), а не по открытым CDN. Если 1 МБ скачивается быстрее 500 мс — в фоне молча "
                + "докачивается один текстовый файл: VPN — свежая подписка с конфигами, Tor — свежие мосты "
                + "(автообновление, живёт 14 дней).\n"
                + "Источники мостов: igareck (РФ, ежечасно) → Learntous7 → OnionHop.");

        section("Движки и потоки");
        editBatch = numberField(String.valueOf(Prefs.batchSize(this)), "штук");
        label("Максимум конфигов за одну партию");
        root.addView(editBatch);
        editThreads = numberField(String.valueOf(Prefs.pingThreads(this)), "потоков");
        label("Потоков пинга одновременно (рекомендуется 100)");
        root.addView(editThreads);
        editTimeout = numberField(String.valueOf(Prefs.pingTimeoutMs(this)), "мс");
        label("Таймаут пинга, мс");
        root.addView(editTimeout);
        editSwitchTimeout = numberField(String.valueOf(Prefs.switchTimeoutSec(this)), "сек");
        label("Сколько секунд без ответа до автопереключения");
        root.addView(editSwitchTimeout);
        editMonitor = numberField(String.valueOf(Prefs.monitorIntervalSec(this)), "сек");
        label("Интервал мониторинга канала, сек (мин. 5)");
        root.addView(editMonitor);

        swAutoSwitch = switchRow("Автопереключение при отсутствии ответа", Prefs.autoSwitch(this));
        swQuietUpdate = switchRow("Тихо догружать в фоне один текстовый файл при быстром канале (< 500 мс / 1 МБ): VPN — конфиги, Tor — мосты", Prefs.quietUpdate(this));
        swAutoStart = switchRow("Фоновая работа при запуске (статистика + тихое докачивание)",
                Prefs.autoUpdateOnStart(this));
        swEmbedded = switchRow("Встроенные резервные конфиги (не удалять)", Prefs.embeddedEnabled(this));
        swInsecure = switchRow("Разрешить невалидные TLS-сертификаты (insecure)", Prefs.vpnInsecure(this));
        swIpv6 = switchRow("Маршрутизировать IPv6", Prefs.ipv6(this));
        swVpnDesync = switchRow("DPI-обход на линии до VPN-сервера",
                Prefs.vpnDesync(this));
        note("Включено по умолчанию (0.017): перед каждым VPN-подключением поднимается локальный "
                + "ByeDPI с вашей стратегией, линия к VPN-серверу идёт через него (по мотивам "
                + "igareck/anti-dpi-for-russia: те же десинхронизации, что и для заблокированных сайтов, "
                + "применяются к рукопожатию VPN). SNI берётся из конфига. Если не помогло — конфиг "
                + "пробуется штатно без обхода. Массовые проверки тоже учитывают этот обход: конфиг, "
                + "оживившийся с ним, не будет удалён, а фаза «непингуемые» в поиске не трогается.");
        swVpnRace = switchRow("Автогонка топ-3 при подключении (кто первый прошёл — туннель ему)",
                Prefs.vpnRace(this));
        note("Три лучших конфига поднимаются одновременно; первый, кто открыл канал и ответил, "
                + "забирает туннель — остальные останавливаются. Если ни один не прошёл — обычный "
                + "последовательный перебор. По умолчанию включено.");
        swBoot = switchRow("Подключаться при загрузке системы", Prefs.bootConnect(this));

        section("DPI-обход (ByeDPI)");
        editStrategy = singleField(Prefs.strategy(this), "аргументы ciadpi, напр. --disorder 1 --auto=torst --tlsrec 1+s");
        label("Стратегия (аргументы для ciadpi)");
        root.addView(editStrategy);
        Button recommended = smallButton("Вставить рекомендуемую: --disorder 1 --auto=torst --tlsrec 1+s");
        recommended.setOnClickListener(v -> editStrategy.setText(Prefs.DEFAULT_STRATEGY));
        root.addView(recommended);
        editByedpiDns = singleField(Prefs.byedpiDns(this), "например 1.1.1.1");
        label("DNS (резерв; в туннеле используется общий DNS)");
        root.addView(editByedpiDns);
        swDpiAuto = switchRow("Перед включением проверить YouTube и подобрать рабочую стратегию",
                Prefs.dpiAutoSelect(this));
        editDpiTestUrls = multiField(joinLines(Prefs.dpiTestUrls(this)), 2);
        label("Ссылки для проверки перед включением (по одной на строку)");
        root.addView(editDpiTestUrls);
        note("При включённом автовыборе перед поднятием туннеля каждая встроенная стратегия "
                + "проверяется локально: нужен ответ от всех ссылок (по умолчанию — YouTube). "
                + "Подходящая стратегия подставляется автоматически. Российские ресурсы в проверку не входят.");
        editDpiStratsUrl = singleField(Prefs.dpiStrategiesUrl(this),
                "URL raw-списка ciadpi-стратегий (пусто = не обновлять)");
        label("Свежие стратегии DPI (обновляются вместе с «Обновить конфиги»)");
        root.addView(editDpiStratsUrl);
        note("Автообновление стратегий: " + bridgesAutoAtLabel(Prefs.dpiStrategiesAutoAt(this)) + ". "
                + "Источник по умолчанию — встроенный список ByeByeDPI (romanvht/ByeByeDPI, proxytest_strategies.list). "
                + "Каждая авто-скачанная строка проходит строгую проверку формата ciadpi; невалидные отбрасываются. "
                + "Свежий список используется вместо встроенного (30 дней).");

        section("Tor");
        swTorBuiltin = switchRow("Если поле пусто — брать мосты из встроенного резерва (Vanilla)",
                Prefs.torBuiltin(this));
        editTorBridges = multiField(Prefs.torBridges(this), 6);
        label("Мосты Tor — по одному на строку (формат: IP:PORT или обфусцированные)");
        root.addView(editTorBridges);
        Button fillBridges = smallButton("Вставить встроенные Vanilla-мосты");
        fillBridges.setOnClickListener(v -> editTorBridges.setText(readAssetFirstN(
                "fallback/tor_bridges_vanilla.txt", 60)));
        root.addView(fillBridges);
        editTorExtra = multiField(Prefs.torExtraTorrc(this), 3);
        label("Дополнительные строки torrc (например ClientTransportPlugin …)");
        root.addView(editTorExtra);
        note("obfs4/webtunnel требуют плагин (lyrebird/webtunnel), которого во встроенном Tor нет — "
                + "используйте Vanilla-мосты или добавьте плагин в APK самостоятельно.");
        note("Автообновление мостов: " + bridgesAutoAtLabel(Prefs.torBridgesAutoAt(this)) + ". "
                + "Докачиваются в фоне только при быстром канале (1 МБ < 500 мс) и включённой опции ниже.");
        editVpnDns = singleField(Prefs.vpnDns(this), "например 1.1.1.1");
        label("DNS для TUN (VPN/Tor)");
        root.addView(editVpnDns);

        section("Фоновая работа");
        String batteryLabel = PermissionsHelper.isBatteryOptimizationIgnored(this)
                ? "Защита от выгрузки: ВКЛЮЧЕНА ✓"
                : "Включить защиту от выгрузки (игнорировать оптимизацию батареи)";
        Button batteryBtn = smallButton(batteryLabel);
        batteryBtn.setOnClickListener(v -> PermissionsHelper.requestBatteryExemption(this));
        root.addView(batteryBtn);
        note("Игнорирование оптимизации батареи позволяет ОС НЕ выгружать приложение из памяти: "
                + "туннель и мониторинг продолжат работать в фоне. Разрешение выдаёт сама система.");

        section("Данные и кэш");
        Button clearCache = smallButton("Удалить скачанные конфиги (встроенные останутся)");
        clearCache.setOnClickListener(v -> {
            int n = ConfigRepository.clearDownloaded(this);
            new LatencyStore(this).clear();
            Toast.makeText(this, "Удалено скачанных записей: " + n, Toast.LENGTH_LONG).show();
        });
        root.addView(clearCache);

        Button save = smallButton("Сохранить");
        save.setBackgroundResource(android.R.drawable.btn_default);
        save.setTextColor(ACCENT);
        save.setTextSize(16);
        save.setOnClickListener(v -> {
            savePrefs();
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save);

        TextView footer = new TextView(this);
        footer.setText(footerVersions());
        footer.setTextColor(TEXT_DIM);
        footer.setTextSize(11);
        footer.setPadding(0, dp(24), 0, 0);
        root.addView(footer);
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePrefs();
    }

    private void savePrefs() {
        android.content.SharedPreferences.Editor e = Prefs.get(this).edit();
        e.putString(Prefs.SOURCES, normalizeLines(editSources.getText().toString()));
        e.putString(Prefs.PING_TARGETS, normalizeLines(editPingTargets.getText().toString()));
        e.putString(Prefs.GITHUB_HOST, editGithubHost.getText().toString().trim());
        e.putString(Prefs.GITHUB_FALLBACK_IPS, editGithubIps.getText().toString().trim());
        e.putString(Prefs.SPEED_TEST_URL, editSpeedUrl.getText().toString().trim());
        e.putString(Prefs.SPEED_GATE_URLS, normalizeLines(editSpeedGateUrls.getText().toString()));
        e.putInt(Prefs.SPEED_MIN_KBPS, parseInt(editSpeedMin, 256));
        e.putInt(Prefs.BATCH_SIZE, Prefs.clamp(parseInt(editBatch, 100), 10, 500));
        e.putInt(Prefs.PING_THREADS, Prefs.clamp(parseInt(editThreads, 100), 1, 200));
        e.putInt(Prefs.PING_TIMEOUT_MS, Prefs.clamp(parseInt(editTimeout, 2500), 300, 15000));
        e.putInt(Prefs.SWITCH_TIMEOUT_SEC, Prefs.clamp(parseInt(editSwitchTimeout, 45), 5, 3600));
        e.putInt(Prefs.MONITOR_INTERVAL_SEC, Prefs.clamp(parseInt(editMonitor, 15), 5, 300));
        e.putString(Prefs.BYEDPI_STRATEGY, editStrategy.getText().toString().trim());
        e.putString(Prefs.DPI_TEST_URLS, normalizeLines(editDpiTestUrls.getText().toString()));
        Prefs.setDpiStrategiesUrl(this, editDpiStratsUrl.getText().toString().trim());
        e.putString(Prefs.BYEDPI_DNS, editByedpiDns.getText().toString().trim());
        e.putString(Prefs.VPN_DNS, editVpnDns.getText().toString().trim());
        e.putString(Prefs.TOR_BRIDGES, normalizeLines(editTorBridges.getText().toString()));
        e.putString(Prefs.TOR_EXTRA_TORRC, editTorExtra.getText().toString().trim());
        e.putBoolean(Prefs.AUTO_SWITCH, swAutoSwitch.isChecked());
        e.putBoolean(Prefs.QUIET_UPDATE, swQuietUpdate.isChecked());
        e.putBoolean(Prefs.AUTO_UPDATE_ON_START, swAutoStart.isChecked());
        e.putBoolean(Prefs.EMBEDDED_ENABLED, swEmbedded.isChecked());
        e.putBoolean(Prefs.VPN_INSECURE, swInsecure.isChecked());
        e.putBoolean(Prefs.IPV6, swIpv6.isChecked());
        e.putBoolean(Prefs.VPN_DESYNC, swVpnDesync.isChecked());
        e.putBoolean(Prefs.VPN_RACE, swVpnRace.isChecked());
        e.putBoolean(Prefs.BOOT_CONNECT, swBoot.isChecked());
        e.putBoolean(Prefs.TOR_BUILTIN, swTorBuiltin.isChecked());
        e.putBoolean(Prefs.DPI_AUTO_SELECT, swDpiAuto.isChecked());
        e.apply();
    }

    private static String bridgesAutoAtLabel(long at) {
        if (at <= 0) return "ещё ни разу";
        return new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date(at));
    }

    // ---------- строительные блоки ----------

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
        tv.setPadding(dp(4), 0, dp(4), dp(4));
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

    private EditText singleField(String value, String hint) {
        EditText et = baseField();
        et.setSingleLine(false);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setHint(hint);
        et.setText(value != null ? value : "");
        return et;
    }

    private EditText numberField(String value, String hint) {
        EditText et = baseField();
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint(hint);
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

    private Switch switchRow(String label, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(TEXT);
        tv.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        row.addView(tv);
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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

    private static String joinLines(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    private String getStringFromPrefs(String key, String fallback) {
        return Prefs.get(this).getString(key, fallback);
    }

    private String readAssetFirstN(String asset, int n) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        try (InputStream in = getAssets().open(asset)) {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
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

    private String footerVersions() {
        String xray = "Xray-core: ";
        try {
            xray += XrayEngine.version();
        } catch (Throwable t) {
            xray += "н/д";
        }
        return "Обход Белых Списков · 0.017 (06.11.2026)\n"
                + xray + "\n"
                + "Tor: 0.4.9.13.2 (tor-android, Guardian Project)\n"
                + "ByeDPI: ciadpi (hufrea/byedpi) · hev-socks5-tunnel (heiher)\n"
                + "Ядра встроены локально; трафик не проходит через сторонние серверы приложения.\n"
                + "Встроенные конфиги: срез igareck/vpn-configs-for-russia на 05.11.2026.";
    }
}
