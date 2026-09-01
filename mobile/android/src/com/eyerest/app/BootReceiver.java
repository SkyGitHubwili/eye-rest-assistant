package com.eyerest.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;
import java.util.Locale;

public class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_RECOVER="com.eyerest.RECOVER";
    @Override public void onReceive(Context context, Intent intent) {
        android.content.SharedPreferences prefs=context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String action=intent==null?"":intent.getAction();
        boolean realBoot=Intent.ACTION_BOOT_COMPLETED.equals(action)||"android.intent.action.QUICKBOOT_POWERON".equals(action);
        if(realBoot)HealthReminderScheduler.schedule(context);
        java.util.Calendar calendar=java.util.Calendar.getInstance();
        if(realBoot&&SleepSettings.isEnabledForPlan(calendar,prefs)
            &&SleepSettings.isInSleepWindow(calendar,prefs)&&!SleepSettings.hasBypassForCurrentWindow(calendar,prefs))
            SleepSettings.setBypass(prefs,calendar,SleepSettings.REASON_REBOOT);
        if(SleepSettings.isEnabledForPlan(calendar,prefs)){
            Intent sleep=new Intent(context,SleepService.class).setAction(SleepService.ACTION_RECOVER);
            try{if(Build.VERSION.SDK_INT>=26)context.startForegroundService(sleep);else context.startService(sleep);}catch(Exception ignored){}
        }

        // 以下保持原护眼助手的恢复条件与行为不变。
        if (!prefs.getBoolean("keep_running_closed",false)) return;
        String mode=prefs.getString("protection_mode", "off");
        Calendar now=Calendar.getInstance();
        String today=String.format(Locale.CHINA,"%04d-%02d-%02d",now.get(Calendar.YEAR),now.get(Calendar.MONTH)+1,now.get(Calendar.DAY_OF_MONTH));
        if (!prefs.getBoolean("mode_started",false)) return;
        if (!"auto".equals(mode) && !("manual".equals(mode)&&today.equals(prefs.getString("manual_date","")))) return;
        Intent service = new Intent(context, EyeRestService.class).setAction(EyeRestService.ACTION_REEVALUATE);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
        } catch (Exception ignored) { }
    }
}
