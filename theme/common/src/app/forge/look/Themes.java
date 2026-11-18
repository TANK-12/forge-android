/* Forge's themes and particle kinds as the APK carries them (assets
   themes.json and particles.json, made by phone/theme/make_assets.py from
   shared/themes and shared/forge_particles.js). */
package app.forge.look;

import android.content.Context;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class Themes {
  public static final class Theme {
    public final String id, label, wall, particles;
    public final int accent, accent2, bg, panel, text, muted;

    Theme(JSONObject o) {
      id = o.optString("id");
      label = o.optString("label", id);
      wall = o.optString("wall", "");
      particles = o.optString("particles", "soft");
      accent = colour(o, "accent", 0xff4fe0a8);
      accent2 = colour(o, "accent2", 0xffe2f2ee);
      bg = colour(o, "bg", 0xff070d14);
      panel = colour(o, "panel", bg);
      text = colour(o, "text", 0xffffffff);
      muted = colour(o, "muted", text);
    }

    /** A light theme (dark text): widgets and pickers follow it. */
    public boolean light() {
      return luminance(bg) > 0.5f;
    }
  }

  public static final class Kind {
    public final String key, label;

    Kind(String key, String label) {
      this.key = key;
      this.label = label;
    }
  }

  private static List<Theme> themes;
  private static List<Kind> kinds;

  public static synchronized List<Theme> all(Context c) {
    if (themes == null) {
      List<Theme> out = new ArrayList<>();
      try {
        JSONArray a = new JSONArray(read(c, "themes.json"));
        for (int i = 0; i < a.length(); i++) out.add(new Theme(a.getJSONObject(i)));
      } catch (IOException | JSONException e) {
        // a broken asset leaves the built-in fallback below
      }
      if (out.isEmpty()) out.add(new Theme(new JSONObject()));
      themes = Collections.unmodifiableList(out);
    }
    return themes;
  }

  /** The theme with this id, or the first one. */
  public static Theme get(Context c, String id) {
    for (Theme t : all(c)) if (t.id.equals(id)) return t;
    for (Theme t : all(c)) if (!t.light()) return t;
    return all(c).get(0);
  }

  public static synchronized List<Kind> kinds(Context c) {
    if (kinds == null) {
      List<Kind> out = new ArrayList<>();
      try {
        JSONArray a = new JSONArray(read(c, "particles.json"));
        for (int i = 0; i < a.length(); i++) {
          JSONObject o = a.getJSONObject(i);
          out.add(new Kind(o.getString("key"), o.optString("label", o.getString("key"))));
        }
      } catch (IOException | JSONException e) {
        for (String k : Particles.kinds()) out.add(new Kind(k, k));
      }
      kinds = Collections.unmodifiableList(out);
    }
    return kinds;
  }

  public static String read(Context c, String asset) throws IOException {
    try (InputStream in = c.getAssets().open(asset)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  static int colour(JSONObject o, String key, int fallback) {
    try {
      return Color.parseColor(o.optString(key, ""));
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  public static float luminance(int c) {
    return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
  }

  private Themes() {}
}
