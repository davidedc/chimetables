package app.timestables;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A short sequence of screens after a session: the "you unlocked riddles" explainer and the daily
 * riddle (yesterday's answer, then today's sealed card). The Next button only appears after a pause
 * long enough to read the screen, and on a card step only once she has opened the card (broken the
 * seal or turned it over), so tapping away can't skip a step. If she walks off instead, the screen
 * times out and nothing is lost: the riddle is already saved, on the sleeping screen and in her book.
 */
final class StoryView extends LinearLayout {
    static final class Step {
        final String title;
        final View body;
        final String hint;
        final String next;
        /** Replaces the hint once the card has been opened. */
        String afterHint;
        /** Runs once when the card is opened (e.g. to remember the seal is broken). */
        Runnable afterCard;
        /** Optional card she must open (seal or flip) before Next appears. */
        RiddleCardView card;
        /** How long before taps on the card count. */
        long cardDelayMs = 1200;
        Runnable onShow;

        Step(String title, View body, String hint, String next) {
            this.title = title;
            this.body = body;
            this.hint = hint;
            this.next = next;
        }
    }

    interface Done { void onDone(); }

    private static final long MIN_READ_MS = 2200;
    private static final long MAX_READ_MS = 5000;
    private static final long PER_CHAR_MS = 30;
    /** After the card is opened: time to read it before Next shows. */
    private static final long AFTER_CARD_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView title;
    private final FrameLayout bodyBox;
    private final TextView hint;
    private final TextView dots;
    private final Button next;
    private List<Step> steps;
    private int index;
    /** On a card step: whether she has opened the card yet (Next never shows before that). */
    private boolean cardOpened;
    private Done done;
    private final Runnable reveal = this::revealNext;

    StoryView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Ui.MYSTERY_TOP, Ui.MYSTERY_BOTTOM}));
        setPadding(Ui.dp(c, 14), Ui.dp(c, 20), Ui.dp(c, 14), Ui.dp(c, 14));
        setClickable(true); // touches never fall through to the screen underneath

        title = Ui.text(c, 26, Ui.MYSTERY_GOLD, false);
        title.setTypeface(Ui.fancy(c));
        title.setMaxLines(2);
        addView(title, new LayoutParams(-1, -2));

        bodyBox = new FrameLayout(c);
        LayoutParams bp = new LayoutParams(-1, 0, 1f);
        bp.topMargin = Ui.dp(c, 12);
        bp.bottomMargin = Ui.dp(c, 8);
        addView(bodyBox, bp);

        hint = Ui.text(c, 17, Ui.NIGHT_INK, false);
        hint.setMinLines(1);
        addView(hint, new LayoutParams(-1, -2));

        dots = Ui.text(c, 15, Ui.MYSTERY_GOLD, false);
        LayoutParams dp = new LayoutParams(-1, -2);
        dp.topMargin = Ui.dp(c, 4);
        addView(dots, dp);

        // Same size and place as the done screen's button, so Next doesn't jump as she taps on.
        next = Ui.button(c, "", Ui.ORANGE, 30);
        LayoutParams np = new LayoutParams(-1, Ui.dp(c, 92));
        np.topMargin = Ui.dp(c, 6);
        addView(next, np);
        next.setOnClickListener(v -> {
            if (!next.isEnabled() || steps == null) return;
            if (index + 1 < steps.size()) show(index + 1);
            else finish();
        });
    }

    void start(List<Step> steps, Done done) {
        this.steps = steps;
        this.done = done;
        show(0);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        steps = null;
    }

    private void finish() {
        Done d = done;
        stop();
        if (d != null) d.onDone();
    }

    private void show(int i) {
        index = i;
        Step s = steps.get(i);
        handler.removeCallbacks(reveal);
        title.setText(s.title);
        bodyBox.removeAllViews();
        if (s.body.getParent() != null) ((android.view.ViewGroup) s.body.getParent()).removeView(s.body);
        bodyBox.addView(s.body, new FrameLayout.LayoutParams(-1, -1));
        s.body.setAlpha(0f);
        s.body.setTranslationY(Ui.dp(getContext(), 24));
        s.body.animate().alpha(1f).translationY(0).setDuration(380).start();
        hint.setText(s.hint);
        hint.setAlpha(1f);
        dots.setText(dots(i, steps.size()));
        dots.setVisibility(steps.size() > 1 ? VISIBLE : INVISIBLE);
        next.setText(s.next);
        next.setEnabled(false);
        next.animate().cancel();
        next.setAlpha(0f);
        next.setVisibility(INVISIBLE);
        cardOpened = false;
        if (s.card != null) {
            s.card.setTapsFrom(SystemClock.uptimeMillis() + s.cardDelayMs);
            s.card.setListener(() -> {
                if (s.afterHint != null) hint.setText(s.afterHint);
                if (cardOpened) return;
                cardOpened = true;
                if (s.afterCard != null) s.afterCard.run();
                handler.removeCallbacks(reveal);
                handler.postDelayed(reveal, AFTER_CARD_MS);
            });
        } else {
            int chars = s.title.length() + s.hint.length() + textLength(s.body);
            handler.postDelayed(reveal, Math.max(MIN_READ_MS, Math.min(MAX_READ_MS, chars * PER_CHAR_MS)));
        }
        if (s.onShow != null) s.onShow.run();
    }

    private void revealNext() {
        if (steps == null || (steps.get(index).card != null && !cardOpened)) return;
        AppLog.log(getContext(), "story: next shown on step " + (index + 1) + " of " + steps.size());
        next.setVisibility(VISIBLE);
        next.setEnabled(true);
        next.animate().alpha(1f).setDuration(350).start();
    }

    private static int textLength(View v) {
        if (v instanceof TextView) return ((TextView) v).getText().length();
        if (v instanceof android.view.ViewGroup) {
            int n = 0;
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) n += textLength(g.getChildAt(i));
            return n;
        }
        return 0;
    }

    private static CharSequence dots(int current, int count) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < count; i++) {
            int start = sb.length();
            sb.append(i == current ? "●" : "○");
            if (i < count - 1) sb.append("  ");
            if (i != current) {
                sb.setSpan(new ForegroundColorSpan(0x80F0CB6B), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }
}
