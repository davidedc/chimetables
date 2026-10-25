package app.timestables.core;

import static app.timestables.core.SrsTest.LONDON;
import static app.timestables.core.SrsTest.at;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.TreeSet;

import org.junit.Test;

public class JsonTest {
    @Test
    public void deckRoundTripKeepsSchedulingState() throws Exception {
        Deck deck = new Deck();
        Srs srs = new Srs(LONDON, null);
        Card c = deck.active(new Settings().tables, 10).get(3);
        srs.answer(c, Rating.EASY, at("2026-10-20T07:45"));
        srs.answer(c, Rating.AGAIN, c.dueAt + 1000);
        deck.countNewCard(srs.dayNumber(at("2026-10-20T07:45")));
        c.tricky = true;
        c.revealMsAvg = 2345;
        deck.recordSession(20_000, 6, 5, false, true);
        deck.badges.put("first_session", 123L);
        deck.fastRunBest = 7;
        deck.collectSticker("wow.png", 20_000);

        Deck back = Json.deckFromJson(Json.deckToJson(deck));
        assertEquals(deck.cards.size(), back.cards.size());
        assertEquals(deck.newDay, back.newDay);
        assertEquals(1, back.newCount);
        Card b = back.cards.get(c.id());
        assertEquals(c.state, b.state);
        assertEquals(c.ease, b.ease, 1e-9);
        assertEquals(c.intervalDays, b.intervalDays);
        assertEquals(c.dueAt, b.dueAt);
        assertEquals(c.lapses, b.lapses);
        assertEquals(c.seen, b.seen);
        assertEquals(c.correct, b.correct);
        assertEquals(c.missedAt, b.missedAt);
        assertTrue(b.missedAt > 0);
        assertTrue(b.tricky);
        assertEquals(2345, b.revealMsAvg);
        assertEquals(1, back.days.get(20_000L).sessions);
        assertTrue(back.days.get(20_000L).morning);
        assertEquals(Long.valueOf(123L), back.badges.get("first_session"));
        assertEquals(7, back.fastRunBest);
        assertTrue(back.stickers.contains("wow.png"));
        assertEquals(20_000, back.stickerDay);
    }

    @Test
    public void settingsRoundTrip() throws Exception {
        Settings s = new Settings();
        s.tables = new TreeSet<>(Arrays.asList(3, 7, 12));
        s.maxFactor = 12;
        s.wakeTimes = Arrays.asList(LocalTime.of(8, 5), LocalTime.of(17, 0));
        s.quietStart = LocalTime.of(20, 0);
        s.sessionSize = 7;
        s.volumePercent = 55;
        Settings back = Json.settingsFromJson(Json.settingsToJson(s));
        assertEquals(s.tables, back.tables);
        assertEquals(12, back.maxFactor);
        assertEquals(s.wakeTimes, back.wakeTimes);
        assertEquals(s.quietStart, back.quietStart);
        assertEquals(s.quietEnd, back.quietEnd);
        assertEquals(7, back.sessionSize);
        assertEquals(55, back.volumePercent);
    }

    @Test
    public void missingSettingsFallBackToDefaults() throws Exception {
        Settings back = Json.settingsFromJson("{\"sessionSize\": 8}");
        assertEquals(8, back.sessionSize);
        assertEquals(new Settings().wakeTimes, back.wakeTimes);
        assertEquals(new Settings().tables, back.tables);
    }
}
