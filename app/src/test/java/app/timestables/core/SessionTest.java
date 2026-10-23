package app.timestables.core;

import static app.timestables.core.SrsTest.LONDON;
import static app.timestables.core.SrsTest.at;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class SessionTest {
    final Srs srs = new Srs(LONDON);
    final Settings settings = new Settings();

    Session plan(Deck deck, long now) {
        return Session.plan(deck, srs, deck.active(settings.tables, settings.maxFactor),
                settings.sessionSize, settings.newPerDay, now);
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
    public void firstSessionIntroducesFiveNewCards() {
        Deck deck = new Deck();
        Session s = plan(deck, at("2026-10-20T07:45"));
        assertEquals(5, s.planned());
        assertEquals("2x2", s.current().id());
        playAll(s, Rating.GOOD, at("2026-10-20T07:46"));
        assertEquals(5, s.finished());
        assertEquals(5, deck.newCount);
    }

    @Test
    public void dailyNewCapStopsFurtherNewCardsAndLaterTodayCardsComeBack() {
        Deck deck = new Deck();
        playAll(plan(deck, at("2026-10-20T07:45")), Rating.GOOD, at("2026-10-20T07:46"));

        // 12:30: the five learning cards are due again; no new cards allowed today.
        Session noon = plan(deck, at("2026-10-20T12:30"));
        assertEquals(5, noon.planned());
        for (Card c : deck.active(settings.tables, 10).subList(0, 5)) assertEquals(Card.State.LEARNING, c.state);
        playAll(noon, Rating.GOOD, at("2026-10-20T12:31"));

        // 16:00: everything graduated to tomorrow and the cap is used up.
        long four = at("2026-10-20T16:00");
        assertTrue(plan(deck, four).isEmpty());
        assertTrue(Session.nothingToDo(deck, srs, deck.active(settings.tables, 10), settings.newPerDay, four));

        // Next morning: 5 reviews fill the session; new cards wait.
        Session tomorrow = plan(deck, at("2026-10-21T07:45"));
        assertEquals(5, tomorrow.planned());
        assertEquals(Card.State.REVIEW, tomorrow.current().state);
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

        List<String> shown = playAll(plan(deck, now), Rating.GOOD, now);
        assertEquals(5, shown.size());
        assertEquals(older.id(), shown.get(0));
        assertEquals(newer.id(), shown.get(1));
        assertEquals("2x2", shown.get(2));
        assertFalse(shown.contains(future.id()));
    }

    @Test
    public void againComesBackTwoCardsLaterAtMostThreeTimes() {
        Deck deck = new Deck();
        long now = at("2026-10-20T07:45");
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
        Deck deck = new Deck();
        long now = at("2026-10-20T07:45");
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
        Deck deck = new Deck();
        assertEquals(3, plan(deck, at("2026-10-20T07:45")).planned());
    }
}
