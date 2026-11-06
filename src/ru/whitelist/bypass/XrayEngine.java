package ru.whitelist.bypass;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Движок VLESS / Trojan / Shadowsocks / VMess на ядре Xray (AndroidLibXrayLite, libv2ray).
 * Поднимает локальный SOCKS5 на 127.0.0.1:<port>, трафик VPN заворачивается в него через hev-socks5-tunnel.
 */
public final class XrayEngine {
    private XrayEngine() {}

    /** Xray-core XUDP: basekey передаётся как RawURLEncoding (base64url БЕЗ паддинга,
     *  ровно 43 символа -> 32 байта). Xray декодирует именно так
     *  (base64.RawURLEncoding.DecodeString + проверка len == 32). */
    private static final String XUDP_BASE_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    public interface Callback {
        void onReady();
        void onError(String message);
        void onLog(String line);
    }

    private static CoreController controller;
    private static boolean envInitialized = false;

    public static synchronized String version() {
        try {
            return Libv2ray.checkVersionX();
        } catch (Throwable t) {
            return "xray: недоступен (" + t.getMessage() + ")";
        }
    }

    public static synchronized void initCoreEnv(android.content.Context ctx) {
        if (envInitialized) return;
        Libv2ray.initCoreEnv(ctx.getFilesDir().getAbsolutePath(), XUDP_BASE_KEY);
        envInitialized = true;
    }

    /** Построить JSON конфига Xray для строки-подписки. Бросает IllegalArgumentException при неподдерживаемом. */
    public static String buildConfigJson(ProxyConfig cfg, int localSocksPort, boolean allowInsecureGlobal,
                                         String dns) {
        return buildConfigJson(cfg, localSocksPort, allowInsecureGlobal, dns, 0);
    }

    /**
     * @param desyncSocksPort если > 0 — основной outbound получает streamSettings.proxySettings
     * {tag:"desync"}, в конец добавляется socks-outbound на 127.0.0.1:port — линия до
     * VPN-сервера идёт через локальный ByeDPI (эксперимент: DPI-обход по RFC-независимой
     * стратегии пользователя).
     */
    public static String buildConfigJson(ProxyConfig cfg, int localSocksPort, boolean allowInsecureGlobal,
                                         String dns, int desyncSocksPort) {
        try {
            JSONObject root = new JSONObject();

            JSONObject log = new JSONObject();
            log.put("loglevel", "warning");
            root.put("log", log);

            JSONArray inbounds = new JSONArray();
            JSONObject inbound = new JSONObject();
            inbound.put("tag", "socks-in")
                    .put("listen", "127.0.0.1")
                    .put("port", localSocksPort)
                    .put("protocol", "socks");
            JSONObject ibSettings = new JSONObject();
            ibSettings.put("auth", "noauth").put("udp", true);
            inbound.put("settings", ibSettings);
            JSONObject sniffing = new JSONObject();
            sniffing.put("enabled", true)
                    .put("destOverride", new JSONArray().put("http").put("tls").put("quic"));
            inbound.put("sniffing", sniffing);
            inbounds.put(inbound);
            root.put("inbounds", inbounds);

            JSONArray outbounds = new JSONArray();
            JSONObject outbound = buildOutbound(cfg, allowInsecureGlobal);
            if (outbound == null) {
                throw new IllegalArgumentException(cfg.unsupportedReason != null ? cfg.unsupportedReason
                        : "неподдерживаемый протокол: " + cfg.scheme);
            }
            outbounds.put(outbound);

            JSONObject freedom = new JSONObject();
            freedom.put("tag", "direct").put("protocol", "freedom");
            freedom.put("settings", new JSONObject());
            outbounds.put(freedom);

            JSONObject blackhole = new JSONObject();
            blackhole.put("tag", "block").put("protocol", "blackhole");
            blackhole.put("settings", new JSONObject());
            outbounds.put(blackhole);

            root.put("outbounds", outbounds);

            if (desyncSocksPort > 0) {
                try {
                    JSONObject desync = new JSONObject();
                    desync.put("tag", "desync").put("protocol", "socks");
                    JSONObject srv = new JSONObject();
                    srv.put("address", "127.0.0.1").put("port", desyncSocksPort);
                    desync.put("settings", new JSONObject()
                            .put("servers", new JSONArray().put(srv)));
                    outbounds.put(desync);
                    JSONObject main = outbounds.getJSONObject(0);
                    JSONObject stream = main.optJSONObject("streamSettings");
                    if (stream == null) {
                        stream = new JSONObject();
                        main.put("streamSettings", stream);
                    }
                    stream.put("proxySettings", new JSONObject().put("tag", "desync"));
                } catch (Exception e) {
                    android.util.Log.w("WhitelistBypass", "desync outbound не добавлен — работаю без него", e);
                }
            }

            // DNS внутри ядра (через сам прокси, ничего не течёт мимо)
            JSONObject dnsObj = new JSONObject();
            dnsObj.put("servers", new JSONArray().put(dns != null && !dns.isEmpty() ? dns : "1.1.1.1"));
            root.put("dns", dnsObj);

            return root.toString();
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("не удалось построить конфиг Xray: " + e.getMessage(), e);
        }
    }

    private static JSONObject buildOutbound(ProxyConfig cfg, boolean allowInsecureGlobal)
            throws org.json.JSONException {
        Map<String, String> p = cfg.params();
        String sni = firstNonEmpty(p.get("sni"), p.get("peer"), p.get("host"), cfg.host);
        String network = firstNonEmpty(p.get("type"), "tcp");
        String security = firstNonEmpty(p.get("security"),
                "trojan".equals(cfg.scheme) || "vless".equals(cfg.scheme) ? "tls" : "none");
        boolean allowInsecure = allowInsecureGlobal
                || "1".equals(p.get("allowInsecure")) || "true".equalsIgnoreCase(p.get("allowInsecure"))
                || "1".equals(p.get("insecure")) || "true".equalsIgnoreCase(p.get("insecure"));
        String fp = firstNonEmpty(p.get("fp"), "chrome");
        String alpn = p.get("alpn");

        JSONObject out = new JSONObject();
        out.put("tag", "proxy");

        switch (cfg.scheme) {
            case "vless": {
                out.put("protocol", "vless");
                JSONObject settings = new JSONObject();
                JSONArray vnext = new JSONArray();
                JSONObject server = new JSONObject();
                server.put("address", cfg.host).put("port", cfg.port);
                JSONArray users = new JSONArray();
                JSONObject user = new JSONObject();
                user.put("id", cfg.userInfo()).put("encryption", "none");
                String flow = p.get("flow");
                if (flow != null && !flow.isEmpty()) user.put("flow", flow);
                users.put(user);
                server.put("users", users);
                vnext.put(server);
                settings.put("vnext", vnext);
                out.put("settings", settings);
                break;
            }
            case "trojan": {
                out.put("protocol", "trojan");
                if ("none".equals(security)) security = "tls";
                JSONObject settings = new JSONObject();
                JSONArray servers = new JSONArray();
                JSONObject server = new JSONObject();
                server.put("address", cfg.host).put("port", cfg.port)
                        .put("password", cfg.userInfo()).put("email", "bypass@localhost");
                servers.put(server);
                settings.put("servers", servers);
                out.put("settings", settings);
                break;
            }
            case "ss":
            case "shadowsocks": {
                out.put("protocol", "shadowsocks");
                security = "none"; // ss сам по себе; TLS опционально через network=ws+security=tls
                String userInfo = cfg.ssUserInfo(); // method:password (SIP002: может быть base64)
                String method = p.get("method");
                String password = p.get("password");
                if (method == null || password == null) {
                    int colon = userInfo.indexOf(':');
                    if (colon > 0) {
                        if (method == null) method = userInfo.substring(0, colon);
                        if (password == null) password = userInfo.substring(colon + 1);
                    }
                }
                JSONObject settings = new JSONObject();
                JSONArray servers = new JSONArray();
                JSONObject server = new JSONObject();
                server.put("address", cfg.host).put("port", cfg.port)
                        .put("method", method != null ? method : "aes-256-gcm")
                        .put("password", password != null ? password : "");
                servers.put(server);
                settings.put("servers", servers);
                out.put("settings", settings);
                // поддержим ss+ws, если в ссылке есть type=ws
                if (!"ws".equals(network) && p.containsKey("security") && "tls".equals(p.get("security"))) {
                    network = "tcp";
                }
                break;
            }
            case "vmess": {
                out.put("protocol", "vmess");
                String json = null;
                try {
                    String b64 = cfg.raw.substring(cfg.raw.indexOf("://") + 3);
                    int hash = b64.indexOf('#');
                    if (hash >= 0) b64 = b64.substring(0, hash);
                    String t = b64.trim().replace('-', '+').replace('_', '/');
                    int pad = t.length() % 4;
                    if (pad > 0) t = t + "====".substring(pad);
                    json = new String(android.util.Base64.decode(t, android.util.Base64.DEFAULT),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                JSONObject v = json != null ? new JSONObject(json) : new JSONObject();
                String uuid = v.optString("id");
                String net = firstNonEmpty(v.optString("net"), network);
                String tls = firstNonEmpty(v.optString("tls"), "none");
                String path = v.optString("path");
                String hostHeader = firstNonEmpty(v.optString("host"), sni);
                String vSni = firstNonEmpty(v.optString("sni"), sni);
                network = "none".equals(net) || net.isEmpty() ? "tcp" : net;
                security = "tls".equalsIgnoreCase(tls) ? "tls" : security;
                sni = vSni;

                JSONObject settings = new JSONObject();
                JSONArray vnext = new JSONArray();
                JSONObject server = new JSONObject();
                server.put("address", cfg.host).put("port", cfg.port);
                JSONArray users = new JSONArray();
                JSONObject user = new JSONObject();
                user.put("id", uuid).put("alterId", 0).put("security", "auto");
                users.put(user);
                server.put("users", users);
                vnext.put(server);
                settings.put("vnext", vnext);
                out.put("settings", settings);

                JSONObject stream = buildStream(security, network, sni, fp, alpn, allowInsecure,
                        path, hostHeader, p, v);
                out.put("streamSettings", stream);
                return out;
            }
            default:
                return null;
        }

        JSONObject stream = buildStream(security, network, sni, fp, alpn, allowInsecure,
                p.get("path"), firstNonEmpty(p.get("host"), sni), p, null);
        out.put("streamSettings", stream);
        return out;
    }

    private static JSONObject buildStream(String security, String network, String sni, String fp,
                                          String alpn, boolean allowInsecure, String path,
                                          String hostHeader, Map<String, String> p,
                                          JSONObject vmess) throws org.json.JSONException {
        JSONObject stream = new JSONObject();
        String net = network == null || network.isEmpty() ? "tcp" : network.toLowerCase();
        stream.put("network", net);

        // настройки транспорта
        switch (net) {
            case "ws": {
                JSONObject ws = new JSONObject();
                ws.put("path", path != null && !path.isEmpty() ? path : "/");
                JSONObject headers = new JSONObject();
                headers.put("Host", hostHeader != null ? hostHeader : sni);
                ws.put("headers", headers);
                stream.put("wsSettings", ws);
                break;
            }
            case "grpc": {
                JSONObject grpc = new JSONObject();
                grpc.put("serviceName", firstNonEmpty(p.get("serviceName"),
                        vmess != null ? vmess.optString("path") : ""));
                stream.put("grpcSettings", grpc);
                break;
            }
            case "httpupgrade": {
                JSONObject hu = new JSONObject();
                hu.put("path", path != null && !path.isEmpty() ? path : "/");
                hu.put("host", hostHeader != null ? hostHeader : sni);
                stream.put("httpupgradeSettings", hu);
                break;
            }
            case "xhttp":
            case "splithttp": {
                stream.put("network", "xhttp");
                JSONObject xh = new JSONObject();
                xh.put("path", path != null && !path.isEmpty() ? path : "/");
                xh.put("host", hostHeader != null ? hostHeader : sni);
                String modeStr = p.get("mode");
                if (modeStr != null) xh.put("mode", modeStr);
                stream.put("xhttpSettings", xh);
                break;
            }
            case "tcp":
            default: {
                JSONObject tcp = new JSONObject();
                JSONObject header = new JSONObject();
                header.put("type", "none");
                tcp.put("header", header);
                stream.put("tcpSettings", tcp);
                break;
            }
        }

        // безопасность
        String sec = security == null ? "none" : security.toLowerCase();
        stream.put("security", sec);
        switch (sec) {
            case "tls": {
                JSONObject tls = new JSONObject();
                tls.put("serverName", sni != null ? sni : "")
                        .put("allowInsecure", allowInsecure);
                if (fp != null && !fp.isEmpty()) tls.put("fingerprint", fp);
                if (alpn != null && !alpn.isEmpty()) {
                    JSONArray arr = new JSONArray();
                    for (String a : alpn.split(",")) arr.put(a.trim());
                    tls.put("alpn", arr);
                }
                stream.put("tlsSettings", tls);
                break;
            }
            case "reality": {
                JSONObject reality = new JSONObject();
                reality.put("serverName", sni != null ? sni : "")
                        .put("fingerprint", fp != null ? fp : "chrome")
                        .put("publicKey", firstNonEmpty(p.get("pbk"), p.get("publicKey"), ""))
                        .put("shortId", firstNonEmpty(p.get("sid"), p.get("shortId"), ""));
                String spx = p.get("spx");
                if (spx != null && !spx.isEmpty()) reality.put("spiderX", spx);
                stream.put("realitySettings", reality);
                break;
            }
            default:
                break;
        }
        return stream;
    }

    public static void start(android.content.Context ctx, String configJson, final Callback callback) {
        initCoreEnv(ctx);
        stop();
        CoreCallbackHandler handler = new CoreCallbackHandler() {
            @Override
            public long onEmitStatus(long code, String msg) {
                if (callback != null) callback.onLog("[xray] " + msg);
                return 0;
            }

            @Override
            public long shutdown() {
                if (callback != null) callback.onLog("[xray] shutdown");
                return 0;
            }

            @Override
            public long startup() {
                if (callback != null) callback.onReady();
                return 0;
            }
        };
        try {
            controller = Libv2ray.newCoreController(handler);
            controller.startLoop(configJson, 0);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Отдельный контроллер для параллельного прогона (массовый тест конфигов).
     * НЕ трогает singleton (static start/stop) — туннель и тесты не мешают друг другу.
     * Готовность определяем внешне (опрос локального SOCKS), onReady не ждём.
     */
    public static CoreController startInstance(android.content.Context ctx, String configJson) {
        initCoreEnv(ctx);
        CoreCallbackHandler handler = new CoreCallbackHandler() {
            @Override
            public long onEmitStatus(long code, String msg) {
                return 0;
            }

            @Override
            public long shutdown() {
                return 0;
            }

            @Override
            public long startup() {
                return 0;
            }
        };
        CoreController controller = Libv2ray.newCoreController(handler);
        try {
            controller.startLoop(configJson, 0);
        } catch (Exception e) {
            try {
                controller.stopLoop();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("xray startLoop failed: " + e.getMessage(), e);
        }
        return controller;
    }

    public static void stopInstance(CoreController controller) {
        if (controller == null) return;
        try {
            controller.stopLoop();
        } catch (Exception ignored) {
        }
    }

    public static void stop() {
        if (controller != null) {
            try {
                controller.stopLoop();
            } catch (Exception ignored) {
            }
            controller = null;
        }
    }

    /** Задержка через ядро (HTTP-запрос по маршруту), мс; -1 при ошибке. */
    public static long measureDelay(String url) {
        CoreController c = controller;
        if (c == null) return -1;
        try {
            return c.measureDelay(url);
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isRunning() {
        try {
            return controller != null && controller.getIsRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }
}
