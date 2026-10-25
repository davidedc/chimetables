package app.timestables;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import app.timestables.core.Card;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The times-table grid, one square per fact (row × column), coloured by how well she knows it:
 * not met yet, learning, knows it, mastered (gold). Squares for facts she has met show the answer.
 */
final class TablesMapView extends View {
    static final int[] COLORS = {
        Color.parseColor("#ECE2D4"), Color.parseColor("#FFE2B4"), Color.parseColor("#FFC85A"), Color.parseColor("#F2A200"),
    };
    static final String[] NAMES = {"Not met yet", "Learning", "Know it", "Mastered"};

    private final Paint cell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private List<Integer> factors = new java.util.ArrayList<>();
    private Set<Integer> tables = new TreeSet<>();
    private Map<String, Card> cards = new java.util.HashMap<>();

    TablesMapView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setColor(Ui.SOFT_INK);
        number.setTextAlign(Paint.Align.CENTER);
        number.setColor(Ui.INK);
    }

    void bind(Set<Integer> tables, int maxFactor, Map<String, Card> cards) {
        TreeSet<Integer> f = new TreeSet<>(tables);
        for (int i = 2; i <= maxFactor; i++) f.add(i);
        this.factors = new java.util.ArrayList<>(f);
        this.tables = tables;
        this.cards = cards;
        invalidate();
    }

    /** 0 not met, 1 learning, 2 knows it, 3 mastered. */
    static int level(Card c) {
        if (c == null || c.state == Card.State.NEW) return 0;
        if (c.isMastered()) return 3;
        if (c.state == Card.State.REVIEW && !c.tricky) return 2;
        return 1;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = factors.size();
        if (n == 0) return;
        float size = getWidth() / (n + 1f);
        float gap = Math.max(1f, 1.5f * d);
        label.setTextSize(size * 0.42f);
        number.setTextSize(size * 0.36f);
        float labelY = size / 2 - (label.descent() + label.ascent()) / 2;
        canvas.drawText("×", size / 2, labelY, label);
        for (int i = 0; i < n; i++) {
            String f = String.valueOf(factors.get(i));
            canvas.drawText(f, (i + 1.5f) * size, labelY, label);
            canvas.drawText(f, size / 2, (i + 1) * size + labelY, label);
        }
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int a = factors.get(r), b = factors.get(c);
                if (!tables.contains(a) && !tables.contains(b)) continue;
                Card card = cards.get(Card.id(a, b));
                int lvl = level(card);
                cell.setColor(COLORS[lvl]);
                float x = (c + 1) * size, y = (r + 1) * size;
                canvas.drawRoundRect(new RectF(x + gap, y + gap, x + size - gap, y + size - gap), size * 0.18f, size * 0.18f, cell);
                if (lvl > 0) {
                    number.setTypeface(lvl == 3 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    canvas.drawText(String.valueOf(a * b), x + size / 2, y + size / 2 - (number.descent() + number.ascent()) / 2, number);
                }
            }
        }
    }
}
