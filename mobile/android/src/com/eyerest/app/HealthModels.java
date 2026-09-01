package com.eyerest.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 健康使用模块使用的不可变数据模型；本文件不依赖 Android，便于纯 Java 测试。 */
public final class HealthModels {
    private HealthModels() {}

    /** UsageEvents.Event 的最小、可测试副本。 */
    public static final class UsageEventRecord {
        public static final int TYPE_FOREGROUND = 1;
        public static final int TYPE_BACKGROUND = 2;
        public static final int TYPE_SCREEN_INTERACTIVE = 15;
        public static final int TYPE_SCREEN_NON_INTERACTIVE = 16;
        public static final int TYPE_KEYGUARD_SHOWN = 17;
        public static final int TYPE_KEYGUARD_HIDDEN = 18;
        public static final int TYPE_ACTIVITY_STOPPED = 23;
        public static final int TYPE_DEVICE_SHUTDOWN = 26;
        public static final int TYPE_DEVICE_STARTUP = 27;

        public final String packageName;
        public final long timestampMillis;
        public final int eventType;

        public UsageEventRecord(String packageName, long timestampMillis, int eventType) {
            this.packageName = packageName == null ? "" : packageName;
            this.timestampMillis = timestampMillis;
            this.eventType = eventType;
        }

        public String getPackageName() { return packageName; }
        public long getTimestampMillis() { return timestampMillis; }
        public int getEventType() { return eventType; }
        public boolean isForeground() { return eventType == TYPE_FOREGROUND; }
        public boolean isBackground() {
            return eventType == TYPE_BACKGROUND || eventType == TYPE_ACTIVITY_STOPPED;
        }
        public boolean isHardBreak() {
            return eventType == TYPE_SCREEN_NON_INTERACTIVE
                || eventType == TYPE_KEYGUARD_SHOWN
                || eventType == TYPE_DEVICE_SHUTDOWN;
        }
    }

    /** UsageStats 的最小副本。usageMillis 始终来自系统统计。 */
    public static final class AppUsageStatRecord {
        public final String packageName;
        public final long usageMillis;
        public final long lastTimeUsedMillis;

        public AppUsageStatRecord(String packageName, long usageMillis, long lastTimeUsedMillis) {
            this.packageName = packageName == null ? "" : packageName;
            this.usageMillis = Math.max(0L, usageMillis);
            this.lastTimeUsedMillis = Math.max(0L, lastTimeUsedMillis);
        }

        public String getPackageName() { return packageName; }
        public long getUsageMillis() { return usageMillis; }
        public long getLastTimeUsedMillis() { return lastTimeUsedMillis; }
    }

    /** PackageManager 元数据；找不到已卸载 App 时，名称回退为包名。 */
    public static final class AppMetadata {
        public final String packageName;
        public final String appName;
        public final boolean installed;
        public final boolean userFacing;

        public AppMetadata(String packageName, String appName, boolean installed, boolean userFacing) {
            this.packageName = packageName == null ? "" : packageName;
            this.appName = appName == null || appName.trim().isEmpty() ? this.packageName : appName;
            this.installed = installed;
            this.userFacing = userFacing;
        }

        public String getPackageName() { return packageName; }
        public String getAppName() { return appName; }
        public boolean isInstalled() { return installed; }
        public boolean isUserFacing() { return userFacing; }
    }

    /** 从 UsageEvents 估算出的单次前台区间。 */
    public static final class UsageInterval {
        public final String packageName;
        public final long startMillis;
        public final long endMillis;
        public final boolean openAtRangeEnd;

        public UsageInterval(String packageName, long startMillis, long endMillis,
                             boolean openAtRangeEnd) {
            this.packageName = packageName == null ? "" : packageName;
            this.startMillis = startMillis;
            this.endMillis = Math.max(startMillis, endMillis);
            this.openAtRangeEnd = openAtRangeEnd;
        }

        public String getPackageName() { return packageName; }
        public long getStartMillis() { return startMillis; }
        public long getEndMillis() { return endMillis; }
        public long getDurationMillis() { return Math.max(0L, endMillis - startMillis); }
        public boolean isOpenAtRangeEnd() { return openAtRangeEnd; }
    }

    public static final class ContinuousUsage {
        public final long longestMillis;
        public final long currentMillis;
        public final long currentStartMillis;
        public final boolean available;

        public ContinuousUsage(long longestMillis, long currentMillis,
                               long currentStartMillis, boolean available) {
            this.longestMillis = Math.max(0L, longestMillis);
            this.currentMillis = Math.max(0L, currentMillis);
            this.currentStartMillis = currentMillis > 0L ? Math.max(0L, currentStartMillis) : 0L;
            this.available = available;
        }

        public long getLongestMillis() { return longestMillis; }
        public long getCurrentMillis() { return currentMillis; }
        public long getCurrentStartMillis() { return currentStartMillis; }
        public boolean isAvailable() { return available; }
    }

    public static final class AppUsage {
        public final String packageName;
        public final String appName;
        public final long usageMillis;
        public final int launchCount;
        public final boolean launchCountAvailable;
        public final boolean installed;
        public final boolean userFacing;
        public final long lastTimeUsedMillis;

        public AppUsage(String packageName, String appName, long usageMillis, int launchCount,
                        boolean launchCountAvailable, boolean installed, boolean userFacing,
                        long lastTimeUsedMillis) {
            this.packageName = packageName == null ? "" : packageName;
            this.appName = appName == null || appName.trim().isEmpty() ? this.packageName : appName;
            this.usageMillis = Math.max(0L, usageMillis);
            this.launchCount = Math.max(0, launchCount);
            this.launchCountAvailable = launchCountAvailable;
            this.installed = installed;
            this.userFacing = userFacing;
            this.lastTimeUsedMillis = Math.max(0L, lastTimeUsedMillis);
        }

        public String getPackageName() { return packageName; }
        public String getAppName() { return appName; }
        public long getUsageMillis() { return usageMillis; }
        public int getLaunchCount() { return launchCount; }
        public boolean isLaunchCountAvailable() { return launchCountAvailable; }
        public boolean isInstalled() { return installed; }
        public boolean isUserFacing() { return userFacing; }
        public long getLastTimeUsedMillis() { return lastTimeUsedMillis; }
    }

    public static final class DayUsage {
        public final long dayStartMillis;
        public final long rangeEndMillis;
        public final long totalUsageMillis;
        public final long longestContinuousMillis;
        public final long currentContinuousMillis;
        public final long currentContinuousStartMillis;
        public final long nightUsageMillis;
        public final int totalLaunchCount;
        public final boolean launchCountsAvailable;
        public final boolean continuousUsageAvailable;
        public final boolean hasUsageData;
        public final List<AppUsage> apps;

        public DayUsage(long dayStartMillis, long rangeEndMillis, long totalUsageMillis,
                        long longestContinuousMillis, long currentContinuousMillis,
                        long currentContinuousStartMillis, long nightUsageMillis,
                        int totalLaunchCount, boolean launchCountsAvailable,
                        boolean continuousUsageAvailable, boolean hasUsageData,
                        List<AppUsage> apps) {
            this.dayStartMillis = dayStartMillis;
            this.rangeEndMillis = Math.max(dayStartMillis, rangeEndMillis);
            this.totalUsageMillis = Math.max(0L, totalUsageMillis);
            this.longestContinuousMillis = Math.max(0L, longestContinuousMillis);
            this.currentContinuousMillis = Math.max(0L, currentContinuousMillis);
            this.currentContinuousStartMillis = currentContinuousMillis > 0L
                ? Math.max(0L, currentContinuousStartMillis) : 0L;
            this.nightUsageMillis = Math.max(0L, nightUsageMillis);
            this.totalLaunchCount = Math.max(0, totalLaunchCount);
            this.launchCountsAvailable = launchCountsAvailable;
            this.continuousUsageAvailable = continuousUsageAvailable;
            this.hasUsageData = hasUsageData;
            this.apps = immutable(apps);
        }

        public long getDayStartMillis() { return dayStartMillis; }
        public long getRangeEndMillis() { return rangeEndMillis; }
        public long getTotalUsageMillis() { return totalUsageMillis; }
        public long getLongestContinuousMillis() { return longestContinuousMillis; }
        public long getCurrentContinuousMillis() { return currentContinuousMillis; }
        public long getCurrentContinuousStartMillis() { return currentContinuousStartMillis; }
        public long getNightUsageMillis() { return nightUsageMillis; }
        public int getTotalLaunchCount() { return totalLaunchCount; }
        public boolean isLaunchCountsAvailable() { return launchCountsAvailable; }
        public boolean isContinuousUsageAvailable() { return continuousUsageAvailable; }
        public boolean hasUsageData() { return hasUsageData; }
        public List<AppUsage> getApps() { return apps; }
    }

    public static final class HealthScore {
        public final int score;
        public final String label;
        public final String explanation;
        public final int goalPenalty;
        public final int continuousPenalty;
        public final int nightPenalty;

        public HealthScore(int score, String label, String explanation, int goalPenalty,
                           int continuousPenalty, int nightPenalty) {
            this.score = Math.max(0, Math.min(100, score));
            this.label = label == null ? "" : label;
            this.explanation = explanation == null ? "" : explanation;
            this.goalPenalty = Math.max(0, goalPenalty);
            this.continuousPenalty = Math.max(0, continuousPenalty);
            this.nightPenalty = Math.max(0, nightPenalty);
        }

        public int getScore() { return score; }
        public String getLabel() { return label; }
        public String getExplanation() { return explanation; }
        public int getGoalPenalty() { return goalPenalty; }
        public int getContinuousPenalty() { return continuousPenalty; }
        public int getNightPenalty() { return nightPenalty; }
    }

    public static final class HealthSnapshot {
        public final DayUsage today;
        public final DayUsage yesterday;
        /** 从最早日期到今天。 */
        public final List<DayUsage> last7Days;
        /** 从最早日期到上一个 7 日周期的最后一天。 */
        public final List<DayUsage> previous7Days;
        public final List<AppUsage> topApps;
        public final HealthScore healthScore;
        public final long generatedAtMillis;
        public final boolean hasData;

        public HealthSnapshot(DayUsage today, DayUsage yesterday, List<DayUsage> last7Days,
                              List<DayUsage> previous7Days, List<AppUsage> topApps,
                              HealthScore healthScore, long generatedAtMillis, boolean hasData) {
            this.today = today;
            this.yesterday = yesterday;
            this.last7Days = immutable(last7Days);
            this.previous7Days = immutable(previous7Days);
            this.topApps = immutable(topApps);
            this.healthScore = healthScore;
            this.generatedAtMillis = generatedAtMillis;
            this.hasData = hasData;
        }

        public DayUsage getToday() { return today; }
        public DayUsage getYesterday() { return yesterday; }
        public List<DayUsage> getLast7Days() { return last7Days; }
        public List<DayUsage> getPrevious7Days() { return previous7Days; }
        public List<AppUsage> getTopApps() { return topApps; }
        public HealthScore getHealthScore() { return healthScore; }
        public long getGeneratedAtMillis() { return generatedAtMillis; }
        public boolean hasData() { return hasData; }
    }

    public static final class AppDetail {
        public final AppUsage app;
        public final long todayMillis;
        public final long yesterdayMillis;
        /** 从最早日期到今天。 */
        public final List<DayUsage> last7Days;
        public final long averageMillis;
        public final long peakDayStartMillis;
        public final long peakMillis;
        public final int todayLaunchCount;
        public final long averageSessionMillis;
        public final boolean launchCountAvailable;

        public AppDetail(AppUsage app, long todayMillis, long yesterdayMillis,
                         List<DayUsage> last7Days, long averageMillis,
                         long peakDayStartMillis, long peakMillis, int todayLaunchCount,
                         long averageSessionMillis, boolean launchCountAvailable) {
            this.app = app;
            this.todayMillis = Math.max(0L, todayMillis);
            this.yesterdayMillis = Math.max(0L, yesterdayMillis);
            this.last7Days = immutable(last7Days);
            this.averageMillis = Math.max(0L, averageMillis);
            this.peakDayStartMillis = Math.max(0L, peakDayStartMillis);
            this.peakMillis = Math.max(0L, peakMillis);
            this.todayLaunchCount = Math.max(0, todayLaunchCount);
            this.averageSessionMillis = Math.max(0L, averageSessionMillis);
            this.launchCountAvailable = launchCountAvailable;
        }

        public AppUsage getApp() { return app; }
        public long getTodayMillis() { return todayMillis; }
        public long getYesterdayMillis() { return yesterdayMillis; }
        public List<DayUsage> getLast7Days() { return last7Days; }
        public long getAverageMillis() { return averageMillis; }
        public long getPeakDayStartMillis() { return peakDayStartMillis; }
        public long getPeakMillis() { return peakMillis; }
        public int getTodayLaunchCount() { return todayLaunchCount; }
        public long getAverageSessionMillis() { return averageSessionMillis; }
        public boolean isLaunchCountAvailable() { return launchCountAvailable; }
    }

    private static <T> List<T> immutable(List<T> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }
}
