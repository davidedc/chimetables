package app.timestables.core;

import static app.timestables.core.SrsTest.LONDON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class WakePlannerTest {
    final Settings s = new Settings();

    ZonedDateTime next(String now) {
        return WakePlanner.next(s.wakeTimes, s.quietStart, s.quietEnd,
                ZonedDateTime.of(LocalDateTime.parse(now), LONDON));
    }

    static String local(ZonedDateTime t) {
        return t.toLocalDateTime().toString();
    }

    @Test
    public void defaultScheduleWalksThroughTheDay() {
        assertEquals("2026-10-20T07:45", local(next("2026-10-20T06:00")));
        assertEquals("2026-10-20T12:30", local(next("2026-10-20T07:45")));
        assertEquals("2026-10-20T16:00", local(next("2026-10-20T12:30:01")));
        assertEquals("2026-10-20T18:00", local(next("2026-10-20T16:10")));
        assertEquals("2026-10-21T07:45", local(next("2026-10-20T18:00")));
    }

    @Test
    public void timesInsideQuietHoursAreSkipped() {
        s.wakeTimes = Arrays.asList(LocalTime.of(6, 30), LocalTime.of(12, 0), LocalTime.of(20, 0));
        assertEquals("2026-10-21T12:00", local(next("2026-10-20T13:00")));
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
        s.wakeTimes = Collections.singletonList(LocalTime.of(21, 0));
        assertNull(next("2026-10-20T12:00"));
        s.wakeTimes = Collections.emptyList();
        assertNull(next("2026-10-20T12:00"));
    }

    @Test
    public void wakeInsideSpringForwardGapStillFires() {
        // 29 Mar 2026 01:00 GMT -> 02:00 BST; a 01:30 wake becomes 02:30 BST.
        s.wakeTimes = Collections.singletonList(LocalTime.of(1, 30));
        s.quietStart = s.quietEnd = LocalTime.MIDNIGHT;
        ZonedDateTime t = next("2026-03-28T23:00");
        assertEquals("2026-03-29T02:30", local(t));
    }
}
