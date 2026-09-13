package com.eyerest.app.ledger;

import android.app.AppOpsManager;
import android.app.usage.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.time.*;
import java.util.*;

public final class UsageRepository extends SQLiteOpenHelper {
    private final Context context;
    public UsageRepository(Context c) { super(c,"screenledger_usage_v1.db",null,1); context=c.getApplicationContext(); }
    public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE days (key TEXT PRIMARY KEY, data TEXT NOT NULL)"); }
    public void onUpgrade(SQLiteDatabase db,int old,int n) { }
    public static boolean allowed(Context c) {
        AppOpsManager ops=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);
        return ops!=null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    public synchronized UsageEngine.Day load(LocalDate date) throws Exception {
        ZoneId zone=ZoneId.systemDefault();
        String key=date+"@"+zone.getId();
        UsageEngine.Day saved=read(key);
        long start=date.atStartOfDay(zone).toInstant().toEpochMilli();
        long end=Math.min(System.currentTimeMillis(),date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        if(!allowed(context)) throw new SecurityException("请先开启使用情况访问权限");
        if(date.isBefore(LocalDate.now().minusDays(3))) return saved!=null?saved:empty(date);
        UsageStatsManager manager=(UsageStatsManager)context.getSystemService(Context.USAGE_STATS_SERVICE);
        UsageEvents stream=manager.queryEvents(start-2*86400000L,end);
        if(stream==null) { if(saved!=null) return saved; throw new IllegalStateException("系统暂未提供记录，请解锁手机后重试"); }
        List<UsageEngine.Event> events=new ArrayList<>(); UsageEvents.Event e=new UsageEvents.Event();
        while(stream.hasNextEvent()) { stream.getNextEvent(e); events.add(new UsageEngine.Event(e.getTimeStamp(),e.getEventType(),e.getPackageName(),e.getClassName())); }
        events.sort(Comparator.comparingLong(x->x.time));
        UsageEngine.Day day=UsageEngine.compute(events,start,end,zone); day.date=date.toString(); day.capturedAt=System.currentTimeMillis();
        if(!day.evidence && day.total==0 && saved!=null) return saved;
        // Never replace an archived full day with a shorter retained event window.
        if(saved!=null && !date.equals(LocalDate.now()) && ((saved.complete&&!day.complete) || saved.total>day.total)) return saved;
        if(day.evidence || day.total>0) { ContentValues v=new ContentValues(); v.put("key",key); v.put("data",encode(day).toString()); getWritableDatabase().insertWithOnConflict("days",null,v,SQLiteDatabase.CONFLICT_REPLACE); }
        return day;
    }
    public void archiveRecent() throws Exception { for(int i=2;i>=0;i--) load(LocalDate.now().minusDays(i)); }
    private UsageEngine.Day empty(LocalDate date) { UsageEngine.Day d=new UsageEngine.Day(); d.date=date.toString(); return d; }
    private UsageEngine.Day read(String key) throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM days WHERE key=?",new String[]{key})) {
            if(!c.moveToFirst()) return null;
            JSONObject j=new JSONObject(c.getString(0)); UsageEngine.Day d=new UsageEngine.Day();
            d.date=j.getString("date"); d.total=j.getLong("total"); d.unlocks=j.getInt("unlocks"); d.evidence=j.getBoolean("evidence"); d.complete=j.getBoolean("complete"); d.capturedAt=j.getLong("capturedAt");
            JSONArray items=j.getJSONArray("apps");
            for(int i=0;i<items.length();i++) { JSONObject x=items.getJSONObject(i); UsageEngine.App a=new UsageEngine.App(x.getString("pkg")); a.millis=x.getLong("millis"); a.opens=x.getInt("opens"); JSONArray h=x.getJSONArray("hours"); for(int k=0;k<24;k++)a.hours[k]=h.getLong(k); d.apps.put(a.pkg,a); }
            return d;
        }
    }
    private JSONObject encode(UsageEngine.Day d) throws Exception {
        JSONObject j=new JSONObject(); j.put("date",d.date).put("total",d.total).put("unlocks",d.unlocks).put("evidence",d.evidence).put("complete",d.complete).put("capturedAt",d.capturedAt);
        JSONArray items=new JSONArray(); for(UsageEngine.App a:d.apps.values()) { JSONArray h=new JSONArray(); for(long v:a.hours)h.put(v); items.put(new JSONObject().put("pkg",a.pkg).put("millis",a.millis).put("opens",a.opens).put("hours",h)); } return j.put("apps",items);
    }
}
