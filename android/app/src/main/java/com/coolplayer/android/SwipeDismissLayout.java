package com.coolplayer.android;

import android.content.Context;
import android.view.*;
import android.widget.LinearLayout;

/** Downward drag that yields to scrollable content until it reaches its top. */
final class SwipeDismissLayout extends LinearLayout {
  float startX, startY;
  boolean dragging, eligible;
  Runnable close;
  View moving;
  SwipeDismissLayout(Context context) { super(context); }
  boolean scrollsUp(View v, float x, float y) {
    if (v.canScrollVertically(-1)) return true;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        View c = g.getChildAt(i);
        float cx = x + g.getScrollX() - c.getLeft(), cy = y + g.getScrollY() - c.getTop();
        if (cx >= 0 && cy >= 0 && cx < c.getWidth() && cy < c.getHeight()
            && scrollsUp(c, cx, cy)) return true;
      }
    }
    return false;
  }
  @Override public boolean dispatchTouchEvent(MotionEvent e) {
    int action = e.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      startX = e.getRawX(); startY = e.getRawY(); dragging = false;
      eligible = !scrollsUp(this, e.getX(), e.getY());
      if (moving != null) moving.animate().cancel();
    }
    float dy = e.getRawY() - startY;
    if (!dragging && eligible && action == MotionEvent.ACTION_MOVE
        && dy > ViewConfiguration.get(getContext()).getScaledTouchSlop() * 2
        && dy > Math.abs(e.getRawX() - startX) * 1.3f) {
      dragging = true;
      MotionEvent cancel = MotionEvent.obtain(e);
      cancel.setAction(MotionEvent.ACTION_CANCEL);
      super.dispatchTouchEvent(cancel);
      cancel.recycle();
    }
    if (dragging) {
      View target = moving == null ? this : moving;
      if (action == MotionEvent.ACTION_MOVE) target.setTranslationY(Math.max(0, dy) * .7f);
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        dragging = false;
        if (action == MotionEvent.ACTION_UP && dy > 56 * getResources().getDisplayMetrics().density && close != null)
          close.run();
        else target.animate().translationY(0).setDuration(220)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
      }
      return true;
    }
    // Consume blank header/handle space as well as child controls, so every
    // starting point receives subsequent MOVE events.
    super.dispatchTouchEvent(e);
    return true;
  }
}
