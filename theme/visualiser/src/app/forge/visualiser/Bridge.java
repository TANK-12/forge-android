/* What the visualiser page may ask of Android, as window.ForgeAndroid. The
   page is the APK's own (Host serves only its assets), so this is the only
   way out of it. Called on the WebView's bridge thread: anything that
   touches the screen is posted to the main thread.

   frame() is the sound, one frame at a time, already in the shape the
   shared visualiser looks draw from (shared/visualiser/viz_core.js), packed
   as bytes in base64: 64 bands (0..255), 512 wave samples (128 = silence),
   then level, bass, mid, treble and beat (0..255) and silent (0 or 1). */
package app.forge.visualiser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import app.forge.look.MediaKeys;
import app.forge.look.Pacer;
import app.forge.look.Sound;

public final class Bridge {
  /** What only the full-screen player can do (the wallpaper passes null). */
  interface Actions {
    void source(String mode);

    void wallpaper(String presetId);

    void close();

    /** The frame rate now allowed changed (a pick or the battery rules). */
    void rate(int fps);
  }

  static final int SIZE = Sound.BANDS + Sound.WAVE + 6;
  private final Context app;
  private final Sound sound;
  private final Pacer pacer;
  private final Actions actions;
  private final boolean wall;
  private volatile boolean stilled;
  private volatile int told = -1;
  private final byte[] out = new byte[SIZE];
  private final Handler main = new Handler(Looper.getMainLooper());

  Bridge(Context context, Sound sound, Pacer pacer, Actions actions, boolean wall) {
    this.app = context.getApplicationContext();
    this.sound = sound;
    this.pacer = pacer;
    this.actions = actions;
    this.wall = wall;
  }

  private static byte unit(float v) {
    return (byte) Math.max(0, Math.min(255, Math.round(v * 255f)));
  }

  @JavascriptInterface public String frame() {
    synchronized (sound) {
      sound.frame();
      int i = 0;
      for (int b = 0; b < Sound.BANDS; b++) out[i++] = unit(sound.bands[b]);
      for (int s = 0; s < Sound.WAVE; s++) {
        out[i++] = (byte) Math.max(0, Math.min(255, Math.round(sound.wave[s] * 127f) + 128));
      }
      out[i++] = unit(sound.level);
      out[i++] = unit(sound.bass);
      out[i++] = unit(sound.mid);
      out[i++] = unit(sound.treble);
      out[i++] = unit(sound.beat);
      out[i] = (byte) (sound.silent ? 1 : 0);
    }
    return Base64.encodeToString(out, Base64.NO_WRAP);
  }

  /** Frames a second allowed now (battery rules); 0 = hold a still picture. */
  @JavascriptInterface public int fps() {
    int fps = pacer.fps();
    if (fps == 0 && !stilled) {
      stilled = true;
      sound.stop();
    } else if (fps > 0 && stilled) {
      stilled = false;
      sound.start(Prefs.source(app), Prefs.micAllowed(app));
    }
    if (actions != null && fps != told) {
      told = fps;
      main.post(() -> actions.rate(fps));
    }
    return fps;
  }

  @JavascriptInterface public String why() {
    return pacer.why();
  }

  @JavascriptInterface public String status() {
    return sound.status();
  }

  @JavascriptInterface public String prefs() {
    return Prefs.json(app);
  }

  @JavascriptInterface public void set(String key, String value) {
    if (key == null || value == null || value.length() > 64 || !Prefs.settable(key)) return;
    Prefs.store(app).edit().putString(key, value).apply();
    if (key.equals(wall ? Prefs.WALL_FPS : Prefs.FPS)) pacer.setWanted(Prefs.fps(app, wall));
    if (key.equals(Prefs.PRESET) || key.equals(Prefs.WALL)) main.post(() -> VizWidget.refreshAll(app));
  }

  /** Media keys for whatever app is playing: prev, play or next. */
  @JavascriptInterface public void key(String which) {
    int code = "prev".equals(which) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
        : "next".equals(which) ? KeyEvent.KEYCODE_MEDIA_NEXT
        : "play".equals(which) ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : 0;
    MediaKeys.press(app, code);
  }

  @JavascriptInterface public void source(String mode) {
    if (actions != null && mode != null) main.post(() -> actions.source(mode));
  }

  @JavascriptInterface public void wallpaper(String presetId) {
    if (actions != null && presetId != null) main.post(() -> actions.wallpaper(presetId));
  }

  @JavascriptInterface public void close() {
    if (actions != null) main.post(actions::close);
  }
}
