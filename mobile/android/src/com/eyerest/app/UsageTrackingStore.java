package com.eyerest.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Small local store for the foreground tracker; no content or network data is saved. */
public final class UsageTrackingStore {
    private static final String PREFS = "usage_tracking";
    private static final String BASE_PREFIX = "base_";
    private static final String TRACK_PREFIX = "track_";
    private static final String START_PREFIX = "started_";
    private final SharedPreferences prefs;

    public UsageTrackingStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveBaseline(String day, Map<String, Long> values, long startedAt) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            editor.putLong(BASE_PREFIX + day + "_" + entry.getKey(), Math.max(0L, entry.getValue()));
        }
        editor.putLong(START_PREFIX + day, startedAt).apply();
    }

    public boolean hasBaseline(String day) { return prefs.contains(START_PREFIX + day); }

    public long baselineStart(String day) { return prefs.getLong(START_PREFIX + day, 0L); }

    public void addTracked(String day, String packageName, long millis) {
        if (packageName == null || packageName.length() == 0 || millis <= 0L) return;
        String key = TRACK_PREFIX + day + "_" + packageName;
        long old = prefs.getLong(key, 0L);
        prefs.edit().putLong(key, safeAdd(old, millis)).apply();
    }

    public Map<String, Long> readCombined(String day) {
        Map<String, Long> result = new HashMap<String, Long>();
        Map<String, ?> all = prefs.getAll();
        String base = BASE_PREFIX + day + "_";
        String track = TRACK_PREFIX + day + "_";
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof Long)) continue;
            if (key.startsWith(base)) {
                result.put(key.substring(base.length()), Math.max(0L, (Long) entry.getValue()));
            }
        }
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof Long) || !key.startsWith(track)) continue;
            String pkg = key.substring(track.length());
            long old = result.containsKey(pkg) ? result.get(pkg) : 0L;
            result.put(pkg, safeAdd(old, Math.max(0L, (Long) entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static long safeAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
