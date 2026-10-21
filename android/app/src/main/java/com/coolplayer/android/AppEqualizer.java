package com.coolplayer.android;

import android.content.Context;
import android.media.audiofx.Equalizer;

/** Applies app presets only to this player's audio session. Gains are millibels. */
final class AppEqualizer {
  static final String[] NAMES = {"끔", "플랫", "어쿠스틱", "저음 강조", "저음 줄이기", "클래식", "댄스", "일렉트로닉", "힙합", "재즈", "록", "보컬 강조"};
  static final int[][] GAINS = {
      {0,0,0,0,0}, {0,0,0,0,0}, {200,100,0,150,200}, {500,300,0,-100,-100},
      {-500,-300,0,0,0}, {200,0,-100,100,300}, {400,200,-100,200,300},
      {400,100,-100,200,400}, {500,300,0,100,100}, {250,100,-100,150,250},
      {350,150,-150,200,350}, {-200,-100,250,300,0}};
  private Equalizer effect;
  void release() { if (effect != null) { effect.release(); effect = null; } }
  boolean apply(Context context, int session) {
    int preset = context.getSharedPreferences("settings", 0).getInt("eqPreset", 0);
    if (preset < 0 || preset >= GAINS.length) preset = 0;
    if (preset == 0) { release(); return true; }
    if (session <= 0) return false;
    try {
      if (effect == null) effect = new Equalizer(0, session);
      short[] range = effect.getBandLevelRange();
      for (short band = 0; band < effect.getNumberOfBands(); band++) {
        double hz = effect.getCenterFreq(band) / 1000.0;
        double x = Math.max(0, Math.min(4, Math.log(Math.max(60, hz) / 60.0) / Math.log(4)));
        int lo = Math.min(3, (int)x);
        int gain = (int)Math.round(GAINS[preset][lo] * (1 - (x-lo)) + GAINS[preset][lo+1] * (x-lo));
        effect.setBandLevel(band, (short)Math.max(range[0], Math.min(range[1], gain)));
      }
      return effect.setEnabled(true) == 0;
    } catch (RuntimeException e) { release(); return false; }
  }
}
