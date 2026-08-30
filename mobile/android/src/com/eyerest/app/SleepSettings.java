package com.eyerest.app;

import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** 睡眠助手的持久化字段与跨午夜时间计算。 */
public final class SleepSettings {
    public static final String MODE_OFF="off", MODE_TODAY="today", MODE_DAILY="daily";
    public static final String REASON_NONE="NONE", REASON_CALL="CALL", REASON_REBOOT="REBOOT", REASON_MANUAL="MANUAL";

    private SleepSettings() {}

    public static int startMinutes(SharedPreferences p){
        return p.getInt("sleep_start_hour",23)*60+p.getInt("sleep_start_minute",30);
    }

    public static int wakeMinutes(SharedPreferences p){
        return p.getInt("sleep_wake_hour",7)*60+p.getInt("sleep_wake_minute",30);
    }

    public static int minutesOfDay(Calendar c){
        return c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);
    }

    public static boolean valid(SharedPreferences p){return startMinutes(p)!=wakeMinutes(p);}

    public static boolean isInSleepWindow(Calendar now,SharedPreferences p){
        int value=minutesOfDay(now),start=startMinutes(p),wake=wakeMinutes(p);
        if(start==wake)return false;
        return start<wake?value>=start&&value<wake:value>=start||value<wake;
    }

    public static Calendar nextStart(Calendar now,SharedPreferences p){
        Calendar target=(Calendar)now.clone();
        target.set(Calendar.HOUR_OF_DAY,p.getInt("sleep_start_hour",23));
        target.set(Calendar.MINUTE,p.getInt("sleep_start_minute",30));
        target.set(Calendar.SECOND,0);target.set(Calendar.MILLISECOND,0);
        if(!target.after(now))target.add(Calendar.DAY_OF_MONTH,1);
        return target;
    }

    public static Calendar wakeForCurrentWindow(Calendar now,SharedPreferences p){
        Calendar wake=(Calendar)now.clone();
        wake.set(Calendar.HOUR_OF_DAY,p.getInt("sleep_wake_hour",7));
        wake.set(Calendar.MINUTE,p.getInt("sleep_wake_minute",30));
        wake.set(Calendar.SECOND,0);wake.set(Calendar.MILLISECOND,0);
        if(!wake.after(now))wake.add(Calendar.DAY_OF_MONTH,1);
        return wake;
    }

    /** 返回这一晚的标识；跨午夜后仍使用入睡日，保证来电/重启豁免不会在零点失效。 */
    public static String currentSessionKey(Calendar now,SharedPreferences p){
        Calendar anchor=(Calendar)now.clone();
        int start=startMinutes(p),wake=wakeMinutes(p),value=minutesOfDay(now);
        if(start>wake&&value<wake)anchor.add(Calendar.DAY_OF_MONTH,-1);
        return date(anchor);
    }

    /** 返回当前或下一次计划的入睡日，用于“今日”模式。 */
    public static String activePlanKey(Calendar now,SharedPreferences p){
        if(isInSleepWindow(now,p))return currentSessionKey(now,p);
        Calendar next=nextStart(now,p);
        return date(next);
    }

    public static boolean isEnabledForPlan(Calendar now,SharedPreferences p){
        String mode=p.getString("sleep_mode",MODE_OFF);
        if(MODE_DAILY.equals(mode))return true;
        if(!MODE_TODAY.equals(mode))return false;
        String planKey=isInSleepWindow(now,p)?currentSessionKey(now,p):activePlanKey(now,p);
        return planKey.equals(p.getString("sleep_mode_date",""));
    }

    public static boolean hasBypassForCurrentWindow(Calendar now,SharedPreferences p){
        return isInSleepWindow(now,p)&&currentSessionKey(now,p).equals(p.getString("sleep_bypass_date",""));
    }

    public static boolean isInWarningWindow(Calendar now,SharedPreferences p){
        if(isInSleepWindow(now,p))return false;
        long left=nextStart(now,p).getTimeInMillis()-now.getTimeInMillis();
        long warning=p.getInt("sleep_warning_minutes",3)*60_000L;
        return left>0&&left<=warning;
    }

    public static void setMode(SharedPreferences p,String mode){
        SharedPreferences.Editor edit=p.edit().putString("sleep_mode",mode)
            .putBoolean("sleep_lock_active",false).putString("sleep_state","NORMAL")
            .putBoolean("sleep_manual_closed",false);
        if(MODE_TODAY.equals(mode))edit.putString("sleep_mode_date",activePlanKey(Calendar.getInstance(),p));
        if(MODE_OFF.equals(mode))edit.putString("sleep_bypass_date","").putString("sleep_bypass_reason",REASON_NONE);
        edit.apply();
    }

    public static void setBypass(SharedPreferences p,Calendar now,String reason){
        p.edit().putString("sleep_bypass_date",currentSessionKey(now,p))
            .putString("sleep_bypass_reason",reason).putBoolean("sleep_lock_active",false)
            .putString("sleep_state",REASON_CALL.equals(reason)?"TODAY_BYPASS_CALL":"TODAY_BYPASS_REBOOT").apply();
    }

    public static String date(Calendar c){
        return new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(c.getTime());
    }

    public static String formatDuration(long millis){
        long seconds=Math.max(0,(millis+999)/1000);
        if(seconds>=3600)return String.format(Locale.CHINA,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);
        return String.format(Locale.CHINA,"%02d:%02d",seconds/60,seconds%60);
    }
}
