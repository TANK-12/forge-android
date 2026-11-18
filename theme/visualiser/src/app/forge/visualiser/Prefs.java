/* Forge Visualiser's settings, one small store shared by the player, the
   wallpaper and the widget: the look each one shows, where the sound comes
   from, and the frame rate and sharpness of the player and the wallpaper.
   Values are strings (the page sets them through Bridge.set). */
package app.forge.visualiser;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import app.forge.look.Sound;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

final class Prefs {
  static final String PRESET = "preset", WALL = "wall", SOURCE = "source";
  static final String FPS = "fps", SCALE = "scale", WALL_FPS = "wallFps", WALL_SCALE = "wallScale";
  private static final List<String> KEYS =
      Arrays.asList(PRESET, WALL, SOURCE, FPS, SCALE, WALL_FPS, WALL_SCALE);
  private static final Map<String, String> DEFAULTS = new HashMap<>();

  static {
    DEFAULTS.put(PRESET, "");
    DEFAULTS.put(WALL, "");
    DEFAULTS.put(SOURCE, "phone");
    DEFAULTS.put(FPS, "60");
    DEFAULTS.put(SCALE, "1");
    DEFAULTS.put(WALL_FPS, "30");
    DEFAULTS.put(WALL_SCALE, "0.75");
  }

  private Prefs() {}

  static SharedPreferences store(Context c) {
    return c.getApplicationContext().getSharedPreferences("visualiser", Context.MODE_PRIVATE);
  }

  static boolean settable(String key) {
    return KEYS.contains(key);
  }

  static String get(Context c, String key) {
    String fallback = DEFAULTS.get(key);
    return store(c).getString(key, fallback == null ? "" : fallback);
  }

  static int fps(Context c, boolean wall) {
    try {
      return Integer.parseInt(get(c, wall ? WALL_FPS : FPS));
    } catch (NumberFormatException e) {
      return 30;
    }
  }

  static Sound.Mode source(Context c) {
    switch (get(c, SOURCE)) {
      case "phone": return Sound.Mode.PHONE;
      case "mic": return Sound.Mode.MIC;
      case "demo": return Sound.Mode.DEMO;
      default: return Sound.Mode.OFF;
    }
  }

  static boolean micAllowed(Context c) {
    return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  /** Everything the page reads, as one JSON object. */
  static String json(Context c) {
    JSONObject o = new JSONObject();
    try {
      for (String key : KEYS) o.put(key, get(c, key));
      o.put("mic", micAllowed(c));
    } catch (JSONException ignored) {
      // only string keys and plain values go in
    }
    return o.toString();
  }
}
