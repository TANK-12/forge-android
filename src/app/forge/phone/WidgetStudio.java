package app.forge.phone;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Storage, templates, app-customisation state, local media/queue state and
 * Binder-safe panel rendering for Forge for Android 2's Widget Maker & Studio
 * (Relay task 1592).
 */
public final class WidgetStudio {
  public static final String PREFS = "forge_widget_studio";
  static final String KEY_DESIGNS = "designs";
  static final String KEY_CUSTOM = "app_custom";
  static final String KEY_MEDIA = "forge_media_state";
  static final String KEY_PENDING = "pending_design_id";
  static final int MAX_DESIGNS = 32;
  static final int MAX_BITMAP_EDGE = 320; // <= 409 KB ARGB_8888, well under Android's 1 MB Binder cap

  private WidgetStudio() {}

  public static SharedPreferences store(Context c) {
    return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** Built-in starter custom widget designs editable in Studio. */
  public static JSONArray defaultDesigns() {
    JSONArray arr = new JSONArray();
    try {
      arr.put(new JSONObject()
          .put("id", "cw-media-queue")
          .put("name", "Media + Up Next")
          .put("size", "card")
          .put("layout", "split")
          .put("primary", "media")
          .put("secondary", "next_up")
          .put("data_source", "auto")
          .put("show_header", true)
          .put("show_controls", true)
          .put("theme_mode", "auto")
          .put("bg_color", "#131826")
          .put("accent_color", "#7cc8a4")
          .put("text_color", "#eef1f8")
          .put("opacity", 92)
          .put("radius_dp", 20)
          .put("text_scale", "normal")
          .put("shortcuts", new JSONArray("[\"music\",\"youtube\",\"media\",\"visualiser\"]"))
          .put("note_title", "Focus")
          .put("note_body", ""));
      arr.put(new JSONObject()
          .put("id", "cw-queue-board")
          .put("name", "Queue & Relay Board")
          .put("size", "board")
          .put("layout", "stack")
          .put("primary", "next_up")
          .put("secondary", "queue")
          .put("data_source", "auto")
          .put("show_header", true)
          .put("show_controls", true)
          .put("theme_mode", "auto")
          .put("bg_color", "#111622")
          .put("accent_color", "#6ea8fe")
          .put("text_color", "#eef1f8")
          .put("opacity", 94)
          .put("radius_dp", 22)
          .put("text_scale", "normal")
          .put("shortcuts", new JSONArray("[\"music\",\"youtube\",\"notes\",\"relay\"]"))
          .put("note_title", "Queue")
          .put("note_body", ""));
      arr.put(new JSONObject()
          .put("id", "cw-oled-clock")
          .put("name", "OLED Clock & Battery")
          .put("size", "card")
          .put("layout", "split")
          .put("primary", "clock")
          .put("secondary", "hardware")
          .put("data_source", "auto")
          .put("show_header", false)
          .put("show_controls", false)
          .put("theme_mode", "oled")
          .put("bg_color", "#000000")
          .put("accent_color", "#a78bfa")
          .put("text_color", "#f4f6fb")
          .put("opacity", 100)
          .put("radius_dp", 24)
          .put("text_scale", "large")
          .put("shortcuts", new JSONArray("[\"deck\",\"focus\",\"music\",\"media\"]"))
          .put("note_title", "")
          .put("note_body", ""));
      arr.put(new JSONObject()
          .put("id", "cw-compact-player")
          .put("name", "Compact Music & YouTube Pill")
          .put("size", "compact")
          .put("layout", "bar")
          .put("primary", "media")
          .put("secondary", "none")
          .put("data_source", "device_media")
          .put("show_header", true)
          .put("show_controls", true)
          .put("theme_mode", "custom")
          .put("bg_color", "#151a28")
          .put("accent_color", "#f28c6b")
          .put("text_color", "#ffffff")
          .put("opacity", 90)
          .put("radius_dp", 28)
          .put("text_scale", "normal")
          .put("shortcuts", new JSONArray("[\"music\",\"youtube\",\"media\",\"deck\"]"))
          .put("note_title", "")
          .put("note_body", ""));
    } catch (JSONException ignored) {
    }
    return arr;
  }

  public static JSONArray designs(Context c) {
    String raw = store(c).getString(KEY_DESIGNS, null);
    if (raw == null || raw.isEmpty()) {
      JSONArray defs = defaultDesigns();
      store(c).edit().putString(KEY_DESIGNS, defs.toString()).apply();
      return defs;
    }
    try {
      JSONArray parsed = new JSONArray(raw);
      return parsed.length() > 0 ? parsed : defaultDesigns();
    } catch (JSONException e) {
      return defaultDesigns();
    }
  }

  public static JSONObject findDesign(Context c, String id) {
    JSONArray all = designs(c);
    if (id != null && !id.isEmpty()) {
      for (int i = 0; i < all.length(); i++) {
        JSONObject d = all.optJSONObject(i);
        if (d != null && id.equals(d.optString("id"))) return sanitizeDesign(d);
      }
    }
    JSONObject first = all.length() > 0 ? all.optJSONObject(0) : null;
    return sanitizeDesign(first != null ? first : new JSONObject());
  }

  /** Validate and normalise a widget design JSON object from the page or storage. */
  public static JSONObject sanitizeDesign(JSONObject in) {
    JSONObject out = new JSONObject();
    if (in == null) in = new JSONObject();
    try {
      String id = cleanId(in.optString("id"), "cw-" + (System.currentTimeMillis() % 100000000L));
      String name = trimMax(in.optString("name", "Custom widget"), 48);
      if (name.isEmpty()) name = "Custom widget";
      String size = oneOf(in.optString("size"), "card", "compact", "card", "board");
      String layout = oneOf(in.optString("layout"), "stack", "stack", "split", "bar");
      String primary = oneOf(in.optString("primary"), "media",
          "media", "queue", "next_up", "jobs", "clock", "hardware", "shortcuts", "note");
      String secondary = oneOf(in.optString("secondary"), "none",
          "none", "media", "queue", "next_up", "jobs", "hardware", "shortcuts", "note");
      String dataSource = oneOf(in.optString("data_source"), "auto",
          "auto", "device_media", "computer_relay");
      String themeMode = oneOf(in.optString("theme_mode"), "auto", "auto", "custom", "oled");
      String bg = cleanHex(in.optString("bg_color"), "#131826");
      String accent = cleanHex(in.optString("accent_color"), "#7cc8a4");
      String text = cleanHex(in.optString("text_color"), "#eef1f8");
      int opacity = Math.max(0, Math.min(100, in.optInt("opacity", 92)));
      int radius = Math.max(0, Math.min(32, in.optInt("radius_dp", 18)));
      String scale = oneOf(in.optString("text_scale"), "normal", "compact", "normal", "large");

      JSONArray scIn = in.optJSONArray("shortcuts");
      JSONArray scOut = new JSONArray();
      if (scIn != null) {
        for (int i = 0; i < Math.min(4, scIn.length()); i++) {
          String s = cleanId(scIn.optString(i), "");
          if (!s.isEmpty()) scOut.put(s);
        }
      }
      if (scOut.length() == 0) {
        scOut = new JSONArray("[\"music\",\"youtube\",\"media\",\"notes\"]");
      }

      out.put("id", id)
          .put("name", name)
          .put("size", size)
          .put("layout", layout)
          .put("primary", primary)
          .put("secondary", secondary)
          .put("data_source", dataSource)
          .put("show_header", in.optBoolean("show_header", true))
          .put("show_controls", in.optBoolean("show_controls", true))
          .put("theme_mode", themeMode)
          .put("bg_color", bg)
          .put("accent_color", accent)
          .put("text_color", text)
          .put("opacity", opacity)
          .put("radius_dp", radius)
          .put("text_scale", scale)
          .put("shortcuts", scOut)
          .put("note_title", trimMax(in.optString("note_title", ""), 60))
          .put("note_body", trimMax(in.optString("note_body", ""), 240));
    } catch (JSONException ignored) {
    }
    return out;
  }

  /** Save or update a design (and optionally bind a specific appWidgetId to it), then redraw widgets. */
  public static synchronized JSONObject saveDesign(Context c, JSONObject rawDesign, int bindWidgetId) {
    JSONObject clean = sanitizeDesign(rawDesign);
    String id = clean.optString("id");
    JSONArray cur = designs(c);
    JSONArray next = new JSONArray();
    boolean replaced = false;
    for (int i = 0; i < cur.length(); i++) {
      JSONObject item = cur.optJSONObject(i);
      if (item == null) continue;
      if (id.equals(item.optString("id"))) {
        next.put(clean);
        replaced = true;
      } else if (next.length() < MAX_DESIGNS - 1) {
        next.put(item);
      }
    }
    if (!replaced) {
      JSONArray prepended = new JSONArray();
      prepended.put(clean);
      for (int i = 0; i < Math.min(MAX_DESIGNS - 1, next.length()); i++) {
        prepended.put(next.opt(i));
      }
      next = prepended;
    }
    SharedPreferences.Editor ed = store(c).edit().putString(KEY_DESIGNS, next.toString());
    if (bindWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && bindWidgetId > 0) {
      ed.putString("bind:" + bindWidgetId, id);
    }
    ed.commit();
    CustomWidget.updateAll(c);
    return clean;
  }

  public static synchronized boolean deleteDesign(Context c, String id) {
    if (id == null || id.isEmpty()) return false;
    JSONArray cur = designs(c);
    if (cur.length() <= 1) return false; // always keep at least one template
    JSONArray next = new JSONArray();
    for (int i = 0; i < cur.length(); i++) {
      JSONObject item = cur.optJSONObject(i);
      if (item != null && !id.equals(item.optString("id"))) next.put(item);
    }
    store(c).edit().putString(KEY_DESIGNS, next.toString()).commit();
    CustomWidget.updateAll(c);
    return true;
  }

  public static String designIdForWidget(Context c, int appWidgetId, String fallbackSize) {
    SharedPreferences s = store(c);
    String bound = s.getString("bind:" + appWidgetId, "");
    if (!bound.isEmpty()) return bound;
    String pending = s.getString(KEY_PENDING, "");
    long pendingAt = s.getLong("pending_design_at", 0L);
    if (!pending.isEmpty() && System.currentTimeMillis() - pendingAt < 120_000L) {
      JSONObject pd = findDesign(c, pending);
      if (fallbackSize.equals(pd.optString("size", fallbackSize))) {
        s.edit().putString("bind:" + appWidgetId, pending)
            .remove(KEY_PENDING).remove("pending_design_at").apply();
        return pending;
      }
    } else if (!pending.isEmpty()) {
      s.edit().remove(KEY_PENDING).remove("pending_design_at").apply();
    }
    JSONArray all = designs(c);
    for (int i = 0; i < all.length(); i++) {
      JSONObject d = all.optJSONObject(i);
      if (d != null && fallbackSize.equals(d.optString("size"))) {
        String pick = d.optString("id");
        s.edit().putString("bind:" + appWidgetId, pick).apply();
        return pick;
      }
    }
    String first = all.length() > 0 && all.optJSONObject(0) != null
        ? all.optJSONObject(0).optString("id") : "cw-media-queue";
    s.edit().putString("bind:" + appWidgetId, first).apply();
    return first;
  }

  public static void setPendingPinDesign(Context c, String designId) {
    store(c).edit()
        .putString(KEY_PENDING, cleanId(designId, ""))
        .putLong("pending_design_at", System.currentTimeMillis())
        .commit();
  }

  public static void unbindWidgets(Context c, int[] ids) {
    SharedPreferences.Editor ed = store(c).edit();
    for (int id : ids) ed.remove("bind:" + id);
    ed.apply();
  }

  // -- App Customisation Preferences -----------------------------------------------

  public static JSONObject defaultAppCustom() {
    JSONObject o = new JSONObject();
    try {
      o.put("quality_mode", "auto")        // auto | full | balanced | saver
          .put("surface_style", "auto")    // auto | glass | solid | oled
          .put("font_scale", "normal")     // compact | normal | large | xl
          .put("density", "comfortable")   // comfortable | compact
          .put("corners", "rounded")       // sharp | rounded | pill
          .put("nav_mode", "auto")         // auto | rail | dock
          .put("app_cols", "auto")         // auto | 2 | 3 | 4 | list
          .put("accent_override", "")
          .put("bg_override", "")
          .put("pause_below_pct", 15)
          .put("home_sections", new JSONArray(
              "[\"hero\",\"media\",\"relay\",\"pins\",\"media_shelf\",\"features\",\"hardware\"]"));
    } catch (JSONException ignored) {
    }
    return o;
  }

  public static JSONObject appCustom(Context c) {
    String raw = store(c).getString(KEY_CUSTOM, null);
    JSONObject def = defaultAppCustom();
    if (raw == null || raw.isEmpty()) return def;
    try {
      JSONObject got = new JSONObject(raw);
      for (java.util.Iterator<String> it = def.keys(); it.hasNext(); ) {
        String k = it.next();
        if (!got.has(k)) got.put(k, def.get(k));
      }
      return got;
    } catch (JSONException e) {
      return def;
    }
  }

  public static JSONObject saveAppCustom(Context c, JSONObject incoming) {
    JSONObject base = appCustom(c);
    if (incoming == null) return base;
    try {
      if (incoming.has("quality_mode")) {
        base.put("quality_mode", oneOf(incoming.optString("quality_mode"), "auto",
            "auto", "full", "balanced", "saver"));
      }
      if (incoming.has("surface_style")) {
        base.put("surface_style", oneOf(incoming.optString("surface_style"), "auto",
            "auto", "glass", "solid", "oled"));
      }
      if (incoming.has("font_scale")) {
        base.put("font_scale", oneOf(incoming.optString("font_scale"), "normal",
            "compact", "normal", "large", "xl"));
      }
      if (incoming.has("density")) {
        base.put("density", oneOf(incoming.optString("density"), "comfortable",
            "comfortable", "compact"));
      }
      if (incoming.has("corners")) {
        base.put("corners", oneOf(incoming.optString("corners"), "rounded",
            "sharp", "rounded", "pill"));
      }
      if (incoming.has("nav_mode")) {
        base.put("nav_mode", oneOf(incoming.optString("nav_mode"), "auto",
            "auto", "rail", "dock"));
      }
      if (incoming.has("app_cols")) {
        base.put("app_cols", oneOf(incoming.optString("app_cols"), "auto",
            "auto", "2", "3", "4", "list"));
      }
      if (incoming.has("accent_override")) {
        String acc = incoming.optString("accent_override", "").trim();
        base.put("accent_override", acc.isEmpty() ? "" : cleanHex(acc, ""));
      }
      if (incoming.has("bg_override")) {
        String bg = incoming.optString("bg_override", "").trim();
        base.put("bg_override", bg.isEmpty() ? "" : cleanHex(bg, ""));
      }
      if (incoming.has("pause_below_pct")) {
        base.put("pause_below_pct", Math.max(5, Math.min(50, incoming.optInt("pause_below_pct", 15))));
      }
      if (incoming.optJSONArray("home_sections") != null) {
        JSONArray hs = incoming.optJSONArray("home_sections");
        JSONArray cleanHs = new JSONArray();
        for (int i = 0; i < Math.min(12, hs.length()); i++) {
          String s = cleanId(hs.optString(i), "");
          if (!s.isEmpty()) cleanHs.put(s);
        }
        if (cleanHs.length() > 0) base.put("home_sections", cleanHs);
      }
      store(c).edit().putString(KEY_CUSTOM, base.toString()).commit();
    } catch (JSONException ignored) {
    }
    return base;
  }

  // -- Local Forge Media / Queue / Next Up State -----------------------------------

  /**
   * Record playback + queue + next_up reported by Forge Music, Forge Media or
   * Forge YouTube, and trigger event-driven widget redraws.
   */
  public static void updateForgeMedia(Context c, String rawJson) {
    if (rawJson == null || rawJson.length() > 16384) return;
    try {
      JSONObject in = new JSONObject(rawJson);
      JSONObject clean = new JSONObject();
      String title = trimMax(in.optString("title", ""), 120);
      String sub = trimMax(in.optString("sub", ""), 120);
      String source = trimMax(in.optString("source", "Forge Media"), 40);
      String appId = cleanId(in.optString("app", "media"), "media");
      boolean playing = in.optBoolean("playing", false);
      clean.put("app", appId)
          .put("source", source)
          .put("title", title)
          .put("sub", sub)
          .put("playing", playing)
          .put("updated_at", System.currentTimeMillis());

      JSONObject nu = in.optJSONObject("next_up");
      if (nu != null) {
        clean.put("next_up", new JSONObject()
            .put("title", trimMax(nu.optString("title", ""), 100))
            .put("sub", trimMax(nu.optString("sub", ""), 80)));
      }
      JSONArray qIn = in.optJSONArray("queue");
      JSONArray qOut = new JSONArray();
      if (qIn != null) {
        for (int i = 0; i < Math.min(8, qIn.length()); i++) {
          JSONObject qi = qIn.optJSONObject(i);
          if (qi == null) continue;
          String qt = trimMax(qi.optString("title", ""), 100);
          if (!qt.isEmpty()) {
            qOut.put(new JSONObject()
                .put("title", qt)
                .put("sub", trimMax(qi.optString("sub", ""), 80)));
          }
        }
      }
      clean.put("queue", qOut);
      store(c).edit().putString(KEY_MEDIA, clean.toString()).apply();

      // Also feed MediaBridge's store when no external OS player is actively playing
      SharedPreferences mb = MediaBridge.store(c);
      boolean externalPlaying = mb.getBoolean("playing", false)
          && !c.getPackageName().equals(mb.getString("pkg", ""));
      if (!externalPlaying && !title.isEmpty()) {
        mb.edit()
            .putBoolean("has_session", true)
            .putBoolean("playing", playing)
            .putString("pkg", c.getPackageName())
            .putString("app", source)
            .putString("title", title)
            .putString("artist", sub)
            .putLong("at", System.currentTimeMillis())
            .apply();
        MediaWidget.drawAll(c);
      }
      CustomWidget.redrawMediaWidgets(c);
    } catch (JSONException ignored) {
    }
  }

  public static JSONObject forgeMediaState(Context c) {
    String raw = store(c).getString(KEY_MEDIA, "");
    if (!raw.isEmpty()) {
      try {
        return new JSONObject(raw);
      } catch (JSONException ignored) {
      }
    }
    // Fall back to MediaBridge's OS-level Now Playing metadata if available
    SharedPreferences mb = MediaBridge.store(c);
    JSONObject out = new JSONObject();
    try {
      out.put("title", mb.getString("title", ""))
          .put("sub", mb.getString("artist", ""))
          .put("source", mb.getString("app", ""))
          .put("playing", mb.getBoolean("playing", false))
          .put("queue", new JSONArray());
    } catch (JSONException ignored) {
    }
    return out;
  }

  /** Full Studio payload for the start page's Studio & Wallpaper views. */
  public static JSONObject studioJson(Context c) {
    JSONObject out = new JSONObject();
    try {
      out.put("designs", designs(c))
          .put("presets", WidgetKit.presetsJson(c))
          .put("placed_custom", CustomWidget.placedSummary(c))
          .put("app_custom", appCustom(c))
          .put("media", forgeMediaState(c))
          .put("pin_supported", pinSupported(c));
    } catch (JSONException ignored) {
    }
    return out;
  }

  public static boolean pinSupported(Context c) {
    AppWidgetManager m = c.getSystemService(AppWidgetManager.class);
    return m != null && m.isRequestPinAppWidgetSupported();
  }

  /**
   * Render a rounded, bordered widget background Bitmap capped strictly to
   * {@link #MAX_BITMAP_EDGE} pixels so Binder transactions always stay tiny.
   */
  public static Bitmap makePanelBitmap(Context c, AppWidgetManager m, int widgetId,
      int bgColor, int accentColor, int opacityPct, int radiusDp) {
    float density = c.getResources().getDisplayMetrics().density;
    Bundle opts = m == null ? null : m.getAppWidgetOptions(widgetId);
    int wDp = opts != null ? Math.max(120, opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)) : 250;
    int hDp = opts != null ? Math.max(56, opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 140)) : 140;
    float rawW = wDp * density;
    float rawH = hDp * density;
    float scale = Math.min(1f, MAX_BITMAP_EDGE / Math.max(rawW, rawH));
    int w = Math.max(64, Math.round(rawW * scale));
    int h = Math.max(36, Math.round(rawH * scale));
    float r = Math.max(0f, radiusDp * density * scale);

    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    int alpha = Math.round((Math.max(0, Math.min(100, opacityPct)) / 100f) * 255f);
    if (alpha > 0) {
      Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
      fill.setStyle(Paint.Style.FILL);
      fill.setColor((bgColor & 0x00ffffff) | (alpha << 24));
      float inset = Math.max(1f, 1.2f * scale);
      RectF rect = new RectF(inset, inset, w - inset, h - inset);
      canvas.drawRoundRect(rect, r, r, fill);

      Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
      stroke.setStyle(Paint.Style.STROKE);
      stroke.setStrokeWidth(Math.max(1f, 1.5f * density * scale));
      int strokeAlpha = Math.min(140, Math.max(36, alpha / 2));
      stroke.setColor((accentColor & 0x00ffffff) | (strokeAlpha << 24));
      canvas.drawRoundRect(rect, r, r, stroke);
    }
    return bmp;
  }

  public static int parseHex(String hex, int fallback) {
    if (hex == null || hex.isEmpty()) return fallback;
    try {
      return Color.parseColor(hex);
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  static String cleanHex(String raw, String fallback) {
    if (raw != null && raw.trim().matches("^#[0-9a-fA-F]{6}$")) {
      return raw.trim().toLowerCase(Locale.ROOT);
    }
    return fallback;
  }

  static String cleanId(String raw, String fallback) {
    if (raw == null) return fallback;
    String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    return s.isEmpty() ? fallback : (s.length() > 40 ? s.substring(0, 40) : s);
  }

  static String trimMax(String raw, int max) {
    if (raw == null) return "";
    String s = raw.trim();
    return s.length() <= max ? s : s.substring(0, max);
  }

  static String oneOf(String val, String def, String... allowed) {
    if (val != null) {
      String v = val.trim().toLowerCase(Locale.ROOT);
      for (String a : allowed) {
        if (a.equals(v)) return a;
      }
    }
    return def;
  }
}
