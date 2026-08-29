import android.content.SharedPreferences;
import com.eyerest.app.SleepSettings;

import java.lang.reflect.Proxy;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public final class SleepSettingsTest {
    public static void main(String[] args){
        SharedPreferences cross=settings(23,30,7,30,3);
        check(SleepSettings.isInSleepWindow(at(2026,8,29,23,45),cross),"23:45 should be locked");
        check(SleepSettings.isInSleepWindow(at(2026,8,30,0,30),cross),"00:30 should be locked");
        check(!SleepSettings.isInSleepWindow(at(2026,8,30,7,30),cross),"wake boundary should unlock");
        check(!SleepSettings.isInSleepWindow(at(2026,8,29,22,0),cross),"22:00 should be normal");
        check(SleepSettings.isInWarningWindow(at(2026,8,29,23,27),cross),"warning boundary should start");
        check(!SleepSettings.isInWarningWindow(at(2026,8,29,23,26),cross),"before warning should be normal");
        check("2026-08-29".equals(SleepSettings.currentSessionKey(at(2026,8,30,1,0),cross)),"session key must survive midnight");
        check(SleepSettings.nextStart(at(2026,8,29,23,31),cross).get(Calendar.DAY_OF_MONTH)==30,"next start must roll to tomorrow");
        check(SleepSettings.wakeForCurrentWindow(at(2026,8,29,23,45),cross).get(Calendar.DAY_OF_MONTH)==30,"wake must roll to tomorrow");

        SharedPreferences daytime=settings(13,0,14,0,3);
        check(SleepSettings.isInSleepWindow(at(2026,8,29,13,30),daytime),"daytime window should lock");
        check(!SleepSettings.isInSleepWindow(at(2026,8,29,14,0),daytime),"daytime wake boundary should unlock");
        System.out.println("SleepSettingsTest: 11 checks passed");
    }

    private static SharedPreferences settings(int sh,int sm,int wh,int wm,int warning){
        Map<String,Object> values=new HashMap<>();
        values.put("sleep_start_hour",sh);values.put("sleep_start_minute",sm);
        values.put("sleep_wake_hour",wh);values.put("sleep_wake_minute",wm);
        values.put("sleep_warning_minutes",warning);
        return (SharedPreferences)Proxy.newProxyInstance(SleepSettingsTest.class.getClassLoader(),new Class[]{SharedPreferences.class},
            (proxy,method,args)->{
                if("getInt".equals(method.getName()))return values.getOrDefault(args[0],args[1]);
                if("getString".equals(method.getName()))return values.getOrDefault(args[0],args[1]);
                if("contains".equals(method.getName()))return values.containsKey(args[0]);
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Calendar at(int year,int month,int day,int hour,int minute){
        Calendar c=Calendar.getInstance();c.set(year,month-1,day,hour,minute,0);c.set(Calendar.MILLISECOND,0);return c;
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
