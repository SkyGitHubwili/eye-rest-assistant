package com.eyerest.app;

import android.app.Activity;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only diagnostics for comparing the statistics exposed by Android.
 * This screen deliberately does not feed any value back into HealthUsage.
 */
public final class UsageDebugActivity extends Activity {
    private static final String TAG = "UsageDebug";
    private static final int GREEN = Color.rgb(45, 122, 89);
    private static final int INK = Color.rgb(24, 52, 42);
    private static final int MUTED = Color.rgb(105, 122, 114);
    private static final int BACKGROUND = Color.rgb(245, 247, 242);
    private static final String[] PACKAGES = {
        "com.xingin.xhs", "com.tencent.mm", "tv.danmaku.bili", "com.quark.browser"
    };
    private static final String[] NAMES = {"小红书", "微信", "哔哩哔哩", "夸克"};

    private LinearLayout content;
    private TextView state;
    private UsageStatsManager usageStatsManager;
    private UsageStatsRepository repository;
    private UsageStatsCalculator calculator;
    private final android.os.Handler mainHandler = new android.os.Handler();
    private final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        usageStatsManager = (UsageStatsManager)getSystemService(Context.USAGE_STATS_SERVICE);
        repository = new UsageStatsRepository(this);
        calculator = new UsageStatsCalculator();
        setContentView(buildScreen());
        load();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View buildScreen() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout toolbar = row();
        toolbar.setPadding(dp(10), dp(6), dp(14), dp(6));
        Button back = button("‹", Color.TRANSPARENT, INK);
        back.setTextSize(32);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(54), dp(52)));
        TextView title = text("统计对照（Debug）", 20, INK, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button refresh = button("刷新", Color.rgb(229, 241, 232), GREEN);
        refresh.setOnClickListener(v -> load());
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(78), dp(46)));
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(18), dp(8), dp(18), dp(28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        state = text("正在读取 Android 统计…", 14, MUTED, false);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dp(30), 0, dp(20));
        content.addView(state);
        return root;
    }

    private void load() {
        if (content == null) return;
        content.removeAllViews();
        state = text("正在读取 Android 统计…", 14, MUTED, false);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dp(30), 0, dp(20));
        content.addView(state);
        executor.execute(() -> {
            final DebugSnapshot result;
            try {
                result = readSnapshot();
            } catch (Throwable error) {
                mainHandler.post(() -> showError(error));
                return;
            }
            mainHandler.post(() -> bind(result));
        });
    }

    private DebugSnapshot readSnapshot() {
        if (!repository.hasUsageAccess()) throw new SecurityException("未开启使用情况访问权限");
        long now = System.currentTimeMillis();
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTimeInMillis(now);
        startCalendar.set(Calendar.HOUR_OF_DAY, 0);
        startCalendar.set(Calendar.MINUTE, 0);
        startCalendar.set(Calendar.SECOND, 0);
        startCalendar.set(Calendar.MILLISECOND, 0);
        long start = startCalendar.getTimeInMillis();
        Map<String, UsageStats> aggregateRaw = usageStatsManager == null
            ? Collections.emptyMap()
            : usageStatsManager.queryAndAggregateUsageStats(start, now);
        List<UsageStats> dailyRaw = usageStatsManager == null
            ? Collections.emptyList()
            : usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now);
        Map<String, UsageStats> dailyByPackage = new HashMap<String, UsageStats>();
        if (dailyRaw != null) {
            for (UsageStats value : dailyRaw) {
                if (value == null || value.getPackageName() == null) continue;
                UsageStats old = dailyByPackage.get(value.getPackageName());
                if (old == null) dailyByPackage.put(value.getPackageName(), value);
                else dailyByPackage.put(value.getPackageName(), mergeStats(old, value));
            }
        }
        List<HealthModels.UsageEventRecord> events = repository.queryEventRecords(start, now);
        Map<String, Long> eventDurations = eventDurations(events, start, now);
        long screen = screenInteractiveDuration(events, start, now);
        DebugSnapshot snapshot = new DebugSnapshot(start, now, aggregateRaw, dailyByPackage,
            eventDurations, screen);
        logSnapshot(snapshot);
        return snapshot;
    }

    /** UsageStats is immutable from the caller's perspective; merge only the fields needed here. */
    private UsageStats mergeStats(UsageStats left, UsageStats right) {
        UsageStats merged = new UsageStats(left);
        merged.add(right);
        return merged;
    }

    private Map<String, Long> eventDurations(List<HealthModels.UsageEventRecord> events,
                                             long start, long end) {
        Map<String, Long> result = new HashMap<String, Long>();
        for (HealthModels.UsageInterval interval : calculator.buildIntervals(events, start, end)) {
            long from = Math.max(start, interval.startMillis);
            long to = Math.min(end, interval.endMillis);
            if (to <= from) continue;
            Long old = result.get(interval.packageName);
            result.put(interval.packageName, (old == null ? 0L : old) + (to - from));
        }
        return result;
    }

    private long screenInteractiveDuration(List<HealthModels.UsageEventRecord> events,
                                           long start, long end) {
        boolean active = false;
        long activeStart = 0L;
        long total = 0L;
        if (events != null) for (HealthModels.UsageEventRecord event : events) {
            if (event == null || event.timestampMillis > end) break;
            if (event.eventType == HealthModels.UsageEventRecord.TYPE_SCREEN_INTERACTIVE) {
                if (!active) { active = true; activeStart = Math.max(start, event.timestampMillis); }
            } else if (event.eventType == HealthModels.UsageEventRecord.TYPE_SCREEN_NON_INTERACTIVE
                && active) {
                total += Math.max(0L, Math.min(end, event.timestampMillis) - activeStart);
                active = false;
            }
        }
        if (active) total += Math.max(0L, end - activeStart);
        return Math.min(Math.max(0L, end - start), total);
    }

    /**
     * Keep a machine-readable line for each target package.  The final value
     * is deliberately assigned from the raw aggregate UsageStats duration;
     * eventDuration is diagnostic-only and can never replace it.
     */
    private void logSnapshot(DebugSnapshot snapshot) {
        Log.d(TAG, "rangeStart=" + snapshot.start + ",rangeEnd=" + snapshot.end
            + ",screenInteractiveTime=" + snapshot.screen
            + " (independent; not distributed to apps)");
        for (int i = 0; i < PACKAGES.length; i++) {
            String packageName = PACKAGES[i];
            UsageStats aggregate = snapshot.aggregate.get(packageName);
            UsageStats daily = snapshot.daily.get(packageName);
            long usageStatsDuration = aggregate == null
                ? 0L : Math.max(0L, aggregate.getTotalTimeInForeground());
            long dailyBucketDuration = daily == null
                ? 0L : Math.max(0L, daily.getTotalTimeInForeground());
            long eventDuration = value(snapshot.events, packageName);
            long finalDuration = usageStatsDuration;
            long visibleDuration = totalTimeVisible(aggregate);
            Log.d(TAG, "packageName=" + packageName
                + ",UsageStats duration=" + usageStatsDuration
                + ",eventDuration=" + eventDuration
                + ",final duration=" + finalDuration
                + ",finalEqualsUsageStats=" + (finalDuration == usageStatsDuration)
                + ",aggregateDuration=" + usageStatsDuration
                + ",dailyBucketDuration=" + dailyBucketDuration
                + ",screenInteractiveTime=" + snapshot.screen
                + ",totalTimeVisible=" + visibleDuration);
        }
    }

    private void bind(DebugSnapshot snapshot) {
        content.removeAllViews();
        TextView range = text("范围：" + dateTime(snapshot.start) + " → " + dateTime(snapshot.end),
            12, MUTED, false);
        range.setPadding(0, dp(4), 0, dp(6));
        content.addView(range);
        TextView note = text("只读对照页，不会改变健康使用页面的最终统计。\n这里显示 Android 公开 UsageStats 原始值；系统设置页面本身没有公开可直接读取的独立接口。",
            12, MUTED, false);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(0, 0, 0, dp(12));
        content.addView(note);

        LinearLayout screenCard = card();
        screenCard.addView(text("屏幕活跃时间（独立指标）", 16, INK, true));
        TextView screenValue = text(format(snapshot.screen), 25, GREEN, true);
        screenValue.setPadding(0, dp(8), 0, dp(2));
        screenCard.addView(screenValue);
        screenCard.addView(text("来自屏幕交互事件，仅作对照，不会分摊或压缩任何 App duration。",
            12, MUTED, false));
        content.addView(screenCard, gapParams(4));

        content.addView(text("重点 App：UsageStats 与事件对照", 16, INK, true), gapParams(16));
        for (int i = 0; i < PACKAGES.length; i++) {
            String pkg = PACKAGES[i];
            UsageStats aggregate = snapshot.aggregate.get(pkg);
            UsageStats daily = snapshot.daily.get(pkg);
            long aggregateDuration = aggregate == null ? 0L : aggregate.getTotalTimeInForeground();
            long visibleDuration = totalTimeVisible(aggregate);
            long dailyDuration = daily == null ? 0L : daily.getTotalTimeInForeground();
            long eventDuration = value(snapshot.events, pkg);
            LinearLayout appCard = card();
            TextView appTitle = text(NAMES[i], 17, INK, true);
            appCard.addView(appTitle);
            TextView packageView = text(pkg, 11, MUTED, false);
            packageView.setPadding(0, dp(3), 0, dp(10));
            appCard.addView(packageView);
            addMetric(appCard, "aggregate · UsageStats.getTotalTimeInForeground()",
                aggregateDuration);
            addMetric(appCard, "daily bucket · queryUsageStats(INTERVAL_DAILY)", dailyDuration);
            addMetric(appCard, "eventDuration · UsageEvents（仅辅助）", eventDuration);
            addMetric(appCard, "screenInteractiveTime · 全局屏幕对照", snapshot.screen);
            addMetric(appCard, "totalTimeVisible · 系统可见（辅助）", visibleDuration);
            addMetric(appCard, "final duration · 当前页面最终值", aggregateDuration);
            TextView equality = text(aggregateDuration == dailyDuration
                ? "✓ aggregate 与 daily bucket 一致；final == UsageStats"
                : "请注意：aggregate 与 daily bucket 不一致",
                12, aggregateDuration == dailyDuration ? GREEN : Color.rgb(177, 109, 35), true);
            equality.setPadding(0, dp(9), 0, 0);
            appCard.addView(equality);
            content.addView(appCard, gapParams(10));
        }

        LinearLayout details = card();
        details.addView(text("UsageStats 其他原始字段", 16, INK, true));
        for (int i = 0; i < PACKAGES.length; i++) {
            UsageStats stat = snapshot.aggregate.get(PACKAGES[i]);
            if (stat == null) continue;
            TextView line = text(NAMES[i] + "：最后使用 " + dateTime(stat.getLastTimeUsed())
                + "；最后可见 " + dateTime(lastTimeVisible(stat)) + "；前台服务 "
                + format(foregroundServiceDuration(stat)), 12, MUTED, false);
            line.setPadding(0, dp(8), 0, 0);
            details.addView(line);
        }
        content.addView(details, gapParams(14));
        TextView foot = text("最终显示值应与 UsageStats前台一致；本页只用于判断系统字段与参考 App 的差异。",
            12, MUTED, false);
        foot.setPadding(0, dp(12), 0, 0);
        content.addView(foot);
    }

    private long foregroundServiceDuration(UsageStats stat) {
        if (stat == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L;
        return Math.max(0L, stat.getTotalTimeForegroundServiceUsed());
    }

    /** totalTimeVisible/lastTimeVisible were added in API 29; keep this
     * diagnostics screen usable on the app's Android 8+ minimum SDK. */
    private long totalTimeVisible(UsageStats stat) {
        if (stat == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L;
        return Math.max(0L, stat.getTotalTimeVisible());
    }

    private long lastTimeVisible(UsageStats stat) {
        if (stat == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L;
        return Math.max(0L, stat.getLastTimeVisible());
    }

    private void addMetric(LinearLayout card, String label, long millis) {
        LinearLayout line = row();
        line.setPadding(0, dp(5), 0, dp(5));
        TextView name = text(label, 12, MUTED, false);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(format(millis), 13, INK, true);
        value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        line.addView(value, new LinearLayout.LayoutParams(dp(132), -2));
        card.addView(line);
    }

    private void showError(Throwable error) {
        content.removeAllViews();
        content.addView(text("无法读取统计", 19, INK, true));
        TextView message = text(error instanceof SecurityException
            ? "请先开启使用情况访问权限，再返回此页面。"
            : "系统统计接口暂时不可用：" + error.getClass().getSimpleName(), 13, MUTED, false);
        message.setPadding(0, dp(10), 0, dp(18));
        content.addView(message);
        Button retry = button("重试", GREEN, Color.WHITE);
        retry.setOnClickListener(v -> load());
        content.addView(retry, new LinearLayout.LayoutParams(dp(150), dp(48)));
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackground(round(Color.WHITE, 16));
        return view;
    }

    private LinearLayout.LayoutParams gapParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(top), 0, 0);
        return params;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value, int background, int foreground) {
        Button view = new Button(this);
        view.setText(value);
        view.setTextColor(foreground);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setBackground(round(background, 12));
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String format(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remain = seconds % 60L;
        if (hours > 0L) return hours + "小时" + minutes + "分" + remain + "秒";
        if (minutes > 0L) return minutes + "分" + remain + "秒";
        return remain + "秒";
    }

    private String dateTime(long millis) {
        return new SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(new Date(millis));
    }

    private static long value(Map<String, Long> values, String key) {
        Long result = values == null ? null : values.get(key);
        return result == null ? 0L : Math.max(0L, result);
    }

    private static final class DebugSnapshot {
        final long start;
        final long end;
        final Map<String, UsageStats> aggregate;
        final Map<String, UsageStats> daily;
        final Map<String, Long> events;
        final long screen;

        DebugSnapshot(long start, long end, Map<String, UsageStats> aggregate,
                      Map<String, UsageStats> daily, Map<String, Long> events, long screen) {
            this.start = start;
            this.end = end;
            this.aggregate = aggregate == null ? Collections.emptyMap() : aggregate;
            this.daily = daily == null ? Collections.emptyMap() : daily;
            this.events = events == null ? Collections.emptyMap() : events;
            this.screen = Math.max(0L, screen);
        }
    }
}
