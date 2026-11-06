package ru.whitelist.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** Кэш измеренных задержек (host:port -> ms). */
public final class LatencyStore {
    private static final String FILE = "latency_cache_v1";

    private final SharedPreferences prefs;

    public LatencyStore(Context ctx) {
        prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String key(String host, int port) {
        return host + ":" + port;
    }

    public long get(String host, int port) {
        return prefs.getLong(key(host, port), -1);
    }

    public void set(String host, int port, long ms) {
        prefs.edit().putLong(key(host, port), ms).apply();
    }

    public Map<String, Long> all() {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getValue() instanceof Long) out.put(e.getKey(), (Long) e.getValue());
        }
        return out;
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
