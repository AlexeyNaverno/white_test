package ru.whitelist.bypass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Встроенный браузер (экспериментальная функция): одна вкладка, поиск по умолчанию —
 * DuckDuckGo. Ходит <b>только</b> через Tor с мостами: обычного канала и VPN у него нет,
 * и это не переключатель, а правило. Если мостов нет или Tor не поднялся — страницы не
 * открываются вовсе, с объяснением причины, чтобы не было иллюзии приватности.
 *
 * Управление всё сверху: адресная строка, кнопка перехода и меню «⋮» (скачать страницу,
 * мосты, выйти). Снизу только строка состояния — страница не залезает под камеру и
 * системную навигацию.
 *
 * Страница сохраняется как MHTML — один файл, внутри и разметка, и картинки: это делает
 * сам WebView (saveWebArchive), поэтому лишнего кода нет.
 */
public class BrowserActivity extends Activity {

    private static final int BG = Ui.BG;
    private static final int TEXT = Ui.TEXT;
    private static final int TEXT_DIM = Ui.TEXT_DIM;
    private static final int ACCENT = Ui.ACCENT;
    /**
     * Поиск идёт GET-запросом: только такие запросы можно повторить внутри Tor.
     * У самого сайта duckduckgo.com форма отправляется POST — она в этом браузере
     * не работает, поэтому стартовая страница своя, со своей GET-формой.
     */
    private static final String SEARCH_BASE = "https://html.duckduckgo.com/html/";
    private static final String SEARCH = SEARCH_BASE + "?q=";

    private static final int MENU_SAVE = 1;
    private static final int MENU_BRIDGES = 2;
    private static final int MENU_EXIT = 3;
    /** Сколько переадресаций проходим сами (WebView не отдаёт нам 3xx-ответы). */
    private static final int MAX_REDIRECTS = 5;
    private static final String START_URL = "about:start";

    private WebView web;
    private EditText urlBar;
    private TextView status;

    /** true — Tor с мостами поднят и через него можно ходить. */
    private volatile boolean torReady;
    private volatile boolean torBusy;
    private volatile String blockReason = "Tor ещё не поднят";

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
        urlBar.setHint("адрес или поиск (DuckDuckGo, только Tor)");
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
        ws.setJavaScriptEnabled(true);          // только внутри Tor; запросы всё равно перехватываем
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setSupportMultipleWindows(false);
        ws.setUserAgentString(ws.getUserAgentString() + " WhitelistBypass/0.1.12");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (url != null && !url.startsWith("about:")) urlBar.setText(url);
                if (!torReady) status.setText("Tor не поднят — страницы не открываются");
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (url != null && !url.startsWith("about:")) urlBar.setText(url);
                if (torReady) status.setText("Готово · через Tor с мостами");
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Одна вкладка и только http(s): чужие схемы (intent://, market://) не пускаем,
                // чтобы не выйти из Tor наружу.
                return url == null || !(url.startsWith("http://") || url.startsWith("https://"));
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                if (url.startsWith("about:")) return null;
                return fetchThroughTor(request);
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

        if (BypassVpnService.isRunning()) {
            // «Никогда через VPN»: пока туннель поднят, весь трафик приложения идёт в него.
            askToStopTunnel();
        } else {
            startTor();
        }
    }

    /**
     * Туннель заворачивает весь трафик приложения, включая Tor, поэтому честный выбор один:
     * остановить туннель или не открывать браузер.
     */
    private void askToStopTunnel() {
        status.setText("Туннель включён — сначала его нужно остановить");
        web.loadDataWithBaseURL(null, infoPage("Туннель включён",
                "Браузер ходит только через Tor с мостами и никогда через VPN.<br>"
                        + "Остановите туннель, чтобы страницы шли через Tor."),
                "text/html", "utf-8", null);
        new AlertDialog.Builder(this)
                .setTitle("Остановить туннель?")
                .setMessage("Пока VPN включён, браузер ходил бы через него. Остановить туннель "
                        + "и открыть страницы через Tor?")
                .setPositiveButton("Остановить туннель", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        stopTunnelThenTor();
                    }
                })
                .setNegativeButton("Закрыть браузер", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void stopTunnelThenTor() {
        status.setText("Останавливаю туннель…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    startService(new Intent(BrowserActivity.this, BypassVpnService.class)
                            .setAction(BypassVpnService.ACTION_STOP));
                } catch (Exception ignored) {
                }
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline && BypassVpnService.isRunning()) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { startTor(); }
                });
            }
        }, "browser-stop-vpn").start();
    }

    // ------------------------------------------------------------------ Tor (всегда и только с мостами)

    private void startTor() {
        if (torBusy || torReady) return;
        if (TorEngine.candidateBridges(this).isEmpty()) {
            blockReason = "нет мостов: добавьте их в настройках Tor";
            status.setText("Мостов нет — браузер не открывает страницы");
            web.loadDataWithBaseURL(null, infoPage("Нет мостов Tor",
                    "Браузер работает только через Tor с мостами, а мостов нет.<br>"
                            + "Настройки → Tor: впишите мосты или нажмите «Проверить мосты "
                            + "и источники через Tor» — приложение выберет самые быстрые."),
                    "text/html", "utf-8", null);
            return;
        }
        torBusy = true;
        torReady = false;
        status.setText("Поднимаю Tor с мостами…");
        web.loadDataWithBaseURL(null, infoPage("Поднимаю Tor",
                "Страницы откроются, когда Tor поднимется с мостом. Это занимает до минуты."),
                "text/html", "utf-8", null);
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = TorEngine.restart(BrowserActivity.this, 120_000);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        torBusy = false;
                        if (!ok) {
                            blockReason = "Tor не поднялся: " + TorEngine.lastError();
                            status.setText(blockReason);
                            web.loadDataWithBaseURL(null, infoPage("Tor не поднялся",
                                    "Причина: " + TorEngine.lastError()
                                            + "<br>Браузер не открывает страницы напрямую — "
                                            + "проверьте мосты в настройках."),
                                    "text/html", "utf-8", null);
                            return;
                        }
                        torReady = true;
                        status.setText("Tor с мостом работает — страницы только через него");
                        status.setTextColor(ACCENT);
                        showStartPage();
                    }
                });
            }
        }, "browser-tor").start();
    }

    /** Своя стартовая страница: GET-форма поиска и ссылки на движки с GET-формами. */
    private void showStartPage() {
        web.loadDataWithBaseURL(SEARCH_BASE, startPage(), "text/html", "utf-8", null);
    }

    private String startPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{margin:0;padding:22px;background:#000;color:#E8EEF5;"
                + "font-family:sans-serif}h1{font-size:19px;color:#3DDC84;margin:0 0 14px}"
                + "form{display:flex;gap:8px}input{flex:1;padding:11px;font-size:15px;"
                + "border-radius:8px;border:1px solid #222C38;background:#0A0E13;color:#E8EEF5}"
                + "button{padding:11px 16px;font-size:15px;border-radius:8px;border:0;"
                + "background:#3DDC84;color:#06210F;font-weight:bold}"
                + "a{display:inline-block;margin:12px 8px 0 0;color:#8CA3B8;text-decoration:none;"
                + "border:1px solid #222C38;border-radius:8px;padding:8px 11px;font-size:13px}"
                + ".t{margin-top:20px;color:#5C7086;font-size:12px;line-height:1.5}"
                + "</style></head><body>"
                + "<h1>Поиск через Tor</h1>"
                + "<form action=\"" + SEARCH_BASE + "\" method=\"get\">"
                + "<input name=\"q\" placeholder=\"что искать\" autocomplete=\"off\">"
                + "<button type=\"submit\">Найти</button></form>"
                + "<div><a href=\"https://www.mojeek.com/\">Mojeek</a>"
                + "<a href=\"https://www.bing.com/\">Bing</a>"
                + "<a href=\"https://search.brave.com/\">Brave</a></div>"
                + "<div class=\"t\">Поиск здесь идёт GET-запросом — такие запросы проходят "
                + "через Tor. Формы с POST (в том числе поиск на самом duckduckgo.com) в этом "
                + "браузере не работают: тело POST перехватить нельзя, а отправлять напрямую "
                + "нельзя тем более.</div></body></html>";
    }

    /** Меню «⋮»: сюда убраны все кнопки, чтобы страница занимала весь экран. */
    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, MENU_SAVE, 1, "Скачать страницу");
        menu.getMenu().add(Menu.NONE, MENU_BRIDGES, 2, "Мосты (настройки Tor)");
        menu.getMenu().add(Menu.NONE, MENU_EXIT, 3, "Выйти в настройки");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
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
        if (!torReady) {
            toast("Страницы только через Tor: " + blockReason);
            status.setText("Только через Tor: " + blockReason);
            return;
        }
        String url;
        if (q.startsWith("http://") || q.startsWith("https://")) {
            url = q;
        } else if (q.contains(".") && !q.contains(" ")) {
            url = "https://" + q;
        } else {
            url = SEARCH + Uri.encode(q);
        }
        web.loadUrl(url);
    }

    // ------------------------------------------------------------------ запросы через Tor

    /**
     * Запрос страницы через Tor.
     *
     * Своего прокси у WebView нет (ProxyController — системный API), поэтому каждый запрос
     * забираем сами через SOCKS-порт Tor и отдаём WebView готовым ответом: внутри Tor
     * оказываются и разметка, и картинки, и стили. Если Tor не готов или запрос не GET —
     * страница с объяснением, но никогда не прямой запрос: иначе был бы обычный интернет.
     *
     * Переадресации проходим сами: WebResourceResponse не принимает коды 300–399
     * («statusCode can't be in the 300,399 range»), а поиск без редиректов не работает.
     */
    private WebResourceResponse fetchThroughTor(WebResourceRequest request) {
        String url = request.getUrl() == null ? null : request.getUrl().toString();
        if (url == null) return blocked("Пустой адрес");
        if (!torReady) return blocked("Tor не поднят: " + blockReason);
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method)) {
            // Тело POST WebView не отдаёт, а отправлять его напрямую нельзя — значит, нельзя и никак.
            return blocked("Запрос " + method + " через Tor не повторяется: тело запроса "
                    + "WebView не отдаёт, а отправлять его напрямую нельзя — это был бы обычный "
                    + "интернет. Введите запрос в адресной строке сверху: она всегда шлёт GET, "
                    + "и поиск работает.");
        }
        try {
            return fetch(url, MAX_REDIRECTS);
        } catch (Exception e) {
            return blocked("Tor: " + e.getMessage());
        }
    }

    /** Один запрос через SOCKS Tor; 3xx разворачиваем сами. */
    private WebResourceResponse fetch(String url, int hops) throws Exception {
        HttpURLConnection conn = null;
        try {
            Proxy socks = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1", TorEngine.SOCKS_PORT));
            conn = (HttpURLConnection) new URL(url).openConnection(socks);
            conn.setConnectTimeout(25000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) WhitelistBypass/0.1.12");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            int code = conn.getResponseCode();

            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) {
                    return blocked("Tor: переадресация без адреса (HTTP " + code + ")");
                }
                if (hops <= 0) return blocked("Tor: больше " + MAX_REDIRECTS + " переадресаций подряд");
                String next = new URL(new URL(url), location).toString();
                if (!next.startsWith("http://") && !next.startsWith("https://")) {
                    return blocked("Tor: переадресация на неподдерживаемый адрес");
                }
                return fetch(next, hops - 1);
            }

            Map<String, String> headers = new HashMap<>();
            String type = conn.getContentType();
            if (type != null) headers.put("Content-Type", type);
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
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Ответ-заглушка: объяснение вместо страницы. Никогда не уходит в обычный интернет. */
    private WebResourceResponse blocked(String reason) {
        return new WebResourceResponse("text/html", "utf-8", 200, "OK",
                new HashMap<String, String>(),
                new ByteArrayInputStream(infoPage("Страница не открыта", reason)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private String infoPage(String title, String text) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:24px;background:#000000;color:#E8EEF5;"
                + "font-family:sans-serif;font-size:15px;line-height:1.45\">"
                + "<div style=\"font-size:18px;margin-bottom:10px;color:#3DDC84\">" + title + "</div>"
                + "<div style=\"color:#8CA3B8\">" + text + "</div>"
                + "<div style=\"margin-top:18px;color:#5C7086;font-size:12px\">"
                + "Браузер работает только через Tor с мостами.</div>"
                + "</body></html>";
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
        b.setBackgroundColor(Ui.SURFACE_2);
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
        // браузер живёт только вместе с Tor: ушли — Tor гасим, чтобы не держать батарею
        try {
            TorEngine.stop(this);
        } catch (Throwable ignored) {
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
