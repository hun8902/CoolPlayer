package com.coolplayer.android;
import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.widget.RemoteViews;
public class PlaylistWidget extends AppWidgetProvider {
  @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }
  @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, android.os.Bundle options) { updateAll(c); }
  static void updateAll(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    for (Class<?> type : new Class<?>[]{PlaylistWidget.class, SmallPlaylistWidget.class}) {
    for (int id : m.getAppWidgetIds(new ComponentName(c, type))) {
      RemoteViews v = new RemoteViews(c.getPackageName(), type == SmallPlaylistWidget.class ? R.layout.widget_playlists_small : R.layout.widget_playlists);
      Intent adapter = new Intent(c, PlaylistWidgetService.class).putExtra("compact", type == SmallPlaylistWidget.class).setData(Uri.parse("coolplayer://playlists/" + id));
      v.setRemoteAdapter(R.id.widget_playlists, adapter);
      v.setEmptyView(R.id.widget_playlists, R.id.widget_empty);
      Intent play = new Intent(c, PlaybackService.class).setAction(c.getPackageName() + ".widget.playlist");
      v.setPendingIntentTemplate(R.id.widget_playlists, PendingIntent.getService(c, 1000 + id, play,
          PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)));
      PendingIntent open = PendingIntent.getActivity(c, 2000 + id, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      v.setOnClickPendingIntent(R.id.playlists_heading, open);
      v.setOnClickPendingIntent(R.id.widget_empty, open);
      m.updateAppWidget(id, v);
      m.notifyAppWidgetViewDataChanged(id, R.id.widget_playlists);
    }
    }
  }
}
