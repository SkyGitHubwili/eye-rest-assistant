package com.eyerest.app;

import com.eyerest.app.ledger.UsageEngine;
import java.util.*;

/** Fixed personal policy. No editable quota, bypass flag or temporary unlock. */
public final class StrictLimitPolicy {
    public static final int NONE=-1, RED=0, DOUYIN=1, BILI=2, GAME=3;
    public static final long MINUTE=60_000L, SESSION=15*MINUTE, REST=2*MINUTE,
        ENTERTAINMENT=180*MINUTE, MORNING=45*MINUTE;
    public static long daily(int group){return new long[]{60*MINUTE,15*MINUTE,105*MINUTE,60*MINUTE}[group];}
    public static String name(int group){return new String[]{"小红书","抖音","哔哩哔哩","全部游戏"}[group];}
    public static boolean needsRest(int group){return group==RED || group==BILI;}
    public static long firstWake(List<UsageEngine.Event> events,long six,long now,long saved){
        if(saved>0)return saved;
        for(UsageEngine.Event e:events)if(e.type==18&&e.time>=six&&e.time<=now)return e.time;
        return 0;
    }
    public static long restRemaining(long wallEnd,long elapsedEnd,int savedBoot,int boot,long wallNow,long elapsedNow){
        if(wallEnd==0)return 0;
        return Math.max(0,savedBoot==boot?elapsedEnd-elapsedNow:Math.min(REST,wallEnd-wallNow));
    }
    public static int category(String pkg,Set<String> games){
        if(pkg==null)return NONE;
        if(pkg.equals("com.xingin.xhs") || pkg.equals("com.xingin.eva"))return RED;
        if(pkg.equals("com.ss.android.ugc.aweme") || pkg.equals("com.ss.android.ugc.aweme.lite"))return DOUYIN;
        if(pkg.equals("tv.danmaku.bili") || pkg.equals("tv.danmaku.bilibilihd") || pkg.equals("com.bilibili.app.in") || pkg.equals("com.bilibili.app.blue"))return BILI;
        return games.contains(pkg)?GAME:NONE;
    }
    public static final class Block {
        public final String pkg;public final long from,to;
        public Block(String pkg,long from,long to){this.pkg=pkg;this.from=from;this.to=to;}
    }
    public static final class Usage {
        public final long[] used=new long[4],continuous=new long[4],lastEnd=new long[4];
        public final Map<String,Long> apps=new HashMap<>();
        public long total(){long n=0;for(long v:used)n+=v;return n;}
    }
    public static Usage summarize(List<UsageEngine.Span> spans,List<Block> blocks,Set<String> games,long[] reset,long now){
        Usage out=new Usage();
        // Block windows are chronological and represent an actually displayed overlay.
        List<Block> sorted=new ArrayList<>(blocks);sorted.sort(Comparator.comparingLong(b->b.from));
        for(UsageEngine.Span span:spans){
            long cursor=span.from;
            for(Block b:sorted){
                if(!span.pkg.equals(b.pkg)||b.to<=cursor||b.from>=span.to)continue;
                add(out,span.pkg,cursor,Math.min(span.to,b.from),games,reset);
                cursor=Math.max(cursor,Math.min(span.to,b.to));
                if(cursor>=span.to)break;
            }
            add(out,span.pkg,cursor,span.to,games,reset);
        }
        for(int c=0;c<4;c++)if(now-out.lastEnd[c]>=REST)out.continuous[c]=0;
        return out;
    }
    private static void add(Usage out,String pkg,long from,long to,Set<String> games,long[] reset){
        if(to<=from)return;
        out.apps.put(pkg,out.apps.getOrDefault(pkg,0L)+to-from);
        int c=category(pkg,games);if(c==NONE)return;
        out.used[c]+=to-from;
        from=Math.max(from,reset[c]);if(to<=from)return;
        if(from-out.lastEnd[c]>=REST)out.continuous[c]=0;
        out.continuous[c]+=to-from;out.lastEnd[c]=to;
    }
    public enum Reason { ALLOW, MORNING, DAILY, TOTAL, REST, START_REST, UNAVAILABLE }
    public static final class Decision {
        public final Reason reason;public final long remaining;
        public Decision(Reason reason,long remaining){this.reason=reason;this.remaining=remaining;}
        public boolean blocked(){return reason!=Reason.ALLOW;}
    }
    public static Decision decide(int c,Usage usage,long morningRemaining,long restRemaining){
        if(c==NONE)return new Decision(Reason.ALLOW,0);
        if(morningRemaining>0)return new Decision(Reason.MORNING,morningRemaining);
        if(usage.used[c]>=daily(c))return new Decision(Reason.DAILY,0);
        if((c==GAME||usage.used[GAME]>0)&&usage.total()>=ENTERTAINMENT)return new Decision(Reason.TOTAL,0);
        if(needsRest(c)){
            if(restRemaining>0)return new Decision(Reason.REST,restRemaining);
            if(usage.continuous[c]>=SESSION)return new Decision(Reason.START_REST,REST);
        }
        return new Decision(Reason.ALLOW,0);
    }
}
