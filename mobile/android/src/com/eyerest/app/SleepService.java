package com.eyerest.app;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.telephony.TelephonyManager;

import java.util.Calendar;

/** 独立睡眠前台服务，避免改变已经稳定的护眼计时生命周期。 */
public final class SleepService extends Service implements SleepController.Host {
    public static final String ACTION_REFRESH="com.eyerest.SLEEP_REFRESH";
    public static final String ACTION_RECOVER="com.eyerest.SLEEP_RECOVER";
    private static final String CHANNEL="sleep_assistant";
    private static final int NOTIFICATION_ID=21;
    private SharedPreferences prefs;
    private SleepController controller;
    private final Handler handler=new Handler();
    private boolean foreground;

    private final BroadcastReceiver events=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String action=intent.getAction();
            if(Intent.ACTION_SCREEN_OFF.equals(action))controller.onScreenOff();
            else if(TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)){
                String phoneState=intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if(TelephonyManager.EXTRA_STATE_RINGING.equals(phoneState))controller.onIncomingCall();
            }else controller.onScreenAvailable();
        }
    };

    @Override public void onCreate(){
        super.onCreate();prefs=getSharedPreferences("settings",MODE_PRIVATE);createChannel();
        controller=new SleepController(this,prefs,this);
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(events,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(events,filter);
        handler.post(tick);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!foreground){startForeground(NOTIFICATION_ID,notification("睡眠计划正在运行"));foreground=true;}
        controller.evaluate();scheduleRecovery();
        if(!isConfigured()){shutdown();return START_NOT_STICKY;}
        return START_STICKY;
    }

    private final Runnable tick=new Runnable(){@Override public void run(){
        controller.evaluate();
        if(!isConfigured()){shutdown();return;}
        handler.postDelayed(this,1000L);
    }};

    private boolean isConfigured(){
        String mode=prefs.getString("sleep_mode",SleepSettings.MODE_OFF);
        if(SleepSettings.MODE_DAILY.equals(mode))return true;
        return SleepSettings.MODE_TODAY.equals(mode)&&SleepSettings.isEnabledForPlan(Calendar.getInstance(),prefs);
    }

    @Override public void updateSleepNotification(String state,String text){
        if(!android.provider.Settings.canDrawOverlays(this)&&("PRE_SLEEP_WARNING".equals(state)||"SLEEP_LOCKED".equals(state)))
            text="缺少覆盖屏幕权限 · "+text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(text));
    }

    @Override public void disableSleepAssistant(){shutdown();}

    private Notification notification(String text){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,21,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(com.eyerest.app.R.mipmap.ic_launcher)
            .setContentTitle("睡眠助手").setContentText(text).setContentIntent(pending)
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false);
        String state=prefs==null?"NORMAL":prefs.getString("sleep_state","NORMAL");
        if(prefs!=null&&SleepSettings.valid(prefs)){
            Calendar now=Calendar.getInstance();
            long target=0;
            if("SLEEP_LOCKED".equals(state)){
                target=SleepSettings.wakeForCurrentWindow(now,prefs).getTimeInMillis();
                builder.setContentText("睡眠模式进行中 · 距离起床");
            }else if("PRE_SLEEP_WARNING".equals(state)){
                target=SleepSettings.nextStart(now,prefs).getTimeInMillis();
                builder.setContentText("即将进入睡眠");
            }else if("NORMAL".equals(state)&&SleepSettings.isEnabledForPlan(now,prefs)){
                target=SleepSettings.nextStart(now,prefs).getTimeInMillis();
                builder.setContentText("距离睡眠");
            }
            if(target>System.currentTimeMillis()){
                builder.setWhen(target).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true);
            }
        }
        return builder.build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"睡眠计划",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("睡前提醒与睡眠锁定服务");getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void scheduleRecovery(){
        long delay=("PRE_SLEEP_WARNING".equals(controller.getState())||"SLEEP_LOCKED".equals(controller.getState()))?30_000L:5*60_000L;
        AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);
        Intent recover=new Intent(this,BootReceiver.class).setAction(ACTION_RECOVER).setPackage(getPackageName());
        PendingIntent pending=PendingIntent.getBroadcast(this,7790,recover,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+delay,pending);
    }

    private void cancelRecovery(){
        AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);
        Intent recover=new Intent(this,BootReceiver.class).setAction(ACTION_RECOVER).setPackage(getPackageName());
        PendingIntent pending=PendingIntent.getBroadcast(this,7790,recover,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pending);pending.cancel();
    }

    private void shutdown(){
        handler.removeCallbacks(tick);cancelRecovery();controller.stop();
        stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent){scheduleRecovery();super.onTaskRemoved(rootIntent);}
    @Override public void onDestroy(){
        handler.removeCallbacks(tick);try{unregisterReceiver(events);}catch(Exception ignored){}
        controller.stop();if(isConfigured())scheduleRecovery();super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
