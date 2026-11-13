package ru.whitelist.bypass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * Содержимое экрана «Рабочие конфиги» — как отдельный блок, а не как Activity.
 *
 * Зачем: рабочие конфиги нужны в двух местах — первой вкладкой в настройках и отдельным
 * экраном с главного. Логика одна, поэтому она живёт здесь, а {@link PoolActivity} и
 * {@link SettingsActivity} только вставляют панель в свой контейнер.
 *
 * Что умеет:
 *   • показывать ОБА пула — Wi-Fi и мобильный (у них разные блокировки и разные замеры);
 *   • подключаться к конкретному конфигу, не перебирая остальные;
 *   • принимать конфиги из буфера обмена и из файла;
 *   • пинговать и перемерять по одному или все сразу;
 *   • убирать из рабочих и отправлять в чёрный список, в том числе «все нерабочие» разом.
 */
public final class PoolPanel {

    /** Открыть файл со списком конфигов (результат приходит в {@link #handleActivityResult}). */
    public static final int REQ_IMPORT_FILE = 410;

    private static final int MAX_ROWS = 60;

    private final Activity act;
    private final LinearLayout host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Какой пул открыт: {@link Net#WIFI} или {@link Net#MOBILE}. */
    private String net;

    private TextView tabWifi, tabLte, nowLabel, summary, status;
    private LinearLayout list;
    private Button paste, fromFile, pingAll, measureAll, blacklistAll;

    private final Set<String> failed = new LinkedHashSet<>();
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private volatile boolean busy = false;

    private static final class Row {
        Pool.Entry entry;
        ProxyConfig cfg;
        TextView status;
        Button connect, ping, measure, remove, black;
    }

    public PoolPanel(Activity act, LinearLayout host) {
        this.act = act;
        this.host = host;
        this.net = Net.key(act);
    }

    /** В какой пул смотрит панель. */
    public String net() {
        return net;
    }

    // ------------------------------------------------------------------ построение

    public void build() {
        // --- переключатель пулов: Wi-Fi / мобильный
        LinearLayout netRow = new LinearLayout(act);
        netRow.setOrientation(LinearLayout.HORIZONTAL);
        netRow.setGravity(Gravity.CENTER_VERTICAL);
        tabWifi = Ui.tab(act, "Wi-Fi");
        tabLte = Ui.tab(act, "LTE");
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.rightMargin = Ui.dp(act, Ui.S2);
        tabWifi.setLayoutParams(tabLp);
        tabWifi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectNet(Net.WIFI); }
        });
        tabLte.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectNet(Net.MOBILE); }
        });
        netRow.addView(tabWifi);
        netRow.addView(tabLte);
        nowLabel = Ui.caption(act, "");
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nowLp.leftMargin = Ui.dp(act, Ui.S2);
        nowLabel.setGravity(Gravity.END);
        netRow.addView(nowLabel, nowLp);
        host.addView(netRow);

        // --- сводка по открытому пулу
        summary = Ui.caption(act, "");
        summary.setTextSize(13);
        summary.setTextColor(Ui.TEXT);
        summary.setBackground(Ui.shape(Ui.SURFACE_2, Ui.STROKE, 1, Ui.R_MD, act));
        int sp = Ui.dp(act, Ui.S3);
        summary.setPadding(sp, Ui.dp(act, 10), sp, Ui.dp(act, 10));
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = Ui.dp(act, Ui.S3);
        summary.setLayoutParams(sumLp);
        host.addView(summary);

        // --- приём конфигов: буфер обмена и файл
        LinearLayout accept = Ui.card(act);
        LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acceptLp.topMargin = Ui.dp(act, Ui.S3);
        accept.setLayoutParams(acceptLp);
        accept.addView(Ui.overline(act, "Добавить конфиги"));
        accept.addView(Ui.caption(act, "Вставленный текст проверяется так же, как сбор пула: "
                + "дубликаты отбрасываются, мёртвое отсекается пингом, у живых меряется "
                + "скорость. В пул попадёт только то, что прошло порог."));

        LinearLayout acceptRow1 = new LinearLayout(act);
        acceptRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ar1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ar1.topMargin = Ui.dp(act, Ui.S3);
        acceptRow1.setLayoutParams(ar1);

        paste = Ui.small(act, "Вставить из буфера");
        paste.setTextColor(Ui.ACCENT);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteFromClipboard(); }
        });
        acceptRow1.addView(paste, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        fromFile = Ui.small(act, "Из файла");
        LinearLayout.LayoutParams fileLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fileLp.leftMargin = Ui.dp(act, Ui.S2);
        fromFile.setLayoutParams(fileLp);
        fromFile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        acceptRow1.addView(fromFile);
        accept.addView(acceptRow1);
        host.addView(accept);

        // --- проверки
        LinearLayout tools = Ui.card(act);
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolsLp.topMargin = Ui.dp(act, Ui.S3);
        tools.setLayoutParams(toolsLp);
        tools.addView(Ui.overline(act, "Проверка"));

        pingAll = Ui.secondary(act, "Пропинговать все");
        pingAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pingAll(); }
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p1.topMargin = Ui.dp(act, Ui.S2);
        tools.addView(pingAll, p1);

        measureAll = Ui.secondary(act, "Перемерить скорость у всех");
        measureAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmMeasureAll(); }
        });
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p2.topMargin = Ui.dp(act, Ui.S2);
        tools.addView(measureAll, p2);

        TextView autoNote = Ui.caption(act, "Провалившиеся проверку удаляются из рабочих "
                + "автоматически и на 7 дней попадают в чёрный список. Если не отвечает "
                + "ни одна посторонняя цель, сеть считается виноватой и ничего не удаляется.");
        autoNote.setPadding(0, Ui.dp(act, Ui.S2), 0, 0);
        tools.addView(autoNote);

        Button reload = Ui.ghost(act, "Обновить список");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });
        tools.addView(reload);
        host.addView(tools);

        status = Ui.caption(act, "");
        status.setPadding(Ui.dp(act, 2), Ui.dp(act, Ui.S3), Ui.dp(act, 2), 0);
        host.addView(status);

        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        host.addView(list);

        blacklistAll = Ui.danger(act, "В чёрный список все нерабочие (0)");
        blacklistAll.setEnabled(false);
        blacklistAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmBlacklistFailed(); }
        });
        LinearLayout.LayoutParams blLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blLp.topMargin = Ui.dp(act, Ui.S4);
        blacklistAll.setLayoutParams(blLp);
        host.addView(blacklistAll);

        TextView foot = Ui.caption(act, "Пинг проверяет только доступность сервера (TCP). "
                + "«Перемерить» поднимает ядро и качает через конфиг — так же, как при сборе "
                + "пула, включая DPI-обход, если он записан у конфига. Порог скорости берётся "
                + "из настроек.");
        foot.setPadding(Ui.dp(act, 2), Ui.dp(act, Ui.S3), Ui.dp(act, 2), 0);
        host.addView(foot);

        refresh();
    }

    /** Переключить пул (Wi-Fi ↔ мобильный). */
    public void selectNet(String value) {
        if (busy) {
            toast("Сначала дождитесь окончания проверки");
            return;
        }
        net = Net.normalize(value);
        failed.clear();
        refresh();
        if (!net.equals(Net.key(act))) {
            status.setText("Открыт пул «" + Net.label(net) + "». Телефон сейчас в сети «"
                    + Net.label(act) + "» — для подключения возьмётся её пул.");
        }
    }

    // ------------------------------------------------------------------ список

    public void refresh() {
        list.removeAllViews();
        rows.clear();
        final List<Pool.Entry> entries = Pool.load(act, net);
        if (failed.retainAll(keysOf(entries))) updateBlacklistButton();

        Ui.paintChip(tabWifi, Net.WIFI.equals(net) ? Ui.ACCENT : Ui.TEXT_DIM,
                Net.WIFI.equals(net), act);
        Ui.paintChip(tabLte, Net.MOBILE.equals(net) ? Ui.ACCENT : Ui.TEXT_DIM,
                Net.MOBILE.equals(net), act);
        tabWifi.setText("Wi-Fi · " + Pool.count(act, Net.WIFI));
        tabLte.setText("LTE · " + Pool.count(act, Net.MOBILE));
        nowLabel.setText("Сейчас: " + Net.label(act));

        if (entries.isEmpty()) {
            LinearLayout emptyCard = Ui.card(act);
            emptyCard.addView(Ui.body(act, "Пул «" + Net.label(net) + "» пуст"));
            TextView hint = Ui.caption(act, "Соберите пул, находясь в этой сети: на главном "
                    + "экране «Собрать пул», либо вставьте конфиги из буфера выше. Замеры для "
                    + "Wi-Fi и мобильного хранятся отдельно, потому что блокировки у них разные.");
            hint.setPadding(0, Ui.dp(act, Ui.S2), 0, 0);
            emptyCard.addView(hint);
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyLp.topMargin = Ui.dp(act, Ui.S3);
            emptyCard.setLayoutParams(emptyLp);
            list.addView(emptyCard);
        }

        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            Pool.Entry e = entries.get(i);
            ProxyConfig cfg = e.cfg();
            if (cfg == null) continue;
            list.addView(buildRow(i + 1, e, cfg));
        }
        if (entries.size() > shown) {
            TextView more = Ui.caption(act, "…и ещё " + (entries.size() - shown)
                    + " конфигов: они уходят в конец по скорости. Полный список — в экспорте пула.");
            more.setPadding(Ui.dp(act, 2), Ui.dp(act, Ui.S3), Ui.dp(act, 2), 0);
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
        LinearLayout row = Ui.card(act);
        int pad = Ui.dp(act, Ui.S4);
        row.setPadding(pad, Ui.dp(act, Ui.S3), pad, Ui.dp(act, Ui.S3));

        final Row r = new Row();
        r.entry = entry;
        r.cfg = cfg;

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.body(act, index + ". " + Prefs.speedText(entry.kbps));
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        long ping = entry.pingMs;
        head.addView(Ui.chip(act, ping > 0 ? ping + " мс" : "пинг —",
                ping > 0 ? (ping < 300 ? Ui.ACCENT : (ping < 800 ? Ui.WARN : Ui.DANGER))
                        : Ui.TEXT_DIM, false));
        row.addView(head);

        TextView sub = Ui.caption(act, cfg.scheme + " · " + cfg.host + ":" + cfg.port
                + (cfg.name != null && !cfg.name.isEmpty() ? " · " + cfg.name : ""));
        sub.setPadding(0, Ui.dp(act, Ui.S1), 0, 0);
        row.addView(sub);

        StringBuilder meta = new StringBuilder("замер ").append(ago(entry.at));
        if (entry.viaDpi()) meta.append(" · обход: ").append(DpiStrategies.shorten(entry.dpi));
        TextView metaView = Ui.caption(act, meta.toString());
        metaView.setTextColor(entry.viaDpi() ? Ui.WARN : Ui.TEXT_DIM);
        row.addView(metaView);

        r.status = Ui.caption(act, "");
        r.status.setPadding(0, Ui.dp(act, Ui.S1), 0, 0);
        row.addView(r.status);

        // Главное действие строки — подключиться именно к этому серверу.
        r.connect = Ui.primary(act, "Подключить этот конфиг");
        r.connect.setTextSize(14);
        LinearLayout.LayoutParams connectLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        connectLp.topMargin = Ui.dp(act, Ui.S3);
        r.connect.setLayoutParams(connectLp);
        r.connect.setMinHeight(Ui.dp(act, 46));
        r.connect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { connectTo(r); }
        });
        row.addView(r.connect);

        LinearLayout firstRow = new LinearLayout(act);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams frLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frLp.topMargin = Ui.dp(act, Ui.S2);
        firstRow.setLayoutParams(frLp);

        r.ping = rowButton("Пинг", Ui.TEXT, true);
        r.ping.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pingOne(r); }
        });
        firstRow.addView(r.ping);

        r.measure = rowButton("Перемерить", Ui.TEXT, false);
        r.measure.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { measureOne(r); }
        });
        firstRow.addView(r.measure);
        row.addView(firstRow);

        LinearLayout secondRow = new LinearLayout(act);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = Ui.dp(act, 6);
        secondRow.setLayoutParams(srLp);

        r.remove = rowButton("Убрать", Ui.TEXT, true);
        r.remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { removeOne(r); }
        });
        secondRow.addView(r.remove);

        r.black = rowButton("В чёрный список", Ui.DANGER, false);
        r.black.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { blacklistOne(r); }
        });
        secondRow.addView(r.black);
        row.addView(secondRow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, Ui.S3);
        row.setLayoutParams(lp);

        String key = ConfigRepository.canonicalKey(entry.raw);
        if (key != null) rows.put(key, r);
        return row;
    }

    private Button rowButton(String text, int color, boolean left) {
        Button b = Ui.small(act, text);
        b.setTextColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (left) lp.rightMargin = Ui.dp(act, 5);
        else lp.leftMargin = Ui.dp(act, 5);
        b.setLayoutParams(lp);
        b.setPadding(Ui.dp(act, 4), 0, Ui.dp(act, 4), 0);
        return b;
    }

    private void updateSummary(int total) {
        StringBuilder sb = new StringBuilder();
        sb.append(Net.label(net)).append(": ").append(total)
          .append(total == 1 ? " конфиг" : " конфигов")
          .append(" · лучший ").append(Prefs.speedText(Pool.bestKbps(act, net)));
        long at = Prefs.poolBuiltAt(act, net);
        if (at > 0) sb.append("\nСобран: ").append(stamp(at));
        sb.append(" · порог: ").append(Prefs.minSpeedText(act));
        if (!failed.isEmpty()) sb.append("\nПровалили проверку: ").append(failed.size());
        if (busy) sb.append("\nИдёт проверка…");
        summary.setText(sb.toString());
    }

    private void updateBlacklistButton() {
        int n = failed.size();
        blacklistAll.setText("В чёрный список нерабочие (" + n + ") · "
                + Net.label(net));
        blacklistAll.setEnabled(!busy && n > 0);
    }

    // ------------------------------------------------------------------ приём конфигов

    /** Вставить конфиги из буфера обмена: «не только из файлов». */
    private void pasteFromClipboard() {
        if (busy) {
            toast("Сначала дождитесь окончания проверки");
            return;
        }
        String text = "";
        try {
            ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip()) {
                ClipData clip = cb.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence cs = clip.getItemAt(0).coerceToText(act);
                    text = cs == null ? "" : cs.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        if (text.trim().isEmpty()) {
            toast("В буфере обмена пусто");
            return;
        }
        importText(text);
    }

    private void pickFile() {
        if (busy) {
            toast("Сначала дождитесь окончания проверки");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        try {
            act.startActivityForResult(intent, REQ_IMPORT_FILE);
        } catch (Throwable t) {
            toast("Не удалось открыть выбор файла");
        }
    }

    /** Хозяин экрана должен передать сюда результат выбора файла. */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT_FILE) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        final Uri uri = data.getData();
        setBusy(true);
        status.setText("Читаю файл…");
        new Thread(new Runnable() {
            @Override public void run() {
                String text = readAll(uri);
                ui.post(new Runnable() {
                    @Override public void run() { importText(text); }
                });
            }
        }, "pool-import-file").start();
        return true;
    }

    private String readAll(Uri uri) {
        try (InputStream in = act.getContentResolver().openInputStream(uri)) {
            if (in == null) return "";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void importText(final String text) {
        if (text == null || text.trim().isEmpty()) {
            toast("Нечего импортировать");
            setBusy(false);
            return;
        }
        setBusy(true);
        status.setText("Проверяю конфиги из " + (text.length() > 20000 ? "файла" : "буфера") + "…");
        final String target = net;
        new Thread(new Runnable() {
            @Override public void run() {
                final PoolIo.Result res = PoolIo.importText(act, target, text,
                        new PoolIo.Log() {
                            @Override public void line(final String message) {
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        if (status != null) status.setText(message);
                                    }
                                });
                            }
                        });
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (act.isFinishing()) return;
                        setBusy(false);
                        status.setText("Импорт: " + res.summary());
                        toast(res.added > 0
                                ? "Добавлено в пул «" + Net.label(target) + "»: " + res.added
                                : "Ничего не добавлено: " + res.summary());
                        refresh();
                    }
                });
            }
        }, "pool-paste").start();
    }

    // ------------------------------------------------------------------ одиночные действия

    private void connectTo(Row r) {
        toast("Подключаюсь: " + truncate(r.cfg.name, 30));
        VpnStart.connectRaw(act, r.cfg.raw);
    }

    private void pingOne(final Row r) {
        if (busy) return;
        setBusy(true);
        r.status.setTextColor(Ui.TEXT_DIM);
        r.status.setText("проверяю пинг…");
        new Thread(new Runnable() {
            @Override public void run() {
                final ConfigTester.Outcome out = ConfigTester.test(
                        new ArrayList<>(java.util.Collections.singletonList(r.cfg)), 2,
                        Prefs.pingTimeoutMs(act), null);
                final boolean alive = !out.alive.isEmpty();
                final long ms = alive ? r.cfg.latency : -1;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (act.isFinishing()) return;
                        setBusy(false);
                        if (alive) {
                            r.status.setTextColor(Ui.ACCENT);
                            r.status.setText("пинг: " + ms + " мс");
                            clearFailed(r.entry);
                        } else {
                            r.status.setTextColor(Ui.DANGER);
                            r.status.setText("пинг не прошёл: сервер не отвечает");
                            markFailed(r.entry);
                        }
                    }
                });
            }
        }, "pool-ping-one").start();
    }

    private void measureOne(final Row r) {
        if (busy) return;
        setBusy(true);
        r.status.setTextColor(Ui.TEXT_DIM);
        r.status.setText("поднимаю ядро и качаю…");
        new Thread(new Runnable() {
            @Override public void run() {
                final Measured m = measure(r.entry, r.cfg);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (act.isFinishing()) return;
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
            refresh();
            return;
        }
        if (cancelled(m.result)) {
            r.status.setTextColor(Ui.TEXT_DIM);
            r.status.setText("проверка прервана");
            return;
        }
        r.status.setTextColor(Ui.DANGER);
        r.status.setText("не прошёл: " + m.result.reason);
        markFailed(r.entry);
    }

    private static boolean cancelled(Probe.Result r) {
        return r != null && !r.ok && r.reason != null && r.reason.contains("остановлен");
    }

    private void removeOne(Row r) {
        Pool.remove(act, net, r.entry.raw);
        clearFailed(r.entry);
        toast("Убран из рабочих: " + truncate(r.cfg.name, 34));
        refresh();
    }

    private void blacklistOne(Row r) {
        Blacklist.add(act, net, r.cfg, "убран вручную с экрана рабочих конфигов");
        Pool.remove(act, net, r.entry.raw);
        clearFailed(r.entry);
        toast("В чёрном списке на 7 дней: " + truncate(r.cfg.name, 30));
        refresh();
    }

    /**
     * Убрать из рабочих всех, кто провалил последнюю проверку.
     *
     * Раньше для этого нужно было нажать отдельную кнопку внизу. Теперь чистка идёт сама
     * после «Пропинговать все» и «Перемерить у всех»: пул — это «рабочие», и оставлять
     * в нём то, что только что не ответило, бессмысленно.
     *
     * Защита от ложного срабатывания: если не отвечает вообще ничего и при этом не
     * пингуется ни одна посторонняя цель, виновата сеть, а не конфиги, — тогда не
     * удаляем никого.
     */
    private void autoCleanFailed(final String why) {
        if (failed.isEmpty()) return;
        final List<Pool.Entry> dead = entryList(failed);
        if (dead.isEmpty()) {
            failed.clear();
            updateBlacklistButton();
            return;
        }
        final String target = net;
        status.setText("Проверяю, жива ли сеть, прежде чем удалять " + dead.size() + " конфигов…");
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean netAlive = networkAnswers();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (act.isFinishing()) return;
                        if (!netAlive) {
                            status.setText("");
                            toast("Сеть не отвечает — никого не удаляю, проверьте подключение");
                            return;
                        }
                        int n = 0;
                        for (Pool.Entry e : dead) {
                            ProxyConfig cfg = e.cfg();
                            if (cfg != null) Blacklist.add(act, target, cfg, why);
                            Pool.remove(act, target, e.raw);
                            n++;
                        }
                        failed.clear();
                        updateBlacklistButton();
                        status.setText("Удалено из рабочих: " + n);
                        toast("Удалено из рабочих: " + n + " · в чёрном списке на 7 дней");
                        refresh();
                    }
                });
            }
        }, "pool-autoclean").start();
    }

    /** Есть ли вообще интернет: пять целей TCP-пинга, синхронно. */
    private boolean networkAnswers() {
        try {
            NetChecker.PingStats st = NetChecker.pingAll(
                    Prefs.pingTargets(act), Prefs.pingTimeoutMs(act));
            return st.ok > 0;
        } catch (Throwable t) {
            return true;    // не смогли проверить — не мешаем чистке
        }
    }

    // ------------------------------------------------------------------ массовые действия

    private void pingAll() {
        if (busy) return;
        final List<Pool.Entry> entries = Pool.load(act, net);
        if (entries.isEmpty()) {
            toast("Пул пуст");
            return;
        }
        setBusy(true);
        status.setText("Пингую " + entries.size() + " конфигов…");
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
                        Prefs.pingThreads(act), Prefs.pingTimeoutMs(act),
                        new ConfigTester.Listener() {
                            @Override public void onProgress(int done, int total, int ok, int error) {
                                final String text = "Пинг " + done + "/" + total
                                        + " → живых " + ok + ", мёртвых " + error;
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        if (!act.isFinishing()) status.setText(text);
                                    }
                                });
                            }
                        });
                final Set<String> aliveKeys = new LinkedHashSet<>();
                for (ProxyConfig c : out.alive) {
                    String k = ConfigRepository.canonicalKey(c.raw);
                    if (k != null) aliveKeys.add(k);
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (act.isFinishing()) return;
                        if (out.cancelled) {
                            setBusy(false);
                            status.setText("Пинг прерван");
                            toast("Пинг прерван — никого не трогаю");
                            return;
                        }
                        failed.clear();
                        for (Map.Entry<String, Pool.Entry> e : byKey.entrySet()) {
                            Row r = rows.get(e.getKey());
                            boolean alive = aliveKeys.contains(e.getKey());
                            if (r != null) {
                                r.status.setTextColor(alive ? Ui.ACCENT : Ui.DANGER);
                                r.status.setText(alive
                                        ? "пинг: " + e.getValue().cfg().latency + " мс"
                                        : "пинг не прошёл: сервер не отвечает");
                            }
                            if (!alive) failed.add(e.getKey());
                        }
                        setBusy(false);
                        updateBlacklistButton();
                        updateSummary(Pool.count(act, net));
                        toast("Пинг: живых " + out.alive.size() + " из " + out.total);
                        // Всё, что не ответило, уходит из рабочих сразу: держать в «рабочих»
                        // заведомо мёртвое — значит снова и снова натыкаться на него.
                        autoCleanFailed("не ответил на пинг");
                    }
                });
            }
        }, "pool-ping-all").start();
    }

    private void confirmMeasureAll() {
        if (busy) return;
        final int n = Pool.count(act, net);
        if (n == 0) {
            toast("Пул пуст");
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("Перемерить все?")
                .setMessage("Поднимем ядро для каждого из " + n + " конфигов пула «"
                        + Net.label(net) + "» и скачаем через него файл — это то же, что "
                        + "делает «Собрать пул», только без обновления списков. Занимает "
                        + "примерно " + Math.max(1, n / 4) + "–" + (n / 2 + 2)
                        + " минут и тратит трафик.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Перемерить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { measureAll(); }
                })
                .show();
    }

    private void measureAll() {
        final List<Pool.Entry> entries = Pool.load(act, net);
        if (entries.isEmpty()) return;
        setBusy(true);
        final int total = entries.size();
        final int lanes = Math.max(1, Math.min(4, Prefs.speedLanes(act)));
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
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (act.isFinishing()) return;
                            status.setText("Замер " + d + "/" + total
                                    + " · рабочих " + okCount.get());
                            String key = ConfigRepository.canonicalKey(entry.raw);
                            Row r = key == null ? null : rows.get(key);
                            if (r != null) {
                                if (m.result.ok && m.result.kbps > 0) {
                                    r.status.setTextColor(Ui.ACCENT);
                                    r.status.setText("OK · " + okText(m)
                                            + (m.dpi.isEmpty() ? " · напрямую" : " · через DPI"));
                                } else if (cancelled(m.result)) {
                                    r.status.setTextColor(Ui.TEXT_DIM);
                                    r.status.setText("проверка прервана");
                                } else {
                                    r.status.setTextColor(Ui.DANGER);
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
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (act.isFinishing()) return;
                        setBusy(false);
                        toast("Замер: рабочих " + okCount.get() + " из " + total);
                        refresh();
                        // «Если все нерабочие — удалять все»: провалившиеся проверку
                        // убираются из пула одной операцией, без отдельной кнопки.
                        autoCleanFailed("не прошёл перемер скорости");
                    }
                });
            }
        }, "pool-measure-all-wait").start();
    }

    private void confirmBlacklistFailed() {
        final List<Pool.Entry> entries = entryList(failed);
        if (entries.isEmpty()) {
            toast("Провалившихся нет — сначала проверьте список");
            return;
        }
        new AlertDialog.Builder(act)
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
                            if (cfg != null) Blacklist.add(act, net, cfg,
                                    "провалил проверку на экране рабочих конфигов");
                            Pool.remove(act, net, e.raw);
                            n++;
                        }
                        failed.clear();
                        toast("В чёрный список: " + n + ", из рабочих убраны");
                        refresh();
                    }
                })
                .show();
    }

    private List<Pool.Entry> entryList(Set<String> keys) {
        List<Pool.Entry> out = new ArrayList<>();
        for (Pool.Entry e : Pool.load(act, net)) {
            String k = ConfigRepository.canonicalKey(e.raw);
            if (k != null && keys.contains(k)) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ замер

    private static final class Measured {
        Probe.Result result;
        String dpi = "";

        Measured(Probe.Result result, String dpi) {
            this.result = result;
            this.dpi = dpi == null ? "" : dpi;
        }
    }

    private static String okText(Measured m) {
        boolean onlyAlive = m.result.speed != null && !m.result.speed.ok() && m.result.speed.alive();
        return onlyAlive ? "связность есть, скорость не измерена"
                : Prefs.speedText(m.result.kbps);
    }

    /**
     * Замер конфига тем же порядком, что и подключение: сначала со «своей» стратегией,
     * если конфиг ею замерен, иначе напрямую; вторая попытка одна. Стратегия по умолчанию
     * берётся для ТОЙ сети, в пул которой пишем.
     */
    private Measured measure(Pool.Entry entry, ProxyConfig cfg) {
        int gate = Prefs.gateTimeoutMs(act);
        if (entry.viaDpi()) {
            Probe.Result r = Probe.measureConfig(act, cfg, gate, entry.dpi);
            if (r.ok) return new Measured(r, entry.dpi);
            Probe.Result direct = Probe.measureConfig(act, cfg, gate, null);
            if (direct.ok) {
                Pool.remember(act, net, cfg.raw, direct.kbps, cfg.latency, "");
                return new Measured(direct, "");
            }
            return new Measured(direct, "");
        }
        Probe.Result r = Probe.measureConfig(act, cfg, gate, null);
        if (r.ok || !Prefs.desyncLine(act)) {
            if (r.ok) Pool.remember(act, net, cfg.raw, r.kbps, cfg.latency, "");
            return new Measured(r, "");
        }
        String strategy = Prefs.dpiStrategyFor(act, net);
        Probe.Result viaDpi = Probe.measureConfig(act, cfg, gate, strategy);
        if (viaDpi.ok) {
            Pool.remember(act, net, cfg.raw, viaDpi.kbps, cfg.latency, strategy);
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
        paste.setEnabled(!value);
        fromFile.setEnabled(!value);
        pingAll.setEnabled(!value);
        measureAll.setEnabled(!value);
        updateBlacklistButton();
        updateSummary(Pool.count(act, net));
    }

    private void toast(String text) {
        Toast.makeText(act, text, Toast.LENGTH_SHORT).show();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** «05.11 21:40» — время сборки пула. */
    private static String stamp(long at) {
        if (at <= 0) return "—";
        return new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(at));
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
