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
  @Override public boolean onInterceptTouchEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      startX = e.getRawX(); startY = e.getRawY(); dragging = false;
      eligible = !scrollsUp(this, e.getX(), e.getY());
    } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && eligible) {
      float dy = e.getRawY() - startY;
      if (dy > ViewConfiguration.get(getContext()).getScaledTouchSlop()
          && dy > Math.abs(e.getRawX() - startX) * 1.3f) {
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      }
    }
    return false;
  }
  @Override public boolean onTouchEvent(MotionEvent e) {
    if (!dragging) return true;
    View target = moving == null ? this : moving;
    float dy = Math.max(0, e.getRawY() - startY);
    if (e.getActionMasked() == MotionEvent.ACTION_MOVE) target.setTranslationY(dy * .7f);
    else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      boolean dismiss = e.getActionMasked() == MotionEvent.ACTION_UP
          && dy > 64 * getResources().getDisplayMetrics().density;
      dragging = false;
      if (dismiss && close != null) close.run();
      else target.animate().translationY(0).setDuration(220)
          .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    return true;
  }
}
