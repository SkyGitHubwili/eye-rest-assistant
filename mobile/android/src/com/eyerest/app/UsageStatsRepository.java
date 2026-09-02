package com.eyerest.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Android UsageStats/UsageEvents 的唯一访问层。这里不生成或填充任何虚构数据。 */
public final class UsageStatsRepository {
    private static final String TAG = "HealthUsage";
    private static final long ACCESS_PROBE_CACHE_MILLIS = 15_000L;
    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;
    private volatile boolean lastUsageStatsQueryAvailable;
    private volatile boolean lastEventQueryAvailable;
    private volatile int lastAppOpsMode = AppOpsManager.MODE_DEFAULT;
    private volatile String lastQueryError = "";
    private volatile long accessProbeAtMillis;
    private volatile boolean accessProbeResult;

    public UsageStatsRepository(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.context = context.getApplicationContext();
        usageStatsManager = (UsageStatsManager) this.context.getSystemService(Context.USAGE_STATS_SERVICE);
        packageManager = this.context.getPackageManager();
    }

    /** 使用情况访问权限由 AppOps 管理，不是普通 runtime permission。 */
    public boolean hasUsageAccess() {
        long now = System.currentTimeMillis();
        if (now - accessProbeAtMillis < ACCESS_PROBE_CACHE_MILLIS) return accessProbeResult;
        lastAppOpsMode = readAppOpsMode(context);
        boolean result = lastAppOpsMode == AppOpsManager.MODE_ALLOWED
            || lastAppOpsMode == AppOpsManager.MODE_FOREGROUND;
        // A few vendor ROMs report MODE_DEFAULT even after the user enabled
        // this special access. A small real query makes the check resilient
        // without treating an empty, permission-denied result as permission.
        if (!result && lastAppOpsMode == AppOpsManager.MODE_DEFAULT && usageStatsManager != null) {
            long begin = now - 24L * 60L * 60L * 1000L;
            try {
                Map<String, UsageStats> probe = usageStatsManager
                    .queryAndAggregateUsageStats(begin, now);
                result = probe != null && !probe.isEmpty();
            } catch (RuntimeException error) {
                lastQueryError = error.getClass().getSimpleName();
            }
        }
        accessProbeResult = result;
        accessProbeAtMillis = now;
        return result;
    }

    /** Force the next permission check to observe a change made in Settings. */
    public void invalidateAccessCache() {
        accessProbeAtMillis = 0L;
    }

    public static boolean hasUsageAccess(Context context) {
        if (context == null) return false;
        return new UsageStatsRepository(context).hasUsageAccess();
    }

    private static int readAppOpsMode(Context context) {
        if (context == null) return AppOpsManager.MODE_ERRORED;
        try {
            Context appContext = context.getApplicationContext();
            AppOpsManager appOps = (AppOpsManager) appContext
                .getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return AppOpsManager.MODE_ERRORED;
            ApplicationInfo info = appContext.getApplicationInfo();
            int uid = info == null ? Process.myUid() : info.uid;
            if (Build.VERSION.SDK_INT >= 29) {
                return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    uid, appContext.getPackageName());
            }
            return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                uid, appContext.getPackageName());
        } catch (RuntimeException ignored) {
            return AppOpsManager.MODE_ERRORED;
        }
    }

    public Intent createUsageAccessSettingsIntent() {
        return createUsageAccessSettingsIntent(context);
    }

    public static Intent createUsageAccessSettingsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** 查询指定区间的 UsageStats，并按包名合并系统返回的桶。 */
    public List<HealthModels.AppUsageStatRecord> queryUsageStatsRecords(long beginMillis,
                                                                        long endMillis) {
        lastUsageStatsQueryAvailable = false;
        lastQueryError = "";
        if (usageStatsManager == null || endMillis <= beginMillis) {
            lastUsageStatsQueryAvailable = usageStatsManager != null;
            return Collections.emptyList();
        }
        List<UsageStats> raw = null;
        String source = "aggregate";
        try {
            Map<String, UsageStats> aggregate = usageStatsManager
                .queryAndAggregateUsageStats(beginMillis, endMillis);
            if (aggregate != null && !aggregate.isEmpty()) {
                raw = new ArrayList<UsageStats>(aggregate.values());
            }
        } catch (RuntimeException error) {
            lastQueryError = error.getClass().getSimpleName();
            Log.w(TAG, "queryAndAggregateUsageStats failed", error);
        }
        // Some vendor implementations expose the bucket API but return an
        // empty aggregate for a partial local day. Keep a bucket fallback.
        if (raw == null || raw.isEmpty()) {
            source = "buckets";
            try {
                raw = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST, beginMillis, endMillis);
            } catch (RuntimeException error) {
                lastQueryError = error.getClass().getSimpleName();
                Log.w(TAG, "queryUsageStats failed", error);
            }
        }
        Map<String, HealthModels.AppUsageStatRecord> merged =
            new HashMap<String, HealthModels.AppUsageStatRecord>();
        if (raw != null) {
            for (UsageStats stat : raw) {
                if (stat == null || stat.getPackageName() == null) continue;
                long usage = Math.max(0L, stat.getTotalTimeInForeground());
                long last = Math.max(0L, stat.getLastTimeUsed());
                HealthModels.AppUsageStatRecord old = merged.get(stat.getPackageName());
                if (old == null) {
                    merged.put(stat.getPackageName(), new HealthModels.AppUsageStatRecord(
                        stat.getPackageName(), usage, last));
                } else {
                    merged.put(stat.getPackageName(), new HealthModels.AppUsageStatRecord(
                        stat.getPackageName(), saturatingAdd(old.usageMillis, usage),
                        Math.max(old.lastTimeUsedMillis, last)));
                }
            }
        }
        List<HealthModels.AppUsageStatRecord> result =
            new ArrayList<HealthModels.AppUsageStatRecord>(merged.values());
        Collections.sort(result, new Comparator<HealthModels.AppUsageStatRecord>() {
            @Override public int compare(HealthModels.AppUsageStatRecord a,
                                          HealthModels.AppUsageStatRecord b) {
                int byUsage = Long.compare(b.usageMillis, a.usageMillis);
                return byUsage != 0 ? byUsage : a.packageName.compareTo(b.packageName);
            }
        });
        lastUsageStatsQueryAvailable = raw != null;
        Log.d(TAG, "usage query package=" + context.getPackageName()
            + " begin=" + beginMillis + " end=" + endMillis
            + " source=" + source + " records=" + result.size());
        return result;
    }

    /** 兼容性别名，便于调用方按 Android API 命名。 */
    public List<HealthModels.AppUsageStatRecord> queryUsageStats(long beginMillis, long endMillis) {
        return queryUsageStatsRecords(beginMillis, endMillis);
    }

    /** 查询真实 UsageEvents；返回按时间升序排列的轻量记录。 */
    public List<HealthModels.UsageEventRecord> queryEventRecords(long beginMillis,
                                                                 long endMillis) {
        lastEventQueryAvailable = false;
        List<HealthModels.UsageEventRecord> result =
            new ArrayList<HealthModels.UsageEventRecord>();
        if (usageStatsManager == null || endMillis <= beginMillis) {
            lastEventQueryAvailable = usageStatsManager != null;
            return result;
        }
        UsageEvents events;
        try {
            events = usageStatsManager.queryEvents(beginMillis, endMillis);
        } catch (RuntimeException error) {
            lastQueryError = error.getClass().getSimpleName();
            Log.w(TAG, "queryEvents failed", error);
            return result;
        }
        if (events == null) {
            lastEventQueryAvailable = true;
            return result;
        }
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) continue;
            String packageName = event.getPackageName();
            int type = event.getEventType();
            if (!isRelevantEventType(type)) continue;
            if ((packageName == null || packageName.length() == 0)
                && (type == 1 || type == 2 || type == 23)) continue;
            result.add(new HealthModels.UsageEventRecord(packageName,
                event.getTimeStamp(), normalizeEventType(type)));
        }
        Collections.sort(result, new Comparator<HealthModels.UsageEventRecord>() {
            @Override public int compare(HealthModels.UsageEventRecord a,
                                          HealthModels.UsageEventRecord b) {
                int byTime = Long.compare(a.timestampMillis, b.timestampMillis);
                return byTime != 0 ? byTime : Integer.compare(a.eventType, b.eventType);
            }
        });
        lastEventQueryAvailable = true;
        return result;
    }

    /** 别名：部分调用方以 events 命名。 */
    public List<HealthModels.UsageEventRecord> queryUsageEvents(long beginMillis, long endMillis) {
        return queryEventRecords(beginMillis, endMillis);
    }

    public boolean wasLastUsageStatsQueryAvailable() { return lastUsageStatsQueryAvailable; }
    public boolean wasLastEventQueryAvailable() { return lastEventQueryAvailable; }

    /** Short diagnostic string for an in-app support/debug surface. */
    public String getLastDiagnostics() {
        return "appOps=" + lastAppOpsMode + ",stats=" + lastUsageStatsQueryAvailable
            + ",events=" + lastEventQueryAvailable + ",error=" + lastQueryError;
    }

    public HealthModels.AppMetadata getAppMetadata(String packageName) {
        if (packageName == null || packageName.length() == 0) {
            return new HealthModels.AppMetadata("", "", false, false);
        }
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            String appName = label == null ? packageName : label.toString();
            Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
            // A package is user-facing only when Android exposes a launcher
            // entry. This removes background providers/services without a
            // brittle hard-coded package blacklist.
            boolean userFacing = !isCoreSystemPackage(packageName) && launchIntent != null;
            return new HealthModels.AppMetadata(packageName, appName, true, userFacing);
        } catch (PackageManager.NameNotFoundException ignored) {
            // A few Android 11+/vendor builds hide otherwise valid packages from
            // PackageManager even though UsageStatsManager still returns their
            // real usage.  Keep the record visible instead of turning a lookup
            // quirk into an empty health page.  Core shell packages are still
            // removed by UsageStatsCalculator's explicit package filter.
            return new HealthModels.AppMetadata(packageName, packageName, true,
                !isCoreSystemPackage(packageName));
        } catch (RuntimeException ignored) {
            return new HealthModels.AppMetadata(packageName, packageName, true,
                !isCoreSystemPackage(packageName));
        }
    }

    public Drawable loadAppIcon(String packageName) {
        if (packageName == null || packageName.length() == 0) return null;
        try {
            return packageManager.getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public Context getContext() { return context; }

    private static boolean isRelevantEventType(int type) {
        return type == 1 || type == 2 || type == 15 || type == 16 || type == 17 || type == 18
            || type == 21 || type == 22 || type == 23 || type == 26 || type == 27;
    }

    /** Packages which represent the shell/system plumbing, not user apps. */
    private static boolean isCoreSystemPackage(String packageName) {
        if (packageName == null) return true;
        return "android".equals(packageName)
            || "com.android.systemui".equals(packageName)
            || "com.android.settings".equals(packageName)
            || "com.android.launcher".equals(packageName)
            || packageName.startsWith("com.android.launcher.")
            || "com.google.android.permissioncontroller".equals(packageName)
            || "com.android.permissioncontroller".equals(packageName)
            || "com.android.packageinstaller".equals(packageName)
            || "com.android.providers.settings".equals(packageName);
    }

    private static int normalizeEventType(int type) {
        // Preserve the canonical foreground/background values for the calculator.
        if (type == 1 || type == 2) return type;
        return type;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
