package app.timestables.core;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/** Parent-configurable settings. Defaults follow the spec, with tables extended to 12 x 12. */
public final class Settings {
    public static final String SCHOOL = "school";
    public static final String HOLIDAY = "holiday";

    public Set<Integer> tables = new TreeSet<>(Arrays.asList(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
    /** Largest second factor (10 or 12). */
    public int maxFactor = 12;
    /** Which week plan is in use: {@link #SCHOOL} or {@link #HOLIDAY}. */
    public String week = SCHOOL;
    public WeekPlan school = defaultSchool();
    public WeekPlan holiday = defaultHoliday();
    public int sessionSize = 5;
    public int newPerDay = 5;
    /** Alarm-stream volume as a percentage of the device maximum. */
    public int volumePercent = 80;
    public int beepRepeats = 5;
    public int beepIntervalSeconds = 120;
    /** 0-255; applied as a fixed (non-adaptive) brightness. */
    public int brightness = 110;
    public int screenTimeoutSeconds = 30;
    /** Once riddles are unlocked, a new sticker turns up at most every this many days. */
    public int newStickerEveryDays = 3;

    /** The week plan in use. */
    public WeekPlan plan() {
        return HOLIDAY.equals(week) ? holiday : school;
    }

    public WeekPlan plan(String which) {
        return HOLIDAY.equals(which) ? holiday : school;
    }

    /** First call before school on weekdays, then one after school, one mid-evening and one before bed. */
    static WeekPlan defaultSchool() {
        WeekPlan p = new WeekPlan(LocalTime.of(21, 0), LocalTime.of(7, 0));
        p.set(DayOfWeek.MONDAY, t(7, 45), t(17, 0), t(18, 45), t(20, 30));
        p.set(DayOfWeek.TUESDAY, t(7, 45), t(15, 45), t(18, 0), t(20, 30));
        p.set(DayOfWeek.WEDNESDAY, t(7, 45), t(17, 0), t(18, 45), t(20, 30));
        p.set(DayOfWeek.THURSDAY, t(7, 45), t(15, 35), t(17, 15), t(20, 30));
        p.set(DayOfWeek.FRIDAY, t(7, 45), t(17, 15), t(18, 50), t(20, 30));
        p.set(DayOfWeek.SATURDAY, t(10, 0), t(12, 30), t(16, 0), t(18, 0));
        p.set(DayOfWeek.SUNDAY, t(10, 0), t(12, 30), t(16, 0), t(18, 0));
        return p;
    }

    static WeekPlan defaultHoliday() {
        return WeekPlan.everyDay(Arrays.asList(t(10, 0), t(12, 30), t(16, 0), t(18, 0)),
                LocalTime.of(19, 30), LocalTime.of(7, 0));
    }

    private static LocalTime t(int h, int m) {
        return LocalTime.of(h, m);
    }
}
