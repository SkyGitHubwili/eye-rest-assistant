package com.eyerest.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        List<Long> dayStarts = dayStarts(nowMillis, SNAPSHOT_DAYS);
        Calendar lookback = Calendar.getInstance();
        lookback.setTimeInMillis(dayStarts.get(0));
        lookback.add(Calendar.DAY_OF_MONTH, -1);
        List<HealthModels.UsageEventRecord> events = repository.queryEventRecords(
            lookback.getTimeInMillis(), nowMillis);
        boolean eventsAvailable = repository.wasLastEventQueryAvailable();

        List<List<HealthModels.AppUsageStatRecord>> statsByDay =
            new ArrayList<List<HealthModels.AppUsageStatRecord>>(SNAPSHOT_DAYS);
        List<Boolean> statsAvailabilityByDay =
            new ArrayList<Boolean>(SNAPSHOT_DAYS);
        Set<String> packageNames = new HashSet<String>();
        for (int index = 0; index < dayStarts.size(); index++) {
            long start = dayStarts.get(index);
            long end = index + 1 < dayStarts.size() ? dayStarts.get(index + 1) : nowMillis;
            List<HealthModels.AppUsageStatRecord> stats =
                repository.queryUsageStatsRecords(start, end);
            statsByDay.add(stats);
            statsAvailabilityByDay.add(repository.wasLastUsageStatsQueryAvailable());
            for (HealthModels.AppUsageStatRecord stat : stats) {
                if (stat != null && stat.packageName.length() > 0) {
                    packageNames.add(stat.packageName);
                }
            }
        }
        for (HealthModels.UsageEventRecord event : events) {
            if (event != null && event.packageName.length() > 0
                && (event.isForeground() || event.isBackground())) {
                packageNames.add(event.packageName);
            }
        }
        Map<String, HealthModels.AppMetadata> metadata = loadMetadata(packageNames);

        List<HealthModels.DayUsage> days =
            new ArrayList<HealthModels.DayUsage>(SNAPSHOT_DAYS);
        for (int index = 0; index < dayStarts.size(); index++) {
            long start = dayStarts.get(index);
            long end = index + 1 < dayStarts.size() ? dayStarts.get(index + 1) : nowMillis;
            boolean preferEvents=index==dayStarts.size()-1;
            days.add(calculator.calculateDay(start, end, statsByDay.get(index),
                events, metadata, statsAvailabilityByDay.get(index), eventsAvailable,
                preferEvents));
        }
        if (!hasUsageAccess()) throw new PermissionDeniedException("Usage access was revoked");

        HealthModels.DayUsage today = days.get(days.size() - 1);
        HealthModels.DayUsage yesterday = days.get(days.size() - 2);
        List<HealthModels.DayUsage> previous7 = immutableSlice(days, 0, 7);
        List<HealthModels.DayUsage> last7 = immutableSlice(days, 7, 14);
        List<HealthModels.AppUsage> topApps = removeOwnApp(
            calculator.topApps(today, TOP_APP_COUNT + 1), TOP_APP_COUNT);
        HealthModels.HealthScore score = scoreCalculator.calculate(today,
            settings.getDailyGoalMillis(), settings.getContinuousReminderMillis());
        return new HealthModels.HealthSnapshot(today, yesterday, last7, previous7,
            topApps, score, nowMillis, today.hasUsageData);
    }

    private Map<String, HealthModels.AppMetadata> loadMetadata(Set<String> packageNames) {
        Map<String, HealthModels.AppMetadata> result =
            new HashMap<String, HealthModels.AppMetadata>();
        for (String packageName : packageNames) {
            if (packageName != null && packageName.length() > 0) {
                result.put(packageName, repository.getAppMetadata(packageName));
            }
        }
        return result;
    }

    private List<HealthModels.AppUsage> removeOwnApp(List<HealthModels.AppUsage> apps, int limit) {
        if (apps == null || apps.isEmpty()) return Collections.emptyList();
        List<HealthModels.AppUsage> result = new ArrayList<HealthModels.AppUsage>();
        for (HealthModels.AppUsage app : apps) {
            if (app == null || context.getPackageName().equals(app.packageName)) continue;
            result.add(app);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static List<Long> dayStarts(long nowMillis, int count) {
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(nowMillis);
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        List<Long> result = new ArrayList<Long>(count);
        for (int offset = count - 1; offset >= 0; offset--) {
            Calendar day = (Calendar) today.clone();
            day.add(Calendar.DAY_OF_MONTH, -offset);
            result.add(day.getTimeInMillis());
        }
        return result;
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
