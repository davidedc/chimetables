package app.timestables.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Achievement badges. Each is awarded once and remembered in {@link Deck#badges}. */
public final class Badges {
    public static final class Badge {
        public final String id;
        public final String emoji;
        public final String title;
        public final String description;

        Badge(String id, String emoji, String title, String description) {
            this.id = id;
            this.emoji = emoji;
            this.title = title;
            this.description = description;
        }

        @Override
        public String toString() {
            return emoji + " " + title;
        }
    }

    static final int EARLY_HOUR = 9;
    static final int EARLY_DAYS = 5;
    static final int SPEEDY_RUN = 10;
    static final long SPEEDY_MS = 3000;

    /** Fixed badges, in display order. Table badges ("7x master") are generated per table. */
    public static final List<Badge> FIXED = Arrays.asList(
            new Badge("first_session", "🌱", "First steps", "Finished your first session"),
            new Badge("streak_3", "🔥", "On a roll", "Practised 3 days in a row"),
            new Badge("streak_7", "📅", "Week warrior", "Practised 7 days in a row"),
            new Badge("streak_14", "🚀", "Unstoppable", "Practised 14 days in a row"),
            new Badge("streak_30", "🏆", "Habit hero", "Practised 30 days in a row"),
            new Badge("perfect_1", "🎯", "Bullseye", "Every card right first time in a session"),
            new Badge("perfect_10", "💎", "Diamond", "10 perfect sessions"),
            new Badge("early_bird", "🐦", "Early bird", "Morning practice on 5 different days"),
            new Badge("full_house", "🏠", "Full house", "Answered every call in one day"),
            new Badge("speedy", "⚡", "Lightning", "10 quick right answers in a row"),
            new Badge("comeback", "💪", "Comeback", "Beat a tricky card"),
            new Badge("answers_100", "💯", "Century", "100 answers"),
            new Badge("answers_500", "🎖️", "Super 500", "500 answers"),
            new Badge("answers_1000", "👑", "Thousand club", "1000 answers"),
            new Badge("half_way", "🌓", "Half way", "Half of your cards mastered"),
            new Badge("champion", "🌕", "Times tables champion", "Every card mastered"));

    private Badges() {}

    public static Badge table(int t) {
        return new Badge("table_" + t, "🏅", t + "× master", "Mastered the " + t + " times table");
    }

    /** Looks up any badge by id, including table badges. */
    public static Badge byId(String id) {
        for (Badge b : FIXED) if (b.id.equals(id)) return b;
        if (id.startsWith("table_")) return table(Integer.parseInt(id.substring(6)));
        return null;
    }

    /** Facts about the session that just ended, for the per-session badges. */
    public static final class SessionResult {
        public boolean perfect;
        public boolean comeback;
        public boolean morning;
    }

    /**
     * Awards any newly earned badges (recording them in the deck) and returns them.
     *
     * @param active     cards of the currently selected tables
     * @param wakesPerDay scheduled wake-ups outside quiet hours (for "full house")
     */
    public static List<Badge> evaluate(Deck deck, Collection<Card> active, Settings settings, int wakesPerDay,
                                       long today, SessionResult session, long now) {
        List<Badge> earned = new ArrayList<>();
        Map<String, Long> have = deck.badges;

        award(earned, have, "first_session", deck.totalSessions() >= 1, now);
        int streak = deck.streak(today);
        award(earned, have, "streak_3", streak >= 3, now);
        award(earned, have, "streak_7", streak >= 7, now);
        award(earned, have, "streak_14", streak >= 14, now);
        award(earned, have, "streak_30", streak >= 30, now);
        award(earned, have, "perfect_1", session != null && session.perfect, now);
        award(earned, have, "perfect_10", deck.totalPerfect() >= 10, now);
        award(earned, have, "early_bird", deck.morningDays() >= EARLY_DAYS, now);
        Deck.DayStats day = deck.days.get(today);
        award(earned, have, "full_house", wakesPerDay >= 3 && day != null && day.sessions >= wakesPerDay, now);
        award(earned, have, "speedy", deck.fastRunBest >= SPEEDY_RUN, now);
        award(earned, have, "comeback", session != null && session.comeback, now);

        int answers = 0, mastered = 0;
        for (Card c : deck.cards.values()) answers += c.seen;
        for (Card c : active) if (c.isMastered()) mastered++;
        award(earned, have, "answers_100", answers >= 100, now);
        award(earned, have, "answers_500", answers >= 500, now);
        award(earned, have, "answers_1000", answers >= 1000, now);
        award(earned, have, "half_way", !active.isEmpty() && mastered * 2 >= active.size(), now);
        award(earned, have, "champion", !active.isEmpty() && mastered == active.size(), now);

        for (int t : settings.tables) {
            boolean all = false;
            for (Card c : active) {
                if (c.a != t && c.b != t) continue;
                if (!c.isMastered()) {
                    all = false;
                    break;
                }
                all = true;
            }
            Badge b = table(t);
            if (all && !have.containsKey(b.id)) {
                have.put(b.id, now);
                earned.add(b);
            }
        }
        return earned;
    }

    private static void award(List<Badge> earned, Map<String, Long> have, String id, boolean condition, long now) {
        if (!condition || have.containsKey(id)) return;
        have.put(id, now);
        earned.add(byId(id));
    }
}
