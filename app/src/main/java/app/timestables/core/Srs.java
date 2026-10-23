package app.timestables.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Simplified SM-2 with a short learning phase.
 *
 * <pre>
 * NEW/LEARNING  Again -> repeat in this session (step 0)
 *               Good  -> step 0: see again later today (step 1); step 1: graduate, 1 day
 *               Easy  -> graduate, 4 days, ease +0.15
 * REVIEW        Again -> lapse: ease -0.20 (min 1.3), relearn in this session
 *               Good  -> 2nd review 3 days, then interval * ease
 *               Easy  -> as Good * 1.3, ease +0.15
 * RELEARNING    Again -> repeat in this session; Good -> 1 day; Easy -> 2 days
 * </pre>
 *
 * Day intervals fall due at local midnight, so a card is ready at the first session of its day.
 */
public final class Srs {
    public static final double START_EASE = 2.5;
    public static final double MIN_EASE = 1.3;
    public static final double EASE_STEP_DOWN = 0.20;
    public static final double EASE_STEP_UP = 0.15;
    public static final double EASY_BONUS = 1.3;
    public static final long LATER_TODAY_MS = 2 * 60 * 60 * 1000L;
    public static final int GRADUATE_DAYS = 1;
    public static final int EASY_GRADUATE_DAYS = 4;
    public static final int SECOND_REVIEW_DAYS = 3;
    public static final int RELEARN_EASY_DAYS = 2;
    public static final int MAX_INTERVAL_DAYS = 90;

    private final ZoneId zone;

    public Srs(ZoneId zone) {
        this.zone = zone;
    }

    /**
     * Records an answer and reschedules the card.
     *
     * @return true if the card should be shown again later in the same session
     */
    public boolean answer(Card card, Rating rating, long now) {
        card.seen++;
        card.lastSeenAt = now;
        if (rating != Rating.AGAIN) card.correct++;

        switch (card.state) {
            case NEW:
            case LEARNING:
                return answerLearning(card, rating, now);
            case RELEARNING:
                return answerRelearning(card, rating, now);
            case REVIEW:
            default:
                return answerReview(card, rating, now);
        }
    }

    private boolean answerLearning(Card card, Rating rating, long now) {
        switch (rating) {
            case AGAIN:
                card.state = Card.State.LEARNING;
                card.step = 0;
                card.dueAt = now;
                return true;
            case GOOD:
                if (card.step == 0) {
                    card.state = Card.State.LEARNING;
                    card.step = 1;
                    card.dueAt = now + LATER_TODAY_MS;
                } else {
                    graduate(card, GRADUATE_DAYS, now);
                }
                return false;
            case EASY:
            default:
                card.ease += EASE_STEP_UP;
                graduate(card, EASY_GRADUATE_DAYS, now);
                return false;
        }
    }

    private boolean answerRelearning(Card card, Rating rating, long now) {
        switch (rating) {
            case AGAIN:
                card.dueAt = now;
                return true;
            case GOOD:
                graduate(card, GRADUATE_DAYS, now);
                return false;
            case EASY:
            default:
                graduate(card, RELEARN_EASY_DAYS, now);
                return false;
        }
    }

    private boolean answerReview(Card card, Rating rating, long now) {
        if (rating == Rating.AGAIN) {
            card.lapses++;
            card.reps = 0;
            card.ease = Math.max(MIN_EASE, card.ease - EASE_STEP_DOWN);
            card.state = Card.State.RELEARNING;
            card.dueAt = now;
            return true;
        }
        card.reps++;
        double next;
        if (card.reps <= 2) {
            next = SECOND_REVIEW_DAYS;
        } else {
            next = card.intervalDays * card.ease;
        }
        if (rating == Rating.EASY) {
            card.ease += EASE_STEP_UP;
            next *= EASY_BONUS;
        }
        int days = (int) Math.round(next);
        days = Math.max(days, card.intervalDays + 1);
        days = Math.min(days, MAX_INTERVAL_DAYS);
        card.intervalDays = days;
        card.dueAt = startOfDayPlus(now, days);
        return false;
    }

    private void graduate(Card card, int days, long now) {
        card.state = Card.State.REVIEW;
        card.step = 0;
        card.reps = 1;
        card.intervalDays = days;
        card.dueAt = startOfDayPlus(now, days);
    }

    /** Local midnight {@code days} days after the day containing {@code now}. */
    public long startOfDayPlus(long now, int days) {
        return LocalDate.from(Instant.ofEpochMilli(now).atZone(zone))
                .plusDays(days)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli();
    }

    /** Local calendar day number of {@code now}. */
    public long dayNumber(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay();
    }
}
