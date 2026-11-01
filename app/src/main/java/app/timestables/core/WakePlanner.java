package app.timestables.core;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/** Picks the next scheduled wake-up that is not inside quiet hours. */
public final class WakePlanner {
    private WakePlanner() {}

    /** Next wake strictly after {@code now}, or null if no day has a time outside quiet hours. */
    public static ZonedDateTime next(WeekPlan plan, ZonedDateTime now) {
        // Up to a week ahead: some days may have no calls at all.
        for (int day = 0; day <= 7; day++) {
            LocalDate date = now.toLocalDate().plusDays(day);
            for (LocalTime t : plan.on(date.getDayOfWeek())) {
                if (plan.isQuiet(t)) continue;
                // ZonedDateTime.of moves times that fall in a DST gap forward, so they still fire.
                ZonedDateTime candidate = ZonedDateTime.of(date, t, now.getZone());
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
