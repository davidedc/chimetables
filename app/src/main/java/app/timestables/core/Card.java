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
    /** Learning step: 0 = repeat within this session, 1 = repeat later today. */
    public int step;
    /** Successful reviews since the card graduated or last lapsed. */
    public int reps;
    public int lapses;
    /** Total answers, and answers rated Good or Easy (for parent stats). */
    public int seen;
    public int correct;
    public long lastSeenAt;

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

    public static String id(int a, int b) {
        return a + "x" + b;
    }

    public boolean isDue(long now) {
        return state != State.NEW && dueAt <= now;
    }

    @Override
    public String toString() {
        return id() + "[" + state + " ease=" + ease + " ivl=" + intervalDays + " step=" + step + "]";
    }
}
