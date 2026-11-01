package app.timestables;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import app.timestables.core.Deck;
import app.timestables.core.PinHasher;
import app.timestables.core.Settings;
import app.timestables.core.WeekPlan;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** PIN-protected settings. Changes are saved and applied when leaving the screen. */
public class ParentActivity extends Activity {
    private static final long IDLE_CLOSE_MS = 3 * 60_000L;
    /** True while parent mode is on screen: a scheduled wake-up then leaves it alone. */
    static volatile boolean open;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleClose = this::finish;
    private Store store;
    private Settings settings;
    /** The week plan the times editor shows: the one in use when parent mode opens. */
    private String editing;
    private LinearLayout list;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.fixedTextSize(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        settings = store.settings();
        editing = settings.week;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        list.setPadding(pad, pad, pad, Ui.dp(this, 48));
        scroll.addView(list);
        setContentView(scroll);
        build();
        AppLog.log(this, "parent mode opened");
    }

    @Override
    protected void onResume() {
        super.onResume();
        open = true;
        onUserInteraction();
    }

    @Override
    public void onUserInteraction() {
        handler.removeCallbacks(idleClose);
        handler.postDelayed(idleClose, IDLE_CLOSE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        open = false;
        handler.removeCallbacks(idleClose);
        store.saveSettings();
        AppLog.log(this, "settings saved: " + settings.week + " week; school " + settings.school
                + "; holiday " + settings.holiday);
        Kiosk.applyDisplaySettings(this, settings);
        Alarms.scheduleNext(this, "settings");
    }

    /**
     * The screen went off (or something covered us): close, so whoever turns the screen on next finds
     * the kiosk, never an unlocked parent mode.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !isFinishing()) {
            AppLog.log(this, "parent mode closed: screen off");
            finish();
        }
    }

    private void build() {
        list.removeAllViews();
        long now = System.currentTimeMillis();

        title("Parent settings");

        section("Progress");
        Deck.Stats st = Deck.stats(store.activeCards(), now);
        Deck deck = store.deck();
        long today = store.srs().dayNumber(now);
        body(st.total + " cards in the selected tables\n"
                + st.mastered + " mastered (interval ≥ 3 weeks), " + st.learning + " learning, " + st.unseen + " not seen yet\n"
                + st.due + " due now · " + st.tricky + " tricky · new today: " + (deck.newDay == today ? deck.newCount : 0)
                + "/" + settings.newPerDay + "\n"
                + st.answers + " answers, " + st.accuracyPercent() + "% Good/Easy\n"
                + "Streak: " + deck.streak(today) + " day(s) · sessions so far: " + deck.totalSessions() + "\n"
                + "Stickers collected: " + deck.stickers.size() + " · new ones left: "
                + (deck.stickersLeft(Stickers.starter(this)) + deck.stickersLeft(Stickers.more(this))) + "\n"
                + "Riddles: " + (deck.unlockedDay > 0 ? deck.riddles.size() + " of " + RiddleBank.get(this).size() + " dealt" : "not unlocked yet") + "\n"
                + "Next wake-up: " + Alarms.format(store.nextWake()));
        body(hardestCards());
        body(slowestCards());

        section("Badges (" + deck.badges.size() + ")");
        body(badgeList(deck));

        section("Tables");
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        for (int t = 1; t <= 12; t++) {
            final int table = t;
            CheckBox cb = new CheckBox(this);
            cb.setText(t + "×");
            cb.setTextSize(20);
            cb.setChecked(settings.tables.contains(t));
            cb.setOnCheckedChangeListener((v, on) -> {
                if (on) {
                    if (settings.tables.contains(table)) return;
                    TreeSet<Integer> next = new TreeSet<>(settings.tables);
                    next.add(table);
                    settings.tables = next;
                    AppLog.log(this, "parent: " + table + "x table on");
                    return;
                }
                if (!settings.tables.contains(table)) return; // set back by the dialog below
                // A tap while scrolling can untick a box without anyone noticing: tick it again
                // and only switch the table off once confirmed.
                v.setChecked(true);
                if (settings.tables.size() == 1) {
                    toast("Keep at least one table");
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Stop practising the " + table + "× table?")
                        .setMessage("Facts only in this table, like " + table + "\u00A0×\u00A0" + table
                                + ", won't come up any more. Her progress on them is kept.")
                        .setPositiveButton("Stop it", (d, w) -> {
                            TreeSet<Integer> next = new TreeSet<>(settings.tables);
                            next.remove(table);
                            settings.tables = next;
                            AppLog.log(this, "parent: " + table + "x table off");
                            v.setChecked(false);
                        })
                        .setNegativeButton("Keep it", null)
                        .show();
            });
            grid.addView(cb);
        }
        list.addView(grid);
        row(stepper("Multiply up to", settings.maxFactor, 10, 12, 2, v -> settings.maxFactor = v));

        section("Wake-up times");
        weekPlans();

        section("Sessions");
        row(stepper("Cards per session", settings.sessionSize, 1, 20, 1, v -> settings.sessionSize = v));
        row(stepper("New cards per day", settings.newPerDay, 0, 20, 1, v -> settings.newPerDay = v));
        row(stepper("Beeps if no answer", settings.beepRepeats, 1, 10, 1, v -> settings.beepRepeats = v));

        section("Sound and screen");
        body("Beep volume");
        row(slider(settings.volumePercent, 0, 100, v -> settings.volumePercent = v));
        row(small("Test beep", v -> Chime.wake(this)));
        body("Brightness");
        row(slider(settings.brightness, 10, 255, v -> {
            settings.brightness = v;
            Kiosk.applyDisplaySettings(this, settings);
        }));

        section("Tools");
        row(button("Test wake-up in 1 minute", Ui.ORANGE, v -> {
            store.saveSettings();
            Alarms.scheduleTest(this, 60, true);
            toast("Turn the screen off and wait a minute");
            finish();
        }));
        row(button("Change PIN", Ui.BLUE, v -> changePin()));
        row(button("Reset all progress…", Ui.SOFT_INK, v -> new AlertDialog.Builder(this)
                .setTitle("Reset all progress?")
                .setMessage("Every card goes back to new. This cannot be undone.")
                .setPositiveButton("Reset", (d, w) -> {
                    store.resetProgress();
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show()));
        if (store.inMaintenance(now)) {
            row(button("Resume kiosk now", Ui.GREEN, v -> {
                Maintenance.end(this);
                finish();
            }));
        } else {
            row(button("Exit kiosk for 30 min (maintenance)", Ui.SOFT_INK, v -> exitKiosk()));
        }
        row(button("Done", Ui.GREEN, v -> finish()));
    }

    private static String badgeList(Deck deck) {
        if (deck.badges.isEmpty()) return "None yet.";
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM");
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Long> e : deck.badges.entrySet()) {
            app.timestables.core.Badges.Badge b = app.timestables.core.Badges.byId(e.getKey());
            if (b == null) continue;
            sb.append(b.emoji).append(' ').append(b.title).append(" — ").append(b.description).append(" (")
                    .append(fmt.format(java.time.Instant.ofEpochMilli(e.getValue()).atZone(java.time.ZoneId.systemDefault())))
                    .append(")\n");
        }
        return sb.toString().trim();
    }

    /** Slowest to recall on average: right but not yet automatic. */
    private String slowestCards() {
        List<app.timestables.core.Card> timed = new ArrayList<>();
        for (app.timestables.core.Card c : store.activeCards()) if (c.revealMsAvg > 0) timed.add(c);
        if (timed.isEmpty()) return "";
        Collections.sort(timed, (a, b) -> Long.compare(b.revealMsAvg, a.revealMsAvg));
        StringBuilder sb = new StringBuilder("Slowest: ");
        for (int i = 0; i < Math.min(6, timed.size()); i++) {
            app.timestables.core.Card c = timed.get(i);
            sb.append(i == 0 ? "" : ", ").append(c.a).append("×").append(c.b)
                    .append(" (").append(Math.round(c.revealMsAvg / 100.0) / 10.0).append("s)");
        }
        return sb.toString();
    }

    private String hardestCards() {
        List<app.timestables.core.Card> seen = new ArrayList<>();
        for (app.timestables.core.Card c : store.activeCards()) if (c.lapses > 0 || c.tricky) seen.add(c);
        if (seen.isEmpty()) return "No forgotten cards yet.";
        Collections.sort(seen, (a, b) -> a.lapses != b.lapses
                ? Integer.compare(b.lapses, a.lapses) : Double.compare(a.ease, b.ease));
        StringBuilder sb = new StringBuilder("Trickiest: ");
        for (int i = 0; i < Math.min(6, seen.size()); i++) {
            app.timestables.core.Card c = seen.get(i);
            sb.append(i == 0 ? "" : ", ").append(c.a).append("×").append(c.b).append(c.tricky ? " 💪" : "");
        }
        return sb.toString();
    }

    private void exitKiosk() {
        store.saveSettings();
        AppLog.log(this, "parent: exit kiosk for maintenance");
        Maintenance.start(this);
        // A lock screen hidden behind the kiosk would otherwise appear over Settings.
        getSystemService(android.app.KeyguardManager.class).requestDismissKeyguard(this, null);
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            toast("Settings unavailable: " + e.getMessage());
        }
        finish();
    }

    private void changePin() {
        PinPad.choose(this, "New PIN (4-8 digits)", pin -> {
            store.setPinHash(PinHasher.hash(pin));
            AppLog.log(this, "parent PIN changed");
            toast("PIN changed");
        });
    }

    // ---------------------------------------------------------------- week plans

    private static String weekName(String which) {
        return Settings.HOLIDAY.equals(which) ? "Holiday weeks" : "School weeks";
    }

    /**
     * Two tabs (school weeks, holiday weeks; ✓ marks the one in use), then the shown plan's times
     * day by day, and its quiet hours. Each day can copy the day before, all of it or one time.
     */
    private void weekPlans() {
        LinearLayout tabs = hbox();
        for (String which : new String[] {Settings.SCHOOL, Settings.HOLIDAY}) {
            boolean shown = which.equals(editing);
            Button b = Ui.button(this, (which.equals(settings.week) ? "✓ " : "") + weekName(which),
                    shown ? Ui.BOOK_PURPLE : Ui.FAINT, 16);
            if (!shown) b.setTextColor(Ui.INK);
            b.setOnClickListener(v -> {
                editing = which;
                build();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
            if (which.equals(Settings.SCHOOL)) p.setMarginEnd(Ui.dp(this, 8));
            tabs.addView(b, p);
        }
        row(tabs);

        LinearLayout use = hbox();
        TextView inUse = Ui.text(this, 16, Ui.SOFT_INK, false);
        inUse.setGravity(Gravity.START);
        use.addView(inUse, new LinearLayout.LayoutParams(0, -2, 1f));
        if (editing.equals(settings.week)) {
            inUse.setText("In use now.");
        } else {
            inUse.setText("Not in use.");
            use.addView(small("Use from now", v -> {
                settings.week = editing;
                AppLog.log(this, "parent: " + weekName(editing).toLowerCase(java.util.Locale.UK) + " in use");
                build();
            }));
        }
        row(use);

        WeekPlan plan = settings.plan(editing);
        for (DayOfWeek d : DayOfWeek.values()) dayTimes(plan, d);

        TextView qt = Ui.text(this, 18, Ui.INK, true);
        qt.setGravity(Gravity.START);
        qt.setText("Quiet hours");
        qt.setPadding(0, Ui.dp(this, 14), 0, 0);
        list.addView(qt);
        body("No calls and no playing.");
        LinearLayout q = hbox();
        q.addView(small("From " + plan.quietStart, v -> pickTime(plan.quietStart, p -> {
            plan.quietStart = p;
            build();
        })));
        q.addView(small("To " + plan.quietEnd, v -> pickTime(plan.quietEnd, p -> {
            plan.quietEnd = p;
            build();
        })));
        row(q);
    }

    /** One day: its name, "copy the day before", and its times as buttons (tap to change or remove). */
    private void dayTimes(WeekPlan plan, DayOfWeek d) {
        String name = d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK);
        String before = WeekPlan.shortName(d.minus(1));
        boolean first = d == DayOfWeek.MONDAY;

        LinearLayout head = hbox();
        TextView label = Ui.text(this, 18, Ui.INK, true);
        label.setGravity(Gravity.START);
        label.setText(name);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!first && plan.sameAsPreviousDay(d)) {
            TextView same = Ui.text(this, 15, Ui.SOFT_INK, false);
            same.setText("same as " + before);
            head.addView(same);
        } else if (!first) {
            head.addView(small("Copy " + before, v -> {
                plan.copyPreviousDay(d);
                build();
            }));
        }
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        hp.topMargin = Ui.dp(this, 10);
        list.addView(head, hp);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        List<LocalTime> times = plan.on(d);
        for (LocalTime t : times) {
            boolean quiet = plan.isQuiet(t);
            Button b = Ui.button(this, t.toString(), quiet ? Ui.FAINT : Ui.BLUE, 16);
            if (quiet) {
                // Inside quiet hours: kept, but it won't ring.
                b.setTextColor(Ui.SOFT_INK);
                b.setPaintFlags(b.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            }
            b.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(name + " " + t + (quiet ? " (in quiet hours: no call)" : ""))
                    .setItems(new String[] {"Change time…", "Remove"}, (dlg, which) -> {
                        if (which == 0) {
                            pickTime(t, picked -> {
                                plan.remove(d, t);
                                plan.add(d, picked);
                                build();
                            });
                        } else {
                            plan.remove(d, t);
                            build();
                        }
                    })
                    .show());
            grid.addView(b, cell());
        }
        if (times.size() < WeekPlan.MAX_PER_DAY) {
            Button add = Ui.button(this, "+", Ui.GREEN, 20);
            add.setOnClickListener(v -> addTime(plan, d, name, first));
            grid.addView(add, cell());
        }
        list.addView(grid, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Offers the day before's times that this day doesn't have yet, or any other time. */
    private void addTime(WeekPlan plan, DayOfWeek d, String name, boolean first) {
        List<LocalTime> offer = new ArrayList<>();
        if (!first) for (LocalTime t : plan.on(d.minus(1))) if (!plan.on(d).contains(t)) offer.add(t);
        TimeCallback add = picked -> {
            plan.add(d, picked);
            build();
        };
        List<LocalTime> today = plan.on(d);
        LocalTime suggest = today.isEmpty() ? LocalTime.of(17, 0) : today.get(today.size() - 1).plusHours(1);
        if (offer.isEmpty()) {
            pickTime(suggest, add);
            return;
        }
        String[] items = new String[offer.size() + 1];
        String from = d.minus(1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK);
        for (int i = 0; i < offer.size(); i++) items[i] = offer.get(i) + "  (as on " + from + ")";
        items[offer.size()] = "Another time…";
        new AlertDialog.Builder(this)
                .setTitle("Add a call on " + name)
                .setItems(items, (dlg, which) -> {
                    if (which < offer.size()) add.onTime(offer.get(which));
                    else pickTime(suggest, add);
                })
                .show();
    }

    private GridLayout.LayoutParams cell() {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
        p.width = 0;
        p.height = Ui.dp(this, 44);
        int m = Ui.dp(this, 3);
        p.setMargins(m, m, m, m);
        return p;
    }

    private interface TimeCallback { void onTime(LocalTime t); }

    private interface IntCallback { void onValue(int v); }

    private void pickTime(LocalTime initial, TimeCallback cb) {
        new TimePickerDialog(this, (v, h, m) -> cb.onTime(LocalTime.of(h, m)),
                initial.getHour(), initial.getMinute(), true).show();
    }

    // ---------------------------------------------------------------- view helpers

    private void title(String s) {
        TextView t = Ui.text(this, 28, Ui.INK, true);
        t.setText(s);
        list.addView(t);
    }

    private void section(String s) {
        TextView t = Ui.text(this, 20, Ui.ORANGE, true);
        t.setGravity(Gravity.START);
        t.setText(s);
        t.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 4));
        list.addView(t);
    }

    private void body(String s) {
        TextView t = Ui.text(this, 16, Ui.INK, false);
        t.setGravity(Gravity.START);
        t.setText(s);
        list.addView(t);
    }

    private void row(View v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(this, 6);
        list.addView(v, p);
    }

    private LinearLayout hbox() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = Ui.button(this, label, color, 18);
        b.setMinHeight(Ui.dp(this, 56));
        b.setOnClickListener(l);
        return b;
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = Ui.button(this, label, Ui.BLUE, 16);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
        p.setMarginStart(Ui.dp(this, 6));
        b.setLayoutParams(p);
        b.setOnClickListener(l);
        return b;
    }

    private View stepper(String label, int value, int min, int max, int step, IntCallback cb) {
        LinearLayout r = hbox();
        TextView name = Ui.text(this, 18, Ui.INK, false);
        name.setGravity(Gravity.START);
        name.setText(label);
        r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        final int[] current = {value};
        final TextView shown = Ui.text(this, 22, Ui.INK, true);
        shown.setTypeface(Typeface.DEFAULT_BOLD);
        shown.setText(String.valueOf(value));
        Button minus = small("−", v -> {
            current[0] = Math.max(min, current[0] - step);
            shown.setText(String.valueOf(current[0]));
            cb.onValue(current[0]);
        });
        Button plus = small("+", v -> {
            current[0] = Math.min(max, current[0] + step);
            shown.setText(String.valueOf(current[0]));
            cb.onValue(current[0]);
        });
        r.addView(minus);
        r.addView(shown, new LinearLayout.LayoutParams(Ui.dp(this, 48), -2));
        r.addView(plus);
        return r;
    }

    private SeekBar slider(int value, int min, int max, IntCallback cb) {
        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setMinimumHeight(Ui.dp(this, 40));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) cb.onValue(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return bar;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
