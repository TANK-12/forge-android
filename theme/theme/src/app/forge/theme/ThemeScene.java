/* The Forge Theme wallpaper picture: the background, Forge particles over
   it, and (when switched on) the music moving them. The live wallpaper and
   the preview in the app both draw this; it runs on SceneLoop's thread. */
package app.forge.theme;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import app.forge.look.Background;
import app.forge.look.Particles;
import app.forge.look.Pacer;
import app.forge.look.SceneLoop;
import app.forge.look.Sound;
import app.forge.look.Themes;

final class ThemeScene implements SceneLoop.Scene {
  final Pacer pacer;
  final Sound sound = new Sound();
  private final Context app;
  private final Background background;
  private final Particles particles = new Particles();
  private final float density;
  private int width, height;
  private boolean running, reload = true;
  private Prefs prefs;

  ThemeScene(Context context) {
    app = context.getApplicationContext();
    background = new Background(app);
    pacer = new Pacer(app);
    DisplayMetrics m = app.getResources().getDisplayMetrics();
    density = m.density;
    prefs = Prefs.read(app);
    pacer.setWanted(prefs.fps);
  }

  /** The settings changed: pick them up on the next frame. */
  void settingsChanged() {
    reload = true;
  }

  @Override public void size(int w, int h) {
    width = w;
    height = h;
    reload = true;
  }

  @Override public void running(boolean on) {
    running = on;
    if (on) listen();
    else sound.stop();
  }

  private void listen() {
    boolean mic = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
    Sound.Mode want = prefs.soundMode();
    if (want == Sound.Mode.OFF) sound.stop();
    else if (sound.mode() != want) sound.start(want, mic);
  }

  private void apply() {
    reload = false;
    long t0 = SystemClock.uptimeMillis();
    prefs = Prefs.read(app);
    pacer.setWanted(prefs.fps);
    Themes.Theme t = prefs.theme(app);
    background.load(width, height, prefs.picture, t, prefs.dim);
    particles.setKind(prefs.kindFor(t));
    particles.setColours(t.accent, t.accent2, background.brightness() > 0.55f);
    particles.setAmount(prefs.amount);
    particles.setSize(prefs.size);
    particles.setSpeed(prefs.speed);
    particles.resize(width, height, density);
    if (running) listen();
    Log.i("ForgeTheme", "set up " + width + "x" + height + " in "
        + (SystemClock.uptimeMillis() - t0) + " ms");
  }

  @Override public void frame(Canvas c, float dt) {
    if (reload) apply();
    if (sound.mode() != Sound.Mode.OFF) {
      sound.frame();
      particles.sound(sound.level, sound.bass, sound.beat >= 0.999f);
    }
    particles.step(dt);
    background.draw(c);
    particles.draw(c);
  }

  /** Home-screen page position, 0 (first page) to 1 (last). */
  void offset(float x) {
    background.offset(x);
    particles.parallax(x);
  }

  void touch(MotionEvent e) {
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        particles.pointer(e.getX(), e.getY());
        if (prefs.taps) particles.spark(e.getX(), e.getY());
        break;
      case MotionEvent.ACTION_MOVE:
        particles.pointer(e.getX(), e.getY());
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        particles.pointerUp();
        break;
      default:
        break;
    }
  }

  void release() {
    sound.stop();
    background.release();
  }
}
