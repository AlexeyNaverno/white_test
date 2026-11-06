package ru.whitelist.bypass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Встроенный браузер (экспериментальная функция): одна вкладка, поиск по умолчанию —
 * DuckDuckGo. Всё управление — сверху: адресная строка, кнопка перехода и меню «⋮»,
 * в котором лежат «через Tor», «Скачать страницу», «Мосты» и «Выйти». Снизу остаётся
 * только строка состояния, поэтому ни страница, ни кнопки не залезают под камеру и
 * системную навигацию.
 *
 * Страница сохраняется как MHTML — один файл, внутри и разметка, и картинки: это делает
 * сам WebView (saveWebArchive), поэтому лишнего кода нет.
 */
public class BrowserActivity extends Activity {

    private static final int BG = 0xFF0E141B;
    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;
    private static final int ACCENT = 0xFF3DDC84;
    private static final String HOME = "https://duckduckgo.com/";
    private static final String SEARCH = "https://duckduckgo.com/?q=";

    private static final int MENU_TOR = 1;
    private static final int MENU_SAVE = 2;
    private static final int MENU_BRIDGES = 3;
    private static final int MENU_EXIT = 4;

    private WebView web;
    private EditText urlBar;
    private TextView status;

    private boolean torOn;
    private volatile boolean torBusy;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(0, dp(2), 0, dp(2));

        // --- верхняя панель: адрес, переход, меню «⋮»
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(8), dp(6), dp(8), dp(4));
        top.setGravity(Gravity.CENTER_VERTICAL);

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextColor(TEXT);
        urlBar.setHintTextColor(TEXT_DIM);
        urlBar.setHint("адрес или поиск (DuckDuckGo)");
        urlBar.setTextSize(13);
        urlBar.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setHorizontallyScrolling(false);
        urlBar.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    go(urlBar.getText().toString());
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(urlBar, barParams);

        Button goButton = smallButton("→", ACCENT);
        goButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                go(urlBar.getText().toString());
                hideKeyboard();
            }
        });
        top.addView(goButton);

        Button menuButton = smallButton("⋮", TEXT);
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(v); }
        });
        top.addView(menuButton);
        root.addView(top);

        // --- сама страница
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setSupportMultipleWindows(false);
        ws.setUserAgentString(ws.getUserAgentString() + " WhitelistBypass/0.0.8");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (url != null) urlBar.setText(url);
                if (!torOn) status.setText("Загрузка…");
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (url != null) urlBar.setText(url);
                status.setText(torOn ? "Готово · через Tor" : "Готово");
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;   // одна вкладка: всё открываем здесь же
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!torOn) return null;                       // обычный режим — как есть
                return fetchThroughTor(request);               // экспериментальный режим
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- строка состояния (единственный элемент снизу)
        status = new TextView(this);
        status.setTextColor(TEXT_DIM);
        status.setTextSize(11);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(dp(10), dp(3), dp(10), dp(3));
        root.addView(status);

        setContentView(root);
        Ui.applyInsets(root);

        torOn = Prefs.browserTor(this);
        updateStatus();
        if (torOn) enableTor();
        web.loadUrl(HOME);
    }

    /** Меню «⋮»: сюда убраны все кнопки, чтобы страница занимала весь экран. */
    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, MENU_TOR, 1, torOn ? "Tor: включён (через мосты)" : "Tor: выключен");
        menu.getMenu().add(Menu.NONE, MENU_SAVE, 2, "Скачать страницу");
        menu.getMenu().add(Menu.NONE, MENU_BRIDGES, 3, "Мосты (настройки Tor)");
        menu.getMenu().add(Menu.NONE, MENU_EXIT, 4, "Выйти в настройки");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case MENU_TOR:
                        toggleTor();
                        return true;
                    case MENU_SAVE:
                        saveCurrentPage();
                        return true;
                    case MENU_BRIDGES:
                        startActivity(new Intent(BrowserActivity.this, SettingsActivity.class));
                        return true;
                    case MENU_EXIT:
                        finish();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    /** Адрес прямо или поиск в DuckDuckGo, если это не похоже на адрес. */
    private void go(String input) {
        String q = input == null ? "" : input.trim();
        if (q.isEmpty()) return;
        String url;
        if (q.startsWith("http://") || q.startsWith("https://") || q.startsWith("file://")) {
            url = q;
        } else if (q.contains(".") && !q.contains(" ")) {
            url = "https://" + q;
        } else {
            url = SEARCH + Uri.encode(q);
        }
        web.loadUrl(url);
    }

    // ------------------------------------------------------------------ Tor

    private void toggleTor() {
        if (torBusy) return;
        if (!torOn && TorEngine.candidateBridges(this).isEmpty()) {
            status.setText("Мостов нет — добавьте их в настройках Tor");
            toast("Без мостов Tor не включается: добавьте мосты в настройках");
            return;
        }
        torOn = !torOn;
        Prefs.get(this).edit().putBoolean(Prefs.BROWSER_TOR, torOn).apply();
        updateStatus();
        if (torOn) enableTor();
        else disableTor();
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText(torOn ? "Tor включён — страницы идут через мосты" : "Tor выключен — страницы напрямую");
        status.setTextColor(torOn ? ACCENT : TEXT_DIM);
    }

    private void enableTor() {
        if (torBusy) return;
        torBusy = true;
        status.setText("Поднимаю Tor с мостами… (Настройки → Tor)");
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = TorEngine.restart(BrowserActivity.this, 120_000);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        torBusy = false;
                        if (!ok) {
                            status.setText("Tor не поднялся — страницы пойдут напрямую");
                            torOn = false;
                            Prefs.get(BrowserActivity.this).edit()
                                    .putBoolean(Prefs.BROWSER_TOR, false).apply();
                            updateStatus();
                            return;
                        }
                        status.setText("Tor работает — страницы идут через мост");
                        status.setTextColor(ACCENT);
                        web.reload();
                    }
                });
            }
        }, "browser-tor").start();
    }

    private void disableTor() {
        TorEngine.stop(this);
        updateStatus();
        web.reload();
    }

    /**
     * Запрос страницы через Tor.
     *
     * Своего прокси у WebView нет (ProxyController — системный API), поэтому каждый GET
     * забираем сами через SOCKS-порт Tor и отдаём WebView готовым ответом. Внутри Tor
     * оказываются и разметка, и картинки, и стили — то есть «через Tor» без вранья.
     * POST не повторяем: тело запроса WebView не отдаёт, такие запросы идут напрямую.
     */
    private WebResourceResponse fetchThroughTor(WebResourceRequest request) {
        String url = request.getUrl() == null ? null : request.getUrl().toString();
        if (url == null || !"GET".equalsIgnoreCase(request.getMethod())) return null;
        HttpURLConnection conn = null;
        try {
            Proxy socks = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1", TorEngine.SOCKS_PORT));
            conn = (HttpURLConnection) new URL(url).openConnection(socks);
            conn.setConnectTimeout(25000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) WhitelistBypass/0.0.8");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            Map<String, String> headers = new HashMap<>();
            String type = conn.getContentType();
            if (type != null) headers.put("Content-Type", type);
            String location = conn.getHeaderField("Location");
            if (location != null) headers.put("Location", location);
            String mime = "text/html";
            String charset = "utf-8";
            if (type != null) {
                String[] parts = type.split(";");
                if (parts[0].trim().length() > 0) mime = parts[0].trim();
                for (String part : parts) {
                    String t = part.trim();
                    if (t.toLowerCase().startsWith("charset=")) charset = t.substring(8).trim();
                }
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 8 * 1024 * 1024) break;
                }
                in.close();
            }
            String reason = code >= 400 ? "Error" : "OK";
            return new WebResourceResponse(mime, charset, code, reason, headers,
                    new ByteArrayInputStream(buf.toByteArray()));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway",
                    new HashMap<String, String>(),
                    new ByteArrayInputStream(("Tor: " + e.getMessage()).getBytes()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ сохранение страницы

    /** MHTML: разметка + картинки/стили в одном файле, отдельного перехвата не нужно. */
    private void saveCurrentPage() {
        String name = "page-" + System.currentTimeMillis() + ".mhtml";
        File dir = getExternalFilesDir("pages");
        if (dir == null) dir = new File(getFilesDir(), "pages");
        if (!dir.exists() && !dir.mkdirs()) {
            toast("Не удалось создать папку для страниц");
            return;
        }
        final File target = new File(dir, name);
        try {
            web.saveWebArchive(target.getAbsolutePath());
        } catch (Throwable t) {
            toast("Страница не сохранилась: " + t.getMessage());
            return;
        }
        if (!target.isFile() || target.length() == 0) {
            toast("Страница не сохранилась — попробуйте после полной загрузки");
            return;
        }
        long size = target.length();
        String shown = copyToDownloads(target, name);
        new AlertDialog.Builder(this)
                .setTitle("Страница сохранена")
                .setMessage((size / 1024) + " КБ\n" + shown)
                .setPositiveButton("Ок", null)
                .show();
    }

    /**
     * На Android 10+ кладём копию в «Загрузки», чтобы файл был виден в файловом менеджере.
     * На старых версиях остаётся путь в папке приложения — без лишних разрешений.
     */
    private String copyToDownloads(File src, String name) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "multipart/related");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/WhitelistBypass");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            InputStream in = new java.io.FileInputStream(src);
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            in.close();
                            return "Скопировано в Загрузки/WhitelistBypass/" + name;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "Файл: " + src.getAbsolutePath();
    }

    // ------------------------------------------------------------------ мелочи

    private Button smallButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackgroundColor(0xFF16202B);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setMinHeight(dp(34));
        b.setMinimumWidth(dp(40));
        return b;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (torOn) {
            // вышли из браузера — Tor больше не нужен, не держим батарею
            try {
                TorEngine.stop(this);
            } catch (Throwable ignored) {
            }
        }
        if (web != null) {
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }
}
