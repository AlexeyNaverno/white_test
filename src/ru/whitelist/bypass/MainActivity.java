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
import java.util.List;
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

    private static final int SURFACE = 0xFF161F2A;
    private static final int SURFACE2 = 0xFF1E2A38;
    private static final int ACCENT = 0xFF3DDC84;
    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;
    private static final int RED = 0xFFFF5252;
    private static final int ORANGE = 0xFFFFAB40;

    /** Сколько конфигов «вживую» проверяем при сборе пула (дольше — не быстрее). */
    private static final int MAX_SPEED_TESTS = 300;
    /** Сколько параллельных проверок — телефон не тянет больше двух Xray сразу. */
    private static final int SPEED_LANES = 2;
    /** Сколько конфигов пингуем в быстром поиске. */
    private static final int QUICK_PING_CAP = 300;
    /** Сколько конфигов реально проверяем в быстром поиске. */
    private static final int QUICK_PROBES = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusPill, poolText, logText;
    private Button powerButton, btnQuick, btnPool, btnSettings;
    private BroadcastReceiver receiver;

    private volatile boolean searching = false;

    private final Runnable statusSync = new Runnable() {
        @Override public void run() {
            boolean on = BypassVpnService.isRunning();
            if (on) {
                statusPill.setText("● Подключено");
                statusPill.setTextColor(ACCENT);
            } else if (BypassVpnService.isConnecting()) {
                statusPill.setText("● Подключение…");
                statusPill.setTextColor(ORANGE);
            } else {
                statusPill.setText("● Отключено");
                statusPill.setTextColor(TEXT_DIM);
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

        // шапка
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
        subtitle.setText("VPN · VLESS · скорость вместо пинга");
        subtitle.setTextSize(11);
        subtitle.setTextColor(TEXT_DIM);
        titleCol.addView(title);
        titleCol.addView(subtitle);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnSettings = smallButton("Настройки");
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        header.addView(btnSettings);
        root.addView(header);

        // статус
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

        // главная кнопка
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
        powerButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConnection(); }
        });
        root.addView(powerButton);

        // две малые кнопки
        btnQuick = fullWidthButton("Быстрый поиск: найти 1 рабочий VPN");
        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (searching) { cancelSearch(); return; }
                startQuickSearch();
            }
        });
        root.addView(btnQuick);

        btnPool = fullWidthButton("Собрать пул: обновить все списки + замерить скорость");
        btnPool.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (searching) { cancelSearch(); return; }
                startBuildPool();
            }
        });
        root.addView(btnPool);

        // карточка пула
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(SURFACE);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(16);
        card.setLayoutParams(cardLp);
        TextView cardTitle = new TextView(this);
        cardTitle.setText("ПУЛ");
        cardTitle.setTextSize(11);
        cardTitle.setTextColor(TEXT_DIM);
        card.addView(cardTitle);
        poolText = new TextView(this);
        poolText.setTextSize(13);
        poolText.setTextColor(TEXT);
        poolText.setPadding(0, dp(6), 0, 0);
        card.addView(poolText);
        root.addView(card);

        // лог
        logText = new TextView(this);
        logText.setText("Лог: готов");
        logText.setTextSize(10);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextColor(TEXT_DIM);
        logText.setBackgroundColor(SURFACE);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        logText.setMinLines(3);
        logText.setMaxLines(12);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logLp.topMargin = dp(14);
        logText.setLayoutParams(logLp);
        root.addView(logText);

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

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setBackgroundResource(R.drawable.bg_button);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }

    private Button fullWidthButton(String text) {
        Button b = smallButton(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
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
                refreshConnectionUi(state, detail);
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
        refreshConnectionUi(state, intent.getStringExtra(BypassVpnService.EXTRA_DETAIL));
    }

    private void refreshConnectionUi(String state, String detail) {
        if (state == null) return;
        if ("on".equals(state)) {
            statusPill.setText("● Подключено");
            statusPill.setTextColor(ACCENT);
            powerButton.setText("Отключить");
            if (detail != null && !detail.isEmpty()) log("Подключено: " + detail);
            applyRunningUi(true);
            refreshPoolCard();
        } else if ("connecting".equals(state)) {
            statusPill.setText("● Подключение…");
            statusPill.setTextColor(ORANGE);
            powerButton.setText("Отмена");
            if (detail != null) log(detail);
        } else if ("error".equals(state)) {
            statusPill.setText("● Ошибка");
            statusPill.setTextColor(RED);
            powerButton.setText("Подключить");
            if (detail != null) log("Ошибка: " + detail);
            applyRunningUi(false);
            refreshPoolCard();
        } else if ("off".equals(state)) {
            statusPill.setText("● Отключено");
            statusPill.setTextColor(TEXT_DIM);
            powerButton.setText("Подключить");
            log("Отключено");
            applyRunningUi(false);
            refreshPoolCard();
        }
    }

    private void applyRunningUi(boolean on) {
        if (on) {
            statusPill.setText("● Подключено");
            statusPill.setTextColor(ACCENT);
            powerButton.setText("Отключить");
        } else if (!"● Подключение…".contentEquals(statusPill.getText())) {
            powerButton.setText("Подключить");
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
                        StringBuilder sb = new StringBuilder();
                        sb.append("Рабочих: ").append(n)
                          .append(" · лучший: ").append(best > 0 ? best + " КБ/с" : "—")
                          .append(" · порог: ").append(min).append(" КБ/с");
                        if (n > 0) {
                            sb.append('\n').append("Собран: ").append(dateStr(at));
                            if (!active.isEmpty()) sb.append(" · активный: ").append(truncate(active, 40));
                        } else {
                            sb.append('\n').append("Пул пуст — «Подключить» найдёт первый рабочий сам");
                        }
                        if (poolText != null) poolText.setText(sb.toString());
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
        uiLog("Быстрый поиск: пул → пинг → реальная проверка…");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final int min = Prefs.minSpeedKbps(MainActivity.this);

                    // 1) один лучший из пула — он уже замерен
                    for (Pool.Entry e : Pool.load(MainActivity.this)) {
                        ProxyConfig cfg = e.cfg();
                        if (cfg == null) continue;
                        uiLog("Проверяю лучший из пула: " + truncate(cfg.name, 45));
                        Probe.Result r = Probe.measureConfig(MainActivity.this, cfg,
                                Prefs.gateTimeoutMs(MainActivity.this));
                        if (ConfigRepository.isCancelled()) { uiLog("Поиск остановлен"); return; }
                        if (r.ok && r.kbps >= min) {
                            adopt(cfg, r.kbps);
                            finishOk(cfg.name, r.kbps);
                            return;
                        }
                        uiLog("Лучший из пула сейчас не проходит (" + (r.kbps < 0 ? "нет данных"
                                : r.kbps + " КБ/с") + ") — ищу по спискам");
                        break;
                    }

                    // 2) по скачанным спискам
                    List<ProxyConfig> all = ConfigRepository.all(MainActivity.this);
                    if (all.isEmpty()) {
                        uiLog("Списков нет — скачиваю источники…");
                        ConfigRepository.UpdateResult res = ConfigRepository.updateAll(
                                MainActivity.this, new ConfigRepository.Progress() {
                                    @Override public void onMessage(String message) { uiLog(message); }
                                });
                        uiLog("Источники: " + res);
                        all = ConfigRepository.all(MainActivity.this);
                    }
                    if (all.isEmpty()) {
                        uiLog("Проверять нечего: запустите «Собрать пул»");
                        return;
                    }
                    if (all.size() > QUICK_PING_CAP) all = new ArrayList<>(all.subList(0, QUICK_PING_CAP));

                    uiLog("Пингую " + all.size() + " конфигов…");
                    List<ProxyConfig> alive = ConfigTester.test(all, Prefs.pingThreads(MainActivity.this),
                            Prefs.pingTimeoutMs(MainActivity.this), new ConfigTester.Listener() {
                                @Override public void onProgress(int done, int total) {
                                    if (done % 100 == 0) uiLog("пинг " + done + "/" + total);
                                }
                                @Override public void onDone(List<ProxyConfig> a, int t, int f) { }
                            });
                    if (alive == null) { uiLog("Поиск остановлен"); return; }
                    if (alive.isEmpty()) { uiLog("Ни один конфиг не пингуется — сеть недоступна?"); return; }

                    int probes = Math.min(alive.size(), QUICK_PROBES);
                    for (int i = 0; i < probes; i++) {
                        if (ConfigRepository.isCancelled()) { uiLog("Поиск остановлен"); return; }
                        ProxyConfig cfg = alive.get(i);
                        uiLog("Проверка " + (i + 1) + "/" + probes + ": " + truncate(cfg.name, 45));
                        Probe.Result r = Probe.measureConfig(MainActivity.this, cfg,
                                Prefs.gateTimeoutMs(MainActivity.this));
                        if (r.ok && r.kbps >= min) {
                            adopt(cfg, r.kbps);
                            finishOk(cfg.name, r.kbps);
                            return;
                        }
                    }
                    uiLog("Готовых конфигов быстрее " + min + " КБ/с не нашлось — попробуйте «Собрать пул»");
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
        uiLog("Сбор пула: 1) все источники → 2) пинг → 3) скорость");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 1) обновить ВСЕ списки
                    ConfigRepository.UpdateResult res = ConfigRepository.updateAll(
                            MainActivity.this, new ConfigRepository.Progress() {
                                @Override public void onMessage(String message) { uiLog(message); }
                            });
                    uiLog("Источники: " + res);
                    for (String m : res.messages) uiLog("· " + m);

                    List<ProxyConfig> all = ConfigRepository.all(MainActivity.this);
                    if (all.isEmpty()) {
                        uiLog("Конфигов нет — проверьте источники в настройках");
                        return;
                    }

                    // 2) пинг
                    uiLog("Пингую " + all.size() + " конфигов…");
                    List<ProxyConfig> alive = ConfigTester.test(all,
                            Prefs.pingThreads(MainActivity.this),
                            Prefs.pingTimeoutMs(MainActivity.this),
                            new ConfigTester.Listener() {
                                @Override public void onProgress(int done, int total) {
                                    if (done % 100 == 0) uiLog("пинг " + done + "/" + total);
                                }
                                @Override public void onDone(List<ProxyConfig> a, int t, int f) { }
                            });
                    if (alive == null) { uiLog("Сбор остановлен"); return; }
                    if (alive.isEmpty()) { uiLog("Ни один конфиг не пингуется — сеть недоступна?"); return; }

                    List<ProxyConfig> testList = alive;
                    if (testList.size() > MAX_SPEED_TESTS) {
                        testList = new ArrayList<>(testList.subList(0, MAX_SPEED_TESTS));
                    }
                    final List<ProxyConfig> work = testList;
                    final int min = Prefs.minSpeedKbps(MainActivity.this);
                    final int total = work.size();
                    uiLog("Меряю скорость у " + total + " живых (порог " + min + " КБ/с)…");

                    // 3) скорость: две «дорожки», каждая — свой экземпляр Xray
                    final List<Pool.Entry> found = java.util.Collections.synchronizedList(new ArrayList<Pool.Entry>());
                    final AtomicInteger cursor = new AtomicInteger();
                    final AtomicInteger done = new AtomicInteger();
                    ExecutorService lane = Executors.newFixedThreadPool(SPEED_LANES);
                    for (int i = 0; i < SPEED_LANES; i++) {
                        lane.execute(new Runnable() {
                            @Override public void run() {
                                for (;;) {
                                    if (ConfigRepository.isCancelled()) break;
                                    int idx = cursor.getAndIncrement();
                                    if (idx >= total) break;
                                    ProxyConfig cfg = work.get(idx);
                                    Probe.Result r = Probe.measureConfig(MainActivity.this, cfg,
                                            Prefs.gateTimeoutMs(MainActivity.this));
                                    if (r.ok && r.kbps >= min) {
                                        found.add(new Pool.Entry(cfg.raw, r.kbps,
                                                cfg.latency, System.currentTimeMillis()));
                                    }
                                    int d = done.incrementAndGet();
                                    if (d % 5 == 0 || d == total) {
                                        uiLog("скорость " + d + "/" + total
                                                + " · рабочих " + found.size());
                                    }
                                }
                            }
                        });
                    }
                    lane.shutdown();
                    lane.awaitTermination(2, TimeUnit.HOURS);

                    List<Pool.Entry> snapshot;
                    synchronized (found) {
                        snapshot = new ArrayList<>(found);
                    }
                    Pool.save(MainActivity.this, snapshot);
                    final int n = snapshot.size();
                    final long best = n > 0 ? Pool.bestKbps(MainActivity.this) : -1;
                    uiLog("Пул собран: " + n + " рабочих" + (best > 0 ? ", лучший " + best + " КБ/с" : ""));
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast("Пул: " + n + " рабочих" + (best > 0 ? ", лучший " + best + " КБ/с" : ""));
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

    private void cancelSearch() {
        ConfigRepository.requestCancel();
        uiLog("Останавливаю… (результаты, что уже есть, сохранятся)");
    }

    // ------------------------------------------------------------------ мелочи

    private void setBusy(boolean busy, String label) {
        btnQuick.setEnabled(!busy);
        btnPool.setEnabled(!busy);
        btnQuick.setText(busy ? "Быстрый поиск: остановить" : "Быстрый поиск: найти 1 рабочий VPN");
        btnPool.setText(busy ? "Сбор пула: остановить"
                : "Собрать пул: обновить все списки + замерить скорость");
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
        CharSequence cur = logText.getText();
        String all = (cur == null ? "" : cur.toString()) + "\n" + line;
        String[] lines = all.split("\n");
        int from = Math.max(0, lines.length - 12);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        logText.setText(sb.toString());
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
