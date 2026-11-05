package app.timestables.core;

/** Quick ways to work a fact out, shown after a wrong answer instead of only the answer. */
public final class Tricks {
    private Tricks() {}

    /**
     * "9 × 6 = 60 − 6 = 54" for a 9× fact either way round (ten times the other number, take it
     * away once), or null. Not for 9 × 1 or 9 × 10, which need no trick.
     */
    public static String nines(int a, int b) {
        int other = a == 9 ? b : b == 9 ? a : 0;
        if (other < 2 || other == 10) return null;
        return a + " × " + b + " = " + 10 * other + " − " + other + " = " + a * b;
    }
}
