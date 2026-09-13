package com.eyerest.app.ledger;

import java.time.LocalDate;
import java.util.*;

public final class WeeklyReportTest {
    static final long H = WeeklyReport.HOUR;
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static List<UsageEngine.Day> week(LocalDate monday,long... hours){
        List<UsageEngine.Day> days=new ArrayList<>();
        for(int i=0;i<7;i++){
            UsageEngine.Day day=new UsageEngine.Day();day.date=monday.plusDays(i).toString();
            if(hours[i]>=0){day.total=hours[i]*H;day.complete=true;day.evidence=true;}
            days.add(day);
        }return days;
    }
    public static void main(String[] args){
        LocalDate monday=LocalDate.of(2026,9,7),saturday=monday.plusDays(5);
        check(WeeklyReport.monday(saturday).equals(monday),"Saturday belongs to Monday-Sunday week");
        check(WeeklyReport.monday(LocalDate.of(2027,1,1)).equals(LocalDate.of(2026,12,28)),"Calendar week spans year boundary");
        List<UsageEngine.Day> current=week(monday,12,11,13,12,12,6,-1);
        List<UsageEngine.Day> previous=week(monday.minusWeeks(1),10,10,10,10,10,10,10);
        WeeklyReport report=new WeeklyReport(monday,saturday,current,previous);
        check(report.current.total==66*H,"Weekly total");
        check(report.current.average==11*H && report.current.knownDays==6,"Future Sunday must not lower average");
        check(report.dayValue(6).equals("未到日期"),"Future day is not zero usage");
        check(report.comparison(true).contains("上升10%"),"Compare daily averages with previous calendar week");
        check(report.comparison(false).contains("下降6%"),"Total comparison uses displayed current total");

        WeeklyReport missing=new WeeklyReport(monday,saturday,week(monday,-1,11,13,12,12,6,-1),previous);
        check(missing.current.average==54*H/5,"Missing Monday not treated as zero");
        check(missing.dayValue(0).equals("无记录"),"Missing data displayed explicitly");
        check(missing.comparison(true).contains("暂不比较"),"No misleading percentage for incomplete week");
        check(new WeeklyReport(monday,saturday,current,week(monday.minusWeeks(1),-1,10,10,10,10,10,10)).comparison(true).contains("上周记录不足"),"Missing previous week gated");
        current.get(0).complete=false;
        check(new WeeklyReport(monday,saturday,current,previous).comparison(true).contains("暂不比较"),"Partial historical records gated");
        current.get(0).complete=true;

        WeeklyReport zero=new WeeklyReport(monday,monday.plusDays(6),week(monday,0,0,0,0,0,0,0),previous);
        check(zero.current.knownDays==7 && zero.current.average==0,"Recorded zero days count toward denominator");
        check(zero.dayValue(2).equals("0分钟"),"Known zero distinguished from no record");
        WeeklyReport empty=new WeeklyReport(monday,saturday,week(monday,-1,-1,-1,-1,-1,-1,-1),previous);
        check(empty.current.knownDays==0 && empty.current.average==0,"Empty week does not divide by zero");
        check(new WeeklyReport(monday,saturday,current,week(monday.minusWeeks(1),0,0,0,0,0,0,0)).comparison(true).contains("新增使用"),"Zero baseline avoids Infinity");

        UsageEngine.App a=new UsageEngine.App("duration.app");a.millis=H;a.opens=1;current.get(0).apps.put(a.pkg,a);
        UsageEngine.App b=new UsageEngine.App("opens.only");b.opens=100;current.get(0).apps.put(b.pkg,b);
        UsageEngine.App again=new UsageEngine.App("duration.app");again.millis=2*H;again.opens=2;current.get(1).apps.put(again.pkg,again);
        report=new WeeklyReport(monday,saturday,current,previous);
        check(report.current.byTime.size()==1 && report.current.byTime.get(0).millis==3*H,"Aggregate same app across days");
        check(report.current.byOpens.get(0).pkg.equals("opens.only"),"Launch ranking includes zero-duration apps");
        check(a.millis==H && a.opens==1,"Reporting does not mutate ledger");

        WeeklyReport.Scale scale=new WeeklyReport.Scale(current);
        check(scale.ticks.equals(Arrays.asList(0L,5*H,10*H,14*H)),"Reference ticks 0/5/10/14 hours");
        check(Math.abs(scale.y(10*H,42,238)-98)<.01,"Tick height proportional to true duration");
        current.get(0).total=18*H+1;
        scale=new WeeklyReport.Scale(current);
        check(scale.maximum==19*H && scale.y(current.get(0).total,42,238)>=42,"More than fourteen hours must not clip");
        check(new HashSet<>(scale.ticks).size()==scale.ticks.size(),"No duplicate ticks");
        for(int i=0;i<7;i++)check(WeeklyReport.Scale.hit(40+(i+.5f)*40,40,320,7)==i,"Tap column "+i);
        check(WeeklyReport.Scale.hit(20,40,320,7)==-1,"Y-axis is not Monday");
        check(WeeklyReport.Scale.hit(320,40,320,7)==-1,"Right margin is not Sunday");
        System.out.println("PASS: calendar weeks, missing/zero/future days, averages/comparisons, rankings, chart scale and hit bounds");
    }
}
