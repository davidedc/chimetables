package app.timestables.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

public class SrsTest {
    static final ZoneId LONDON = ZoneId.of("Europe/London");
    final Srs srs = new Srs(LONDON);

    static long at(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), LONDON).toInstant().toEpochMilli();
    }

    @Test
    public void newCardGoodIsSeenAgainLaterToday() {
        Card c = new Card(7, 8);
        long now = at("2026-10-20T07:45");
        assertFalse(srs.answer(c, Rating.GOOD, now));
        assertEquals(Card.State.LEARNING, c.state);
        assertEquals(1, c.step);
        assertEquals(now + Srs.LATER_TODAY_MS, c.dueAt);
    }

    @Test
    public void learningGoodGraduatesToOneDayDueAtLocalMidnight() {
        Card c = new Card(7, 8);
        srs.answer(c, Rating.GOOD, at("2026-10-20T07:45"));
        assertFalse(srs.answer(c, Rating.GOOD, at("2026-10-20T12:30")));
        assertEquals(Card.State.REVIEW, c.state);
        assertEquals(1, c.intervalDays);
        assertEquals(at("2026-10-21T00:00"), c.dueAt);
    }

    @Test
    public void againRepeatsWithinSessionAndThenNeedsLaterToday() {
        Card c = new Card(6, 7);
        long now = at("2026-10-20T07:45");
        assertTrue(srs.answer(c, Rating.AGAIN, now));
        assertEquals(Card.State.LEARNING, c.state);
        assertEquals(0, c.step);
        assertTrue(c.isDue(now));
        assertFalse(srs.answer(c, Rating.GOOD, now + 30_000));
        assertEquals(1, c.step);
        assertFalse(c.isDue(now + 60_000));
    }

    @Test
    public void newCardEasyGraduatesFourDaysAndRaisesEase() {
        Card c = new Card(2, 2);
        srs.answer(c, Rating.EASY, at("2026-10-20T07:45"));
        assertEquals(Card.State.REVIEW, c.state);
        assertEquals(4, c.intervalDays);
        assertEquals(Srs.START_EASE + Srs.EASE_STEP_UP, c.ease, 1e-9);
        assertEquals(at("2026-10-24T00:00"), c.dueAt);
    }

    @Test
    public void goodReviewIntervalsGrowThenCap() {
        Card c = new Card(3, 4);
        srs.answer(c, Rating.GOOD, at("2026-01-05T07:45"));
        srs.answer(c, Rating.GOOD, at("2026-01-05T12:30"));
        assertEquals(1, c.intervalDays);
        int[] expected = {3, 8, 20, 50, 90, 90};
        for (int want : expected) {
            srs.answer(c, Rating.GOOD, c.dueAt + 8 * 3600_000L);
            assertEquals(want, c.intervalDays);
        }
        assertEquals(Srs.START_EASE, c.ease, 1e-9);
    }

    @Test
    public void lapseDropsEaseRelearnsThenRestartsAtOneDay() {
        Card c = new Card(8, 7);
        srs.answer(c, Rating.EASY, at("2026-10-01T07:45"));
        srs.answer(c, Rating.GOOD, c.dueAt + 3600_000L);
        int before = c.intervalDays;
        assertTrue(before > 1);
        long now = c.dueAt + 8 * 3600_000L;
        assertTrue(srs.answer(c, Rating.AGAIN, now));
        assertEquals(Card.State.RELEARNING, c.state);
        assertEquals(1, c.lapses);
        assertEquals(Srs.START_EASE + Srs.EASE_STEP_UP - Srs.EASE_STEP_DOWN, c.ease, 1e-9);
        assertTrue(srs.answer(c, Rating.AGAIN, now + 1000));
        assertEquals(1, c.lapses);
        assertFalse(srs.answer(c, Rating.GOOD, now + 2000));
        assertEquals(Card.State.REVIEW, c.state);
        assertEquals(1, c.intervalDays);
    }

    @Test
    public void easeNeverDropsBelowMinimum() {
        Card c = new Card(9, 9);
        srs.answer(c, Rating.EASY, at("2026-10-01T07:45"));
        for (int i = 0; i < 20; i++) {
            srs.answer(c, Rating.AGAIN, c.dueAt + 1000);
            srs.answer(c, Rating.GOOD, c.dueAt + 2000);
        }
        assertEquals(Srs.MIN_EASE, c.ease, 1e-9);
        assertEquals(20, c.lapses);
    }

    @Test
    public void dayIntervalsLandOnLocalMidnightAcrossClockChange() {
        // UK clocks go back at 02:00 BST on Sunday 25 Oct 2026.
        Card c = new Card(5, 5);
        srs.answer(c, Rating.GOOD, at("2026-10-24T16:00"));
        srs.answer(c, Rating.EASY, at("2026-10-24T18:00"));
        assertEquals(4, c.intervalDays);
        assertEquals(at("2026-10-28T00:00"), c.dueAt);
        assertEquals(at("2026-10-26T00:00"), srs.startOfDayPlus(at("2026-10-24T23:59"), 2));
    }

    @Test
    public void countsSeenAndCorrect() {
        Card c = new Card(4, 6);
        long now = at("2026-10-20T07:45");
        srs.answer(c, Rating.AGAIN, now);
        srs.answer(c, Rating.GOOD, now);
        srs.answer(c, Rating.EASY, now);
        assertEquals(3, c.seen);
        assertEquals(2, c.correct);
    }
}
