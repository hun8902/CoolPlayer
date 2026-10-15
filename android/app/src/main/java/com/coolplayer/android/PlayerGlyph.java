package com.coolplayer.android;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Resolution-independent controls, with identical optical bounds across screen sizes. */
final class PlayerGlyph extends Drawable {
  final String name;
  final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  final int size;

  PlayerGlyph(String name, int color, int size) {
    this.name = name;
    this.size = size;
    paint.setColor(color);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
  }

  void line(Canvas c, float... v) {
    Path p = new Path();
    p.moveTo(v[0], v[1]);
    for (int i = 2; i < v.length; i += 2) p.lineTo(v[i], v[i + 1]);
    c.drawPath(p, paint);
  }

  @Override
  public void draw(Canvas canvas) {
    canvas.save();
    canvas.translate(getBounds().left, getBounds().top);
    canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
    paint.setStyle(Paint.Style.STROKE);
    switch (name) {
      case "play":
        paint.setStyle(Paint.Style.FILL);
        line(canvas, 8, 5, 19, 12, 8, 19, 8, 5);
        break;
      case "pause":
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(7, 5, 10, 19, 1, 1, paint);
        canvas.drawRoundRect(14, 5, 17, 19, 1, 1, paint);
        break;
      case "next":
        paint.setStyle(Paint.Style.FILL);
        line(canvas, 6, 5, 16, 12, 6, 19, 6, 5);
        paint.setStyle(Paint.Style.STROKE);
        line(canvas, 18, 5, 18, 19);
        break;
      case "previous":
        paint.setStyle(Paint.Style.FILL);
        line(canvas, 18, 5, 8, 12, 18, 19, 18, 5);
        paint.setStyle(Paint.Style.STROKE);
        line(canvas, 6, 5, 6, 19);
        break;
      case "shuffle":
        line(canvas, 3, 6, 6, 6, 18, 18, 21, 18);
        line(canvas, 17, 14, 21, 18, 17, 22);
        line(canvas, 3, 18, 6, 18, 10, 14);
        line(canvas, 14, 10, 18, 6, 21, 6);
        line(canvas, 17, 2, 21, 6, 17, 10);
        break;
      case "repeat_one":
      case "repeat":
        line(canvas, 4, 10, 4, 6, 20, 6, 17, 3);
        line(canvas, 20, 14, 20, 18, 4, 18, 7, 21);
        if (name.equals("repeat_one")) {
          paint.setStyle(Paint.Style.FILL);
          paint.setTextSize(8);
          paint.setTextAlign(Paint.Align.CENTER);
          canvas.drawText("1", 12, 15, paint);
        }
        break;
      case "heart":
        Path p = new Path();
        p.moveTo(12, 20);
        p.cubicTo(0, 12, 2, 4, 7, 4);
        p.cubicTo(10, 4, 12, 7, 12, 7);
        p.cubicTo(12, 7, 14, 4, 17, 4);
        p.cubicTo(22, 4, 24, 12, 12, 20);
        canvas.drawPath(p, paint);
        break;
      case "back":
        line(canvas, 6, 9, 12, 15, 18, 9);
        break;
      case "add":
        line(canvas, 12, 5, 12, 19);
        line(canvas, 5, 12, 19, 12);
        break;
      case "more":
        paint.setStyle(Paint.Style.FILL);
        for (int i = 5; i < 22; i += 7) canvas.drawCircle(i, 12, 1.5f, paint);
        break;
      case "album":
        canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
        canvas.drawCircle(12, 12, 5, paint);
        canvas.drawCircle(12, 12, 1, paint);
        break;
      case "settings":
        canvas.drawCircle(12, 12, 4, paint);
        for (int i = 0; i < 8; i++) {
          canvas.save();
          canvas.rotate(i * 45, 12, 12);
          line(canvas, 12, 2, 12, 5);
          canvas.restore();
        }
        break;
      case "lyrics":
        canvas.drawRoundRect(3, 3, 21, 18, 3, 3, paint);
        line(canvas, 7, 18, 7, 22, 12, 18);
        line(canvas, 7, 8, 17, 8);
        line(canvas, 7, 12, 15, 12);
        break;
      case "music":
        line(canvas, 9, 17, 9, 5, 20, 3, 20, 15);
        canvas.drawOval(3, 15, 9, 20, paint);
        canvas.drawOval(14, 13, 20, 18, paint);
        break;
      default:
        line(canvas, 4, 6, 20, 6);
        line(canvas, 4, 12, 20, 12);
        line(canvas, 4, 18, 15, 18);
        break;
    }
    canvas.restore();
  }

  @Override
  public int getIntrinsicWidth() {
    return size;
  }

  @Override
  public int getIntrinsicHeight() {
    return size;
  }

  @Override
  public void setAlpha(int a) {
    paint.setAlpha(a);
  }

  @Override
  public void setColorFilter(ColorFilter f) {
    paint.setColorFilter(f);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
