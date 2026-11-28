package app.forge.phone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import app.forge.look.Themes;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shared widget preset registry, theme palette and safe RemoteViews helpers
 * (Relay task 1591). Designed so both Forge's built-in widgets and a future
 * widget maker can reuse the same presets, Forge colours, coalesced remote
 * cache and battery-light update rules.
 */
public final class WidgetKit {
  private static final String TAG = "ForgeWidget";

  /** Declarative preset descriptor reusable by the start page and widget maker. */
  public static final class Preset {
    public final String id;
    public final String label;
    public final String summary;
    public final String category;
    public final String className;
    public final int defaultCols;
    public final int defaultRows;
    public final String batteryNote;

    Preset(String id, String label, String summary, String category, String className,
        int defaultCols, int defaultRows, String batteryNote) {
      this.id = id;
      this.label = label;
      this.summary = summary;
      this.category = category;
      this.className = className;
      this.defaultCols = defaultCols;
      this.defaultRows = defaultRows;
      this.batteryNote = batteryNote;
    }
  }

  /** Resolved Forge colours for any widget panel. */
  public static final class Palette {
    public final int panel;
    public final int text;
    public final int muted;
    public final int accent;
    public final int accent2;

    public Palette(int panel, int text, int muted, int accent, int accent2) {
      this.panel = panel;
      this.text = text;
      this.muted = muted;
      this.accent = accent;
      this.accent2 = accent2;
    }
  }

  public static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
      new Preset("queue", "Forge queue", "Relay task queue and items waiting on your computer",
          "relay", "app.forge.phone.QueueWidget", 4, 2, "Shared 30-min fetch only while reachable"),
      new Preset("media", "Forge media", "Now playing track with play, pause, skip and visualiser",
          "media", "app.forge.phone.MediaWidget", 4, 2, "Event-driven media updates only; no polling"),
      new Preset("next_up", "Forge next up", "Top priority item: needs you, next in queue, or progress",
          "relay", "app.forge.phone.NextUpWidget", 4, 2, "Shared 30-min fetch only while reachable"),
      new Preset("quick", "Forge quick launch", "One-tap dock for Forge, Visualiser, Notes, Focus and Relay",
          "tools", "app.forge.phone.QuickForgeWidget", 4, 1, "Static buttons only; zero background work"),
      new Preset("jobs", "Forge jobs", "Running Relay tasks, long job progress and last done",
          "relay", "app.forge.phone.JobsWidget", 4, 2, "Shared 30-min fetch only while reachable"),
      new Preset("clock", "Forge clock", "Time, date and theme label",
          "theme", "app.forge.theme.Widgets$ClockWidget", 4, 2, "System TextClock; zero app wakeups"),
      new Preset("particles", "Forge particles", "Animated Forge particles over your theme picture",
          "theme", "app.forge.theme.Widgets$ParticlesWidget", 4, 3, "Launcher-driven vector animation"),
      new Preset("music", "Forge music keys", "Compact play, pause, skip, particles and visualiser bar",
          "media", "app.forge.theme.Widgets$MusicWidget", 4, 1, "Redraws on tap or theme change only"),
      new Preset("visualiser", "Forge visualiser", "Visualiser look, animated bars and music keys",
          "media", "app.forge.visualiser.VizWidget", 4, 2, "Launcher-driven bars; redraws on tap only")
  ));

  public static final Map<String, String> WIDGET_MAP;

  static {
    Map<String, String> map = new LinkedHashMap<>();
    for (Preset p : PRESETS) map.put(p.id, p.className);
    WIDGET_MAP = Collections.unmodifiableMap(map);
  }

  private WidgetKit() {}

  /** Palette from the paired computer's theme JSON, falling back to this device's Forge theme. */
  public static Palette palette(Context c, JSONObject remoteTheme) {
    Themes.Theme local = localTheme(c);
    int panel = colour(remoteTheme, "panel", local != null ? local.panel : 0xff121826);
    int text = colour(remoteTheme, "text", local != null ? local.text : 0xffffffff);
    int muted = colour(remoteTheme, "muted", local != null ? local.muted : 0xb3ffffff);
    int accent = colour(remoteTheme, "accent", local != null ? local.accent : 0xff7cc4a4);
    int accent2 = colour(remoteTheme, "accent2", local != null ? local.accent2 : accent);
    return new Palette(panel, text, muted, accent, accent2);
  }

  /** Palette from this device's active Forge theme only. */
  public static Palette localPalette(Context c) {
    return palette(c, null);
  }

  private static Themes.Theme localTheme(Context c) {
    try {
      String id = c.getApplicationContext().getSharedPreferences("look", Context.MODE_PRIVATE)
          .getString("theme", "midnight");
      return Themes.get(c, id);
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static int colour(JSONObject theme, String key, int fallback) {
    try {
      String v = theme == null ? "" : theme.optString(key, "");
      return v.isEmpty() ? fallback : Color.parseColor(v);
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  public static void applyPanel(RemoteViews v, int bgId, Palette pal, int alpha) {
    v.setInt(bgId, "setColorFilter", pal.panel);
    v.setInt(bgId, "setImageAlpha", alpha);
  }

  public static boolean isShort(AppWidgetManager m, int id, int thresholdDp) {
    Bundle o = m == null ? null : m.getAppWidgetOptions(id);
    return o != null && o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200) < thresholdDp;
  }

  public static void setRow(RemoteViews v, int id, String text) {
    boolean show = text != null && !text.isEmpty();
    v.setViewVisibility(id, show ? View.VISIBLE : View.GONE);
    if (show) v.setTextViewText(id, text);
  }

  public static boolean safeUpdate(AppWidgetManager m, int id, RemoteViews views) {
    if (m == null || views == null) return false;
    try {
      m.updateAppWidget(id, views);
      return true;
    } catch (RuntimeException e) {
      Log.w(TAG, "updateAppWidget failed for id " + id, e);
      return false;
    }
  }

  public static PendingIntent openMain(Context c, int requestCode, String action,
      String extraKey, String extraVal) {
    Intent i = new Intent(c, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (action != null && !action.isEmpty()) i.setAction(action);
    if (extraKey != null && extraVal != null) i.putExtra(extraKey, extraVal);
    return PendingIntent.getActivity(c, requestCode, i,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  /** All widget IDs across every widget that shares the coalesced /hub/api/jobs cache. */
  static int[] allRemoteWidgetIds(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return new int[0];
    int[] a = m.getAppWidgetIds(new ComponentName(c, JobsWidget.class));
    int[] b = m.getAppWidgetIds(new ComponentName(c, QueueWidget.class));
    int[] d = m.getAppWidgetIds(new ComponentName(c, NextUpWidget.class));
    int[] cw = CustomWidget.remoteWidgetIds(c);
    int[] out = new int[a.length + b.length + d.length + cw.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    System.arraycopy(d, 0, out, a.length + b.length, d.length);
    System.arraycopy(cw, 0, out, a.length + b.length + d.length, cw.length);
    return out;
  }

  /** Redraw every remote-jobs widget (Jobs, Queue, Next up, Custom) from the shared cache. */
  static void redrawRemoteWidgets(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return;
    for (int id : m.getAppWidgetIds(new ComponentName(c, JobsWidget.class))) JobsWidget.draw(c, m, id);
    for (int id : m.getAppWidgetIds(new ComponentName(c, QueueWidget.class))) QueueWidget.draw(c, m, id);
    for (int id : m.getAppWidgetIds(new ComponentName(c, NextUpWidget.class))) NextUpWidget.draw(c, m, id);
    CustomWidget.updateAll(c);
  }

  /** Redraw all local theme-following phone widgets (Media + Quick launch + Custom). */
  static void redrawLocalWidgets(Context c) {
    MediaWidget.drawAll(c);
    QuickForgeWidget.drawAll(c);
    CustomWidget.updateAll(c);
  }

  /** JSON list of presets for the start page and future widget maker. */
  static JSONArray presetsJson(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    JSONArray arr = new JSONArray();
    for (Preset p : PRESETS) {
      int placed = 0;
      if (m != null) {
        try {
          placed = m.getAppWidgetIds(new ComponentName(c, p.className)).length;
        } catch (RuntimeException ignored) {
        }
      }
      try {
        arr.put(new JSONObject()
            .put("id", p.id)
            .put("label", p.label)
            .put("summary", p.summary)
            .put("category", p.category)
            .put("cols", p.defaultCols)
            .put("rows", p.defaultRows)
            .put("battery", p.batteryNote)
            .put("placed", placed));
      } catch (JSONException ignored) {
      }
    }
    return arr;
  }

  /** Plain-words inventory of active Forge widgets for Wallpaper & widget check. */
  static String diagnostics(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return "AppWidgetManager unavailable on this device.";
    StringBuilder sb = new StringBuilder();
    sb.append("Media title access (NotificationListener): ")
        .append(MediaBridge.isAccessEnabled(c) ? "enabled" : "not enabled (controls still work via media keys)");
    int total = 0;
    StringBuilder placed = new StringBuilder();
    for (Preset p : PRESETS) {
      int n = 0;
      try {
        n = m.getAppWidgetIds(new ComponentName(c, p.className)).length;
      } catch (RuntimeException ignored) {
      }
      total += n;
      if (n > 0) {
        if (placed.length() > 0) placed.append(", ");
        placed.append(p.id).append("x").append(n);
      }
    }
    int customN = CustomWidget.placedCount(c);
    total += customN;
    if (customN > 0) {
      if (placed.length() > 0) placed.append(", ");
      placed.append("customx").append(customN);
    }
    sb.append("\nPlaced Forge widgets: ").append(total);
    if (total > 0) sb.append(" (").append(placed).append(")");
    return sb.toString();
  }

  /** Per-install secret token attached to widget button PendingIntents so third-party
      apps cannot forge custom actions on exported AppWidgetProvider receivers. */
  static final String EXTRA_TOKEN = "_forge_t";
  private static final String AUTH_PREFS = "forge_widget_auth";
  private static volatile String cachedToken;

  static synchronized String actionToken(Context c) {
    if (cachedToken != null && !cachedToken.isEmpty()) return cachedToken;
    SharedPreferences p = c.getApplicationContext().getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
    String tok = p.getString("token", "");
    if (tok.isEmpty()) {
      byte[] raw = new byte[16];
      new java.security.SecureRandom().nextBytes(raw);
      StringBuilder sb = new StringBuilder(32);
      for (byte b : raw) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
      tok = sb.toString();
      p.edit().putString("token", tok).commit();
    }
    cachedToken = tok;
    return tok;
  }

  static Intent signTap(Context c, Intent intent) {
    return intent.putExtra(EXTRA_TOKEN, actionToken(c));
  }

  static boolean validTap(Context c, Intent intent) {
    if (intent == null) return false;
    String got = intent.getStringExtra(EXTRA_TOKEN);
    if (got == null || got.isEmpty()) return false;
    String want = actionToken(c);
    return java.security.MessageDigest.isEqual(
        got.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        want.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static boolean hasId(int[] ids, int target) {
    if (ids == null || target == AppWidgetManager.INVALID_APPWIDGET_ID) return false;
    for (int id : ids) if (id == target) return true;
    return false;
  }

  /** Store key helper for widget-specific computer assignment (shared with JobsWidget). */
  static void clearHostPicks(Context c, int[] ids) {
    SharedPreferences.Editor e = JobsWidget.store(c).edit();
    for (int id : ids) e.remove("for:" + id);
    e.apply();
  }
}
