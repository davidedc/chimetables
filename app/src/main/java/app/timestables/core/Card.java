package app.timestables.core;

/** One flashcard "a x b" plus its spaced-repetition state. 7x8 and 8x7 are different cards. */
public final class Card {
    public enum State { NEW, LEARNING, REVIEW, RELEARNING }

    public final int a;
    public final int b;

    public State state = State.NEW;
    public double ease = Srs.START_EASE;
    /** Current interval in days once the card is in REVIEW. */
    public int intervalDays;
    /** When the card is next due (epoch millis). Unused while NEW. */
    public long dueAt;
    /** Learning step: 0 = repeat within this session, 1 = repeat at a later session today. */
    public int step;
    /** Successful reviews since the card graduated or last lapsed. */
    public int reps;
    public int lapses;
    /** "Leech": forgotten {@link Srs#LEECH_LAPSES} times; reviewed more often until it sticks. */
    public boolean tricky;
    /** Total answers, and answers rated Good or Easy (for parent stats). */
    public int seen;
    public int correct;
    public long lastSeenAt;
    /** When it was last answered wrong (epoch millis, 0 = never). */
    public long missedAt;
    /** Moving average of question-to-reveal time in ms (0 = not measured yet). */
    public long revealMsAvg;

    public Card(int a, int b) {
        this.a = a;
        this.b = b;
    }

    public int answer() {
        return a * b;
    }

    public String id() {
        return id(a, b);
    }

    /** The commuted card (8x7 for 7x8); the same id for squares. */
    public String siblingId() {
        return id(b, a);
    }

    public static String id(int a, int b) {
        return a + "x" + b;
    }

    public boolean isDue(long now) {
        return state != State.NEW && dueAt <= now;
    }

    public boolean isMastered() {
        return state == State.REVIEW && intervalDays >= Srs.MASTERED_DAYS;
    }

    @Override
    public String toString() {
        return id() + "[" + state + " ease=" + Math.round(ease * 100) / 100.0 + " ivl=" + intervalDays
                + " step=" + step + (tricky ? " tricky" : "") + "]";
    }
}
