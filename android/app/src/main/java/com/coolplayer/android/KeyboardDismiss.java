package com.coolplayer.android;

import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.EditText;

final class KeyboardDismiss {
  static void hide(View input) {
    InputMethodManager keyboard = (InputMethodManager) input.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
    input.clearFocus();
  }
  static boolean inputAt(View view, int x, int y) {
    if (view.getVisibility() != View.VISIBLE) return false;
    Rect bounds = new Rect();
    if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) return false;
    if (view instanceof EditText) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = group.getChildCount() - 1; i >= 0; i--)
        if (inputAt(group.getChildAt(i), x, y)) return true;
    }
    return false;
  }
  static void outside(Window window, MotionEvent event) {
    if (event.getActionMasked() != MotionEvent.ACTION_DOWN || window == null) return;
    View focused = window.getCurrentFocus();
    if (focused instanceof EditText && !inputAt(window.getDecorView(), (int)event.getRawX(), (int)event.getRawY())) hide(focused);
  }
  static void done(EditText input) {
    input.setImeOptions(EditorInfo.IME_ACTION_DONE);
    input.setOnEditorActionListener((view, action, event) -> {
      if (action == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
        hide(input); return true;
      }
      return false;
    });
  }
}
