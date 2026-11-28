package app.forge.phone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Forge Quick Launch home-screen widget (Relay task 1591): compact 5-button
 * Forge dock for Home, Visualiser, offline Notes, offline Focus, and Relay.
 *
 * Battery: static RemoteViews only (updatePeriodMillis=0), zero background
 * jobs, threads or polling. Redraws only when added, resized or theme changes.
 */
public final class QuickForgeWidget extends AppWidgetProvider {
  public static final String OPEN_OFFLINE = "app.forge.phone.OPEN_OFFLINE";

  static int[] ids(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    return m == null ? new int[0] : m.getAppWidgetIds(new ComponentName(c, QuickForgeWidget.class));
  }

  static void drawAll(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return;
    for (int id : ids(c)) draw(c, m, id);
  }

  @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    for (int id : ids) draw(c, m, id);
  }

  @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
    draw(c, m, id);
  }

  static void draw(Context c, AppWidgetManager m, int id) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
    WidgetKit.Palette pal = WidgetKit.localPalette(c);
    WidgetKit.applyPanel(v, R.id.bg, pal, 232);

    v.setTextColor(R.id.q_home, pal.accent);
    v.setTextColor(R.id.q_viz, pal.text);
    v.setTextColor(R.id.q_notes, pal.text);
    v.setTextColor(R.id.q_focus, pal.text);
    v.setTextColor(R.id.q_relay, pal.accent);

    v.setOnClickPendingIntent(R.id.q_home, WidgetKit.openMain(c, 40001, OPEN_OFFLINE, "pane", "home"));

    Intent viz = new Intent(c, app.forge.visualiser.PlayerActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    v.setOnClickPendingIntent(R.id.q_viz, PendingIntent.getActivity(c, 40002, viz,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

    v.setOnClickPendingIntent(R.id.q_notes, WidgetKit.openMain(c, 40003, OPEN_OFFLINE, "app", "notes"));
    v.setOnClickPendingIntent(R.id.q_focus, WidgetKit.openMain(c, 40004, OPEN_OFFLINE, "app", "focus"));

    JSONArray pcs = JobsWidget.computers(c);
    JSONObject first = pcs.length() > 0 ? pcs.optJSONObject(0) : null;
    String host = first == null ? "" : first.optString("host");
    if (!host.isEmpty()) {
      v.setOnClickPendingIntent(R.id.q_relay, WidgetKit.openMain(c, 40005, JobsWidget.OPEN, "host", host));
    } else {
      v.setOnClickPendingIntent(R.id.q_relay, WidgetKit.openMain(c, 40005, OPEN_OFFLINE, "pane", "computers"));
    }

    WidgetKit.safeUpdate(m, id, v);
  }
}
