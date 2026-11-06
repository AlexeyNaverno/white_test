package ru.whitelist.bypass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
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
import java.util.List;
import java.util.Map;

/**
 * Встроенный браузер (экспериментальная функция): одна вкладка, поиск по умолчанию —
 * DuckDuckGo, кнопки «Скачать страницу», «через Tor», «Мосты» и «Выйти».
 *
 * Смысл: посмотреть, что реально доступно, не поднимая VPN на всё устройство, и проверить
 * резервный путь через Tor. Страница сохраняется как MHTML — один файл, внутри и разметка,
 * и картинки: это делает сам WebView (saveWebArchive), поэтому лишнего кода нет.
 */
public class BrowserActivity extends Activity {

    private static final int BG = 0xFF0E141B;
    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF8CA3B8;
    private static final int ACCENT = 0xFF3DDC84;
    private static final String HOME = "https://duckduckgo.com/";
    private static final String SEARCH = "https://duckduckgo.com/?q=";

    private WebView web;
    private EditText urlBar;
    private TextView status;
    private Button torButton;

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

        // --- адресная строка: и переход по ссылке, и поиск
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(4));
        top.setGravity(Gravity.CENTER_VERTICAL);

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextColor(TEXT);
        urlBar.setHintTextColor(TEXT_DIM);
        urlBar.setHint("адрес или поиск (DuckDuckGo)");
        urlBar.setTextSize(14);
        urlBar.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
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
        ws.setUserAgentString(ws.getUserAgentString() + " WhitelistBypass/0.0.7");
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

        // --- нижняя панель
        status = new TextView(this);
        status.setTextColor(TEXT_DIM);
        status.setTextSize(11);
        status.setPadding(dp(10), dp(4), dp(10), dp(2));
        root.addView(status);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(2), dp(6), dp(8));

        Button savePage = smallButton("Скачать страницу", TEXT);
        savePage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveCurrentPage(); }
        });
        bar.addView(savePage, rowParams());

        torButton = smallButton("через Tor: выкл", TEXT);
        torButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleTor(); }
        });
        bar.addView(torButton, rowParams());

        Button bridges = smallButton("Мосты", TEXT);
        bridges.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // настройки Tor — там же, где мосты и их проверка
                startActivity(new android.content.Intent(BrowserActivity.this, SettingsActivity.class));
            }
        });
        bar.addView(bridges, rowParams());

        Button exit = smallButton("Выйти", 0xFFFF8A80);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(exit, rowParams());
        root.addView(bar);

        setContentView(root);

        torOn = Prefs.browserTor(this);
        updateTorButton();
        if (torOn) enableTor();
        web.loadUrl(HOME);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
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
        torOn = !torOn;
        Prefs.get(this).edit().putBoolean(Prefs.BROWSER_TOR, torOn).apply();
        updateTorButton();
        if (torOn) enableTor();
        else disableTor();
    }

    private void updateTorButton() {
        if (torButton == null) return;
        torButton.setText(torOn ? "через Tor: вкл" : "через Tor: выкл");
        torButton.setTextColor(torOn ? ACCENT : TEXT);
    }

    private void enableTor() {
        if (torBusy) return;
        torBusy = true;
        status.setText("Поднимаю Tor… (мосты: Настройки → Tor)");
        new Thread(new Runnable() {
            @Override public void run() {
                // Tor мог уже работать для скачивания списков — тогда перезапускаем:
                // нужен HTTP-туннель, а не только SOCKS.
                final boolean ok = TorEngine.restart(BrowserActivity.this, 120_000);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        torBusy = false;
                        if (!ok) {
                            status.setText("Tor не поднялся — страницы пойдут напрямую");
                            torOn = false;
                            Prefs.get(BrowserActivity.this).edit()
                                    .putBoolean(Prefs.BROWSER_TOR, false).apply();
                            updateTorButton();
                            return;
                        }
                        status.setText("Tor работает — страницы идут через мост");
                        web.reload();
                    }
                });
            }
        }, "browser-tor").start();
    }

    private void disableTor() {
        TorEngine.stop(this);
        status.setText("Tor выключен — страницы идут напрямую");
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
                    "Mozilla/5.0 (Linux; Android) WhitelistBypass/0.0.7");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            Map<String, String> headers = new HashMap<>();
            String type = conn.getContentType();
            if (type != null) headers.put("Content-Type", type);
            String location = conn.getHeaderField("Location");
            if (location != null) headers.put("Location", location);
            String contentType = conn.getContentType();
            String mime = "text/html";
            String charset = "utf-8";
            if (contentType != null) {
                String[] parts = contentType.split(";");
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
                            java.io.InputStream in = new java.io.FileInputStream(src);
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
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackgroundColor(0xFF16202B);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setMinHeight(dp(34));
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
