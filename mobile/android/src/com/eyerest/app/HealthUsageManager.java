package com.eyerest.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 健康使用模块的门面：在后台线程读取 UsageStats，组合快照，再切回主线程回调。
 * Activity/View 不需要直接承担统计逻辑。
 */
public final class HealthUsageManager {
    private static final String TAG = "HealthUsage";
    private static final int SNAPSHOT_DAYS = 14;
    private static final int TOP_APP_COUNT = 5;
    private static final long DETAIL_CACHE_MILLIS = 2L * 60L * 1000L;

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(Throwable error);
    }

    public static final class PermissionDeniedException extends SecurityException {
        public PermissionDeniedException() {
            super("Usage access permission is not enabled");
        }

        public PermissionDeniedException(String message) { super(message); }
    }

    private final Context context;
    private final UsageStatsRepository repository;
    private final UsageStatsCalculator calculator;
    private final HealthScoreCalculator scoreCalculator;
    private final HealthSettings settings;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicLong generation = new AtomicLong(0L);
    private volatile Future<?> activeTask;
    private volatile boolean closed;
    private volatile HealthModels.HealthSnapshot cachedSnapshot;

    public HealthUsageManager(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.context = context.getApplicationContext();
        repository = new UsageStatsRepository(this.context);
        calculator = new UsageStatsCalculator();
        scoreCalculator = new HealthScoreCalculator();
        settings = new HealthSettings(this.context);
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "health-usage-query");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }
        });
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public boolean hasUsageAccess() { return repository.hasUsageAccess(); }

    /** Called when the Activity resumes from the system Usage Access screen. */
    public void invalidateUsageAccessCache() { repository.invalidateAccessCache(); }

    public Intent createUsageAccessSettingsIntent() {
        return repository.createUsageAccessSettingsIntent();
    }

    public HealthSettings getSettings() { return settings; }

    public Drawable loadAppIcon(String packageName) {
        return repository.loadAppIcon(packageName);
    }

    public HealthModels.HealthSnapshot getCachedSnapshot() { return cachedSnapshot; }

    public void refresh(final Callback<HealthModels.HealthSnapshot> callback) {
        if (callback == null) throw new IllegalArgumentException("callback == null");
        if (closed) {
            postError(callback, new IllegalStateException("HealthUsageManager is shut down"), generation.get());
            return;
        }
        final long requestId = generation.incrementAndGet();
        Future<?> previous=activeTask;
        if(previous!=null)previous.cancel(true);
        activeTask=executor.submit(new Runnable() {
            @Override public void run() {
                try {
                    HealthModels.HealthSnapshot value = buildSnapshot(System.currentTimeMillis());
                    cachedSnapshot = value;
                    postSuccess(callback, value, requestId);
                } catch (Throwable error) {
                    postError(callback, error, requestId);
                }
            }
        });
    }

    public void loadAppDetail(final String packageName,
                              final Callback<HealthModels.AppDetail> callback) {
        if (callback == null) throw new IllegalArgumentException("callback == null");
        if (packageName == null || packageName.trim().isEmpty()) {
            postError(callback, new IllegalArgumentException("packageName is empty"), generation.get());
            return;
        }
        if (closed) {
            postError(callback, new IllegalStateException("HealthUsageManager is shut down"), generation.get());
            return;
        }
        final long requestId = generation.incrementAndGet();
        Future<?> previous=activeTask;
        if(previous!=null)previous.cancel(true);
        activeTask=executor.submit(new Runnable() {
            @Override public void run() {
                try {
                    if (!hasUsageAccess()) throw new PermissionDeniedException();
                    long now = System.currentTimeMillis();
                    HealthModels.HealthSnapshot snapshot = cachedSnapshot;
                    if (snapshot == null
                        || now - snapshot.generatedAtMillis > DETAIL_CACHE_MILLIS) {
                        snapshot = buildSnapshot(now);
                        cachedSnapshot = snapshot;
                    }
                    HealthModels.AppDetail detail = calculator.createAppDetail(packageName,
                        snapshot.today, snapshot.yesterday, snapshot.last7Days);
                    postSuccess(callback, detail, requestId);
                } catch (Throwable error) {
                    postError(callback, error, requestId);
                }
            }
        });
    }

    /** Ignore and interrupt work that belongs to a detached/hidden page. */
    public void cancelPending() {
        generation.incrementAndGet();
        Future<?> task=activeTask;
        if(task!=null)task.cancel(true);
        activeTask=null;
    }

    /** 停止本页面自己的查询线程，不会停止护眼或睡眠 Service。 */
    public void shutdown() {
        closed = true;
        cancelPending();
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private HealthModels.HealthSnapshot buildSnapshot(long nowMillis) {
        if (!hasUsageAccess()) throw new PermissionDeniedException();
        List<HealthModels.DayUsage> days=new ArrayList<>();
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();
        java.time.LocalDate todayDate=java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        try(com.eyerest.app.ledger.UsageRepository ledger=new com.eyerest.app.ledger.UsageRepository(context)) {
            ledger.archiveRecent();
            for(int i=SNAPSHOT_DAYS-1;i>=0;i--){
                java.time.LocalDate date=todayDate.minusDays(i);
                com.eyerest.app.ledger.UsageEngine.Day day=ledger.load(date);
                List<HealthModels.AppUsage> apps=new ArrayList<>();
                int opens=0;
                for(com.eyerest.app.ledger.UsageEngine.App app:day.sorted()){
                    HealthModels.AppMetadata info=repository.getAppMetadata(app.pkg);
                    apps.add(new HealthModels.AppUsage(app.pkg,info.appName,app.millis,app.opens,
                        day.evidence,info.installed,true,0L));
                    opens+=app.opens;
                }
                long start=date.atStartOfDay(zone).toInstant().toEpochMilli();
                long end=Math.min(nowMillis,date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
                days.add(new HealthModels.DayUsage(start,end,day.total,0,0,0,0,opens,
                    day.evidence,false,day.evidence||day.total>0,apps));
            }
        } catch(SecurityException e){throw new PermissionDeniedException();}
          catch(Exception e){throw new IllegalStateException("ScreenLedger statistics unavailable",e);}
        HealthModels.DayUsage today=days.get(days.size()-1), yesterday=days.get(days.size()-2);
        return new HealthModels.HealthSnapshot(today,yesterday,immutableSlice(days,7,14),
            immutableSlice(days,0,7),new ArrayList<>(today.apps.subList(0,Math.min(TOP_APP_COUNT,today.apps.size()))),
            scoreCalculator.calculate(today,settings.getDailyGoalMillis(),settings.getContinuousReminderMillis()),
            nowMillis,today.hasUsageData);
    }

    private static List<HealthModels.DayUsage> immutableSlice(
        List<HealthModels.DayUsage> days, int from, int to) {
        return Collections.unmodifiableList(new ArrayList<HealthModels.DayUsage>(
            days.subList(from, to)));
    }

    private <T> void postSuccess(final Callback<T> callback, final T value, final long requestId) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!closed && requestId==generation.get()) callback.onSuccess(value);
            }
        });
    }

    private <T> void postError(final Callback<T> callback, final Throwable error, final long requestId) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!closed && requestId==generation.get()) callback.onError(error);
            }
        });
    }
}
