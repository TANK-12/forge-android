/* Forge Theme as an Android live wallpaper: on every home-screen page (and
   the lock screen where the phone shows the same wallpaper), panning a
   little as the pages move. Draws only while it is seen; settings changed
   in the app reach it at once. */
package app.forge.theme;

import android.app.WallpaperColors;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import app.forge.look.SceneLoop;
import app.forge.look.Themes;

public final class ThemeWallpaper extends WallpaperService {
  @Override public Engine onCreateEngine() {
    return new Look();
  }

  final class Look extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
    private ThemeScene scene;
    private SceneLoop loop;

    @Override public void onCreate(SurfaceHolder holder) {
      super.onCreate(holder);
      setTouchEventsEnabled(true);
      scene = new ThemeScene(ThemeWallpaper.this);
      loop = new SceneLoop(holder, scene, scene.pacer);
      Prefs.store(ThemeWallpaper.this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
      super.onSurfaceChanged(holder, format, w, h);
      loop.surface(w, h);
    }

    @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
      loop.surfaceGone();
      super.onSurfaceDestroyed(holder);
    }

    @Override public void onVisibilityChanged(boolean visible) {
      loop.visible(visible);
    }

    @Override public void onOffsetsChanged(float xOffset, float yOffset, float xStep,
        float yStep, int xPixels, int yPixels) {
      loop.post(() -> scene.offset(xOffset));
    }

    @Override public void onTouchEvent(MotionEvent event) {
      MotionEvent copy = MotionEvent.obtain(event);
      loop.post(() -> {
        scene.touch(copy);
        copy.recycle();
      });
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
      loop.post(scene::settingsChanged);
      loop.redraw();
      if (Build.VERSION.SDK_INT >= 27) notifyColorsChanged();
    }

    /** The theme's colours, so Android's own colours follow the Forge theme. */
    @Override public WallpaperColors onComputeColors() {
      if (Build.VERSION.SDK_INT < 27) return null;
      Themes.Theme t = Prefs.read(ThemeWallpaper.this).theme(ThemeWallpaper.this);
      return new WallpaperColors(Color.valueOf(t.accent), Color.valueOf(t.accent2),
          Color.valueOf(t.bg));
    }

    @Override public void onDestroy() {
      Prefs.store(ThemeWallpaper.this).unregisterOnSharedPreferenceChangeListener(this);
      loop.post(scene::release);
      loop.quit();
      super.onDestroy();
    }
  }
}
