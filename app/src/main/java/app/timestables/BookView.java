package app.timestables;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.timestables.core.Badges;
import app.timestables.core.Card;
import app.timestables.core.Deck;
import app.timestables.core.Riddle;
import app.timestables.core.Settings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "My book": her stickers, badges, a map of every times-table fact and, once unlocked, her riddle
 * cards. Look-only: nothing here can be earned or changed. Tabs at the top, or swipe sideways.
 */
final class BookView extends FrameLayout {
    interface Listener { void onClose(); }

    private static final int STICKERS = 0, BADGES = 1, MAP = 2, RIDDLES = 3;
    /** dp from the screen edge to the title, tabs, headings and grids. */
    private static final int EDGE = 15;
    private static final String[] TABS = {"Stickers", "🏅 Badges", "🗺️ Map", "🧩 Riddles"};
    /** Badges that stay a surprise until earned. "Lightning" is about speed, which she is never shown. */
    private static final List<String> SECRET = java.util.Arrays.asList("speedy", "comeback");

    private final Store store;
    /** Called when she breaks the seal of the newest riddle here. */
    private final Runnable onSealBroken;
    private final long today;
    private final LinearLayout tabs;
    private final FrameLayout page;
    private final List<TextView> tabViews = new ArrayList<>();
    private final boolean riddlesOpen;
    private int current = -1;
    private int riddleIndex = -1;
    private float downX, downY;
    private boolean swiping;

    BookView(Context c, Listener listener, Runnable onSealBroken) {
        super(c);
        store = Store.get(c);
        this.onSealBroken = onSealBroken;
        today = store.srs().dayNumber(System.currentTimeMillis());
        riddlesOpen = store.deck().unlockedDay > 0;
        setBackgroundColor(Ui.BG);
        setClickable(true);

        LinearLayout main = new LinearLayout(c);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 10));
        addView(main, new LayoutParams(-1, -1));

        // Everything in the book lines up on one edge, EDGE in from the screen side.
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(c, 26, Ui.INK, true);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setText("📒 My book");
        title.setPadding(Ui.dp(c, EDGE - 12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = Ui.text(c, 22, Ui.INK, true);
        close.setText("✕");
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(Ui.FAINT);
        close.setBackground(round);
        close.setOnClickListener(v -> listener.onClose());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48));
        cp.rightMargin = Ui.dp(c, EDGE - 12);
        top.addView(close, cp);
        main.addView(top, new LinearLayout.LayoutParams(-1, -2));

        tabs = new LinearLayout(c);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 44));
        tp.topMargin = Ui.dp(c, 10);
        tp.bottomMargin = Ui.dp(c, 8);
        main.addView(tabs, tp);
        for (int i = 0; i < TABS.length; i++) {
            if (i == RIDDLES && !riddlesOpen) continue;
            final int index = i;
            TextView t = Ui.text(c, 13, Ui.INK, true);
            t.setText(TABS[i]);
            t.setOnClickListener(v -> select(index));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
            p.setMargins(Ui.dp(c, EDGE - 12), 0, Ui.dp(c, EDGE - 12), 0);
            tabs.addView(t, p);
            tabViews.add(t);
        }

        page = new FrameLayout(c);
        main.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        select(STICKERS);
    }

    private int pageCount() {
        return riddlesOpen ? 4 : 3;
    }

    private void select(int index) {
        if (index == current) return;
        current = index;
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == index;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? Ui.BOOK_PURPLE : Ui.FAINT);
            bg.setCornerRadius(Ui.dp(getContext(), 18));
            t.setBackground(bg);
            t.setTextColor(on ? Color.WHITE : Ui.INK);
            // There is no sticker emoji: this tab uses the sticker icon, coloured like the text.
            if (i == STICKERS) t.setText(StickerIcon.before(TABS[STICKERS], on ? Color.WHITE : Ui.INK, Ui.dp(getContext(), 17)));
        }
        page.removeAllViews();
        View v;
        switch (index) {
            case STICKERS: v = stickersPage(); break;
            case BADGES: v = badgesPage(); break;
            case MAP: v = mapPage(); break;
            default: v = riddlesPage(); break;
        }
        page.addView(v, new LayoutParams(-1, -1));
    }

    // ---------------------------------------------------------------- swiping between pages

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiping = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (Math.abs(dx) > Ui.dp(getContext(), 48) && Math.abs(dx) > 2 * Math.abs(dy)) swiping = true;
                return swiping;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (swiping && ev.getActionMasked() == MotionEvent.ACTION_UP) {
            swiping = false;
            int to = current + (ev.getX() < downX ? 1 : -1);
            if (to >= 0 && to < pageCount()) select(to);
            return true;
        }
        return swiping || super.onTouchEvent(ev);
    }

    // ---------------------------------------------------------------- stickers

    private View stickersPage() {
        Context c = getContext();
        Deck deck = store.deck();
        List<String> starter = Stickers.starter(c);
        List<String> available = new ArrayList<>(starter);
        available.addAll(Stickers.more(c));
        List<String> owned = new ArrayList<>();
        for (String s : deck.stickers) if (available.contains(s)) owned.add(s);
        List<String> slots = new ArrayList<>(owned);
        boolean locked = deck.unlockedDay == 0;
        if (locked) for (String s : starter) if (!owned.contains(s)) slots.add(null); // unknown ones as "?"

        LinearLayout col = column(c);
        String head = owned.isEmpty() ? "No stickers yet"
                : locked ? owned.size() + " of " + starter.size() + " stickers"
                : owned.size() + (owned.size() == 1 ? " sticker" : " stickers");
        col.addView(heading(c, head, locked && !owned.isEmpty() ? "Collect them all!" : owned.isEmpty()
                ? "A great play earns a sticker." : "Tap one to see it big."));
        LinearLayout row = null;
        for (int i = 0; i < slots.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(c);
                col.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(c, 110)));
            }
            final String name = slots.get(i);
            FrameLayout cell = new FrameLayout(c);
            cell.setBackground(rounded(name == null ? Ui.FAINT : Color.WHITE, 16));
            if (name == null) {
                TextView q = Ui.text(c, 36, Ui.SOFT_INK, true);
                q.setText("?");
                cell.addView(q, new LayoutParams(-1, -1));
            } else {
                ImageView img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Bitmap bm = Stickers.load(c, name, 2);
                if (bm != null) img.setImageBitmap(bm);
                int p = Ui.dp(c, 5);
                img.setPadding(p, p, p, p);
                cell.addView(img, new LayoutParams(-1, -1));
                cell.setOnClickListener(v -> showSticker(name));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            int m = Ui.dp(c, EDGE - 12);
            lp.setMargins(m, m, m, m);
            row.addView(cell, lp);
        }
        while (row != null && row.getChildCount() % 3 != 0) row.addView(new View(c), new LinearLayout.LayoutParams(0, -1, 1f));
        return scroll(c, col);
    }

    private void showSticker(String name) {
        Context c = getContext();
        LinearLayout big = new LinearLayout(c);
        big.setOrientation(LinearLayout.VERTICAL);
        big.setGravity(Gravity.CENTER);
        big.setBackgroundColor(0xF5120D2E);
        big.setClickable(true);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setImageBitmap(Stickers.load(c, name, 1));
        // About the picture's own size: any bigger and it goes soft.
        big.addView(img, new LinearLayout.LayoutParams(-1, Ui.dp(c, 270)));
        TextView phrase = Ui.text(c, 26, Color.WHITE, true);
        phrase.setText(Stickers.phrase(c, name));
        phrase.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), 0);
        big.addView(phrase, new LinearLayout.LayoutParams(-1, -2));
        TextView close = Ui.text(c, 15, Ui.NIGHT_INK, false);
        close.setText("Tap anywhere to close");
        close.setPadding(0, Ui.dp(c, 18), 0, 0);
        big.addView(close, new LinearLayout.LayoutParams(-1, -2));
        big.setOnClickListener(v -> removeView(big));
        addView(big, new LayoutParams(-1, -1));
        img.setScaleX(0.6f);
        img.setScaleY(0.6f);
        img.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
    }

    // ---------------------------------------------------------------- badges

    private View badgesPage() {
        Context c = getContext();
        Deck deck = store.deck();
        Settings s = store.settings();
        LinearLayout col = column(c);
        int total = Badges.FIXED.size() + s.tables.size();
        col.addView(heading(c, deck.badges.size() + " of " + total + " badges",
                deck.badges.isEmpty() ? "Play to earn your first one!" : null));

        List<Map.Entry<String, Long>> earned = new ArrayList<>(deck.badges.entrySet());
        earned.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        SimpleDateFormat day = new SimpleDateFormat("d MMM", Locale.UK);
        for (Map.Entry<String, Long> e : earned) {
            Badges.Badge b = Badges.byId(e.getKey());
            if (b == null || b.id.startsWith("table_")) continue;
            col.addView(badgeRow(c, b.emoji, b.title, b.description, "Earned " + day.format(new Date(e.getValue())), true));
        }
        boolean header = false;
        for (Badges.Badge b : Badges.FIXED) {
            if (deck.badges.containsKey(b.id)) continue;
            if (!header) {
                col.addView(section(c, "Still to find"));
                header = true;
            }
            if (SECRET.contains(b.id)) col.addView(badgeRow(c, "❓", "Secret badge", "Keep playing to find it!", null, false));
            else col.addView(badgeRow(c, b.emoji, b.title, b.description, null, false));
        }

        col.addView(section(c, "Times-table masters"));
        LinearLayout row = null;
        int i = 0;
        for (int t : s.tables) {
            if (i++ % 6 == 0) {
                row = new LinearLayout(c);
                col.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(c, 46)));
            }
            boolean got = deck.badges.containsKey(Badges.table(t).id);
            TextView chip = Ui.text(c, 17, got ? Color.WHITE : Ui.SOFT_INK, true);
            chip.setText(got ? "🏅" + t : t + "×");
            chip.setBackground(rounded(got ? Ui.GOLD : Ui.FAINT, 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            int m = Ui.dp(c, EDGE - 12);
            lp.setMargins(m, m, m, m);
            row.addView(chip, lp);
        }
        while (row != null && row.getChildCount() < 6) row.addView(new View(c), new LinearLayout.LayoutParams(0, -1, 1f));
        TextView note = Ui.text(c, 14, Ui.SOFT_INK, false);
        note.setGravity(Gravity.START);
        note.setText("Master every fact in a table to win its medal.");
        note.setPadding(Ui.dp(c, EDGE - 12), Ui.dp(c, 6), 0, Ui.dp(c, 6));
        col.addView(note);
        return scroll(c, col);
    }

    private View badgeRow(Context c, String emoji, String title, String desc, String date, boolean earned) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(earned ? Color.WHITE : 0xFFF6EEE2, 14));
        int p = Ui.dp(c, 8);
        row.setPadding(p, p, p, p);
        TextView e = Ui.text(c, 30, Ui.INK, false);
        e.setText(emoji);
        e.setAlpha(earned ? 1f : 0.35f);
        row.addView(e, new LinearLayout.LayoutParams(Ui.dp(c, 54), -2));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, 17, earned ? Ui.INK : Ui.SOFT_INK, true);
        t.setGravity(Gravity.START);
        t.setText(title);
        texts.addView(t);
        TextView d = Ui.text(c, 14, Ui.SOFT_INK, false);
        d.setGravity(Gravity.START);
        d.setText(desc);
        texts.addView(d);
        if (date != null) {
            TextView w = Ui.text(c, 13, Ui.ORANGE, true);
            w.setGravity(Gravity.START);
            w.setText(date);
            texts.addView(w);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(Ui.dp(c, EDGE - 12), 0, Ui.dp(c, EDGE - 12), Ui.dp(c, 6));
        row.setLayoutParams(lp);
        return row;
    }

    // ---------------------------------------------------------------- map

    private View mapPage() {
        Context c = getContext();
        Settings s = store.settings();
        List<Card> active = store.activeCards();
        int[] counts = new int[4];
        for (Card card : active) counts[TablesMapView.level(card)]++;

        LinearLayout col = column(c);
        col.addView(heading(c, "Times-table map", null));
        TablesMapView map = new TablesMapView(c);
        map.bind(s.tables, s.maxFactor, store.deck().cards);
        col.addView(map, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout legend = null;
        for (int i = 3; i >= 0; i--) {
            if (i % 2 == 1) {
                legend = new LinearLayout(c);
                legend.setPadding(Ui.dp(c, EDGE - 12), 0, 0, 0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 34));
                lp.topMargin = Ui.dp(c, i == 3 ? 10 : 0);
                col.addView(legend, lp);
            }
            LinearLayout item = new LinearLayout(c);
            item.setGravity(Gravity.CENTER_VERTICAL);
            View swatch = new View(c);
            swatch.setBackground(rounded(TablesMapView.COLORS[i], 6));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20));
            sp.rightMargin = Ui.dp(c, 8);
            item.addView(swatch, sp);
            TextView t = Ui.text(c, 15, Ui.INK, false);
            t.setGravity(Gravity.START);
            t.setText(TablesMapView.NAMES[i] + "  " + counts[i]);
            item.addView(t);
            legend.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        TextView note = Ui.text(c, 14, Ui.SOFT_INK, false);
        note.setGravity(Gravity.START);
        note.setText("Every fact you've met shows its answer. Facts turn gold once you've known them for 3 weeks.");
        note.setPadding(Ui.dp(c, EDGE - 12), Ui.dp(c, 8), Ui.dp(c, EDGE - 12), 0);
        col.addView(note);
        return scroll(c, col);
    }

    // ---------------------------------------------------------------- riddles

    private View riddlesPage() {
        Context c = getContext();
        Deck deck = store.deck();
        List<Riddle> bank = RiddleBank.get(c);
        final List<Riddle> cards = new ArrayList<>();
        for (Deck.DealtRiddle d : deck.riddles) cards.add(Deck.findRiddle(bank, d.key));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        if (cards.isEmpty()) {
            col.addView(heading(c, "No riddles yet", "Your first play of the day brings a new riddle."));
            return col;
        }
        if (riddleIndex < 0 || riddleIndex >= cards.size()) riddleIndex = cards.size() - 1;

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = Ui.button(c, "◀", Ui.BOOK_PURPLE, 22);
        Button next = Ui.button(c, "▶", Ui.BOOK_PURPLE, 22);
        TextView label = Ui.text(c, 18, Ui.INK, true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 44));
        ap.setMargins(Ui.dp(c, EDGE - 12), 0, Ui.dp(c, EDGE - 12), 0);
        top.addView(prev, ap);
        top.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(next, new LinearLayout.LayoutParams(ap));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        final RiddleCardView card = new RiddleCardView(c, false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cp.setMargins(Ui.dp(c, EDGE - 12), Ui.dp(c, 10), Ui.dp(c, EDGE - 12), Ui.dp(c, 6));
        col.addView(card, cp);
        final TextView hint = Ui.text(c, 15, Ui.SOFT_INK, false);
        hint.setMinLines(2);
        col.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        Runnable show = new Runnable() {
            @Override
            public void run() {
                int i = riddleIndex;
                boolean latest = i == cards.size() - 1;
                Deck.DealtRiddle dealt = deck.riddles.get(i);
                Riddle r = cards.get(i);
                label.setText("Riddle " + (i + 1) + " of " + cards.size());
                prev.setAlpha(i > 0 ? 1f : 0.3f);
                next.setAlpha(latest ? 0.3f : 1f);
                if (r == null) {
                    card.setVisibility(INVISIBLE);
                    hint.setText("This riddle has been taken out of the box.");
                    return;
                }
                card.setVisibility(VISIBLE);
                // A card she hasn't opened yet (she walked off after playing) is still sealed here.
                boolean sealed = latest && !dealt.opened;
                card.bind(r, i + 1, !latest, sealed);
                String answerWhen = "🔒 The answer comes with your first play "
                        + (dealt.day == today ? "tomorrow." : "today.");
                hint.setText(sealed ? "Tap the seal to open it." : latest ? answerWhen : "Tap the card to turn it over.");
                card.setListener(() -> {
                    if (!latest || dealt.opened) return;
                    onSealBroken.run();
                    hint.setText(answerWhen);
                });
            }
        };
        prev.setOnClickListener(v -> {
            if (riddleIndex > 0) {
                riddleIndex--;
                show.run();
            }
        });
        next.setOnClickListener(v -> {
            if (riddleIndex < cards.size() - 1) {
                riddleIndex++;
                show.run();
            }
        });
        show.run();
        return col;
    }

    // ---------------------------------------------------------------- a call while she reads

    /**
     * A scheduled wake-up came while she's in her book: instead of snatching it away, a banner
     * slides up at the bottom with a Play button. Closing the book works as usual.
     */
    void offerPlay(Runnable play) {
        Context c = getContext();
        if (findViewWithTag("play-banner") != null) return;
        LinearLayout banner = new LinearLayout(c);
        banner.setTag("play-banner");
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackground(rounded(Ui.ORANGE, 20));
        banner.setElevation(Ui.dp(c, 6));
        int p = Ui.dp(c, 10);
        banner.setPadding(Ui.dp(c, 16), p, p, p);
        banner.setClickable(true);
        TextView t = Ui.text(c, 20, Color.WHITE, true);
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t.setText("🔔 Time to play!");
        banner.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Button go = Ui.button(c, "▶ Play", Color.WHITE, 22);
        go.setTextColor(Ui.ORANGE);
        go.setOnClickListener(v -> play.run());
        banner.addView(go, new LinearLayout.LayoutParams(Ui.dp(c, 120), Ui.dp(c, 54)));
        // At the bottom, under her thumb, leaving the title and ✕ free.
        LayoutParams lp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(c, 10), 0, Ui.dp(c, 10), Ui.dp(c, 10));
        addView(banner, lp);
        banner.setTranslationY(Ui.dp(c, 120));
        banner.animate().translationY(0).setDuration(350).start();
    }

    // ---------------------------------------------------------------- helpers

    private static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Ui.dp(c, 12));
        return col;
    }

    private static ScrollView scroll(Context c, View content) {
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(content, new LayoutParams(-1, -2));
        return sv;
    }

    private static View heading(Context c, String title, String sub) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, EDGE - 12), Ui.dp(c, 2), Ui.dp(c, EDGE - 12), Ui.dp(c, 8));
        TextView t = Ui.text(c, 21, Ui.INK, true);
        t.setGravity(Gravity.START);
        t.setText(title);
        box.addView(t);
        if (sub != null) {
            TextView s = Ui.text(c, 14, Ui.SOFT_INK, false);
            s.setGravity(Gravity.START);
            s.setText(sub);
            box.addView(s);
        }
        return box;
    }

    private static View section(Context c, String title) {
        TextView t = Ui.text(c, 16, Ui.SOFT_INK, true);
        t.setGravity(Gravity.START);
        t.setText(title);
        t.setPadding(Ui.dp(c, EDGE - 12), Ui.dp(c, 12), 0, Ui.dp(c, 6));
        return t;
    }

    private static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        return g;
    }
}
