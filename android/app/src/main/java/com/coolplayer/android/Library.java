package com.coolplayer.android;

import android.content.*;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import androidx.media3.common.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Library {
  static class Track {
    String id, uri, title, artist, album, art = "", format = "", lyrics = "";
    long duration;
    int sampleRate, bitDepth;
    boolean favorite;

    JSONObject json() throws JSONException {
      JSONObject j = new JSONObject();
      j.put("id", id);
      j.put("uri", uri);
      j.put("title", title);
      j.put("artist", artist);
      j.put("album", album);
      j.put("art", art);
      j.put("format", format);
      j.put("duration", duration);
      j.put("favorite", favorite);
      j.put("lyrics", lyrics);
      j.put("sampleRate", sampleRate);
      j.put("bitDepth", bitDepth);
      return j;
    }

    static Track from(JSONObject j) {
      Track t = new Track();
      t.id = j.optString("id");
      t.uri = j.optString("uri");
      t.title = j.optString("title");
      t.artist = j.optString("artist");
      t.album = j.optString("album");
      t.art = j.optString("art");
      t.format = j.optString("format");
      t.duration = j.optLong("duration");
      t.favorite = j.optBoolean("favorite");
      t.lyrics = j.optString("lyrics");
      t.sampleRate = j.optInt("sampleRate");
      t.bitDepth = j.optInt("bitDepth");
      return t;
    }

    MediaItem media() {
      MediaMetadata.Builder m =
          new MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album);
      if (!art.isEmpty()) m.setArtworkUri(Uri.fromFile(new File(art)));
      return new MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(m.build()).build();
    }
  }

  final Context context;
  final ArrayList<Track> tracks = new ArrayList<>();
  final LinkedHashMap<String, ArrayList<String>> playlists = new LinkedHashMap<>();

  Library(Context c) {
    context = c.getApplicationContext();
    load();
    if (!context.getSharedPreferences("settings", 0).getBoolean("experienceSongsV4", false)) {
      try {
        Track first = find("cool-player-welcome");
        if (first == null) {
          first = bundledTrack("cool-player-welcome", "amazing-grace.mp3", "Amazing Grace", "Rocks From The Garden", "MP3", 37000);
          first.lyrics = bundledLyrics("amazing-grace.lrc"); tracks.add(first);
        }
        if (first.art.isEmpty()) first.art = sampleCover("grace", "AMAZING", "GRACE", false);
        Track second = find("cool-player-experience-morning-blue");
        if (second == null) {
          second = bundledTrack("cool-player-experience-morning-blue", "morning-blue.mp3", "Morning Blue", "Josh Woodward", "MP3", 244872);
          second.art = sampleCover("morning-blue", "MORNING", "BLUE", false);
          tracks.add(second);
        }
        tracks.removeIf(track -> track.id.equals("cool-player-experience-auld") || track.id.equals("cool-player-experience-twinkle"));
        for (ArrayList<String> ids : playlists.values()) {
          for (int i = 0; i < ids.size(); i++) if (ids.get(i).equals("cool-player-experience-auld") || ids.get(i).equals("cool-player-experience-twinkle")) ids.set(i, second.id);
        }
        boolean hasExperience = false;
        for (ArrayList<String> ids : playlists.values()) if (ids.contains(first.id) && ids.contains(second.id)) hasExperience = true;
        if (!hasExperience) {
          String name = "Cool Player 체험"; int suffix = 2;
          while (playlists.containsKey(name)) name = "Cool Player 체험 " + suffix++;
          playlists.put(name, new ArrayList<>(Arrays.asList(first.id, second.id)));
        }
        save();
        context.getSharedPreferences("settings", 0).edit().putBoolean("experienceSongsV4", true).apply();
      } catch (IOException ignored) { }
    }
  }

  String sampleCover(String name, String title, String subtitle, boolean night) throws IOException {
    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888);
    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
    android.graphics.Paint paint = new android.graphics.Paint(3);
    paint.setShader(new android.graphics.LinearGradient(0, 0, 640, 640,
        night ? 0xff172742 : 0xffc1cdb7, night ? 0xff796b9c : 0xff4a7973, android.graphics.Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, 640, 640, paint); paint.setShader(null);
    if (night) {
      java.util.Random random = new java.util.Random(42);
      paint.setColor(0xffeee4d1);
      for (int i = 0; i < 64; i++) canvas.drawCircle(random.nextInt(640), random.nextInt(400), 1 + random.nextInt(3), paint);
      canvas.drawCircle(470, 150, 56, paint);
      paint.setColor(0xff3c4268); canvas.drawCircle(490, 132, 54, paint);
    } else {
      paint.setColor(0x66fdf6df); canvas.drawCircle(460, 160, 95, paint);
      for (int i = 0; i < 5; i++) { paint.setColor(0x24305050); canvas.drawOval(-200, 240+i*50, 900, 820+i*50, paint); }
    }
    paint.setColor(0xfffaf8f0); paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
    paint.setTextSize(50); canvas.drawText(title, 48, 470, paint);
    paint.setTextSize(38); canvas.drawText(subtitle, 48, 520, paint);
    paint.setTextSize(16); canvas.drawText("COOL PLAYER  /  LISTEN & DISCOVER", 48, 588, paint);
    File folder = new File(context.getFilesDir(), "experience");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create cover folder");
    File file = new File(folder, name + ".png");
    try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
    bitmap.recycle(); return file.getAbsolutePath();
  }

  Track bundledTrack(String id, String asset, String title, String artist, String format, long duration)
      throws IOException {
    File folder = new File(context.getFilesDir(), "experience");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create sample folder");
    File audio = new File(folder, asset);
    try (InputStream in = context.getAssets().open(asset); FileOutputStream out = new FileOutputStream(audio)) {
      out.write(readBytes(in, 4_000_000));
    }
    Track track = new Track();
    track.id = id; track.uri = Uri.fromFile(audio).toString();
    track.title = title; track.artist = artist; track.album = "Cool Player 체험";
    track.format = format; track.duration = duration;
    return track;
  }

  String bundledLyrics(String asset) throws IOException {
    try (InputStream in = context.getAssets().open(asset)) {
      return new String(readBytes(in, 100_000), StandardCharsets.UTF_8);
    }
  }

  synchronized void load() {
    tracks.clear();
    playlists.clear();
    try {
      JSONObject j =
          new JSONObject(
              new String(
                  java.nio.file.Files.readAllBytes(
                      new File(context.getFilesDir(), "library.json").toPath()),
                  StandardCharsets.UTF_8));
      JSONArray a = j.optJSONArray("tracks");
      if (a != null)
        for (int i = 0; i < a.length(); i++) tracks.add(Track.from(a.getJSONObject(i)));
      JSONObject p = j.optJSONObject("playlists");
      if (p != null)
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
          String k = it.next();
          ArrayList<String> ids = new ArrayList<>();
          JSONArray v = p.getJSONArray(k);
          for (int i = 0; i < v.length(); i++) ids.add(v.getString(i));
          playlists.put(k, ids);
        }
    } catch (Exception ignored) {
    }
  }

  synchronized void save() {
    try {
      JSONObject j = new JSONObject();
      JSONArray a = new JSONArray();
      for (Track t : tracks) a.put(t.json());
      j.put("tracks", a);
      JSONObject p = new JSONObject();
      for (String k : playlists.keySet()) p.put(k, new JSONArray(playlists.get(k)));
      j.put("playlists", p);
      File temp = new File(context.getFilesDir(), "library.tmp");
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(j.toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      java.nio.file.Files.move(
          temp.toPath(),
          new File(context.getFilesDir(), "library.json").toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      PlaylistWidget.updateAll(context);
    } catch (Exception e) {
      throw new IllegalStateException("보관함을 저장하지 못했습니다", e);
    }
  }

  synchronized Track find(String id) {
    for (Track t : tracks) if (t.id.equals(id)) return t;
    return null;
  }

  synchronized Track add(Uri uri) throws Exception {
    for (Track t : tracks) if (t.uri.equals(uri.toString())) return t;
    Track t = new Track();
    t.uri = uri.toString();
    t.id = UUID.randomUUID().toString();
    String name = "음원";
    try (Cursor c =
        context
            .getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) name = c.getString(0);
    }
    t.title = name.replaceFirst("\\.[^.]+$", "");
    t.artist = "알 수 없는 아티스트";
    t.album = "앨범 없음";
    t.format =
        name.contains(".")
            ? name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT)
            : "AUDIO";
    MediaMetadataRetriever r = new MediaMetadataRetriever();
    try {
      r.setDataSource(context, uri);
      t.title = value(r, MediaMetadataRetriever.METADATA_KEY_TITLE, t.title);
      t.artist = value(r, MediaMetadataRetriever.METADATA_KEY_ARTIST, t.artist);
      t.album = value(r, MediaMetadataRetriever.METADATA_KEY_ALBUM, t.album);
      t.duration = Long.parseLong(value(r, MediaMetadataRetriever.METADATA_KEY_DURATION, "0"));
      if (android.os.Build.VERSION.SDK_INT >= 31) {
        t.sampleRate =
            Integer.parseInt(value(r, MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, "0"));
        t.bitDepth =
            Integer.parseInt(value(r, MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE, "0"));
      }
      byte[] art = r.getEmbeddedPicture();
      if (art != null) {
        File f = new File(context.getFilesDir(), t.id + ".jpg");
        try (FileOutputStream o = new FileOutputStream(f)) {
          o.write(art);
        }
        t.art = f.getAbsolutePath();
      }
    } finally {
      r.release();
    }
    tracks.add(t);
    return t;
  }

  static String value(MediaMetadataRetriever r, int k, String fallback) {
    String s = r.extractMetadata(k);
    return s == null || s.trim().isEmpty() ? fallback : s;
  }

  void importFolder(Uri tree) throws Exception {
    walk(tree, DocumentsContract.getTreeDocumentId(tree));
  }

  private void walk(Uri tree, String parent) throws Exception {
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
    ArrayList<Uri> audio = new ArrayList<>();
    HashMap<String, Uri> texts = new HashMap<>();
    ArrayList<String> dirs = new ArrayList<>();
    try (Cursor c =
        context
            .getContentResolver()
            .query(
                children,
                new String[] {
                  DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                  DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                  DocumentsContract.Document.COLUMN_MIME_TYPE
                },
                null,
                null,
                null)) {
      if (c == null) return;
      while (c.moveToNext()) {
        String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) dirs.add(id);
        else if (name.matches("(?i).*\\.(flac|m4a|mp3|wav|ogg|opus|aac|aiff|alac)$")) audio.add(u);
        else if (name.matches("(?i).*\\.(lrc|txt)$"))
          texts.put(name.replaceFirst("\\.[^.]+$", "").toLowerCase(Locale.ROOT), u);
      }
    }
    for (Uri u : audio) {
      try {
        Track t = add(u);
        String name = "";
        try (Cursor c =
            context
                .getContentResolver()
                .query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
          if (c != null && c.moveToFirst()) name = c.getString(0);
        }
        Uri l = texts.get(name.replaceFirst("\\.[^.]+$", "").toLowerCase(Locale.ROOT));
        if (l != null) t.lyrics = read(l);
      } catch (Exception ignored) {
      }
    }
    for (String d : dirs) walk(tree, d);
  }

  String read(Uri uri) throws IOException {
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("파일을 열 수 없습니다");
      byte[] b = readBytes(in, 2_000_000);
      return new String(b, StandardCharsets.UTF_8).replace("\uFEFF", "");
    }
  }

  static byte[] readBytes(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > limit) throw new IOException("파일이 너무 큽니다");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  static String time(long ms) {
    return String.format(
        Locale.ROOT, "%d:%02d", Math.max(0, ms) / 60000, Math.max(0, ms) / 1000 % 60);
  }
}
