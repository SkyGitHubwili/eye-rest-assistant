package com.eyerest.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Calendar;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 41, PICK_SLEEP_IMAGE = 42;
    private static final int GREEN = Color.rgb(45, 122, 89);
    private static final int RED = Color.rgb(205, 61, 61);
    private static final int INK = Color.rgb(24, 52, 42);
    private SharedPreferences prefs;
    private TextView countdown, status, imageStatus, overlayStatus, modeStatus, breakDuration, sleepStatus;
    private ImageView breakPreview;
    private Button toggle, resetButton, breakNowButton, manualModeButton, autoModeButton, offModeButton;
    private Button sleepOffButton, sleepTodayButton, sleepDailyButton;
    private Spinner workSpinner, startHourSpinner, startMinuteSpinner, endHourSpinner, endMinuteSpinner;
    private Spinner sleepStartHourSpinner, sleepStartMinuteSpinner, sleepWakeHourSpinner, sleepWakeMinuteSpinner;
    private TextView sleepStartPeriod, sleepWakePeriod;
    private final android.os.Handler handler = new android.os.Handler();
    private final int[] workValues = {0, 20, 25, 30};
    private boolean openPermissionPageAfterStartup;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        // Reset the one-time legacy schedule migration. Older builds could
        // persist accidental values; the intended default is 11:30 PM–08:00 AM.
        if(!prefs.getBoolean("sleep_time_defaults_v3",false)){
            prefs.edit().putInt("sleep_start_hour",23).putInt("sleep_start_minute",30)
                .putInt("sleep_wake_hour",8).putInt("sleep_wake_minute",0)
                .putBoolean("sleep_time_defaults_v3",true).apply();
        }
        if(!prefs.contains("keep_running_closed_user_set"))
            prefs.edit().putBoolean("keep_running_closed",false).putBoolean("keep_running_closed_user_set",true).apply();
        if(!prefs.getBoolean("background_policy_v154",false))
            prefs.edit().putBoolean("keep_running_closed",false).putBoolean("background_policy_v154",true).apply();
        clearStaleSession();
        normalizeDurations();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        int systemUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (Build.VERSION.SDK_INT < 30) systemUi |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        setContentView(buildUi());
        requestNotificationPermission();
        handler.post(ticker);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setBackgroundColor(Color.rgb(245,247,242));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout root = column();
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(245,247,242));
        scroll.addView(root);

        LinearLayout header = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.eyerest.app.R.mipmap.ic_launcher);
        header.addView(logo, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout titles = column();
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("护眼与睡眠助手", 25, INK, true));
        titles.addView(text("定时放松眼睛，也帮助你按时入睡", 13, Color.rgb(115,128,121), false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        status = text("专注中", 12, GREEN, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(13), dp(7), dp(13), dp(7));
        status.setBackground(round(Color.rgb(229,241,232), 99));
        header.addView(status);
        root.addView(header);

        LinearLayout.LayoutParams cardGap = new LinearLayout.LayoutParams(-1, -2);
        cardGap.setMargins(0, dp(22), 0, 0);
        LinearLayout ruleCard=card();ruleCard.setBackground(round(Color.rgb(233,243,236),22));
        ruleCard.addView(text("20 · 20 · 20 护眼法",18,INK,true));
        TextView rule=text("每使用屏幕 20 分钟，眺望 20 英尺（约 6 米）以外，至少 20 秒。",13,Color.rgb(82,104,95),false);rule.setPadding(0,dp(8),0,0);ruleCard.addView(rule);
        TextView limit=text("为了保证休息效果，提前结束休息每月最多 3 次。",11,Color.rgb(122,138,130),false);limit.setPadding(0,dp(7),0,0);ruleCard.addView(limit);
        root.addView(ruleCard,cardGap);

        LinearLayout timerCard = card();
        TextView eye = text("◉", 46, GREEN, false);
        eye.setGravity(Gravity.CENTER);
        timerCard.addView(eye);
        TextView label = text("距离下次休息", 14, Color.rgb(115,128,121), false);
        label.setGravity(Gravity.CENTER); timerCard.addView(label);
        countdown = text("20:00", 52, INK, true);
        countdown.setGravity(Gravity.CENTER); countdown.setPadding(0, dp(4), 0, dp(18));
        timerCard.addView(countdown);
        LinearLayout actions = row();
        toggle = button("暂停计时", GREEN, Color.WHITE);
        toggle.setOnClickListener(v -> toggleTimer());
        actions.addView(toggle, weighted());
        resetButton = button("重新计时", Color.rgb(234,240,235), INK);
        LinearLayout.LayoutParams rp = weighted(); rp.setMargins(dp(10),0,0,0);
        actions.addView(resetButton, rp);
        resetButton.setOnClickListener(v -> startTimer());
        timerCard.addView(actions);
        breakNowButton = button("现在休息一下", Color.TRANSPARENT, GREEN);
        breakNowButton.setOnClickListener(v -> service(EyeRestService.ACTION_BREAK_NOW));
        timerCard.addView(breakNowButton, new LinearLayout.LayoutParams(-1, dp(50)));
        root.addView(timerCard, cardGap);

        LinearLayout timeCard = card();
        timeCard.addView(text("时间设置", 18, INK, true));
        LinearLayout selectors = row(); selectors.setPadding(0, dp(14), 0, 0);
        workSpinner = spinner(new String[]{"10 秒（测试）","20 分钟","25 分钟","30 分钟"});
        breakDuration = fixedValue("20 秒");
        LinearLayout workCol = labeled("专注时长", workSpinner);
        LinearLayout breakCol = labeled("休息时长（自动）", breakDuration);
        selectors.addView(workCol, weightedWrap());
        LinearLayout.LayoutParams bp = weightedWrap(); bp.setMargins(dp(10),0,0,0);
        selectors.addView(breakCol, bp);
        timeCard.addView(selectors);
        root.addView(timeCard, cardGap);
        workSpinner.setSelection(indexOf(workValues, prefs.getInt("work_minutes",20)));
        breakDuration.setText(breakSecondsForWork(prefs.getInt("work_minutes",20))+" 秒");
        android.widget.AdapterView.OnItemSelectedListener saveSelection = new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                int workChoice=workValues[workSpinner.getSelectedItemPosition()];
                int breakSeconds=breakSecondsForWork(workChoice);
                prefs.edit().putInt("work_minutes",workChoice).putInt("break_seconds",breakSeconds).apply();
                breakDuration.setText(breakSeconds+" 秒");
            }
        };
        workSpinner.setOnItemSelectedListener(saveSelection);

        LinearLayout autoCard = card();
        autoCard.addView(text("护眼开启方式", 18, INK, true));
        modeStatus=text("",12,Color.rgb(115,128,121),false);modeStatus.setPadding(0,dp(6),0,0);autoCard.addView(modeStatus);
        LinearLayout modeRow=row();modeRow.setPadding(0,dp(14),0,0);
        offModeButton=button("关闭",Color.rgb(234,240,235),INK);offModeButton.setOnClickListener(v->setProtectionMode("off"));modeRow.addView(offModeButton,weighted());
        manualModeButton=button("手动（今天）",Color.rgb(234,240,235),INK);manualModeButton.setOnClickListener(v->setProtectionMode("manual"));LinearLayout.LayoutParams mp=weighted();mp.setMargins(dp(8),0,0,0);modeRow.addView(manualModeButton,mp);
        autoModeButton=button("自动（每天）",Color.rgb(234,240,235),INK);autoModeButton.setOnClickListener(v->setProtectionMode("auto"));LinearLayout.LayoutParams ap=weighted();ap.setMargins(dp(8),0,0,0);modeRow.addView(autoModeButton,ap);
        autoCard.addView(modeRow);
        TextView modeHint=text("手动：仅今天在时间段内循环；自动：以后每天都按时间段工作。",11,Color.rgb(137,148,142),false);modeHint.setPadding(0,dp(10),0,0);autoCard.addView(modeHint);
        Switch keepRunningClosed = new Switch(this);
        keepRunningClosed.setText("开启自启动和后台运行权限");
        keepRunningClosed.setTextColor(GREEN);keepRunningClosed.setTextSize(16);keepRunningClosed.setChecked(prefs.getBoolean("keep_running_closed",false));
        LinearLayout.LayoutParams kr = new LinearLayout.LayoutParams(-1,-2);kr.setMargins(0,dp(38),0,dp(24));autoCard.addView(keepRunningClosed,kr);
        TextView backgroundHint=text("要像电脑开机自启一样运行，请按提示打开自启动和后台运行权限。",13,GREEN,false);
        backgroundHint.setPadding(0,dp(2),0,dp(24));
        autoCard.addView(backgroundHint);
        String[] hours = new String[24], minutes = new String[60];
        for(int i=0;i<24;i++) hours[i]=String.format(Locale.CHINA,"%02d",i);
        for(int i=0;i<60;i++) minutes[i]=String.format(Locale.CHINA,"%02d",i);
        startHourSpinner=spinner(hours); startMinuteSpinner=spinner(minutes);
        endHourSpinner=spinner(hours); endMinuteSpinner=spinner(minutes);
        startHourSpinner.setSelection(prefs.getInt("start_hour",8)); startMinuteSpinner.setSelection(prefs.getInt("start_minute",0));
        endHourSpinner.setSelection(prefs.getInt("end_hour",23)); endMinuteSpinner.setSelection(prefs.getInt("end_minute",0));
        LinearLayout hourRow=row(); hourRow.setPadding(0,dp(20),0,0);
        LinearLayout startTimePicker=compactTimePicker(startHourSpinner,startMinuteSpinner);
        LinearLayout endTimePicker=compactTimePicker(endHourSpinner,endMinuteSpinner);
        hourRow.addView(labeled("开始时间",startTimePicker),weightedWrap());
        LinearLayout.LayoutParams eh=weightedWrap();eh.setMargins(dp(10),0,0,0);hourRow.addView(labeled("结束时间",endTimePicker),eh);autoCard.addView(hourRow);
        TextView sleepHint=text("时间段外自动暂停；手动模式次日自动关闭，自动模式次日继续。",11,Color.rgb(137,148,142),false);sleepHint.setPadding(0,dp(10),0,0);autoCard.addView(sleepHint);
        root.addView(autoCard,cardGap);
        keepRunningClosed.setOnClickListener(v->{
            if(keepRunningClosed.isChecked()){
                keepRunningClosed.setChecked(false);confirmBackgroundRunning(keepRunningClosed);
            }else{
                prefs.edit().putBoolean("keep_running_closed",false).apply();
                service(EyeRestService.ACTION_REEVALUATE);
            }
        });
        android.widget.AdapterView.OnItemSelectedListener saveHours=new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                prefs.edit().putInt("start_hour",startHourSpinner.getSelectedItemPosition()).putInt("start_minute",startMinuteSpinner.getSelectedItemPosition())
                    .putInt("end_hour",endHourSpinner.getSelectedItemPosition()).putInt("end_minute",endMinuteSpinner.getSelectedItemPosition()).apply();
                if(!"off".equals(effectiveMode()))service(EyeRestService.ACTION_REEVALUATE);
            }
        };
        startHourSpinner.setOnItemSelectedListener(saveHours);startMinuteSpinner.setOnItemSelectedListener(saveHours);
        endHourSpinner.setOnItemSelectedListener(saveHours);endMinuteSpinner.setOnItemSelectedListener(saveHours);

        LinearLayout sleepCard=card();sleepCard.setBackground(round(Color.rgb(251,246,247),22));
        sleepCard.addView(text("睡眠助手",20,Color.rgb(92,25,34),true));
        TextView sleepIntro=text("帮助你按时放下手机，让今晚真正开始休息",12,Color.rgb(126,75,82),false);
        sleepIntro.setPadding(0,dp(6),0,0);sleepCard.addView(sleepIntro);
        sleepStatus=text("",13,Color.rgb(174,40,52),true);sleepStatus.setPadding(0,dp(12),0,0);sleepCard.addView(sleepStatus);

        LinearLayout sleepModes=row();sleepModes.setPadding(0,dp(14),0,0);
        sleepOffButton=button("关闭",Color.rgb(240,231,232),Color.rgb(92,25,34));
        sleepTodayButton=button("今日",Color.rgb(240,231,232),Color.rgb(92,25,34));
        sleepDailyButton=button("每天",Color.rgb(240,231,232),Color.rgb(92,25,34));
        sleepModes.addView(sleepOffButton,weighted());
        LinearLayout.LayoutParams todayParams=weighted();todayParams.setMargins(dp(8),0,0,0);sleepModes.addView(sleepTodayButton,todayParams);
        LinearLayout.LayoutParams dailyParams=weighted();dailyParams.setMargins(dp(8),0,0,0);sleepModes.addView(sleepDailyButton,dailyParams);
        sleepCard.addView(sleepModes);
        sleepOffButton.setOnClickListener(v->setSleepMode(SleepSettings.MODE_OFF));
        sleepTodayButton.setOnClickListener(v->setSleepMode(SleepSettings.MODE_TODAY));
        sleepDailyButton.setOnClickListener(v->setSleepMode(SleepSettings.MODE_DAILY));

        String[] clockHours=new String[24],clockMinutes=new String[60];
        for(int i=0;i<24;i++)clockHours[i]=String.format(Locale.CHINA,"%02d",i);
        for(int i=0;i<60;i++)clockMinutes[i]=String.format(Locale.CHINA,"%02d",i);
        sleepStartHourSpinner=spinner(clockHours);sleepStartMinuteSpinner=spinner(clockMinutes);
        sleepWakeHourSpinner=spinner(clockHours);sleepWakeMinuteSpinner=spinner(clockMinutes);
        int savedStartHour=prefs.getInt("sleep_start_hour",23),savedWakeHour=prefs.getInt("sleep_wake_hour",8);
        sleepStartHourSpinner.setSelection(savedStartHour);
        sleepStartMinuteSpinner.setSelection(prefs.getInt("sleep_start_minute",30));
        sleepWakeHourSpinner.setSelection(savedWakeHour);
        sleepWakeMinuteSpinner.setSelection(prefs.getInt("sleep_wake_minute",0));

        LinearLayout sleepTimes=row();sleepTimes.setPadding(0,dp(18),0,0);
        LinearLayout startPicker=row();
        // Keep enough width for the hour label (for example "11 PM").  A
        // weighted spinner was too narrow on phones and rendered it as "0..".
        startPicker.addView(sleepStartHourSpinner,new LinearLayout.LayoutParams(dp(54),dp(48)));
        TextView startColon=text(":",18,INK,true);startColon.setGravity(Gravity.CENTER);startPicker.addView(startColon,new LinearLayout.LayoutParams(dp(14),dp(52)));
        startPicker.addView(sleepStartMinuteSpinner,new LinearLayout.LayoutParams(dp(44),dp(48)));
        sleepStartPeriod=periodLabel();startPicker.addView(sleepStartPeriod,new LinearLayout.LayoutParams(dp(30),dp(48)));
        LinearLayout wakePicker=row();
        wakePicker.addView(sleepWakeHourSpinner,new LinearLayout.LayoutParams(dp(54),dp(48)));
        TextView wakeColon=text(":",18,INK,true);wakeColon.setGravity(Gravity.CENTER);wakePicker.addView(wakeColon,new LinearLayout.LayoutParams(dp(14),dp(52)));
        wakePicker.addView(sleepWakeMinuteSpinner,new LinearLayout.LayoutParams(dp(44),dp(48)));
        sleepWakePeriod=periodLabel();wakePicker.addView(sleepWakePeriod,new LinearLayout.LayoutParams(dp(30),dp(48)));
        sleepStartPeriod.setVisibility(View.GONE);sleepWakePeriod.setVisibility(View.GONE);
        startPicker.setGravity(Gravity.CENTER);wakePicker.setGravity(Gravity.CENTER);
        LinearLayout startBox=blueTimeBox(startPicker),wakeBox=blueTimeBox(wakePicker);
        sleepTimes.addView(sleepLabeled("睡眠时间",startBox),weightedWrap());
        LinearLayout.LayoutParams wakeParams=weightedWrap();wakeParams.setMargins(dp(10),0,0,0);sleepTimes.addView(sleepLabeled("起床时间",wakeBox),wakeParams);
        sleepStartHourSpinner.setBackgroundColor(Color.TRANSPARENT);sleepStartMinuteSpinner.setBackgroundColor(Color.TRANSPARENT);
        sleepWakeHourSpinner.setBackgroundColor(Color.TRANSPARENT);sleepWakeMinuteSpinner.setBackgroundColor(Color.TRANSPARENT);
        updateSleepPeriodLabels();
        sleepCard.addView(sleepTimes);
        TextView clockHint=text("睡眠时间使用 24 小时制，例如 23:30。",11,Color.rgb(126,75,82),false);
        clockHint.setPadding(0,dp(8),0,0);sleepCard.addView(clockHint);

        TextView warningHint=text("睡眠前提醒：提前 3 分钟，以红色倒计时提示保存操作",11,Color.rgb(126,75,82),false);
        warningHint.setLineSpacing(dp(3),1f);warningHint.setPadding(0,dp(14),0,dp(10));sleepCard.addView(warningHint);
        TextView emergencyHint=text("睡眠期间可下滑通知栏查看消息，通知、来电和闹钟正常可用。",11,Color.rgb(126,75,82),false);
        emergencyHint.setLineSpacing(dp(3),1f);emergencyHint.setPadding(0,dp(4),0,dp(10));sleepCard.addView(emergencyHint);
        TextView manualUnlockHint=text("睡眠锁界面支持紧急解除，每月最多 10 次（测试）；确认解除后会自动关闭睡眠助手。",11,Color.rgb(126,75,82),false);
        manualUnlockHint.setLineSpacing(dp(3),1f);manualUnlockHint.setPadding(0,dp(4),0,dp(10));sleepCard.addView(manualUnlockHint);
        TextView bypassHint=text("来电会立即解除当晚睡眠锁；睡眠中重启后，当晚也不会再次锁定。",11,Color.rgb(126,75,82),false);
        bypassHint.setLineSpacing(dp(3),1f);bypassHint.setPadding(0,dp(4),0,dp(4));sleepCard.addView(bypassHint);
        root.addView(sleepCard,cardGap);

        android.widget.AdapterView.OnItemSelectedListener saveSleepTime=new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){saveSleepTimes();updateSleepPeriodLabels();}
        };
        sleepStartHourSpinner.setOnItemSelectedListener(saveSleepTime);sleepStartMinuteSpinner.setOnItemSelectedListener(saveSleepTime);
        sleepWakeHourSpinner.setOnItemSelectedListener(saveSleepTime);sleepWakeMinuteSpinner.setOnItemSelectedListener(saveSleepTime);

        LinearLayout imageCard = card();
        imageCard.addView(text("护眼模式休息画面", 18, INK, true));
        imageStatus = text(new File(getFilesDir(),"break_image").exists()?"已设置自己的图片":"自然渐变背景", 12, Color.rgb(115,128,121), false);
        imageCard.addView(imageStatus);
        FrameLayout previewFrame=new FrameLayout(this);
        previewFrame.setBackground(round(Color.rgb(238,242,238),16));
        previewFrame.setClipToOutline(true);
        breakPreview = new ImageView(this);
        breakPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewFrame.addView(breakPreview,new FrameLayout.LayoutParams(-1,-1));
        View previewTint=new View(this);previewTint.setBackgroundColor(Color.argb(55,8,24,20));
        previewFrame.addView(previewTint,new FrameLayout.LayoutParams(-1,-1));
        TextView previewTitle=text("看看远处，放松眼睛",18,Color.WHITE,true);
        previewTitle.setGravity(Gravity.CENTER);previewTitle.setShadowLayer(dp(2),0,dp(1),Color.argb(150,0,0,0));
        FrameLayout.LayoutParams previewTitleParams=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);
        previewTitleParams.setMargins(dp(12),0,dp(12),0);previewFrame.addView(previewTitle,previewTitleParams);
        LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(-1,dp(150));
        previewParams.setMargins(0,dp(14),0,0);imageCard.addView(previewFrame,previewParams);
        refreshBreakPreview();
        LinearLayout imageActions=row();imageActions.setPadding(0,dp(14),0,0);
        Button choose = button("选择自己的图片", Color.rgb(234,240,235), INK);
        imageActions.addView(choose,weighted());choose.setOnClickListener(v -> chooseImage());
        Button restore = button("恢复默认画面", Color.rgb(234,240,235), INK);
        LinearLayout.LayoutParams restoreP=weighted();restoreP.setMargins(dp(10),0,0,0);
        imageActions.addView(restore,restoreP);restore.setOnClickListener(v -> restoreDefaultImage());
        imageCard.addView(imageActions);
        root.addView(imageCard, cardGap);

        LinearLayout sleepImageCard=card();
        sleepImageCard.addView(text("睡眠倒计时提醒画面",18,INK,true));
        TextView sleepImageStatus=text(new File(getFilesDir(),"sleep_warning_image").exists()?"已设置自己的图片":"默认提醒画面",12,Color.rgb(115,128,121),false);
        sleepImageCard.addView(sleepImageStatus);
        FrameLayout sleepPreviewFrame=new FrameLayout(this);
        sleepPreviewFrame.setBackground(round(Color.rgb(238,242,238),16));sleepPreviewFrame.setClipToOutline(true);
        ImageView sleepPreview=new ImageView(this);sleepPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        File sleepCustom=new File(getFilesDir(),"sleep_warning_image");
        if(sleepCustom.exists())sleepPreview.setImageBitmap(BitmapFactory.decodeFile(sleepCustom.getAbsolutePath()));
        else sleepPreview.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(72,7,13),Color.rgb(137,35,52)}));
        sleepPreviewFrame.addView(sleepPreview,new FrameLayout.LayoutParams(-1,-1));
        TextView sleepPreviewTitle=text("即将进入睡眠 · 倒计时",18,Color.WHITE,true);sleepPreviewTitle.setGravity(Gravity.CENTER);
        sleepPreviewFrame.addView(sleepPreviewTitle,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams sleepPreviewParams=new LinearLayout.LayoutParams(-1,dp(150));sleepPreviewParams.setMargins(0,dp(14),0,0);sleepImageCard.addView(sleepPreviewFrame,sleepPreviewParams);
        LinearLayout sleepImageActions=row();sleepImageActions.setPadding(0,dp(14),0,0);
        Button chooseSleep=button("选择自己的图片",Color.rgb(234,240,235),INK);sleepImageActions.addView(chooseSleep,weighted());chooseSleep.setOnClickListener(v->chooseSleepImage());
        Button restoreSleep=button("恢复默认画面",Color.rgb(234,240,235),INK);LinearLayout.LayoutParams restoreSleepP=weighted();restoreSleepP.setMargins(dp(10),0,0,0);sleepImageActions.addView(restoreSleep,restoreSleepP);
        restoreSleep.setOnClickListener(v->{File f=new File(getFilesDir(),"sleep_warning_image");if(f.exists())f.delete();sleepImageStatus.setText("默认提醒画面");sleepPreview.setImageDrawable(null);sleepPreview.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(72,7,13),Color.rgb(137,35,52)}));});
        sleepImageCard.addView(sleepImageActions);root.addView(sleepImageCard,cardGap);

        LinearLayout permissionCard = card();
        permissionCard.addView(text("全屏提醒权限", 18, INK, true));
        overlayStatus = text("", 12, Color.rgb(115,128,121), false);
        permissionCard.addView(overlayStatus);
        Button permission = button("开启覆盖屏幕权限", GREEN, Color.WHITE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(48)); pp.setMargins(0,dp(14),0,0);
        permissionCard.addView(permission, pp);
        permission.setOnClickListener(v -> explainOverlayPermission());
        root.addView(permissionCard, cardGap);
        updateOverlayStatus();

        updateModeButtons();
        updateSleepUi();
        String mode=effectiveMode();
        if (!"off".equals(mode) && prefs.getBoolean("mode_started",false)
            && (!prefs.getBoolean("user_paused",false) || prefs.getBoolean("running",false)))
            service(EyeRestService.ACTION_REEVALUATE);
        if(!SleepSettings.MODE_OFF.equals(prefs.getString("sleep_mode",SleepSettings.MODE_OFF)))startSleepService();
        return scroll;
    }

    private void toggleTimer() {
        if (prefs.getBoolean("running", false)) service(EyeRestService.ACTION_PAUSE); else service(EyeRestService.ACTION_RESUME);
        handler.postDelayed(this::refresh, 150);
    }
    private void startTimer() { service(EyeRestService.ACTION_RESET); }
    private void setProtectionMode(String mode) {
        if ("manual".equals(mode)) {
            prefs.edit().putString("protection_mode","manual").putString("manual_date",today())
                .putBoolean("mode_started",false).putBoolean("running",false)
                .putBoolean("user_paused",false).putLong("remaining_ms",0).apply();
            service(EyeRestService.ACTION_MODE_MANUAL);
        } else if ("auto".equals(mode)) {
            prefs.edit().putString("protection_mode","auto").putBoolean("user_paused",false)
                .putBoolean("mode_started",false).putBoolean("running",false).putLong("remaining_ms",0).apply();
            service(EyeRestService.ACTION_MODE_AUTO);
        } else {
            prefs.edit().putString("protection_mode","off").putBoolean("running",false)
                .putBoolean("mode_started",false).putBoolean("user_paused",false).putLong("remaining_ms",0).apply();
            service(EyeRestService.ACTION_MODE_OFF);
        }
        handler.postDelayed(this::refresh,150);
    }
    private void setSleepMode(String mode){
        SleepSettings.setMode(prefs,mode);
        startSleepService();
        handler.postDelayed(this::updateSleepUi,150);
    }
    private void saveSleepTimes(){
        if(sleepStartHourSpinner==null||sleepWakeMinuteSpinner==null)return;
        int startHour=sleepStartHourSpinner.getSelectedItemPosition();
        int wakeHour=sleepWakeHourSpinner.getSelectedItemPosition();
        prefs.edit().putInt("sleep_start_hour",startHour)
            .putInt("sleep_start_minute",sleepStartMinuteSpinner.getSelectedItemPosition())
            .putInt("sleep_wake_hour",wakeHour)
            .putInt("sleep_wake_minute",sleepWakeMinuteSpinner.getSelectedItemPosition())
            .putInt("sleep_warning_minutes",3).putString("sleep_bypass_date","")
            .putString("sleep_bypass_reason",SleepSettings.REASON_NONE).apply();
        if(SleepSettings.MODE_TODAY.equals(prefs.getString("sleep_mode",SleepSettings.MODE_OFF)))
            prefs.edit().putString("sleep_mode_date",SleepSettings.activePlanKey(Calendar.getInstance(),prefs)).apply();
        if(!SleepSettings.MODE_OFF.equals(prefs.getString("sleep_mode",SleepSettings.MODE_OFF)))startSleepService();
        updateSleepUi();
    }
    private void startSleepService(){
        Intent intent=new Intent(this,SleepService.class).setAction(SleepService.ACTION_REFRESH);
        try{if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);}catch(Exception ignored){}
    }
    private void service(String action) {
        Intent i = new Intent(this, EyeRestService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }
    private final Runnable ticker = new Runnable() {
        public void run() { refresh(); handler.postDelayed(this, 1000); }
    };
    private void refresh() {
        prefs.edit().putLong("main_heartbeat",System.currentTimeMillis()).apply();
        String mode=effectiveMode();
        boolean running = prefs.getBoolean("running", false);
        boolean active = isWithinActiveHours();
        long end = prefs.getLong("work_end", 0);
        boolean paused=prefs.getBoolean("user_paused",false);
        boolean started=prefs.getBoolean("mode_started",false);
        long saved=prefs.getLong("remaining_ms",0);
        long left = running ? Math.max(0, end-System.currentTimeMillis()) : paused&&saved>0?saved:fullWorkMillis();
        countdown.setText(String.format(Locale.CHINA,"%02d:%02d",left/60000,(left/1000)%60));
        boolean enabled=!"off".equals(mode);
        status.setText(!enabled?"未开启":!started?"等待开始":!active?"睡眠时段":running?"专注中":paused?"已暂停":"等待开始");
        status.setTextColor(enabled?GREEN:RED);
        status.setBackground(round(enabled?Color.rgb(229,241,232):Color.rgb(253,235,235),99));
        toggle.setText(!enabled?"请先选择护眼开启方式":!started?"开始计时":!active?"睡眠时段":running?"暂停计时":paused?"继续计时":"开始计时");
        toggle.setEnabled(enabled&&active);toggle.setClickable(enabled&&active);
        toggle.setTextSize(!enabled?12:14);
        toggle.setTextColor(!enabled?RED:Color.WHITE);
        toggle.setBackground(round(!enabled?Color.rgb(253,235,235):GREEN,12));
        resetButton.setEnabled(enabled&&started&&active);resetButton.setAlpha(enabled&&started&&active?1f:.45f);
        breakNowButton.setEnabled(enabled&&started&&active);breakNowButton.setAlpha(enabled&&started&&active?1f:.45f);
        updateModeButtons();
        updateSleepUi();
    }

    private void updateSleepUi(){
        if(sleepStatus==null)return;
        Calendar now=Calendar.getInstance();
        String mode=prefs.getString("sleep_mode",SleepSettings.MODE_OFF);
        if(SleepSettings.MODE_TODAY.equals(mode)&&!SleepSettings.isEnabledForPlan(now,prefs)){
            SleepSettings.setMode(prefs,SleepSettings.MODE_OFF);mode=SleepSettings.MODE_OFF;
        }
        String value;
        String now24=String.format(Locale.CHINA,"当前时间 %02d:%02d",now.get(Calendar.HOUR_OF_DAY),now.get(Calendar.MINUTE));
        if(SleepSettings.MODE_OFF.equals(mode))value="睡眠助手未开启";
        else if(!SleepSettings.valid(prefs))value="睡眠时间不能与起床时间相同";
        else if(SleepSettings.hasBypassForCurrentWindow(now,prefs)){
            String reason=prefs.getString("sleep_bypass_reason",SleepSettings.REASON_NONE);
            value="今日睡眠已解除 · "+(SleepSettings.REASON_CALL.equals(reason)?"检测到来电":"设备刚刚重启");
        }else if(SleepSettings.isInSleepWindow(now,prefs)){
            long left=SleepSettings.wakeForCurrentWindow(now,prefs).getTimeInMillis()-now.getTimeInMillis();
            value="正在睡眠 · 距离起床 "+SleepSettings.formatDuration(left);
        }else if(SleepSettings.isInWarningWindow(now,prefs)){
            long left=SleepSettings.nextStart(now,prefs).getTimeInMillis()-now.getTimeInMillis();
            value="即将进入睡眠模式 · "+SleepSettings.formatDuration(left);
        }else{
            long left=SleepSettings.nextStart(now,prefs).getTimeInMillis()-now.getTimeInMillis();
            value=now24+" · 距离睡眠还有 "+SleepSettings.formatDuration(left);
        }
        sleepStatus.setText(value);
        sleepStatus.setTextColor(SleepSettings.MODE_OFF.equals(mode)?Color.rgb(115,128,121):GREEN);
        styleSleepModeButton(sleepOffButton,SleepSettings.MODE_OFF.equals(mode),false);
        styleSleepModeButton(sleepTodayButton,SleepSettings.MODE_TODAY.equals(mode),true);
        styleSleepModeButton(sleepDailyButton,SleepSettings.MODE_DAILY.equals(mode),true);
    }
    private void styleSleepModeButton(Button button,boolean selected,boolean enabledMode){
        if(button==null)return;button.setTextColor(selected?Color.WHITE:Color.rgb(92,25,34));
        int selectedColor=enabledMode?GREEN:Color.rgb(174,40,52);
        button.setBackground(round(selected?selectedColor:Color.rgb(240,231,232),12));
    }

    private String effectiveMode(){
        String mode=prefs.getString("protection_mode","off");
        if("manual".equals(mode)&&!today().equals(prefs.getString("manual_date",""))){
            prefs.edit().putString("protection_mode","off").putBoolean("mode_started",false).putBoolean("running",false).putBoolean("user_paused",false).putLong("remaining_ms",0).apply();return "off";
        }
        return mode;
    }
    private String today(){Calendar c=Calendar.getInstance();return String.format(Locale.CHINA,"%04d-%02d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));}
    private void updateModeButtons(){
        if(modeStatus==null)return;String mode=effectiveMode();
        boolean started=prefs.getBoolean("mode_started",false);
        modeStatus.setText("manual".equals(mode)?(started?"今日手动护眼已开启":"已选择手动护眼，请点击开始计时"):
            "auto".equals(mode)?(started?"每日自动护眼已开启":"已选择自动护眼，请点击开始计时"):"护眼尚未开启");
        styleModeButton(offModeButton,"off".equals(mode));styleModeButton(manualModeButton,"manual".equals(mode));styleModeButton(autoModeButton,"auto".equals(mode));
    }
    private void styleModeButton(Button b,boolean selected){if(b==null)return;b.setTextColor(selected?Color.WHITE:INK);b.setBackground(round(selected?GREEN:Color.rgb(234,240,235),12));}

    private void chooseImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_IMAGE);
    }
    private void chooseSleepImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_SLEEP_IMAGE);
    }
    private void restoreDefaultImage() {
        File image=new File(getFilesDir(),"break_image");
        if(image.exists()&&!image.delete()){
            Toast.makeText(this,"恢复失败，请稍后重试",Toast.LENGTH_SHORT).show();return;
        }
        imageStatus.setText("自然渐变背景");
        refreshBreakPreview();
        Toast.makeText(this,"已恢复默认画面",Toast.LENGTH_SHORT).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if ((request!=PICK_IMAGE&&request!=PICK_SLEEP_IMAGE) || result!=RESULT_OK || data==null) return;
        String targetName=request==PICK_SLEEP_IMAGE?"sleep_warning_image":"break_image";
        try (InputStream in=getContentResolver().openInputStream(data.getData()); FileOutputStream out=new FileOutputStream(new File(getFilesDir(),targetName))) {
            byte[] b=new byte[8192]; int n; while((n=in.read(b))>0) out.write(b,0,n);
            if(request==PICK_IMAGE){imageStatus.setText("已设置自己的图片");refreshBreakPreview();}
            Toast.makeText(this,request==PICK_SLEEP_IMAGE?"睡眠提醒图片已更新":"护眼休息图片已更新",Toast.LENGTH_SHORT).show();
        } catch(Exception e) { Toast.makeText(this,"图片设置失败",Toast.LENGTH_SHORT).show(); }
    }
    private void refreshBreakPreview(){
        if(breakPreview==null)return;
        File custom=new File(getFilesDir(),"break_image");
        breakPreview.setImageDrawable(null);
        if(custom.exists()){
            Bitmap bitmap=decodePreview(custom,dp(720),dp(300));
            if(bitmap!=null){breakPreview.setBackgroundColor(Color.rgb(238,242,238));breakPreview.setImageBitmap(bitmap);return;}
        }
        breakPreview.setImageBitmap(null);
        breakPreview.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(18,59,53),Color.rgb(79,137,105),Color.rgb(203,174,116)}));
    }
    private Bitmap decodePreview(File file,int reqWidth,int reqHeight){
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);int sample=1;
        while(bounds.outWidth/sample>reqWidth*2||bounds.outHeight/sample>reqHeight*2)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(),options);
    }
    private void explainOverlayPermission() {
        if (Settings.canDrawOverlays(this)) { Toast.makeText(this,"全屏提醒已开启",Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("开启全屏提醒")
            .setMessage("请允许护眼助手显示在其他应用上层。到休息时间时，手机才能直接显示全屏休息画面。")
            .setNegativeButton("暂不开启",null).setPositiveButton("去开启",(d,w)->{
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));
            }).show();
    }
    private boolean openBackgroundPermissionSettings(){
        Intent auto=new Intent("miui.intent.action.OP_AUTO_START");
        auto.setClassName("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity");
        try{startActivity(auto);return true;}catch(Exception ignored){}
        openAppPermissionSettings();
        return false;
    }
    private void openAppPermissionSettings(){
        Intent miui=new Intent("miui.intent.action.APP_PERM_EDITOR");
        miui.setClassName("com.miui.securitycenter","com.miui.permcenter.permissions.PermissionsEditorActivity");
        miui.putExtra("extra_pkgname",getPackageName());
        try{startActivity(miui);return;}catch(Exception ignored){}
        Intent app=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));
        try{startActivity(app);}catch(Exception ignored){startActivity(new Intent(Settings.ACTION_SETTINGS));}
    }
    private void showBackgroundPermissionGuide(){
        LinearLayout panel=column();
        panel.setPadding(dp(24),dp(22),dp(24),dp(18));
        panel.setBackground(round(Color.WHITE,24));
        TextView title=text("开启后台护眼权限",20,INK,true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title,new LinearLayout.LayoutParams(-1,dp(34)));
        TextView message=text("为了像电脑开机自启一样工作，请按下面设置：",14,Color.rgb(82,104,95),false);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0,dp(12),0,0);
        panel.addView(message,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout steps=column();
        String[] guideSteps={
            "① 在“自启动管理”里搜索并打开“护眼助手”",
            "② 返回后打开“后台弹出界面”（可不选）",
            "③ 打开“锁屏显示”（可不选）",
            "④ 确认“显示悬浮窗”已开启"
        };
        for(String guideStep:guideSteps){
            TextView step=text(guideStep,14,Color.rgb(82,104,95),false);
            step.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            step.setPadding(0,dp(2),0,dp(2));
            steps.addView(step,new LinearLayout.LayoutParams(-1,dp(42)));
        }
        panel.addView(steps,new LinearLayout.LayoutParams(-1,dp(168)));
        TextView note=text("系统权限需要你在系统页面手动确认，护眼助手无法代替系统自动开启。",11,Color.rgb(137,148,142),false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0,dp(8),0,0);
        panel.addView(note,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout actions=row();
        LinearLayout.LayoutParams actionsParams=new LinearLayout.LayoutParams(-1,dp(48));
        actionsParams.setMargins(0,dp(10),0,0);
        panel.addView(actions,actionsParams);
        Button later=button("稍后设置",Color.rgb(234,240,235),INK);
        actions.addView(later,weighted());
        Button go=button("打开自启动管理",GREEN,Color.WHITE);
        LinearLayout.LayoutParams goParams=weighted();
        goParams.setMargins(dp(10),0,0,0);
        actions.addView(go,goParams);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel).create();
        dialog.setOnShowListener(d->{
            later.setOnClickListener(v->dialog.dismiss());
            go.setOnClickListener(v->{dialog.dismiss();openPermissionPageAfterStartup=openBackgroundPermissionSettings();});
        });
        dialog.show();
        android.view.Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(dp(340),ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
    private void confirmBackgroundRunning(Switch target){
        LinearLayout panel=column();panel.setPadding(dp(24),dp(22),dp(24),dp(18));panel.setBackground(round(Color.WHITE,24));
        TextView title=text("确认开启后台护眼",20,INK,true);title.setGravity(Gravity.CENTER);panel.addView(title,new LinearLayout.LayoutParams(-1,dp(34)));
        TextView message=text("",14,Color.rgb(82,104,95),false);message.setGravity(Gravity.CENTER);message.setPadding(0,dp(12),0,0);panel.addView(message,new LinearLayout.LayoutParams(-1,dp(108)));
        LinearLayout actions=row();LinearLayout.LayoutParams actionsParams=new LinearLayout.LayoutParams(-1,dp(48));actionsParams.setMargins(0,dp(10),0,0);panel.addView(actions,actionsParams);
        Button cancel=button("取消",Color.rgb(234,240,235),INK);actions.addView(cancel,weighted());
        Button ok=button("确认",GREEN,Color.WHITE);LinearLayout.LayoutParams okParams=weighted();okParams.setMargins(dp(10),0,0,0);actions.addView(ok,okParams);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel).create();
        dialog.setOnShowListener(d->{
            cancel.setOnClickListener(v->dialog.dismiss());ok.setEnabled(false);ok.setAlpha(.5f);
            final int[] left={5};
            final Runnable[] tick={null};
            setConfirmMessage(message,5,false);
            tick[0]=()->{left[0]--;if(left[0]<=0){setConfirmMessage(message,0,true);ok.setEnabled(true);ok.setAlpha(1f);}else{setConfirmMessage(message,left[0],false);handler.postDelayed(tick[0],1000);}};
            handler.postDelayed(tick[0],1000);
            ok.setOnClickListener(v->{prefs.edit().putBoolean("keep_running_closed",true).putBoolean("keep_running_closed_user_set",true).apply();target.setChecked(true);dialog.dismiss();if(prefs.getBoolean("mode_started",false)&&!"off".equals(effectiveMode()))service(EyeRestService.ACTION_REEVALUATE);showBackgroundPermissionGuide();});
            dialog.setOnDismissListener(x->{if(left[0]>0)handler.removeCallbacks(tick[0]);});
        });
        dialog.show();android.view.Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setLayout(dp(330),ViewGroup.LayoutParams.WRAP_CONTENT);}
    }
    private void setConfirmMessage(TextView view,int seconds,boolean ready){
        String text=ready?"开启后，关闭软件仍会在后台护眼。\n\n可以确认开启。":"开启后，关闭软件仍会在后台护眼。\n\n请确认是否开启？\n"+seconds+" 秒后可点击确认。";
        SpannableString styled=new SpannableString(text);
        String first="开启后，关闭软件仍会在后台护眼。";
        styled.setSpan(new ForegroundColorSpan(RED),0,first.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if(!ready){int start=text.indexOf(String.valueOf(seconds));if(start>=0)styled.setSpan(new ForegroundColorSpan(RED),start,start+String.valueOf(seconds).length()+2,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        view.setText(styled);
    }
    private void updateOverlayStatus() {
        if (overlayStatus!=null) overlayStatus.setText(Settings.canDrawOverlays(this)?"已开启：到点可覆盖其他应用":"未开启：请授权后使用全屏提醒");
    }
    @Override protected void onResume() {
        super.onResume();
        updateOverlayStatus();
        if(openPermissionPageAfterStartup){
            openPermissionPageAfterStartup=false;
            handler.postDelayed(this::openAppPermissionSettings,300);
        }
    }
    @Override protected void onStop() {
        if(prefs.getBoolean("keep_running_closed",false)&&prefs.getBoolean("mode_started",false)&&!"off".equals(effectiveMode()))
            service(EyeRestService.ACTION_REEVALUATE);
        if(!SleepSettings.MODE_OFF.equals(prefs.getString("sleep_mode",SleepSettings.MODE_OFF)))startSleepService();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if(isFinishing()&&!isChangingConfigurations()&&!prefs.getBoolean("keep_running_closed",false))
            service(EyeRestService.ACTION_APP_CLOSED);
        handler.removeCallbacks(ticker); super.onDestroy();
    }
    private void requestNotificationPermission() {
        java.util.ArrayList<String> missing=new java.util.ArrayList<>();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.READ_PHONE_STATE);
        if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),7);
    }

    private LinearLayout column(){ LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v; }
    private LinearLayout row(){ LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    private LinearLayout card(){ LinearLayout v=column();v.setPadding(dp(20),dp(20),dp(20),dp(18));v.setBackground(round(Color.WHITE,22));v.setElevation(dp(2));return v; }
    private TextView text(String s,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String s,int bg,int fg){Button b=new Button(this);b.setText(s);b.setTextColor(fg);b.setTextSize(14);b.setAllCaps(false);b.setBackground(round(bg,12));return b;}
    private TextView fixedValue(String value){TextView v=text(value,14,INK,false);v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);v.setPadding(dp(12),0,dp(12),0);v.setBackground(round(Color.rgb(243,246,242),10));return v;}
    private Spinner spinner(String[] items){
        Spinner s=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,items){
            private View style(View view,boolean dropdown){
                TextView label=(TextView)view;
                label.setTextColor(INK);label.setTextSize(14);label.setSingleLine(true);
                label.setGravity(Gravity.CENTER);
                if(!dropdown){
                    String shown=label.getText().toString().replace(" AM","").replace(" PM","");
                    label.setText(shown);
                }
                // The selected value needs to fit "11 PM" without ellipsis.
                label.setPadding(dp(dropdown?10:6),0,dp(dropdown?10:6),0);label.setMinHeight(dp(52));
                return label;
            }
            @Override public View getView(int position,View convertView,ViewGroup parent){return style(super.getView(position,convertView,parent),false);}
            @Override public View getDropDownView(int position,View convertView,ViewGroup parent){return style(super.getDropDownView(position,convertView,parent),true);}
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);s.setBackground(round(Color.rgb(243,246,242),10));s.setPadding(0,0,0,0);return s;
    }
    private LinearLayout labeled(String label,View child){LinearLayout c=column();TextView l=text(label,12,Color.rgb(115,128,121),false);l.setPadding(0,0,0,dp(6));c.addView(l);c.addView(child,new LinearLayout.LayoutParams(-1,dp(52)));return c;}
    private LinearLayout sleepLabeled(String label,View child){LinearLayout c=column();TextView l=text(label,12,Color.rgb(115,128,121),false);l.setPadding(0,0,0,dp(6));c.addView(l);c.addView(child,new LinearLayout.LayoutParams(-1,dp(52)));return c;}
    private TextView periodLabel(){TextView v=text("PM",13,INK,false);v.setGravity(Gravity.CENTER);return v;}
    private void updateSleepPeriodLabels(){
        if(sleepStartPeriod!=null&&sleepStartHourSpinner!=null)
            sleepStartPeriod.setText(sleepStartHourSpinner.getSelectedItemPosition()<12?"AM":"PM");
        if(sleepWakePeriod!=null&&sleepWakeHourSpinner!=null)
            sleepWakePeriod.setText(sleepWakeHourSpinner.getSelectedItemPosition()<12?"AM":"PM");
    }
    private LinearLayout blueTimeBox(View child){
        LinearLayout box=row();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6),dp(5),dp(6),dp(5));
        // Simple neutral card, matching the reference layout.
        box.setBackground(round(Color.rgb(243,246,242),14));
        box.addView(child,new LinearLayout.LayoutParams(-1,dp(58)));
        return box;
    }
    private LinearLayout compactTimePicker(Spinner hour,Spinner minute){
        LinearLayout p=row();p.setGravity(Gravity.CENTER);
        p.addView(hour,new LinearLayout.LayoutParams(0,dp(52),1));
        TextView colon=text(":",16,INK,true);colon.setGravity(Gravity.CENTER);p.addView(colon,new LinearLayout.LayoutParams(dp(14),dp(52)));
        p.addView(minute,new LinearLayout.LayoutParams(0,dp(52),1));
        return p;
    }
    private LinearLayout.LayoutParams weighted(){return new LinearLayout.LayoutParams(0,dp(48),1);}
    private LinearLayout.LayoutParams weightedWrap(){return new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private int indexOf(int[] a,int value){for(int i=0;i<a.length;i++)if(a[i]==value)return i;return 0;}
    private void normalizeDurations(){
        int saved=prefs.getInt("work_minutes",20);
        int work=saved==0||saved==25||saved==30?saved:20;
        SharedPreferences.Editor edit=prefs.edit().putInt("work_minutes",work).putInt("break_seconds",breakSecondsForWork(work));
        if(saved!=work)edit.putBoolean("running",false).putBoolean("user_paused",false).putLong("remaining_ms",0);
        edit.apply();
    }
    private void clearStaleSession(){
        if(!prefs.getBoolean("keep_running_closed",false)&&prefs.getBoolean("mode_started",false)
            &&System.currentTimeMillis()-prefs.getLong("main_heartbeat",0)>4000L)
            prefs.edit().putBoolean("mode_started",false).putBoolean("running",false)
                .putBoolean("user_paused",false).putLong("remaining_ms",0).putLong("work_end",0).apply();
    }
    private int breakSecondsForWork(int work){return work==0?10:work;}
    private String hourOption(int hour24){int hour12=hour24%12; if(hour12==0)hour12=12; return String.format(Locale.CHINA,"%02d %s",hour12,hour24<12?"AM":"PM");}
    private long fullWorkMillis(){return prefs.getInt("work_minutes",20)==0?10000L:prefs.getInt("work_minutes",20)*60000L;}
    private boolean isWithinActiveHours(){
        Calendar now=Calendar.getInstance();
        int value=now.get(Calendar.HOUR_OF_DAY)*60+now.get(Calendar.MINUTE);
        int start=prefs.getInt("start_hour",8)*60+prefs.getInt("start_minute",0);
        int end=prefs.getInt("end_hour",23)*60+prefs.getInt("end_minute",0);
        if(start==end)return true;return start<end?value>=start&&value<end:value>=start||value<end;
    }
}
