package com.eyerest.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.util.*;

/** Keep recognised packages even after uninstall so today's quota cannot reset. */
public final class GameRegistry {
    private static final Set<String> KNOWN=new HashSet<>(Arrays.asList(
        "com.tencent.tmgp.sgame","com.tencent.tmgp.pubgmhd","com.miHoYo.Yuanshen",
        "com.miHoYo.hkrpg","com.miHoYo.Nap","com.netease.dwrg","com.netease.hyxd",
        "com.codex.balancerider","cn.codex.gomoku","com.neonrush.game",
        "com.wedobest.fivechess.mi","com.Creativeworld.QuadcopterFX","com.xiaomi.minigame"));
    private final Context context;
    private final Set<String> games=new HashSet<>(),checked=new HashSet<>();
    public GameRegistry(Context context){
        this.context=context.getApplicationContext();games.addAll(KNOWN);
        games.addAll(context.getSharedPreferences("strict_games",0).getStringSet("packages",Collections.emptySet()));
    }
    public synchronized void scan(){
        checked.clear();
        for(ApplicationInfo app:context.getPackageManager().getInstalledApplications(0))inspect(app);
        save();
    }
    private void inspect(ApplicationInfo app){
        checked.add(app.packageName);
        if(app.category==ApplicationInfo.CATEGORY_GAME || (app.flags&ApplicationInfo.FLAG_IS_GAME)!=0)
            games.add(app.packageName);
    }
    public synchronized boolean isGame(String pkg){
        if(pkg==null)return false;
        if(!checked.contains(pkg)){
            checked.add(pkg);
            try{inspect(context.getPackageManager().getApplicationInfo(pkg,0));save();}catch(Exception ignored){}
        }
        return games.contains(pkg);
    }
    public synchronized Set<String> packages(){return new HashSet<>(games);}
    private void save(){context.getSharedPreferences("strict_games",0).edit().putStringSet("packages",new HashSet<>(games)).apply();}
    public static boolean protectedPackage(Context context,String pkg){
        GameRegistry r=new GameRegistry(context);r.isGame(pkg);return StrictLimitPolicy.category(pkg,r.packages())!=StrictLimitPolicy.NONE;
    }
}
