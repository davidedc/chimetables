package app.timestables.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** JSON (de)serialisation of {@link Deck} and {@link Settings}. Unknown or missing fields fall back to defaults. */
public final class Json {
    private static final int VERSION = 1;

    private Json() {}

    public static String deckToJson(Deck deck) {
        try {
            JSONArray cards = new JSONArray();
            for (Card c : deck.cards.values()) {
                cards.put(new JSONObject()
                        .put("a", c.a).put("b", c.b)
                        .put("state", c.state.name())
                        .put("ease", c.ease)
                        .put("ivl", c.intervalDays)
                        .put("due", c.dueAt)
                        .put("step", c.step)
                        .put("reps", c.reps)
                        .put("lapses", c.lapses)
                        .put("seen", c.seen)
                        .put("correct", c.correct)
                        .put("last", c.lastSeenAt)
                        .put("missed", c.missedAt)
                        .put("tricky", c.tricky)
                        .put("revealMs", c.revealMsAvg));
            }
            JSONObject days = new JSONObject();
            for (java.util.Map.Entry<Long, Deck.DayStats> e : deck.days.entrySet()) {
                Deck.DayStats d = e.getValue();
                days.put(String.valueOf(e.getKey()), new JSONObject()
                        .put("sessions", d.sessions)
                        .put("answers", d.answers)
                        .put("correct", d.correct)
                        .put("perfect", d.perfect)
                        .put("morning", d.morning));
            }
            JSONObject badges = new JSONObject();
            for (java.util.Map.Entry<String, Long> e : deck.badges.entrySet()) badges.put(e.getKey(), e.getValue());
            JSONArray riddles = new JSONArray();
            for (Deck.DealtRiddle r : deck.riddles) {
                riddles.put(new JSONObject().put("key", r.key).put("day", r.day).put("opened", r.opened));
            }
            return new JSONObject()
                    .put("version", VERSION)
                    .put("newDay", deck.newDay)
                    .put("newCount", deck.newCount)
                    .put("fastRun", deck.fastRun)
                    .put("fastRunBest", deck.fastRunBest)
                    .put("days", days)
                    .put("badges", badges)
                    .put("stickers", new JSONArray(deck.stickers))
                    .put("stickerDay", deck.stickerDay)
                    .put("unlockedDay", deck.unlockedDay)
                    .put("unlockSeen", deck.unlockSeen)
                    .put("riddles", riddles)
                    .put("cards", cards)
                    .toString(1);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Deck deckFromJson(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        Deck deck = new Deck();
        deck.newDay = root.optLong("newDay");
        deck.newCount = root.optInt("newCount");
        deck.fastRun = root.optInt("fastRun");
        deck.fastRunBest = root.optInt("fastRunBest");
        JSONObject days = root.optJSONObject("days");
        if (days != null) {
            for (java.util.Iterator<String> it = days.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject o = days.getJSONObject(key);
                Deck.DayStats d = new Deck.DayStats();
                d.sessions = o.optInt("sessions");
                d.answers = o.optInt("answers");
                d.correct = o.optInt("correct");
                d.perfect = o.optInt("perfect");
                d.morning = o.optBoolean("morning");
                deck.days.put(Long.parseLong(key), d);
            }
        }
        JSONArray stickers = root.optJSONArray("stickers");
        for (int i = 0; stickers != null && i < stickers.length(); i++) deck.stickers.add(stickers.getString(i));
        deck.stickerDay = root.optLong("stickerDay");
        deck.unlockedDay = root.optLong("unlockedDay");
        deck.unlockSeen = root.optBoolean("unlockSeen");
        JSONArray riddles = root.optJSONArray("riddles");
        for (int i = 0; riddles != null && i < riddles.length(); i++) {
            JSONObject r = riddles.getJSONObject(i);
            Deck.DealtRiddle dealt = new Deck.DealtRiddle(r.getString("key"), r.getLong("day"));
            dealt.opened = r.optBoolean("opened");
            deck.riddles.add(dealt);
        }
        JSONObject badges = root.optJSONObject("badges");
        if (badges != null) {
            for (java.util.Iterator<String> it = badges.keys(); it.hasNext(); ) {
                String key = it.next();
                deck.badges.put(key, badges.getLong(key));
            }
        }
        JSONArray cards = root.optJSONArray("cards");
        for (int i = 0; cards != null && i < cards.length(); i++) {
            JSONObject o = cards.getJSONObject(i);
            Card c = new Card(o.getInt("a"), o.getInt("b"));
            c.state = Card.State.valueOf(o.optString("state", "NEW"));
            c.ease = o.optDouble("ease", Srs.START_EASE);
            c.intervalDays = o.optInt("ivl");
            c.dueAt = o.optLong("due");
            c.step = o.optInt("step");
            c.reps = o.optInt("reps");
            c.lapses = o.optInt("lapses");
            c.seen = o.optInt("seen");
            c.correct = o.optInt("correct");
            c.lastSeenAt = o.optLong("last");
            c.missedAt = o.optLong("missed");
            c.tricky = o.optBoolean("tricky");
            c.revealMsAvg = o.optLong("revealMs");
            deck.cards.put(c.id(), c);
        }
        return deck;
    }

    public static String settingsToJson(Settings s) {
        try {
            return new JSONObject()
                    .put("version", VERSION)
                    .put("tables", new JSONArray(s.tables))
                    .put("maxFactor", s.maxFactor)
                    .put("week", s.week)
                    .put(Settings.SCHOOL, planToJson(s.school))
                    .put(Settings.HOLIDAY, planToJson(s.holiday))
                    .put("sessionSize", s.sessionSize)
                    .put("newPerDay", s.newPerDay)
                    .put("volumePercent", s.volumePercent)
                    .put("beepRepeats", s.beepRepeats)
                    .put("beepIntervalSeconds", s.beepIntervalSeconds)
                    .put("brightness", s.brightness)
                    .put("screenTimeoutSeconds", s.screenTimeoutSeconds)
                    .put("newStickerEveryDays", s.newStickerEveryDays)
                    .toString(1);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Settings settingsFromJson(String text) throws JSONException {
        return settingsFromJson(text, new Settings());
    }

    /** Applies the fields present in {@code text} on top of {@code s} (which is modified and returned). */
    public static Settings settingsFromJson(String text, Settings s) throws JSONException {
        JSONObject o = new JSONObject(text);
        JSONArray tables = o.optJSONArray("tables");
        if (tables != null) {
            s.tables = new TreeSet<>();
            for (int i = 0; i < tables.length(); i++) s.tables.add(tables.getInt(i));
        }
        s.maxFactor = o.optInt("maxFactor", s.maxFactor);
        if (o.has("week")) {
            String week = o.getString("week");
            if (!Settings.SCHOOL.equals(week) && !Settings.HOLIDAY.equals(week)) {
                throw new JSONException("week must be \"" + Settings.SCHOOL + "\" or \"" + Settings.HOLIDAY + "\"");
            }
            s.week = week;
        }
        if (o.has(Settings.SCHOOL)) planFromJson(o.getJSONObject(Settings.SCHOOL), s.school);
        if (o.has(Settings.HOLIDAY)) planFromJson(o.getJSONObject(Settings.HOLIDAY), s.holiday);
        if (o.has("wakeTimes") && !o.has(Settings.SCHOOL) && !o.has(Settings.HOLIDAY)) {
            // Saved before week plans: one list for every day. Those were holiday times, so they
            // become the holiday plan, still in use; school weeks start from the defaults.
            JSONArray times = o.getJSONArray("wakeTimes");
            List<LocalTime> list = new ArrayList<>();
            for (int i = 0; i < times.length(); i++) list.add(LocalTime.parse(times.getString(i)));
            s.holiday = WeekPlan.everyDay(list,
                    o.has("quietStart") ? LocalTime.parse(o.getString("quietStart")) : s.holiday.quietStart,
                    o.has("quietEnd") ? LocalTime.parse(o.getString("quietEnd")) : s.holiday.quietEnd);
            s.week = Settings.HOLIDAY;
        }
        s.sessionSize = o.optInt("sessionSize", s.sessionSize);
        s.newPerDay = o.optInt("newPerDay", s.newPerDay);
        s.volumePercent = o.optInt("volumePercent", s.volumePercent);
        s.beepRepeats = o.optInt("beepRepeats", s.beepRepeats);
        s.beepIntervalSeconds = o.optInt("beepIntervalSeconds", s.beepIntervalSeconds);
        s.brightness = o.optInt("brightness", s.brightness);
        s.screenTimeoutSeconds = o.optInt("screenTimeoutSeconds", s.screenTimeoutSeconds);
        s.newStickerEveryDays = o.optInt("newStickerEveryDays", s.newStickerEveryDays);
        return s;
    }

    private static JSONObject planToJson(WeekPlan p) throws JSONException {
        JSONObject o = new JSONObject()
                .put("quietStart", p.quietStart.toString())
                .put("quietEnd", p.quietEnd.toString());
        for (DayOfWeek d : DayOfWeek.values()) {
            JSONArray times = new JSONArray();
            for (LocalTime t : p.on(d)) times.put(t.toString());
            o.put(WeekPlan.key(d), times);
        }
        return o;
    }

    /** Applies the days and quiet hours present in {@code o} to {@code p}; days left out keep their times. */
    private static void planFromJson(JSONObject o, WeekPlan p) throws JSONException {
        if (o.has("quietStart")) p.quietStart = LocalTime.parse(o.getString("quietStart"));
        if (o.has("quietEnd")) p.quietEnd = LocalTime.parse(o.getString("quietEnd"));
        for (DayOfWeek d : DayOfWeek.values()) {
            JSONArray times = o.optJSONArray(WeekPlan.key(d));
            if (times == null) continue;
            List<LocalTime> list = new ArrayList<>();
            for (int i = 0; i < times.length(); i++) list.add(LocalTime.parse(times.getString(i)));
            p.set(d, list);
        }
    }
}
