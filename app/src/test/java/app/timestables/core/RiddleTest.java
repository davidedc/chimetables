package app.timestables.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RiddleTest {
    @Test
    public void parsesBlocksWithContinuationLinesAndComments() {
        List<Riddle> riddles = Riddle.parse("# a comment\n"
                + "Q: What has keys but can't open locks?\n"
                + "A: A piano!\n"
                + "\n\n"
                + "Q: 11 x 12 = 132\n"
                + "11 x 13 = 143\n"
                + "A: 286\n"
                + "F: Split the digits.\n"
                + "L: 2\n"
                + "\n"
                + "Q: No answer, so skipped\n");
        assertEquals(2, riddles.size());
        assertEquals("A piano!", riddles.get(0).answer);
        assertEquals(1, riddles.get(0).level);
        assertEquals("", riddles.get(0).fact);
        assertEquals("11 x 12 = 132\n11 x 13 = 143", riddles.get(1).question);
        assertEquals("Split the digits.", riddles.get(1).fact);
        assertEquals(2, riddles.get(1).level);
    }

    @Test
    public void keyIgnoresCaseSpacesAndPunctuation() {
        assertEquals(new Riddle("What has keys?", "x", null, 1).key(), new Riddle("what has  KEYS", "y", null, 1).key());
        assertNotEquals(new Riddle("What has keys?", "x", null, 1).key(), new Riddle("What has hands?", "x", null, 1).key());
    }

    @Test
    public void familyRiddlesAreMixedIn() {
        List<Riddle> bank = Arrays.asList(r("1"), r("2"), r("3"), r("4"), r("5"));
        List<Riddle> family = Arrays.asList(r("a"), r("b"), r("c"));
        StringBuilder order = new StringBuilder();
        for (Riddle x : Riddle.mix(bank, family, 2)) order.append(x.question);
        assertEquals("12a34b5c", order.toString());
    }

    @Test
    public void theShippedBankParsesCompletelyWithUniqueKeys() throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/assets/riddles.txt")), java.nio.charset.StandardCharsets.UTF_8);
        List<Riddle> bank = Riddle.parse(text);
        int questions = 0;
        for (String line : text.split("\n")) if (line.startsWith("Q:")) questions++;
        assertEquals(questions, bank.size());
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Riddle r : bank) {
            org.junit.Assert.assertTrue(r.question, keys.add(r.key()));
            org.junit.Assert.assertFalse(r.question, r.answer.isEmpty());
        }
        org.junit.Assert.assertTrue(bank.size() >= 100);
    }

    private static Riddle r(String q) {
        return new Riddle(q, q, null, 1);
    }
}
