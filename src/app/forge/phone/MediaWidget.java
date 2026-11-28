package app.forge.phone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Forge Media home-screen widget (Relay task 1591): Now Playing title, artist
 * and status with Previous, Play/Pause, direct Pause, Next and Visualiser.
 *
 * Supports two sources (switchable with ⇄ when a paired computer allows remote):
 * - This device: event-driven MediaBridge (NotificationListenerService) for
 *   live track titles and TransportControls, with zero-permission MediaKeys
 *   fallback when Notification Access is not enabled.
 * - Paired computer: fetches /hub/api/remote only on tap or button press
 *   (never polled in the background).
 */
public final class MediaWidget extends AppWidgetProvider {
  static final String CMD = "app.forge.phone.media.CMD";
  static final String SOURCE = "app.forge.phone.media.SOURCE";
  static final String ACCESS = "app.forge.phone.media.ACCESS";

  static int[] ids(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    return m == null ? new int[0] : m.getAppWidgetIds(new ComponentName(c, MediaWidget.class));
  }

  static boolean hasWidgets(Context c) {
    return ids(c).length > 0;
  }

  static void drawAll(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return;
    for (int id : ids(c)) draw(c, m, id);
  }

  @Override public void onEnabled(Context c) {
    MediaBridge.syncActive(c);
  }

  @Override public void onDisabled(Context c) {
    MediaBridge.syncActive(c);
  }

  @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    MediaBridge.syncActive(c);
    for (int id : ids) draw(c, m, id);
  }

  @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
    draw(c, m, id);
  }

  @Override public void onDeleted(Context c, int[] ids) {
    SharedPreferences.Editor e = MediaBridge.store(c).edit();
    for (int id : ids) e.remove("src:" + id);
    e.apply();
    MediaBridge.syncActive(c);
  }

  @Override public void onReceive(Context c, Intent intent) {
    super.onReceive(c, intent);
    String action = intent.getAction();
    if (!ACCESS.equals(action) && !SOURCE.equals(action) && !CMD.equals(action)) return;
    if (!WidgetKit.validTap(c, intent)) return;
    int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
    if (ACCESS.equals(action)) {
      try {
        c.startActivity(MediaBridge.accessSettingsIntent());
      } catch (RuntimeException ignored) {
      }
      return;
    }
    if (SOURCE.equals(action)) {
      if (WidgetKit.hasId(ids(c), id)) cycleSource(c, id);
      return;
    }
    if (CMD.equals(action)) {
      String op = intent.getStringExtra("op");
      if (!"prev".equals(op) && !"play".equals(op) && !"pause".equals(op)
          && !"next".equals(op) && !"refresh".equals(op)) return;
      String src = WidgetKit.hasId(ids(c), id) ? sourceFor(c, id) : "local";
      if ("local".equals(src)) {
        if (!"refresh".equals(op)) MediaBridge.control(c, op);
        drawAll(c);
      } else {
        sendRemote(c, src, op, goAsync());
      }
    }
  }

  /** Find paired computers that allow "remote" control. */
  private static String nextRemoteHost(Context c, String current) {
    JSONArray list = JobsWidget.computers(c);
    java.util.List<String> hosts = new java.util.ArrayList<>();
    hosts.add("local");
    for (int i = 0; i < list.length(); i++) {
      JSONObject o = list.optJSONObject(i);
      if (o == null) continue;
      String h = o.optString("host");
      if (h.isEmpty()) continue;
      JSONArray may = o.optJSONArray("may");
      boolean canRemote = o.optBoolean("owner");
      if (!canRemote && may != null) {
        for (int j = 0; j < may.length(); j++) {
          if ("remote".equals(may.optString(j))) {
            canRemote = true;
            break;
          }
        }
      }
      if (canRemote && !hosts.contains(h)) hosts.add(h);
    }
    if (hosts.size() <= 1) return "local";
    int idx = hosts.indexOf(current);
    return hosts.get((idx + 1) % hosts.size());
  }

  private static boolean hasRemoteComputer(Context c) {
    return !"local".equals(nextRemoteHost(c, "local"));
  }

  private static String sourceFor(Context c, int id) {
    String src = MediaBridge.store(c).getString("src:" + id, "local");
    if (!"local".equals(src) && JobsWidget.computer(c, src) == null) {
      MediaBridge.store(c).edit().putString("src:" + id, "local").apply();
      return "local";
    }
    return src;
  }

  private void cycleSource(Context c, int id) {
    String next = nextRemoteHost(c, sourceFor(c, id));
    MediaBridge.store(c).edit().putString("src:" + id, next).apply();
    drawAll(c);
    if (!"local".equals(next)) {
      sendRemote(c, next, "refresh", goAsync());
    }
  }

  private static void sendRemote(Context context, String host, String op, PendingResult done) {
    Context app = context.getApplicationContext();
    new Thread(() -> {
      try {
        if (JobsWidget.emulator()) Link.loopbackOk = true;
        JSONObject pc = JobsWidget.computer(app, host);
        String pin = pc == null ? "" : pc.optString("pin", "");
        String token = JobsWidget.store(app).getString("token:" + host, "");
        if (pin.isEmpty() || token.isEmpty()) {
          saveRemote(app, host, "", "", "", false, "Open Forge to sign in");
          return;
        }
        Link.Reply stateReply = Link.get(host, pin, "/hub/api/remote", token);
        if (stateReply.status != 200) {
          saveRemote(app, host, "", "", "", false, "Not reachable (" + stateReply.status + ")");
          return;
        }
        JSONObject doc = new JSONObject(stateReply.body);
        JSONArray players = doc.optJSONArray("players");
        if (!"refresh".equals(op) && players != null && players.length() > 0) {
          JSONObject target = pickPlayer(players);
          String bus = target == null ? "" : target.optString("bus");
          if (!bus.isEmpty()) {
            Link.Reply boot = Link.get(host, pin, "/hub/api/bootstrap", token);
            String csrf = Link.field(boot.body, "csrf");
            if (boot.status == 200 && csrf != null) {
              String remoteOp = "play".equals(op) ? "toggle" : op;
              String body = new JSONObject().put("do", remoteOp).put("bus", bus).toString();
              Link.Reply post = Link.request("POST", host, pin, "/hub/api/remote",
                  Link.COOKIE + "=" + token, csrf, body);
              if (post.status == 200) {
                doc = new JSONObject(post.body);
                players = doc.optJSONArray("players");
              }
            }
          }
        }
        JSONObject cur = pickPlayer(players);
        if (cur == null) {
          saveRemote(app, host, "", "", "", false, "Nothing playing on " + JobsWidget.nameOf(app, host));
        } else {
          boolean playing = "Playing".equalsIgnoreCase(cur.optString("state"));
          saveRemote(app, host, cur.optString("title"), cur.optString("artist"),
              cur.optString("name"), playing, "");
        }
      } catch (Link.Failure | JSONException | RuntimeException e) {
        saveRemote(app, host, "", "", "", false, "Computer not reachable");
      } finally {
        drawAll(app);
        if (done != null) done.finish();
      }
    }, "forge-media-remote").start();
  }

  private static JSONObject pickPlayer(JSONArray players) {
    if (players == null || players.length() == 0) return null;
    for (int i = 0; i < players.length(); i++) {
      JSONObject p = players.optJSONObject(i);
      if (p != null && "Playing".equalsIgnoreCase(p.optString("state"))) return p;
    }
    return players.optJSONObject(0);
  }

  private static void saveRemote(Context c, String host, String title, String artist,
      String player, boolean playing, String note) {
    MediaBridge.store(c).edit()
        .putString("r_title:" + host, title)
        .putString("r_artist:" + host, artist)
        .putString("r_app:" + host, player)
        .putBoolean("r_playing:" + host, playing)
        .putString("r_note:" + host, note)
        .apply();
  }

  private static PendingIntent cmdTap(Context c, int id, String op, int slot) {
    Intent i = WidgetKit.signTap(c, new Intent(c, MediaWidget.class).setAction(CMD)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        .putExtra("op", op));
    return PendingIntent.getBroadcast(c, 20000 + id * 8 + slot, i,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static void draw(Context c, AppWidgetManager m, int id) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_media);
    WidgetKit.Palette pal = WidgetKit.localPalette(c);
    WidgetKit.applyPanel(v, R.id.bg, pal, 232);
    v.setTextColor(R.id.badge, pal.accent);
    v.setTextColor(R.id.track, pal.text);
    v.setTextColor(R.id.artist, pal.muted);

    String src = sourceFor(c, id);
    boolean isLocal = "local".equals(src);
    SharedPreferences s = MediaBridge.store(c);
    boolean compact = WidgetKit.isShort(m, id, 95);

    String badge, track, artist;
    PendingIntent headerTap;
    if (isLocal) {
      boolean hasAccess = MediaBridge.isAccessEnabled(c);
      boolean hasSession = s.getBoolean("has_session", false);
      boolean playing = s.getBoolean("playing", false);
      String app = s.getString("app", "");
      String t = s.getString("title", "");
      String a = s.getString("artist", "");
      if (hasAccess && hasSession && !t.isEmpty()) {
        badge = (playing ? "Playing" : "Paused") + (app.isEmpty() ? "" : " · " + app);
        track = t;
        artist = a.isEmpty() ? "This device" : a;
        headerTap = openPlayerOrForge(c, id, s.getString("pkg", ""));
      } else if (hasAccess) {
        badge = "This device";
        track = "Nothing playing";
        artist = "Tap play to resume your last player";
        headerTap = WidgetKit.openMain(c, 25000 + id, null, null, null);
      } else {
        badge = "Forge media";
        track = "Now playing";
        artist = "Controls ready · Tap here for song titles";
        Intent ask = WidgetKit.signTap(c, new Intent(c, MediaWidget.class).setAction(ACCESS));
        headerTap = PendingIntent.getBroadcast(c, 26000 + id, ask,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      }
    } else {
      String pcName = JobsWidget.nameOf(c, src);
      String t = s.getString("r_title:" + src, "");
      String a = s.getString("r_artist:" + src, "");
      String player = s.getString("r_app:" + src, "");
      boolean playing = s.getBoolean("r_playing:" + src, false);
      String note = s.getString("r_note:" + src, "");
      badge = (playing ? "Playing" : "Remote") + " · " + pcName + (player.isEmpty() ? "" : " · " + player);
      track = t.isEmpty() ? (note.isEmpty() ? "Tap play or ⇄ to check " + pcName : note) : t;
      artist = t.isEmpty() ? "Computer remote (updates on tap)" : a;
      headerTap = cmdTap(c, id, "refresh", 6);
    }

    v.setTextViewText(R.id.badge, badge);
    v.setTextViewText(R.id.track, track);
    WidgetKit.setRow(v, R.id.artist, compact ? null : artist);
    v.setOnClickPendingIntent(R.id.header_area, headerTap);

    boolean showSwap = hasRemoteComputer(c);
    v.setViewVisibility(R.id.swap, showSwap ? View.VISIBLE : View.GONE);
    v.setInt(R.id.swap, "setColorFilter", pal.muted);
    Intent sw = WidgetKit.signTap(c, new Intent(c, MediaWidget.class).setAction(SOURCE)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
    v.setOnClickPendingIntent(R.id.swap, PendingIntent.getBroadcast(c, 27000 + id, sw,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

    v.setOnClickPendingIntent(R.id.prev, cmdTap(c, id, "prev", 0));
    v.setOnClickPendingIntent(R.id.play, cmdTap(c, id, "play", 1));
    v.setOnClickPendingIntent(R.id.pause, cmdTap(c, id, "pause", 2));
    v.setOnClickPendingIntent(R.id.next, cmdTap(c, id, "next", 3));
    for (int bid : new int[] {R.id.prev, R.id.play, R.id.pause, R.id.next}) {
      v.setInt(bid, "setColorFilter", pal.text);
    }
    Intent viz = new Intent(c, app.forge.visualiser.PlayerActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    v.setOnClickPendingIntent(R.id.vis, PendingIntent.getActivity(c, 28000 + id, viz,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    v.setInt(R.id.vis, "setColorFilter", pal.accent);

    WidgetKit.safeUpdate(m, id, v);
  }

  private static PendingIntent openPlayerOrForge(Context c, int id, String pkg) {
    if (pkg != null && !pkg.isEmpty()) {
      Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
      if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 25000 + id, launch,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      }
    }
    return WidgetKit.openMain(c, 25000 + id, null, null, null);
  }
}
