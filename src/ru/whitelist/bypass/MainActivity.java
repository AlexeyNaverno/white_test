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

    private static final int REQ_VPN = 100;

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

    /** Сколько живых конфигов «вживую» проверяем при сборе пула (дольше — не быстрее). */
    private static final int MAX_SPEED_TESTS = 150;
    /** Как только в пуле набралось столько рабочих — дальше не меряем: пул уже есть. */
    private static final int POOL_ENOUGH = 25;
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
    private Button powerButton, btnQuick, btnPool;
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

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        Ui.applyInsets(scroll);
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

        // ---- главная карточка: состояние + большая кнопка
        LinearLayout hero = Ui.card(this);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = dp(18);
        hero.setLayoutParams(heroLp);

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
        hero.addView(statusRow);

        LinearLayout powerWrap = new LinearLayout(this);
        powerWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = dp(18);
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
        int powerSize = dp(168);
        powerButton.setLayoutParams(new LinearLayout.LayoutParams(powerSize, powerSize));
        powerButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConnection(); }
        });
        powerWrap.addView(powerButton);
        hero.addView(powerWrap);

        powerHint = Ui.caption(this, "Нажмите, чтобы подключиться");
        powerHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(14);
        powerHint.setLayoutParams(hintLp);
        hero.addView(powerHint);
        root.addView(hero);
        stylePowerButton("off");

        // ---- действия: две равные по весу кнопки, каждая со своей подписью
        LinearLayout actionsCard = Ui.card(this);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(16);
        actionsCard.setLayoutParams(actionsLp);
        actionsCard.addView(Ui.overline(this, "Действия"));

        btnQuick = Ui.secondary(this, "Быстрый поиск");
        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (searching) { cancelSearch(); return; }
                startQuickSearch();
            }
        });
        LinearLayout.LayoutParams quickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        quickLp.topMargin = dp(10);
        quickLp.bottomMargin = dp(4);
        actionsCard.addView(btnQuick, quickLp);
        actionsCard.addView(Ui.caption(this,
                "Найти один рабочий VPN и подключиться к нему. Быстро, но без выбора."));

        btnPool = Ui.secondary(this, "Собрать пул");
        btnPool.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (searching) { cancelSearch(); return; }
                startBuildPool();
            }
        });
        LinearLayout.LayoutParams poolBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        poolBtnLp.topMargin = dp(14);
        poolBtnLp.bottomMargin = dp(4);
        actionsCard.addView(btnPool, poolBtnLp);
        actionsCard.addView(Ui.caption(this,
                "Обновить все списки из источников и замерить скорость у живых. Долго, зато "
                        + "есть из чего выбирать."));
        root.addView(actionsCard);

        // ---- карточка пула: цифры и переход в «Рабочие конфиги»
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(16);
        card.setLayoutParams(cardLp);

        LinearLayout poolHead = new LinearLayout(this);
        poolHead.setOrientation(LinearLayout.HORIZONTAL);
        poolHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView poolTitle = Ui.h2(this, "Рабочий пул");
        poolHead.addView(poolTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(22);
        chevron.setTextColor(Ui.TEXT_DIM);
        poolHead.addView(chevron);
        card.addView(poolHead);

        poolText = Ui.caption(this, "Пул пуст");
        LinearLayout.LayoutParams poolTextLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        poolTextLp.topMargin = dp(6);
        poolText.setLayoutParams(poolTextLp);
        card.addView(poolText);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Pool.count(MainActivity.this) == 0) {
                    toast("Пул пуст — нажмите «Собрать пул»");
                    return;
                }
                startActivity(new Intent(MainActivity.this, PoolActivity.class));
            }
        });
        root.addView(card);

        // ---- журнал: то, что раньше было «простынёй текста» внизу экрана
        LinearLayout logCard = Ui.card(this);
        LinearLayout.LayoutParams logCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logCardLp.topMargin = dp(16);
        logCard.setLayoutParams(logCardLp);

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
        logCard.addView(logHead);

        logText = Ui.mono(this, "Лог: готов", 11, Ui.TEXT_DIM);
        logScroll = new ScrollView(this);
        logScroll.addView(logText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setScrollbarFadingEnabled(false);
        logScroll.setFillViewport(true);
        logScroll.setBackground(Ui.shape(Ui.BG, Ui.STROKE, 1, Ui.R_MD, this));
        logScroll.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        logLp.topMargin = dp(8);
        logScroll.setLayoutParams(logLp);
        logCard.addView(logScroll);
        root.addView(logCard);

        // ---- подвал: версия и напоминание про уведомление
        TextView footer = Ui.caption(this, versionLine()
                + "\nВ уведомлении — «Пауза», «Переключить» и «Остановить».");
        footer.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(footer);

        registerStatusReceiver();
        requestNotificationPermission();
        PermissionsHelper.requestOnce(this);
        handleStatusIntent(getIntent());
        refreshPoolCard();
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
        String name = "0.1.11";
        long code = 21;
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
                toast("Нужно разрешение на VPN-подключение");
            }
        }
    }

    private void startVpnService() {
        Intent i = new Intent(this, BypassVpnService.class);
        i.setAction(BypassVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
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
        if ("on".equals(state)) {
            setStatus(tor ? "● Tor-мосты" : "● Подключено", tor ? Ui.TOR : Ui.ACCENT);
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
            setStatus(tor ? "● Tor-мосты…" : "● Подключение…", tor ? Ui.TOR : Ui.WARN);
            stylePowerButton(tor ? "tor-connecting" : "connecting");
            if (detail != null) log(detail);
        } else if ("error".equals(state)) {
            setStatus("● Ошибка", Ui.DANGER);
            stylePowerButton("error");
            if (detail != null) log("Ошибка: " + detail);
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
                final String active = Prefs.currentName(MainActivity.this);
                final int min = Prefs.minSpeedKbps(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (poolText == null) return;
                        if (n == 0) {
                            poolText.setText("Пусто. «Подключить» найдёт первый рабочий сам, "
                                    + "а «Собрать пул» — запас на будущее.");
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append(n).append(n == 1 ? " конфиг" : " конфигов")
                          .append(" · лучший ").append(Prefs.speedText(best))
                          .append("\nСобран ").append(dateStr(at));
                        if (!active.isEmpty()) {
                            sb.append(" · активный ").append(truncate(active, 28));
                        }
                        sb.append("\nНажмите, чтобы проверить: пинг, перемер, удаление");
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
        uiLog("Сбор пула: 1) источники (лимит по времени) → 2) пинг → 3) скорость");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 1) обновить ВСЕ списки. Любая ошибка на этом шаге не должна валить
                    // весь сбор пула: работаем с тем, что уже лежит в хранилище.
                    ConfigRepository.UpdateResult res = null;
                    try {
                        res = ConfigRepository.updateAll(
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

                    List<ProxyConfig> all = ConfigRepository.all(MainActivity.this);
                    if (all.isEmpty()) {
                        uiLog("Конфигов нет — проверьте источники в настройках");
                        return;
                    }

                    // 1.1) Отсекаем то, что ядро не поднимет НИКОГДА. У таких конфигов
                    // TCP-пинг проходит, они попадают в «живые» и занимают дорожку замера,
                    // а потом падают одной и той же ошибкой ядра: в отчёте из 90 замеров
                    // так уходило 61, и пул собирал 3 рабочих из 1656 «живых».
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

                    // 1.1b) Чёрный список: то, что уже проверяли и оно не работало, не
                    // пингуем вовсе. Запись живёт неделю — потом конфиг перепроверяется.
                    int inBlacklist = Blacklist.size(MainActivity.this);
                    if (inBlacklist > 0) {
                        int before = all.size();
                        all = Blacklist.filter(MainActivity.this, all);
                        int skipped = before - all.size();
                        uiLog("Чёрный список: " + inBlacklist + " нерабочих"
                                + (skipped > 0 ? ", из них " + skipped + " есть в текущих списках — пропускаю"
                                                : " (в текущих списках их нет)"));
                        if (all.isEmpty()) {
                            uiLog("Все конфиги в чёрном списке — очистите его в настройках"
                                    + " (Данные → «Очистить список нерабочих конфигураций»)");
                            return;
                        }
                    }

                    // 1.2) стратегии DPI: тот же сбор пула — удобный момент их обновить.
                    // Ошибка здесь тоже не повод бросать замер скорости.
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

                    // 2) пинг: партиями, с накопленной статистикой
                    uiLog("Пингую " + all.size() + " конфигов (потоков "
                            + Prefs.pingThreads(MainActivity.this) + ", таймаут "
                            + Prefs.pingTimeoutMs(MainActivity.this) + " мс)…");
                    final int pingStep = 50;
                    ConfigTester.Outcome ping = ConfigTester.test(all,
                            Prefs.pingThreads(MainActivity.this),
                            Prefs.pingTimeoutMs(MainActivity.this),
                            new ConfigTester.Listener() {
                                @Override public void onProgress(int done, int total, int ok, int failed) {
                                    if (done % pingStep == 0 || done == total) {
                                        uiLog("  пинг " + done + "/" + total
                                                + " → живых " + ok + ", мёртвых " + failed);
                                    }
                                }
                            });
                    if (ping.cancelled) { uiLog("Сбор остановлен"); return; }
                    uiLog("Пинг: " + ping.summary());
                    if (ping.alive.isEmpty()) {
                        uiLog("Ни один конфиг не пингуется — сеть недоступна или источники пустые");
                        return;
                    }
                    StringBuilder aliveLine = new StringBuilder("Пингуются: ");
                    for (int i = 0; i < Math.min(10, ping.alive.size()); i++) {
                        ProxyConfig a = ping.alive.get(i);
                        if (i > 0) aliveLine.append(" · ");
                        aliveLine.append(truncate(a.name, 20)).append(' ').append(a.latency).append(" мс");
                    }
                    if (ping.alive.size() > 10) aliveLine.append(" · ещё ").append(ping.alive.size() - 10);
                    uiLog(aliveLine.toString());

                    // 3) скорость: сначала НОВЫЕ конфиги, затем уже лежащие в пуле.
                    // Старые замеры моложе POOL_REUSE_MS переиспользуем, а не гоняем заново:
                    // иначе каждый сбор пула заново меряет те же 200 конфигов (это и было
                    // «результат сильно не сразу»).
                    final Map<String, Pool.Entry> oldPool = new LinkedHashMap<>();
                    for (Pool.Entry e : Pool.load(MainActivity.this)) {
                        String key = ConfigRepository.canonicalKey(e.raw);
                        if (key != null) oldPool.put(key, e);
                    }
                    List<ProxyConfig> fresh = new ArrayList<>();
                    List<ProxyConfig> known = new ArrayList<>();
                    final List<Pool.Entry> found =
                            java.util.Collections.synchronizedList(new ArrayList<Pool.Entry>());
                    // Счётчик причин отказа: в конце видно, ЧТО именно мешает —
                    // «нет данных», «ядро не запустилось», «несовместим» и т.п.
                    final Map<String, Integer> why = java.util.Collections.synchronizedMap(
                            new LinkedHashMap<String, Integer>());
                    long now = System.currentTimeMillis();
                    int reused = 0;
                    for (ProxyConfig cfg : ping.alive) {
                        String key = ConfigRepository.canonicalKey(cfg.raw);
                        Pool.Entry old = key == null ? null : oldPool.get(key);
                        if (old == null) {
                            fresh.add(cfg);
                            continue;
                        }
                        if (old.at > 0 && now - old.at < POOL_REUSE_MS) {
                            // недавно замерен и всё ещё пингуется — оставляем как есть
                            synchronized (found) {
                                found.add(old);
                            }
                            reused++;
                        } else {
                            known.add(cfg);
                        }
                    }
                    if (reused > 0) {
                        uiLog("Замеры из пула свежие (< " + (POOL_REUSE_MS / 3600000) + " ч): "
                                + reused + " конфигов не перемеряю");
                    }
                    List<ProxyConfig> ordered = new ArrayList<>(fresh);
                    ordered.addAll(known);
                    List<ProxyConfig> testList = ordered;
                    if (testList.size() > MAX_SPEED_TESTS) {
                        uiLog("К замеру " + ordered.size() + " — меряю первые "
                                + MAX_SPEED_TESTS + " (новые вперёд)");
                        testList = new ArrayList<>(ordered.subList(0, MAX_SPEED_TESTS));
                    }
                    final List<ProxyConfig> work = testList;
                    final int min = Prefs.minSpeedKbps(MainActivity.this);
                    final String minText = Prefs.minSpeedText(MainActivity.this);
                    final int total = work.size();
                    final int lanes = Prefs.speedLanes(MainActivity.this);
                    uiLog("Меряю скорость у " + total + " живых параллельно: " + lanes
                            + " ядра, порог " + minText
                            + " (остановлюсь на " + POOL_ENOUGH + " рабочих)");

                    // «Дорожки»: каждая — свой экземпляр Xray. Считаем только НОВЫЕ
                    // удачные замеры: переиспользованные из пула не должны останавливать
                    // замер раньше времени.
                    final AtomicInteger cursor = new AtomicInteger();
                    final AtomicInteger done = new AtomicInteger();
                    final AtomicInteger measuredOk = new AtomicInteger();
                    ExecutorService lane = Executors.newFixedThreadPool(lanes);
                    for (int i = 0; i < lanes; i++) {
                        lane.execute(new Runnable() {
                            @Override public void run() {
                                for (;;) {
                                    if (ConfigRepository.isCancelled()) break;
                                    if (measuredOk.get() >= POOL_ENOUGH) break;
                                    int idx = cursor.getAndIncrement();
                                    if (idx >= total) break;
                                    ProxyConfig cfg = work.get(idx);
                                    Probe.Result r = Probe.measureConfig(MainActivity.this, cfg,
                                            Prefs.gateTimeoutMs(MainActivity.this));
                                    boolean passed = r.ok && r.kbps >= min;
                                    if (passed) {
                                        measuredOk.incrementAndGet();
                                        Blacklist.forget(MainActivity.this, cfg.raw);
                                        synchronized (found) {
                                            found.add(new Pool.Entry(cfg.raw, r.kbps,
                                                    cfg.latency, System.currentTimeMillis()));
                                        }
                                    } else {
                                        bump(why, r.reason);
                                        // Ядро не поднялось / данных нет — конфиг в чёрный
                                        // список: в следующий раз его даже не пингуем.
                                        if (r.hard) Blacklist.add(MainActivity.this, cfg, r.reason);
                                    }
                                    int d = done.incrementAndGet();
                                    if (d % 5 == 0 || d == total || passed) {
                                        uiLog("скорость " + d + "/" + total
                                                + " · рабочих " + found.size()
                                                + (passed ? " · +" + Prefs.speedText(r.kbps)
                                                           : " · " + truncate(cfg.name, 24)
                                                             + ": " + r.reason));
                                    }
                                }
                            }
                        });
                    }
                    lane.shutdown();
                    lane.awaitTermination(2, TimeUnit.HOURS);

                    // 2.5) DPI-обход для тех, кто не дал данных напрямую. Параллельно
                    // нельзя: ciadpi один, поэтому по очереди и с ограничением. Успех
                    // запоминается в пул вместе со стратегией — при подключении возьмём её.
                    if (!ConfigRepository.isCancelled() && Prefs.desyncLine(MainActivity.this)
                            && measuredOk.get() < POOL_ENOUGH) {
                        final String strategy = Prefs.dpiStrategy(MainActivity.this);
                        java.util.Set<String> okKeys = new java.util.HashSet<>();
                        synchronized (found) {
                            for (Pool.Entry e : found) {
                                String k = ConfigRepository.canonicalKey(e.raw);
                                if (k != null) okKeys.add(k);
                            }
                        }
                        int tried = 0;
                        for (ProxyConfig cfg : work) {
                            if (ConfigRepository.isCancelled()) break;
                            if (tried >= DPI_RETRIES || measuredOk.get() >= POOL_ENOUGH) break;
                            String key = ConfigRepository.canonicalKey(cfg.raw);
                            if (key != null && okKeys.contains(key)) continue;
                            tried++;
                            uiLog("DPI-обход: " + truncate(cfg.name, 26) + " через "
                                    + DpiStrategies.shorten(strategy));
                            Probe.Result r = Probe.measureConfig(MainActivity.this, cfg,
                                    Prefs.gateTimeoutMs(MainActivity.this), strategy);
                            if (r.ok && r.kbps >= min) {
                                measuredOk.incrementAndGet();
                                Blacklist.forget(MainActivity.this, cfg.raw);
                                DpiStrategies.rememberWorking(MainActivity.this, strategy);
                                synchronized (found) {
                                    found.add(new Pool.Entry(cfg.raw, r.kbps, cfg.latency,
                                            System.currentTimeMillis(), strategy));
                                }
                                uiLog("  + через DPI: " + Prefs.speedText(r.kbps) + " · "
                                        + DpiStrategies.shorten(strategy));
                            } else {
                                bump(why, r.reason);
                                if (r.hard) Blacklist.add(MainActivity.this, cfg, r.reason);
                                uiLog("  → через DPI не прошёл · " + r.reason);
                            }
                        }
                        if (tried > 0) {
                            uiLog("DPI-обход: проверено " + tried + ", рабочих в пуле "
                                    + found.size());
                        }
                    }

                    List<Pool.Entry> snapshot;
                    synchronized (found) {
                        snapshot = new ArrayList<>(found);
                    }
                    if (snapshot.isEmpty() && !work.isEmpty()
                            && !ConfigRepository.isCancelled()) {
                        uiLog("Параллельный замер не дал рабочих (канал делится на дорожки) — "
                                + "проверяю первые 20 по одному…");
                        List<Pool.Entry> again = Probe.measureSequential(MainActivity.this, work, 20);
                        snapshot.addAll(again);
                        uiLog("По одному: рабочих " + again.size());
                    }
                    Pool.save(MainActivity.this, snapshot);
                    logWhy(why);
                    final int n = snapshot.size();
                    final long best = n > 0 ? Pool.bestKbps(MainActivity.this) : -1;
                    uiLog("Пул собран: " + n + " рабочих из " + ping.alive.size() + " живых"
                            + (best > 0 ? ", лучший " + Prefs.speedText(best) : "")
                            + ". Порог: " + minText);
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
        btnQuick.setText(busy ? "■ Остановить поиск" : "Быстрый поиск");
        btnPool.setText(busy ? "■ Остановить сбор" : "Собрать пул");
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
