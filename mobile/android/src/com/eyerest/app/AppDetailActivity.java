package com.eyerest.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Displays real UsageStats data for one installed application. */
public final class AppDetailActivity extends Activity {
    public static final String EXTRA_PACKAGE_NAME = "package_name";

    private static final int GREEN = Color.rgb(45, 122, 89);
    private static final int INK = Color.rgb(24, 52, 42);
    private static final int MUTED = Color.rgb(111, 128, 120);
    private static final int BACKGROUND = Color.rgb(245, 247, 242);

    private HealthUsageManager manager;
    private String packageName;
    private ScrollView contentScroll;
    private LinearLayout statePanel;
    private TextView stateTitle;
    private TextView stateMessage;
    private Button stateAction;
    private ImageView appIcon;
    private TextView appName;
    private TextView packageLabel;
    private TextView todayValue;
    private TextView yesterdayValue;
    private TextView averageValue;
    private TextView peakValue;
    private TextView launchesValue;
    private TextView sessionValue;
    private SevenDayChart chart;
    private int requestGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        manager = new HealthUsageManager(this);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        setContentView(buildScreen());

        if (TextUtils.isEmpty(packageName)) {
            showState("无法打开应用详情", "没有收到要查看的应用信息。", "返回", this::finish);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!TextUtils.isEmpty(packageName)) refreshData();
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
        TextView title = text("应用使用详情", 20, INK, true);
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

        LinearLayout identity = card();
        identity.setGravity(Gravity.CENTER_HORIZONTAL);
        appIcon = new ImageView(this);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        identity.addView(appIcon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        appName = text("", 24, INK, true);
        appName.setGravity(Gravity.CENTER);
        appName.setPadding(0, dp(12), 0, 0);
        identity.addView(appName);
        packageLabel = text("", 11, MUTED, false);
        packageLabel.setGravity(Gravity.CENTER);
        packageLabel.setPadding(0, dp(4), 0, 0);
        identity.addView(packageLabel);
        content.addView(identity, gapParams(0));

        LinearLayout todayCard = card();
        TextView todayLabel = text("今日使用", 14, MUTED, false);
        todayLabel.setGravity(Gravity.CENTER);
        todayCard.addView(todayLabel);
        todayValue = text("—", 38, GREEN, true);
        todayValue.setGravity(Gravity.CENTER);
        todayValue.setPadding(0, dp(5), 0, dp(4));
        todayCard.addView(todayValue);
        content.addView(todayCard, gapParams(14));

        LinearLayout comparison = row();
        yesterdayValue = metricCard(comparison, "昨天", true);
        averageValue = metricCard(comparison, "7 日平均", false);
        content.addView(comparison, gapParams(12));

        LinearLayout trendCard = card();
        trendCard.addView(text("最近 7 天", 18, INK, true));
        TextView chartHint = text("每日实际前台使用时长", 12, MUTED, false);
        chartHint.setPadding(0, dp(4), 0, dp(8));
        trendCard.addView(chartHint);
        chart = new SevenDayChart(this);
        trendCard.addView(chart, new LinearLayout.LayoutParams(-1, dp(210)));
        peakValue = text("最高使用日：—", 13, MUTED, false);
        peakValue.setPadding(0, dp(10), 0, 0);
        trendCard.addView(peakValue);
        content.addView(trendCard, gapParams(14));

        LinearLayout openingCard = card();
        openingCard.addView(text("今日打开情况", 18, INK, true));
        LinearLayout openingMetrics = row();
        openingMetrics.setPadding(0, dp(14), 0, 0);
        launchesValue = metricBlock(openingMetrics, "打开次数", true);
        sessionValue = metricBlock(openingMetrics, "平均每次", false);
        openingCard.addView(openingMetrics);
        TextView eventHint = text("打开次数根据系统 UsageEvents 估算；部分系统可能不提供此项数据。", 11, MUTED, false);
        eventHint.setPadding(0, dp(12), 0, 0);
        openingCard.addView(eventHint);
        content.addView(openingCard, gapParams(14));

        statePanel = column();
        statePanel.setGravity(Gravity.CENTER);
        statePanel.setPadding(dp(30), dp(40), dp(30), dp(40));
        stateTitle = text("正在读取使用数据…", 20, INK, true);
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
        // Permission may have been changed in Settings while this detail page
        // was paused, so force a fresh AppOps observation before querying.
        manager.invalidateUsageAccessCache();
        // Invalidate callbacks from a previous query before handling the new
        // permission state, preventing stale data from resurfacing on return.
        final int generation = ++requestGeneration;
        if (!manager.hasUsageAccess()) {
            showState("需要使用情况访问权限",
                "健康使用需要读取手机的应用使用时间，才能显示此应用的真实统计。",
                "去开启", this::openUsageSettings);
            return;
        }
        showLoading();
        manager.loadAppDetail(packageName, new HealthUsageManager.Callback<HealthModels.AppDetail>() {
            @Override public void onSuccess(HealthModels.AppDetail detail) {
                if (generation != requestGeneration || isFinishing()) return;
                if (detail == null || detail.app == null) {
                    showState("暂无使用数据", "系统暂时没有返回这个应用的使用记录。", "重试",
                        AppDetailActivity.this::refreshData);
                    return;
                }
                bindDetail(detail);
            }

            @Override public void onError(Throwable error) {
                if (generation != requestGeneration || isFinishing()) return;
                if (error instanceof HealthUsageManager.PermissionDeniedException || !manager.hasUsageAccess()) {
                    showState("需要使用情况访问权限",
                        "权限已关闭，无法读取真实的应用使用数据。", "去开启",
                        AppDetailActivity.this::openUsageSettings);
                } else {
                    showState("使用数据暂时不可用", "系统没有返回可用数据，请稍后重试。", "重试",
                        AppDetailActivity.this::refreshData);
                }
            }
        });
    }

    private void bindDetail(HealthModels.AppDetail detail) {
        contentScroll.setVisibility(View.VISIBLE);
        statePanel.setVisibility(View.GONE);

        String name = TextUtils.isEmpty(detail.app.appName) ? detail.app.packageName : detail.app.appName;
        appName.setText(name);
        packageLabel.setText(detail.app.packageName);
        Drawable icon = manager.loadAppIcon(detail.app.packageName);
        appIcon.setImageDrawable(icon != null ? icon : getDrawable(android.R.drawable.sym_def_app_icon));
        todayValue.setText(formatDuration(detail.todayMillis));
        yesterdayValue.setText(formatDuration(detail.yesterdayMillis));
        averageValue.setText(formatDuration(detail.averageMillis));
        chart.setDays(detail.last7Days,detail.app.packageName);

        if (detail.peakDayStartMillis > 0L && detail.peakMillis > 0L) {
            peakValue.setText("最高使用日：" + formatDate(detail.peakDayStartMillis) + " · "
                + formatDuration(detail.peakMillis));
        } else {
            peakValue.setText("最高使用日：暂无使用记录");
        }

        if (detail.launchCountAvailable) {
            launchesValue.setText(detail.todayLaunchCount + " 次");
            sessionValue.setText(detail.todayLaunchCount > 0
                ? formatDuration(detail.averageSessionMillis) : "今日未打开");
        } else {
            launchesValue.setText("数据不可用");
            sessionValue.setText("数据不可用");
        }
    }

    private void showLoading() {
        contentScroll.setVisibility(View.GONE);
        statePanel.setVisibility(View.VISIBLE);
        stateTitle.setText("正在读取使用数据…");
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
            Intent intent = manager.createUsageAccessSettingsIntent();
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception ignored) { showState("无法打开系统设置", "请在系统设置中手动开启“使用情况访问权限”。", "重试", this::openUsageSettings); }
        }
    }

    private TextView metricCard(LinearLayout parent, String label, boolean first) {
        LinearLayout box = card();
        TextView labelView = text(label, 12, MUTED, false);
        labelView.setGravity(Gravity.CENTER);
        box.addView(labelView);
        TextView value = text("—", 20, INK, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(6), 0, 0);
        box.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (!first) params.setMargins(dp(10), 0, 0, 0);
        parent.addView(box, params);
        return value;
    }

    private TextView metricBlock(LinearLayout parent, String label, boolean first) {
        LinearLayout block = column();
        TextView labelView = text(label, 12, MUTED, false);
        labelView.setGravity(Gravity.CENTER);
        block.addView(labelView);
        TextView value = text("—", 20, INK, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(6), 0, 0);
        block.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (!first) params.setMargins(dp(10), 0, 0, 0);
        parent.addView(block, params);
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

    private String formatDuration(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0L) return minutes > 0L ? hours + "小时" + minutes + "分钟" : hours + "小时";
        if (minutes > 0L) return minutes + "分钟";
        return seconds > 0L ? "不足1分钟" : "0分钟";
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("M月d日 E", Locale.CHINA).format(new Date(millis));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SevenDayChart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat dayFormat = new SimpleDateFormat("E", Locale.CHINA);
        private List<HealthModels.DayUsage> days = new ArrayList<>();
        private String packageName="";
        private final float density;

        SevenDayChart(Activity context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setContentDescription("最近 7 天应用使用柱形图");
        }

        void setDays(List<HealthModels.DayUsage> value,String packageName) {
            days = value == null ? new ArrayList<>() : new ArrayList<>(value);
            this.packageName=packageName==null?"":packageName;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = 4f * density;
            float right = getWidth() - 4f * density;
            float top = 24f * density;
            float bottom = getHeight() - 30f * density;
            paint.setStrokeWidth(density);
            paint.setColor(Color.rgb(221, 229, 223));
            canvas.drawLine(left, bottom, right, bottom, paint);
            if (days.isEmpty()) {
                drawCentered(canvas, "暂无趋势数据", getWidth() / 2f, getHeight() / 2f, 12, MUTED);
                return;
            }

            long max = 0L;
            for(HealthModels.DayUsage day:days)max=Math.max(max,appUsage(day));
            int count = Math.max(1, days.size());
            float slot = (right - left) / count;
            float barWidth = Math.min(24f * density, slot * .54f);
            for (int i = 0; i < days.size(); i++) {
                HealthModels.DayUsage day = days.get(i);
                long value=appUsage(day);
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

        private long appUsage(HealthModels.DayUsage day){
            if(day==null||day.apps==null)return 0L;
            for(HealthModels.AppUsage app:day.apps)if(app!=null&&packageName.equals(app.packageName))return Math.max(0L,app.usageMillis);
            return 0L;
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
