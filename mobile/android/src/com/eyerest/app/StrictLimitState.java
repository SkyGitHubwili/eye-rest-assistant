package com.eyerest.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.*;
import java.util.*;
import java.time.*;
import com.eyerest.app.ledger.UsageEngine;

/** Persist actual blocked windows and rest deadlines, independently of editable limits. */
final class StrictLimitState {
    private final SharedPreferences prefs;
    private final int boot;
    private final List<StrictLimitPolicy.Block> closed=new ArrayList<>();
    private String activePkg;
    private long activeFrom,lastCheckpoint;
    StrictLimitState(Context context){
        prefs=context.getSharedPreferences("strict_limits_v1",0);
        boot=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,0);
        long now=System.currentTimeMillis();
        try{
            JSONArray a=new JSONArray(prefs.getString("blocks","[]"));
            for(int i=0;i<a.length();i++){JSONArray b=a.getJSONArray(i);
                if(b.getLong(2)>now-2*86400000L)closed.add(new StrictLimitPolicy.Block(b.getString(0),b.getLong(1),b.getLong(2)));}
        }catch(JSONException ignored){}
        String previous=prefs.getString("active_pkg",null);
        long from=prefs.getLong("active_from",0),to=prefs.getLong("active_seen",0);
        // A killed service no longer shows its window: never exempt the offline gap.
        if(previous!=null&&to>from)closed.add(new StrictLimitPolicy.Block(previous,from,Math.min(to,now)));
        saveBlocks(now);
    }
    synchronized void overlay(String pkg,long now){
        if(Objects.equals(activePkg,pkg)){checkpoint(now);return;}
        if(activePkg!=null&&now>activeFrom)closed.add(new StrictLimitPolicy.Block(activePkg,activeFrom,now));
        activePkg=pkg;activeFrom=now;saveBlocks(now);
    }
    synchronized void checkpoint(long now){if(activePkg!=null&&now-lastCheckpoint>=1000){
        prefs.edit().putLong("active_seen",now).apply();lastCheckpoint=now;}}
    synchronized List<StrictLimitPolicy.Block> blocks(long now){
        List<StrictLimitPolicy.Block> result=new ArrayList<>(closed);
        if(activePkg!=null)result.add(new StrictLimitPolicy.Block(activePkg,activeFrom,now));
        return result;
    }
    private void saveBlocks(long now){
        JSONArray a=new JSONArray();closed.removeIf(b->b.to<now-2*86400000L);
        for(StrictLimitPolicy.Block b:closed)a.put(new JSONArray().put(b.pkg).put(b.from).put(b.to));
        prefs.edit().putString("blocks",a.toString()).putString("active_pkg",activePkg)
            .putLong("active_from",activeFrom).putLong("active_seen",now).commit();lastCheckpoint=now;
    }
    synchronized void beginRest(int group,long now){
        if(restRemaining(group,now)>0)return;
        prefs.edit().putInt("rest_boot_"+group,boot).putLong("rest_elapsed_"+group,SystemClock.elapsedRealtime()+StrictLimitPolicy.REST)
            .putLong("rest_wall_"+group,now+StrictLimitPolicy.REST).commit();
    }
    synchronized long restRemaining(int group,long now){
        long end=prefs.getLong("rest_wall_"+group,0);if(end==0)return 0;
        long remaining=StrictLimitPolicy.restRemaining(end,prefs.getLong("rest_elapsed_"+group,0),prefs.getInt("rest_boot_"+group,-1),boot,now,SystemClock.elapsedRealtime());
        if(remaining<=0){prefs.edit().putLong("rest_wall_"+group,0).putLong("reset_"+group,now).commit();return 0;}
        return remaining;
    }
    static final class RestState {final long[] resets=new long[4],remaining=new long[4];}
    synchronized RestState restState(long now){
        RestState result=new RestState();
        for(int i=0;i<4;i++){result.remaining[i]=restRemaining(i,now);result.resets[i]=prefs.getLong("reset_"+i,0);}
        return result;
    }
    synchronized long morningRemaining(List<UsageEngine.Event> events,LocalDate date,ZoneId zone,long now){
        long six=date.atTime(6,0).atZone(zone).toInstant().toEpochMilli();
        if(now<six)return 0;
        long first=date.toString().equals(prefs.getString("wake_date",""))?prefs.getLong("wake_start",0):0;
        if(first==0){
            first=StrictLimitPolicy.firstWake(events,six,now,0);
            if(first>0)prefs.edit().putString("wake_date",date.toString()).putLong("wake_start",first).commit();
        }
        return first==0?0:Math.max(0,first+StrictLimitPolicy.MORNING-now);
    }
    void publish(String date,StrictLimitPolicy.Usage usage,long morningRemaining,String status,long now){
        SharedPreferences.Editor edit=prefs.edit().putString("date",date).putLong("updated",now)
            .putLong("morning_remaining",morningRemaining).putString("status",status);
        if(usage!=null)for(int i=0;i<4;i++)edit.putLong("used_"+i,usage.used[i]).putLong("continuous_"+i,usage.continuous[i]);
        edit.apply();
    }
}
