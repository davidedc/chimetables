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
    /** Local day number when the last new sticker was collected: one new sticker a day. */
    public long stickerDay;
    /**
     * Local day number when the first sticker set was completed, which unlocks riddles and more
     * stickers (0 = not yet).
     */
    public long unlockedDay;
    /** True once she has seen the "you unlocked riddles" screens through to her first riddle. */
    public boolean unlockSeen;
    /** Riddles dealt so far, oldest first. The last one is today's (or the latest): its answer is still hidden. */
    public final List<DealtRiddle> riddles = new ArrayList<>();

    /** Practice days needed to unlock riddles when no stickers are bundled. */
    static final int UNLOCK_DAYS_WITHOUT_STICKERS = 7;

    public static final class DealtRiddle {
        public final String key;
        public final long day;
        /** She has broken its seal (after a play or in her book); until then it stays sealed everywhere. */
        public boolean opened;

        public DealtRiddle(String key, long day) {
            this.key = key;
            this.day = day;
        }
    }

    /** The newest riddle, or null. */
    public DealtRiddle latestRiddle() {
        return riddles.isEmpty() ? null : riddles.get(riddles.size() - 1);
    }

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
        recordSession(day, true, answers, correct, perfect, morning);
    }

    /** As above; {@code newSession} false adds to a session already counted (one that was resumed). */
    public void recordSession(long day, boolean newSession, int answers, int correct, boolean perfect,
                              boolean morning) {
        DayStats d = days.get(day);
        if (d == null) {
            d = new DayStats();
            days.put(day, d);
        }
        if (newSession) d.sessions++;
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

    /**
     * The stickers a celebration may show: ones not collected yet when a new one is allowed today,
     * otherwise the collected ones again. Before the unlock only the first set counts, one new sticker
     * a day. After it the extra stickers join: the first one the next day, then one every
     * {@code everyDays} days, so they stay special and the parent's supply lasts.
     */
    public List<String> stickerPool(List<String> starter, List<String> more, long today, int everyDays) {
        List<String> available = new ArrayList<>(starter);
        if (unlockedDay > 0) available.addAll(more);
        boolean extrasOwned = false;
        for (String name : more) extrasOwned |= stickers.contains(name);
        int gap = unlockedDay > 0 && extrasOwned ? Math.max(1, everyDays) : 1;
        boolean newAllowed = stickerDay == 0 || today - stickerDay >= gap;
        List<String> fresh = new ArrayList<>();
        List<String> owned = new ArrayList<>();
        for (String name : available) (stickers.contains(name) ? owned : fresh).add(name);
        if (!fresh.isEmpty() && (newAllowed || owned.isEmpty())) return fresh;
        return owned.isEmpty() ? fresh : owned;
    }

    /** Extra stickers not collected yet (for the parent's status line). */
    public int stickersLeft(List<String> more) {
        int n = 0;
        for (String name : more) if (!stickers.contains(name)) n++;
        return n;
    }

    /**
     * True when riddles should unlock now: every sticker of the first set collected, or, with no
     * stickers bundled, {@link #UNLOCK_DAYS_WITHOUT_STICKERS} days of practice.
     */
    public boolean unlockReady(List<String> starter) {
        if (unlockedDay > 0) return false;
        if (starter.isEmpty()) return practiceDays() >= UNLOCK_DAYS_WITHOUT_STICKERS;
        return stickers.containsAll(starter);
    }

    public int practiceDays() {
        int n = 0;
        for (DayStats d : days.values()) if (d.sessions > 0) n++;
        return n;
    }

    /** True when riddles are unlocked and none has been dealt today yet: the next finished session deals one. */
    public boolean riddleDue(long today) {
        return unlockedDay > 0 && (riddles.isEmpty() || riddles.get(riddles.size() - 1).day != today);
    }

    /**
     * Deals the next riddle: the first one in the bank she hasn't had, or once she has had them all,
     * the one she had longest ago. Null if the bank is empty.
     */
    public Riddle dealRiddle(List<Riddle> bank, long today) {
        if (bank.isEmpty()) return null;
        Map<String, Integer> lastDealt = new java.util.HashMap<>();
        for (int i = 0; i < riddles.size(); i++) lastDealt.put(riddles.get(i).key, i);
        Riddle pick = null;
        int oldest = Integer.MAX_VALUE;
        for (Riddle r : bank) {
            Integer at = lastDealt.get(r.key());
            if (at == null) {
                pick = r;
                break;
            }
            if (at < oldest) {
                oldest = at;
                pick = r;
            }
        }
        riddles.add(new DealtRiddle(pick.key(), today));
        return pick;
    }

    /** The riddle with this key, or null if it is no longer in the bank. */
    public static Riddle findRiddle(List<Riddle> bank, String key) {
        for (Riddle r : bank) if (r.key().equals(key)) return r;
        return null;
    }

    /** Records a sticker shown on a celebration; true if it is new to the collection. */
    public boolean collectSticker(String name, long today) {
        if (!stickers.add(name)) return false;
        stickerDay = today;
        return true;
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
        stickerDay = 0;
        unlockedDay = 0;
        unlockSeen = false;
        riddles.clear();
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
