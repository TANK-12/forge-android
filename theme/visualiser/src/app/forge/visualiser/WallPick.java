/* Forge Visualiser's live wallpaper for other parts of the app (task 1322):
   the main Forge app's start page picks the look the wallpaper shows. */
package app.forge.visualiser;

import android.content.Context;

public final class WallPick {
  /** Whether the visualiser is the live wallpaper now. */
  public static boolean active(Context c) {
    return VizWidget.onWallpaper(c);
  }

  /** The look the wallpaper shows (its id; the first look until one is picked). */
  public static String look(Context c) {
    Presets.Preset p = Presets.find(c, Prefs.get(c, Prefs.WALL));
    return p == null ? "" : p.id;
  }

  /** Pick the wallpaper's look; false for a look this build lacks. */
  public static boolean use(Context c, String id) {
    Presets.Preset p = Presets.find(c, id);
    if (p == null || !p.id.equals(id)) return false;
    Prefs.store(c).edit().putString(Prefs.WALL, id).apply();
    VizWidget.refreshAll(c);
    return true;
  }

  private WallPick() {}
}
