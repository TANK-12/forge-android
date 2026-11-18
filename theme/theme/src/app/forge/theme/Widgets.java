/* Forge Theme's home-screen widgets, in the theme's colours:
     ClockWidget      time, date and a greeting; the clock ticks by itself
                      (TextClock), so the app is never woken for it
     ParticlesWidget  a little window of particles over the theme picture:
                      a short seamless loop of frames the home screen flips
                      through while it is on screen (widgets cannot animate
                      any other way)
     MusicWidget      media keys for whatever is playing, next particle kind,
                      and Forge Visualiser
   Nothing redraws on a timer: they change when the settings change. */
package app.forge.theme;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.RemoteViews;
import app.forge.look.Background;
import app.forge.look.MediaKeys;
import app.forge.look.Particles;
import app.forge.look.Themes;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;

final class Widgets {
  static final int FRAMES = 12;
  static final String VISUALISER = "app.forge.visualiser";
  private static HandlerThread worker;

  /** The visualiser player: inside this app when it carries one (the main Forge app,
      task 1316), else the Forge Visualiser app, else null. */
  static Intent visualiser(Context c) {
    Intent own = new Intent().setClassName(c, VISUALISER + ".PlayerActivity");
    if (!VISUALISER.equals(c.getPackageName())
        && own.resolveActivity(c.getPackageManager()) != null) {
      return own.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    return c.getPackageManager().getLaunchIntentForPackage(VISUALISER);
  }

  /** Redraw every Forge Theme widget (after a settings change). */
  static void refreshAll(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    Class<?>[] kinds = {ClockWidget.class, ParticlesWidget.class, MusicWidget.class};
    for (Class<?> k : kinds) {
      int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
      if (ids.length == 0) continue;
      if (k == ClockWidget.class) ClockWidget.draw(c, m, ids);
      else if (k == MusicWidget.class) MusicWidget.draw(c, m, ids);
      else ParticlesWidget.drawLater(c, ids);
    }
  }

  static synchronized Handler worker() {
    if (worker == null) {
      worker = new HandlerThread("forge-widgets");
      worker.start();
    }
    return new Handler(worker.getLooper());
  }

  static void panel(RemoteViews v, Themes.Theme t) {
    v.setInt(R.id.bg, "setColorFilter", t.panel);
    v.setInt(R.id.bg, "setImageAlpha", 222);
  }

  static PendingIntent openApp(Context c) {
    Intent i = new Intent(c, ThemeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE);
  }

  /** The widget's size in pixels, from what the home screen reports. */
  static int[] sizePx(Context c, AppWidgetManager m, int id) {
    Bundle o = m.getAppWidgetOptions(id);
    float d = c.getResources().getDisplayMetrics().density;
    int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250);
    int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
    w = Math.max(110, w);
    h = Math.max(60, h);
    float scale = Math.min(1f, 560f / (Math.max(w, h) * d));
    return new int[] {Math.round(w * d * scale), Math.round(h * d * scale), Math.round(d * scale * 100)};
  }

  public static final class ClockWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
      draw(c, m, ids);
    }

    static void draw(Context c, AppWidgetManager m, int[] ids) {
      Themes.Theme t = Prefs.read(c).theme(c);
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_clock);
      panel(v, t);
      v.setTextColor(R.id.time, t.text);
      v.setTextColor(R.id.date, t.muted);
      v.setInt(R.id.line, "setColorFilter", t.accent);
      v.setTextColor(R.id.note, t.muted);
      v.setTextViewText(R.id.note, t.label);
      v.setOnClickPendingIntent(R.id.root, openApp(c));
      m.updateAppWidget(ids, v);
    }
  }

  public static final class ParticlesWidget extends AppWidgetProvider {
    private static final SecureRandom RANDOM = new SecureRandom();

    // goAsync: the process stays awake until the frames are written
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
      drawLater(c, ids, goAsync());
    }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id,
        Bundle options) {
      drawLater(c, new int[] {id}, goAsync());
    }

    @Override public void onDeleted(Context c, int[] ids) {
      for (int id : ids) clear(c, id, null);
    }

    static void drawLater(Context context, int[] ids) {
      drawLater(context, ids, null);
    }

    static void drawLater(Context context, int[] ids, PendingResult done) {
      Context c = context.getApplicationContext();
      worker().post(() -> {
        try {
          AppWidgetManager m = AppWidgetManager.getInstance(c);
          for (int id : ids) draw(c, m, id);
        } finally {
          if (done != null) done.finish();
        }
      });
    }

    static void draw(Context c, AppWidgetManager m, int id) {
      Prefs p = Prefs.read(c);
      Themes.Theme t = p.theme(c);
      int[] size = sizePx(c, m, id);
      int w = size[0], h = size[1];
      float density = size[2] / 100f;
      // 64 random bits in every name: other apps cannot guess a frame's
      // address, and a frame can hold a picture of your own
      String tag = SystemClock.elapsedRealtime() + "-" + Long.toHexString(RANDOM.nextLong());
      Background bg = new Background(c);
      bg.load(w, h, p.picture, t, p.dim);
      Particles parts = new Particles();
      String kind = p.kindFor(t);
      parts.setKind("off".equals(kind) ? t.particles : kind);
      parts.setColours(t.accent, t.accent2, bg.brightness() > 0.55f);
      parts.setAmount(p.amount);
      parts.setSize(p.size);
      parts.setSpeed(p.speed);
      Bitmap frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(frame);
      Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
      clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
      Path corners = new Path();
      float r = 22 * density;
      corners.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
      corners.setFillType(Path.FillType.INVERSE_WINDING);
      File dir = new File(c.getFilesDir(), "widget");
      if (!dir.isDirectory() && !dir.mkdirs()) return;
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_particles);
      v.removeAllViews(R.id.frames);
      for (int i = 0; i < FRAMES; i++) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        bg.offset(0.5f);
        bg.draw(canvas);
        parts.drawLoopFrame(canvas, w, h, density, i / (float) FRAMES);
        canvas.drawPath(corners, clear);
        String name = "p" + id + "-" + i + "-" + tag + ".png";
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
          frame.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
          return;
        }
        RemoteViews f = new RemoteViews(c.getPackageName(), R.layout.widget_frame);
        f.setImageViewUri(R.id.frame, Uri.parse("content://" + FramesProvider.authority(c) + "/" + name));
        v.addView(R.id.frames, f);
      }
      frame.recycle();
      bg.release();
      v.setOnClickPendingIntent(R.id.root, openApp(c));
      m.updateAppWidget(id, v);
      clear(c, id, tag);
    }

    /** Remove this widget's old frames (all of them when keep is null). */
    static void clear(Context c, int id, String keep) {
      File[] old = new File(c.getFilesDir(), "widget").listFiles();
      if (old == null) return;
      String mine = "p" + id + "-";
      for (File f : old) {
        String n = f.getName();
        if (n.startsWith(mine) && (keep == null || !n.endsWith("-" + keep + ".png"))) {
          if (!f.delete()) f.deleteOnExit();
        }
      }
    }
  }

  public static final class MusicWidget extends AppWidgetProvider {
    /** Redraw the music keys only (cheap): shows the Visualiser button once
        Forge Visualiser is installed. */
    static void refresh(Context c) {
      AppWidgetManager m = AppWidgetManager.getInstance(c);
      int[] ids = m.getAppWidgetIds(new ComponentName(c, MusicWidget.class));
      if (ids.length > 0) draw(c, m, ids);
    }

    static final String KEY = "app.forge.theme.KEY", KIND = "app.forge.theme.NEXT_KIND";

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
      draw(c, m, ids);
    }

    @Override public void onReceive(Context c, Intent intent) {
      if (KEY.equals(intent.getAction())) {
        MediaKeys.press(c, intent.getIntExtra("code", 0));
        return;
      }
      if (KIND.equals(intent.getAction())) {
        nextKind(c);
        return;
      }
      super.onReceive(c, intent);
    }

    static void nextKind(Context c) {
      Prefs p = Prefs.read(c);
      List<Themes.Kind> kinds = Themes.kinds(c);
      String now = p.kindFor(p.theme(c));
      int at = -1; // from Off (not in the list), the first kind comes next
      for (int i = 0; i < kinds.size(); i++) if (kinds.get(i).key.equals(now)) at = i;
      String next = kinds.get((at + 1) % kinds.size()).key;
      Prefs.store(c).edit().putString(Prefs.KIND, next).apply();
      refreshAll(c);
    }

    static void draw(Context c, AppWidgetManager m, int[] ids) {
      Themes.Theme t = Prefs.read(c).theme(c);
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_music);
      panel(v, t);
      int[][] keys = {
        {R.id.prev, KeyEvent.KEYCODE_MEDIA_PREVIOUS},
        {R.id.play, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE},
        {R.id.next, KeyEvent.KEYCODE_MEDIA_NEXT},
      };
      for (int[] k : keys) {
        Intent i = new Intent(c, MusicWidget.class).setAction(KEY).putExtra("code", k[1]);
        v.setOnClickPendingIntent(k[0], PendingIntent.getBroadcast(c, k[1], i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setInt(k[0], "setColorFilter", t.text);
      }
      Intent kind = new Intent(c, MusicWidget.class).setAction(KIND);
      v.setOnClickPendingIntent(R.id.kind, PendingIntent.getBroadcast(c, 1, kind,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      v.setInt(R.id.kind, "setColorFilter", t.accent);
      Intent vis = visualiser(c);
      if (vis != null) {
        v.setOnClickPendingIntent(R.id.vis, PendingIntent.getActivity(c, 2, vis,
            PendingIntent.FLAG_IMMUTABLE));
        v.setInt(R.id.vis, "setColorFilter", t.accent);
        v.setViewVisibility(R.id.vis, android.view.View.VISIBLE);
      } else {
        v.setViewVisibility(R.id.vis, android.view.View.GONE);
      }
      m.updateAppWidget(ids, v);
    }
  }

  private Widgets() {}
}
