package app.forge.phone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Date;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Forge Next Up home-screen widget (Relay task 1591): single-focus spotlight on
 * what matters next — a blocked task needing you, the next queued Relay task,
 * or active long-job progress — sharing JobsWidget's single coalesced cache and
 * 30-minute JobScheduler job (zero extra battery or network polling).
 */
public final class NextUpWidget extends AppWidgetProvider {
  static final String REFRESH = "app.forge.phone.nextup.REFRESH";
  static final String NEXT = "app.forge.phone.nextup.NEXT";

  static int[] ids(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    return m == null ? new int[0] : m.getAppWidgetIds(new ComponentName(c, NextUpWidget.class));
  }

  @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    for (int id : ids) draw(c, m, id);
    boolean stale = false;
    for (int id : ids) {
      String host = JobsWidget.hostFor(c, id);
      if (!host.isEmpty()
          && System.currentTimeMillis() - JobsWidget.store(c).getLong("at:" + host, 0) > JobsWidget.EVERY) {
        stale = true;
      }
    }
    if (stale) JobsWidget.askOnce(c);
    else JobsWidget.plan(c);
  }

  @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
    draw(c, m, id);
  }

  @Override public void onDeleted(Context c, int[] ids) {
    WidgetKit.clearHostPicks(c, ids);
    JobsWidget.plan(c);
  }

  @Override public void onDisabled(Context c) {
    JobsWidget.plan(c);
  }

  @Override public void onReceive(Context c, Intent intent) {
    super.onReceive(c, intent);
    String action = intent.getAction();
    if (!REFRESH.equals(action) && !NEXT.equals(action)) return;
    if (!WidgetKit.validTap(c, intent)) return;
    int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
    if (!WidgetKit.hasId(ids(c), id)) return;
    if (NEXT.equals(action)) {
      JSONArray list = JobsWidget.computers(c);
      String now = JobsWidget.hostFor(c, id);
      int at = -1;
      for (int i = 0; i < list.length(); i++) {
        JSONObject o = list.optJSONObject(i);
        if (o != null && now.equals(o.optString("host"))) at = i;
      }
      JSONObject next = list.length() == 0 ? null : list.optJSONObject((at + 1) % list.length());
      if (next != null) JobsWidget.store(c).edit().putString("for:" + id, next.optString("host")).apply();
      draw(c, AppWidgetManager.getInstance(c), id);
    }
    if (REFRESH.equals(action) || NEXT.equals(action)) {
      if (REFRESH.equals(action)) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_next_up);
        v.setTextViewText(R.id.foot, "Asking…");
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m != null) {
          try {
            m.partiallyUpdateAppWidget(id, v);
          } catch (RuntimeException ignored) {
          }
        }
      }
      PendingResult done = goAsync();
      final Context app = c.getApplicationContext();
      new Thread(() -> {
        try {
          String host = JobsWidget.hostFor(app, id);
          synchronized (JobsWidget.LOCK) {
            if (!host.isEmpty()) JobsWidget.fetch(app, host);
          }
          WidgetKit.redrawRemoteWidgets(app);
          JobsWidget.plan(app);
        } finally {
          done.finish();
        }
      }, "forge-nextup-tap").start();
    }
  }

  private static PendingIntent tap(Context c, int id, String action) {
    Intent i = WidgetKit.signTap(c, new Intent(c, NextUpWidget.class).setAction(action)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
    int code = 30000 + id * 2 + (NEXT.equals(action) ? 1 : 0);
    return PendingIntent.getBroadcast(c, code, i,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static void draw(Context c, AppWidgetManager m, int id) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_next_up);
    String host = JobsWidget.hostFor(c, id);
    SharedPreferences s = JobsWidget.store(c);
    String state = host.isEmpty() ? "none" : s.getString("state:" + host, "");
    JSONObject reply = null;
    try {
      String raw = s.getString("reply:" + host, "");
      if (!raw.isEmpty()) reply = new JSONObject(raw);
    } catch (JSONException e) {
      reply = null;
    }
    WidgetKit.Palette pal = WidgetKit.palette(c, reply == null ? null : reply.optJSONObject("theme"));
    WidgetKit.applyPanel(v, R.id.bg, pal, 232);
    v.setTextColor(R.id.title, pal.muted);
    v.setTextColor(R.id.eyebrow, pal.accent);
    v.setTextColor(R.id.primary, pal.text);
    v.setTextColor(R.id.secondary, pal.muted);
    v.setTextColor(R.id.foot, pal.muted);
    v.setInt(R.id.refresh, "setColorFilter", pal.text);
    v.setInt(R.id.swap, "setColorFilter", pal.text);
    if (Build.VERSION.SDK_INT >= 31) {
      v.setColorStateList(R.id.bar, "setProgressTintList", ColorStateList.valueOf(pal.accent));
    }
    v.setViewVisibility(R.id.swap, JobsWidget.computers(c).length() > 1 ? View.VISIBLE : View.GONE);
    v.setOnClickPendingIntent(R.id.refresh, tap(c, id, REFRESH));
    v.setOnClickPendingIntent(R.id.swap, tap(c, id, NEXT));

    boolean ok = !host.isEmpty() && reply != null && "ok".equals(state);
    if (host.isEmpty()) {
      v.setOnClickPendingIntent(R.id.root, WidgetKit.openMain(c, 30000 + id,
          QuickForgeWidget.OPEN_OFFLINE, "app", "focus"));
    } else {
      v.setOnClickPendingIntent(R.id.root, WidgetKit.openMain(c, 30000 + id,
          ok ? JobsWidget.OPEN : null, ok ? "host" : null, ok ? host : null));
    }

    boolean small = WidgetKit.isShort(m, id, 120);
    String name = host.isEmpty() ? "" : JobsWidget.nameOf(c, host);
    v.setTextViewText(R.id.title, name.isEmpty() ? "Next up" : "Next up · " + name);
    long at = s.getLong("ok:" + host, 0);
    String updated = at == 0 ? "" : "Updated " + DateFormat.getTimeFormat(c).format(new Date(at));
    v.setViewVisibility(R.id.bar, View.GONE);

    if (host.isEmpty()) {
      v.setTextViewText(R.id.eyebrow, "On this device");
      v.setTextViewText(R.id.primary, "Open Focus or pair a computer");
      WidgetKit.setRow(v, R.id.secondary, small ? null : "Pair a computer in Forge to see what is next in Relay.");
      v.setTextViewText(R.id.foot, "Tap to open Focus");
    } else if (!ok) {
      String msg = s.getString("msg:" + host, "");
      String head = state.isEmpty() ? "Asking…" : "away".equals(state) ? "Not reachable"
          : "refused".equals(state) ? "Not allowed yet" : "unknown".equals(state) ? "Sign-in needed" : "Could not ask";
      v.setTextViewText(R.id.eyebrow, head);
      v.setTextViewText(R.id.primary, state.isEmpty() ? "Checking " + name + "…" : msg);
      WidgetKit.setRow(v, R.id.secondary, null);
      v.setTextViewText(R.id.foot, "Tap ↻ to try again");
    } else {
      fillNextUp(v, reply, small, updated);
    }
    WidgetKit.safeUpdate(m, id, v);
  }

  private static void fillNextUp(RemoteViews v, JSONObject r, boolean small, String updated) {
    JSONArray blocked = r.optJSONArray("blocked");
    JSONObject firstBlocked = blocked != null && blocked.length() > 0 ? blocked.optJSONObject(0) : null;
    JSONObject nextQueued = r.optJSONObject("next_up");
    if (nextQueued == null) {
      JSONArray queued = r.optJSONArray("queued");
      if (queued != null && queued.length() > 0) nextQueued = queued.optJSONObject(0);
    }
    JSONArray running = r.optJSONArray("running");
    JSONObject firstRunning = running != null && running.length() > 0 ? running.optJSONObject(0) : null;
    JSONArray longs = r.optJSONArray("long");
    JSONObject longJob = longs != null && longs.length() > 0 ? longs.optJSONObject(0) : null;
    if (longJob != null && longJob.optBoolean("ended")) longJob = null;

    int waiting = r.optInt("waiting");
    int needs = r.optInt("needs_you");

    String eyebrow, primary, secondary = null;
    if (firstBlocked != null) {
      eyebrow = "Needs you" + (needs > 1 ? " (" + needs + ")" : "");
      primary = "#" + firstBlocked.optInt("id") + "  " + firstBlocked.optString("title");
      if (nextQueued != null) {
        secondary = "Next in queue: #" + nextQueued.optInt("id") + " " + nextQueued.optString("title");
      } else if (firstRunning != null) {
        secondary = "Running now: #" + firstRunning.optInt("id") + " " + firstRunning.optString("title");
      }
    } else if (nextQueued != null) {
      eyebrow = "deferred".equals(nextQueued.optString("status")) ? "Deferred next" : "Next in queue";
      primary = "#" + nextQueued.optInt("id") + "  " + nextQueued.optString("title");
      if (firstRunning != null) {
        secondary = "Now: #" + firstRunning.optInt("id") + " " + firstRunning.optString("title");
      } else if (waiting > 1) {
        secondary = (waiting - 1) + " more waiting behind it";
      }
    } else if (longJob != null) {
      eyebrow = "Long job running";
      primary = longJob.optString("name");
      boolean known = !longJob.isNull("percent");
      int pct = known ? (int) Math.round(longJob.optDouble("percent", 0)) : 0;
      v.setViewVisibility(R.id.bar, View.VISIBLE);
      v.setProgressBar(R.id.bar, 100, pct, !known);
      String left = longJob.isNull("left") ? "measuring…" : longJob.optString("left") + " left";
      secondary = (known ? pct + "% · " : "") + left;
    } else if (firstRunning != null) {
      eyebrow = "Running now";
      primary = "#" + firstRunning.optInt("id") + "  " + firstRunning.optString("title");
      secondary = waiting > 0 ? waiting + " waiting in queue" : "Nothing else queued";
    } else if (needs > 0 || waiting > 0) {
      eyebrow = needs > 0 ? "Needs you" : "Waiting in queue";
      primary = waiting + " queued · " + needs + " blocked";
      secondary = "Tap to open Relay";
    } else {
      eyebrow = "All clear";
      primary = "Nothing waiting in Relay";
      JSONObject last = r.optJSONObject("last_done");
      if (last != null) {
        secondary = "Last done: #" + last.optInt("id") + " " + last.optString("title");
      }
    }

    v.setTextViewText(R.id.eyebrow, eyebrow);
    v.setTextViewText(R.id.primary, primary);
    WidgetKit.setRow(v, R.id.secondary, small ? null : secondary);
    v.setTextViewText(R.id.foot, updated);
  }
}
