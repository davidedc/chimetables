package app.timestables.core;

/**
 * Typed-answer checking and automatic grading (the child no longer rates herself).
 *
 * <ul>
 *   <li>The right number is accepted the moment it is typed.</li>
 *   <li>A wrong number is marked wrong once it has as many digits as the answer (or on ✓), so
 *       she can't keep trying numbers until one is accepted.</li>
 *   <li>Grade from recall speed, measured to the <em>first</em> key press so typing speed doesn't
 *       count: right within 3 s = Easy, right later = Good (which {@link Srs#grade} turns into Hard
 *       after 10 s), wrong = Again.</li>
 * </ul>
 */
public final class Answer {
    public enum Check { INCOMPLETE, CORRECT, WRONG }

    public static final long QUICK_MS = 3000;
    public static final int MAX_DIGITS = 3;

    private Answer() {}

    public static Check check(String typed, int answer, boolean submitted) {
        if (typed == null || typed.isEmpty()) return Check.INCOMPLETE;
        String target = String.valueOf(answer);
        if (typed.equals(target)) return Check.CORRECT;
        if (submitted || typed.length() >= target.length()) return Check.WRONG;
        return Check.INCOMPLETE;
    }

    public static Rating rate(boolean correct, long firstKeyMs) {
        if (!correct) return Rating.AGAIN;
        return firstKeyMs > 0 && firstKeyMs <= QUICK_MS ? Rating.EASY : Rating.GOOD;
    }
}
