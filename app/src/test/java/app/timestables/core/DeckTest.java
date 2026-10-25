package app.timestables.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

public class DeckTest {

    static Set<Integer> tables(Integer... t) {
        return new TreeSet<>(Arrays.asList(t));
    }

    static List<String> ids(List<Card> cards) {
        String[] out = new String[cards.size()];
        for (int i = 0; i < out.length; i++) out[i] = cards.get(i).id();
        return Arrays.asList(out);
    }

    @Test
    public void defaultIsTwoToTwelveUpToTwelve() {
        Settings s = new Settings();
        List<Card> cards = new Deck().active(s.tables, s.maxFactor);
        assertEquals(121, cards.size());
        assertEquals(121, new HashSet<>(ids(cards)).size());
        assertTrue(ids(cards).contains("7x8"));
        assertTrue(ids(cards).contains("8x7"));
        assertTrue(ids(cards).contains("12x12"));
        assertTrue(ids(cards).contains("11x12"));
    }

    @Test
    public void twoToTenUpToTenGivesEightyOneCards() {
        List<Card> cards = new Deck().active(tables(2, 3, 4, 5, 6, 7, 8, 9, 10), 10);
        assertEquals(81, cards.size());
        assertTrue(ids(cards).contains("7x8"));
        assertTrue(ids(cards).contains("8x7"));
    }

    @Test
    public void sevenEightAndEightSevenAreSeparateCards() {
        Deck deck = new Deck();
        deck.active(new Settings().tables, 12);
        assertNotSame(deck.cards.get("7x8"), deck.cards.get("8x7"));
        assertEquals(56, deck.cards.get("8x7").answer());
    }

    @Test
    public void introductionStartsWithTheTwosInterleavingBothOrders() {
        Settings s = new Settings();
        List<String> ids = ids(new Deck().active(s.tables, s.maxFactor));
        assertEquals(Arrays.asList("2x2", "2x3", "3x2", "2x4", "4x2"), ids.subList(0, 5));
        // 21 "twos" cards (2x2..2x12 and 3x2..12x2), then the tens.
        assertEquals("10x3", ids.get(21));
        assertEquals("12x12", ids.get(ids.size() - 1));
    }

    @Test
    public void singleTableIncludesBothOrders() {
        List<String> ids = ids(new Deck().active(tables(5), 10));
        assertEquals(17, ids.size());
        assertTrue(ids.contains("5x7"));
        assertTrue(ids.contains("7x5"));
        assertTrue(ids.contains("5x5"));
    }

    @Test
    public void twelveTimesTableExtendsFactors() {
        List<String> ids = ids(new Deck().active(tables(12), 12));
        assertTrue(ids.contains("12x12"));
        assertTrue(ids.contains("11x12"));
        assertEquals(21, ids.size()); // 12 x 2..12 and 2..11 x 12
    }

    @Test
    public void deselectingTablesKeepsProgress() {
        Deck deck = new Deck();
        Card c = deck.active(tables(7), 10).get(0);
        c.seen = 3;
        deck.active(tables(2), 10);
        List<Card> again = deck.active(tables(7), 10);
        assertSame(c, again.get(0));
        assertEquals(3, again.get(0).seen);
    }

    @Test
    public void newCardCapResetsEachDay() {
        Deck deck = new Deck();
        assertEquals(5, deck.newAllowedToday(100, 5));
        deck.countNewCard(100);
        deck.countNewCard(100);
        assertEquals(3, deck.newAllowedToday(100, 5));
        assertEquals(5, deck.newAllowedToday(101, 5));
        deck.countNewCard(101);
        assertEquals(4, deck.newAllowedToday(101, 5));
    }

    @Test
    public void oneNewStickerADayThenCollectedOnesAgain() {
        Deck deck = new Deck();
        List<String> all = Arrays.asList("a.png", "b.png", "c.png");
        List<String> none = Arrays.asList();
        assertEquals(all, deck.stickerPool(all, none, 10, 3));
        assertTrue(deck.collectSticker("b.png", 10));
        assertEquals(Arrays.asList("b.png"), deck.stickerPool(all, none, 10, 3)); // later the same day: repeats only
        assertTrue(!deck.collectSticker("b.png", 10));
        assertEquals(Arrays.asList("a.png", "c.png"), deck.stickerPool(all, none, 11, 3)); // next day: a new one
        deck.collectSticker("a.png", 11);
        deck.collectSticker("c.png", 12);
        assertEquals(all, deck.stickerPool(all, none, 13, 3)); // all collected: any of them
        assertEquals(none, deck.stickerPool(none, none, 13, 3));
    }

    @Test
    public void extraStickersOnlyAfterTheUnlockThenEveryFewDays() {
        Deck deck = new Deck();
        List<String> starter = Arrays.asList("a.png", "b.png");
        List<String> more = Arrays.asList("more/x.png", "more/y.png", "more/z.png");
        deck.collectSticker("a.png", 10);
        deck.collectSticker("b.png", 11);
        assertEquals(starter, deck.stickerPool(starter, more, 12, 3)); // locked: the first set only
        assertTrue(deck.unlockReady(starter));
        deck.unlockedDay = 11;
        assertTrue(!deck.unlockReady(starter));
        assertEquals(starter, deck.stickerPool(starter, more, 11, 3)); // unlock day: a new one was collected already
        assertEquals(more, deck.stickerPool(starter, more, 12, 3)); // the first extra comes the next day
        deck.collectSticker("more/y.png", 12);
        assertEquals(2, deck.stickersLeft(more));
        List<String> owned = Arrays.asList("a.png", "b.png", "more/y.png");
        assertEquals(owned, deck.stickerPool(starter, more, 13, 3));
        assertEquals(owned, deck.stickerPool(starter, more, 14, 3));
        assertEquals(Arrays.asList("more/x.png", "more/z.png"), deck.stickerPool(starter, more, 15, 3)); // 3 days on
        assertEquals(Arrays.asList("more/x.png", "more/z.png"), deck.stickerPool(starter, more, 15, 1));
    }

    @Test
    public void withoutStickersRiddlesUnlockAfterAWeekOfPractice() {
        Deck deck = new Deck();
        List<String> none = Arrays.asList();
        for (long day = 1; day <= 6; day++) deck.recordSession(day, 5, 5, true, false);
        assertTrue(!deck.unlockReady(none));
        deck.recordSession(9, 5, 5, true, false);
        assertTrue(deck.unlockReady(none));
    }

    @Test
    public void oneRiddleADayInBankOrderThenTheOldestAgain() {
        Deck deck = new Deck();
        List<Riddle> bank = Arrays.asList(new Riddle("One?", "1", null, 1), new Riddle("Two?", "2", null, 1));
        assertTrue(!deck.riddleDue(10)); // still locked
        deck.unlockedDay = 10;
        assertTrue(deck.riddleDue(10));
        assertEquals("One?", deck.dealRiddle(bank, 10).question);
        assertTrue(!deck.riddleDue(10));
        assertTrue(deck.riddleDue(11));
        assertEquals("Two?", deck.dealRiddle(bank, 13).question); // missed days don't skip riddles
        assertEquals("One?", deck.dealRiddle(bank, 14).question); // bank used up: the oldest again
        assertEquals("Two?", deck.dealRiddle(bank, 15).question);
        assertEquals("Two?", Deck.findRiddle(bank, deck.riddles.get(1).key).question);
        assertEquals(null, deck.dealRiddle(Arrays.<Riddle>asList(), 16));
        deck.reset();
        assertTrue(deck.riddles.isEmpty() && deck.unlockedDay == 0);
    }

    @Test
    public void aResumedSessionAddsToTheDayWithoutCountingTwice() {
        Deck deck = new Deck();
        deck.recordSession(10, 3, 2, false, false);
        deck.recordSession(10, false, 3, 2, false, false);
        assertEquals(1, deck.days.get(10L).sessions);
        assertEquals(6, deck.days.get(10L).answers);
        assertEquals(4, deck.days.get(10L).correct);
    }
}
