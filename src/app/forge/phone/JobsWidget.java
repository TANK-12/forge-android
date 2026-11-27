package app.forge.phone;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
 * Forge job status on the home screen (Relay task 1301): what a paired
 * computer is working on, from its phone link's /hub/api/jobs (phone/
 * phone_jobs.py): Relay tasks running and waiting, the last finished one and
 * a long job with its measured progress. A tap opens that computer's Jobs
 * page in the app; ↻ asks again; ⇄ picks the next paired computer.
 *
 * It lives in the main Forge app because the pairing does: each computer's
 * pinned certificate and its sign-in token, which Main copies from the
 * WebView's cookie store into this app's private "jobs" store (the widget
 * never starts a WebView).
 *
 * Battery: it asks when the app is open, on a tap, and otherwise at most
 * every 30 minutes (a JobScheduler job on an unmetered network with the
 * battery not low). When no widget's computer answered last time the job is
 * cancelled, so nothing wakes the device for a computer that is away; the
 * widget says so, and a tap or opening the app tries again.
 */
public class JobsWidget extends AppWidgetProvider {
  static final String STORE = "jobs";
  static final String REFRESH = "app.forge.phone.jobs.REFRESH";
  static final String NEXT = "app.forge.phone.jobs.NEXT";
  static final String OPEN = "app.forge.phone.JOBS";
  static final int JOB = 1301;
  static final long EVERY = 30 * 60 * 1000L;
  static final long QUIET = 60 * 1000L; // the app's own refresh skips a computer asked this recently
  static final int DEFAULT_PANEL = 0xff121826;
  static final Object LOCK = new Object();

  static SharedPreferences store(Context c) {
    return c.getSharedPreferences(STORE, Context.MODE_PRIVATE);
  }

  // -- the pairing, as Main keeps it -------------------------------------------

  static JSONArray computers(Context c) {
    try {
      return new JSONArray(c.getSharedPreferences("forge", Context.MODE_PRIVATE).getString("computers", "[]"));
    } catch (JSONException e) {
      return new JSONArray();
    }
  }

  static JSONObject computer(Context c, String host) {
    JSONArray list = computers(c);
    for (int i = 0; i < list.length(); i++) {
      JSONObject o = list.optJSONObject(i);
      if (o != null && !host.isEmpty() && host.equals(o.optString("host"))) return o;
    }
    return null;
  }

  /** Main keeps each computer's sign-in token here too (pairing, opening the app). */
  static void keepToken(Context c, String host, String token) {
    if (host == null || host.isEmpty()) return;
    SharedPreferences.Editor e = store(c).edit();
    if (token == null || token.isEmpty()) e.remove("token:" + host);
    else e.putString("token:" + host, token);
    e.apply();
  }

  /** A computer's pairing moved to a new address (Main.moveComputer): its token and widget picks follow. */
  static void moved(Context c, String old, String at, String token) {
    synchronized (LOCK) {
      SharedPreferences s = store(c);
      SharedPreferences.Editor e = s.edit();
      for (java.util.Map.Entry<String, ?> kv : s.getAll().entrySet()) {
        if (kv.getKey().startsWith("for:") && old.equals(kv.getValue())) e.putString(kv.getKey(), at);
      }
      e.remove("token:" + old).remove("reply:" + old).remove("state:" + old)
          .remove("msg:" + old).remove("at:" + old).remove("ok:" + old);
      if (token != null && !token.isEmpty()) e.putString("token:" + at, token);
      e.commit();
    }
    refreshSoon(c, true);
  }

  /** A forgotten computer: its token and what was last heard from it go. */
  static void forget(Context c, String host) {
    synchronized (LOCK) { // not under a refresh that would write its answer back
      store(c).edit().remove("token:" + host).remove("reply:" + host).remove("state:" + host)
          .remove("msg:" + host).remove("at:" + host).remove("ok:" + host).commit();
    }
  }

  /** The computer a widget shows: its own pick while still paired, else the first. */
  static String hostFor(Context c, int id) {
    String host = store(c).getString("for:" + id, "");
    if (computer(c, host) != null) return host;
    JSONArray list = computers(c);
    JSONObject first = list.length() > 0 ? list.optJSONObject(0) : null;
    host = first == null ? "" : first.optString("host", "");
    store(c).edit().putString("for:" + id, host).apply();
    return host;
  }

  static String nameOf(Context c, String host) {
    String reply = store(c).getString("reply:" + host, "");
    try {
      String n = reply.isEmpty() ? "" : new JSONObject(reply).optString("computer", "");
      if (!n.isEmpty()) return n;
    } catch (JSONException e) {
      // an old reply: the address will do
    }
    JSONObject o = computer(c, host);
    String n = o == null ? "" : o.optString("name", "");
    return n.isEmpty() ? host : n;
  }

  // -- asking the computer ---------------------------------------------------------

  /**
   * Ask one computer what it is working on and keep the answer. The state
   * kept is ok, away (no answer, or its phone link is off), refused (not
   * allowed to see jobs), unknown (the computer forgot this device) or error.
   */
  /** Loopback is the test computer only on an emulator (adb reverse); on a phone it is the phone. */
  static boolean emulator() {
    return "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE);
  }

  static void fetch(Context c, String host) {
    // the widget's process may start without Main (a refresh, a tap): set the flag here too
    if (emulator()) Link.loopbackOk = true;
    JSONObject o = computer(c, host);
    String pin = o == null ? "" : o.optString("pin", "");
    String token = store(c).getString("token:" + host, "");
    String state, msg = "";
    String name = nameOf(c, host);
    String body = null;
    if (pin.isEmpty()) {
      state = "unknown";
      msg = "This computer is no longer paired. Open Forge to pair one.";
    } else if (token.isEmpty()) {
      state = "unknown";
      msg = "Open Forge once so this widget can sign in to " + name + ".";
    } else {
      try {
        Link.Reply r = Link.get(host, pin, "/hub/api/jobs", token);
        if (r.status == 200) {
          new JSONObject(r.body); // only a whole answer is kept
          state = "ok";
          body = r.body;
        } else if (r.status == 401) {
          state = "unknown";
          msg = name + " no longer knows this device. Open Forge and pair it again.";
        } else if (r.status == 403) {
          state = "refused";
          msg = "On " + name + ", open Devices, Phones and allow this device to see what the computer is working on.";
        } else if (r.status == 503) {
          state = "away";
          msg = "The phone link is off on " + name + ". Switch it on in Devices, Phones there.";
        } else {
          state = "error";
          msg = Link.said(r);
        }
      } catch (Link.Failure f) {
        boolean away = f.getMessage() != null && f.getMessage().equals(Link.unreachable());
        state = away ? "away" : "error";
        msg = away ? name + " does not answer. It may be off, or this device away from its Wi-Fi." : f.getMessage();
      } catch (JSONException e) {
        state = "error";
        msg = name + " sent an answer this widget cannot read. Update Forge there.";
      }
    }
    SharedPreferences.Editor e = store(c).edit().putString("state:" + host, state).putString("msg:" + host, msg)
        .putLong("at:" + host, System.currentTimeMillis());
    if (body != null) e.putString("reply:" + host, body).putLong("ok:" + host, System.currentTimeMillis());
    e.apply();
  }

  static int[] ids(Context c) {
    return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, JobsWidget.class));
  }

  /**
   * Ask every widget's computer once (each computer once) and redraw.
   * periodic: the 30-minute job, which skips computers that did not answer
   * last time. force: a tap, which asks even if the app just did.
   */
  static void refreshAll(Context c, boolean periodic, boolean force) {
    int[] ids = ids(c);
    if (ids.length == 0) {
      unschedule(c);
      return;
    }
    synchronized (LOCK) {
      java.util.Set<String> asked = new java.util.HashSet<>();
      long now = System.currentTimeMillis();
      for (int id : ids) {
        String host = hostFor(c, id);
        if (host.isEmpty() || !asked.add(host)) continue;
        String last = store(c).getString("state:" + host, "");
        if (periodic && !last.isEmpty() && !"ok".equals(last)) continue; // away: no wake-ups for it
        if (!force && now - store(c).getLong("at:" + host, 0) < QUIET) continue;
        fetch(c, host);
      }
    }
    drawAll(c);
    plan(c);
  }

  /** Ask in the background, from the app or a tap. */
  static void refreshSoon(Context c, boolean force) {
    final Context app = c.getApplicationContext();
    new Thread(() -> refreshAll(app, false, force), "forge-jobs").start();
  }

  // -- the 30-minute job -------------------------------------------------------------

  /** Keep the 30-minute job while some widget's computer answers; else no job. */
  static void plan(Context c) {
    boolean any = false;
    for (int id : ids(c)) {
      String host = hostFor(c, id);
      if (!host.isEmpty() && "ok".equals(store(c).getString("state:" + host, ""))) any = true;
    }
    if (any) schedule(c);
    else unschedule(c);
  }

  static void schedule(Context c) {
    JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (js == null || js.getPendingJob(JOB) != null) return;
    JobInfo.Builder b = new JobInfo.Builder(JOB, new ComponentName(c, Refresh.class))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setRequiresBatteryNotLow(true)
        .setPeriodic(EVERY, 10 * 60 * 1000L)
        .setPersisted(true); // kept over a restart
    js.schedule(b.build());
  }

  /**
   * Added, or the home screen came back after a restart: one ask as soon as
   * there is a network (a restart is often before Wi-Fi), run by the system
   * so the app cannot be frozen half way.
   */
  static void askOnce(Context c) {
    JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (js == null) return;
    js.schedule(new JobInfo.Builder(JOB + 1, new ComponentName(c, Refresh.class))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .build());
  }

  static void unschedule(Context c) {
    JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (js != null) js.cancel(JOB);
  }

  /** The 30-minute job: ask the computers that answered last time. */
  public static final class Refresh extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
      final boolean once = params.getJobId() == JOB + 1;
      new Thread(() -> {
        refreshAll(getApplicationContext(), !once, true);
        jobFinished(params, false);
      }, "forge-jobs-every").start();
      return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
      return false; // the next period asks again
    }
  }

  // -- the home screen ------------------------------------------------------------------

  @Override
  public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    drawAll(c);
    // added, or the home screen came back after a restart: ask only when what
    // is shown is older than the 30 minutes (or was never asked)
    boolean stale = false;
    for (int id : ids) {
      String host = hostFor(c, id);
      if (!host.isEmpty() && System.currentTimeMillis() - store(c).getLong("at:" + host, 0) > EVERY) stale = true;
    }
    if (stale) askOnce(c);
    else plan(c);
  }

  @Override
  public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
    draw(c, m, id);
  }

  @Override
  public void onDeleted(Context c, int[] ids) {
    SharedPreferences.Editor e = store(c).edit();
    for (int id : ids) e.remove("for:" + id);
    e.apply();
  }

  @Override
  public void onDisabled(Context c) {
    unschedule(c);
  }

  @Override
  public void onReceive(Context c, Intent intent) {
    super.onReceive(c, intent);
    String action = intent.getAction();
    int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
    if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return;
    if (NEXT.equals(action)) {
      JSONArray list = computers(c);
      String now = hostFor(c, id);
      int at = -1;
      for (int i = 0; i < list.length(); i++) {
        if (now.equals(list.optJSONObject(i) == null ? "" : list.optJSONObject(i).optString("host"))) at = i;
      }
      JSONObject next = list.length() == 0 ? null : list.optJSONObject((at + 1) % list.length());
      if (next != null) store(c).edit().putString("for:" + id, next.optString("host")).apply();
      draw(c, AppWidgetManager.getInstance(c), id);
    }
    if (REFRESH.equals(action) || NEXT.equals(action)) {
      if (REFRESH.equals(action)) {
        // say it is asking at once (before the answer can come back): it can take a few seconds
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_jobs);
        v.setTextViewText(R.id.foot, "Asking…");
        AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(id, v);
      }
      PendingResult done = goAsync();
      final Context app = c.getApplicationContext();
      new Thread(() -> {
        try {
          String host = hostFor(app, id);
          synchronized (LOCK) {
            if (!host.isEmpty()) fetch(app, host);
          }
          drawAll(app);
          plan(app);
        } finally {
          done.finish();
        }
      }, "forge-jobs-tap").start();
    }
  }

  static void drawAll(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    for (int id : ids(c)) draw(c, m, id);
  }

  static int colour(JSONObject theme, String key, int fallback) {
    try {
      String v = theme == null ? "" : theme.optString(key, "");
      return v.isEmpty() ? fallback : Color.parseColor(v);
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  static String ago(long at) {
    long s = Math.max(0, System.currentTimeMillis() / 1000 - at);
    if (s < 90) return "just now";
    if (s < 5400) return Math.round(s / 60.0) + " min ago";
    if (s < 129600) return Math.round(s / 3600.0) + " h ago";
    return Math.round(s / 86400.0) + " days ago";
  }

  static PendingIntent tap(Context c, int id, String action) {
    Intent i = new Intent(c, JobsWidget.class).setAction(action)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
    int code = id * 2 + (NEXT.equals(action) ? 1 : 0);
    return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static void draw(Context c, AppWidgetManager m, int id) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_jobs);
    String host = hostFor(c, id);
    SharedPreferences s = store(c);
    String state = host.isEmpty() ? "none" : s.getString("state:" + host, "");
    JSONObject reply = null;
    try {
      String raw = s.getString("reply:" + host, "");
      if (!raw.isEmpty()) reply = new JSONObject(raw);
    } catch (JSONException e) {
      reply = null;
    }
    JSONObject theme = reply == null ? null : reply.optJSONObject("theme");
    int text = colour(theme, "text", 0xffffffff);
    int muted = colour(theme, "muted", 0xb3ffffff);
    int accent = colour(theme, "accent", 0xff7cc4a4);
    v.setInt(R.id.bg, "setColorFilter", colour(theme, "panel", DEFAULT_PANEL));
    v.setInt(R.id.bg, "setImageAlpha", 230);
    v.setTextColor(R.id.title, muted);
    v.setTextColor(R.id.head, accent);
    for (int t : new int[] {R.id.line1, R.id.line2, R.id.long_name}) v.setTextColor(t, text);
    for (int t : new int[] {R.id.long_text, R.id.counts, R.id.last, R.id.foot}) v.setTextColor(t, muted);
    v.setInt(R.id.refresh, "setColorFilter", text);
    v.setInt(R.id.swap, "setColorFilter", text);
    if (Build.VERSION.SDK_INT >= 31) {
      v.setColorStateList(R.id.bar, "setProgressTintList", ColorStateList.valueOf(accent));
    }
    v.setViewVisibility(R.id.swap, computers(c).length() > 1 ? View.VISIBLE : View.GONE);
    v.setOnClickPendingIntent(R.id.refresh, tap(c, id, REFRESH));
    v.setOnClickPendingIntent(R.id.swap, tap(c, id, NEXT));

    // a tap on the rest opens the computer's Jobs page (or the start page when it is not reachable)
    Intent open = new Intent(c, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (!host.isEmpty() && reply != null && "ok".equals(state)) open.setAction(OPEN).putExtra("host", host);
    v.setOnClickPendingIntent(R.id.root,
        PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

    Bundle o = m.getAppWidgetOptions(id);
    boolean small = o != null && o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200) < 150;
    String name = host.isEmpty() ? "" : nameOf(c, host);
    v.setTextViewText(R.id.title, name.isEmpty() ? "Forge jobs" : "Forge jobs on " + name);
    long at = s.getLong("ok:" + host, 0);
    String updated = at == 0 ? "" : "Updated " + DateFormat.getTimeFormat(c).format(new Date(at));

    if (host.isEmpty()) {
      words(v, "No computer yet", "Open Forge and pair a computer: this widget then shows what it is working on.", small);
      v.setTextViewText(R.id.foot, "");
    } else if (reply == null || !"ok".equals(state)) {
      // nothing usable, or the last ask failed: say why in plain words, with when it last worked
      String msg = s.getString("msg:" + host, "");
      String head = state.isEmpty() ? "Asking…" : "away".equals(state) ? "Not reachable"
          : "refused".equals(state) ? "Not allowed yet" : "unknown".equals(state) ? "Sign-in needed" : "Could not ask";
      words(v, head, state.isEmpty() ? "" : msg, small);
      long tried = s.getLong("at:" + host, 0);
      String foot = tried == 0 ? "" : "Tried " + DateFormat.getTimeFormat(c).format(new Date(tried)) + ". Tap ↻ to try again.";
      if (reply != null && at > 0) foot += " Last news " + ago(at / 1000) + ".";
      v.setTextViewText(R.id.foot, foot);
    } else {
      v.setInt(R.id.foot, "setMaxLines", 2);
      fill(v, reply, small);
      v.setTextViewText(R.id.foot, updated);
    }
    m.updateAppWidget(id, v);
  }

  /** A message in place of the lists. */
  static void words(RemoteViews v, String head, String body, boolean small) {
    v.setTextViewText(R.id.head, head);
    v.setTextViewText(R.id.line1, body);
    v.setViewVisibility(R.id.line1, body.isEmpty() ? View.GONE : View.VISIBLE);
    // short widget: the message and the "tap to try again" line both stay in view
    v.setInt(R.id.line1, "setMaxLines", small ? 2 : 4);
    v.setInt(R.id.foot, "setMaxLines", small ? 1 : 2);
    for (int t : new int[] {R.id.line2, R.id.long_box, R.id.counts, R.id.last}) v.setViewVisibility(t, View.GONE);
  }

  static void fill(RemoteViews v, JSONObject r, boolean small) {
    JSONArray running = r.optJSONArray("running");
    int count = r.optInt("running_count", running == null ? 0 : running.length());
    boolean relay = r.optBoolean("relay", false);
    JSONArray longs = r.optJSONArray("long");
    JSONObject job = longs == null || longs.length() == 0 ? null : longs.optJSONObject(0);
    v.setTextViewText(R.id.head, count > 0 ? count + " running" : job != null && !job.optBoolean("ended") ? "A long job is running"
        : relay ? "Nothing running" : "No Relay tasks here");
    String[] rows = new String[2];
    for (int i = 0; running != null && i < Math.min(2, running.length()); i++) {
      JSONObject t = running.optJSONObject(i);
      if (t != null) rows[i] = "#" + t.optInt("id") + "  " + t.optString("title");
    }
    if (rows[1] != null && count > 2) rows[1] += "  (+" + (count - 2) + " more)";
    v.setInt(R.id.line1, "setMaxLines", 1);
    row(v, R.id.line1, rows[0]);
    row(v, R.id.line2, small ? null : rows[1]);
    if (job != null && !(small && count > 0)) {
      v.setViewVisibility(R.id.long_box, View.VISIBLE);
      v.setTextViewText(R.id.long_name, job.optString("name") + (job.optInt("task") > 0 ? "  (#" + job.optInt("task") + ")" : ""));
      boolean known = !job.isNull("percent");
      int pct = known ? (int) Math.round(job.optDouble("percent", 0)) : 0;
      v.setProgressBar(R.id.bar, 100, pct, !known && !job.optBoolean("ended"));
      int total = job.optInt("total");
      String unit = job.optString("unit", "steps");
      String done = total > 0 ? job.optInt("done") + " of " + total + " " + unit + " (" + pct + "%)"
          : job.optInt("done") + " " + unit + " done";
      String left = job.optBoolean("ended") ? "ended" : job.isNull("left") ? "no estimate yet" : job.optString("left") + " left";
      v.setTextViewText(R.id.long_text, done + " · " + left);
    } else {
      v.setViewVisibility(R.id.long_box, View.GONE);
    }
    if (relay) {
      int needs = r.optInt("needs_you");
      row(v, R.id.counts, r.optInt("waiting") + " waiting · " + needs + (needs == 1 ? " needs you" : " need you"));
      JSONObject last = r.optJSONObject("last_done");
      row(v, R.id.last, small || last == null ? null
          : "Last done: #" + last.optInt("id") + " " + last.optString("title") + ", " + ago(last.optLong("at")));
    } else {
      row(v, R.id.counts, null);
      row(v, R.id.last, null);
    }
  }

  static void row(RemoteViews v, int id, String text) {
    v.setViewVisibility(id, text == null ? View.GONE : View.VISIBLE);
    if (text != null) v.setTextViewText(id, text);
  }
}
