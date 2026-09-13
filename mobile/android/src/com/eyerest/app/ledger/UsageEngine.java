package com.eyerest.app.ledger;

import java.time.*;
import java.util.*;

/** One foreground owner at a time. Pure Java, independently testable. */
public final class UsageEngine {
    public static final class Span {
        public final String pkg; public final long from,to;
        public Span(String pkg,long from,long to){this.pkg=pkg;this.from=from;this.to=to;}
    }
    public static final class Event {
        public final long time; public final int type; public final String pkg, activity;
        public Event(long t, int k, String p, String a) { time=t; type=k; pkg=p; activity=a; }
    }
    public static final class App {
        public final String pkg; public long millis; public long fragmentMillis; public int opens;
        public final long[] hours=new long[24];
        public App(String p) { pkg=p; }
    }
    public static final class Day {
        // Optional trace used by the limiter; normal statistics do not retain it.
        public List<Span> spans;
        public String foregroundPackage;
        public String foregroundActivity;
        public final Map<String,App> apps=new HashMap<>();
        public long total; public long fragmentMillis; public int unlocks; public boolean evidence, complete;
        public long capturedAt; public String date;
        public List<App> sorted() { List<App> a=new ArrayList<>(apps.values()); a.removeIf(x->x.millis==0); a.sort((x,y)->Long.compare(y.millis,x.millis)); return a; }
    }
    public static Day compute(List<Event> events, long start, long end, ZoneId zone) {
        return compute(events,start,end,zone,false);
    }
    public static Day compute(List<Event> events, long start, long end, ZoneId zone, boolean trace) {
        Day out=new Day();
        if(trace)out.spans=new ArrayList<>();
        String owner=null, activity=null, lastOwner=null; long since=start, lastEnd=Long.MIN_VALUE;
        boolean screen=true, locked=false;
        for(Event e:events) {
            if(e.time>=end) break;
            if(e.time>=start) out.evidence=true;
            if(e.time<=start) out.complete=true;
            if(e.type==1 && e.pkg!=null) {
                if(owner!=null) add(out, owner, since, e.time, start, end, zone);
                boolean same= e.pkg.equals(owner) || (e.pkg.equals(lastOwner) && e.time-lastEnd<=1000);
                owner=null; activity=null;
                if(screen && !locked) {
                    owner=e.pkg; activity=e.activity; since=e.time;
                    if(e.time>=start && !same) out.apps.computeIfAbsent(owner, App::new).opens++;
                }
            } else if(e.type==2 && Objects.equals(owner,e.pkg) && Objects.equals(activity,e.activity)) {
                add(out,owner,since,e.time,start,end,zone);
                lastOwner=owner; lastEnd=e.time; owner=null; activity=null;
            } else if(e.type==16 || e.type==17 || e.type==26 || e.type==27) {
                // Startup with no shutdown: duration since the last event is unknowable.
                if(owner!=null && e.type!=27) add(out,owner,since,e.time,start,end,zone);
                owner=null; activity=null; lastOwner=null;
                if(e.type==16) screen=false;
                if(e.type==17) locked=true;
                if(e.type==26 || e.type==27) { screen=true; locked=false; }
            } else if(e.type==15) screen=true;
            else if(e.type==18) { locked=false; if(e.time>=start) out.unlocks++; }
        }
        out.foregroundPackage=owner;
        out.foregroundActivity=activity;
        if(owner!=null) add(out,owner,since,end,start,end,zone);
        return out;
    }
    private static void add(Day day,String pkg,long from,long to,long start,long end,ZoneId zone) {
        long a=Math.max(from,start), b=Math.min(to,end);
        if(b<=a) return;
        if(day.spans!=null)day.spans.add(new Span(pkg,a,b));
        App app=day.apps.computeIfAbsent(pkg,App::new);
        long duration=b-a; app.millis+=duration; day.total+=duration;
        if(duration<120000L){app.fragmentMillis+=duration;day.fragmentMillis+=duration;}
        while(a<b) {
            ZonedDateTime z=Instant.ofEpochMilli(a).atZone(zone);
            long next=z.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli();
            long stop=Math.min(b,Math.max(a+1,next));
            app.hours[z.getHour()]+=stop-a; a=stop;
        }
    }
}
