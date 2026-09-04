package com.eyerest.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Calendar;

/** 对真实 UsageStats/UsageEvents 做纯 Java 计算，不负责读取系统或渲染 UI。 */
public final class UsageStatsCalculator {
    /** App 切换时系统事件可能有很短空档；小于一分钟仍视为同一连续使用段。 */
    public static final long CONTINUOUS_GAP_TOLERANCE_MILLIS = 60_000L;
    public static final int NIGHT_START_HOUR = 22;
    public static final int NIGHT_END_HOUR = 6;

    public UsageStatsCalculator() {}

    public HealthModels.DayUsage calculateDay(
        long dayStartMillis,
        long rangeEndMillis,
        List<HealthModels.AppUsageStatRecord> stats,
        List<HealthModels.UsageEventRecord> events,
        Map<String, HealthModels.AppMetadata> metadata,
        boolean usageStatsAvailable,
        boolean eventsAvailable
    ) {
        return calculateDay(dayStartMillis, rangeEndMillis, stats, events, metadata,
            usageStatsAvailable, eventsAvailable, false);
    }

    /**
     * Calculate a day. The legacy final flag is retained for source
     * compatibility, but App duration is never selected from UsageEvents.
     */
    public HealthModels.DayUsage calculateDay(
        long dayStartMillis,
        long rangeEndMillis,
        List<HealthModels.AppUsageStatRecord> stats,
        List<HealthModels.UsageEventRecord> events,
        Map<String, HealthModels.AppMetadata> metadata,
        boolean usageStatsAvailable,
        boolean eventsAvailable,
        boolean ignoredPreferEventDurations
    ) {
        long end = Math.max(dayStartMillis, rangeEndMillis);
        long rangeLength = Math.max(0L, end - dayStartMillis);
        List<HealthModels.UsageInterval> intervals =
            buildIntervals(events, dayStartMillis, end);

        Map<String, Long> eventDurations = new HashMap<String, Long>();
        Map<String, Integer> launches = new HashMap<String, Integer>();
        Set<String> eventPackages = new HashSet<String>();
        for (HealthModels.UsageInterval interval : intervals) {
            long start = Math.max(dayStartMillis, interval.startMillis);
            long intervalEnd = Math.min(end, interval.endMillis);
            if (intervalEnd <= start || interval.packageName.length() == 0) continue;
            eventPackages.add(interval.packageName);
            addDuration(eventDurations, interval.packageName, intervalEnd - start);
            if (interval.startMillis >= dayStartMillis && interval.startMillis < end) {
                Integer old = launches.get(interval.packageName);
                launches.put(interval.packageName, old == null ? 1 : old + 1);
            }
        }

        Map<String, Long> statDurations = new HashMap<String, Long>();
        Map<String, Long> lastUsed = new HashMap<String, Long>();
        if (stats != null) {
            for (HealthModels.AppUsageStatRecord stat : stats) {
                if (stat == null || stat.packageName.length() == 0) continue;
                long duration = Math.min(rangeLength, Math.max(0L, stat.usageMillis));
                addDuration(statDurations, stat.packageName, duration);
                Long oldLast = lastUsed.get(stat.packageName);
                if (oldLast == null || stat.lastTimeUsedMillis > oldLast) {
                    lastUsed.put(stat.packageName, stat.lastTimeUsedMillis);
                }
            }
        }

        // UsageStats owns App total duration whenever its query succeeded.
        // Event-only packages are behavior evidence, not usage-time records.
        Set<String> packages = new HashSet<String>();
        if (usageStatsAvailable) packages.addAll(statDurations.keySet());
        else packages.addAll(eventDurations.keySet());

        boolean hasEventEvidence = hasEventEvidence(events, intervals, dayStartMillis, end);
        boolean launchCountsAvailable = eventsAvailable
            && hasEventEvidence;
        List<HealthModels.AppUsage> apps = new ArrayList<HealthModels.AppUsage>();
        long total = 0L;
        for (String packageName : packages) {
            long statDuration = value(statDurations, packageName);
            long eventDuration = value(eventDurations, packageName);
            // App total duration has one source of truth: Android's
            // UsageStats.getTotalTimeInForeground(). UsageEvents remain
            // available for launches, continuity, night usage and timelines.
            long duration = usageStatsAvailable ? statDuration : eventDuration;
            duration = Math.min(rangeLength, Math.max(0L, duration));
            if (duration <= 0L) continue;
            HealthModels.AppMetadata info = metadata == null ? null : metadata.get(packageName);
            if (info == null) {
                // Metadata lookup can transiently fail on vendor ROMs. Keep
                // the real package/duration rather than silently dropping it;
                // the explicit core-package filter below still protects the
                // ranking from Android shell components.
                info = new HealthModels.AppMetadata(packageName, packageName, true, true);
            }
            // UsageStats can retain entries for uninstalled packages and can
            // include Android shell/service components. They must not inflate
            // the user's phone-time total or appear in Top Apps. A preinstalled
            // app with a launch activity remains userFacing and is retained.
            if (!info.installed || !info.userFacing || isCoreSystemPackage(packageName)) {
                continue;
            }
            total = saturatingAdd(total, duration);
            int launchCount = intValue(launches, packageName);
            boolean packageLaunchesAvailable = launchCountsAvailable
                && eventPackages.contains(packageName);
            apps.add(new HealthModels.AppUsage(packageName, info.appName, duration,
                launchCount, packageLaunchesAvailable, info.installed, info.userFacing,
                value(lastUsed, packageName)));
        }
        // Protect the aggregate against malformed vendor values without
        // replacing it with an event-derived duration.
        total = Math.min(rangeLength, total);
        sortApps(apps);

        HealthModels.ContinuousUsage continuous = calculateContinuousUsage(
            events, dayStartMillis, end);
        long night = eventsAvailable
            ? calculateNightUsage(intervals, dayStartMillis, end) : 0L;
        int totalLaunches = 0;
        if (launchCountsAvailable) {
            for (Integer count : launches.values()) {
                if (count != null && count > 0) {
                    totalLaunches = safeIntAdd(totalLaunches, count);
                }
            }
        }
        boolean hasData = total > 0L || !apps.isEmpty();
        return new HealthModels.DayUsage(dayStartMillis, end, total,
            continuous.longestMillis, continuous.currentMillis,
            continuous.currentStartMillis, night, totalLaunches,
            launchCountsAvailable, eventsAvailable && continuous.available,
            hasData, apps);
    }

    public HealthModels.DayUsage calculateDay(
        long dayStartMillis,
        long rangeEndMillis,
        List<HealthModels.AppUsageStatRecord> stats,
        List<HealthModels.UsageEventRecord> events,
        Map<String, HealthModels.AppMetadata> metadata
    ) {
        return calculateDay(dayStartMillis, rangeEndMillis, stats, events, metadata,
            true, true);
    }

    /**
     * 从事件构造 App 前台区间。调用方可传入早于 rangeStart 的事件，以识别跨边界会话。
     */
    public List<HealthModels.UsageInterval> buildIntervals(
        List<HealthModels.UsageEventRecord> source,
        long rangeStartMillis,
        long rangeEndMillis
    ) {
        if (rangeEndMillis <= rangeStartMillis || source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<HealthModels.UsageEventRecord> events = sortedEvents(source);
        List<HealthModels.UsageInterval> intervals =
            new ArrayList<HealthModels.UsageInterval>();
        Map<String, Long> activeStarts = new HashMap<String, Long>();
        String currentPackage = null;
        for (HealthModels.UsageEventRecord event : events) {
            if (event == null || event.timestampMillis > rangeEndMillis) break;
            if (event.isForeground()) {
                if (event.packageName.length() == 0) continue;
                String packageName=event.packageName;
                if(currentPackage!=null&&!currentPackage.equals(packageName)){
                    Long start=activeStarts.remove(currentPackage);
                    if(start!=null)addInterval(intervals,currentPackage,start,event.timestampMillis,
                        rangeStartMillis, false);
                }
                // Repeated RESUMED/FOREGROUND events for the same package do
                // not create overlapping sessions.
                if(!activeStarts.containsKey(packageName))activeStarts.put(packageName,event.timestampMillis);
                currentPackage=packageName;
            } else if (event.isBackground()) {
                Long start=activeStarts.remove(event.packageName);
                if(start!=null)addInterval(intervals,event.packageName,start,event.timestampMillis,
                    rangeStartMillis, false);
                if(event.packageName.equals(currentPackage)){
                    currentPackage=null;
                }
            } else if (event.isHardBreak()) {
                for(Map.Entry<String,Long> active:new ArrayList<Map.Entry<String,Long>>(activeStarts.entrySet())){
                    addInterval(intervals,active.getKey(),active.getValue(),event.timestampMillis,
                        rangeStartMillis, false);
                }
                activeStarts.clear();currentPackage=null;
            }
        }
        for(Map.Entry<String,Long> active:activeStarts.entrySet()){
            addInterval(intervals,active.getKey(),active.getValue(),rangeEndMillis,
                rangeStartMillis, true);
        }
        return mergeIntervals(intervals);
    }

    /**
     * 估算今日最长/当前连续使用。锁屏、熄屏、关机会立即切断连续段；普通 App
     * 切换仅允许最多一分钟的系统事件空档。
     */
    public HealthModels.ContinuousUsage calculateContinuousUsage(
        List<HealthModels.UsageEventRecord> events,
        long rangeStartMillis,
        long rangeEndMillis
    ) {
        if (rangeEndMillis <= rangeStartMillis || events == null || events.isEmpty()) {
            return new HealthModels.ContinuousUsage(0L, 0L, 0L, false);
        }
        List<HealthModels.UsageInterval> intervals =
            buildIntervals(events, rangeStartMillis, rangeEndMillis);
        if (intervals.isEmpty()) {
            return new HealthModels.ContinuousUsage(0L, 0L, 0L, hasRelevantEvent(events));
        }
        List<Long> hardBreaks = new ArrayList<Long>();
        for (HealthModels.UsageEventRecord event : events) {
            if (event != null && event.isHardBreak()
                && event.timestampMillis >= rangeStartMillis
                && event.timestampMillis <= rangeEndMillis) {
                hardBreaks.add(event.timestampMillis);
            }
        }

        long segmentStart = Math.max(rangeStartMillis, intervals.get(0).startMillis);
        long segmentEnd = Math.min(rangeEndMillis, intervals.get(0).endMillis);
        long longest = Math.max(0L, segmentEnd - segmentStart);
        long current = 0L;
        long currentStart = 0L;
        boolean lastOpen = intervals.get(0).openAtRangeEnd;

        for (int index = 1; index < intervals.size(); index++) {
            HealthModels.UsageInterval next = intervals.get(index);
            long nextStart = Math.max(rangeStartMillis, next.startMillis);
            long nextEnd = Math.min(rangeEndMillis, next.endMillis);
            long gap = Math.max(0L, nextStart - segmentEnd);
            boolean hardBreak = containsBreak(hardBreaks, segmentEnd, nextStart);
            if (!hardBreak && gap <= CONTINUOUS_GAP_TOLERANCE_MILLIS) {
                segmentEnd = Math.max(segmentEnd, nextEnd);
            } else {
                longest = Math.max(longest, Math.max(0L, segmentEnd - segmentStart));
                segmentStart = nextStart;
                segmentEnd = nextEnd;
            }
            lastOpen = next.openAtRangeEnd;
        }
        longest = Math.max(longest, Math.max(0L, segmentEnd - segmentStart));
        if (lastOpen && segmentEnd >= rangeEndMillis) {
            currentStart = segmentStart;
            current = Math.max(0L, rangeEndMillis - segmentStart);
        }
        return new HealthModels.ContinuousUsage(longest, current, currentStart,
            hasRelevantEvent(events));
    }

    /** Top App 优先保留当前可见/可启动应用，不足时再使用真实剩余记录。 */
    public List<HealthModels.AppUsage> topApps(HealthModels.DayUsage day, int limit) {
        if (day == null || limit <= 0 || day.apps.isEmpty()) return Collections.emptyList();
        List<HealthModels.AppUsage> primary = new ArrayList<HealthModels.AppUsage>();
        List<HealthModels.AppUsage> fallback = new ArrayList<HealthModels.AppUsage>();
        for (HealthModels.AppUsage app : day.apps) {
            if (app == null || app.usageMillis <= 0L) continue;
            if (isCoreSystemPackage(app.packageName)) continue;
            if (app.userFacing) primary.add(app); else fallback.add(app);
        }
        sortApps(primary);
        sortApps(fallback);
        List<HealthModels.AppUsage> result = new ArrayList<HealthModels.AppUsage>();
        addUpTo(result, primary, limit);
        addUpTo(result, fallback, limit);
        return result;
    }

    public HealthModels.AppDetail createAppDetail(String packageName,
                                                   HealthModels.DayUsage today,
                                                   HealthModels.DayUsage yesterday,
                                                   List<HealthModels.DayUsage> last7Days) {
        if (packageName == null || packageName.length() == 0) {
            throw new IllegalArgumentException("packageName is empty");
        }
        HealthModels.AppUsage todayApp = findApp(today, packageName);
        HealthModels.AppUsage yesterdayApp = findApp(yesterday, packageName);
        HealthModels.AppUsage identity = todayApp != null ? todayApp : yesterdayApp;
        if (identity == null && last7Days != null) {
            for (HealthModels.DayUsage day : last7Days) {
                identity = findApp(day, packageName);
                if (identity != null) break;
            }
        }
        if (identity == null) {
            identity = new HealthModels.AppUsage(packageName, packageName, 0L,
                0, false, false, false, 0L);
        }
        long sum = 0L;
        long peakMillis = 0L;
        long peakDay = 0L;
        int count = last7Days == null ? 0 : last7Days.size();
        if (last7Days != null) {
            for (HealthModels.DayUsage day : last7Days) {
                HealthModels.AppUsage app = findApp(day, packageName);
                long duration = app == null ? 0L : app.usageMillis;
                sum = saturatingAdd(sum, duration);
                if (duration > peakMillis) {
                    peakMillis = duration;
                    peakDay = day == null ? 0L : day.dayStartMillis;
                }
            }
        }
        long todayMillis = todayApp == null ? 0L : todayApp.usageMillis;
        long yesterdayMillis = yesterdayApp == null ? 0L : yesterdayApp.usageMillis;
        int launches = todayApp == null ? 0 : todayApp.launchCount;
        boolean launchesAvailable = todayApp != null && todayApp.launchCountAvailable;
        long averageSession = launchesAvailable && launches > 0 ? todayMillis / launches : 0L;
        return new HealthModels.AppDetail(identity, todayMillis, yesterdayMillis,
            last7Days, count == 0 ? 0L : sum / count, peakDay, peakMillis,
            launches, averageSession, launchesAvailable);
    }

    private static HealthModels.AppUsage findApp(HealthModels.DayUsage day,
                                                  String packageName) {
        if (day == null || day.apps == null) return null;
        for (HealthModels.AppUsage app : day.apps) {
            if (app != null && packageName.equals(app.packageName)) return app;
        }
        return null;
    }

    private static long calculateNightUsage(List<HealthModels.UsageInterval> intervals,
                                            long dayStartMillis, long rangeEndMillis) {
        if (intervals == null || intervals.isEmpty()) return 0L;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dayStartMillis);
        calendar.set(Calendar.HOUR_OF_DAY, NIGHT_END_HOUR);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long morningEnd = calendar.getTimeInMillis();
        calendar.setTimeInMillis(dayStartMillis);
        calendar.set(Calendar.HOUR_OF_DAY, NIGHT_START_HOUR);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long nightStart = calendar.getTimeInMillis();
        long total = 0L;
        for (HealthModels.UsageInterval interval : intervals) {
            long start = Math.max(dayStartMillis, interval.startMillis);
            long end = Math.min(rangeEndMillis, interval.endMillis);
            total = saturatingAdd(total, overlap(start, end, dayStartMillis,
                Math.min(rangeEndMillis, morningEnd)));
            total = saturatingAdd(total, overlap(start, end,
                Math.max(dayStartMillis, nightStart), rangeEndMillis));
        }
        return Math.min(Math.max(0L, rangeEndMillis - dayStartMillis), total);
    }

    private static long overlap(long start, long end, long windowStart, long windowEnd) {
        if (windowEnd <= windowStart) return 0L;
        return Math.max(0L, Math.min(end, windowEnd) - Math.max(start, windowStart));
    }

    private static void addInterval(List<HealthModels.UsageInterval> target, String packageName,
                                    long start, long end, long rangeStart, boolean open) {
        if (packageName == null || packageName.length() == 0 || end <= start) {
            return;
        }
        long clippedStart=Math.max(rangeStart,start);
        long clippedEnd=Math.min(end,Long.MAX_VALUE);
        if(clippedEnd<=clippedStart)return;
        target.add(new HealthModels.UsageInterval(packageName,clippedStart,clippedEnd,
            open&&clippedEnd>=end));
    }

    /** Merge overlapping intervals for one package, then restore time order. */
    private static List<HealthModels.UsageInterval> mergeIntervals(
        List<HealthModels.UsageInterval> source) {
        if(source==null||source.size()<2)return source==null?Collections.emptyList():source;
        List<HealthModels.UsageInterval> sorted=new ArrayList<HealthModels.UsageInterval>(source);
        Collections.sort(sorted,new Comparator<HealthModels.UsageInterval>(){
            @Override public int compare(HealthModels.UsageInterval a,HealthModels.UsageInterval b){
                int byPackage=a.packageName.compareTo(b.packageName);
                if(byPackage!=0)return byPackage;
                int byStart=Long.compare(a.startMillis,b.startMillis);
                return byStart!=0?byStart:Long.compare(a.endMillis,b.endMillis);
            }
        });
        List<HealthModels.UsageInterval> merged=new ArrayList<HealthModels.UsageInterval>();
        for(HealthModels.UsageInterval next:sorted){
            if(merged.isEmpty()){merged.add(next);continue;}
            HealthModels.UsageInterval current=merged.get(merged.size()-1);
            if(current.packageName.equals(next.packageName)&&next.startMillis<current.endMillis){
                merged.set(merged.size()-1,new HealthModels.UsageInterval(current.packageName,
                    current.startMillis,Math.max(current.endMillis,next.endMillis),
                    current.openAtRangeEnd||next.openAtRangeEnd));
            }else merged.add(next);
        }
        Collections.sort(merged,new Comparator<HealthModels.UsageInterval>(){
            @Override public int compare(HealthModels.UsageInterval a,HealthModels.UsageInterval b){
                int byStart=Long.compare(a.startMillis,b.startMillis);
                return byStart!=0?byStart:a.packageName.compareTo(b.packageName);
            }
        });
        return merged;
    }

    private static boolean hasEventEvidence(List<HealthModels.UsageEventRecord> events,
                                            List<HealthModels.UsageInterval> intervals,
                                            long start, long end) {
        if (intervals != null && !intervals.isEmpty()) return true;
        if (events == null) return false;
        for (HealthModels.UsageEventRecord event : events) {
            if (event != null && event.timestampMillis >= start && event.timestampMillis <= end
                && (event.isForeground() || event.isBackground() || event.isHardBreak())) return true;
        }
        return false;
    }

    private static boolean hasRelevantEvent(List<HealthModels.UsageEventRecord> events) {
        if (events == null) return false;
        for (HealthModels.UsageEventRecord event : events) {
            if (event != null
                && (event.isForeground() || event.isBackground() || event.isHardBreak())) return true;
        }
        return false;
    }

    private static boolean containsBreak(List<Long> breaks, long afterInclusive,
                                         long beforeInclusive) {
        if (breaks == null || breaks.isEmpty()) return false;
        for (Long value : breaks) {
            if (value != null && value >= afterInclusive && value <= beforeInclusive) return true;
        }
        return false;
    }

    private static List<HealthModels.UsageEventRecord> sortedEvents(
        List<HealthModels.UsageEventRecord> source) {
        List<HealthModels.UsageEventRecord> result =
            new ArrayList<HealthModels.UsageEventRecord>();
        for (HealthModels.UsageEventRecord event : source) if (event != null) result.add(event);
        Collections.sort(result, new Comparator<HealthModels.UsageEventRecord>() {
            @Override public int compare(HealthModels.UsageEventRecord a,
                                          HealthModels.UsageEventRecord b) {
                int byTime = Long.compare(a.timestampMillis, b.timestampMillis);
                return byTime != 0 ? byTime : Integer.compare(a.eventType, b.eventType);
            }
        });
        return result;
    }

    private static void sortApps(List<HealthModels.AppUsage> apps) {
        Collections.sort(apps, new Comparator<HealthModels.AppUsage>() {
            @Override public int compare(HealthModels.AppUsage a, HealthModels.AppUsage b) {
                int byDuration = Long.compare(b.usageMillis, a.usageMillis);
                if (byDuration != 0) return byDuration;
                int byLast = Long.compare(b.lastTimeUsedMillis, a.lastTimeUsedMillis);
                return byLast != 0 ? byLast : a.packageName.compareTo(b.packageName);
            }
        });
    }

    private static void addUpTo(List<HealthModels.AppUsage> target,
                                List<HealthModels.AppUsage> source, int limit) {
        for (HealthModels.AppUsage app : source) {
            if (target.size() >= limit) return;
            target.add(app);
        }
    }

    /** Do not put the Android shell itself in a user-facing Top Apps list. */
    private static boolean isCoreSystemPackage(String packageName) {
        if (packageName == null) return true;
        return "android".equals(packageName)
            || "com.android.systemui".equals(packageName)
            || "com.android.settings".equals(packageName)
            || "com.android.launcher".equals(packageName)
            || packageName.startsWith("com.android.launcher.")
            || "com.google.android.permissioncontroller".equals(packageName)
            || "com.android.permissioncontroller".equals(packageName)
            || "com.android.packageinstaller".equals(packageName)
            || "com.android.providers.settings".equals(packageName);
    }

    private static void addDuration(Map<String, Long> values, String key, long duration) {
        if (duration <= 0L) return;
        Long old = values.get(key);
        values.put(key, saturatingAdd(old == null ? 0L : old, duration));
    }

    private static long sum(Map<String, Long> values) {
        long result = 0L;
        for (Long value : values.values()) {
            if (value != null && value > 0L) result = saturatingAdd(result, value);
        }
        return result;
    }

    private static long value(Map<String, Long> values, String key) {
        Long value = values.get(key);
        return value == null ? 0L : Math.max(0L, value);
    }

    private static int intValue(Map<String, Integer> values, String key) {
        Integer value = values.get(key);
        return value == null ? 0 : Math.max(0, value);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static int safeIntAdd(int left, int right) {
        if (right > 0 && left > Integer.MAX_VALUE - right) return Integer.MAX_VALUE;
        return left + right;
    }

    private static long unionDuration(List<HealthModels.UsageInterval> intervals,
                                      long rangeStart, long rangeEnd) {
        List<long[]> spans = new ArrayList<long[]>();
        for (HealthModels.UsageInterval interval : intervals) {
            if (interval == null) continue;
            long start = Math.max(rangeStart, interval.startMillis);
            long end = Math.min(rangeEnd, interval.endMillis);
            if (end > start) spans.add(new long[]{start, end});
        }
        if (spans.isEmpty()) return 0L;
        Collections.sort(spans, new Comparator<long[]>() {
            @Override public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
        });
        long total = 0L, start = spans.get(0)[0], end = spans.get(0)[1];
        for (int i = 1; i < spans.size(); i++) {
            long[] next = spans.get(i);
            if (next[0] <= end) end = Math.max(end, next[1]);
            else { total = saturatingAdd(total, end - start); start = next[0]; end = next[1]; }
        }
        return saturatingAdd(total, end - start);
    }
}
