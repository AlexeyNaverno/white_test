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
import android.text.TextUtils;
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

/**
 * Главный экран — две вещи по сути:
 *   1) Подключить — берёт самый быстрый конфиг из пула (пул пуст → сервис сам найдёт
 *      рабочий партией в одном ядре, см. BypassVpnService.quickFind);
 *   2) Сбор конфигураций — обновить ВСЕ списки из источников, прогнать кластеры и
 *      партии в одном ядре (PoolScan) и сложить рабочих в пул.
 * Плюс «Логи» (консоль) и строка пула с живым счётчиком найденного.
 *
 * С 0.1.19 отдельного «быстрого поиска» нет: сбор пула находит рабочие конфиги на
 * первых секундах, а счётчик на главном показывает, когда найденного уже достаточно.
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
    private Button powerButton;
    /**
     * Нижняя линейка: с 0.1.19 ровно две кнопки — «Сбор конфигураций» и «Логи».
     *
     * Что убрано и почему: «Поиск» (быстрый поиск одного конфига) — сбор пула находит
     * рабочие конфиги на первых секундах, отдельный ускоренный поиск больше ничего не
     * добавлял; «Рабочие» — это первая вкладка настроек; «DPI» — второстепенный режим,
     * он остался в настройках; выбор региона тоже переехал в настройки, по умолчанию
     * Европа.
     */
    private RailButton btnPool, btnConsole;
    /** Живой счётчик сбора для главного экрана: «найдено рабочих N». */
    private volatile String livePool = null;
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

        btnPool = railButton("Сбор конфигураций");
        btnPool.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (searching) { cancelSearch(); return; }
                startBuildPool();
            }
        });
        quickRow.addView(btnPool.root, railLp(0));

        btnConsole = railButton("Логи");
        btnConsole.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConsole(); }
        });
        quickRow.addView(btnConsole.root, railLp(1));
        root.addView(quickRow);

        // ---- консоль: скрыта, пока её не открыли кнопкой «Логи»
        consoleCard = Ui.card(this);
        LinearLayout.LayoutParams consoleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        consoleLp.topMargin = dp(12);
        consoleCard.setLayoutParams(consoleLp);
        consoleCard.setVisibility(View.GONE);

        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.addView(Ui.overline(this, "Логи"), new LinearLayout.LayoutParams(0,
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

    /**
     * Квадратная кнопка нижней линейки.
     *
     * Раньше это была обычная кнопка с текстом: при пяти кнопках на узком экране подпись
     * «Рабочие» не влезала и переносилась — последняя буква уезжала вниз и обрезалась.
     * Теперь кнопка — квадрат 56 dp с подписью в одну строку по центру: переносить нечего,
     * а ряд из пяти квадратов занимает ровно ту же ширину, что и раньше.
     */
    private RailButton railButton(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(true);
        Ui.ripple(box, Ui.SURFACE_2, 1, Ui.R_MD, 0x33FFFFFF, this);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(11);
        text.setTextColor(Ui.TEXT);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(dp(2), 0, dp(2), 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(text);
        return new RailButton(box, text);
    }

    private LinearLayout.LayoutParams railLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        if (index > 0) lp.leftMargin = dp(6);
        return lp;
    }

    /** Квадратная кнопка линейки: подпись и её цвет — единственное, что меняется извне. */
    private static final class RailButton {
        final LinearLayout root;
        final TextView label;

        RailButton(LinearLayout root, TextView label) {
            this.root = root;
            this.label = label;
        }

        void setText(String text) { label.setText(text); }

        void setTextColor(int color) { label.setTextColor(color); }

        void setEnabled(boolean on) {
            root.setEnabled(on);
            label.setEnabled(on);
        }
    }

    /** Консоль открывается кнопкой «Логи» и закрывается повторным нажатием. */
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
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Логи", text));
            toast("Логи скопированы");
        } catch (Throwable t) {
            toast("Не удалось скопировать журнал");
        }
    }

    // ------------------------------------------------------------------ подключение

    private void toggleConnection() {
        if (BypassVpnService.isRunning() || BypassVpnService.isConnecting()) {
            // Общая точка остановки: она умеет и команду сервису, и прямой stopService,
            // если система не даёт стартовать сервис из фона.
            VpnStart.stop(this);
            log("Останавливаю туннель…");
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
                        // Пока идёт сбор, в этой строке живёт счётчик найденного:
                        // пользователь видит, что рабочих уже достаточно, и останавливает
                        // сбор сам, не открывая лог.
                        String live = livePool;
                        if (searching && live != null) {
                            poolText.setText(live);
                            poolText.setTextColor(Ui.ACCENT);
                            return;
                        }
                        poolText.setTextColor(Ui.TEXT);
                        if (n == 0) {
                            poolText.setText("Пул «" + netLabel + "» пуст. «Подключить» найдёт "
                                    + "рабочий сам, «Сбор конфигураций» — запас на будущее.");
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

    /**
     * Живой счётчик сбора на главном экране.
     *
     * Зачем: рабочие конфиги находятся уже на первых секундах, и человеку обычно хватает
     * нескольких. Раньше это было видно только в логе — приходилось держать консоль
     * открытой и следить. Теперь число найденных стоит прямо под кнопкой питания, а
     * повторное нажатие на «■ Стоп» останавливает сбор (найденное уже сохранено).
     */
    private void showScanProgress(final int ok, final int tested, final int total) {
        String found = ok == 0
                ? "Ищем рабочие конфиги…"
                : "Найдено рабочих: " + ok + (ok == 1 ? " конфиг" : " конфигов")
                        + (ok >= 3 ? " — можно остановить" : "");
        livePool = found + "\nпроверено " + tested + " из " + total
                + (ok == 0 ? " · список пополняется" : " · нажмите, чтобы посмотреть");
        if (poolText == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!searching) return;
                poolText.setText(livePool);
                poolText.setTextColor(ok > 0 ? Ui.ACCENT : Ui.TEXT_DIM);
                // Счётчик продублирован на кнопке: видно даже когда строка описания
                // занята чужой подписью.
                if (btnPool != null) btnPool.setText(ok > 0 ? "■ Стоп " + ok : "■ Стоп");
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
        uiLog("Сбор пула: обновляю списки → дедупликация → кластеры провайдеров → "
                + "партии по " + Prefs.batchSize(this) + " конфигов в ОДНОМ ядре");

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

                    // 1.8) БЫСТРЫЙ ОТСЕВ. Первая ступень из трёх: TCP-connect по серверам
                    // (по одному на пару host:port), потом полный пинг с TLS, потом замер
                    // скорости. Без этой ступени полный пинг жёг минуты на серверах,
                    // которых давно нет, — а их в публичных списках большинство.
                    if (!ConfigRepository.isCancelled()) {
                        uiLog("Быстрый отсев: TCP-connect по серверам · потоков "
                                + Prefs.fastPingThreads(MainActivity.this)
                                + ", таймаут " + Prefs.fastPingTimeoutMs(MainActivity.this) + " мс");
                        final FastPing.Outcome fast = FastPing.filter(MainActivity.this, all,
                                new FastPing.Log() {
                                    @Override public void line(String message) { uiLog(message); }
                                });
                        if (fast.cancelled) {
                            uiLog("Сбор остановлен");
                            return;
                        }
                        all = fast.alive;
                        uiLog("Быстрый отсев: " + fast.summary());
                        if (all.isEmpty()) {
                            uiLog("Ни один сервер не открылся — сеть сейчас не пускает "
                                    + "ни к одному. Проверьте интернет и повторите.");
                            return;
                        }
                    }

                    // 2) БАТЧ-ЗАМЕР В ОДНОМ ЯДРЕ + КЛАСТЕРНАЯ ВЫБОРКА (0.1.18).
                    //
                    // Порядок обхода больше не случаен: конфиги одного провайдера — это
                    // один кластер с общей судьбой, а партия составляется чересстрочно,
                    // чтобы первые сто проб пришлись на сто РАЗНЫХ провайдеров. Плюс
                    // дедупликация, «нерешённые» с прошлого раза первыми и TTL-вердикты
                    // кластеров. Всё это внутри PoolScan; здесь только журнал и подсчёт.
                    final Map<String, Pool.Entry> pool = new LinkedHashMap<>();
                    for (Pool.Entry e : Pool.load(MainActivity.this)) {
                        String key = ConfigRepository.canonicalKey(e.raw);
                        if (key != null) pool.put(key, e);
                    }
                    final Map<String, Integer> why = java.util.Collections.synchronizedMap(
                            new LinkedHashMap<String, Integer>());
                    int measuredOk = 0;
                    int dpiBudget = DPI_RETRIES;

                    PoolScan.Outcome scan = PoolScan.run(MainActivity.this, all,
                            new PoolScan.Log() {
                                @Override public void line(String message) { uiLog(message); }
                                @Override public void progress(int ok, int tested, int total) {
                                    showScanProgress(ok, tested, total);
                                }
                            });
                    measuredOk += scan.ok;
                    uiLog("Разбор списка: " + scan.summary());
                    if (scan.cancelled) uiLog("Сбор остановлен");

                    // 2.1) Кому батч не дал вердикта (не «данных нет», а «не успели») —
                    // одна попытка через ciadpi. Бюджет общий на весь сбор.
                    if (dpiBudget > 0 && !scan.failed.isEmpty()
                            && !ConfigRepository.isCancelled()) {
                        for (Pool.Entry e : Pool.load(MainActivity.this)) {
                            String key = ConfigRepository.canonicalKey(e.raw);
                            if (key != null) pool.put(key, e);
                        }
                        int[] dpi = retryWithDpi(new ArrayList<>(scan.failed), pool, why, dpiBudget);
                        dpiBudget -= dpi[0];
                        measuredOk += dpi[1];
                    }

                    final Map<String, Pool.Entry> poolNow = new LinkedHashMap<>();
                    for (Pool.Entry e : Pool.load(MainActivity.this)) {
                        String key = ConfigRepository.canonicalKey(e.raw);
                        if (key != null) poolNow.put(key, e);
                    }

                    List<Pool.Entry> snapshot = snapshot(poolNow);
                    Pool.save(MainActivity.this, snapshot);
                    logWhy(why);
                    final int n = snapshot.size();
                    final long best = n > 0 ? Pool.bestKbps(MainActivity.this) : -1;
                    uiLog("Пул собран: " + n + " рабочих · проверено " + scan.tested
                            + " из " + scan.unique + " · каналов (кластеров) живых "
                            + scan.clustersAlive + " · батч-замеров " + measuredOk
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
        btnPool.setEnabled(true);
        btnPool.setText(busy ? "■ Стоп" : "Сбор конфигураций");
        btnPool.setTextColor(busy ? Ui.DANGER : Ui.TEXT);
        if (!busy) {
            livePool = null;
            refreshPoolCard();
        }
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
