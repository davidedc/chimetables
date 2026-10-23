package app.timestables.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PinHasherTest {
    @Test
    public void verifiesOnlyTheRightPin() {
        String stored = PinHasher.hash("2468");
        assertTrue(PinHasher.verify("2468", stored));
        assertFalse(PinHasher.verify("2469", stored));
        assertFalse(PinHasher.verify("", stored));
        assertFalse(stored.contains("2468"));
    }

    @Test
    public void saltMakesEachHashDifferent() {
        assertNotEquals(PinHasher.hash("1357"), PinHasher.hash("1357"));
    }

    @Test
    public void rejectsMalformedStoredValues() {
        assertFalse(PinHasher.verify("1234", null));
        assertFalse(PinHasher.verify("1234", "plain1234"));
        assertFalse(PinHasher.verify(null, PinHasher.hash("1234")));
    }

    @Test
    public void pinMustBeFourToEightDigits() {
        assertTrue(PinHasher.isValidPin("0000"));
        assertTrue(PinHasher.isValidPin("12345678"));
        assertFalse(PinHasher.isValidPin("123"));
        assertFalse(PinHasher.isValidPin("123456789"));
        assertFalse(PinHasher.isValidPin("12a4"));
        assertFalse(PinHasher.isValidPin(null));
    }
}
