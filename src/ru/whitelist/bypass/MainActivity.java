package ru.whitelist.bypass;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Экран из трёх кнопок:
 *   1) Подключить — берёт самый быстрый конфиг из пула (пул пуст → сам найдёт рабочий);
 *   2) Быстрый поиск — найти и запомнить один рабочий VPN;
 *   3) Собрать пул — обновить ВСЕ списки из источников и замерить скорость у живых.
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF000000;
    private static final int SURFACE = 0xFF0A0E13;
    private static final int SURFACE2 = 0xFF121A22;
    private static final int ACCENT = 0xFF3DDC84;
    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;
    private static final int RED = 0xFFFF5252;
    private static final int ORANGE = 0xFFFFAB40;
    /** Фиолетовый — туннель идёт через Tor-мосты (резервный режим). */
    private static final int PURPLE = 0xFF9C27B0;
    private static final int PURPLE_DARK = 0xFF6A1B9A;

    /**
     * Сколько конфигов проверяем за один заход. Пакет набирается случайно из ВСЕХ
     * скачанных списков, а не «первые строки первого источника»: только так дорожки
     * замера не занимает один список, пока до остальных не дошла очередь.
     */
    private static final int POOL_BATCH = 200;
    /** Замер из пула моложе этого возраста переиспользуем, не гоняя конфиг заново. */
    private static final long POOL_REUSE_MS = 12 * 60 * 60 * 1000L;
    /**
     * Сколько конфигов за один сбор перепроверить через ciadpi. Мало: линия одна,
     * замер идёт по очереди, и каждый — до нескольких секунд.
     */
    private static final int DPI_RETRIES = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusPill, statusDetail, powerHint, poolText, logText;
    private ScrollView logScroll;
    /** Полный лог: в консоль попадает последнее, но история остаётся — её можно листать. */
    private final StringBuilder logBuffer = new StringBuilder();
    private static final int LOG_MAX_CHARS = 40000;
    private Button powerButton, btnQuick, btnPool, btnWorkers, btnDpi, btnConsole;
    private LinearLayout consoleCard;
    private boolean consoleOpen = false;
    private View btnSettings;
    private BroadcastReceiver receiver;

    private volatile boolean searching = false;

    private final Runnable statusSync = new Runnable() {
        @Override public void run() {
            boolean on = BypassVpnService.isRunning();
            if (on && BypassVpnService.isPaused()) {
                setStatus("❚❚ Пауза", Ui.INFO);
                stylePowerButton("paused");
            } else if (on) {
                boolean tor = BypassVpnService.isTorMode();
                setStatus(tor ? "● Tor-мосты" : "● Подключено", tor ? Ui.TOR : Ui.ACCENT);
                stylePowerButton(tor ? "tor" : "on");
            } else if (BypassVpnService.isConnecting()) {
                setStatus("● Подключение…", Ui.WARN);
                stylePowerButton("connecting");
            } else {
                setStatus("● Отключено", Ui.TEXT_DIM);
                stylePowerButton("off");
            }
            applyRunningUi(on);
            refreshPoolCard();
        }
    };

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashReporter.install(this);
        Prefs.migrate(this);
        String previousCrash = CrashReporter.consumeIfPresent(this);
        super.onCreate(savedInstanceState);

        // Прокрутки на главном нет: экран должен целиком влезать в один экран телефона.
        // Всё лишнее ужато — мелкая линейка действий, короткий журнал, который к тому же
        // скрыт по умолчанию и при открытии забирает ровно оставшееся место.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_OLED);        // OLED: чёрный фон только здесь
        int p = dp(16);
        root.setPadding(p, dp(12), p, dp(12));
        setContentView(root);
        Ui.applyInsets(root);
        if (previousCrash != null) showCrashDialog(previousCrash);

        // ---- шапка: название, подпись и вход в настройки
        btnSettings = Ui.iconButton(this, "⚙", Ui.TEXT);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        root.addView(Ui.header(this, "Обход Белых Списков",
                "VPN · VLESS · скорость вместо пинга", btnSettings));

        // ---- состояние: только текст на чёрном. Карточка здесь не нужна: она съедала
        // чёрный фон, а на OLED именно фон и экономит батарею.
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusPill = Ui.chip(this, "● Отключено", Ui.TEXT_DIM, false);
        statusRow.addView(statusPill);
        statusDetail = Ui.caption(this, "");
        statusDetail.setGravity(Gravity.END);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        detailLp.leftMargin = dp(10);
        statusRow.addView(statusDetail, detailLp);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(14);
        root.addView(statusRow, statusLp);

        // ---- главная кнопка: единственный крупный элемент экрана
        LinearLayout powerWrap = new LinearLayout(this);
        powerWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = dp(16);
        powerWrap.setLayoutParams(wrapLp);

        powerButton = new Button(this);
        powerButton.setText("Подключить");
        powerButton.setTextSize(19);
        powerButton.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        powerButton.setAllCaps(false);
        powerButton.setPadding(0, 0, 0, 0);
        powerButton.setMinWidth(0);
        powerButton.setMinHeight(0);
        powerButton.setMinimumWidth(0);
        powerButton.setMinimumHeight(0);
        powerButton.setStateListAnimator(null);
        powerButton.setBackgroundTintList(null);
        int powerSize = dp(160);
        powerButton.setLayoutParams(new LinearLayout.LayoutParams(powerSize, powerSize));
        powerButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConnection(); }
        });
        powerWrap.addView(powerButton);
        root.addView(powerWrap);

        powerHint = Ui.caption(this, "Нажмите, чтобы подключиться");
        powerHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(10);
        powerHint.setLayoutParams(hintLp);
        root.addView(powerHint);

        // ---- пул: одна строка. Цифры — на главном, подробности и выбор конфига — в «Рабочих»
        poolText = Ui.caption(this, "Рабочий пул: пусто");
        poolText.setTextColor(Ui.TEXT);
        poolText.setGravity(Gravity.CENTER);
        poolText.setBackground(Ui.shape(0x00000000, 0x00000000, 0, Ui.R_PILL, this));
        poolText.setPadding(dp(12), dp(12), dp(12), dp(12));
        poolText.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PoolActivity.class));
            }
        });
        LinearLayout.LayoutParams poolTextLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        poolTextLp.topMargin = dp(12);
        poolText.setLayoutParams(poolTextLp);
        root.addView(poolText);

        // ---- вспомогательные действия: одна линейка высотой с одну кнопку.
        // Раньше это были две большие кнопки с подписями и отдельная карточка пула —
        // на главном экране они занимали пол-экрана, хотя нужны редко.
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams quickRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        quickRowLp.topMargin = dp(14);
        quickRow.setLayoutParams(quickRowLp);

        btnQuick = railButton("Поиск");
        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (searching) { cancelSearch(); return; }
                startQuickSearch();
            }
        });
        quickRow.addView(btnQuick, railLp(0));

        btnPool = railButton("Пул");
        btnPool.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (searching) { cancelSearch(); return; }
                startBuildPool();
            }
        });
        quickRow.addView(btnPool, railLp(1));

        btnWorkers = railButton("Рабочие");
        btnWorkers.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PoolActivity.class));
            }
        });
        quickRow.addView(btnWorkers, railLp(2));

        btnDpi = railButton("DPI");
        btnDpi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (BypassVpnService.isRunning() || BypassVpnService.isConnecting()) {
                    toast("Сначала отключите текущее подключение");
                    return;
                }
                VpnStart.connectDpiOnly(MainActivity.this);
            }
        });
        quickRow.addView(btnDpi, railLp(3));

        btnConsole = railButton("Журнал");
        btnConsole.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConsole(); }
        });
        quickRow.addView(btnConsole, railLp(4));
        root.addView(quickRow);

        // ---- консоль: скрыта, пока её не открыли кнопкой
        consoleCard = Ui.card(this);
        LinearLayout.LayoutParams consoleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        consoleLp.topMargin = dp(12);
        consoleCard.setLayoutParams(consoleLp);
        consoleCard.setVisibility(View.GONE);

        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.addView(Ui.overline(this, "Журнал"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button copyLog = Ui.ghost(this, "Копировать");
        copyLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyLogToClipboard(); }
        });
        logHead.addView(copyLog);
        consoleCard.addView(logHead);

        logText = Ui.mono(this, "Лог: готов", 11, Ui.TEXT_DIM);
        logScroll = new ScrollView(this);
        logScroll.addView(logText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setScrollbarFadingEnabled(false);
        logScroll.setFillViewport(true);
        logScroll.setBackground(Ui.shape(Ui.BG, Ui.STROKE, 1, Ui.R_MD, this));
        logScroll.setPadding(dp(10), dp(10), dp(10), dp(10));
        // Журнал больше не 200 dp: он занимает остаток экрана, а не выталкивает кнопки.
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logLp.topMargin = dp(8);
        logScroll.setLayoutParams(logLp);
        consoleCard.addView(logScroll);
        root.addView(consoleCard);

        // ---- подвал: версия и напоминание про уведомление
        TextView footer = Ui.caption(this, versionLine()
                + " · в уведомлении «Пауза» и «Остановить»");
        footer.setPadding(dp(4), dp(12), dp(4), 0);
        root.addView(footer);

        registerStatusReceiver();
        requestNotificationPermission();
        PermissionsHelper.requestOnce(this);
        handleStatusIntent(getIntent());
        refreshPoolCard();
    }

    /** Кнопка вспомогательной линейки: мельче основной, без «карточки» вокруг. */
    private Button railButton(String label) {
        Button b = Ui.small(this, label);
        b.setTextSize(12);
        b.setTextColor(Ui.TEXT);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private LinearLayout.LayoutParams railLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        if (index > 0) lp.leftMargin = dp(5);
        return lp;
    }

    /** Консоль открывается кнопкой «Журнал» и закрывается повторным нажатием. */
    private void toggleConsole() {
        setConsole(!consoleOpen);
    }

    private void setConsole(boolean open) {
        consoleOpen = open;
        if (consoleCard != null) consoleCard.setVisibility(open ? View.VISIBLE : View.GONE);
        if (btnConsole != null) btnConsole.setTextColor(open ? Ui.ACCENT : Ui.TEXT);
        if (open && logScroll != null) {
            logScroll.post(new Runnable() {
                @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleStatusIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(statusSync, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusSync);
    }

    @Override
    protected void onDestroy() {
        unregisterStatusReceiver();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ построение виджетов

    /**
     * Вид большой круглой кнопки: зелёная «Подключить», оранжевая «Отмена»,
     * красная «Отключить». Так же делают почти все VPN-клиенты.
     */
    /**
     * Состояние в шапке карточки: текст + контур нужного цвета.
     * Раньше здесь просто меняли цвет текста, и «Отключено» выглядело так же, как
     * «Ошибка», только другим оттенком.
     */
    private void setStatus(String text, int color) {
        if (statusPill == null) return;
        statusPill.setText(text);
        Ui.paintChip(statusPill, color, false, this);
        if (statusDetail != null) {
            String name = Prefs.currentName(this);
            statusDetail.setText(name == null ? "" : truncate(name, 26));
        }
    }

    private void stylePowerButton(String state) {
        if (powerButton == null) return;
        int bg;
        int fg;
        String text;
        if ("tor".equals(state)) {
            // Резервный режим: фиолетовая кнопка — сразу видно, что канал идёт через Tor.
            bg = PURPLE;
            fg = 0xFFFFFFFF;
            text = "Отключить";
        } else if ("tor-connecting".equals(state)) {
            bg = PURPLE_DARK;
            fg = 0xFFFFFFFF;
            text = "Отмена";
        } else if ("paused".equals(state)) {
            // Пауза: туннель снят, трафик идёт с хоста. Кнопка по-прежнему «Отключить» —
            // сервис жив, и выключить его нужно явно.
            bg = Ui.INFO;
            fg = 0xFF04121F;
            text = "Отключить";
        } else if ("on".equals(state)) {
            bg = 0xFFE5484D;
            fg = 0xFFFFFFFF;
            text = "Отключить";
        } else if ("connecting".equals(state)) {
            bg = Ui.WARN;
            fg = 0xFF1A1206;
            text = "Отмена";
        } else if ("error".equals(state)) {
            bg = 0xFF7F2A2E;
            fg = 0xFFFFFFFF;
            text = "Подключить";
        } else {
            bg = Ui.ACCENT;
            fg = Ui.ACCENT_INK;
            text = "Подключить";
        }
        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(bg);
        shape.setStroke(dp(2), 0x40FFFFFF);
        powerButton.setBackground(shape);
        powerButton.setTextColor(fg);
        powerButton.setText(text);
        powerButton.setLetterSpacing(0.01f);
        if (powerHint != null) {
            if ("on".equals(state)) powerHint.setText("Туннель включён · нажмите, чтобы отключить");
            else if ("tor".equals(state)) powerHint.setText("Резервный канал: трафик идёт через Tor-мосты");
            else if ("paused".equals(state)) powerHint.setText("Пауза: трафик идёт напрямую, конфиг сохранён");
            else if ("connecting".equals(state) || "tor-connecting".equals(state))
                powerHint.setText("Подключение… можно отменить этой же кнопкой");
            else if ("error".equals(state)) powerHint.setText("Не удалось подключиться — попробуйте ещё раз");
            else powerHint.setText("Нажмите, чтобы подключиться");
        }
    }

    /** Строка версии для подвала: «Обход Белых Списков 0.1.11 (21)». */
    private String versionLine() {
        String name = "0.1.14";
        long code = 24;
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            name = info.versionName == null ? name : info.versionName;
            code = android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
        }
        return "Обход Белых Списков " + name + " (" + code + ")";
    }

    /** Журнал целиком — в буфер обмена: так его удобнее прислать в обсуждении. */
    private void copyLogToClipboard() {
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cb == null) return;
            String text = logBuffer.length() == 0 ? "Лог пуст" : logBuffer.toString();
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Журнал", text));
            toast("Журнал скопирован");
        } catch (Throwable t) {
            toast("Не удалось скопировать журнал");
        }
    }

    // ------------------------------------------------------------------ подключение

    private void toggleConnection() {
        if (BypassVpnService.isRunning() || BypassVpnService.isConnecting()) {
            try {
                Intent stop = new Intent(this, BypassVpnService.class);
                stop.setAction(BypassVpnService.ACTION_STOP);
                startService(stop);
                log("Останавливаю туннель…");
            } catch (Throwable t) {
                log("Не удалось остановить сервис: " + t.getMessage());
            }
            return;
        }
        if (searching) {
            toast("Сначала дождитесь окончания поиска или остановите его");
            return;
        }
        log("Запускаю туннель…");
        VpnStart.connect(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (VpnStart.handleResult(this, requestCode, resultCode)) {
            if (resultCode != RESULT_OK) toast("Нужно разрешение на VPN-подключение");
        }
    }

    // ------------------------------------------------------------------ статус

    private void registerStatusReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String state = intent.getStringExtra(BypassVpnService.EXTRA_STATE);
                String detail = intent.getStringExtra(BypassVpnService.EXTRA_DETAIL);
                String mode = intent.getStringExtra(BypassVpnService.EXTRA_MODE);
                refreshConnectionUi(state, detail, mode);
            }
        };
        IntentFilter filter = new IntentFilter(BypassVpnService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
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

    private void handleStatusIntent(Intent intent) {
        if (intent == null) return;
        String state = intent.getStringExtra(BypassVpnService.EXTRA_STATE);
        if (state == null) return;
        refreshConnectionUi(state, intent.getStringExtra(BypassVpnService.EXTRA_DETAIL),
                intent.getStringExtra(BypassVpnService.EXTRA_MODE));
    }

    private void refreshConnectionUi(String state, String detail, String mode) {
        if (state == null) return;
        boolean tor = BypassVpnService.MODE_TOR.equals(mode) || BypassVpnService.isTorMode();
        boolean dpi = BypassVpnService.MODE_DPI.equals(mode) || BypassVpnService.isDpiMode();
        if ("on".equals(state)) {
            if (dpi) setStatus("● Только DPI", Ui.INFO);
            else setStatus(tor ? "● Tor-мосты" : "● Подключено", tor ? Ui.TOR : Ui.ACCENT);
            stylePowerButton(tor ? "tor" : "on");
            if (detail != null && !detail.isEmpty()) log("Подключено: " + detail);
            applyRunningUi(true);
            refreshPoolCard();
        } else if ("paused".equals(state)) {
            setStatus("❚❚ Пауза", Ui.INFO);
            stylePowerButton("paused");
            log("Пауза: трафик идёт напрямую (продолжить — кнопкой в уведомлении)");
            applyRunningUi(true);
            refreshPoolCard();
        } else if ("connecting".equals(state)) {
            if (dpi) setStatus("● DPI-обход…", Ui.INFO);
            else setStatus(tor ? "● Tor-мосты…" : "● Подключение…", tor ? Ui.TOR : Ui.WARN);
            stylePowerButton(tor ? "tor-connecting" : "connecting");
            if (detail != null) log(detail);
        } else if ("error".equals(state)) {
            setStatus("● Ошибка", Ui.DANGER);
            stylePowerButton("error");
            if (detail != null) log("Ошибка: " + detail);
            setConsole(true);            // ошибку надо показать, а не спрятать в закрытой консоли
            applyRunningUi(false);
            refreshPoolCard();
        } else if ("off".equals(state)) {
            setStatus("● Отключено", Ui.TEXT_DIM);
            stylePowerButton("off");
            log("Отключено");
            applyRunningUi(false);
            refreshPoolCard();
        }
    }

    private void applyRunningUi(boolean on) {
        if (on) {
            if (BypassVpnService.isPaused()) return;   // «Пауза» рисует refreshConnectionUi
            boolean tor = BypassVpnService.isTorMode();
            setStatus(tor ? "● Tor-мосты" : "● Подключено", tor ? Ui.TOR : Ui.ACCENT);
            stylePowerButton(tor ? "tor" : "on");
        } else if (!"● Подключение…".contentEquals(statusPill.getText())
                && !"● Tor-мосты…".contentEquals(statusPill.getText())) {
            stylePowerButton("off");
        }
    }

    // ------------------------------------------------------------------ карточка пула

    private void refreshPoolCard() {
        new Thread(new Runnable() {
            @Override public void run() {
                final int n = Pool.count(MainActivity.this);
                final long best = Pool.bestKbps(MainActivity.this);
                final long at = Prefs.poolBuiltAt(MainActivity.this);
                final String netLabel = Net.label(MainActivity.this);
                final String active = Prefs.currentName(MainActivity.this);
                final int min = Prefs.minSpeedKbps(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (poolText == null) return;
                        if (n == 0) {
                            poolText.setText("Пул «" + netLabel + "» пуст. «Подключить» найдёт "
                                    + "рабочий сам, «Пул» — запас на будущее.");
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append(netLabel).append(": ")
                          .append(n).append(n == 1 ? " конфиг" : " конфигов")
                          .append(" · лучший ").append(Prefs.speedText(best))
                          .append("\nСобран ").append(dateStr(at));
                        if (!active.isEmpty()) {
                            sb.append(" · активный ").append(truncate(active, 28));
                        }
                        sb.append("\nНажмите, чтобы выбрать конфиг вручную");
                        poolText.setText(sb.toString());
                    }
                });
            }
        }, "pool-card").start();
    }

    // ------------------------------------------------------------------ поиск: 1 рабочий VPN

    private void startQuickSearch() {
        if (BypassVpnService.isRunning()) {
            toast("Остановите туннель перед поиском");
            return;
        }
        searching = true;
        ConfigRepository.clearCancel();
        setBusy(true, "Поиск…");
        uiLog("Быстрый поиск: сеть → круги по новым конфигам (пул не перепроверяю)");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    QuickSearch.Found found = QuickSearch.find(MainActivity.this,
                            new QuickSearch.Log() {
                                @Override public void line(String message) { uiLog(message); }
                            });
                    if (found == null) {
                        uiLog("Рабочий конфиг не найден — попробуйте «Собрать пул» или добавьте источники");
                        return;
                    }
                    adopt(found.cfg, found.kbps);
                    finishOk(found.cfg.name, found.kbps);
                } catch (Throwable t) {
                    Log.e("MainActivity", "quick search failed", t);
                    uiLog("Ошибка поиска: " + t.getMessage());
                } finally {
                    searching = false;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { setBusy(false, null); }
                    });
                }
            }
        }, "quick-search").start();
    }

    private void adopt(ProxyConfig cfg, long kbps) {
        Prefs.setCurrentConfig(this, cfg.raw, cfg.name, cfg.host, cfg.port);
        Pool.remember(this, cfg.raw, kbps, cfg.latency);
        Blacklist.forget(this, cfg.raw);   // он работает — приговор снимаем
    }

    private void finishOk(final String name, final long kbps) {
        uiLog("Найден рабочий: " + truncate(name, 50) + " · " + kbps + " КБ/с");
        runOnUiThread(new Runnable() {
            @Override public void run() {
                toast("Готово: " + truncate(name, 40) + " · " + kbps + " КБ/с");
                refreshPoolCard();
            }
        });
    }

    // ------------------------------------------------------------------ сбор пула

    private void startBuildPool() {
        if (BypassVpnService.isRunning()) {
            toast("Остановите туннель перед сбором пула");
            return;
        }
        searching = true;
        ConfigRepository.clearCancel();
        setBusy(true, "Сбор пула…");
        uiLog("Сбор пула: обновляю списки, затем пакетами по " + POOL_BATCH
                + " случайных конфигов — пинг, замер, и так до конца всех списков");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 1) обновить ВСЕ списки. Любая ошибка на этом шаге не должна валить
                    // весь сбор пула: работаем с тем, что уже лежит в хранилище.
                    try {
                        ConfigRepository.UpdateResult res = ConfigRepository.updateAll(
                                MainActivity.this, new ConfigRepository.Progress() {
                                    @Override public void onMessage(String message) { uiLog(message); }
                                });
                        uiLog("Источники: " + res);
                        for (String m : res.messages) uiLog("· " + m);
                        if (res.stored == 0
                                && ConfigRepository.countDownloaded(MainActivity.this) > 0) {
                            uiLog("Загрузка ничего не дала — работаю с локальным списком");
                        }
                    } catch (Throwable t) {
                        Log.e("MainActivity", "sources failed", t);
                        uiLog("Источники: сбой (" + t.getMessage()
                                + ") — продолжаю с локальным списком");
                    }

                    // 1.0) вычеркнуть из скачанных списков всё, что уже в чёрном списке:
                    // иначе мёртвые конфиги возвращаются с каждым обновлением и снова
                    // занимают пинг и дорожки замера.
                    int purged = ConfigRepository.dropBlacklisted(MainActivity.this);
                    if (purged > 0) {
                        uiLog("Чёрный список: из скачанных списков вычеркнуто " + purged);
                    }

                    List<ProxyConfig> all = ConfigRepository.all(MainActivity.this);
                    if (all.isEmpty()) {
                        uiLog("Конфигов нет — проверьте источники в настройках");
                        return;
                    }

                    // 1.1) Отсекаем то, что ядро не поднимет НИКОГДА: такие конфиги
                    // проходят пинг, занимают дорожку и падают одной и той же ошибкой.
                    final Map<String, Integer> rejects = new LinkedHashMap<>();
                    final boolean relay = Prefs.allowNoTls(MainActivity.this);
                    all = Probe.usable(all, rejects, relay);
                    if (!rejects.isEmpty()) {
                        uiLog(Probe.rejectsText(rejects) + " — не пингую и не меряю");
                    }
                    if (relay) {
                        int viaRelay = 0;
                        for (ProxyConfig c : all) if (XrayEngine.needsRelay(c)) viaRelay++;
                        if (viaRelay > 0) {
                            uiLog("Без TLS — через локальный ретранслятор: " + viaRelay
                                    + " конфигов (настройки → «Разрешать конфиги без TLS»)");
                        }
                    }
                    if (all.isEmpty()) {
                        uiLog("Все конфиги несовместимы с ядром — обновите источники в настройках");
                        return;
                    }

                    // 1.1b) Страховка: в хранилище могли остаться записи из чёрного списка
                    // (например, добавленные только что, уже после чистки).
                    int inBlacklist = Blacklist.size(MainActivity.this);
                    if (inBlacklist > 0) {
                        int before = all.size();
                        all = Blacklist.filter(MainActivity.this, all);
                        int skipped = before - all.size();
                        uiLog("Чёрный список: " + inBlacklist + " нерабочих"
                                + (skipped > 0 ? ", из них " + skipped
                                        + " есть в текущих списках — пропускаю" : ""));
                        if (all.isEmpty()) {
                            uiLog("Все конфиги в чёрном списке — очистите его в настройках"
                                    + " (Данные → «Очистить список нерабочих конфигураций»)");
                            return;
                        }
                    }

                    // 1.2) стратегии DPI: тот же сбор пула — удобный момент их обновить.
                    if (!ConfigRepository.isCancelled()) {
                        try {
                            uiLog("Обновляю стратегии DPI-обхода с GitHub…");
                            int n = DpiStrategies.refresh(MainActivity.this,
                                    new DpiStrategies.Log() {
                                        @Override public void line(String message) {
                                            uiLog(message);
                                        }
                                    });
                            uiLog("DPI-стратегии: доступно " + n);
                        } catch (Throwable t) {
                            uiLog("DPI-стратегии: пропускаю (" + t.getMessage() + ")");
                        }
                    }

                    // 1.5) сеть: 5 целей параллельно
                    NetChecker.PingStats net = NetChecker.pingAll(
                            Prefs.pingTargets(MainActivity.this),
                            Prefs.pingTimeoutMs(MainActivity.this));
                    uiLog("Сеть: " + net.text());

                    // 2) Порядок обхода списков случайный, а не «первые две тысячи строк»:
                    // источники отдают файлы подряд, и при последовательном обходе все
                    // дорожки занимал один список, пока до остальных не доходило дело.
                    java.util.Collections.shuffle(all, new java.util.Random());

                    final Map<String, Pool.Entry> pool = new LinkedHashMap<>();
                    for (Pool.Entry e : Pool.load(MainActivity.this)) {
                        String key = ConfigRepository.canonicalKey(e.raw);
                        if (key != null) pool.put(key, e);
                    }
                    final int poolAtStart = pool.size();
                    final Map<String, Integer> why = java.util.Collections.synchronizedMap(
                            new LinkedHashMap<String, Integer>());

                    int batches = (all.size() + POOL_BATCH - 1) / POOL_BATCH;
                    int pinged = 0, alive = 0, reused = 0, measuredOk = 0, dpiBudget = DPI_RETRIES;
                    uiLog("К перебору " + all.size() + " конфигов пакетами по " + POOL_BATCH
                            + ": " + batches + " заходов. Потоков пинга "
                            + Prefs.pingThreads(MainActivity.this) + ", дорожек замера "
                            + Prefs.speedLanes(MainActivity.this));

                    for (int from = 0; from < all.size(); from += POOL_BATCH) {
                        if (ConfigRepository.isCancelled()) break;
                        int batchNo = from / POOL_BATCH + 1;
                        List<ProxyConfig> batch = new ArrayList<>(
                                all.subList(from, Math.min(all.size(), from + POOL_BATCH)));
                        uiLog("Пакет " + batchNo + "/" + batches + ": " + batch.size()
                                + " конфигов (проверено " + pinged + " из " + all.size() + ")");

                        // 2.1) пинг только этого пакета
                        ConfigTester.Outcome ping = ConfigTester.test(batch,
                                Prefs.pingThreads(MainActivity.this),
                                Prefs.pingTimeoutMs(MainActivity.this),
                                new ConfigTester.Listener() {
                                    @Override public void onProgress(int done, int total, int ok,
                                                                     int failed) {
                                        if (done % 100 == 0 || done == total) {
                                            uiLog("  пинг " + done + "/" + total
                                                    + " → живых " + ok + ", мёртвых " + failed);
                                        }
                                    }
                                });
                        pinged += batch.size();
                        if (ping.cancelled) {
                            uiLog("Сбор остановлен");
                            break;
                        }
                        alive += ping.alive.size();
                        uiLog("  пинг: " + ping.summary());
                        if (ping.alive.isEmpty()) continue;

                        // 2.2) к замеру — без свежих замеров из пула (их не гоняем заново)
                        List<ProxyConfig> work = new ArrayList<>();
                        long now = System.currentTimeMillis();
                        for (ProxyConfig cfg : ping.alive) {
                            String key = ConfigRepository.canonicalKey(cfg.raw);
                            Pool.Entry old = key == null ? null : pool.get(key);
                            if (old != null && old.at > 0 && now - old.at < POOL_REUSE_MS) {
                                reused++;
                                continue;
                            }
                            work.add(cfg);
                        }
                        if (work.isEmpty()) {
                            uiLog("  все живые уже замерены меньше "
                                    + (POOL_REUSE_MS / 3600000) + " ч назад — пропускаю пакет");
                            continue;
                        }

                        final List<ProxyConfig> failed =
                                java.util.Collections.synchronizedList(new ArrayList<ProxyConfig>());
                        int added = measureBatch(work, pool, why, failed);
                        measuredOk += added;

                        // 2.3) тем, кто не дал данных напрямую, — одна попытка через ciadpi.
                        // Бюджет общий на весь сбор: DpiStrategies.PROBE держит один процесс.
                        if (dpiBudget > 0 && !failed.isEmpty()
                                && !ConfigRepository.isCancelled()) {
                            List<ProxyConfig> retry;
                            synchronized (failed) {
                                retry = new ArrayList<>(failed);
                            }
                            int[] dpi = retryWithDpi(retry, pool, why, dpiBudget);
                            dpiBudget -= dpi[0];
                            measuredOk += dpi[1];
                        }

                        // 2.4) сохраняем прогресс после каждого пакета: если пользователь
                        // остановит сбор, уже найденное останется в пуле.
                        Pool.save(MainActivity.this, snapshot(pool));
                        uiLog("Пакет " + batchNo + " готов: +" + added + " рабочих, в пуле "
                                + pool.size() + " (проверено " + pinged + ", живых " + alive + ")");
                    }

                    List<Pool.Entry> snapshot = snapshot(pool);
                    if (snapshot.size() == poolAtStart && !ConfigRepository.isCancelled()
                            && !all.isEmpty()) {
                        uiLog("Пакетный замер не дал новых рабочих — проверяю первые 20 по одному…");
                        int upto = Math.min(20, all.size());
                        List<Pool.Entry> again = Probe.measureSequential(MainActivity.this,
                                new ArrayList<>(all.subList(0, upto)), upto);
                        for (Pool.Entry e : again) {
                            String key = ConfigRepository.canonicalKey(e.raw);
                            if (key != null) pool.put(key, e);
                        }
                        snapshot = snapshot(pool);
                        uiLog("По одному: рабочих " + again.size());
                    }
                    Pool.save(MainActivity.this, snapshot);
                    logWhy(why);
                    if (reused > 0) {
                        uiLog("Не перемерял свежие замеры: " + reused + " конфигов");
                    }
                    final int n = snapshot.size();
                    final long best = n > 0 ? Pool.bestKbps(MainActivity.this) : -1;
                    uiLog("Пул собран: " + n + " рабочих · пинг " + pinged + " · живых "
                            + alive + " · замеров " + measuredOk
                            + (best > 0 ? " · лучший " + Prefs.speedText(best) : "")
                            + ". Порог: " + Prefs.minSpeedText(MainActivity.this));
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast("Пул: " + n + " рабочих"
                                    + (best > 0 ? ", лучший " + Prefs.speedText(best) : ""));
                        }
                    });
                } catch (Throwable t) {
                    Log.e("MainActivity", "pool build failed", t);
                    uiLog("Ошибка сбора пула: " + t.getMessage());
                } finally {
                    searching = false;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setBusy(false, null);
                            refreshPoolCard();
                        }
                    });
                }
            }
        }, "build-pool").start();
    }

    private static List<Pool.Entry> snapshot(Map<String, Pool.Entry> pool) {
        synchronized (pool) {
            return new ArrayList<>(pool.values());
        }
    }

    private int poolSize(Map<String, Pool.Entry> pool) {
        synchronized (pool) {
            return pool.size();
        }
    }

    /**
     * Замер пакета «дорожками»: каждая — свой экземпляр ядра Xray.
     * Найденное сразу складывается в {@code pool}; непрошедшие напрямую попадают в
     * {@code failed} — им потом даётся одна попытка через DPI-обход.
     */
    private int measureBatch(final List<ProxyConfig> work,
                             final Map<String, Pool.Entry> pool,
                             final Map<String, Integer> why,
                             final List<ProxyConfig> failed) {
        final int lanes = Prefs.speedLanes(this);
        final int min = Prefs.minSpeedKbps(this);
        final int total = work.size();
        final AtomicInteger cursor = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger okCount = new AtomicInteger();
        ExecutorService lane = Executors.newFixedThreadPool(lanes);
        for (int i = 0; i < lanes; i++) {
            lane.execute(new Runnable() {
                @Override public void run() {
                    for (;;) {
                        if (ConfigRepository.isCancelled()) break;
                        int idx = cursor.getAndIncrement();
                        if (idx >= total) break;
                        ProxyConfig cfg = work.get(idx);
                        Probe.Result r = Probe.measureConfig(MainActivity.this, cfg,
                                Prefs.gateTimeoutMs(MainActivity.this));
                        boolean passed = r.ok && r.kbps >= min;
                        if (passed) {
                            okCount.incrementAndGet();
                            Blacklist.forget(MainActivity.this, cfg.raw);
                            String key = ConfigRepository.canonicalKey(cfg.raw);
                            Pool.Entry e = new Pool.Entry(cfg.raw, r.kbps, cfg.latency,
                                    System.currentTimeMillis());
                            synchronized (pool) {
                                if (key != null) pool.put(key, e);
                            }
                        } else {
                            bump(why, r.reason);
                            // Ядро не поднялось / данных нет — в чёрный список: в следующий
                            // раз его даже не пингуем. «Мягкие» отказы — кандидаты на DPI.
                            if (r.hard) {
                                Blacklist.add(MainActivity.this, cfg, r.reason);
                            } else {
                                synchronized (failed) {
                                    failed.add(cfg);
                                }
                            }
                        }
                        int d = done.incrementAndGet();
                        if (d % 10 == 0 || d == total || passed) {
                            uiLog("  скорость " + d + "/" + total + " · рабочих "
                                    + poolSize(pool)
                                    + (passed ? " · +" + Prefs.speedText(r.kbps)
                                              : " · " + truncate(cfg.name, 24) + ": " + r.reason));
                        }
                    }
                }
            });
        }
        lane.shutdown();
        try {
            lane.awaitTermination(2, TimeUnit.HOURS);
        } catch (InterruptedException ignored) {
        }
        return okCount.get();
    }

    /**
     * Попытка поднять непрошедшие конфиги через линию ciadpi.
     * Возвращает {сколько попыток потрачено, сколько заработало}.
     */
    private int[] retryWithDpi(List<ProxyConfig> failed,
                               Map<String, Pool.Entry> pool,
                               Map<String, Integer> why,
                               int budget) {
        if (!Prefs.desyncLine(this) || budget <= 0 || failed.isEmpty()) {
            return new int[]{0, 0};
        }
        final String strategy = Prefs.dpiStrategyFor(this);
        final int min = Prefs.minSpeedKbps(this);
        int tried = 0;
        int ok = 0;
        for (ProxyConfig cfg : failed) {
            if (ConfigRepository.isCancelled() || tried >= budget) break;
            tried++;
            uiLog("DPI-обход: " + truncate(cfg.name, 26) + " через "
                    + DpiStrategies.shorten(strategy));
            Probe.Result r = Probe.measureConfig(this, cfg, Prefs.gateTimeoutMs(this), strategy);
            if (r.ok && r.kbps >= min) {
                ok++;
                Blacklist.forget(this, cfg.raw);
                DpiStrategies.rememberWorking(this, strategy);
                String key = ConfigRepository.canonicalKey(cfg.raw);
                Pool.Entry e = new Pool.Entry(cfg.raw, r.kbps, cfg.latency,
                        System.currentTimeMillis(), strategy);
                synchronized (pool) {
                    if (key != null) pool.put(key, e);
                }
                uiLog("  + через DPI: " + Prefs.speedText(r.kbps) + " · "
                        + DpiStrategies.shorten(strategy));
            } else {
                bump(why, r.reason);
                if (r.hard) Blacklist.add(this, cfg, r.reason);
                uiLog("  → через DPI не прошёл · " + r.reason);
            }
        }
        return new int[]{tried, ok};
    }

    /** +1 к счётчику причин отказа (ключ — человеческая причина из Probe). */
    private static void bump(Map<String, Integer> map, String reason) {
        if (reason == null) reason = "без причины";
        String key = reason.length() > 60 ? reason.substring(0, 60) + "…" : reason;
        synchronized (map) {
            Integer old = map.get(key);
            map.put(key, old == null ? 1 : old + 1);
        }
    }

    /** Итог: «почему не прошло» по частоте — самое полезное при разборе «ничего не находит». */
    private void logWhy(Map<String, Integer> map) {
        if (map.isEmpty()) return;
        List<Map.Entry<String, Integer>> list;
        synchronized (map) {
            list = new ArrayList<>(map.entrySet());
        }
        java.util.Collections.sort(list, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        uiLog("Почему не прошли замер (" + list.size() + " причин):");
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            Map.Entry<String, Integer> e = list.get(i);
            uiLog("  ×" + e.getValue() + " · " + e.getKey());
        }
    }

    private void cancelSearch() {
        ConfigRepository.requestCancel();
        uiLog("Останавливаю… (результаты, что уже есть, сохранятся)");
    }

    // ------------------------------------------------------------------ мелочи

    private void setBusy(boolean busy, String label) {
        // ВАЖНО: кнопки остаются активными. Пока идёт работа, они и есть «Остановить».
        // Раньше здесь стояло setEnabled(!busy) — из-за этого остановить поиск было нельзя.
        btnQuick.setEnabled(true);
        btnPool.setEnabled(true);
        btnQuick.setText(busy ? "■ Поиск" : "Поиск");
        btnPool.setText(busy ? "■ Пул" : "Пул");
        btnQuick.setTextColor(busy ? Ui.DANGER : Ui.TEXT);
        btnPool.setTextColor(busy ? Ui.DANGER : Ui.TEXT);
        if (!busy) refreshPoolCard();
    }

    private void uiLog(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() { log(message); }
        });
    }

    private void log(String message) {
        Log.i("MainActivity", message);
        String line = message == null ? "" : message;
        if (logBuffer.length() > 0) logBuffer.append('\n');
        logBuffer.append(line);
        // Копия в общий буфер: из настроек консоль сохраняется в .json одной кнопкой.
        Console.line(line);
        if (logBuffer.length() > LOG_MAX_CHARS) {
            // режем по границе строки, чтобы не рвать половину сообщения
            int cut = logBuffer.indexOf("\n", logBuffer.length() - LOG_MAX_CHARS);
            if (cut > 0) logBuffer.delete(0, cut + 1);
        }
        logText.setText(logBuffer.toString());
        if (logScroll != null) {
            logScroll.post(new Runnable() {
                @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }

    private void showCrashDialog(String crash) {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(crash);
        tv.setTextSize(10);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Прошлый запуск завершился ошибкой")
                .setView(scroll)
                .setPositiveButton("Понятно", null)
                .show();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String dateStr(long at) {
        if (at <= 0) return "—";
        return new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date(at));
    }
}
