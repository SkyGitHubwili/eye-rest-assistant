package com.eyerest.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Records foreground package time locally so the app has a stable, auditable time source. */
public final class UsageTrackingService extends Service {
    private static final String CHANNEL = "usage_tracking";
    private static final int NOTIFICATION_ID = 71;
    private static final long TICK_MILLIS = 5000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UsageStatsManager usageStats;
    private PowerManager power;
    private UsageTrackingStore store;
    private String currentPackage;
    private long lastTick;

    public static void start(android.content.Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, UsageTrackingService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, TICK_MILLIS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        usageStats = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        power = (PowerManager) getSystemService(POWER_SERVICE);
        store = new UsageTrackingStore(this);
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        ensureBaseline();
        lastTick = System.currentTimeMillis();
        handler.post(ticker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        tick();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void tick() {
        long now = System.currentTimeMillis();
        ensureBaseline();
        if (lastTick <= 0L) lastTick = now;
        String observed = currentForeground();
        if (observed != null) currentPackage = observed;
        if (power != null && power.isInteractive() && currentPackage != null
            && !getPackageName().equals(currentPackage)) {
            long elapsed = Math.min(TICK_MILLIS * 2L, Math.max(0L, now - lastTick));
            if (elapsed > 0L) store.addTracked(dayKey(lastTick), currentPackage, elapsed);
        }
        lastTick = now;
    }

    private String currentForeground() {
        if (usageStats == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events;
        try { events = usageStats.queryEvents(Math.max(0L, now - 60_000L), now); }
        catch (RuntimeException ignored) { return null; }
        if (events == null) return null;
        UsageEvents.Event event = new UsageEvents.Event();
        String latest = null;
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) continue;
            int type = event.getEventType();
            String pkg = event.getPackageName();
            if (pkg == null || pkg.length() == 0) continue;
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == 1) latest = pkg;
            else if ((type == UsageEvents.Event.MOVE_TO_BACKGROUND || type == 23)
                && pkg.equals(latest)) latest = null;
            else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                || type == UsageEvents.Event.KEYGUARD_SHOWN) latest = null;
        }
        return latest;
    }

    private void ensureBaseline() {
        long now = System.currentTimeMillis();
        String day = dayKey(now);
        if (store.hasBaseline(day) || usageStats == null) return;
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(now);
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        Map<String, Long> values = new HashMap<String, Long>();
        try {
            List<UsageStats> stats = usageStats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, start.getTimeInMillis(), now);
            if (stats != null) for (UsageStats stat : stats) {
                if (stat != null && stat.getPackageName() != null) {
                    values.put(stat.getPackageName(), Math.max(0L, stat.getTotalTimeInForeground()));
                }
            }
        } catch (RuntimeException ignored) { }
        store.saveBaseline(day, values, now);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "使用时间记录", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification notification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(com.eyerest.app.R.mipmap.ic_launcher)
            .setContentTitle("使用时间记录中")
            .setContentText("仅记录 App 使用时长，数据保存在本机")
            .setOngoing(true).build();
    }

    private static String dayKey(long millis) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(millis));
    }
}
