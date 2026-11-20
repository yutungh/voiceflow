package com.voiceflowkeyboard.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The live waveform in the listening bar: rounded bars for the most recent
 * microphone levels, newest on the right. Decorative only; the bar says
 * "Listening" in words, so it is hidden from TalkBack.
 */
final class WaveformView extends View {
    private static final int BARS = 48;
    private final float[] levels = new float[BARS];
    private int next;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final float density;

    WaveformView(Context context, int color) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setColor(color);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Adds one level, 0 (silence) to 1 (loud). */
    void push(float level) {
        levels[next] = Math.max(0f, Math.min(1f, level));
        next = (next + 1) % BARS;
        invalidate();
    }

    void clear() {
        java.util.Arrays.fill(levels, 0f);
        next = 0;
        invalidate();
    }

    /** Fills the bars with a fixed, plausible speech shape (screenshots and tests). */
    void sample() {
        for (int i = 0; i < BARS; i++) {
            push((float) (0.25 + 0.55 * Math.abs(Math.sin(i * 0.9) * Math.cos(i * 0.37))));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        float barWidth = 3f * density;
        float gap = 2.5f * density;
        int count = Math.max(1, Math.min(BARS, (int) ((width + gap) / (barWidth + gap))));
        float used = count * barWidth + (count - 1) * gap;
        float x = getPaddingLeft() + (width - used) / 2f;
        float mid = getPaddingTop() + height / 2f;
        float min = 3f * density;
        for (int i = 0; i < count; i++) {
            int index = (next - count + i + BARS * 2) % BARS;
            float h = Math.max(min, levels[index] * height);
            bar.set(x, mid - h / 2f, x + barWidth, mid + h / 2f);
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint);
            x += barWidth + gap;
        }
    }
}
