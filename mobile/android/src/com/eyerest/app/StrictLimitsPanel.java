package com.eyerest.app;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.*;
import java.time.LocalDate;
import java.util.*;

/** Read-only policy summary; no edit, delete, disable or temporary unlock controls. */
public final class StrictLimitsPanel extends LinearLayout {
    private final TextView status,morning,pool;
    private final TextView[] used=new TextView[4];
    public StrictLimitsPanel(Context context){
        super(context);setOrientation(VERTICAL);setPadding(dp(16),dp(18),dp(16),dp(18));
        GradientDrawable background=new GradientDrawable();background.setColor(0xffffffff);background.setCornerRadius(dp(20));setBackground(background);
        addView(label("固定娱乐规则 · 不可修改",18,true));
        status=label("正在检查限制服务",12,false);addView(status);
        for(int c=0;c<4;c++){
            TextView title=label(StrictLimitPolicy.name(c)+"  ·  每天 "+StrictLimitPolicy.daily(c)/60000+" 分钟",15,true);
            title.setPadding(0,dp(18),0,dp(4));addView(title);
            if(StrictLimitPolicy.needsRest(c))addView(label("连续使用 15 分钟，强制休息 2 分钟",12,false));
            used[c]=label("正在读取今日用量",12,false);addView(used[c]);
        }
        pool=label("玩游戏当天，四类娱乐合计最多 180 分钟",13,true);pool.setPadding(0,dp(20),0,dp(6));addView(pool);
        addView(label("早上 6 点后第一次解锁，禁用这四类应用 45 分钟",13,true));
        morning=label("",12,false);addView(morning);
        TextView note=label("今天已有的使用计入额度；每日零点重置。短暂切换不重置连续使用计时，离开该应用满 2 分钟才算休息。",12,false);
        note.setPadding(0,dp(14),0,dp(8));addView(note);
        Button scope=new Button(context);scope.setText("查看已识别的游戏");scope.setOnClickListener(v->showGames());addView(scope);
        Button usage=new Button(context);usage.setText("使用情况访问权限");usage.setOnClickListener(v->context.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));addView(usage);
        Button overlay=new Button(context);overlay.setText("悬浮窗权限");overlay.setOnClickListener(v->context.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+context.getPackageName()))));addView(overlay);
        addView(label("系统未标记的游戏可能漏识别；强行停止、撤销权限或卸载后，普通应用无法继续拦截。",11,false));
    }
    private TextView label(String text,int size,boolean bold){TextView t=new TextView(getContext());t.setText(text);t.setTextSize(size);t.setTextColor(bold?0xff18253f:0xff65756e);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void showGames(){
        GameRegistry registry=new GameRegistry(getContext());registry.scan();List<String> names=new ArrayList<>();
        for(String pkg:registry.packages())try{names.add(getContext().getPackageManager().getApplicationLabel(getContext().getPackageManager().getApplicationInfo(pkg,0)).toString());}catch(Exception ignored){}
        Collections.sort(names);new AlertDialog.Builder(getContext()).setTitle("游戏共用每天 60 分钟")
            .setMessage(names.isEmpty()?"暂未识别到已安装游戏":String.join("\n",names)).setPositiveButton("知道了",null).show();
    }
    private final Runnable refresh=new Runnable(){public void run(){
        SharedPreferences p=getContext().getSharedPreferences("strict_limits_v1",0);long now=System.currentTimeMillis(),updated=p.getLong("updated",0);
        boolean fresh=now-updated<5000&&LocalDate.now().toString().equals(p.getString("date",""));
        status.setText(fresh?p.getString("status",""):"服务未提供最新状态，请检查权限和后台运行");
        long total=0;
        for(int c=0;c<4;c++){long amount=p.getLong("used_"+c,0);total+=amount;
            used[c].setText(fresh?"今日已用 "+duration(amount)+" · 剩余 "+duration(Math.max(0,StrictLimitPolicy.daily(c)-amount)):"等待今日记录");}
        pool.setText("玩游戏当天，娱乐合计最多 180 分钟"+(fresh?"\n今日合计 "+duration(total):""));
        long remaining=Math.max(0,p.getLong("morning_remaining",0)-Math.max(0,now-updated));
        morning.setText(!fresh?"等待起床保护状态":remaining>0?"起床保护中，还需 "+duration(remaining):"当前不在起床保护时段");
        postDelayed(this,1000);
    }};
    private String duration(long ms){long s=(Math.max(0,ms)+999)/1000;return s/60+" 分 "+s%60+" 秒";}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();AppLimitService.start(getContext());post(refresh);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(refresh);super.onDetachedFromWindow();}
}
