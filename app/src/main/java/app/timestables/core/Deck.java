package app.timestables.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * All cards the child has ever had, keyed by id, plus the daily new-card counter.
 * Cards for tables that are later deselected keep their progress but are left out of sessions.
 */
public final class Deck {
    /** Order in which tables are introduced, easiest first. */
    static final int[] TABLE_ORDER = {2, 10, 5, 3, 4, 11, 9, 6, 7, 8, 12, 1};
    static final int MIN_FACTOR = 2;

    public final Map<String, Card> cards = new LinkedHashMap<>();
    /** Local day number and count of NEW cards first shown that day. */
    public long newDay;
    public int newCount;

    /**
     * The cards for the selected tables, created on demand, in introduction order.
     * A card a x b is included when a or b is a selected table and both factors are in
     * {@code 2..maxFactor} plus the selected tables themselves (so 1 and 11/12 can be chosen).
     */
    public List<Card> active(Set<Integer> tables, int maxFactor) {
        List<Card> result = new ArrayList<>();
        for (int[] pair : pairs(tables, maxFactor)) {
            String id = Card.id(pair[0], pair[1]);
            Card card = cards.get(id);
            if (card == null) {
                card = new Card(pair[0], pair[1]);
                cards.put(id, card);
            }
            result.add(card);
        }
        return result;
    }

    static List<int[]> pairs(Set<Integer> tables, int maxFactor) {
        Set<Integer> factors = new TreeSet<>(tables);
        for (int f = MIN_FACTOR; f <= maxFactor; f++) factors.add(f);

        List<int[]> pairs = new ArrayList<>();
        for (int a : factors) {
            for (int b : factors) {
                if (tables.contains(a) || tables.contains(b)) pairs.add(new int[] {a, b});
            }
        }
        Collections.sort(pairs, Comparator
                .comparingInt((int[] p) -> groupRank(p, tables))
                .thenComparingInt(p -> other(p, tables))
                .thenComparingInt(p -> p[0] == groupTable(p, tables) ? 0 : 1));
        return pairs;
    }

    private static int rank(int table) {
        for (int i = 0; i < TABLE_ORDER.length; i++) {
            if (TABLE_ORDER[i] == table) return i;
        }
        return TABLE_ORDER.length + table;
    }

    /** The easiest selected table this pair belongs to. */
    private static int groupTable(int[] p, Set<Integer> tables) {
        if (!tables.contains(p[0])) return p[1];
        if (!tables.contains(p[1])) return p[0];
        return rank(p[0]) <= rank(p[1]) ? p[0] : p[1];
    }

    private static int groupRank(int[] p, Set<Integer> tables) {
        return rank(groupTable(p, tables));
    }

    private static int other(int[] p, Set<Integer> tables) {
        return p[0] == groupTable(p, tables) ? p[1] : p[0];
    }

    /** NEW cards still allowed today. */
    public int newAllowedToday(long today, int newPerDay) {
        int used = newDay == today ? newCount : 0;
        return Math.max(0, newPerDay - used);
    }

    /** Call when a NEW card is answered for the first time. */
    public void countNewCard(long today) {
        if (newDay != today) {
            newDay = today;
            newCount = 0;
        }
        newCount++;
    }

    public void reset() {
        cards.clear();
        newDay = 0;
        newCount = 0;
    }

    /** Parent-mode summary for a set of cards. */
    public static Stats stats(Collection<Card> cards, long now) {
        Stats s = new Stats();
        for (Card c : cards) {
            s.total++;
            if (c.state == Card.State.NEW) {
                s.unseen++;
                continue;
            }
            if (c.isDue(now)) s.due++;
            if (c.state == Card.State.REVIEW && c.intervalDays >= 21) s.mastered++;
            else s.learning++;
            s.answers += c.seen;
            s.correct += c.correct;
        }
        return s;
    }

    public static final class Stats {
        public int total;
        public int unseen;
        public int learning;
        public int mastered;
        public int due;
        public int answers;
        public int correct;

        public int accuracyPercent() {
            return answers == 0 ? 0 : Math.round(100f * correct / answers);
        }
    }
}
