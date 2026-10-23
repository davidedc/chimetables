package app.timestables.core;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Picks the next scheduled wake-up that is not inside quiet hours. */
public final class WakePlanner {
    private WakePlanner() {}

    /** Next wake strictly after {@code now}, or null if every wake time is inside quiet hours. */
    public static ZonedDateTime next(List<LocalTime> times, LocalTime quietStart, LocalTime quietEnd,
                                     ZonedDateTime now) {
        List<LocalTime> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        for (int day = 0; day <= 1; day++) {
            for (LocalTime t : sorted) {
                if (isQuiet(t, quietStart, quietEnd)) continue;
                // ZonedDateTime.of moves times that fall in a DST gap forward, so they still fire.
                ZonedDateTime candidate = ZonedDateTime.of(now.toLocalDate().plusDays(day), t, now.getZone());
                if (candidate.isAfter(now)) return candidate;
            }
        }
        return null;
    }

    /** Quiet hours may wrap midnight (19:30-07:00). Equal start and end means no quiet hours. */
    public static boolean isQuiet(LocalTime t, LocalTime start, LocalTime end) {
        if (start.equals(end)) return false;
        if (start.isBefore(end)) return !t.isBefore(start) && t.isBefore(end);
        return !t.isBefore(start) || t.isBefore(end);
    }
}
