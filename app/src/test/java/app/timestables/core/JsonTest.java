package app.timestables.core;

import static app.timestables.core.SrsTest.LONDON;
import static app.timestables.core.SrsTest.at;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
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

        deck.unlockedDay = 20_001;
        deck.unlockSeen = true;
        deck.riddles.add(new Deck.DealtRiddle("abc1", 20_001));
        deck.riddles.get(0).opened = true;
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
        assertEquals(20_001, back.unlockedDay);
        assertTrue(back.unlockSeen);
        assertEquals("abc1", back.riddles.get(0).key);
        assertEquals(20_001, back.riddles.get(0).day);
        assertTrue(back.riddles.get(0).opened);
    }

    @Test
    public void settingsRoundTrip() throws Exception {
        Settings s = new Settings();
        s.tables = new TreeSet<>(Arrays.asList(3, 7, 12));
        s.maxFactor = 12;
        s.week = Settings.HOLIDAY;
        s.school.set(DayOfWeek.TUESDAY, LocalTime.of(8, 5), LocalTime.of(17, 0));
        s.school.set(DayOfWeek.SUNDAY);
        s.holiday.quietStart = LocalTime.of(20, 0);
        s.sessionSize = 7;
        s.volumePercent = 55;
        Settings back = Json.settingsFromJson(Json.settingsToJson(s));
        assertEquals(s.tables, back.tables);
        assertEquals(12, back.maxFactor);
        assertEquals(Settings.HOLIDAY, back.week);
        assertEquals(s.school, back.school);
        assertEquals(s.holiday, back.holiday);
        assertEquals(Arrays.asList(LocalTime.of(8, 5), LocalTime.of(17, 0)), back.school.on(DayOfWeek.TUESDAY));
        assertTrue(back.school.on(DayOfWeek.SUNDAY).isEmpty());
        assertEquals(7, back.sessionSize);
        assertEquals(55, back.volumePercent);
    }

    @Test
    public void missingSettingsFallBackToDefaults() throws Exception {
        Settings back = Json.settingsFromJson("{\"sessionSize\": 8}");
        assertEquals(8, back.sessionSize);
        assertEquals(new Settings().school, back.school);
        assertEquals(new Settings().holiday, back.holiday);
        assertEquals(Settings.SCHOOL, back.week);
        assertEquals(new Settings().tables, back.tables);
    }

    @Test
    public void aPlanCanBeChangedOneDayAtATime() throws Exception {
        Settings s = new Settings();
        Json.settingsFromJson("{\"week\":\"holiday\",\"school\":{\"mon\":[\"08:00\"],\"quietStart\":\"21:30\"}}", s);
        assertEquals(Settings.HOLIDAY, s.week);
        assertEquals(Arrays.asList(LocalTime.of(8, 0)), s.school.on(DayOfWeek.MONDAY));
        assertEquals(new Settings().school.on(DayOfWeek.TUESDAY), s.school.on(DayOfWeek.TUESDAY));
        assertEquals(LocalTime.of(21, 30), s.school.quietStart);
        assertEquals(LocalTime.of(7, 0), s.school.quietEnd);
    }

    @Test(expected = org.json.JSONException.class)
    public void anUnknownWeekIsRefused() throws Exception {
        Json.settingsFromJson("{\"week\":\"summer\"}");
    }

    @Test
    public void settingsFromBeforeWeekPlansBecomeTheHolidayPlanInUse() throws Exception {
        Settings back = Json.settingsFromJson("{\"version\":1,\"wakeTimes\":[\"12:30\",\"16:00\",\"18:00\",\"07:45\"],"
                + "\"quietStart\":\"19:30\",\"quietEnd\":\"07:00\",\"newPerDay\":15}");
        assertEquals(Settings.HOLIDAY, back.week);
        List<LocalTime> sorted = Arrays.asList(LocalTime.of(7, 45), LocalTime.of(12, 30), LocalTime.of(16, 0), LocalTime.of(18, 0));
        for (DayOfWeek d : DayOfWeek.values()) assertEquals(sorted, back.holiday.on(d));
        assertEquals(LocalTime.of(19, 30), back.holiday.quietStart);
        assertEquals(new Settings().school, back.school);
        assertEquals(15, back.newPerDay);
    }
}
