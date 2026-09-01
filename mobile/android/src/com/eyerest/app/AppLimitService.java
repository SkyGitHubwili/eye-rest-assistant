package com.eyerest.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import android.util.Log;

/** Best-effort on-device app limiter. Android does not expose a hard device-admin lock to normal apps. */
public final class AppLimitService extends Service {
    private static final String TAG = "AppLimit";
    private static final String CHANNEL = "health_app_limits";
    // Keep the restriction responsive without spinning a tight loop.
    private static final long CHECK_INTERVAL_MS = 250L;
    private final Handler handler = new Handler();
    private WindowManager windowManager;
    private View overlay;
    private String blockedPackage;
    private long overlayShownAt;

    public static void start(Context context) {
        Intent i = new Intent(context, AppLimitService.class);
        try { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i); else context.startService(i); }
        catch (RuntimeException ignored) {}
    }
    public static void stop(Context context) { try { context.stopService(new Intent(context, AppLimitService.class)); } catch (RuntimeException ignored) {} }

    @Override public void onCreate() { super.onCreate(); createChannel(); startForeground(31, notification()); handler.post(checker); Log.i(TAG, "service created"); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "service command limits=" + AppLimitStore.get(this).size());
        if (!AppLimitStore.hasEnabled(this)) { stopSelf(); return START_NOT_STICKY; }
        handler.removeCallbacks(checker); handler.post(checker); return START_STICKY;
    }
    private final Runnable checker = new Runnable() { @Override public void run() { check(); handler.postDelayed(this, CHECK_INTERVAL_MS); } };

    private void check() {
        if (!AppLimitStore.hasEnabled(this)) { removeOverlay(); stopSelf(); return; }
        if (!Settings.canDrawOverlays(this)) return;
        String foreground = currentForegroundPackage();
        Log.d(TAG, "foreground=" + foreground);
        // Showing our overlay can produce a newer UsageEvents record for this
        // package. Keep the block attached to the limited app until a real
        // different foreground package is observed.
        if (overlay != null && getPackageName().equals(foreground)) {
            foreground = blockedPackage;
        }
        if (overlay != null && foreground == null) {
            if (System.currentTimeMillis() - overlayShownAt < 30_000L) foreground = blockedPackage;
            else { removeOverlay(); return; }
        }
        AppLimit matched = null;
        for (AppLimit limit : AppLimitStore.get(this)) if (limit.enabled && limit.packageName.equals(foreground)) { matched = limit; break; }
        if (matched == null) { removeOverlay(); return; }
        long used = usageToday(matched.packageName);
        Log.d(TAG, "matched=" + matched.packageName + " used=" + used + " limit=" + matched.dailyLimitMillis);
        if (used >= matched.dailyLimitMillis) showOverlay(matched.packageName, matched.dailyLimitMillis);
        else removeOverlay();
    }

    private long usageToday(String pkg) {
        UsageStatsManager manager = (UsageStatsManager)getSystemService(USAGE_STATS_SERVICE); if (manager == null) return 0L;
        Calendar day = Calendar.getInstance(); day.set(Calendar.HOUR_OF_DAY,0); day.set(Calendar.MINUTE,0); day.set(Calendar.SECOND,0); day.set(Calendar.MILLISECOND,0);
        Map<String, UsageStats> stats = manager.queryAndAggregateUsageStats(day.getTimeInMillis(), System.currentTimeMillis());
        UsageStats value = stats == null ? null : stats.get(pkg); return value == null ? 0L : value.getTotalTimeInForeground();
    }

    private String currentForegroundPackage() {
        UsageStatsManager manager = (UsageStatsManager)getSystemService(USAGE_STATS_SERVICE); if (manager == null) return null;
        long now = System.currentTimeMillis(); UsageEvents events = manager.queryEvents(now - 60_000L, now);
        String current = null; long currentTime = -1L;
        if (events != null) {
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) { events.getNextEvent(event); int type = event.getEventType(); long time = event.getTimeStamp();
                // On Android, ACTIVITY_RESUMED shares value 1 with
                // MOVE_TO_FOREGROUND. ACTIVITY_STOPPED is 23 and must be
                // treated as background, never as a foreground signal.
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND && time >= currentTime) { current = event.getPackageName(); currentTime = time; }
                else if ((type == UsageEvents.Event.MOVE_TO_BACKGROUND || type == 23 || type == 24) && event.getPackageName().equals(current) && time >= currentTime) { current = null; currentTime = time; }
            }
        }
        if (current != null) return current;
        List<UsageStats> values = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now);
        if (values == null) return null;
        String best = null; long latest = 0L;
        for (UsageStats stat : values) {
            if (stat == null || getPackageName().equals(stat.getPackageName())) continue;
            if (stat.getLastTimeUsed() > latest) { latest = stat.getLastTimeUsed(); best = stat.getPackageName(); }
        }
        return latest > 0L && now - latest <= 30_000L ? best : null;
    }

    private void showOverlay(String pkg, long limit) {
        if (overlay != null && pkg.equals(blockedPackage)) return;
        removeOverlay(); blockedPackage = pkg; overlayShownAt = System.currentTimeMillis(); windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.argb(245, 18, 32, 27));
        TextView text = new TextView(this); text.setText("今日使用时间已达到上限\n\n请明天再使用"); text.setTextColor(Color.WHITE); text.setTextSize(22); text.setGravity(Gravity.CENTER); text.setPadding(40,40,40,40);
        root.addView(text, new FrameLayout.LayoutParams(-1,-1));
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1,-1,type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP|Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { windowManager.addView(root, lp); overlay = root; Log.d(TAG, "overlay shown for " + pkg); }
        catch (RuntimeException error) { Log.e(TAG, "overlay failed", error); }
    }
    private void removeOverlay() { if (overlay != null && windowManager != null) { try { windowManager.removeView(overlay); } catch (RuntimeException ignored) {} } overlay = null; blockedPackage = null; }
    private void createChannel() { if (Build.VERSION.SDK_INT >= 26) { NotificationChannel c = new NotificationChannel(CHANNEL,"应用使用限制",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(c); } }
    private Notification notification() { return new Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("应用使用限制已开启").setContentText("达到每日上限后会显示限制提示").setOngoing(true).build(); }
    @Override public void onDestroy() { handler.removeCallbacks(checker); removeOverlay(); super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
