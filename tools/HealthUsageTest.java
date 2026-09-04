import com.eyerest.app.AppLimit;
import com.eyerest.app.HealthModels;
import com.eyerest.app.HealthScoreCalculator;
import com.eyerest.app.UsageStatsCalculator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HealthUsageTest {
    private static final long MINUTE=60_000L;

    public static void main(String[] args){
        UsageStatsCalculator calculator=new UsageStatsCalculator();
        List<HealthModels.UsageEventRecord> events=Arrays.asList(
            event("a",0,1),event("a",20,2),
            event("b",20,1),event("b",50,2),
            event("",55,16),
            event("c",70,1),event("c",100,2));
        HealthModels.ContinuousUsage continuous=calculator.calculateContinuousUsage(events,0,120*MINUTE);
        check(continuous.available,"continuous data should be available");
        check(continuous.longestMillis==50*MINUTE,"app switches should remain one continuous segment");
        check(continuous.currentMillis==0,"closed interval must not be current use");

        List<HealthModels.UsageEventRecord> open=Arrays.asList(event("a",10,1),event("a",20,2),event("b",20,1));
        continuous=calculator.calculateContinuousUsage(open,0,80*MINUTE);
        check(continuous.currentMillis==70*MINUTE,"open foreground interval must become current use");
        check(continuous.currentStartMillis==10*MINUTE,"current segment start must be retained");

        List<HealthModels.AppUsageStatRecord> stats=Arrays.asList(
            new HealthModels.AppUsageStatRecord("a",40*MINUTE,50*MINUTE),
            new HealthModels.AppUsageStatRecord("b",20*MINUTE,30*MINUTE));
        Map<String,HealthModels.AppMetadata> metadata=new HashMap<>();
        metadata.put("a",new HealthModels.AppMetadata("a","A",true,true));
        metadata.put("b",new HealthModels.AppMetadata("b","B",true,true));
        HealthModels.DayUsage day=calculator.calculateDay(0,120*MINUTE,stats,events,metadata,true,true);
        check(day.totalUsageMillis==60*MINUTE,"App duration must use UsageStats records only");
        check(day.totalLaunchCount==3,"foreground sessions must be counted");
        check(calculator.topApps(day,1).get(0).packageName.equals("a"),"ranking must sort by duration");

        // Today's App duration must remain the UsageStats value even when
        // foreground events disagree or contain an open interval.
        List<HealthModels.UsageEventRecord> todayEvents=Arrays.asList(
            event("a",-5,1),event("a",2,2),
            event("b",2,1),event("b",5,2),
            event("c",6,1));
        List<HealthModels.AppUsageStatRecord> staleStats=Arrays.asList(
            new HealthModels.AppUsageStatRecord("a",100*MINUTE,100*MINUTE),
            new HealthModels.AppUsageStatRecord("b",100*MINUTE,100*MINUTE),
            new HealthModels.AppUsageStatRecord("c",100*MINUTE,100*MINUTE));
        Map<String,HealthModels.AppMetadata> todayMetadata=new HashMap<>();
        todayMetadata.put("a",new HealthModels.AppMetadata("a","A",true,true));
        todayMetadata.put("b",new HealthModels.AppMetadata("b","B",true,true));
        todayMetadata.put("c",new HealthModels.AppMetadata("c","C",true,true));
        HealthModels.DayUsage today=calculator.calculateDay(0,10*MINUTE,staleStats,todayEvents,
            todayMetadata,true,true,true);
        check(find(today,"a")==10*MINUTE,"today App duration must use UsageStats for a");
        check(find(today,"b")==10*MINUTE,"today App duration must use UsageStats for b");
        check(find(today,"c")==10*MINUTE,"today App duration must use UsageStats for c");
        check(today.totalUsageMillis==30*MINUTE,
            "App total must be the sum of UsageStats durations, independent of screen time");

        List<HealthModels.UsageEventRecord> duplicate=Arrays.asList(
            event("d",0,1),event("d",0,1),event("d",3,2),event("d",3,2));
        List<HealthModels.UsageInterval> duplicateIntervals=calculator.buildIntervals(duplicate,0,10*MINUTE);
        check(duplicateIntervals.size()==1&&duplicateIntervals.get(0).getDurationMillis()==3*MINUTE,
            "duplicate foreground/background events must not overlap");

        List<HealthModels.DayUsage> week=new ArrayList<>();
        for(int i=0;i<7;i++)week.add(dayWithApp(i*1440*MINUTE,"a",i*10*MINUTE));
        HealthModels.AppDetail detail=calculator.createAppDetail("a",week.get(6),week.get(5),week);
        check(detail.averageMillis==30*MINUTE,"7-day average must include zero-use days");
        check(detail.peakMillis==60*MINUTE,"peak day must use the largest real value");

        HealthModels.DayUsage healthy=new HealthModels.DayUsage(0,120*MINUTE,90*MINUTE,50*MINUTE,0,0,0,3,true,true,true,day.apps);
        HealthModels.HealthScore score=new HealthScoreCalculator().calculate(healthy,120*MINUTE,60*MINUTE);
        check(score.score==100,"within goal and threshold should keep full score");
        HealthModels.DayUsage over=new HealthModels.DayUsage(0,300*MINUTE,240*MINUTE,120*MINUTE,0,0,60*MINUTE,4,true,true,true,Collections.emptyList());
        score=new HealthScoreCalculator().calculate(over,120*MINUTE,60*MINUTE);
        check(score.score<100&&score.goalPenalty>0&&score.continuousPenalty>0&&score.nightPenalty>0,
            "transparent score penalties must react to all three metrics");

        AppLimit limit=new AppLimit("pkg",30*MINUTE,true,5,false);
        check(limit.withTemporaryUnlock(true).temporaryUnlock,"V2 temporary unlock contract must be preserved");
        System.out.println("HealthUsageTest: 18 checks passed");
    }

    private static HealthModels.UsageEventRecord event(String pkg,long minute,int type){
        return new HealthModels.UsageEventRecord(pkg,minute*MINUTE,type);
    }

    private static HealthModels.DayUsage dayWithApp(long start,String pkg,long usage){
        HealthModels.AppUsage app=new HealthModels.AppUsage(pkg,pkg,usage,1,true,true,true,start+usage);
        return new HealthModels.DayUsage(start,start+1440*MINUTE,usage,usage,0,0,0,1,true,true,usage>0,
            usage>0?Collections.singletonList(app):Collections.emptyList());
    }

    private static long find(HealthModels.DayUsage day,String pkg){
        for(HealthModels.AppUsage app:day.apps)if(pkg.equals(app.packageName))return app.usageMillis;
        return 0L;
    }

    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
