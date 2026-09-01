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
import android.os.Process;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Android UsageStats/UsageEvents 的唯一访问层。这里不生成或填充任何虚构数据。 */
public final class UsageStatsRepository {
    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;
    private volatile boolean lastUsageStatsQueryAvailable;
    private volatile boolean lastEventQueryAvailable;

    public UsageStatsRepository(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.context = context.getApplicationContext();
        usageStatsManager = (UsageStatsManager) this.context.getSystemService(Context.USAGE_STATS_SERVICE);
        packageManager = this.context.getPackageManager();
    }

    /** 使用情况访问权限由 AppOps 管理，不是普通 runtime permission。 */
    public boolean hasUsageAccess() { return hasUsageAccess(context); }

    public static boolean hasUsageAccess(Context context) {
        if (context == null) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) context.getApplicationContext()
                .getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException ignored) {
            return false;
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
        if (usageStatsManager == null || endMillis <= beginMillis) {
            lastUsageStatsQueryAvailable = usageStatsManager != null;
            return Collections.emptyList();
        }
        List<UsageStats> raw = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, beginMillis, endMillis);
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
        lastUsageStatsQueryAvailable = true;
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
        UsageEvents events = usageStatsManager.queryEvents(beginMillis, endMillis);
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

    public HealthModels.AppMetadata getAppMetadata(String packageName) {
        if (packageName == null || packageName.length() == 0) {
            return new HealthModels.AppMetadata("", "", false, false);
        }
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            String appName = label == null ? packageName : label.toString();
            Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
            boolean userFacing = launchIntent != null
                || (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            return new HealthModels.AppMetadata(packageName, appName, true, userFacing);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Usage data may remain after uninstall; retain the package as a truthful fallback.
            return new HealthModels.AppMetadata(packageName, packageName, false, false);
        } catch (RuntimeException ignored) {
            return new HealthModels.AppMetadata(packageName, packageName, false, false);
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
            || type == 23 || type == 26 || type == 27;
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
