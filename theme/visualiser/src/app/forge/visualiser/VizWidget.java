/* The Forge visualiser home-screen widget: the look in use (on the
   wallpaper when the visualiser is the wallpaper, else in the player), a
   next-look button, music keys and the full-screen player. Home screens
   cannot run a live picture in a widget, so the moving visualiser lives in
   the wallpaper and the player; the widget's bars are an animated drawable
   the home screen plays by itself (task 1322), in the look's colours, and
   the widget only redraws when tapped. */
package app.forge.visualiser;

import android.app.PendingIntent;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.view.KeyEvent;
import android.widget.RemoteViews;
import app.forge.look.MediaKeys;

public final class VizWidget extends AppWidgetProvider {
  static final String KEY = "app.forge.visualiser.KEY", LOOK = "app.forge.visualiser.NEXT_LOOK";

  @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    draw(c, m, ids);
  }

  @Override public void onReceive(Context c, Intent intent) {
    if (KEY.equals(intent.getAction())) {
      MediaKeys.press(c, intent.getIntExtra("code", 0));
      return;
    }
    if (LOOK.equals(intent.getAction())) {
      String key = onWallpaper(c) ? Prefs.WALL : Prefs.PRESET;
      Presets.Preset next = Presets.after(c, Prefs.get(c, key));
      if (next != null) Prefs.store(c).edit().putString(key, next.id).apply();
      refreshAll(c);
      return;
    }
    super.onReceive(c, intent);
  }

  /** Whether Forge Visualiser is the live wallpaper right now. */
  static boolean onWallpaper(Context c) {
    try {
      WallpaperInfo info = WallpaperManager.getInstance(c).getWallpaperInfo();
      // the class too: inside the main Forge app the particles wallpaper shares the package
      return info != null && c.getPackageName().equals(info.getPackageName())
          && VizWallpaper.class.getName().equals(info.getServiceName());
    } catch (RuntimeException e) {
      return false;
    }
  }

  static void refreshAll(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return;
    draw(c, m, m.getAppWidgetIds(new ComponentName(c, VizWidget.class)));
  }

  static void draw(Context c, AppWidgetManager m, int[] ids) {
    if (ids == null || ids.length == 0) return;
    boolean wall = onWallpaper(c);
    Presets.Preset p = Presets.find(c, Prefs.get(c, wall ? Prefs.WALL : Prefs.PRESET));
    String where = wall ? "On your wallpaper" : "In the player";
    Intent player = new Intent(c, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent open = PendingIntent.getActivity(c, 0, player, PendingIntent.FLAG_IMMUTABLE);
    for (int id : ids) {
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_visualiser);
      v.setTextViewText(R.id.name, p == null ? c.getString(R.string.viz_name) : p.name);
      v.setTextViewText(R.id.info, p == null ? "No looks in this build" : p.label() + " · " + where);
      if (p != null) {
        v.setInt(R.id.bg, "setColorFilter", 0xe6000000 | (p.background & 0xffffff));
        if (Build.VERSION.SDK_INT >= 31) {
          v.setColorStateList(R.id.bars, "setIndeterminateTintList", ColorStateList.valueOf(p.primary));
        }
      }
      v.setOnClickPendingIntent(R.id.open, open);
      v.setOnClickPendingIntent(R.id.full, open);
      int[][] keys = {
        {R.id.prev, KeyEvent.KEYCODE_MEDIA_PREVIOUS},
        {R.id.play, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE},
        {R.id.pause, KeyEvent.KEYCODE_MEDIA_PAUSE},
        {R.id.next, KeyEvent.KEYCODE_MEDIA_NEXT},
      };
      for (int[] k : keys) {
        Intent i = new Intent(c, VizWidget.class).setAction(KEY).putExtra("code", k[1]);
        v.setOnClickPendingIntent(k[0], PendingIntent.getBroadcast(c, k[1], i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      }
      Intent look = new Intent(c, VizWidget.class).setAction(LOOK);
      v.setOnClickPendingIntent(R.id.look, PendingIntent.getBroadcast(c, 1, look,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      try {
        m.updateAppWidget(id, v);
      } catch (RuntimeException ignored) {
      }
    }
  }
}
