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
      case "remove":
        canvas.drawCircle(12, 12, 9, paint);
        line(canvas, 7, 12, 17, 12);
        break;
      case "language":
        canvas.drawCircle(12, 12, 9, paint);
        canvas.drawOval(8, 3, 16, 21, paint);
        line(canvas, 3, 12, 21, 12);
        break;
      case "eq":
        line(canvas, 6, 3, 6, 21); line(canvas, 12, 3, 12, 21); line(canvas, 18, 3, 18, 21);
        line(canvas, 3, 8, 9, 8); line(canvas, 9, 16, 15, 16); line(canvas, 15, 10, 21, 10);
        break;
      case "folder":
        line(canvas, 3, 7, 3, 20, 21, 20, 21, 7, 12, 7, 9, 4, 3, 4, 3, 7);
        break;
      case "edit":
        line(canvas, 5, 16, 16, 5, 20, 9, 9, 20, 4, 21, 5, 16, 9, 20);
        break;
      case "export":
        line(canvas, 12, 15, 12, 3, 7, 8);
        line(canvas, 12, 3, 17, 8);
        line(canvas, 5, 12, 5, 21, 19, 21, 19, 12);
        break;
      case "clock":
        canvas.drawCircle(12, 12, 9, paint);
        line(canvas, 12, 6, 12, 12, 16, 14);
        break;
      case "font":
        line(canvas, 3, 19, 9, 5, 15, 19);
        line(canvas, 5, 14, 13, 14);
        line(canvas, 16, 10, 23, 10);
        line(canvas, 19.5f, 10, 19.5f, 19);
        break;
      case "trash":
        line(canvas, 4, 6, 20, 6);
        line(canvas, 9, 6, 9, 3, 15, 3, 15, 6);
        line(canvas, 6, 6, 7, 21, 17, 21, 18, 6);
        line(canvas, 10, 10, 10, 17);
        line(canvas, 14, 10, 14, 17);
        break;
      case "info":
        canvas.drawCircle(12, 12, 9, paint);
        line(canvas, 12, 11, 12, 17);
        canvas.drawPoint(12, 7, paint);
        break;
      case "close":
        line(canvas, 7, 7, 17, 17);
        line(canvas, 17, 7, 7, 17);
        break;
      case "search":
        canvas.drawCircle(10, 10, 6, paint);
        line(canvas, 15, 15, 21, 21);
        break;
      case "sort":
        line(canvas, 4, 6, 20, 6);
        line(canvas, 7, 12, 17, 12);
        line(canvas, 10, 18, 14, 18);
        break;
      case "download":
        line(canvas, 12, 3, 12, 16);
        line(canvas, 7, 11, 12, 16, 17, 11);
        line(canvas, 4, 17, 4, 21, 20, 21, 20, 17);
        break;
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
      case "shuffle_on":
      case "shuffle":
        paint.setStrokeWidth(1.7f);
        Path shuffleRoute = new Path();
        shuffleRoute.moveTo(3.5f, 17);
        shuffleRoute.lineTo(6, 17);
        shuffleRoute.cubicTo(10, 17, 14, 6, 18, 6);
        shuffleRoute.lineTo(20.5f, 6);
        // The second route passes behind the first with a deliberate central gap.
        shuffleRoute.moveTo(3.5f, 6);
        shuffleRoute.lineTo(6, 6);
        shuffleRoute.cubicTo(7.7f, 6, 8.9f, 7.8f, 10.2f, 9.7f);
        shuffleRoute.moveTo(13.8f, 13.3f);
        shuffleRoute.cubicTo(15.1f, 15.2f, 16.3f, 17, 18, 17);
        shuffleRoute.lineTo(20.5f, 17);
        canvas.drawPath(shuffleRoute, paint);
        // Compact arrowheads stay separate from the crossing and viewport edge.
        paint.setStyle(Paint.Style.FILL);
        Path arrows = new Path();
        arrows.moveTo(18, 3.5f); arrows.lineTo(21.5f, 6); arrows.lineTo(18, 8.5f); arrows.close();
        arrows.moveTo(18, 14.5f); arrows.lineTo(21.5f, 17); arrows.lineTo(18, 19.5f); arrows.close();
        canvas.drawPath(arrows, paint);
        if (name.equals("shuffle_on")) canvas.drawCircle(12, 22, 1.1f, paint);
        paint.setStyle(Paint.Style.STROKE);
        break;
      case "repeat_all":
      case "repeat_one":
      case "repeat":
        paint.setStrokeWidth(1.8f);
        Path loop = new Path();
        loop.moveTo(4, 10); loop.lineTo(4, 8); loop.quadTo(4, 6, 6, 6); loop.lineTo(20, 6);
        loop.moveTo(20, 14); loop.lineTo(20, 16); loop.quadTo(20, 18, 18, 18); loop.lineTo(4, 18);
        canvas.drawPath(loop, paint);
        if (name.equals("repeat")) {
          line(canvas, 17, 3, 20, 6, 17, 9);
          line(canvas, 7, 15, 4, 18, 7, 21);
        } else {
          paint.setStyle(Paint.Style.FILL);
          line(canvas, 17, 3, 21, 6, 17, 9, 17, 3);
          line(canvas, 7, 15, 3, 18, 7, 21, 7, 15);
          canvas.drawCircle(12, 22, 1.1f, paint);
          if (name.equals("repeat_one")) {
            paint.setTextSize(8);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText("1", 12, 12 - (metrics.ascent + metrics.descent) / 2, paint);
          }
          paint.setStyle(Paint.Style.STROKE);
        }
        break;
      case "heart_filled":
      case "heart":
        if (name.equals("heart_filled")) paint.setStyle(Paint.Style.FILL);
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
