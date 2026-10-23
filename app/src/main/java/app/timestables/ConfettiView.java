package app.timestables;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/** Falling confetti drawn over the screen for a few seconds. Never takes touches. */
final class ConfettiView extends View {
    private static final int COUNT = 110;
    private static final long DURATION_MS = 5000;
    private static final int[] COLORS = {
        Color.parseColor("#FF8A3D"), Color.parseColor("#43A047"), Color.parseColor("#42A5F5"),
        Color.parseColor("#FFB300"), Color.parseColor("#E91E63"), Color.parseColor("#AB47BC"),
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float[] x = new float[COUNT], y = new float[COUNT], vx = new float[COUNT], vy = new float[COUNT];
    private final float[] angle = new float[COUNT], spin = new float[COUNT], size = new float[COUNT];
    private final int[] color = new int[COUNT];
    private long startedAt;
    private long lastFrame;

    ConfettiView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
    }

    void burst() {
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < COUNT; i++) {
            x[i] = random.nextFloat() * w;
            y[i] = -random.nextFloat() * h * 0.6f;
            vx[i] = (random.nextFloat() - 0.5f) * 60 * d;
            vy[i] = (120 + random.nextFloat() * 160) * d;
            angle[i] = random.nextFloat() * 360;
            spin[i] = (random.nextFloat() - 0.5f) * 720;
            size[i] = (5 + random.nextFloat() * 6) * d;
            color[i] = COLORS[random.nextInt(COLORS.length)];
        }
        startedAt = lastFrame = SystemClock.uptimeMillis();
        setVisibility(VISIBLE);
        invalidate();
    }

    void stop() {
        startedAt = 0;
        setVisibility(INVISIBLE); // INVISIBLE, not GONE: keeps its size for the next burst
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (startedAt == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        long age = now - startedAt;
        if (age > DURATION_MS) {
            stop();
            return;
        }
        int alpha = age < DURATION_MS - 800 ? 255 : (int) (255 * (DURATION_MS - age) / 800f);
        for (int i = 0; i < COUNT; i++) {
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            angle[i] += spin[i] * dt;
            paint.setColor(color[i]);
            paint.setAlpha(alpha);
            canvas.save();
            canvas.rotate(angle[i], x[i], y[i]);
            canvas.drawRect(x[i] - size[i] / 2, y[i] - size[i] / 4, x[i] + size[i] / 2, y[i] + size[i] / 4, paint);
            canvas.restore();
        }
        postInvalidateOnAnimation();
    }
}
