/* Forge Theme's wallpapers for other parts of the app (task 1322): the main
   Forge app's start page picks a theme for the live wallpaper, or sets a
   theme's picture as a plain still wallpaper (home screen, lock screen or
   both), which every tablet allows with no live wallpaper screen. */
package app.forge.theme;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.util.Log;
import app.forge.look.Background;
import app.forge.look.Themes;
import java.io.IOException;

public final class Walls {
  /** Whether Forge particles is the live wallpaper now. */
  public static boolean active(Context c) {
    try {
      WallpaperInfo info = WallpaperManager.getInstance(c).getWallpaperInfo();
      return info != null && c.getPackageName().equals(info.getPackageName())
          && ThemeWallpaper.class.getName().equals(info.getServiceName());
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The theme picked now (its id). */
  public static String theme(Context c) {
    return Prefs.read(c).theme;
  }

  /** Pick a theme for the wallpaper and widgets; false for a theme this build lacks. */
  public static boolean use(Context c, String id) {
    if (!known(c, id)) return false;
    Prefs.store(c).edit().putString(Prefs.THEME, id).apply();
    Widgets.refreshAll(c);
    return true;
  }

  static boolean known(Context c, String id) {
    if (id == null) return false;
    for (Themes.Theme t : Themes.all(c)) if (t.id.equals(id)) return true;
    return false;
  }

  /** The theme's picture (or its colours), dimmed as in the settings, as a still
      wallpaper. which: WallpaperManager.FLAG_SYSTEM, FLAG_LOCK or both. Slow
      (decodes and hands over a screen-sized picture): call it off the main
      thread. Returns null when done, else what went wrong in plain words. */
  public static String still(Context c, String id, int which) {
    if (!known(c, id)) return "That theme is not in this version of Forge.";
    Prefs p = Prefs.read(c);
    Themes.Theme t = Themes.get(c, id);
    WallpaperManager wm = WallpaperManager.getInstance(c);
    if (!wm.isWallpaperSupported() || !wm.isSetWallpaperAllowed()) {
      return "This tablet does not let apps change the wallpaper.";
    }
    DisplayMetrics d = c.getResources().getDisplayMetrics();
    // the long side both ways, so the picture fills the screen either way up
    int side = Math.min(3200, Math.max(d.widthPixels, d.heightPixels));
    Background bg = new Background(c);
    Bitmap frame = null;
    try {
      bg.load(side, side, "theme", t, p.dim);
      bg.offset(0.5f);
      frame = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
      bg.draw(new Canvas(frame));
      wm.setBitmap(frame, null, true, which);
      return null;
    } catch (IOException | RuntimeException | OutOfMemoryError e) {
      Log.w("ForgeTheme", "still wallpaper", e);
      return "The picture could not be set: " + e.getClass().getSimpleName() + ".";
    } finally {
      bg.release();
      if (frame != null) frame.recycle();
    }
  }

  private Walls() {}
}
