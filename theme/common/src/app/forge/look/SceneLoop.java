/* Draws a Scene on a surface (a live wallpaper or a preview in an app) on its
   own thread, only while it is visible, at no more than the Pacer's frame
   rate. Frames follow the screen's refresh (Choreographer) and skip ahead to
   the cap, so 30 frames a second on a 120 Hz tablet draws every fourth one.
   A cap of 0 (battery nearly empty) draws one still frame and stops. */
package app.forge.look;

import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;

public final class SceneLoop implements Choreographer.FrameCallback {
  public interface Scene {
    /** The surface size in pixels; called on the drawing thread. */
    void size(int width, int height);

    /** Draw one whole frame; dt is seconds since the last one. */
    void frame(Canvas c, float dt);

    /** Drawing starts (true) or stops (false): start or stop listening. */
    void running(boolean on);
  }

  private final SurfaceHolder holder;
  private final Scene scene;
  private final Pacer pacer;
  private final HandlerThread thread;
  private final Handler handler;
  private Choreographer choreographer;
  private boolean visible, hasSurface, scheduled, sized, wasRunning, listening;
  private int width, height, hintFps = -1;
  private final Runnable recheck = this::recheck;
  private long lastFrameNs, lastDrawNs;

  public SceneLoop(SurfaceHolder holder, Scene scene, Pacer pacer) {
    this.holder = holder;
    this.scene = scene;
    this.pacer = pacer;
    thread = new HandlerThread("forge-scene");
    thread.start();
    handler = new Handler(thread.getLooper());
    handler.post(() -> choreographer = Choreographer.getInstance());
  }

  /** Run something on the drawing thread (settings changes, touches). */
  public void post(Runnable r) {
    handler.post(r);
  }

  public void surface(int w, int h) {
    handler.post(() -> {
      hasSurface = w > 0 && h > 0;
      hintFps = -1;
      if (w != width || h != height) sized = false;
      width = w;
      height = h;
      update();
    });
  }

  public void surfaceGone() {
    handler.post(() -> {
      hasSurface = false;
      update();
    });
  }

  public void visible(boolean on) {
    handler.post(() -> {
      visible = on;
      update();
    });
  }

  /** Draw again soon even when paused at a still frame (a setting changed). */
  public void redraw() {
    handler.post(() -> {
      lastDrawNs = 0;
      if (visible && hasSurface && !scheduled) {
        scheduled = true;
        choreographer.postFrameCallback(this);
      }
    });
  }

  public void quit() {
    handler.post(() -> {
      visible = false;
      update();
      thread.quitSafely();
    });
  }

  private void update() {
    boolean run = visible && hasSurface;
    if (run != wasRunning) {
      wasRunning = run;
      lastFrameNs = 0;
      if (!run) {
        handler.removeCallbacks(recheck);
        listen(false);
      }
    }
    if (run && !scheduled) {
      scheduled = true;
      choreographer.postFrameCallback(this);
    }
  }

  /** Listening follows moving frames: off when hidden or at a still picture. */
  private void listen(boolean on) {
    if (on != listening) {
      listening = on;
      scene.running(on);
    }
  }

  private void recheck() {
    if (visible && hasSurface && !scheduled) {
      scheduled = true;
      choreographer.postFrameCallback(this);
    }
  }

  /** Sleep until about the next frame instead of waking on every refresh. */
  private void later(long ms) {
    scheduled = true;
    if (ms > 0) choreographer.postFrameCallbackDelayed(this, ms);
    else choreographer.postFrameCallback(this);
  }

  /** Tell Android 11+ the rate, so a 90 or 120 Hz screen can slow down. */
  private void hint(int fps) {
    if (fps == hintFps || Build.VERSION.SDK_INT < 30) return;
    hintFps = fps;
    try {
      Surface s = holder.getSurface();
      if (s != null && s.isValid()) s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
    } catch (RuntimeException e) {
      // only a hint
    }
  }

  @Override public void doFrame(long frameTimeNanos) {
    scheduled = false;
    if (!(visible && hasSurface)) return;
    int fps = pacer.fps();
    hint(fps);
    listen(fps > 0);
    long now = SystemClock.elapsedRealtimeNanos();
    long interval = fps > 0 ? 1_000_000_000L / fps : 0;
    // a little early is fine: frames land on the refresh, not between
    if (fps > 0 && lastDrawNs != 0 && now - lastDrawNs < interval - 4_000_000L) {
      later((interval - (now - lastDrawNs)) / 1_000_000L - 4);
      return;
    }
    draw(now);
    if (fps > 0) {
      later(interval / 1_000_000L - 6);
    } else {
      // a still picture: look again in a while (charging, Battery Saver off)
      handler.removeCallbacks(recheck);
      handler.postDelayed(recheck, 30_000);
    }
  }

  private void draw(long now) {
    if (!sized) {
      scene.size(width, height);
      sized = true;
    }
    float dt = lastFrameNs == 0 ? 1 / 30f : Math.min(0.1f, (now - lastFrameNs) / 1e9f);
    lastFrameNs = now;
    lastDrawNs = now;
    Canvas c = null;
    try {
      c = holder.lockHardwareCanvas();
      if (c != null) scene.frame(c, dt);
    } catch (IllegalStateException | IllegalArgumentException e) {
      // the surface went away between frames; the next surface() call fixes it
    } finally {
      if (c != null) {
        try {
          holder.unlockCanvasAndPost(c);
        } catch (IllegalStateException | IllegalArgumentException ignored) {
        }
      }
    }
  }
}
