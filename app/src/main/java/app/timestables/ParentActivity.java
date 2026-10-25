package app.timestables;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
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
    private Settings settings;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = Store.get(this).settings();
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
        Store.get(this).saveSettings();
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
        Store store = Store.get(this);
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
                TreeSet<Integer> next = new TreeSet<>(settings.tables);
                if (on) next.add(table);
                else next.remove(table);
                if (next.isEmpty()) {
                    v.setChecked(true);
                    toast("Keep at least one table");
                    return;
                }
                settings.tables = next;
            });
            grid.addView(cb);
        }
        list.addView(grid);
        row(stepper("Multiply up to", settings.maxFactor, 10, 12, 2, v -> settings.maxFactor = v));

        section("Wake-up times");
        List<LocalTime> times = new ArrayList<>(settings.wakeTimes);
        Collections.sort(times);
        for (LocalTime t : times) {
            LinearLayout r = hbox();
            TextView label = Ui.text(this, 22, Ui.INK, true);
            label.setText(t.toString());
            r.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(small("Change", v -> pickTime(t, picked -> replaceTime(t, picked))));
            r.addView(small("Remove", v -> {
                settings.wakeTimes.remove(t);
                build();
            }));
            row(r);
        }
        row(small("Add a time", v -> pickTime(LocalTime.of(17, 0), picked -> {
            if (!settings.wakeTimes.contains(picked)) settings.wakeTimes.add(picked);
            build();
        })));

        section("Quiet hours (no wake-ups)");
        LinearLayout q = hbox();
        q.addView(small("From " + settings.quietStart, v -> pickTime(settings.quietStart, p -> {
            settings.quietStart = p;
            build();
        })));
        q.addView(small("To " + settings.quietEnd, v -> pickTime(settings.quietEnd, p -> {
            settings.quietEnd = p;
            build();
        })));
        row(q);

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
            Store.get(this).saveSettings();
            Alarms.scheduleTest(this, 60, true);
            toast("Turn the screen off and wait a minute");
            finish();
        }));
        row(button("Change PIN", Ui.BLUE, v -> changePin()));
        row(button("Reset all progress…", Ui.SOFT_INK, v -> new AlertDialog.Builder(this)
                .setTitle("Reset all progress?")
                .setMessage("Every card goes back to new. This cannot be undone.")
                .setPositiveButton("Reset", (d, w) -> {
                    Store.get(this).resetProgress();
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
        for (app.timestables.core.Card c : Store.get(this).activeCards()) if (c.revealMsAvg > 0) timed.add(c);
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
        for (app.timestables.core.Card c : Store.get(this).activeCards()) if (c.lapses > 0 || c.tricky) seen.add(c);
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
        Store.get(this).saveSettings();
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
            Store.get(this).setPinHash(PinHasher.hash(pin));
            AppLog.log(this, "parent PIN changed");
            toast("PIN changed");
        });
    }

    private void replaceTime(LocalTime old, LocalTime picked) {
        settings.wakeTimes.remove(old);
        if (!settings.wakeTimes.contains(picked)) settings.wakeTimes.add(picked);
        build();
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
