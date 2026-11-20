package com.voiceflowkeyboard.ime;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays its children out left to right, wrapping onto new lines: the style sheet's chips. */
final class FlowRow extends ViewGroup {
    private final int gapX;
    private final int gapY;

    FlowRow(Context context, int gapX, int gapY) {
        super(context);
        this.gapX = gapX;
        this.gapY = gapY;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        boolean bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED;
        int x = 0;
        int lineHeight = 0;
        int height = 0;
        int widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            LayoutParams params = child.getLayoutParams();
            int childWidthSpec = bounded
                    ? getChildMeasureSpec(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), 0, params.width)
                    : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            child.measure(childWidthSpec, getChildMeasureSpec(heightMeasureSpec, 0, params.height));
            int w = child.getMeasuredWidth();
            if (bounded && x > 0 && x + w > maxWidth) {
                height += lineHeight + gapY;
                x = 0;
                lineHeight = 0;
            }
            x += w + gapX;
            widest = Math.max(widest, x - gapX);
            lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
        }
        height += lineHeight;
        int width = bounded ? MeasureSpec.getSize(widthMeasureSpec) : widest + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(width, height + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = getPaddingTop();
        int lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > maxWidth) {
                y += lineHeight + gapY;
                x = 0;
                lineHeight = 0;
            }
            int left = isRtl() ? r - l - getPaddingRight() - x - w : getPaddingLeft() + x;
            child.layout(left, y, left + w, y + h);
            x += w + gapX;
            lineHeight = Math.max(lineHeight, h);
        }
    }

    private boolean isRtl() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }
}
