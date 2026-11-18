/* The visualiser's presets as this APK carries them (assets/visualiser/
   presets.json, copied from Forge's shared/visualiser at build time), for the
   widget's label and its next-look button. The page reads the same file. */
package app.forge.visualiser;

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

final class Presets {
  static final class Preset {
    final String id, name, weight;
    final int background, primary, secondary;

    Preset(String id, String name, String weight, JSONObject colours) {
      this.id = id;
      this.name = name;
      this.weight = weight;
      background = colour(colours, "background", 0xff05070c);
      primary = colour(colours, "primary", 0xff8fb8ff);
      secondary = colour(colours, "secondary", 0xff3a6fd8);
    }

    private static int colour(JSONObject colours, String key, int fallback) {
      if (colours == null) return fallback;
      try {
        return Color.parseColor(colours.optString(key, ""));
      } catch (IllegalArgumentException e) {
        return fallback;
      }
    }

    /** Light runs on any tablet; heavy looks are labelled Showpiece. */
    String label() {
      return "heavy".equals(weight) ? "Showpiece" : "Light";
    }
  }

  private static List<Preset> cached;

  private Presets() {}

  static synchronized List<Preset> all(Context c) {
    if (cached != null) return cached;
    List<Preset> out = new ArrayList<>();
    try (InputStream in = c.getAssets().open("visualiser/presets.json")) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
      JSONArray rows = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()))
          .getJSONArray("presets");
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        String id = row.optString("id", "");
        if (id.isEmpty()) continue;
        out.add(new Preset(id, row.optString("name", id), row.optString("weight", "light"),
            row.optJSONObject("colours")));
      }
    } catch (IOException | JSONException e) {
      // no presets in this build: the widget says so
    }
    cached = Collections.unmodifiableList(out);
    return cached;
  }

  /** The preset with this id, else the first one (null when there are none). */
  static Preset find(Context c, String id) {
    List<Preset> list = all(c);
    for (Preset p : list) if (p.id.equals(id)) return p;
    return list.isEmpty() ? null : list.get(0);
  }

  static Preset after(Context c, String id) {
    List<Preset> list = all(c);
    if (list.isEmpty()) return null;
    int at = -1;
    for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) at = i;
    // An unknown id shows as the first preset (see find), so the next is the second.
    return list.get((Math.max(at, 0) + 1) % list.size());
  }
}
