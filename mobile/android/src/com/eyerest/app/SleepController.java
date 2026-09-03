package com.eyerest.app;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ImageView;

import java.io.File;

import java.util.Calendar;

/** 睡眠状态机及强制 Overlay。与护眼休息层使用相同的窗口层级。 */
public final class SleepController {
    private static final int MAX_EARLY_ENDS_PER_MONTH = 10;
    public interface Host { void updateSleepNotification(String state,String text); void disableSleepAssistant(); }

    private final Context context;
    private final SharedPreferences prefs;
    private final Host host;
    private WindowManager windows;
    private View overlay;
    private TextView title,countdown,tip;
    private Button manualUnlock;
    private String state="NORMAL",lastNoticeKey="";

    public SleepController(Context context,SharedPreferences prefs,Host host){
        this.context=context;this.prefs=prefs;this.host=host;
    }

    public void evaluate(){
        Calendar now=Calendar.getInstance();
        if(!SleepSettings.valid(prefs)||!SleepSettings.isEnabledForPlan(now,prefs)){
            transition("NORMAL",now);return;
        }
        if(SleepSettings.hasBypassForCurrentWindow(now,prefs)){
            String reason=prefs.getString("sleep_bypass_reason",SleepSettings.REASON_NONE);
            transition(SleepSettings.REASON_CALL.equals(reason)?"TODAY_BYPASS_CALL":
                SleepSettings.REASON_MANUAL.equals(reason)?"TODAY_BYPASS_MANUAL":"TODAY_BYPASS_REBOOT",now);return;
        }
        if(SleepSettings.isInSleepWindow(now,prefs)){transition("SLEEP_LOCKED",now);return;}
        if(SleepSettings.isInWarningWindow(now,prefs)){transition("PRE_SLEEP_WARNING",now);return;}
        transition("NORMAL",now);
    }

    public void onScreenOff(){removeOverlay();}
    public void onScreenAvailable(){evaluate();}

    public void onIncomingCall(){
        Calendar now=Calendar.getInstance();
        if("SLEEP_LOCKED".equals(state)||"PRE_SLEEP_WARNING".equals(state)||SleepSettings.isInSleepWindow(now,prefs)){
            SleepSettings.setBypass(prefs,now,SleepSettings.REASON_CALL);
            transition("TODAY_BYPASS_CALL",now);
        }
    }

    private void requestManualUnlock(){
        int remaining=earlyEndRemaining();
        if(remaining<=0)return;
        AlertDialog dialog=new AlertDialog.Builder(context)
            .setTitle("确定解除睡眠？")
            .setMessage("解除后将自动关闭睡眠助手，本月还剩 "+remaining+" 次紧急解除机会。")
            .setNegativeButton("取消",null)
            .setPositiveButton("确定解除",null)
            .create();
        // 对话框由前台服务发起，必须使用悬浮窗窗口类型，否则部分厂商系统会静默拒绝显示。
        if(Build.VERSION.SDK_INT>=26&&dialog.getWindow()!=null)
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.setOnShowListener(ignored -> {
            Button confirm=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if(confirm!=null)confirm.setOnClickListener(v->{
                if(!consumeEarlyEnd())return;
                Calendar now=Calendar.getInstance();
                SleepSettings.setBypass(prefs,now,SleepSettings.REASON_MANUAL);
                // 使用 commit 确保服务停止、用户重新打开应用前，关闭状态和次数已经落盘。
                prefs.edit().putString("sleep_mode",SleepSettings.MODE_OFF)
                    .putBoolean("sleep_lock_active",false).putString("sleep_state","NORMAL")
                    .putBoolean("sleep_manual_closed",true).commit();
                dialog.dismiss();
                removeOverlay();
                host.updateSleepNotification("TODAY_BYPASS_MANUAL","睡眠助手已关闭 · 本次紧急解除");
                host.disableSleepAssistant();
            });
        });
        try{dialog.show();}catch(Exception ignored){
            // 无法显示系统对话框时保持睡眠锁，不得直接解除或消耗次数。
        }
    }

    public void stop(){removeOverlay();prefs.edit().putBoolean("sleep_lock_active",false).apply();}
    public String getState(){return state;}

    private void transition(String next,Calendar now){
        state=next;
        prefs.edit().putString("sleep_state",state).putBoolean("sleep_lock_active","SLEEP_LOCKED".equals(state)).apply();
        if("PRE_SLEEP_WARNING".equals(state)){
            long left=SleepSettings.nextStart(now,prefs).getTimeInMillis()-now.getTimeInMillis();
            // 睡前提醒阶段必须允许用户设置闹钟、保存工作和关闭后台软件，不显示拦截触摸的全屏层。
            if(isScreenUsable())showOrUpdateWarning(left);else removeOverlay();
            notifyAtMostEachMinute("warning",left,"即将进入睡眠 · "+SleepSettings.formatDuration(left));
        }else if("SLEEP_LOCKED".equals(state)){
            long left=SleepSettings.wakeForCurrentWindow(now,prefs).getTimeInMillis()-now.getTimeInMillis();
            if(isScreenUsable())showOrUpdateLock(left);else removeOverlay();
            notifyAtMostEachMinute("locked",left,"睡眠模式进行中 · 距离起床 "+SleepSettings.formatDuration(left));
        }else{
            removeOverlay();
            if("TODAY_BYPASS_CALL".equals(state))notifyOnce("call","今日睡眠已解除 · 检测到来电");
            else if("TODAY_BYPASS_REBOOT".equals(state))notifyOnce("reboot","今日睡眠已解除 · 设备刚刚重启");
            else {
                long left=SleepSettings.nextStart(now,prefs).getTimeInMillis()-now.getTimeInMillis();
                notifyAtMostEachMinute("normal",left,"距离睡眠 "+SleepSettings.formatDuration(left));
            }
        }
    }

    private void showOrUpdateWarning(long left){
        if(overlay==null||!"warning".equals(overlay.getTag()))createOverlay(true);
        title.setText("即将进入睡眠时间");
        countdown.setText(SleepSettings.formatDuration(left));
        tip.setText("准备中 · 可继续操作手机");
        // Keep the compact card background stable while the countdown updates.
    }

    private void showOrUpdateLock(long left){
        if(overlay==null||!"locked".equals(overlay.getTag()))createOverlay(false);
        title.setText("到睡眠时间了");
        countdown.setText(SleepSettings.formatDuration(left));
        tip.setText("手机已进入睡眠模式\n通知和来电仍然可用");
    }

    private void createOverlay(boolean warning){
        removeOverlay();
        if(!Settings.canDrawOverlays(context))return;
        windows=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        FrameLayout root=new FrameLayout(context);root.setTag(warning?"warning":"locked");
        root.setClipToOutline(true);
        int opacity=Math.max(0,Math.min(100,prefs.getInt("sleep_warning_alpha",85)));
        if(warning){
            GradientDrawable warningBg=new GradientDrawable();
            warningBg.setColor(Color.argb(opacity*255/100,72,7,13));warningBg.setCornerRadius(dp(18));
            root.setBackground(warningBg);
        }else root.setBackgroundColor(Color.rgb(10,12,18));
        root.setClickable(!warning);root.setFocusable(false);
        // 根层只负责挡住普通应用；返回 false 让锁层内的“紧急解除”按钮正常收到触摸事件。
        root.setOnTouchListener((v,e)->false);

        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);content.setPadding(dp(28),warning?dp(20):dp(48),dp(28),warning?dp(20):dp(48));
        if(warning){
            File imageFile=new File(context.getFilesDir(),"sleep_warning_image");
            if(imageFile.exists()){
                ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                // The warning overlay may be created during app cold-start.
                // Avoid decoding a full-resolution camera image on the main
                // service thread; the preview only needs a 240dp-wide bitmap.
                image.setImageBitmap(decodePreview(imageFile,dp(480),dp(440)));
                image.setAlpha(Math.max(0f,Math.min(1f,prefs.getInt("sleep_warning_alpha",85)/100f)));
                root.addView(image,new FrameLayout.LayoutParams(-1,dp(220)));
                View tint=new View(context);tint.setBackgroundColor(Color.argb(opacity*120/100,30,8,18));
                root.addView(tint,new FrameLayout.LayoutParams(-1,dp(220)));
            }
        }
        title=label("",warning?22:28,warning?Color.rgb(255,145,145):Color.rgb(232,237,246),true);
        title.setGravity(Gravity.CENTER);content.addView(title,new LinearLayout.LayoutParams(-1,-2));
        countdown=label("",warning?44:54,warning?Color.rgb(255,105,115):Color.WHITE,true);
        countdown.setGravity(Gravity.CENTER);countdown.setPadding(0,dp(26),0,dp(24));
        content.addView(countdown,new LinearLayout.LayoutParams(-1,-2));
        tip=label("",warning?14:16,warning?Color.rgb(255,215,215):Color.rgb(166,177,196),false);
        tip.setGravity(Gravity.CENTER);tip.setLineSpacing(dp(5),1f);content.addView(tip,new LinearLayout.LayoutParams(-1,-2));
        if(!warning){
            manualUnlock=new Button(context);
            int remaining=earlyEndRemaining();
            manualUnlock.setText(remaining>0?"紧急解除睡眠（本月剩余 "+remaining+" 次）":"本月紧急解除次数已用完");
            manualUnlock.setTextColor(Color.WHITE);manualUnlock.setTextSize(13);manualUnlock.setAllCaps(false);
            manualUnlock.setEnabled(remaining>0);manualUnlock.setAlpha(remaining>0?1f:.55f);
            GradientDrawable buttonBg=new GradientDrawable();buttonBg.setColor(Color.argb(55,255,255,255));buttonBg.setCornerRadius(dp(14));buttonBg.setStroke(dp(1),Color.argb(120,255,255,255));manualUnlock.setBackground(buttonBg);
            manualUnlock.setOnClickListener(v->requestManualUnlock());
            LinearLayout.LayoutParams unlockParams=new LinearLayout.LayoutParams(dp(290),dp(52));unlockParams.setMargins(0,dp(30),0,0);
            content.addView(manualUnlock,unlockParams);
        }
        root.addView(content,new FrameLayout.LayoutParams(-1,warning?ViewGroup.LayoutParams.WRAP_CONTENT:-1));

        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        // 不覆盖系统状态栏：锁住普通应用区域，同时保留下拉通知与来电入口。
        int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if(warning)flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(warning?dp(240):-1,warning?WindowManager.LayoutParams.WRAP_CONTENT:-1,type,flags,PixelFormat.TRANSLUCENT);
        lp.gravity=warning?(Gravity.TOP|Gravity.CENTER_HORIZONTAL):(Gravity.TOP|Gravity.START);
        if(warning)lp.y=dp(72);
        try{windows.addView(root,lp);overlay=root;}catch(Exception ignored){overlay=null;}
    }

    private boolean isScreenUsable(){
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        if(power==null||!power.isInteractive())return false;
        KeyguardManager keyguard=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard==null||!keyguard.isKeyguardLocked();
    }

    private void removeOverlay(){
        if(overlay!=null&&windows!=null){try{windows.removeView(overlay);}catch(Exception ignored){}overlay=null;}
        title=null;countdown=null;tip=null;manualUnlock=null;
    }

    private void notifyAtMostEachMinute(String prefix,long left,String text){
        String key=prefix+":"+(left/60_000L);
        if(!key.equals(lastNoticeKey)){lastNoticeKey=key;host.updateSleepNotification(state,text);}
    }

    private void notifyOnce(String key,String text){
        if(!key.equals(lastNoticeKey)){lastNoticeKey=key;host.updateSleepNotification(state,text);}
    }

    private int earlyEndRemaining(){
        String month=new java.text.SimpleDateFormat("yyyy-MM",java.util.Locale.CHINA).format(new java.util.Date());
        if(!month.equals(prefs.getString("sleep_early_end_month","")))
            prefs.edit().putString("sleep_early_end_month",month).putInt("sleep_early_end_count",0).apply();
        return Math.max(0,MAX_EARLY_ENDS_PER_MONTH-prefs.getInt("sleep_early_end_count",0));
    }

    private boolean consumeEarlyEnd(){
        int remaining=earlyEndRemaining();
        if(remaining<=0)return false;
        return prefs.edit().putInt("sleep_early_end_count",prefs.getInt("sleep_early_end_count",0)+1).commit();
    }

    private TextView label(String value,int sp,int color,boolean bold){
        TextView view=new TextView(context);view.setText(value);view.setTextSize(sp);view.setTextColor(color);
        if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;
    }
    private android.graphics.Bitmap decodePreview(File file,int reqWidth,int reqHeight){
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
        int sample=1;
        while(bounds.outWidth/sample>reqWidth*2||bounds.outHeight/sample>reqHeight*2)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inSampleSize=sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(),options);
    }
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
