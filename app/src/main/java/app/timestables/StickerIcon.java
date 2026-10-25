package app.timestables;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;

/**
 * The sticker icon (a square with a peeled corner) from Phosphor Icons, "sticker", regular weight
 * (https://phosphoricons.com, MIT licence), redrawn here as a vector from its 256-unit SVG paths.
 * There is no sticker emoji, and the picture-frame one looked dull.
 */
final class StickerIcon extends Drawable {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final Path peel = new Path();
    private final Path corner = new Path();

    /**
     * {@code cornerColor} fills the peeled-up corner (0 for a plain outline); {@code strokeUnits} is
     * out of 256 (the original is 16).
     */
    StickerIcon(int color, int cornerColor, float strokeUnits) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(color);
        stroke.setStrokeWidth(strokeUnits);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(cornerColor);
        // M136,216H88a48,48,0,0,1-48-48V88A48,48,0,0,1,88,40h80a48,48,0,0,1,48,48v48C208,160,160,208,136,216Z
        body.moveTo(136, 216);
        body.lineTo(88, 216);
        body.arcTo(new RectF(40, 120, 136, 216), 90, 90);
        body.lineTo(40, 88);
        body.arcTo(new RectF(40, 40, 136, 136), 180, 90);
        body.lineTo(168, 40);
        body.arcTo(new RectF(120, 40, 216, 136), 270, 90);
        body.lineTo(216, 136);
        body.cubicTo(208, 160, 160, 208, 136, 216);
        body.close();
        // M136,216V184a48,48,0,0,1,48-48h32
        peel.moveTo(136, 216);
        peel.lineTo(136, 184);
        peel.arcTo(new RectF(136, 136, 232, 232), 180, 90);
        peel.lineTo(216, 136);
        corner.addPath(peel);
        corner.cubicTo(208, 160, 160, 208, 136, 216);
        corner.close();
    }

    /** {@code text} with the icon in front, sized {@code sizePx}, for a TextView. */
    static CharSequence before(CharSequence text, int color, int sizePx) {
        StickerIcon icon = new StickerIcon(color, 0, 20);
        icon.setBounds(0, 0, sizePx, sizePx);
        SpannableStringBuilder sb = new SpannableStringBuilder("￼ ");
        sb.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(text);
        return sb;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float scale = Math.min(b.width(), b.height()) / 256f;
        canvas.save();
        canvas.translate(b.exactCenterX() - 128 * scale, b.exactCenterY() - 128 * scale);
        canvas.scale(scale, scale);
        if (fill.getColor() != 0) canvas.drawPath(corner, fill);
        canvas.drawPath(body, stroke);
        canvas.drawPath(peel, stroke);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
