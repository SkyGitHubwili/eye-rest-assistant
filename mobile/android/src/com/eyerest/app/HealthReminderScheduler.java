package com.eyerest.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 连续使用提醒的低频调度器。
 *
 * <p>这里使用单次、非精确 Alarm；每次接收后再安排下一次。它不会启动常驻 Service，
 * 也不会为了统计使用情况进行高频轮询。</p>
 */
public final class HealthReminderScheduler {
    public static final String ACTION_CHECK_CONTINUOUS_USAGE =
        "com.eyerest.app.action.CHECK_CONTINUOUS_USAGE";
    public static final long CHECK_INTERVAL_MILLIS = 15L * 60L * 1000L;

    private static final long WINDOW_MILLIS = 3L * 60L * 1000L;
    private static final int REQUEST_CODE = 7401;

    private HealthReminderScheduler() {}

    /**
     * 根据当前权限和设置安排下一次检查；关闭提醒或失去使用情况访问权限时会取消。
     */
    public static void schedule(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        HealthSettings settings = new HealthSettings(appContext);
        if (!settings.isContinuousReminderEnabled() || !hasUsageAccess(appContext)) {
            cancel(appContext);
            return;
        }

        AlarmManager alarms = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long triggerAt = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MILLIS;
        alarms.setWindow(
            // 连续使用只会发生在设备唤醒期间；不为一次健康提醒额外唤醒休眠设备。
            AlarmManager.ELAPSED_REALTIME,
            triggerAt,
            WINDOW_MILLIS,
            pendingIntent(appContext, PendingIntent.FLAG_UPDATE_CURRENT)
        );
    }

    /** 设置变化或权限页面返回时调用；名字明确，便于 UI 层使用。 */
    public static void reschedule(Context context) {
        cancel(context);
        schedule(context);
    }

    /** 与健康页面设置保存逻辑兼容的别名。 */
    public static void update(Context context) {
        reschedule(context);
    }

    public static void cancel(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        AlarmManager alarms = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            PendingIntent existing = pendingIntent(appContext, PendingIntent.FLAG_NO_CREATE);
            if (existing != null) alarms.cancel(existing);
        }
    }

    /** Android 的“使用情况访问权限”由 AppOps 管理，普通运行时权限检查不足以判断。 */
    public static boolean hasUsageAccess(Context context) {
        return context != null && new UsageStatsRepository(context).hasUsageAccess();
    }

    private static PendingIntent pendingIntent(Context context, int extraFlag) {
        Intent intent = new Intent(context, HealthReminderReceiver.class)
            .setAction(ACTION_CHECK_CONTINUOUS_USAGE);
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            extraFlag | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
