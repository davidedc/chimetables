package app.timestables;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * "Slide to start": the thumb has to be dragged from the left end almost to the right end.
 * Touches that don't begin on the thumb are ignored, and drifting far off the track vertically
 * or letting go early springs it back, so taps and brushes in a pocket can't start a session.
 */
final class SlideToStart extends View {
    interface Listener { void onSlid(); }

    private static final float DONE_FRACTION = 0.92f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String label = "";
    private float thumbX;
    private float grabOffset;
    private float downY;
    private boolean dragging;
    private ValueAnimator springBack;
    private Listener listener;

    SlideToStart(Context context) {
        super(context);
        trackPaint.setColor(Color.parseColor("#FFD8BD"));
        fillPaint.setColor(Ui.ORANGE);
        thumbPaint.setColor(Color.WHITE);
        thumbPaint.setShadowLayer(Ui.dp(context, 3), 0, Ui.dp(context, 1), 0x40000000);
        setLayerType(LAYER_TYPE_SOFTWARE, thumbPaint);
        labelPaint.setColor(Ui.INK);
        labelPaint.setTextSize(Ui.dp(context, 24));
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        arrowPaint.setColor(Ui.ORANGE);
        arrowPaint.setTextSize(Ui.dp(context, 34));
        arrowPaint.setTypeface(Typeface.DEFAULT_BOLD);
        arrowPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setLabel(String text) {
        label = text;
        invalidate();
    }

    /** Track and fill colours (the arrow uses the fill colour). */
    void setColors(int track, int fill) {
        trackPaint.setColor(track);
        fillPaint.setColor(fill);
        arrowPaint.setColor(fill);
        invalidate();
    }

    void setLabelSize(float px) {
        labelPaint.setTextSize(px);
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void reset() {
        if (springBack != null) springBack.cancel();
        dragging = false;
        thumbX = 0;
        invalidate();
    }

    private float maxX() {
        return Math.max(0, getWidth() - getHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        canvas.drawRoundRect(0, 0, w, h, r, r, trackPaint);
        canvas.drawRoundRect(0, 0, thumbX + h, h, r, r, fillPaint);

        float progress = maxX() == 0 ? 0 : thumbX / maxX();
        labelPaint.setAlpha((int) (255 * Math.max(0, 1 - progress * 2)));
        float textY = r - (labelPaint.descent() + labelPaint.ascent()) / 2;
        canvas.drawText(label, h + (w - h) / 2f, textY, labelPaint);

        float pad = Ui.dp(getContext(), 5);
        canvas.drawCircle(thumbX + r, r, r - pad, thumbPaint);
        float arrowY = r - (arrowPaint.descent() + arrowPaint.ascent()) / 2;
        canvas.drawText("➜", thumbX + r, arrowY, arrowPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float h = getHeight();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (ev.getX() > thumbX + h * 1.2f) return false; // must start on the thumb
                if (springBack != null) springBack.cancel();
                dragging = true;
                grabOffset = ev.getX() - thumbX;
                downY = ev.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                if (Math.abs(ev.getY() - downY) > h * 1.5f) {
                    dragging = false;
                    animateBack();
                    return true;
                }
                thumbX = Math.max(0, Math.min(maxX(), ev.getX() - grabOffset));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                if (ev.getActionMasked() == MotionEvent.ACTION_UP && thumbX >= maxX() * DONE_FRACTION) {
                    thumbX = maxX();
                    invalidate();
                    performClick();
                } else {
                    animateBack();
                }
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onSlid();
        return true;
    }

    private void animateBack() {
        springBack = ValueAnimator.ofFloat(thumbX, 0);
        springBack.setDuration(250);
        springBack.addUpdateListener(a -> {
            thumbX = (float) a.getAnimatedValue();
            invalidate();
        });
        springBack.start();
    }
}
