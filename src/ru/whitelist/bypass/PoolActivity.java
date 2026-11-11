package ru.whitelist.bypass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * «Рабочие конфиги» — экран пула: что лежит в рабочих, что из этого ещё живо.
 *
 * Зачем отдельный экран: конфиг может быть рабочим «по документам» (лежит в пуле с замером
 * скорости) и при этом давно не отвечать. Раньше это выяснялось только при подключении —
 * и пользователь видел «конфиги есть, а VPN не работает». Здесь каждый пункт можно
 * пропинговать, перемерить, убрать из рабочих или сразу отправить в чёрный список,
 * а после общей проверки — одной кнопкой убрать все провалившиеся.
 */
public class PoolActivity extends Activity {

    private static final int BG = Ui.BG;
    private static final int SURFACE = Ui.SURFACE;
    private static final int ACCENT = Ui.ACCENT;
    private static final int TEXT = Ui.TEXT;
    private static final int TEXT_DIM = Ui.TEXT_DIM;
    private static final int RED = Ui.DANGER;
    private static final int ORANGE = Ui.WARN;

    /** Больше строк не рисуем: пул и так ограничен, а телефон — не сервер. */
    private static final int MAX_ROWS = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView summary;
    private Button pingAll, measureAll, blacklistAll;
    private ScrollView scroll;

    /** Канонические ключи конфигов, проваливших последнюю проверку. */
    private final Set<String> failed = new LinkedHashSet<>();
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private volatile boolean busy = false;

    private static final class Row {
        Pool.Entry entry;
        ProxyConfig cfg;
        TextView title, status;
        Button ping, measure, remove, black;
    }

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashReporter.install(this);
        Prefs.migrate(this);
        super.onCreate(savedInstanceState);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(Ui.S4);
        root.setPadding(p, p, p, dp(Ui.S6));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        Ui.applyInsets(scroll);

        TextView back = Ui.iconButton(this, "←", Ui.TEXT);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(Ui.headerLeft(this, "Рабочие конфиги",
                "Проверка пула вживую: пинг, перемер, чистка", back));

        summary = Ui.caption(this, "");
        summary.setTextSize(13);
        summary.setTextColor(TEXT);
        summary.setBackground(Ui.shape(Ui.SURFACE_2, Ui.STROKE, 1, Ui.R_MD, this));
        int sp = dp(Ui.S3);
        summary.setPadding(sp, dp(10), sp, dp(10));
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(Ui.S4);
        summary.setLayoutParams(summaryLp);
        root.addView(summary);

        LinearLayout actions = Ui.card(this);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(Ui.S4);
        actions.setLayoutParams(actionsLp);
        actions.addView(Ui.overline(this, "Проверка"));

        pingAll = Ui.secondary(this, "Пропинговать все");
        pingAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pingAll(); }
        });
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        a1.topMargin = dp(10);
        actions.addView(pingAll, a1);

        measureAll = Ui.secondary(this, "Перемерить скорость у всех");
        measureAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmMeasureAll(); }
        });
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        a2.topMargin = dp(Ui.S2);
        actions.addView(measureAll, a2);

        Button reload = Ui.ghost(this, "Обновить список");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { render(); }
        });
        actions.addView(reload);
        root.addView(actions);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        blacklistAll = Ui.danger(this, "В чёрный список все нерабочие (0)");
        blacklistAll.setEnabled(false);
        blacklistAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmBlacklistFailed(); }
        });
        LinearLayout.LayoutParams blLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blLp.topMargin = dp(18);
        blacklistAll.setLayoutParams(blLp);
        root.addView(blacklistAll);

        TextView footNote = Ui.caption(this, "Пинг проверяет только доступность сервера (TCP). "
                + "«Перемерить» поднимает ядро и качает через конфиг — так же, как при сборе "
                + "пула, включая DPI-обход, если он записан у конфига.");
        footNote.setPadding(dp(2), dp(Ui.S4), dp(2), 0);
        root.addView(footNote);

        render();
    }

    // ------------------------------------------------------------------ список

    private void render() {
        list.removeAllViews();
        rows.clear();
        final List<Pool.Entry> entries = Pool.load(this);
        boolean changed = failed.retainAll(keysOf(entries));
        if (changed) updateBlacklistButton();

        if (entries.isEmpty()) {
            LinearLayout emptyCard = Ui.card(this);
            emptyCard.addView(Ui.body(this, "Пул пуст"));
            TextView hint = Ui.caption(this, "«Собрать пул» на главном экране обновит списки "
                    + "и замерит скорость у живых — после этого здесь появятся строки.");
            hint.setPadding(0, dp(Ui.S2), 0, 0);
            emptyCard.addView(hint);
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyLp.topMargin = dp(Ui.S4);
            emptyCard.setLayoutParams(emptyLp);
            list.addView(emptyCard);
        }

        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            Pool.Entry e = entries.get(i);
            ProxyConfig cfg = e.cfg();
            if (cfg == null) continue;                     // битую строку не показываем
            list.addView(buildRow(i + 1, e, cfg));
        }
        if (entries.size() > shown) {
            TextView more = Ui.caption(this, "…и ещё " + (entries.size() - shown)
                    + " конфигов: они уходят в конец по скорости. Полный список — в экспорте пула.");
            more.setPadding(dp(2), dp(Ui.S3), dp(2), 0);
            list.addView(more);
        }
        updateSummary(entries.size());
        updateBlacklistButton();
    }

    private static Set<String> keysOf(List<Pool.Entry> entries) {
        Set<String> out = new LinkedHashSet<>();
        for (Pool.Entry e : entries) {
            String k = ConfigRepository.canonicalKey(e.raw);
            if (k != null && !k.isEmpty()) out.add(k);
        }
        return out;
    }

    private View buildRow(int index, final Pool.Entry entry, final ProxyConfig cfg) {
        LinearLayout row = Ui.card(this);
        final int pad = dp(Ui.S4);
        row.setPadding(pad, dp(Ui.S3), pad, dp(Ui.S3));

        final Row r = new Row();
        r.entry = entry;
        r.cfg = cfg;

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        r.title = Ui.body(this, index + ". " + Prefs.speedText(entry.kbps));
        r.title.setTextSize(15);
        r.title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        head.addView(r.title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        long ping = entry.pingMs;
        TextView pingChip = Ui.chip(this, ping > 0 ? ping + " мс" : "пинг —",
                ping > 0 ? (ping < 300 ? ACCENT : (ping < 800 ? ORANGE : RED)) : TEXT_DIM, false);
        head.addView(pingChip);
        row.addView(head);

        TextView sub = Ui.caption(this, cfg.scheme + " · " + cfg.host + ":" + cfg.port
                + (cfg.name != null && !cfg.name.isEmpty() ? " · " + cfg.name : ""));
        sub.setPadding(0, dp(Ui.S1), 0, 0);
        row.addView(sub);

        StringBuilder meta = new StringBuilder("замер ").append(ago(entry.at));
        if (entry.viaDpi()) meta.append(" · обход: ").append(DpiStrategies.shorten(entry.dpi));
        TextView metaView = Ui.caption(this, meta.toString());
        metaView.setTextColor(entry.viaDpi() ? ORANGE : TEXT_DIM);
        row.addView(metaView);

        r.status = Ui.caption(this, "");
        r.status.setPadding(0, dp(Ui.S1), 0, 0);
        row.addView(r.status);

        // Две строки по две кнопки: четыре в ряд на узком экране превращаются
        // в текст по слогам и промахи пальцем.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(0, dp(Ui.S2), 0, 0);

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        r.ping = rowButton("Пинг", TEXT, true);
        r.ping.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pingOne(r); }
        });
        firstRow.addView(r.ping);

        r.measure = rowButton("Перемерить скорость", TEXT, false);
        r.measure.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { measureOne(r); }
        });
        firstRow.addView(r.measure);
        buttons.addView(firstRow);

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondLp.topMargin = dp(6);
        secondRow.setLayoutParams(secondLp);

        r.remove = rowButton("Убрать из рабочих", TEXT, true);
        r.remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { removeOne(r); }
        });
        secondRow.addView(r.remove);

        r.black = rowButton("В чёрный список", RED, false);
        r.black.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { blacklistOne(r); }
        });
        secondRow.addView(r.black);
        buttons.addView(secondRow);
        row.addView(buttons);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(Ui.S3);
        row.setLayoutParams(lp);

        String key = ConfigRepository.canonicalKey(entry.raw);
        if (key != null) rows.put(key, r);
        return row;
    }

    /** Кнопка строки: компактная, но не меньше 40 dp по высоте. */
    private Button rowButton(String text, int color, boolean left) {
        Button b = Ui.small(this, text);
        b.setTextColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (left) lp.rightMargin = dp(5); else lp.leftMargin = dp(5);
        b.setLayoutParams(lp);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private void updateSummary(int total) {
        int failedCount = failed.size();
        StringBuilder sb = new StringBuilder();
        sb.append("В пуле: ").append(total)
          .append(" · лучший: ").append(Prefs.speedText(Pool.bestKbps(this)))
          .append(" · порог: ").append(Prefs.minSpeedText(this));
        if (failedCount > 0) sb.append("\nПровалили проверку: ").append(failedCount);
        if (busy) sb.append("\nИду проверку…");
        summary.setText(sb.toString());
    }

    private void updateBlacklistButton() {
        int n = failed.size();
        blacklistAll.setText("В чёрный список все нерабочие (" + n + ")");
        blacklistAll.setEnabled(!busy && n > 0);
    }

    // ------------------------------------------------------------------ одиночные действия

    /** TCP-пинг одного конфига: доступен ли сервер вообще. */
    private void pingOne(final Row r) {
        if (busy) return;
        setBusy(true);
        r.status.setTextColor(TEXT_DIM);
        r.status.setText("проверяю пинг…");
        new Thread(new Runnable() {
            @Override public void run() {
                final ConfigTester.Outcome out = ConfigTester.test(
                        new ArrayList<>(java.util.Collections.singletonList(r.cfg)), 2,
                        Prefs.pingTimeoutMs(PoolActivity.this), null);
                final boolean alive = !out.alive.isEmpty();
                final long ms = alive ? r.cfg.latency : -1;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        setBusy(false);
                        if (alive) {
                            r.status.setTextColor(ACCENT);
                            r.status.setText("пинг: " + ms + " мс");
                            clearFailed(r.entry);
                        } else {
                            r.status.setTextColor(RED);
                            r.status.setText("пинг не прошёл: сервер не отвечает");
                            markFailed(r.entry);
                        }
                    }
                });
            }
        }, "pool-ping-one").start();
    }

    /** Перемер скорости одного конфига — тем же путём, что при сборе пула. */
    private void measureOne(final Row r) {
        if (busy) return;
        setBusy(true);
        r.status.setTextColor(TEXT_DIM);
        r.status.setText("поднимаю ядро и качаю…");
        new Thread(new Runnable() {
            @Override public void run() {
                final Measured m = measure(r.entry, r.cfg);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        setBusy(false);
                        applyMeasured(r, m);
                    }
                });
            }
        }, "pool-measure-one").start();
    }

    private void applyMeasured(Row r, Measured m) {
        if (m.result.ok && m.result.kbps > 0) {
            clearFailed(r.entry);
            toast("OK · " + okText(m)
                    + (m.dpi.isEmpty() ? " · напрямую" : " · через DPI"));
            render();   // скорость и время замера изменились — список пересортируется
            return;
        }
        if (cancelled(m.result)) {
            r.status.setTextColor(TEXT_DIM);
            r.status.setText("проверка прервана");
            return;
        }
        r.status.setTextColor(RED);
        r.status.setText("не прошёл: " + m.result.reason);
        markFailed(r.entry);
    }

    /** Проверку прервали извне (кнопка «Отмена» на главном экране) — это не приговор. */
    private static boolean cancelled(Probe.Result r) {
        return r != null && !r.ok && r.reason != null && r.reason.contains("остановлен");
    }

    private void removeOne(Row r) {
        Pool.remove(this, r.entry.raw);
        clearFailed(r.entry);
        toast("Убран из рабочих: " + truncate(r.cfg.name, 34));
        render();
    }

    private void blacklistOne(Row r) {
        Blacklist.add(this, r.cfg, "убран вручную с экрана рабочих конфигов");
        Pool.remove(this, r.entry.raw);
        clearFailed(r.entry);
        toast("В чёрном списке на 7 дней: " + truncate(r.cfg.name, 30));
        render();
    }

    // ------------------------------------------------------------------ массовые действия

    /** Пинг всех: живые — зелёные, мёртвые — в списке «нерабочих». */
    private void pingAll() {
        if (busy) return;
        final List<Pool.Entry> entries = Pool.load(this);
        if (entries.isEmpty()) {
            toast("Пул пуст");
            return;
        }
        setBusy(true);
        summary.setText("Пингую " + entries.size() + " конфигов…");
        new Thread(new Runnable() {
            @Override public void run() {
                List<ProxyConfig> configs = new ArrayList<>();
                Map<String, Pool.Entry> byKey = new LinkedHashMap<>();
                for (Pool.Entry e : entries) {
                    ProxyConfig c = e.cfg();
                    if (c == null) continue;
                    configs.add(c);
                    String k = ConfigRepository.canonicalKey(e.raw);
                    if (k != null) byKey.put(k, e);
                }
                final ConfigTester.Outcome out = ConfigTester.test(configs,
                        Prefs.pingThreads(PoolActivity.this), Prefs.pingTimeoutMs(PoolActivity.this),
                        new ConfigTester.Listener() {
                            @Override public void onProgress(int done, int total, int ok, int error) {
                                final String text = "Пинг " + done + "/" + total
                                        + " → живых " + ok + ", мёртвых " + error;
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        if (!isFinishing()) summary.setText(text);
                                    }
                                });
                            }
                        });
                final Set<String> aliveKeys = new LinkedHashSet<>();
                for (ProxyConfig c : out.alive) {
                    String k = ConfigRepository.canonicalKey(c.raw);
                    if (k != null) aliveKeys.add(k);
                }
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (out.cancelled) {
                            setBusy(false);
                            summary.setText("Пинг прерван");
                            toast("Пинг прерван — никого не трогаю");
                            return;
                        }
                        failed.clear();
                        for (Map.Entry<String, Pool.Entry> e : byKey.entrySet()) {
                            Row r = rows.get(e.getKey());
                            boolean alive = aliveKeys.contains(e.getKey());
                            if (r != null) {
                                r.status.setTextColor(alive ? ACCENT : RED);
                                r.status.setText(alive
                                        ? "пинг: " + e.getValue().cfg().latency + " мс"
                                        : "пинг не прошёл: сервер не отвечает");
                            }
                            if (!alive) failed.add(e.getKey());
                        }
                        setBusy(false);
                        updateBlacklistButton();
                        updateSummary(Pool.count(PoolActivity.this));
                        toast("Пинг: живых " + out.alive.size() + " из " + out.total);
                    }
                });
            }
        }, "pool-ping-all").start();
    }

    private void confirmMeasureAll() {
        if (busy) return;
        final int n = Pool.count(this);
        if (n == 0) {
            toast("Пул пуст");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Перемерить все?")
                .setMessage("Поднимем ядро для каждого из " + n + " конфигов и скачаем через него "
                        + "файл — это то же, что делает «Собрать пул», только без обновления "
                        + "списков. Занимает примерно " + Math.max(1, n / 4) + "–" + (n / 2 + 2)
                        + " минут и тратит трафик.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Перемерить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { measureAll(); }
                })
                .show();
    }

    private void measureAll() {
        final List<Pool.Entry> entries = Pool.load(this);
        if (entries.isEmpty()) return;
        setBusy(true);
        final int total = entries.size();
        final int lanes = Math.max(1, Math.min(4, Prefs.speedLanes(this)));
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger okCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(lanes);
        for (final Pool.Entry entry : entries) {
            final ProxyConfig cfg = entry.cfg();
            if (cfg == null) continue;
            pool.execute(new Runnable() {
                @Override public void run() {
                    final Measured m = measure(entry, cfg);
                    final int d = done.incrementAndGet();
                    if (m.result.ok && m.result.kbps > 0) okCount.incrementAndGet();
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            summary.setText("Замер " + d + "/" + total
                                    + " · рабочих " + okCount.get());
                            String key = ConfigRepository.canonicalKey(entry.raw);
                            Row r = key == null ? null : rows.get(key);
                            if (r != null) {
                                if (m.result.ok && m.result.kbps > 0) {
                                    r.status.setTextColor(ACCENT);
                                    r.status.setText("OK · " + okText(m)
                                            + (m.dpi.isEmpty() ? " · напрямую" : " · через DPI"));
                                } else if (cancelled(m.result)) {
                                    r.status.setTextColor(TEXT_DIM);
                                    r.status.setText("проверка прервана");
                                } else {
                                    r.status.setTextColor(RED);
                                    r.status.setText("не прошёл: " + m.result.reason);
                                }
                            }
                            if (key != null) {
                                if (m.result.ok && m.result.kbps > 0) failed.remove(key);
                                else if (!cancelled(m.result)) failed.add(key);
                            }
                            updateBlacklistButton();
                        }
                    });
                }
            });
        }
        pool.shutdown();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    pool.awaitTermination(2, java.util.concurrent.TimeUnit.HOURS);
                } catch (InterruptedException ignored) {
                }
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        setBusy(false);
                        toast("Замер: рабочих " + okCount.get() + " из " + total);
                        render();
                    }
                });
            }
        }, "pool-measure-all-wait").start();
    }

    private void confirmBlacklistFailed() {
        final List<Pool.Entry> entries = new ArrayList<>();
        for (Pool.Entry e : Pool.load(this)) {
            String k = ConfigRepository.canonicalKey(e.raw);
            if (k != null && failed.contains(k)) entries.add(e);
        }
        if (entries.isEmpty()) {
            toast("Провалившихся нет — сначала проверьте список");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("В чёрный список " + entries.size() + " конфигов?")
                .setMessage("Они будут убраны из рабочих и не будут проверяться 7 дней. "
                        + "Их серверы могли просто лежать — вернуть их можно кнопкой "
                        + "«Очистить список нерабочих» в настройках.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Убрать", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        for (Pool.Entry e : entries) {
                            ProxyConfig cfg = e.cfg();
                            if (cfg != null) Blacklist.add(PoolActivity.this, cfg,
                                    "провалил проверку на экране рабочих конфигов");
                            Pool.remove(PoolActivity.this, e.raw);
                            n++;
                        }
                        failed.clear();
                        toast("В чёрный список: " + n + ", из рабочих убраны");
                        render();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ замер

    /**
     * Как показать удачный замер. Пустой 204 — тоже удача (туннель работает), но
     * «4 КБ/с» здесь были бы выдумкой, поэтому пишем честно.
     */
    private static String okText(Measured m) {
        boolean onlyAlive = m.result.speed != null && !m.result.speed.ok() && m.result.speed.alive();
        return onlyAlive ? "связность есть, скорость не измерена"
                : Prefs.speedText(m.result.kbps);
    }

    private static final class Measured {
        Probe.Result result;
        String dpi = "";

        Measured(Probe.Result result, String dpi) {
            this.result = result;
            this.dpi = dpi == null ? "" : dpi;
        }
    }

    /**
     * Замер конфига тем же порядком, что и подключение: если конфиг замерен через обход —
     * сначала со своей стратегией, иначе сначала напрямую. Вторая попытка одна.
     */
    private Measured measure(Pool.Entry entry, ProxyConfig cfg) {
        int gate = Prefs.gateTimeoutMs(this);
        if (entry.viaDpi()) {
            Probe.Result r = Probe.measureConfig(this, cfg, gate, entry.dpi);
            if (r.ok) return new Measured(r, entry.dpi);
            Probe.Result direct = Probe.measureConfig(this, cfg, gate, null);
            if (direct.ok) {
                Pool.remember(this, cfg.raw, direct.kbps, cfg.latency, "");
                return new Measured(direct, "");
            }
            return new Measured(direct, "");
        }
        Probe.Result r = Probe.measureConfig(this, cfg, gate, null);
        if (r.ok || !Prefs.desyncLine(this)) {
            if (r.ok) Pool.remember(this, cfg.raw, r.kbps, cfg.latency, "");
            return new Measured(r, "");
        }
        String strategy = Prefs.dpiStrategy(this);
        Probe.Result viaDpi = Probe.measureConfig(this, cfg, gate, strategy);
        if (viaDpi.ok) {
            Pool.remember(this, cfg.raw, viaDpi.kbps, cfg.latency, strategy);
            return new Measured(viaDpi, strategy);
        }
        return new Measured(viaDpi, "");
    }

    // ------------------------------------------------------------------ мелочи

    private void markFailed(Pool.Entry e) {
        String k = ConfigRepository.canonicalKey(e.raw);
        if (k != null) failed.add(k);
        updateBlacklistButton();
    }

    private void clearFailed(Pool.Entry e) {
        String k = ConfigRepository.canonicalKey(e.raw);
        if (k != null) failed.remove(k);
        updateBlacklistButton();
    }

    private void setBusy(boolean value) {
        busy = value;
        pingAll.setEnabled(!value);
        measureAll.setEnabled(!value);
        updateBlacklistButton();
        updateSummary(Pool.count(this));
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** «3 ч назад», «12 мин назад» — по времени замера. */
    private static String ago(long at) {
        if (at <= 0) return "—";
        long diff = System.currentTimeMillis() - at;
        if (diff < 0) diff = 0;
        long min = diff / 60000;
        if (min < 1) return "только что";
        if (min < 60) return min + " мин назад";
        long hours = min / 60;
        if (hours < 24) return hours + " ч назад";
        return (hours / 24) + " дн назад";
    }
}
