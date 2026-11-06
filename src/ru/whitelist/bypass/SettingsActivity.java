package ru.whitelist.bypass;

import android.app.Activity;
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

/**
 * Настройки. Только то, что реально влияет на работу: источники, порог скорости,
 * DPI-обход линии, Tor-мосты для скачивания, сеть и данные.
 */
public class SettingsActivity extends Activity {

    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;

    private LinearLayout root;

    private EditText editSources, editSpeedUrls, editMinSpeed, editThreads, editTimeout,
            editGateTimeout, editStrategy, editVpnDns, editMonitor, editSwitchTimeout,
            editTorBridges, editTorExtra, editPingTargets, editHealthUrls, editLanes;
    private Switch swDesync, swIpv6, swAutoSwitch, swBoot, swEmbedded;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0E141B);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        // --- скорость ---
        section("Скорость");
        editMinSpeed = numberField(String.valueOf(Prefs.minSpeedKbps(this)));
        label("Порог «рабочего» конфига, КБ/с: 128 = 128 КБ/с = 1024 кбит/с");
        root.addView(editMinSpeed);
        note("Единицы: 1 КБ/с = 8 кбит/с. Значение 128 — это про КИЛОБАЙТЫ в секунду "
                + "(≈ 1 Мбит/с), а не 128 кбит/с. Уменьшите до 64, если нужен более мягкий отбор.");

        editLanes = numberField(String.valueOf(Prefs.speedLanes(this)));
        label("Сколько ядер мерить скорость одновременно (1–4, по умолчанию 3)");
        root.addView(editLanes);

        editSpeedUrls = multiField(Prefs.join(Prefs.speedUrls(this)), 3);
        label("Файлы для замера скорости (по одному на строку)");
        root.addView(editSpeedUrls);

        editGateTimeout = numberField(String.valueOf(Prefs.gateTimeoutMs(this)));
        label("Сколько ждём на один замер, мс");
        root.addView(editGateTimeout);

        note("Скорость важнее пинга: пул сортируется по КБ/с, подключение начинается "
                + "с самого быстрого. Конфиги медленнее порога не попадают в пул вообще.");

        // --- поиск ---
        section("Поиск и пинг");
        editThreads = numberField(String.valueOf(Prefs.pingThreads(this)));
        label("Потоков пинга одновременно");
        root.addView(editThreads);
        editTimeout = numberField(String.valueOf(Prefs.pingTimeoutMs(this)));
        label("Таймаут пинга, мс (2500 по умолчанию; рабочий сервер может пинговаться до 1000 мс)");
        root.addView(editTimeout);

        editPingTargets = multiField(Prefs.join(Prefs.pingTargets(this)), 5);
        label("5 целей TCP-пинга «есть ли интернет» (host:port, по одной на строку)");
        root.addView(editPingTargets);

        editHealthUrls = multiField(Prefs.join(Prefs.healthUrls(this)), 5);
        label("5 мест проверки канала через туннель (URL, по одному на строку)");
        root.addView(editHealthUrls);

        note("Пинг не решает «рабочий/не рабочий»: он только отсекает мёртвое. Конфиг, "
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
        swDesync = switchRow("DPI-обход линии до VPN-сервера (ciadpi), если напрямую не вышло",
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
        section("Tor (только для скачивания списков)");
        note("Tor используется в единственном случае: прямые источники и jsDelivr не "
                + "отдали списки. Как режим обхода он не применяется.");
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

        TextView footer = new TextView(this);
        footer.setText("Обход Белых Списков 0.0.2\n"
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
        savePrefs();
    }

    private void savePrefs() {
        android.content.SharedPreferences.Editor e = Prefs.get(this).edit();
        e.putString(Prefs.SOURCES, normalizeLines(editSources.getText().toString()));
        e.putString(Prefs.SPEED_URLS, normalizeLines(editSpeedUrls.getText().toString()));
        e.putInt(Prefs.MIN_SPEED_KBPS, Prefs.clamp(parseInt(editMinSpeed, 128), 8, 20000));
        e.putInt(Prefs.GATE_TIMEOUT_MS, Prefs.clamp(parseInt(editGateTimeout, 6000), 2000, 20000));
        e.putInt(Prefs.PING_THREADS, Prefs.clamp(parseInt(editThreads, 60), 1, 200));
        e.putInt(Prefs.PING_TIMEOUT_MS, Prefs.clamp(parseInt(editTimeout, 2500), 300, 15000));
        e.putInt(Prefs.SPEED_LANES, Prefs.clamp(parseInt(editLanes, 3), 1, 4));
        e.putString(Prefs.PING_TARGETS, normalizeLines(editPingTargets.getText().toString()));
        e.putString(Prefs.HEALTH_URLS, normalizeLines(editHealthUrls.getText().toString()));
        e.putString(Prefs.DPI_STRATEGY, editStrategy.getText().toString().trim());
        e.putString(Prefs.VPN_DNS, editVpnDns.getText().toString().trim());
        e.putInt(Prefs.MONITOR_INTERVAL_SEC, Prefs.clamp(parseInt(editMonitor, 15), 5, 300));
        e.putInt(Prefs.SWITCH_TIMEOUT_SEC, Prefs.clamp(parseInt(editSwitchTimeout, 45), 5, 3600));
        e.putString(Prefs.TOR_BRIDGES, normalizeLines(editTorBridges.getText().toString()));
        e.putString(Prefs.TOR_EXTRA_TORRC, editTorExtra.getText().toString().trim());
        e.putBoolean(Prefs.DESYNC_LINE, swDesync.isChecked());
        e.putBoolean(Prefs.IPV6, swIpv6.isChecked());
        e.putBoolean(Prefs.AUTO_SWITCH, swAutoSwitch.isChecked());
        e.putBoolean(Prefs.BOOT_CONNECT, swBoot.isChecked());
        e.putBoolean(Prefs.EMBEDDED, swEmbedded.isChecked());
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
