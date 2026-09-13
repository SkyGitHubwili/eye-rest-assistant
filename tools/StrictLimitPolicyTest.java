package com.eyerest.app;

import com.eyerest.app.ledger.UsageEngine;
import java.time.*;
import java.util.*;
import static com.eyerest.app.StrictLimitPolicy.*;

public final class StrictLimitPolicyTest {
    static final String REDPKG="com.xingin.eva",BILIPKG="tv.danmaku.bili",DY="com.ss.android.ugc.aweme",G1="game.one",G2="game.two";
    static final Set<String> GAMES=new HashSet<>(Arrays.asList(G1,G2));
    static final long BASE=1_800_000_000_000L;
    static int checks;
    static void eq(long actual,long expected){checks++;if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
    static void reason(Decision d,Reason expected){checks++;if(d.reason!=expected)throw new AssertionError(d.reason+" != "+expected);}
    static Usage usage(UsageEngine.Span... spans){return summarize(Arrays.asList(spans),Collections.emptyList(),GAMES,new long[4],spans.length==0?BASE:spans[spans.length-1].to);}
    static UsageEngine.Span span(String pkg,long minute,long length){return new UsageEngine.Span(pkg,BASE+minute*MINUTE,BASE+(minute+length)*MINUTE);}
    public static void main(String[] args){
        // Individual limits and exact threshold (no minute rounding).
        for(int c=0;c<4;c++){
            Usage u=new Usage();u.used[c]=daily(c)-1;reason(decide(c,u,0,0),Reason.ALLOW);
            u.used[c]++;reason(decide(c,u,0,0),Reason.DAILY);
        }
        eq(category(REDPKG,GAMES),RED);eq(category("com.xingin.xhs",GAMES),RED);
        eq(category("com.ss.android.ugc.aweme.lite",GAMES),DOUYIN);
        eq(category("com.bilibili.app.in",GAMES),BILI);eq(category("com.tencent.mm",GAMES),NONE);
        // Games share one quota, including a newly recognised game.
        Usage games=usage(span(G1,0,40),span(G2,40,20));eq(games.used[GAME],60*MINUTE);
        reason(decide(GAME,games,0,0),Reason.DAILY);
        Set<String> added=new HashSet<>(GAMES);added.add("new.game");eq(category("new.game",added),GAME);
        Usage mixed=usage(span(REDPKG,0,50),span(DY,50,15),span(BILIPKG,65,95),span(G1,160,20));
        eq(mixed.total(),180*MINUTE);reason(decide(RED,mixed,0,0),Reason.TOTAL);reason(decide(BILI,mixed,0,0),Reason.TOTAL);reason(decide(GAME,mixed,0,0),Reason.TOTAL);
        // Attempting the first game after spending three hours is also blocked.
        Usage noGame=new Usage();noGame.used[RED]=60*MINUTE;noGame.used[DOUYIN]=15*MINUTE;noGame.used[BILI]=105*MINUTE;
        reason(decide(GAME,noGame,0,0),Reason.TOTAL);
        // A short switch or activity restart cannot reset fifteen minutes.
        Usage interrupted=usage(span(REDPKG,0,10),span("com.tencent.mm",10,1),span(REDPKG,11,5));
        eq(interrupted.continuous[RED],15*MINUTE);reason(decide(RED,interrupted,0,0),Reason.START_REST);
        Usage rested=usage(span(REDPKG,0,10),span(REDPKG,12,5));eq(rested.continuous[RED],5*MINUTE);
        reason(decide(RED,rested,0,0),Reason.ALLOW);
        // Countdown has priority over a reopened app, and cannot extend itself.
        reason(decide(BILI,new Usage(),0,1),Reason.REST);
        reason(decide(BILI,new Usage(),0,0),Reason.ALLOW);
        long wall=BASE+120_000,elapsed=200_000;
        eq(restRemaining(wall,elapsed,7,7,BASE+999_999,150_000),50_000); // wall clock jump
        eq(restRemaining(wall,elapsed,7,8,BASE+60_000,10_000),60_000); // reboot
        eq(restRemaining(wall,elapsed,7,7,BASE,200_000),0);
        // The actually blocked two minutes do not consume either daily or group quota.
        List<UsageEngine.Span> trace=Arrays.asList(span(REDPKG,0,18));
        List<Block> blocks=Arrays.asList(new Block(REDPKG,BASE+15*MINUTE,BASE+17*MINUTE));
        long[] resets={BASE+17*MINUTE,0,0,0};
        Usage after=summarize(trace,blocks,GAMES,resets,BASE+18*MINUTE);
        eq(after.used[RED],16*MINUTE);eq(after.continuous[RED],MINUTE);
        // Repeated/overlapping block windows never double-subtract.
        blocks=Arrays.asList(new Block(REDPKG,BASE+15*MINUTE,BASE+17*MINUTE),new Block(REDPKG,BASE+16*MINUTE,BASE+17*MINUTE));
        eq(summarize(trace,blocks,GAMES,resets,BASE+18*MINUTE).used[RED],16*MINUTE);
        // Morning starts at the first unlock at/after 06:00, not the alarm or a screen-on.
        long six=BASE+360*MINUTE;
        List<UsageEngine.Event> wakes=Arrays.asList(new UsageEngine.Event(six-1,18,null,null),new UsageEngine.Event(six,15,null,null),new UsageEngine.Event(six+30*MINUTE,18,null,null),new UsageEngine.Event(six+40*MINUTE,18,null,null));
        eq(firstWake(wakes,six,six+29*MINUTE,0),0);
        long wake=firstWake(wakes,six,six+40*MINUTE,0);eq(wake,six+30*MINUTE);
        eq(firstWake(Collections.emptyList(),six,six+40*MINUTE,wake),wake);
        for(int c=0;c<4;c++){reason(decide(c,new Usage(),MORNING,0),Reason.MORNING);reason(decide(c,new Usage(),1,0),Reason.MORNING);reason(decide(c,new Usage(),0,0),Reason.ALLOW);}
        reason(decide(NONE,new Usage(),MORNING,0),Reason.ALLOW);
        // The original event engine remains the source of foreground intervals;
        // long foreground runs and midnight clipping cannot reset the quota.
        ZoneId zone=ZoneId.of("Asia/Shanghai");long start=LocalDate.of(2026,9,13).atStartOfDay(zone).toInstant().toEpochMilli();
        List<UsageEngine.Event> ev=Arrays.asList(new UsageEngine.Event(start-10*MINUTE,1,REDPKG,"A"));
        UsageEngine.Day day=UsageEngine.compute(ev,start,start+30*MINUTE,zone,true);
        eq(day.total,30*MINUTE);eq(day.spans.size(),1);eq(usage(day.spans.get(0)).used[RED],30*MINUTE);
        checks++;if(!REDPKG.equals(day.foregroundPackage))throw new AssertionError("Long running foreground lost");
        System.out.println("PASS: "+checks+" fixed-limit checks (quotas, shared games, breaks, rest persistence, morning unlock and midnight)");
    }
}
