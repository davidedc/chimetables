package app.timestables.core;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * One kind of week (school weeks, holiday weeks): the wake-up times for each day and the quiet
 * hours, when there are no calls and no playing.
 */
public final class WeekPlan {
    /** Upper limit on calls per day, so the parent screen stays tidy. */
    public static final int MAX_PER_DAY = 8;

    private final Map<DayOfWeek, List<LocalTime>> days = new EnumMap<>(DayOfWeek.class);
    public LocalTime quietStart;
    public LocalTime quietEnd;

    public WeekPlan(LocalTime quietStart, LocalTime quietEnd) {
        this.quietStart = quietStart;
        this.quietEnd = quietEnd;
        for (DayOfWeek d : DayOfWeek.values()) days.put(d, new ArrayList<>());
    }

    /** The same times on every day of the week. */
    public static WeekPlan everyDay(List<LocalTime> times, LocalTime quietStart, LocalTime quietEnd) {
        WeekPlan p = new WeekPlan(quietStart, quietEnd);
        for (DayOfWeek d : DayOfWeek.values()) p.set(d, times);
        return p;
    }

    /** The day's times, earliest first (a read-only view). */
    public List<LocalTime> on(DayOfWeek day) {
        return Collections.unmodifiableList(days.get(day));
    }

    /** Replaces the day's times; duplicates are dropped and the rest sorted. */
    public void set(DayOfWeek day, List<LocalTime> times) {
        List<LocalTime> sorted = new ArrayList<>(new TreeSet<>(times));
        days.put(day, sorted.size() > MAX_PER_DAY ? new ArrayList<>(sorted.subList(0, MAX_PER_DAY)) : sorted);
    }

    public void set(DayOfWeek day, LocalTime... times) {
        set(day, Arrays.asList(times));
    }

    /** Adds one time to the day (no-op if it is already there or the day is full). */
    public boolean add(DayOfWeek day, LocalTime time) {
        List<LocalTime> now = days.get(day);
        if (now.contains(time) || now.size() >= MAX_PER_DAY) return false;
        List<LocalTime> next = new ArrayList<>(now);
        next.add(time);
        set(day, next);
        return true;
    }

    public void remove(DayOfWeek day, LocalTime time) {
        List<LocalTime> next = new ArrayList<>(days.get(day));
        next.remove(time);
        set(day, next);
    }

    /** Copies all of the previous day's times onto {@code day} (Monday copies Sunday). */
    public void copyPreviousDay(DayOfWeek day) {
        set(day, days.get(day.minus(1)));
    }

    public boolean sameAsPreviousDay(DayOfWeek day) {
        return days.get(day).equals(days.get(day.minus(1)));
    }

    public boolean isQuiet(LocalTime t) {
        return WakePlanner.isQuiet(t, quietStart, quietEnd);
    }

    /** Calls the day actually gets: its times outside quiet hours. */
    public int callsOn(DayOfWeek day) {
        int n = 0;
        for (LocalTime t : days.get(day)) if (!isQuiet(t)) n++;
        return n;
    }

    public WeekPlan copy() {
        WeekPlan p = new WeekPlan(quietStart, quietEnd);
        for (DayOfWeek d : DayOfWeek.values()) p.set(d, days.get(d));
        return p;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WeekPlan)) return false;
        WeekPlan p = (WeekPlan) o;
        return days.equals(p.days) && quietStart.equals(p.quietStart) && quietEnd.equals(p.quietEnd);
    }

    @Override
    public int hashCode() {
        return days.hashCode() * 31 + quietStart.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            sb.append(sb.length() == 0 ? "" : " ").append(shortName(d)).append('=').append(days.get(d));
        }
        return sb.append(" quiet=").append(quietStart).append('-').append(quietEnd).toString();
    }

    /** "mon".."sun": the keys used in settings.json. */
    public static String key(DayOfWeek d) {
        return shortName(d).toLowerCase(java.util.Locale.ROOT);
    }

    /** "Mon".."Sun". */
    public static String shortName(DayOfWeek d) {
        String n = d.name();
        return n.charAt(0) + n.substring(1, 3).toLowerCase(java.util.Locale.ROOT);
    }
}
