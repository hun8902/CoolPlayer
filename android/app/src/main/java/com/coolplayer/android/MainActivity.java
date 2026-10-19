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

  AppUpdates updates;

  @Override
  protected void onResume() {
    super.onResume();
    if (updates != null) updates.resume();
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    library = new Library(this);
    updates = new AppUpdates(this);
    if (b != null) {
      now = b.getBoolean("now");
      tab = b.getString("tab", "곡");
    }
    showPhoneLyrics = b != null && b.getBoolean("lyrics");
    colors();
    render();
    handler.postDelayed(
        () -> {
          if (!destroyed) updates.automaticCheck();
        },
        2500);
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
    out.putBoolean("lyrics", showPhoneLyrics);
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    colors();
    render();
  }

  @Override
  protected void onDestroy() {
    updates.close();
    if (screenTransition != null) screenTransition.cancel();
    if (backdrop != null) backdrop.stop();
    destroyed = true;
    searchGeneration++;
    handler.removeCallbacksAndMessages(null);
    io.shutdownNow();
    if (connection != null) MediaController.releaseFuture(connection);
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    navigateBack();
  }

  void navigateBack() {
    if (now) {
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
    darkTheme = dark;
    bg = Color.parseColor(dark ? "#15181E" : "#FAFBFD");
    card = Color.parseColor(dark ? "#232831" : "#EEF2F7");
    ink = Color.parseColor(dark ? "#F2F4F8" : "#202633");
    muted = Color.parseColor(dark ? "#A3ADBD" : "#758094");
    accent = Color.parseColor(dark ? "#BDCAE9" : "#526FA7");
    setTheme(dark ? R.style.DarkTheme : R.style.AppTheme);
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    getWindow()
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
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  TextView button(String s, Runnable action) {
    TextView b = text(tr(s), 15, accent);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(12), dp(10), dp(12), dp(10));
    b.setMinHeight(dp(48));
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x227788AA), shape(card, 14), null));
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
        icon.invalidate();
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

  LinearLayout navigation(boolean rail) {
    LinearLayout nav = rail ? column() : row();
    if (!rail) {
      nav.setPadding(dp(5), dp(4), dp(5), dp(4));
      nav.setBackground(shape(darkTheme ? 0xf2252932 : 0xf2ffffff, 30));
      nav.setElevation(dp(8));
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
      v.setTextSize(11);
      v.setPadding(dp(4), dp(5), dp(4), dp(4));
      PlayerGlyph glyph = new PlayerGlyph(symbols[i], tab.equals(label) ? accent : muted, dp(22));
      v.setCompoundDrawablesWithIntrinsicBounds(null, glyph, null, null);
      v.setCompoundDrawablePadding(dp(3));
      if (!tab.equals(label)) v.setBackgroundColor(Color.TRANSPARENT);
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
    root = column();
    if (previous != null) root.setPadding(previous.getPaddingLeft(), previous.getPaddingTop(), previous.getPaddingRight(), previous.getPaddingBottom());
    else root.setPadding(dp(20), dp(32), dp(20), dp(20));
    root.setBackgroundColor(bg);
    if (now) {
      backdrop = new ArtworkBackdrop(darkTheme, wasNow ? previousBackdrop : null);
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
    brand.setImageResource(R.drawable.brand_mono);
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
      shell.addView(navigation(true), new LinearLayout.LayoutParams(dp(76), -1));
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
      // Reserve the measured dock height, including enlarged accessibility text.
      View scrollContent = list != null && list.getParent() == body ? list : body;
      if (scrollContent instanceof ListView) ((ListView) scrollContent).setClipToPadding(false);
      dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
        int bottom = b - t + dp(8);
        if (scrollContent.getPaddingBottom() != bottom)
          scrollContent.setPadding(scrollContent.getPaddingLeft(), scrollContent.getPaddingTop(),
              scrollContent.getPaddingRight(), bottom);
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
      entry.setOnClickListener(v -> { playlistFilter = name; render(); });
      Runnable menu = () -> new SheetDialog.Builder(this)
          .setTitle(name)
          .setItems(new String[]{"재생목록 열기", "삭제"}, (dialog, which) -> {
            if (which == 0) { playlistFilter = name; render(); return; }
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
    mini.setPadding(dp(10), dp(8), dp(6), dp(8));
    mini.setBackground(shape(darkTheme ? 0xf2252932 : 0xf2ffffff, 26));
    mini.setElevation(dp(6));
    miniArt = new ImageView(this);
    miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
    miniArt.setBackground(shape(card, 10));
    miniArt.setClipToOutline(true);
    artwork(miniArt, current());
    mini.addView(miniArt, new LinearLayout.LayoutParams(dp(44), dp(44)));
    LinearLayout labels = column();
    labels.setPadding(dp(12), 0, dp(8), 0);
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
    miniPlay = icon("play", "재생 또는 일시정지", this::toggle);
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
    if (player.isPlaying()) player.pause();
    else {
      if (player.getMediaItemCount() == 0 && !library.tracks.isEmpty()) play(library.tracks, 0);
      else {
        player.prepare();
        player.play();
      }
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
    new SheetDialog.Builder(this)
        .setTitle(t.title)
        .setItems(
            choices.toArray(new String[0]),
            (d, w) -> {
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
    input.setSingleLine();
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
              options.inSampleSize = 16;
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
    boolean wide = expanded();
    LinearLayout top = row();
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
    Space topBalance = new Space(this);
    top.addView(topBalance, new LinearLayout.LayoutParams(dp(48), dp(48)));
    root.addView(top);
    space(root, 8);
    LinearLayout stage = wide ? row() : column();
    stage.setGravity(Gravity.TOP);
    root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout left = column();
    stage.addView(
        left,
        wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, -1));
    if (wide) {
      Space gap = new Space(this);
      stage.addView(gap, new LinearLayout.LayoutParams(dp(32), 1));
      LinearLayout right = column();
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
      if (showPhoneLyrics) {
        LinearLayout pane = column();
        left.addView(pane, new LinearLayout.LayoutParams(-1, 0, 1));
        buildLyricsPanel(pane, t);
      } else {
        buildArtwork(left, t);
      }
      buildTransport(left, t);
    }
    LinearLayout tools = row();
    tools.setGravity(Gravity.CENTER);
    TextView lyricsToggle = icon("lyrics", tr("가사"), () -> {
      showPhoneLyrics = !showPhoneLyrics;
      render();
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
    pane.setPadding(dp(8), 0, dp(8), 0);
    LinearLayout head = row();
    lyricLabel = text(tr("가사"), 16, muted);
    head.addView(new Space(this), new LinearLayout.LayoutParams(0, dp(36), 1));
    head.addView(
        icon(
            "more",
            "가사 검색 · 편집 · 내보내기",
            () -> {
              if (current() != null) lyricsMenu(current());
            }));
    pane.addView(head);
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
      if (t != null) lyricContent.addView(button(tr("가사 가져오기"), () -> autoLyrics(t)));
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
    String title = t == null ? "음악을 선택하세요" : t.title, artist = t == null ? "Cool Player" : t.artist;
    boolean playing = player.isPlaying();
    if (mini != null) {
      miniTitle.setText(title);
      miniArtist.setText(artist);
      setIcon(miniPlay, playing ? "pause" : "play", ink, 24);
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
    TextView summary = text(tr("곡을 누르면 재생 · 메뉴에서 순서 변경"), 12, muted);
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
                      | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
              if (w == 0) {
                in.setType("audio/*");
                in.addCategory(Intent.CATEGORY_OPENABLE);
                in.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
              }
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
                  u, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
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
                      boolean exists = false;
                      for (Library.Track saved : library.tracks)
                        if (saved.uri.equals(incoming.uri)) {
                          exists = true;
                          if (saved.lyrics.isEmpty()) saved.lyrics = incoming.lyrics;
                          if (!incoming.id.equals(saved.id) && !incoming.art.isEmpty())
                            new File(incoming.art).delete();
                          break;
                        }
                      if (!exists) {
                        library.tracks.add(incoming);
                        added++;
                      }
                    }
                    library.save();
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
                runOnUiThread(() -> applyLyrics(t, raw));
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

  void autoLyrics(Library.Track t) {
    toast("곡명과 가수로 가사를 찾고 있습니다");
    io.execute(
        () -> {
          try {
            String raw = Lyrics.automatic(t);
            runOnUiThread(() -> applyLyrics(t, raw));
          } catch (Exception e) {
            toast(e.getMessage() == null ? "가사를 가져오지 못했습니다" : e.getMessage());
          }
        });
  }

  void lyricsMenu(Library.Track t) {
    new SheetDialog.Builder(this)
        .setTitle("가사")
        .setItems(
            new String[] {
              "자동으로 가져와 저장", "곡명·가수 직접 검색", "LRC / TXT 파일 불러오기", "가사 직접 편집", "가사 파일 내보내기", "가사 글자 크기", "가사 타이밍 조절"
            },
            (d, w) -> {
              if (w == 0) autoLyrics(t);
              else if (w == 1) searchLyrics(t);
              else if (w == 2) {
                lyricTrackId = t.id;
                Intent in =
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
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
    title.setText(t.title);
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
    group.setBackground(shape(card, 18));
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
    row.setOnClickListener(v -> action.run());
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

  void chooseLanguage() {
    String[] codes = {"system", "ko", "en", "ja"};
    int selected = Arrays.asList(codes).indexOf(getSharedPreferences("settings", 0).getString("language", "system"));
    new SheetDialog.Builder(this).setTitle("언어")
        .setSingleChoiceItems(new String[]{tr("기기 설정"), "한국어", "English", "日本語"}, selected, (d, w) -> {
          getSharedPreferences("settings", 0).edit().putString("language", codes[w]).apply();
          d.dismiss(); render();
        }).show();
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
    settingRow(appearance, "album", tr("언어"), getSharedPreferences("settings", 0).getString("language", "system"), this::chooseLanguage);
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
    settingRow(
        appearance,
        "lyrics",
        "가사 자동 스크롤",
        prefs.getBoolean("follow", true) ? "켬" : "끔",
        () ->
            new SheetDialog.Builder(this)
                .setTitle("가사 자동 스크롤")
                .setSingleChoiceItems(
                    new String[] {"켜기", "끄기"},
                    prefs.getBoolean("follow", true) ? 0 : 1,
                    (d, w) -> {
                      prefs.edit().putBoolean("follow", w == 0).apply();
                      d.dismiss();
                      render();
                    })
                .show());
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
    settingRow(
        audio,
        "settings",
        "기기 이퀄라이저",
        "",
        () -> {
          Intent eq =
              new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                  .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                  .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                  .putExtra(
                      AudioEffect.EXTRA_AUDIO_SESSION,
                      getSharedPreferences("playback", 0).getInt("audioSession", 0));
          try {
            startActivity(eq);
          } catch (ActivityNotFoundException e) {
            toast("이 기기에는 시스템 이퀄라이저가 없습니다");
          }
        });
    LinearLayout libraryGroup = settingsGroup(stack, "보관함과 홈 화면");
    settingRow(libraryGroup, "add", "음악 가져오기", "", this::importMenu);
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
          if (widgets.isRequestPinAppWidgetSupported())
            widgets.requestPinAppWidget(new ComponentName(this, PlayerWidget.class), null, null);
          else toast("홈 화면을 길게 눌러 Cool Player 위젯을 선택하세요");
        });
    LinearLayout updateGroup = settingsGroup(stack, "앱 업데이트");
    settingRow(
        updateGroup, "repeat", "업데이트 확인", "현재 " + updates.version(), () -> updates.check(true));
    settingRow(
        updateGroup,
        "settings",
        "새 버전 자동 확인",
        updates.automatic() ? "켬" : "끔",
        () -> {
          updates.toggleAutomatic();
          render();
        });
    if (new java.io.File(getCacheDir(), "updates/Cool-Player-Android.apk").exists())
      settingRow(updateGroup, "download", "다운로드한 업데이트 설치", "", () -> updates.install());
    LinearLayout about = settingsGroup(stack, "Cool Player");
    settingRow(
        about,
        "music",
        "앱 정보",
        updates.version(),
        () ->
            new SheetDialog.Builder(this)
                .setTitle("Cool Player " + updates.version())
                .setMessage(
                    "기기에 담긴 음악과 가사를 함께 듣는 플레이어\n\n"
                        + "FLAC · WAV · MP3 · AAC · Ogg · Opus\n\n"
                        + "ALAC는 기기 디코더 지원에 따라 달라지며 실제 출력 형식은 연결된 장치에 따라 달라집니다.")
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
                    "음원은 기기에서 재생됩니다. 가사 검색 시 곡명과 가수를 Bugs와 LRCLIB로 전송합니다.\n\n"
                        + "선택한 앨범아트는 앱 보관함에 저장되며 원본 음원 파일에는 쓰지 않습니다. 이미지 주소로 가져오기는 입력한 주소에"
                        + " 접속합니다.\n\n"
                        + "앱 삭제 시 보관함, 가사, 변경한 앨범아트가 함께 삭제됩니다.")
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
