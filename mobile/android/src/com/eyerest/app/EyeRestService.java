package com.eyerest.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;

public class EyeRestService extends Service {
    public static final String ACTION_RESUME="com.eyerest.RESUME", ACTION_RESET="com.eyerest.RESET",
        ACTION_PAUSE="com.eyerest.PAUSE", ACTION_REEVALUATE="com.eyerest.REEVALUATE",
        ACTION_MODE_MANUAL="com.eyerest.MODE_MANUAL", ACTION_MODE_AUTO="com.eyerest.MODE_AUTO",
        ACTION_MODE_OFF="com.eyerest.MODE_OFF", ACTION_BREAK_NOW="com.eyerest.BREAK_NOW",
        ACTION_APP_CLOSED="com.eyerest.APP_CLOSED";
    private static final String CHANNEL="eye_rest_timer";
    private SharedPreferences prefs;
    private final Handler handler=new Handler();
    private WindowManager windowManager;
    private View overlay;
    private View keepAliveOverlay;
    private TextView breakCountdown;
    private long breakEnd;
    private boolean inBreak;
    private boolean sleepPaused;
    private boolean foregroundStarted;
    private PowerManager.WakeLock timerWakeLock;
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))enterScreenPause();else reevaluate();
        }
    };

    @Override public void onCreate(){
        super.onCreate();prefs=getSharedPreferences("settings",MODE_PRIVATE);normalizeDurations();createChannel();
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenReceiver,filter);
        handler.post(tick);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?ACTION_REEVALUATE:intent.getAction();
        if(!foregroundStarted){startForeground(20,notification(initialTimerText(action)));foregroundStarted=true;}
        if(prefs.getBoolean("keep_running_closed",false))scheduleRecovery(15*60*1000L);else cancelRecovery();
        if(ACTION_MODE_MANUAL.equals(action))enableMode("manual");
        else if(ACTION_MODE_AUTO.equals(action))enableMode("auto");
        else if(ACTION_MODE_OFF.equals(action)){disableProtection();return START_NOT_STICKY;}
        else if(ACTION_PAUSE.equals(action))pauseWork();
        else if(ACTION_RESUME.equals(action))resumeWork();
        else if(ACTION_RESET.equals(action))startFresh();
        else if(ACTION_BREAK_NOW.equals(action)){if(isEnabledToday()&&isWithinActiveHours())showBreak();}
        else if(ACTION_APP_CLOSED.equals(action)){closeForAppExit();return START_NOT_STICKY;}
        else reevaluate();
        if(prefs.getBoolean("screen_paused",false))cancelRecovery();else scheduleRecovery(15*60*1000L);
        updateRuntimeProtection();
        return START_STICKY;
    }

    private void enableMode(String mode){
        SharedPreferences.Editor edit=prefs.edit().putString("protection_mode",mode).putBoolean("mode_started",false)
            .putBoolean("running",false).putBoolean("user_paused",false).putBoolean("screen_paused",false).putLong("remaining_ms",0);
        if("manual".equals(mode))edit.putString("manual_date",today());
        edit.apply();
        stopRuntimeProtection();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }

    private void disableProtection(){
        cancelRecovery();
        inBreak=false;sleepPaused=false;
        if(overlay!=null&&windowManager!=null){windowManager.removeView(overlay);overlay=null;}
        prefs.edit().putString("protection_mode","off").putBoolean("running",false)
            .putBoolean("mode_started",false).putBoolean("user_paused",false).putBoolean("screen_paused",false).putLong("remaining_ms",0)
            .putLong("break_end",0).apply();
        stopRuntimeProtection();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }

    private void startFresh(){
        if(!isEnabledToday())return;
        prefs.edit().putBoolean("mode_started",true).putLong("remaining_ms",fullWorkMillis()).putBoolean("user_paused",false).apply();
        resumeWork();
    }

    private void resumeWork(){
        if(!isEnabledToday())return;
        if(!isWithinActiveHours()){enterSleep();return;}
        if(!isScreenUsable()){enterScreenPause();return;}
        sleepPaused=false;
        long remaining=prefs.getLong("remaining_ms",0);
        if(remaining<=0)remaining=fullWorkMillis();
        long end=System.currentTimeMillis()+remaining;
        prefs.edit().putBoolean("mode_started",true).putBoolean("running",true).putBoolean("user_paused",false).putBoolean("screen_paused",false)
            .putLong("work_end",end).putLong("remaining_ms",0).apply();
        updateNotification("护眼倒计时 · "+formatDuration(remaining));
        scheduleRecovery(15*60*1000L);
        updateRuntimeProtection();
    }

    private void pauseWork(){
        long remaining=prefs.getBoolean("running",false)
            ?Math.max(1000,prefs.getLong("work_end",0)-System.currentTimeMillis())
            :prefs.getLong("remaining_ms",fullWorkMillis());
        prefs.edit().putBoolean("running",false).putBoolean("user_paused",true)
            .putBoolean("screen_paused",false).putLong("remaining_ms",remaining).putString("pause_date",today()).apply();
        updateRuntimeProtection();
        updateNotification("计时已暂停 · 剩余 "+formatDuration(remaining));
    }

    private void reevaluate(){
        if(!isEnabledToday()){disableProtection();return;}
        if(!prefs.getBoolean("mode_started",false)){stopRuntimeProtection();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();return;}
        clearExpiredAutoPause();
        if(!isWithinActiveHours()){enterSleep();return;}
        if(!isScreenUsable()){enterScreenPause();return;}
        if(prefs.getBoolean("screen_paused",false)){
            prefs.edit().putBoolean("screen_paused",false).apply();
            if(!prefs.getBoolean("user_paused",false))resumeWork();else updateNotification("计时已暂停");
            return;
        }
        long savedBreakEnd=prefs.getLong("break_end",0);
        if(savedBreakEnd>System.currentTimeMillis()){
            if(!inBreak)showBreakUntil(savedBreakEnd);
            else updateNotification("正在护眼休息");
            return;
        }
        if(savedBreakEnd>0)prefs.edit().putLong("break_end",0).apply();
        if(inBreak)return;
        if(prefs.getBoolean("running",false)){
            if(prefs.getLong("work_end",0)<=System.currentTimeMillis())showBreak();
            else scheduleRecovery(15*60*1000L);
            return;
        }
        if(!prefs.getBoolean("user_paused",false)){
            if(sleepPaused)startFresh();else resumeWork();
        }else updateNotification("计时已暂停");
    }

    private final Runnable tick=new Runnable(){public void run(){
        long now=System.currentTimeMillis();
        if(!isEnabledToday()){if(prefs.getBoolean("running",false)||inBreak)disableProtection();return;}
        if(!prefs.getBoolean("mode_started",false)){stopRuntimeProtection();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();return;}
        clearExpiredAutoPause();
        if(!isWithinActiveHours()){
            if(!sleepPaused||prefs.getBoolean("running",false)||inBreak)enterSleep();
            handler.postDelayed(this,1000);return;
        }
        if(!isScreenUsable()){
            if(!prefs.getBoolean("screen_paused",false)||prefs.getBoolean("running",false)||inBreak)enterScreenPause();
            handler.postDelayed(this,1000);return;
        }
        if(prefs.getBoolean("screen_paused",false)){
            prefs.edit().putBoolean("screen_paused",false).apply();
            if(!prefs.getBoolean("user_paused",false))resumeWork();
        }
        if(!prefs.getBoolean("keep_running_closed",false)
            &&(System.currentTimeMillis()-prefs.getLong("main_heartbeat",0)>4000L)){closeForAppExit();return;}
        if(sleepPaused){sleepPaused=false;if(!prefs.getBoolean("user_paused",false))startFresh();}
        long savedBreakEnd=prefs.getLong("break_end",0);
        if(!inBreak&&savedBreakEnd>now)showBreakUntil(savedBreakEnd);
        else if(!inBreak&&savedBreakEnd>0)prefs.edit().putLong("break_end",0).apply();
        if(inBreak){
            long left=Math.max(0,breakEnd-now);
            if(breakCountdown!=null)breakCountdown.setText(String.valueOf((left+999)/1000));
            if(left<=0)endBreak();
        }else if(prefs.getBoolean("running",false)){
            long left=Math.max(0,prefs.getLong("work_end",0)-now);
            if(left<=0)showBreak();
        }
        handler.postDelayed(this,1000);
    }};

    private void showBreak(){
        showBreakUntil(System.currentTimeMillis()+prefs.getInt("break_seconds",20)*1000L);
    }

    private void showBreakUntil(long endAt){
        if(inBreak||!isEnabledToday()||!prefs.getBoolean("mode_started",false)||!isWithinActiveHours()||!isScreenUsable())return;
        breakEnd=endAt;
        prefs.edit().putBoolean("running",false).putLong("remaining_ms",0).putLong("break_end",breakEnd).apply();
        inBreak=true;
        removeKeepAliveOverlay();
        updateWakeLock();
        scheduleRecovery(15*60*1000L);
        if(!Settings.canDrawOverlays(this)){updateNotification("休息时间到了，请放下手机看看远处");return;}
        windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
        FrameLayout root=new FrameLayout(this);
        final int immersiveFlags=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN
            |View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        root.setSystemUiVisibility(immersiveFlags);
        root.setOnSystemUiVisibilityChangeListener(visibility->root.setSystemUiVisibility(immersiveFlags));
        File custom=new File(getFilesDir(),"break_image");
        if(custom.exists()){
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(BitmapFactory.decodeFile(custom.getAbsolutePath()));
            root.addView(image,new FrameLayout.LayoutParams(-1,-1));
        }else root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(18,59,53),Color.rgb(79,137,105),Color.rgb(203,174,116)}));
        View tint=new View(this);tint.setBackgroundColor(Color.argb(55,8,24,20));root.addView(tint,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.CENTER);content.setPadding(dp(28),dp(40),dp(28),dp(40));
        TextView title=text("看看远处，放松眼睛",36,true);title.setTextColor(Color.rgb(190,242,201));title.setGravity(Gravity.CENTER);title.setShadowLayer(dp(3),0,dp(2),Color.argb(170,0,40,20));content.addView(title);
        TextView tip=text("缓慢眨眼 · 放松肩颈 · 深呼吸",18,false);tip.setTextColor(Color.rgb(213,247,220));tip.setGravity(Gravity.CENTER);tip.setShadowLayer(dp(2),0,dp(1),Color.argb(150,0,40,20));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.setMargins(0,dp(14),0,dp(30));content.addView(tip,tp);
        breakCountdown=text(String.valueOf(prefs.getInt("break_seconds",20)),44,true);breakCountdown.setGravity(Gravity.CENTER);breakCountdown.setBackground(circle());content.addView(breakCountdown,new LinearLayout.LayoutParams(dp(112),dp(112)));
        int earlyRemaining=earlyEndRemaining();
        Button end=new Button(this);end.setText(earlyRemaining>0?"提前结束（本月剩余 "+earlyRemaining+" 次）":"本月提前结束机会已用完");end.setTextColor(Color.WHITE);end.setTextSize(13);end.setAllCaps(false);end.setEnabled(earlyRemaining>0);end.setAlpha(earlyRemaining>0?1f:.55f);GradientDrawable eb=new GradientDrawable();eb.setColor(Color.argb(50,255,255,255));eb.setCornerRadius(dp(14));eb.setStroke(dp(1),Color.argb(115,255,255,255));end.setBackground(eb);end.setOnClickListener(v->{if(consumeEarlyEnd())endBreak();});
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(dp(250),dp(52));ep.setMargins(0,dp(34),0,0);content.addView(end,ep);
        root.addView(content,new FrameLayout.LayoutParams(-1,-1));
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,type,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS|WindowManager.LayoutParams.FLAG_FULLSCREEN,PixelFormat.TRANSLUCENT);
        if(Build.VERSION.SDK_INT>=28)lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.gravity=Gravity.TOP|Gravity.START;windowManager.addView(root,lp);
        if(Build.VERSION.SDK_INT>=30&&root.getWindowInsetsController()!=null){
            root.getWindowInsetsController().hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
            root.getWindowInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        overlay=root;updateNotification("正在护眼休息");
    }

    private void endBreak(){
        inBreak=false;prefs.edit().putLong("break_end",0).apply();if(overlay!=null&&windowManager!=null){windowManager.removeView(overlay);overlay=null;}
        if(isEnabledToday()&&isWithinActiveHours())startFresh();else if(isEnabledToday())enterSleep();else disableProtection();
    }

    private void enterSleep(){
        sleepPaused=true;inBreak=false;
        if(overlay!=null&&windowManager!=null){windowManager.removeView(overlay);overlay=null;}
        SharedPreferences.Editor edit=prefs.edit().putBoolean("running",false).putBoolean("screen_paused",false).putLong("break_end",0);
        if(!prefs.getBoolean("user_paused",false))edit.putLong("remaining_ms",fullWorkMillis());
        edit.apply();stopRuntimeProtection();updateNotification("睡眠时段，护眼计时已暂停");
    }

    private void enterScreenPause(){
        long now=System.currentTimeMillis();
        boolean wasBreak=inBreak||prefs.getLong("break_end",0)>now;
        long remaining=wasBreak?fullWorkMillis():(prefs.getBoolean("running",false)
            ?Math.max(1000,prefs.getLong("work_end",0)-now)
            :prefs.getLong("remaining_ms",fullWorkMillis()));
        inBreak=false;
        if(overlay!=null&&windowManager!=null){try{windowManager.removeView(overlay);}catch(Exception ignored){}overlay=null;}
        prefs.edit().putBoolean("running",false).putBoolean("screen_paused",true)
            .putLong("remaining_ms",remaining).putLong("break_end",0).apply();
        cancelRecovery();stopRuntimeProtection();updateNotification("屏幕已关闭，护眼计时已暂停");
    }

    private boolean isScreenUsable(){
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        if(power==null||!power.isInteractive())return false;
        KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        return keyguard==null||!keyguard.isKeyguardLocked();
    }

    private boolean isEnabledToday(){
        String mode=prefs.getString("protection_mode","off");
        if("auto".equals(mode))return true;
        if("manual".equals(mode)&&today().equals(prefs.getString("manual_date","")))return true;
        if("manual".equals(mode))prefs.edit().putString("protection_mode","off").putBoolean("mode_started",false).putBoolean("running",false).putBoolean("user_paused",false).putLong("remaining_ms",0).apply();
        return false;
    }

    private void clearExpiredAutoPause(){
        if("auto".equals(prefs.getString("protection_mode","off"))&&prefs.getBoolean("user_paused",false)
            &&!today().equals(prefs.getString("pause_date","")))
            prefs.edit().putBoolean("user_paused",false).putLong("remaining_ms",0).apply();
    }

    private String today(){Calendar c=Calendar.getInstance();return String.format(Locale.CHINA,"%04d-%02d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));}
    private String initialTimerText(String action){
        long remaining;
        if(ACTION_RESET.equals(action))remaining=fullWorkMillis();
        else if(prefs.getBoolean("running",false))remaining=Math.max(0,prefs.getLong("work_end",0)-System.currentTimeMillis());
        else remaining=prefs.getLong("remaining_ms",0)>0?prefs.getLong("remaining_ms",0):fullWorkMillis();
        return "护眼倒计时 · "+formatDuration(remaining);
    }
    private void normalizeDurations(){int saved=prefs.getInt("work_minutes",20);int work=saved==0||saved==25||saved==30?saved:20;SharedPreferences.Editor edit=prefs.edit().putInt("work_minutes",work).putInt("break_seconds",work==0?10:work);if(saved!=work)edit.putBoolean("running",false).putBoolean("user_paused",false).putLong("remaining_ms",0);edit.apply();}
    private long fullWorkMillis(){return prefs.getInt("work_minutes",20)==0?10000L:prefs.getInt("work_minutes",20)*60000L;}
    private String formatDuration(long millis){return String.format(Locale.CHINA,"%02d:%02d",millis/60000,(millis/1000)%60);}
    private int earlyEndRemaining(){Calendar now=Calendar.getInstance();String month=now.get(Calendar.YEAR)+"-"+(now.get(Calendar.MONTH)+1);if(!month.equals(prefs.getString("early_end_month","")))prefs.edit().putString("early_end_month",month).putInt("early_end_count",0).apply();return Math.max(0,3-prefs.getInt("early_end_count",0));}
    private boolean consumeEarlyEnd(){int remaining=earlyEndRemaining();if(remaining<=0)return false;prefs.edit().putInt("early_end_count",prefs.getInt("early_end_count",0)+1).apply();return true;}
    private boolean isWithinActiveHours(){int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY),start=prefs.getInt("start_hour",8),end=prefs.getInt("end_hour",23);if(start==end)return true;return start<end?hour>=start&&hour<end:hour>=start||hour<end;}
    private TextView text(String value,int sp,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(sp);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private GradientDrawable circle(){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(Color.argb(60,255,255,255));return d;}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"护眼计时",NotificationManager.IMPORTANCE_LOW);c.setDescription("保持护眼计时在后台运行");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification notification(String content){
        Intent i=new Intent(this,MainActivity.class);
        PendingIntent p=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(com.eyerest.app.R.mipmap.ic_launcher)
            .setContentTitle("护眼助手").setContentIntent(p).setOnlyAlertOnce(true).setOngoing(true);
        long end=prefs==null?0:prefs.getLong("work_end",0);
        if(prefs!=null&&prefs.getBoolean("running",false)&&end>System.currentTimeMillis()){
            builder.setContentText("距离下次护眼休息").setWhen(end).setUsesChronometer(true)
                .setChronometerCountDown(true).setShowWhen(true);
        }else builder.setContentText(content).setShowWhen(false);
        return builder.build();
    }
    private void updateNotification(String text){getSystemService(NotificationManager.class).notify(20,notification(text));}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onTaskRemoved(Intent rootIntent){
        if(prefs.getBoolean("keep_running_closed",false)&&isEnabledToday()&&prefs.getBoolean("mode_started",false)){
            updateNotification(initialTimerText(ACTION_REEVALUATE));
            handler.removeCallbacks(tick);handler.post(tick);
            scheduleRecovery(2000L);
        }else if(!prefs.getBoolean("keep_running_closed",false)){
            closeForAppExit();
        }
        super.onTaskRemoved(rootIntent);
    }
    private void scheduleRecovery(long delay){
        if(!prefs.getBoolean("keep_running_closed",false)||!isEnabledToday()||!prefs.getBoolean("mode_started",false))return;
        long now=System.currentTimeMillis();
        long workEnd=prefs.getLong("work_end",0),savedBreakEnd=prefs.getLong("break_end",0);
        if(prefs.getBoolean("running",false)&&workEnd>now)delay=Math.min(delay,workEnd-now);
        if(savedBreakEnd>now)delay=Math.min(delay,savedBreakEnd-now);
        delay=Math.max(1000L,delay);
        AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);
        Intent i=new Intent(this,BootReceiver.class).setAction(BootReceiver.ACTION_RECOVER).setPackage(getPackageName());
        PendingIntent p=PendingIntent.getBroadcast(this,7788,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+delay,p);
    }
    private void cancelRecovery(){
        AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);
        Intent i=new Intent(this,BootReceiver.class).setAction(BootReceiver.ACTION_RECOVER).setPackage(getPackageName());
        PendingIntent p=PendingIntent.getBroadcast(this,7788,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(p);p.cancel();
    }
    private void updateWakeLock(){
        boolean shouldHold=prefs!=null&&prefs.getBoolean("keep_running_closed",false)
            &&prefs.getBoolean("mode_started",false)
            &&(prefs.getBoolean("running",false)||prefs.getLong("break_end",0)>System.currentTimeMillis());
        if(shouldHold){
            if(timerWakeLock==null){
                PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
                timerWakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"EyeRest:Timer");
                timerWakeLock.setReferenceCounted(false);
            }
            if(!timerWakeLock.isHeld())timerWakeLock.acquire();
        }else releaseWakeLock();
    }
    private void releaseWakeLock(){
        if(timerWakeLock!=null&&timerWakeLock.isHeld())timerWakeLock.release();
    }
    private boolean shouldProtectRuntime(){
        return prefs!=null&&prefs.getBoolean("keep_running_closed",false)
            &&prefs.getBoolean("mode_started",false)
            &&(prefs.getBoolean("running",false)||prefs.getLong("break_end",0)>System.currentTimeMillis());
    }
    private void updateRuntimeProtection(){
        updateWakeLock();
        if(shouldProtectRuntime()&&!inBreak&&Settings.canDrawOverlays(this))ensureKeepAliveOverlay();
        else removeKeepAliveOverlay();
    }
    private void ensureKeepAliveOverlay(){
        if(keepAliveOverlay!=null)return;
        if(windowManager==null)windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
        View marker=new View(this);marker.setBackgroundColor(Color.TRANSPARENT);
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(1,1,type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;lp.alpha=.01f;
        try{windowManager.addView(marker,lp);keepAliveOverlay=marker;}catch(Exception ignored){}
    }
    private void removeKeepAliveOverlay(){
        if(keepAliveOverlay!=null&&windowManager!=null){
            try{windowManager.removeView(keepAliveOverlay);}catch(Exception ignored){}
            keepAliveOverlay=null;
        }
    }
    private void stopRuntimeProtection(){removeKeepAliveOverlay();releaseWakeLock();}
    private void closeForAppExit(){
        cancelRecovery();inBreak=false;sleepPaused=false;
        if(overlay!=null&&windowManager!=null){windowManager.removeView(overlay);overlay=null;}
        prefs.edit().putBoolean("running",false).putBoolean("mode_started",false)
            .putBoolean("user_paused",false).putBoolean("screen_paused",false).putLong("remaining_ms",0).putLong("work_end",0)
            .putLong("break_end",0).apply();
        stopRuntimeProtection();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }
    private boolean hasMainTask(){
        ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
        if(manager==null)return false;
        for(ActivityManager.AppTask task:manager.getAppTasks()){
            ActivityManager.RecentTaskInfo info=task.getTaskInfo();
            if(info!=null&&info.baseIntent!=null&&info.baseIntent.getComponent()!=null
                &&getPackageName().equals(info.baseIntent.getComponent().getPackageName()))return true;
        }
        return false;
    }
    @Override public void onDestroy(){
        handler.removeCallbacks(tick);try{unregisterReceiver(screenReceiver);}catch(Exception ignored){}
        if(overlay!=null&&windowManager!=null)windowManager.removeView(overlay);
        if(prefs!=null&&prefs.getBoolean("keep_running_closed",false)&&isEnabledToday()&&prefs.getBoolean("mode_started",false))scheduleRecovery(2000L);
        stopRuntimeProtection();
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
