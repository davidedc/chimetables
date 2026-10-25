package app.timestables.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One riddle card: the question on the front, the answer (and an optional "did you know?") on the
 * back. The bank is a text file of blocks separated by blank lines:
 *
 * <pre>
 * Q: What has keys but can't open locks?
 * A: A piano!
 * F: (optional) a line for the back of the card
 * L: 1   (optional difficulty, 1-3)
 * </pre>
 *
 * A line without a tag continues the field above it on a new line. Lines starting with # are comments.
 */
public final class Riddle {
    public final String question;
    public final String answer;
    /** Optional extra for the back of the card, e.g. the trick behind a number riddle. */
    public final String fact;
    /** Difficulty 1-3, shown as magnifying glasses. */
    public final int level;

    public Riddle(String question, String answer, String fact, int level) {
        this.question = question;
        this.answer = answer;
        this.fact = fact == null ? "" : fact;
        this.level = Math.max(1, Math.min(3, level));
    }

    /**
     * Stable id kept in the progress file (from the question, ignoring case, spaces and punctuation),
     * so the bank can be reordered or added to without mixing up which riddles she has had.
     */
    public String key() {
        String norm = question.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return Integer.toHexString(norm.hashCode());
    }

    public static List<Riddle> parse(String text) {
        List<Riddle> out = new ArrayList<>();
        String[] fields = new String[4]; // Q, A, F, L
        int last = -1;
        for (String raw : (text + "\n\n").split("\r?\n", -1)) {
            String line = raw.trim();
            if (line.startsWith("#")) continue;
            if (line.isEmpty()) {
                if (fields[0] != null && fields[1] != null) {
                    int level = 1;
                    try {
                        if (fields[3] != null) level = Integer.parseInt(fields[3].trim());
                    } catch (NumberFormatException ignored) {
                        // keep 1
                    }
                    out.add(new Riddle(fields[0], fields[1], fields[2], level));
                }
                fields = new String[4];
                last = -1;
                continue;
            }
            int tag = line.length() > 1 && line.charAt(1) == ':' ? "QAFL".indexOf(line.charAt(0)) : -1;
            if (tag >= 0) {
                fields[tag] = line.substring(2).trim();
                last = tag;
            } else if (last >= 0) {
                fields[last] = fields[last] + "\n" + line;
            }
        }
        return out;
    }

    /** The main bank with a family's own riddles mixed in: one of theirs after every {@code every} of the bank's. */
    public static List<Riddle> mix(List<Riddle> bank, List<Riddle> family, int every) {
        List<Riddle> out = new ArrayList<>();
        int f = 0;
        for (int i = 0; i < bank.size(); i++) {
            out.add(bank.get(i));
            if ((i + 1) % every == 0 && f < family.size()) out.add(family.get(f++));
        }
        while (f < family.size()) out.add(family.get(f++));
        return out;
    }
}
