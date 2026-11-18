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
    Intent pick = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            new ComponentName(this, VizWallpaper.class));
    try {
      startActivity(pick);
    } catch (ActivityNotFoundException e) {
      try {
        startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
      } catch (ActivityNotFoundException none) {
        Host.js(web, "note('This tablet has no live wallpaper picker')");
      }
    }
  }

  @Override public void close() {
    finish();
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
