package com.eyerest.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Button;

/** Keeps the existing app picker, limits, permissions and editing controls. */
public final class AppLimitActivity extends Activity {
    private HealthUsageView limits;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff4f6fb);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i.consumeSystemWindowInsets();});
        Button back=new Button(this);back.setText("‹ 返回健康使用");back.setOnClickListener(v->finish());root.addView(back);
        limits=new HealthUsageView(this,true);root.addView(limits,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);root.requestApplyInsets();
    }
    @Override protected void onResume(){super.onResume();if(limits!=null)limits.refreshData();}
    @Override protected void onDestroy(){if(limits!=null)limits.destroy();super.onDestroy();}
}
