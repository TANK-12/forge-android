package app.forge.phone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Custom home-screen widgets built in Forge Studio (Relay task 1592).
 *
 * Three size providers ({@link Compact}, {@link Card}, {@link Board}) share
 * one event-driven renderer and support custom palettes, OLED black, opacity,
 * corner roundness, text sizes, stack/split layouts, and mix-and-match blocks:
 * Media, Queue, Next Up, Forge Jobs, Clock & Date, Hardware & Battery,
 * Quick Shortcuts, and Custom Notes.
 *
 * Battery: updatePeriodMillis=0 on all providers; zero background timers.
 */
public abstract class CustomWidget extends AppWidgetProvider {
  public static final String ACTION_MEDIA = "app.forge.phone.custom.MEDIA";
  public static final String ACTION_REFRESH = "app.forge.phone.custom.REFRESH";
  public static final String ACTION_EDIT = "app.forge.phone.Main.STUDIO_EDIT";

  public static final class Compact extends CustomWidget {
    @Override protected String sizeKind() { return "compact"; }
  }

  public static final class Card extends CustomWidget {
    @Override protected String sizeKind() { return "card"; }
  }

  public static final class Board extends CustomWidget {
    @Override protected String sizeKind() { return "board"; }
  }

  protected abstract String sizeKind();

  public static Class<?> providerForSize(String size) {
    if ("compact".equals(size)) return Compact.class;
    if ("board".equals(size)) return Board.class;
    return Card.class;
  }

  static Class<?>[] allProviders() {
    return new Class<?>[] {Compact.class, Card.class, Board.class};
  }

  public static boolean hasAnyPlaced(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return false;
    for (Class<?> cls : allProviders()) {
      if (m.getAppWidgetIds(new ComponentName(c, cls)).length > 0) return true;
    }
    return false;
  }

  private static boolean designUsesMedia(JSONObject d) {
    if (d == null) return false;
    String p = d.optString("primary");
    String s = d.optString("secondary");
    if ("media".equals(p) || "media".equals(s)) return true;
    if (!"computer_relay".equals(d.optString("data_source"))
        && ("queue".equals(p) || "queue".equals(s) || "next_up".equals(p) || "next_up".equals(s))) {
      return true;
    }
    return false;
  }

  public static boolean hasMediaWidgets(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return false;
    String[][] kinds = {{"compact", Compact.class.getName()},
        {"card", Card.class.getName()}, {"board", Board.class.getName()}};
    for (String[] k : kinds) {
      for (int id : m.getAppWidgetIds(new ComponentName(c, k[1]))) {
        JSONObject d = WidgetStudio.findDesign(c, WidgetStudio.designIdForWidget(c, id, k[0]));
        if (designUsesMedia(d)) return true;
      }
    }
    return false;
  }

  public static void redrawMediaWidgets(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return;
    String[][] kinds = {{"compact", Compact.class.getName()},
        {"card", Card.class.getName()}, {"board", Board.class.getName()}};
    for (String[] k : kinds) {
      for (int id : m.getAppWidgetIds(new ComponentName(c, k[1]))) {
        JSONObject d = WidgetStudio.findDesign(c, WidgetStudio.designIdForWidget(c, id, k[0]));
        if (designUsesMedia(d)) draw(c, m, id, k[0]);
      }
    }
  }

  public static int placedCount(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return 0;
    int total = 0;
    for (Class<?> cls : allProviders()) {
      total += m.getAppWidgetIds(new ComponentName(c, cls)).length;
    }
    return total;
  }

  /** IDs of placed custom widgets that show paired-computer Relay blocks. */
  static int[] remoteWidgetIds(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return new int[0];
    java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
    String[][] kinds = {{"compact", Compact.class.getName()},
        {"card", Card.class.getName()}, {"board", Board.class.getName()}};
    for (String[] k : kinds) {
      for (int id : m.getAppWidgetIds(new ComponentName(c, k[1]))) {
        JSONObject d = WidgetStudio.findDesign(c, WidgetStudio.designIdForWidget(c, id, k[0]));
        String p = d.optString("primary");
        String s = d.optString("secondary");
        if ("jobs".equals(p) || "jobs".equals(s)
            || (!"device_media".equals(d.optString("data_source"))
                && ("queue".equals(p) || "queue".equals(s) || "next_up".equals(p) || "next_up".equals(s)))) {
          list.add(id);
        }
      }
    }
    int[] out = new int[list.size()];
    for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
    return out;
  }

  public static JSONArray placedSummary(Context c) {
    JSONArray out = new JSONArray();
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return out;
    String[][] kinds = {{"compact", Compact.class.getName()},
        {"card", Card.class.getName()}, {"board", Board.class.getName()}};
    for (String[] k : kinds) {
      int[] ids;
      try {
        ids = m.getAppWidgetIds(new ComponentName(c, k[1]));
      } catch (RuntimeException e) {
        continue;
      }
      for (int id : ids) {
        String designId = WidgetStudio.designIdForWidget(c, id, k[0]);
        JSONObject d = WidgetStudio.findDesign(c, designId);
        try {
          out.put(new JSONObject()
              .put("widget_id", id)
              .put("size", k[0])
              .put("design_id", designId)
              .put("name", d.optString("name", "Custom widget")));
        } catch (JSONException ignored) {
        }
      }
    }
    return out;
  }

  public static void updateAll(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return;
    for (int id : m.getAppWidgetIds(new ComponentName(c, Compact.class))) draw(c, m, id, "compact");
    for (int id : m.getAppWidgetIds(new ComponentName(c, Card.class))) draw(c, m, id, "card");
    for (int id : m.getAppWidgetIds(new ComponentName(c, Board.class))) draw(c, m, id, "board");
  }

  public static void redrawHardwareWidgets(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return;
    String[][] kinds = {{"compact", Compact.class.getName()},
        {"card", Card.class.getName()}, {"board", Board.class.getName()}};
    for (String[] k : kinds) {
      int[] ids = m.getAppWidgetIds(new ComponentName(c, k[1]));
      for (int id : ids) {
        JSONObject d = WidgetStudio.findDesign(c, WidgetStudio.designIdForWidget(c, id, k[0]));
        if ("hardware".equals(d.optString("primary")) || "hardware".equals(d.optString("secondary"))) {
          draw(c, m, id, k[0]);
        }
      }
    }
  }

  @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    MediaBridge.syncActive(c);
    for (int id : ids) draw(c, m, id, sizeKind());
    JobsWidget.plan(c);
    if (remoteWidgetIds(c).length > 0) JobsWidget.refreshSoon(c, false);
  }

  @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
    draw(c, m, id, sizeKind());
  }

  @Override public void onDeleted(Context c, int[] ids) {
    WidgetStudio.unbindWidgets(c, ids);
    JobsWidget.plan(c);
    MediaBridge.syncActive(c);
  }

  @Override public void onDisabled(Context c) {
    JobsWidget.plan(c);
    MediaBridge.syncActive(c);
  }

  @Override public void onReceive(Context c, Intent intent) {
    super.onReceive(c, intent);
    String action = intent == null ? null : intent.getAction();
    if (ACTION_MEDIA.equals(action)) {
      if (!WidgetKit.validTap(c, intent)) return;
      String op = intent.getStringExtra("op");
      if (op != null && !op.isEmpty()) {
        MediaBridge.control(c, op);
        updateAll(c);
      }
    } else if (ACTION_REFRESH.equals(action)) {
      if (!WidgetKit.validTap(c, intent)) return;
      PendingResult done = goAsync();
      Context app = c.getApplicationContext();
      new Thread(() -> {
        try {
          JSONArray pcs = JobsWidget.computers(app);
          JSONObject first = pcs.length() > 0 ? pcs.optJSONObject(0) : null;
          String host = first == null ? "" : first.optString("host");
          synchronized (JobsWidget.LOCK) {
            if (!host.isEmpty()) JobsWidget.fetch(app, host);
          }
          WidgetKit.redrawRemoteWidgets(app);
          updateAll(app);
          JobsWidget.plan(app);
        } finally {
          done.finish();
        }
      }, "forge-custom-refresh").start();
    }
  }

  private static final class BlockText {
    String eyebrow = "";
    String headline = "";
    String line1 = "";
    String line2 = "";
    boolean usesClock;
    boolean needsRemoteRefresh;
    String tapTarget = ""; // app name, "relay", "visualiser", or ""
  }

  private static BlockText resolveBlock(Context c, String block, String dataSource, JSONObject design) {
    BlockText b = new BlockText();
    if ("clock".equals(block)) {
      b.usesClock = true;
      b.eyebrow = DateFormat.format("EEEE, d MMMM", new Date()).toString();
      b.headline = "";
      b.tapTarget = "deck";
      return b;
    }
    if ("hardware".equals(block)) {
      HardwareProfile.Snapshot s = HardwareProfile.capture(c);
      HardwareProfile.Adaptive a = HardwareProfile.computeAdaptive(s);
      String tierLabel = a.tier.isEmpty() ? "Standard"
          : Character.toUpperCase(a.tier.charAt(0)) + a.tier.substring(1);
      b.eyebrow = "Device · " + tierLabel;
      String bat = s.battery >= 0 ? s.battery + "%" + (s.charging ? " charging" : "") : "Battery ok";
      b.headline = bat + " · " + HardwareProfile.thermalWord(s.thermal);
      b.line1 = String.format(Locale.ROOT, "%.1f GB RAM · %d cores · GLES %s",
          s.memoryGb, s.cores, HardwareProfile.formatGles(s.glesRaw));
      b.line2 = "Mode: " + a.qualityMode + (s.powerSave ? " (Saver on)" : "");
      b.tapTarget = "pane:settings";
      return b;
    }
    if ("note".equals(block)) {
      String nt = design.optString("note_title", "").trim();
      String nb = design.optString("note_body", "").trim();
      b.eyebrow = nt.isEmpty() ? "Forge note" : nt;
      String[] parts = nb.isEmpty() ? new String[] {"Tap ✎ to write a custom note or checklist"} : nb.split("\\n");
      b.headline = parts[0];
      if (parts.length > 1) b.line1 = parts[1];
      if (parts.length > 2) b.line2 = parts[2];
      b.tapTarget = "notes";
      return b;
    }
    if ("shortcuts".equals(block)) {
      b.eyebrow = "Quick launch";
      JSONArray sc = design.optJSONArray("shortcuts");
      StringBuilder names = new StringBuilder();
      if (sc != null) {
        for (int i = 0; i < sc.length(); i++) {
          if (i > 0) names.append(" · ");
          names.append(labelForShortcut(sc.optString(i)));
        }
      }
      b.headline = names.length() > 0 ? names.toString() : "Music · YouTube · Media · Notes";
      b.tapTarget = sc != null && sc.length() > 0 ? sc.optString(0) : "music";
      return b;
    }
    if ("media".equals(block)) {
      JSONObject fm = WidgetStudio.forgeMediaState(c);
      SharedPreferences mb = MediaBridge.store(c);
      boolean osHas = mb.getBoolean("has_session", false) && !mb.getString("title", "").isEmpty();
      String title = osHas ? mb.getString("title", "") : fm.optString("title", "");
      String sub = osHas ? mb.getString("artist", "") : fm.optString("sub", "");
      String src = osHas ? mb.getString("app", "This device") : fm.optString("source", "Forge Media");
      boolean playing = osHas ? mb.getBoolean("playing", false) : fm.optBoolean("playing", false);
      b.eyebrow = (playing ? "Playing" : "Media") + (src.isEmpty() ? "" : " · " + src);
      b.headline = title.isEmpty() ? "Ready to play" : title;
      b.line1 = sub.isEmpty() ? "Tap to open Forge Music or Media" : sub;
      JSONObject nu = fm.optJSONObject("next_up");
      if (nu != null && !nu.optString("title").isEmpty()) {
        b.line2 = "Next: " + nu.optString("title");
      }
      b.tapTarget = "youtube".equals(fm.optString("app")) ? "youtube"
          : "media".equals(fm.optString("app")) ? "media" : "music";
      return b;
    }

    // Queue, Next Up, or Jobs: decide between local media queue vs paired computer Relay
    JSONObject fm = WidgetStudio.forgeMediaState(c);
    JSONArray mediaQueue = fm.optJSONArray("queue");
    boolean hasMediaQueue = mediaQueue != null && mediaQueue.length() > 0;
    JSONArray pcs = JobsWidget.computers(c);
    String host = pcs.length() > 0 && pcs.optJSONObject(0) != null
        ? pcs.optJSONObject(0).optString("host") : "";
    boolean preferMedia = "device_media".equals(dataSource)
        || ("auto".equals(dataSource) && !"jobs".equals(block) && (hasMediaQueue || host.isEmpty()));

    if (preferMedia && !"jobs".equals(block)) {
      String src = fm.optString("source", "Music & Media");
      if ("next_up".equals(block)) {
        JSONObject nu = fm.optJSONObject("next_up");
        if (nu == null && hasMediaQueue) nu = mediaQueue.optJSONObject(0);
        b.eyebrow = "Next up · " + src;
        b.headline = nu != null && !nu.optString("title").isEmpty()
            ? nu.optString("title") : "Nothing queued next";
        b.line1 = nu != null ? nu.optString("sub", "") : "Add songs or videos in Music or YouTube";
      } else {
        b.eyebrow = "Up next queue · " + src;
        if (hasMediaQueue) {
          JSONObject q0 = mediaQueue.optJSONObject(0);
          JSONObject q1 = mediaQueue.length() > 1 ? mediaQueue.optJSONObject(1) : null;
          JSONObject q2 = mediaQueue.length() > 2 ? mediaQueue.optJSONObject(2) : null;
          b.headline = q0 != null ? "1. " + q0.optString("title") : "Queue ready";
          if (q1 != null) b.line1 = "2. " + q1.optString("title");
          if (q2 != null) b.line2 = "3. " + q2.optString("title");
        } else {
          b.headline = "Media queue is clear";
          b.line1 = "Tap +Q in Music or YouTube to line up tracks";
        }
      }
      b.tapTarget = "youtube".equals(fm.optString("app")) ? "youtube" : "music";
      return b;
    }

    // Paired computer Relay Jobs / Queue / Next Up (reusing JobsWidget's shared cache)
    b.needsRemoteRefresh = true;
    b.tapTarget = host.isEmpty() ? "pane:computers" : "relay:" + host;
    if (host.isEmpty()) {
      b.eyebrow = "Computer Relay";
      b.headline = "No computer paired yet";
      b.line1 = "Open Forge → Computers to sign in";
      return b;
    }
    SharedPreferences js = JobsWidget.store(c);
    String state = js.getString("state:" + host, "");
    String pcName = JobsWidget.nameOf(c, host);
    JSONObject reply = null;
    try {
      String raw = js.getString("reply:" + host, "");
      if (!raw.isEmpty()) reply = new JSONObject(raw);
    } catch (JSONException ignored) {
    }
    if (reply == null || !"ok".equals(state)) {
      b.eyebrow = pcName;
      b.headline = "away".equals(state) ? "Computer not reachable" : "Tap ↻ to check " + pcName;
      b.line1 = js.getString("msg:" + host, "");
      return b;
    }

    int runningCount = reply.optInt("running_count");
    int waiting = reply.optInt("waiting");
    int needs = reply.optInt("needs_you");
    JSONArray running = reply.optJSONArray("running");
    JSONArray queued = reply.optJSONArray("queued");
    JSONArray blocked = reply.optJSONArray("blocked");

    if ("next_up".equals(block)) {
      JSONObject topBlocked = blocked != null && blocked.length() > 0 ? blocked.optJSONObject(0) : null;
      JSONObject nextUp = reply.optJSONObject("next_up");
      if (nextUp == null && queued != null && queued.length() > 0) nextUp = queued.optJSONObject(0);
      if (topBlocked != null) {
        b.eyebrow = "Needs you · " + pcName;
        b.headline = "#" + topBlocked.optInt("id") + " " + topBlocked.optString("title");
      } else if (nextUp != null) {
        b.eyebrow = "Next in Relay · " + pcName;
        b.headline = "#" + nextUp.optInt("id") + " " + nextUp.optString("title");
      } else if (running != null && running.length() > 0 && running.optJSONObject(0) != null) {
        JSONObject r0 = running.optJSONObject(0);
        b.eyebrow = "Running · " + pcName;
        b.headline = "#" + r0.optInt("id") + " " + r0.optString("title");
      } else {
        b.eyebrow = pcName;
        b.headline = "All clear in Relay";
      }
      b.line1 = runningCount + " running · " + waiting + " waiting";
    } else if ("queue".equals(block)) {
      b.eyebrow = "Relay queue · " + pcName;
      b.headline = waiting > 0 ? waiting + " waiting" + (needs > 0 ? " · " + needs + " need you" : "") : "Queue clear";
      if (queued != null && queued.length() > 0 && queued.optJSONObject(0) != null) {
        b.line1 = "#" + queued.optJSONObject(0).optInt("id") + " " + queued.optJSONObject(0).optString("title");
      }
      if (queued != null && queued.length() > 1 && queued.optJSONObject(1) != null) {
        b.line2 = "#" + queued.optJSONObject(1).optInt("id") + " " + queued.optJSONObject(1).optString("title");
      }
    } else {
      b.eyebrow = "Forge jobs · " + pcName;
      b.headline = runningCount > 0 ? runningCount + " running now" : "Idle";
      if (running != null && running.length() > 0 && running.optJSONObject(0) != null) {
        b.line1 = "#" + running.optJSONObject(0).optInt("id") + " " + running.optJSONObject(0).optString("title");
      }
      b.line2 = waiting + " queued · " + needs + " blocked";
    }
    return b;
  }

  private static String labelForShortcut(String code) {
    return switch (code == null ? "" : code) {
      case "music" -> "Music";
      case "youtube" -> "YouTube";
      case "media" -> "Media";
      case "deck" -> "Deck";
      case "visualiser" -> "Visualiser";
      case "relay" -> "Relay";
      case "notes" -> "Notes";
      case "focus" -> "Focus";
      case "ai" -> "AI";
      case "studio" -> "Studio";
      default -> code == null || code.isEmpty() ? "Forge"
          : Character.toUpperCase(code.charAt(0)) + code.substring(1);
    };
  }

  private static PendingIntent tapForTarget(Context c, int reqCode, String target) {
    if ("visualiser".equals(target)) {
      Intent viz = new Intent(c, app.forge.visualiser.PlayerActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      return PendingIntent.getActivity(c, reqCode, viz,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    if (target != null && target.startsWith("relay:")) {
      String host = target.substring("relay:".length());
      return WidgetKit.openMain(c, reqCode, JobsWidget.OPEN, "host", host);
    }
    if ("relay".equals(target)) {
      JSONArray pcs = JobsWidget.computers(c);
      String host = pcs.length() > 0 && pcs.optJSONObject(0) != null
          ? pcs.optJSONObject(0).optString("host") : "";
      if (!host.isEmpty()) return WidgetKit.openMain(c, reqCode, JobsWidget.OPEN, "host", host);
      return WidgetKit.openMain(c, reqCode, QuickForgeWidget.OPEN_OFFLINE, "pane", "computers");
    }
    if (target != null && target.startsWith("pane:")) {
      return WidgetKit.openMain(c, reqCode, QuickForgeWidget.OPEN_OFFLINE, "pane", target.substring(5));
    }
    if ("studio".equals(target)) {
      return WidgetKit.openMain(c, reqCode, QuickForgeWidget.OPEN_OFFLINE, "pane", "studio");
    }
    if (target != null && !target.isEmpty()) {
      return WidgetKit.openMain(c, reqCode, QuickForgeWidget.OPEN_OFFLINE, "app", target);
    }
    return WidgetKit.openMain(c, reqCode, null, null, null);
  }

  private static PendingIntent mediaBroadcast(Context c, Class<?> cls, int id, String op, int slot) {
    Intent i = WidgetKit.signTap(c, new Intent(c, cls).setAction(ACTION_MEDIA).putExtra("op", op));
    return PendingIntent.getBroadcast(c, 50000 + id * 16 + slot, i,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static void draw(Context c, AppWidgetManager m, int id, String sizeKind) {
    String designId = WidgetStudio.designIdForWidget(c, id, sizeKind);
    JSONObject d = WidgetStudio.findDesign(c, designId);

    WidgetKit.Palette autoPal = WidgetKit.localPalette(c);
    String themeMode = d.optString("theme_mode", "auto");
    int bg = "oled".equals(themeMode) ? 0xff000000
        : "custom".equals(themeMode) ? WidgetStudio.parseHex(d.optString("bg_color"), autoPal.panel)
        : autoPal.panel;
    int accent = "custom".equals(themeMode) || "oled".equals(themeMode)
        ? WidgetStudio.parseHex(d.optString("accent_color"), autoPal.accent) : autoPal.accent;
    int text = "custom".equals(themeMode) || "oled".equals(themeMode)
        ? WidgetStudio.parseHex(d.optString("text_color"), autoPal.text) : autoPal.text;
    int muted = (text & 0x00ffffff) | 0xb3000000;

    int opacity = d.optInt("opacity", 92);
    int radiusDp = d.optInt("radius_dp", 18);
    float scale = "compact".equals(d.optString("text_scale")) ? 0.88f
        : "large".equals(d.optString("text_scale")) ? 1.18f : 1.0f;

    String primaryKind = d.optString("primary", "media");
    String secondaryKind = d.optString("secondary", "none");
    String dataSource = d.optString("data_source", "auto");
    BlockText prim = resolveBlock(c, primaryKind, dataSource, d);
    BlockText sec = "compact".equals(sizeKind) || "none".equals(secondaryKind)
        ? null : resolveBlock(c, secondaryKind, dataSource, d);
    boolean split = "split".equals(d.optString("layout")) && sec != null;

    Class<?> providerCls = providerForSize(sizeKind);
    int layoutId = "compact".equals(sizeKind) ? R.layout.widget_custom_compact
        : "board".equals(sizeKind)
            ? (split ? R.layout.widget_custom_board_split : R.layout.widget_custom_board)
            : (split ? R.layout.widget_custom_card : R.layout.widget_custom_card_stack);
    RemoteViews v = new RemoteViews(c.getPackageName(), layoutId);

    // Custom rounded/opacity panel bitmap (strictly capped <= 320px edge for Binder safety)
    Bitmap bmp = WidgetStudio.makePanelBitmap(c, m, id, bg, accent, opacity, radiusDp);
    v.setImageViewBitmap(R.id.cw_bg, bmp);

    // Edit button -> opens Forge Studio focused on this exact widget & design
    Intent editIntent = new Intent(c, Main.class)
        .setAction(ACTION_EDIT)
        .putExtra("design_id", designId)
        .putExtra("widget_id", id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent editPi = PendingIntent.getActivity(c, 52000 + id, editIntent,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    v.setOnClickPendingIntent(R.id.cw_edit, editPi);
    v.setTextColor(R.id.cw_edit, muted);
    v.setViewVisibility(R.id.cw_edit, d.optBoolean("show_header", true) ? View.VISIBLE : View.GONE);

    if ("compact".equals(sizeKind)) {
      v.setViewVisibility(R.id.cw_clock, prim.usesClock ? View.VISIBLE : View.GONE);
      v.setTextColor(R.id.cw_clock, text);
      v.setTextColor(R.id.cw_eyebrow, accent);
      v.setTextColor(R.id.cw_primary, text);
      v.setTextColor(R.id.cw_sub, muted);
      v.setTextViewTextSize(R.id.cw_primary, TypedValue.COMPLEX_UNIT_SP, 14f * scale);
      v.setTextViewText(R.id.cw_eyebrow, prim.eyebrow);
      v.setTextViewText(R.id.cw_primary, prim.usesClock ? d.optString("name", "Forge") : prim.headline);
      Bundle opts = m == null ? null : m.getAppWidgetOptions(id);
      boolean tallCompact = opts != null && opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 56) >= 88;
      WidgetKit.setRow(v, R.id.cw_sub, tallCompact ? prim.line1 : null);
      v.setOnClickPendingIntent(R.id.cw_main_tap, tapForTarget(c, 53000 + id, prim.tapTarget));
      boolean showMedia = d.optBoolean("show_controls", true) && "media".equals(primaryKind);
      v.setViewVisibility(R.id.cw_media_bar, showMedia ? View.VISIBLE : View.GONE);
      if (showMedia) {
        bindMediaButtons(c, v, providerCls, id, text);
        boolean narrow = opts != null && opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220) < 210;
        v.setViewVisibility(R.id.cw_prev, narrow ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.cw_pause, narrow ? View.GONE : View.VISIBLE);
      }
      WidgetKit.safeUpdate(m, id, v);
      return;
    }

    // Card (4x2) or Board (4x3): orientation is fixed by the selected RemoteViews layout
    boolean showHeader = d.optBoolean("show_header", true);
    v.setViewVisibility(R.id.cw_header, showHeader ? View.VISIBLE : View.GONE);
    v.setTextViewText(R.id.cw_title, d.optString("name", "Forge Studio"));
    v.setTextColor(R.id.cw_title, muted);

    v.setViewVisibility(R.id.cw_clock, prim.usesClock ? View.VISIBLE : View.GONE);
    v.setTextColor(R.id.cw_clock, text);
    v.setTextViewTextSize(R.id.cw_clock, TypedValue.COMPLEX_UNIT_SP,
        ("board".equals(sizeKind) ? 36f : 30f) * scale);
    v.setTextColor(R.id.cw_eyebrow, accent);
    v.setTextColor(R.id.cw_primary, text);
    v.setTextColor(R.id.cw_line1, muted);
    v.setTextColor(R.id.cw_line2, muted);
    v.setTextViewTextSize(R.id.cw_primary, TypedValue.COMPLEX_UNIT_SP,
        ("board".equals(sizeKind) ? 17f : 15f) * scale);

    v.setTextViewText(R.id.cw_eyebrow, prim.eyebrow);
    v.setViewVisibility(R.id.cw_primary, prim.headline.isEmpty() ? View.GONE : View.VISIBLE);
    v.setTextViewText(R.id.cw_primary, prim.headline);
    WidgetKit.setRow(v, R.id.cw_line1, prim.line1);
    WidgetKit.setRow(v, R.id.cw_line2, "card".equals(sizeKind) && sec != null && !split ? null : prim.line2);
    v.setOnClickPendingIntent(R.id.cw_col_a, tapForTarget(c, 53000 + id, prim.tapTarget));

    if (sec != null) {
      v.setViewVisibility(R.id.cw_col_b, View.VISIBLE);
      v.setTextColor(R.id.cw_sec_eyebrow, accent);
      v.setTextColor(R.id.cw_sec_line1, text);
      v.setTextColor(R.id.cw_sec_line2, muted);
      v.setTextColor(R.id.cw_sec_line3, muted);
      v.setTextViewTextSize(R.id.cw_sec_line1, TypedValue.COMPLEX_UNIT_SP, 13.5f * scale);
      v.setTextViewText(R.id.cw_sec_eyebrow, sec.eyebrow);
      v.setTextViewText(R.id.cw_sec_line1, sec.usesClock ? "Clock" : sec.headline);
      WidgetKit.setRow(v, R.id.cw_sec_line2, sec.line1);
      WidgetKit.setRow(v, R.id.cw_sec_line3, "board".equals(sizeKind) ? sec.line2 : null);
      v.setOnClickPendingIntent(R.id.cw_col_b, tapForTarget(c, 54000 + id, sec.tapTarget));
    } else {
      v.setViewVisibility(R.id.cw_col_b, View.GONE);
    }

    boolean anyRefresh = prim.needsRemoteRefresh || (sec != null && sec.needsRemoteRefresh);
    v.setViewVisibility(R.id.cw_refresh, anyRefresh ? View.VISIBLE : View.GONE);
    if (anyRefresh) {
      v.setInt(R.id.cw_refresh, "setColorFilter", text);
      Intent ref = WidgetKit.signTap(c, new Intent(c, providerCls).setAction(ACTION_REFRESH));
      v.setOnClickPendingIntent(R.id.cw_refresh, PendingIntent.getBroadcast(c, 55000 + id, ref,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    boolean wantControls = d.optBoolean("show_controls", true);
    boolean hasMediaBlock = "media".equals(primaryKind) || "media".equals(secondaryKind);
    boolean hasShortcutBlock = "shortcuts".equals(primaryKind) || "shortcuts".equals(secondaryKind)
        || ("board".equals(sizeKind) && wantControls);

    v.setViewVisibility(R.id.cw_media_bar, wantControls && hasMediaBlock ? View.VISIBLE : View.GONE);
    if (wantControls && hasMediaBlock) {
      bindMediaButtons(c, v, providerCls, id, text);
    }

    JSONArray shortcuts = d.optJSONArray("shortcuts");
    boolean showScRow = (wantControls && !hasMediaBlock) || ("board".equals(sizeKind) && hasShortcutBlock);
    v.setViewVisibility(R.id.cw_shortcuts, showScRow && shortcuts != null && shortcuts.length() > 0
        ? View.VISIBLE : View.GONE);
    if (showScRow && shortcuts != null) {
      int[] scIds = {R.id.cw_sc1, R.id.cw_sc2, R.id.cw_sc3, R.id.cw_sc4};
      for (int i = 0; i < scIds.length; i++) {
        if (i < shortcuts.length()) {
          String target = shortcuts.optString(i, "");
          v.setViewVisibility(scIds[i], View.VISIBLE);
          v.setTextViewText(scIds[i], labelForShortcut(target));
          v.setTextColor(scIds[i], i == 0 ? accent : text);
          v.setOnClickPendingIntent(scIds[i], tapForTarget(c, 56000 + id * 8 + i, target));
        } else {
          v.setViewVisibility(scIds[i], View.GONE);
        }
      }
    }

    WidgetKit.safeUpdate(m, id, v);
  }

  private static void bindMediaButtons(Context c, RemoteViews v, Class<?> cls, int id, int tint) {
    v.setOnClickPendingIntent(R.id.cw_prev, mediaBroadcast(c, cls, id, "prev", 1));
    v.setOnClickPendingIntent(R.id.cw_play, mediaBroadcast(c, cls, id, "play", 2));
    v.setOnClickPendingIntent(R.id.cw_pause, mediaBroadcast(c, cls, id, "pause", 3));
    v.setOnClickPendingIntent(R.id.cw_next, mediaBroadcast(c, cls, id, "next", 4));
    for (int bid : new int[] {R.id.cw_prev, R.id.cw_play, R.id.cw_pause, R.id.cw_next}) {
      v.setInt(bid, "setColorFilter", tint);
    }
  }
}
