package ru.whitelist.bypass;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * VLESS / Trojan / Shadowsocks / VMess на ядре Xray (AndroidLibXrayLite, libv2ray).
 * Поднимает локальный SOCKS5 на 127.0.0.1:&lt;port&gt;; TUN заворачивается в него через
 * hev-socks5-tunnel.
 */
public final class XrayEngine {
    private XrayEngine() {}

    /** XUDP basekey: RawURLEncoding, 43 символа = 32 байта (требование xray-core). */
    private static final String XUDP_BASE_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    public interface Callback {
        void onReady();
        void onError(String message);
        void onLog(String line);
    }

    private static CoreController controller;
    private static boolean envInitialized = false;

    /** Транспорты, которые умеет текущее ядро. kcp/quic/domainsocket — не умеет. */
    private static final java.util.Set<String> NETWORKS = new java.util.HashSet<>(java.util.Arrays.asList(
            "tcp", "ws", "grpc", "h2", "http", "httpupgrade", "xhttp", "splithttp"));

    /** Шифрование, которое умеет ядро. xtls из Xray убран, «none» — без TLS. */
    private static final java.util.Set<String> SECURITIES = new java.util.HashSet<>(java.util.Arrays.asList(
            "none", "tls", "reality"));

    /** Методы Shadowsocks, которые ядро реально поддерживает. */
    private static final java.util.Set<String> SS_METHODS = new java.util.HashSet<>(java.util.Arrays.asList(
            "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
            "xchacha20-poly1305", "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
            "none", "plain"));

    private static final java.util.regex.Pattern UUID_RE = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public static boolean isUuid(String v) {
        return v != null && UUID_RE.matcher(v.trim()).matches();
    }

    /**
     * Почему ядро не сможет поднять этот конфиг. null — можно пробовать.
     *
     * Проверка нужна потому, что TCP-пинг проходит у любого живого порта, а startLoop
     * падает уже внутри ядра — и в логе это выглядело как «xray startLoop failed»
     * у конфига, который «проверен на пинг».
     */
    public static String compatProblem(ProxyConfig cfg) {
        if (cfg == null) return "пустой конфиг";
        if (!cfg.supported) {
            return cfg.unsupportedReason != null ? cfg.unsupportedReason : "схема не поддерживается";
        }
        Map<String, String> p = cfg.params();
        String network = firstNonEmpty(p.get("type"), "tcp").toLowerCase();
        String security = firstNonEmpty(p.get("security"),
                "trojan".equals(cfg.scheme) ? "tls" : "none").toLowerCase();
        String flow = p.get("flow");

        if (!NETWORKS.contains(network)) {
            return "транспорт «" + network + "» ядро не умеет";
        }
        if (!SECURITIES.contains(security)) {
            return "шифрование «" + security + "» ядро не умеет";
        }
        if (flow != null && !flow.isEmpty()) {
            if (!"xtls-rprx-vision".equals(flow)) {
                return "flow «" + flow + "» устарел, ядро его не знает";
            }
            if (!"tcp".equals(network)) return "flow xtls-rprx-vision работает только на tcp";
            if (!"tls".equals(security) && !"reality".equals(security)) {
                return "flow xtls-rprx-vision без TLS не работает";
            }
        }
        if ("reality".equals(security)) {
            String pbk = firstNonEmpty(p.get("pbk"), p.get("publicKey"));
            if (pbk.isEmpty()) return "reality без publicKey (pbk)";
            if (pbk.length() < 40) return "reality: publicKey короче 43 символов";
            if (firstNonEmpty(p.get("sni"), p.get("peer")).isEmpty()) return "reality без sni";
        }
        if ("vless".equals(cfg.scheme)) {
            String enc = p.get("encryption");
            if (enc != null && !enc.isEmpty() && !"none".equalsIgnoreCase(enc)) {
                return "vless: encryption=" + enc + " не поддерживается";
            }
            if (!isUuid(cfg.userInfo())) return "vless: id не UUID";
        }
        if ("vmess".equals(cfg.scheme)) {
            String id = vmessJson(cfg).optString("id");
            if (!isUuid(id)) return "vmess: id не UUID";
        }
        if ("ss".equals(cfg.scheme) || "shadowsocks".equals(cfg.scheme)) {
            String method = ssMethod(cfg);
            if (method == null || !SS_METHODS.contains(method.toLowerCase())) {
                return "ss: метод «" + (method != null ? method : "?") + "» не поддерживается";
            }
            if (ssPassword(cfg) == null || ssPassword(cfg).isEmpty()) return "ss: нет пароля";
        }
        return null;
    }

    /** Метод Shadowsocks: из параметров ссылки или из userinfo (base64 method:password). */
    private static String ssMethod(ProxyConfig cfg) {
        Map<String, String> p = cfg.params();
        String method = p.get("method");
        if (method != null && !method.isEmpty()) return method;
        String userInfo = cfg.ssUserInfo();
        int colon = userInfo.indexOf(':');
        return colon > 0 ? userInfo.substring(0, colon) : null;
    }

    private static String ssPassword(ProxyConfig cfg) {
        Map<String, String> p = cfg.params();
        String password = p.get("password");
        if (password != null && !password.isEmpty()) return password;
        String userInfo = cfg.ssUserInfo();
        int colon = userInfo.indexOf(':');
        return colon > 0 ? userInfo.substring(colon + 1) : null;
    }

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

    public static String buildConfigJson(ProxyConfig cfg, int localSocksPort, boolean allowInsecure,
                                         String dns) {
        return buildConfigJson(cfg, localSocksPort, allowInsecure, dns, 0);
    }

    /**
     * @param desyncSocksPort &gt; 0 — линия до VPN-сервера идёт через локальный ByeDPI:
     *                        основному outbound ставится streamSettings.proxySettings
     *                        и добавляется socks-outbound «desync».
     */
    public static String buildConfigJson(ProxyConfig cfg, int localSocksPort, boolean allowInsecure,
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
            inbound.put("settings", new JSONObject().put("auth", "noauth").put("udp", true));
            inbound.put("sniffing", new JSONObject()
                    .put("enabled", true)
                    .put("destOverride", new JSONArray().put("http").put("tls").put("quic")));
            inbounds.put(inbound);
            root.put("inbounds", inbounds);

            JSONArray outbounds = new JSONArray();
            JSONObject main = buildOutbound(cfg, allowInsecure);
            if (main == null) {
                throw new IllegalArgumentException(cfg.unsupportedReason != null
                        ? cfg.unsupportedReason : "неподдерживаемый протокол: " + cfg.scheme);
            }
            outbounds.put(main);
            outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom")
                    .put("settings", new JSONObject()));
            outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole")
                    .put("settings", new JSONObject()));

            if (desyncSocksPort > 0) {
                try {
                    JSONObject desync = new JSONObject();
                    desync.put("tag", "desync").put("protocol", "socks");
                    JSONObject server = new JSONObject()
                            .put("address", "127.0.0.1").put("port", desyncSocksPort);
                    desync.put("settings", new JSONObject()
                            .put("servers", new JSONArray().put(server)));
                    outbounds.put(desync);

                    JSONObject stream = main.optJSONObject("streamSettings");
                    if (stream == null) {
                        stream = new JSONObject();
                        main.put("streamSettings", stream);
                    }
                    stream.put("proxySettings", new JSONObject().put("tag", "desync"));
                } catch (Exception e) {
                    android.util.Log.w("WhitelistBypass", "desync-outbound не добавлен", e);
                }
            }

            root.put("outbounds", outbounds);
            // Несколько резервных DNS: ядро пробует их по очереди.
            JSONArray servers = new JSONArray();
            if (dns != null) {
                for (String part : dns.split("[,;\\s]+")) {
                    String t = part.trim();
                    if (!t.isEmpty()) servers.put(t);
                }
            }
            if (servers.length() == 0) servers.put("1.1.1.1");
            JSONObject dnsObj = new JSONObject();
            dnsObj.put("servers", servers);
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
        String security = firstNonEmpty(p.get("security"), "none");
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
                JSONArray vnext = new JSONArray();
                JSONArray users = new JSONArray();
                JSONObject user = new JSONObject();
                user.put("id", cfg.userInfo()).put("encryption", "none");
                String flow = p.get("flow");
                if (flow != null && !flow.isEmpty()) user.put("flow", flow);
                users.put(user);
                vnext.put(new JSONObject()
                        .put("address", cfg.host).put("port", cfg.port).put("users", users));
                out.put("settings", new JSONObject().put("vnext", vnext));
                break;
            }
            case "trojan": {
                out.put("protocol", "trojan");
                if ("none".equals(security)) security = "tls";
                JSONArray servers = new JSONArray();
                servers.put(new JSONObject()
                        .put("address", cfg.host).put("port", cfg.port)
                        .put("password", cfg.userInfo()).put("email", "bypass@localhost"));
                out.put("settings", new JSONObject().put("servers", servers));
                break;
            }
            case "ss":
            case "shadowsocks": {
                out.put("protocol", "shadowsocks");
                String method = ssMethod(cfg);
                String password = ssPassword(cfg);
                JSONArray servers = new JSONArray();
                servers.put(new JSONObject()
                        .put("address", cfg.host).put("port", cfg.port)
                        .put("method", method != null ? method : "aes-256-gcm")
                        .put("password", password != null ? password : ""));
                out.put("settings", new JSONObject().put("servers", servers));
                break;
            }
            case "vmess": {
                out.put("protocol", "vmess");
                JSONObject v = vmessJson(cfg);
                String net = firstNonEmpty(v.optString("net"), network);
                String tls = firstNonEmpty(v.optString("tls"), "none");
                String path = v.optString("path");
                String hostHeader = firstNonEmpty(v.optString("host"), sni);
                String vSni = firstNonEmpty(v.optString("sni"), sni);
                network = "none".equals(net) || net.isEmpty() ? "tcp" : net;
                if ("tls".equalsIgnoreCase(tls)) security = "tls";
                sni = vSni;

                JSONArray vnext = new JSONArray();
                JSONArray users = new JSONArray();
                users.put(new JSONObject()
                        .put("id", v.optString("id")).put("alterId", 0).put("security", "auto"));
                vnext.put(new JSONObject()
                        .put("address", cfg.host).put("port", cfg.port).put("users", users));
                out.put("settings", new JSONObject().put("vnext", vnext));
                out.put("streamSettings", buildStream(security, network, sni, fp, alpn,
                        allowInsecure, path, hostHeader, p));
                return out;
            }
            default:
                return null;
        }

        out.put("streamSettings", buildStream(security, network, sni, fp, alpn, allowInsecure,
                p.get("path"), firstNonEmpty(p.get("host"), sni), p));
        return out;
    }

    private static JSONObject vmessJson(ProxyConfig cfg) {
        try {
            String b64 = cfg.raw.substring(cfg.raw.indexOf("://") + 3);
            int hash = b64.indexOf('#');
            if (hash >= 0) b64 = b64.substring(0, hash);
            String s = b64.trim().replace('-', '+').replace('_', '/');
            int pad = s.length() % 4;
            if (pad > 0) s = s + "=====".substring(0, pad);
            String json = new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT),
                    java.nio.charset.StandardCharsets.UTF_8);
            return new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject buildStream(String security, String network, String sni, String fp,
                                          String alpn, boolean allowInsecure, String path,
                                          String hostHeader, Map<String, String> p)
            throws org.json.JSONException {
        JSONObject stream = new JSONObject();
        String net = network == null || network.isEmpty() ? "tcp" : network.toLowerCase();
        stream.put("network", net);

        switch (net) {
            case "ws": {
                JSONObject ws = new JSONObject();
                ws.put("path", path != null && !path.isEmpty() ? path : "/");
                ws.put("headers", new JSONObject().put("Host",
                        hostHeader != null && !hostHeader.isEmpty() ? hostHeader : (sni != null ? sni : "")));
                stream.put("wsSettings", ws);
                break;
            }
            case "grpc": {
                stream.put("grpcSettings", new JSONObject().put("serviceName",
                        firstNonEmpty(p.get("serviceName"), path)));
                break;
            }
            case "http":
            case "h2": {
                stream.put("network", "h2");
                JSONObject h2 = new JSONObject()
                        .put("path", path != null && !path.isEmpty() ? path : "/");
                if (hostHeader != null && !hostHeader.isEmpty()) {
                    h2.put("host", new JSONArray().put(hostHeader));
                }
                stream.put("h2Settings", h2);
                break;
            }
            case "httpupgrade": {
                stream.put("httpupgradeSettings", new JSONObject()
                        .put("path", path != null && !path.isEmpty() ? path : "/")
                        .put("host", hostHeader != null ? hostHeader : ""));
                break;
            }
            case "xhttp":
            case "splithttp": {
                stream.put("network", "xhttp");
                JSONObject xh = new JSONObject()
                        .put("path", path != null && !path.isEmpty() ? path : "/")
                        .put("host", hostHeader != null ? hostHeader : "");
                String mode = p.get("mode");
                if (mode != null && !mode.isEmpty()) xh.put("mode", mode);
                stream.put("xhttpSettings", xh);
                break;
            }
            case "tcp":
            default: {
                stream.put("tcpSettings", new JSONObject()
                        .put("header", new JSONObject().put("type", "none")));
                break;
            }
        }

        String sec = security == null || security.isEmpty() ? "none" : security.toLowerCase();
        stream.put("security", sec);
        if ("tls".equals(sec)) {
            JSONObject tls = new JSONObject();
            tls.put("serverName", sni != null ? sni : "").put("allowInsecure", allowInsecure);
            if (fp != null && !fp.isEmpty()) tls.put("fingerprint", fp);
            if (alpn != null && !alpn.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String a : alpn.split(",")) {
                    if (!a.trim().isEmpty()) arr.put(a.trim());
                }
                tls.put("alpn", arr);
            }
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(sec)) {
            JSONObject reality = new JSONObject();
            reality.put("serverName", sni != null ? sni : "")
                    .put("fingerprint", fp != null && !fp.isEmpty() ? fp : "chrome")
                    .put("publicKey", firstNonEmpty(p.get("pbk"), p.get("publicKey"), ""))
                    .put("shortId", firstNonEmpty(p.get("sid"), p.get("shortId"), ""));
            String spx = p.get("spx");
            if (spx != null && !spx.isEmpty()) reality.put("spiderX", spx);
            stream.put("realitySettings", reality);
        }
        return stream;
    }

    // ------------------------------------------------------------------ жизненный цикл

    /** Основной (единственный) экземпляр ядра — тот, что обслуживает туннель. */
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
            // startLoop в libv2ray ничего не возвращает: отказ виден либо исключением,
            // либо тем, что локальный SOCKS так и не открылся (это проверяет вызывающий).
            controller.startLoop(configJson, 0);
        } catch (Exception e) {
            if (callback != null) callback.onError(String.valueOf(e.getMessage()));
        }
    }

    public static void stop() {
        CoreController c = controller;
        controller = null;
        if (c != null) {
            try {
                c.stopLoop();
            } catch (Exception ignored) {
            }
        }
    }

    /** Отдельный экземпляр (для разовых проверок в поиске/замере). Синглтон не трогает. */
    public static CoreController startInstance(android.content.Context ctx, String configJson) {
        initCoreEnv(ctx);
        CoreCallbackHandler handler = new CoreCallbackHandler() {
            @Override public long onEmitStatus(long code, String msg) { return 0; }
            @Override public long shutdown() { return 0; }
            @Override public long startup() { return 0; }
        };
        CoreController c = Libv2ray.newCoreController(handler);
        try {
            c.startLoop(configJson, 0);
        } catch (Exception e) {
            try {
                c.stopLoop();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("ядро не запустилось: " + e.getMessage(), e);
        }
        return c;
    }

    public static void stopInstance(CoreController c) {
        if (c == null) return;
        try {
            c.stopLoop();
        } catch (Exception ignored) {
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }
}
