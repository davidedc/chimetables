package app.timestables.core;

import static app.timestables.core.Answer.Check.CORRECT;
import static app.timestables.core.Answer.Check.INCOMPLETE;
import static app.timestables.core.Answer.Check.WRONG;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AnswerTest {
    @Test
    public void rightNumberIsAcceptedAsSoonAsItIsTyped() {
        assertEquals(INCOMPLETE, Answer.check("", 56, false));
        assertEquals(INCOMPLETE, Answer.check("5", 56, false));
        assertEquals(CORRECT, Answer.check("56", 56, false));
        assertEquals(CORRECT, Answer.check("4", 4, false));
        assertEquals(CORRECT, Answer.check("144", 144, false));
    }

    @Test
    public void wrongNumberIsMarkedOnceItIsAsLongAsTheAnswer() {
        assertEquals(WRONG, Answer.check("54", 56, false)); // no free retries
        assertEquals(WRONG, Answer.check("6", 4, false));
        assertEquals(INCOMPLETE, Answer.check("10", 100, false));
        assertEquals(WRONG, Answer.check("108", 100, false));
    }

    @Test
    public void submittingEarlyIsWrongUnlessRight() {
        assertEquals(WRONG, Answer.check("10", 100, true));
        assertEquals(WRONG, Answer.check("5", 56, true));
        assertEquals(INCOMPLETE, Answer.check("", 56, true));
    }

    @Test
    public void gradeComesFromCorrectnessAndRecallSpeed() {
        assertEquals(Rating.AGAIN, Answer.rate(false, 1000));
        assertEquals(Rating.EASY, Answer.rate(true, 1500));
        assertEquals(Rating.EASY, Answer.rate(true, 3000));
        assertEquals(Rating.GOOD, Answer.rate(true, 4500));
        assertEquals(Rating.GOOD, Answer.rate(true, -1));
        // A slow right answer is Good here, and the scheduler turns it into Hard.
        assertEquals(Srs.Grade.HARD, Srs.grade(Answer.rate(true, 12_000), 12_000));
    }
}
