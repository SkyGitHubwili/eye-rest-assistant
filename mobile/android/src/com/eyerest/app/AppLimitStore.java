package com.eyerest.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists the user's per-app daily limits without inventing usage data. */
public final class AppLimitStore {
    private static final String KEY = "health_app_limits_v1";
    private AppLimitStore() {}

    public static List<AppLimit> get(Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        String raw = p.getString(KEY, "");
        List<AppLimit> result = new ArrayList<AppLimit>();
        if (raw != null) for (String line : raw.split("\\n")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length < 3 || parts[0].length() == 0) continue;
            try { result.add(new AppLimit(parts[0], Long.parseLong(parts[1]) * 60000L, "1".equals(parts[2]), 0, true)); }
            catch (RuntimeException ignored) {}
        }
        return result;
    }

    public static void upsert(Context context, AppLimit value) {
        List<AppLimit> values = get(context); boolean replaced = false;
        for (int i = 0; i < values.size(); i++) if (values.get(i).packageName.equals(value.packageName)) { values.set(i, value); replaced = true; break; }
        if (!replaced) values.add(value);
        save(context, values);
    }

    public static void remove(Context context, String packageName) {
        List<AppLimit> values = get(context);
        for (int i = values.size() - 1; i >= 0; i--) if (values.get(i).packageName.equals(packageName)) values.remove(i);
        save(context, values);
    }

    public static void save(Context context, List<AppLimit> values) {
        StringBuilder out = new StringBuilder();
        if (values != null) for (AppLimit v : values) if (v != null) {
            if (out.length() > 0) out.append('\n');
            out.append(v.packageName).append('\t').append(v.dailyLimitMillis / 60000L).append('\t').append(v.enabled ? '1' : '0');
        }
        context.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, out.toString()).apply();
    }

    public static boolean hasEnabled(Context context) {
        for (AppLimit v : get(context)) if (v.enabled && v.dailyLimitMillis > 0) return true;
        return false;
    }
}
