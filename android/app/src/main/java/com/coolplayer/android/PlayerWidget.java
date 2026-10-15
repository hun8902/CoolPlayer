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

  static void updateAll(Context c, Player player) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    int[] ids = m.getAppWidgetIds(new ComponentName(c, PlayerWidget.class));
    if (ids.length == 0) return;
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
    String title = c.getSharedPreferences("playback", 0).getString("title", "Cool Player"),
        artist = c.getSharedPreferences("playback", 0).getString("artist", "음악을 선택하세요");
    v.setTextViewText(R.id.widget_title, title);
    v.setTextViewText(R.id.widget_artist, artist);
    v.setTextViewText(R.id.widget_play, player != null && player.isPlaying() ? "Ⅱ" : "▶");
    v.setImageViewResource(R.id.widget_art, R.drawable.brand);
    if (player != null && player.getMediaMetadata().artworkUri != null) {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = 8;
      Bitmap b = BitmapFactory.decodeFile(player.getMediaMetadata().artworkUri.getPath(), options);
      if (b != null) v.setImageViewBitmap(R.id.widget_art, b);
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
    m.updateAppWidget(ids, v);
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
