package app.timestables.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * All cards the child has ever had, keyed by id, plus the daily new-card counter, per-day history
 * (for streaks) and earned badges. Cards for tables that are later deselected keep their progress
 * but are left out of sessions.
 */
public final class Deck {
    /** Order in which tables are introduced, easiest first. */
    static final int[] TABLE_ORDER = {2, 10, 5, 3, 4, 11, 9, 6, 7, 8, 12, 1};
    static final int MIN_FACTOR = 2;

    public final Map<String, Card> cards = new LinkedHashMap<>();
    /** Local day number and count of NEW cards first shown that day. */
    public long newDay;
    public int newCount;
    /** Local day number -> what happened that day. */
    public final TreeMap<Long, DayStats> days = new TreeMap<>();
    /** Badge id -> when it was earned (epoch millis). */
    public final Map<String, Long> badges = new LinkedHashMap<>();
    /** Current and best run of quick (<= 3 s) right answers in a row. */
    public int fastRun;
    public int fastRunBest;
    /** Celebration stickers already shown (collected), by file name. */
    public final Set<String> stickers = new java.util.LinkedHashSet<>();

    public static final class DayStats {
        public int sessions;
        public int answers;
        public int correct;
        public int perfect;
        public boolean morning;
    }

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

    /** Updates the quick-answer run after each answer. */
    public void recordAnswer(Rating rating, long revealMs) {
        if (rating != Rating.AGAIN && revealMs > 0 && revealMs <= Badges.SPEEDY_MS) {
            fastRun++;
            fastRunBest = Math.max(fastRunBest, fastRun);
        } else {
            fastRun = 0;
        }
    }

    public void recordSession(long day, int answers, int correct, boolean perfect, boolean morning) {
        DayStats d = days.get(day);
        if (d == null) {
            d = new DayStats();
            days.put(day, d);
        }
        d.sessions++;
        d.answers += answers;
        d.correct += correct;
        if (perfect) d.perfect++;
        d.morning |= morning;
    }

    /**
     * Days in a row with at least one session, ending today, or yesterday if today has none yet
     * (so the streak doesn't look broken before the first session of the day).
     */
    public int streak(long today) {
        long day = today;
        if (!practised(day)) day--;
        int n = 0;
        while (practised(day)) {
            n++;
            day--;
        }
        return n;
    }

    private boolean practised(long day) {
        DayStats d = days.get(day);
        return d != null && d.sessions > 0;
    }

    public int totalSessions() {
        int n = 0;
        for (DayStats d : days.values()) n += d.sessions;
        return n;
    }

    public int totalPerfect() {
        int n = 0;
        for (DayStats d : days.values()) n += d.perfect;
        return n;
    }

    public int morningDays() {
        int n = 0;
        for (DayStats d : days.values()) if (d.morning) n++;
        return n;
    }

    public void reset() {
        cards.clear();
        newDay = 0;
        newCount = 0;
        days.clear();
        badges.clear();
        fastRun = 0;
        fastRunBest = 0;
        stickers.clear();
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
            if (c.tricky) s.tricky++;
            if (c.isMastered()) s.mastered++;
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
        public int tricky;
        public int answers;
        public int correct;

        public int accuracyPercent() {
            return answers == 0 ? 0 : Math.round(100f * correct / answers);
        }
    }
}
