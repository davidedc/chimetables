package app.timestables;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.timestables.core.Card;
import app.timestables.core.PinHasher;
import app.timestables.core.Rating;
import app.timestables.core.Session;
import app.timestables.core.Settings;

/**
 * The single screen the child sees. Modes:
 * SLEEPING (screen normally off) -> CALLING (alarm woke us: chime every ~2 min until touched)
 * -> QUESTION <-> ANSWER (Again/Good/Easy) -> DONE ("well done", then screen off).
 * Tapping the top-left corner 7 times opens the PIN prompt for parent mode.
 */
public class KioskActivity extends Activity {
    public static final String ACTION_WAKE = "app.timestables.action.WAKE";

    private static final int CORNER_TAPS = 7;
    private static final long CORNER_WINDOW_MS = 5000;
    private static final long TAP_GUARD_MS = 450;
    private static final long DONE_SCREEN_MS = 6000;

    private enum Mode { SLEEPING, CALLING, QUESTION, ANSWER, DONE }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Mode mode = Mode.SLEEPING;
    private Session session;
    private int beepsPlayed;
    private long lastModeChange;
    private int cornerTaps;
    private long firstCornerTap;

    private LinearLayout content;
    private TextView topLine;
    private TextView bigText;
    private TextView subText;
    private Button primary;
    private LinearLayout ratings;
    private TextView footer;

    private final Runnable beepTask = this::beep;
    private final Runnable giveUpTask = this::giveUp;
    private final Runnable sleepTask = this::goToSleep;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildViews();
        AppLog.log(this, "KioskActivity created");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_WAKE.equals(intent.getAction())) {
            intent.setAction(null); // don't re-trigger on recreation
            startCalling();
        } else if (mode == Mode.SLEEPING) {
            render();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        Store store = Store.get(this);
        long now = System.currentTimeMillis();
        if (store.maintenanceUntil() > 0 && !store.inMaintenance(now)) Maintenance.end(this);
        if (Kiosk.isDeviceOwner(this) && !store.inMaintenance(now)) {
            Kiosk.allowLockTask(this);
            Kiosk.dpm(this).setStatusBarDisabled(AdminReceiver.component(this), true);
            if (!Kiosk.isInLockTask(this)) {
                try {
                    startLockTask();
                } catch (IllegalStateException e) {
                    AppLog.log(this, "startLockTask failed: " + e.getMessage());
                }
            }
        }
        // Alarms are lost on reboot and the vendor may block BOOT_COMPLETED, so HOME re-arms them too.
        if (store.nextWake() < now) Alarms.scheduleNext(this, "resume");
        if (mode == Mode.SLEEPING) render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mode == Mode.CALLING && !isChangingConfigurations()) {
            // Screen turned off (power button) while calling: the child dismissed it.
            AppLog.log(this, "calling dismissed (screen off) after " + beepsPlayed + " beeps");
            stopCalling();
            mode = Mode.SLEEPING;
            render();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Back does nothing in the kiosk.
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------------------------------------------------------------- calling

    private void startCalling() {
        handler.removeCallbacksAndMessages(null);
        session = null;
        mode = Mode.CALLING;
        beepsPlayed = 0;
        lastModeChange = SystemClock.uptimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AppLog.log(this, "calling: screenOn=" + getSystemService(android.os.PowerManager.class).isInteractive());
        render();
        beep();
    }

    private void beep() {
        Settings s = Store.get(this).settings();
        Chime.wake(this);
        beepsPlayed++;
        long interval = s.beepIntervalSeconds * 1000L;
        handler.postDelayed(beepsPlayed < s.beepRepeats ? beepTask : giveUpTask, interval);
    }

    private void giveUp() {
        AppLog.log(this, "calling: no answer after " + beepsPlayed + " beeps, going back to sleep");
        stopCalling();
        goToSleep();
    }

    private void stopCalling() {
        handler.removeCallbacks(beepTask);
        handler.removeCallbacks(giveUpTask);
    }

    // ---------------------------------------------------------------- session

    private void startSession() {
        stopCalling();
        Store store = Store.get(this);
        Settings s = store.settings();
        session = Session.plan(store.deck(), store.srs(), store.activeCards(), s.sessionSize, s.newPerDay,
                System.currentTimeMillis());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AppLog.log(this, "session start: " + session.planned() + " cards");
        if (session.isEmpty()) {
            finishSession();
            return;
        }
        setMode(Mode.QUESTION);
    }

    private void reveal() {
        setMode(Mode.ANSWER);
    }

    private void rate(Rating rating) {
        if (session == null || session.current() == null) return;
        Card card = session.current();
        session.answer(rating, System.currentTimeMillis());
        Store.get(this).saveDeck();
        AppLog.log(this, "answer " + card.id() + " " + rating + " -> " + card);
        if (rating == Rating.EASY) Chime.easy();
        else if (rating == Rating.GOOD) Chime.good();
        else Chime.again();
        if (session.isDone()) finishSession();
        else setMode(Mode.QUESTION);
    }

    private void finishSession() {
        AppLog.log(this, "session done: " + (session == null ? 0 : session.finished()) + " cards");
        setMode(Mode.DONE);
        Chime.done();
        handler.postDelayed(sleepTask, DONE_SCREEN_MS);
    }

    private void goToSleep() {
        handler.removeCallbacksAndMessages(null);
        session = null;
        mode = Mode.SLEEPING;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render();
        Alarms.scheduleNext(this, "sleep");
        Kiosk.screenOff(this);
    }

    private void setMode(Mode m) {
        mode = m;
        lastModeChange = SystemClock.uptimeMillis();
        render();
    }

    /** Stops a fast double-tap from answering the next card too. */
    private boolean tapAllowed() {
        return SystemClock.uptimeMillis() - lastModeChange > TAP_GUARD_MS;
    }

    // ---------------------------------------------------------------- views

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, Ui.dp(this, 28), pad, Ui.dp(this, 20));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        topLine = Ui.text(this, 30, Ui.ORANGE, true);
        content.addView(topLine, new LinearLayout.LayoutParams(-1, -2));

        bigText = Ui.text(this, 96, Ui.INK, true);
        bigText.setAutoSizeTextTypeUniformWithConfiguration(36, 110, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
        bigText.setMaxLines(2);
        content.addView(bigText, new LinearLayout.LayoutParams(-1, 0, 3f));

        subText = Ui.text(this, 64, Ui.INK, true);
        subText.setAutoSizeTextTypeUniformWithConfiguration(24, 88, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
        content.addView(subText, new LinearLayout.LayoutParams(-1, 0, 2f));

        primary = Ui.button(this, "", Ui.ORANGE, 30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 96));
        lp.topMargin = Ui.dp(this, 8);
        content.addView(primary, lp);
        primary.setOnClickListener(v -> {
            if (!tapAllowed()) return;
            if (mode == Mode.CALLING) startSession();
            else if (mode == Mode.QUESTION) reveal();
            else if (mode == Mode.SLEEPING) startSession();
            else if (mode == Mode.DONE) goToSleep();
        });

        ratings = new LinearLayout(this);
        ratings.setOrientation(LinearLayout.HORIZONTAL);
        ratings.addView(ratingButton("🔁\nAgain", Ui.BLUE, Rating.AGAIN), ratingParams());
        ratings.addView(ratingButton("👍\nGood", Ui.GREEN, Rating.GOOD), ratingParams());
        ratings.addView(ratingButton("⭐\nEasy", Ui.GOLD, Rating.EASY), ratingParams());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 120));
        rp.topMargin = Ui.dp(this, 8);
        content.addView(ratings, rp);

        footer = Ui.text(this, 16, Ui.SOFT_INK, false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = Ui.dp(this, 12);
        content.addView(footer, fp);

        // Tapping the question area also reveals the answer.
        bigText.setOnClickListener(v -> {
            if (mode == Mode.QUESTION && tapAllowed()) reveal();
        });
        subText.setOnClickListener(v -> {
            if (mode == Mode.QUESTION && tapAllowed()) reveal();
        });

        setContentView(root);
    }

    private Button ratingButton(String label, int color, Rating rating) {
        Button b = Ui.button(this, label, color, 24);
        b.setOnClickListener(v -> {
            if (mode == Mode.ANSWER && tapAllowed()) rate(rating);
        });
        return b;
    }

    private LinearLayout.LayoutParams ratingParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        int m = Ui.dp(this, 4);
        p.setMargins(m, 0, m, 0);
        return p;
    }

    private void render() {
        Store store = Store.get(this);
        long now = System.currentTimeMillis();
        boolean night = mode == Mode.SLEEPING;
        content.setBackgroundColor(night ? Ui.NIGHT : Ui.BG);
        bigText.setTextColor(night ? Ui.NIGHT_INK : Ui.INK);
        subText.setTextColor(night ? Ui.NIGHT_INK : Ui.INK);
        primary.setVisibility(View.VISIBLE);
        ratings.setVisibility(View.GONE);
        footer.setText("");

        switch (mode) {
            case SLEEPING: {
                topLine.setText("");
                bigText.setText("😴");
                long next = store.nextWake();
                subText.setText(next > 0 ? "Zzz… see you at " + Alarms.formatTime(next) : "Zzz…");
                Settings s = store.settings();
                boolean due = !Session.nothingToDo(store.deck(), store.srs(), store.activeCards(), s.newPerDay, now);
                primary.setVisibility(due ? View.VISIBLE : View.GONE);
                primary.setText("Play now ▶");
                if (store.inMaintenance(now)) {
                    footer.setText("Maintenance mode until " + Alarms.formatTime(store.maintenanceUntil()));
                }
                break;
            }
            case CALLING:
                topLine.setText("🔔 🔔 🔔");
                bigText.setText("Times\ntables!");
                subText.setText("👋");
                primary.setText("Let's go! ▶");
                break;
            case QUESTION:
            case ANSWER: {
                Card card = session.current();
                topLine.setText(progressStars());
                bigText.setText(card.a + " × " + card.b);
                if (mode == Mode.QUESTION) {
                    subText.setText("= ?");
                    subText.setTextColor(Ui.SOFT_INK);
                    primary.setText("Show answer 👀");
                } else {
                    subText.setText("= " + card.answer());
                    subText.setTextColor(Ui.GREEN);
                    primary.setVisibility(View.GONE);
                    ratings.setVisibility(View.VISIBLE);
                }
                break;
            }
            case DONE:
                topLine.setText(progressStars());
                bigText.setText("🌟");
                subText.setText(session == null || session.isEmpty() ? "All done for now!" : "Well done!");
                primary.setText("Bye bye 👋");
                break;
        }
    }

    private String progressStars() {
        if (session == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < session.planned(); i++) sb.append(i < session.finished() ? "★" : "☆");
        return sb.toString();
    }

    // ---------------------------------------------------------------- parent corner

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (isTopLeftCorner(ev)) {
                long now = SystemClock.uptimeMillis();
                if (cornerTaps == 0 || now - firstCornerTap > CORNER_WINDOW_MS) {
                    cornerTaps = 0;
                    firstCornerTap = now;
                }
                if (++cornerTaps >= CORNER_TAPS) {
                    cornerTaps = 0;
                    askForPin();
                }
                return true; // corner taps never reach the cards
            }
            if (mode == Mode.CALLING) {
                startSession(); // any touch answers the call
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isTopLeftCorner(MotionEvent ev) {
        float size = Ui.dp(this, 72);
        return ev.getX() < size && ev.getY() < size;
    }

    private void askForPin() {
        Store store = Store.get(this);
        long now = System.currentTimeMillis();
        if (store.pinLockedUntil() > now) {
            Toast.makeText(this, "Try again later", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean firstTime = store.pinHash() == null;
        PinPad.show(this, firstTime ? "Choose a parent PIN (4-8 digits)" : "Parent PIN",
                pin -> checkPin(pin, firstTime));
    }

    private void checkPin(String pin, boolean firstTime) {
        Store store = Store.get(this);
        if (firstTime) {
            if (!PinHasher.isValidPin(pin)) {
                Toast.makeText(this, "PIN must be 4-8 digits", Toast.LENGTH_SHORT).show();
                return;
            }
            store.setPinHash(PinHasher.hash(pin));
            AppLog.log(this, "parent PIN set on device");
        } else {
            boolean ok = PinHasher.verify(pin, store.pinHash());
            store.recordPinResult(ok, System.currentTimeMillis());
            AppLog.log(this, "parent PIN " + (ok ? "accepted" : "rejected"));
            if (!ok) {
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        handler.removeCallbacksAndMessages(null);
        stopCalling();
        session = null;
        mode = Mode.SLEEPING;
        startActivity(new Intent(this, ParentActivity.class));
    }
}
