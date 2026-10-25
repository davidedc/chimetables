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
import android.graphics.drawable.GradientDrawable;
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
import app.timestables.core.Riddle;
import app.timestables.core.Session;
import app.timestables.core.Settings;
import app.timestables.core.Srs;
import app.timestables.core.WakePlanner;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The single screen the child sees. Modes:
 * SLEEPING (screen normally off) -> CALLING (alarm woke us: chime every ~2 min until she slides to
 * start) -> QUESTION (she types the answer on a keypad) -> FEEDBACK (right, or the correct answer)
 * -> ... -> DONE ("well done", or confetti + a sticker + any new badges), then screen off.
 * Collecting the first sticker set unlocks riddles: from then on the first finished session of
 * each day goes on to STORY (yesterday's answer, today's sealed riddle card), and the very first
 * time it explains the unlock. On the sleeping screen "Slide to open" turns into two buttons: play
 * (the big one) or her BOOK (stickers, badges, a map of the tables, riddles). Today's riddle is shown.
 * Answers are graded automatically from correctness and recall speed (see {@link Answer}).
 * Outside quiet hours she can also wake the screen and slide to play: a normal session if anything
 * is due, otherwise a bonus round that doesn't change the schedule or earn rewards. A session left
 * unfinished (screen off) carries on where it was if she slides again within 10 minutes, so
 * restarting after a mistake doesn't give a fresh, "perfect" session.
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
    /** An unfinished session carries on if she slides again within this long. */
    private static final long RESUME_MS = 10 * 60_000L;
    /** The book and the riddle screens keep the screen on until this long after the last touch. */
    private static final long BROWSE_IDLE_MS = 90_000L;
    /** A done screen waiting for "Next" (riddles to come) goes to sleep after this long untouched. */
    private static final long DONE_STORY_WAIT_MS = 60_000L;
    /** What the screen shows, for STATUS (scripts/install.sh won't update while she's using it). */
    static volatile String currentMode = "SLEEPING";
    /** Before "Next" appears on a done screen that leads on to riddles, so a stray tap can't skip it. */
    private static final long DONE_NEXT_PAUSE_MS = 2000;
    /** The play / book buttons go back to the slider after this long without a choice. */
    private static final long CHOICE_MS = 10_000;

    private enum Mode { SLEEPING, CALLING, QUESTION, FEEDBACK, DONE, BOOK, STORY }

    /** What sliding on the sleeping screen starts. */
    private enum Play { NOTHING, SESSION, BONUS, RESUME }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Mode mode = Mode.SLEEPING;
    private Session session;
    /** A session left unfinished when the screen went off, and when (elapsedRealtime). */
    private Session paused;
    private long pausedAt;
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
    private CharSequence doneDetail;
    private Stickers.Sticker doneSticker;
    /** Screens to show after the done screen (unlock, riddles), or null. */
    private List<StoryView.Step> story;
    private boolean doneNextReady;

    private LinearLayout content;
    private FrameLayout hero;
    private RiddleCardView todayCard;
    /** After "Slide to open": play (big) or her book, in the slider's place. */
    private LinearLayout choice;
    private Button playButton;
    private boolean choosing;
    /** Holds the book or the story on top of everything but the confetti. */
    private FrameLayout overlay;
    private StoryView storyView;
    private BookView book;
    private TextView topLine;
    private TextView bigText;
    private ImageView stickerView;
    private TextView subText;
    private TextView footer;
    private LinearLayout shelf;
    private Button primary;
    private SlideToStart slider;
    private LinearLayout keypad;
    private ConfettiView confetti;

    private final Runnable beepTask = this::beep;
    private final Runnable giveUpTask = this::giveUp;
    private final Runnable sleepTask = this::goToSleep;
    private final Runnable nextTask = this::next;
    private final Runnable endChoiceTask = () -> {
        choosing = false;
        if (mode == Mode.SLEEPING) render();
    };
    private final Runnable doneNextTask = () -> {
        doneNextReady = true;
        if (mode == Mode.DONE) {
            primary.setAlpha(0f);
            primary.setVisibility(View.VISIBLE);
            primary.animate().alpha(1f).setDuration(300).start();
        }
    };

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
            if (mode == Mode.QUESTION || mode == Mode.FEEDBACK || mode == Mode.DONE || mode == Mode.STORY) {
                AppLog.log(this, "wake while playing (" + mode + "): carrying on");
            } else if (mode == Mode.BOOK && book != null) {
                // She's holding it and reading: no alarm, no snatching the book away. A soft chime
                // and a "Time to play!" banner in the book; the sleeping screen offers play too.
                AppLog.log(this, "wake while reading the book: offered play there");
                Chime.nudge();
                book.offerPlay(() -> {
                    if (mode == Mode.BOOK) startSession(false);
                });
            } else {
                startCalling();
            }
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
            Kiosk.applyScreenCapture(this);
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
        choosing = false; // the screen comes back on with the slider, never the buttons
        handler.removeCallbacks(endChoiceTask);
        if (isChangingConfigurations() || mode == Mode.SLEEPING) return;
        // The screen went off mid-call or mid-session (power button, or the child walked away and
        // the idle timeout ran out). Progress is saved per answer, so just go back to sleep.
        AppLog.log(this, "screen off during " + mode + (mode == Mode.CALLING ? " after " + beepsPlayed + " beeps" : ""));
        if ((mode == Mode.QUESTION || mode == Mode.FEEDBACK) && session != null) {
            if (session.answers() > 0 && !session.isBonus()) recordSession(); // still counts for the streak
            if (!session.isDone()) {
                paused = session;
                pausedAt = SystemClock.elapsedRealtime();
                AppLog.log(this, "session paused after " + session.answers() + " answers; carries on if resumed within "
                        + RESUME_MS / 60_000 + " min");
            }
        }
        stopCalling();
        handler.removeCallbacksAndMessages(null);
        releaseScreen();
        session = null;
        story = null;
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

    private void startSession(boolean bonus) {
        stopCalling();
        Session left = resumable();
        paused = null;
        if (left != null) {
            session = left;
            holdScreen(SESSION_IDLE_MS);
            AppLog.log(this, "session resumed: " + left.finished() + " of " + left.planned() + " cards done");
            next();
            return;
        }
        Store store = Store.get(this);
        Settings s = store.settings();
        session = bonus
                ? Session.bonus(store.deck(), store.srs(), store.activeCards(), s.sessionSize, random)
                : Session.plan(store.deck(), store.srs(), store.activeCards(), s.sessionSize, s.newPerDay,
                        System.currentTimeMillis());
        holdScreen(SESSION_IDLE_MS);
        AppLog.log(this, (bonus ? "bonus round start: " : "session start: ") + session.planned() + " cards");
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
        AppLog.log(this, (session.isBonus() ? "bonus answer " : "answer ") + card.id() + " typed " + typed
                + (right ? " right" : " wrong") + " first key " + firstKeyMs + "ms total " + totalMs + "ms -> " + rating
                + " " + card);
        AppLog.review(this, "{\"t\":" + now + ",\"card\":\"" + card.id() + "\",\"typed\":\"" + typed
                + "\",\"right\":" + right + ",\"rating\":\"" + rating + "\",\"grade\":\"" + Srs.grade(rating, firstKeyMs)
                + "\",\"firstKeyMs\":" + firstKeyMs + ",\"totalMs\":" + totalMs + ",\"state\":\"" + card.state
                + "\",\"ivl\":" + card.intervalDays + ",\"ease\":" + Math.round(card.ease * 100) / 100.0
                + ",\"lapses\":" + card.lapses + ",\"due\":" + card.dueAt
                + (session.isBonus() ? ",\"bonus\":true" : "") + "}");
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
        story = null;
        doneNextReady = false;
        Store store = Store.get(this);
        long today = store.srs().dayNumber(System.currentTimeMillis());
        if (session != null && session.isBonus()) {
            // Practice only: no streak, badges or stickers, but confetti for a great round.
            celebrate = session.firstTryCorrect() * 100 >= session.planned() * CELEBRATE_PERCENT;
            doneEmoji = celebrate ? "🎉" : "⭐";
            doneTitle = celebrate ? "Amazing bonus!" : "Bonus done!";
            AppLog.log(this, "bonus round done: " + session.firstTryCorrect() + "/" + session.planned()
                    + " right first time");
        } else if (session != null && !session.isEmpty()) {
            List<Badges.Badge> earned = recordSession();
            Deck deck = store.deck();
            boolean great = session.firstTryCorrect() * 100 >= session.planned() * CELEBRATE_PERCENT;
            celebrate = great || !earned.isEmpty();
            doneEmoji = session.perfect() ? "🎯" : great ? "🎉" : "🌟";
            doneTitle = session.perfect() ? "Perfect!" : great ? "Amazing!" : "Well done!";
            boolean newSticker = false;
            if (celebrate) {
                doneSticker = Stickers.pick(this, deck, today, store.settings().newStickerEveryDays);
                if (doneSticker != null) {
                    newSticker = deck.collectSticker(doneSticker.name, today);
                    store.saveDeck();
                }
            }
            List<CharSequence> lines = new ArrayList<>();
            if (newSticker) {
                // No total once the extra stickers are unlocked: the collection just keeps growing.
                String count = deck.unlockedDay == 0 ? " " + deck.stickers.size() + " of " + Stickers.starter(this).size() : "";
                lines.add(StickerIcon.before("New sticker!" + count, Ui.ORANGE, Ui.dp(this, 22)));
            }
            for (Badges.Badge b : earned) lines.add("New badge: " + b.emoji + ' ' + b.title);
            int streak = deck.streak(today);
            if (streak >= 2) lines.add("🔥 " + streak + " days in a row!");
            android.text.SpannableStringBuilder detail = new android.text.SpannableStringBuilder();
            for (CharSequence line : lines) detail.append(detail.length() > 0 ? "\n" : "").append(line);
            doneDetail = detail;
            AppLog.log(this, "session done: " + session.finished() + " cards, " + session.firstTryCorrect() + "/"
                    + session.planned() + " right first time, perfect=" + session.perfect() + ", streak=" + streak
                    + ", new badges=" + earned
                    + (doneSticker != null ? ", sticker " + doneSticker.name + (newSticker ? " (new)" : " (again)") : ""));
        } else {
            AppLog.log(this, "session done: nothing to practise");
        }
        if (session != null && !session.isEmpty()) story = buildStory(store, today);
        setMode(Mode.DONE);
        if (celebrate) {
            confetti.burst();
            Chime.fanfare();
            if (doneSticker != null) popIn(stickerView);
        } else {
            Chime.done();
        }
        // With riddles to come, wait for her to tap Next (shown after a pause) instead of sleeping.
        // The riddle is already saved (sealed until she opens it), so walking off loses nothing.
        if (story != null) handler.postDelayed(doneNextTask, DONE_NEXT_PAUSE_MS);
        handler.postDelayed(sleepTask, story != null ? DONE_STORY_WAIT_MS : celebrate ? CELEBRATION_MS : DONE_SCREEN_MS);
    }

    // ---------------------------------------------------------------- riddles

    /**
     * After a finished session (bonus rounds too): unlocks riddles once the first sticker set is
     * complete, and deals a riddle at the first finished session of each day. The riddle is saved
     * straight away, so walking off before the screens below loses nothing: it is on the sleeping
     * screen and in the book. Returns the screens to show, or null.
     */
    private List<StoryView.Step> buildStory(Store store, long today) {
        Deck deck = store.deck();
        List<Riddle> bank = RiddleBank.get(this);
        if (bank.isEmpty()) return null;
        if (deck.unlockReady(Stickers.starter(this))) {
            deck.unlockedDay = today;
            AppLog.log(this, "riddles and more stickers unlocked");
        }
        if (deck.unlockedDay == 0) return null;
        List<StoryView.Step> steps = new ArrayList<>();
        boolean explain = !deck.unlockSeen;
        if (explain) steps.addAll(unlockSteps());
        Riddle current = null;
        boolean sealed = true;
        if (deck.riddleDue(today)) {
            if (!deck.riddles.isEmpty()) {
                Deck.DealtRiddle last = deck.riddles.get(deck.riddles.size() - 1);
                Riddle previous = Deck.findRiddle(bank, last.key);
                if (previous != null) {
                    steps.add(answerStep(previous, deck.riddles.size(),
                            today - last.day == 1 ? "Yesterday's riddle" : "Your last riddle"));
                }
            }
            current = deck.dealRiddle(bank, today);
            AppLog.log(this, "riddle " + deck.riddles.size() + " dealt: " + current.question.replace('\n', ' '));
        } else if (explain && !deck.riddles.isEmpty()) {
            // The explainer was left half-way earlier today: end it with today's riddle again.
            current = Deck.findRiddle(bank, deck.latestRiddle().key);
            sealed = !deck.latestRiddle().opened;
        }
        store.saveDeck();
        if (current != null) steps.add(riddleStep(current, deck.riddles.size(), sealed, explain));
        return steps.isEmpty() ? null : steps;
    }

    private List<StoryView.Step> unlockSteps() {
        List<StoryView.Step> steps = new ArrayList<>();
        List<String> starter = Stickers.starter(this);
        if (!starter.isEmpty()) {
            StoryView.Step all = new StoryView.Step("All " + starter.size() + " stickers!", stickerCollage(starter),
                    "You collected the whole set!", "Next ➜");
            all.onShow = () -> {
                confetti.burst();
                Chime.fanfare();
            };
            steps.add(all);
        }
        StoryView.Step riddles = new StoryView.Step("You unlocked…", reveal("🧩", "Riddles"),
                "A brand-new kind of card, just for you.", "Next ➜");
        riddles.onShow = Chime::shimmer;
        steps.add(riddles);
        steps.add(new StoryView.Step("How riddles work", storyText("Every day, your first play brings a new "
                + "riddle card.\n\nThink about it all day long…\n\nThe next day, you turn the card over to find "
                + "the answer!"), "", "Next ➜"));
        List<String> more = Stickers.more(this);
        if (!more.isEmpty()) {
            steps.add(new StoryView.Step("And…", stickerReveal(),
                    "You unlocked more stickers too. New ones will turn up as you play!", "Next ➜"));
        }
        steps.add(new StoryView.Step("Your book", reveal("📒", "My book"),
                "Your stickers, badges, riddles and a map of your times tables. On the sleeping screen, "
                        + "slide to open, then tap 📒 Book.", "Next ➜"));
        return steps;
    }

    private StoryView.Step answerStep(Riddle r, int number, String title) {
        RiddleCardView card = new RiddleCardView(this, false);
        card.bind(r, number, true, false);
        StoryView.Step step = new StoryView.Step(title, card, "Tap the card to turn it over", "Next ➜");
        step.afterHint = "Did you get it?";
        step.card = card;
        // Time to read the question again before it can be turned over.
        step.cardDelayMs = Math.min(4500, 1500 + 30L * r.question.length());
        return step;
    }

    /** Today's card. {@code endsExplainer}: reaching it means she has seen the unlock screens. */
    private StoryView.Step riddleStep(Riddle r, int number, boolean sealed, boolean endsExplainer) {
        RiddleCardView card = new RiddleCardView(this, false);
        card.bind(r, number, false, sealed);
        StoryView.Step step = new StoryView.Step(number == 1 ? "Your first riddle" : "Today's riddle", card,
                sealed ? "Tap the seal to open it" : "Think about it… the answer comes tomorrow!", "Bye bye 👋");
        step.afterHint = "Think about it… the answer comes tomorrow!";
        if (sealed) {
            step.card = card;
            step.afterCard = this::markRiddleOpened;
        }
        if (endsExplainer) {
            step.onShow = () -> {
                Store store = Store.get(this);
                store.deck().unlockSeen = true;
                store.saveDeck();
            };
        }
        return step;
    }

    /** The seal of the newest riddle has been broken: it stays open everywhere from now on. */
    void markRiddleOpened() {
        Store store = Store.get(this);
        Deck.DealtRiddle latest = store.deck().latestRiddle();
        if (latest == null || latest.opened) return;
        latest.opened = true;
        store.saveDeck();
        AppLog.log(this, "riddle " + store.deck().riddles.size() + " opened");
    }

    private View reveal(String emoji, String word) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView e = Ui.text(this, 104, Ui.INK, false);
        e.setText(emoji);
        box.addView(e);
        TextView w = Ui.text(this, 40, Ui.MYSTERY_GOLD, false);
        w.setTypeface(Ui.fancy(this));
        w.setText(word);
        box.addView(w);
        e.setScaleX(0.3f);
        e.setScaleY(0.3f);
        e.animate().scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(600)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
        return box;
    }

    /** The sticker icon, big and gold, for "more stickers". */
    private View stickerReveal() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        View icon = new View(this);
        icon.setBackground(new StickerIcon(Ui.MYSTERY_GOLD, Ui.MYSTERY_GOLD, 14));
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 132)));
        TextView w = Ui.text(this, 40, Ui.MYSTERY_GOLD, false);
        w.setTypeface(Ui.fancy(this));
        w.setText("More stickers");
        w.setPadding(0, Ui.dp(this, 14), 0, 0);
        box.addView(w);
        icon.setScaleX(0.3f);
        icon.setScaleY(0.3f);
        icon.setRotation(-20);
        icon.animate().scaleX(1f).scaleY(1f).rotation(0).setStartDelay(200).setDuration(600)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
        return box;
    }

    private TextView storyText(String text) {
        TextView t = Ui.text(this, 23, Ui.MYSTERY_INK, false);
        t.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD));
        t.setLineSpacing(0, 1.1f);
        t.setText(text);
        return t;
    }

    private View stickerCollage(List<String> names) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);
        LinearLayout row = null;
        for (int i = 0; i < names.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                grid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
            }
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageBitmap(Stickers.load(this, names.get(i), 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            int m = Ui.dp(this, 3);
            lp.setMargins(m, m, m, m);
            row.addView(img, lp);
            popIn(img);
        }
        return grid;
    }

    private void startStory() {
        if (story == null) {
            goToSleep();
            return;
        }
        handler.removeCallbacks(sleepTask);
        handler.removeCallbacks(doneNextTask);
        setMode(Mode.STORY);
        holdScreen(BROWSE_IDLE_MS);
        storyView = new StoryView(this);
        overlay.removeAllViews();
        overlay.addView(storyView, new FrameLayout.LayoutParams(-1, -1));
        overlay.setVisibility(View.VISIBLE);
        List<StoryView.Step> steps = story;
        story = null;
        AppLog.log(this, "riddle screens: " + steps.size());
        storyView.start(steps, this::goToSleep);
    }

    // ---------------------------------------------------------------- open: play or book

    private void showChoice() {
        choosing = true;
        lastModeChange = SystemClock.uptimeMillis(); // the finger lifting off the slider isn't a tap
        render();
        choice.setAlpha(0f);
        choice.setScaleX(0.94f);
        choice.setScaleY(0.94f);
        choice.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        handler.removeCallbacks(endChoiceTask);
        handler.postDelayed(endChoiceTask, CHOICE_MS);
    }

    private void onChoice(boolean play) {
        if (mode != Mode.SLEEPING || !choosing || !tapAllowed()) return;
        handler.removeCallbacks(endChoiceTask);
        choosing = false;
        Store store = Store.get(this);
        if (quietNow(store.settings())) {
            render();
        } else if (!play) {
            openBook();
        } else {
            Play offered = playOffered(store, System.currentTimeMillis());
            if (offered == Play.NOTHING) render();
            else startSession(offered == Play.BONUS);
        }
    }

    private static boolean quietNow(Settings s) {
        return WakePlanner.isQuiet(LocalTime.now(), s.quietStart, s.quietEnd);
    }

    // ---------------------------------------------------------------- book

    private void openBook() {
        setMode(Mode.BOOK);
        holdScreen(BROWSE_IDLE_MS);
        overlay.removeAllViews();
        book = new BookView(this, this::closeBook, this::markRiddleOpened);
        overlay.addView(book, new FrameLayout.LayoutParams(-1, -1));
        overlay.setVisibility(View.VISIBLE);
        AppLog.log(this, "book opened");
    }

    private void closeBook() {
        if (mode != Mode.BOOK) return;
        setMode(Mode.SLEEPING);
        releaseScreen(); // back to the normal screen timeout
    }

    private void closeOverlay() {
        if (storyView != null) storyView.stop();
        storyView = null;
        book = null;
        if (overlay.getChildCount() > 0) overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
    }

    /** Adds the session to the day's history (streaks) and awards any new badges. */
    private List<Badges.Badge> recordSession() {
        Store store = Store.get(this);
        Deck deck = store.deck();
        Srs srs = store.srs();
        long now = System.currentTimeMillis();
        long today = srs.dayNumber(now);
        boolean morning = srs.hourOfDay(now) < MORNING_BEFORE_HOUR;
        deck.recordSession(today, !session.recorded(), session.unrecordedAnswers(), session.unrecordedCorrect(),
                session.perfect(), morning);
        session.markRecorded();
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
        story = null;
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

        // The question (or emoji) and, on celebrations, the sticker share the big slot; while
        // sleeping, today's riddle card takes it once riddles are unlocked.
        hero = new FrameLayout(this);
        bigText = Ui.text(this, 96, Ui.INK, true);
        bigText.setAutoSizeTextTypeUniformWithConfiguration(36, 110, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
        bigText.setMaxLines(2);
        hero.addView(bigText, new FrameLayout.LayoutParams(-1, -1));
        stickerView = new ImageView(this);
        stickerView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stickerView.setVisibility(View.GONE);
        hero.addView(stickerView, new FrameLayout.LayoutParams(-1, -1));
        todayCard = new RiddleCardView(this, true);
        todayCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams tcp = new FrameLayout.LayoutParams(-1, -1);
        tcp.setMargins(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        hero.addView(todayCard, tcp);
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

        shelf = new LinearLayout(this);
        shelf.setGravity(Gravity.CENTER);
        shelf.setVisibility(View.GONE);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 34));
        shp.bottomMargin = Ui.dp(this, 6);
        content.addView(shelf, shp);

        primary = Ui.button(this, "", Ui.ORANGE, 30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 92));
        lp.topMargin = Ui.dp(this, 6);
        content.addView(primary, lp);
        primary.setOnClickListener(v -> {
            if (mode != Mode.DONE || !tapAllowed()) return;
            if (story != null) {
                if (doneNextReady) startStory();
            } else {
                goToSleep();
            }
        });

        // Starting a session needs a deliberate slide, so pocket touches can't start one.
        slider = new SlideToStart(this);
        slider.setListener(() -> {
            if (mode == Mode.CALLING) {
                startSession(false);
            } else if (mode == Mode.SLEEPING) {
                // Decide again now: the screen may have been on since quiet hours began.
                Store store = Store.get(this);
                if (quietNow(store.settings())) render();
                else if (playOffered(store, System.currentTimeMillis()) == Play.NOTHING) openBook();
                else showChoice();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 88));
        sp.topMargin = Ui.dp(this, 6);
        content.addView(slider, sp);

        // The slide proved it isn't a pocket, so plain buttons are safe here. Playing is the big one,
        // on the right: where the slide ends, under a right-handed thumb.
        choice = new LinearLayout(this);
        choice.setVisibility(View.GONE);
        Button bookButton = Ui.button(this, "📒 Book", Ui.BOOK_PURPLE, 23);
        bookButton.setOnClickListener(v -> onChoice(false));
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(0, -1, 1f);
        bbp.rightMargin = Ui.dp(this, 8);
        choice.addView(bookButton, bbp);
        playButton = Ui.button(this, "", Ui.ORANGE, 27);
        playButton.setOnClickListener(v -> onChoice(true));
        choice.addView(playButton, new LinearLayout.LayoutParams(0, -1, 1.75f));
        content.addView(choice, new LinearLayout.LayoutParams(sp));

        keypad = buildKeypad();
        content.addView(keypad, new LinearLayout.LayoutParams(-1, -2));

        overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

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
        currentMode = mode.name();
        Store store = Store.get(this);
        long now = System.currentTimeMillis();
        boolean night = mode == Mode.SLEEPING;
        content.setBackgroundColor(night ? Ui.NIGHT : Ui.BG);
        bigText.setTextColor(night ? Ui.NIGHT_INK : Ui.INK);
        subText.setTextColor(night ? Ui.NIGHT_INK : Ui.INK);
        if (mode != Mode.BOOK && mode != Mode.STORY) closeOverlay();
        bigText.setVisibility(View.VISIBLE);
        stickerView.setVisibility(View.GONE);
        todayCard.setVisibility(View.GONE);
        topLine.setVisibility(night ? View.GONE : View.VISIBLE);
        setHeroWeights(3f, 2f);
        subText.setMaxLines(2);
        primary.setVisibility(View.GONE);
        primary.animate().cancel();
        primary.setAlpha(1f);
        slider.setVisibility(View.GONE);
        slider.reset();
        if (mode != Mode.SLEEPING) choosing = false;
        choice.setVisibility(View.GONE);
        keypad.setVisibility(View.GONE);
        keypad.setAlpha(1f);
        footer.setText("");
        footer.setVisibility(View.VISIBLE);
        shelf.setVisibility(View.GONE);
        footer.setTextSize(16);
        footer.setMinLines(0);
        footer.setMaxLines(Integer.MAX_VALUE);
        footer.setTextColor(night ? Ui.NIGHT_INK : Ui.SOFT_INK);

        switch (mode) {
            case SLEEPING: {
                topLine.setText("");
                bigText.setText("😴");
                long next = store.nextWake();
                String zzz = next > 0 ? "Zzz… see you at " + Alarms.formatTime(next) : "Zzz…";
                subText.setText(zzz);
                Riddle latest = latestRiddle(store.deck());
                if (latest != null) {
                    // Today's riddle to think about; the sleepy face moves down next to the time.
                    bigText.setVisibility(View.GONE);
                    todayCard.bind(latest, store.deck().riddles.size(), false, !store.deck().latestRiddle().opened);
                    todayCard.setVisibility(View.VISIBLE);
                    subText.setText("😴 " + zzz);
                    subText.setMaxLines(1);
                    setHeroWeights(3.4f, 0.8f);
                }
                // One slider; after it, play or her book (nothing at all in quiet hours).
                boolean quiet = quietNow(store.settings());
                Play play = playOffered(store, now);
                if (quiet) {
                    choosing = false;
                } else if (choosing && play != Play.NOTHING) {
                    playButton.setText(play == Play.BONUS ? "⭐ Bonus round" : play == Play.RESUME ? "↩ Carry on" : "▶ Play");
                    choice.setVisibility(View.VISIBLE);
                } else {
                    slider.setVisibility(View.VISIBLE);
                    slider.setLabel("Slide to open");
                }
                if (store.inMaintenance(now)) {
                    footer.setText("Maintenance mode until " + Alarms.formatTime(store.maintenanceUntil()));
                } else {
                    footer.setVisibility(View.GONE);
                    fillShelf(store.deck(), store.srs().dayNumber(now), Stickers.starter(this).size());
                }
                break;
            }
            case CALLING:
                topLine.setText("🔔 🔔 🔔");
                bigText.setText("Times\ntables!");
                subText.setText("👋");
                slider.setVisibility(View.VISIBLE);
                slider.setLabel("Slide to start");
                Deck deck = store.deck();
                if (deck.riddleDue(store.srs().dayNumber(now))) {
                    footer.setTextSize(20);
                    footer.setTextColor(Ui.INK);
                    footer.setText(deck.riddles.isEmpty() ? "🧩 A new riddle is waiting!" : "🧩 Your riddle answer is waiting!");
                }
                break;
            case QUESTION: {
                Card card = session.current();
                topLine.setText(progressStars());
                bigText.setText(card.a + " × " + card.b);
                subText.setText("= " + (typed.length() == 0 ? "?" : typed));
                subText.setTextColor(typed.length() == 0 ? Ui.SOFT_INK : Ui.INK);
                reserveFeedbackLines();
                if (card.tricky) footer.setText("💪 A tricky one: you can do it!");
                else if (session.isBonus()) footer.setText("⭐ Bonus round");
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
                primary.setVisibility(story == null || doneNextReady ? View.VISIBLE : View.INVISIBLE);
                primary.setText(story == null ? "Bye bye 👋" : "Next ➜");
                break;
            case BOOK:
            case STORY:
                break;
        }
    }

    /**
     * Nothing during quiet hours; otherwise the unfinished session if it was left in the last
     * 10 minutes, a new session if anything is due, else a bonus round.
     */
    private Play playOffered(Store store, long now) {
        Settings s = store.settings();
        if (WakePlanner.isQuiet(LocalTime.now(), s.quietStart, s.quietEnd)) return Play.NOTHING;
        if (resumable() != null) return Play.RESUME;
        List<Card> active = store.activeCards();
        if (!Session.nothingToDo(store.deck(), store.srs(), active, s.newPerDay, now)) return Play.SESSION;
        return Session.bonusAvailable(active) ? Play.BONUS : Play.NOTHING;
    }

    /** The paused session if it was left less than {@link #RESUME_MS} ago; otherwise forgets it. */
    private Session resumable() {
        if (paused != null && SystemClock.elapsedRealtime() - pausedAt > RESUME_MS) paused = null;
        return paused;
    }

    /**
     * Gives the footer the same two-line height on the question and feedback screens, so the
     * "let's remember" line doesn't shrink and shift the sum when it appears.
     */
    private void reserveFeedbackLines() {
        footer.setTextSize(20);
        footer.setLines(2);
    }

    private void setHeroWeights(float heroWeight, float subWeight) {
        LinearLayout.LayoutParams h = (LinearLayout.LayoutParams) hero.getLayoutParams();
        LinearLayout.LayoutParams s = (LinearLayout.LayoutParams) subText.getLayoutParams();
        if (h.weight == heroWeight && s.weight == subWeight) return;
        h.weight = heroWeight;
        s.weight = subWeight;
        hero.setLayoutParams(h);
        subText.setLayoutParams(s);
    }

    /** The newest riddle she has been dealt (today's, or the one waiting for its answer), or null. */
    private Riddle latestRiddle(Deck deck) {
        if (deck.unlockedDay == 0 || deck.riddles.isEmpty()) return null;
        return Deck.findRiddle(RiddleBank.get(this), deck.riddles.get(deck.riddles.size() - 1).key);
    }

    /**
     * The sleeping screen's summary: streak, stickers and badges as three small chips. The details
     * (which badges, which stickers) are in her book.
     */
    private void fillShelf(Deck deck, long today, int firstSet) {
        shelf.removeAllViews();
        int streak = deck.streak(today);
        if (streak >= 1) addChip("🔥 " + streak + (streak == 1 ? " day" : " days"));
        if (!deck.stickers.isEmpty()) {
            // Before the unlock she is collecting a set of known size; after it, no total.
            addChip(StickerIcon.before(deck.stickers.size() + (deck.unlockedDay == 0 && firstSet > 0 ? " of " + firstSet : ""),
                    Ui.NIGHT_INK, Ui.dp(this, 19)));
        }
        if (!deck.badges.isEmpty()) addChip("🏅 " + deck.badges.size());
        shelf.setVisibility(shelf.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void addChip(CharSequence text) {
        TextView chip = Ui.text(this, 16, Ui.NIGHT_INK, true);
        chip.setText(text);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.NIGHT_CHIP);
        bg.setCornerRadius(Ui.dp(this, 17));
        chip.setBackground(bg);
        chip.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        shelf.addView(chip, lp);
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
            if (mode == Mode.BOOK) holdScreen(BROWSE_IDLE_MS);
            else if (mode != Mode.SLEEPING && mode != Mode.CALLING) holdScreen(SESSION_IDLE_MS);
            if (!tapAllowed() && (mode == Mode.QUESTION || mode == Mode.DONE || mode == Mode.BOOK
                    || mode == Mode.STORY)) return true;
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
        story = null;
        mode = Mode.SLEEPING;
        currentMode = "PARENT";
        closeOverlay();
        startActivity(new Intent(this, ParentActivity.class));
    }
}
