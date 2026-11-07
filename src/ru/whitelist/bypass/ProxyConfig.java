package ru.whitelist.bypass;

import android.util.Base64;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Распарсенная строка-подписка (vless/trojan/ss/vmess/…).
 * Хранит raw для передачи в ядро Xray и host/port для пинга.
 */
public class ProxyConfig {
    public final String raw;
    public final String scheme;
    public final String host;
    public final int port;
    public final String name;
    public final boolean supported;
    public final String unsupportedReason;
    public final String source;      // имя/url источника
    public final boolean embedded;   // из встроенного резерва
    public long latency = -1;        // мс, -1 = не измеряли / недоступен

    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z0-9]+)://");
    private static final Pattern HOSTPORT = Pattern.compile("^([^@/\\s?#]+)@(?:\\[([^\\]]+)\\]|([^:/?#\\s]+)):(\\d{1,5})");

    private ProxyConfig(String raw, String scheme, String host, int port, String name,
                        boolean supported, String reason, String source, boolean embedded) {
        this.raw = raw;
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.name = (name == null || name.isEmpty()) ? host + ":" + port : name;
        this.supported = supported;
        this.unsupportedReason = reason;
        this.source = source;
        this.embedded = embedded;
    }

    /** Разбор одной строки; null, если строка не конфиг. */
    public static ProxyConfig parse(String line, String source, boolean embedded) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) return null;

        Matcher sm = SCHEME.matcher(s);
        if (!sm.find()) return null;
        String scheme = sm.group(1).toLowerCase();
        String rest = s.substring(sm.end());

        String fragment = null;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = safeDecode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }

        try {
            switch (scheme) {
                case "vless":
                case "trojan":
                case "hysteria2":
                case "hy2":
                case "tuic": {
                    String authority = rest.split("\\?", 2)[0];
                    String[] hp = splitHostPort(authority);
                    String host = hp[0];
                    int port = Integer.parseInt(hp[1]);
                    boolean ok = "vless".equals(scheme) || "trojan".equals(scheme);
                    String reason = ok ? null : scheme + ": протокол не поддерживается ядром Xray";
                    return new ProxyConfig(s, scheme, host, port, fragment, ok, reason, source, embedded);
                }
                case "ss":
                case "shadowsocks": {
                    String authority = rest.split("\\?", 2)[0];
                    String host, portStr;
                    if (authority.contains("@")) {
                        String[] hp = splitHostPort(authority);
                        host = hp[0];
                        portStr = hp[1];
                    } else {
                        String decoded = new String(tryBase64(authority), StandardCharsets.UTF_8);
                        int at = decoded.lastIndexOf('@');
                        String hpPart = at >= 0 ? decoded.substring(at + 1) : decoded;
                        int colon = hpPart.lastIndexOf(':');
                        host = hpPart.substring(0, colon);
                        portStr = hpPart.substring(colon + 1);
                    }
                    return new ProxyConfig(s, "ss", host, Integer.parseInt(portStr), fragment, true, null, source, embedded);
                }
                case "vmess": {
                    String b64 = rest.split("\\?", 2)[0];
                    String json = new String(tryBase64(b64), StandardCharsets.UTF_8);
                    JSONObject o = new JSONObject(json);
                    String host = o.optString("add", o.optString("host"));
                    int port = o.optInt("port", 443);
                    String ps = o.optString("ps");
                    if (ps.isEmpty()) ps = fragment;
                    return new ProxyConfig(s, "vmess", host, port, ps, true, null, source, embedded);
                }
                default:
                    return new ProxyConfig(s, scheme, "", 0, fragment, false,
                            "схема «" + scheme + "» не поддерживается", source, embedded);
            }
        } catch (Throwable t) {
            return new ProxyConfig(s, scheme, "", 0, fragment, false,
                    "ошибка разбора: " + t.getMessage(), source, embedded);
        }
    }

    private static final Pattern SNI_RE = Pattern.compile(
            "[?&;](?:sni|peer|servername|serverName|server_name)=([^&\\s#]+)", Pattern.CASE_INSENSITIVE);

    /** Есть ли в конфиге TLS (vless security=tls/reality/xtls, trojan, любой sni=/peer= в запросе). */
    public boolean hasTls() {
        if (raw == null) return false;
        String r = raw.toLowerCase();
        if ("trojan".equals(scheme) || "vless".equals(scheme) || "hysteria2".equals(scheme)
                || "hy2".equals(scheme) || "tuic".equals(scheme)) {
            if (r.contains("security=tls") || r.contains("security=reality")
                    || r.contains("security=xtls") || r.contains("tls=1")) return true;
            if (SNI_RE.matcher(raw).find()) return true;
        }
        return "trojan".equals(scheme); // trojan по умолчанию — TLS
    }

    /** SNI для TLS-проверки (из sni=/peer=/servername=; для trojan по умолчанию host), иначе null. */
    public String tlsServerName() {
        if (raw != null) {
            Matcher m = SNI_RE.matcher(raw);
            if (m.find()) {
                String v = m.group(1);
                if (!v.isEmpty() && !"none".equalsIgnoreCase(v)) return v;
            }
        }
        return "trojan".equals(scheme) ? host : null;
    }

    /** userinfo@host:port → [host, port] */
    private static String[] splitHostPort(String authority) {
        Matcher m = HOSTPORT.matcher(authority);
        if (m.find()) {
            String host = m.group(2) != null ? m.group(2) : m.group(3);
            return new String[]{host, m.group(4)};
        }
        // без userinfo: host:port
        int at = authority.indexOf('@');
        String hp = at >= 0 ? authority.substring(at + 1) : authority;
        int colon = hp.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("нет host:port");
        return new String[]{hp.substring(0, colon), hp.substring(colon + 1)};
    }

    /** base64 с правильной добивкой: длина %4 == 2 → «==», %4 == 3 → «=». */
    private static byte[] tryBase64(String s) {
        String t = s.trim().replace('-', '+').replace('_', '/');
        int pad = t.length() % 4;
        if (pad == 2) t = t + "==";
        else if (pad == 3) t = t + "=";
        return Base64.decode(t, Base64.DEFAULT);
    }

    private static String safeDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Короткий идентификатор для UI/логов. */
    public String shortLabel() {
        return name + "  (" + scheme + " · " + host + ":" + port + ")";
    }

    /** Параметры query (?a=b&c=d) либо пустая map. */
    public Map<String, String> params() {
        Map<String, String> out = new HashMap<>();
        int q = raw.indexOf('?');
        if (q < 0) return out;
        int end = raw.indexOf('#', q);
        String query = end < 0 ? raw.substring(q + 1) : raw.substring(q + 1, end);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), safeDecode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    /** userinfo (uuid / password / method:pass) для vless/trojan (часть до @). */
    public String userInfo() {
        try {
            int schemeEnd = raw.indexOf("://") + 3;
            String rest = raw.substring(schemeEnd);
            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);
            int hash = rest.indexOf('#');
            if (hash >= 0) rest = rest.substring(0, hash);
            int at = rest.indexOf('@');
            return at > 0 ? URLDecoder.decode(rest.substring(0, at), "UTF-8") : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Данные Shadowsocks (method:password) с учётом base64url-кодирования SIP002.
     * Возвращает либо исходную строку (если уже method:password), либо декодированную base64.
     */
    public String ssUserInfo() {
        String info = userInfo();
        if (info.indexOf(':') > 0) return info; // уже method:password
        try {
            String t = info.replace('-', '+').replace('_', '/');
            int pad = t.length() % 4;
            if (pad > 0) t = t + "====".substring(pad);
            String decoded = new String(android.util.Base64.decode(t, android.util.Base64.DEFAULT),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (decoded.indexOf(':') > 0) return decoded;
            return info;
        } catch (Exception e) {
            return info;
        }
    }
}
