package app.timestables.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class BadgesTest {
    final Settings settings = new Settings();
    final Deck deck = new Deck();
    final List<Card> active = deck.active(settings.tables, settings.maxFactor);

    List<String> evaluate(long today, Badges.SessionResult result) {
        List<String> ids = new ArrayList<>();
        for (Badges.Badge b : Badges.evaluate(deck, active, settings, 4, today, result, today * 1000)) ids.add(b.id);
        return ids;
    }

    static Badges.SessionResult result(boolean perfect) {
        Badges.SessionResult r = new Badges.SessionResult();
        r.perfect = perfect;
        return r;
    }

    @Test
    public void firstSessionAndPerfectAreAwardedOnce() {
        deck.recordSession(100, 5, 5, true, false);
        List<String> first = evaluate(100, result(true));
        assertTrue(first.contains("first_session"));
        assertTrue(first.contains("perfect_1"));
        deck.recordSession(100, 5, 5, true, false);
        assertTrue(evaluate(100, result(true)).isEmpty());
    }

    @Test
    public void streakCountsConsecutiveDaysAndSurvivesUntilTodaysFirstSession() {
        deck.recordSession(100, 5, 4, false, false);
        deck.recordSession(101, 5, 4, false, false);
        deck.recordSession(102, 5, 4, false, false);
        assertEquals(3, deck.streak(102));
        assertEquals(3, deck.streak(103)); // nothing yet today: yesterday's streak still counts
        assertEquals(0, deck.streak(104)); // a whole day missed
        assertTrue(evaluate(102, result(false)).contains("streak_3"));
    }

    @Test
    public void aGapResetsTheStreak() {
        for (long d = 100; d < 106; d++) deck.recordSession(d, 5, 5, false, false);
        deck.recordSession(107, 5, 5, false, false);
        assertEquals(1, deck.streak(107));
        assertFalse(evaluate(107, result(false)).contains("streak_7"));
        for (long d = 108; d <= 113; d++) deck.recordSession(d, 5, 5, false, false);
        assertEquals(7, deck.streak(113));
        assertTrue(evaluate(113, result(false)).contains("streak_7"));
    }

    @Test
    public void earlyBirdNeedsFiveDifferentMornings() {
        for (long d = 100; d < 104; d++) deck.recordSession(d, 5, 5, false, true);
        deck.recordSession(103, 5, 5, false, true);
        assertFalse(evaluate(103, result(false)).contains("early_bird"));
        deck.recordSession(104, 5, 5, false, true);
        assertTrue(evaluate(104, result(false)).contains("early_bird"));
    }

    @Test
    public void fullHouseWhenEveryCallOfTheDayIsAnswered() {
        for (int i = 0; i < 3; i++) deck.recordSession(100, 5, 5, false, false);
        assertFalse(evaluate(100, result(false)).contains("full_house"));
        deck.recordSession(100, 5, 5, false, false);
        assertTrue(evaluate(100, result(false)).contains("full_house"));
    }

    @Test
    public void tableMasterWhenEveryCardOfThatTableIsMastered() {
        for (Card c : active) {
            if (c.a == 2 || c.b == 2) {
                c.state = Card.State.REVIEW;
                c.intervalDays = Srs.MASTERED_DAYS;
            }
        }
        List<String> ids = evaluate(100, null);
        assertTrue(ids.contains("table_2"));
        assertFalse(ids.contains("table_3"));
        assertEquals("2× master", Badges.byId("table_2").title);
    }

    @Test
    public void milestonesSpeedAndChampion() {
        for (Card c : active) {
            c.seen = 9;
            c.state = Card.State.REVIEW;
            c.intervalDays = 30;
        }
        deck.fastRunBest = 10;
        List<String> ids = evaluate(100, null);
        assertTrue(ids.contains("answers_100"));
        assertTrue(ids.contains("answers_1000"));
        assertTrue(ids.contains("speedy"));
        assertTrue(ids.contains("half_way"));
        assertTrue(ids.contains("champion"));
        assertTrue(ids.contains("table_12"));
    }

    @Test
    public void everyBadgeIdResolves() {
        for (Badges.Badge b : Badges.FIXED) assertEquals(b.title, Badges.byId(b.id).title);
    }
}
