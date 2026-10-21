package com.coolplayer.android;

import android.app.PendingIntent;
import android.content.*;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.*;
import java.util.*;
import org.json.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackService extends MediaSessionService {
  ExoPlayer player;
  MediaSession session;
  final AppEqualizer equalizer = new AppEqualizer();
  final android.content.SharedPreferences.OnSharedPreferenceChangeListener eqListener = (prefs, key) -> {
    if ("eqPreset".equals(key) && player != null) applyEqualizer();
  };
  void applyEqualizer() {
    boolean applied = equalizer.apply(this, player.getAudioSessionId());
    getSharedPreferences("playback", 0).edit().putBoolean("eqApplied", applied).apply();
    if (!applied && player.getAudioSessionId() > 0)
      android.widget.Toast.makeText(this, AppLanguage.text(this, "현재 오디오 출력에서 이퀄라이저를 사용할 수 없습니다"), android.widget.Toast.LENGTH_LONG).show();
  }
  final Handler handler = new Handler(Looper.getMainLooper());
  static final String SLEEP = "com.coolplayer.android.SLEEP";
  final Runnable sleep =
      () -> {
        if (player != null) {
          player.pause();
          getSharedPreferences("settings", 0).edit().remove("sleepEnd").apply();
        }
      };
  final Runnable persist =
      new Runnable() {
        public void run() {
          saveQueue();
          handler.postDelayed(this, 5000);
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    player =
        new ExoPlayer.Builder(this)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build();
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    session = new MediaSession.Builder(this, player).setSessionActivity(open).build();
    getSharedPreferences("settings", 0).registerOnSharedPreferenceChangeListener(eqListener);
    restoreQueue();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onShuffleModeEnabledChanged(boolean enabled) {
            if (enabled) player.setShuffleOrder(new androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(player.getMediaItemCount()));
          }

          @Override
          public void onAudioSessionIdChanged(int id) {
            getSharedPreferences("playback", 0).edit().putInt("audioSession", id).apply();
            equalizer.release();
            applyEqualizer();
          }

          @Override
          public void onEvents(Player p, Player.Events events) {
            saveQueue();
            PlayerWidget.updateAll(PlaybackService.this, p);
          }
        });
    handler.post(persist);
  }

  @Override
  public MediaSession onGetSession(MediaSession.ControllerInfo info) {
    return session;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    // Widget PendingIntents deliver directly to the owner of the player. No transient
    // controller can disconnect before the command reaches the playback thread.
    if (intent != null && intent.getAction() != null) {
      String action = intent.getAction();
      String prefix = getPackageName() + ".widget.";
      if (action.equals(prefix + "playlist")) {
        Library library = new Library(this);
        ArrayList<String> ids = library.playlists.get(intent.getStringExtra("playlist"));
        ArrayList<MediaItem> items = new ArrayList<>();
        if (ids != null) for (String trackId : ids) {
          Library.Track track = library.find(trackId);
          if (track != null) items.add(track.media());
        }
        if (!items.isEmpty()) { player.setMediaItems(items); player.prepare(); player.play(); }
      }
      else if (action.equals(prefix + "previous")) player.seekToPreviousMediaItem();
      else if (action.equals(prefix + "next")) player.seekToNextMediaItem();
      else if (action.equals(prefix + "toggle")) {
        if (player.getPlayWhenReady()) player.pause();
        else {
          if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
          player.prepare();
          player.play();
        }
      }
      if (action.startsWith(prefix)) {
        saveQueue();
        PlayerWidget.updateAll(this, player);
      }
    }
    if (intent != null && SLEEP.equals(intent.getAction())) {
      handler.removeCallbacks(sleep);
      long minutes = intent.getLongExtra("minutes", 0);
      if (minutes > 0) handler.postDelayed(sleep, minutes * 60_000);
      getSharedPreferences("settings", 0)
          .edit()
          .putLong("sleepEnd", minutes > 0 ? System.currentTimeMillis() + minutes * 60_000 : 0)
          .apply();
    }
    return super.onStartCommand(intent, flags, id);
  }

  void saveQueue() {
    if (player == null) return;
    JSONArray a = new JSONArray();
    for (int i = 0; i < player.getMediaItemCount(); i++) a.put(player.getMediaItemAt(i).mediaId);
    getSharedPreferences("playback", 0)
        .edit()
        .putString("queue", a.toString())
        .putInt("index", player.getCurrentMediaItemIndex())
        .putLong("position", player.getCurrentPosition())
        .putBoolean("shuffle", player.getShuffleModeEnabled())
        .putInt("repeat", player.getRepeatMode())
        .putString(
            "title",
            String.valueOf(
                player.getMediaMetadata().title == null
                    ? "Cool Player"
                    : player.getMediaMetadata().title))
        .putString(
            "artist",
            String.valueOf(
                player.getMediaMetadata().artist == null
                    ? "음악을 선택하세요"
                    : player.getMediaMetadata().artist))
        .apply();
  }

  void restoreQueue() {
    try {
      SharedPreferences p = getSharedPreferences("playback", 0);
      JSONArray a = new JSONArray(p.getString("queue", "[]"));
      Library l = new Library(this);
      ArrayList<MediaItem> items = new ArrayList<>();
      int old = p.getInt("index", 0), index = 0;
      for (int i = 0; i < a.length(); i++) {
        Library.Track t = l.find(a.getString(i));
        if (t != null) {
          if (i == old) index = items.size();
          items.add(t.media());
        }
      }
      if (!items.isEmpty()) {
        player.setMediaItems(items, index, p.getLong("position", 0));
        player.prepare();
      }
      player.setShuffleModeEnabled(p.getBoolean("shuffle", false));
      player.setRepeatMode(p.getInt("repeat", Player.REPEAT_MODE_OFF));
    } catch (Exception ignored) {
    }
    getSharedPreferences("settings", 0).edit().remove("sleepEnd").apply();
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    // Removing the app from Recents explicitly ends playback.
    handler.removeCallbacks(sleep);
    getSharedPreferences("settings", 0).edit().remove("sleepEnd").apply();
    if (player != null) {
      player.pause();
      saveQueue();
      PlayerWidget.updateAll(this, player);
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    saveQueue();
    if (player != null) {
      player.pause();
      PlayerWidget.updateAll(this, player);
    }
    getSharedPreferences("settings", 0).unregisterOnSharedPreferenceChangeListener(eqListener);
    equalizer.release();
    if (session != null) session.release();
    if (player != null) player.release();
    super.onDestroy();
  }
}
