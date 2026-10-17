package com.brouken.player;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The bottom control bar, with one thing added: it travels as far as it is tall.
 *
 * <p>Media3 parks the bar off screen by translating it down by {@code exo_styled_bottom_bar_height} —
 * a resource value read once and baked into PlayerControlViewLayoutManager's animators, so it never
 * learns that the bar has since been resized. The bar is grown by the bottom inset (the navigation bar,
 * or synthesized TV overscan) so its scrim keeps reaching the screen edge, which leaves it taller than
 * that constant by exactly the inset: the park stopped short by the same amount and left the top of the
 * bar — the top of the button row with it — sitting over the picture while only the seek bar was up.
 *
 * <p>Scaling every translation by how much taller the bar is restores the two ends the animators mean:
 * flush at rest, fully parked when hidden, and no seam in between.
 */
public class BottomBarLayout extends FrameLayout {

    private float travelScale = 1f;
    private float travel;
    /** A view that is part of the bar without being inside it - the TV's button row above the seek bar. */
    private View companion;

    public BottomBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Bar height as laid out, over the {@code exo_styled_bottom_bar_height} Media3 parks it by. */
    void setTravelScale(float scale) {
        if (scale != travelScale) {
            travelScale = scale;
            super.setTranslationY(travel * scale);
        }
    }

    @Override
    public void setTranslationY(float translationY) {
        travel = translationY;
        super.setTranslationY(translationY * travelScale);
        follow();
    }

    /**
     * Makes a view come and go with the bar. It is not moved with it - the bar parks below the screen,
     * and a row above the seek bar moved by as much would be parked over the picture - so it fades by
     * how far the bar has travelled: whole at rest, gone once the bar is parked.
     */
    void setCompanion(View view) {
        companion = view;
        follow();
    }

    private void follow() {
        if (companion == null) {
            return;
        }
        final float height = getHeight();
        companion.setAlpha(height <= 0 ? 1f
                : Math.max(0f, Math.min(1f, 1f - Math.abs(super.getTranslationY()) / height)));
    }
}
