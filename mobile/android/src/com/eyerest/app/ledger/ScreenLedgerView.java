package com.eyerest.app.ledger;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class ScreenLedgerView extends LinearLayout {
    private final Activity activity;
    private static final int BLUE=0xff4169e1;
    private static final int[] COLORS={BLUE,0xff28b9a4,0xffffb355,0xff9775df,0xffed7794,0xff70a4c2};
    private int bg,card,ink,muted,line; private boolean dark,active,busy; private int page=0,generation=0;
    private LinearLayout root,body; private ScrollView scroll;
    private LocalDate selected=LocalDate.now(); private UsageEngine.Day data;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,String> names=new HashMap<>();
    private String pendingCsv;
    private int weekOffset=0;
    private String selectedWeekDay;
    private final Runnable tick=new Runnable(){public void run(){if(active){if(!busy && ((page==0 && selected.equals(LocalDate.now())) || (page==1 && weekOffset==0)))refresh(false);handler.postDelayed(this,30000);}}};

    public ScreenLedgerView(Activity activity) {
        super(activity); this.activity=activity; setOrientation(VERTICAL); palette(); build();
    }
    public void refreshData(){ setActive(true); refresh(true); }
    public boolean hasLoaded(){ return data!=null; }
    public void setActive(boolean value){
        active=value; handler.removeCallbacks(tick);
        if(value){ArchiveJob.schedule(activity);handler.postDelayed(tick,30000);}
        else {generation++;busy=false;}
    }
    public void destroy(){active=false;generation++;handler.removeCallbacksAndMessages(null);worker.shutdownNow();}
    private SharedPreferences getPreferences(int mode){return activity.getSharedPreferences("screenledger_ui",mode);}
    private void runOnUiThread(Runnable action){activity.runOnUiThread(action);}
    private boolean isDestroyed(){return activity.isDestroyed() || worker.isShutdown();}
    private void startActivity(Intent intent){activity.startActivity(intent);}
    private void startActivityForResult(Intent intent,int code){activity.startActivityForResult(intent,code);}
    private String getPackageName(){return activity.getPackageName();}
    private PackageManager getPackageManager(){return activity.getPackageManager();}
    private ContentResolver getContentResolver(){return activity.getContentResolver();}
    private void palette(){dark=getPreferences(0).getBoolean("dark",false); bg=dark?0xff101724:0xfff4f6fb;card=dark?0xff1a2536:Color.WHITE;ink=dark?0xffedf1fb:0xff18253f;muted=dark?0xffa4b0c6:0xff778399;line=dark?0xff2b3a50:0xffeaf0f8;}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(activity);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void gap(LinearLayout l,int height){l.addView(new View(activity),new LinearLayout.LayoutParams(1,dp(height)));}
    private TextView button(String label,Runnable run){TextView b=text(label,14,BLUE,true);b.setGravity(Gravity.CENTER);b.setPadding(dp(14),dp(13),dp(14),dp(13));b.setBackground(round(dark?0xff283959:0xffedf1ff,14));b.setOnClickListener(v->run.run());return b;}
    private void title(LinearLayout l,String s,String sub){l.addView(text(s,28,ink,true));gap(l,8);l.addView(text(sub,13,muted,false));}
    private LinearLayout box(){LinearLayout l=col();l.setPadding(dp(20),dp(20),dp(20),dp(20));l.setBackground(round(card,24));return l;}
    private void build(){
        removeAllViews();root=col();root.setBackgroundColor(page==1?WeeklyReportView.BACKGROUND:bg);addView(root,new LinearLayout.LayoutParams(-1,-1));
        scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        body=col();int inset=page==1?10:22;body.setPadding(dp(inset),dp(inset),dp(inset),dp(24));scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=row();nav.setPadding(dp(12),dp(8),dp(12),dp(8));nav.setBackgroundColor(card);
        String[] labels={"◷  使用概览","▥  周报","⚙  设置"};
        for(int i=0;i<3;i++){final int idx=i;TextView b=text(labels[i],13,page==i?BLUE:muted,page==i);b.setGravity(Gravity.CENTER);b.setPadding(0,dp(15),0,dp(15));if(page==i)b.setBackground(round(dark?0xff283959:0xffedf1ff,14));nav.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{page=idx;generation++;busy=false;build();refresh(true);});}
        root.addView(nav);if(page==2)settings();
    }
    private void refresh(boolean loading){
        if(page==2){settings();return;}
        if(!UsageRepository.allowed(activity)){generation++;busy=false;data=null;permission();return;}
        final int token=++generation;final int viewPage=page; final LocalDate date=selected;busy=true;
        final LocalDate today=LocalDate.now(),reportStart=WeeklyReport.monday(today).plusWeeks(weekOffset);
        if(loading){body.removeAllViews();title(body,viewPage==0?"使用概览":"时间周报","正在读取手机使用记录…");}
        worker.submit(()->{
            try(UsageRepository r=new UsageRepository(activity)){
                if(loading)r.archiveRecent();
                if(viewPage==0){UsageEngine.Day d=r.load(date);runOnUiThread(()->{if(token!=generation||isDestroyed())return;busy=false;data=d;overview();});}
                else{
                    java.util.List<UsageEngine.Day> days=new ArrayList<>(),previous=new ArrayList<>();
                    for(int i=0;i<7;i++){
                        LocalDate reportDate=reportStart.plusDays(i);
                        if(reportDate.isAfter(today)){UsageEngine.Day future=new UsageEngine.Day();future.date=reportDate.toString();days.add(future);}
                        else days.add(r.load(reportDate));
                        previous.add(r.load(reportStart.minusWeeks(1).plusDays(i)));
                    }
                    WeeklyReport report=new WeeklyReport(reportStart,today,days,previous);
                    runOnUiThread(()->{if(token!=generation||isDestroyed())return;busy=false;trend(report);});
                }
            }catch(Exception e){runOnUiThread(()->{if(token!=generation||isDestroyed())return;busy=false;data=null;body.removeAllViews();title(body,"暂时无法读取","请检查使用情况访问权限，然后重试。");gap(body,24);body.addView(button("检查权限",this::openPermission));gap(body,12);body.addView(button("重新读取",()->refresh(true)));});}
        });
    }
    private void permission(){body.removeAllViews();title(body,"时光账本","把花在手机上的时间，看得更清楚。");gap(body,32);LinearLayout b=box();TextView icon=text("◷",76,BLUE,true);icon.setGravity(Gravity.CENTER);b.addView(icon);gap(b,20);b.addView(text("先连接你的使用记录",22,ink,true));gap(b,14);TextView intro=text("开启「使用情况访问权限」，即可查看各个应用使用了多久，以及每天的使用总时长。",15,muted,false);intro.setLineSpacing(dp(5),1);b.addView(intro);gap(b,24);b.addView(button("开启使用情况访问权限",this::openPermission));gap(b,18);b.addView(text("跳转设置后，找到「护眼睡眠助手」并允许访问，再返回这里。",13,muted,false));body.addView(b);gap(body,24);body.addView(text("本地统计 · 无需登录 · 无广告\n不读取聊天内容，不申请联网权限",14,muted,false));}
    private void openPermission(){try{startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(Exception e){try{startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));}catch(Exception x){new AlertDialog.Builder(activity).setMessage("请在手机设置中搜索「使用情况访问权限」，找到护眼睡眠助手并开启。").setPositiveButton("知道了",null).show();}}}
    private void datePicker(){DatePickerDialog d=new DatePickerDialog(activity,(v,y,m,day)->{selected=LocalDate.of(y,m+1,day);refresh(true);},selected.getYear(),selected.getMonthValue()-1,selected.getDayOfMonth());d.getDatePicker().setMaxDate(System.currentTimeMillis());d.show();}
    private void overview(){
        int oldY=scroll.getScrollY();body.removeAllViews();
        title(body,"时间，花在哪里","时光账本  /  每一天都值得被看见");gap(body,24);
        LinearLayout date=row();date.addView(button("‹",()->{selected=selected.minusDays(1);refresh(true);}));
        TextView center=text((selected.equals(LocalDate.now())?"今天 · ":"")+selected.format(DateTimeFormatter.ofPattern("MM月dd日")),16,ink,true);center.setGravity(Gravity.CENTER);date.addView(center,new LinearLayout.LayoutParams(0,-2,1));center.setOnClickListener(v->datePicker());
        TextView next=button("›",()->{if(selected.isBefore(LocalDate.now())){selected=selected.plusDays(1);refresh(true);}});next.setEnabled(selected.isBefore(LocalDate.now()));next.setAlpha(next.isEnabled()?1:.3f);date.addView(next);body.addView(date);gap(body,12);body.addView(button("应用使用限制",()->startActivity(new Intent(activity,com.eyerest.app.AppLimitActivity.class))));gap(body,18);
        LinearLayout hero=box();hero.addView(text("手机使用总时长",14,muted,true));
        hero.addView(new Donut(data),new LinearLayout.LayoutParams(-1,dp(252)));
        gap(hero,dp(16));LinearLayout stats=row();stats.setGravity(Gravity.CENTER);int opens=0;for(UsageEngine.App a:data.apps.values())opens+=a.opens;
        long fragmentPct=data.total==0?0:Math.round(data.fragmentMillis*100.0/data.total);stat(stats,"时间碎片",fragmentPct+"%");stat(stats,"使用时长",format(data.total));stat(stats,"启动次数",opens+"");stat(stats,"解锁次数",data.unlocks+"");hero.addView(stats);body.addView(hero);gap(body,18);
        LinearLayout list=box();list.setPadding(dp(16),dp(4),dp(16),dp(4));java.util.List<UsageEngine.App> apps=data.sorted();
        if(apps.isEmpty()){TextView empty=text("暂无应用记录\n使用手机一段时间后，点击刷新。",15,muted,false);empty.setPadding(0,dp(24),0,dp(24));list.addView(empty);}
        for(int i=0;i<apps.size();i++){UsageEngine.App a=apps.get(i);appRow(list,a,i);if(i<apps.size()-1){View divider=new View(activity);divider.setBackgroundColor(line);list.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}}
        body.addView(list);gap(body,20);body.addView(button("刷新记录",()->refresh(true)));scroll.post(()->scroll.scrollTo(0,oldY));
    }
    private void stat(LinearLayout r,String label,String value){LinearLayout l=col();l.setGravity(Gravity.CENTER);TextView v=text(value,11,ink,true);v.setGravity(Gravity.CENTER);v.setSingleLine(true);v.setTextSize(11);l.addView(v,new LinearLayout.LayoutParams(-1,dp(25)));TextView t=text(label,11,muted,false);t.setGravity(Gravity.CENTER);t.setSingleLine(true);l.addView(t,new LinearLayout.LayoutParams(-1,dp(22)));r.addView(l,new LinearLayout.LayoutParams(0,dp(50),1));}
    private String name(String pkg){if(names.containsKey(pkg))return names.get(pkg);String n=pkg;try{ApplicationInfo a=getPackageManager().getApplicationInfo(pkg,0);n=getPackageManager().getApplicationLabel(a).toString();}catch(Exception ignored){}names.put(pkg,n);return n;}
    private void appRow(LinearLayout list,UsageEngine.App a,int index){
        LinearLayout r=row();r.setPadding(0,dp(15),0,dp(15));ImageView icon=new ImageView(activity);icon.setContentDescription(name(a.pkg)+"图标");
        icon.setImageDrawable(AppIcons.load(activity,a.pkg));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);icon.setPadding(dp(2),dp(2),dp(2),dp(2));
        r.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(28)));LinearLayout info=col();info.setPadding(dp(12),0,dp(8),0);TextView label=text(name(a.pkg),14,ink,false);label.setMaxLines(1);label.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(label);
        gap(info,3);TextView share=text("时长占比 "+WeeklyReport.percentage(a.millis,data.total),11,muted,false);info.addView(share,new LinearLayout.LayoutParams(-1,-2));
        r.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout value=col();TextView duration=text(format(a.millis),15,ink,true);duration.setGravity(Gravity.RIGHT);value.addView(duration);TextView opens=text(a.opens+" 次",12,muted,false);opens.setGravity(Gravity.RIGHT);value.addView(opens);r.addView(value,new LinearLayout.LayoutParams(dp(92),-2));r.setOnClickListener(v->detail(a));list.addView(r);
    }
    private void detail(UsageEngine.App a){LinearLayout b=box();b.addView(text(format(a.millis)+"  ·  "+a.opens+" 次进入前台",18,ink,true));gap(b,10);b.addView(text(a.pkg,12,muted,false));gap(b,22);b.addView(text("各时段使用分布",14,muted,true));b.addView(new HourChart(a.hours),new LinearLayout.LayoutParams(-1,dp(150)));gap(b,12);b.addView(text("进入前台包含从其他应用返回，不等同于冷启动次数。",12,muted,false));new AlertDialog.Builder(activity).setTitle(name(a.pkg)).setView(b).setPositiveButton("完成",null).show();}
    private void trend(WeeklyReport report){
        int oldY=scroll.getScrollY();
        body.removeAllViews();
        body.addView(new WeeklyReportView(activity,report,dark,selectedWeekDay,new WeeklyReportView.Actions(){
            public void changeWeek(int direction){
                weekOffset=Math.min(0,weekOffset+direction);selectedWeekDay=null;
                scroll.scrollTo(0,0);refresh(true);
            }
            public void selectDay(String date){selectedWeekDay=date;}
        }),new LinearLayout.LayoutParams(-1,-2));
        scroll.post(()->scroll.scrollTo(0,oldY));
    }
    private void settings(){body.removeAllViews();title(body,"让时间留在本机","简单、透明，也照顾你的隐私");gap(body,24);LinearLayout b=box();Switch toggle=new Switch(activity);toggle.setText("深色模式");toggle.setTextColor(ink);toggle.setChecked(dark);toggle.setPadding(0,dp(10),0,dp(10));toggle.setOnCheckedChangeListener((v,on)->{getPreferences(0).edit().putBoolean("dark",on).apply();palette();build();});b.addView(toggle);gap(b,18);b.addView(button("使用情况访问权限",this::openPermission));gap(b,12);b.addView(button("应用设置 / 电池管理",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));body.addView(b);gap(body,24);LinearLayout about=box();about.addView(text("统计说明",19,ink,true));gap(about,14);TextView desc=text("• 使用总时长是各应用前台时长之和，包含桌面及本应用；不含熄屏、锁屏和后台播放。\n\n• 分屏按最近进入前台的应用归属，不双重计时；系统漏报事件会影响结果，可能与系统自带统计不同。\n\n• 「进入前台」是切换次数，同一应用内短暂切页会合并。解锁次数由系统事件提供。\n\n• 每 6 小时尝试保存最近记录，系统可能延迟任务。历史保存在本机，首次安装前较久的数据无法补回。\n\n• 切换手机时区后，按新时区重新计算最近日期；旧时区存档保留且不混入。\n\n• 无账号、无广告、无联网权限，不读取聊天或通知内容。卸载或清除数据会删除本机历史。",14,muted,false);desc.setLineSpacing(dp(4),1);about.addView(desc);body.addView(about);gap(body,24);body.addView(text("健康使用 · ScreenLedger 1.0.0 计时核心",12,muted,false));}
    private String csvCell(String s){if(!s.isEmpty() && "=+-@\t\r\n".indexOf(s.charAt(0))>=0)s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    private void export(){if(data==null||data.sorted().isEmpty()){Toast.makeText(activity,"当前日期没有可导出的记录",Toast.LENGTH_SHORT).show();return;}StringBuilder s=new StringBuilder("\uFEFF日期,时区,应用,包名,使用秒数,进入前台次数,数据完整性\r\n");for(UsageEngine.App a:data.sorted())s.append(data.date).append(',').append(csvCell(ZoneId.systemDefault().getId())).append(',').append(csvCell(name(a.pkg))).append(',').append(csvCell(a.pkg)).append(',').append(a.millis/1000).append(',').append(a.opens).append(',').append(data.complete?"事件覆盖当天":"可能不完整").append("\r\n");pendingCsv=s.toString();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"时光账本-"+data.date+".csv");try{startActivityForResult(intent,41);}catch(Exception e){Toast.makeText(activity,"未找到文件保存程序",Toast.LENGTH_LONG).show();}}
    public void onActivityResult(int request,int result,Intent intent){if(request==41&&result==Activity.RESULT_OK&&intent!=null&&intent.getData()!=null){if(pendingCsv==null){Toast.makeText(activity,"导出已中断，请重新导出",Toast.LENGTH_LONG).show();return;}try(OutputStream out=getContentResolver().openOutputStream(intent.getData())){if(out==null)throw new IOException();out.write(pendingCsv.getBytes(StandardCharsets.UTF_8));Toast.makeText(activity,"已保存 CSV",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(activity,"保存失败，请重新选择位置",Toast.LENGTH_LONG).show();}pendingCsv=null;}}
    public static String format(long ms){long m=ms/60000;if(ms>0&&m==0)return "不足1分钟";return m>=60?m/60+"小时"+(m%60==0?"":m%60+"分"):m+"分钟";}
    private class Donut extends View {
        final UsageEngine.Day day;final Paint p=new Paint(3);
        Donut(UsageEngine.Day d){super(activity);day=d;setContentDescription("手机使用总时长："+format(d.total)+"，应用使用占比圆环");}
        protected void onDraw(Canvas c){
            super.onDraw(c);
            float x=getWidth()/2f,y=getHeight()/2f;
            int iconSize=dp(18),orbitGap=dp(23),edge=dp(3);
            float r=RingIconLayout.radius(getWidth(),getHeight(),Math.min(getWidth(),getHeight())*.40f,orbitGap,iconSize,edge);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(17));p.setStrokeCap(Paint.Cap.ROUND);p.setColor(line);c.drawCircle(x,y,r,p);RectF oval=new RectF(x-r,y-r,x+r,y+r);float angle=-90;java.util.List<UsageEngine.App> apps=day.sorted();int shown=Math.min(6,apps.size());float[] starts=new float[shown],sweeps=new float[shown];for(int i=0;i<shown;i++){long amount=apps.get(i).millis;if(i==5)for(int j=6;j<apps.size();j++)amount+=apps.get(j).millis;float sweep=day.total==0?0:360f*amount/day.total;starts[i]=angle;sweeps[i]=sweep;float gap=Math.min(5,sweep*.3f);p.setColor(COLORS[i]);p.setStrokeCap(Paint.Cap.BUTT);c.drawArc(oval,angle+gap/2,sweep-gap,false,p);angle+=sweep;}
            // Keep the ring uncluttered: show only each app icon around its segment.
            for(int i=0;i<shown;i++){
                double mid=Math.toRadians(starts[i]+sweeps[i]/2f);
                float ix=RingIconLayout.clampCenter(x+(float)Math.cos(mid)*(r+orbitGap),getWidth(),iconSize,edge);
                float iy=RingIconLayout.clampCenter(y+(float)Math.sin(mid)*(r+orbitGap),getHeight(),iconSize,edge);
                Drawable d=AppIcons.load(activity,apps.get(i).pkg);
                int left=Math.round(ix-iconSize/2f),top=Math.round(iy-iconSize/2f);
                d.setBounds(left,top,left+iconSize,top+iconSize);d.draw(c);
            }
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setColor(muted);p.setTextSize(dp(12));c.drawText("累计前台使用",x,y-dp(23),p);p.setColor(ink);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));String value=day.total==0&&!day.evidence?"暂无记录":format(day.total);p.setTextSize(dp(27));while(p.measureText(value)>r*1.65f)p.setTextSize(p.getTextSize()-1);c.drawText(value,x,y+dp(12),p);p.setTypeface(Typeface.DEFAULT);p.setTextSize(dp(11));p.setColor(muted);c.drawText(day.total>0?apps.size()+" 个应用 · 点击下方查看详情":"你的时间，从这里开始",x,y+dp(36),p);
        }
    }
    private class HourChart extends View {
        final long[] hours;final Paint p=new Paint(3);HourChart(long[] h){super(activity);hours=h;setContentDescription("24小时应用使用分布柱状图");}
        protected void onDraw(Canvas c){long max=60000;for(long h:hours)max=Math.max(max,h);float bottom=getHeight()-dp(28),width=getWidth()/24f;p.setColor(BLUE);for(int i=0;i<24;i++){float h=(bottom-dp(12))*hours[i]/max;c.drawRoundRect(i*width+dp(2),bottom-h,(i+1)*width-dp(2),bottom,dp(2),dp(2),p);}p.setColor(muted);p.setTextSize(dp(10));for(int i=0;i<24;i+=6)c.drawText(String.format(Locale.ROOT,"%02d:00",i),i*width,getHeight()-dp(6),p);}
    }
}
