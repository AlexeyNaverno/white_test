package ru.whitelist.bypass;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Список конфигов (программный UI): выбор активного; живые — по задержке выше. */
public class ConfigsActivity extends Activity {

    private static final int SURFACE = 0xFF161F2A;
    private static final int SURFACE2 = 0xFF1E2A38;
    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;
    private static final int ACCENT = 0xFF3DDC84;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView header = new TextView(this);
        header.setTextSize(12);
        header.setTextColor(TEXT_DIM);
        header.setPadding(dp(4), dp(4), dp(4), dp(10));
        root.addView(header);

        List<ProxyConfig> configs;
        try {
            configs = ConfigRepository.activeList(this);
        } catch (Throwable t) {
            android.util.Log.e("ConfigsActivity", "activeList failed", t);
            header.setText("Ошибка чтения конфигов: " + t.getMessage());
            Toast.makeText(this, "Не удалось прочитать конфиги", Toast.LENGTH_LONG).show();
            return;
        }
        List<ProxyConfig> alive = new ArrayList<>();
        List<ProxyConfig> dead = new ArrayList<>();
        for (ProxyConfig p : configs) {
            if (p.latency >= 0 && p.supported) alive.add(p); else dead.add(p);
        }
        alive.sort(Comparator.comparingLong(a -> a.latency));
        List<ProxyConfig> ordered = new ArrayList<>(alive);
        ordered.addAll(dead);
        if (ordered.size() > 400) ordered = new ArrayList<>(ordered.subList(0, 400));
        final List<ProxyConfig> items = ordered;

        header.setText("Всего: " + configs.size() + " · живых (по кэшу): " + alive.size() + " · нажмите для выбора");

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Конфиги не найдены.\nНажмите «Обновить конфиги» на главном экране — или дождитесь встроенных.");
            empty.setTextSize(13);
            empty.setTextColor(TEXT_DIM);
            empty.setPadding(dp(8), dp(24), dp(8), dp(8));
            empty.setGravity(Gravity.CENTER);
            root.addView(empty);
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            final ProxyConfig p = items.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(i % 2 == 0 ? SURFACE : SURFACE2);
            TextView title = new TextView(this);
            title.setText(p.name);
            title.setTextSize(13);
            title.setTextColor(p.supported ? TEXT : TEXT_DIM);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = new TextView(this);
            String lat = p.latency >= 0 ? p.latency + " мс" : "не проверен";
            String support = p.supported ? "" : " · не поддерж.: " + (p.unsupportedReason != null ? p.unsupportedReason : "");
            sub.setText(p.scheme.toUpperCase() + " · " + p.host + ":" + p.port
                    + " · " + lat + (p.embedded ? " · резерв" : "") + support);
            sub.setTextSize(10);
            sub.setTextColor(TEXT_DIM);
            sub.setPadding(0, dp(2), 0, 0);
            row.addView(title);
            row.addView(sub);
            final int idx = i;
            row.setOnClickListener(v -> {
                if (!p.supported) {
                    Toast.makeText(this, p.unsupportedReason != null ? p.unsupportedReason : "не поддерживается",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Prefs.setCurrentConfig(this, p.raw, p.name, p.host, p.port);
                Toast.makeText(this, "Выбран: " + p.name, Toast.LENGTH_SHORT).show();
                finish();
            });
            root.addView(row);
        }
    }
}
