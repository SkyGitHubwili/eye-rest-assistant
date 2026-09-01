package com.eyerest.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 健康使用模块的本地设置。设置与现有应用共用 settings 偏好文件，但键名独立。 */
public final class HealthSettings {
    public static final String PREFS_NAME = "settings";
    public static final String KEY_DAILY_GOAL_MINUTES = "health_daily_goal_minutes";
    public static final String KEY_CONTINUOUS_REMINDER_MINUTES =
        "health_continuous_reminder_minutes";
    public static final int DEFAULT_DAILY_GOAL_MINUTES = 240;
    public static final int DEFAULT_CONTINUOUS_REMINDER_MINUTES = 45;
    public static final int REMINDER_OFF = 0;

    private final SharedPreferences preferences;

    public HealthSettings(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        preferences = context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getDailyGoalMinutes() {
        int value = preferences.getInt(KEY_DAILY_GOAL_MINUTES, DEFAULT_DAILY_GOAL_MINUTES);
        return clamp(value, 1, 24 * 60);
    }

    /** 目标以分钟保存；V1 只作为健康目标，不执行系统锁定。 */
    public void setDailyGoalMinutes(int minutes) {
        int value = clamp(minutes, 1, 24 * 60);
        preferences.edit().putInt(KEY_DAILY_GOAL_MINUTES, value).apply();
    }

    public long getDailyGoalMillis() { return getDailyGoalMinutes() * 60_000L; }

    public int getContinuousReminderMinutes() {
        int value = preferences.getInt(KEY_CONTINUOUS_REMINDER_MINUTES,
            DEFAULT_CONTINUOUS_REMINDER_MINUTES);
        return normalizeReminder(value);
    }

    /** 允许值：关闭、30、45、60、90 分钟。非法值回退默认值。 */
    public void setContinuousReminderMinutes(int minutes) {
        preferences.edit().putInt(KEY_CONTINUOUS_REMINDER_MINUTES,
            normalizeReminderForWrite(minutes)).apply();
    }

    public long getContinuousReminderMillis() {
        return getContinuousReminderMinutes() * 60_000L;
    }

    public boolean isContinuousReminderEnabled() {
        return getContinuousReminderMinutes() > REMINDER_OFF;
    }

    public SharedPreferences getPreferences() { return preferences; }

    public static boolean isValidReminderMinutes(int minutes) {
        return minutes == REMINDER_OFF || minutes == 30 || minutes == 45
            || minutes == 60 || minutes == 90;
    }

    private static int normalizeReminder(int minutes) {
        return isValidReminderMinutes(minutes) ? minutes : DEFAULT_CONTINUOUS_REMINDER_MINUTES;
    }

    private static int normalizeReminderForWrite(int minutes) {
        // Keep the explicit "off" choice; all other unsupported values use a safe default.
        return isValidReminderMinutes(minutes) ? minutes : DEFAULT_CONTINUOUS_REMINDER_MINUTES;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
