/* Forge Theme's screen: a live preview of the wallpaper and every setting
   beside it (below it on a phone held upright). Set as wallpaper opens
   Android's own wallpaper screen with Forge particles picked; the widgets
   can be added straight from here on home screens that allow it. */
package app.forge.theme;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import app.forge.look.SceneLoop;
import app.forge.look.Themes;
import java.util.List;

public final class ThemeActivity extends Activity {
  private static final int PICK = 7, MIC = 8;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Runnable widgetsLater = () -> Widgets.refreshAll(this);
  private ThemeScene scene;
  private SceneLoop loop;
  private LinearLayout controls;
  private TextView soundNote, fpsNote;
  private Themes.Theme theme;
  private float dp;

  @Override protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    dp = getResources().getDisplayMetrics().density;
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    scene = new ThemeScene(this);
    build();
  }

  private void build() {
    Prefs p = Prefs.read(this);
    theme = p.theme(this);
    boolean wide = getResources().getConfiguration().screenWidthDp
        > getResources().getConfiguration().screenHeightDp;
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    root.setBackgroundColor(theme.bg);
    int pad = px(16);
    root.setPadding(pad, pad + px(12), pad, pad);

    SurfaceView preview = new SurfaceView(this);
    preview.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View v, Outline o) {
        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(22));
      }
    });
    preview.setClipToOutline(true);
    LinearLayout.LayoutParams pl = wide
        ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f)
        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.9f);
    root.addView(preview, pl);
    if (loop != null) loop.quit();
    loop = new SceneLoop(preview.getHolder(), scene, scene.pacer);
    preview.getHolder().addCallback(new SurfaceHolder.Callback() {
      @Override public void surfaceCreated(SurfaceHolder h) {}

      @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        loop.surface(w, hh);
        loop.visible(true);
      }

      @Override public void surfaceDestroyed(SurfaceHolder h) {
        loop.visible(false);
        loop.surfaceGone();
      }
    });
    preview.setOnTouchListener((v, e) -> {
      android.view.MotionEvent copy = android.view.MotionEvent.obtain(e);
      loop.post(() -> {
        scene.touch(copy);
        copy.recycle();
      });
      return true;
    });

    ScrollView scroll = new ScrollView(this);
    controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.VERTICAL);
    controls.setPadding(wide ? px(20) : 0, wide ? 0 : px(14), 0, px(24));
    scroll.addView(controls);
    root.addView(scroll, wide
        ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f));
    fill(p);
    setContentView(root);
  }

  private void fill(Prefs p) {
    controls.removeAllViews();
    TextView title = text(String.valueOf(getTitle()), 26, theme.text); // its label: Forge Theme, or Wallpaper and widgets inside Forge
    controls.addView(title);
    controls.addView(text(ours() ? "Your home screen wallpaper now. Changes show at once."
        : "Forge particles over your home screen, on every page.", 14, theme.muted));
    TextView set = button(ours() ? "Set again or change where it shows" : "Set as wallpaper", true);
    set.setOnClickListener(v -> setWallpaper());
    controls.addView(set, spaced());

    section("Theme");
    LinearLayout row = new LinearLayout(this);
    for (Themes.Theme t : Themes.all(this)) {
      TextView chip = chip(t.label, t.id.equals(theme.id), t.accent);
      chip.setOnClickListener(v -> save(Prefs.THEME, t.id, true));
      row.addView(chip);
    }
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    hs.addView(row);
    controls.addView(hs);

    section("Picture");
    Flow pics = new Flow(this);
    String pic = p.picture;
    pics.addView(choice(theme.wall.isEmpty() ? "Theme colours" : "The theme's picture",
        "theme".equals(pic), v -> save(Prefs.PICTURE, "theme", false)));
    pics.addView(choice(pic.startsWith("content:") ? "Your picture (change)" : "A picture from this device",
        pic.startsWith("content:"), v -> pickPicture()));
    pics.addView(choice("Just colours", "none".equals(pic), v -> save(Prefs.PICTURE, "none", false)));
    controls.addView(pics);
    slider("Dim the picture", 0f, 0.7f, p.dim, Prefs.DIM);

    section("Particles");
    Flow kinds = new Flow(this);
    kinds.addView(choice("Match the theme", "theme".equals(p.kind),
        v -> save(Prefs.KIND, "theme", false)));
    List<Themes.Kind> list = Themes.kinds(this);
    for (Themes.Kind k : list) {
      kinds.addView(choice(k.label, k.key.equals(p.kind), v -> save(Prefs.KIND, k.key, false)));
    }
    kinds.addView(choice("Off", "off".equals(p.kind), v -> save(Prefs.KIND, "off", false)));
    controls.addView(kinds);
    slider("Amount", 0.25f, 2.5f, p.amount, Prefs.AMOUNT);
    slider("Size", 0.5f, 2f, p.size, Prefs.SIZE);
    slider("Speed", 0.25f, 3f, p.speed, Prefs.SPEED);
    Flow taps = new Flow(this);
    taps.addView(choice("Taps make sparks", p.taps, v -> saveBool(Prefs.TAPS, true)));
    taps.addView(choice("No sparks", !p.taps, v -> saveBool(Prefs.TAPS, false)));
    controls.addView(taps);

    section("Dance to music");
    Flow sound = new Flow(this);
    sound.addView(choice("Off", "off".equals(p.sound), v -> save(Prefs.SOUND, "off", false)));
    sound.addView(choice("This device's own sound", "phone".equals(p.sound), v -> wantSound("phone")));
    sound.addView(choice("The microphone", "mic".equals(p.sound), v -> wantSound("mic")));
    sound.addView(choice("Demo beat", "demo".equals(p.sound), v -> save(Prefs.SOUND, "demo", false)));
    controls.addView(sound);
    soundNote = text("", 13, theme.muted);
    controls.addView(soundNote);

    section("Frame rate");
    Flow fps = new Flow(this);
    for (int f : new int[] {15, 20, 30, 45, 60, 90, 120}) {
      fps.addView(choice(f + " a second", p.fps == f, v -> saveInt(Prefs.FPS, f)));
    }
    controls.addView(fps);
    fpsNote = text("", 13, theme.muted);
    controls.addView(fpsNote);
    controls.addView(text("It draws only while the home screen is in view, never with the screen "
        + "off; 15 frames a second on Battery Saver or when the tablet is hot, and a still picture "
        + "under 15% battery.", 13, theme.muted));

    section("Widgets");
    controls.addView(text("Long-press the home screen, Widgets, Forge Theme; or add them here.",
        13, theme.muted));
    Flow widgets = new Flow(this);
    widgets.addView(choice("Add the clock", false, v -> pin(Widgets.ClockWidget.class)));
    widgets.addView(choice("Add particles", false, v -> pin(Widgets.ParticlesWidget.class)));
    widgets.addView(choice("Add music keys", false, v -> pin(Widgets.MusicWidget.class)));
    controls.addView(widgets);
    notes();
  }

  private void notes() {
    if (soundNote == null) return;
    soundNote.setText(scene.sound.status());
    String why = scene.pacer.why();
    fpsNote.setText(why);
    fpsNote.setVisibility(why.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private boolean ours() {
    WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
    // the class too: inside the main Forge app the visualiser wallpaper shares the package
    return info != null && getPackageName().equals(info.getPackageName())
        && ThemeWallpaper.class.getName().equals(info.getServiceName());
  }

  private void setWallpaper() {
    // task 1330: the tablet's own wallpaper screen, however its maker named it
    // it says where to tap there itself, by the tablet's own names (task 1334)
    int opened = app.forge.look.LiveWall.open(this, new ComponentName(this, ThemeWallpaper.class)).kind;
    if (opened != app.forge.look.LiveWall.NONE) return;
    // no live wallpaper screen Forge can open: say where the still picture is
    Toast.makeText(this, "On this tablet the theme goes on as a picture: Forge, Wallpapers, Still",
        Toast.LENGTH_LONG).show();
  }

  private void pickPicture() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("image/*");
    try {
      startActivityForResult(i, PICK);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this, "No picture picker on this tablet", Toast.LENGTH_LONG).show();
    }
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    try {
      getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (SecurityException e) {
      Toast.makeText(this, "That picture cannot be kept; pick it from the Files app", Toast.LENGTH_LONG).show();
      return;
    }
    save(Prefs.PICTURE, uri.toString(), false);
  }

  private void wantSound(String mode) {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      Prefs.store(this).edit().putString(Prefs.SOUND, mode).apply();
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MIC);
      return;
    }
    save(Prefs.SOUND, mode, false);
  }

  @Override public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
    super.onRequestPermissionsResult(request, perms, results);
    changed(false);
  }

  private void pin(Class<?> provider) {
    AppWidgetManager m = getSystemService(AppWidgetManager.class);
    if (m != null && m.isRequestPinAppWidgetSupported()) {
      m.requestPinAppWidget(new ComponentName(this, provider), null, null);
    } else {
      Toast.makeText(this, "Long-press the home screen, then Widgets, Forge Theme",
          Toast.LENGTH_LONG).show();
    }
  }

  private void save(String key, String value, boolean rebuild) {
    Prefs.store(this).edit().putString(key, value).apply();
    changed(rebuild);
  }

  private void saveInt(String key, int value) {
    Prefs.store(this).edit().putInt(key, value).apply();
    changed(false);
  }

  private void saveBool(String key, boolean value) {
    Prefs.store(this).edit().putBoolean(key, value).apply();
    changed(false);
  }

  private void changed(boolean rebuild) {
    loop.post(scene::settingsChanged);
    loop.redraw();
    ui.removeCallbacks(widgetsLater);
    ui.postDelayed(widgetsLater, 800);
    if (rebuild) {
      build();
    } else {
      int y = ((ScrollView) controls.getParent()).getScrollY();
      fill(Prefs.read(this));
      controls.post(() -> ((ScrollView) controls.getParent()).scrollTo(0, y));
    }
    ui.postDelayed(this::notes, 600);
  }

  private void slider(String label, float min, float max, float now, String key) {
    TextView name = text(label + "  " + fmt(now), 14, theme.text);
    controls.addView(name);
    SeekBar bar = new SeekBar(this);
    bar.setMax(100);
    bar.setProgress(Math.round((now - min) / (max - min) * 100));
    bar.getProgressDrawable().setTint(theme.accent);
    bar.getThumb().setTint(theme.accent);
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
        float val = min + (max - min) * v / 100f;
        name.setText(label + "  " + fmt(val));
        if (user) {
          Prefs.store(ThemeActivity.this).edit().putFloat(key, val).apply();
          loop.post(scene::settingsChanged);
          loop.redraw();
        }
      }

      @Override public void onStartTrackingTouch(SeekBar s) {}

      @Override public void onStopTrackingTouch(SeekBar s) {
        ui.removeCallbacks(widgetsLater);
        ui.postDelayed(widgetsLater, 800);
      }
    });
    controls.addView(bar);
  }

  private static String fmt(float v) {
    return String.format(java.util.Locale.ROOT, "%.2f", v);
  }

  private void section(String label) {
    TextView t = text(label, 17, theme.text);
    t.setPadding(0, px(18), 0, px(6));
    controls.addView(t);
  }

  private TextView text(String s, int sp, int colour) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setTextColor(colour);
    return t;
  }

  private TextView button(String label, boolean primary) {
    TextView b = text(label, 16, primary ? (Themes.luminance(theme.accent) > 0.6f ? 0xff101418 : 0xffffffff) : theme.text);
    b.setGravity(Gravity.CENTER);
    b.setPadding(px(18), px(12), px(18), px(12));
    GradientDrawable g = new GradientDrawable();
    g.setCornerRadius(px(14));
    g.setColor(primary ? theme.accent : theme.panel);
    b.setBackground(g);
    return b;
  }

  private TextView chip(String label, boolean on, int dot) {
    TextView c = text(label, 14, on ? theme.text : theme.muted);
    c.setPadding(px(14), px(8), px(14), px(8));
    GradientDrawable g = new GradientDrawable();
    g.setCornerRadius(px(18));
    g.setColor(on ? (theme.accent & 0x00ffffff) | 0x40000000 : theme.panel);
    g.setStroke(px(on ? 2 : 1), on ? theme.accent : (dot != 0 ? dot : theme.muted & 0x40ffffff));
    c.setBackground(g);
    ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, px(4), px(8), px(4));
    c.setLayoutParams(lp);
    return c;
  }

  private TextView choice(String label, boolean on, View.OnClickListener click) {
    TextView c = chip(label, on, 0);
    c.setOnClickListener(click);
    return c;
  }

  private LinearLayout.LayoutParams spaced() {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, px(14), 0, px(4));
    return lp;
  }

  private int px(float v) {
    return Math.round(v * dp);
  }

  @Override protected void onResume() {
    super.onResume();
    String said = app.forge.look.LiveWall.back(this); // task 1334: on now, or where to tap again
    if (said != null) Toast.makeText(this, said, Toast.LENGTH_LONG).show();
    if (loop != null) loop.visible(true);
    notes();
    Widgets.MusicWidget.refresh(this);
  }

  // turning the tablet: side by side when wide, stacked when tall
  @Override public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    build();
  }

  @Override protected void onPause() {
    if (loop != null) loop.visible(false);
    super.onPause();
  }

  @Override protected void onDestroy() {
    if (loop != null) {
      loop.post(scene::release);
      loop.quit();
    }
    super.onDestroy();
  }

  /** Lays chips out in rows, wrapping to the next row when one is full. */
  static final class Flow extends ViewGroup {
    Flow(Context c) {
      super(c);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
      int max = MeasureSpec.getSize(wSpec);
      int x = 0, y = 0, rowH = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View c = getChildAt(i);
        measureChildWithMargins(c, wSpec, 0, hSpec, 0);
        MarginLayoutParams lp = (MarginLayoutParams) c.getLayoutParams();
        int cw = c.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        int ch = c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
        if (x > 0 && x + cw > max) {
          x = 0;
          y += rowH;
          rowH = 0;
        }
        x += cw;
        rowH = Math.max(rowH, ch);
      }
      setMeasuredDimension(max, y + rowH);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int max = r - l, x = 0, y = 0, rowH = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View c = getChildAt(i);
        MarginLayoutParams lp = (MarginLayoutParams) c.getLayoutParams();
        int cw = c.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        int ch = c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
        if (x > 0 && x + cw > max) {
          x = 0;
          y += rowH;
          rowH = 0;
        }
        c.layout(x + lp.leftMargin, y + lp.topMargin, x + lp.leftMargin + c.getMeasuredWidth(),
            y + lp.topMargin + c.getMeasuredHeight());
        x += cw;
        rowH = Math.max(rowH, ch);
      }
    }

    @Override protected LayoutParams generateLayoutParams(LayoutParams p) {
      return new MarginLayoutParams(p);
    }

    @Override public LayoutParams generateLayoutParams(android.util.AttributeSet attrs) {
      return new MarginLayoutParams(getContext(), attrs);
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
      return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override protected boolean checkLayoutParams(LayoutParams p) {
      return p instanceof MarginLayoutParams;
    }
  }
}
