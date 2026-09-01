package com.eyerest.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Full real-data summary for today and the most recent seven days. */
public final class HealthDataActivity extends Activity {
    private static final int GREEN = Color.rgb(45, 122, 89);
    private static final int INK = Color.rgb(24, 52, 42);
    private static final int MUTED = Color.rgb(111, 128, 120);
    private static final int BACKGROUND = Color.rgb(245, 247, 242);

    private HealthUsageManager manager;
    private ScrollView contentScroll;
    private LinearLayout statePanel;
    private TextView stateTitle;
    private TextView stateMessage;
    private Button stateAction;
    private TextView todayUsage;
    private TextView launchCount;
    private TextView longestContinuous;
    private LinearLayout topApps;
    private TextView topEmpty;
    private SevenDayChart chart;
    private TextView averageValue;
    private TextView maximumValue;
    private TextView minimumValue;
    private TextView weekComparison;
    private int requestGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        manager = new HealthUsageManager(this);
        setContentView(buildScreen());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshData();
    }

    @Override protected void onDestroy() {
        requestGeneration++;
        if (manager != null) manager.shutdown();
        super.onDestroy();
    }

    private View buildScreen() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);

        LinearLayout toolbar = row();
        toolbar.setPadding(dp(10), dp(6), dp(16), dp(6));
        Button back = button("‹", Color.TRANSPARENT, INK);
        back.setTextSize(32);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(54), dp(52)));
        TextView title = text("全部健康数据", 20, INK, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(toolbar);

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(10), dp(20), dp(28));
        contentScroll.addView(content);
        body.addView(contentScroll, new FrameLayout.LayoutParams(-1, -1));

        TextView todayTitle = text("今日", 22, INK, true);
        content.addView(todayTitle);
        LinearLayout todayCard = card();
        todayUsage = primaryMetric(todayCard, "总使用");
        LinearLayout todaySecondary = row();
        todaySecondary.setPadding(0, dp(18), 0, 0);
        launchCount = metricBlock(todaySecondary, "打开次数", true);
        longestContinuous = metricBlock(todaySecondary, "最长连续使用", false);
        todayCard.addView(todaySecondary);
        content.addView(todayCard, gapParams(10));

        LinearLayout appsCard = card();
        appsCard.addView(text("今日 Top Apps", 18, INK, true));
        TextView appsHint = text("点击应用可查看最近 7 天详情", 12, MUTED, false);
        appsHint.setPadding(0, dp(4), 0, dp(8));
        appsCard.addView(appsHint);
        topApps = column();
        appsCard.addView(topApps);
        topEmpty = text("暂无应用使用数据", 13, MUTED, false);
        topEmpty.setGravity(Gravity.CENTER);
        topEmpty.setPadding(0, dp(24), 0, dp(18));
        appsCard.addView(topEmpty);
        content.addView(appsCard, gapParams(14));

        TextView weekTitle = text("最近 7 天", 22, INK, true);
        LinearLayout.LayoutParams weekTitleParams = gapParams(24);
        content.addView(weekTitle, weekTitleParams);
        LinearLayout weekCard = card();
        TextView chartHint = text("每日总使用时间", 12, MUTED, false);
        chartHint.setPadding(0, 0, 0, dp(8));
        weekCard.addView(chartHint);
        chart = new SevenDayChart(this);
        weekCard.addView(chart, new LinearLayout.LayoutParams(-1, dp(220)));
        content.addView(weekCard, gapParams(10));

        LinearLayout statsRow = row();
        averageValue = smallMetricCard(statsRow, "日均", true);
        maximumValue = smallMetricCard(statsRow, "最多", false);
        minimumValue = smallMetricCard(statsRow, "最少", false);
        content.addView(statsRow, gapParams(12));

        LinearLayout compareCard = card();
        compareCard.addView(text("与上周比较", 13, MUTED, false));
        weekComparison = text("—", 18, INK, true);
        weekComparison.setPadding(0, dp(7), 0, 0);
        compareCard.addView(weekComparison);
        content.addView(compareCard, gapParams(12));

        statePanel = column();
        statePanel.setGravity(Gravity.CENTER);
        statePanel.setPadding(dp(30), dp(40), dp(30), dp(40));
        stateTitle = text("正在汇总健康数据…", 20, INK, true);
        stateTitle.setGravity(Gravity.CENTER);
        statePanel.addView(stateTitle);
        stateMessage = text("请稍候", 13, MUTED, false);
        stateMessage.setGravity(Gravity.CENTER);
        stateMessage.setPadding(0, dp(10), 0, dp(18));
        statePanel.addView(stateMessage);
        stateAction = button("重试", GREEN, Color.WHITE);
        stateAction.setVisibility(View.GONE);
        statePanel.addView(stateAction, new LinearLayout.LayoutParams(dp(150), dp(48)));
        body.addView(statePanel, new FrameLayout.LayoutParams(-1, -1));

        contentScroll.setVisibility(View.GONE);
        return root;
    }

    private void refreshData() {
        if (!manager.hasUsageAccess()) {
            showState("需要使用情况访问权限",
                "健康使用需要读取手机的应用使用时间，才能统计今日使用情况和 App 排行。",
                "去开启", this::openUsageSettings);
            return;
        }
        final int generation = ++requestGeneration;
        showLoading();
        manager.refresh(new HealthUsageManager.Callback<HealthModels.HealthSnapshot>() {
            @Override public void onSuccess(HealthModels.HealthSnapshot snapshot) {
                if (generation != requestGeneration || isFinishing()) return;
                if (snapshot == null || !snapshot.hasData) {
                    showState("暂无使用数据", "系统暂时没有返回可用的手机使用记录。", "重新读取",
                        HealthDataActivity.this::refreshData);
                    return;
                }
                bindSnapshot(snapshot);
            }

            @Override public void onError(Throwable error) {
                if (generation != requestGeneration || isFinishing()) return;
                if (error instanceof HealthUsageManager.PermissionDeniedException || !manager.hasUsageAccess()) {
                    showState("需要使用情况访问权限", "权限已关闭，无法读取真实的手机使用数据。", "去开启",
                        HealthDataActivity.this::openUsageSettings);
                } else {
                    showState("健康数据暂时不可用", "系统没有返回可用数据，请稍后重试。", "重试",
                        HealthDataActivity.this::refreshData);
                }
            }
        });
    }

    private void bindSnapshot(HealthModels.HealthSnapshot snapshot) {
        contentScroll.setVisibility(View.VISIBLE);
        statePanel.setVisibility(View.GONE);
        HealthModels.DayUsage today = snapshot.today;
        todayUsage.setText(formatDuration(today == null ? 0L : today.totalUsageMillis));
        if (today != null && today.continuousUsageAvailable) {
            longestContinuous.setText(formatDuration(today.longestContinuousMillis));
        } else {
            longestContinuous.setText("数据不可用");
        }
        if (today != null && today.launchCountsAvailable) launchCount.setText(today.totalLaunchCount + " 次");
        else launchCount.setText("数据不可用");

        bindTopApps(snapshot.topApps);
        chart.setDays(snapshot.last7Days);
        bindWeekSummary(snapshot.last7Days, snapshot.previous7Days);
    }

    private void bindTopApps(List<HealthModels.AppUsage> apps) {
        topApps.removeAllViews();
        topEmpty.setVisibility(apps == null || apps.isEmpty() ? View.VISIBLE : View.GONE);
        if (apps == null || apps.isEmpty()) return;
        long max = 0L;
        for (HealthModels.AppUsage app : apps) if (app != null) max = Math.max(max, app.usageMillis);
        int limit = Math.min(5, apps.size());
        for (int i = 0; i < limit; i++) {
            HealthModels.AppUsage app = apps.get(i);
            if (app == null) continue;
            topApps.addView(appRow(app, max, i), new LinearLayout.LayoutParams(-1, dp(74)));
        }
    }

    private View appRow(HealthModels.AppUsage app, long maximum, int index) {
        LinearLayout row = row();
        row.setPadding(0, dp(7), 0, dp(7));
        row.setBackground(selectableBackground());
        ImageView icon = new ImageView(this);
        Drawable drawable = manager.loadAppIcon(app.packageName);
        icon.setImageDrawable(drawable != null ? drawable : getDrawable(android.R.drawable.sym_def_app_icon));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = column();
        labels.setPadding(dp(12), 0, dp(10), 0);
        String name = TextUtils.isEmpty(app.appName) ? app.packageName : app.appName;
        TextView nameView = text((index + 1) + "  " + name, 14, INK, true);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(nameView);
        ProgressBar track = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        track.setMax(1000);
        track.setProgress(maximum <= 0L ? 0 : (int) Math.min(1000L, app.usageMillis * 1000L / maximum));
        track.setProgressTintList(ColorStateList.valueOf(GREEN));
        track.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(230, 237, 231)));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(-1, dp(6));
        trackParams.setMargins(0, dp(8), 0, 0);
        labels.addView(track, trackParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView duration = text(formatDuration(app.usageMillis), 13, GREEN, true);
        duration.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        duration.setSingleLine(true);
        row.addView(duration, new LinearLayout.LayoutParams(dp(82), dp(48)));
        row.setOnClickListener(v -> {
            Intent intent = new Intent(this, AppDetailActivity.class);
            intent.putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, app.packageName);
            startActivity(intent);
        });
        return row;
    }

    private void bindWeekSummary(List<HealthModels.DayUsage> current, List<HealthModels.DayUsage> previous) {
        List<HealthModels.DayUsage> days = current == null ? new ArrayList<>() : current;
        if (days.isEmpty()) {
            averageValue.setText("暂无数据");
            maximumValue.setText("暂无数据");
            minimumValue.setText("暂无数据");
            weekComparison.setText("暂无可比较数据");
            return;
        }

        long total = 0L;
        HealthModels.DayUsage maximum = null;
        HealthModels.DayUsage minimum = null;
        for (HealthModels.DayUsage day : days) {
            if (day == null) continue;
            total += Math.max(0L, day.totalUsageMillis);
            if (maximum == null || day.totalUsageMillis > maximum.totalUsageMillis) maximum = day;
            if (minimum == null || day.totalUsageMillis < minimum.totalUsageMillis) minimum = day;
        }
        averageValue.setText(formatDuration(total / Math.max(1, days.size())));
        maximumValue.setText(maximum == null ? "暂无数据" : shortDate(maximum.dayStartMillis) + "\n" + formatDuration(maximum.totalUsageMillis));
        minimumValue.setText(minimum == null ? "暂无数据" : shortDate(minimum.dayStartMillis) + "\n" + formatDuration(minimum.totalUsageMillis));

        long previousTotal = sumUsage(previous);
        if (previous == null || previous.isEmpty()) {
            weekComparison.setText("上周暂无可比较数据");
        } else if (previousTotal <= 0L) {
            weekComparison.setText(total <= 0L ? "与上周持平" : "上周暂无使用数据");
        } else {
            long percentage = Math.round(Math.abs(total - previousTotal) * 100.0 / previousTotal);
            if (total > previousTotal) weekComparison.setText("较上周增加 " + percentage + "%");
            else if (total < previousTotal) weekComparison.setText("较上周减少 " + percentage + "%");
            else weekComparison.setText("与上周持平");
        }
    }

    private long sumUsage(List<HealthModels.DayUsage> days) {
        long total = 0L;
        if (days != null) for (HealthModels.DayUsage day : days) if (day != null) total += Math.max(0L, day.totalUsageMillis);
        return total;
    }

    private void showLoading() {
        contentScroll.setVisibility(View.GONE);
        statePanel.setVisibility(View.VISIBLE);
        stateTitle.setText("正在汇总健康数据…");
        stateMessage.setText("请稍候");
        stateAction.setVisibility(View.GONE);
    }

    private void showState(String title, String message, String action, Runnable callback) {
        contentScroll.setVisibility(View.GONE);
        statePanel.setVisibility(View.VISIBLE);
        stateTitle.setText(title);
        stateMessage.setText(message);
        stateAction.setText(action);
        stateAction.setVisibility(View.VISIBLE);
        stateAction.setOnClickListener(v -> callback.run());
    }

    private void openUsageSettings() {
        try {
            startActivity(manager.createUsageAccessSettingsIntent());
        } catch (ActivityNotFoundException | SecurityException error) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception ignored) { showState("无法打开系统设置", "请在系统设置中手动开启“使用情况访问权限”。", "重试", this::openUsageSettings); }
        }
    }

    private TextView primaryMetric(LinearLayout parent, String label) {
        TextView labelView = text(label, 13, MUTED, false);
        labelView.setGravity(Gravity.CENTER);
        parent.addView(labelView);
        TextView value = text("—", 36, GREEN, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(5), 0, 0);
        parent.addView(value);
        return value;
    }

    private TextView metricBlock(LinearLayout parent, String label, boolean first) {
        LinearLayout block = column();
        TextView labelView = text(label, 12, MUTED, false);
        labelView.setGravity(Gravity.CENTER);
        block.addView(labelView);
        TextView value = text("—", 18, INK, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(6), 0, 0);
        block.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (!first) params.setMargins(dp(10), 0, 0, 0);
        parent.addView(block, params);
        return value;
    }

    private TextView smallMetricCard(LinearLayout parent, String label, boolean first) {
        LinearLayout box = card();
        box.setPadding(dp(8), dp(14), dp(8), dp(14));
        TextView labelView = text(label, 11, MUTED, false);
        labelView.setGravity(Gravity.CENTER);
        box.addView(labelView);
        TextView value = text("—", 13, INK, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(6), 0, 0);
        box.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (!first) params.setMargins(dp(8), 0, 0, 0);
        parent.addView(box, params);
        return value;
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(18), dp(18), dp(18), dp(18));
        view.setBackground(round(Color.WHITE, 20));
        view.setElevation(dp(1));
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

    private Drawable selectableBackground() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)) return getDrawable(value.resourceId);
        return round(Color.TRANSPARENT, 8);
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0L) return minutes > 0L ? hours + "小时" + minutes + "分钟" : hours + "小时";
        if (minutes > 0L) return minutes + "分钟";
        return seconds > 0L ? "不足1分钟" : "0分钟";
    }

    private String shortDate(long millis) {
        return new SimpleDateFormat("M/d", Locale.CHINA).format(new Date(millis));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SevenDayChart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat dayFormat = new SimpleDateFormat("E", Locale.CHINA);
        private List<HealthModels.DayUsage> days = new ArrayList<>();
        private final float density;

        SevenDayChart(Activity context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setContentDescription("最近 7 天总使用时间柱形图");
        }

        void setDays(List<HealthModels.DayUsage> value) {
            days = value == null ? new ArrayList<>() : new ArrayList<>(value);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = 4f * density;
            float right = getWidth() - 4f * density;
            float top = 28f * density;
            float bottom = getHeight() - 30f * density;
            paint.setStrokeWidth(density);
            paint.setColor(Color.rgb(221, 229, 223));
            canvas.drawLine(left, bottom, right, bottom, paint);
            if (days.isEmpty()) {
                drawCentered(canvas, "暂无趋势数据", getWidth() / 2f, getHeight() / 2f, 12, MUTED);
                return;
            }

            long max = 0L;
            for (HealthModels.DayUsage day : days) if (day != null) max = Math.max(max, day.totalUsageMillis);
            int count = Math.max(1, days.size());
            float slot = (right - left) / count;
            float barWidth = Math.min(25f * density, slot * .56f);
            for (int i = 0; i < days.size(); i++) {
                HealthModels.DayUsage day = days.get(i);
                long value = day == null ? 0L : Math.max(0L, day.totalUsageMillis);
                float height = max <= 0L ? 0f : (bottom - top) * value / max;
                if (value > 0L) height = Math.max(height, 3f * density);
                float center = left + slot * (i + .5f);
                paint.setColor(GREEN);
                canvas.drawRoundRect(new RectF(center - barWidth / 2f, bottom - height,
                    center + barWidth / 2f, bottom), 5f * density, 5f * density, paint);
                String label = day == null ? "" : dayFormat.format(new Date(day.dayStartMillis))
                    .replace("周", "").replace("星期", "");
                drawCentered(canvas, label, center, getHeight() - 9f * density, 11, MUTED);
            }
        }

        private void drawCentered(Canvas canvas, String text, float x, float baseline, int sp, int color) {
            paint.setColor(color);
            paint.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(text, x, baseline, paint);
        }
    }
}
