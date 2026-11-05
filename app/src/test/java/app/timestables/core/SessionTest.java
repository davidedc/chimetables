package app.timestables.core;

import static app.timestables.core.SrsTest.LONDON;
import static app.timestables.core.SrsTest.at;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SessionTest {
    final Srs srs = new Srs(LONDON, null);
    final Settings settings = new Settings();

    Session plan(Deck deck, long now) {
        return Session.plan(deck, srs, deck.active(settings.tables, settings.maxFactor),
                settings.sessionSize, settings.newPerDay, now);
    }

    /** Marks these cards as met (in review, 3-day interval) and due at {@code dueAt}. */
    void meet(Deck deck, long dueAt, String... ids) {
        deck.active(settings.tables, settings.maxFactor);
        for (String id : ids) {
            Card c = deck.cards.get(id);
            c.state = Card.State.REVIEW;
            c.intervalDays = 3;
            c.dueAt = dueAt;
        }
    }

    /** A deck with five met cards, all due, so a session is five reviews. */
    Deck fiveDue(long now) {
        Deck deck = new Deck();
        meet(deck, now - 3600_000L, "2x5", "2x6", "2x7", "2x8", "2x9");
        return deck;
    }

    static List<String> playAll(Session s, Rating rating, long now) {
        List<String> shown = new ArrayList<>();
        while (!s.isDone()) {
            shown.add(s.current().id());
            s.answer(rating, now);
        }
        return shown;
    }

    @Test
    public void firstSessionIntroducesTwoNewCards() {
        Deck deck = new Deck();
        Session s = plan(deck, at("2026-10-20T07:45"));
        assertEquals(2, s.planned()); // nothing met yet, so no practice cards either
        assertEquals("2x2", s.current().id());
        playAll(s, Rating.GOOD, at("2026-10-20T07:46"));
        assertEquals(2, s.finished());
        assertEquals(2, deck.newCount);
    }

    @Test
    public void dailyNewCapStopsFurtherNewCardsAndLaterTodayCardsComeBack() {
        settings.newPerDay = 2;
        Deck deck = new Deck();
        List<String> morning = playAll(plan(deck, at("2026-10-20T07:45")), Rating.GOOD, at("2026-10-20T07:46"));

        // 12:30: the two learning cards are due again; no new cards allowed today.
        Session noon = plan(deck, at("2026-10-20T12:30"));
        assertEquals(morning, playAll(noon, Rating.GOOD, at("2026-10-20T12:31")));
        for (String id : morning) assertEquals(Card.State.REVIEW, deck.cards.get(id).state);

        // 16:00: everything graduated to tomorrow and the cap is used up.
        long four = at("2026-10-20T16:00");
        assertTrue(plan(deck, four).isEmpty());
        assertTrue(Session.nothingToDo(deck, srs, deck.active(settings.tables, 10), settings.newPerDay, four));

        // Next morning: the reviews come first, then today's new cards.
        Session tomorrow = plan(deck, at("2026-10-21T07:45"));
        assertEquals(Arrays.asList("2x2", "2x3", "2x4", "2x5"), playAll(tomorrow, Rating.GOOD, at("2026-10-21T07:46")));
    }

    @Test
    public void dueCardsComeFirstOldestFirstThenNewCards() {
        Deck deck = new Deck();
        List<Card> active = deck.active(settings.tables, 10);
        long now = at("2026-10-22T07:45");
        Card older = active.get(10);
        older.state = Card.State.REVIEW;
        older.dueAt = now - 2 * 86_400_000L;
        Card newer = active.get(20);
        newer.state = Card.State.REVIEW;
        newer.dueAt = now - 86_400_000L;
        Card future = active.get(30);
        future.state = Card.State.REVIEW;
        future.dueAt = now + 86_400_000L;

        Session s = plan(deck, now);
        List<String> shown = playAll(s, Rating.GOOD, now);
        assertEquals(Arrays.asList(older.id(), newer.id(), "2x2", "2x3", future.id()), shown);
        assertTrue(s.isPractice(future)); // not due: it only fills the last place
        assertEquals(now + 86_400_000L, future.dueAt);
    }

    @Test
    public void againComesBackTwoCardsLaterAtMostThreeTimes() {
        long now = at("2026-10-20T07:45");
        Deck deck = fiveDue(now);
        Session s = plan(deck, now);
        Card first = s.current();
        s.answer(Rating.AGAIN, now);
        assertEquals(0, s.finished());
        s.answer(Rating.GOOD, now);
        s.answer(Rating.GOOD, now);
        assertEquals(first, s.current());

        s.answer(Rating.AGAIN, now);  // second show
        s.answer(Rating.GOOD, now);
        s.answer(Rating.GOOD, now);
        assertEquals(first, s.current());
        s.answer(Rating.AGAIN, now);  // third show: no more repeats this session
        List<String> rest = playAll(s, Rating.GOOD, now);
        assertFalse(rest.contains(first.id()));
        assertEquals(5, s.finished());
        assertTrue(first.isDue(now)); // still due for the next session
    }

    @Test
    public void againOnLastCardStillRepeats() {
        long now = at("2026-10-20T07:45");
        Deck deck = fiveDue(now);
        Session s = plan(deck, now);
        for (int i = 0; i < 4; i++) s.answer(Rating.GOOD, now);
        Card last = s.current();
        s.answer(Rating.AGAIN, now);
        assertEquals(last, s.current());
        s.answer(Rating.GOOD, now);
        assertTrue(s.isDone());
        assertNull(s.current());
    }

    @Test
    public void sessionSizeSettingIsRespected() {
        settings.sessionSize = 3;
        long now = at("2026-10-20T07:45");
        assertEquals(3, plan(fiveDue(now), now).planned());
    }

    @Test
    public void newCardsSkipSiblingsOfCardsAlreadyPicked() {
        long now = at("2026-10-20T07:45");
        Deck deck = new Deck();
        meet(deck, now - 3600_000L, "2x3");
        // 2x3 is due; the next new cards are 2x2, then 3x2 (its sibling, so skipped), then 2x4.
        assertEquals(Arrays.asList("2x3", "2x2", "2x4"), playAll(plan(deck, now), Rating.GOOD, now));
    }

    @Test
    public void dueSiblingsAreSplitAcrossSessions() {
        Deck deck = new Deck();
        List<Card> active = deck.active(settings.tables, settings.maxFactor);
        long now = at("2026-10-22T07:45");
        for (Card c : active) {
            if (c.id().equals("7x8") || c.id().equals("8x7")) {
                c.state = Card.State.REVIEW;
                c.intervalDays = 3;
                c.dueAt = now - 3600_000L;
            }
        }
        deck.newDay = srs.dayNumber(now);
        deck.newCount = settings.newPerDay; // no new cards: only the two due siblings compete
        List<String> first = playAll(plan(deck, now), Rating.GOOD, now);
        assertEquals(1, first.size());
        List<String> second = playAll(plan(deck, now + 1000), Rating.GOOD, now + 1000);
        assertEquals(1, second.size());
        assertFalse(first.get(0).equals(second.get(0)));
    }

    @Test
    public void perfectNeedsEveryCardRightFirstTime() {
        long now = at("2026-10-20T07:45");
        Session good = plan(fiveDue(now), now);
        playAll(good, Rating.GOOD, now);
        assertTrue(good.perfect());
        assertEquals(5, good.firstTryCorrect());

        Session mixed = plan(fiveDue(now), now);
        mixed.answer(Rating.AGAIN, now);
        playAll(mixed, Rating.GOOD, now);
        assertFalse(mixed.perfect());
        assertEquals(4, mixed.firstTryCorrect());
        assertEquals(5, mixed.firstTryCount());
        assertEquals(6, mixed.answers());
    }

    @Test
    public void aCardMissedThisSessionCannotComeBackAsEasy() {
        long now = at("2026-10-20T07:45");
        Deck deck = new Deck();
        meet(deck, now + 86_400_000L, "2x5", "2x6", "2x7"); // practice cards after the two new ones
        Session s = plan(deck, now);
        Card first = s.current();
        assertEquals(Card.State.NEW, first.state);
        s.answer(Rating.AGAIN, now);
        s.answer(Rating.GOOD, now);
        s.answer(Rating.GOOD, now);
        assertEquals(first, s.current());
        s.answer(Rating.EASY, now, 1_000);
        assertEquals(Card.State.LEARNING, first.state); // Easy would have graduated it to 3 days
        assertEquals(1, first.step);
    }

    @Test
    public void quickRightAnswersBuildARun() {
        long now = at("2026-10-20T07:45");
        Deck deck = fiveDue(now);
        Session s = plan(deck, now);
        s.answer(Rating.GOOD, now, 1_500);
        s.answer(Rating.EASY, now, 2_000);
        assertEquals(2, deck.fastRun);
        s.answer(Rating.GOOD, now, 5_000);
        assertEquals(0, deck.fastRun);
        assertEquals(2, deck.fastRunBest);
    }

    Session bonus(Deck deck, int size) {
        return Session.bonus(deck, srs, deck.active(settings.tables, settings.maxFactor), size, null);
    }

    @Test
    public void noBonusRoundBeforeAnyCardHasBeenSeen() {
        Deck deck = new Deck();
        assertFalse(Session.bonusAvailable(deck.active(settings.tables, settings.maxFactor)));
        assertTrue(bonus(deck, 5).isEmpty());
    }

    @Test
    public void bonusRoundLeavesScheduleCountersAndRunsAlone() {
        long eight = at("2026-10-20T08:00");
        Deck deck = new Deck();
        List<String> morning = Arrays.asList("2x5", "2x6", "2x7", "2x8", "2x9");
        meet(deck, eight + 86_400_000L, morning.toArray(new String[0]));
        deck.newDay = srs.dayNumber(eight);
        deck.newCount = settings.newPerDay; // today's new cards are used up
        List<Card> active = deck.active(settings.tables, settings.maxFactor);
        assertTrue(Session.nothingToDo(deck, srs, active, settings.newPerDay, eight));
        assertTrue(Session.bonusAvailable(active));

        List<String> before = new ArrayList<>();
        for (String id : morning) {
            Card c = deck.cards.get(id);
            before.add(c + " due=" + c.dueAt + " seen=" + c.seen + " correct=" + c.correct + " avg=" + c.revealMsAvg);
        }
        int newCount = deck.newCount;

        Session b = bonus(deck, 5);
        assertTrue(b.isBonus());
        assertEquals(5, b.planned());
        b.answer(Rating.AGAIN, eight, 4_000);
        b.answer(Rating.EASY, eight, 1_000);
        b.answer(Rating.EASY, eight, 1_000);
        List<String> rest = playAll(b, Rating.EASY, eight);
        assertEquals(3, rest.size()); // the missed card came back once, plus the two still waiting
        assertTrue(b.isDone());
        assertEquals(4, b.firstTryCorrect());

        List<String> after = new ArrayList<>();
        for (String id : morning) {
            Card c = deck.cards.get(id);
            after.add(c + " due=" + c.dueAt + " seen=" + c.seen + " correct=" + c.correct + " avg=" + c.revealMsAvg);
        }
        assertEquals(before, after);
        assertEquals(newCount, deck.newCount);
        assertEquals(0, deck.fastRun);
        assertTrue(Session.nothingToDo(deck, srs, active, settings.newPerDay, eight));
    }

    @Test
    public void bonusRoundPrefersTheLeastEstablishedCardsAndSkipsSiblings() {
        Deck deck = new Deck();
        long now = at("2026-10-22T07:45");
        for (Card c : deck.active(settings.tables, settings.maxFactor)) {
            int ivl;
            switch (c.id()) {
                case "9x9": ivl = 30; c.tricky = true; break;
                case "7x8": ivl = 2; break;
                case "8x7": ivl = 2; break;
                case "6x6": ivl = 5; break;
                case "3x3": ivl = 40; break;
                default: continue;
            }
            c.state = Card.State.REVIEW;
            c.intervalDays = ivl;
            c.dueAt = now + 86_400_000L;
        }
        List<String> shown = playAll(bonus(deck, 3), Rating.GOOD, now);
        assertEquals(java.util.Arrays.asList("9x9", "7x8", "6x6"), shown);
    }

    @Test
    public void aCardMissedEarlierTodayCannotComeBackAsEasyInALaterSession() {
        Deck deck = new Deck();
        Session first = plan(deck, at("2026-10-20T07:45"));
        Card missed = first.current();
        first.answer(Rating.AGAIN, at("2026-10-20T07:45"), 2_000);
        // She turns the screen off and starts again: the missed card is due straight away.
        Session again = plan(deck, at("2026-10-20T07:46"));
        assertEquals(missed, again.current());
        assertEquals(Rating.GOOD, again.answer(Rating.EASY, at("2026-10-20T07:46"), 1_000));
        assertEquals(Card.State.LEARNING, missed.state); // Easy would have graduated it to 3 days
        assertEquals(1, missed.step);

        // The next day a quick right answer is Easy again.
        Session tomorrow = plan(deck, at("2026-10-21T08:30"));
        while (tomorrow.current() != missed) tomorrow.answer(Rating.GOOD, at("2026-10-21T08:30"));
        assertEquals(Rating.EASY, tomorrow.answer(Rating.EASY, at("2026-10-21T08:31"), 1_000));
    }

    @Test
    public void partOfASessionIsRecordedOnceAndTheRestAddedLater() {
        long now = at("2026-10-20T07:45");
        Session s = plan(fiveDue(now), now);
        s.answer(Rating.GOOD, now);
        s.answer(Rating.AGAIN, now);
        assertFalse(s.recorded());
        assertEquals(2, s.unrecordedAnswers());
        assertEquals(1, s.unrecordedCorrect());
        s.markRecorded();
        assertEquals(0, s.unrecordedAnswers());
        playAll(s, Rating.GOOD, now);
        assertTrue(s.recorded());
        assertEquals(4, s.unrecordedAnswers()); // 3 more cards + the missed one again
        assertEquals(3, s.unrecordedCorrect());
    }

    /** Two cards missed this morning, and three reviews that fell due at midnight. */
    Deck missedThisMorning() {
        Deck deck = new Deck();
        meet(deck, at("2026-11-04T00:00"), "3x7", "3x8", "4x4");
        for (String id : new String[] {"9x6", "9x8"}) {
            Card c = deck.cards.get(id);
            c.state = Card.State.LEARNING;
            c.step = 0;
            c.dueAt = at("2026-11-04T07:46");
        }
        return deck;
    }

    @Test
    public void cardsStillBeingLearnedComeBeforeReviews() {
        // Her Wednesday: 9x6 and 9x8 missed at 07:46 waited behind the midnight reviews all day.
        Deck deck = missedThisMorning();
        assertEquals(Arrays.asList("9x6", "9x8", "3x7", "3x8", "4x4"),
                playAll(plan(deck, at("2026-11-04T17:00")), Rating.GOOD, at("2026-11-04T17:01")));
    }

    @Test
    public void aLearningCardDueWithinHalfAnHourCountsAsDue() {
        settings.newPerDay = 0;
        Deck deck = new Deck();
        deck.active(settings.tables, settings.maxFactor);
        Card c = deck.cards.get("4x12");
        c.state = Card.State.LEARNING;
        c.step = 1;
        c.dueAt = at("2026-11-02T19:05"); // right at 17:05, back "later today"
        List<Card> active = deck.active(settings.tables, settings.maxFactor);
        assertTrue(Session.nothingToDo(deck, srs, active, 0, at("2026-11-02T18:30")));
        assertFalse(Session.nothingToDo(deck, srs, active, 0, at("2026-11-02T18:45")));
        assertEquals(c, plan(deck, at("2026-11-02T18:45")).current());
    }

    @Test
    public void noNewCardsWhileTwoAreStillBeingLearned() {
        long now = at("2026-11-04T17:00");
        Deck deck = missedThisMorning();
        for (String id : new String[] {"3x7", "3x8", "4x4"}) deck.cards.get(id).dueAt = now + 86_400_000L;
        assertEquals("2 learning, 0 due, 0 new, 3 practice", plan(deck, now).makeup());
        // 9x6 has settled: with one card still being learned, new cards may come again.
        deck.cards.get("9x6").state = Card.State.REVIEW;
        deck.cards.get("9x6").dueAt = now + 86_400_000L;
        assertEquals("1 learning, 0 due, 2 new, 2 practice", plan(deck, now).makeup());
    }

    @Test
    public void atMostTwoNewCardsAndPracticeFillsTheRest() {
        // Her Tuesday 15:45: nothing due after two rounds of her own, so the call was five new cards.
        settings.sessionSize = 8;
        long now = at("2026-11-03T15:45");
        Deck deck = new Deck();
        meet(deck, now + 86_400_000L, "2x5", "2x6", "2x7", "2x8", "2x9", "2x10", "2x11", "2x12");
        Session s = plan(deck, now);
        assertEquals(8, s.planned());
        assertEquals("0 learning, 0 due, 2 new, 6 practice", s.makeup());
        List<Card> cards = new ArrayList<>();
        while (!s.isDone()) {
            cards.add(s.current());
            s.answer(Rating.EASY, now, 1_000);
        }
        assertEquals("2x2", cards.get(0).id());
        assertEquals("2x3", cards.get(1).id());
        assertEquals(2, deck.newCount);
        for (Card c : cards.subList(2, 8)) {
            assertTrue(s.isPractice(c));
            assertEquals(now + 86_400_000L, c.dueAt); // practice only: the schedule is untouched
            assertEquals(0, c.seen);
        }
        assertTrue(s.perfect());
    }

    @Test
    public void practiceSkipsCardsAnsweredToday() {
        long now = at("2026-11-05T17:15");
        Deck deck = new Deck();
        meet(deck, now - 3600_000L, "2x5");
        meet(deck, now + 86_400_000L, "2x6", "2x7", "2x8");
        deck.cards.get("2x6").lastSeenAt = at("2026-11-05T07:45");
        deck.newDay = srs.dayNumber(now);
        deck.newCount = settings.newPerDay;
        settings.sessionSize = 3;
        assertEquals(Arrays.asList("2x5", "2x7", "2x8"), playAll(plan(deck, now), Rating.GOOD, now));
    }

    @Test
    public void nothingToDoIsAnEmptySessionNotAPracticeOne() {
        long now = at("2026-11-05T17:15");
        Deck deck = new Deck();
        meet(deck, now + 86_400_000L, "2x5", "2x6", "2x7");
        deck.newDay = srs.dayNumber(now);
        deck.newCount = settings.newPerDay;
        assertTrue(plan(deck, now).isEmpty());
        assertTrue(Session.nothingToDo(deck, srs, deck.active(settings.tables, settings.maxFactor), settings.newPerDay, now));
    }
}
