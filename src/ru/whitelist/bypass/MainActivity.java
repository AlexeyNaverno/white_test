package ru.whitelist.bypass;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextUtils;
import ru.whitelist.bypass.jni.ByeDpiProxy;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Программный (без XML-разметки) интерфейс: стандартные виджеты Android.
 * Вся логика управления — как в исходной сборке; CrashReporter защищает старт.
 */
public class MainActivity extends Activity {

    private static final int REQ_VPN = 100;

    private static final int SURFACE = 0xFF161F2A;
    private static final int SURFACE2 = 0xFF1E2A38;
    private static final int SURFACE3 = 0xFF1F3A2A;
    private static final int ACCENT = 0xFF3DDC84;
    private static final int ACCENT_DIM = 0xFF1F5C3A;
    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;
    private static final int RED = 0xFFFF5252;
    private static final int ORANGE = 0xFFFFAB40;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();

    private TextView statusPill, activeConfigText, pingText, speedText, configsCountText, logText;
    private TextView torInfoText, dpiInfoText;
    private Button powerButton, btnQuickSearch, btnFullCheck, btnUpdate, btnConfigs, btnSettings;
    private Button btnTorBridges, btnTorMass, btnDpiPick, btnDpiMicro, btnDpiStrats;
    private LinearLayout vpnPanel, torPanel, dpiPanel, autoPanel;
    private Button modeVpn, modeTor, modeDpi, modeAuto;
    private Button btnAutoQuick, btnAutoUpdate, btnStopAll;
    private String mode = "dpi";
    private volatile boolean vpnTestRunning = false;
    private volatile boolean vpnTestStopRequested = false;
    /** Scratch-ByeDPI для теста конфига с DPI-обходом (тест ≠ туннель, поэтому отдельный процесс). */
    private volatile ByeDpiProxy desyncTestProxy;
    private volatile boolean dpiProbeRunning = false;

    private BroadcastReceiver receiver;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashReporter.install(this);                    // самый первый вызов
        String previousCrash = CrashReporter.consumeIfPresent(this);
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        if (previousCrash != null) showCrashDialog(previousCrash);

        // ---------- шапка: название + кнопка настроек ----------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Обход Белых Списков");
        title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        TextView subtitle = new TextView(this);
        subtitle.setText("VPN · Tor · DPI-обход — всегда на связи");
        subtitle.setTextSize(11);
        subtitle.setTextColor(TEXT_DIM);
        titleCol.addView(title);
        titleCol.addView(subtitle);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnSettings = smallButton("Настройки");
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(btnSettings);
        root.addView(header);

        // ---------- статус-плашка ----------
        statusPill = new TextView(this);
        statusPill.setText("● Отключено");
        statusPill.setTextSize(13);
        statusPill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusPill.setTextColor(TEXT_DIM);
        statusPill.setPadding(dp(14), dp(7), dp(14), dp(7));
        statusPill.setBackgroundColor(SURFACE2);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.topMargin = dp(14);
        statusPill.setLayoutParams(pillLp);
        root.addView(statusPill);

        // ---------- большая кнопка питания ----------
        powerButton = new Button(this);
        powerButton.setText("Подключить");
        powerButton.setTextSize(20);
        powerButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        powerButton.setTextColor(TEXT);
        powerButton.setAllCaps(false);
        powerButton.setBackgroundColor(SURFACE2);
        LinearLayout.LayoutParams powerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        powerLp.topMargin = dp(18);
        powerButton.setLayoutParams(powerLp);
        powerButton.setOnClickListener(v -> toggleConnection());
        root.addView(powerButton);

        // ---------- выбор режима ----------
        TextView section = capsLabel("Способ обхода");
        LinearLayout.LayoutParams secLp = (LinearLayout.LayoutParams) section.getLayoutParams();
        if (secLp == null) secLp = new LinearLayout.LayoutParams(-2, -2);
        secLp.topMargin = dp(18);
        section.setLayoutParams(secLp);
        root.addView(section);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modeVpn = modeButton("VPN\nXray");
        modeTor = modeButton("Tor\nмосты");
        modeDpi = modeButton("DPI\nобход");
        modeAuto = modeButton("Авто\nVPN→Tor→DPI");
        LinearLayout.LayoutParams mLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mLp1.setMargins(0, 0, dp(3), 0);
        LinearLayout.LayoutParams mLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mLp2.setMargins(dp(3), 0, dp(3), 0);
        LinearLayout.LayoutParams mLp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mLp3.setMargins(dp(3), 0, dp(3), 0);
        LinearLayout.LayoutParams mLp4 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mLp4.setMargins(dp(3), 0, 0, 0);
        modes.addView(modeVpn, mLp1);
        modes.addView(modeTor, mLp2);
        modes.addView(modeDpi, mLp3);
        modes.addView(modeAuto, mLp4);
        root.addView(modes);

        btnStopAll = fullWidthButton("■  СТОП — остановить всё (тесты, поиски, обновления)");
        btnStopAll.setTextColor(RED);
        LinearLayout.LayoutParams stopLp = (LinearLayout.LayoutParams) btnStopAll.getLayoutParams();
        if (stopLp == null) stopLp = new LinearLayout.LayoutParams(-1, -2);
        stopLp.topMargin = dp(10);
        btnStopAll.setLayoutParams(stopLp);
        btnStopAll.setOnClickListener(v -> stopEverythingUi());
        root.addView(btnStopAll);
        modeVpn.setOnClickListener(v -> setMode("vpn"));
        modeTor.setOnClickListener(v -> setMode("tor"));
        modeDpi.setOnClickListener(v -> setMode("dpi"));
        modeAuto.setOnClickListener(v -> setMode("auto"));

        // ========== VPN-панель: «Активный путь» + конфиги ==========
        vpnPanel = new LinearLayout(this);
        vpnPanel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = cardPanel("Активный путь");
        activeConfigText = new TextView(this);
        activeConfigText.setText("не выбран");
        activeConfigText.setTextSize(13);
        activeConfigText.setTextColor(TEXT);
        activeConfigText.setSingleLine(true);
        activeConfigText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(activeConfigText);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsLp.topMargin = dp(8);
        stats.setLayoutParams(statsLp);
        TextView pingLbl = plain("Пинг: ", 12, TEXT_DIM);
        pingText = plain("– мс", 12, ACCENT);
        TextView speedLbl = plain("   Скорость: ", 12, TEXT_DIM);
        speedText = plain("– КБ/с", 12, ACCENT);
        stats.addView(pingLbl);
        stats.addView(pingText);
        stats.addView(speedLbl);
        stats.addView(speedText);
        card.addView(stats);

        configsCountText = plain("Конфиги: —", 11, TEXT_DIM);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.topMargin = dp(6);
        configsCountText.setLayoutParams(ccLp);
        card.addView(configsCountText);
        vpnPanel.addView(card);

        btnQuickSearch = fullWidthButton("Быстрый поиск: пинг всех → скорость до 5 рабочих");
        ((LinearLayout.LayoutParams) btnQuickSearch.getLayoutParams()).topMargin = dp(16);
        btnQuickSearch.setOnClickListener(v -> runVpnSearch(true));
        vpnPanel.addView(btnQuickSearch);

        btnFullCheck = fullWidthButton("Полная проверка: дедуп → пинг → скорость (до 200)");
        ((LinearLayout.LayoutParams) btnFullCheck.getLayoutParams()).topMargin = dp(8);
        btnFullCheck.setOnClickListener(v -> runVpnSearch(false));
        vpnPanel.addView(btnFullCheck);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams act2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        act2Lp.topMargin = dp(8);
        actions.setLayoutParams(act2Lp);
        btnUpdate = fullWidthButton("Обновить конфиги");
        btnConfigs = fullWidthButton("Список конфигов");
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l1.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l2.setMargins(dp(4), 0, 0, 0);
        actions.addView(btnUpdate, l1);
        actions.addView(btnConfigs, l2);
        btnUpdate.setOnClickListener(v -> runFullUpdate());
        btnConfigs.setOnClickListener(v ->
                startActivity(new Intent(this, ConfigsActivity.class)));
        vpnPanel.addView(actions);
        root.addView(vpnPanel);

        // ========== Авто: одна большая кнопка — цепочка VPN → Tor → DPI ==========
        autoPanel = new LinearLayout(this);
        autoPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout autoCard = cardPanel("Авто · VPN → Tor → DPI");
        TextView autoInfo = plain(
                "Одна кнопка: «Подключить». Сначала пробую конфиги VPN из хранилища "
                + "(до 8 живых, по одному). Если ни один не вышел — поднимаю Tor через мосты. "
                + "Если и Tor не отвечает — включаю DPI-обход заблокированного. Что сработает — то и останется. "
                + "Конфиги устаревают: если раньше работавшее перестало — жмите «Быстрый тест».",
                12, TEXT);
        autoCard.addView(autoInfo);
        autoPanel.addView(autoCard);

        btnAutoQuick = fullWidthButton("Быстрый тест: пинг живых конфигов → скорость до 5");
        ((LinearLayout.LayoutParams) btnAutoQuick.getLayoutParams()).topMargin = dp(16);
        btnAutoQuick.setOnClickListener(v -> runVpnSearch(true));
        autoPanel.addView(btnAutoQuick);

        btnAutoUpdate = fullWidthButton("Обновить конфиги");
        ((LinearLayout.LayoutParams) btnAutoUpdate.getLayoutParams()).topMargin = dp(8);
        btnAutoUpdate.setOnClickListener(v -> runFullUpdate());
        autoPanel.addView(btnAutoUpdate);
        root.addView(autoPanel);

        // ========== Tor-панель: свои проверки и свои кнопки ==========
        torPanel = new LinearLayout(this);
        torPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout torCard = cardPanel("Tor · мосты");
        torInfoText = plain("Мосты: —", 12, TEXT);
        torCard.addView(torInfoText);
        torPanel.addView(torCard);

        btnTorBridges = fullWidthButton("Обновить мосты");
        ((LinearLayout.LayoutParams) btnTorBridges.getLayoutParams()).topMargin = dp(16);
        btnTorBridges.setOnClickListener(v -> runTorBridgesRefresh());
        torPanel.addView(btnTorBridges);

        btnTorMass = fullWidthButton("Массовая проверка мостов (TLS)");
        ((LinearLayout.LayoutParams) btnTorMass.getLayoutParams()).topMargin = dp(8);
        btnTorMass.setOnClickListener(v -> runTorBridgesMass());
        torPanel.addView(btnTorMass);
        root.addView(torPanel);

        // ========== DPI-панель: свои проверки и свои кнопки ==========
        dpiPanel = new LinearLayout(this);
        dpiPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dpiCard = cardPanel("DPI · ByeDPI");
        dpiInfoText = plain("Стратегия: —", 12, TEXT);
        dpiCard.addView(dpiInfoText);
        dpiPanel.addView(dpiCard);

        btnDpiPick = fullWidthButton("Подобрать рабочую стратегию (полный проход)");
        ((LinearLayout.LayoutParams) btnDpiPick.getLayoutParams()).topMargin = dp(16);
        btnDpiPick.setOnClickListener(v -> runDpiPick(false));
        dpiPanel.addView(btnDpiPick);

        btnDpiMicro = fullWidthButton("Микро-тест: 5 случайных стратегий");
        ((LinearLayout.LayoutParams) btnDpiMicro.getLayoutParams()).topMargin = dp(8);
        btnDpiMicro.setOnClickListener(v -> runDpiPick(true));
        dpiPanel.addView(btnDpiMicro);

        btnDpiStrats = fullWidthButton("Обновить список стратегий (GitHub)");
        ((LinearLayout.LayoutParams) btnDpiStrats.getLayoutParams()).topMargin = dp(8);
        btnDpiStrats.setOnClickListener(v -> runDpiStratsRefresh());
        dpiPanel.addView(btnDpiStrats);
        root.addView(dpiPanel);

        // ---------- лог ----------
        logText = new TextView(this);
        logText.setText("Лог: готов");
        logText.setTextSize(10);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextColor(TEXT_DIM);
        logText.setBackgroundColor(SURFACE);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        logText.setMinLines(3);
        logText.setMaxLines(8);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logLp.topMargin = dp(14);
        logText.setLayoutParams(logLp);
        root.addView(logText);

        // ---------- инициализация ----------
        mode = Prefs.mode(this);
        updateModeSelection();
        if (Prefs.autoUpdateOnStart(this)) {
            updateCountsAsync();
        } else {
            configsCountText.setText("Хранилище: — · Проверено: — · Топ-5: —");
        }

        requestNotificationPermission();
        PermissionsHelper.requestOnce(this);            // исключение из оптимизации батареи
        registerStatusReceiver();
        handleStatusIntent(getIntent());

        // Тихое фоновое обновление — только если пользователь включил «Автообновление при запуске»
        if (Prefs.autoUpdateOnStart(this)) {
            handler.postDelayed(this::maybeQuietUpdate, 2500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleStatusIntent(intent);
    }

    private void handleStatusIntent(Intent intent) {
        // статус приходит через BroadcastReceiver
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerStatusReceiver();
        refreshConnectionUI(BypassVpnService.isRunning() ? "on" : "off",
                BypassVpnService.currentDetail(), Prefs.mode(this));
        if (Prefs.autoUpdateOnStart(this)) updateCountsAsync();
        refreshTorCard();
        refreshDpiCard();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterStatusReceiver();
    }

    // ---------- строительные блоки ----------

    private LinearLayout cardPanel(String caps) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(SURFACE);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(16);
        card.setLayoutParams(cardLp);
        card.addView(capsLabel(caps));
        return card;
    }

    private TextView plain(String text, float size, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(color);
        return tv;
    }

    private TextView capsLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setAllCaps(true);
        tv.setTextColor(TEXT_DIM);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackgroundColor(SURFACE2);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        return b;
    }

    private Button fullWidthButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackgroundColor(SURFACE2);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private Button modeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackgroundColor(SURFACE2);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        return b;
    }

    // ---------- режим ----------

    private void setMode(String newMode) {
        mode = newMode;
        Prefs.setMode(this, newMode);
        updateModeSelection();
        log("Режим: " + newMode);
    }

    private void updateModeSelection() {
        styleModeButton(modeVpn, "vpn".equals(mode));
        styleModeButton(modeTor, "tor".equals(mode));
        styleModeButton(modeDpi, "dpi".equals(mode));
        styleModeButton(modeAuto, "auto".equals(mode));
        applyModePanels();
    }

    private void applyModePanels() {
        if (vpnPanel != null) vpnPanel.setVisibility("vpn".equals(mode) ? View.VISIBLE : View.GONE);
        if (autoPanel != null) autoPanel.setVisibility("auto".equals(mode) ? View.VISIBLE : View.GONE);
        if (torPanel != null) torPanel.setVisibility("tor".equals(mode) ? View.VISIBLE : View.GONE);
        if (dpiPanel != null) dpiPanel.setVisibility("dpi".equals(mode) ? View.VISIBLE : View.GONE);
        refreshTorCard();
        refreshDpiCard();
    }

    private void styleModeButton(Button b, boolean selected) {
        b.setTextColor(selected ? ACCENT : TEXT);
        b.setBackgroundColor(selected ? SURFACE3 : SURFACE2);
    }

    /** Универсальная отмена: тесты, поиски, обновления, TOR-проверки + остановка туннеля. */
    private void stopEverythingUi() {
        ConfigRepository.requestCancelAll();
        vpnTestStopRequested = true;
        try { if (desyncTestProxy != null) desyncTestProxy.stopProxy(); } catch (Throwable ignored) { }
        desyncTestProxy = null;
        if (BypassVpnService.isRunning()) {
            Intent stop = new Intent(this, BypassVpnService.class);
            stop.setAction(BypassVpnService.ACTION_STOP);
            startService(stop);
        }
        log("СТОП: тесты, поиски и обновления отменены, туннель останавливается…");
        Toast.makeText(this, "Остановлено пользователем", Toast.LENGTH_SHORT).show();
    }

    // ---------- подключение ----------

    private void toggleConnection() {
        if (BypassVpnService.isRunning()) {
            Intent stop = new Intent(this, BypassVpnService.class);
            stop.setAction(BypassVpnService.ACTION_STOP);
            startService(stop);
            log("Остановка туннеля…");
            return;
        }
        // весь перебор/допроверка стратегий — в сервисе (DPI-допроверка YouTube ≤ 2 с,
        // перебор до 12 стратегий); здесь лишь запрашиваем VPN-разрешение и стартуем
        log("Запуск туннеля…");
        proceedVpnPermission();
    }

    private void proceedVpnPermission() {
        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, REQ_VPN);
        } else {
            startVpnService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                Toast.makeText(this, "Нужно разрешение на VPN-подключение", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startVpnService() {
        Intent i = new Intent(this, BypassVpnService.class);
        i.setAction(BypassVpnService.ACTION_START);
        i.putExtra(BypassVpnService.EXTRA_MODE, mode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    // ---------- статус ----------

    private void registerStatusReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (BypassVpnService.ACTION_STATUS.equals(action)) {
                    String state = intent.getStringExtra(BypassVpnService.EXTRA_STATE);
                    String detail = intent.getStringExtra(BypassVpnService.EXTRA_DETAIL);
                    String m = intent.getStringExtra(BypassVpnService.EXTRA_MODE);
                    refreshConnectionUI(state, detail, m);
                    log("[" + state + "] " + detail);
                } else if (BypassVpnService.ACTION_SWITCHED.equals(action)) {
                    String detail = intent.getStringExtra(BypassVpnService.EXTRA_DETAIL);
                    log("[switch] " + detail);
                    Toast.makeText(MainActivity.this, detail, Toast.LENGTH_LONG).show();
                    updateCountsAsync();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BypassVpnService.ACTION_STATUS);
        filter.addAction(BypassVpnService.ACTION_SWITCHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void unregisterStatusReceiver() {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
    }

    private void refreshConnectionUI(String state, String detail, String m) {
        runOnUiThread(() -> {
            boolean on = "on".equals(state);
            boolean connecting = "connecting".equals(state);
            boolean error = "error".equals(state);
            statusPill.setText(on ? "● Подключено"
                    : connecting ? "● Подключение…"
                    : error ? "● Ошибка"
                    : "● Отключено");
            statusPill.setTextColor(on ? ACCENT : connecting ? ORANGE : error ? RED : TEXT_DIM);
            powerButton.setText(on ? "Отключить" : "Подключить");
            powerButton.setBackgroundColor(on ? ACCENT_DIM : SURFACE2);
            if (detail != null && !detail.isEmpty() && !"off".equals(state)) {
                activeConfigText.setText(detail);
                activeConfigText.setSelected(true); // marquee
            } else if ("off".equals(state)) {
                activeConfigText.setText("не выбран");
            }
            if (on || error) updateLatencyAsync();
        });
    }

    // ---------- статистика ----------

    private void updateCountsAsync() {
        new Thread(() -> {
        try {
            int n = ConfigRepository.countActive(this);
            String cur = Prefs.currentConfigName(this);
            if (cur.isEmpty()) {
                List<ProxyConfig> list = ConfigRepository.activeList(this);
                ProxyConfig best = ConfigRepository.best(list);
                if (best != null) {
                    Prefs.setCurrentConfig(this, best.raw, best.name, best.host, best.port);
                    cur = best.name;
                }
            }
            final String curFinal = cur;
            final int lvl1 = ConfigRepository.countDownloaded(this);
            final int lvl2 = ConfigRepository.countDataOk(this);
            final int lvl3 = ConfigRepository.countTower(this);
            String lu = ConfigRepository.readLastUpdate(this);
            final String luLine = lu.isEmpty() ? "" : "\nОбновление: " + truncate(lu, 110);
            runOnUiThread(() -> {
                configsCountText.setText("Хранилище: " + lvl1 + " · Проверено (грузят): " + lvl2
                        + " · Топ-5 для подключения: " + lvl3
                        + (lvl3 == 0 ? "\n«Быстрый поиск» соберёт топ-5 рабочих" : "")
                        + luLine);
                if (!curFinal.isEmpty() && !BypassVpnService.isRunning()) {
                    activeConfigText.setText(curFinal);
                }
            });
        } catch (Throwable t) {
            Log.e("MainActivity", "updateCountsAsync failed", t);
            runOnUiThread(() -> log("Ошибка подсчёта конфигов: " + t.getMessage()));
        }
        }, "counts").start();
    }

    private void updateLatencyAsync() {
        new Thread(() -> {
        try {
            String host = Prefs.currentConfigHost(this);
            int port = Prefs.currentConfigPort(this);
            long lat = host.isEmpty() ? -1 : new LatencyStore(this).get(host, port);
            long speed = -1;
            int socks = BypassVpnService.currentSocksPort();
            if (socks > 0) {
                speed = NetChecker.measureSpeedThroughSocks(Prefs.speedTestUrl(this),
                        "127.0.0.1", socks, 6000, 256 * 1024);
            }
            final long latF = lat, speedF = speed;
            runOnUiThread(() -> {
                pingText.setText(latF >= 0 ? latF + " мс" : "– мс");
                speedText.setText(speedF >= 0 ? speedF + " КБ/с" : "– КБ/с");
            });
        } catch (Throwable t) {
            Log.e("MainActivity", "updateLatencyAsync failed", t);
        }
        }, "latency").start();
    }

    // ---------- тест на загрузку данных (через реальный Xray-конфиг) ----------

    private static final int FULL_CHECK_SPEED_CAP = 200;  // скоростью тестируем не больше 200 лучших
    private static final int QUICK_SPEED_CAP = 60;        // быстрый поиск: скоростью не больше 60 лучших
    private static final int TOWER_WINNERS = 5;           // «Вершина»: сколько лучших забрать
    private static final int MASS_CHECK_LANES = 3;        // параллельные Xray-инстанции
    private static final String[] DATA_TEST_URLS = {
            // 361 байт, raw GitHub — проверено 06.11.2026 (HTTP 200)
            "https://raw.githubusercontent.com/Learntous7/Collect/main/vanilla_72h.txt",
            // ровно 1 КБ, Cloudflare — иностранная точка присутствия
            "https://speed.cloudflare.com/__down?bytes=1024"
    };

    /** Свободный локальный порт или -1. */
    private int freePort() {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Ждём, пока локальный SOCKS начнёт принимать соединения. */
    private boolean waitSocksLocal(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket sock = new java.net.Socket()) {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Один конфиг → TRUE/FALSE/null(окружение сломано — конфиг НЕ трогаем).
     * Xray поднимается отдельным инстансом (не singleton) — туннелю не мешаем,
     * поэтому параллельно можно много таких проверок сразу.
     */
    /** Тест конфига: штатно; если не вышел — один раз с DPI-обходом на линии (SNI из конфига). */
    private Boolean dataTestOne(ProxyConfig cfg) {
        if (ConfigRepository.isCancelled()) return null;
        Boolean ok = dataTestAttempt(cfg, 0);
        if (ok == null || ok) return ok; // ядро не стартовало — не приговор; TRUE — успех
        if (Prefs.vpnDesync(this)) {
            int desyncPort = startDesyncForTest(cfg);
            if (desyncPort > 0) {
                try {
                    Boolean ok2 = dataTestAttempt(cfg, desyncPort);
                    if (Boolean.TRUE.equals(ok2)) {
                        Log.i("DataTest", "cfg=" + cfg.name + " ожил с DPI-обходом линии");
                        return Boolean.TRUE;
                    }
                } finally {
                    stopDesyncForTest();
                }
            }
        }
        return Boolean.FALSE;
    }

    private Boolean dataTestAttempt(ProxyConfig cfg, int desyncSocksPort) {
        int port = freePort();
        if (port <= 0) return null;
        libv2ray.CoreController controller = null;
        try {
            String json = XrayEngine.buildConfigJson(cfg, port, Prefs.vpnInsecure(this),
                    Prefs.vpnDns(this), desyncSocksPort);
            controller = XrayEngine.startInstance(this, json);
        } catch (Throwable t) {
            Log.w("DataTest", "xray start failed for " + cfg.name + ": " + t);
            return null; // наш Xray не смог — не приговор конфигу и точно не повод удалять
        }
        try {
            if (!waitSocksLocal(port, 2500)) return Boolean.FALSE;
            for (String url : DATA_TEST_URLS) {
                NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(url, "127.0.0.1", port,
                        1500, 8192);
                Log.i("DataTest", "cfg=" + cfg.name + " url=" + url + " ms=" + r.ms
                        + " bytes=" + r.bytes + " code=" + r.code
                        + (desyncSocksPort > 0 ? " (desync)" : ""));
                if (ConfigRepository.isCancelled()) return null;
                if (r.ok && r.bytes >= 40 && r.code < 400) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } finally {
            XrayEngine.stopInstance(controller);
        }
    }

    private int startDesyncForTest(ProxyConfig cfg) {
        stopDesyncForTest();
        try {
            String sni = cfg.tlsServerName();
            if (sni == null || sni.isEmpty()) sni = cfg.host != null ? cfg.host : "www.youtube.com";
            int port = freePort();
            if (port <= 0) return -1;
            String[] args = DpiArgs.build(Prefs.strategy(this), port, sni);
            ByeDpiProxy proxy = new ByeDpiProxy();
            desyncTestProxy = proxy;
            Thread t = new Thread(() -> {
                try { proxy.startProxy(args); } catch (Throwable ignored) { }
            }, "byedpi-test");
            t.setDaemon(true);
            t.start();
            for (int i = 0; i < 12; i++) { // ждём открытия локального SOCKS (обычно < 300 мс)
                if (NetChecker.tcpPing("127.0.0.1", port, 150) >= 0) return port;
                try { Thread.sleep(100); } catch (InterruptedException e) { return -1; }
            }
            stopDesyncForTest();
            return -1;
        } catch (Throwable t) {
            stopDesyncForTest();
            return -1;
        }
    }

    private void stopDesyncForTest() {
        try { if (desyncTestProxy != null) desyncTestProxy.stopProxy(); } catch (Throwable ignored) { }
        desyncTestProxy = null;
    }

    private void markLatencyDead(java.util.List<ProxyConfig> cfgs) {
        LatencyStore store = new LatencyStore(this);
        for (ProxyConfig cfg : cfgs) {
            store.set(cfg.host, cfg.port, -1);
        }
    }

    /**
     * Единый движок поиска VPN: «Быстрый поиск» (до 5 рабочих, стоп) или
     * «Полная проверка» (дедуп → пинг → скорость, до 200 лучших, повторный тап = стоп).
     * Оба: удаление дубликатов и мёртвых (из скачанного хранилища), «Вершина» — топ-5 в connect,
     * скачанные первыми/только; встроенные — только latency=-1.
     */
    private void runVpnSearch(boolean quick) {
        if (BypassVpnService.isRunning()) {
            Toast.makeText(this, "Остановите туннель перед проверкой", Toast.LENGTH_LONG).show();
            return;
        }
        if (vpnTestRunning) {
            if (quick) {
                Toast.makeText(this, "Идёт проверка — дождитесь окончания", Toast.LENGTH_SHORT).show();
            } else {
                vpnTestStopRequested = true;
                log("Останавливаю полную проверку… (проверенное сохранится)");
            }
            return;
        }
        vpnTestRunning = true;
        vpnTestStopRequested = false;
        ConfigRepository.clearCancel();
        btnQuickSearch.setEnabled(false);
        btnFullCheck.setText("ОСТАНОВИТЬ полную проверку");
        log(quick ? "Быстрый поиск: дубликаты → пинг всех → скорость до 5 рабочих…"
                : "Полная проверка: дубликаты → пинг всех → скорость (до 200), параллельно 3 Xray…");
        new Thread(() -> {
            try {
                int dup = ConfigRepository.dedupeDownloaded(this);
                if (dup > 0) log("Дубликатов удалено из хранилища: " + dup);

                List<ProxyConfig> pool = ConfigRepository.activeList(this);
                if (pool.isEmpty()) {
                    toastLog("Список пуст — нажмите «Обновить конфиги»");
                    return;
                }
                final int total = pool.size();
                // --- фаза 1: пинг ВСЕХ параллельно (TLS-handshake для TLS-конфигов) ---
                final boolean desyncOn = Prefs.vpnDesync(this);
                List<ProxyConfig> alive = ConfigTester.test(pool, Prefs.pingThreads(this),
                        Prefs.pingTimeoutMs(this), new ConfigTester.Listener() {
                            @Override
                            public void onProgress(int d, int t) {
                                if (d % 50 == 0 || d == t) {
                                    runOnUiThread(() -> logText.setText(
                                            (quick ? "Быстрый поиск" : "Полная проверка") + ": пинг " + d + "/" + t));
                                }
                            }

                            @Override
                            public void onDone(List<ProxyConfig> a, int t, int failed) {
                            }
                        });
                if (alive == null) { // СТОП (универсальная кнопка)
                    toastLog("Поиск остановлен — хранилище не трогаю");
                    return;
                }
                LatencyStore store = new LatencyStore(this);
                for (ProxyConfig p : pool) store.set(p.host, p.port, p.latency);
                // удаляем непингуемые из скачанного хранилища
                Set<String> pingDead = new HashSet<>();
                for (ProxyConfig p : pool) {
                    if (p.latency < 0) pingDead.add(ConfigRepository.configHash(p.raw));
                }
                int deletedPing = 0;
                if (desyncOn) {
                    log("Фаза 1: удаление мёртвых по пингу пропущено (включён DPI-обход линии — "
                            + "часть «мёртвых» оживёт в тесте на загрузку)");
                } else if (!alive.isEmpty() && !pingDead.isEmpty()) {
                    // страховка: если не пингуется вообще ничто — сеть/устройство, ничего не удаляем
                    deletedPing = ConfigRepository.deleteByHashes(this, pingDead);
                }
                // --- фаза 2: скорость, по возрастанию пинга (при desync — пингующиеся первыми,
                //              непингуемые в конец, а не выброшены: они могут ожить с обходом) ---
                List<ProxyConfig> candidates = new ArrayList<>(alive);
                candidates.sort(Comparator.comparingLong(x -> x.latency));
                if (desyncOn) {
                    List<ProxyConfig> withDead = new ArrayList<>(candidates);
                    for (ProxyConfig p : pool) {
                        if (p.latency < 0 && p.supported) withDead.add(p);
                    }
                    candidates = withDead;
                }
                int cap = quick ? QUICK_SPEED_CAP : FULL_CHECK_SPEED_CAP;
                if (candidates.size() > cap) {
                    candidates = new ArrayList<>(candidates.subList(0, cap));
                }
                final List<ProxyConfig> fList = candidates;
                final List<String> winRaws = java.util.Collections.synchronizedList(new ArrayList<>());
                final List<ProxyConfig> winCfgs = java.util.Collections.synchronizedList(new ArrayList<>());
                final List<ProxyConfig> failedCfgs = java.util.Collections.synchronizedList(new ArrayList<>());
                final java.util.concurrent.atomic.AtomicInteger tested =
                        new java.util.concurrent.atomic.AtomicInteger();
                final int cTotal = fList.size();
                if (quick) {
                    for (int i = 0; i < cTotal && winRaws.size() < TOWER_WINNERS
                            && !vpnTestStopRequested && !ConfigRepository.isCancelled();
                         i++) {
                        ProxyConfig cfg = fList.get(i);
                        final int ti = i + 1;
                        runOnUiThread(() -> logText.setText(
                                "Быстрый поиск: скорость " + ti + "/" + cTotal + " (рабочих " + winRaws.size() + ")"));
                        Boolean ok = dataTestOne(cfg);
                        if (ok == null) continue;
                        tested.incrementAndGet();
                        if (ok) { winRaws.add(cfg.raw); winCfgs.add(cfg); }
                        else failedCfgs.add(cfg);
                    }
                } else {
                    java.util.concurrent.atomic.AtomicInteger cursor =
                            new java.util.concurrent.atomic.AtomicInteger();
                    java.util.concurrent.ExecutorService pool3 =
                            java.util.concurrent.Executors.newFixedThreadPool(MASS_CHECK_LANES);
                    for (int lane = 0; lane < MASS_CHECK_LANES; lane++) {
                        pool3.execute(() -> {
                            for (;;) {
                                if (vpnTestStopRequested || ConfigRepository.isCancelled()) break;
                                if (winRaws.size() >= FULL_CHECK_SPEED_CAP) break;
                                int i = cursor.getAndIncrement();
                                if (i >= cTotal) break;
                                ProxyConfig cfg = fList.get(i);
                                Boolean ok;
                                try {
                                    ok = dataTestOne(cfg);
                                } catch (Throwable t) {
                                    Log.w("FullCheck", "candidate failed: " + t);
                                    continue;
                                }
                                if (ok == null) continue;
                                tested.incrementAndGet();
                                if (ok) { winRaws.add(cfg.raw); winCfgs.add(cfg); }
                                else failedCfgs.add(cfg);
                                int d = tested.get();
                                if (d % 10 == 0 || i == cTotal - 1) {
                                    final int dd = d, cc = cTotal;
                                    runOnUiThread(() -> logText.setText(
                                            "Полная проверка: скорость " + dd + "/" + cc
                                                    + " (рабочих " + winRaws.size() + ")"));
                                }
                            }
                        });
                    }
                    pool3.shutdown();
                    pool3.awaitTermination(45, java.util.concurrent.TimeUnit.MINUTES);
                }
                // --- итоги ---
                boolean stopped = vpnTestStopRequested || ConfigRepository.isCancelled();
                ConfigRepository.markDataOk(this, winRaws);
                // «Вершина»: до 5 победителей с лучшим пингом
                List<ProxyConfig> towerSrc;
                synchronized (winCfgs) {
                    towerSrc = new ArrayList<>(winCfgs);
                }
                towerSrc.sort(Comparator.comparingLong(x -> x.latency));
                List<String> towerRaws = new ArrayList<>();
                for (ProxyConfig cfg : towerSrc) {
                    towerRaws.add(cfg.raw);
                    if (towerRaws.size() >= TOWER_WINNERS) break;
                }
                ConfigRepository.writeTower(this, towerRaws);
                // удаляем непрошедших (только при найденных рабочих; скачанные — физически)
                List<ProxyConfig> failedCopy;
                synchronized (failedCfgs) {
                    failedCopy = new ArrayList<>(failedCfgs);
                }
                Set<String> failHashes = new HashSet<>();
                int downloadedFailed = 0, embeddedFailed = 0;
                for (ProxyConfig cfg : failedCopy) {
                    failHashes.add(ConfigRepository.configHash(cfg.raw));
                    if (cfg.embedded) embeddedFailed++; else downloadedFailed++;
                }
                markLatencyDead(failedCopy);
                int deletedData = 0;
                if (!winRaws.isEmpty() && !failHashes.isEmpty()) {
                    deletedData = ConfigRepository.deleteByHashes(this, failHashes);
                }
                final String summary = (quick ? "Быстрый поиск" : "Полная проверка")
                        + (stopped ? " (остановлено)" : "")
                        + ": пинг жив " + alive.size() + "/" + total
                        + (deletedPing > 0 ? ", удалено непингуемых " + deletedPing : "")
                        + ", скоростью проверено " + tested.get() + ", найдено рабочих " + winRaws.size()
                        + ", удалено скачанных " + deletedData + "/" + downloadedFailed
                        + ", встроенных помечено " + embeddedFailed
                        + ", в «Вершине»: " + towerRaws.size()
                        + (winRaws.isEmpty() ? " (ни одного живого — ничего не удалял)" : "");
                toastLog(summary);
            } catch (Throwable t) {
                Log.e("MainActivity", "vpn search failed", t);
                toastLog("Ошибка поиска VPN: " + t.getMessage());
            } finally {
                vpnTestRunning = false;
                vpnTestStopRequested = false;
                runOnUiThread(() -> {
                    btnQuickSearch.setEnabled(true);
                    btnFullCheck.setText("Полная проверка: дедуп → пинг → скорость (до 200)");
                    btnFullCheck.setEnabled(true);
                    updateCountsAsync();
                });
            }
        }, quick ? "quick-search" : "full-check").start();
    }

    private void toastLog(String message) {
        runOnUiThread(() -> {
            log(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            updateCountsAsync();
        });
    }

    // ---------- Tor ----------

    private void refreshTorCard() {
        new Thread(() -> {
            try {
                int userLines = 0;
                for (String line : Prefs.torBridges(this).split("\r?\n")) {
                    if (!line.trim().isEmpty()) userLines++;
                }
                int autoLines = 0;
                java.io.File auto = new java.io.File(getFilesDir(), ConfigRepository.AUTO_BRIDGES_FILE);
                if (auto.isFile()) {
                    for (String line : java.nio.file.Files.readAllLines(auto.toPath(),
                            java.nio.charset.StandardCharsets.UTF_8)) {
                        if (!line.trim().isEmpty()) autoLines++;
                    }
                }
                int deadCount = ConfigRepository.loadTorDead(this).size();
                final String info = "Ваши мосты: " + userLines
                        + " · авто: " + autoLines + " (обн. " + dateStr(Prefs.torBridgesAutoAt(this)) + ")"
                        + " · в чёрном списке: " + deadCount
                        + "\nАвтообновление: igareck (РФ) → 3 мировых коллектора";
                runOnUiThread(() -> {
                    if (torInfoText != null) torInfoText.setText(info);
                });
            } catch (Throwable t) {
                Log.w("MainActivity", "refreshTorCard failed", t);
            }
        }, "tor-card").start();
    }

    private void runTorBridgesRefresh() {
        log("Тор: скачиваю свежие мосты (igareck → Learntous7 → OnionHop)…");
        new Thread(() -> {
            ConfigRepository.clearCancel();
            int n = ConfigRepository.refreshTorBridges(this, null);
            final String m = n >= 10 ? "Мосты Tor обновлены: " + n + " (будут использованы при следующем подключении)"
                    : "Мосты Tor не обновлены (источники недоступны/мусор) — оставлены прежние";
            runOnUiThread(() -> {
                log(m);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                refreshTorCard();
            });
        }, "tor-bridges").start();
    }

    private List<String> readAutoBridgesLines() {
        List<String> out = new ArrayList<>();
        java.io.File auto = ConfigRepository.torBridgesAutoFile(this);
        if (!auto.isFile()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(auto), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
        } catch (java.io.IOException ignored) {
        }
        return out;
    }

    private List<String> readBuiltinBridgesLines() {
        List<String> out = new ArrayList<>();
        try (java.io.InputStream is = getAssets().open("fallback/tor_bridges_vanilla.txt");
             java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(is,
                     java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.regionMatches(true, 0, "Bridge ", 0, 7)) t = t.substring(7).trim();
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Массовая проверка мостов: TLS-handshake к каждому (прямое соединение, как у самого Tor).
     * Нерабочие удаляются из авто-списка и пользовательских мостов; встроенные — в чёрный список.
     * Если не жив НИ ОДИН — ничего не удаляем (сеть недоступна).
     */
    private void runTorBridgesMass() {
        log("Tor: массово проверяю мосты (TLS-handshake, 40 потоков)…");
        btnTorMass.setEnabled(false);
        new Thread(() -> {
            try {
                ConfigRepository.clearCancel();
                // [линия, происхождение] — свои → авто → встроенные; дедуп по ключу ip:port
                List<String[]> candidates = new ArrayList<>();
                java.util.LinkedHashSet<String> seenKeys = new java.util.LinkedHashSet<>();
                for (String raw : Prefs.lines(Prefs.torBridges(this))) {
                    String key = ConfigRepository.bridgeKey(raw);
                    if (key != null && seenKeys.add(key)) candidates.add(new String[]{raw.trim(), "ваши"});
                }
                for (String raw : readAutoBridgesLines()) {
                    String key = ConfigRepository.bridgeKey(raw);
                    if (key != null && seenKeys.add(key)) candidates.add(new String[]{raw.trim(), "авто"});
                }
                for (String raw : readBuiltinBridgesLines()) {
                    String key = ConfigRepository.bridgeKey(raw);
                    if (key != null && seenKeys.add(key)) candidates.add(new String[]{raw.trim(), "встроенные"});
                }

                java.util.Set<String> alreadyDead = ConfigRepository.loadTorDead(this);
                final List<String[]> testable = new ArrayList<>();
                int skippedDead = 0;
                for (String[] c2 : candidates) {
                    String key = ConfigRepository.bridgeKey(c2[0]);
                    if (key != null && alreadyDead.contains(key)) { skippedDead++; continue; }
                    testable.add(c2);
                }
                final int total = testable.size();
                if (total == 0) {
                    toastLog("Tor: нечего проверять (список пуст или всё в чёрном списке)");
                    return;
                }
                java.util.concurrent.ConcurrentHashMap<String, Boolean> results =
                        new java.util.concurrent.ConcurrentHashMap<>();
                java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newFixedThreadPool(40);
                for (String[] c2 : testable) {
                    if (ConfigRepository.isCancelled()) break;
                    pool.execute(() -> {
                        if (ConfigRepository.isCancelled()) return;
                        String key = ConfigRepository.bridgeKey(c2[0]);
                        String host = key.substring(0, key.lastIndexOf(':'));
                        int port = Integer.parseInt(key.substring(key.lastIndexOf(':') + 1));
                        long ms = NetChecker.tlsPing(host, port, null, 3000);
                        results.put(key, ms >= 0);
                        int d = done.incrementAndGet();
                        if (d % 20 == 0 || d == total) {
                            final int dd = d;
                            runOnUiThread(() -> logText.setText("Tor: мосты " + dd + "/" + total));
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS);
                if (ConfigRepository.isCancelled()) {
                    toastLog("Tor: остановлено — результаты проверки НЕ применены");
                    return;
                }

                int alive = 0;
                List<String> aliveLines = new ArrayList<>();
                java.util.Set<String> deadKeys = new java.util.HashSet<>();
                for (String[] c2 : testable) {
                    String key = ConfigRepository.bridgeKey(c2[0]);
                    Boolean ok = results.get(key);
                    if (Boolean.TRUE.equals(ok)) { alive++; aliveLines.add(c2[0]); }
                    else if (Boolean.FALSE.equals(ok)) deadKeys.add(key);
                }
                ConfigRepository.markTorGood(this, aliveLines);
                if (alive == 0) {
                    toastLog("Tor: НИ ОДИН из " + total + " мостов не прошёл TLS — ничего не удаляю (проверьте сеть)");
                    return;
                }
                int removedFiles = ConfigRepository.filterDeadBridges(this, deadKeys);
                ConfigRepository.addTorDead(this, deadKeys);
                final String m = "Tor: проверено " + total + ", живых " + alive
                        + " → в «проверенных» (пойдут в torrc)"
                        + ", нерабочих " + deadKeys.size()
                        + " (удалено из списков " + removedFiles + ")"
                        + (skippedDead > 0 ? ", уже было в чёрном списке: " + skippedDead : "");
                toastLog(m);
            } catch (Throwable t) {
                Log.e("MainActivity", "tor mass check failed", t);
                toastLog("Ошибка массовой проверки мостов: " + t.getMessage());
            } finally {
                runOnUiThread(() -> {
                    btnTorMass.setEnabled(true);
                    refreshTorCard();
                });
            }
        }, "tor-mass").start();
    }

    // ---------- DPI ----------

    private static String dateStr(long at) {
        if (at <= 0) return "никогда";
        return new java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date(at));
    }

    private static String truncate(String str, int n) {
        if (str == null) return "";
        return str.length() <= n ? str : str.substring(0, n) + "…";
    }

    private void refreshDpiCard() {
        String strategy = Prefs.strategy(this);
        if (strategy == null || strategy.trim().isEmpty()) strategy = Prefs.DEFAULT_STRATEGY;
        long at = Prefs.dpiStrategiesAutoAt(this);
        boolean fresh = at > 0 && System.currentTimeMillis() - at < 30L * 24 * 3600 * 1000;
        java.io.File f = new java.io.File(getFilesDir(), ConfigRepository.AUTO_STRATS_FILE);
        String src = (fresh && f.isFile()) ? "авто-список (обн. " + dateStr(at) + ")" : "встроенный (проверен)";
        List<String> urls = Prefs.dpiTestUrls(this);
        int deadCount = ConfigRepository.loadDpiDead(this).size();
        String info = "Стратегия: " + truncate(strategy.trim(), 110)
                + "\nСписок: " + src
                + " · мёртвых в чёрном списке: " + deadCount
                + "\nПроверка: " + (urls.isEmpty() ? "https://www.youtube.com" : urls.get(0));
        if (dpiInfoText != null) dpiInfoText.setText(info);
    }

    /**
     * Пробный запуск ciadpi с данной стратегией (без TUN-туннеля).
     * Фаза 1 «пинг»: HTTP к проверочным ссылкам (YouTube) ≤ 2 с.
     * Фаза 2 «скорость»: 1 МБ с иностранных файлов допроверки ≤ 2 с (как у кнопки питания).
     */
    private boolean probeDpiStrategy(String strategy, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            urls = java.util.Collections.singletonList("https://www.youtube.com");
        }
        List<String> gateUrls = Prefs.speedGateUrls(this);
        if (gateUrls == null || gateUrls.isEmpty()) {
            gateUrls = java.util.Collections.singletonList("https://speed.cloudflare.com/__down?bytes=1048576");
        }
        int port = freePort();
        if (port <= 0) return false;
        String[] args = DpiArgs.build(strategy, port, DpiArgs.sniFromUrls(urls));
        ru.whitelist.bypass.jni.ByeDpiProxy proxy = new ru.whitelist.bypass.jni.ByeDpiProxy();
        Thread t = new Thread(() -> {
            try {
                proxy.startProxy(args);
            } catch (Throwable ignored) {
            }
        }, "dpi-probe");
        t.setDaemon(true);
        t.start();
        boolean up = waitSocksLocal(port, 5000);
        boolean ok = false;
        if (up) {
            // фаза 1: пинг (YouTube и прочие заблокированные)
            ok = true;
            for (String url : urls) {
                boolean alive = NetChecker.httpCheckThroughSocks(url, "127.0.0.1", port, 2500);
                Log.i("DpiProbe", "ping strategy=" + truncate(strategy, 40) + " url=" + url + " ok=" + alive);
                if (!alive) { ok = false; break; }
            }
            // фаза 2: скорость 1 МБ ≤ 2 с
            if (ok) {
                ok = false;
                for (String url : gateUrls) {
                    NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(url, "127.0.0.1", port,
                            2500, 1024L * 1024L);
                    Log.i("DpiProbe", "speed strategy=" + truncate(strategy, 40) + " url=" + url
                            + " ms=" + r.ms + " bytes=" + r.bytes + " code=" + r.code);
                    if (r.ok && r.ms <= 2000 && r.bytes >= 512L * 1024L) { ok = true; break; }
                }
            }
        }
        try { proxy.stopProxy(); } catch (Throwable ignored) { }
        return ok;
    }

    private List<String> dpiCandidatesForUi(int max) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        String saved = Prefs.strategy(this);
        if (saved != null && !saved.trim().isEmpty()) set.add(saved.trim());
        try {
            long at = Prefs.dpiStrategiesAutoAt(this);
            boolean fresh = at > 0 && System.currentTimeMillis() - at < 30L * 24 * 3600 * 1000;
            java.io.File f = new java.io.File(getFilesDir(), ConfigRepository.AUTO_STRATS_FILE);
            List<String> src = null;
            if (fresh && f.isFile()) {
                src = new ArrayList<>();
                for (String line : java.nio.file.Files.readAllLines(f.toPath(),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) src.add(t);
                }
                if (src.size() < 10) src = null;
            }
            if (src == null) {
                src = new ArrayList<>();
                try (java.io.InputStream is = getAssets().open("strategies/byedpi_strategies.txt");
                     java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    for (String line : new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                            .split("[\r\n]+")) {
                        String t = line.trim();
                        if (!t.isEmpty() && !t.startsWith("#")) src.add(t);
                    }
                } catch (Exception ignored) {
                }
            }
            for (String st : src) {
                set.add(st);
                if (set.size() >= max) break;
            }
        } catch (Exception e) {
            set.add(Prefs.DEFAULT_STRATEGY);
        }
        if (set.isEmpty()) set.add(Prefs.DEFAULT_STRATEGY);
        return new ArrayList<>(set);
    }

    /**
     * Подбор рабочей стратегии: полный проход по всему списку (или 5 случайных — микро-тест),
     * до первой стратегии, прошедшей пинг + скорость (1 МБ ≤ 2 с). Рабочую сохраняем как основную.
     * НЕпрошедшие стратегии удаляем: из авто-списка — физически (с бэкапом .bak),
     * встроенные и прочие — по хэшу в «чёрный список» (dpi_strategies_dead.txt).
     * Если не прошла НИ ОДНА — ничего не удаляем (сеть недоступна / ciadpi сломан).
     */
    private void runDpiPick(boolean micro) {
        if (dpiProbeRunning) {
            Toast.makeText(this, "Подбор стратегии уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        dpiProbeRunning = true;
        btnDpiPick.setEnabled(false);
        btnDpiMicro.setEnabled(false);
        log(micro ? "DPI: микро-тест — 5 случайных стратегий…"
                : "DPI: полный подбор — до первой рабочей стратегии (пинг + 1 МБ ≤ 2 с)…");
        new Thread(() -> {
            try {
                ConfigRepository.clearCancel();
                List<String> all = dpiCandidatesForUi(Integer.MAX_VALUE);
                java.util.Set<String> dead = ConfigRepository.loadDpiDead(this);
                List<String> pool = new ArrayList<>();
                for (String st : all) {
                    if (!dead.contains(ConfigRepository.strategyHash(st))) pool.add(st);
                }
                if (micro && pool.size() > 5) {
                    List<String> shuffled = new ArrayList<>(pool);
                    java.util.Collections.shuffle(shuffled);
                    pool = new ArrayList<>(shuffled.subList(0, 5));
                }
                if (pool.isEmpty()) {
                    toastLog("DPI: список стратегий пуст (все в чёрном списке)");
                    return;
                }
                List<String> failed = new ArrayList<>();
                int found = -1;
                int total = pool.size();
                for (int i = 0; i < total; i++) {
                    if (ConfigRepository.isCancelled()) {
                        toastLog("DPI: остановлено — результаты не применены");
                        return;
                    }
                    String strategy = pool.get(i);
                    final String label = "DPI: стратегия " + (i + 1) + "/" + total
                            + " — " + truncate(strategy, 60);
                    runOnUiThread(() -> logText.setText(label));
                    if (probeDpiStrategy(strategy, Prefs.dpiTestUrls(this))) {
                        found = i + 1;
                        Prefs.setStrategy(this, strategy);
                        int listIdx = dpiCandidatesForUi(Integer.MAX_VALUE).indexOf(strategy);
                        if (listIdx >= 0) Prefs.setDpiStrategyIndex(this, listIdx);
                        break;
                    }
                    failed.add(strategy);
                }
                if (found > 0) {
                    Set<String> deadHashes = new HashSet<>();
                    for (String st : failed) deadHashes.add(ConfigRepository.strategyHash(st));
                    ConfigRepository.addDpiDead(this, new HashSet<>(failed));
                    int removedAuto = ConfigRepository.filterDeadDpiAuto(this, deadHashes);
                    final String m = "DPI: рабочая стратегия — №" + found + " из " + total
                            + " (сохранена как основная). Удалено нерабочих: " + failed.size()
                            + " (из авто-списка: " + removedAuto + ", остальные — в чёрном списке)";
                    toastLog(m);
                } else {
                    final String m = "DPI: ни одна из " + total
                            + " стратегий не прошла пинг+скорость — ничего не удаляю (проверьте сеть)";
                    toastLog(m);
                }
            } catch (Throwable t) {
                Log.e("MainActivity", "dpi pick failed", t);
                toastLog("Ошибка подбора стратегии: " + t.getMessage());
            } finally {
                dpiProbeRunning = false;
                runOnUiThread(() -> {
                    btnDpiPick.setEnabled(true);
                    btnDpiMicro.setEnabled(true);
                    refreshDpiCard();
                });
            }
        }, micro ? "dpi-micro" : "dpi-pick").start();
    }

    private void runDpiStratsRefresh() {
        log("DPI: скачиваю свежий список стратегий (ByeByeDPI, GitHub)…");
        new Thread(() -> {
            ConfigRepository.clearCancel();
            int n = ConfigRepository.refreshDpiStrategies(this, null);
            final String m = n >= 10
                    ? "Стратегии DPI обновлены: " + n + " (будут использованы при следующем включении)"
                    : "Стратегии DPI не обновлены (источник недоступен или мало рабочих строк) — оставлены прежние";
            runOnUiThread(() -> {
                log(m);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                refreshDpiCard();
            });
        }, "dpi-strats").start();
    }

    // ---------- проверка путей ----------

    private void runFullUpdate() {
        log("Обновление конфигов из источников…");
        new Thread(() -> {
        try {
            ConfigRepository.clearCancel();
            ConfigRepository.UpdateResult r = ConfigRepository.fullUpdate(this, msg ->
                    runOnUiThread(() -> logText.setText(msg)));
            StringBuilder sb = new StringBuilder(r.toString());
            for (String m : r.messages) sb.append("\n").append(m);
            final String out = sb.toString();
            ConfigRepository.writeLastUpdate(this, out.replace('\n', ' '));
            runOnUiThread(() -> {
                log(out);
                Toast.makeText(this, out, Toast.LENGTH_LONG).show();
                updateCountsAsync();
            });
        } catch (Throwable t) {
            Log.e("MainActivity", "runFullUpdate failed", t);
            runOnUiThread(() -> log("Ошибка обновления: " + t.getMessage()));
        }
        }, "full-update").start();
    }

    private void maybeQuietUpdate() {
        if (BypassVpnService.isRunning()) {
            log("Тихое обновление пропущено: туннель включён");
            return;
        }
        if (!Prefs.quietUpdate(this)) return;
        new Thread(() -> {
        try {
            ConfigRepository.clearCancel();
            ConfigRepository.UpdateResult r = ConfigRepository.quietUpdate(this);
            String msg = "Тихое обновление: " + r;
            if (!r.messages.isEmpty()) msg += " (" + r.messages.get(0) + ")";
            log(msg);
            if (r.keptAlive > 0) runOnUiThread(this::updateCountsAsync);
        } catch (Throwable t) {
            Log.e("MainActivity", "maybeQuietUpdate failed", t);
            log("Тихое обновление: ошибка " + t.getMessage());
        }
        }, "quiet-update").start();
    }

    // ---------- утилиты ----------

    private void showCrashDialog(String crash) {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(crash);
        tv.setTextSize(10);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pad = dp(12);
        tv.setPadding(pad, pad, pad, pad);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Предыдущий запуск упал — стектрейс")
                .setView(scroll)
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void log(String message) {
        long t = System.currentTimeMillis();
        String time = android.text.format.DateFormat.format("HH:mm:ss", t).toString();
        synchronized (logBuffer) {
            logBuffer.append(time).append("  ").append(message).append('\n');
            String s = logBuffer.toString();
            int lines = s.split("\n").length;
            int extra = lines - 8;
            while (extra-- > 0) {
                int idx = s.indexOf('\n');
                if (idx < 0) break;
                s = s.substring(idx + 1);
            }
            final String out = s;
            runOnUiThread(() -> logText.setText(out));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            }
        }
    }
}
