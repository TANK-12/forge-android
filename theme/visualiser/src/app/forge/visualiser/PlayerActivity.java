/* Forge Visualiser full screen: the page (assets/host) with its looks and
   settings, the sound read here while the player is on screen and stopped
   the moment it is not. Also the wallpaper's settings screen. */
package app.forge.visualiser;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebView;
import app.forge.look.Pacer;
import app.forge.look.Sound;

public final class PlayerActivity extends Activity implements Bridge.Actions {
  private static final int ASK_MIC = 7;
  private final Sound sound = new Sound();
  private Pacer pacer;
  private WebView web;
  private boolean onScreen;

  @Override protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    pacer = new Pacer(this);
    pacer.setWanted(Prefs.fps(this, false));
    build();
    // Widgets lose their buttons after Clear data; opening the player redraws them.
    VizWidget.refreshAll(this);
  }

  private void build() {
    web = Host.make(this, "player", new Bridge(this, sound, pacer, this, false), this::rebuild);
    setContentView(web);
  }

  /** The system stopped the page's renderer (low memory): start a new one. */
  private void rebuild() {
    WebView dead = web;
    build();
    if (dead != null) dead.destroy();
    if (onScreen) web.onResume();
  }

  @Override protected void onResume() {
    super.onResume();
    onScreen = true;
    fullScreen();
    web.onResume();
    listen();
    Host.js(web, "resume()");
  }

  @Override protected void onPause() {
    onScreen = false;
    Host.js(web, "pause()");
    web.onPause();
    sound.stop();
    super.onPause();
  }

  @Override protected void onDestroy() {
    sound.stop();
    if (web != null) web.destroy();
    web = null;
    super.onDestroy();
  }

  @Override public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (focus) fullScreen();
  }

  private void listen() {
    sound.start(Prefs.source(this), Prefs.micAllowed(this));
  }

  @Override public void source(String mode) {
    Prefs.store(this).edit().putString(Prefs.SOURCE, mode).commit();
    Sound.Mode want = Prefs.source(this);
    if ((want == Sound.Mode.PHONE || want == Sound.Mode.MIC) && !Prefs.micAllowed(this)) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
      return;
    }
    if (onScreen) listen();
    Host.js(web, "sourceChanged()");
  }

  @Override public void onRequestPermissionsResult(int code, String[] names, int[] results) {
    if (code != ASK_MIC) return;
    if (onScreen) listen();
    Host.js(web, "sourceChanged()");
  }

  @Override public void wallpaper(String presetId) {
    Prefs.store(this).edit().putString(Prefs.WALL, presetId).apply();
    VizWidget.refreshAll(this);
    // task 1330: the tablet's own wallpaper screen, however its maker named it
    int opened = app.forge.look.LiveWall.open(this, new ComponentName(this, VizWallpaper.class));
    String hint = app.forge.look.LiveWall.hint(opened, "Forge visualiser");
    if (hint != null) Host.js(web, "note(" + org.json.JSONObject.quote(hint) + ")");
    else if (opened == app.forge.look.LiveWall.NONE) {
      Host.js(web, "note('Moving wallpapers are off on this tablet: the visualiser plays here, full screen')");
    }
  }

  @Override public void close() {
    finish();
  }

  /** Ask Android for a display mode fast enough for the frame rate allowed
      (Relay task 1320): a 90 or 120 Hz screen often stays at 60 for a page
      nobody is touching. 60 or less leaves the choice to the system. */
  @Override public void rate(int fps) {
    Display.Mode mode = fps > 60 ? modeFor(screen(), fps) : null;
    WindowManager.LayoutParams lp = getWindow().getAttributes();
    int id = mode == null ? 0 : mode.getModeId();
    float hz = mode == null ? 0f : mode.getRefreshRate();
    if (lp.preferredDisplayModeId == id && lp.preferredRefreshRate == hz) return;
    lp.preferredDisplayModeId = id;
    lp.preferredRefreshRate = hz;
    getWindow().setAttributes(lp);
  }

  private Display screen() {
    return Build.VERSION.SDK_INT >= 30 ? getDisplay() : screenOld();
  }

  @SuppressWarnings("deprecation")
  private Display screenOld() {
    return getWindowManager().getDefaultDisplay();
  }

  /** The screen's mode at its present size with the lowest refresh rate that
      reaches fps (90 for 90 when it has one, else 120), or its fastest when
      none does; null when nothing beats 60 Hz. */
  static Display.Mode modeFor(Display display, int fps) {
    if (display == null) return null;
    Display.Mode now = display.getMode(), pick = null, fastest = null;
    for (Display.Mode m : display.getSupportedModes()) {
      if (m.getPhysicalWidth() != now.getPhysicalWidth()
          || m.getPhysicalHeight() != now.getPhysicalHeight()) continue;
      if (fastest == null || m.getRefreshRate() > fastest.getRefreshRate()) fastest = m;
      if (m.getRefreshRate() >= fps - 1f
          && (pick == null || m.getRefreshRate() < pick.getRefreshRate())) pick = m;
    }
    if (pick == null) pick = fastest;
    return pick != null && pick.getRefreshRate() > 61f ? pick : null;
  }

  private void fullScreen() {
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      WindowInsetsController bars = getWindow().getInsetsController();
      if (bars != null) {
        bars.hide(WindowInsets.Type.systemBars());
        bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      }
    } else {
      fullScreenOld();
    }
  }

  @SuppressWarnings("deprecation")
  private void fullScreenOld() {
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
  }
}
