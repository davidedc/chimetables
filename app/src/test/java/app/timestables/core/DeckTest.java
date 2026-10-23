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
    public void defaultTablesGiveEightyOneDistinctCards() {
        List<Card> cards = new Deck().active(new Settings().tables, 10);
        assertEquals(81, cards.size());
        assertEquals(81, new HashSet<>(ids(cards)).size());
        assertTrue(ids(cards).contains("7x8"));
        assertTrue(ids(cards).contains("8x7"));
    }

    @Test
    public void sevenEightAndEightSevenAreSeparateCards() {
        Deck deck = new Deck();
        deck.active(new Settings().tables, 10);
        assertNotSame(deck.cards.get("7x8"), deck.cards.get("8x7"));
        assertEquals(56, deck.cards.get("8x7").answer());
    }

    @Test
    public void introductionStartsWithTheTwosInterleavingBothOrders() {
        List<String> ids = ids(new Deck().active(new Settings().tables, 10));
        assertEquals(Arrays.asList("2x2", "2x3", "3x2", "2x4", "4x2"), ids.subList(0, 5));
        // 17 "twos" cards (2x2..2x10 and 3x2..10x2), then the tens.
        assertEquals("10x3", ids.get(17));
        assertEquals("8x8", ids.get(ids.size() - 1));
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
}
