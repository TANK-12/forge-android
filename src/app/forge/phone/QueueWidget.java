package app.forge.phone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Date;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Forge Queue home-screen widget (Relay task 1591): shows your paired
 * computer's queued and blocked Relay tasks in order, using the exact same
 * single coalesced /hub/api/jobs cache and 30-minute JobScheduler job as
 * JobsWidget (zero extra wake-ups or polling).
 */
public final class QueueWidget extends AppWidgetProvider {
  static final String REFRESH = "app.forge.phone.queue.REFRESH";
  static final String NEXT = "app.forge.phone.queue.NEXT";

  static int[] ids(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    return m == null ? new int[0] : m.getAppWidgetIds(new ComponentName(c, QueueWidget.class));
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
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_queue);
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
      }, "forge-queue-tap").start();
    }
  }

  private static PendingIntent tap(Context c, int id, String action) {
    Intent i = WidgetKit.signTap(c, new Intent(c, QueueWidget.class).setAction(action)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
    int code = 10000 + id * 2 + (NEXT.equals(action) ? 1 : 0);
    return PendingIntent.getBroadcast(c, code, i,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static void draw(Context c, AppWidgetManager m, int id) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_queue);
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
    v.setTextColor(R.id.head, pal.accent);
    int[] rows = {R.id.q1, R.id.q2, R.id.q3, R.id.q4};
    for (int rid : rows) v.setTextColor(rid, pal.text);
    v.setTextColor(R.id.foot, pal.muted);
    v.setInt(R.id.refresh, "setColorFilter", pal.text);
    v.setInt(R.id.swap, "setColorFilter", pal.text);
    v.setViewVisibility(R.id.swap, JobsWidget.computers(c).length() > 1 ? View.VISIBLE : View.GONE);
    v.setOnClickPendingIntent(R.id.refresh, tap(c, id, REFRESH));
    v.setOnClickPendingIntent(R.id.swap, tap(c, id, NEXT));

    boolean ok = !host.isEmpty() && reply != null && "ok".equals(state);
    v.setOnClickPendingIntent(R.id.root, WidgetKit.openMain(c, 10000 + id,
        ok ? JobsWidget.OPEN : null, ok ? "host" : null, ok ? host : null));

    int maxRows = maxRowsFor(c, m, id);
    String name = host.isEmpty() ? "" : JobsWidget.nameOf(c, host);
    v.setTextViewText(R.id.title, name.isEmpty() ? "Forge queue" : "Queue · " + name);
    long at = s.getLong("ok:" + host, 0);
    String updated = at == 0 ? "" : DateFormat.getTimeFormat(c).format(new Date(at));

    if (host.isEmpty()) {
      v.setTextViewText(R.id.head, "No computer yet");
      WidgetKit.setRow(v, R.id.q1, "Open Forge to pair a computer and see its Relay queue.");
      for (int i = 1; i < rows.length; i++) WidgetKit.setRow(v, rows[i], null);
      v.setTextViewText(R.id.foot, "");
    } else if (!ok) {
      String msg = s.getString("msg:" + host, "");
      String head = state.isEmpty() ? "Asking…" : "away".equals(state) ? "Not reachable"
          : "refused".equals(state) ? "Not allowed yet" : "unknown".equals(state) ? "Sign-in needed" : "Could not ask";
      v.setTextViewText(R.id.head, head);
      WidgetKit.setRow(v, R.id.q1, state.isEmpty() ? "" : msg);
      for (int i = 1; i < rows.length; i++) WidgetKit.setRow(v, rows[i], null);
      long tried = s.getLong("at:" + host, 0);
      String foot = tried == 0 ? "" : "Tried " + DateFormat.getTimeFormat(c).format(new Date(tried)) + " · Tap ↻";
      v.setTextViewText(R.id.foot, foot);
    } else {
      fillQueue(v, reply, rows, maxRows, updated);
    }
    WidgetKit.safeUpdate(m, id, v);
  }

  /** Pick row count from the widget's live height so the footer never clips (110dp -> 1 row,
      default/unset 4x2 -> 3 rows, tall >=205dp -> 4 rows). */
  static int maxRowsFor(Context c, AppWidgetManager m, int id) {
    Bundle o = m == null ? null : m.getAppWidgetOptions(id);
    if (o == null) return 3;
    boolean landscape = c.getResources().getConfiguration().orientation
        == Configuration.ORIENTATION_LANDSCAPE;
    int primary = o.getInt(landscape
        ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
    int fallback = o.getInt(landscape
        ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
    int h = primary > 0 ? primary : fallback;
    if (h <= 0) return 3;
    if (h < 135) return 1;
    if (h < 165) return 2;
    if (h < 205) return 3;
    return 4;
  }

  private static void fillQueue(RemoteViews v, JSONObject r, int[] rows, int maxRows, String updated) {
    int waiting = r.optInt("waiting");
    int needs = r.optInt("needs_you");
    int runningCount = r.optInt("running_count");
    boolean relay = r.optBoolean("relay", false);
    if (!relay) {
      v.setTextViewText(R.id.head, "No Relay queue here");
      WidgetKit.setRow(v, rows[0], "This computer does not keep Relay tasks.");
      for (int i = 1; i < rows.length; i++) WidgetKit.setRow(v, rows[i], null);
      v.setTextViewText(R.id.foot, updated.isEmpty() ? "" : "Updated " + updated);
      return;
    }
    String head;
    if (needs > 0 && waiting > 0) head = waiting + " queued · " + needs + (needs == 1 ? " needs you" : " need you");
    else if (needs > 0) head = needs + (needs == 1 ? " task needs you" : " tasks need you");
    else if (waiting > 0) head = waiting + " in queue";
    else head = "Queue clear";
    v.setTextViewText(R.id.head, head);

    String[] lines = new String[rows.length];
    int idx = 0;

    // Show blocked items first (need Emerson), then queued/deferred items in queue order.
    JSONArray blocked = r.optJSONArray("blocked");
    if (blocked != null) {
      for (int i = 0; i < blocked.length() && idx < maxRows; i++) {
        JSONObject t = blocked.optJSONObject(i);
        if (t != null) lines[idx++] = "[needs you] #" + t.optInt("id") + " " + t.optString("title");
      }
    }
    JSONArray queued = r.optJSONArray("queued");
    if (queued != null) {
      for (int i = 0; i < queued.length() && idx < maxRows; i++) {
        JSONObject t = queued.optJSONObject(i);
        if (t == null) continue;
        String tag = "deferred".equals(t.optString("status")) ? "[wait] "
            : "high".equals(t.optString("priority")) || "urgent".equals(t.optString("priority")) ? "[high] " : "";
        lines[idx++] = tag + "#" + t.optInt("id") + " " + t.optString("title");
      }
    }
    // Backward compatibility with older hubs that only report counts + running list.
    if (idx == 0 && (waiting > 0 || needs > 0)) {
      lines[idx++] = waiting + " waiting · " + needs + " blocked (update Forge on PC for titles)";
    } else if (idx == 0) {
      JSONArray running = r.optJSONArray("running");
      if (running != null && running.length() > 0) {
        JSONObject cur = running.optJSONObject(0);
        if (cur != null) lines[idx++] = "Running now: #" + cur.optInt("id") + " " + cur.optString("title");
      } else {
        lines[idx++] = "Nothing waiting right now.";
      }
    }
    int totalItems = waiting + needs;
    if (totalItems > maxRows && lines[maxRows - 1] != null) {
      lines[maxRows - 1] += " (+" + (totalItems - maxRows) + " more)";
    }
    for (int i = 0; i < rows.length; i++) WidgetKit.setRow(v, rows[i], i < maxRows ? lines[i] : null);

    StringBuilder foot = new StringBuilder();
    if (runningCount > 0) foot.append(runningCount).append(" running");
    else foot.append("Idle");
    if (!updated.isEmpty()) foot.append(" · ").append(updated);
    v.setTextViewText(R.id.foot, foot.toString());
  }
}
