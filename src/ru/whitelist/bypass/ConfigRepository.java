package ru.whitelist.bypass;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Скачивание и хранение списков конфигов.
 *
 * Единственная задача: «обновить все конфиги со всех источников».
 * - источники обходятся целиком (без партий и лимитов «новых за раз»);
 * - дедупликация по каноническому ключу (схема|host|port|user);
 * - если напрямую не вышло — то же самое через jsDelivr;
 * - если и это не вышло — через Tor (единственное место, где используется Tor).
 *
 * Никаких «мёртвых списков», меток «грузит», «Вершины» и фоновых докачек —
 * что скачали, то и лежит в configs.txt.
 */
public final class ConfigRepository {
    private ConfigRepository() {}

    private static final String FILE = "configs.txt";
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    /**
     * Лимиты прямого канала. В этих списках — килобайты, а не мегабайты, поэтому
     * двух секунд на источник хватает с запасом; всё, что медленнее, просто не наш случай.
     * Лимит считается на каждый источник отдельно — общего «на всё про всё» нет.
     */
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 2000;
    private static final int SOURCE_BUDGET_MS = 2000;
    /**
     * Лимиты для Tor: он медленный по определению, но это резервный канал, и здесь
     * времени даём заметно больше — иначе резерв никогда не сработает.
     */
    private static final int TOR_CONNECT_TIMEOUT_MS = 20000;
    private static final int TOR_READ_TIMEOUT_MS = 25000;
    private static final int TOR_SOURCE_BUDGET_MS = 40000;
    /** Потолок хранилища: больше 2 000 строк телефону не нужно. */
    private static final int MAX_LINES = 2000;

    /** Встроенный резерв — только чтобы «Подключить» работало сразу после установки. */
    private static final String[] EMBEDDED = {
            "fallback/vless_black_mobile.txt",
            "fallback/vless_white_reality_mobile.txt",
            "fallback/wlunlocker_all.txt"
    };

    private static volatile boolean cancelRequested = false;

    public static void requestCancel() { cancelRequested = true; }
    public static void clearCancel() { cancelRequested = false; }
    public static boolean isCancelled() { return cancelRequested; }

    public interface Progress {
        void onMessage(String message);
    }

    public static final class UpdateResult {
        public int sourcesTried;
        public int sourcesOk;
        /** Источники, которых больше нет (HTTP 404/410/451): ни зеркало, ни Tor не помогут. */
        public int sourcesMissing;
        public int parsed;
        public int skipped;
        public int stored;
        public long bytes;
        public long elapsedMs;
        public boolean timedOut;
        public boolean viaTor;
        public final List<String> messages = new ArrayList<>();

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("источников OK ").append(sourcesOk).append('/').append(sourcesTried);
            if (sourcesMissing > 0) sb.append(", недоступно ").append(sourcesMissing);
            sb.append(", конфигов ").append(parsed);
            if (viaTor) sb.append(" (через Tor)");
            sb.append(", в хранилище ").append(stored);
            if (bytes > 0) sb.append(", скачано ").append(bytes / 1024).append(" КБ за ")
                    .append(Math.max(1, elapsedMs / 1000)).append(" с");
            if (timedOut) sb.append(" — упёрлись в лимит времени");
            return sb.toString();
        }
    }

    /** Ошибка источника: HTTP-код (0 — сеть/таймаут) и признак «источника больше нет». */
    public static final class SourceError extends IOException {
        public final int httpCode;
        public final boolean missing;

        SourceError(String message, int httpCode) {
            super(message);
            this.httpCode = httpCode;
            this.missing = httpCode == 404 || httpCode == 410 || httpCode == 451;
        }
    }

    // ------------------------------------------------------------------ чтение

    private static File configsFile(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static List<String> readAsset(Context c, String name) {
        List<String> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Скачанные конфиги (только поддерживаемые ядром). */
    public static List<ProxyConfig> downloaded(Context c) {
        List<ProxyConfig> out = new ArrayList<>();
        for (String line : readLines(configsFile(c))) {
            ProxyConfig p = ProxyConfig.parse(line, "скачано", false);
            if (p != null && p.supported) out.add(p);
        }
        return out;
    }

    /** Встроенный резерв (можно отключить в настройках). */
    public static List<ProxyConfig> embedded(Context c) {
        List<ProxyConfig> out = new ArrayList<>();
        if (!Prefs.embeddedEnabled(c)) return out;
        for (String asset : EMBEDDED) {
            for (String line : readAsset(c, asset)) {
                ProxyConfig p = ProxyConfig.parse(line, "резерв", true);
                if (p != null && p.supported) out.add(p);
            }
        }
        return out;
    }

    /** Скачанные + резерв, без дублей. */
    public static List<ProxyConfig> all(Context c) {
        LinkedHashMap<String, ProxyConfig> map = new LinkedHashMap<>();
        for (ProxyConfig p : downloaded(c)) {
            map.put(canonicalKey(p.raw), p);
        }
        for (ProxyConfig p : embedded(c)) {
            String key = canonicalKey(p.raw);
            if (!map.containsKey(key)) map.put(key, p);
        }
        return new ArrayList<>(map.values());
    }

    public static int countDownloaded(Context c) {
        return readLines(configsFile(c)).size();
    }

    public static void clearDownloaded(Context c) {
        File f = configsFile(c);
        if (f.isFile()) f.delete();
    }

    // ------------------------------------------------------------------ ключ дедупликации

    /** Схема | host | port | userinfo — #имя, порядок параметров и регистр не важны. */
    public static String canonicalKey(String raw) {
        if (raw == null) return "raw|";
        String t = raw.trim();
        ProxyConfig p = ProxyConfig.parse(t, "", false);
        if (p != null && p.host != null && !p.host.isEmpty() && p.port > 0) {
            String user;
            if ("ss".equals(p.scheme) || "shadowsocks".equals(p.scheme)) {
                String ui = p.ssUserInfo();
                user = ui == null ? "" : normalize(ui).toLowerCase();
            } else if ("vmess".equals(p.scheme)) {
                user = vmessId(t);
            } else {
                String ui = p.userInfo();
                user = ui == null ? "" : ui.trim();
            }
            return p.scheme.toLowerCase() + "|" + p.host.toLowerCase() + "|" + p.port + "|" + user;
        }
        return "raw|" + normalize(t);
    }

    private static String vmessId(String raw) {
        try {
            String b64 = raw.substring(raw.indexOf("://") + 3);
            int hash = b64.indexOf('#');
            if (hash >= 0) b64 = b64.substring(0, hash);
            String s = b64.trim().replace('-', '+').replace('_', '/');
            int pad = s.length() % 4;
            if (pad > 0) s = s + "=====".substring(0, pad);
            String json = new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT),
                    StandardCharsets.UTF_8);
            return new org.json.JSONObject(json).optString("id");
        } catch (Exception e) {
            return normalize(raw);
        }
    }

    private static String normalize(String t) {
        return t.trim().replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------------ обновление всех списков

    /**
     * Полное обновление: обойти ВСЕ источники, забрать ВСЕ поддерживаемые строки,
     * дедуплицировать, заменить configs.txt.
     */
    public static UpdateResult updateAll(Context c, Progress progress) {
        UpdateResult res = new UpdateResult();
        List<String> sources = Prefs.sources(c);
        if (sources.isEmpty()) {
            res.messages.add("Список источников пуст (Настройки → Источники)");
            res.stored = countDownloaded(c);
            return res;
        }
        Map<String, String> unique = new LinkedHashMap<>();
        List<String> retryViaTor = new ArrayList<>();
        long started = System.currentTimeMillis();
        for (String src : sources) {
            if (isCancelled()) break;
            res.sourcesTried++;
            if (progress != null) progress.onMessage("Скачиваю: " + shortSource(src));
            long[] bytes = new long[1];
            long t0 = System.currentTimeMillis();
            try {
                List<String> lines = fetch(src, null, bytes);
                res.sourcesOk++;
                res.bytes += bytes[0];
                int added = collect(unique, lines, res);
                if (progress != null) {
                    progress.onMessage(shortSource(src) + ": +" + added
                            + " (всего " + unique.size() + "), " + (bytes[0] / 1024) + " КБ за "
                            + Math.max(1, (System.currentTimeMillis() - t0) / 1000) + " с");
                }
            } catch (SourceError e) {
                if (e.missing) {
                    res.sourcesMissing++;
                    res.messages.add("Пропускаю " + shortSource(src) + ": " + e.getMessage());
                    continue;   // репозитория нет — ни зеркало, ни Tor не помогут
                }
                res.messages.add("Не ответил " + shortSource(src) + ": " + e.getMessage());
                retryViaTor.add(src);
            } catch (Exception e) {
                res.messages.add("Не ответил " + shortSource(src) + ": " + e.getMessage());
                retryViaTor.add(src);
            }
            if (unique.size() >= MAX_LINES) break;
        }
        res.elapsedMs = System.currentTimeMillis() - started;

        // Прямой путь не дал ничего → Tor как канал для скачивания списков.
        if (unique.isEmpty() && !isCancelled() && !retryViaTor.isEmpty()) {
            if (progress != null) progress.onMessage("Прямые источники недоступны — поднимаю Tor…");
            res.viaTor = true;
            if (!TorEngine.ensureStarted(c, 150_000)) {
                res.messages.add("Tor не поднялся (" + TorEngine.lastError() + ") — списки не обновлены");
            } else {
                try {
                    for (String src : retryViaTor) {
                        if (isCancelled()) break;
                        try {
                            long[] bytes = new long[1];
                            List<String> lines = fetch(src, torProxy(), bytes);
                            res.sourcesOk++;
                            res.bytes += bytes[0];
                            collect(unique, lines, res);
                        } catch (Exception e) {
                            res.messages.add("Через Tor не ответил: " + shortSource(src));
                        }
                        if (unique.size() >= MAX_LINES) break;
                    }
                } finally {
                    TorEngine.stop(c);
                }
            }
        }

        if (!unique.isEmpty()) {
            writeAll(c, new ArrayList<>(unique.values()));
            res.stored = unique.size();
        } else {
            res.stored = countDownloaded(c);
            res.messages.add("Новых строк не получено — оставлено прежнее хранилище");
        }
        return res;
    }

    /** Ссылка на конфиг внутри строки: подходит и для JSON, и для markdown/HTML. */
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile(
            "(?:vless|vmess|trojan|ss|ssr|hysteria2|hy2|tuic)://[^\\s\"'<>\\,)]+",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static int collect(Map<String, String> unique, List<String> lines, UpdateResult res) {
        int added = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;
            added += addOne(unique, t, res);
            if (unique.size() >= MAX_LINES) break;
            // строка может быть не конфигом, а JSON/разметкой: вытаскиваем ссылки из неё
            java.util.regex.Matcher m = TOKEN.matcher(t);
            while (m.find()) {
                added += addOne(unique, m.group(), res);
                if (unique.size() >= MAX_LINES) break;
            }
            if (unique.size() >= MAX_LINES) break;
        }
        return added;
    }

    private static int addOne(Map<String, String> unique, String raw, UpdateResult res) {
        if (unique.size() >= MAX_LINES) return 0;
        ProxyConfig p = ProxyConfig.parse(raw, "источник", false);
        if (p == null || p.host == null || p.host.isEmpty() || p.port <= 0) return 0;
        if (!p.supported) { res.skipped++; return 0; }
        String key = canonicalKey(raw);
        if (unique.containsKey(key)) return 0;
        unique.put(key, raw);
        res.parsed++;
        return 1;
    }

    private static void writeAll(Context c, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        try (FileOutputStream fos = new FileOutputStream(configsFile(c), false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ сеть

    private static Proxy torProxy() {
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", TorEngine.SOCKS_PORT));
    }

    /** Скачать список строк. Сначала напрямую, потом через jsDelivr-зеркало. */
    private static List<String> fetch(String urlStr, Proxy proxy) throws IOException {
        return fetch(urlStr, proxy, null);
    }

    /**
     * То же, но с учётом байтов и «умным» пропуском:
     * если репозитория больше нет (404/410) — зеркало не пробуем, это только трата времени.
     */
    private static List<String> fetch(String urlStr, Proxy proxy, long[] bytesOut) throws IOException {
        IOException last = null;
        for (String candidate : candidates(urlStr)) {
            try {
                return doFetch(candidate, proxy, bytesOut);
            } catch (SourceError e) {
                if (e.missing) throw e;
                last = e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("нет адреса для " + urlStr);
    }

    /** jsDelivr — единственное зеркало, которое реально отдаёт эти репозитории. */
    private static List<String> candidates(String urlStr) {
        List<String> out = new ArrayList<>();
        out.add(urlStr);
        String marker = "raw.githubusercontent.com/";
        int i = urlStr.indexOf(marker);
        if (i > 0) {
            String path = urlStr.substring(i + marker.length());
            String[] parts = path.split("/", -1);
            if (parts.length >= 4) {
                String owner = parts[0];
                String repo = parts[1];
                String branch;
                int idx;
                if (parts.length >= 6 && "refs".equals(parts[2]) && "heads".equals(parts[3])) {
                    branch = parts[4];
                    idx = 5;
                } else {
                    branch = parts[2];
                    idx = 3;
                }
                StringBuilder file = new StringBuilder();
                for (int j = idx; j < parts.length; j++) {
                    if (j > idx) file.append('/');
                    file.append(parts[j]);
                }
                if (file.length() > 0) {
                    out.add("https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch + "/" + file);
                }
            }
        }
        return out;
    }

    private static List<String> doFetch(String urlStr, Proxy proxy, long[] bytesOut) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) (proxy != null
                ? new URL(urlStr).openConnection(proxy)
                : new URL(urlStr).openConnection());
        boolean viaTor = proxy != null;
        int connectTimeout = viaTor ? TOR_CONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS;
        int readTimeout = viaTor ? TOR_READ_TIMEOUT_MS : READ_TIMEOUT_MS;
        long budget = viaTor ? TOR_SOURCE_BUDGET_MS : SOURCE_BUDGET_MS;
        try {
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WhitelistBypass/0.1.7");
            conn.setRequestProperty("Accept", "text/plain, */*");

            int code;
            try {
                code = conn.getResponseCode();
            } catch (java.net.SocketTimeoutException e) {
                throw new SourceError("нет ответа за " + (connectTimeout / 1000) + " с", 0);
            }
            if (code == 404 || code == 410 || code == 451) {
                throw new SourceError("HTTP " + code + " — источника больше нет", code);
            }
            if (code < 200 || code >= 400) {
                throw new SourceError("HTTP " + code, code);
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + budget;
            try (InputStream in = conn.getInputStream()) {
                byte[] chunk = new byte[16384];
                int n;
                int total = 0;
                while (total < MAX_DOWNLOAD_BYTES) {
                    if (isCancelled()) throw new SourceError("загрузка остановлена", 0);
                    try {
                        n = in.read(chunk);
                    } catch (java.net.SocketTimeoutException e) {
                        if (total == 0) {
                            throw new SourceError("данные не пошли за " + (readTimeout / 1000) + " с", 0);
                        }
                        throw new SourceError("данные оборвались на " + (total / 1024)
                                + " КБ (" + (readTimeout / 1000) + " с тишины)", 0);
                    }
                    if (n < 0) break;
                    buf.write(chunk, 0, n);
                    total += n;
                    if (System.currentTimeMillis() > deadline) {
                        // медленный, но живой источник: забираем что успели
                        break;
                    }
                }
                if (bytesOut != null) bytesOut[0] = total;
                if (total == 0) throw new SourceError("пустой ответ (0 байт)", 0);
            }

            List<String> out = new ArrayList<>();
            for (String line : new String(buf.toByteArray(), StandardCharsets.UTF_8).split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ ленивая подгрузка

    /**
     * Скачать ОДИН источник (для ленивой подгрузки в быстром поиске: тянем по файлу,
     * а не все сразу). viaTor — через локальный Tor SOCKS (его поднимает вызывающий).
     */
    public static List<String> fetchSource(String urlStr, boolean viaTor) throws IOException {
        return fetch(urlStr, viaTor ? torProxy() : null);
    }

    /** Поднять Tor (только как канал скачивания). true — SOCKS готов. */
    public static boolean startTor(Context c) {
        return TorEngine.ensureStarted(c, 150_000);
    }

    public static void stopTor(Context c) {
        TorEngine.stop(c);
    }

    /**
     * Добавить новые строки в хранилище, не теряя уже сохранённые.
     * Возвращает, сколько НОВЫХ конфигов добавилось (дубли по каноническому ключу не в счёт).
     */
    public static synchronized int appendNew(Context c, List<String> lines) {
        if (lines == null || lines.isEmpty()) return 0;
        Map<String, String> unique = new LinkedHashMap<>();
        for (String line : readLines(configsFile(c))) {
            unique.put(canonicalKey(line), line);
        }
        int before = unique.size();
        UpdateResult counter = new UpdateResult();
        collect(unique, lines, counter);
        int added = unique.size() - before;
        if (added > 0) {
            writeAll(c, new ArrayList<>(unique.values()));
        }
        return added;
    }

    public static String shortSource(String url) {
        try {
            URL u = new URL(url);
            String path = u.getPath();
            int slash = path.lastIndexOf('/');
            return u.getHost() + (slash >= 0 ? path.substring(slash) : path);
        } catch (Exception e) {
            return url.length() > 60 ? url.substring(0, 60) + "…" : url;
        }
    }
}
