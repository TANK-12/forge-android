package app.forge.look;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Opening Android's screen that sets a live wallpaper, on any tablet, and
 * saying exactly where to tap in it (Relay tasks 1330 and 1334). Some makers'
 * Android leaves out the standard picker, or keeps it but hides it from the
 * usual intents. This tries, most direct first: the confirm screen for this
 * wallpaper, the same screen reached by name when it lacks the default
 * category, the list of live wallpapers (the same two ways), the standard
 * picker by name, a live wallpaper page inside the maker's wallpaper app,
 * and last the maker's wallpaper app itself (through SET_WALLPAPER).
 *
 * <p>{@link #plan} words what a tap does on this tablet from what is really
 * there: the name the tablet shows for the app Forge opens, the name that
 * app itself uses for its live wallpaper section (read from its own words,
 * "Live wallpapers" when it has none Forge can find) and the name the
 * tablet lists Forge's wallpaper under. No route or a wallpaper policy
 * restriction returns {@link #NONE}. A picture remains a separate choice;
 * neither opening a picker nor selecting a service proves moving frames.
 */
public final class LiveWall {
  /** What a tap opens. */
  public static final int NONE = 0, CONFIRM = 1, LIST = 2, WALLPAPER_APP = 3;

  private static final String[][] BY_NAME = {
    {"com.android.wallpaper.livepicker", "com.android.wallpaper.livepicker.LiveWallpaperChange"},
    {"com.android.wallpaper.livepicker", "com.android.wallpaper.livepicker.LiveWallpaperActivity"},
  };

  /** Names a wallpaper app may give its live wallpaper section, in its own strings. */
  private static final String[] SECTION_IDS = {
    "live_wallpapers", "live_wallpaper", "live_wallpapers_title", "live_wallpaper_title",
    "live_wallpapers_category_title", "live_wallpaper_category_title", "category_live_wallpapers",
    "category_live_wallpaper", "live_wallpaper_list_title", "live_wallpaper_picker_title",
    "dynamic_wallpapers", "dynamic_wallpaper", "live_wallpaper_label",
  };

  /** A page inside a wallpaper app that lists live wallpapers, by its class name. */
  private static final Pattern INSIDE = Pattern.compile(
      "(?i).*live.*(wall|paper|picker|list|chooser|select|gallery).*");
  private static final Pattern NOT_INSIDE = Pattern.compile(
      "(?i).*(preview|crop|setting|detail|service|edit|apply|confirm|receiver|provider).*");

  private static final String PREFS = "forge_live_wall";

  private LiveWall() {}

  /** Whether this Android runs live wallpapers at all. */
  public static boolean supported(Context c) {
    return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER);
  }

  /** The ways to try, in order: each an explicit intent to an activity that is there. */
  static List<Way> ways(Context c, ComponentName service) {
    List<Way> out = new ArrayList<>();
    // The feature declaration is missing on some ROMs with usable picker activities.
    WallpaperManager manager = WallpaperManager.getInstance(c);
    if (!manager.isWallpaperSupported() || !manager.isSetWallpaperAllowed()) return out;
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
          out.add(new Way(i, isChange ? CONFIRM : LIST, true, label(pm, a)));
        }
      } catch (PackageManager.NameNotFoundException e) {
        // not on this tablet
      }
    }
    int at = out.size();
    add(out, c, pm, new Intent(Intent.ACTION_SET_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, service), WALLPAPER_APP);
    // the live wallpaper page inside the wallpaper app, when it has one by name
    if (out.size() > at && out.get(at).sys) {
      Way app = out.get(at);
      List<Way> inside = new ArrayList<>();
      for (ActivityInfo a : activities(pm, app.intent.getComponent().getPackageName())) {
        ComponentName cn = new ComponentName(a.packageName, a.name);
        String simple = a.name.substring(a.name.lastIndexOf('.') + 1);
        if (!INSIDE.matcher(simple).matches() || NOT_INSIDE.matcher(simple).matches()) continue;
        if (has(out, cn)) continue;
        Way w = new Way(new Intent(Intent.ACTION_MAIN).setComponent(cn), LIST, true, app.label);
        w.inside = true;
        inside.add(w);
      }
      out.addAll(at, inside);
    }
    return out;
  }

  /** The exported, enabled activities of `pkg` that any app may start. */
  private static List<ActivityInfo> activities(PackageManager pm, String pkg) {
    List<ActivityInfo> out = new ArrayList<>();
    try {
      PackageInfo p = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
      if (p.activities != null) {
        for (ActivityInfo a : p.activities) {
          if (a.exported && a.enabled && a.permission == null) out.add(a);
        }
      }
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      // hidden from Forge or gone: no page inside it
    }
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
      String name = label(pm, r);
      (sys ? system : other).add(new Way(new Intent(base).setComponent(cn), kind, sys, name));
    }
    List<Way> found2 = new ArrayList<>(system);
    found2.addAll(other);
    if (kind == WALLPAPER_APP) {
      // a gallery also answers SET_WALLPAPER but only crops a photo: wallpaper apps first
      found2.sort((x, y) -> score(y) - score(x));
      for (Way w : found2) if (score(w) > 0) out.add(w); // a gallery never lists live wallpapers
    } else {
      out.addAll(found2);
    }
  }

  /** 0 for a gallery or photos app, 2 for the system's wallpaper or theme app,
      1 for anything else (an installed wallpaper app cannot set Forge's). */
  static int score(Way w) {
    ComponentName cn = w.intent.getComponent();
    String n = cn == null ? "" : (cn.getPackageName() + "/" + cn.getClassName()).toLowerCase(Locale.ROOT);
    // the gallery first: AOSP's own crops photos in a class named Wallpaper
    if (n.contains("gallery") || n.contains("photo") || n.contains("album")) return 0;
    if (w.sys && (n.contains("wallpaper") || n.contains("theme"))) return 2;
    return 1;
  }

  private static boolean has(List<Way> out, ComponentName cn) {
    for (Way w : out) if (cn.equals(w.intent.getComponent())) return true;
    return false;
  }

  /** The name the tablet shows for an activity: its own label, else its app's. */
  private static String label(PackageManager pm, ResolveInfo r) {
    try {
      CharSequence s = r.loadLabel(pm);
      if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
    } catch (RuntimeException e) {
      // no label
    }
    return r.activityInfo == null ? "" : label(pm, r.activityInfo);
  }

  private static String label(PackageManager pm, ActivityInfo a) {
    try {
      CharSequence s = a.loadLabel(pm);
      if (s == null || s.toString().trim().isEmpty()) s = a.applicationInfo.loadLabel(pm);
      return s == null ? "" : s.toString().trim();
    } catch (RuntimeException e) {
      return "";
    }
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

  /** What a tap does on this tablet, in words from the tablet itself. */
  public static Plan plan(Context c, ComponentName service) {
    List<Way> all = ways(c, service);
    return all.isEmpty() ? new Plan(NONE, "", "", entry(c, service), false, null)
        : plan(c, service, all, all.get(0));
  }

  /** The words for opening `w`, one of `all`. */
  private static Plan plan(Context c, ComponentName service, List<Way> all, Way w) {
    PackageManager pm = c.getPackageManager();
    String entry = entry(c, service);
    if (w.kind == WALLPAPER_APP && chooses(all)) {
      // Android asks which app: name only the system's own (another app
      // cannot set Forge's wallpaper); the section depends on the pick
      List<String> apps = new ArrayList<>();
      for (Way x : all) {
        if (x.kind == WALLPAPER_APP && x.sys && !x.label.isEmpty() && !apps.contains(x.label)) apps.add(x.label);
      }
      return new Plan(WALLPAPER_APP, "", "", entry, false, apps);
    }
    ComponentName cn = w.intent.getComponent();
    return new Plan(w.kind, w.label, section(pm, cn == null ? "" : cn.getPackageName()), entry,
        w.inside, null);
  }

  /** The name the tablet lists Forge's wallpaper under, told apart from a
      wallpaper of the same name from another app (the old Forge Theme app). */
  static String entry(Context c, ComponentName service) {
    PackageManager pm = c.getPackageManager();
    String entry = "";
    try {
      CharSequence s = pm.getServiceInfo(service, 0).loadLabel(pm);
      entry = s == null ? "" : s.toString().trim();
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      // unnamed: the plain name below
    }
    if (entry.isEmpty()) return "Forge's wallpaper";
    try {
      Intent any = new Intent("android.service.wallpaper.WallpaperService");
      for (ResolveInfo r : pm.queryIntentServices(any, PackageManager.GET_META_DATA)) {
        if (r.serviceInfo == null || service.getPackageName().equals(r.serviceInfo.packageName)) continue;
        CharSequence s = r.serviceInfo.loadLabel(pm);
        if (s == null || !entry.equalsIgnoreCase(s.toString().trim())) continue;
        CharSequence other = r.serviceInfo.applicationInfo.loadLabel(pm);
        String app = c.getApplicationInfo().loadLabel(pm).toString();
        return entry + " (the one from " + app
            + (other == null ? ")" : ", not from " + other + ")");
      }
    } catch (RuntimeException e) {
      // no list of wallpapers: the plain name
    }
    return entry;
  }

  /** The wallpaper app's own name for its live wallpaper section, or "". */
  static String section(PackageManager pm, String pkg) {
    if (pkg.isEmpty()) return "";
    try {
      Resources r = pm.getResourcesForApplication(pkg);
      for (String id : SECTION_IDS) {
        int at = r.getIdentifier(id, "string", pkg);
        if (at == 0) continue;
        String s = r.getString(at).trim();
        if (!s.isEmpty() && s.length() <= 32 && !s.contains("%")) return s;
      }
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      // its words are hidden from Forge: the usual name
    }
    return "";
  }

  /** Whether Android has to ask which app: several, none clearly the wallpaper app. */
  private static boolean chooses(List<Way> all) {
    int apps = 0;
    for (Way w : all) if (w.kind == WALLPAPER_APP) apps++;
    for (Way w : all) if (w.kind == WALLPAPER_APP) return apps > 1 && score(w) < 2;
    return false;
  }

  /** Open the first way that starts, then say where to tap; the words for what
      opened (its kind {@link #NONE} when nothing could). */
  public static Plan open(Activity a, ComponentName service) {
    return open(a, service, false);
  }

  /** Retry another Android screen without permanently excluding a cancelled one. */
  public static Plan openNext(Activity activity, ComponentName service) {
    return open(activity, service, true);
  }

  private static Plan open(Activity a, ComponentName service, boolean next) {
    List<Way> all = ways(a, service);
    if (next && !all.isEmpty()) {
      String previous = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("route", "");
      for (int index = 0; index < all.size(); index++) {
        if (all.get(index).intent.getComponent().flattenToShortString().equals(previous)) {
          Collections.rotate(all, -(index + 1));
          break;
        }
      }
    }
    boolean choose = chooses(all);
    for (Way w : all) {
      try {
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("owner", a.getClass().getName())
            .putString("service", service.flattenToString())
            .putString("route", w.intent.getComponent().flattenToShortString())
            .putString("last_result", "Waiting for Android to apply " + entry(a, service) + ".")
            .putLong("at", System.currentTimeMillis()).commit();
        if (w.kind == WALLPAPER_APP && choose) {
          // no app that is clearly the wallpaper app: Android asks which one
          a.startActivity(chooser(a, all));
        } else {
          a.startActivity(w.intent);
        }
        ComponentName cn = w.intent.getComponent();
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("opened", w.inside && cn != null ? cn.flattenToString() : "")
            .putString("service", service.flattenToString())
            .putLong("at", System.currentTimeMillis())
            .apply();
        Plan said = plan(a, service, all, w);
        // one toast, shown while Forge is still in front: Android drops a
        // later one from the background when Forge's notifications are off
        String line = said.line();
        if (!line.isEmpty() && w.kind != CONFIRM) Toast.makeText(a, line, Toast.LENGTH_LONG).show();
        return said;
      } catch (android.content.ActivityNotFoundException | SecurityException e) {
        // The advertised activity cannot be opened: try the next route.
      }
    }
    a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("at")
        .putString("last_result", "No live wallpaper picker could be opened. Android has not applied animation.").apply();
    return new Plan(NONE, "", "", entry(a, service), false, null);
  }

  /**
   * Back in Forge after {@link #open}: what to say (the wallpaper is on, or
   * where to tap again), or null for another activity. Opening settings from
   * Android's preview must not consume the originating activity's attempt.
   */
  public static String back(Context c) {
    SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    if (!c.getClass().getName().equals(prefs.getString("owner", ""))) return null;
    ComponentName service = ComponentName.unflattenFromString(prefs.getString("service", ""));
    if (prefs.getLong("at", 0) == 0 || service == null) return null;
    int status = state(c, service);
    String result = status == 1
        ? entry(c, service) + " was selected by Android. Motion follows the particle and battery settings below."
        : status == 0 ? "Android did not apply " + entry(c, service)
            + ". Opening a wallpaper app alone does not enable it. You can try again."
        : "Android could not confirm the wallpaper. Your selection is kept while Forge checks again.";
    SharedPreferences.Editor edit = prefs.edit().putString("last_result", result);
    if (status != -1) edit.remove("at").remove("opened");
    edit.apply();
    return result;
  }

  /** 1 when Android selected this service, 0 for another wallpaper, -1 when unreadable. */
  public static int state(Context context, ComponentName service) {
    try {
      android.app.WallpaperInfo info = WallpaperManager.getInstance(context).getWallpaperInfo();
      return info != null && service.equals(info.getComponent()) ? 1 : 0;
    } catch (RuntimeException error) {
      return -1;
    }
  }

  public static String result(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_result", "");
  }

  public static void recordResult(Context context, String result) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_result", result).apply();
  }

  /** Device-local evidence, without computer addresses, pairing keys or personal files. */
  public static String diagnostics(Context context, ComponentName service) {
    WallpaperManager manager = WallpaperManager.getInstance(context);
    StringBuilder text = new StringBuilder("Device: ").append(android.os.Build.MANUFACTURER)
        .append(" ").append(android.os.Build.MODEL)
        .append("\nAndroid: ").append(android.os.Build.VERSION.RELEASE)
        .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")")
        .append("\nLive wallpaper feature declared: ").append(supported(context))
        .append("\nWallpaper supported: ").append(manager.isWallpaperSupported())
        .append("\nWallpaper changes allowed: ").append(manager.isSetWallpaperAllowed())
        .append("\nRequested service: ").append(service.flattenToShortString())
        .append("\nAndroid selected this service: ").append(state(context, service))
        .append(" (1 yes, 0 no, -1 unknown)");
    List<Way> routes = ways(context, service);
    text.append("\nPicker routes: ").append(routes.size());
    for (Way route : routes) text.append("\n  ").append(word(route.kind)).append(": ")
        .append(route.intent.getComponent().flattenToShortString());
    text.append("\nLast route: ").append(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("route", "none")).append("\n").append(result(context));
    return text.toString();
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
            "Pick the wallpaper app, then Live wallpapers")
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, drop.toArray(new ComponentName[0]));
  }

  /** What a tap does here, worded from the tablet's own names. */
  public static final class Plan {
    public final int kind;
    /** The name the tablet shows for the app or screen Forge opens. */
    public final String app;
    /** That app's own name for its live wallpaper section ("" when not found). */
    public final String section;
    /** The name the tablet lists Forge's wallpaper under. */
    public final String entry;
    /** Forge opens the live wallpaper page inside the wallpaper app. */
    public final boolean inside;
    /** When Android asks which app: the apps it offers; otherwise null. */
    public final List<String> apps;

    Plan(int kind, String app, String section, String entry, boolean inside, List<String> apps) {
      this.kind = kind;
      this.app = app;
      this.section = section;
      this.entry = entry;
      this.inside = inside;
      this.apps = apps;
    }

    private String app() {
      return app.isEmpty() ? "the tablet's wallpaper app" : app;
    }

    private String set() {
      return "Set wallpaper (some tablets say Apply)";
    }

    /** The steps for the start page, in order; empty for {@link #NONE}. */
    public List<String> steps() {
      List<String> s = new ArrayList<>();
      if (kind == CONFIRM) {
        s.add("Tap Set as live wallpaper: Android shows " + entry + " full screen.");
        s.add("Tap " + set() + ".");
      } else if (kind == LIST) {
        s.add(inside
            ? "Tap Set as live wallpaper: Forge opens " + app() + " at what looks like its "
                + (section.isEmpty() ? "live wallpapers" : section) + " page."
            : "Tap Set as live wallpaper: Forge opens " + app() + ", the tablet's list of live wallpapers.");
        s.add("Tap " + entry + ".");
        s.add("Tap " + set() + ".");
      } else if (kind == WALLPAPER_APP) {
        s.add(apps != null && !apps.isEmpty()
            ? "Tap Set as live wallpaper: Android asks which app. Pick " + String.join(" or ", apps) + "."
            : "Tap Set as live wallpaper: Forge opens " + app() + ".");
        String in = apps != null ? "that app" : app();
        s.add(section.isEmpty()
            ? "In " + in + ", find " + entry + ": most tablets list it under Live wallpapers"
                + " (scroll down if it is not on the first screen)."
            : "In " + in + ", tap " + section + ".");
        s.add("Tap " + entry + ", then " + set() + ".");
      }
      if (kind != NONE) s.add("Come back to Forge: it says when " + entry + " is on.");
      return s;
    }

    /** The same in one line, for a note or a toast. */
    public String line() {
      if (kind == CONFIRM) return "Android shows " + entry + ": tap " + set() + ".";
      if (kind == LIST) return "In " + app() + ", tap " + entry + ", then " + set() + ".";
      if (kind == WALLPAPER_APP) {
        String where = apps != null && !apps.isEmpty() ? String.join(" or ", apps) : app();
        return section.isEmpty()
            ? "In " + where + ": find " + entry + " (most tablets list it under Live wallpapers),"
                + " tap it, then " + set() + "."
            : "In " + where + ": tap " + section + ", then " + entry + ", then " + set() + ".";
      }
      return "";
    }

  }

  static final class Way {
    final Intent intent;
    final int kind;
    final boolean sys;
    final String label;
    boolean inside;

    Way(Intent intent, int kind, boolean sys, String label) {
      this.intent = intent;
      this.kind = kind;
      this.sys = sys;
      this.label = label == null ? "" : label;
    }
  }
}
