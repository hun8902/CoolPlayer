package com.coolplayer.android;

import android.text.Html;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.json.*;

final class Lyrics {
  static class Line {
    long time;
    String text;

    Line(long t, String s) {
      time = t;
      text = s;
    }
  }

  static class Result {
    String id, title, artist, album, source, text = "";

    public String toString() {
      return title + "\n" + artist + " · " + source + (album.trim().isEmpty() ? "" : " · " + album);
    }
  }

  static class Page {
    ArrayList<Result> results = new ArrayList<>();
    boolean more;
    String notice = "";
  }

  static ArrayList<Line> parse(String raw) {
    ArrayList<Line> lines = new ArrayList<>();
    Pattern p = Pattern.compile("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    long offset = 0;
    Matcher off =
        Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE).matcher(raw);
    if (off.find())
      try {
        offset = Long.parseLong(off.group(1));
      } catch (Exception ignored) {
      }
    for (String row : raw.split("\\r?\\n")) {
      Matcher m = p.matcher(row);
      ArrayList<Long> times = new ArrayList<>();
      int end = 0;
      while (m.find()) {
        String f = m.group(3);
        long fraction = f == null ? 0 : Long.parseLong((f + "00").substring(0, 3));
        times.add(
            Long.parseLong(m.group(1)) * 60000
                + Long.parseLong(m.group(2)) * 1000
                + fraction
                + offset);
        end = m.end();
      }
      for (long time : times) lines.add(new Line(time, row.substring(end).trim()));
    }
    lines.sort(Comparator.comparingLong(l -> l.time));
    return lines;
  }

  static String request(String address) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
    c.setConnectTimeout(12000);
    c.setReadTimeout(15000);
    c.setRequestProperty("User-Agent", "CoolPlayer/1.0 (Android local music player)");
    c.setRequestProperty("Accept", "application/json,text/html");
    try {
      if (c.getResponseCode() != 200) throw new IOException("응답 " + c.getResponseCode());
      try (InputStream in = c.getInputStream()) {
        return new String(Library.readBytes(in, 4_000_000), StandardCharsets.UTF_8);
      }
    } finally {
      c.disconnect();
    }
  }

  static String enc(String text) {
    return android.net.Uri.encode(Normalizer.normalize(text.trim(), Normalizer.Form.NFC));
  }

  static String html(String text) {
    return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim();
  }

  static String match(String pattern, String text) {
    Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
    return m.find() ? html(m.group(1)) : "";
  }

  static Page search(String title, String artist, int page) {
    Page out = new Page();
    String query = (title + " " + artist).trim();
    if (query.isEmpty()) return out;
    try {
      String raw =
          request(
              "https://music.bugs.co.kr/search/track?q="
                  + enc(query)
                  + "&target=ARTIST_TRACK_ALBUM&page="
                  + (page + 1));
      Matcher rows =
          Pattern.compile(
                  "<tr\\b[^>]*trackId=\"([0-9]+)\"[^>]*>(.*?)</tr>",
                  Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
              .matcher(raw);
      while (rows.find()) {
        String body = rows.group(2);
        Result r = new Result();
        r.source = "Bugs";
        r.id = rows.group(1);
        r.title = match("track_title=\"([^\"]*)\"", body);
        r.artist = match("artist_disp_nm=\"([^\"]*)\"", body);
        r.album = match("class=\"album\"[^>]*title=\"([^\"]*)\"", body);
        if (!r.title.trim().isEmpty()) out.results.add(r);
      }
      Matcher pages = Pattern.compile("retriveTrackPage\\(([0-9]+)\\)").matcher(raw);
      while (pages.find()) if (Integer.parseInt(pages.group(1)) > page + 1) out.more = true;
    } catch (Exception e) {
      out.notice = "Bugs 연결이 원활하지 않습니다. ";
    }
    if (page == 0) {
      LinkedHashSet<String> queries = new LinkedHashSet<>();
      queries.add(query);
      queries.add(international(query));
      queries.add(international(query).replace("Kenshi Yonezu", "米津玄師"));
      HashSet<String> ids = new HashSet<>();
      ArrayList<Result> internationalResults = new ArrayList<>();
      for (String term : queries) {
        try {
          JSONArray array = new JSONArray(request("https://lrclib.net/api/search?q=" + enc(term)));
          for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.getJSONObject(i);
            Result r = new Result();
            r.source = "LRCLIB";
            r.id = j.optString("id");
            r.title = j.optString("trackName");
            r.artist = j.optString("artistName");
            r.album = j.optString("albumName");
            r.text = j.optString("syncedLyrics", "");
            if (r.text.trim().isEmpty() || r.text.equals("null")) r.text = j.optString("plainLyrics", "");
            if (!r.text.trim().isEmpty() && !r.text.equals("null") && ids.add(r.id)) internationalResults.add(r);
          }
        } catch (Exception e) {
          out.notice += "LRCLIB 연결을 확인해 주세요. ";
        }
      }
      out.results.addAll(0, internationalResults);
    }
    return out;
  }

  static String resolve(Result r) throws Exception {
    if (!r.text.isEmpty()) return r.text;
    if (r.source.equals("Bugs")) {
      for (String kind : new String[] {"T", "N"}) {
        JSONObject j =
            new JSONObject(request("https://music.bugs.co.kr/player/lyrics/" + kind + "/" + r.id));
        String raw = j.optString("lyrics", "");
        if (raw.trim().isEmpty() || raw.equals("null")) continue;
        if (kind.equals("T") && raw.contains("|")) {
          StringBuilder b = new StringBuilder();
          for (String row : raw.split("[＃#]")) {
            int split = row.indexOf('|');
            if (split < 0) continue;
            try {
              long ms = (long) (Double.parseDouble(row.substring(0, split).trim()) * 1000);
              b.append(
                  String.format(
                      Locale.ROOT,
                      "[%02d:%02d.%02d]%s\n",
                      ms / 60000,
                      ms / 1000 % 60,
                      ms / 10 % 100,
                      html(row.substring(split)).replaceFirst("^\\|", "")));
            } catch (NumberFormatException ignored) {
            }
          }
          if (b.length() > 0) return b.toString();
        } else if (kind.equals("N"))
          return html(raw.replace("＃", "\n").replace("<br>", "\n").replace("<br/>", "\n"));
      }
    }
    throw new IOException("이 곡에 제공되는 가사가 없습니다");
  }

  static String international(String query) {
    return Normalizer.normalize(query, Normalizer.Form.NFKC)
        .replaceAll("(?i)요아소비|ヨアソビ", "YOASOBI")
        .replaceAll("요네즈[\\s]*[켄캔]시|米津玄師", "Kenshi Yonezu")
        .replace("아이묭", "aimyon").replace("아도", "Ado");
  }

  static String norm(String s) {
    return Normalizer.normalize(international(s), Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replace("십센치", "10cm")
        .replace("아이유", "iu")
        .replaceAll("[^\\p{L}\\p{N}]", "");
  }

  static String automatic(Library.Track t) throws Exception {
    Page p = search(t.title, t.artist.equals("알 수 없는 아티스트") ? "" : t.artist, 0);
    String plain = null;
    for (Result r : p.results) {
      if (!norm(r.title).equals(norm(t.title))) continue;
      if (!t.artist.equals("알 수 없는 아티스트") && !norm(r.artist).equals(norm(t.artist))) continue;
      try {
        String raw = resolve(r);
        if (!parse(raw).isEmpty()) return raw;
        if (plain == null) plain = raw;
      } catch (Exception ignored) {
      }
    }
    if (plain != null) return plain;
    throw new IOException("일치하는 가사를 찾지 못했습니다. 곡명과 가수를 직접 검색해 주세요.");
  }
}
