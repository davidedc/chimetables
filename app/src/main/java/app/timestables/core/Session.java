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
 * One short session: up to {@code size} cards, due cards first (oldest first), then new cards
 * within the daily cap. A card and its commuted sibling (7x8 / 8x7) are never in the same session,
 * so the second isn't answered from short-term memory (Anki's "bury siblings").
 * A card rated Again comes back {@link #RELEARN_GAP} cards later, at most {@link #MAX_SHOWS} times,
 * after which it simply stays due for the next session; when it comes back it can be Good at best.
 * A bonus round ({@link #bonus}) is practice only: answers don't touch the schedule or the deck.
 */
public final class Session {
    static final int RELEARN_GAP = 2;
    static final int MAX_SHOWS = 3;
    /** Sessions with fewer cards than this can't be "perfect". */
    static final int PERFECT_MIN_CARDS = 3;
    /** A bonus round picks at random from this many times its size of the least established cards. */
    static final int BONUS_POOL = 3;

    private final Deck deck;
    private final Srs srs;
    private final List<Card> queue;
    private final Map<String, Integer> shows = new HashMap<>();
    private final Map<String, Boolean> firstTry = new HashMap<>();
    private final int planned;
    private final boolean bonus;
    private int finished;
    private int answers;
    private boolean comeback;

    private Session(Deck deck, Srs srs, List<Card> picked, boolean bonus) {
        this.deck = deck;
        this.srs = srs;
        this.queue = new ArrayList<>(picked);
        this.planned = picked.size();
        this.bonus = bonus;
    }

    public static Session plan(Deck deck, Srs srs, List<Card> active, int size, int newPerDay, long now) {
        return new Session(deck, srs, pick(deck, srs, active, size, newPerDay, now), false);
    }

    /**
     * A bonus round for when nothing is due: up to {@code size} cards she has already met, drawn at
     * random from the least established ones (tricky first, then shortest interval, then slowest
     * recall). {@code random == null} keeps that order (for tests).
     */
    public static Session bonus(Deck deck, Srs srs, List<Card> active, int size, Random random) {
        List<Card> seen = new ArrayList<>();
        for (Card c : active) if (c.state != Card.State.NEW) seen.add(c);
        Collections.sort(seen, Comparator
                .comparing((Card c) -> !c.tricky)
                .thenComparingInt(c -> c.intervalDays)
                .thenComparingLong(c -> -c.revealMsAvg));
        List<Card> pool = new ArrayList<>(seen.subList(0, Math.min(seen.size(), size * BONUS_POOL)));
        if (random != null) Collections.shuffle(pool, random);

        List<Card> picked = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Card c : pool) {
            if (picked.size() >= size) break;
            if (taken.contains(c.siblingId())) continue;
            picked.add(c);
            taken.add(c.id());
        }
        return new Session(deck, srs, picked, true);
    }

    /** True once she has met at least one card, so a bonus round has something to ask. */
    public static boolean bonusAvailable(List<Card> active) {
        for (Card c : active) if (c.state != Card.State.NEW) return true;
        return false;
    }

    static List<Card> pick(Deck deck, Srs srs, List<Card> active, int size, int newPerDay, long now) {
        List<Card> due = new ArrayList<>();
        List<Card> fresh = new ArrayList<>();
        for (Card c : active) {
            if (c.state == Card.State.NEW) fresh.add(c);
            else if (c.isDue(now)) due.add(c);
        }
        Collections.sort(due, Comparator.comparingLong((Card c) -> c.dueAt));

        List<Card> picked = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Card c : due) {
            if (picked.size() >= size) break;
            if (taken.contains(c.siblingId())) continue;
            picked.add(c);
            taken.add(c.id());
        }
        int newSlots = Math.min(size - picked.size(), deck.newAllowedToday(srs.dayNumber(now), newPerDay));
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
        if (!bonus && card.state == Card.State.NEW) deck.countNewCard(srs.dayNumber(now));
        Integer prev = shows.get(card.id());
        int count = (prev == null ? 0 : prev) + 1;
        shows.put(card.id(), count);
        Boolean first = firstTry.get(card.id());
        if (first == null) firstTry.put(card.id(), rating != Rating.AGAIN);
        // Missed earlier in this session: getting it right now is good, but not "easy".
        if (Boolean.FALSE.equals(first) && rating == Rating.EASY) rating = Rating.GOOD;
        answers++;

        boolean again;
        if (bonus) {
            again = rating == Rating.AGAIN; // practice only: the card and its schedule stay as they were
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
