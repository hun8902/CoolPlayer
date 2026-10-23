package com.coolplayer.android;
import android.content.Context;
import java.util.*;
import org.json.*;
final class AppLanguage {
  static final String[] CODES = {"system","ko","en","ja","zh-Hans","zh-Hant","es","fr","de","it","pt","ru","id","vi","th","tr","pl","nl","ar","hi"};
  static final String[] NAMES = {"기기 설정","한국어","English","日本語","简体中文","繁體中文","Español","Français","Deutsch","Italiano","Português","Русский","Bahasa Indonesia","Tiếng Việt","ไทย","Türkçe","Polski","Nederlands","العربية","हिन्दी"};
  private static JSONObject words;
  private static final Map<String,JSONObject> extra = new HashMap<>();
  static String language(Context c) {
    String lang = c.getSharedPreferences("settings",0).getString("language","system");
    if (lang.equals("system")) {
      Locale l = Locale.getDefault(); lang = l.getLanguage();
      if (lang.equals("zh")) lang = l.getScript().equals("Hant") || Arrays.asList("TW","HK","MO").contains(l.getCountry()) ? "zh-Hant" : "zh-Hans";
    }
    return lang;
  }
  static String displayName(Context c) {
    String code = c.getSharedPreferences("settings",0).getString("language","system");
    int index = Arrays.asList(CODES).indexOf(code);
    return index < 0 ? code : text(c,NAMES[index]);
  }
  static synchronized String text(Context c, String source) {
    if (source == null) return "";
    String lang = language(c);
    if (lang.equals("ko")) return source;
    try {
      if (words == null) words = read(c,"translations.json");
      if (!lang.equals("en") && !lang.equals("ko")) {
        if (!extra.containsKey(lang)) {
          try { extra.put(lang,read(c,"translations-"+lang+".json")); }
          catch (Exception e) { extra.put(lang,new JSONObject()); }
        }
        String value = extra.get(lang).optString(source, "");
        if (!value.isEmpty()) return value;
      }
      JSONArray translated = words.optJSONArray(source);
      if (translated != null) return translated.getString(lang.equals("ja") ? 1 : 0);
      if (source.contains("\n")) {
        String[] lines = source.split("\n",-1);
        for (int i=0;i<lines.length;i++) lines[i]=text(c,lines[i]);
        return String.join("\n",lines);
      }
      return source;
    } catch (Exception e) { return source; }
  }
  private static JSONObject read(Context c,String asset) throws Exception {
    try (java.io.InputStream in=c.getAssets().open(asset)) {
      return new JSONObject(new String(Library.readBytes(in,1000000),java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
