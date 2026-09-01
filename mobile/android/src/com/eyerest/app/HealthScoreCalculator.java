package com.eyerest.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地、透明的健康指数。满分 100：超目标最多扣 30，连续使用最多扣 20，
 * 夜间（22:00-06:00）使用最多扣 20。它只是习惯反馈，不具有医疗含义。
 */
public final class HealthScoreCalculator {
    public static final int MAX_GOAL_PENALTY = 30;
    public static final int MAX_CONTINUOUS_PENALTY = 20;
    public static final int MAX_NIGHT_PENALTY = 20;
    public static final long FALLBACK_CONTINUOUS_REFERENCE_MILLIS = 60L * 60L * 1000L;
    public static final long NIGHT_FULL_PENALTY_MILLIS = 2L * 60L * 60L * 1000L;

    public HealthScoreCalculator() {}

    public HealthModels.HealthScore calculate(HealthModels.DayUsage today,
                                               long dailyGoalMillis,
                                               long continuousThresholdMillis) {
        if (today == null || !today.hasUsageData) {
            return new HealthModels.HealthScore(0, "暂无数据",
                "暂无真实使用数据，暂不计算健康指数。", 0, 0, 0);
        }
        long goal = Math.max(1L, dailyGoalMillis);
        int goalPenalty = today.totalUsageMillis <= goal ? 0
            : proportionalPenalty(today.totalUsageMillis - goal, goal, MAX_GOAL_PENALTY);

        long continuousReference = continuousThresholdMillis > 0L
            ? continuousThresholdMillis : FALLBACK_CONTINUOUS_REFERENCE_MILLIS;
        int continuousPenalty = 0;
        int nightPenalty = 0;
        if (today.continuousUsageAvailable) {
            continuousPenalty = today.longestContinuousMillis <= continuousReference ? 0
                : proportionalPenalty(today.longestContinuousMillis - continuousReference,
                    continuousReference, MAX_CONTINUOUS_PENALTY);
            nightPenalty = proportionalPenalty(today.nightUsageMillis,
                NIGHT_FULL_PENALTY_MILLIS, MAX_NIGHT_PENALTY);
        }

        int score = Math.max(0, 100 - goalPenalty - continuousPenalty - nightPenalty);
        String label = score >= 85 ? "优秀" : score >= 70 ? "良好"
            : score >= 50 ? "一般" : "需改善";
        List<String> reasons = new ArrayList<String>();
        if (goalPenalty == 0) reasons.add("今日总使用时间未超过目标");
        else reasons.add("今日总使用时间已超过目标");
        if (!today.continuousUsageAvailable) {
            reasons.add("连续与夜间数据暂不可用");
        } else {
            if (continuousPenalty == 0) reasons.add("连续使用控制较好");
            else reasons.add("单次连续使用时间偏长");
            if (nightPenalty > 0) reasons.add("夜间使用占用了一些休息时间");
        }
        return new HealthModels.HealthScore(score, label, join(reasons), goalPenalty,
            continuousPenalty, nightPenalty);
    }

    private static int proportionalPenalty(long numerator, long denominator, int maximum) {
        if (numerator <= 0L || denominator <= 0L || maximum <= 0) return 0;
        double ratio = (double) numerator / (double) denominator;
        return Math.max(0, Math.min(maximum, (int) Math.ceil(ratio * maximum)));
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) result.append('；');
            result.append(values.get(index));
        }
        if (result.length() > 0) result.append('。');
        return result.toString();
    }
}
