package com.eyerest.app.ledger;

import java.time.LocalDate;
import java.util.*;

/** Calendar-week presentation data. Never changes the underlying usage ledger. */
public final class WeeklyReport {
    public static final long HOUR = 3_600_000L;
    public final LocalDate start, today;
    public final List<UsageEngine.Day> days;
    public final Summary current, previous;

    public WeeklyReport(LocalDate start, LocalDate today, List<UsageEngine.Day> days,
                        List<UsageEngine.Day> previous) {
        this.start = start;
        this.today = today;
        this.days = Collections.unmodifiableList(new ArrayList<>(days));
        this.current = new Summary(days, today);
        this.previous = new Summary(previous, today);
    }

    public static LocalDate monday(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1);
    }

    public static boolean known(UsageEngine.Day day) {
        return day.complete || day.evidence || day.total > 0;
    }

    public String dayValue(int index) {
        UsageEngine.Day day = days.get(index);
        if (LocalDate.parse(day.date).isAfter(today)) return "未到日期";
        if (!known(day)) return "无记录";
        return duration(day.total);
    }

    public String comparison(boolean average) {
        if (previous.knownDays < 7 || previous.partialDays > 0) return "上周记录不足，暂不比较";
        if (current.knownDays < current.elapsedDays || current.partialDays > 0) return "本周记录不足，暂不比较";
        long before = average ? previous.average : previous.total;
        long now = average ? current.average : current.total;
        String prefix = "上周" + duration(before) + "  ";
        if (before == 0) return prefix + (now == 0 ? "基本持平" : "本周新增使用");
        long percent = Math.round(Math.abs(now - before) * 100.0 / before);
        return prefix + (percent == 0 ? "基本持平" : (now > before ? "上升" : "下降") + percent + "%");
    }

    public String coverage() {
        if (current.knownDays == 0) return "本周暂无可读取的记录";
        if (current.knownDays < current.elapsedDays) {
            return "日均按已记录的 " + current.knownDays + " 天计算；缺失日期不计为 0";
        }
        String result = start.plusDays(6).isAfter(today) ? "截至今天，日均按 " + current.knownDays + " 天计算" : "日均按 7 天计算";
        if (current.partialDays > 0) result += " · 部分日期记录不完整";
        return result;
    }

    public static String percentage(long value, long total) {
        if (value <= 0 || total <= 0) return "0.0%";
        double percent = Math.min(100.0, value * 100.0 / total);
        if (percent < 0.1) return "<0.1%";
        return String.format(Locale.CHINA, "%.1f%%", percent);
    }

    public static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (millis > 0 && minutes == 0) return "不足1分钟";
        return minutes >= 60 ? minutes / 60 + "时" + minutes % 60 + "分" : minutes + "分钟";
    }

    public static final class Summary {
        public long total, average;
        public int knownDays, elapsedDays, partialDays;
        public final List<UsageEngine.App> byTime, byOpens;

        Summary(List<UsageEngine.Day> days, LocalDate today) {
            Map<String, UsageEngine.App> apps = new HashMap<>();
            for (UsageEngine.Day day : days) {
                LocalDate date = LocalDate.parse(day.date);
                if (date.isAfter(today)) continue;
                elapsedDays++;
                if (!known(day)) continue;
                knownDays++;
                if (date.isBefore(today) && !day.complete) partialDays++;
                total += day.total;
                for (UsageEngine.App app : day.apps.values()) {
                    UsageEngine.App sum = apps.computeIfAbsent(app.pkg, UsageEngine.App::new);
                    sum.millis += app.millis;
                    sum.opens += app.opens;
                    for (int i = 0; i < 24; i++) sum.hours[i] += app.hours[i];
                }
            }
            average = knownDays == 0 ? 0 : total / knownDays;
            byTime = new ArrayList<>(apps.values());
            byTime.removeIf(a -> a.millis <= 0);
            byTime.sort(Comparator.comparingLong((UsageEngine.App a) -> a.millis).reversed().thenComparing(a -> a.pkg));
            byOpens = new ArrayList<>(apps.values());
            byOpens.removeIf(a -> a.opens <= 0);
            byOpens.sort(Comparator.comparingInt((UsageEngine.App a) -> a.opens).reversed().thenComparing(a -> a.pkg));
        }
    }

    public static final class Scale {
        public final long maximum;
        public final List<Long> ticks = new ArrayList<>();
        public Scale(List<UsageEngine.Day> days) {
            long peak = 0;
            for (UsageEngine.Day day : days) peak = Math.max(peak, day.total);
            maximum = Math.max(14 * HOUR, ((peak + HOUR - 1) / HOUR) * HOUR);
            for (long value = 0; value < maximum; value += 5 * HOUR) ticks.add(value);
            ticks.add(maximum);
        }
        public float y(long millis, float top, float bottom) {
            return bottom - (bottom - top) * millis / maximum;
        }
        public static int hit(float x, float left, float right, int count) {
            if (count <= 0 || right <= left || x < left || x >= right) return -1;
            return Math.min(count - 1, (int) ((x - left) * count / (right - left)));
        }
    }
}
