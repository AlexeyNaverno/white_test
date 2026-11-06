package ru.whitelist.bypass;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Хранение и обновление конфигов:
 *  - встроенный резерв (assets/fallback/*.txt, актуален на 05.11.2026), удалять нельзя;
 *  - скачанные подписки (filesDir/configs/downloaded.txt), не более batch_size за партию;
 *  - источники обходятся по кругу (чередование), GitHub проверяется пингом;
 *  - тихие обновления — только при достаточной скорости и НЕ при включённом VPN.
 */
public final class ConfigRepository {
    private ConfigRepository() {}

    private static final String DIR = "configs";
    private static final String FILE = "downloaded.txt";
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOTAL_NEW_PER_FULL_UPDATE = 300;

    private static final String[] EMBEDDED_ASSETS = {
            "fallback/vless_black_mobile.txt",
            "fallback/vless_white_reality_mobile.txt",
            "fallback/white_cidr_all.txt",
            "fallback/white_sni_all.txt",
            "fallback/ss_black_all.txt",
            "fallback/vless_black.txt",
            "fallback/wlunlocker_all.txt",
            "fallback/wlunlocker_vpn1.txt",
            "fallback/byewhitelists2.txt",
    };

    // ---------- Глобальная отмена фоновых задач (универсальная кнопка «СТОП») ----------
    private static volatile boolean cancelRequested = false;

    public static void requestCancelAll() { cancelRequested = true; }

    public static void clearCancel() { cancelRequested = false; }

    public static boolean isCancelled() { return cancelRequested; }

    public static final class UpdateResult {
        public int downloaded;
        public int keptAlive;
        public int rejectedDead;
        public int sourcesTried;
        public int sourcesOk;
        public final List<String> messages = new ArrayList<>();
        @Override public String toString() {
            return "скачано " + downloaded + ", живых " + keptAlive + ", отбраковано " + rejectedDead
                    + ", источников ОК " + sourcesOk + "/" + sourcesTried;
        }
    }

    public interface Progress {
        void onMessage(String msg);
    }

    // ---------- Чтение ----------

    private static File downloadedFile(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE);
    }

    private static List<String> readLinesFile(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
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
        try (InputStream in = c.getAssets().open(name)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static List<ProxyConfig> loadEmbedded(Context c) {
        List<ProxyConfig> out = new ArrayList<>();
        for (String asset : EMBEDDED_ASSETS) {
            for (String line : readAsset(c, asset)) {
                ProxyConfig p = ProxyConfig.parse(line, "встроенный резерв", true);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    public static List<ProxyConfig> loadDownloaded(Context c) {
        List<ProxyConfig> out = new ArrayList<>();
        for (String line : readLinesFile(downloadedFile(c))) {
            ProxyConfig p = ProxyConfig.parse(line, "загружено", false);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Полный активный список: скачанные + (опционально) встроенные, без дублей, с задержками. */
    public static List<ProxyConfig> activeList(Context c) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ProxyConfig> out = new ArrayList<>();
        LatencyStore latency = new LatencyStore(c);
        for (ProxyConfig p : loadDownloaded(c)) {
            if (seen.add(sha1(p.raw))) {
                p.latency = latency.get(p.host, p.port);
                out.add(p);
            }
        }
        if (Prefs.embeddedEnabled(c)) {
            for (ProxyConfig p : loadEmbedded(c)) {
                if (seen.add(sha1(p.raw))) {
                    p.latency = latency.get(p.host, p.port);
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** Число уникальных конфигов без пинга. */
    public static int countActive(Context c) {
        return activeList(c).size();
    }

    public static int clearDownloaded(Context c) {
        int n = readLinesFile(downloadedFile(c)).size();
        File f = downloadedFile(c);
        if (f.exists()) f.delete();
        return n;
    }

    // ---------- Скачивание ----------

    private static Set<String> knownHashes(Context c) {
        Set<String> out = new HashSet<>();
        for (String line : readLinesFile(downloadedFile(c))) out.add(sha1(line.trim()));
        for (String asset : EMBEDDED_ASSETS) {
            if (Prefs.embeddedEnabled(c)) {
                for (String line : readAsset(c, asset)) {
                    String t = line.trim();
                    if (!t.isEmpty()) out.add(configHash(t));
                }
            }
        }
        return out;
    }

    private static List<String> fetchUrl(String urlStr) throws IOException {
        return fetchUrl(urlStr, null);
    }

    private static List<String> fetchUrl(String urlStr, java.net.Proxy proxy) throws IOException {
        IOException lastError = null;
        for (String candidate : fetchCandidates(urlStr)) {
            try {
                if (!candidate.equals(urlStr)) {
                    android.util.Log.i("WhitelistBypass", "зеркало: " + candidate);
                }
                return doFetch(candidate, proxy);
            } catch (IOException e) {
                lastError = e;
                android.util.Log.i("WhitelistBypass", "не вышло (" + e.getMessage() + "): " + candidate);
            }
        }
        // последняя надежда: через поднятый Tor-туннель (только если прокси не задан явно)
        if (proxy == null && torSocksOpen()) {
            try {
                android.util.Log.i("WhitelistBypass", "тяну список через Tor…");
                return doFetch(urlStr, torProxy());
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("нет доступных источников для " + urlStr);
    }

    private static List<String> fetchCandidates(String urlStr) {
        List<String> out = new ArrayList<>();
        out.add(urlStr);
        int i = urlStr.indexOf("://");
        if (i > 0) {
            String rest = urlStr.substring(i + 3);
            if (rest.startsWith("raw.githubusercontent.com/")) {
                String path = rest.substring("raw.githubusercontent.com/".length());
                String[] parts = path.split("/", -1);
                if (parts.length >= 4) {
                    String owner = parts[0], repo = parts[1];
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
                        String f = file.toString();
                        out.add("https://gitlab.com/" + owner + "/" + repo + "/-/raw/" + branch + "/" + f);
                        out.add("https://codeberg.org/" + owner + "/" + repo + "/raw/branch/" + branch + "/" + f);
                        out.add("https://bitbucket.org/" + owner + "/" + repo + "/raw/" + branch + "/" + f);
                        out.add("https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch + "/" + f);
                    }
                }
            }
        }
        return out;
    }

    private static boolean torSocksOpen() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", TorEngine.SOCKS_PORT), 250);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static java.net.Proxy torProxy() {
        return new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress("127.0.0.1", TorEngine.SOCKS_PORT));
    }

    private static List<String> doFetch(String urlStr, java.net.Proxy proxy) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) (proxy != null
                ? new URL(urlStr).openConnection(proxy)
                : new URL(urlStr).openConnection());
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WhitelistBypass/1.0");
            conn.setRequestProperty("Accept", "text/plain, */*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) throw new IOException("HTTP " + code + " для " + urlStr);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] chunk = new byte[16384];
                int n, total = 0;
                while (total < MAX_DOWNLOAD_BYTES && (n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                    total += n;
                }
            }
            String body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String line : body.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Публичный хеш конфига (для меток/удаления/дедупликации). Канонический:
     * #имя, порядок параметров, пробелы и регистр — не считаются различием,
     * поэтому две копии одного сервера схлопываются ещё ДО первой проверки.
     */
    public static String configHash(String raw) {
        return sha1(canonicalKey(raw));
    }

    /** Канонический ключ: схема|host|port|userinfo (vmess — по полю id). */
    public static String canonicalKey(String raw) {
        if (raw == null) return "raw|";
        String t = raw.trim();
        ProxyConfig p = ProxyConfig.parse(t, "", false);
        if (p != null && p.host != null && !p.host.isEmpty() && p.port > 0) {
            String user;
            if ("vmess".equals(p.scheme)) {
                user = vmessCanonicalId(t);
            } else if ("ss".equals(p.scheme) || "shadowsocks".equals(p.scheme)) {
                String ui = p.ssUserInfo();
                user = ui == null ? "" : normalizeSpaces(ui).toLowerCase();
            } else {
                String ui = p.userInfo();
                user = ui == null ? "" : ui.trim();
            }
            return p.scheme.toLowerCase() + "|" + p.host.toLowerCase() + "|" + p.port + "|" + user;
        }
        return "raw|" + normalizeSpaces(t);
    }

    private static String vmessCanonicalId(String raw) {
        try {
            String b64 = raw.substring(raw.indexOf("://") + 3);
            int h = b64.indexOf('#');
            if (h >= 0) b64 = b64.substring(0, h);
            String s = b64.trim().replace('-', '+').replace('_', '/');
            int pad = s.length() % 4;
            if (pad > 0) s = s + "====".substring(pad);
            String json = new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT),
                    StandardCharsets.UTF_8);
            return new org.json.JSONObject(json).optString("id");
        } catch (Exception e) {
            return normalizeSpaces(raw);
        }
    }

    private static File dataOkFile(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "data_ok.txt");
    }

    /** Отметить конфиги как прошедшие тест на загрузку данных (sha1 → ts), свежесть 7 дней. */
    public static void markDataOk(Context c, List<String> raws) {
        if (raws == null) return;
        Map<String, Long> map = loadDataOkMap(c);
        long now = System.currentTimeMillis();
        for (String raw : raws) {
            if (raw != null && !raw.trim().isEmpty()) map.put(configHash(raw), now);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        try (FileOutputStream fos = new FileOutputStream(dataOkFile(c), false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static Map<String, Long> loadDataOkMap(Context c) {
        Map<String, Long> map = new java.util.HashMap<>();
        File f = dataOkFile(c);
        if (!f.exists()) return map;
        long now = System.currentTimeMillis();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                int sp = t.indexOf(' ');
                if (sp <= 0) continue;
                long ts;
                try {
                    ts = Long.parseLong(t.substring(sp + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (now - ts > 7L * 24 * 3600 * 1000) continue; // метки живут 7 дней
                map.put(t.substring(0, sp), ts);
            }
        } catch (IOException ignored) {
        }
        return map;
    }

    /** sha1 нормализованной (пробелы сжаты) стратегии ciadpi — единый ключ «чёрного списка». */
    public static String strategyHash(String raw) {
        return configHash(normalizeSpaces(raw == null ? "" : raw));
    }

    /** sha1-хешей конфигов, прошедших тест на загрузку (свежие, < 7 дней). */
    public static Set<String> loadDataOk(Context c) {
        return new HashSet<>(loadDataOkMap(c).keySet());
    }

    /** Удалить конфиги (по sha1(raw)) из загруженного списка. Возвращает число удалённых. */
    public static int deleteByHashes(Context c, Set<String> hashes) {
        if (hashes == null || hashes.isEmpty()) return 0;
        File f = downloadedFile(c);
        List<String> lines = readLinesFile(f);
        StringBuilder sb = new StringBuilder();
        int deleted = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (hashes.contains(configHash(t))) {
                deleted++;
                continue;
            }
            sb.append(t).append('\n');
        }
        if (deleted > 0) {
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return 0;
            }
        }
        return deleted;
    }

    /**
     * Фоновое тихое докачивание одного текстового файла-подписки ЧЕРЕЗ уже поднятый туннель.
     * Берёт первый доступный источник из Prefs.sources (по кругу), парсит новые строки,
     * тестирует партию (≤25 живых) и дописывает их в хранилище.
     * Возвращает число добавленных конфигов (0 — ничего нового / не удалось / опция выключена).
     */
    public static int quietOneThroughSocks(Context c, String socksHost, int socksPort) {
        if (!Prefs.quietUpdate(c)) return 0;
        List<String> sources = Prefs.sources(c);
        if (sources.isEmpty()) return 0;
        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress(socksHost, socksPort));
        Set<String> known = knownHashes(c);
        LatencyStore latency = new LatencyStore(c);
        int added = 0;
        int start = Prefs.lastSourceIndex(c);
        int srcIdx = start;
        for (int k = 0; k < Math.min(2, sources.size()) && added == 0; k++) {
            if (isCancelled()) return added;
            String src = sources.get((start + k) % sources.size());
            srcIdx = (start + k) % sources.size();
            final List<String> lines;
            try {
                lines = fetchUrl(src, proxy);
            } catch (Exception e) {
                continue;
            }
            List<ProxyConfig> batchCfgs = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (known.add(configHash(t))) {
                    ProxyConfig p = ProxyConfig.parse(t, src, false);
                    if (p != null) batchCfgs.add(p);
                }
                if (batchCfgs.size() >= 25) break;
            }
            if (batchCfgs.isEmpty()) continue;
            List<ProxyConfig> alive = ConfigTester.test(batchCfgs, Math.min(25, Prefs.pingThreads(c)),
                    Prefs.pingTimeoutMs(c), null);
            if (alive == null) return added; // СТОП — ничего не пишем
            List<String> aliveRaws = new ArrayList<>();
            for (ProxyConfig p2 : alive) {
                aliveRaws.add(p2.raw);
                latency.set(p2.host, p2.port, p2.latency);
            }
            try {
                appendLines(c, aliveRaws);
                added = aliveRaws.size();
            } catch (IOException e) {
                added = 0;
            }
        }
        Prefs.setLastSourceIndex(c, (srcIdx + 1) % sources.size());
        return added;
    }

    private static void appendLines(Context c, List<String> newLines) throws IOException {
        if (newLines.isEmpty()) return;
        File f = downloadedFile(c);
        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            for (String line : newLines) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
                fos.write('\n');
            }
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    // ---------- Автообновление списков (параллельно с конфигами): мосты Tor и стратегии DPI ----------

    public static final String AUTO_BRIDGES_FILE = "tor_bridges_auto.txt";
    public static final String AUTO_STRATS_FILE = "dpi_strategies_auto.txt";

    /** Источники протестированных vanilla-мостов (проверено 06.11.2026, все HTTP 200, автообновление).
     *  igareck — РФ-курированный (106); дальше — мировые коллекторы (TCP-тест при сборе). */
    public static final String[] BRIDGE_URLS = {
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_VANILLA.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_ALL.txt",
            "https://raw.githubusercontent.com/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/vanilla_tested.txt",
            "https://raw.githubusercontent.com/Koros0111/Tor-Bridges-Collector/main/bridge/vanilla_tested.txt",
            "https://raw.githubusercontent.com/Learntous7/Collect/main/vanilla_tested.txt",
            "https://raw.githubusercontent.com/Koros0111/OnionHop-Bridges-Collector/main/bridge/vanilla_tested.txt",
    };

    /** Прокси-параметр null = прямые подключения (как конфиги), SOCKS-Proxy = через туннель (тихий фон).
     *  Мержит ВСЕ источники (дедуп по ключу ip:port, без мёртвых), хранит до 300 штук. */
    public static int refreshTorBridges(Context c, java.net.Proxy proxy) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        Set<String> dead = loadTorDead(c);
        for (String url : BRIDGE_URLS) {
            try {
                List<String> lines = fetchUrl(url, proxy);
                for (String rawLine : lines) {
                    String t = rawLine.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    if (t.regionMatches(true, 0, "Bridge ", 0, 7)) t = t.substring(7).trim();
                    if (!t.matches("^(\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}(\\s+[0-9A-Fa-f]{40,})?$")) {
                        continue;
                    }
                    String key = bridgeKey(t);
                    if (key == null || dead.contains(key)) continue;
                    merged.add(t);
                    if (merged.size() >= 300) break;
                }
            } catch (Exception ignored) {
            }
            if (merged.size() >= 300) break;
        }
        if (merged.size() < 10) return 0;
        StringBuilder sb = new StringBuilder();
        for (String t : merged) sb.append(t).append('\n');
        if (!writeAtomic(c, AUTO_BRIDGES_FILE, sb.toString())) return 0;
        Prefs.setTorBridgesAutoAt(c, System.currentTimeMillis());
        return merged.size();
    }

    /** Свежие ciadpi-стратегии из настроенного источника; валидируются побитово по whitelist флагов. */
    public static int refreshDpiStrategies(Context c, java.net.Proxy proxy) {
        String url = Prefs.dpiStrategiesUrl(c);
        if (url.isEmpty()) return 0;
        try {
            List<String> lines = fetchUrl(url, proxy);
            return writeAutoStrats(c, lines);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int writeAutoStrats(Context c, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        int good = 0;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        Set<String> deadStrats = loadDpiDead(c);
        for (String rawLine : lines) {
            String t = rawLine.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!validStrategyLine(t)) continue;
            if (deadStrats.contains(strategyHash(t))) continue; // помечена мёртвой
            if (seen.add(normalizeSpaces(t))) {
                sb.append(normalizeSpaces(t)).append('\n');
                if (++good >= 150) break;
            }
        }
        if (good < 10) return 0;
        if (!writeAtomic(c, AUTO_STRATS_FILE, sb.toString())) return 0;
        Prefs.setDpiStrategiesAutoAt(c, System.currentTimeMillis());
        return good;
    }

    private static String normalizeSpaces(String t) {
        return t.trim().replaceAll("\\s+", " ");
    }

    private static boolean writeAtomic(Context c, String name, String content) {
        try {
            File tmp = new File(c.getFilesDir(), name + ".tmp");
            File dst = new File(c.getFilesDir(), name);
            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            if (dst.exists()) dst.delete();
            return tmp.renameTo(dst);
        } catch (IOException e) {
            return false;
        }
    }

    // --- валидация строк ciadpi (для авто-скачанных списков) ---

    private static final java.util.Set<String> CIADPI_SHORT = new java.util.HashSet<>(
            java.util.Arrays.asList("-i", "-p", "-D", "-w", "-E", "-c", "-N", "-U", "-I", "-b",
                    "-x", "-g", "-F", "-T", "-W", "-A", "-L", "-y", "-u", "-K", "-H", "-j",
                    "-V", "-R", "-s", "-d", "-o", "-q", "-f", "-S", "-n", "-t", "-O", "-l",
                    "-Q", "-e", "-M", "-r", "-m", "-a", "-Y"));

    private static final java.util.Set<String> CIADPI_LONG = new java.util.HashSet<>(
            java.util.Arrays.asList("ip", "port", "daemon", "pidfile", "transparent",
                    "max-conn", "no-domain", "no-udp", "conn-ip", "buf-size", "debug",
                    "def-ttl", "tfo", "tcp-timeout", "tls-timeout", "auto", "auto-mode",
                    "cache-file", "cache-ttl", "proto", "hosts", "ipset", "pf", "round",
                    "split", "disorder", "oob", "disoob", "fake", "md5sig", "fake-sni",
                    "ttl", "fake-offset", "fake-data", "fake-tls-mod", "oob-data",
                    "mod-http", "tlsrec", "tlsminor", "udp-fake", "drop-sack"));

    static boolean validStrategyLine(String t) {
        if (t.isEmpty() || t.length() > 400) return false;
        if (!t.matches("^[A-Za-z0-9\\s\\-+,.:=_/{}]+$")) return false;
        for (String tok : t.split("\\s+")) {
            if (!validStrategyToken(tok)) return false;
        }
        return true;
    }

    private static boolean validStrategyToken(String tok) {
        if (tok.isEmpty()) return true;
        if (!tok.startsWith("-")) {
            return tok.matches("[A-Za-z0-9_{},.:+=]{1,64}");
        }
        boolean dbl = tok.startsWith("--");
        String rest = dbl ? tok.substring(2) : tok.substring(1);
        if (rest.isEmpty() || rest.equals("-")) return false;
        if (rest.charAt(0) == '-') rest = rest.substring(1); // «-f-1»
        if (rest.charAt(0) >= '0' && rest.charAt(0) <= '9') {
            // отрицательное число (значение): -1, -5+se
            return rest.matches("\\d[\\d,:+a-zA-Z]{0,15}");
        }
        int i = 0;
        while (i < rest.length() && (Character.isLetterOrDigit(rest.charAt(i)) || rest.charAt(i) == '-')) i++;
        String flag = rest.substring(0, i);
        if (flag.isEmpty()) return false;
        if (dbl) {
            String name = flag.split("=", 2)[0];
            if (!CIADPI_LONG.contains(name)) return false;
            String val = tok.substring(2 + name.length());
            return val.isEmpty() || val.startsWith("=") || val.matches("[A-Za-z0-9_{},.:+=]{1,32}");
        }
        if (!CIADPI_SHORT.contains("-" + rest.substring(0, 1))) return false;
        String val = rest.substring(1);
        return val.isEmpty() || val.matches("-?[A-Za-z0-9_{},.:+=]{1,32}");
    }

    // ---------- Обновления ----------

    /**
     * Полное обновление: источники по кругу (чередование), каждая партия ≤ batchSize,
     * каждая партия пингуется, мёртвые отбраковываются.
     */
    public static UpdateResult fullUpdate(Context c, Progress progress) {
        UpdateResult res = new UpdateResult();
        if (!NetChecker.githubReachable(c)) {
            // GitHub напрямую в РФ почти всегда недоступен — не прерываем загрузку:
            // fetchUrl сам подставит jsDelivr-зеркало при отказе raw.
            if (progress != null) {
                progress.onMessage("GitHub напрямую недоступен — качаю через зеркало jsDelivr…");
            }
        }
        List<String> sources = Prefs.sources(c);
        if (sources.isEmpty()) {
            res.messages.add("Список источников пуст (Настройки → Источники)");
            return res;
        }
        dedupeDownloaded(c); // пункт: хранилище без дубликатов ДО первой проверки
        if (isCancelled()) {
            res.messages.add("остановлено пользователем");
            return res;
        }
        Set<String> known = knownHashes(c);
        int start = Prefs.lastSourceIndex(c) % sources.size();
        int batch = Prefs.batchSize(c);
        List<String> kept = new ArrayList<>();
        LatencyStore latency = new LatencyStore(c);

        for (int k = 0; k < sources.size() && kept.size() < MAX_TOTAL_NEW_PER_FULL_UPDATE; k++) {
            if (isCancelled()) {
                res.messages.add("остановлено пользователем");
                break;
            }
            int idx = (start + k) % sources.size();
            String src = sources.get(idx);
            res.sourcesTried++;
            if (progress != null) progress.onMessage("Загрузка: " + shortSource(src));
            final List<String> lines;
            try {
                lines = fetchUrl(src);
            } catch (Exception e) {
                if (progress != null) progress.onMessage("Ошибка источника: " + e.getMessage());
                res.messages.add("Источник не ответил: " + shortSource(src));
                continue;
            }
            res.sourcesOk++;
            // новые, незнакомые строки
            List<ProxyConfig> batchCfgs = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (known.add(configHash(t))) {
                    ProxyConfig p = ProxyConfig.parse(t, src, false);
                    if (p != null) batchCfgs.add(p);
                }
                if (batchCfgs.size() >= batch) break;
            }
            if (batchCfgs.isEmpty()) {
                if (progress != null) progress.onMessage("Новых нет: " + shortSource(src));
                continue;
            }
            res.downloaded += batchCfgs.size();
            // проверка партии
            List<ProxyConfig> alive = ConfigTester.test(batchCfgs, Prefs.pingThreads(c),
                    Prefs.pingTimeoutMs(c), null);
            if (alive == null) { // СТОП во время теста
                res.messages.add("остановлено пользователем");
                return res;
            }
            List<String> aliveRaws = new ArrayList<>();
            for (ProxyConfig p : alive) {
                aliveRaws.add(p.raw);
                latency.set(p.host, p.port, p.latency);
            }
            kept.addAll(aliveRaws);
            res.keptAlive += alive.size();
            res.rejectedDead += batchCfgs.size() - alive.size();
            if (progress != null) {
                progress.onMessage("Партия: " + shortSource(src) + " → живых " + alive.size()
                        + " из " + batchCfgs.size());
            }
            try {
                appendLines(c, aliveRaws);
            } catch (IOException e) {
                res.messages.add("Ошибка записи: " + e.getMessage());
            }
        }
        // параллельно с конфигами: свежие мосты Tor и список стратегий DPI
        if (isCancelled()) {
            Prefs.setLastSourceIndex(c, (start + res.sourcesTried) % sources.size());
            return res;
        }
        int bridges = refreshTorBridges(c, null);
        res.messages.add(bridges >= 10
                ? "Мосты Tor: обновлено " + bridges + " (авто)"
                : "Мосты Tor: источники недоступны или мусор — оставлены прежние");
        int strats = refreshDpiStrategies(c, null);
        res.messages.add(strats >= 10
                ? "Стратегии DPI: обновлено " + strats + " (авто)"
                : "Стратегии DPI: источники недоступны или мало рабочих строк — оставлены прежние");
        Prefs.setLastSourceIndex(c, (start + res.sourcesTried) % sources.size());
        if (res.keptAlive == 0 && res.messages.isEmpty()) {
            res.messages.add("Новых живых конфигов не найдено — все источники уже проверены");
        }
        return res;
    }

    /**
     * Тихое обновление: только если VPN сейчас выключен, скорость выше порога,
     * GitHub доступен и включена опция. Одна партия (≤ batchSize), один источник по кругу.
     */
    public static UpdateResult quietUpdate(Context c) {
        UpdateResult res = new UpdateResult();
        if (BypassVpnService.isRunning()) {
            res.messages.add("пропущено: туннель включён");
            return res;
        }
        if (!Prefs.quietUpdate(c)) {
            res.messages.add("тихое обновление выключено в настройках");
            return res;
        }
        if (!NetChecker.githubReachable(c)) {
            res.messages.add("GitHub напрямую недоступен — пробую источники через зеркало jsDelivr…");
            // не выходим: jsDelivr-зеркало подставится в fetchUrl при отказе raw
        }
        long speed = NetChecker.measureSpeedKbps(Prefs.speedTestUrl(c), 8000, 512 * 1024);
        if (speed >= 0 && speed < Prefs.speedMinKbps(c)) {
            res.messages.add("скорость " + speed + " КБ/с ниже порога " + Prefs.speedMinKbps(c) + " КБ/с");
            return res;
        }
        List<String> sources = Prefs.sources(c);
        if (sources.isEmpty()) {
            res.messages.add("источники не заданы");
            return res;
        }
        dedupeDownloaded(c);
        if (isCancelled()) {
            res.messages.add("остановлено пользователем");
            return res;
        }
        Set<String> known = knownHashes(c);
        int batch = Prefs.batchSize(c);
        int start = Prefs.lastSourceIndex(c) % sources.size();
        LatencyStore latency = new LatencyStore(c);
        // пробуем источники по кругу, пока какой-нибудь не даст новых
        for (int k = 0; k < sources.size(); k++) {
            if (isCancelled()) {
                res.messages.add("остановлено пользователем");
                return res;
            }
            int idx = (start + k) % sources.size();
            String src = sources.get(idx);
            res.sourcesTried++;
            final List<String> lines;
            try {
                lines = fetchUrl(src);
            } catch (Exception e) {
                continue;
            }
            res.sourcesOk++;
            List<ProxyConfig> batchCfgs = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (known.add(configHash(t))) {
                    ProxyConfig p = ProxyConfig.parse(t, src, false);
                    if (p != null) batchCfgs.add(p);
                }
                if (batchCfgs.size() >= batch) break;
            }
            if (batchCfgs.isEmpty()) continue;
            res.downloaded += batchCfgs.size();
            List<ProxyConfig> alive = ConfigTester.test(batchCfgs, Prefs.pingThreads(c),
                    Prefs.pingTimeoutMs(c), null);
            if (alive == null) { // СТОП во время теста
                res.messages.add("остановлено пользователем");
                return res;
            }
            List<String> raws = new ArrayList<>();
            for (ProxyConfig p : alive) {
                raws.add(p.raw);
                latency.set(p.host, p.port, p.latency);
            }
            res.keptAlive += alive.size();
            res.rejectedDead += batchCfgs.size() - alive.size();
            try {
                appendLines(c, raws);
            } catch (IOException e) {
                res.messages.add("ошибка записи: " + e.getMessage());
            }
            Prefs.setLastSourceIndex(c, (idx + 1) % sources.size());
            return res;
        }
        Prefs.setLastSourceIndex(c, (start + 1) % sources.size());
        res.messages.add("новых конфигов не найдено");
        return res;
    }

    // ---------- Мосты Tor: массовый тест, мёртвые в отдельный список ----------

    public static final String TOR_DEAD_FILE = "tor_bridges_dead.txt";
    public static final String DPI_DEAD_FILE = "dpi_strategies_dead.txt";

    /** Ключ моста «ip:port» (нижний регистр, без fingerprint). null — не vanilla-линия. */
    public static String bridgeKey(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.regionMatches(true, 0, "Bridge ", 0, 7)) t = t.substring(7).trim();
        if (t.isEmpty()) return null;
        int sp = t.indexOf(' ');
        String first = sp > 0 ? t.substring(0, sp) : t;
        if (!first.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}:[0-9]{1,5}$")) return null;
        return first.toLowerCase(java.util.Locale.ROOT);
    }

    private static File deadFile(Context c, String name) {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    public static File torBridgesAutoFile(Context c) {
        return new File(c.getFilesDir(), AUTO_BRIDGES_FILE); // writeAtomic пишет в корень filesDir
    }

    public static File dpiStratsAutoFile(Context c) {
        return new File(c.getFilesDir(), AUTO_STRATS_FILE); // writeAtomic пишет в корень filesDir
    }

    public static Set<String> loadTorDead(Context c) {
        Set<String> out = new HashSet<>();
        for (String t : readLinesFile(deadFile(c, TOR_DEAD_FILE))) {
            String k = bridgeKey(t);
            if (k != null) out.add(k);
        }
        return out;
    }

    public static void addTorDead(Context c, Set<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        Set<String> all = loadTorDead(c);
        for (String k : keys) {
            if (k != null) all.add(k.toLowerCase(java.util.Locale.ROOT));
        }
        writeLinesAtomic(deadFile(c, TOR_DEAD_FILE), all);
    }

    /**
     * Удаляет мёртвые мосты из авто-списка и пользовательских мостов (с бэкапами .bak в filesDir/configs/).
     * Встроенный резерв (assets) переписать нельзя — он отсекается по tor_bridges_dead.txt при запуске Tor.
     * Возвращает сколько строк удалено (авто + пользовательские).
     */
    public static int filterDeadBridges(Context c, Set<String> deadKeys) {
        if (deadKeys == null || deadKeys.isEmpty()) return 0;
        int removed = 0;
        File auto = torBridgesAutoFile(c);
        if (auto.isFile()) {
            List<String> lines = readLinesFile(auto);
            StringBuilder sb = new StringBuilder();
            int kept = 0;
            for (String line : lines) {
                String k = bridgeKey(line);
                if (k != null && deadKeys.contains(k)) { removed++; continue; }
                sb.append(line.trim()).append('\n');
                kept++;
            }
            if (kept != lines.size()) {
                try {
                    copyFile(auto, new File(auto.getParentFile(), AUTO_BRIDGES_FILE + ".bak"));
                    writeTextAtomic(auto, sb.toString());
                } catch (Exception ignored) {
                }
            }
        }
        String userRaw = Prefs.torBridges(c);
        if (userRaw != null && !userRaw.trim().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int kept = 0, total = 0;
            for (String rawLine : userRaw.split("\r?\n")) {
                String t = rawLine.trim();
                if (t.isEmpty()) continue;
                total++;
                String k = bridgeKey(t);
                if (k != null && deadKeys.contains(k)) { removed++; continue; }
                sb.append(t).append('\n');
                kept++;
            }
            if (kept != total) {
                try {
                    writeTextAtomic(new File(deadFile(c, TOR_DEAD_FILE).getParentFile(),
                            "tor_bridges_user.bak.txt"), userRaw);
                } catch (IOException ignored) {
                }
                Prefs.setTorBridges(c, sb.toString().trim());
            }
        }
        return removed;
    }

    // ---------- Уровни хранения (для UI) ----------

    /** Уровень 1: сколько строк в скачанном хранилище (до парсинга). */
    public static int countDownloaded(Context c) {
        return readLinesFile(downloadedFile(c)).size();
    }

    /** Уровень 2: сколько конфигов имеют свежую метку «грузит» (< 7 дней). */
    public static int countDataOk(Context c) {
        return loadDataOkMap(c).size();
    }

    // ---------- Стратегии DPI: мёртвый список ----------

    public static Set<String> loadDpiDead(Context c) {
        Set<String> out = new HashSet<>();
        for (String t : readLinesFile(deadFile(c, DPI_DEAD_FILE))) {
            String t2 = t.trim();
            if (!t2.isEmpty()) out.add(t2);
        }
        return out;
    }

    public static void addDpiDead(Context c, Set<String> strategyRaws) {
        if (strategyRaws == null || strategyRaws.isEmpty()) return;
        Set<String> all = loadDpiDead(c);
        for (String raw : strategyRaws) {
            if (raw != null && !raw.trim().isEmpty()) all.add(strategyHash(raw));
        }
        writeLinesAtomic(deadFile(c, DPI_DEAD_FILE), all);
    }

    /** Переписывает авто-список стратегий без мёртвых (с бэкапом .bak). Возвращает число удалённых строк. */
    public static int filterDeadDpiAuto(Context c, Set<String> deadHashes) {
        File f = dpiStratsAutoFile(c);
        if (!f.isFile() || deadHashes == null || deadHashes.isEmpty()) return 0;
        List<String> lines = readLinesFile(f);
        StringBuilder sb = new StringBuilder();
        int removed = 0;
        for (String line : lines) {
            if (deadHashes.contains(strategyHash(line))) { removed++; continue; }
            sb.append(line).append('\n');
        }
        if (removed > 0) {
            try {
                copyFile(f, new File(f.getParentFile(), AUTO_STRATS_FILE + ".bak"));
                writeTextAtomic(f, sb.toString());
            } catch (IOException e) {
                return 0;
            }
        }
        return removed;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static void writeTextAtomic(File f, String text) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f, false)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeLinesAtomic(File f, Set<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        try {
            writeTextAtomic(f, sb.toString());
        } catch (IOException ignored) {
        }
    }

    // ---------- Дедуп хранилища и «Вершина» (топ-5 проверенных) ----------

    /** Удалить дубликаты строк из скачанного хранилища. Возвращает число удалённых. */
    public static int dedupeDownloaded(Context c) {
        File f = downloadedFile(c);
        List<String> lines = readLinesFile(f);
        if (lines.isEmpty()) return 0;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        int dup = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (seen.add(configHash(t))) {
                sb.append(t).append('\n');
            } else {
                dup++;
            }
        }
        if (dup > 0) {
            try {
                writeTextAtomic(f, sb.toString());
            } catch (IOException e) {
                return 0;
            }
        }
        return dup;
    }

    private static File configsFile(Context c, String name) {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    private static final String TOWER_FILE = "tower.txt";
    private static final long TOWER_TTL_MS = 7L * 24 * 3600 * 1000;

    /** «Вершина»: до 6 конфигов со свежей скоростью загрузки (порядок = приоритет). */
    public static void writeTower(Context c, List<String> rawsInPriorityOrder) {
        if (rawsInPriorityOrder == null || rawsInPriorityOrder.isEmpty()) return;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String raw : rawsInPriorityOrder) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (seen.add(configHash(t))) {
                sb.append(t).append('\n');
                if (seen.size() >= 6) break;
            }
        }
        try {
            writeTextAtomic(configsFile(c, TOWER_FILE), sb.toString());
        } catch (IOException ignored) {
        }
    }

    /** «Вершина» (свежие, < 7 дней); протухшую не отдаём. */
    public static List<String> loadTower(Context c) {
        File f = configsFile(c, TOWER_FILE);
        if (!f.isFile()) return new ArrayList<>();
        if (System.currentTimeMillis() - f.lastModified() > TOWER_TTL_MS) return new ArrayList<>();
        return readLinesFile(f);
    }

    public static int countTower(Context c) { return loadTower(c).size(); }

    /** Итог последнего «Обновить конфиги» — показываем в карточке. */
    public static void writeLastUpdate(Context c, String text) {
        try {
            writeTextAtomic(configsFile(c, "last_update.txt"), text == null ? "" : text);
        } catch (IOException ignored) {
        }
    }

    public static String readLastUpdate(Context c) {
        List<String> l = readLinesFile(configsFile(c, "last_update.txt"));
        if (l.isEmpty()) return "";
        return android.text.TextUtils.join(" ", l);
    }

    public static long lastUpdateAt(Context c) {
        File f = configsFile(c, "last_update.txt");
        return f.isFile() ? f.lastModified() : -1;
    }

    // ---------- Мосты Tor «проверенные» (прошли массовый тест) ----------

    public static final String TOR_GOOD_FILE = "tor_bridges_good.txt";

    /** Переписать список проверенных мостов (живые из массового теста, до 100, дедуп по ip:port). */
    public static void markTorGood(Context c, List<String> aliveLines) {
        if (aliveLines == null) return;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String raw : aliveLines) {
            String t = raw == null ? "" : raw.trim();
            if (t.isEmpty()) continue;
            String key = bridgeKey(t);
            if (key == null || !seen.add(key)) continue;
            sb.append(t).append('\n');
            if (seen.size() >= 100) break;
        }
        try {
            writeTextAtomic(configsFile(c, TOR_GOOD_FILE), sb.toString());
        } catch (IOException ignored) {
        }
    }

    public static List<String> loadTorGood(Context c) {
        return readLinesFile(configsFile(c, TOR_GOOD_FILE));
    }

    /** Выбрать лучший конфиг из списка (по задержке); текущий сохраняется, если он ещё жив. */
    public static ProxyConfig best(List<ProxyConfig> list) {
        ProxyConfig best = null;
        for (ProxyConfig p : list) {
            if (p.latency < 0 || !p.supported) continue;
            if (best == null || p.latency < best.latency) best = p;
        }
        return best;
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
