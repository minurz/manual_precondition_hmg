package dev.manualprecond;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays its one child out on a fixed design canvas and scales it, uniformly, to fit the
 * space the window actually gets, less this view's padding (MainActivity pads it clear of
 * what the car draws over the window: the split-screen panel and its handle). MainActivity sizes everything in canvas pixels, so
 * the screen looks the same whatever the head unit's density, font scale, status bar or
 * dock: nothing can be pushed off the edge or under another panel.
 *
 * The canvas is DESIGN_H tall and as wide as the window's shape allows (at least
 * MIN_DESIGN_W): a wide screen gets a wider canvas, not empty side bars.
 */
final class FitFrame extends ViewGroup {
    static final int DESIGN_H = 520, MIN_DESIGN_W = 1000;

    FitFrame(Context c) {
        super(c);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        int w = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight(),
                h = MeasureSpec.getSize(hSpec) - getPaddingTop() - getPaddingBottom();
        if (getChildCount() == 0 || w <= 0 || h <= 0) return;
        int[] d = design(w, h);
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(d[0], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(d[1], MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() == 0) return;
        int w = r - l - getPaddingLeft() - getPaddingRight(), h = b - t - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        int[] d = design(w, h);
        float s = Math.min(w / (float) d[0], h / (float) d[1]);
        View c = getChildAt(0);
        c.layout(0, 0, d[0], d[1]);
        c.setPivotX(0);
        c.setPivotY(0);
        c.setScaleX(s);
        c.setScaleY(s);
        c.setTranslationX(getPaddingLeft() + (w - d[0] * s) / 2f);   // centred in what the padding leaves
        c.setTranslationY(getPaddingTop() + (h - d[1] * s) / 2f);
    }

    /** the canvas for a w x h window: DESIGN_H tall unless the window is too narrow for MIN_DESIGN_W */
    static int[] design(int w, int h) {
        int dw = Math.round(w * DESIGN_H / (float) h);
        if (dw >= MIN_DESIGN_W) return new int[] { dw, DESIGN_H };
        return new int[] { MIN_DESIGN_W, Math.round(h * MIN_DESIGN_W / (float) w) };
    }
}
