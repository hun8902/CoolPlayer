package com.coolplayer.android;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** GitHub release updates, with explicit download and Android's installation confirmation. */
final class AppUpdates {
  static final String ROOT = "https://github.com/hun8902/CoolPlayer/releases/download/";
  final MainActivity a;
  final SharedPreferences prefs;
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  boolean busy;
  volatile boolean closed;
  boolean waitingForPermission;
  JSONObject pending;

  AppUpdates(MainActivity activity) {
    a = activity;
    prefs = a.getSharedPreferences("app_updates", 0);
    try {
      JSONObject saved = new JSONObject(prefs.getString("pending", "{}"));
      long installed = code(a.getPackageManager().getPackageInfo(a.getPackageName(), 0));
      if (saved.optLong("versionCode") <= installed) {
        apk().delete();
        prefs.edit().remove("pending").apply();
      }
    } catch (Exception ignored) {
    }
  }

  String version() {
    try {
      return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
    } catch (Exception e) {
      return "?";
    }
  }

  long code(PackageInfo p) {
    return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
  }

  boolean automatic() {
    return prefs.getBoolean("automatic", true);
  }

  void toggleAutomatic() {
    prefs.edit().putBoolean("automatic", !automatic()).apply();
  }

  void automaticCheck() {
    if (automatic() && System.currentTimeMillis() - prefs.getLong("checked", 0) > 86400000L)
      check(false);
  }

  void ui(Runnable action) {
    a.runOnUiThread(
        () -> {
          if (!closed && !a.isFinishing() && !a.isDestroyed()) action.run();
        });
  }

  void message(String title, String body) {
    new SheetDialog.Builder(a)
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton(a.tr("확인"), null)
        .show();
  }

  void check(boolean manual) {
    if (busy) {
      if (manual) a.toast(a.tr("업데이트를 처리하고 있습니다"));
      return;
    }
    busy = true;
    if (manual) a.toast(a.tr("새 버전을 확인하고 있습니다"));
    worker.execute(
        () -> {
          try {
            JSONObject best = null;
            long installed = code(a.getPackageManager().getPackageInfo(a.getPackageName(), 0));
            // Android and macOS releases share a repository. Never compare their version strings.
            for (int page = 1; page <= 10; page++) {
              JSONArray releases =
                  new JSONArray(
                      read(
                          "https://api.github.com/repos/hun8902/CoolPlayer/releases?per_page=100&page="
                              + page,
                          4000000));
              for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                if (r.optBoolean("draft")
                    || r.optBoolean("prerelease")
                    || !r.optString("tag_name").startsWith("android-v")) continue;
                JSONArray assets = r.getJSONArray("assets");
                for (int j = 0; j < assets.length(); j++) {
                  JSONObject asset = assets.getJSONObject(j);
                  if (!asset.optString("name").equals("android-update.json")) continue;
                  String url = asset.getString("browser_download_url");
                  requireAsset(url);
                  JSONObject m = new JSONObject(read(url, 65536));
                  if (!a.getPackageName().equals(m.getString("packageName"))) continue;
                  requireAsset(m.getString("apkUrl"));
                  if (!m.getString("sha256").matches("[a-fA-F0-9]{64}")
                      || m.getLong("size") <= 0
                      || m.getLong("size") > 200000000) throw new IOException(a.tr("업데이트 정보가 올바르지 않습니다"));
                  if (m.getInt("minSdk") <= Build.VERSION.SDK_INT
                      && (best == null || m.getLong("versionCode") > best.getLong("versionCode")))
                    best = m;
                }
              }
              if (releases.length() < 100) break;
            }
            prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
            JSONObject found = best;
            ui(
                () -> {
                  busy = false;
                  if (found != null && found.optLong("versionCode") > installed) {
                    new SheetDialog.Builder(a)
                        .setTitle("Cool Player " + found.optString("versionName"))
                        .setMessage(
                            a.tr("현재 버전 ")
                                + version()
                                + "\n\n"
                                + found.optString("notes", a.tr("새 버전을 사용할 수 있습니다."))
                                + a.tr("\n\n다운로드 후 Android 설치 화면에서 업데이트를 확정합니다."))
                        .setPositiveButton(a.tr("다운로드"), (d, w) -> download(found))
                        .setNegativeButton(a.tr("나중에"), null)
                        .show();
                  } else if (manual)
                    message(
                        a.tr("업데이트 확인"),
                        found == null
                            ? a.tr("공개된 Android 업데이트 정보를 찾지 못했습니다. 잠시 후 다시 확인해 주세요.")
                            : a.tr("최신 버전을 사용하고 있습니다.\nCool Player ") + version());
                });
          } catch (Exception e) {
            ui(
                () -> {
                  busy = false;
                  if (manual)
                    message(
                        a.tr("업데이트를 확인하지 못했습니다"),
                        a.tr("네트워크 연결 또는 GitHub 응답을 확인한 뒤 다시 시도해 주세요.\n\n") + e.getMessage());
                });
          }
        });
  }

  static void requireAsset(String url) throws IOException {
    if (!url.startsWith(ROOT)
        || !url.substring(ROOT.length()).matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"))
      throw new IOException("공식 릴리스 주소가 아닙니다");
  }

  HttpURLConnection open(String address) throws IOException {
    URL url = new URL(address);
    for (int n = 0; n < 6; n++) {
      if (!url.getProtocol().equals("https")) throw new IOException(a.tr("HTTPS 연결이 필요합니다"));
      HttpURLConnection c = (HttpURLConnection) url.openConnection();
      c.setConnectTimeout(15000);
      c.setReadTimeout(30000);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent", "CoolPlayer-Android");
      int status = c.getResponseCode();
      if (status >= 300 && status < 400) {
        String location = c.getHeaderField("Location");
        c.disconnect();
        if (location == null) throw new IOException(a.tr("다운로드 주소가 없습니다"));
        url = new URL(url, location);
        continue;
      }
      if (status != 200) {
        c.disconnect();
        throw new IOException("GitHub HTTP " + status);
      }
      return c;
    }
    throw new IOException(a.tr("다운로드 연결을 완료하지 못했습니다"));
  }

  String read(String url, int limit) throws Exception {
    HttpURLConnection c = open(url);
    try (InputStream in = c.getInputStream()) {
      return new String(Library.readBytes(in, limit), java.nio.charset.StandardCharsets.UTF_8);
    } finally {
      c.disconnect();
    }
  }

  File apk() {
    return new File(new File(a.getCacheDir(), "updates"), "Cool-Player-Android.apk");
  }

  void download(JSONObject info) {
    if (busy) return;
    busy = true;
    TextView progress = a.text(a.tr("다운로드 준비 중…"), 16, a.ink);
    SheetDialog dialog =
        new SheetDialog.Builder(a)
            .setTitle(a.tr("업데이트 다운로드"))
            .setView(progress)
            .setNegativeButton(a.tr("닫기"), null)
            .show();
    worker.execute(
        () -> {
          File target = apk(), part = new File(target.getParentFile(), "update.part");
          try {
            target.getParentFile().mkdirs();
            String url = info.getString("apkUrl");
            requireAsset(url);
            HttpURLConnection c = open(url);
            long expected = info.getLong("size"), count = 0;
            int previous = -1;
            try (InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(part)) {
              byte[] buffer = new byte[32768];
              int n;
              while ((n = in.read(buffer)) != -1) {
                if (closed || Thread.currentThread().isInterrupted())
                  throw new IOException(a.tr("다운로드가 중단되었습니다"));
                count += n;
                if (count > expected) throw new IOException(a.tr("파일 크기가 일치하지 않습니다"));
                out.write(buffer, 0, n);
                int percent = (int) (count * 100 / expected);
                if (percent != previous) {
                  previous = percent;
                  ui(() -> progress.setText(a.tr("다운로드 중 · ") + percent + "%"));
                }
              }
              if (count != expected) throw new IOException(a.tr("파일 다운로드가 완료되지 않았습니다"));
            } finally {
              c.disconnect();
            }
            verify(part, info);
            if (!part.renameTo(target)) throw new IOException(a.tr("업데이트 파일을 저장하지 못했습니다"));
            prefs.edit().putString("pending", info.toString()).apply();
            ui(
                () -> {
                  busy = false;
                  dialog.dismiss();
                  pending = info;
                  offerInstall();
                });
          } catch (Exception e) {
            part.delete();
            ui(
                () -> {
                  busy = false;
                  dialog.dismiss();
                  message(a.tr("업데이트 다운로드 실패"), e.getMessage() + a.tr("\n\n설정에서 업데이트 확인을 눌러 다시 시도하세요."));
                });
          }
        });
  }

  void verify(File file, JSONObject info) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] b = new byte[32768];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
    if (!hex.toString().equalsIgnoreCase(info.getString("sha256")))
      throw new IOException(a.tr("다운로드 파일의 체크섬이 일치하지 않습니다"));
    int flags =
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
    PackageManager pm = a.getPackageManager();
    PackageInfo next = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags),
        current = pm.getPackageInfo(a.getPackageName(), flags);
    if (next == null
        || !a.getPackageName().equals(next.packageName)
        || code(next) != info.getLong("versionCode")
        || code(next) <= code(current)) throw new IOException(a.tr("설치할 앱의 패키지 또는 버전이 올바르지 않습니다"));
    Signature[] n =
        Build.VERSION.SDK_INT >= 28 ? next.signingInfo.getApkContentsSigners() : next.signatures;
    Signature[] c =
        Build.VERSION.SDK_INT >= 28
            ? current.signingInfo.getApkContentsSigners()
            : current.signatures;
    if (n == null
        || c == null
        || n.length == 0
        || !new HashSet<>(Arrays.asList(n)).equals(new HashSet<>(Arrays.asList(c))))
      throw new IOException(a.tr("기존 앱과 서명키가 일치하지 않습니다"));
  }

  void offerInstall() {
    new SheetDialog.Builder(a)
        .setTitle(a.tr("설치 준비 완료"))
        .setMessage(a.tr("새 버전을 설치해도 음악 보관함과 설정은 유지됩니다. Android 설치 화면에서 업데이트를 눌러 주세요."))
        .setPositiveButton(a.tr("설치"), (d, w) -> install())
        .setNegativeButton(a.tr("나중에"), null)
        .show();
  }

  void resume() {
    if (waitingForPermission) {
      waitingForPermission = false;
      if (a.getPackageManager().canRequestPackageInstalls()) offerInstall();
    }
  }

  void install() {
    if (!a.getPackageManager().canRequestPackageInstalls()) {
      new SheetDialog.Builder(a)
          .setTitle(a.tr("업데이트 설치 허용"))
          .setMessage(a.tr("Android 설정에서 Cool Player의 ‘이 출처 허용’을 켜면 다운로드한 업데이트를 설치할 수 있습니다."))
          .setPositiveButton(
              a.tr("설정 열기"),
              (d, w) -> {
                try {
                  waitingForPermission = true;
                  a.startActivity(
                      new Intent(
                          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                          Uri.parse("package:" + a.getPackageName())));
                } catch (Exception e) {
                  waitingForPermission = false;
                  message(a.tr("설정을 열지 못했습니다"), e.getMessage());
                }
              })
          .setNegativeButton(a.tr("취소"), null)
          .show();
      return;
    }
    if (busy) return;
    busy = true;
    worker.execute(
        () -> {
          try {
            JSONObject info =
                pending != null ? pending : new JSONObject(prefs.getString("pending", "{}"));
            verify(apk(), info);
            ui(
                () -> {
                  busy = false;
                  try {
                    Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".updates", apk());
                    a.startActivity(
                        new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                  } catch (Exception e) {
                    message(a.tr("설치 화면을 열지 못했습니다"), e.getMessage());
                  }
                });
          } catch (Exception e) {
            ui(
                () -> {
                  busy = false;
                  message(a.tr("설치할 수 없습니다"), e.getMessage());
                });
          }
        });
  }

  void close() {
    closed = true;
    worker.shutdownNow();
  }
}
