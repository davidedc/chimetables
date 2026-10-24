package app.timestables;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.timestables.core.Answer;
import app.timestables.core.Badges;
import app.timestables.core.Card;
import app.timestables.core.Deck;
import app.timestables.core.PinHasher;
import app.timestables.core.Rating;
import app.timestables.core.Session;
import app.timestables.core.Settings;
import app.timestables.core.Srs;
import app.timestables.core.WakePlanner;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single screen the child sees. Modes:
 * SLEEPING (screen normally off) -> CALLING (alarm woke us: chime every ~2 min until she slides to
 * start) -> QUESTION (she types the answer on a keypad) -> FEEDBACK (right, or the correct answer)
 * -> ... -> DONE ("well done", or confetti + a sticker + any new badges), then screen off.
 * Answers are graded automatically from correctness and recall speed (see {@link Answer}).
 * Tapping the top-left corner 7 times opens the PIN prompt for parent mode.
 */
public class KioskActivity extends Activity {
    public static final String ACTION_WAKE = "app.timestables.action.WAKE";

    private static final int CORNER_TAPS = 7;
    private static final long CORNER_WINDOW_MS = 5000;
    private static final long TAP_GUARD_MS = 300;
    private static final long DONE_SCREEN_MS = 6000;
    private static final long CELEBRATION_MS = 10_000;
    private static final long RIGHT_PAUSE_MS = 700;
    private static final long WRONG_PAUSE_MS = 2800;
    /** Share of cards right first time that earns confetti. */
    private static final int CELEBRATE_PERCENT = 80;
    private static final int MORNING_BEFORE_HOUR = 9;
    /** A session keeps the screen on until this long after the last touch. */
    private static final long SESSION_IDLE_MS = 3 * 60_000L;

    private enum Mode { SLEEPING, CALLING, QUESTION, FEEDBACK, DONE }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Mode mode = Mode.SLEEPING;
    private Session session;
    private int beepsPlayed;
    private long lastModeChange;
    private int cornerTaps;
    private long firstCornerTap;
    private PowerManager.WakeLock screenLock;

    // Current question
    private final StringBuilder typed = new StringBuilder();
    private long questionShownAt;
    private long firstKeyMs = -1;
    private Card feedbackCard;
    private boolean feedbackRight;
    private String feedbackTyped;

    // Done screen
    private String doneEmoji;
    private String doneTitle;
    private String doneDetail;
    private Stickers.Sticker doneSticker;

    private LinearLayout content;
    private TextView topLine;
    private TextView bigText;
    private ImageView stickerView;
    private TextView subText;
    private TextView footer;
    private Button primary;
    private SlideToStart slider;
    private LinearLayout keypad;
    private ConfettiView confetti;

    private final Runnable beepTask = this::beep;
    private final Runnable giveUpTask = this::giveUp;
    private final Runnable sleepTask = this::goToSleep;
    private final Runnable nextTask = this::next;

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
        if (intent != null && Maintenance.ACTION_END.equals(intent.getAction())) {
            intent.setAction(null);
            Maintenance.end(this, false); // onResume then re-enters lock task
        } else if (intent != null && ACTION_WAKE.equals(intent.getAction())) {
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
        if (store.maintenanceUntil() > 0 && !store.inMaintenance(now)) Maintenance.end(this, false);
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
        if (isChangingConfigurations() || mode == Mode.SLEEPING) return;
        // The screen went off mid-call or mid-session (power button, or the child walked away and
        // the idle timeout ran out). Progress is saved per answer, so just go back to sleep.
        AppLog.log(this, "screen off during " + mode + (mode == Mode.CALLING ? " after " + beepsPlayed + " beeps" : ""));
        if ((mode == Mode.QUESTION || mode == Mode.FEEDBACK) && session != null && session.answers() > 0) {
            recordSession(); // a partly done session still counts for the streak
        }
        stopCalling();
        handler.removeCallbacksAndMessages(null);
        releaseScreen();
        session = null;
        mode = Mode.SLEEPING;
        render();
        Alarms.scheduleNext(this, "screen off");
    }

    /**
     * Keeps the screen on with our own wake lock. FLAG_KEEP_SCREEN_ON is not reliable on this
     * firmware after an alarm wake (gate G4), so it is not used.
     */
    @SuppressWarnings("deprecation")
    private void holdScreen(long millis) {
        if (screenLock == null) {
            screenLock = getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "timestables:screen");
            screenLock.setReferenceCounted(false);
        }
        screenLock.acquire(millis);
    }

    private void releaseScreen() {
        if (screenLock != null && screenLock.isHeld()) screenLock.release();
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
        Settings s = Store.get(this).settings();
        holdScreen((long) s.beepRepeats * s.beepIntervalSeconds * 1000L + 30_000L);
        AppLog.log(this, "calling: screenOn=" + getSystemService(PowerManager.class).isInteractive());
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
        holdScreen(SESSION_IDLE_MS);
        AppLog.log(this, "session start: " + session.planned() + " cards");
        if (session.isEmpty()) {
            finishSession();
            return;
        }
        showQuestion();
    }

    private void showQuestion() {
        typed.setLength(0);
        firstKeyMs = -1;
        setMode(Mode.QUESTION);
        questionShownAt = lastModeChange;
    }

    private void onKey(String key) {
        if (mode != Mode.QUESTION || session == null || session.current() == null) return;
        boolean submitted = false;
        if ("⌫".equals(key)) {
            if (typed.length() > 0) typed.setLength(typed.length() - 1);
        } else if ("✓".equals(key)) {
            submitted = true;
        } else if (typed.length() < Answer.MAX_DIGITS) {
            if (firstKeyMs < 0) firstKeyMs = SystemClock.uptimeMillis() - questionShownAt;
            typed.append(key);
        }
        Answer.Check check = Answer.check(typed.toString(), session.current().answer(), submitted);
        if (check == Answer.Check.INCOMPLETE) {
            render();
        } else {
            answer(check == Answer.Check.CORRECT);
        }
    }

    private void answer(boolean right) {
        Card card = session.current();
        long now = System.currentTimeMillis();
        long totalMs = SystemClock.uptimeMillis() - questionShownAt;
        Rating rating = session.answer(Answer.rate(right, firstKeyMs), now, firstKeyMs);
        Store.get(this).saveDeck();
        AppLog.log(this, "answer " + card.id() + " typed " + typed + (right ? " right" : " wrong") + " first key "
                + firstKeyMs + "ms total " + totalMs + "ms -> " + rating + " " + card);
        AppLog.review(this, "{\"t\":" + now + ",\"card\":\"" + card.id() + "\",\"typed\":\"" + typed
                + "\",\"right\":" + right + ",\"rating\":\"" + rating + "\",\"grade\":\"" + Srs.grade(rating, firstKeyMs)
                + "\",\"firstKeyMs\":" + firstKeyMs + ",\"totalMs\":" + totalMs + ",\"state\":\"" + card.state
                + "\",\"ivl\":" + card.intervalDays + ",\"ease\":" + Math.round(card.ease * 100) / 100.0
                + ",\"lapses\":" + card.lapses + ",\"due\":" + card.dueAt + "}");
        if (!right) Chime.again();
        else if (rating == Rating.EASY) Chime.easy();
        else Chime.good();

        feedbackCard = card;
        feedbackRight = right;
        feedbackTyped = typed.toString();
        setMode(Mode.FEEDBACK);
        handler.postDelayed(nextTask, right ? RIGHT_PAUSE_MS : WRONG_PAUSE_MS);
    }

    private void next() {
        if (session == null) return;
        if (session.isDone()) finishSession();
        else showQuestion();
    }

    private void finishSession() {
        boolean celebrate = false;
        doneEmoji = "🌟";
        doneTitle = "All done for now!";
        doneDetail = "";
        doneSticker = null;
        if (session != null && !session.isEmpty()) {
            List<Badges.Badge> earned = recordSession();
            Store store = Store.get(this);
            Deck deck = store.deck();
            long today = store.srs().dayNumber(System.currentTimeMillis());
            boolean great = session.firstTryCorrect() * 100 >= session.planned() * CELEBRATE_PERCENT;
            celebrate = great || !earned.isEmpty();
            doneEmoji = session.perfect() ? "🎯" : great ? "🎉" : "🌟";
            doneTitle = session.perfect() ? "Perfect!" : great ? "Amazing!" : "Well done!";
            if (celebrate) {
                doneSticker = Stickers.pick(this, deck.stickers);
                if (doneSticker != null) {
                    deck.stickers.add(doneSticker.name);
                    store.saveDeck();
                }
            }
            StringBuilder detail = new StringBuilder();
            for (Badges.Badge b : earned) detail.append("New badge: ").append(b.emoji).append(' ').append(b.title).append('\n');
            int streak = deck.streak(today);
            if (streak >= 2) detail.append("🔥 ").append(streak).append(" days in a row!");
            doneDetail = detail.toString().trim();
            AppLog.log(this, "session done: " + session.finished() + " cards, " + session.firstTryCorrect() + "/"
                    + session.planned() + " right first time, perfect=" + session.perfect() + ", streak=" + streak
                    + ", new badges=" + earned + (doneSticker != null ? ", sticker " + doneSticker.name : ""));
        } else {
            AppLog.log(this, "session done: nothing to practise");
        }
        setMode(Mode.DONE);
        if (celebrate) {
            confetti.burst();
            Chime.fanfare();
            if (doneSticker != null) popIn(stickerView);
        } else {
            Chime.done();
        }
        handler.postDelayed(sleepTask, celebrate ? CELEBRATION_MS : DONE_SCREEN_MS);
    }

    /** Adds the session to the day's history (streaks) and awards any new badges. */
    private List<Badges.Badge> recordSession() {
        Store store = Store.get(this);
        Deck deck = store.deck();
        Srs srs = store.srs();
        long now = System.currentTimeMillis();
        long today = srs.dayNumber(now);
        boolean morning = srs.hourOfDay(now) < MORNING_BEFORE_HOUR;
        deck.recordSession(today, session.answers(), session.firstTryCorrect(), session.perfect(), morning);
        Badges.SessionResult result = new Badges.SessionResult();
        result.perfect = session.perfect();
        result.comeback = session.comeback();
        result.morning = morning;
        List<Badges.Badge> earned = Badges.evaluate(deck, store.activeCards(), store.settings(),
                wakesPerDay(store.settings()), today, result, now);
        store.saveDeck();
        if (!earned.isEmpty()) AppLog.log(this, "badges earned: " + earned);
        return earned;
    }

    private static int wakesPerDay(Settings s) {
        int n = 0;
        for (LocalTime t : s.wakeTimes) if (!WakePlanner.isQuiet(t, s.quietStart, s.quietEnd)) n++;
        return n;
    }

    private void goToSleep() {
        handler.removeCallbacksAndMessages(null);
        session = null;
        mode = Mode.SLEEPING;
        releaseScreen();
        confetti.stop();
        render();
        Alarms.scheduleNext(this, "sleep");
        Kiosk.screenOff(this);
    }

    private void setMode(Mode m) {
        mode = m;
        lastModeChange = SystemClock.uptimeMillis();
        render();
    }

    /** Stops a fast double-tap from also hitting the next screen. */
    private boolean tapAllowed() {
        return SystemClock.uptimeMillis() - lastModeChange > TAP_GUARD_MS;
    }

    private static void popIn(View v) {
        v.setScaleX(0.2f);
        v.setScaleY(0.2f);
        v.setRotation(-14);
        v.animate().scaleX(1f).scaleY(1f).rotation(0).setDuration(550)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    // ---------------------------------------------------------------- views

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 14);
        content.setPadding(pad, Ui.dp(this, 22), pad, Ui.dp(this, 14));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        topLine = Ui.text(this, 30, Ui.ORANGE, true);
        content.addView(topLine, new LinearLayout.LayoutParams(-1, -2));

        // The question (or emoji) and, on celebrations, the sticker share the big slot.
        FrameLayout hero = new FrameLayout(this);
        bigText = Ui.text(this, 96, Ui.INK, true);
        bigText.setAutoSizeTextTypeUniformWithConfiguration(36, 110, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
        bigText.setMaxLines(2);
        hero.addView(bigText, new FrameLayout.LayoutParams(-1, -1));
        stickerView = new ImageView(this);
        stickerView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stickerView.setVisibility(View.GONE);
        hero.addView(stickerView, new FrameLayout.LayoutParams(-1, -1));
        content.addView(hero, new LinearLayout.LayoutParams(-1, 0, 3f));

        subText = Ui.text(this, 64, Ui.INK, true);
        subText.setAutoSizeTextTypeUniformWithConfiguration(22, 80, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
        subText.setMaxLines(2);
        content.addView(subText, new LinearLayout.LayoutParams(-1, 0, 2f));

        // Hint / feedback / badges / streak line, just above the buttons.
        footer = Ui.text(this, 16, Ui.SOFT_INK, false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.bottomMargin = Ui.dp(this, 4);
        content.addView(footer, fp);

        primary = Ui.button(this, "", Ui.ORANGE, 30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 92));
        lp.topMargin = Ui.dp(this, 6);
        content.addView(primary, lp);
        primary.setOnClickListener(v -> {
            if (mode == Mode.DONE && tapAllowed()) goToSleep();
        });

        // Starting a session needs a deliberate slide, so pocket touches can't start one.
        slider = new SlideToStart(this);
        slider.setListener(() -> {
            if (mode == Mode.CALLING || mode == Mode.SLEEPING) startSession();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 88));
        sp.topMargin = Ui.dp(this, 6);
        content.addView(slider, sp);

        keypad = buildKeypad();
        content.addView(keypad, new LinearLayout.LayoutParams(-1, -2));

        confetti = new ConfettiView(this);
        confetti.setVisibility(View.INVISIBLE);
        root.addView(confetti, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private LinearLayout buildKeypad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "✓"}};
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : row) {
                int color = "✓".equals(key) ? Ui.GREEN : "⌫".equals(key) ? Ui.SOFT_INK : Ui.BLUE;
                Button b = Ui.button(this, key, color, 30);
                b.setOnClickListener(v -> onKey(key));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1f);
                int m = Ui.dp(this, 4);
                p.setMargins(m, m, m, m);
                line.addView(b, p);
            }
            pad.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        return pad;
    }

    private void render() {
        Store store = Store.get(this);
        long now = System.currentTimeMillis();
        boolean night = mode == Mode.SLEEPING;
        content.setBackgroundColor(night ? Ui.NIGHT : Ui.BG);
        bigText.setTextColor(night ? Ui.NIGHT_INK : Ui.INK);
        subText.setTextColor(night ? Ui.NIGHT_INK : Ui.INK);
        bigText.setVisibility(View.VISIBLE);
        stickerView.setVisibility(View.GONE);
        primary.setVisibility(View.GONE);
        slider.setVisibility(View.GONE);
        slider.reset();
        keypad.setVisibility(View.GONE);
        keypad.setAlpha(1f);
        footer.setText("");
        footer.setTextSize(16);
        footer.setMinLines(0);
        footer.setMaxLines(Integer.MAX_VALUE);
        footer.setTextColor(night ? Ui.NIGHT_INK : Ui.SOFT_INK);

        switch (mode) {
            case SLEEPING: {
                topLine.setText("");
                bigText.setText("😴");
                long next = store.nextWake();
                subText.setText(next > 0 ? "Zzz… see you at " + Alarms.formatTime(next) : "Zzz…");
                Settings s = store.settings();
                boolean due = !Session.nothingToDo(store.deck(), store.srs(), store.activeCards(), s.newPerDay, now);
                slider.setVisibility(due ? View.VISIBLE : View.GONE);
                slider.setLabel("Slide to play");
                if (store.inMaintenance(now)) {
                    footer.setText("Maintenance mode until " + Alarms.formatTime(store.maintenanceUntil()));
                } else {
                    footer.setTextSize(20);
                    footer.setText(trophyShelf(store.deck(), store.srs().dayNumber(now), Stickers.all(this).size()));
                }
                break;
            }
            case CALLING:
                topLine.setText("🔔 🔔 🔔");
                bigText.setText("Times\ntables!");
                subText.setText("👋");
                slider.setVisibility(View.VISIBLE);
                slider.setLabel("Slide to start");
                break;
            case QUESTION: {
                Card card = session.current();
                topLine.setText(progressStars());
                bigText.setText(card.a + " × " + card.b);
                subText.setText("= " + (typed.length() == 0 ? "?" : typed));
                subText.setTextColor(typed.length() == 0 ? Ui.SOFT_INK : Ui.INK);
                reserveFeedbackLines();
                if (card.tricky) footer.setText("💪 A tricky one: you can do it!");
                keypad.setVisibility(View.VISIBLE);
                break;
            }
            case FEEDBACK: {
                topLine.setText(progressStars());
                bigText.setText(feedbackCard.a + " × " + feedbackCard.b);
                subText.setText("= " + feedbackCard.answer() + (feedbackRight ? " ✓" : ""));
                subText.setTextColor(Ui.GREEN);
                reserveFeedbackLines();
                if (!feedbackRight) {
                    footer.setTextColor(Ui.INK);
                    footer.setText("Not " + feedbackTyped + " — let's remember " + feedbackCard.answer() + "!");
                }
                keypad.setVisibility(View.VISIBLE);
                keypad.setAlpha(0.35f);
                break;
            }
            case DONE:
                topLine.setText(doneSticker != null ? doneEmoji + " " + doneTitle : progressStars());
                if (doneSticker != null) {
                    bigText.setVisibility(View.INVISIBLE);
                    stickerView.setImageBitmap(doneSticker.bitmap);
                    stickerView.setVisibility(View.VISIBLE);
                    subText.setText(doneSticker.phrase);
                } else {
                    bigText.setText(doneEmoji);
                    subText.setText(doneTitle);
                }
                footer.setTextSize(20);
                footer.setTextColor(Ui.INK);
                footer.setText(doneDetail);
                primary.setVisibility(View.VISIBLE);
                primary.setText("Bye bye 👋");
                break;
        }
    }

    /**
     * Gives the footer the same two-line height on the question and feedback screens, so the
     * "let's remember" line doesn't shrink and shift the sum when it appears.
     */
    private void reserveFeedbackLines() {
        footer.setTextSize(20);
        footer.setLines(2);
    }

    /** Streak, recent badges and the sticker collection, for the sleeping screen. */
    private static String trophyShelf(Deck deck, long today, int stickersAvailable) {
        StringBuilder sb = new StringBuilder();
        int streak = deck.streak(today);
        if (streak >= 1) sb.append("🔥 ").append(streak).append(streak == 1 ? " day" : " days in a row");
        if (stickersAvailable > 0 && !deck.stickers.isEmpty()) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append("🖼️ ").append(deck.stickers.size()).append(" of ")
                    .append(stickersAvailable).append(" stickers");
        }
        if (!deck.badges.isEmpty()) {
            List<Map.Entry<String, Long>> earned = new ArrayList<>(deck.badges.entrySet());
            earned.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
            sb.append(sb.length() > 0 ? "\n" : "");
            for (int i = 0; i < Math.min(8, earned.size()); i++) {
                Badges.Badge b = Badges.byId(earned.get(i).getKey());
                if (b != null) sb.append(b.emoji).append(' ');
            }
            if (earned.size() > 8) sb.append("+").append(earned.size() - 8);
        }
        return sb.toString().trim();
    }

    /** One star per card in this session: gold once done, faint until then. Hidden for a single card. */
    private CharSequence progressStars() {
        if (session == null || session.planned() < 2) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < session.planned(); i++) {
            boolean done = i < session.finished();
            int start = sb.length();
            sb.append(done ? "★" : "☆");
            sb.setSpan(new ForegroundColorSpan(done ? Ui.GOLD : Ui.FAINT), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
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
            if (mode != Mode.SLEEPING && mode != Mode.CALLING) holdScreen(SESSION_IDLE_MS);
            if (!tapAllowed() && (mode == Mode.QUESTION || mode == Mode.DONE)) return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isTopLeftCorner(MotionEvent ev) {
        float size = Ui.dp(this, 64);
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
        if (firstTime) PinPad.choose(this, "Choose a parent PIN (4-8 digits)", pin -> checkPin(pin, true));
        else PinPad.show(this, "Parent PIN", pin -> checkPin(pin, false));
    }

    private void checkPin(String pin, boolean firstTime) {
        Store store = Store.get(this);
        if (firstTime) {
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
