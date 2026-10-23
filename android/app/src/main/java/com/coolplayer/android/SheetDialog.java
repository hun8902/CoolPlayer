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
  boolean dismissing;
  static final Set<SheetDialog> open = Collections.newSetFromMap(new WeakHashMap<SheetDialog, Boolean>());
  static void resizeOpen(MainActivity activity) {
    for (SheetDialog dialog : new ArrayList<>(open)) if (dialog.a == activity && dialog.isShowing()) {
      Window w = dialog.getWindow();
      if (w != null) {
        w.setLayout(activity.expanded() ? activity.dp(520) : WindowManager.LayoutParams.MATCH_PARENT,
            dialog.spec.flexible ? (int)(activity.getResources().getDisplayMetrics().heightPixels * .86) : WindowManager.LayoutParams.WRAP_CONTENT);
        w.setGravity(activity.expanded() ? Gravity.CENTER : Gravity.BOTTOM);
        w.getDecorView().requestLayout();
      }
    }
  }

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
                    Math.min(MeasureSpec.getSize(h), maximum),
                    spec.flexible ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
          }
        };
    surface.setOrientation(LinearLayout.VERTICAL);
    surface.setPadding(a.dp(20), a.dp(10), a.dp(20), a.dp(12));
    surface.setBackground(a.shape(a.darkTheme ? 0xff1c1c1e : 0xfff2f2f7, 28));
    surface.setClipToOutline(true);
    TextView handle = a.text("", 1, a.muted);
    handle.setBackground(a.shape(a.muted, 2));
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(a.dp(32), a.dp(4));
    hp.gravity = Gravity.CENTER;
    hp.bottomMargin = a.dp(6);
    surface.addView(handle, hp);
    LinearLayout header = a.row();
    TextView title = a.text(spec.title == null ? "Cool Player" : spec.title, 18, a.ink);
    title.setTypeface(null, Typeface.BOLD);
    title.setMaxLines(2);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    View close = a.icon("close", "닫기", this::cancel);
    close.setBackground(a.shape(a.darkTheme ? 0xff363638 : 0xffe5e5ea, 22));
    header.addView(close, new LinearLayout.LayoutParams(a.dp(36), a.dp(36)));
    surface.addView(header);
    a.space(surface, 16);
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
    if (!spec.flexible) surface.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    if (spec.message != null) {
      TextView message = a.text(spec.message, 15, a.muted);
      message.setLineSpacing(a.dp(3), 1.05f);
      message.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(18));
      content.addView(message);
    }
    if (spec.items != null) {
      LinearLayout group = a.column();
      group.setBackground(a.shape(a.darkTheme ? 0xff2c2c2e : Color.WHITE, 18));
      group.setClipToOutline(true);
      for (int i = 0; i < spec.items.length; i++) {
        final int index = i;
        LinearLayout row = a.row();
        row.setPadding(a.dp(16), a.dp(8), a.dp(12), a.dp(8));
        row.setMinimumHeight(a.dp(48));
        android.widget.ImageView glyph = new android.widget.ImageView(a);
        glyph.setImageDrawable(new PlayerGlyph(menuIcon(spec.originalItems[i]), a.accent, a.dp(21)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(a.dp(22), a.dp(22));
        iconParams.rightMargin = a.dp(12);
        if (!spec.single) row.addView(glyph, iconParams);
        if (spec.single) row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView text = a.text(spec.items[i], 15, a.ink);
        text.setIncludeFontPadding(true);
        if (spec.single) {
          text.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
          text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
          text.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mark = a.text(spec.selected == i ? "✓" : spec.navigation ? "›" : "", 16, spec.selected == i ? a.accent : a.muted);
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
          divider.setBackgroundColor(a.darkTheme ? 0xff3a3a3c : 0xffe5e5ea);
          LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, 1);
          line.leftMargin = a.dp(spec.single ? 16 : 50);
          group.addView(divider, line);
        }
      }
      content.addView(group);
    }
    if (spec.view != null) {
      if (spec.view.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup) spec.view.getParent()).removeView(spec.view);
      style(spec.view);
      if (spec.flexible) surface.addView(spec.view, new LinearLayout.LayoutParams(-1, 0, 1));
      else content.addView(spec.view, 0, new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          spec.view instanceof EditText ? a.dp(56) : ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    LinearLayout buttons = a.column();
    for (Action action : spec.actions) {
      if (action.listener == null && (action.label.equals(a.tr("닫기")) || action.label.equals(a.tr("확인")))) continue;
      a.space(buttons, 8);
      TextView b =
          a.button(
              action.label,
              () -> {
                dismiss();
                if (action.listener != null) action.listener.onClick(this, action.which);
              });
      if (action.which == DialogInterface.BUTTON_POSITIVE) {
        b.setBackground(a.shape(a.accent, 24));
        b.setTextColor(a.darkTheme ? 0xff172031 : Color.WHITE);
      } else {
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextColor(action.label.contains("비우기") || action.label.contains("삭제") ? 0xffff453a : a.accent);
      }
      buttons.addView(b, new LinearLayout.LayoutParams(-1, a.dp(50)));
    }
    surface.addView(buttons);
    SwipeDismissLayout dragHost = new SwipeDismissLayout(a);
    dragHost.setOrientation(LinearLayout.VERTICAL);
    dragHost.close = this::cancel;
    dragHost.addView(surface, new LinearLayout.LayoutParams(-1, -2));
    setContentView(dragHost);
    dragHost.moving = getWindow().getDecorView();
    Window window = getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(.24f);
      if (android.os.Build.VERSION.SDK_INT >= 31) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.setBlurBehindRadius(a.dp(20));
        window.setAttributes(attributes);
      }
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (android.os.Build.VERSION.SDK_INT < 35) window.setNavigationBarColor(a.bg);
      window.setGravity(a.expanded() ? Gravity.CENTER : Gravity.BOTTOM);
    }
    setCancelable(true);
    setCanceledOnTouchOutside(true);
  }

  void style(View v) {
    if (v instanceof ListView) {
      v.setOnTouchListener(
          (view, event) -> {
            view.getParent()
                .requestDisallowInterceptTouchEvent(
                    view.canScrollVertically(-1) && event.getAction() != MotionEvent.ACTION_UP
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
      android.graphics.drawable.GradientDrawable field = a.shape(a.card, 12);
      field.setStroke(a.dp(1), a.muted);
      e.setBackground(field);
      e.setMinHeight(a.dp(52));
    } else if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) style(group.getChildAt(i));
    }
  }

  @Override
  public void show() {
    dismissing = false;
    super.show();
    open.add(this);
    Window w = getWindow();
    if (w != null) {
      w.setLayout(
          a.expanded() ? a.dp(520) : WindowManager.LayoutParams.MATCH_PARENT,
          spec.flexible ? (int) (a.getResources().getDisplayMetrics().heightPixels * .86)
              : WindowManager.LayoutParams.WRAP_CONTENT);
      if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
        View content = w.getDecorView();
        content.animate().cancel();
        content.setTranslationY(a.dp(a.expanded() ? 16 : 56));
        content.setScaleX(.97f);
        content.setScaleY(.97f);
        content.setAlpha(0);
        content
            .animate()
            .translationY(0)
            .scaleX(1)
            .scaleY(1)
            .alpha(1)
            .setDuration(320)
            .setInterpolator(new android.view.animation.PathInterpolator(.22f, .75f, .18f, 1))
            .start();
      }
    }
  }

  @Override
  public void dismiss() {
    Window window = getWindow();
    if (window != null) window.getDecorView().animate().cancel();
    dismissing = false;
    open.remove(this);
    super.dismiss();
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

  static String menuIcon(String label) {
    if (label.contains("다음에 재생")) return "next";
    if (label.contains("제거")) return "remove";
    if (label.contains("재생목록") || label.contains("대기열")) return "queue";
    if (label.contains("앨범아트")) return "album";
    if (label.contains("삭제") || label.contains("비우기")) return "trash";
    if (label.contains("검색")) return "search";
    if (label.contains("내보내기")) return "export";
    if (label.contains("편집") || label.contains("이름")) return "edit";
    if (label.contains("타이밍") || label.contains("타이머")) return "clock";
    if (label.contains("글자")) return "font";
    if (label.contains("파일") || label.contains("폴더")) return "folder";
    if (label.contains("가져") || label.contains("업데이트")) return "download";
    if (label.contains("즐겨")) return "heart";
    if (label.contains("앨범")) return "album";
    if (label.contains("가사")) return "lyrics";
    if (label.contains("설정") || label.contains("테마")) return "settings";
    if (label.contains("라이선스") || label.contains("저작권") || label.contains("정보")) return "info";
    if (label.contains("재생") || label.contains("플레이어") || label.contains("체험")) return "music";
    return "list";
  }

  static class Builder {
    final MainActivity a;
    String title, message;
    View view;
    String[] items, originalItems;
    boolean single, flexible, navigation;
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

    Builder setFlexibleView(View v) {
      view = v;
      flexible = true;
      return this;
    }

    Builder setItems(String[] s, DialogInterface.OnClickListener listener) {
      originalItems = s.clone();
      items = s.clone();
      for (int i = 0; i < items.length; i++) items[i] = a.tr(items[i]);
      select = listener;
      return this;
    }

    Builder setNavigationItems(String[] s, DialogInterface.OnClickListener listener) {
      navigation = true;
      return setItems(s, listener);
    }

    Builder setSingleChoiceItems(String[] s, int choice, DialogInterface.OnClickListener listener) {
      originalItems = s.clone();
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
