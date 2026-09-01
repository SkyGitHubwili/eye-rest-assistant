package com.eyerest.app;

/**
 * V2 应用限制的数据契约。V1 仅保存/传递该模型，不据此拦截应用，避免引入不可靠的锁定逻辑。
 */
public final class AppLimit {
    public final String packageName;
    public final long dailyLimitMillis;
    public final boolean enabled;
    public final int warningMinutes;
    public final boolean strictMode;
    public final boolean temporaryUnlock;

    public AppLimit(String packageName, long dailyLimitMillis, boolean enabled,
                    int warningMinutes, boolean strictMode, boolean temporaryUnlock) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is empty");
        }
        this.packageName = packageName;
        this.dailyLimitMillis = Math.max(0L, dailyLimitMillis);
        this.enabled = enabled;
        this.warningMinutes = Math.max(0, warningMinutes);
        this.strictMode = strictMode;
        this.temporaryUnlock = temporaryUnlock;
    }

    public AppLimit(String packageName, long dailyLimitMillis, boolean enabled,
                    int warningMinutes, boolean strictMode) {
        this(packageName, dailyLimitMillis, enabled, warningMinutes, strictMode, false);
    }

    public String getPackageName() { return packageName; }
    public long getDailyLimitMillis() { return dailyLimitMillis; }
    public boolean isEnabled() { return enabled; }
    public int getWarningMinutes() { return warningMinutes; }
    public boolean isStrictMode() { return strictMode; }
    public boolean isTemporaryUnlock() { return temporaryUnlock; }

    public AppLimit withTemporaryUnlock(boolean value) {
        return new AppLimit(packageName, dailyLimitMillis, enabled, warningMinutes, strictMode, value);
    }

    public AppLimit withEnabled(boolean value) {
        return new AppLimit(packageName, dailyLimitMillis, value, warningMinutes, strictMode,
            temporaryUnlock);
    }
}
