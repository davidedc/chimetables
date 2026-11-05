package app.timestables.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * One short session: up to {@code size} cards. Cards she is still learning come first (missed ones,
 * and new ones waiting for their second go), then due reviews, oldest first, then new cards: at most
 * {@link #MAX_NEW} a session, none while {@link #LEARNING_LIMIT} cards are still being learned, and
 * within the daily cap. Any places left go to practice cards ({@link #isPractice}). A card and its
 * commuted sibling (7x8 / 8x7) are never in the same session, so the second isn't answered from
 * short-term memory (Anki's "bury siblings").
 * A card rated Again comes back {@link #RELEARN_GAP} cards later, at most {@link #MAX_SHOWS} times,
 * after which it simply stays due for the next session. A card missed earlier the same day, in this
 * session or an earlier one, can be Good at best: right after seeing the answer isn't "easy".
 * Practice cards, and every card of a bonus round ({@link #bonus}), are practice only: answers don't
 * touch the schedule or the deck.
 */
public final class Session {
    static final int RELEARN_GAP = 2;
    static final int MAX_SHOWS = 3;
    /** Sessions with fewer cards than this can't be "perfect". */
    static final int PERFECT_MIN_CARDS = 3;
    /** A bonus round picks at random from this many times its size of the least established cards. */
    static final int BONUS_POOL = 3;
    /** New cards in one session, so a round is never a pile of facts she has never seen. */
    static final int MAX_NEW = 2;
    /** No new cards while this many are still being learned: the hard ones settle first. */
    static final int LEARNING_LIMIT = 2;
    /**
     * A learning card due within this long counts as due, so a card waiting for its "later today"
     * go doesn't miss a call by a few minutes (Anki's "learn ahead limit").
     */
    static final long LEARN_AHEAD_MS = 30 * 60_000L;

    private final Deck deck;
    private final Srs srs;
    private final List<Card> queue;
    private final Map<String, Integer> shows = new HashMap<>();
    private final Map<String, Boolean> firstTry = new HashMap<>();
    private final int planned;
    private final boolean bonus;
    /** Ids of the practice cards filling the places nothing else needed. */
    private final Set<String> practice;
    /** What the session was made of, for the log. */
    private final String makeup;
    private int finished;
    private int answers;
    private boolean comeback;
    /** What has already gone into the day's history, so a paused and resumed session counts once. */
    private boolean recorded;
    private int recordedAnswers;
    private int recordedCorrect;

    private Session(Deck deck, Srs srs, List<Card> picked, boolean bonus, Set<String> practice, String makeup) {
        this.deck = deck;
        this.srs = srs;
        this.queue = new ArrayList<>(picked);
        this.planned = picked.size();
        this.bonus = bonus;
        this.practice = practice;
        this.makeup = makeup;
    }

    public static Session plan(Deck deck, Srs srs, List<Card> active, int size, int newPerDay, long now) {
        return plan(deck, srs, active, size, newPerDay, now, null);
    }

    /**
     * The cards that need doing ({@link #pick}), then practice cards up to {@code size} if there is
     * room: cards she has met, least established first, drawn at random from a few times as many
     * ({@code random == null} keeps that order, for tests). Nothing to do means an empty session,
     * not a practice-only one: that is a bonus round.
     */
    public static Session plan(Deck deck, Srs srs, List<Card> active, int size, int newPerDay, long now,
                               Random random) {
        List<Card> picked = pick(deck, srs, active, size, newPerDay, now);
        int learning = 0, fresh = 0;
        for (Card c : picked) {
            if (c.state == Card.State.NEW) fresh++;
            else if (c.state != Card.State.REVIEW) learning++;
        }
        int due = picked.size() - learning - fresh;
        Set<String> practice = new HashSet<>();
        if (!picked.isEmpty() && picked.size() < size) {
            Set<String> taken = new HashSet<>();
            for (Card c : picked) taken.add(c.id());
            long today = srs.dayNumber(now);
            List<Card> candidates = new ArrayList<>();
            for (Card c : active) if (c.state == Card.State.REVIEW && !taken.contains(c.id())) candidates.add(c);
            // Cards she hasn't answered today first: no point practising one she just reviewed.
            Comparator<Card> order = Comparator.comparing((Card c) -> srs.dayNumber(c.lastSeenAt) == today);
            for (Card c : leastEstablished(candidates, order, size - picked.size(), taken, random)) {
                picked.add(c);
                practice.add(c.id());
            }
        }
        String makeup = learning + " learning, " + due + " due, " + fresh + " new, " + practice.size() + " practice";
        return new Session(deck, srs, picked, false, practice, makeup);
    }

    /**
     * A bonus round for when nothing is due: up to {@code size} cards she has already met, drawn at
     * random from the least established ones (tricky first, then shortest interval, then slowest
     * recall). {@code random == null} keeps that order (for tests).
     */
    public static Session bonus(Deck deck, Srs srs, List<Card> active, int size, Random random) {
        List<Card> seen = new ArrayList<>();
        for (Card c : active) if (c.state != Card.State.NEW) seen.add(c);
        List<Card> picked = leastEstablished(seen, (x, y) -> 0, size, new HashSet<>(), random);
        return new Session(deck, srs, picked, true, new HashSet<>(), "bonus");
    }

    /**
     * Up to {@code size} of {@code cards} for practice: sorted by {@code first}, then tricky first,
     * shortest interval, slowest recall; drawn at random from {@link #BONUS_POOL} times as many.
     * Skips siblings of cards in {@code taken}, and adds the cards it picks to it.
     */
    private static List<Card> leastEstablished(List<Card> cards, Comparator<Card> first, int size,
                                               Set<String> taken, Random random) {
        List<Card> sorted = new ArrayList<>();
        for (Card c : cards) if (!taken.contains(c.siblingId())) sorted.add(c);
        Collections.sort(sorted, first
                .thenComparing((Card c) -> !c.tricky)
                .thenComparingInt(c -> c.intervalDays)
                .thenComparingLong(c -> -c.revealMsAvg));
        List<Card> pool = new ArrayList<>(sorted.subList(0, Math.min(sorted.size(), size * BONUS_POOL)));
        if (random != null) Collections.shuffle(pool, random);

        List<Card> picked = new ArrayList<>();
        for (Card c : pool) {
            if (picked.size() >= size) break;
            if (taken.contains(c.siblingId())) continue;
            picked.add(c);
            taken.add(c.id());
        }
        return picked;
    }

    /** True once she has met at least one card, so a bonus round has something to ask. */
    public static boolean bonusAvailable(List<Card> active) {
        for (Card c : active) if (c.state != Card.State.NEW) return true;
        return false;
    }

    /** The cards that need doing now: learning, then due reviews, then new cards (see the class comment). */
    static List<Card> pick(Deck deck, Srs srs, List<Card> active, int size, int newPerDay, long now) {
        List<Card> learning = new ArrayList<>();
        List<Card> due = new ArrayList<>();
        List<Card> fresh = new ArrayList<>();
        int stillLearning = 0;
        for (Card c : active) {
            if (c.state == Card.State.NEW) {
                fresh.add(c);
            } else if (c.state == Card.State.REVIEW) {
                if (c.isDue(now)) due.add(c);
            } else {
                stillLearning++;
                if (c.dueAt <= now + LEARN_AHEAD_MS) learning.add(c);
            }
        }
        Collections.sort(learning, Comparator.comparingLong((Card c) -> c.dueAt));
        Collections.sort(due, Comparator.comparingLong((Card c) -> c.dueAt));
        List<Card> needed = new ArrayList<>(learning);
        needed.addAll(due);

        List<Card> picked = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Card c : needed) {
            if (picked.size() >= size) break;
            if (taken.contains(c.siblingId())) continue;
            picked.add(c);
            taken.add(c.id());
        }
        int newSlots = stillLearning >= LEARNING_LIMIT ? 0 : Math.min(Math.min(size - picked.size(), MAX_NEW),
                deck.newAllowedToday(srs.dayNumber(now), newPerDay));
        for (Card c : fresh) {
            if (newSlots <= 0) break;
            if (taken.contains(c.siblingId())) continue;
            picked.add(c);
            taken.add(c.id());
            newSlots--;
        }
        return picked;
    }

    /** True when nothing is due and no new card may be introduced, i.e. no point waking up. */
    public static boolean nothingToDo(Deck deck, Srs srs, List<Card> active, int newPerDay, long now) {
        return pick(deck, srs, active, 1, newPerDay, now).isEmpty();
    }

    public boolean isBonus() {
        return bonus;
    }

    /** True for a card that is only practice: in a bonus round, or filling a place in a session. */
    public boolean isPractice(Card card) {
        return bonus || practice.contains(card.id());
    }

    /** E.g. "1 learning, 3 due, 2 new, 2 practice", or "bonus". */
    public String makeup() {
        return makeup;
    }

    public boolean isEmpty() {
        return planned == 0;
    }

    public boolean isDone() {
        return queue.isEmpty();
    }

    public Card current() {
        return queue.isEmpty() ? null : queue.get(0);
    }

    public int planned() {
        return planned;
    }

    /** Cards completed so far (for the progress stars). */
    public int finished() {
        return finished;
    }

    public int answers() {
        return answers;
    }

    /** Cards answered Good/Easy the first time they came up in this session. */
    public int firstTryCorrect() {
        int n = 0;
        for (boolean ok : firstTry.values()) if (ok) n++;
        return n;
    }

    public int firstTryCount() {
        return firstTry.size();
    }

    public boolean perfect() {
        return planned >= PERFECT_MIN_CARDS && isDone() && firstTryCorrect() == planned;
    }

    /** True once (part of) this session has been added to the day's history. */
    public boolean recorded() {
        return recorded;
    }

    /** Answers not yet added to the day's history. */
    public int unrecordedAnswers() {
        return answers - recordedAnswers;
    }

    /** Right-first-time cards not yet added to the day's history. */
    public int unrecordedCorrect() {
        return firstTryCorrect() - recordedCorrect;
    }

    public void markRecorded() {
        recorded = true;
        recordedAnswers = answers;
        recordedCorrect = firstTryCorrect();
    }

    /** True if a tricky card stopped being tricky during this session. */
    public boolean comeback() {
        return comeback;
    }

    public Rating answer(Rating rating, long now) {
        return answer(rating, now, -1);
    }

    /** Records an answer for the current card; returns the rating actually applied. */
    public Rating answer(Rating rating, long now, long revealMs) {
        Card card = queue.remove(0);
        boolean practiceOnly = isPractice(card);
        if (!practiceOnly && card.state == Card.State.NEW) deck.countNewCard(srs.dayNumber(now));
        Integer prev = shows.get(card.id());
        int count = (prev == null ? 0 : prev) + 1;
        shows.put(card.id(), count);
        Boolean first = firstTry.get(card.id());
        if (first == null) firstTry.put(card.id(), rating != Rating.AGAIN);
        // Missed earlier in this session or earlier today: getting it right now is good, but not "easy".
        if (rating == Rating.EASY && (Boolean.FALSE.equals(first) || srs.missedToday(card, now))) rating = Rating.GOOD;
        answers++;

        boolean again;
        if (practiceOnly) {
            again = rating == Rating.AGAIN; // the card and its schedule stay as they were
        } else {
            deck.recordAnswer(rating, revealMs);
            boolean wasTricky = card.tricky;
            again = srs.answer(card, rating, now, revealMs);
            if (wasTricky && !card.tricky) comeback = true;
        }
        if (again && count < MAX_SHOWS) {
            queue.add(Math.min(RELEARN_GAP, queue.size()), card);
        } else {
            finished++;
        }
        return rating;
    }
}
