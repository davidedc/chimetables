package app.timestables.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One short session: up to {@code size} cards, due cards first (oldest first), then new cards
 * within the daily cap. A card rated Again comes back {@link #RELEARN_GAP} cards later, at most
 * {@link #MAX_SHOWS} times, after which it simply stays due for the next session.
 */
public final class Session {
    static final int RELEARN_GAP = 2;
    static final int MAX_SHOWS = 3;

    private final Deck deck;
    private final Srs srs;
    private final List<Card> queue;
    private final Map<String, Integer> shows = new HashMap<>();
    private final int planned;
    private int finished;

    private Session(Deck deck, Srs srs, List<Card> picked) {
        this.deck = deck;
        this.srs = srs;
        this.queue = new ArrayList<>(picked);
        this.planned = picked.size();
    }

    public static Session plan(Deck deck, Srs srs, List<Card> active, int size, int newPerDay, long now) {
        return new Session(deck, srs, pick(deck, srs, active, size, newPerDay, now));
    }

    static List<Card> pick(Deck deck, Srs srs, List<Card> active, int size, int newPerDay, long now) {
        List<Card> due = new ArrayList<>();
        List<Card> fresh = new ArrayList<>();
        for (Card c : active) {
            if (c.state == Card.State.NEW) fresh.add(c);
            else if (c.isDue(now)) due.add(c);
        }
        Collections.sort(due, Comparator.comparingLong((Card c) -> c.dueAt));

        List<Card> picked = new ArrayList<>(due.subList(0, Math.min(size, due.size())));
        int newSlots = Math.min(size - picked.size(), deck.newAllowedToday(srs.dayNumber(now), newPerDay));
        for (int i = 0; i < newSlots && i < fresh.size(); i++) picked.add(fresh.get(i));
        return picked;
    }

    /** True when nothing is due and no new card may be introduced, i.e. no point waking up. */
    public static boolean nothingToDo(Deck deck, Srs srs, List<Card> active, int newPerDay, long now) {
        return pick(deck, srs, active, 1, newPerDay, now).isEmpty();
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

    /** Cards completed so far (for the progress dots). */
    public int finished() {
        return finished;
    }

    public void answer(Rating rating, long now) {
        Card card = queue.remove(0);
        if (card.state == Card.State.NEW) deck.countNewCard(srs.dayNumber(now));
        Integer prev = shows.get(card.id());
        int count = (prev == null ? 0 : prev) + 1;
        shows.put(card.id(), count);

        boolean again = srs.answer(card, rating, now);
        if (again && count < MAX_SHOWS) {
            queue.add(Math.min(RELEARN_GAP, queue.size()), card);
        } else {
            finished++;
        }
    }
}
