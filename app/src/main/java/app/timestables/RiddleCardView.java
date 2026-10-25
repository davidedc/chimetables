package app.timestables;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.timestables.core.Riddle;

import java.util.Random;

/**
 * A riddle as a two-sided card: the question on a starry midnight front with a gold border, the
 * answer on an old-parchment back. A new card can arrive sealed with a wax seal that cracks open
 * when tapped; a card whose answer is out turns over with a 3D flip; tapping a card whose answer
 * comes tomorrow makes it wobble and say so. Taps before {@link #setTapsFrom} are ignored, so a
 * tap-happy child can't skip past the question. The compact variant (sleeping screen) is front-only.
 */
final class RiddleCardView extends FrameLayout {
    /** Called after the seal is broken or the card has turned over. */
    interface Listener { void onCardAction(); }

    private final boolean compact;
    private final FrameLayout front;
    private final FrameLayout back;
    private final TextView frontTitle;
    private final TextView question;
    private final TextView frontFooter;
    private final TextView answer;
    private final TextView factLabel;
    private final TextView fact;
    private final TextView backFooter;
    private final TextView lockedNote;
    private final SealView seal;
    private boolean sealed;
    private boolean answerAvailable;
    private boolean showingBack;
    private boolean busy;
    private long tapsFrom;
    private Listener listener;

    RiddleCardView(Context c, boolean compact) {
        super(c);
        this.compact = compact;
        setCameraDistance(9000 * getResources().getDisplayMetrics().density);
        Typeface serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD);

        front = new FrameLayout(c);
        LinearLayout f = column(c, compact ? 12 : 24, compact ? 10 : 22, compact ? 10 : 16);
        frontTitle = fancy(c, compact ? 16 : 20, Ui.MYSTERY_GOLD);
        f.addView(frontTitle, new LinearLayout.LayoutParams(-1, -2));
        if (!compact) f.addView(fancy(c, 13, Ui.MYSTERY_GOLD, "✦   ✦   ✦", 0.7f), new LinearLayout.LayoutParams(-1, -2));
        question = new TextView(c);
        question.setTypeface(serifBold);
        question.setTextColor(Ui.MYSTERY_INK);
        question.setGravity(Gravity.CENTER);
        question.setLineSpacing(0, 1.12f);
        question.setAutoSizeTextTypeUniformWithConfiguration(compact ? 12 : 16, compact ? 21 : 30, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, 0, 1f);
        qp.topMargin = Ui.dp(c, compact ? 4 : 10);
        qp.bottomMargin = Ui.dp(c, compact ? 0 : 8);
        f.addView(question, qp);
        frontFooter = Ui.text(c, 14, Ui.MYSTERY_GOLD, false);
        frontFooter.setAlpha(0.85f);
        if (!compact) f.addView(frontFooter, new LinearLayout.LayoutParams(-1, -2));
        front.setBackground(new MysteryFace(c));
        front.addView(f, new LayoutParams(-1, -1));
        seal = new SealView(c);
        seal.setVisibility(GONE);
        int sealSize = Ui.dp(c, 132);
        front.addView(seal, new LayoutParams(sealSize, sealSize, Gravity.CENTER));
        addView(front, new LayoutParams(-1, -1));

        back = new FrameLayout(c);
        LinearLayout b = column(c, 24, 22, 16);
        b.addView(fancy(c, 18, Ui.PARCHMENT_EDGE, "The answer", 1f), new LinearLayout.LayoutParams(-1, -2));
        b.addView(fancy(c, 14, Ui.PARCHMENT_EDGE, "❦", 0.8f), new LinearLayout.LayoutParams(-1, -2));
        answer = new TextView(c);
        answer.setTypeface(serifBold);
        answer.setTextColor(Ui.PARCHMENT_INK);
        answer.setGravity(Gravity.CENTER);
        answer.setAutoSizeTextTypeUniformWithConfiguration(20, 46, 1, TypedValue.COMPLEX_UNIT_SP);
        b.addView(answer, new LinearLayout.LayoutParams(-1, 0, 1.2f));
        factLabel = Ui.text(c, 15, Ui.PARCHMENT_EDGE, false);
        factLabel.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
        factLabel.setText("Did you know?");
        b.addView(factLabel, new LinearLayout.LayoutParams(-1, -2));
        fact = new TextView(c);
        fact.setTypeface(Typeface.SERIF);
        fact.setTextColor(Ui.PARCHMENT_INK);
        fact.setGravity(Gravity.CENTER);
        fact.setLineSpacing(0, 1.08f);
        fact.setAutoSizeTextTypeUniformWithConfiguration(12, 17, 1, TypedValue.COMPLEX_UNIT_SP);
        b.addView(fact, new LinearLayout.LayoutParams(-1, 0, 1f));
        backFooter = fancy(c, 12, Ui.PARCHMENT_EDGE);
        backFooter.setAlpha(0.8f);
        b.addView(backFooter, new LinearLayout.LayoutParams(-1, -2));
        back.setBackground(new ParchmentFace(c));
        back.addView(b, new LayoutParams(-1, -1));
        back.setVisibility(GONE);
        addView(back, new LayoutParams(-1, -1));

        lockedNote = Ui.text(c, 18, Ui.PARCHMENT_INK, true);
        lockedNote.setText("🔒 Tomorrow!");
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Ui.MYSTERY_GOLD);
        pill.setCornerRadius(Ui.dp(c, 22));
        lockedNote.setBackground(pill);
        lockedNote.setPadding(Ui.dp(c, 18), Ui.dp(c, 8), Ui.dp(c, 18), Ui.dp(c, 8));
        lockedNote.setAlpha(0f);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        lp.bottomMargin = Ui.dp(c, 46);
        addView(lockedNote, lp);

        if (!compact) setOnClickListener(v -> onTap());
    }

    private static LinearLayout column(Context c, int side, int top, int bottom) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(Ui.dp(c, side), Ui.dp(c, top), Ui.dp(c, side), Ui.dp(c, bottom));
        return l;
    }

    private static TextView fancy(Context c, float sp, int color) {
        return fancy(c, sp, color, "", 1f);
    }

    private static TextView fancy(Context c, float sp, int color, String text, float alpha) {
        TextView t = Ui.text(c, sp, color, false);
        t.setTypeface(Ui.fancy(c));
        t.setText(text);
        t.setAlpha(alpha);
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * Shows {@code r} front up. {@code answerAvailable}: the back can be turned over (otherwise a tap
     * says "tomorrow"). {@code sealed}: the question is hidden under a wax seal until tapped.
     */
    void bind(Riddle r, int number, boolean answerAvailable, boolean sealed) {
        this.answerAvailable = answerAvailable;
        this.sealed = sealed && !compact;
        showingBack = false;
        busy = false;
        setRotationY(0);
        frontTitle.setText(numbered(getContext(), "Riddle No. ", number));
        question.setText(keepSumsTogether(r.question));
        StringBuilder level = new StringBuilder();
        for (int i = 0; i < r.level; i++) level.append("🔍");
        frontFooter.setText(answerAvailable ? level : level + "   ·   answer tomorrow");
        answer.setText(keepSumsTogether(r.answer));
        boolean hasFact = !r.fact.isEmpty();
        factLabel.setVisibility(hasFact ? VISIBLE : GONE);
        fact.setVisibility(hasFact ? VISIBLE : GONE);
        fact.setText(keepSumsTogether(r.fact));
        backFooter.setText(numbered(getContext(), "Riddle No. ", number));
        front.setVisibility(VISIBLE);
        back.setVisibility(GONE);
        seal.reset();
        seal.setVisibility(this.sealed ? VISIBLE : GONE);
        question.setAlpha(this.sealed ? 0f : 1f);
        lockedNote.animate().cancel();
        lockedNote.setAlpha(0f);
    }

    /**
     * "Riddle No. 12" with the number in a plain serif: Cinzel's old-style 1 looks like a capital I.
     */
    private static CharSequence numbered(Context c, String label, int number) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(label);
        int start = sb.length();
        sb.append(String.valueOf(number));
        sb.setSpan(new android.text.style.TypefaceSpan(Typeface.create(Typeface.SERIF, Typeface.BOLD)), start, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Non-breaking spaces around + × = − → so "4 + 7" never wraps onto two lines. */
    static String keepSumsTogether(String text) {
        return text.replaceAll(" ([+×=−→]) ", "\u00A0$1\u00A0");
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Taps are ignored until {@code uptimeMillis} (SystemClock.uptimeMillis()). */
    void setTapsFrom(long uptimeMillis) {
        tapsFrom = uptimeMillis;
    }

    private void onTap() {
        if (busy || SystemClock.uptimeMillis() < tapsFrom) return;
        if (sealed) breakSeal();
        else if (showingBack || answerAvailable) flip();
        else wobble();
    }

    private void breakSeal() {
        busy = true;
        Chime.shimmer();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(950);
        a.addUpdateListener(v -> seal.setProgress((float) v.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                seal.setVisibility(GONE);
                sealed = false;
                question.animate().alpha(1f).setDuration(450).withEndAction(() -> {
                    busy = false;
                    if (listener != null) listener.onCardAction();
                }).start();
            }
        });
        a.start();
    }

    private void flip() {
        busy = true;
        Chime.flip();
        animate().rotationY(90).setDuration(190).setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
            showingBack = !showingBack;
            front.setVisibility(showingBack ? GONE : VISIBLE);
            back.setVisibility(showingBack ? VISIBLE : GONE);
            setRotationY(-90);
            animate().rotationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                busy = false;
                if (listener != null) listener.onCardAction();
            }).start();
        }).start();
    }

    private void wobble() {
        busy = true;
        Chime.tick();
        ObjectAnimator w = ObjectAnimator.ofFloat(this, "rotationY", 0, 20, -13, 8, -4, 0);
        w.setDuration(650);
        w.start();
        lockedNote.animate().cancel();
        lockedNote.setAlpha(0f);
        lockedNote.animate().alpha(1f).setDuration(200).withEndAction(() -> {
            busy = false;
            lockedNote.animate().alpha(0f).setStartDelay(1300).setDuration(400).start();
        }).start();
    }

    // ---------------------------------------------------------------- faces

    /** Starry midnight with a double gold border. */
    private static final class MysteryFace extends Drawable {
        private final float d;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] stars = new float[4 * 46];
        private final float[] sparkles = new float[3 * 6];

        MysteryFace(Context c) {
            d = c.getResources().getDisplayMetrics().density;
            Random r = new Random(7);
            for (int i = 0; i < stars.length; i += 4) {
                stars[i] = r.nextFloat();
                stars[i + 1] = r.nextFloat();
                stars[i + 2] = (0.5f + r.nextFloat() * 1.1f) * d;
                stars[i + 3] = 50 + r.nextInt(120);
            }
            // sparkles near the edges, away from the question
            float[][] spots = {{0.12f, 0.16f}, {0.86f, 0.12f}, {0.08f, 0.55f}, {0.92f, 0.48f}, {0.15f, 0.88f},
                {0.84f, 0.86f}};
            for (int i = 0; i < spots.length; i++) {
                sparkles[3 * i] = spots[i][0];
                sparkles[3 * i + 1] = spots[i][1];
                sparkles[3 * i + 2] = (3 + r.nextInt(4)) * d;
            }
            line.setStyle(Paint.Style.STROKE);
            line.setColor(Ui.MYSTERY_GOLD);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            RectF r = new RectF(b.left + d, b.top + d, b.right - d, b.bottom - d);
            fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, Ui.MYSTERY_TOP, Ui.MYSTERY_BOTTOM, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, 18 * d, 18 * d, fill);
            for (int i = 0; i < stars.length; i += 4) {
                dot.setColor(Color.WHITE);
                dot.setAlpha((int) stars[i + 3]);
                canvas.drawCircle(b.left + stars[i] * w, b.top + stars[i + 1] * h, stars[i + 2], dot);
            }
            dot.setColor(Ui.MYSTERY_GOLD);
            dot.setAlpha(170);
            for (int i = 0; i < sparkles.length; i += 3) {
                drawSparkle(canvas, b.left + sparkles[i] * w, b.top + sparkles[i + 1] * h, sparkles[i + 2], dot);
            }
            line.setAlpha(255);
            line.setStrokeWidth(2.5f * d);
            canvas.drawRoundRect(inset(r, 3 * d), 16 * d, 16 * d, line);
            line.setAlpha(130);
            line.setStrokeWidth(1f * d);
            RectF inner = inset(r, 9 * d);
            canvas.drawRoundRect(inner, 11 * d, 11 * d, line);
            dot.setAlpha(220);
            float k = 5 * d;
            for (float[] p : new float[][] {{inner.left + k, inner.top + k}, {inner.right - k, inner.top + k},
                {inner.left + k, inner.bottom - k}, {inner.right - k, inner.bottom - k}}) {
                drawSparkle(canvas, p[0], p[1], 3.2f * d, dot);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Old paper: warm gradient, faint speckles, a brown double border. */
    private static final class ParchmentFace extends Drawable {
        private final float d;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] specks = new float[3 * 140];

        ParchmentFace(Context c) {
            d = c.getResources().getDisplayMetrics().density;
            Random r = new Random(11);
            for (int i = 0; i < specks.length; i += 3) {
                specks[i] = r.nextFloat();
                specks[i + 1] = r.nextFloat();
                specks[i + 2] = 12 + r.nextInt(30);
            }
            dot.setColor(Ui.PARCHMENT_EDGE);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(Ui.PARCHMENT_EDGE);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            RectF r = new RectF(b.left + d, b.top + d, b.right - d, b.bottom - d);
            fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, Ui.PARCHMENT_TOP, Ui.PARCHMENT_BOTTOM, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, 18 * d, 18 * d, fill);
            shade.setShader(new RadialGradient(b.exactCenterX(), b.exactCenterY(), Math.max(w, h) * 0.7f,
                    new int[] {0x00000000, 0x00000000, 0x2A6B4A1E}, new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, 18 * d, 18 * d, shade);
            for (int i = 0; i < specks.length; i += 3) {
                dot.setAlpha((int) specks[i + 2]);
                canvas.drawCircle(b.left + specks[i] * w, b.top + specks[i + 1] * h, 0.8f * d, dot);
            }
            line.setAlpha(230);
            line.setStrokeWidth(2f * d);
            canvas.drawRoundRect(inset(r, 3 * d), 16 * d, 16 * d, line);
            line.setAlpha(110);
            line.setStrokeWidth(1f * d);
            canvas.drawRoundRect(inset(r, 8 * d), 12 * d, 12 * d, line);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static RectF inset(RectF r, float by) {
        return new RectF(r.left + by, r.top + by, r.right - by, r.bottom - by);
    }

    /** A four-pointed twinkle. */
    private static void drawSparkle(Canvas canvas, float x, float y, float s, Paint p) {
        Path path = new Path();
        float t = s * 0.22f;
        path.moveTo(x, y - s);
        path.quadTo(x + t, y - t, x + s, y);
        path.quadTo(x + t, y + t, x, y + s);
        path.quadTo(x - t, y + t, x - s, y);
        path.quadTo(x - t, y - t, x, y - s);
        canvas.drawPath(path, p);
    }

    // ---------------------------------------------------------------- seal

    /** A red wax seal with a gold "?". {@link #setProgress} 0..1 cracks it and lets the halves fall away. */
    private static final class SealView extends View {
        private final Paint wax = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private float progress;

        SealView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            rim.setStyle(Paint.Style.STROKE);
            mark.setTypeface(Ui.fancy(c));
            mark.setTextAlign(Paint.Align.CENTER);
            mark.setColor(Ui.MYSTERY_GOLD);
            mark.setShadowLayer(2 * d, 0, d, 0x80000000);
            crack.setStyle(Paint.Style.STROKE);
            crack.setColor(0xFF3A0610);
            crack.setStrokeWidth(2.2f * d);
            crack.setStrokeJoin(Paint.Join.ROUND);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // shadow layer on text
        }

        void reset() {
            progress = 0;
            setAlpha(1f);
            invalidate();
        }

        void setProgress(float p) {
            progress = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, R = Math.min(cx, cy) * 0.86f;
            float crackPart = Math.min(1f, progress / 0.3f);
            float fall = Math.max(0f, (progress - 0.3f) / 0.7f);
            Path zig = zigzag(cx, cy, R);
            if (fall == 0) {
                drawSeal(canvas, cx, cy, R, 255);
                if (crackPart > 0) {
                    canvas.save();
                    canvas.clipRect(0, 0, getWidth(), cy - R * 1.1f + crackPart * R * 2.2f);
                    canvas.drawPath(zig, crack);
                    canvas.restore();
                }
                return;
            }
            int alpha = (int) (255 * (1 - fall));
            for (int side = -1; side <= 1; side += 2) {
                canvas.save();
                canvas.translate(side * fall * R * 0.9f, fall * fall * R * 1.4f);
                canvas.rotate(side * 32 * fall, cx, cy + R);
                Path half = new Path(zig);
                float edge = cx + side * R * 2f;
                half.lineTo(edge, cy + R * 2f);
                half.lineTo(edge, cy - R * 2f);
                half.close();
                canvas.clipPath(half);
                drawSeal(canvas, cx, cy, R, alpha);
                canvas.restore();
            }
        }

        private static Path zigzag(float cx, float cy, float R) {
            float[] xs = {0, 0.13f, -0.1f, 0.15f, -0.09f, 0.11f, 0};
            Path p = new Path();
            for (int i = 0; i < xs.length; i++) {
                float y = cy - R * 1.1f + i * (R * 2.2f / (xs.length - 1));
                if (i == 0) p.moveTo(cx + xs[i] * R, y);
                else p.lineTo(cx + xs[i] * R, y);
            }
            return p;
        }

        private void drawSeal(Canvas canvas, float cx, float cy, float R, int alpha) {
            Path blob = new Path();
            for (int i = 0; i <= 90; i++) {
                double t = 2 * Math.PI * i / 90;
                double rr = R * (1 + 0.045 * Math.sin(9 * t) + 0.025 * Math.sin(5 * t + 1));
                float x = cx + (float) (rr * Math.cos(t)), y = cy + (float) (rr * Math.sin(t));
                if (i == 0) blob.moveTo(x, y);
                else blob.lineTo(x, y);
            }
            blob.close();
            wax.setShader(new RadialGradient(cx - R * 0.3f, cy - R * 0.35f, R * 1.45f,
                    new int[] {0xFFE25A6C, 0xFFB3263A, 0xFF74121F}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            wax.setAlpha(alpha);
            canvas.drawPath(blob, wax);
            rim.setStrokeWidth(2 * d);
            rim.setColor(0xFF5E0D19);
            rim.setAlpha(alpha * 200 / 255);
            canvas.drawPath(blob, rim);
            rim.setStrokeWidth(2.5f * d);
            rim.setColor(0xFF5A0E1A);
            rim.setAlpha(alpha * 110 / 255);
            canvas.drawCircle(cx + d, cy + d, R * 0.7f, rim);
            rim.setColor(0xFFF28A99);
            rim.setAlpha(alpha * 120 / 255);
            canvas.drawCircle(cx, cy, R * 0.7f, rim);
            mark.setTextSize(R * 1.05f);
            mark.setAlpha(alpha);
            canvas.drawText("?", cx, cy - (mark.descent() + mark.ascent()) / 2, mark);
        }
    }
}
