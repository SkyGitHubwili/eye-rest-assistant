package com.eyerest.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** Health usage page. All statistics are supplied by HealthUsageManager off the UI thread. */
public final class HealthUsageView extends ScrollView {
    private static final int GREEN=Color.rgb(45,122,89);
    private static final int INK=Color.rgb(24,52,42);
    private static final int MUTED=Color.rgb(105,122,114);
    private static final int SURFACE=Color.rgb(245,247,242);
    private final Activity activity;
    private final LinearLayout root;
    private final HealthUsageManager manager;
    private HealthModels.HealthSnapshot snapshot;
    private boolean loaded;
    private boolean loading;
    private float pullStartY=-1f;

    public HealthUsageView(Activity activity){
        super(activity);
        this.activity=activity;
        manager=new HealthUsageManager(activity);
        setFillViewport(true);setClipToPadding(true);setBackgroundColor(SURFACE);
        root=column();root.setPadding(dp(20),dp(16),dp(20),dp(26));root.setBackgroundColor(SURFACE);
        addView(root,new ScrollView.LayoutParams(-1,-2));
        setOnTouchListener((view,event)->{
            if(event.getAction()==MotionEvent.ACTION_DOWN)pullStartY=getScrollY()==0?event.getY():-1f;
            else if(event.getAction()==MotionEvent.ACTION_UP){
                if(pullStartY>=0f&&event.getY()-pullStartY>=dp(90)&&!loading)refreshData();
                pullStartY=-1f;
            }else if(event.getAction()==MotionEvent.ACTION_CANCEL)pullStartY=-1f;
            return false;
        });
        showInitial();
    }

    public boolean hasLoaded(){return loaded;}

    public void refreshData(){
        if(loading)return;
        if(AppLimitStore.hasEnabled(activity)) AppLimitService.start(activity);
        // The user may have just toggled Usage Access in Settings. Do not let
        // a short-lived cached AppOps result keep the permission card visible.
        manager.invalidateUsageAccessCache();
        if(!manager.hasUsageAccess()){
            loaded=true;snapshot=null;HealthReminderScheduler.cancel(activity);showPermission();return;
        }
        HealthReminderScheduler.schedule(activity);
        loading=true;showLoading();
        manager.refresh(new HealthUsageManager.Callback<HealthModels.HealthSnapshot>(){
            @Override public void onSuccess(HealthModels.HealthSnapshot value){
                loading=false;loaded=true;snapshot=value;showSnapshot(value);
            }
            @Override public void onError(Throwable error){
                loading=false;loaded=true;
                if(error instanceof HealthUsageManager.PermissionDeniedException)showPermission();
                else showError();
            }
        });
    }

    public void destroy(){manager.shutdown();}

    private void showInitial(){
        root.removeAllViews();addHeader(false);
        TextView prompt=text("进入此页面后会读取手机提供的真实使用数据",14,MUTED,false);
        prompt.setGravity(Gravity.CENTER);prompt.setPadding(0,dp(70),0,0);root.addView(prompt);
    }

    private void showLoading(){
        root.removeAllViews();addHeader(false);
        ProgressBar progress=new ProgressBar(activity);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView label=text("正在整理使用数据…",14,MUTED,false);label.setGravity(Gravity.CENTER);root.addView(label);
    }

    private void showPermission(){
        root.removeAllViews();addHeader(false);
        LinearLayout panel=card();
        panel.addView(text("开启使用情况访问权限",20,INK,true));
        TextView message=text("健康使用需要读取手机的应用使用时间，才能统计今日使用情况和 App 排行。",14,MUTED,false);
        message.setLineSpacing(dp(4),1f);message.setPadding(0,dp(12),0,dp(16));panel.addView(message);
        Button open=button("去开启",GREEN,Color.WHITE);panel.addView(open,new LinearLayout.LayoutParams(-1,dp(48)));
        open.setOnClickListener(v->{
            try{activity.startActivity(manager.createUsageAccessSettingsIntent());}
            catch(Exception e){
                try{activity.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}
                catch(Exception ignored){Toast.makeText(activity,"无法打开权限页面，请在系统设置中手动开启",Toast.LENGTH_LONG).show();}
            }
        });
        TextView note=text("未授权时不会显示示例或随机数据。返回本应用后会自动刷新。",12,Color.rgb(131,144,137),false);
        note.setPadding(0,dp(14),0,0);panel.addView(note);
        addCard(panel);
    }

    private void showError(){
        root.removeAllViews();addHeader(false);
        LinearLayout panel=card();panel.addView(text("暂时无法读取使用数据",18,INK,true));
        TextView note=text("系统统计服务暂时不可用，请稍后重试。",14,MUTED,false);note.setPadding(0,dp(8),0,dp(14));panel.addView(note);
        Button retry=button("重新加载",GREEN,Color.WHITE);retry.setOnClickListener(v->refreshData());panel.addView(retry,new LinearLayout.LayoutParams(-1,dp(48)));
        addCard(panel);
    }

    private void showSnapshot(HealthModels.HealthSnapshot value){
        root.removeAllViews();addHeader(true);
        if(value==null||!value.hasData){
            LinearLayout empty=card();empty.addView(text("暂无使用数据",20,INK,true));
            TextView hint=text("系统尚未返回今天的应用使用记录。你可以稍后手动刷新。",14,MUTED,false);
            hint.setPadding(0,dp(10),0,dp(16));empty.addView(hint);
            Button retry=button("刷新数据",GREEN,Color.WHITE);retry.setOnClickListener(v->refreshData());empty.addView(retry,new LinearLayout.LayoutParams(-1,dp(48)));
            addCard(empty);addGoalCard(value);addAppLimitCard();return;
        }

        LinearLayout today=card();today.setBackground(round(Color.rgb(233,243,236),22));
        TextView caption=text("今日手机使用",14,MUTED,false);caption.setGravity(Gravity.CENTER);today.addView(caption);
        TextView total=text(duration(value.today.totalUsageMillis),42,INK,true);total.setGravity(Gravity.CENTER);total.setPadding(0,dp(5),0,dp(5));today.addView(total);
        TextView comparison=text(comparison(value.today.totalUsageMillis,value.yesterday.totalUsageMillis),13,
            value.today.totalUsageMillis<=value.yesterday.totalUsageMillis?GREEN:Color.rgb(184,74,53),false);
        comparison.setGravity(Gravity.CENTER);today.addView(comparison);addCard(today);

        addGoalCard(value);
        addAppLimitCard();
        addRanking(value);
        addUsageSignals(value);
        addScore(value.healthScore);

        Button all=button("查看全部数据",Color.rgb(229,241,232),GREEN);
        all.setOnClickListener(v->activity.startActivity(new Intent(activity,HealthDataActivity.class)));
        LinearLayout.LayoutParams allParams=new LinearLayout.LayoutParams(-1,dp(50));allParams.setMargins(0,dp(20),0,0);root.addView(all,allParams);
        TextView disclaimer=text("健康指数仅用于帮助理解使用习惯，不代表医疗诊断。",11,Color.rgb(137,148,142),false);
        disclaimer.setGravity(Gravity.CENTER);disclaimer.setPadding(0,dp(12),0,0);root.addView(disclaimer);
    }

    private void addHeader(boolean canRefresh){
        LinearLayout header=row();
        LinearLayout titleColumn=column();
        titleColumn.addView(text("健康使用",26,INK,true));
        titleColumn.addView(text("看见习惯，给注意力留一点空间",13,MUTED,false));
        header.addView(titleColumn,new LinearLayout.LayoutParams(0,-2,1));
        if(canRefresh){
            Button refresh=button("刷新",Color.rgb(229,241,232),GREEN);refresh.setTextSize(12);
            refresh.setOnClickListener(v->refreshData());header.addView(refresh,new LinearLayout.LayoutParams(dp(72),dp(42)));
        }
        root.addView(header);
    }

    private void addGoalCard(HealthModels.HealthSnapshot value){
        int goalMinutes=manager.getSettings().getDailyGoalMinutes();
        long goalMillis=goalMinutes*60000L;
        long used=value==null||value.today==null?0:value.today.totalUsageMillis;
        LinearLayout panel=card();
        LinearLayout heading=row();heading.addView(text("每日目标",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button edit=button("修改",Color.TRANSPARENT,GREEN);edit.setTextSize(12);heading.addView(edit,new LinearLayout.LayoutParams(dp(64),dp(40)));panel.addView(heading);
        TextView values=text(duration(used)+" / "+duration(goalMillis),16,INK,true);values.setPadding(0,dp(12),0,dp(8));panel.addView(values);
        ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);
        progress.setProgress(goalMillis<=0?0:(int)Math.min(1000,used*1000L/goalMillis));progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        panel.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        String remaining=used<=goalMillis?"还剩 "+duration(goalMillis-used):"已超过目标 "+duration(used-goalMillis);
        TextView remainingView=text(remaining,13,used<=goalMillis?MUTED:Color.rgb(184,74,53),false);remainingView.setPadding(0,dp(9),0,0);panel.addView(remainingView);
        edit.setOnClickListener(v->showGoalDialog());addCard(panel);
    }

    private void addRanking(HealthModels.HealthSnapshot value){
        LinearLayout panel=card();panel.addView(text("今日使用排行",18,INK,true));
        if(value.topApps==null||value.topApps.isEmpty()){
            TextView empty=text("暂无可排行的应用数据",14,MUTED,false);empty.setPadding(0,dp(12),0,0);panel.addView(empty);addCard(panel);return;
        }
        long max=Math.max(1,value.topApps.get(0).usageMillis);
        int count=Math.min(5,value.topApps.size());
        for(int i=0;i<count;i++){
            HealthModels.AppUsage app=value.topApps.get(i);
            LinearLayout item=row();item.setPadding(0,dp(14),0,dp(8));
            ImageView icon=new ImageView(activity);Drawable drawable=manager.loadAppIcon(app.packageName);
            icon.setImageDrawable(drawable!=null?drawable:activity.getDrawable(android.R.drawable.sym_def_app_icon));
            icon.setContentDescription(app.appName);item.addView(icon,new LinearLayout.LayoutParams(dp(38),dp(38)));
            LinearLayout details=column();details.setPadding(dp(11),0,0,0);
            LinearLayout line=row();TextView name=text(app.appName,14,INK,true);name.setSingleLine(true);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            line.addView(text(duration(app.usageMillis),13,MUTED,false));details.addView(line);
            ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgress((int)(app.usageMillis*1000L/max));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
            LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,dp(8));barParams.setMargins(0,dp(7),0,0);details.addView(bar,barParams);
            item.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            item.setBackground(round(Color.TRANSPARENT,12));item.setClickable(true);item.setFocusable(true);
            item.setOnClickListener(v->{Intent intent=new Intent(activity,AppDetailActivity.class).putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME,app.packageName);activity.startActivity(intent);});
            panel.addView(item);
        }
        addCard(panel);
    }

    private void addUsageSignals(HealthModels.HealthSnapshot value){
        LinearLayout panel=card();
        LinearLayout heading=row();heading.addView(text("使用节奏",18,INK,true));panel.addView(heading);
        int threshold=manager.getSettings().getContinuousReminderMinutes();
        long longest=value.today.longestContinuousMillis;
        TextView longestTitle=text("最长连续使用",12,MUTED,false);longestTitle.setPadding(0,dp(14),0,0);panel.addView(longestTitle);
        panel.addView(text(value.today.continuousUsageAvailable?duration(longest):"数据不可用",25,INK,true));
        String warning=!value.today.continuousUsageAvailable?"当前系统未提供可靠的连续使用事件":threshold==0?"连续使用提醒已关闭":longest>=threshold*60000L?"连续使用已超过 "+threshold+" 分钟":"提醒阈值 "+threshold+" 分钟";
        TextView warningView=text(warning,13,longest>=threshold*60000L&&threshold>0?Color.rgb(184,74,53):MUTED,false);warningView.setPadding(0,dp(5),0,0);panel.addView(warningView);
        View divider=new View(activity);divider.setBackgroundColor(Color.rgb(230,235,231));LinearLayout.LayoutParams dividerParams=new LinearLayout.LayoutParams(-1,dp(1));dividerParams.setMargins(0,dp(14),0,dp(12));panel.addView(divider,dividerParams);
        String launches=value.today.launchCountsAvailable?String.valueOf(value.today.totalLaunchCount):"数据不可用";
        panel.addView(metricRow("今日 App 打开次数",launches));
        if(value.today.continuousUsageAvailable&&value.today.currentContinuousMillis>0)panel.addView(metricRow("当前连续使用",duration(value.today.currentContinuousMillis)));
        addCard(panel);
    }

    private void addAppLimitCard(){
        LinearLayout panel=card();
        LinearLayout heading=row();
        heading.addView(text("应用使用限制",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button add=button("添加应用",Color.TRANSPARENT,GREEN); add.setTextSize(12); heading.addView(add,new LinearLayout.LayoutParams(dp(92),dp(40))); panel.addView(heading);
        TextView hint=text("选择某个 App 并设置每日可使用时长，达到上限后会显示限制画面。",13,MUTED,false); hint.setPadding(0,dp(7),0,dp(8)); panel.addView(hint);
        List<AppLimit> limits=AppLimitStore.get(activity);
        if(limits.isEmpty()){
            TextView empty=text("暂未设置应用限制",13,MUTED,false); empty.setPadding(0,dp(8),0,dp(4)); panel.addView(empty);
        } else for(AppLimit limit:limits){
            LinearLayout item=row(); item.setPadding(0,dp(8),0,dp(4));
            ImageView icon=new ImageView(activity); Drawable limitIcon=manager.loadAppIcon(limit.packageName);
            icon.setImageDrawable(limitIcon!=null?limitIcon:activity.getDrawable(android.R.drawable.sym_def_app_icon));
            item.addView(icon,new LinearLayout.LayoutParams(dp(38),dp(38)));
            LinearLayout labels=column(); labels.setPadding(dp(10),0,dp(8),0);
            labels.addView(text(appLabel(limit.packageName),15,INK,true));
            labels.addView(text("今日已用 "+duration(usageToday(limit.packageName))+" / 上限 "+duration(limit.dailyLimitMillis),12,MUTED,false));
            item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            Button remove=button("移除",Color.TRANSPARENT,Color.rgb(184,74,53)); remove.setTextSize(12); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(56),dp(38)); rp.setMargins(dp(6),0,0,0); item.addView(remove,rp);
            item.setOnClickListener(v->showAppLimitDialogV2(limit));
            remove.setOnClickListener(v->{AppLimitStore.remove(activity,limit.packageName); if(AppLimitStore.hasEnabled(activity))AppLimitService.start(activity);else AppLimitService.stop(activity); if(snapshot!=null)showSnapshot(snapshot);});
            panel.addView(item);
        }
        add.setOnClickListener(v->showAppLimitDialogV2(null)); addCard(panel);
    }

    private String appLabel(String pkg){
        try{return String.valueOf(activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(pkg,0)));}
        catch(Exception e){return pkg;}
    }

    private long usageToday(String pkg){
        UsageStatsManager usage=(UsageStatsManager)activity.getSystemService(Activity.USAGE_STATS_SERVICE);
        if(usage==null)return 0L;
        java.util.Calendar day=java.util.Calendar.getInstance(); day.set(java.util.Calendar.HOUR_OF_DAY,0); day.set(java.util.Calendar.MINUTE,0); day.set(java.util.Calendar.SECOND,0); day.set(java.util.Calendar.MILLISECOND,0);
        java.util.Map<String,UsageStats> values=usage.queryAndAggregateUsageStats(day.getTimeInMillis(),System.currentTimeMillis());
        UsageStats stat=values==null?null:values.get(pkg); return stat==null?0L:stat.getTotalTimeInForeground();
    }

    private void showAppLimitDialogV2(final AppLimit editing){
        final PackageManager pm=activity.getPackageManager();
        Intent launcher=new Intent(Intent.ACTION_MAIN); launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps=pm.queryIntentActivities(launcher,PackageManager.MATCH_ALL);
        final List<ResolveInfo> choices=new ArrayList<ResolveInfo>(); final List<String> labels=new ArrayList<String>();
        for(ResolveInfo info:apps){String pkg=info.activityInfo.packageName; if(pkg.equals(activity.getPackageName()))continue; String label=String.valueOf(info.loadLabel(pm)); if(!labels.contains(label)){choices.add(info);labels.add(label);}}
        if(choices.isEmpty()){Toast.makeText(activity,"没有找到可限制的应用",Toast.LENGTH_SHORT).show();return;}
        LinearLayout form=column(); form.setPadding(dp(20),dp(2),dp(20),0);
        TextView mode=text("每日累计使用时长（不是时间段）",13,GREEN,true); mode.setPadding(0,0,0,dp(6)); form.addView(mode);
        EditText search=new EditText(activity); search.setSingleLine(true); search.setHint("搜索应用"); search.setTextSize(15); search.setPadding(dp(12),0,dp(12),0); search.setBackground(round(Color.rgb(243,246,242),10)); form.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
        ScrollView appScroll=new ScrollView(activity); LinearLayout appList=column(); appScroll.addView(appList,new ScrollView.LayoutParams(-1,-2)); LinearLayout.LayoutParams appScrollParams=new LinearLayout.LayoutParams(-1,dp(220)); appScrollParams.setMargins(0,dp(6),0,dp(6)); form.addView(appScroll,appScrollParams);
        int initialSelection=0;
        if(editing!=null) for(int i=0;i<choices.size();i++) if(editing.packageName.equals(choices.get(i).activityInfo.packageName)){initialSelection=i;break;}
        final int[] selected={initialSelection}; final TextView selectedLabel=text("已选择："+labels.get(initialSelection),14,INK,true); selectedLabel.setPadding(0,dp(3),0,dp(5)); form.addView(selectedLabel);
        LinearLayout durationLabels=row(); durationLabels.addView(text("小时",12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1)); durationLabels.addView(text("分钟",12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1)); form.addView(durationLabels);
        int initialMinutes=editing==null?60:(int)Math.max(1L,editing.dailyLimitMillis/60000L);
        LinearLayout pick=row(); NumberPicker hours=new NumberPicker(activity); hours.setMinValue(0); hours.setMaxValue(23); String[] hourLabels=new String[24]; for(int i=0;i<24;i++)hourLabels[i]=String.format(Locale.CHINA,"%02d 小时",i); hours.setDisplayedValues(hourLabels); hours.setValue(initialMinutes/60); NumberPicker mins=new NumberPicker(activity); mins.setMinValue(0); mins.setMaxValue(59); String[] minuteLabels=new String[60]; for(int i=0;i<60;i++)minuteLabels[i]=String.format(Locale.CHINA,"%02d 分钟",i); mins.setDisplayedValues(minuteLabels); mins.setValue(initialMinutes%60); pick.addView(hours,new LinearLayout.LayoutParams(0,dp(150),1)); pick.addView(mins,new LinearLayout.LayoutParams(0,dp(150),1)); form.addView(pick);
        TextView note=text("达到每日累计时长后，当天会显示限制画面；例如设置 1:00，就是当天累计使用 1 小时。",12,MUTED,false); note.setLineSpacing(dp(3),1f); note.setPadding(0,dp(4),0,0); form.addView(note);
        final Runnable[] render={null}; render[0]=()->{
            appList.removeAllViews(); String query=search.getText().toString().trim().toLowerCase(Locale.CHINA); int shown=0;
            for(int i=0;i<choices.size();i++){String label=labels.get(i); if(query.length()>0&&!label.toLowerCase(Locale.CHINA).contains(query))continue; final int index=i;
                LinearLayout appRow=row(); appRow.setPadding(0,dp(5),0,dp(5)); ImageView icon=new ImageView(activity); icon.setImageDrawable(choices.get(i).loadIcon(pm)); appRow.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40))); TextView name=text(label,15,INK,false); name.setPadding(dp(10),0,0,0); appRow.addView(name,new LinearLayout.LayoutParams(0,dp(46),1)); appRow.setOnClickListener(v->{selected[0]=index;selectedLabel.setText("已选择："+labels.get(index));}); appList.addView(appRow); shown++; }
            if(shown==0){TextView empty=text("没有匹配的应用",13,MUTED,false);empty.setGravity(Gravity.CENTER);appList.addView(empty,new LinearLayout.LayoutParams(-1,dp(48)));}
        };
        render[0].run(); search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){render[0].run();} public void afterTextChanged(Editable e){}});
        new AlertDialog.Builder(activity).setTitle(editing==null?"设置应用使用限制":"修改应用使用限制").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
            int total=hours.getValue()*60+mins.getValue(); if(total<1){Toast.makeText(activity,"时长至少 1 分钟",Toast.LENGTH_SHORT).show();return;}
            String pkg=choices.get(selected[0]).activityInfo.packageName;
            AppLimitStore.upsert(activity,new AppLimit(pkg,total*60000L,true,0,true)); AppLimitService.start(activity); if(snapshot!=null)showSnapshot(snapshot);
        }).show();
    }

    private void showAppLimitDialog(){
        PackageManager pm=activity.getPackageManager();
        Intent launcher=new Intent(Intent.ACTION_MAIN); launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps=pm.queryIntentActivities(launcher,PackageManager.MATCH_ALL);
        final List<ResolveInfo> choices=new ArrayList<ResolveInfo>(); final List<String> labels=new ArrayList<String>();
        for(ResolveInfo info:apps){String pkg=info.activityInfo.packageName; if(pkg.equals(activity.getPackageName()))continue; if(!labels.contains(String.valueOf(info.loadLabel(pm)))){choices.add(info);labels.add(String.valueOf(info.loadLabel(pm)));}}
        if(choices.isEmpty()){Toast.makeText(activity,"没有找到可限制的应用",Toast.LENGTH_SHORT).show();return;}
        LinearLayout form=column(); form.setPadding(dp(20),dp(4),dp(20),0);
        android.widget.Spinner appSpinner=new android.widget.Spinner(activity); appSpinner.setAdapter(new android.widget.ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,labels)); form.addView(appSpinner,new LinearLayout.LayoutParams(-1,dp(52)));
        LinearLayout pick=row(); NumberPicker hours=new NumberPicker(activity); hours.setMinValue(0); hours.setMaxValue(23); hours.setValue(1); NumberPicker mins=new NumberPicker(activity); mins.setMinValue(0); mins.setMaxValue(59); mins.setValue(0); pick.addView(hours,new LinearLayout.LayoutParams(0,dp(150),1)); TextView colon=text(":",20,INK,true);colon.setGravity(Gravity.CENTER);pick.addView(colon,new LinearLayout.LayoutParams(dp(18),dp(150))); pick.addView(mins,new LinearLayout.LayoutParams(0,dp(150),1)); form.addView(pick);
        TextView note=text("每日 0:01 至 23:59 可选，达到后当天无法继续使用该 App。",12,MUTED,false); note.setPadding(0,dp(4),0,0); form.addView(note);
        new AlertDialog.Builder(activity).setTitle("设置应用使用限制").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
            int total=hours.getValue()*60+mins.getValue(); if(total<1){Toast.makeText(activity,"时长至少 1 分钟",Toast.LENGTH_SHORT).show();return;}
            String pkg=choices.get(appSpinner.getSelectedItemPosition()).activityInfo.packageName;
            AppLimitStore.upsert(activity,new AppLimit(pkg,total*60000L,true,0,true)); AppLimitService.start(activity); if(snapshot!=null)showSnapshot(snapshot);
        }).show();
    }

    private void addScore(HealthModels.HealthScore score){
        if(score==null)return;
        LinearLayout panel=card();panel.setBackground(round(Color.rgb(240,245,242),22));
        panel.addView(text("今日健康指数",18,INK,true));
        LinearLayout scoreLine=row();scoreLine.setPadding(0,dp(10),0,0);scoreLine.addView(text(String.valueOf(score.score),38,GREEN,true));
        TextView label=text(score.label,15,INK,true);label.setPadding(dp(12),0,0,0);scoreLine.addView(label);panel.addView(scoreLine);
        TextView explanation=text(score.explanation,13,MUTED,false);explanation.setLineSpacing(dp(3),1f);explanation.setPadding(0,dp(8),0,0);panel.addView(explanation);
        TextView formula=text("本地透明计算：目标 -"+score.goalPenalty+" · 连续使用 -"+score.continuousPenalty+" · 夜间使用 -"+score.nightPenalty,11,Color.rgb(137,148,142),false);
        formula.setPadding(0,dp(9),0,0);panel.addView(formula);addCard(panel);
    }

    private LinearLayout metricRow(String label,String value){
        LinearLayout row=row();row.addView(text(label,13,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));row.addView(text(value,14,INK,true));return row;
    }

    private void showGoalDialog(){
        int current=manager.getSettings().getDailyGoalMinutes();
        LinearLayout pickers=row();pickers.setPadding(dp(24),dp(10),dp(24),0);
        NumberPicker hours=new NumberPicker(activity);hours.setMinValue(0);hours.setMaxValue(23);hours.setValue(current/60);
        NumberPicker minutes=new NumberPicker(activity);minutes.setMinValue(0);minutes.setMaxValue(11);String[] labels=new String[12];for(int i=0;i<12;i++)labels[i]=String.format(Locale.CHINA,"%02d 分",i*5);minutes.setDisplayedValues(labels);minutes.setValue((current%60)/5);
        pickers.addView(hours,new LinearLayout.LayoutParams(0,dp(150),1));pickers.addView(minutes,new LinearLayout.LayoutParams(0,dp(150),1));
        new AlertDialog.Builder(activity).setTitle("每日手机使用目标").setView(pickers).setNegativeButton("取消",null)
            .setPositiveButton("保存",(d,w)->{int value=hours.getValue()*60+minutes.getValue()*5;if(value<30){Toast.makeText(activity,"目标至少设置为 30 分钟",Toast.LENGTH_SHORT).show();return;}manager.getSettings().setDailyGoalMinutes(value);if(snapshot!=null)showSnapshot(snapshot);}).show();
    }

    private void showReminderDialog(){
        final int[] values={30,45,60,90,0};String[] labels={"30 分钟","45 分钟","60 分钟","90 分钟","关闭"};
        int saved=manager.getSettings().getContinuousReminderMinutes(),checked=1;for(int i=0;i<values.length;i++)if(values[i]==saved)checked=i;
        final int[] selected={checked};
        new AlertDialog.Builder(activity).setTitle("连续使用提醒").setSingleChoiceItems(labels,checked,(d,which)->selected[0]=which)
            .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                manager.getSettings().setContinuousReminderMinutes(values[selected[0]]);
                HealthReminderScheduler.reschedule(activity);
                if(snapshot!=null)showSnapshot(snapshot);
            }).show();
    }

    private String comparison(long today,long yesterday){
        if(yesterday<=0)return "昨日暂无可比较数据";
        long percent=Math.round(Math.abs(today-yesterday)*100d/yesterday);
        if(today==yesterday)return "与昨日持平";
        return "较昨日 "+(today<yesterday?"↓ ":"↑ ")+percent+"%";
    }

    static String duration(long millis){
        long minutes=Math.max(0,millis)/60000L,hours=minutes/60,rest=minutes%60;
        if(hours>0&&rest>0)return hours+"小时"+rest+"分钟";
        if(hours>0)return hours+"小时";
        return rest+"分钟";
    }

    private void addCard(View card){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(20),0,0);root.addView(card,p);}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(20),dp(20),dp(20),dp(18));v.setBackground(round(Color.WHITE,22));v.setElevation(dp(2));return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int sp,int color,boolean bold){TextView v=new TextView(activity);v.setText(value);v.setTextSize(sp);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String value,int background,int foreground){Button b=new Button(activity);b.setText(value);b.setTextColor(foreground);b.setTextSize(14);b.setAllCaps(false);b.setBackground(round(background,12));return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
