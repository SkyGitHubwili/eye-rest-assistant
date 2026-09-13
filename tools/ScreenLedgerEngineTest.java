package com.eyerest.app.ledger;
import java.time.*;
import java.util.*;
public class ScreenLedgerEngineTest {
    static UsageEngine.Event e(long time,int kind,String pkg,String activity){return new UsageEngine.Event(time,kind,pkg,activity);}
    static UsageEngine.Day calc(long start,long end,UsageEngine.Event... events){return UsageEngine.compute(Arrays.asList(events),start,end,ZoneId.of("UTC"));}
    static void eq(long expected,long actual,String label){if(expected!=actual)throw new AssertionError(label+": "+actual+" != "+expected);}
    public static void main(String[] args){
        UsageEngine.Day d=calc(0,10000,e(0,1,"a","A"),e(4000,1,"b","B"),e(5000,2,"a","A"),e(9000,2,"b","B"));
        eq(9000,d.total,"switch has no double count");eq(4000,d.apps.get("a").millis,"old pause ignored");eq(5000,d.apps.get("b").millis,"new app duration");
        d=calc(0,20000,e(0,1,"a","A"),e(5000,16,null,null),e(12000,15,null,null),e(13000,18,null,null),e(14000,1,"b","B"));
        eq(11000,d.total,"screen off not counted");eq(1,d.unlocks,"unlock");
        d=calc(5000,15000,e(1000,1,"a","A"),e(10000,2,"a","A"));eq(5000,d.total,"midnight clipping");eq(0,d.apps.get("a").opens,"prior-day open excluded");
        d=calc(0,10000,e(0,1,"a","A"),e(3000,1,"a","B"),e(4000,2,"a","A"),e(8000,2,"a","B"));eq(8000,d.total,"same package activities");eq(1,d.apps.get("a").opens,"activity switch not launch");
        d=calc(0,10000,e(0,1,"a","A"),e(4000,2,"a","A"),e(4100,1,"a","B"));eq(9900,d.total,"activity transition gap");eq(1,d.apps.get("a").opens,"short transition merged");
        d=calc(0,10000,e(0,1,"a","A"),e(3000,17,null,null),e(4000,1,"lock","L"),e(7000,18,null,null),e(8000,1,"a","A"));eq(5000,d.total,"lock screen excluded");
        d=calc(0,10000,e(0,1,"a","A"),e(3000,26,null,null),e(7000,27,null,null),e(8000,1,"b","B"));eq(5000,d.total,"shutdown gap");
        d=calc(0,10000,e(0,1,"a","A"),e(7000,27,null,null),e(8000,1,"b","B"));eq(2000,d.total,"unknown shutdown discarded");
        d=calc(0,10000,e(0,1,"a","A"),e(10000,1,"b","B"));eq(10000,d.total,"end exclusive");
        d=calc(0,10000);eq(0,d.total,"no events");if(d.evidence)throw new AssertionError("missing != observed zero");
        d=calc(0,7200000,e(3500000,1,"a","A"),e(3700000,2,"a","A"));eq(100000,d.apps.get("a").hours[0],"hour 0 split");eq(100000,d.apps.get("a").hours[1],"hour 1 split");
        ZoneId ny=ZoneId.of("America/New_York");LocalDate fall=LocalDate.of(2025,11,2);long start=fall.atStartOfDay(ny).toInstant().toEpochMilli(),end=fall.plusDays(1).atStartOfDay(ny).toInstant().toEpochMilli();
        d=UsageEngine.compute(Arrays.asList(e(start,1,"a","A")),start,end,ny);eq(25*3600000L,d.total,"DST 25 hour day");eq(2*3600000L,d.apps.get("a").hours[1],"repeated hour");
        Random random=new Random(701);for(int trial=0;trial<1000;trial++){List<UsageEngine.Event> ev=new ArrayList<>();long t=0;for(int i=0;i<200;i++){t+=random.nextInt(1000);int[] types={1,2,15,16,17,18,26,27};int kind=types[random.nextInt(types.length)];String pkg="p"+random.nextInt(5);ev.add(e(t,kind,pkg,"A"));}d=UsageEngine.compute(ev,0,t+1000,ZoneId.of("UTC"));long total=0;for(UsageEngine.App a:d.apps.values()){total+=a.millis;long hourly=0;for(long h:a.hours)hourly+=h;eq(a.millis,hourly,"hour total invariant");}eq(total,d.total,"app sum invariant");if(d.total>t+1000||d.total<0)throw new AssertionError("duration outside day");}
        System.out.println("PASS: 12 edge-case scenarios + 1,000 randomized duration invariants");
    }
}
