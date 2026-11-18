package app.forge.look;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Opening Android's screen that sets a live wallpaper, on any tablet (Relay
 * task 1330). Some makers' Android leaves out the standard picker, or keeps
 * it but hides it from the usual intents, so Forge said "no live wallpaper
 * picker" on Emerson's tablet. This tries, most direct first: the confirm
 * screen for this wallpaper, the same screen reached by name when it lacks
 * the default category, the list of live wallpapers (the same two ways), the
 * standard picker by name, and last the maker's own wallpaper app (through
 * SET_WALLPAPER), where live wallpapers usually have their own section. A
 * tablet with none of them, or with live wallpapers off altogether, gets
 * {@link #NONE} and the caller offers a still picture instead of a complaint.
 */
public final class LiveWall {
  /** What a tap opens. */
  public static final int NONE = 0, CONFIRM = 1, LIST = 2, WALLPAPER_APP = 3;

  private static final String[][] BY_NAME = {
    {"com.android.wallpaper.livepicker", "com.android.wallpaper.livepicker.LiveWallpaperChange"},
    {"com.android.wallpaper.livepicker", "com.android.wallpaper.livepicker.LiveWallpaperActivity"},
  };

  private LiveWall() {}

  /** Whether this Android runs live wallpapers at all. */
  public static boolean supported(Context c) {
    return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER);
  }

  /** The ways to try, in order: each an explicit intent to an activity that is there. */
  static List<Way> ways(Context c, ComponentName service) {
    List<Way> out = new ArrayList<>();
    if (!supported(c)) return out;
    PackageManager pm = c.getPackageManager();
    Intent change = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, service);
    Intent list = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
    add(out, c, pm, change, CONFIRM);
    add(out, c, pm, list, LIST);
    for (String[] n : BY_NAME) {
      ComponentName cn = new ComponentName(n[0], n[1]);
      try {
        ActivityInfo a = pm.getActivityInfo(cn, 0);
        if (a.exported && a.enabled && !has(out, cn)) {
          boolean isChange = n[1].endsWith("Change");
          Intent i = new Intent(isChange ? change : list).setComponent(cn);
          out.add(new Way(i, isChange ? CONFIRM : LIST, true));
        }
      } catch (PackageManager.NameNotFoundException e) {
        // not on this tablet
      }
    }
    add(out, c, pm, new Intent(Intent.ACTION_SET_WALLPAPER), WALLPAPER_APP);
    return out;
  }

  /** Every exported activity answering `base` (default category or not), system apps first. */
  private static void add(List<Way> out, Context c, PackageManager pm, Intent base, int kind) {
    List<ResolveInfo> found;
    try {
      found = pm.queryIntentActivities(base, 0);
    } catch (RuntimeException e) {
      return;
    }
    List<Way> system = new ArrayList<>(), other = new ArrayList<>();
    for (ResolveInfo r : found) {
      ActivityInfo a = r.activityInfo;
      if (a == null || !a.exported || !a.enabled || c.getPackageName().equals(a.packageName)) continue;
      ComponentName cn = new ComponentName(a.packageName, a.name);
      if (has(out, cn)) continue;
      boolean sys = a.applicationInfo != null
          && (a.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
      (sys ? system : other).add(new Way(new Intent(base).setComponent(cn), kind, sys));
    }
    out.addAll(system);
    out.addAll(other);
    if (kind == WALLPAPER_APP) {
      // a gallery also answers SET_WALLPAPER but only crops a photo: wallpaper apps first
      List<Way> apps = new ArrayList<>(out.subList(out.size() - system.size() - other.size(), out.size()));
      out.removeAll(apps);
      apps.sort((x, y) -> score(y) - score(x));
      for (Way w : apps) if (score(w) > 0) out.add(w); // a gallery never lists live wallpapers
    }
  }

  /** 0 for a gallery or photos app, 2 for the system's wallpaper or theme app,
      1 for anything else (an installed wallpaper app cannot set Forge's). */
  static int score(Way w) {
    ComponentName cn = w.intent.getComponent();
    String n = cn == null ? "" : (cn.getPackageName() + "/" + cn.getClassName()).toLowerCase(java.util.Locale.ROOT);
    // the gallery first: AOSP's own crops photos in a class named Wallpaper
    if (n.contains("gallery") || n.contains("photo") || n.contains("album")) return 0;
    if (w.sys && (n.contains("wallpaper") || n.contains("theme"))) return 2;
    return 1;
  }

  private static boolean has(List<Way> out, ComponentName cn) {
    for (Way w : out) if (cn.equals(w.intent.getComponent())) return true;
    return false;
  }

  /** What a tap would open on this tablet, for the start page to word its buttons. */
  public static int way(Context c, ComponentName service) {
    List<Way> w = ways(c, service);
    return w.isEmpty() ? NONE : w.get(0).kind;
  }

  /** The same as a word: "confirm", "list", "app" or "none". */
  public static String word(int kind) {
    return kind == CONFIRM ? "confirm" : kind == LIST ? "list" : kind == WALLPAPER_APP ? "app" : "none";
  }

  /** Open the first way that starts; what opened, or {@link #NONE} when nothing could. */
  public static int open(Activity a, ComponentName service) {
    List<Way> all = ways(a, service);
    int apps = 0;
    for (Way w : all) if (w.kind == WALLPAPER_APP) apps++;
    for (Way w : all) {
      try {
        if (w.kind == WALLPAPER_APP && apps > 1 && score(w) < 2) {
          // no app that is clearly the wallpaper app: Android asks which one
          a.startActivity(chooser(a, all));
        } else {
          a.startActivity(w.intent);
        }
        return w.kind;
      } catch (ActivityNotFoundException | SecurityException e) {
        // the next way
      }
    }
    return NONE;
  }

  /** Android's chooser over the wallpaper apps kept, without the galleries dropped. */
  private static Intent chooser(Context c, List<Way> kept) {
    List<ComponentName> drop = new ArrayList<>();
    for (ResolveInfo r : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_SET_WALLPAPER), 0)) {
      if (r.activityInfo == null) continue;
      ComponentName cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
      if (!has(kept, cn)) drop.add(cn);
    }
    return Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
            "Open the wallpaper app, then Live wallpapers")
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, drop.toArray(new ComponentName[0]));
  }

  /** What to say after `open`, naming the wallpaper to pick; null when nothing needs saying. */
  public static String hint(int kind, String name) {
    if (kind == LIST) return "Pick " + name + " in the list";
    if (kind == WALLPAPER_APP) return "Open Live wallpapers there, then pick " + name;
    return null;
  }

  static final class Way {
    final Intent intent;
    final int kind;
    final boolean sys;

    Way(Intent intent, int kind, boolean sys) {
      this.intent = intent;
      this.kind = kind;
      this.sys = sys;
    }
  }
}
