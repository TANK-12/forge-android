/* Forge Theme's settings, one place for their names and defaults. They live
   in the app's own preferences; the wallpaper, the preview and the widgets
   read them and listen for changes. */
package app.forge.theme;

import android.content.Context;
import android.content.SharedPreferences;
import app.forge.look.Sound;
import app.forge.look.Themes;

final class Prefs {
  static final String FILE = "look";
  static final String THEME = "theme", PICTURE = "picture", KIND = "kind", AMOUNT = "amount",
      SIZE = "size", SPEED = "speed", FPS = "fps", DIM = "dim", SOUND = "sound", TAPS = "taps";

  final String theme, picture, kind, sound;
  final float amount, size, speed, dim;
  final int fps;
  final boolean taps;

  private Prefs(SharedPreferences p) {
    theme = p.getString(THEME, "midnight");
    picture = p.getString(PICTURE, "theme"); // "theme", "none" or a content address
    kind = p.getString(KIND, "theme"); // "theme" follows the theme, else a kind or "off"
    amount = p.getFloat(AMOUNT, 1f);
    size = p.getFloat(SIZE, 1f);
    speed = p.getFloat(SPEED, 1f);
    dim = p.getFloat(DIM, 0.12f);
    fps = p.getInt(FPS, 30);
    sound = p.getString(SOUND, "off"); // off, phone, mic
    taps = p.getBoolean(TAPS, true);
  }

  static SharedPreferences store(Context c) {
    return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
  }

  static Prefs read(Context c) {
    return new Prefs(store(c));
  }

  Themes.Theme theme(Context c) {
    return Themes.get(c, theme);
  }

  /** The particle kind to draw: the theme's when following it. */
  String kindFor(Themes.Theme t) {
    return "theme".equals(kind) ? t.particles : kind;
  }

  Sound.Mode soundMode() {
    switch (sound) {
      case "phone": return Sound.Mode.PHONE;
      case "mic": return Sound.Mode.MIC;
      case "demo": return Sound.Mode.DEMO;
      default: return Sound.Mode.OFF;
    }
  }
}
