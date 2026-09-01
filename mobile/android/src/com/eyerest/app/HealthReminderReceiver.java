package com.eyerest.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 接收低频闹钟，读取真实 UsageEvents，并在达到阈值时发出一次休息提醒。 */
public final class HealthReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "health_continuous_usage";
    private static final int NOTIFICATION_ID = 7402;
    private static final int OPEN_HEALTH_REQUEST_CODE = 7403;
    private static final String PREFS = "settings";
    private static final String KEY_LAST_DAY = "health_reminder_last_day";
    private static final String KEY_LAST_SEGMENT_START = "health_reminder_last_segment_start";
    private static final ExecutorService QUERY_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
            || !HealthReminderScheduler.ACTION_CHECK_CONTINUOUS_USAGE.equals(intent.getAction())) return;

        Context appContext = context.getApplicationContext();
        HealthSettings settings = new HealthSettings(appContext);
        if (!settings.isContinuousReminderEnabled()
            || !HealthReminderScheduler.hasUsageAccess(appContext)) {
            HealthReminderScheduler.cancel(appContext);
            clearDeduplication(appContext);
            return;
        }

        final PendingResult pendingResult = goAsync();
        try {
            QUERY_EXECUTOR.execute(new Runnable() {
                @Override public void run() {
                    try {
                        checkAndNotify(appContext, settings);
                    } catch (RuntimeException ignored) {
                        // UsageStats 在部分 ROM 上可能临时不可用；不伪造数据，下个低频周期再尝试。
                    } finally {
                        HealthReminderScheduler.schedule(appContext);
                        pendingResult.finish();
                    }
                }
            });
        } catch (RuntimeException rejected) {
            HealthReminderScheduler.schedule(appContext);
            pendingResult.finish();
        }
    }

    private static void checkAndNotify(Context context, HealthSettings settings) {
        long now = System.currentTimeMillis();
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long todayStart = start.getTimeInMillis();

        UsageStatsRepository repository = new UsageStatsRepository(context);
        List<HealthModels.UsageEventRecord> events = repository.queryEventRecords(
            todayStart - 24L * 60L * 60L * 1000L,
            now
        );
        HealthModels.ContinuousUsage continuous = new UsageStatsCalculator()
            .calculateContinuousUsage(events, todayStart, now);

        long thresholdMillis = settings.getContinuousReminderMillis();
        if (continuous == null || continuous.currentStartMillis <= 0L
            || continuous.currentMillis < thresholdMillis) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(start.getTime());
        if (today.equals(prefs.getString(KEY_LAST_DAY, ""))
            && continuous.currentStartMillis == prefs.getLong(KEY_LAST_SEGMENT_START, -1L)) return;

        int usedMinutes = (int) Math.max(
            settings.getContinuousReminderMinutes(),
            continuous.currentMillis / 60_000L
        );
        if (!showNotification(context, usedMinutes)) return;

        prefs.edit()
            .putString(KEY_LAST_DAY, today)
            .putLong(KEY_LAST_SEGMENT_START, continuous.currentStartMillis)
            .apply();
    }

    /** 只有真正提交了通知才记录去重状态；通知权限未开时以后仍可再次尝试。 */
    private static boolean showNotification(Context context, int usedMinutes) {
        if (Build.VERSION.SDK_INT >= 33
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;

        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "连续使用提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("达到连续使用阈值时提醒休息");
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, MainActivity.class)
            .setAction("com.eyerest.app.action.OPEN_HEALTH")
            .putExtra("page", "health")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            context,
            OPEN_HEALTH_REQUEST_CODE,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("你已经连续使用手机 " + usedMinutes + " 分钟")
            .setContentText("休息一下吧。")
            .setStyle(new Notification.BigTextStyle().bigText(
                "你已经连续使用手机 " + usedMinutes + " 分钟\n\n休息一下吧。"
            ))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build();
        try {
            manager.notify(NOTIFICATION_ID, notification);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static void clearDeduplication(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LAST_DAY)
            .remove(KEY_LAST_SEGMENT_START)
            .apply();
    }
}
