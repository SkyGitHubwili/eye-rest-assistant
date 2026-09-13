package com.eyerest.app;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.util.Log;
import com.eyerest.app.ledger.UsageEngine;
import com.eyerest.app.ledger.UsageRepository;
import java.time.*;
import java.util.*;

/** Fixed personal rules. Worker reads events; main thread owns all windows. */
public final class AppLimitService extends Service {
    private static final String CHANNEL="health_app_limits";
    private final Handler main=new Handler(Looper.getMainLooper());
    private HandlerThread thread;private Handler worker;
    private WindowManager windows;private View overlay;private TextView message;
    private volatile String blockedPackage;private volatile boolean destroyed;
    private StrictLimitState state;private GameRegistry games;
    private final List<UsageEngine.Event> events=new ArrayList<>();
    private final Set<String> seen=new HashSet<>();
    private long cursor,fullReadAt,publishedAt;private String dayKey,lastForeground;
    public static void start(Context c){try{c.startForegroundService(new Intent(c,AppLimitService.class));}catch(RuntimeException e){Log.w("AppLimit","Cannot start limiter",e);}}
    public static void stop(Context c){start(c);}
    @Override public void onCreate(){
        super.onCreate();state=new StrictLimitState(this);games=new GameRegistry(this);
        windows=(WindowManager)getSystemService(WINDOW_SERVICE);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"应用使用限制",NotificationManager.IMPORTANCE_LOW));
        startForeground(31,notification());
        thread=new HandlerThread("strict-app-limits");thread.start();worker=new Handler(thread.getLooper());
        worker.post(()->{games.scan();worker.post(checker);});
    }
    @Override public int onStartCommand(Intent i,int flags,int id){worker.post(()->games.scan());return START_STICKY;}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,31,new Intent(this,AppLimitActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("固定应用限制已开启")
            .setContentText("每日额度、连续使用休息及起床保护").setContentIntent(open).setOngoing(true).build();
    }
    private final Runnable checker=new Runnable(){public void run(){
        if(destroyed)return;
        try{check();}catch(Exception e){
            Log.w("AppLimit","Usage temporarily unavailable",e);
            state.publish(LocalDate.now().toString(),null,0,"使用记录暂时不可用",System.currentTimeMillis());
            if(lastForeground!=null&&protectedGroup(lastForeground)!=StrictLimitPolicy.NONE)
                display(lastForeground,new StrictLimitPolicy.Decision(StrictLimitPolicy.Reason.UNAVAILABLE,0),0);
        }
        if(!destroyed)worker.postDelayed(this,250);
    }};
    private int protectedGroup(String pkg){games.isGame(pkg);return StrictLimitPolicy.category(pkg,games.packages());}
    private void readEvents(long start,long now,String date){
        UsageStatsManager manager=getSystemService(UsageStatsManager.class);
        boolean full=!date.equals(dayKey)||now<cursor||now-fullReadAt>=60_000;
        long from=full?start-2*86400000L:Math.max(start-2*86400000L,cursor-1000);
        UsageEvents stream=manager.queryEvents(from,now);if(stream==null)throw new IllegalStateException("No UsageEvents");
        if(full){events.clear();seen.clear();dayKey=date;fullReadAt=now;}
        UsageEvents.Event e=new UsageEvents.Event();
        while(stream.hasNextEvent()){
            stream.getNextEvent(e);String key=e.getTimeStamp()+"|"+e.getEventType()+"|"+e.getPackageName()+"|"+e.getClassName();
            if(seen.add(key))events.add(new UsageEngine.Event(e.getTimeStamp(),e.getEventType(),e.getPackageName(),e.getClassName()));
        }
        events.sort(Comparator.comparingLong(e2->e2.time));cursor=now;
    }
    private void check(){
        long now=System.currentTimeMillis();LocalDate date=LocalDate.now();ZoneId zone=ZoneId.systemDefault();
        if(!UsageRepository.allowed(this)||!Settings.canDrawOverlays(this)){
            state.publish(date.toString(),null,0,"请开启使用情况访问权限和悬浮窗权限",now);display(null,null,0);return;
        }
        long start=date.atStartOfDay(zone).toInstant().toEpochMilli();readEvents(start,now,date.toString());
        UsageEngine.Day trace=UsageEngine.compute(events,start,now,zone,true);
        String foreground=trace.foregroundPackage;
        if(blockedPackage!=null&&(foreground==null||(getPackageName().equals(foreground)&&trace.foregroundActivity==null)))foreground=blockedPackage;
        lastForeground=foreground;
        for(UsageEngine.Span span:trace.spans)games.isGame(span.pkg);
        StrictLimitState.RestState rest=state.restState(now);
        StrictLimitPolicy.Usage usage=StrictLimitPolicy.summarize(trace.spans,state.blocks(now),games.packages(),rest.resets,now);
        long morning=state.morningRemaining(events,date,zone,now);
        if(now-publishedAt>=1000){state.publish(date.toString(),usage,morning,"固定规则生效中",now);publishedAt=now;}
        PowerManager power=getSystemService(PowerManager.class);KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        if(!power.isInteractive()||keyguard.isKeyguardLocked()){display(null,null,0);return;}
        int group=protectedGroup(foreground);
        StrictLimitPolicy.Decision decision=StrictLimitPolicy.decide(group,usage,morning,group<0?0:rest.remaining[group]);
        if(decision.reason==StrictLimitPolicy.Reason.START_REST){state.beginRest(group,now);decision=new StrictLimitPolicy.Decision(StrictLimitPolicy.Reason.REST,state.restRemaining(group,now));}
        long customLimit=0;
        if(group==StrictLimitPolicy.NONE&&foreground!=null){
            for(AppLimit rule:AppLimitStore.get(this))if(rule.enabled&&rule.packageName.equals(foreground)&&usage.apps.getOrDefault(foreground,0L)>=rule.dailyLimitMillis){
                customLimit=rule.dailyLimitMillis;decision=new StrictLimitPolicy.Decision(StrictLimitPolicy.Reason.DAILY,0);break;
            }
        }
        display(decision.blocked()?foreground:null,decision,group<0?customLimit:StrictLimitPolicy.daily(group));
    }
    private void display(String pkg,StrictLimitPolicy.Decision decision,long limit){
        main.post(()->{if(destroyed)return;
            if(pkg==null||decision==null||!decision.blocked()){removeOverlay();return;}
            if(!pkg.equals(blockedPackage)){
                removeOverlay();LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
                root.setGravity(Gravity.CENTER);root.setPadding(dp(28),dp(32),dp(28),dp(32));root.setBackgroundColor(0xff12201b);
                message=new TextView(this);message.setTextColor(Color.WHITE);message.setTextSize(22);message.setGravity(Gravity.CENTER);
                root.addView(message,new LinearLayout.LayoutParams(-1,-2));
                Button home=new Button(this);home.setText("返回桌面");LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,dp(52));hp.topMargin=dp(32);root.addView(home,hp);
                home.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                root.setFocusableInTouchMode(true);root.setOnKeyListener((v,key,event)->key==KeyEvent.KEYCODE_BACK);
                WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
                lp.gravity=Gravity.TOP|Gravity.START;
                try{windows.addView(root,lp);root.requestFocus();overlay=root;blockedPackage=pkg;state.overlay(pkg,System.currentTimeMillis());}
                catch(RuntimeException e){message=null;state.publish(LocalDate.now().toString(),null,0,"拦截窗口未能显示，请检查悬浮窗权限",System.currentTimeMillis());Log.e("AppLimit","Overlay failed",e);return;}
            }
            String title;
            switch(decision.reason){
                case MORNING:title="起床后先远离娱乐信息\n\n还需等待 "+countdown(decision.remaining)+"\n\n起床后的 45 分钟内不可使用";break;
                case REST:case START_REST:title="已连续使用 15 分钟\n\n请休息 "+countdown(decision.remaining)+"\n\n倒计时结束后才可继续使用";break;
                case TOTAL:title="今日娱乐总时长已满 3 小时\n\n小红书、抖音、哔站及游戏\n今天均不可继续使用";break;
                case UNAVAILABLE:title="暂时无法核对使用额度\n\n请返回护眼睡眠助手检查权限";break;
                default:title="今日使用额度已用完\n\n每日上限 "+(limit/60_000)+" 分钟\n请明天再使用";
            }
            message.setText(title);state.checkpoint(System.currentTimeMillis());
        });
    }
    private static String countdown(long ms){long s=(Math.max(0,ms)+999)/1000;return String.format(Locale.CHINA,"%02d:%02d",s/60,s%60);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void removeOverlay(){if(overlay!=null){try{windows.removeView(overlay);}catch(RuntimeException ignored){}overlay=null;message=null;blockedPackage=null;state.overlay(null,System.currentTimeMillis());}}
    @Override public void onDestroy(){destroyed=true;if(worker!=null)worker.removeCallbacksAndMessages(null);removeOverlay();if(thread!=null)thread.quitSafely();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
