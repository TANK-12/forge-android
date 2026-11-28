/* Forge Visualiser as a live wallpaper. Runs the visualiser page in a WebView
   inside a Presentation on a private virtual display, AND automatically falls
   back to NativeVizScene (direct SurfaceHolder Canvas via SceneLoop) whenever
   a tablet cannot run VirtualDisplay/Presentation or WebView does not paint a
   frame (Relay task 1591). Battery: pauses rendering and stops audio capture
   immediately on visibility loss or ACTION_SCREEN_OFF, and follows Pacer caps
   (30 fps default, 15 on Battery Saver/thermal, 0 still under 15% battery). */
package app.forge.visualiser;

import android.app.Presentation;
import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.webkit.WebView;
import app.forge.look.Pacer;
import app.forge.look.SceneLoop;
import app.forge.look.Sound;
import java.util.concurrent.atomic.AtomicInteger;

public final class VizWallpaper extends WallpaperService {
  static final long WEBVIEW_WATCHDOG_MS = 5000L;

  @Override public Engine onCreateEngine() {
    return new Look();
  }

  final class Look extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Sound sound = new Sound();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger webFrames = new AtomicInteger();
    private AudioManager audio;
    private PowerManager power;
    private Pacer pacer;
    private VirtualDisplay display;
    private Presentation stage;
    private WebView web;
    private NativeVizScene nativeScene;
    private SceneLoop nativeLoop;
    private int width, height;
    private boolean shown, screenOn = true, usingNative;
    private int webCrashes;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
          screenOn = false;
          if (nativeLoop != null) nativeLoop.screenOn(false);
          pauseWeb();
        } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
          screenOn = true;
          if (nativeLoop != null) nativeLoop.screenOn(true);
          if (shown) resumeWeb();
        }
      }
    };

    private final Runnable watchdog = () -> {
      if (!shown || !screenOn || usingNative || web == null) return;
      if (webFrames.get() == 0) {
        Log.w("ForgeViz", "WebView wallpaper produced 0 frames in 5s; switching to Native Canvas");
        switchToNative("WebView produced no frames on this device's virtual display");
      }
    };

    @Override public void onCreate(SurfaceHolder holder) {
      super.onCreate(holder);
      audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
      power = (PowerManager) getSystemService(Context.POWER_SERVICE);
      screenOn = power == null || power.isInteractive();
      pacer = new Pacer(VizWallpaper.this);
      pacer.setWanted(Prefs.fps(VizWallpaper.this, true));
      Prefs.store(VizWallpaper.this).registerOnSharedPreferenceChangeListener(this);
      IntentFilter filter = new IntentFilter();
      filter.addAction(Intent.ACTION_SCREEN_OFF);
      filter.addAction(Intent.ACTION_SCREEN_ON);
      if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        registerReceiver(screenReceiver, filter);
      }
    }

    @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
      super.onSurfaceChanged(holder, format, w, h);
      width = w;
      height = h;
      if (w <= 0 || h <= 0) return;
      if (usingNative && nativeLoop != null) {
        nativeLoop.surface(w, h);
        return;
      }
      if ("native".equals(Prefs.get(VizWallpaper.this, Prefs.WALL_ENGINE))) {
        startNative(holder, "Lightweight native canvas (selected in settings)");
        return;
      }
      if (usingNative) {
        startNative(holder, "Native canvas fallback");
      } else if (display == null) {
        openWeb(holder);
      } else {
        try {
          display.resize(w, h, dpi());
          display.setSurface(holder.getSurface());
        } catch (RuntimeException e) {
          Log.w("ForgeViz", "VirtualDisplay resize failed", e);
          switchToNative("VirtualDisplay resize failed: " + e.getClass().getSimpleName());
        }
      }
    }

    @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
      main.removeCallbacks(watchdog);
      pauseWeb();
      if (display != null) {
        try {
          display.setSurface(null);
        } catch (RuntimeException ignored) {
        }
      }
      if (nativeLoop != null) nativeLoop.surfaceGone();
      super.onSurfaceDestroyed(holder);
    }

    @Override public void onVisibilityChanged(boolean visible) {
      shown = visible;
      boolean interactive = power == null || power.isInteractive();
      screenOn = interactive;
      if (nativeLoop != null) {
        nativeLoop.screenOn(screenOn);
        nativeLoop.visible(visible);
        return;
      }
      if (visible && screenOn) {
        resumeWeb();
      } else {
        pauseWeb();
      }
    }

    private int dpi() {
      int d = getResources().getDisplayMetrics().densityDpi;
      return d > 0 ? d : 320;
    }

    private void openWeb(SurfaceHolder holder) {
      DisplayManager displays = getSystemService(DisplayManager.class);
      if (displays == null || holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
        startNative(holder, "No display service or surface for WebView");
        return;
      }
      try {
        display = displays.createVirtualDisplay("forge-visualiser", width, height, dpi(),
            holder.getSurface(), 0);
        if (display == null || display.getDisplay() == null) {
          throw new IllegalStateException("VirtualDisplay returned null");
        }
        Context stageCtx = presentationContext(display.getDisplay());
        stage = new Presentation(stageCtx, display.getDisplay());
        if (Build.VERSION.SDK_INT < 30 && stage.getWindow() != null) {
          stage.getWindow().setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION);
        }
        webFrames.set(0);
        Bridge bridge = new Bridge(VizWallpaper.this, sound, pacer, null, true, webFrames::incrementAndGet);
        web = Host.make(stage.getContext(), "wallpaper", bridge, this::onWebRendererGone);
        stage.setContentView(web);
        stage.show();
        Prefs.recordWallStatus(VizWallpaper.this, "WebView live wallpaper active");
        if ((shown || isVisible()) && screenOn) {
          shown = true;
          resumeWeb();
        } else {
          pauseWeb();
        }
      } catch (RuntimeException e) {
        Log.w("ForgeViz", "WebView wallpaper could not start; using Native Canvas fallback", e);
        closeWeb();
        startNative(holder, "Native canvas fallback (" + e.getClass().getSimpleName() + ")");
      }
    }

    /** Display-bound context avoids BadTokenException on strict OEM Android 11-15 tablets. */
    private Context presentationContext(android.view.Display virtualDisplay) {
      try {
        Context dc = createDisplayContext(virtualDisplay);
        if (Build.VERSION.SDK_INT >= 30) {
          return dc.createWindowContext(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION, null);
        }
        return dc;
      } catch (RuntimeException e) {
        return VizWallpaper.this;
      }
    }

    private void onWebRendererGone() {
      webCrashes++;
      if (webCrashes >= 2) {
        switchToNative("WebView renderer stopped repeatedly");
        return;
      }
      closeWeb();
      SurfaceHolder holder = getSurfaceHolder();
      if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
        openWeb(holder);
      }
    }

    private void switchToNative(String reason) {
      closeWeb();
      SurfaceHolder holder = getSurfaceHolder();
      if (holder != null && width > 0 && height > 0) {
        startNative(holder, "Native canvas fallback (" + reason + ")");
      }
    }

    private void startNative(SurfaceHolder holder, String reason) {
      closeWeb();
      usingNative = true;
      if (nativeLoop == null && holder != null) {
        nativeScene = new NativeVizScene(VizWallpaper.this, sound, pacer);
        nativeLoop = new SceneLoop(holder, nativeScene, pacer);
      }
      Prefs.recordWallStatus(VizWallpaper.this, reason);
      if (nativeLoop != null) {
        nativeLoop.surface(width, height);
        nativeLoop.screenOn(screenOn);
        nativeLoop.visible(shown || isVisible());
      }
    }

    private void stopNative() {
      if (nativeLoop != null) {
        nativeLoop.quitAndWait(500);
        nativeLoop = null;
      }
      nativeScene = null;
      usingNative = false;
    }

    private void resumeWeb() {
      if (web == null || !screenOn) return;
      web.onResume();
      sound.start(Prefs.source(VizWallpaper.this), Prefs.micAllowed(VizWallpaper.this),
          () -> audio != null && audio.isMusicActive());
      Host.js(web, "resume()");
      main.removeCallbacks(watchdog);
      if (webFrames.get() == 0) main.postDelayed(watchdog, WEBVIEW_WATCHDOG_MS);
    }

    private void pauseWeb() {
      main.removeCallbacks(watchdog);
      if (web == null) return;
      sound.stop();
      Host.js(web, "pause()");
      web.onPause();
    }

    private void closeWeb() {
      main.removeCallbacks(watchdog);
      if (web != null || display != null || stage != null) sound.stop();
      if (stage != null) {
        try {
          stage.dismiss();
        } catch (RuntimeException ignored) {
          // its display may be gone already
        }
      }
      if (web != null) {
        try {
          web.destroy();
        } catch (RuntimeException ignored) {
        }
      }
      if (display != null) {
        try {
          display.release();
        } catch (RuntimeException ignored) {
        }
      }
      stage = null;
      web = null;
      display = null;
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
      if (Prefs.WALL_STATUS.equals(key)) return;
      if (Prefs.WALL_FPS.equals(key)) pacer.setWanted(Prefs.fps(VizWallpaper.this, true));
      if (Prefs.WALL_ENGINE.equals(key)) {
        boolean wantNative = "native".equals(Prefs.get(VizWallpaper.this, Prefs.WALL_ENGINE));
        SurfaceHolder holder = getSurfaceHolder();
        if (wantNative && !usingNative) {
          startNative(holder, "Lightweight native canvas (selected in settings)");
          return;
        } else if (!wantNative && usingNative && holder != null && width > 0 && height > 0) {
          stopNative();
          openWeb(holder);
          return;
        }
      }
      if (usingNative && nativeScene != null && nativeLoop != null) {
        nativeLoop.post(nativeScene::settingsChanged);
        nativeLoop.redraw();
      } else {
        if (Prefs.SOURCE.equals(key) && shown && screenOn && web != null) {
          sound.start(Prefs.source(VizWallpaper.this), Prefs.micAllowed(VizWallpaper.this),
              () -> audio != null && audio.isMusicActive());
        }
        Host.js(web, "prefsChanged()");
      }
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
      try {
        unregisterReceiver(screenReceiver);
      } catch (RuntimeException ignored) {
      }
      stopNative();
      closeWeb();
      super.onDestroy();
    }
  }
}
