package com.coolplayer.android;
import android.content.Intent;
import android.widget.*;
import java.util.*;
public class PlaylistWidgetService extends RemoteViewsService {
  @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
    return new RemoteViewsFactory() {
      final ArrayList<String> names = new ArrayList<>();
      final ArrayList<Integer> counts = new ArrayList<>();
      public void onCreate() { }
      public void onDataSetChanged() {
        Library l = new Library(getApplicationContext());
        names.clear(); counts.clear();
        for (Map.Entry<String,ArrayList<String>> e : l.playlists.entrySet()) {
          names.add(e.getKey());
          int count = 0; for (String id : e.getValue()) if (l.find(id) != null) count++;
          counts.add(count);
        }
      }
      public void onDestroy() { }
      public int getCount() { return names.size(); }
      public RemoteViews getViewAt(int position) {
        if (position < 0 || position >= names.size()) return null;
        RemoteViews v = new RemoteViews(getPackageName(), R.layout.widget_playlist_row);
        v.setTextViewText(R.id.playlist_name, names.get(position));
        v.setTextViewText(R.id.playlist_count, counts.get(position) + "곡");
        v.setOnClickFillInIntent(R.id.playlist_row, new Intent().putExtra("playlist", names.get(position)));
        return v;
      }
      public RemoteViews getLoadingView() { return null; }
      public int getViewTypeCount() { return 1; }
      public long getItemId(int p) { return p; }
      public boolean hasStableIds() { return false; }
    };
  }
}
