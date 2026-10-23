package com.coolplayer.android;

import android.graphics.*;
import android.view.*;
import android.widget.*;

/** One-time, dismissible guide anchored to an actual control. */
final class SpotlightGuide extends FrameLayout {
  final View target;
  SpotlightGuide(MainActivity a, View target, String message, String action, Runnable next) {
    super(a);
    this.target = target;
    followTarget = () -> {
    Rect current = new Rect();
    this.target.getGlobalVisibleRect(current);
    if (!current.equals(lastTargetBounds)) { lastTargetBounds.set(current); invalidate(); }
    return true;
  };
    setWillNotDraw(false);
    setClickable(true);
    setFocusableInTouchMode(true);
    setOnTouchListener((v, event) -> {
      Rect bounds = new Rect();
      boolean hit = target.getGlobalVisibleRect(bounds)
          && bounds.contains((int) event.getRawX(), (int) event.getRawY());
      if (hit && event.getActionMasked() == MotionEvent.ACTION_UP) { remove(); next.run(); }
      return true;
    });
    LinearLayout card = a.column();
    card.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16));
    card.setBackground(a.shape(a.bg, 20));
    int split = message.indexOf('\n');
    TextView heading = a.text(split >= 0 ? message.substring(0, split) : "가사 시작하기", 14, a.accent);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    card.addView(heading);
    a.space(card, 8);
    TextView text = a.text(split >= 0 ? message.substring(split + 1) : message, 16, a.ink);
    text.setIncludeFontPadding(true);
    text.setLineSpacing(a.dp(3), 1.05f);
    card.addView(text);
    a.space(card, 20);
    TextView primary = a.button(action, () -> { remove(); next.run(); });
    primary.setBackground(a.shape(a.accent, 16));
    primary.setTextColor(a.darkTheme ? 0xff172031 : Color.WHITE);
    card.addView(primary, new LinearLayout.LayoutParams(-1, a.dp(48)));
    a.space(card, 10);
    TextView skip = a.button("건너뛰기", this::remove);
    skip.setBackgroundColor(Color.TRANSPARENT);
    skip.setTextColor(a.muted);
    card.addView(skip, new LinearLayout.LayoutParams(-1, a.dp(44)));
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(a.getResources().getDisplayMetrics().widthPixels - a.dp(40), a.dp(400)), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    lp.setMargins(a.dp(20), a.dp(20), a.dp(20), a.dp(36));
    addView(card, lp);
    setOnKeyListener((v, key, event) -> {
      if (key == KeyEvent.KEYCODE_BACK) { if (event.getAction() == KeyEvent.ACTION_UP) remove(); return true; }
      return false;
    });
  }
  final Rect lastTargetBounds = new Rect();
  final android.view.ViewTreeObserver.OnPreDrawListener followTarget;
  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    getViewTreeObserver().addOnPreDrawListener(followTarget);
  }
  @Override protected void onDetachedFromWindow() {
    if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(followTarget);
    super.onDetachedFromWindow();
  }
  void remove() { if (getParent() instanceof ViewGroup) ((ViewGroup)getParent()).removeView(this); }
  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    Rect bounds = new Rect();
    int[] origin = new int[2];
    getLocationOnScreen(origin);
    boolean visible = target.getGlobalVisibleRect(bounds);
    RectF hole = new RectF(bounds);
    hole.offset(-origin[0], -origin[1]);
    Outline outline = new Outline();
    if (target.getBackground() != null) target.getBackground().getOutline(outline);
    float radius = outline.getRadius();
    if (radius < 0) radius = 12 * getResources().getDisplayMetrics().density;
    float scale = target.getWidth() > 0 ? hole.width() / target.getWidth() : 1f;
    Path path = new Path();
    if (visible) path.addRoundRect(hole, radius * scale, radius * scale, Path.Direction.CW);
    canvas.save(); canvas.clipOutPath(path); canvas.drawColor(0xD9000000); canvas.restore();
  }
  static void show(MainActivity a, View target, String text, String action, Runnable next) {
    if (a.isFinishing() || a.isDestroyed() || target == null) return;
    SpotlightGuide guide = new SpotlightGuide(a, target, text, action, next);
    a.addContentView(guide, new ViewGroup.LayoutParams(-1, -1)); guide.requestFocus();
  }
}
