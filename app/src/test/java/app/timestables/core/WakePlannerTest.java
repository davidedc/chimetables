package app.timestables.core;

import static app.timestables.core.SrsTest.LONDON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class WakePlannerTest {
    final Settings s = new Settings();

    /** {@code now} is local London time; 2 Nov 2026 is a Monday. */
    ZonedDateTime next(String now) {
        return WakePlanner.next(s.plan(), ZonedDateTime.of(LocalDateTime.parse(now), LONDON));
    }

    static String local(ZonedDateTime t) {
        return t.toLocalDateTime().toString();
    }

    @Test
    public void schoolWeekWalksThroughEachDay() {
        assertEquals("2026-11-02T07:45", local(next("2026-11-02T06:00")));
        assertEquals("2026-11-02T17:00", local(next("2026-11-02T07:45")));
        assertEquals("2026-11-02T18:45", local(next("2026-11-02T17:00:01")));
        assertEquals("2026-11-02T20:30", local(next("2026-11-02T19:00")));
        assertEquals("2026-11-03T07:45", local(next("2026-11-02T20:30")));
        assertEquals("2026-11-03T15:45", local(next("2026-11-03T08:00")));
        assertEquals("2026-11-03T18:00", local(next("2026-11-03T16:00")));
        assertEquals("2026-11-05T15:35", local(next("2026-11-05T08:00")));
        assertEquals("2026-11-05T17:15", local(next("2026-11-05T15:35")));
        assertEquals("2026-11-06T18:50", local(next("2026-11-06T17:15")));
        // Friday night to Saturday's late start, Sunday evening to Monday's early one.
        assertEquals("2026-11-07T10:00", local(next("2026-11-06T20:30")));
        assertEquals("2026-11-07T12:30", local(next("2026-11-07T10:00")));
        assertEquals("2026-11-09T07:45", local(next("2026-11-08T18:00")));
    }

    @Test
    public void holidayWeeksUseTheirOwnTimes() {
        s.week = Settings.HOLIDAY;
        assertEquals("2026-11-02T10:00", local(next("2026-11-02T06:00")));
        assertEquals("2026-11-03T10:00", local(next("2026-11-02T18:00")));
    }

    @Test
    public void theSchoolPlanAllowsCallsUntilItsLaterQuietHours() {
        assertFalse(s.school.isQuiet(LocalTime.of(20, 30)));
        assertTrue(s.holiday.isQuiet(LocalTime.of(20, 30)));
        assertEquals(4, s.school.callsOn(DayOfWeek.MONDAY));
    }

    @Test
    public void timesInsideQuietHoursAreSkipped() {
        s.week = Settings.HOLIDAY;
        s.holiday = WeekPlan.everyDay(Arrays.asList(LocalTime.of(6, 30), LocalTime.of(12, 0), LocalTime.of(20, 0)),
                LocalTime.of(19, 30), LocalTime.of(7, 0));
        assertEquals("2026-10-21T12:00", local(next("2026-10-20T13:00")));
        assertEquals(1, s.holiday.callsOn(DayOfWeek.TUESDAY));
    }

    @Test
    public void aDayWithoutCallsIsSkipped() {
        s.school.set(DayOfWeek.TUESDAY);
        assertEquals("2026-11-04T07:45", local(next("2026-11-02T20:30")));
    }

    @Test
    public void quietHoursCanWrapMidnightOrNotExist() {
        LocalTime start = LocalTime.of(19, 30), end = LocalTime.of(7, 0);
        assertTrue(WakePlanner.isQuiet(LocalTime.of(19, 30), start, end));
        assertTrue(WakePlanner.isQuiet(LocalTime.of(23, 0), start, end));
        assertTrue(WakePlanner.isQuiet(LocalTime.of(6, 59), start, end));
        assertFalse(WakePlanner.isQuiet(LocalTime.of(7, 0), start, end));
        assertFalse(WakePlanner.isQuiet(LocalTime.of(12, 0), start, end));
        assertTrue(WakePlanner.isQuiet(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(14, 0)));
        assertFalse(WakePlanner.isQuiet(LocalTime.of(3, 0), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT));
    }

    @Test
    public void allTimesQuietOrNoTimesMeansNoWake() {
        s.school = WeekPlan.everyDay(Collections.singletonList(LocalTime.of(21, 30)), LocalTime.of(21, 0), LocalTime.of(7, 0));
        assertNull(next("2026-10-20T12:00"));
        s.school = new WeekPlan(LocalTime.of(21, 0), LocalTime.of(7, 0));
        assertNull(next("2026-10-20T12:00"));
    }

    @Test
    public void wakeInsideSpringForwardGapStillFires() {
        // 29 Mar 2026 01:00 GMT -> 02:00 BST; a 01:30 wake becomes 02:30 BST.
        s.school = WeekPlan.everyDay(Collections.singletonList(LocalTime.of(1, 30)), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);
        assertEquals("2026-03-29T02:30", local(next("2026-03-28T23:00")));
    }

    @Test
    public void aDayCanCopyTheDayBefore() {
        WeekPlan p = Settings.defaultSchool();
        assertTrue(p.sameAsPreviousDay(DayOfWeek.SUNDAY));
        assertFalse(p.sameAsPreviousDay(DayOfWeek.TUESDAY));
        p.copyPreviousDay(DayOfWeek.TUESDAY);
        assertEquals(p.on(DayOfWeek.MONDAY), p.on(DayOfWeek.TUESDAY));
        assertTrue(p.sameAsPreviousDay(DayOfWeek.TUESDAY));
        // A copy, not the same list: changing Tuesday leaves Monday alone.
        p.remove(DayOfWeek.TUESDAY, LocalTime.of(7, 45));
        assertEquals(4, p.on(DayOfWeek.MONDAY).size());
    }

    @Test
    public void timesStaySortedWithoutDuplicates() {
        WeekPlan p = new WeekPlan(LocalTime.of(21, 0), LocalTime.of(7, 0));
        assertTrue(p.add(DayOfWeek.MONDAY, LocalTime.of(17, 0)));
        assertTrue(p.add(DayOfWeek.MONDAY, LocalTime.of(7, 45)));
        assertFalse(p.add(DayOfWeek.MONDAY, LocalTime.of(17, 0)));
        assertEquals(Arrays.asList(LocalTime.of(7, 45), LocalTime.of(17, 0)), p.on(DayOfWeek.MONDAY));
        for (int h = 8; h < 20; h++) p.add(DayOfWeek.MONDAY, LocalTime.of(h, 0));
        assertEquals(WeekPlan.MAX_PER_DAY, p.on(DayOfWeek.MONDAY).size());
    }
}
