package app.timestables.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TricksTest {
    @Test
    public void ninesTakeTheOtherNumberAwayFromTenTimesIt() {
        assertEquals("9 × 6 = 60 − 6 = 54", Tricks.nines(9, 6));
        assertEquals("7 × 9 = 70 − 7 = 63", Tricks.nines(7, 9));
        assertEquals("9 × 9 = 90 − 9 = 81", Tricks.nines(9, 9));
        assertEquals("12 × 9 = 120 − 12 = 108", Tricks.nines(12, 9));
    }

    @Test
    public void noTrickWhereNoneIsNeeded() {
        assertNull(Tricks.nines(9, 10));
        assertNull(Tricks.nines(10, 9));
        assertNull(Tricks.nines(9, 1));
        assertNull(Tricks.nines(7, 8));
    }
}
