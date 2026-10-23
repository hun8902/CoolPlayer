package com.coolplayer.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  Library library;
  MediaController player;
  ListenableFuture<MediaController> connection;
  final ExecutorService io = Executors.newSingleThreadExecutor();
  final Handler handler = new Handler(Looper.getMainLooper());
  LinearLayout root, body, mini, lyricContent;
  FrameLayout screenHost;
  ArtworkBackdrop backdrop;
  boolean renderedNow = false, renderedLyrics = false;
  String renderedTab = "";
  boolean darkTheme;
  android.animation.AnimatorSet screenTransition;

  TextView miniTitle,
      miniArtist,
      miniPlay,
      nowTitle,
      nowArtist,
      elapsed,
      total,
      playButton,
      shuffleButton,
      repeatButton,
      lyricLabel;
  ImageView nowArt, miniArt;
  boolean showPhoneLyrics = false;
  boolean fullLyrics = false;
  long lyricTouchUntil = 0;
  androidx.window.java.layout.WindowInfoTrackerCallbackAdapter foldTracker;
  android.graphics.Rect foldBounds = new android.graphics.Rect();
  boolean horizontalFold = false;
  final androidx.core.util.Consumer<androidx.window.layout.WindowLayoutInfo> foldListener =
      info -> {
        android.graphics.Rect next = new android.graphics.Rect();
        boolean horizontal = false;
        for (androidx.window.layout.DisplayFeature f : info.getDisplayFeatures()) {
          if (f instanceof androidx.window.layout.FoldingFeature) {
            androidx.window.layout.FoldingFeature fold = (androidx.window.layout.FoldingFeature) f;
            if (fold.isSeparating()
                || fold.getOcclusionType()
                    == androidx.window.layout.FoldingFeature.OcclusionType.FULL) {
              next.set(fold.getBounds());
              horizontal =
                  fold.getOrientation()
                      == androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL;
              break;
            }
          }
        }
        if (!next.equals(foldBounds) || horizontal != horizontalFold) {
          foldBounds = next;
          horizontalFold = horizontal;
          if (!this.destroyed) render();
        }
      };

  boolean compactLandscapePlayer() {
    Configuration configuration = getResources().getConfiguration();
    return configuration.screenWidthDp > configuration.screenHeightDp
        && configuration.screenHeightDp < 480;
  }

  boolean expanded() {
    return getResources().getConfiguration().screenWidthDp >= 600;
  }

  SeekBar timeline;
  ScrollView lyricScroll;
  ListView list;
  TrackAdapter adapter;
  ArrayList<Library.Track> visible = new ArrayList<>();
  ArrayList<Lyrics.Line> lines = new ArrayList<>();
  ArrayList<TextView> lyricViews = new ArrayList<>();
  String artworkTrackId = "";
  String tab = "곡",
      query = "",
      albumFilter = "",
      playlistFilter = "",
      shownId = "",
      exportText = "",
      lyricTrackId = "";
  int bg, card, ink, muted, accent, activeLine = -1;
  boolean now = false, dragging = false, importing = false;
  int searchGeneration = 0;
  boolean destroyed = false;
  final Runnable ticker =
      new Runnable() {
        public void run() {
          if (!destroyed) {
            updatePlayback();
            handler.postDelayed(this, 200);
          }
        }
      };

  String appVersion() {
    try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
    catch (Exception e) { return "?"; }
  }

  @Override
  protected void onResume() {
    super.onResume();
    PlaylistWidget.updateAll(this);
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    library = new Library(this);
    if (b != null) {
      now = b.getBoolean("now");
      tab = b.getString("tab", "곡");
      playlistFilter = b.getString("playlistFilter", "");
      albumFilter = b.getString("albumFilter", "");
    }
    showPhoneLyrics = b != null && b.getBoolean("lyrics");
    colors();
    render();
    if (b == null && !getSharedPreferences("settings", 0).getBoolean("spotlightGuideSeen", false)) {
      getSharedPreferences("settings", 0).edit().putBoolean("spotlightGuideSeen", true).apply();
      showSpotlightTutorial();
    }
    foldTracker =
        new androidx.window.java.layout.WindowInfoTrackerCallbackAdapter(
            androidx.window.layout.WindowInfoTracker.getOrCreate(this));
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
    connection =
        new MediaController.Builder(
                this, new SessionToken(this, new ComponentName(this, PlaybackService.class)))
            .buildAsync();
    connection.addListener(
        () -> {
          try {
            player = connection.get();
            if (destroyed) {
              player.release();
              return;
            }
            player.addListener(
                new Player.Listener() {
                  @Override
                  public void onMediaItemTransition(MediaItem item, int reason) {
                    shownId = "";
                    if (now) render();
                    else {
                      updatePlayback();
                      if (adapter != null) adapter.notifyDataSetChanged();
                    }
                  }

                  @Override
                  public void onPlayerError(PlaybackException e) {
                    toast("이 음원을 재생하지 못했습니다. 파일 접근 권한 또는 지원 형식을 확인해 주세요.");
                  }

                  @Override
                  public void onEvents(Player source, Player.Events events) {
                    updatePlayback();
                  }

                  @Override
                  public void onPlaybackStateChanged(int state) {
                    updatePlayback();
                  }
                });
            render();
          } catch (Exception e) {
            toast("재생 서비스에 연결하지 못했습니다");
          }
        },
        handler::post);
    handler.post(ticker);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 90);
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (foldTracker != null)
      foldTracker.addWindowLayoutInfoListener(this, handler::post, foldListener);
  }

  @Override
  protected void onStop() {
    if (foldTracker != null) foldTracker.removeWindowLayoutInfoListener(foldListener);
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putBoolean("now", now);
    out.putString("tab", tab);
    out.putString("playlistFilter", playlistFilter);
    out.putString("albumFilter", albumFilter);
    out.putBoolean("lyrics", showPhoneLyrics);
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    colors();
    render();
    SheetDialog.resizeOpen(this);
  }

  @Override
  protected void onDestroy() {
    if (screenTransition != null) screenTransition.cancel();
    if (backdrop != null) backdrop.stop();
    destroyed = true;
    searchGeneration++;
    handler.removeCallbacksAndMessages(null);
    io.shutdownNow();
    if (connection != null) MediaController.releaseFuture(connection);
    super.onDestroy();
  }

  // API 33+ uses the registered OnBackInvokedCallback; this handles older devices.
  @android.annotation.SuppressLint("GestureBackNavigation")
  @Override
  public void onBackPressed() {
    navigateBack();
  }

  @Override public boolean dispatchTouchEvent(MotionEvent event) {
    KeyboardDismiss.outside(getWindow(), event);
    return super.dispatchTouchEvent(event);
  }

  void navigateBack() {
    if (now && fullLyrics) { fullLyrics = false; render(); }
    else if (now) {
      now = false;
      render();
    } else if (!albumFilter.isEmpty() || !playlistFilter.isEmpty()) {
      albumFilter = "";
      playlistFilter = "";
      render();
    } else finish();
  }

  int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }

  void colors() {
    String theme = getSharedPreferences("settings", 0).getString("theme", "system");
    boolean dark =
        theme.equals("dark")
            || theme.equals("system")
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
    if (now && (fullLyrics || compactLandscapePlayer())) dark = true;
    darkTheme = dark;
    bg = Color.parseColor(dark ? "#15181E" : "#FAFBFD");
    card = Color.parseColor(dark ? "#232831" : "#EEF2F7");
    ink = Color.parseColor(dark ? "#F2F4F8" : "#202633");
    muted = Color.parseColor(dark ? "#A3ADBD" : "#758094");
    accent = Color.parseColor(dark ? "#BDCAE9" : "#526FA7");
    setTheme(dark ? R.style.DarkTheme : R.style.AppTheme);
    if (Build.VERSION.SDK_INT < 35) {
      getWindow().setStatusBarColor(bg);
      getWindow().setNavigationBarColor(bg);
    }
    if (Build.VERSION.SDK_INT >= 30) {
      // Ensure PhoneWindow has created its DecorView before requesting its controller.
      getWindow().getDecorView();
      android.view.WindowInsetsController controller = getWindow().getInsetsController();
      if (controller != null) {
        int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(dark ? 0 : appearance, appearance);
      }
    } else getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                | (dark
                    ? 0
                    : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  TextView text(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setIncludeFontPadding(false);
    v.setText(tr(s));
    v.setTextSize(size);
    v.setTextColor(color);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  TextView button(String s, Runnable action) {
    TextView b = text(tr(s), 14, accent);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setIncludeFontPadding(true);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(20), dp(10), dp(20), dp(10));
    b.setMinHeight(dp(48));
    b.setBackground(new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(darkTheme ? 0x28ffffff : 0x185370a0),
        shape(darkTheme ? 0xff303139 : 0xffedf0f5, 24), shape(Color.WHITE, 24)));
    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
      android.animation.StateListAnimator states = new android.animation.StateListAnimator();
      android.animation.AnimatorSet pressed = new android.animation.AnimatorSet();
      pressed.playTogether(android.animation.ObjectAnimator.ofFloat(b, "scaleX", .97f),
          android.animation.ObjectAnimator.ofFloat(b, "scaleY", .97f));
      pressed.setDuration(100);
      android.animation.AnimatorSet released = new android.animation.AnimatorSet();
      released.playTogether(android.animation.ObjectAnimator.ofFloat(b, "scaleX", 1f),
          android.animation.ObjectAnimator.ofFloat(b, "scaleY", 1f));
      released.setDuration(220);
      released.setInterpolator(new android.view.animation.PathInterpolator(.22f, .75f, .18f, 1));
      states.addState(new int[] {android.R.attr.state_pressed, android.R.attr.state_enabled}, pressed);
      states.addState(new int[] {}, released);
      b.setStateListAnimator(states);
    }
    b.setClickable(true);
    b.setFocusable(true);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  void space(LinearLayout l, int h) {
    Space s = new Space(this);
    l.addView(s, new LinearLayout.LayoutParams(1, dp(h)));
  }

  void heading(LinearLayout l, String title, String sub) {
    TextView t = text(tr(title), 26, ink);
    t.setTypeface(null, Typeface.BOLD);
    l.addView(t);
    if (!sub.isEmpty()) {
      TextView st = text(tr(sub), 13, muted);
      st.setPadding(0, dp(5), 0, dp(12));
      l.addView(st);
    }
  }

  void toast(String s) {
    runOnUiThread(
        () -> {
          if (!destroyed) Toast.makeText(this, tr(s), Toast.LENGTH_LONG).show();
        });
  }

  final class IconControl extends TextView {
    String symbol = "play";
    int tint = ink, extent = 24;

    IconControl() {
      super(MainActivity.this);
      setGravity(Gravity.CENTER);
      setClickable(true);
      setFocusable(true);
      setMinimumWidth(dp(48));
      setMinimumHeight(dp(48));
    }

    @Override
    protected void onDraw(Canvas canvas) {
      PlayerGlyph g = new PlayerGlyph(symbol, tint, dp(extent));
      int size = dp(extent), x = (getWidth() - size) / 2, y = (getHeight() - size) / 2;
      g.setBounds(x, y, x + size, y + size);
      g.draw(canvas);
    }
  }

  void setIcon(TextView view, String name, int color, int size) {
    if (view instanceof IconControl) {
      IconControl icon = (IconControl) view;
      if (!icon.symbol.equals(name) || icon.tint != color || icon.extent != size) {
        icon.symbol = name;
        icon.tint = color;
        icon.extent = size;
        icon.postInvalidateOnAnimation();
      }
    }
  }

  TextView icon(String name, String description, Runnable action) {
    IconControl v = new IconControl();
    v.symbol = name;
    v.setContentDescription(description);
    v.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x227788AA), null, shape(Color.WHITE, 24)));
    v.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
    v.setOnClickListener(w -> {
      w.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
      action.run();
    });
    return v;
  }

  android.graphics.drawable.GradientDrawable dockSurface(int radius) {
    // Opaque material prevents unblurred list text from showing through the controls.
    android.graphics.drawable.GradientDrawable surface =
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            darkTheme ? new int[] {0xff2b303a, 0xff252932}
                : new int[] {0xffffffff, 0xfff5f7fb});
    surface.setCornerRadius(dp(radius));
    surface.setStroke(dp(1), darkTheme ? 0xff3d4451 : 0xffe2e7ef);
    return surface;
  }

  LinearLayout navigation(boolean rail) {
    LinearLayout nav = rail ? column() : row();
    if (!rail) {
      nav.setPadding(dp(5), dp(4), dp(5), dp(4));
      nav.setBackground(dockSurface(30));
      nav.setElevation(0);
      nav.setClipToOutline(true);
    }
    String[] labels = {"곡", "앨범", "즐겨찾기", "목록", "설정"};
    String[] symbols = {"music", "album", "heart", "queue", "settings"};
    for (int i = 0; i < labels.length; i++) {
      String label = labels[i];
      TextView v =
          button(
              tr(label),
              () -> {
                tab = label;
                albumFilter = "";
                playlistFilter = "";
                query = "";
                render();
              });
      v.setTag("guide-tab-" + label);
      v.setTextSize(11);
      v.setPadding(dp(4), dp(5), dp(4), dp(4));
      PlayerGlyph glyph = new PlayerGlyph(symbols[i], tab.equals(label) ? accent : muted, dp(22));
      v.setCompoundDrawablesWithIntrinsicBounds(null, glyph, null, null);
      v.setCompoundDrawablePadding(dp(3));
      v.setBackground(tab.equals(label)
          ? shape(darkTheme ? 0xff353d4c : 0xffeaf0f8, 24)
          : new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
      v.setTextColor(tab.equals(label) ? accent : muted);
      nav.addView(
          v,
          rail
              ? new LinearLayout.LayoutParams(-1, dp(68))
              : new LinearLayout.LayoutParams(0, dp(54), 1));
      if (rail) space(nav, 6);
    }
    return nav;
  }

  void transitionScreen(LinearLayout previous, boolean wasNow, String wasTab, boolean wasLyrics) {
    if (previous == null) return;
    final LinearLayout incoming = root;
    boolean open = now && !wasNow,
        close = !now && wasNow,
        change = !tab.equals(wasTab) || showPhoneLyrics != wasLyrics || tab.equals("설정");
    if (!android.animation.ValueAnimator.areAnimatorsEnabled() || (!open && !close && !change)) {
      screenHost.removeView(previous);
      return;
    }
    previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    previous.setEnabled(false);
    // Keep the controls responsive during the transition.
    screenHost.setOnTouchListener(null);
    ArrayList<android.animation.Animator> animations = new ArrayList<>();
    float height = Math.max(dp(400), screenHost.getHeight());
    if (open) {
      incoming.setTranslationY(height * .78f);
      incoming.setAlpha(.5f);
      animations.add(
          android.animation.ObjectAnimator.ofFloat(incoming, "translationY", height * .78f, 0));
      animations.add(android.animation.ObjectAnimator.ofFloat(incoming, "alpha", .5f, 1));
      animations.add(android.animation.ObjectAnimator.ofFloat(previous, "scaleX", 1, .96f));
      animations.add(android.animation.ObjectAnimator.ofFloat(previous, "scaleY", 1, .96f));
    } else if (close) {
      screenHost.bringChildToFront(previous);
      incoming.setScaleX(.96f);
      incoming.setScaleY(.96f);
      animations.add(android.animation.ObjectAnimator.ofFloat(incoming, "scaleX", .96f, 1));
      animations.add(android.animation.ObjectAnimator.ofFloat(incoming, "scaleY", .96f, 1));
      animations.add(android.animation.ObjectAnimator.ofFloat(previous, "translationY", 0, height));
      animations.add(android.animation.ObjectAnimator.ofFloat(previous, "alpha", 1, 0));
    } else {
      incoming.setAlpha(0);
      incoming.setTranslationX(dp(16));
      animations.add(android.animation.ObjectAnimator.ofFloat(incoming, "translationX", dp(16), 0));
      animations.add(android.animation.ObjectAnimator.ofFloat(incoming, "alpha", 0, 1));
      animations.add(android.animation.ObjectAnimator.ofFloat(previous, "alpha", 1, 0));
    }
    android.animation.AnimatorSet animation = new android.animation.AnimatorSet();
    screenTransition = animation;
    animation.playTogether(animations);
    animation.setDuration(open || close ? 420 : 280);
    animation.setInterpolator(new android.view.animation.PathInterpolator(.22f, .75f, .18f, 1));
    animation.addListener(
        new android.animation.AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(android.animation.Animator a) {
            if (previous.getParent() == screenHost) screenHost.removeView(previous);
            incoming.setAlpha(1);
            incoming.setTranslationX(0);
            incoming.setTranslationY(0);
            incoming.setScaleX(1);
            incoming.setScaleY(1);
            screenHost.setOnTouchListener(null);
            if (screenTransition == a) screenTransition = null;
          }
        });
    animation.start();
  }

  void render() {
    if (destroyed) return;
    colors();
    LinearLayout previous = root;
    ArtworkBackdrop previousBackdrop = backdrop;
    boolean wasNow = renderedNow, wasLyrics = renderedLyrics;
    String wasTab = renderedTab;
    if (screenTransition != null) {
      screenTransition.cancel();
      screenTransition = null;
    }
    if (backdrop != null) {
      backdrop.stop();
      backdrop = null;
    }
    if (now) {
      SwipeDismissLayout playerRoot = new SwipeDismissLayout(this);
      playerRoot.setOrientation(LinearLayout.VERTICAL);
      playerRoot.close = () -> { if (fullLyrics) fullLyrics = false; else now = false; render(); };
      root = playerRoot;
    } else root = column();
    if (previous != null) root.setPadding(previous.getPaddingLeft(), previous.getPaddingTop(), previous.getPaddingRight(), previous.getPaddingBottom());
    else root.setPadding(dp(20), dp(32), dp(20), dp(20));
    root.setBackgroundColor(bg);
    if (now) {
      backdrop = new ArtworkBackdrop(darkTheme, wasNow ? previousBackdrop : null);
      backdrop.artworkEnabled = fullLyrics || compactLandscapePlayer();
      root.setBackground(backdrop);
    }
    if (screenHost == null) {
      screenHost =
          new FrameLayout(this) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
              return super.onInterceptTouchEvent(event);
            }
          };
      setContentView(screenHost);
    }
    screenHost.removeAllViews();
    if (previous != null) {
      previous.animate().cancel();
      previous.setTranslationY(0);
      previous.setTranslationX(0);
      previous.setScaleX(1);
      previous.setScaleY(1);
      previous.setAlpha(1);
      screenHost.addView(previous, new FrameLayout.LayoutParams(-1, -1));
    }
    screenHost.addView(root, new FrameLayout.LayoutParams(-1, -1));
    renderedNow = now;
    renderedTab = tab;
    renderedLyrics = showPhoneLyrics;
    LinearLayout target = root;
    root.post(() -> {
      if (root == target && !destroyed) transitionScreen(previous, wasNow, wasTab, wasLyrics);
    });
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    screenHost.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int left = 0, right = 0, top = 0, bottom = 0;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            left = bars.left;
            right = bars.right;
            top = bars.top;
            bottom = bars.bottom;
          } else {
            left = insets.getSystemWindowInsetLeft();
            right = insets.getSystemWindowInsetRight();
            top = insets.getSystemWindowInsetTop();
            bottom = insets.getSystemWindowInsetBottom();
          }
          root.setPadding(
              left + dp(expanded() ? 24 : 16),
              top + dp(8),
              right + dp(expanded() ? 24 : 16),
              bottom + dp(12));
          return Build.VERSION.SDK_INT >= 30
              ? WindowInsets.CONSUMED
              : insets.consumeSystemWindowInsets();
        });
    screenHost.requestApplyInsets();
    mini = null;
    miniArt = null;
    timeline = null;
    nowTitle = null;
    nowArt = null;
    lyricContent = null;
    lyricViews.clear();
    activeLine = -1;
    if (now) {
      renderNow();
      return;
    }
    LinearLayout header = row();
    ImageView brand = new ImageView(this);
    brand.setImageResource(R.drawable.brand);
    header.addView(brand, new LinearLayout.LayoutParams(dp(36), dp(36)));
    TextView name = text("Cool Player", 19, ink);
    name.setTypeface(null, Typeface.BOLD);
    header.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1));
    header.addView(icon("add", "음악 추가", this::importMenu));
    root.addView(header);
    space(root, 12);
    LinearLayout shell = row();
    shell.setGravity(Gravity.TOP);
    FrameLayout libraryViewport = new FrameLayout(this);
    root.addView(libraryViewport, new LinearLayout.LayoutParams(-1, 0, 1));
    libraryViewport.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    if (expanded()) {
      ScrollView navigationScroll = new ScrollView(this);
      navigationScroll.setVerticalScrollBarEnabled(false);
      navigationScroll.setClipToPadding(false);
      navigationScroll.addView(navigation(true), new ScrollView.LayoutParams(-1, -2));
      shell.addView(navigationScroll, new LinearLayout.LayoutParams(dp(76), -1));
      Space gap = new Space(this);
      shell.addView(gap, new LinearLayout.LayoutParams(dp(20), 1));
    }
    body = column();
    shell.addView(body, new LinearLayout.LayoutParams(0, -1, 1));
    if (tab.equals("설정")) renderSettings();
    else if (tab.equals("앨범") && albumFilter.isEmpty()) renderAlbums();
    else if (tab.equals("목록") && playlistFilter.isEmpty()) renderPlaylists();
    else renderLibrary();
    buildMini();
    {
      root.removeView(mini);
      LinearLayout dock = column();
      dock.setPadding(dp(4), dp(8), dp(4), dp(4));
      dock.addView(mini);
      if (!expanded()) {
        space(dock, 6);
        dock.addView(navigation(false));
      }
      libraryViewport.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
      // Reserve dock space for both navigation and library content, not only the song list.
      dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
        int bottom = b - t + dp(8);
        FrameLayout.LayoutParams shellParams = (FrameLayout.LayoutParams) shell.getLayoutParams();
        if (shellParams.bottomMargin != bottom) {
          shellParams.bottomMargin = bottom;
          shell.setLayoutParams(shellParams);
        }
      });
    }
    updatePlayback();
  }

  void renderLibrary() {
    String name =
        !albumFilter.isEmpty()
            ? albumFilter
            : !playlistFilter.isEmpty() ? playlistFilter : tab.equals("곡") ? "나의 음악" : tab;
    heading(body, name, library.tracks.size() + "곡");
    LinearLayout search = row();
    EditText input = new EditText(this);
    input.setSingleLine(true);
    KeyboardDismiss.done(input);
    input.setTextSize(15);
    input.setTextColor(ink);
    input.setHintTextColor(muted);
    input.setHint(tr("곡, 가수, 앨범 검색"));
    input.setText(query);
    input.setBackground(shape(card, 22));
    input.setPadding(dp(16), 0, dp(12), 0);
    search.addView(input, new LinearLayout.LayoutParams(0, dp(44), 1));
    Space searchGap = new Space(this);
    search.addView(searchGap, new LinearLayout.LayoutParams(dp(8), 1));
    search.addView(
        icon(
            "sort", "정렬",
            () ->
                new SheetDialog.Builder(this)
                    .setItems(
                        new String[] {"곡명순", "가수순", "최근 추가순"},
                        (d, w) -> {
                          getSharedPreferences("settings", 0).edit().putInt("sort", w).apply();
                          filter();
                        })
                    .show()));
    body.addView(search);
    space(body, 8);
    list = new ListView(this);
    list.setDivider(null);
    list.setClipToPadding(false);
    adapter = new TrackAdapter();
    list.setAdapter(adapter);
    body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView empty = text(tr("아직 음악이 없습니다.\n오른쪽 위 ＋에서 파일이나 폴더를 추가하세요."), 15, muted);
    empty.setGravity(Gravity.CENTER);
    body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    list.setEmptyView(empty);
    list.setOnItemClickListener((p, v, pos, id) -> play(visible, pos));
    list.setOnItemLongClickListener(
        (p, v, pos, id) -> {
          trackMenu(visible.get(pos));
          return true;
        });
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int b, int c) {
            query = s.toString();
            filter();
          }

          public void afterTextChanged(Editable e) {}
        });
    filter();
  }

  void filter() {
    visible.clear();
    String q = query.toLowerCase(Locale.ROOT);
    for (Library.Track t : library.tracks) {
      if (tab.equals("즐겨찾기") && !t.favorite) continue;
      if (!albumFilter.isEmpty() && !t.album.equals(albumFilter)) continue;
      if (!playlistFilter.isEmpty()
          && !library.playlists.getOrDefault(playlistFilter, new ArrayList<>()).contains(t.id))
        continue;
      if (!(t.title + " " + t.artist + " " + t.album).toLowerCase(Locale.ROOT).contains(q))
        continue;
      visible.add(t);
    }
    int sort = getSharedPreferences("settings", 0).getInt("sort", 0);
    if (sort == 0) visible.sort(Comparator.comparing(t -> t.title, String.CASE_INSENSITIVE_ORDER));
    else if (sort == 1)
      visible.sort(Comparator.comparing(t -> t.artist, String.CASE_INSENSITIVE_ORDER));
    else Collections.reverse(visible);
    adapter.notifyDataSetChanged();
  }

  final android.util.LruCache<String, Bitmap> artCache = new android.util.LruCache<>(48);

  void artwork(ImageView image, Library.Track t) {
    image.setImageResource(R.drawable.brand);
    image.setTag(t == null ? "" : t.art);
    if (t == null || t.art.isEmpty()) return;
    Bitmap cached = artCache.get(t.art);
    if (cached != null) {
      image.setImageBitmap(cached);
      if (image == nowArt && now && backdrop != null) backdrop.album(cached, darkTheme);
      return;
    }
    String path = t.art;
    io.execute(
        () -> {
          BitmapFactory.Options o = new BitmapFactory.Options();
          o.inJustDecodeBounds = true;
          BitmapFactory.decodeFile(path, o);
          int sample = 1;
          while (Math.max(o.outWidth, o.outHeight) / sample > 900) sample *= 2;
          o.inSampleSize = sample;
          o.inJustDecodeBounds = false;
          Bitmap b = BitmapFactory.decodeFile(path, o);
          if (b != null) {
            artCache.put(path, b);
            runOnUiThread(
                () -> {
                  if (!destroyed && path.equals(image.getTag())) {
                    image.setImageBitmap(b);
                    if (image == nowArt && now && backdrop != null) backdrop.album(b, darkTheme);
                  }
                });
          }
        });
  }

  class TrackAdapter extends BaseAdapter {
    public int getCount() {
      return visible.size();
    }

    public Object getItem(int i) {
      return visible.get(i);
    }

    public long getItemId(int i) {
      return i;
    }

    public View getView(int pos, View old, android.view.ViewGroup parent) {
      Library.Track t = visible.get(pos);
      LinearLayout row = row();
      row.setPadding(dp(8), dp(10), dp(2), dp(10));
      row.setClickable(true);
      row.setOnClickListener(
          v -> {
            ArrayList<Library.Track> queue = new ArrayList<>(visible);
            int index = queue.indexOf(t);
            if (index >= 0) play(queue, index);
          });
      row.setOnLongClickListener(
          v -> {
            trackMenu(t);
            return true;
          });
      boolean selected = current() != null && current().id.equals(t.id);
      row.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(0x337D98C4),
              shape(selected ? card : Color.TRANSPARENT, 16),
              null));
      ImageView art = new ImageView(MainActivity.this);
      art.setScaleType(ImageView.ScaleType.CENTER_CROP);
      art.setBackground(shape(card, 10));
      art.setClipToOutline(true);
      artwork(art, t);
      row.addView(art, new LinearLayout.LayoutParams(dp(52), dp(52)));
      LinearLayout labels = column();
      labels.setPadding(dp(12), 0, dp(4), 0);
      TextView title = text(t.title, 15, selected ? accent : ink);
      title.setMaxLines(1);
      title.setEllipsize(TextUtils.TruncateAt.END);
      labels.addView(title);
      TextView detail = text(t.artist + " · " + t.format, 12, muted);
      detail.setMaxLines(1);
      detail.setEllipsize(TextUtils.TruncateAt.END);
      detail.setPadding(0, dp(5), 0, 0);
      labels.addView(detail);
      row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      TextView more = icon("more", "곡 메뉴", () -> trackMenu(t));
      more.setBackgroundColor(Color.TRANSPARENT);
      more.setContentDescription(t.title + " 메뉴");
      row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
      return row;
    }
  }

  void renderAlbums() {
    TreeMap<String, ArrayList<Library.Track>> albums = new TreeMap<>();
    for (Library.Track t : library.tracks)
      albums.computeIfAbsent(t.album, k -> new ArrayList<>()).add(t);
    heading(body, "앨범", albums.size() + "개의 앨범");
    ScrollView scroll = new ScrollView(this);
    LinearLayout grid = column();
    scroll.addView(grid);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    int columns =
        expanded()
            ? Math.max(
                2, Math.min(4, (getResources().getConfiguration().screenWidthDp - 140) / 170))
            : 2;
    LinearLayout line = null;
    int count = 0;
    for (String album : albums.keySet()) {
      if (count % columns == 0) {
        line = row();
        line.setGravity(Gravity.TOP);
        grid.addView(line);
      }
      LinearLayout tile = column();
      tile.setPadding(dp(6), dp(8), dp(6), dp(16));
      line.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
      ImageView cover = new ImageView(this);
      cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
      cover.setBackground(shape(card, 16));
      cover.setClipToOutline(true);
      tile.addView(cover, new LinearLayout.LayoutParams(-1, dp(140)));
      tile.addOnLayoutChangeListener(
          (v, l, t, r, b, ol, ot, or, ob) -> {
            int size = Math.max(1, r - l - dp(12));
            if (cover.getLayoutParams().height != size) {
              cover.getLayoutParams().height = size;
              cover.requestLayout();
            }
          });
      artwork(cover, albums.get(album).get(0));
      TextView label = text(album, 14, ink);
      label.setPadding(0, dp(10), 0, 0);
      label.setMaxLines(2);
      label.setEllipsize(TextUtils.TruncateAt.END);
      tile.addView(label);
      TextView subtitle = text(albums.get(album).size() + "곡", 12, muted);
      subtitle.setPadding(0, dp(4), 0, 0);
      tile.addView(subtitle);
      tile.setOnClickListener(
          v -> {
            albumFilter = album;
            render();
          });
      count++;
    }
    if (line != null)
      while (count++ % columns != 0)
        line.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
    if (albums.isEmpty()) grid.addView(text(tr("음악 폴더를 추가하면 앨범이 여기에 모입니다."), 15, muted));
  }

  void renderPlaylists() {
    heading(body, "재생목록", "듣고 싶은 순서대로");
    body.addView(
        button(
            "＋ 새 재생목록",
            () ->
                prompt(
                    "재생목록 이름",
                    "",
                    name -> {
                      if (name.trim().isEmpty() || library.playlists.containsKey(name)) return;
                      library.playlists.put(name, new ArrayList<>());
                      library.save();
                      render();
                    })));
    space(body, 12);
    ScrollView s = new ScrollView(this);
    LinearLayout items = column();
    s.addView(items);
    body.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
    if (library.playlists.isEmpty()) {
      TextView empty = text(tr("새 재생목록을 만들고 좋아하는 곡을 담아보세요."), 14, muted);
      empty.setPadding(dp(8), dp(24), dp(8), dp(24));
      items.addView(empty);
    }
    for (String name : library.playlists.keySet()) {
      ArrayList<String> tracks = library.playlists.get(name);
      LinearLayout entry = row();
      entry.setPadding(dp(12), dp(12), dp(4), dp(12));
      ImageView cover = new ImageView(this);
      cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
      cover.setBackground(shape(card, 12));
      cover.setClipToOutline(true);
      artwork(cover, tracks.isEmpty() ? null : library.find(tracks.get(0)));
      entry.addView(cover, new LinearLayout.LayoutParams(dp(54), dp(54)));
      LinearLayout labels = column();
      labels.setPadding(dp(14), 0, dp(8), 0);
      TextView title = text(name, 16, ink);
      title.setTypeface(null, Typeface.BOLD);
      title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
      labels.addView(title);
      TextView count = text(tracks.size() + tr("곡"), 12, muted);
      count.setPadding(0, dp(5), 0, 0);
      labels.addView(count);
      entry.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      entry.setBackground(new android.graphics.drawable.RippleDrawable(
          android.content.res.ColorStateList.valueOf(0x227788aa), null, shape(android.graphics.Color.WHITE, 14)));
      entry.setTag("guide-playlist-" + name);
      entry.setOnClickListener(v -> { playlistFilter = name; render(); });
      Runnable menu = () -> new SheetDialog.Builder(this)
          .setTitle(name)
          .setItems(new String[]{"재생목록 열기", "재생목록만 삭제", "음악 파일도 함께 삭제"}, (dialog, which) -> {
            if (which == 0) { playlistFilter = name; render(); return; }
            if (which == 2) { deletePlaylistFiles(name); return; }
            new SheetDialog.Builder(this).setTitle(name)
                .setMessage("이 재생목록을 삭제할까요? 음원 파일은 보존됩니다.")
                .setPositiveButton("삭제", (d, w) -> {
                  library.playlists.remove(name); library.save(); render();
                }).setNegativeButton("취소", null).show();
          }).show();
      entry.addView(icon("more", "재생목록 메뉴", menu));
      entry.setOnLongClickListener(v -> { menu.run(); return true; });
      items.addView(entry);
      View divider = new View(this);
      divider.setBackgroundColor(darkTheme ? 0xff303640 : 0xffe3e7ed);
      LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
      line.leftMargin = dp(80);
      items.addView(divider, line);
    }
  }

  void buildMini() {
    mini = row();
    mini.setPadding(dp(12), dp(10), dp(8), dp(10));
    mini.setBackground(dockSurface(26));
    mini.setElevation(0);
    miniArt = new ImageView(this);
    miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
    miniArt.setBackground(shape(card, 10));
    miniArt.setClipToOutline(true);
    artwork(miniArt, current());
    mini.addView(miniArt, new LinearLayout.LayoutParams(dp(44), dp(44)));
    LinearLayout labels = column();
    labels.setPadding(dp(10), 0, dp(4), 0);
    miniTitle = text(tr("음악을 선택하세요"), 14, ink);
    miniTitle.setTypeface(null, Typeface.BOLD);
    miniTitle.setSingleLine();
    miniTitle.setEllipsize(TextUtils.TruncateAt.END);
    miniArtist = text("Cool Player", 12, muted);
    miniArtist.setSingleLine();
    miniArtist.setEllipsize(TextUtils.TruncateAt.END);
    miniTitle.setIncludeFontPadding(true);
    miniArtist.setIncludeFontPadding(true);
    miniArtist.setPadding(0, dp(2), 0, 0);
    labels.addView(miniTitle);
    labels.addView(miniArtist);
    labels.setOnClickListener(
        v -> {
          now = true;
          render();
        });
    miniArt.setOnClickListener(
        v -> {
          now = true;
          render();
        });
    mini.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    mini.addView(icon("previous", "이전 곡", () -> {
      if (player != null) player.seekToPreviousMediaItem();
    }));
    miniPlay = icon("play", "재생", this::toggle);
    mini.addView(miniPlay);
    mini.addView(
        icon(
            "next",
            "다음 곡",
            () -> {
              if (player != null) player.seekToNextMediaItem();
            }));
    root.addView(mini, new LinearLayout.LayoutParams(-1, -2));
    mini.setMinimumHeight(dp(64));
  }

  Library.Track current() {
    return player == null || player.getCurrentMediaItem() == null
        ? null
        : library.find(player.getCurrentMediaItem().mediaId);
  }

  void play(java.util.List<Library.Track> tracks, int index) {
    if (player == null) {
      toast("재생 서비스를 준비하고 있습니다");
      return;
    }
    if (index < 0 || index >= tracks.size()) return;
    ArrayList<MediaItem> items = new ArrayList<>();
    for (Library.Track t : new ArrayList<>(tracks)) items.add(t.media());
    player.setMediaItems(items, index, 0);
    player.prepare();
    player.play();
  }

  void toggle() {
    if (player == null) return;
    if (player.getPlayWhenReady()) player.pause();
    else {
      if (player.getMediaItemCount() == 0 && !library.tracks.isEmpty()) play(library.tracks, 0);
      else {
        player.prepare();
        player.play();
      }
    }
  }

  void confirmDeleteFile(Library.Track t) {
    String lyrics = t.lyricUri.isEmpty()
        ? "앱에 저장된 가사도 함께 삭제됩니다. 별도로 내보낸 가사 파일은 삭제하지 않습니다."
        : "연결된 가사 파일과 앱에 저장된 가사도 함께 삭제됩니다.";
    new SheetDialog.Builder(this).setTitle("원본 파일 삭제")
        .setMessage(t.title + "\n" + t.artist + "\n\n" + tr("음악 파일을 기기에서 삭제합니다. 앱에서 복구할 수 없습니다.")
            + "\n\n" + tr(lyrics))
        .setNegativeButton("취소", null)
        .setPositiveButton("삭제", (d, w) -> {
          // Stop only the selected source before deleting, including duplicate references.
          if (player != null) for (int i = player.getMediaItemCount() - 1; i >= 0; i--) {
            Library.Track queued = library.find(player.getMediaItemAt(i).mediaId);
            if (queued != null && queued.uri.equals(t.uri)) player.removeMediaItem(i);
          }
          io.execute(() -> {
            try {
              deleteDocument(t.uri);
            } catch (Exception e) {
              toast("파일을 삭제하지 못했습니다. 폴더를 다시 선택해 쓰기 권한을 허용해 주세요.");
              return;
            }
            boolean lyricFailed = false;
            if (!t.lyricUri.isEmpty() && !t.lyricUri.equals(t.uri)) {
              try { deleteDocument(t.lyricUri); } catch (Exception e) { lyricFailed = true; }
            }
            final boolean partial = lyricFailed;
            runOnUiThread(() -> {
              ArrayList<String> removed = new ArrayList<>();
              for (Library.Track item : library.tracks) if (item.uri.equals(t.uri)) removed.add(item.id);
              library.tracks.removeIf(item -> removed.contains(item.id));
              for (ArrayList<String> ids : library.playlists.values()) ids.removeAll(removed);
              library.save(); render();
              toast(partial ? "음악은 삭제했지만 연결된 가사 파일은 삭제하지 못했습니다." : "음악 파일과 저장된 가사를 삭제했습니다");
            });
          });
        }).show();
  }

  void deletePlaylistFiles(String name) {
    ArrayList<String> selected = new ArrayList<>(library.playlists.getOrDefault(name, new ArrayList<>()));
    new SheetDialog.Builder(this).setTitle("음악 파일도 함께 삭제")
        .setMessage(name + "\n\n재생목록의 음악 파일과 연결된 가사 파일을 기기에서 삭제합니다. 다른 재생목록에서도 제거되며 앱에서 복구할 수 없습니다. 계속할까요?")
        .setNegativeButton("취소", null)
        .setPositiveButton("파일까지 삭제", (d, w) -> {
          ArrayList<Library.Track> targets = new ArrayList<>();
          for (String id : selected) { Library.Track t = library.find(id); if (t != null) targets.add(t); }
          if (player != null) for (int i = player.getMediaItemCount() - 1; i >= 0; i--)
            if (selected.contains(player.getMediaItemAt(i).mediaId)) player.removeMediaItem(i);
          io.execute(() -> {
            HashSet<String> deleted = new HashSet<>(); int failed = 0;
            for (Library.Track t : targets) {
              try { if (!deleted.contains(t.uri)) { deleteDocument(t.uri); deleted.add(t.uri); } }
              catch (Exception e) { failed++; continue; }
              try { if (!t.lyricUri.isEmpty() && !t.lyricUri.equals(t.uri)) deleteDocument(t.lyricUri); }
              catch (Exception e) { failed++; }
            }
            final int failures = failed;
            runOnUiThread(() -> {
              HashSet<String> ids = new HashSet<>();
              for (Library.Track t : library.tracks) if (deleted.contains(t.uri)) ids.add(t.id);
              library.tracks.removeIf(t -> ids.contains(t.id));
              for (ArrayList<String> list : library.playlists.values()) list.removeAll(ids);
              if (failures == 0) library.playlists.remove(name);
              if (name.equals(playlistFilter) && !library.playlists.containsKey(name)) playlistFilter = "";
              library.save(); render();
              toast(failures == 0 ? "재생목록과 음악 파일을 삭제했습니다" : "일부 파일을 삭제하지 못했습니다. 재생목록을 유지합니다.");
            });
          });
        }).show();
  }

  void deleteDocument(String location) throws Exception {
    Uri uri = Uri.parse(location);
    if ("file".equals(uri.getScheme())) {
      if (!new File(uri.getPath()).delete()) throw new IOException("Delete failed");
    } else if (!android.provider.DocumentsContract.isDocumentUri(this, uri)
        || !android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri)) {
      throw new IOException("Delete unsupported");
    }
  }

  void trackMenu(Library.Track t) {
    ArrayList<String> choices =
        new ArrayList<>(
            Arrays.asList(
                t.favorite ? "즐겨찾기 해제" : "즐겨찾기",
                "다음에 재생",
                "대기열에 추가",
                "재생목록에 추가",
                "가사 자동 가져오기",
                "가사 검색",
                "음원 정보",
                "보관함에서 제거"));
    choices.add("앨범아트 가져오기 · 변경");
    if (!playlistFilter.isEmpty()) choices.add("이 재생목록에서 제거");
    choices.add("원본 파일 삭제");
    new SheetDialog.Builder(this)
        .setTitle(t.title)
        .setItems(
            choices.toArray(new String[0]),
            (d, w) -> {
              if (w == choices.size() - 1) { confirmDeleteFile(t); return; }
              switch (w) {
                case 0:
                  t.favorite = !t.favorite;
                  library.save();
                  render();
                  break;
                case 1:
                  if (player != null)
                    player.addMediaItem(
                        Math.min(player.getMediaItemCount(), player.getCurrentMediaItemIndex() + 1),
                        t.media());
                  break;
                case 2:
                  if (player != null) player.addMediaItem(t.media());
                  break;
                case 3:
                  addPlaylist(t);
                  break;
                case 4:
                  autoLyrics(t);
                  break;
                case 5:
                  searchLyrics(t);
                  break;
                case 6:
                  info(t);
                  break;
                case 7:
                  new SheetDialog.Builder(this)
                      .setMessage("보관함에서 제거할까요? 원본 파일은 삭제하지 않습니다.")
                      .setPositiveButton(
                          "제거",
                          (dd, ww) -> {
                            library.tracks.remove(t);
                            for (ArrayList<String> ids : library.playlists.values())
                              ids.remove(t.id);
                            if (player != null)
                              for (int i = player.getMediaItemCount() - 1; i >= 0; i--)
                                if (player.getMediaItemAt(i).mediaId.equals(t.id))
                                  player.removeMediaItem(i);
                            library.save();
                            render();
                          })
                      .setNegativeButton("취소", null)
                      .show();
                  break;
                case 8:
                  artworkMenu(t);
                  break;
                case 9:
                  library.playlists.get(playlistFilter).remove(t.id);
                  library.save();
                  render();
                  break;
              }
            })
        .show();
  }

  interface TextAction {
    void run(String value);
  }

  void prompt(String title, String initial, TextAction action) {
    EditText input = new EditText(this);
    input.setText(initial);
    input.setHint(tr(title));
    input.setMinHeight(dp(52));
    input.setSingleLine();
    KeyboardDismiss.done(input);
    new SheetDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setPositiveButton("저장", (d, w) -> action.run(input.getText().toString().trim()))
        .setNegativeButton("취소", null)
        .show();
  }

  void addPlaylist(Library.Track t) {
    ArrayList<String> names = new ArrayList<>(library.playlists.keySet());
    names.add("＋ 새 재생목록");
    new SheetDialog.Builder(this)
        .setTitle("재생목록에 추가")
        .setItems(
            names.toArray(new String[0]),
            (d, w) -> {
              if (w == names.size() - 1)
                prompt(
                    "재생목록 이름",
                    "",
                    name -> {
                      if (name.trim().isEmpty()) return;
                      ArrayList<String> ids =
                          library.playlists.computeIfAbsent(name, k -> new ArrayList<>());
                      if (!ids.contains(t.id)) ids.add(t.id);
                      library.save();
                      toast("추가했습니다");
                    });
              else {
                ArrayList<String> ids = library.playlists.get(names.get(w));
                if (!ids.contains(t.id)) ids.add(t.id);
                library.save();
                toast("추가했습니다");
              }
            })
        .show();
  }

  void info(Library.Track t) {
    new SheetDialog.Builder(this)
        .setTitle(t.title)
        .setMessage(
            t.artist
                + "\n"
                + t.album
                + "\n\n"
                + t.format
                + " · "
                + Library.time(t.duration)
                + (t.sampleRate > 0 ? "\n" + t.sampleRate + " Hz" : "")
                + (t.bitDepth > 0 ? " · " + t.bitDepth + " bit" : "")
                + "\n\n"
                + t.uri)
        .setPositiveButton("닫기", null)
        .show();
  }

  void renderFullLyrics(Library.Track t) {
    LinearLayout header = row();
    header.addView(icon("back", "플레이어로 돌아가기", () -> { fullLyrics = false; render(); }));
    LinearLayout names = column();
    nowTitle = text(t == null ? "" : t.title, 18, ink);
    nowTitle.setTypeface(null, Typeface.BOLD); nowTitle.setGravity(Gravity.CENTER);
    nowArtist = text(t == null ? "" : t.artist, 14, muted); nowArtist.setGravity(Gravity.CENTER);
    names.addView(nowTitle); names.addView(nowArtist);
    header.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(icon("more", "가사", () -> { if (current() != null) lyricsMenu(current()); }));
    root.addView(header);
    space(root, 16);
    LinearLayout lyrics = column();
    root.addView(lyrics, new LinearLayout.LayoutParams(-1, 0, 1));
    buildLyricsPanel(lyrics, t);
    LinearLayout transport = column();
    buildTransport(transport, t);
    // Full view keeps the timeline and three transport buttons only.
    LinearLayout transportPanel = (LinearLayout) transport.getChildAt(0);
    // buildTransport adds a panel to its parent; metadata belongs to that panel.
    if (transportPanel.getChildCount() >= 2) {
      transportPanel.removeViewAt(0);
      transportPanel.removeViewAt(0);
    }
    View last = transportPanel.getChildAt(transportPanel.getChildCount() - 1);
    if (last instanceof LinearLayout) {
      LinearLayout controls = (LinearLayout) last;
      controls.removeView(shuffleButton); controls.removeView(repeatButton);
    }
    root.addView(transport, new LinearLayout.LayoutParams(-1, -2));
    for (TextView line : lyricViews) { line.setTextSize(Math.max(24, lyricFontSize())); line.setTypeface(null, Typeface.BOLD); }
  }

  void renderNow() {
    Library.Track t = current();
    if (t != null && !t.art.isEmpty() && backdrop != null) {
      Bitmap cached = artCache.get(t.art);
      if (cached != null) backdrop.album(cached, darkTheme);
      else {
        ArtworkBackdrop destination = backdrop;
        String path = t.art;
        io.execute(
            () -> {
              BitmapFactory.Options options = new BitmapFactory.Options();
              options.inSampleSize = destination.artworkEnabled ? 2 : 16;
              Bitmap image = BitmapFactory.decodeFile(path, options);
              if (image != null)
                runOnUiThread(
                    () -> {
                      if (!destroyed && destination == backdrop)
                        destination.album(image, darkTheme);
                      image.recycle();
                    });
            });
      }
    }
    if (fullLyrics) { renderFullLyrics(t); return; }
    boolean wide = expanded();
    LinearLayout top = row();
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.CENTER_VERTICAL);
    top.addView(
        icon(
            "back",
            "보관함으로",
            () -> {
              now = false;
              render();
            }));
    TextView title = text(tr("지금 재생 중"), 13, muted);
    title.setGravity(Gravity.CENTER);
    top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
    top.addView(icon("more", "가사 검색 · 편집 · 내보내기", () -> {
      if (current() != null) lyricsMenu(current());
    }));
    root.addView(top);
    space(root, 8);
    LinearLayout stage = wide ? row() : column();
    stage.setGravity(Gravity.TOP);
    root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout left = column();
    stage.addView(
        left,
        wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, -1));
    final LinearLayout contentPane;
    if (wide) {
      Space gap = new Space(this);
      stage.addView(gap, new LinearLayout.LayoutParams(dp(32), 1));
      LinearLayout right = column();
      contentPane = right;
      stage.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
      buildArtwork(left, t);
      buildTransport(left, t);
      if (showPhoneLyrics) buildLyricsPanel(right, t);
      else buildInlineQueue(right);
      stage.addOnLayoutChangeListener(
          (v, l, tp, r, b, ol, ot, or, ob) -> {
            if (foldBounds.width() == 0 && foldBounds.height() == 0) return;
            int[] location = new int[2];
            stage.getLocationInWindow(location);
            if (horizontalFold) {
              stage.setOrientation(LinearLayout.VERTICAL);
              int h = foldBounds.top - location[1] - dp(8);
              if (h > dp(180) && stage.getHeight() - h - foldBounds.height() > dp(160)) {
                setLayout(left, -1, h, 0);
                setLayout(gap, 1, Math.max(dp(16), foldBounds.height() + dp(16)), 0);
                setLayout(right, -1, 0, 1);
              }
            } else {
              int w = foldBounds.left - location[0] - dp(12);
              if (w > dp(180) && stage.getWidth() - w - foldBounds.width() > dp(180)) {
                setLayout(left, w, -1, 0);
                setLayout(gap, Math.max(dp(24), foldBounds.width() + dp(24)), 1, 0);
                setLayout(right, 0, -1, 1);
              }
            }
          });
    } else {
      contentPane = column();
      left.addView(contentPane, new LinearLayout.LayoutParams(-1, 0, 1));
      if (showPhoneLyrics) buildLyricsPanel(contentPane, t);
      else buildArtwork(contentPane, t);
      buildTransport(left, t);
    }
    LinearLayout tools = row();
    tools.setGravity(Gravity.CENTER);
    TextView lyricsToggle = icon("lyrics", tr("가사"), () -> {});
    lyricsToggle.setOnClickListener(v -> {
      android.transition.TransitionManager.endTransitions(contentPane);
      if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
        android.transition.TransitionSet transition = new android.transition.TransitionSet();
        transition.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        transition.addTransition(new android.transition.Fade());
        transition.addTransition(new android.transition.ChangeBounds());
        transition.setDuration(360);
        transition.setInterpolator(new android.view.animation.PathInterpolator(.22f, .75f, .18f, 1));
        android.transition.TransitionManager.beginDelayedTransition(contentPane, transition);
      }
      showPhoneLyrics = !showPhoneLyrics;
      renderedLyrics = showPhoneLyrics;
      contentPane.removeAllViews();
      lyricContent = null;
      lyricViews.clear();
      if (showPhoneLyrics) buildLyricsPanel(contentPane, current());
      else if (wide) buildInlineQueue(contentPane);
      else buildArtwork(contentPane, current());
      setIcon(lyricsToggle, "lyrics", showPhoneLyrics ? accent : muted, 22);
      lyricsToggle.setSelected(showPhoneLyrics);
      updatePlayback();
    });
    setIcon(lyricsToggle, "lyrics", showPhoneLyrics ? accent : muted, 22);
    tools.addView(lyricsToggle, new LinearLayout.LayoutParams(0, dp(48), 1));
    tools.addView(icon("more", tr("가사"), () -> { if (current() != null) lyricsMenu(current()); }), new LinearLayout.LayoutParams(0, dp(48), 1));
    tools.addView(icon("queue", tr("재생 대기열"), this::showQueue), new LinearLayout.LayoutParams(0, dp(48), 1));
    root.addView(tools);
    updatePlayback();
  }

  void setLayout(View view, int w, int h, float weight) {
    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
    if (p.width != w || p.height != h || p.weight != weight) {
      p.width = w;
      p.height = h;
      p.weight = weight;
      view.setLayoutParams(p);
    }
  }

  void buildArtwork(LinearLayout parent, Library.Track t) {
    parent.setTag(null);
    if (compactLandscapePlayer()) {
      nowArt = null;
      parent.setGravity(Gravity.CENTER_VERTICAL);
      return;
    }
    FrameLayout frame = new FrameLayout(this);
    parent.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
    nowArt = new ImageView(this);
    nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
    nowArt.setBackground(shape(card, 22));
    nowArt.setClipToOutline(true);
    nowArt.setElevation(dp(4));
    FrameLayout.LayoutParams initial = new FrameLayout.LayoutParams(1, 1, Gravity.CENTER);
    frame.addView(nowArt, initial);
    ImageView art = nowArt;
    frame.addOnLayoutChangeListener(
        (v, l, top, r, b, ol, ot, or, ob) -> {
          int size = Math.max(1, Math.min(dp(420), Math.min(r - l - dp(24), b - top - dp(24))));
          FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) art.getLayoutParams();
          if (p.width != size) {
            p.width = size;
            p.height = size;
            art.setLayoutParams(p);
          }
        });
    nowArt.setContentDescription(tr("앨범아트 · 좌우로 밀어 곡 변경"));
    final float[] origin = new float[2];
    nowArt.setOnTouchListener((v, event) -> {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
        origin[0] = event.getX(); origin[1] = event.getY();
        return true;
      }
      if (event.getActionMasked() == MotionEvent.ACTION_UP) {
        float dx = event.getX() - origin[0], dy = event.getY() - origin[1];
        if (Math.abs(dx) > dp(64) && Math.abs(dx) > Math.abs(dy) * 1.5f && player != null) {
          if (dx < 0) player.seekToNextMediaItem(); else player.seekToPreviousMediaItem();
        } else v.performClick();
        return true;
      }
      return true;
    });
    artwork(nowArt, t);
  }

  void buildTransport(LinearLayout parent, Library.Track t) {
    LinearLayout panel = column();
    panel.setPadding(dp(4), dp(8), dp(4), dp(12));
    parent.addView(panel, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout info = row();
    LinearLayout names = column();
    nowTitle = text(t == null ? "음악을 선택하세요" : t.title, 21, ink);
    nowTitle.setTypeface(null, Typeface.BOLD);
    nowTitle.setSingleLine();
    nowTitle.setEllipsize(TextUtils.TruncateAt.END);
    names.addView(nowTitle);
    nowArtist = text(t == null ? "보관함에서 곡을 선택하세요" : t.artist, 14, muted);
    nowArtist.setSingleLine();
    nowArtist.setEllipsize(TextUtils.TruncateAt.END);
    nowArtist.setPadding(0, dp(6), 0, 0);
    names.addView(nowArtist);
    info.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
    TextView favorite =
        icon(
            "heart",
            "즐겨찾기",
            () -> {
              Library.Track track = current();
              if (track != null) {
                track.favorite = !track.favorite;
                library.save();
                render();
              }
            });
    if (t != null && t.favorite) setIcon(favorite, "heart_filled", accent, 24);
    info.addView(favorite);
    info.addView(
        icon(
            "more",
            "곡 메뉴",
            () -> {
              if (t != null) trackMenu(t);
            }));
    panel.addView(info);
    TextView format =
        text(
            t == null
                ? ""
                : t.format
                    + (t.sampleRate > 0
                        ? "  ·  " + String.format(Locale.ROOT, "%.1f kHz", t.sampleRate / 1000.0)
                        : "")
                    + (t.bitDepth > 0 ? "  ·  " + t.bitDepth + " bit" : ""),
            11,
            muted);
    format.setPadding(0, dp(8), 0, 0);
    panel.addView(format);
    timeline = new SeekBar(this);
    timeline.setMax(1000);
    timeline.setPadding(0, 0, 0, 0);
    timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
    timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
    panel.addView(timeline, new LinearLayout.LayoutParams(-1, dp(36)));
    timeline.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {
            dragging = true;
          }

          public void onStopTrackingTouch(SeekBar s) {
            if (player != null && player.getDuration() > 0)
              player.seekTo(player.getDuration() * s.getProgress() / 1000);
            dragging = false;
          }

          public void onProgressChanged(SeekBar s, int p, boolean user) {
            if (user && player != null && player.getDuration() > 0)
              elapsed.setText(Library.time(player.getDuration() * p / 1000));
          }
        });
    LinearLayout times = row();
    elapsed = text("0:00", 11, muted);
    total = text("0:00", 11, muted);
    times.addView(elapsed, new LinearLayout.LayoutParams(0, dp(18), 1));
    times.addView(total);
    panel.addView(times);
    space(panel, 6);
    LinearLayout controls = row();
    controls.setGravity(Gravity.CENTER);
    shuffleButton =
        icon(
            "shuffle",
            "셔플",
            () -> {
              if (player != null) {
                player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
                updatePlayback();

              }
            });
    controls.addView(shuffleButton, new LinearLayout.LayoutParams(0, dp(56), 1));
    controls.addView(
        icon(
            "previous",
            "이전 곡",
            () -> {
              if (player != null) player.seekToPrevious();
            }),
        new LinearLayout.LayoutParams(0, dp(56), 1));
    playButton = icon("play", "재생 또는 일시정지", this::toggle);
    playButton.setBackground(shape(accent, 32));
    controls.addView(playButton, new LinearLayout.LayoutParams(dp(64), dp(64)));
    controls.addView(
        icon(
            "next",
            "다음 곡",
            () -> {
              if (player != null) player.seekToNextMediaItem();
            }),
        new LinearLayout.LayoutParams(0, dp(56), 1));
    repeatButton =
        icon(
            "repeat",
            "반복",
            () -> {
              if (player != null) {
                player.setRepeatMode(player.getRepeatMode() == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL : player.getRepeatMode() == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                updatePlayback();
              }
            });
    controls.addView(repeatButton, new LinearLayout.LayoutParams(0, dp(56), 1));
    panel.addView(controls, new LinearLayout.LayoutParams(-1, dp(72)));
  }

  void buildLyricsPanel(LinearLayout pane, Library.Track t) {
    pane.setTag("no-player-dismiss");
    pane.setPadding(dp(8), 0, dp(8), 0);
    lyricLabel = text(tr("가사"), 16, muted);
    lyricScroll = new ScrollView(this);
    lyricScroll.setVerticalScrollBarEnabled(false);
    lyricScroll.setFillViewport(true);
    lyricScroll.setOnTouchListener(
        (v, event) -> {
          lyricTouchUntil = System.currentTimeMillis() + 4000;
          return false;
        });
    lyricContent = column();
    lyricContent.setPadding(dp(8), dp(44), dp(8), dp(100));
    lyricScroll.addView(lyricContent);
    pane.addView(lyricScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    loadLyrics(t);
  }

  void showLyricTiming() {
              LinearLayout options = row();
              options.addView(
                  button(tr("− 0.5초"), () -> adjustOffset(-500)),
                  new LinearLayout.LayoutParams(0, dp(52), 1));
              options.addView(
                  button(tr("＋ 0.5초"), () -> adjustOffset(500)),
                  new LinearLayout.LayoutParams(0, dp(52), 1));
              new SheetDialog.Builder(this)
                  .setTitle("가사 타이밍")
                  .setView(options)
                  .setNeutralButton(
                      "초기화",
                      (d, w) -> {
                        Library.Track tr = current();
                        if (tr != null) {
                          getSharedPreferences("settings", 0)
                              .edit()
                              .remove("offset:" + tr.id)
                              .apply();
                          activeLine = -1;
                        }
                      })
                  .setPositiveButton("닫기", null)
                  .show();
  }

  void adjustOffset(int delta) {
    Library.Track t = current();
    if (t == null) return;
    SharedPreferences p = getSharedPreferences("settings", 0);
    long value = p.getLong("offset:" + t.id, 0) + delta;
    p.edit().putLong("offset:" + t.id, value).apply();
    activeLine = -1;
    toast(String.format(Locale.ROOT, "가사 표시 지연 %+.1f초", value / 1000.0));
  }

  void loadLyrics(Library.Track t) {
    if (lyricContent == null) return;
    lyricContent.removeAllViews();
    lyricViews.clear();
    lines = Lyrics.parse(t == null ? "" : t.lyrics);
    activeLine = -1;
    shownId = t == null ? "" : t.id;
    lyricContent.setGravity(Gravity.TOP);
    if (t == null || t.lyrics.trim().isEmpty()) {
      lyricLabel.setText(tr("가사"));
      lyricContent.setGravity(Gravity.CENTER);
      TextView empty = text(tr("가사와 함께 들어보세요"), 18, muted);
      empty.setGravity(Gravity.CENTER);
      lyricContent.addView(empty);
      space(lyricContent, 20);
      if (t != null) {
        TextView fetch = button("가사 가져오기", () -> autoLyrics(t));
        fetch.setTag("guide-fetch-lyrics");
        PlayerGlyph glyph = new PlayerGlyph("download", accent, dp(18));
        glyph.setBounds(0, 0, dp(18), dp(18));
        fetch.setCompoundDrawablesRelative(glyph, null, null, null);
        fetch.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-2, -2);
        placement.gravity = Gravity.CENTER_HORIZONTAL;
        lyricContent.addView(fetch, placement);
      }
      return;
    }
    if (lines.isEmpty()) {
      TextView plain = text(t.lyrics, lyricFontSize(), ink);
      plain.setLineSpacing(dp(8), 1.1f);
      lyricContent.addView(plain);
      lyricLabel.setText(tr("가사 · 시간 정보 없음"));
      return;
    }
    lyricLabel.setText(tr("실시간 가사"));
    for (Lyrics.Line line : lines) {
      TextView v = text(line.text.trim().isEmpty() ? "♪" : line.text, lyricFontSize(), muted);
      v.setTypeface(null, Typeface.BOLD);
      v.setPadding(0, dp(12), 0, dp(12));
      v.setOnClickListener(
          view -> {
            if (player != null)
              player.seekTo(
                  Math.max(
                      0,
                      line.time
                          + getSharedPreferences("settings", 0).getLong("offset:" + t.id, 0)));
          });
      lyricViews.add(v);
      lyricContent.addView(v);
    }
  }

  void updatePlayback() {
    if (player == null) return;
    Library.Track t = current();
    if (now && fullLyrics && t != null && !t.id.equals(shownId)) { render(); return; }
    String title = t == null ? "음악을 선택하세요" : t.title, artist = t == null ? "Cool Player" : t.artist;
    boolean playing = player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
    if (mini != null) {
      miniTitle.setText(title);
      miniArtist.setText(artist);
      setIcon(miniPlay, playing ? "pause" : "play", ink, 26);
      miniPlay.setContentDescription(playing ? "일시정지" : "재생");
      miniPlay.setSelected(playing);
      if (miniArt != null && t != null && !t.art.equals(miniArt.getTag())) artwork(miniArt, t);
    }
    if (!now || timeline == null) return;
    if (t != null && !t.id.equals(shownId)) loadLyrics(t);
    setIcon(playButton, playing ? "pause" : "play", bg, 30);
    setIcon(shuffleButton, player.getShuffleModeEnabled() ? "shuffle_on" : "shuffle", player.getShuffleModeEnabled() ? ink : muted, 26);
    shuffleButton.setAlpha(player.getShuffleModeEnabled() ? 1f : .48f);
    shuffleButton.setSelected(player.getShuffleModeEnabled());
    setIcon(
        repeatButton,
        player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "repeat_one" : player.getRepeatMode() == Player.REPEAT_MODE_ALL ? "repeat_all" : "repeat",
        player.getRepeatMode() == Player.REPEAT_MODE_OFF ? muted : ink,
        26);
    repeatButton.setAlpha(player.getRepeatMode() == Player.REPEAT_MODE_OFF ? .48f : 1f);
    shuffleButton.setContentDescription(player.getShuffleModeEnabled() ? "셔플 켜짐" : "셔플 꺼짐");
    repeatButton.setContentDescription(
        player.getRepeatMode() == Player.REPEAT_MODE_ONE
            ? "한 곡 반복"
            : player.getRepeatMode() == Player.REPEAT_MODE_ALL ? "전체 반복" : "반복 꺼짐");
    long pos = player.getCurrentPosition(), duration = player.getDuration();
    if (!dragging) {
      timeline.setProgress(duration > 0 ? (int) (pos * 1000 / duration) : 0);
      elapsed.setText(Library.time(pos));
    }
    total.setText(duration > 0 ? Library.time(duration) : "0:00");
    if (t == null || lyricContent == null || lines.isEmpty()) return;
    long at = pos - getSharedPreferences("settings", 0).getLong("offset:" + t.id, 0);
    int current = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).time <= at) current = i;
      else break;
    }
    if (current != activeLine) {
      activeLine = current;
      for (int i = 0; i < lyricViews.size(); i++) {
        TextView line = lyricViews.get(i);
        line.setTextColor(i == current ? ink : muted);
        line.animate().alpha(i == current ? 1f : .72f).setDuration(200).start();
      }
      if (current >= 0
          && getSharedPreferences("settings", 0).getBoolean("follow", true)
          && System.currentTimeMillis() > lyricTouchUntil) {
        TextView line = lyricViews.get(current);
        lyricScroll.post(
            () ->
                lyricScroll.smoothScrollTo(
                    0, Math.max(0, line.getTop() - lyricScroll.getHeight() / 3)));
      }
    }
  }

  void buildInlineQueue(LinearLayout parent) {
    TextView heading = text(tr("재생 대기열"), 16, ink);
    heading.setPadding(dp(8), dp(8), 0, dp(12));
    parent.addView(heading);
    ListView queue = new ListView(this);
    queue.setDivider(null);
    queue.setVerticalScrollBarEnabled(false);
    BaseAdapter items = new BaseAdapter() {
      public int getCount() { return player == null ? 0 : player.getMediaItemCount(); }
      public Object getItem(int p) { return player.getMediaItemAt(p); }
      public long getItemId(int p) { return p; }
      public View getView(int p, View old, ViewGroup container) {
        MediaItem item = player.getMediaItemAt(p);
        LinearLayout entry = row();
        entry.setPadding(dp(8), dp(8), dp(4), dp(8));
        ImageView art = new ImageView(MainActivity.this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(shape(card, 8)); art.setClipToOutline(true);
        artwork(art, library.find(item.mediaId));
        entry.addView(art, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout names = column(); names.setPadding(dp(12), 0, 0, 0);
        TextView title = text(String.valueOf(item.mediaMetadata.title), 15,
          p == player.getCurrentMediaItemIndex() ? accent : ink);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        TextView artist = text(String.valueOf(item.mediaMetadata.artist), 12, muted);
        artist.setSingleLine(); artist.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title); names.addView(artist);
        entry.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        entry.setOnClickListener(v -> { player.seekTo(p, 0); player.play(); });
        return entry;
      }
    };
    queue.setAdapter(items);
    parent.addView(queue, new LinearLayout.LayoutParams(-1, 0, 1));
    Player.Listener listener = new Player.Listener() {
      public void onEvents(Player source, Player.Events events) { items.notifyDataSetChanged(); }
    };
    queue.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
      public void onViewAttachedToWindow(View v) { if (player != null) player.addListener(listener); }
      public void onViewDetachedFromWindow(View v) { if (player != null) player.removeListener(listener); }
    });
    queue.setSelection(player == null ? 0 : Math.max(0, player.getCurrentMediaItemIndex()));
  }

  void showQueue() {
    if (player == null) return;
    LinearLayout panel = column();
    TextView summary = text(tr("다음 재생"), 13, muted);
    summary.setPadding(0, 0, 0, dp(12));
    panel.addView(summary);
    ListView queue = new ListView(this);
    queue.setDivider(null);
    queue.setVerticalScrollBarEnabled(false);
    panel.addView(
        queue,
        new LinearLayout.LayoutParams(
            -1, 0, 1));
    BaseAdapter queueAdapter =
        new BaseAdapter() {
          public int getCount() {
            return player.getMediaItemCount();
          }

          public Object getItem(int p) {
            return player.getMediaItemAt(p);
          }

          public long getItemId(int p) {
            return p;
          }

          public View getView(int position, View old, ViewGroup parent) {
            MediaItem item = player.getMediaItemAt(position);
            Library.Track track = library.find(item.mediaId);
            LinearLayout row = row();
            row.setPadding(dp(10), dp(10), dp(2), dp(10));
            if (position == player.getCurrentMediaItemIndex()) row.setBackground(shape(card, 16));
            ImageView cover = new ImageView(MainActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(shape(card, 10));
            cover.setClipToOutline(true);
            artwork(cover, track);
            row.addView(cover, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout labels = column();
            labels.setPadding(dp(12), 0, dp(4), 0);
            TextView title =
                text(
                    String.valueOf(item.mediaMetadata.title),
                    15,
                    position == player.getCurrentMediaItemIndex() ? accent : ink);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            TextView detail =
                text(
                    (position == player.getCurrentMediaItemIndex() ? "지금 재생 · " : "")
                        + String.valueOf(item.mediaMetadata.artist),
                    12,
                    muted);
            detail.setSingleLine();
            detail.setEllipsize(TextUtils.TruncateAt.END);
            detail.setPadding(0, dp(5), 0, 0);
            labels.addView(detail);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(
                v -> {
                  if (position < player.getMediaItemCount()) {
                    player.seekTo(position, 0);
                    player.play();
                  }
                });
            row.addView(
                icon(
                    "more",
                    "대기열 곡 메뉴",
                    () ->
                        new SheetDialog.Builder(MainActivity.this)
                            .setTitle(String.valueOf(item.mediaMetadata.title))
                            .setItems(
                                new String[] {"위로 이동", "아래로 이동", "대기열에서 제거"},
                                (d, w) -> {
                                  if (position >= player.getMediaItemCount()
                                      || !player
                                          .getMediaItemAt(position)
                                          .mediaId
                                          .equals(item.mediaId)) return;
                                  if (w == 0 && position > 0)
                                    player.moveMediaItem(position, position - 1);
                                  else if (w == 1 && position + 1 < player.getMediaItemCount())
                                    player.moveMediaItem(position, position + 1);
                                  else if (w == 2) player.removeMediaItem(position);
                                })
                            .show()));
            return row;
          }
        };
    queue.setAdapter(queueAdapter);
    TextView empty = text(tr("대기열이 비어 있습니다"), 16, muted);
    panel.addView(empty);
    queue.setEmptyView(empty);
    SheetDialog dialog =
        new SheetDialog.Builder(this)
            .setTitle("재생 대기열")
            .setFlexibleView(panel)
            .setNeutralButton(
                "대기열 비우기",
                (d, w) ->
                    new SheetDialog.Builder(this)
                        .setTitle("대기열을 비울까요?")
                        .setMessage("현재 재생도 멈춥니다. 보관함의 음원은 유지됩니다.")
                        .setPositiveButton("비우기", (dd, ww) -> player.clearMediaItems())
                        .setNegativeButton("취소", null)
                        .show())
            .create();
    Player.Listener listener =
        new Player.Listener() {
          @Override
          public void onEvents(Player p, Player.Events events) {
            queueAdapter.notifyDataSetChanged();
          }
        };
    player.addListener(listener);
    dialog.setOnDismissListener(
        d -> {
          if (player != null) player.removeListener(listener);
        });
    dialog.show();
    queue.setSelection(Math.max(0, player.getCurrentMediaItemIndex()));
  }

  void artworkMenu(Library.Track t) {
    LinearLayout preview = column();
    ImageView cover = new ImageView(this);
    cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
    artwork(cover, t);
    preview.addView(cover, new LinearLayout.LayoutParams(-1, dp(180)));
    TextView note = text(tr("보관함에 적용됩니다. 원본 음원 파일은 유지합니다."), 12, muted);
    note.setGravity(Gravity.CENTER);
    note.setPadding(0, dp(12), 0, dp(8));
    preview.addView(note);
    new SheetDialog.Builder(this)
        .setTitle("앨범아트")
        .setView(preview)
        .setItems(
            new String[] {"사진·이미지 파일에서 가져오기", "이미지 주소로 가져오기", "원본 음원의 앨범아트 다시 읽기"},
            (d, w) -> {
              if (w == 0) {
                artworkTrackId = t.id;
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    14);
              } else if (w == 1)
                prompt(
                    "앨범 이미지 주소 · HTTPS",
                    "",
                    url -> {
                      if (!url.startsWith("https://")) {
                        toast("HTTPS 이미지 주소를 입력하세요");
                        return;
                      }
                      fetchArtwork(t, url);
                    });
              else {
                toast("원본 앨범아트를 읽고 있습니다");
                io.execute(
                    () -> {
                      android.media.MediaMetadataRetriever reader =
                          new android.media.MediaMetadataRetriever();
                      try {
                        reader.setDataSource(this, Uri.parse(t.uri));
                        byte[] bytes = reader.getEmbeddedPicture();
                        if (bytes == null) throw new IOException("원본 음원에 앨범아트가 없습니다");
                        storeArtwork(t, bytes);
                      } catch (Exception e) {
                        toast(e.getMessage() == null ? "앨범아트를 읽지 못했습니다" : e.getMessage());
                      } finally {
                        try {
                          reader.release();
                        } catch (Exception ignored) {
                        }
                      }
                    });
              }
            })
        .show();
  }

  void fetchArtwork(Library.Track t, String address) {
    toast("앨범 이미지를 가져오고 있습니다");
    io.execute(
        () -> {
          java.net.HttpURLConnection c = null;
          try {
            c = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) throw new IOException("이미지를 가져오지 못했습니다");
            try (InputStream in = c.getInputStream()) {
              storeArtwork(t, Library.readBytes(in, 12_000_000));
            }
          } catch (Exception e) {
            toast("이미지를 가져오지 못했습니다. 이미지 파일 주소를 확인하세요.");
          } finally {
            if (c != null) c.disconnect();
          }
        });
  }

  void storeArtwork(Library.Track t, byte[] bytes) throws Exception {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("지원하지 않는 이미지입니다");
    int sample = 1;
    while (Math.max(options.outWidth, options.outHeight) / sample > 1600) sample *= 2;
    options.inSampleSize = sample;
    options.inJustDecodeBounds = false;
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (bitmap == null) throw new IOException("이미지를 읽지 못했습니다");
    File target = new File(getFilesDir(), t.id + "-art-" + System.currentTimeMillis() + ".jpg");
    try (FileOutputStream out = new FileOutputStream(target)) {
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
        throw new IOException("이미지를 저장하지 못했습니다");
    } finally {
      bitmap.recycle();
    }
    runOnUiThread(
        () -> {
          if (destroyed) return;
          t.art = target.getAbsolutePath();
          library.save();
          if (player != null)
            for (int i = 0; i < player.getMediaItemCount(); i++)
              if (player.getMediaItemAt(i).mediaId.equals(t.id))
                player.replaceMediaItem(i, t.media());
          render();
          toast("앨범아트를 변경했습니다");
        });
  }

  void importMenu() {
    if (importing) {
      toast("음악을 가져오는 중입니다");
      return;
    }
    new SheetDialog.Builder(this)
        .setTitle("음악 추가")
        .setItems(
            new String[] {"음원 파일 선택", "폴더와 하위 폴더 추가"},
            (d, w) -> {
              Intent in =
                  new Intent(
                      w == 0 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT_TREE);
              in.addFlags(
                  Intent.FLAG_GRANT_READ_URI_PERMISSION
                      | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                      | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
              if (w == 0) {
                in.setType("audio/*");
                in.addCategory(Intent.CATEGORY_OPENABLE);
                in.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
              }
              getSharedPreferences("pending_import", 0).edit()
                  .putString("playlist", playlistFilter).commit();
              startActivityForResult(in, w == 0 ? 10 : 11);
            })
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null) return;
    Uri uri = data.getData();
    if (request == 14 && uri != null) {
      Library.Track t = library.find(artworkTrackId);
      if (t != null)
        io.execute(
            () -> {
              try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException();
                storeArtwork(t, Library.readBytes(in, 12_000_000));
              } catch (Exception e) {
                toast("이미지를 읽지 못했습니다. 다른 이미지 파일을 선택하세요.");
              }
            });
      return;
    }
    if (request == 10 || request == 11) {
      ArrayList<Uri> uris = new ArrayList<>();
      if (data.getClipData() != null)
        for (int i = 0; i < data.getClipData().getItemCount(); i++)
          uris.add(data.getClipData().getItemAt(i).getUri());
      else if (uri != null) uris.add(uri);
      for (Uri u : uris)
        try {
          getContentResolver()
              .takePersistableUriPermission(
                  u, (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                      ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                      : Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
      final String targetPlaylist = getSharedPreferences("pending_import", 0)
          .getString("playlist", playlistFilter);
      getSharedPreferences("pending_import", 0).edit().remove("playlist").apply();
      importing = true;
      toast("음악 정보를 가져오고 있습니다");
      io.execute(
          () -> {
            Library imported = new Library(this);
            imported.tracks.clear();
            int failed = 0;
            for (Uri u : uris)
              try {
                if (request == 11) imported.importFolder(u);
                else imported.add(u);
              } catch (Exception e) {
                failed++;
              }
            int errors = failed;
            runOnUiThread(
                () -> {
                  importing = false;
                  if (!destroyed) {
                    int added = 0;
                    for (Library.Track incoming : imported.tracks) {
                      String importedId = incoming.id;
                      boolean exists = false;
                      for (Library.Track saved : library.tracks)
                        if (saved.uri.equals(incoming.uri)) {
                          exists = true;
                          importedId = saved.id;
                          if (saved.lyrics.isEmpty()) saved.lyrics = incoming.lyrics;
                          if (!incoming.lyricUri.isEmpty()) saved.lyricUri = incoming.lyricUri;
                          if (!incoming.id.equals(saved.id) && !incoming.art.isEmpty())
                            new File(incoming.art).delete();
                          break;
                        }
                      if (!exists) {
                        library.tracks.add(incoming);
                        added++;
                      }
                      if (!targetPlaylist.isEmpty() && library.playlists.containsKey(targetPlaylist)) {
                        ArrayList<String> ids = library.playlists.get(targetPlaylist);
                        if (!ids.contains(importedId)) ids.add(importedId);
                      }
                    }
                    library.save();
                    if (!targetPlaylist.isEmpty()) {
                      playlistFilter = targetPlaylist; albumFilter = ""; query = ""; tab = "목록";
                    }
                    render();
                    toast(added + "곡 추가" + (errors > 0 ? " · " + errors + "개 파일을 읽지 못했습니다" : ""));
                  }
                });
          });
    } else if (request == 12 && uri != null) {
      Library.Track t = library.find(lyricTrackId);
      if (t != null)
        io.execute(
            () -> {
              try {
                String raw = library.read(uri);
                runOnUiThread(() -> {
                  t.lyricUri = uri.toString();
                  try { getContentResolver().takePersistableUriPermission(uri, (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                      ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                      : Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                  catch (Exception ignored) { }
                  applyLyrics(t, raw);
                });
              } catch (Exception e) {
                toast("가사 파일을 읽지 못했습니다");
              }
            });
    } else if (request == 13 && uri != null) {
      String raw = exportText;
      io.execute(
          () -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException();
              out.write(raw.getBytes(StandardCharsets.UTF_8));
              toast("가사 파일을 저장했습니다");
            } catch (Exception e) {
              toast("파일을 저장하지 못했습니다");
            }
          });
    }
  }

  void applyLyrics(Library.Track t, String raw) {
    if (destroyed) return;
    t.lyrics = raw;
    library.save();
    try {
      File file = new File(getFilesDir(), t.id + (Lyrics.parse(raw).isEmpty() ? ".txt" : ".lrc"));
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(raw.getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception e) {
      toast("가사 파일 저장에 실패했습니다");
    }
    if (now && current() != null && current().id.equals(t.id)) loadLyrics(t);
    toast("가사를 가져와 저장했습니다");
  }

  final java.util.HashSet<String> lyricRequests = new java.util.HashSet<>();

  void autoLyrics(Library.Track t) {
    if (!lyricRequests.add(t.id)) return;
    boolean bundled = t.id.equals("cool-player-experience-morning-blue");
    LinearLayout status = row();
    status.setPadding(dp(4), dp(12), dp(4), dp(16));
    ProgressBar spinner = new ProgressBar(this);
    spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
    status.addView(spinner, new LinearLayout.LayoutParams(dp(24), dp(24)));
    TextView message = text(tr(bundled ? "체험곡에 제공된 가사를 불러오는 중" : "곡명과 가수로 가사를 찾는 중"), 15, muted);
    message.setPadding(dp(14), 0, 0, 0);
    status.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
    SheetDialog progress = new SheetDialog.Builder(this).setTitle("가사 가져오기").setView(status).create();
    final boolean[] cancelled = {false};
    progress.setOnCancelListener(d -> cancelled[0] = true);
    progress.show();
    io.execute(() -> {
      try {
        String raw = bundled ? library.bundledLyrics("morning-blue.lrc") : Lyrics.automatic(t);
        runOnUiThread(() -> {
          lyricRequests.remove(t.id);
          if (destroyed || cancelled[0]) return;
          if (lyricContent != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            android.transition.TransitionManager.beginDelayedTransition(lyricContent,
                new android.transition.Fade().setDuration(280));
          }
          applyLyrics(t, raw);
          spinner.setVisibility(View.GONE);
          message.setText(tr("가사를 저장했습니다"));
          message.setTextColor(accent);
          status.announceForAccessibility(message.getText());
          status.postDelayed(() -> { if (!destroyed) progress.dismiss(); }, 550);
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          lyricRequests.remove(t.id);
          if (destroyed || cancelled[0]) return;
          progress.dismiss();
          new SheetDialog.Builder(this).setTitle("가사를 가져오지 못했습니다")
              .setMessage(e.getMessage() == null ? "연결을 확인하고 다시 시도해 주세요." : e.getMessage())
              .setPositiveButton("수동 검색", (d, w) -> searchLyrics(t))
              .setNegativeButton("닫기", null).show();
        });
      }
    });
  }

  void lyricsMenu(Library.Track t) {
    new SheetDialog.Builder(this)
        .setTitle("가사")
        .setItems(
            new String[] {
              "자동으로 가져와 저장", "곡명·가수 직접 검색", "LRC / TXT 파일 불러오기", "가사 직접 편집", "가사 파일 내보내기", "가사 글자 크기", "가사 타이밍 조절", "전체 화면 가사"
            },
            (d, w) -> {
              if (w == 7) { fullLyrics = true; now = true; render(); }
              else if (w == 0) autoLyrics(t);
              else if (w == 1) searchLyrics(t);
              else if (w == 2) {
                lyricTrackId = t.id;
                Intent in =
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(in, 12);
              } else if (w == 3) {
                EditText edit = new EditText(this);
                edit.setText(t.lyrics);
                edit.setGravity(Gravity.TOP);
                edit.setMinLines(8);
                ScrollView scroll = new ScrollView(this);
                scroll.addView(edit);
                new SheetDialog.Builder(this)
                    .setTitle("가사 편집 · LRC 또는 일반 텍스트")
                    .setView(scroll)
                    .setPositiveButton("저장", (dd, ww) -> applyLyrics(t, edit.getText().toString()))
                    .setNegativeButton("취소", null)
                    .show();
              } else if (w == 6) {
                showLyricTiming();
              } else if (w == 5) {
                chooseLyricSize();
              } else {
                if (t.lyrics.isEmpty()) {
                  toast("저장할 가사가 없습니다");
                  return;
                }
                exportText = t.lyrics;
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("text/plain")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(
                            Intent.EXTRA_TITLE,
                            t.title + (Lyrics.parse(t.lyrics).isEmpty() ? ".txt" : ".lrc")),
                    13);
              }
            })
        .show();
  }

  void searchLyrics(Library.Track t) {
    LinearLayout panel = column();
    panel.setPadding(0, 0, 0, 0);
    EditText title = new EditText(this);
    title.setHint(tr("곡명"));
    title.setSingleLine();
    title.setText(t.lyricSearchTitle.isEmpty() ? t.title : t.lyricSearchTitle);
    EditText artist = new EditText(this);
    artist.setHint(tr("가수 이름"));
    artist.setSingleLine();
    artist.setText(t.artist.equals("알 수 없는 아티스트") ? "" : t.artist);
    LinearLayout fields = row();
    fields.setBackground(shape(card, 24));
    fields.setPadding(dp(4), 0, dp(4), 0);
    fields.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
    panel.addView(fields);
    space(panel, 8);
    LinearLayout filters = row();
    filters.addView(artist, new LinearLayout.LayoutParams(0, dp(40), 1));
    panel.addView(filters);
    final boolean[] artistFilter = {false};
    TextView status = text(tr("곡명 또는 가수 이름으로 검색하세요"), 12, muted);
    status.setPadding(dp(4), dp(12), dp(4), dp(12));
    panel.addView(status);
    ListView results = new ListView(this);
    ArrayList<Lyrics.Result> items = new ArrayList<>();
    BaseAdapter a =
        new BaseAdapter() {
          public int getCount() {
            return items.size();
          }

          public Object getItem(int position) {
            return items.get(position);
          }

          public long getItemId(int position) {
            return position;
          }

          public View getView(int position, View recycled, ViewGroup parent) {
            Lyrics.Result result = items.get(position);
            LinearLayout entry = column();
            entry.setPadding(dp(14), dp(13), dp(14), dp(13));
            TextView name = text(result.title, 16, ink);
            name.setMaxLines(2);
            name.setTypeface(null, Typeface.BOLD);
            name.setEllipsize(TextUtils.TruncateAt.END);
            entry.addView(name);
            TextView artistText = text(result.artist, 13, muted);
            artistText.setSingleLine();
            artistText.setEllipsize(TextUtils.TruncateAt.END);
            artistText.setPadding(0, dp(6), 0, 0);
            entry.addView(artistText);
            if (!result.album.isEmpty() && !result.album.equalsIgnoreCase(result.title)) {
              TextView album = text(result.album, 12, muted);
              album.setMaxLines(1);
              album.setEllipsize(TextUtils.TruncateAt.END);
              album.setPadding(0, dp(4), 0, 0);
              entry.addView(album);
            }
            return entry;
          }
        };
    results.setDivider(new android.graphics.drawable.ColorDrawable(darkTheme ? 0xff303640 : 0xffe3e7ed));
    results.setDividerHeight(dp(1));
    results.setVerticalScrollBarEnabled(false);
    results.setBackgroundColor(Color.TRANSPARENT);
    results.setAdapter(a);
    panel.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView more = button(tr("검색"), () -> {});
    more.setTextSize(13);
    more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    more.setVisibility(View.GONE);
    panel.addView(more, new LinearLayout.LayoutParams(-1, dp(44)));
    final int[] page = {0}, generation = {0};
    final boolean[] busy = {false}, hasMore = {false};
    final String[] terms = {"", ""};
    SheetDialog dialog =
        new SheetDialog.Builder(this)
            .setTitle("가사 검색")
            .setFlexibleView(panel)
            .create();
    Runnable fetch =
        () -> {
          if (busy[0]) return;
          busy[0] = true;
          int gen = generation[0], p = page[0];
          String q = terms[0], ar = terms[1];
          status.setText(tr("검색 중…"));
          more.setText(tr("불러오는 중…"));
          more.setVisibility(View.VISIBLE);
          more.setEnabled(false);
          io.execute(
              () -> {
                Lyrics.Page found = Lyrics.search(q, ar, p);
                runOnUiThread(
                    () -> {
                      if (destroyed || !dialog.isShowing() || gen != generation[0]) return;
                      busy[0] = false;
                      HashSet<String> ids = new HashSet<>();
                      for (Lyrics.Result r : items) ids.add(r.source + ":" + r.id);
                      for (Lyrics.Result r : found.results)
                        if (ids.add(r.source + ":" + r.id)) items.add(r);
                      page[0]++;
                      hasMore[0] = found.more;
                      a.notifyDataSetChanged();
                      status.setText(items.isEmpty()
                          ? tr(found.notice.trim().isEmpty() ? "검색 결과가 없습니다" : "연결이 원활하지 않습니다. 다시 검색해 주세요.")
                          : items.size() + tr("개 결과"));
                      more.setText(
                          tr(found.more ? "결과 더 불러오기" : items.isEmpty() ? "검색 결과가 없습니다" : "마지막 결과입니다"));
                      more.setEnabled(found.more);
                      more.setVisibility(found.more ? View.VISIBLE : View.GONE);
                    });
              });
        };
    TextView search =
        icon(
            "search", "검색",
            () -> {
              android.view.inputmethod.InputMethodManager keyboard =
                  (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
              if (keyboard != null) keyboard.hideSoftInputFromWindow(title.getWindowToken(), 0);
              title.clearFocus(); artist.clearFocus();
              generation[0]++;
              busy[0] = false;
              page[0] = 0;
              hasMore[0] = false;
              more.setVisibility(View.GONE);
              items.clear();
              a.notifyDataSetChanged();
              terms[0] = artistFilter[0] ? "" : title.getText().toString().trim();
              terms[1] = artist.getText().toString().trim();
              if ((terms[0] + terms[1]).trim().isEmpty()) {
                status.setText(tr("곡명 또는 가수 이름을 입력하세요"));
                return;
              }
              more.setEnabled(true);
              fetch.run();
            });
    TextView clearQuery = icon("close", tr("지우기"), () -> {
      title.setText("");
      title.requestFocus();
    });
    clearQuery.setVisibility(title.length() == 0 ? View.GONE : View.VISIBLE);
    title.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
      public void onTextChanged(CharSequence value, int start, int before, int count) {
        clearQuery.setVisibility(value.length() == 0 ? View.GONE : View.VISIBLE);
      }
      public void afterTextChanged(android.text.Editable value) {}
    });
    fields.addView(clearQuery, new LinearLayout.LayoutParams(dp(48), dp(48)));
    fields.addView(search, new LinearLayout.LayoutParams(dp(48), dp(48)));
    TextView artistOnly = text(tr("가수로만 검색"), 12, muted);
    artistOnly.setGravity(Gravity.CENTER);
    artistOnly.setPadding(dp(12), 0, dp(12), 0);
    artistOnly.setBackground(shape(card, 20));
    artistOnly.setFocusable(true);
    artistOnly.setOnClickListener(v -> {
      artistFilter[0] = !artistFilter[0];
      artistOnly.setSelected(artistFilter[0]);
      artistOnly.setTextColor(artistFilter[0] ? accent : muted);
      artistOnly.setText((artistFilter[0] ? "✓ " : "") + tr("가수로만 검색"));
      title.setEnabled(!artistFilter[0]);
      title.setAlpha(artistFilter[0] ? .45f : 1);
      search.performClick();
    });
    filters.addView(artistOnly, new LinearLayout.LayoutParams(-2, dp(48)));
    title.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    title.setOnEditorActionListener((view, action, event) -> {
      if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
        search.performClick(); return true;
      }
      return false;
    });
    artist.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    artist.setOnEditorActionListener((view, action, event) -> {
      if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
        search.performClick(); return true;
      }
      return false;
    });
    more.setOnClickListener(
        v -> {
          if (hasMore[0]) fetch.run();
        });
    results.setOnScrollListener(
        new AbsListView.OnScrollListener() {
          public void onScrollStateChanged(AbsListView v, int state) {}

          public void onScroll(AbsListView v, int first, int count, int total) {
            if (total > 0 && first > 0 && first + count >= total && hasMore[0] && !busy[0])
              fetch.run();
          }
        });
    results.setOnItemClickListener(
        (p, v, position, id) -> {
          Lyrics.Result selected = items.get(position);
          status.setText(tr("가사를 불러오는 중…"));
          io.execute(
              () -> {
                try {
                  String raw = Lyrics.resolve(selected);
                  runOnUiThread(
                      () -> {
                        if (destroyed || !dialog.isShowing()) return;
                        TextView preview = text(raw, 15, ink);
                        preview.setPadding(dp(16), dp(8), dp(16), dp(8));
                        ScrollView scroll = new ScrollView(this);
                        scroll.addView(preview);
                        new SheetDialog.Builder(this)
                            .setTitle(selected.title + " · " + selected.artist)
                            .setView(scroll)
                            .setPositiveButton(
                                "이 가사 사용",
                                (dd, ww) -> {
                                  applyLyrics(t, raw);
                                  dialog.dismiss();
                                })
                            .setNegativeButton("취소", null)
                            .show();
                        status.setText(items.size() + "개 결과");
                      });
                } catch (Exception e) {
                  toast("이 결과의 가사를 불러오지 못했습니다");
                  runOnUiThread(() -> status.setText(tr("다른 검색 결과를 선택해 주세요")));
                }
              });
        });
    dialog.setOnDismissListener(
        d -> {
          generation[0]++;
        });
    dialog.show();
    title.setBackgroundColor(Color.TRANSPARENT);
    title.setPadding(dp(14), 0, dp(4), 0);
    artist.setBackgroundColor(Color.TRANSPARENT);
    artist.setTextSize(13);
    artist.setPadding(dp(8), 0, dp(8), 0);
    search.performClick();
  }

  LinearLayout settingsGroup(LinearLayout parent, String title) {
    TextView label = text(tr(title), 12, muted);
    label.setPadding(dp(14), dp(22), 0, dp(8));
    parent.addView(label);
    LinearLayout group = column();
    group.setBackground(shape(darkTheme ? 0xff202024 : Color.WHITE, 22));
    group.setClipToOutline(true);
    parent.addView(group);
    return group;
  }

  void settingRow(LinearLayout group, String symbol, String title, String value, Runnable action) {
    if (group.getChildCount() > 0) {
      View divider = new View(this);
      divider.setBackgroundColor(darkTheme ? 0xff343944 : 0xffdfe4eb);
      LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(-1, 1);
      d.leftMargin = dp(52);
      group.addView(divider, d);
    }
    LinearLayout row = row();
    row.setPadding(dp(14), dp(8), dp(12), dp(8));
    row.setMinimumHeight(dp(58));
    ImageView image = new ImageView(this);
    image.setImageDrawable(new PlayerGlyph(symbol, accent, dp(22)));
    row.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));
    TextView name = text(tr(title), 15, ink);
    name.setPadding(dp(12), 0, dp(8), 0);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    TextView detail = text(tr(value), 13, muted);
    detail.setMaxLines(1);
    row.addView(detail);
    TextView chevron = text("  ›", 21, muted);
    row.addView(chevron);
    row.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x227788aa), null, shape(Color.WHITE, 16)));
    row.setClickable(true);
    row.setFocusable(true);
    row.setOnClickListener(v -> {
      if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
        row.animate().cancel();
        row.setScaleX(.985f);
        row.setScaleY(.985f);
        row.animate().scaleX(1).scaleY(1).setDuration(180)
            .setInterpolator(new android.view.animation.PathInterpolator(.22f, .75f, .18f, 1)).start();
      }
      action.run();
    });
    group.addView(row);
  }

  void settingSwitch(LinearLayout group, String title, boolean checked,
      java.util.function.Consumer<Boolean> changed) {
    if (group.getChildCount() > 0) {
      View divider = new View(this);
      divider.setBackgroundColor(darkTheme ? 0xff343438 : 0xffe8e8ed);
      LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, 1);
      line.setMargins(dp(16), 0, dp(16), 0);
      group.addView(divider, line);
    }
    LinearLayout row = row();
    row.setPadding(dp(16), dp(6), dp(16), dp(6));
    row.setMinimumHeight(dp(54));
    row.addView(text(tr(title), 15, ink), new LinearLayout.LayoutParams(0, -2, 1));
    Switch toggle = new Switch(this);
    toggle.setContentDescription(tr(title));
    toggle.setShowText(false);
    toggle.setChecked(checked);
    toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
    toggle.setTrackTintList(new android.content.res.ColorStateList(
        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
        new int[] {0xff34c759, darkTheme ? 0xff505158 : 0xffd7d9df}));
    toggle.setOnCheckedChangeListener((v, value) -> changed.accept(value));
    row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(44)));
    row.setOnClickListener(v -> toggle.toggle());
    group.addView(row);
  }

  int lyricFontSize() {
    return getSharedPreferences("settings", 0).getInt("lyricFontSize", 24);
  }

  void chooseLyricSize() {
    int[] sizes = {18, 22, 24, 28, 32, 36};
    String[] labels = {"18", "22", "24", "28", "32", "36"};
    int selected = -1;
    for (int i = 0; i < sizes.length; i++) if (sizes[i] == lyricFontSize()) selected = i;
    new SheetDialog.Builder(this).setTitle("가사 글자 크기")
        .setSingleChoiceItems(labels, selected, (d, w) -> {
          getSharedPreferences("settings", 0).edit().putInt("lyricFontSize", sizes[w]).apply();
          d.dismiss(); render();
        }).show();
  }

  String tr(String source) { return AppLanguage.text(this, source); }

  void scanDuplicates() {
    android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
    SheetDialog loading = new SheetDialog.Builder(this).setTitle("곡 중복 정리")
        .setMessage("같은 파일 내용을 가진 곡을 찾고 있습니다. 자동으로 삭제하지 않습니다.")
        .setView(spinner).show();
    java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    loading.setOnCancelListener(d -> cancelled.set(true));
    ArrayList<Library.Track> snapshot = new ArrayList<>(library.tracks);
    io.execute(() -> {
      java.util.LinkedHashMap<String, ArrayList<Library.Track>> groups = new java.util.LinkedHashMap<>();
      int unreadable = 0;
      for (Library.Track track : snapshot) {
        if (cancelled.get() || destroyed) return;
        try (InputStream stream = getContentResolver().openInputStream(Uri.parse(track.uri))) {
          if (stream == null) throw new IOException();
          java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
          byte[] buffer = new byte[65536]; int count;
          while ((count = stream.read(buffer)) != -1) {
            if (cancelled.get() || destroyed) return;
            digest.update(buffer, 0, count);
          }
          String key = android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
          ArrayList<Library.Track> group = groups.computeIfAbsent(key, k -> new ArrayList<>());
          boolean sameUri = false;
          for (Library.Track existing : group) if (existing.uri.equals(track.uri)) sameUri = true;
          if (!sameUri) group.add(track);
        } catch (Exception e) { unreadable++; }
      }
      ArrayList<ArrayList<Library.Track>> duplicates = new ArrayList<>();
      for (ArrayList<Library.Track> group : groups.values()) if (group.size() > 1) duplicates.add(group);
      final int skipped = unreadable;
      runOnUiThread(() -> {
        if (cancelled.get() || destroyed) return;
        loading.dismiss();
        if (duplicates.isEmpty()) {
          new SheetDialog.Builder(this).setTitle("곡 중복 정리")
              .setMessage(tr("내용이 같은 중복 파일이 없습니다.") + (skipped > 0 ? "\n" + skipped + " " + tr("개 파일을 읽지 못했습니다") : "")).show();
          return;
        }
        String[] labels = new String[duplicates.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = duplicates.get(i).get(0).title + " · " + duplicates.get(i).size();
        new SheetDialog.Builder(this).setTitle("곡 중복 정리")
            .setMessage("내용이 일치하는 파일만 표시합니다. 그룹을 열어 삭제할 파일을 직접 선택하세요.")
            .setNavigationItems(labels, (d, index) -> {
              ArrayList<Library.Track> group = duplicates.get(index);
              String[] files = new String[group.size()];
              for (int i = 0; i < files.length; i++) files[i] = group.get(i).title + "\n" + Uri.decode(group.get(i).uri);
              new SheetDialog.Builder(this).setTitle("삭제할 파일 선택")
                  .setItems(files, (dd, selected) -> confirmDeleteFile(group.get(selected))).show();
            }).show();
      });
    });
  }

  void showAppEqualizer() {
    int selected = getSharedPreferences("settings", 0).getInt("eqPreset", 0);
    new SheetDialog.Builder(this).setTitle("앱 이퀄라이저")
        .setMessage("선택한 음색은 Cool Player 재생에 적용됩니다.")
        .setSingleChoiceItems(AppEqualizer.NAMES, selected, (dialog, index) -> {
          getSharedPreferences("settings", 0).edit().putInt("eqPreset", index).apply();
          dialog.dismiss(); render();
        }).show();
  }

  void chooseLanguage() {
    String[] codes = AppLanguage.CODES;
    int selected = Arrays.asList(codes).indexOf(getSharedPreferences("settings", 0).getString("language", "system"));
    new SheetDialog.Builder(this).setTitle("언어")
        .setSingleChoiceItems(AppLanguage.NAMES, selected, (d, w) -> {
          getSharedPreferences("settings", 0).edit().putString("language", codes[w]).apply();
          d.dismiss(); render();
        }).show();
  }

  void showInfoAsset(String title, String asset) {
    try {
      LinearLayout sections = column();
      boolean guide = asset.startsWith("guide-");
      if (guide) {
        String imagePath = "guide/" + asset.substring(6, asset.indexOf('.')) + ".png";
        android.graphics.Bitmap screenshot;
        try (java.io.InputStream imageStream = getAssets().open(imagePath)) {
          screenshot = android.graphics.BitmapFactory.decodeStream(imageStream);
        }
        ImageView preview = new ImageView(this);
        preview.setImageBitmap(screenshot);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(title + " 화면 예시, 누르면 확대");
        preview.setBackground(shape(darkTheme ? 0xff2c2c2e : Color.WHITE, 18));
        preview.setPadding(dp(8), dp(8), dp(8), dp(8));
        sections.addView(preview, new LinearLayout.LayoutParams(-1, dp(260)));
        TextView hint = text("실제 앱 화면 · 누르면 크게 보기", 12, muted);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(14));
        sections.addView(hint);
        preview.setOnClickListener(v -> {
          ImageView full = new ImageView(this);
          full.setImageBitmap(screenshot);
          full.setAdjustViewBounds(true);
          full.setScaleType(ImageView.ScaleType.FIT_CENTER);
          new SheetDialog.Builder(this).setTitle(title).setView(full).show();
        });
      }
      int step = 0;
      String source = tr(library.bundledLyrics(asset));
      if (asset.equals("APACHE-2.0.txt")) {
        TextView license = text(source.replaceAll("(?m)^[ \t]+", ""), 13, ink);
        license.setGravity(Gravity.START);
        license.setTextIsSelectable(true);
        license.setLineSpacing(dp(3), 1.1f);
        license.setPadding(dp(4), dp(8), dp(4), dp(8));
        new SheetDialog.Builder(this).setTitle("오픈소스 라이선스 · Apache 2.0")
            .setMessage("앱에 포함된 AndroidX·Media3·Guava 등 오픈소스 구성요소의 라이선스입니다. Cool Player 자체의 이용약관이 아닙니다.")
            .setView(license).show();
        return;
      }
      boolean firstSection = true;
      for (String section : source.split("\\n\\s*\\n")) {
        LinearLayout cardView = column();
        if (guide) {
          cardView.setPadding(dp(14), dp(10), dp(14), dp(10));
          cardView.setBackground(shape(darkTheme ? 0xff2c2c2e : Color.WHITE, 18));
        } else {
          cardView.setPadding(dp(4), firstSection ? 0 : dp(16), dp(4), 0);
        }
        firstSection = false;
        int split = section.indexOf('\n');
        if (split > 0 && section.substring(0, split).length() < 45) {
          TextView heading = text((guide ? (++step) + "  " : "") + section.substring(0, split).replaceFirst("^\\d+\\.\\s*", ""), 16, ink);
          heading.setTypeface(null, Typeface.BOLD);
          cardView.addView(heading);
          space(cardView, guide ? 8 : 4);
          section = section.substring(split + 1);
        }
        android.text.SpannableStringBuilder body = new android.text.SpannableStringBuilder();
        for (String line : section.split("\\n")) {
          if (body.length() > 0) body.append("\n");
          String clean = line.trim();
          if (clean.startsWith("https://") || clean.startsWith("http://")) {
            String label = clean.contains("creativecommons.org") ? "라이선스 보기 ↗"
                : clean.contains("commons.wikimedia.org") ? "음원 출처 보기 ↗"
                : clean.contains("apache.org") ? "Apache 2.0 원문 ↗"
                : clean.contains("github.com/androidx/media") ? tr("Media3 프로젝트 ↗")
                : clean.contains("github.com/androidx/androidx") ? tr("AndroidX 프로젝트 ↗")
                : clean.contains("github.com/google/guava") ? tr("Guava 프로젝트 ↗")
                : clean.contains("joshwoodward.com") ? tr("Josh Woodward 공식 사이트 ↗")
                : android.net.Uri.parse(clean).getHost() + " ↗";
            int start = body.length(); body.append(tr(label));
            body.setSpan(new android.text.style.URLSpan(clean), start, body.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          } else body.append(line);
        }
        TextView copy = text(body.toString(), 14, muted);
        copy.setText(body);
        copy.setTextIsSelectable(false);
        copy.setLinksClickable(true);
        copy.setLinkTextColor(accent);
        copy.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        copy.setLineSpacing(dp(3), 1.1f);
        cardView.addView(copy);
        sections.addView(cardView, new LinearLayout.LayoutParams(-1, -2));
        if (guide) space(sections, 6);
      }
      new SheetDialog.Builder(this).setTitle(title).setView(sections).show();
    } catch (java.io.IOException e) { toast("안내를 불러오지 못했습니다"); }
  }

  void showSpotlightTutorial() {
    now = false; tab = "목록"; playlistFilter = ""; albumFilter = ""; query = "";
    render();
      for (String demoName : library.playlists.keySet()) {
        if (!library.playlists.get(demoName).contains("cool-player-welcome")) continue;
        final String demo = demoName;
        root.post(() -> SpotlightGuide.show(this, root.findViewWithTag("guide-tab-목록"),
            "1 / 4 · 재생목록\n목록 탭에서 체험곡을 찾아보세요.", "목록으로 이동", () -> {
          tab = "목록"; playlistFilter = ""; albumFilter = ""; now = false; render();
          root.post(() -> SpotlightGuide.show(this, root.findViewWithTag("guide-playlist-" + demo),
              "2 / 4 · 체험곡\n체험 재생목록을 열어보세요.", "재생목록 열기", () -> {
            playlistFilter = demo; query = ""; render();
            list.post(() -> {
              Library.Track morning = library.find("cool-player-experience-morning-blue");
              if (morning == null) { toast("Morning Blue 체험곡을 추가해 주세요."); return; }
              int index = visible.indexOf(morning);
              if (index < 0) return;
              list.setSelection(index);
              list.post(() -> SpotlightGuide.show(this, list.getChildAt(index - list.getFirstVisiblePosition()),
                  "3 / 4 · Morning Blue\nMorning Blue를 열고 가사 화면으로 이동하세요.", "Morning Blue 열기", () -> {
                if (player == null) { toast("재생 준비 후 튜토리얼을 다시 열어주세요."); return; }
                play(visible, index); now = true; showPhoneLyrics = true; render();
                root.post(() -> {
                  View fetch = root.findViewWithTag("guide-fetch-lyrics");
                  if (fetch == null) { toast("이미 가사가 저장되어 있습니다."); return; }
                  SpotlightGuide.show(this, fetch,
                      "4 / 4 · 가사 가져오기\n강조된 ‘가사 가져오기’ 버튼을 눌러 가사를 검색하고 저장하세요.",
                      "가사 가져오기", fetch::performClick);
                });
              }));
            });
          }));
        }));
        return;
      }
    toast("체험 재생목록이 있어야 튜토리얼을 볼 수 있습니다. 사용법에서 체험곡을 추가해 주세요.");
  }

  void showUserGuide() {
    String[] topics = {"음악 가져오기와 보관함", "플레이어와 제스처", "가사 가져오기와 타이밍", "재생목록과 위젯", "설정과 업데이트", "체험곡 사용하기"};
    new SheetDialog.Builder(this).setTitle("사용법").setNavigationItems(topics,
        (dialog, index) -> showInfoAsset(topics[index], "guide-" + index + ".txt")).show();
  }

  void renderSettings() {
    heading(body, "설정", "");
    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout stack = column();
    scroll.addView(stack);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    SharedPreferences prefs = getSharedPreferences("settings", 0);
    LinearLayout appearance = settingsGroup(stack, "화면과 가사");
    settingRow(appearance, "lyrics", "가사 글자 크기", String.valueOf(lyricFontSize()), this::chooseLyricSize);
    settingRow(appearance, "language", tr("언어"), AppLanguage.displayName(this), this::chooseLanguage);
    String[] modes = {"기기 설정", "라이트", "다크"};
    int selected =
        Arrays.asList("system", "light", "dark").indexOf(prefs.getString("theme", "system"));
    settingRow(
        appearance,
        "album",
        "화면 모드",
        modes[Math.max(0, selected)],
        () ->
            new SheetDialog.Builder(this)
                .setTitle("화면 모드")
                .setSingleChoiceItems(
                    modes,
                    selected,
                    (d, w) -> {
                      prefs
                          .edit()
                          .putString("theme", new String[] {"system", "light", "dark"}[w])
                          .apply();
                      d.dismiss();
                      colors();
                      render();
                    })
                .show());
    settingSwitch(appearance, "가사 자동 스크롤", prefs.getBoolean("follow", true),
        value -> prefs.edit().putBoolean("follow", value).apply());
    LinearLayout audio = settingsGroup(stack, "재생");
    String speed =
        player == null
            ? "1.0×"
            : String.format(Locale.ROOT, "%.2g×", player.getPlaybackParameters().speed);
    settingRow(
        audio,
        "play",
        "재생 속도",
        speed,
        () ->
            new SheetDialog.Builder(this)
                .setTitle("재생 속도")
                .setItems(
                    new String[] {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×"},
                    (d, w) -> {
                      if (player != null)
                        player.setPlaybackSpeed(new float[] {.75f, 1f, 1.25f, 1.5f, 2f}[w]);
                      render();
                    })
                .show());
    long remaining = prefs.getLong("sleepEnd", 0) - System.currentTimeMillis();
    settingRow(
        audio,
        "settings",
        "취침 타이머",
        remaining > 0 ? (remaining / 60000 + 1) + "분 남음" : "끔",
        () ->
            new SheetDialog.Builder(this)
                .setTitle("취침 타이머")
                .setItems(
                    new String[] {"15분", "30분", "45분", "60분", "90분", "타이머 취소"},
                    (d, w) -> {
                      long minutes = new long[] {15, 30, 45, 60, 90, 0}[w];
                      prefs
                          .edit()
                          .putLong(
                              "sleepEnd",
                              minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60000)
                          .apply();
                      startService(
                          new Intent(this, PlaybackService.class)
                              .setAction(PlaybackService.SLEEP)
                              .putExtra("minutes", minutes));
                      render();
                    })
                .show());
    settingRow(audio, "eq", "앱 이퀄라이저", "", this::showAppEqualizer);
    LinearLayout libraryGroup = settingsGroup(stack, "보관함과 홈 화면");
    settingRow(libraryGroup, "add", "음악 가져오기", "", this::importMenu);
    settingRow(libraryGroup, "sort", "곡 중복 정리", "", this::scanDuplicates);
    settingRow(
        libraryGroup,
        "album",
        "현재 곡 앨범아트",
        "",
        () -> {
          Library.Track t = current();
          if (t != null) artworkMenu(t);
          else toast("먼저 보관함에서 곡을 선택하세요");
        });
    settingRow(
        libraryGroup,
        "queue",
        "홈 화면 위젯 추가",
        "",
        () -> {
          android.appwidget.AppWidgetManager widgets =
              android.appwidget.AppWidgetManager.getInstance(this);
          new SheetDialog.Builder(this).setTitle("위젯 선택")
              .setItems(new String[]{"가로형 플레이어", "앨범형 플레이어", "재생목록", "작은 재생목록"}, (dialog, index) -> {
                Class<?> type = index == 0 ? PlayerWidget.class : index == 1 ? SquarePlayerWidget.class : index == 2 ? PlaylistWidget.class : SmallPlaylistWidget.class;
                if (widgets.isRequestPinAppWidgetSupported()) widgets.requestPinAppWidget(new ComponentName(this, type), null, null);
                else toast("홈 화면을 길게 눌러 Cool Player 위젯을 선택하세요");
              }).show();
        });
    LinearLayout help = settingsGroup(stack, "사용법 및 정보");
    settingRow(help, "lyrics", "사용법", "", this::showUserGuide);
    settingRow(help, "info", "튜토리얼 다시 보기", "", this::showSpotlightTutorial);
    settingRow(help, "info", "Cool Player 안내 및 저작권", "", () -> showInfoAsset("Cool Player 안내 및 저작권", "COPYRIGHT.txt"));
    settingRow(help, "music", "체험곡 출처", "", () -> showInfoAsset("체험곡 출처", "DEMO_CREDITS.txt"));
    settingRow(help, "info", "오픈소스 고지", "", () ->
        new SheetDialog.Builder(this).setTitle("오픈소스 고지")
            .setNavigationItems(new String[]{"사용한 라이브러리", "라이선스 전문"}, (dialog, index) ->
                showInfoAsset(index == 0 ? "사용한 라이브러리" : "라이선스 전문",
                    index == 0 ? "OPEN_SOURCE.txt" : "APACHE-2.0.txt")).show());
    LinearLayout about = settingsGroup(stack, "Cool Player");
    settingRow(
        about,
        "music",
        "앱 정보",
        appVersion(),
        () ->
            new SheetDialog.Builder(this)
                .setTitle("Cool Player " + appVersion())
                .setMessage(
                    "음악 재생\n기기에서 선택한 음악 파일과 폴더를 보관함에 추가해 재생합니다. 곡·앨범·즐겨찾기·재생목록으로 음악을 탐색하고, 재생 대기열에서 다음에 들을 곡을 관리할 수 있습니다.\n\n가사\n시간 정보가 있는 가사는 재생 위치에 맞춰 표시됩니다. 가사 자동 가져오기와 수동 검색, LRC·TXT 파일 불러오기·편집·내보내기를 지원하며 글자 크기와 표시 타이밍을 조절할 수 있습니다.\n\n재생 설정\n셔플·반복 재생, 재생 속도, 취침 타이머와 앱 이퀄라이저를 제공합니다. 홈 화면 위젯에서도 재생을 제어하고 재생목록을 열 수 있습니다. 오디오 효과는 기기 지원 여부에 따라 달라질 수 있습니다.\n\n파일 관리\n보관함이나 재생목록에서 제거하는 동작과 기기의 원본 파일을 삭제하는 동작은 구분됩니다. 파일 삭제를 선택하고 경고를 확인한 경우 음악과 연결된 가사 파일이 삭제될 수 있습니다. 자세한 조작은 설정의 사용법에서 확인하세요.")
                .setPositiveButton("확인", null)
                .show());
    settingRow(
        about,
        "lyrics",
        "데이터와 저장 안내",
        "",
        () ->
            new SheetDialog.Builder(this)
                .setTitle("데이터와 저장")
                .setMessage(
                    "내 음악\n가져온 원본 음악은 선택한 위치에 유지됩니다. 보관함에서 제거해도 원본은 남습니다. ‘파일 삭제’를 선택하고 확인하면 기기의 원본 음악과 연결된 가사 파일을 삭제합니다.\n\n"
                        + "가사 보관\n가사 메뉴의 ‘가사 파일 내보내기’에서 원하는 폴더에 LRC 또는 TXT 파일로 보관할 수 있습니다.\n\n"
                        + "앱 보관함\n재생목록·설정·앱에서 저장한 가사와 변경한 앨범아트는 앱 전용 공간에 저장됩니다. 별도로 보관할 가사는 파일로 내보내세요. 사용자가 외부 폴더에 보관한 원본 파일과 앱 전용 데이터는 저장 위치가 다릅니다.")
                .setPositiveButton("확인", null)
                .show());
    settingRow(
        about,
        "music",
        "홈페이지",
        "",
        () ->
            startActivity(
                new Intent(
                    Intent.ACTION_VIEW, Uri.parse("https://hun8902.github.io/CoolPlayer/"))));
    space(stack, 24);
  }
}
