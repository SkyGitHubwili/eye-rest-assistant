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
        check(day.totalUsageMillis==90*MINUTE,"real event duration must fill packages omitted by UsageStats");
        check(day.totalLaunchCount==3,"foreground sessions must be counted");
        check(calculator.topApps(day,1).get(0).packageName.equals("a"),"ranking must sort by duration");

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
        System.out.println("HealthUsageTest: 13 checks passed");
    }

    private static HealthModels.UsageEventRecord event(String pkg,long minute,int type){
        return new HealthModels.UsageEventRecord(pkg,minute*MINUTE,type);
    }

    private static HealthModels.DayUsage dayWithApp(long start,String pkg,long usage){
        HealthModels.AppUsage app=new HealthModels.AppUsage(pkg,pkg,usage,1,true,true,true,start+usage);
        return new HealthModels.DayUsage(start,start+1440*MINUTE,usage,usage,0,0,0,1,true,true,usage>0,
            usage>0?Collections.singletonList(app):Collections.emptyList());
    }

    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
