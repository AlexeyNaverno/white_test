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

    /**
     * Транспорты, которые умеет ядро Xray 26.x И которые умеет строить это приложение.
     *
     * Важно: `raw` — новое имя tcp (Xray 1.8.4+), такие ссылки встречаются всё чаще.
     * А `h2`/`http`/`h3` из Xray 26 удалены (остался XHTTP) — если пропустить их в ядро,
     * оно отвечает «config error: infra/conf: HTTP transport ... removed», и в логе это
     * выглядело как непонятная ошибка запуска. Такие конфиги отсекаем заранее и с причиной.
     */
    private static final java.util.Set<String> NETWORKS = new java.util.HashSet<>(java.util.Arrays.asList(
            "raw", "tcp", "ws", "websocket", "grpc", "httpupgrade", "xhttp", "splithttp"));

    /** Транспорты, удалённые из Xray 26 — ядро их не поднимет никогда. */
    private static final java.util.Set<String> REMOVED_NETWORKS = new java.util.HashSet<>(java.util.Arrays.asList(
            "h2", "h3", "http", "quic"));

    /** Транспорты, которые ядро умеет, но приложение не строит (нужны свои параметры). */
    private static final java.util.Set<String> UNBUILDABLE_NETWORKS =
            new java.util.HashSet<>(java.util.Arrays.asList("kcp", "mkcp", "hysteria", "masque", "xdrive"));

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

    /**
     * Ядро Xray 26 отказывается поднимать VLESS/Trojan без TLS к публичному адресу
     * (infra/conf: requiresTransportSecurity) — ровно этой строкой и падает половина
     * публичных списков. Формулировка одна на всех, чтобы в сводке причин они
     * складывались в один счётчик, а не в десятки строк.
     */
    public static final String NO_TLS_REASON =
            "ядро Xray 26 не пускает без TLS — нужен security=tls или reality";

    /** Похоже ли на UUID. Не требование: Xray сам переводит произвольную строку в UUID v5. */
    public static boolean isUuid(String v) {
        return v != null && UUID_RE.matcher(v.trim()).matches();
    }

    /** Значения security в ссылках бывают «tls/true/false/none/1» — приводим к каноническим. */
    public static String normalizeSecurity(String value, boolean trojanDefaultTls) {
        String v = value == null ? "" : value.trim().toLowerCase();
        if (v.isEmpty()) return trojanDefaultTls ? "tls" : "none";
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) return "tls";
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) return "none";
        return v;
    }

    /**
     * Собственное шифрование VLESS (Xray 26: mlkem768x25519plus…): с ним ядро пускает
     * outbound и без TLS. null — шифрования нет.
     */
    private static String vlessEncryption(Map<String, String> p) {
        String enc = p.get("encryption");
        if (enc == null) return null;
        enc = enc.trim();
        return enc.isEmpty() || "none".equalsIgnoreCase(enc) ? null : enc;
    }

    private static final java.util.regex.Pattern IPV4_RE = java.util.regex.Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /**
     * Адрес, который ядро считает «своим» и пускает без TLS: частные, loopback и
     * link-local IP плюс локальные домены (см. geodata.GetPrivateDomainMatcher).
     */
    public static boolean isPrivateHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.isEmpty()) return false;
        if ("localhost".equals(h)) return true;
        for (String suffix : new String[]{".localhost", ".local", ".lan", ".internal",
                ".home.arpa", ".test", ".invalid"}) {
            if (h.endsWith(suffix)) return true;
        }
        java.util.regex.Matcher m = IPV4_RE.matcher(h);
        if (m.matches()) {
            try {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                return a == 10 || a == 127
                        || (a == 192 && b == 168)
                        || (a == 172 && b >= 16 && b <= 31)
                        || (a == 169 && b == 254);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (h.indexOf(':') >= 0) {   // IPv6
            return "::1".equals(h) || h.startsWith("fc") || h.startsWith("fd")
                    || h.startsWith("fe8") || h.startsWith("fe9")
                    || h.startsWith("fea") || h.startsWith("feb");
        }
        return false;
    }

    /**
     * Конфиг попадёт под запрет «VLESS/Trojan без TLS к публичному адресу», но его можно
     * спасти: outbound смотрит на локальный dokodemo-door (loopback — «свой» адрес для
     * ядра), а тот отдаёт соединение настоящему серверу. Так публичные списки без TLS
     * (ws/CDN, обычный tcp) снова работают — ценой одного локального хопа.
     */
    public static boolean needsRelay(ProxyConfig cfg) {
        if (cfg == null || cfg.host == null || cfg.host.trim().isEmpty()) return false;
        if (!"vless".equals(cfg.scheme) && !"trojan".equals(cfg.scheme)) return false;
        Map<String, String> p = cfg.params();
        String security = normalizeSecurity(p.get("security"), "trojan".equals(cfg.scheme));
        if ("tls".equals(security) || "reality".equals(security)) return false;
        if ("vless".equals(cfg.scheme) && vlessEncryption(p) != null) return false;
        return !isPrivateHost(cfg.host);
    }

    /** Свободный порт на loopback; 0 — не нашёлся. */
    static int freePort() {
        java.net.ServerSocket socket = null;
        try {
            socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            return socket.getLocalPort();
        } catch (Exception e) {
            return 0;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Почему ядро не сможет поднять этот конфиг. null — можно пробовать.
     *
     * Проверка нужна потому, что TCP-пинг проходит у любого живого порта, а startLoop
     * падает уже внутри ядра — и в логе это выглядело как «xray startLoop failed»
     * у конфига, который «проверен на пинг».
     */
    public static String compatProblem(ProxyConfig cfg) {
        return compatProblem(cfg, false);
    }

    /**
     * @param allowRelay true — конфиги без TLS разрешены: они пойдут через локальный
     *                   ретранслятор (см. {@link #needsRelay}), и правило ядра
     *                   «только к приватному адресу» сработает на loopback.
     */
    public static String compatProblem(ProxyConfig cfg, boolean allowRelay) {
        if (cfg == null) return "пустой конфиг";
        if (!cfg.supported) {
            return cfg.unsupportedReason != null ? cfg.unsupportedReason : "схема не поддерживается";
        }
        if (cfg.host == null || cfg.host.trim().isEmpty()) return "адрес сервера не разобран";
        if (cfg.port <= 0 || cfg.port > 65535) return "порт не разобран";

        Map<String, String> p = cfg.params();
        String network = firstNonEmpty(p.get("type"), "tcp").toLowerCase();
        String security = normalizeSecurity(p.get("security"), "trojan".equals(cfg.scheme));
        String flow = p.get("flow");

        if ("vmess".equals(cfg.scheme)) {
            // У vmess транспорт и TLS лежат ВНУТРИ base64-JSON, а не в параметрах ссылки:
            // без этого «net»/«tls» из JSON не проверялись, и такой конфиг уходил в ядро,
            // которое отвечало «config error» (например, на удалённом h2).
            JSONObject v = vmessJson(cfg);
            String id = v.optString("id");
            if (id.isEmpty()) return "vmess: ссылка не разбирается (нет id)";
            network = firstNonEmpty(p.get("type"), v.optString("net"), "tcp").toLowerCase();
            if ("none".equals(network)) network = "tcp";
            if ("websocket".equals(network)) network = "ws";
            String tls = firstNonEmpty(p.get("security"), v.optString("tls"), "none").toLowerCase();
            security = "tls".equals(tls) ? "tls" : "none";
            flow = firstNonEmpty(p.get("flow"), v.optString("flow"));
        }

        if (REMOVED_NETWORKS.contains(network)) {
            return "транспорт «" + network + "» удалён из ядра Xray (его заменил xhttp)";
        }
        if (UNBUILDABLE_NETWORKS.contains(network)) {
            return "транспорт «" + network + "» приложение не строит";
        }
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
            if (!"tcp".equals(network) && !"raw".equals(network)) {
                return "flow xtls-rprx-vision работает только на tcp/raw";
            }
            if (!"tls".equals(security) && !"reality".equals(security)) {
                return "flow xtls-rprx-vision без TLS не работает";
            }
        }
        if ("reality".equals(security)) {
            // Xray: REALITY only supports RAW, XHTTP and gRPC — остальное падает уже в ядре.
            if (!"tcp".equals(network) && !"raw".equals(network) && !"xhttp".equals(network)
                    && !"splithttp".equals(network) && !"grpc".equals(network)) {
                return "reality работает только с tcp/xhttp/grpc";
            }
            String pbk = firstNonEmpty(p.get("pbk"), p.get("publicKey"));
            if (pbk.isEmpty()) return "reality без publicKey (pbk)";
            if (pbk.length() < 40) return "reality: publicKey короче 43 символов";
            if (firstNonEmpty(p.get("sni"), p.get("peer")).isEmpty()) return "reality без sni";
        }
        // Ядро Xray 26 не поднимает VLESS/Trojan без TLS к публичному адресу — проверяем
        // это ДО запуска ядра: иначе каждая такая попытка съедает дорожку замера и
        // засоряет лог километровой цепочкой infra/conf: serial: …
        // Правило касается только VLESS и Trojan: у vmess и Shadowsocks своё шифрование,
        // их ядро без TLS пускает.
        if (("vless".equals(cfg.scheme) || "trojan".equals(cfg.scheme))
                && !"tls".equals(security) && !"reality".equals(security)) {
            boolean vlessEncrypted = "vless".equals(cfg.scheme) && vlessEncryption(p) != null;
            if (!vlessEncrypted && !isPrivateHost(cfg.host) && !allowRelay) {
                return NO_TLS_REASON;
            }
        }
        if ("vless".equals(cfg.scheme)) {
            // пустой id отсекаем, но формат не проверяем: Xray переводит строку в UUID сам
            if (cfg.userInfo() == null || cfg.userInfo().isEmpty()) return "vless: нет id";
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
            // Конфиг без TLS к публичному адресу ядро не примет: подставляем локальный
            // ретранслятор — outbound идёт на 127.0.0.1, а dokodemo-door отдаёт
            // соединение настоящему серверу (дальше по правилам routing).
            final int relayPort = needsRelay(cfg) ? freePort() : 0;
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
            if (relayPort > 0) {
                JSONObject relay = new JSONObject();
                relay.put("tag", "relay-in")
                        .put("listen", "127.0.0.1")
                        .put("port", relayPort)
                        .put("protocol", "dokodemo-door")
                        .put("settings", new JSONObject()
                                .put("address", cfg.host)
                                .put("port", cfg.port)
                                .put("network", "tcp")
                                .put("followRedirect", false));
                inbounds.put(relay);
            }
            root.put("inbounds", inbounds);

            JSONArray outbounds = new JSONArray();
            JSONObject main = buildOutbound(cfg, allowInsecure, relayPort);
            if (main == null) {
                throw new IllegalArgumentException(cfg.unsupportedReason != null
                        ? cfg.unsupportedReason : "неподдерживаемый протокол: " + cfg.scheme);
            }
            outbounds.put(main);
            outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom")
                    .put("settings", new JSONObject()));
            outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole")
                    .put("settings", new JSONObject()));

            boolean desyncReady = false;
            if (desyncSocksPort > 0) {
                try {
                    JSONObject desync = new JSONObject();
                    desync.put("tag", "desync").put("protocol", "socks");
                    JSONObject server = new JSONObject()
                            .put("address", "127.0.0.1").put("port", desyncSocksPort);
                    desync.put("settings", new JSONObject()
                            .put("servers", new JSONArray().put(server)));
                    outbounds.put(desync);
                    desyncReady = true;

                    // При ретрансляторе proxySettings не нужен: ciadpi фрагментировал бы
                    // бессмысленный loopback-хоп. Вместо этого relay-in маршрутизируется в
                    // «desync», и DPI-обход работает на настоящем соединении с сервером.
                    if (relayPort <= 0) {
                        JSONObject stream = main.optJSONObject("streamSettings");
                        if (stream == null) {
                            stream = new JSONObject();
                            main.put("streamSettings", stream);
                        }
                        stream.put("proxySettings", new JSONObject().put("tag", "desync"));
                    }
                } catch (Exception e) {
                    android.util.Log.w("WhitelistBypass", "desync-outbound не добавлен", e);
                }
            }

            if (relayPort > 0) {
                JSONArray rules = new JSONArray();
                rules.put(new JSONObject()
                        .put("type", "field")
                        .put("inboundTag", new JSONArray().put("relay-in"))
                        .put("outboundTag", desyncReady ? "desync" : "direct"));
                root.put("routing", new JSONObject()
                        .put("domainStrategy", "AsIs")
                        .put("rules", rules));
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

    private static JSONObject buildOutbound(ProxyConfig cfg, boolean allowInsecureGlobal,
                                            int relayPort)
            throws org.json.JSONException {
        Map<String, String> p = cfg.params();
        // С ретранслятором outbound смотрит на loopback, настоящий адрес — у dokodemo.
        final String outHost = relayPort > 0 ? "127.0.0.1" : cfg.host;
        final int outPort = relayPort > 0 ? relayPort : cfg.port;
        String sni = firstNonEmpty(p.get("sni"), p.get("peer"), p.get("host"), cfg.host);
        String network = firstNonEmpty(p.get("type"), "tcp");
        String security = normalizeSecurity(p.get("security"), "trojan".equals(cfg.scheme));
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
                // encryption из ссылки (Xray 26: mlkem768x25519plus…) — с ним ядро
                // разрешает VLESS без TLS; иначе классическое «none».
                String enc = vlessEncryption(p);
                user.put("id", cfg.userInfo()).put("encryption", enc != null ? enc : "none");
                String flow = p.get("flow");
                if (flow != null && !flow.isEmpty()) user.put("flow", flow);
                users.put(user);
                vnext.put(new JSONObject()
                        .put("address", outHost).put("port", outPort).put("users", users));
                out.put("settings", new JSONObject().put("vnext", vnext));
                break;
            }
            case "trojan": {
                out.put("protocol", "trojan");
                if ("none".equals(security)) security = "tls";
                JSONArray servers = new JSONArray();
                servers.put(new JSONObject()
                        .put("address", outHost).put("port", outPort)
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
                        .put("address", outHost).put("port", outPort)
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
                        .put("address", outHost).put("port", outPort).put("users", users));
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

    /**
     * Разбор vmess-ссылки: base64 от JSON.
     *
     * Раньше здесь была ошибка в добивке «=»: для строк, чья длина не кратна 4 на 3,
     * добавлялось три «=» вместо одного, декодирование падало, JSON получался пустым —
     * и такой конфиг объявлялся «vmess: нет id», хотя он рабочий. Именно из-за этого
     * «Собрать пул» массово отбрасывал vmess-конфиги на этапе замера скорости.
     */
    private static JSONObject vmessJson(ProxyConfig cfg) {
        String raw = cfg == null || cfg.raw == null ? "" : cfg.raw.trim();
        int idx = raw.indexOf("://");
        if (idx < 0) return new JSONObject();
        String body = raw.substring(idx + 3);
        // отрезаем и #название, и ?параметры — в обоих случаях дальше base64 уже нет
        int cut = body.length();
        int hash = body.indexOf('#');
        if (hash >= 0) cut = hash;
        int q = body.indexOf('?');
        if (q >= 0 && q < cut) cut = q;
        body = body.substring(0, cut).trim();
        try {
            byte[] data = base64(body);
            String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            int brace = json.indexOf('{');
            if (brace > 0) json = json.substring(brace);
            int end = json.lastIndexOf('}');
            if (end > 0) json = json.substring(0, end + 1);
            return new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** base64 с правильной добивкой: 2 символа → «==», 3 символа → «=». */
    private static byte[] base64(String s) {
        String t = s.replace('-', '+').replace('_', '/');
        StringBuilder clean = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/') {
                clean.append(ch);
            }
        }
        String v = clean.toString();
        int pad = v.length() % 4;
        if (pad == 2) v = v + "==";
        else if (pad == 3) v = v + "=";
        return android.util.Base64.decode(v, android.util.Base64.DEFAULT);
    }

    private static JSONObject buildStream(String security, String network, String sni, String fp,
                                          String alpn, boolean allowInsecure, String path,
                                          String hostHeader, Map<String, String> p)
            throws org.json.JSONException {
        JSONObject stream = new JSONObject();
        String net = network == null || network.isEmpty() ? "tcp" : network.toLowerCase();
        if ("websocket".equals(net)) net = "ws";
        if ("raw".equals(net)) net = "tcp";   // Xray 26 принимает оба имени, старое надёжнее
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
                // Сюда попадать не должны (compatProblem отсекает раньше), но если попали —
                // не подсовываем ядру удалённый транспорт: XHTTP в режиме stream-one идёт
                // по тому же h2-соединению и хотя бы не ломает разбор конфига.
                stream.put("network", "xhttp");
                stream.put("xhttpSettings", new JSONObject()
                        .put("path", path != null && !path.isEmpty() ? path : "/")
                        .put("host", hostHeader != null ? hostHeader : "")
                        .put("mode", "stream-one"));
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
            if (callback != null) callback.onError(explainCoreError(e.getMessage()));
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
            throw new IllegalStateException(explainCoreError(e.getMessage()), e);
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

    /**
     * Короткое объяснение ошибки ядра. Цепочка «config error: infra/conf/serial: failed to
     * parse json config > … > …» бесполезна в логе: оставляем суть и режем длину.
     */
    public static String explainCoreError(String message) {
        if (message == null || message.trim().isEmpty()) return "ядро не приняло конфиг";
        String m = message.trim();
        String low = m.toLowerCase();
        if (low.contains("vless without tls") || low.contains("trojan without tls")) {
            return NO_TLS_REASON;
        }
        if (low.contains("reality only supports")) {
            return "REALITY работает только с raw/xhttp/grpc";
        }
        if (low.contains("unknown security")) return "ядро не знает такого типа шифрования";
        if (low.contains("unknown transport")) return "ядро не знает такого транспорта";
        if (low.contains("removed") || low.contains("legacy xtls")) {
            return "функция удалена из ядра Xray: " + m;
        }
        int pos = m.lastIndexOf("> ");
        if (pos >= 0) m = m.substring(pos + 2).trim();
        m = m.replace("config error: ", "").trim();
        if (m.length() > 140) m = m.substring(0, 140) + "…";
        return m;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }
}
