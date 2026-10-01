package fun.nyama.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.TextView;

/** Single-line TextView used for the HH:mm clock with a true black outline. */
public final class OutlineTextView extends TextView {
    private int strokeColor = Color.BLACK;
    private float strokeWidth = 4f;

    public OutlineTextView(Context context) { super(context); }
    public OutlineTextView(Context context, AttributeSet attrs) { super(context, attrs); }
    public OutlineTextView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setStrokeColor(int color) { strokeColor = color; invalidate(); }
    public void setStrokeWidth(float width) { strokeWidth = width; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        CharSequence value = getText();
        if (value == null) return;
        String text = value.toString();
        Paint paint = getPaint();
        Paint.Style oldStyle = paint.getStyle();
        float oldWidth = paint.getStrokeWidth();
        int oldColor = paint.getColor();
        Paint.Align oldAlign = paint.getTextAlign();

        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float x = getWidth() / 2f;
        float y = (getHeight() - fm.bottom - fm.top) / 2f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(strokeColor);
        canvas.drawText(text, x, y, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getCurrentTextColor());
        canvas.drawText(text, x, y, paint);

        paint.setStyle(oldStyle);
        paint.setStrokeWidth(oldWidth);
        paint.setColor(oldColor);
        paint.setTextAlign(oldAlign);
    }
}
