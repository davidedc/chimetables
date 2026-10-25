package app.timestables.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Random;

/**
 * Anki-style SM-2 scheduler (implemented from the Anki manual's description, not Anki's code),
 * tuned for a child learning a small, closed set of facts where speed of recall matters.
 *
 * <pre>
 * NEW/LEARNING  Again -> repeat in this session (step 0)
 *               Good  -> step 0: next session later today (step 1); step 1: graduate, 1 day
 *               Hard* -> as Good, but step 1 repeats later today instead of graduating
 *               Easy  -> graduate, 3 days, ease +0.15
 * REVIEW        Again -> lapse: ease -0.20 (min 1.3), relearn in this session, then 1 day
 *               Hard* -> interval x1.2, ease -0.15
 *               Good  -> (interval + daysLate/2) x ease
 *               Easy  -> (interval + daysLate) x ease x 1.3, ease +0.15
 * RELEARNING    Again -> repeat in this session; Good/Hard -> 1 day; Easy -> 2 days
 *
 * * There is no Hard button. "Hard" is a Good that took more than 10 s to reveal: right but not
 *   yet automatic, so it shouldn't race ahead. A slow Easy counts as Good. Reveals over 90 s are
 *   treated as "distracted" and ignored. The child is never told; it only shapes the schedule.
 * </pre>
 *
 * Every review interval grows by at least a day, is capped at 60 days (14 for tricky cards), and
 * from 7 days on gets ±5% fuzz so cards learned together drift apart. Day intervals fall due at
 * local midnight, so a card is ready at the first session of its day. A card forgotten 4 times
 * becomes "tricky" until it is remembered again at its 14-day cap.
 */
public final class Srs {
    public enum Grade { AGAIN, HARD, GOOD, EASY }

    public static final double START_EASE = 2.5;
    public static final double MIN_EASE = 1.3;
    public static final double EASE_AGAIN = 0.20;
    public static final double EASE_HARD = 0.15;
    public static final double EASE_EASY = 0.15;
    public static final double HARD_FACTOR = 1.2;
    public static final double EASY_BONUS = 1.3;
    public static final long LATER_TODAY_MS = 2 * 60 * 60 * 1000L;
    public static final int GRADUATE_DAYS = 1;
    public static final int EASY_GRADUATE_DAYS = 3;
    public static final int RELEARN_EASY_DAYS = 2;
    public static final int MAX_INTERVAL_DAYS = 60;
    public static final int TRICKY_MAX_INTERVAL_DAYS = 14;
    public static final int LEECH_LAPSES = 4;
    public static final int MASTERED_DAYS = 21;
    public static final long SLOW_REVEAL_MS = 10_000;
    public static final long DISTRACTED_REVEAL_MS = 90_000;
    public static final int FUZZ_FROM_DAYS = 7;
    public static final double FUZZ = 0.05;

    private final ZoneId zone;
    private final Random random;

    /** Production scheduler with fuzz. */
    public Srs(ZoneId zone) {
        this(zone, new Random());
    }

    /** {@code random == null} disables fuzz (deterministic, for tests). */
    public Srs(ZoneId zone, Random random) {
        this.zone = zone;
        this.random = random;
    }

    /** The grade used for scheduling: a slow Good becomes Hard, a slow Easy becomes Good. */
    public static Grade grade(Rating rating, long revealMs) {
        boolean slow = revealMs > SLOW_REVEAL_MS && revealMs <= DISTRACTED_REVEAL_MS;
        switch (rating) {
            case AGAIN: return Grade.AGAIN;
            case EASY: return slow ? Grade.GOOD : Grade.EASY;
            case GOOD:
            default: return slow ? Grade.HARD : Grade.GOOD;
        }
    }

    public boolean answer(Card card, Rating rating, long now) {
        return answer(card, rating, now, -1);
    }

    /**
     * Records an answer and reschedules the card.
     *
     * @param revealMs time from question shown to "show answer", or -1 if unknown
     * @return true if the card should be shown again later in the same session
     */
    public boolean answer(Card card, Rating rating, long now, long revealMs) {
        card.seen++;
        card.lastSeenAt = now;
        if (rating != Rating.AGAIN) card.correct++;
        else card.missedAt = now;
        if (revealMs > 0 && revealMs <= DISTRACTED_REVEAL_MS) {
            card.revealMsAvg = card.revealMsAvg == 0 ? revealMs : (card.revealMsAvg * 2 + revealMs) / 3;
        }
        Grade grade = grade(rating, revealMs);

        switch (card.state) {
            case NEW:
            case LEARNING:
                return answerLearning(card, grade, now);
            case RELEARNING:
                return answerRelearning(card, grade, now);
            case REVIEW:
            default:
                return answerReview(card, grade, now);
        }
    }

    private boolean answerLearning(Card card, Grade grade, long now) {
        switch (grade) {
            case AGAIN:
                card.state = Card.State.LEARNING;
                card.step = 0;
                card.dueAt = now;
                return true;
            case HARD:
            case GOOD:
                if (card.step == 0 || grade == Grade.HARD) {
                    card.state = Card.State.LEARNING;
                    card.step = 1;
                    card.dueAt = now + LATER_TODAY_MS;
                } else {
                    graduate(card, GRADUATE_DAYS, now);
                }
                return false;
            case EASY:
            default:
                card.ease += EASE_EASY;
                graduate(card, EASY_GRADUATE_DAYS, now);
                return false;
        }
    }

    private boolean answerRelearning(Card card, Grade grade, long now) {
        switch (grade) {
            case AGAIN:
                card.dueAt = now;
                return true;
            case EASY:
                graduate(card, RELEARN_EASY_DAYS, now);
                return false;
            default:
                graduate(card, GRADUATE_DAYS, now);
                return false;
        }
    }

    private boolean answerReview(Card card, Grade grade, long now) {
        if (grade == Grade.AGAIN) {
            card.lapses++;
            card.reps = 0;
            card.ease = Math.max(MIN_EASE, card.ease - EASE_AGAIN);
            card.state = Card.State.RELEARNING;
            card.dueAt = now;
            if (card.lapses >= LEECH_LAPSES) card.tricky = true;
            return true;
        }

        int ivl = card.intervalDays;
        long daysLate = Math.max(0, dayNumber(now) - dayNumber(card.dueAt));
        double next;
        switch (grade) {
            case HARD:
                card.ease = Math.max(MIN_EASE, card.ease - EASE_HARD);
                next = ivl * HARD_FACTOR;
                break;
            case EASY:
                card.ease += EASE_EASY;
                next = (ivl + daysLate) * card.ease * EASY_BONUS;
                break;
            case GOOD:
            default:
                next = (ivl + daysLate / 2.0) * card.ease;
                break;
        }

        int cap = card.tricky ? TRICKY_MAX_INTERVAL_DAYS : MAX_INTERVAL_DAYS;
        if (card.tricky && ivl >= TRICKY_MAX_INTERVAL_DAYS && grade != Grade.HARD) {
            card.tricky = false; // remembered at the tricky cap: it has stuck
            cap = MAX_INTERVAL_DAYS;
        }
        int days = (int) Math.round(next);
        days = fuzz(days);
        days = Math.max(days, ivl + 1);
        days = Math.min(days, cap);

        card.reps++;
        card.intervalDays = days;
        card.dueAt = startOfDayPlus(now, days);
        return false;
    }

    private int fuzz(int days) {
        if (random == null || days < FUZZ_FROM_DAYS) return days;
        int range = Math.max(1, (int) Math.round(days * FUZZ));
        return days + random.nextInt(2 * range + 1) - range;
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

    /** True if the card was answered wrong earlier on the same local day as {@code now}. */
    public boolean missedToday(Card card, long now) {
        return card.missedAt > 0 && dayNumber(card.missedAt) == dayNumber(now);
    }

    /** Local calendar day number of {@code now}. */
    public long dayNumber(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay();
    }

    /** Local hour of day of {@code now}. */
    public int hourOfDay(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).getHour();
    }
}
