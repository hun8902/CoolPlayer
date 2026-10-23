package com.coolplayer.android;

import android.animation.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Soft album-derived color fields; no full-resolution bitmap work on animation frames. */
final class ArtworkBackdrop extends Drawable {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int[] colors = new int[3];
  private ValueAnimator blend;
  private int opacity = 255;
  boolean artworkEnabled;
  private Bitmap backgroundArt;

  ArtworkBackdrop(boolean dark) {
    int base = dark ? 0xff18202e : 0xffe9edf4;
    java.util.Arrays.fill(colors, base);
  }

  ArtworkBackdrop(boolean dark, ArtworkBackdrop previous) {
    this(dark);
    if (previous != null) System.arraycopy(previous.colors, 0, colors, 0, 3);
  }

  static int mix(int a, int b, float f) {
    return Color.rgb(
        (int) (Color.red(a) * (1 - f) + Color.red(b) * f),
        (int) (Color.green(a) * (1 - f) + Color.green(b) * f),
        (int) (Color.blue(a) * (1 - f) + Color.blue(b) * f));
  }

  void album(Bitmap bitmap, boolean dark) {
    if (artworkEnabled) {
      int width = Math.min(512, bitmap.getWidth());
      int height = Math.max(1, Math.round(bitmap.getHeight() * (width / (float) bitmap.getWidth())));
      Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
      Bitmap owned = scaled.copy(Bitmap.Config.ARGB_8888, false);
      if (scaled != bitmap) scaled.recycle();
      if (backgroundArt != null) backgroundArt.recycle();
      backgroundArt = owned;
      invalidateSelf();
    }
    int[] target = new int[3];
    long[][] sums = new long[3][4];
    for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 32))
      for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 32)) {
        int color = bitmap.getPixel(x, y);
        if (Color.alpha(color) < 128) continue;
        int region = Math.min(2, x * 3 / bitmap.getWidth());
        sums[region][0] += Color.red(color);
        sums[region][1] += Color.green(color);
        sums[region][2] += Color.blue(color);
        sums[region][3]++;
      }
    for (int i = 0; i < 3; i++) {
      long n = Math.max(1, sums[i][3]);
      int sample =
          Color.rgb((int) (sums[i][0] / n), (int) (sums[i][1] / n), (int) (sums[i][2] / n));
      float[] hsv = new float[3];
      Color.colorToHSV(sample, hsv);
      hsv[1] = Math.min(.7f, hsv[1] * 1.25f);
      hsv[2] = dark ? .32f : .94f;
      target[i] = mix(Color.HSVToColor(hsv), dark ? 0xff11141c : 0xfffafbfe, dark ? .26f : .60f);
    }
    if (blend != null) blend.cancel();
    int[] start = colors.clone();
    if (!ValueAnimator.areAnimatorsEnabled()) {
      System.arraycopy(target, 0, colors, 0, 3);
      invalidateSelf();
      return;
    }
    blend = ValueAnimator.ofFloat(0, 1);
    blend.setDuration(650);
    blend.addUpdateListener(
        a -> {
          float f = (float) a.getAnimatedValue();
          for (int i = 0; i < 3; i++) colors[i] = mix(start[i], target[i], f);
          invalidateSelf();
        });
    blend.start();
  }

  void stop() {
    if (blend != null) blend.cancel();
    if (backgroundArt != null) { backgroundArt.recycle(); backgroundArt = null; }
  }

  @Override
  public void draw(Canvas canvas) {
    Rect b = getBounds();
    float w = b.width(), h = b.height();
    if (w <= 0 || h <= 0) return;
    if (artworkEnabled && backgroundArt != null && !backgroundArt.isRecycled()) {
      float scale = Math.max(w / backgroundArt.getWidth(), h / backgroundArt.getHeight());
      float iw = backgroundArt.getWidth() * scale, ih = backgroundArt.getHeight() * scale;
      RectF destination = new RectF(b.left + (w-iw)/2, b.top + (h-ih)/2,
          b.left + (w+iw)/2, b.top + (h+ih)/2);
      paint.setShader(null); paint.setAlpha(opacity); paint.setFilterBitmap(true);
      canvas.drawBitmap(backgroundArt, null, destination, paint);
      paint.setColor(0xB8000000);
      canvas.drawRect(b, paint);
      paint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
          0x18000000, 0x99000000, Shader.TileMode.CLAMP));
      canvas.drawRect(b, paint); paint.setShader(null);
      return;
    }
    paint.setAlpha(opacity);
    paint.setShader(new LinearGradient(0, 0, w, h, colors[0], colors[2], Shader.TileMode.CLAMP));
    canvas.drawRect(b, paint);
    paint.setShader(
        new RadialGradient(
            w * .75f,
            h * .3f,
            Math.max(w, h) * .8f,
            new int[] {colors[1], colors[1] & 0x00ffffff},
            null,
            Shader.TileMode.CLAMP));
    canvas.drawRect(b, paint);
    paint.setShader(null);
  }

  @Override
  public void setAlpha(int a) {
    opacity = a;
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter f) {
    paint.setColorFilter(f);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.OPAQUE;
  }
}
