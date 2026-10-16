package com.coolplayer.android;
import android.content.Context;
import java.util.Locale;
import org.json.*;
final class AppLanguage {
  private static JSONObject words;
  static String text(Context c, String source) {
    String lang = c.getSharedPreferences("settings", 0).getString("language", "system");
    if (lang.equals("system")) lang = Locale.getDefault().getLanguage();
    if (!lang.equals("en") && !lang.equals("ja")) return source;
    try {
      if (words == null) try (java.io.InputStream in = c.getAssets().open("translations.json")) {
        words = new JSONObject(new String(Library.readBytes(in, 200000), java.nio.charset.StandardCharsets.UTF_8));
      }
      JSONArray translated = words.optJSONArray(source);
      return translated == null ? source : translated.getString(lang.equals("ja") ? 1 : 0);
    } catch (Exception e) { return source; }
  }
}
