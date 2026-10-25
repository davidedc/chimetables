package app.timestables.core;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Parent-configurable settings. Defaults follow the spec, with tables extended to 12 x 12. */
public final class Settings {
    public Set<Integer> tables = new TreeSet<>(Arrays.asList(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
    /** Largest second factor (10 or 12). */
    public int maxFactor = 12;
    public List<LocalTime> wakeTimes = new ArrayList<>(Arrays.asList(
            LocalTime.of(7, 45), LocalTime.of(12, 30), LocalTime.of(16, 0), LocalTime.of(18, 0)));
    public LocalTime quietStart = LocalTime.of(19, 30);
    public LocalTime quietEnd = LocalTime.of(7, 0);
    public int sessionSize = 5;
    public int newPerDay = 5;
    /** Alarm-stream volume as a percentage of the device maximum. */
    public int volumePercent = 80;
    public int beepRepeats = 5;
    public int beepIntervalSeconds = 120;
    /** 0-255; applied as a fixed (non-adaptive) brightness. */
    public int brightness = 110;
    public int screenTimeoutSeconds = 30;
    /** Once riddles are unlocked, a new sticker turns up at most every this many days. */
    public int newStickerEveryDays = 3;
}
