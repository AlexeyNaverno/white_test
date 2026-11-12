package ru.whitelist.bypass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * «Рабочие конфиги» отдельным экраном.
 *
 * Вся логика пула переехала в {@link PoolPanel}: тот же блок открывается первой вкладкой
 * в настройках, и дублировать четыреста строк разметки ради второго входа незачем. Здесь
 * остаётся только шапка, прокрутка и передача результатов выбора файла в панель.
 */
public class PoolActivity extends Activity {

    private PoolPanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashReporter.install(this);
        Prefs.migrate(this);
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, Ui.S4);
        root.setPadding(p, p, p, Ui.dp(this, Ui.S6));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        Ui.applyInsets(scroll);

        TextView back = Ui.iconButton(this, "←", Ui.TEXT);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(Ui.headerLeft(this, "Рабочие конфиги",
                "Подключение к конкретному серверу и проверка пула", back));

        panel = new PoolPanel(this, root);
        panel.build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (panel != null) panel.refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (panel != null && panel.handleActivityResult(requestCode, resultCode, data)) return;
        if (VpnStart.handleResult(this, requestCode, resultCode)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }
}
