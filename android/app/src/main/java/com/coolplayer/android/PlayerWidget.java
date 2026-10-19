package com.coolplayer.android;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.*;
import android.widget.RemoteViews;
import androidx.media3.common.*;
import androidx.media3.session.*;
import com.google.common.util.concurrent.ListenableFuture;

public class PlayerWidget extends AppWidgetProvider {
  @Override
  public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
    updateAll(c, null);
  }

  @Override
  public void onAppWidgetOptionsChanged(Context c, AppWidgetManager manager, int id, android.os.Bundle options) {
    updateAll(c, null);
  }

  static void updateAll(Context c, Player player) {
    updateType(c, player, PlayerWidget.class, R.layout.widget);
    updateType(c, player, SquarePlayerWidget.class, R.layout.widget_square);
  }

  static void updateType(Context c, Player player, Class<?> provider, int layout) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    int[] ids = m.getAppWidgetIds(new ComponentName(c, provider));
    if (ids.length == 0) return;
    RemoteViews v = new RemoteViews(c.getPackageName(), layout);
    String title = c.getSharedPreferences("playback", 0).getString("title", "Cool Player"),
        artist = c.getSharedPreferences("playback", 0).getString("artist", "음악을 선택하세요");
    v.setTextViewText(R.id.widget_title, title);
    v.setTextViewText(R.id.widget_artist, artist);
    android.content.SharedPreferences state = c.getSharedPreferences("widget", 0);
    if (player != null) state.edit()
        .putBoolean("playing", player.isPlaying())
        .putString("art", player.getMediaMetadata().artworkUri == null ? "" : player.getMediaMetadata().artworkUri.getPath()).apply();
    boolean playing = player != null ? player.isPlaying() : state.getBoolean("playing", false);
    widgetIcon(v, R.id.widget_play, playing ? "pause" : "play");
    v.setContentDescription(R.id.widget_play, playing ? "일시정지" : "재생");
    widgetIcon(v, R.id.widget_previous, "previous");
    widgetIcon(v, R.id.widget_next, "next");
    Bitmap placeholder = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    Canvas placeholderCanvas = new Canvas(placeholder);
    Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    placeholderPaint.setColor(0xffe0e6f0);
    placeholderCanvas.drawRoundRect(new RectF(0, 0, 256, 256), 24, 24, placeholderPaint);
    PlayerGlyph note = new PlayerGlyph("music", 0xff637da5, 100);
    note.setBounds(78, 78, 178, 178);
    note.draw(placeholderCanvas);
    v.setImageViewBitmap(R.id.widget_art, placeholder);
    String art = state.getString("art", "");
    if (!art.isEmpty()) {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(art, options);
      options.inSampleSize = 1;
      while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 384)
        options.inSampleSize *= 2;
      options.inJustDecodeBounds = false;
      Bitmap source = BitmapFactory.decodeFile(art, options);
      if (source != null) {
        Bitmap cover = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cover);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        float scale = 256f / Math.min(source.getWidth(), source.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate((256 - source.getWidth() * scale) / 2, (256 - source.getHeight() * scale) / 2);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        canvas.drawRoundRect(new RectF(0, 0, 256, 256), 24, 24, paint);
        v.setImageViewBitmap(R.id.widget_art, cover);
        source.recycle();
      }
    }
    v.setOnClickPendingIntent(
        R.id.widget_open,
        PendingIntent.getActivity(
            c,
            0,
            new Intent(c, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    int[] controls = {R.id.widget_previous, R.id.widget_play, R.id.widget_next};
    String[] actions = {"previous", "toggle", "next"};
    for (int i = 0; i < controls.length; i++) {
      Intent in =
          new Intent(c, PlayerWidget.class).setAction(c.getPackageName() + "." + actions[i]);
      v.setOnClickPendingIntent(
          controls[i],
          PendingIntent.getBroadcast(
              c, i + 10, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
    for (int id : ids) {
      RemoteViews sized = new RemoteViews(v);
      android.os.Bundle options = m.getAppWidgetOptions(id);
      int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
      int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160);
      // On Android 12+ use actual launcher bounds rather than one fixed art size.
      if (android.os.Build.VERSION.SDK_INT >= 31 && layout == R.layout.widget) {
        int artSize = Math.max(56, Math.min(112, Math.min(height - 32, width / 3)));
        sized.setViewLayoutWidth(R.id.widget_art, artSize, android.util.TypedValue.COMPLEX_UNIT_DIP);
        sized.setViewLayoutHeight(R.id.widget_art, artSize, android.util.TypedValue.COMPLEX_UNIT_DIP);
      }
      m.updateAppWidget(id, sized);
    }
  }

  static void widgetIcon(RemoteViews views, int id, String name) {
    Bitmap bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
    PlayerGlyph glyph = new PlayerGlyph(name, 0xff202633, 72);
    glyph.setBounds(0, 0, 72, 72);
    glyph.draw(new Canvas(bitmap));
    views.setImageViewBitmap(id, bitmap);
  }

  @Override
  public void onReceive(Context c, Intent intent) {
    super.onReceive(c, intent);
    String action = intent.getAction();
    if (action == null || !action.startsWith(c.getPackageName() + ".")) return;
    PendingResult pending = goAsync();
    ListenableFuture<MediaController> future =
        new MediaController.Builder(
                c, new SessionToken(c, new ComponentName(c, PlaybackService.class)))
            .buildAsync();
    future.addListener(
        () -> {
          try {
            MediaController p = future.get();
            if (action.endsWith("previous")) p.seekToPreviousMediaItem();
            else if (action.endsWith("next")) p.seekToNextMediaItem();
            else {
              if (p.isPlaying()) p.pause();
              else {
                p.prepare();
                p.play();
              }
            }
            updateAll(c, p);
            p.release();
          } catch (Exception ignored) {
          } finally {
            pending.finish();
          }
        },
        new android.os.Handler(android.os.Looper.getMainLooper())::post);
  }
}
