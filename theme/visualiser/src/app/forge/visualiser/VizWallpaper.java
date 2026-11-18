/* Forge Visualiser as a live wallpaper. A wallpaper is a bare surface, so
   the visualiser page runs in a WebView inside a Presentation on a private
   virtual display that draws straight onto that surface. While the home
   screen is hidden the page, the sound and the frames all stop; the frame
   rate follows the battery rules in Pacer (30 by default, 15 on Battery
   Saver, a still picture under 15%). If this tablet will not show a
   Presentation there, the wallpaper says so in plain words instead. */
package app.forge.visualiser;

import android.app.Presentation;
import android.app.WallpaperColors;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.webkit.WebView;
import app.forge.look.Pacer;
import app.forge.look.Sound;

public final class VizWallpaper extends WallpaperService {
  @Override public Engine onCreateEngine() {
    return new Look();
  }

  final class Look extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Sound sound = new Sound();
    private Pacer pacer;
    private VirtualDisplay display;
    private Presentation stage;
    private WebView web;
    private int width, height;
    private boolean shown, failed;

    @Override public void onCreate(SurfaceHolder holder) {
      super.onCreate(holder);
      pacer = new Pacer(VizWallpaper.this);
      pacer.setWanted(Prefs.fps(VizWallpaper.this, true));
      Prefs.store(VizWallpaper.this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
      super.onSurfaceChanged(holder, format, w, h);
      width = w;
      height = h;
      if (failed) {
        still(holder);
      } else if (display == null) {
        open(holder);
      } else {
        display.resize(w, h, dpi());
        display.setSurface(holder.getSurface());
      }
    }

    @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
      pause();
      if (display != null) display.setSurface(null);
      super.onSurfaceDestroyed(holder);
    }

    @Override public void onVisibilityChanged(boolean visible) {
      shown = visible;
      if (visible) resume();
      else pause();
    }

    private int dpi() {
      return getResources().getDisplayMetrics().densityDpi;
    }

    private void open(SurfaceHolder holder) {
      DisplayManager displays = getSystemService(DisplayManager.class);
      try {
        // flags 0: a private display only this app draws on
        display = displays.createVirtualDisplay("forge-visualiser", width, height, dpi(),
            holder.getSurface(), 0);
        stage = new Presentation(VizWallpaper.this, display.getDisplay());
        if (Build.VERSION.SDK_INT < 30) {
          // newer Android picks this type itself for a private display
          stage.getWindow().setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION);
        }
        web = Host.make(stage.getContext(), "wallpaper",
            new Bridge(VizWallpaper.this, sound, pacer, null, true), this::reopen);
        stage.setContentView(web);
        stage.show();
        if (shown || isVisible()) {
          shown = true;
          resume();
        } else {
          pause();
        }
      } catch (RuntimeException e) {
        Log.w("ForgeViz", "wallpaper could not start the page", e);
        close();
        failed = true;
        still(holder);
      }
    }

    /** The page's renderer was stopped by the system: build it again. */
    private void reopen() {
      close();
      SurfaceHolder holder = getSurfaceHolder();
      if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
        open(holder);
      }
    }

    private void resume() {
      if (web == null) return;
      web.onResume();
      sound.start(Prefs.source(VizWallpaper.this), Prefs.micAllowed(VizWallpaper.this));
      Host.js(web, "resume()");
    }

    private void pause() {
      sound.stop();
      if (web == null) return;
      Host.js(web, "pause()");
      web.onPause();
    }

    private void close() {
      sound.stop();
      if (stage != null) {
        try {
          stage.dismiss();
        } catch (RuntimeException ignored) {
          // its display may be gone already
        }
      }
      if (web != null) web.destroy();
      if (display != null) display.release();
      stage = null;
      web = null;
      display = null;
    }

    /** One plain picture saying the visualiser cannot run as a wallpaper here. */
    private void still(SurfaceHolder holder) {
      Canvas c = null;
      try {
        c = holder.lockCanvas();
        if (c == null) return;
        c.drawColor(Color.rgb(5, 7, 11));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(214, 232, 255));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f,
            getResources().getDisplayMetrics()));
        c.drawText("Forge Visualiser cannot run as a wallpaper on this tablet.",
            c.getWidth() / 2f, c.getHeight() / 2f, p);
        p.setColor(Color.rgb(138, 180, 255));
        c.drawText("Open the Forge Visualiser app for full screen.",
            c.getWidth() / 2f, c.getHeight() / 2f + p.getTextSize() * 1.8f, p);
      } catch (RuntimeException e) {
        Log.w("ForgeViz", "wallpaper could not draw its note", e);
      } finally {
        if (c != null) holder.unlockCanvasAndPost(c);
      }
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
      if (Prefs.WALL_FPS.equals(key)) pacer.setWanted(Prefs.fps(VizWallpaper.this, true));
      if (Prefs.SOURCE.equals(key) && shown && web != null) {
        sound.start(Prefs.source(VizWallpaper.this), Prefs.micAllowed(VizWallpaper.this));
      }
      Host.js(web, "prefsChanged()");
      if (Prefs.WALL.equals(key) && Build.VERSION.SDK_INT >= 27) notifyColorsChanged();
    }

    /** The look's colours, so Android's own colours follow the wallpaper. */
    @Override public WallpaperColors onComputeColors() {
      if (Build.VERSION.SDK_INT < 27) return null;
      Presets.Preset p = Presets.find(VizWallpaper.this, Prefs.get(VizWallpaper.this, Prefs.WALL));
      if (p == null) return null;
      return new WallpaperColors(Color.valueOf(p.primary), Color.valueOf(p.secondary),
          Color.valueOf(p.background));
    }

    @Override public void onDestroy() {
      Prefs.store(VizWallpaper.this).unregisterOnSharedPreferenceChangeListener(this);
      close();
      super.onDestroy();
    }
  }
}
