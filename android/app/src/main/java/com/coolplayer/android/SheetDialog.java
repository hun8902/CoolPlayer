package com.coolplayer.android;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Shared presentation for every in-app menu, choice, editor and confirmation. */
final class SheetDialog extends Dialog {
  final MainActivity a;
  final Builder spec;

  SheetDialog(Builder b) {
    super(b.a);
    a = b.a;
    spec = b;
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout surface =
        new LinearLayout(a) {
          @Override
          protected void onMeasure(int w, int h) {
            int maximum = (int) (a.getResources().getDisplayMetrics().heightPixels * .86);
            super.onMeasure(
                w,
                MeasureSpec.makeMeasureSpec(
                    Math.min(MeasureSpec.getSize(h), maximum), MeasureSpec.AT_MOST));
          }
        };
    surface.setOrientation(LinearLayout.VERTICAL);
    surface.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(16));
    surface.setBackground(a.shape(a.bg, 28));
    TextView handle = a.text("", 1, a.muted);
    handle.setBackground(a.shape(a.muted, 2));
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(a.dp(32), a.dp(4));
    hp.gravity = Gravity.CENTER;
    hp.bottomMargin = a.dp(12);
    surface.addView(handle, hp);
    LinearLayout header = a.row();
    TextView title = a.text(spec.title == null ? "Cool Player" : spec.title, 21, a.ink);
    title.setTypeface(null, Typeface.BOLD);
    title.setMaxLines(2);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(a.icon("back", "닫기", this::dismiss));
    surface.addView(header);
    a.space(surface, 12);
    ScrollView scroll =
        new ScrollView(a) {
          @Override
          protected void onMeasure(int w, int h) {
            int limit =
                Math.max(
                    a.dp(100),
                    (int) (a.dp(a.getResources().getConfiguration().screenHeightDp) * .86)
                        - a.dp(120 + spec.actions.size() * 58));
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
          }
        };
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout content = a.column();
    scroll.addView(content);
    surface.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    if (spec.message != null) {
      TextView message = a.text(spec.message, 15, a.muted);
      message.setLineSpacing(a.dp(5), 1.1f);
      message.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(18));
      content.addView(message);
    }
    if (spec.items != null) {
      LinearLayout group = a.column();
      group.setBackground(a.shape(a.card, 18));
      for (int i = 0; i < spec.items.length; i++) {
        final int index = i;
        LinearLayout row = a.row();
        row.setPadding(a.dp(16), a.dp(8), a.dp(12), a.dp(8));
        row.setMinimumHeight(a.dp(56));
        TextView text = a.text(spec.items[i], 16, a.ink);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mark = a.text(spec.selected == i ? "✓" : spec.single ? "" : "›", 18, a.accent);
        row.addView(mark);
        row.setBackground(
            new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x227788aa),
                null,
                a.shape(Color.WHITE, 12)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(
            v -> {
              if (!spec.single) dismiss();
              if (spec.select != null) spec.select.onClick(this, index);
            });
        group.addView(row);
        if (i < spec.items.length - 1) {
          View divider = new View(a);
          divider.setBackgroundColor(a.darkTheme ? 0xff343944 : 0xffdfe4eb);
          LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, 1);
          line.leftMargin = a.dp(16);
          group.addView(divider, line);
        }
      }
      content.addView(group);
    }
    if (spec.view != null) {
      if (spec.view.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup) spec.view.getParent()).removeView(spec.view);
      style(spec.view);
      content.addView(spec.view, 0);
    }
    LinearLayout buttons = a.column();
    for (Action action : spec.actions) {
      a.space(buttons, 8);
      TextView b =
          a.button(
              action.label,
              () -> {
                dismiss();
                if (action.listener != null) action.listener.onClick(this, action.which);
              });
      b.setTextColor(action.which == DialogInterface.BUTTON_POSITIVE ? a.accent : a.muted);
      buttons.addView(b, new LinearLayout.LayoutParams(-1, a.dp(50)));
    }
    surface.addView(buttons);
    setContentView(surface);
    Window window = getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(.38f);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      window.setNavigationBarColor(a.bg);
      window.setGravity(a.expanded() ? Gravity.CENTER : Gravity.BOTTOM);
    }
    setCanceledOnTouchOutside(true);
  }

  void style(View v) {
    if (v instanceof ListView) {
      v.setOnTouchListener(
          (view, event) -> {
            view.getParent()
                .requestDisallowInterceptTouchEvent(
                    event.getAction() != MotionEvent.ACTION_UP
                        && event.getAction() != MotionEvent.ACTION_CANCEL);
            return false;
          });
    }
    if (v instanceof EditText) {
      EditText e = (EditText) v;
      e.setTextColor(a.ink);
      e.setHintTextColor(a.muted);
      e.setTextSize(15);
      e.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
      e.setBackground(a.shape(a.card, 12));
    } else if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) style(group.getChildAt(i));
    }
  }

  @Override
  public void show() {
    super.show();
    Window w = getWindow();
    if (w != null) {
      w.setLayout(
          a.expanded() ? a.dp(520) : WindowManager.LayoutParams.MATCH_PARENT,
          WindowManager.LayoutParams.WRAP_CONTENT);
      if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
        View content = w.getDecorView();
        content.setTranslationY(a.dp(28));
        content.setAlpha(0);
        content
            .animate()
            .translationY(0)
            .alpha(1)
            .setDuration(240)
            .setInterpolator(new android.view.animation.PathInterpolator(.22f, .75f, .18f, 1))
            .start();
      }
    }
  }

  static class Action {
    String label;
    int which;
    DialogInterface.OnClickListener listener;

    Action(String l, int w, DialogInterface.OnClickListener c) {
      label = l;
      which = w;
      listener = c;
    }
  }

  static class Builder {
    final MainActivity a;
    String title, message;
    View view;
    String[] items;
    boolean single;
    int selected = -1;
    DialogInterface.OnClickListener select;
    final ArrayList<Action> actions = new ArrayList<>();

    Builder(MainActivity a) {
      this.a = a;
    }

    Builder setTitle(String s) {
      title = a.tr(s);
      return this;
    }

    Builder setMessage(String s) {
      message = a.tr(s);
      return this;
    }

    Builder setView(View v) {
      view = v;
      return this;
    }

    Builder setItems(String[] s, DialogInterface.OnClickListener listener) {
      items = s.clone();
      for (int i = 0; i < items.length; i++) items[i] = a.tr(items[i]);
      select = listener;
      return this;
    }

    Builder setSingleChoiceItems(String[] s, int choice, DialogInterface.OnClickListener listener) {
      items = s.clone();
      for (int i = 0; i < items.length; i++) items[i] = a.tr(items[i]);
      selected = choice;
      single = true;
      select = listener;
      return this;
    }

    Builder setPositiveButton(String s, DialogInterface.OnClickListener listener) {
      actions.add(new Action(a.tr(s), DialogInterface.BUTTON_POSITIVE, listener));
      return this;
    }

    Builder setNegativeButton(String s, DialogInterface.OnClickListener listener) {
      actions.add(new Action(a.tr(s), DialogInterface.BUTTON_NEGATIVE, listener));
      return this;
    }

    Builder setNeutralButton(String s, DialogInterface.OnClickListener listener) {
      actions.add(new Action(a.tr(s), DialogInterface.BUTTON_NEUTRAL, listener));
      return this;
    }

    SheetDialog create() {
      return new SheetDialog(this);
    }

    SheetDialog show() {
      SheetDialog d = create();
      d.show();
      return d;
    }
  }
}
