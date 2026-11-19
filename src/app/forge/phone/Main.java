package app.forge.phone;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Forge on the phone: one WebView. The start page and the apps that run on
 * the phone alone come from this APK's own files, each on its own made-up
 * https origin (start.offline.forge, focus.offline.forge, ...) that never
 * reaches the network. The laptop's phone hub opens at the saved address;
 * its sign-in is the laptop's. No password is kept in the app; a paired
 * computer's sign-in token stays in the WebView's own cookie store.
 *
 * ForgeAndroid.profile() tells a page how strong this phone is (memory,
 * processor, Android's thermal status, battery and battery saver; nothing
 * that names the phone or its owner), so Forge scales its heavy features to
 * it (shared/perf/device.json, Relay task 596). A page's fullscreen request
 * (a game) fills the screen with the system bars hidden; Back leaves it.
 *
 * Two ways to a computer (Relay task 666). Paired, the usual one: a released
 * Forge's phone link on the home network (Devices, Phones). The start page
 * takes its address, check code and pairing code (typed, pasted, from a
 * forge-phone://pair link the camera opens, or an address found on the
 * Wi-Fi); Link.java pins the computer's own certificate by the check code
 * and pairs. Several computers stay paired side by side (0.4, Relay task
 * 1292: a tablet with Heat, Mirror and the XPS), each with its own token in
 * the WebView's cookie store and its own pin in this app's private
 * settings; only that certificate is accepted for that address. The start
 * page lists each computer's apps (its /hub/api/apps, fetched here as
 * /hub.json?host=), and files shared to Forge from other apps go to the
 * computer picked there (its inbox, when its owner allows it). Owner: the architect's own laptop over Tailscale (.ts.net),
 * signed in with its password and authenticator code on the hub's page.
 */
public class Main extends Activity {
  static final String LOCAL = ".offline.forge";
  static final String START = "start" + LOCAL;
  static final int HUB_PORT = 9464;
  static final int DARK = Color.rgb(11, 13, 18);
  WebView web;
  LinearLayout bar;
  TextView barTitle;
  final List<Uri> sharing = new ArrayList<>();
  SharedPreferences prefs;
  ValueCallback<Uri[]> picking;
  FrameLayout root;
  View full;
  WebChromeClient.CustomViewCallback fullDone;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = getSharedPreferences("forge", MODE_PRIVATE);
    migrate();
    noteUpdated();
    Updates.Done.page = this;
    Link.loopbackOk = JobsWidget.emulator();
    getWindow().setStatusBarColor(DARK);
    getWindow().setNavigationBarColor(DARK);
    web = new WebView(this);
    web.setBackgroundColor(DARK);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    CookieManager.getInstance().setAcceptCookie(true);
    web.setWebViewClient(new Client());
    web.setWebChromeClient(new Chrome());
    web.setDownloadListener((url, agent, disposition, mime, length) -> download(url, agent, disposition, mime));
    web.addJavascriptInterface(new Device(), "ForgeAndroid");
    LinearLayout column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.addView(makeBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    column.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    root = new FrameLayout(this);
    root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setContentView(root);
    // a share is taken once: not again when Android brings the app back after
    // closing it, or from the recent apps list (the files would go twice)
    if (saved == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
      takeShare(getIntent());
    }
    String jobs = jobsPage(getIntent());
    if (jobs != null) web.loadUrl(jobs);
    else if (saved != null) web.restoreState(saved);
    else web.loadUrl(startUrl(getIntent()));
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    String jobs = jobsPage(intent);
    if (jobs != null) web.loadUrl(jobs);
    else if (pairLink(intent) != null || takeShare(intent)) web.loadUrl(startUrl(intent));
  }

  /** The Forge jobs widget's tap: that paired computer's Jobs page (no other address). */
  String jobsPage(Intent intent) {
    if (intent == null || !JobsWidget.OPEN.equals(intent.getAction())) return null;
    // back from recents after the app was closed: the old tap, not a new one
    if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null;
    String host = intent.getStringExtra("host");
    intent.setAction(Intent.ACTION_MAIN); // once: not again when the activity is made again
    return computer(host) == null ? null : Link.origin(host) + "/#jobs";
  }

  // -- the jobs widget (Relay task 1301) -------------------------------------------

  final android.os.Handler ticks = new android.os.Handler(android.os.Looper.getMainLooper());
  final Runnable tick = new Runnable() {
    @Override
    public void run() {
      if (JobsWidget.ids(Main.this).length > 0) JobsWidget.refreshSoon(Main.this, false);
      ticks.postDelayed(this, 2 * 60 * 1000L);
    }
  };

  @Override
  protected void onResume() {
    super.onResume();
    web.onResume();
    Updates.Done.front = true;
    Intent ask = Updates.Done.pending; // the installer's question, held while Forge was out of sight
    if (ask != null) {
      Updates.Done.pending = null;
      try {
        startActivity(ask);
      } catch (RuntimeException e) {
        answer("{\"update\":\"failed\",\"error\":\"Android did not show its install question. Try again.\"}");
      }
    }
    resumeUpdate();
    wallBack();
    answer("{\"resumed\":true}"); // the home page reads the theme and updates again
    // the widget signs in with each computer's token, copied from the WebView's
    // cookie store (where the pairing put it) into this app's own store
    JSONArray list = computers();
    for (int i = 0; i < list.length(); i++) {
      JSONObject c = list.optJSONObject(i);
      String host = c == null ? "" : c.optString("host");
      String token = host.isEmpty() ? "" : token(host);
      if (!token.isEmpty()) JobsWidget.keepToken(this, host, token);
    }
    ticks.removeCallbacks(tick);
    ticks.post(tick); // ask now, and every 2 minutes while the app is open
  }

  @Override
  protected void onPause() {
    super.onPause();
    Updates.Done.front = false;
    ticks.removeCallbacks(tick);
    web.onPause(); // the home page's moving parts rest while Forge is out of sight
  }

  @Override
  protected void onDestroy() {
    if (Updates.Done.page == this) Updates.Done.page = null;
    super.onDestroy();
  }

  // -- the visualiser and the wallpaper, folded in (task 1316) ---------------------

  void openVisualiser() {
    startActivity(new Intent(this, app.forge.visualiser.PlayerActivity.class));
  }

  void openTheme() {
    startActivity(new Intent(this, app.forge.theme.ThemeActivity.class));
  }

  /** The Forge theme picked in Wallpaper and widgets (its colours and picture), for the home page. */
  WebResourceResponse lookJson() {
    String id = getSharedPreferences("look", MODE_PRIVATE).getString("theme", "midnight");
    try {
      JSONArray all = new JSONArray(app.forge.look.Themes.read(this, "themes.json"));
      JSONObject pick = null;
      for (int i = 0; i < all.length(); i++) {
        JSONObject t = all.optJSONObject(i);
        if (t == null) continue;
        if (pick == null || id.equals(t.optString("id"))) pick = t;
        if (id.equals(t.optString("id"))) break;
      }
      return json(pick == null ? "{}" : pick.toString());
    } catch (IOException | JSONException e) {
      return json("{}");
    }
  }

  // -- wallpapers and widgets from the start page (task 1322) ---------------------

  static final String THEME_WALL = "app.forge.theme.ThemeWallpaper";
  static final String VIZ_WALL = "app.forge.visualiser.VizWallpaper";

  /** What the wallpaper is now and what each Forge wallpaper shows, for the
      start page's Wallpapers section. */
  WebResourceResponse wallsJson() {
    String now = "still";
    try {
      android.app.WallpaperInfo info = android.app.WallpaperManager.getInstance(this).getWallpaperInfo();
      if (info != null) {
        boolean mine = getPackageName().equals(info.getPackageName());
        now = mine && THEME_WALL.equals(info.getServiceName()) ? "theme"
            : mine && VIZ_WALL.equals(info.getServiceName()) ? "visualiser" : "other";
      }
    } catch (RuntimeException e) {
      now = "unknown";
    }
    android.appwidget.AppWidgetManager widgets = getSystemService(android.appwidget.AppWidgetManager.class);
    try {
      JSONObject o = new JSONObject()
          .put("now", now)
          .put("theme", app.forge.theme.Walls.theme(this))
          .put("look", app.forge.visualiser.WallPick.look(this))
          .put("pin", widgets != null && widgets.isRequestPinAppWidgetSupported())
          // task 1330: what "Set as live wallpaper" opens here (confirm, list, app or none)
          .put("live", app.forge.look.LiveWall.word(app.forge.look.LiveWall.way(this,
              new android.content.ComponentName(this, THEME_WALL))));
      // task 1334: exactly where to tap, in the tablet's own names, for each wallpaper
      JSONObject steps = new JSONObject();
      for (String[] w : new String[][] {{"theme", THEME_WALL}, {"visualiser", VIZ_WALL}}) {
        app.forge.look.LiveWall.Plan plan =
            app.forge.look.LiveWall.plan(this, new android.content.ComponentName(this, w[1]));
        steps.put(w[0], new org.json.JSONArray(plan.steps()));
        if (w[0].equals("theme")) o.put("live_app", plan.app).put("live_section", plan.section);
      }
      o.put("live_steps", steps);
      return json(o.toString());
    } catch (JSONException e) {
      return json("{}");
    }
  }

  /** Use a Forge live wallpaper: a theme with particles, or a visualiser look.
      When that wallpaper is already the one in use, it changes at once with no
      system screen; otherwise Android's own screen asks to confirm (apps cannot
      set a live wallpaper by themselves), once. */
  /** The live wallpaper pick waiting on Android's confirm: kind and the pick before it. */
  String[] wallAsked;

  void wallLive(String kind, String id) {
    boolean viz = "visualiser".equals(kind);
    String before = viz ? app.forge.visualiser.WallPick.look(this) : app.forge.theme.Walls.theme(this);
    boolean known = viz ? app.forge.visualiser.WallPick.use(this, id) : app.forge.theme.Walls.use(this, id);
    if (!known) return;
    boolean active = viz ? app.forge.visualiser.WallPick.active(this) : app.forge.theme.Walls.active(this);
    if (active) {
      Toast.makeText(this, "Wallpaper changed", Toast.LENGTH_SHORT).show();
      return;
    }
    // task 1330: the tablet's own wallpaper screen, however its maker named it
    wallAsked = new String[] {viz ? "visualiser" : "theme", before};
    // it says where to tap there itself (task 1334)
    int opened = app.forge.look.LiveWall.open(this,
        new android.content.ComponentName(this, viz ? VIZ_WALL : THEME_WALL)).kind;
    if (opened != app.forge.look.LiveWall.NONE) return;
    // no live wallpaper screen Forge can open: the pick goes back and the card
    // (reloaded) offers the still picture instead; nothing is changed unasked
    wallAsked = null;
    if (before != null && !before.isEmpty()) {
      if (viz) app.forge.visualiser.WallPick.use(this, before);
      else app.forge.theme.Walls.use(this, before);
    }
    if (web != null) web.evaluateJavascript("typeof loadWalls === 'function' && loadWalls()", null);
  }

  /** A theme's picture as a plain wallpaper on the home screen, the lock screen or both. */
  void wallStill(String id, String where) {
    int which = "lock".equals(where) ? android.app.WallpaperManager.FLAG_LOCK
        : "home".equals(where) ? android.app.WallpaperManager.FLAG_SYSTEM
        : android.app.WallpaperManager.FLAG_SYSTEM | android.app.WallpaperManager.FLAG_LOCK;
    String done = "lock".equals(where) ? "Set on the lock screen"
        : "home".equals(where) ? "Set on the home screen" : "Set on the home and lock screens";
    Toast.makeText(this, "Setting the picture...", Toast.LENGTH_SHORT).show();
    new Thread(() -> {
      String err = app.forge.theme.Walls.still(this, id, which);
      runOnUiThread(() -> Toast.makeText(this, err == null ? done : err, Toast.LENGTH_LONG).show());
    }, "forge-still-wallpaper").start();
  }

  /** Put a Forge widget on the home screen: Android shows its own Add box. */
  void widgetAdd(String kind) {
    String cls = kind == null ? null : WIDGETS.get(kind);
    if (cls == null) return;
    android.appwidget.AppWidgetManager m = getSystemService(android.appwidget.AppWidgetManager.class);
    boolean asked = false;
    try {
      asked = m != null && m.isRequestPinAppWidgetSupported()
          && m.requestPinAppWidget(new android.content.ComponentName(this, cls), null, null);
    } catch (RuntimeException e) {
      asked = false;
    }
    if (!asked) {
      Toast.makeText(this, "Long-press the home screen, then Widgets, then Forge", Toast.LENGTH_LONG).show();
    }
  }

  /** The start page's widget names and their classes. */
  static final Map<String, String> WIDGETS = new HashMap<>();

  static {
    WIDGETS.put("clock", "app.forge.theme.Widgets$ClockWidget");
    WIDGETS.put("particles", "app.forge.theme.Widgets$ParticlesWidget");
    WIDGETS.put("music", "app.forge.theme.Widgets$MusicWidget");
    WIDGETS.put("visualiser", "app.forge.visualiser.VizWidget");
    WIDGETS.put("jobs", "app.forge.phone.JobsWidget");
  }

  // -- updates over the air (task 1316) -------------------------------------------

  boolean checking, fetching;
  static final long WAIT_FOR_ALLOW = 15L * 60 * 1000; // back from "allow" later than this: tap Update again

  /** After an update: remember which version came before, for the home page's note. */
  void noteUpdated() {
    long now = Updates.installedCode(this), seen = prefs.getLong("seen_code", 0);
    SharedPreferences.Editor e = prefs.edit().putLong("seen_code", now);
    if (seen != 0 && now > seen) e.putString("updated_from", prefs.getString("seen_name", "")).putLong("updated_at", System.currentTimeMillis());
    if (seen == 0 && Updates.updatedOver(this)) e.putString("updated_from", "").putLong("updated_at", System.currentTimeMillis());
    e.putString("seen_name", Updates.installedName(this)).apply();
  }

  List<Updates.Computer> pcs() {
    List<Updates.Computer> out = new ArrayList<>();
    JSONArray list = computers();
    for (int i = 0; i < list.length(); i++) {
      JSONObject c = list.optJSONObject(i);
      String host = c == null ? "" : c.optString("host");
      if (host.isEmpty()) continue;
      out.add(new Updates.Computer(host, c.optString("pin"), token(host), c.optString("name")));
    }
    return out;
  }

  String ownerCookie() {
    return laptop().isEmpty() ? null : CookieManager.getInstance().getCookie("https://" + laptop() + ":" + HUB_PORT);
  }

  /** Ask every source for a newer Forge; the page hears {"update":"checked"} and reads /update.json. */
  void checkUpdates() {
    if (checking) return;
    checking = true;
    List<Updates.Computer> pcs = pcs();
    String owner = laptop(), cookie = ownerCookie();
    answer("{\"update\":\"checking\"}");
    new Thread(() -> {
      List<String> said = new ArrayList<>();
      Updates.Offer found;
      try {
        found = Updates.check(this, pcs, owner, cookie, said);
      } catch (RuntimeException e) {
        said.add("the check stopped part way (" + e.getClass().getSimpleName() + ")");
        found = null;
      }
      Updates.Offer o = found;
      runOnUiThread(() -> {
        checking = false;
        SharedPreferences.Editor e = prefs.edit().putLong("update_checked", System.currentTimeMillis())
            .putString("update_said", new JSONArray(said).toString());
        if (o != null) e.putString("update_offer", o.json().toString());
        else e.remove("update_offer");
        e.apply();
        answer("{\"update\":\"checked\"}");
      });
    }).start();
  }

  Updates.Offer offer() {
    try {
      Updates.Offer o = Updates.Offer.of(new JSONObject(prefs.getString("update_offer", "{}")));
      return o != null && o.code > Updates.installedCode(this) ? o : null;
    } catch (JSONException e) {
      return null;
    }
  }

  /** Fetch, check and hand the offered build to Android's installer. */
  void installUpdate() {
    Updates.Offer o = offer();
    if (o == null || fetching) return;
    fetching = true;
    JSONObject c = "computer".equals(o.kind) ? computer(o.host) : null;
    Updates.Computer pc = c == null ? null : new Updates.Computer(o.host, c.optString("pin"), token(o.host), c.optString("name"));
    String cookie = ownerCookie();
    answer("{\"update\":\"downloading\",\"done\":0,\"size\":" + o.size + "}");
    new Thread(() -> {
      try {
        File f = Updates.fetch(this, o, pc, cookie,
            (done, size) -> answer("{\"update\":\"downloading\",\"done\":" + done + ",\"size\":" + size + "}"));
        Updates.vet(this, f);
        runOnUiThread(() -> handOver(f));
      } catch (Updates.Stop s) {
        updateFailed(o, s);
      } catch (RuntimeException e) {
        updateFailed(o, new Updates.Stop("The update stopped part way. Try again."));
      }
    }).start();
  }

  /** A download or a file that went wrong: a bad file is passed over by the next check. */
  void updateFailed(Updates.Offer o, Updates.Stop s) {
    if (s.badFile && o != null) Updates.refuse(this, o);
    String why = s.getMessage() + (s.badFile ? " Check for updates again to look for another copy." : "");
    runOnUiThread(() -> {
      fetching = false;
      answer("{\"update\":\"failed\",\"error\":" + Link.quote(why) + "}");
    });
  }

  /** Android asks once whether Forge may install apps; after that the installer asks to confirm. */
  void handOver(File f) {
    if (!getPackageManager().canRequestPackageInstalls()) {
      prefs.edit().putLong("update_waiting_at", System.currentTimeMillis()).apply();
      fetching = false;
      answer("{\"update\":\"allow\"}");
      try {
        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + getPackageName())));
      } catch (ActivityNotFoundException e) {
        answer("{\"update\":\"failed\",\"error\":\"Open Settings, Apps, Forge, Install unknown apps, allow it, then tap Update again.\"}");
      }
      return;
    }
    prefs.edit().remove("update_waiting_at").apply();
    new Thread(() -> {
      try {
        Updates.install(this, f);
        answer("{\"update\":\"handed\"}");
      } catch (Updates.Stop s) {
        answer("{\"update\":\"failed\",\"error\":" + Link.quote(s.getMessage()) + "}");
      }
      runOnUiThread(() -> fetching = false);
    }).start();
  }

  /** Back from Android's "install unknown apps" switch: carry on with the checked file. */
  void resumeUpdate() {
    long asked = prefs.getLong("update_waiting_at", 0);
    if (asked == 0 || fetching) return;
    File f = Updates.file(this);
    if (System.currentTimeMillis() - asked > WAIT_FOR_ALLOW || !f.isFile() || offer() == null) {
      // too long ago to carry on without a tap: the next Update starts again
      prefs.edit().remove("update_waiting_at").apply();
      f.delete();
      return;
    }
    if (!getPackageManager().canRequestPackageInstalls()) return;
    fetching = true;
    new Thread(() -> {
      try {
        Updates.vet(this, f); // the file again: the app may have been closed meanwhile
        runOnUiThread(() -> handOver(f));
      } catch (Updates.Stop s) {
        prefs.edit().remove("update_waiting_at").apply();
        updateFailed(offer(), s);
      } catch (RuntimeException e) {
        prefs.edit().remove("update_waiting_at").apply();
        runOnUiThread(() -> fetching = false);
      }
    }).start();
  }

  /** This Forge, the newer one found (if any), when and what was last asked; a due check starts. */
  WebResourceResponse updateJson() {
    long checked = prefs.getLong("update_checked", 0);
    boolean due = System.currentTimeMillis() - checked > Updates.AUTO_EVERY;
    if (due) runOnUiThread(this::checkUpdates);
    Updates.Offer o = offer();
    JSONObject out = new JSONObject();
    try {
      out.put("version", Updates.installedName(this)).put("code", Updates.installedCode(this))
          .put("checked", checked).put("checking", checking || due).put("fetching", fetching)
          .put("offer", o == null ? JSONObject.NULL : o.json())
          .put("said", new JSONArray(prefs.getString("update_said", "[]")))
          .put("updated_from", prefs.getString("updated_from", ""))
          .put("updated_at", prefs.getLong("updated_at", 0));
    } catch (JSONException e) {
      return json("{}");
    }
    return json(out.toString());
  }

  int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  /** The thin bar over a computer's or an app's pages: back to the start page, the name, reload. */
  View makeBar() {
    bar = new LinearLayout(this);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setBackgroundColor(Color.rgb(20, 24, 33));
    TextView home = barButton("‹  Forge", "Back to the start page and your computers");
    home.setOnClickListener(v -> web.loadUrl(startUrl(null)));
    barTitle = new TextView(this);
    barTitle.setTextColor(Color.rgb(154, 163, 181));
    barTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    barTitle.setSingleLine(true);
    barTitle.setEllipsize(TextUtils.TruncateAt.END);
    barTitle.setPadding(dp(8), 0, dp(8), 0);
    TextView reload = barButton("↻", "Reload");
    reload.setOnClickListener(v -> web.reload());
    bar.addView(home, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    bar.addView(barTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    bar.addView(reload, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT));
    bar.setVisibility(View.GONE);
    return bar;
  }

  TextView barButton(String text, String about) {
    TextView b = new TextView(this);
    b.setText(text);
    b.setContentDescription(about);
    b.setTextColor(Color.rgb(232, 235, 242));
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(16), 0, dp(16), 0);
    b.setClickable(true);
    b.setFocusable(true);
    TypedValue ripple = new TypedValue();
    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    b.setBackgroundResource(ripple.resourceId);
    return b;
  }

  /** Show the bar on every page but the start page, named for where it is. */
  void showBar(String url) {
    Uri uri = Uri.parse(url == null ? "" : url);
    String host = uri.getHost() == null ? "" : uri.getHost();
    if (host.equals(START) || host.isEmpty()) {
      bar.setVisibility(View.GONE);
      return;
    }
    JSONObject c = computerAt(uri);
    String title;
    if (c != null) title = c.optString("name", "").isEmpty() ? c.optString("host") : c.optString("name");
    else if (host.endsWith(LOCAL)) title = "On this device";
    else title = host;
    barTitle.setText(title);
    bar.setVisibility(full == null ? View.VISIBLE : View.GONE);
  }

  /** A scanned or tapped forge-phone://pair?host=&check=&code= link, or null. */
  static Uri pairLink(Intent intent) {
    Uri data = intent == null ? null : intent.getData();
    if (data == null || !"forge-phone".equals(data.getScheme()) || !"pair".equals(data.getHost())) return null;
    return data;
  }

  /** Files shared to Forge from another app (the share sheet); true when there were some. */
  boolean takeShare(Intent intent) {
    if (intent == null) return false;
    List<Uri> got = new ArrayList<>();
    if (Intent.ACTION_SEND.equals(intent.getAction())) {
      Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      if (one != null) got.add(one);
    } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
      ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (many != null) got.addAll(many);
    }
    got.removeIf(u -> u == null || !"content".equals(u.getScheme()));
    if (got.isEmpty()) return false;
    sharing.clear();
    sharing.addAll(got.subList(0, Math.min(got.size(), 50)));
    return true;
  }

  /** The start page, with a pairing link's fields filled in (it never leaves the app). */
  String startUrl(Intent intent) {
    StringBuilder url = new StringBuilder("https://" + START + "/?owner=" + Uri.encode(laptop()));
    Uri link = pairLink(intent);
    if (link != null) {
      for (String k : new String[] {"host", "check", "code"}) {
        String v = link.getQueryParameter(k);
        if (v != null && v.length() < 80) url.append("&pair_").append(k).append('=').append(Uri.encode(v));
      }
    }
    return url.toString();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    web.saveState(out);
  }

  @Override
  public void onBackPressed() {
    if (full != null) leaveFull();
    else if (web.canGoBack()) web.goBack();
    else if (!onStart(web)) web.loadUrl(startUrl(null));
    else super.onBackPressed();
  }

  String laptop() {
    return prefs.getString("host", "");
  }

  // -- the paired computers ------------------------------------------------------

  /** Every paired computer: [{host, pin, name, may}], in the order they were paired. */
  JSONArray computers() {
    try {
      return new JSONArray(prefs.getString("computers", "[]"));
    } catch (JSONException e) {
      return new JSONArray();
    }
  }

  void saveComputers(JSONArray list) {
    prefs.edit().putString("computers", list.toString()).apply();
  }

  /** 0.3 kept one computer as "home" and "pin": it becomes the first of the list. */
  void migrate() {
    String home = prefs.getString("home", ""), pin = prefs.getString("pin", "");
    if (home.isEmpty()) return;
    JSONArray list = computers();
    try {
      if (computer(home) == null && !pin.isEmpty()) list.put(new JSONObject().put("host", home).put("pin", pin).put("name", ""));
    } catch (JSONException e) {
      return;
    }
    saveComputers(list);
    prefs.edit().remove("home").remove("pin").apply();
  }

  JSONObject computer(String host) {
    if (host == null || host.isEmpty()) return null;
    JSONArray list = computers();
    for (int i = 0; i < list.length(); i++) {
      JSONObject c = list.optJSONObject(i);
      if (c != null && host.equals(c.optString("host"))) return c;
    }
    return null;
  }

  String pinFor(String host) {
    JSONObject c = computer(host);
    return c == null ? "" : c.optString("pin", "");
  }

  /** Change or add one computer's entry (by host), keeping the others. */
  synchronized void putComputer(JSONObject entry) {
    JSONArray list = computers(), out = new JSONArray();
    boolean put = false;
    for (int i = 0; i < list.length(); i++) {
      JSONObject c = list.optJSONObject(i);
      if (c == null) continue;
      if (c.optString("host").equals(entry.optString("host"))) {
        out.put(entry);
        put = true;
      } else {
        out.put(c);
      }
    }
    if (!put) out.put(entry);
    saveComputers(out);
  }

  synchronized void dropComputer(String host) {
    JSONArray list = computers(), out = new JSONArray();
    for (int i = 0; i < list.length(); i++) {
      JSONObject c = list.optJSONObject(i);
      if (c != null && !c.optString("host").equals(host)) out.put(c);
    }
    saveComputers(out);
  }

  static String bare(String host) {
    return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  /** The paired computer an https address is on (any of its ports), or null. */
  JSONObject computerAt(Uri uri) {
    if (uri == null || !"https".equals(uri.getScheme())) return null;
    return computer(bare(uri.getHost()));
  }

  boolean ours(Uri uri) {
    String host = uri.getHost();
    if (host == null || !"https".equals(uri.getScheme())) return false;
    return host.endsWith(LOCAL) || (!laptop().isEmpty() && host.equals(laptop())) || computerAt(uri) != null;
  }

  /** A paired computer's token from the WebView's cookie store ("" when none). */
  static String token(String host) {
    String all = CookieManager.getInstance().getCookie(Link.origin(host));
    if (all == null) return "";
    for (String part : all.split(";")) {
      String p = part.trim();
      if (p.startsWith(Link.COOKIE + "=")) return p.substring(Link.COOKIE.length() + 1);
    }
    return "";
  }

  void dropToken(String host) {
    if (host.isEmpty()) return;
    CookieManager cm = CookieManager.getInstance();
    cm.setCookie(Link.origin(host), Link.COOKIE + "=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0");
    cm.flush();
  }

  static boolean onStart(WebView view) {
    Uri now = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
    return START.equals(now.getHost());
  }

  /** Tell the start page how a pairing, a forget or a sending went. */
  void answer(String json) {
    runOnUiThread(() -> {
      if (onStart(web)) web.evaluateJavascript("window.forgeAnswer && forgeAnswer(" + json + ")", null);
    });
  }

  void pair(String host, String check, String code, String name) {
    new Thread(() -> {
      try {
        Link.Paired p = Link.pair(host, check, code, name);
        runOnUiThread(() -> {
          JSONObject old = computer(p.host);
          JSONObject entry = new JSONObject();
          try {
            entry.put("host", p.host).put("pin", p.fingerprint).put("name", old == null ? "" : old.optString("name"));
          } catch (JSONException e) {
            answer("{\"ok\":false,\"error\":\"The pairing could not be saved\"}");
            return;
          }
          putComputer(entry);
          CookieManager cm = CookieManager.getInstance();
          // answered once the cookie is really stored: the start page reads it at once (0.3 showed
          // an empty form until a restart)
          JobsWidget.keepToken(this, p.host, p.token);
          JobsWidget.refreshSoon(this, true);
          cm.setCookie(Link.origin(p.host), Link.COOKIE + "=" + p.token
              + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=15552000", stored -> {
                cm.flush();
                answer("{\"ok\":true,\"host\":" + Link.quote(p.host) + "}");
              });
        });
      } catch (Link.Failure f) {
        answer("{\"ok\":false,\"error\":" + Link.quote(f.getMessage()) + "}");
      }
    }).start();
  }

  void forget(String host) {
    String pin = pinFor(host), token = token(host);
    if (pin.isEmpty()) return;
    new Thread(() -> {
      try {
        if (!token.isEmpty()) Link.signOut(host, pin, token);
      } catch (Link.Failure f) {
        // the computer is away: it still lists this device until Forget there
      }
      runOnUiThread(() -> {
        dropToken(host);
        dropComputer(host);
        JobsWidget.forget(this, host);
        JobsWidget.drawAll(this);
        JobsWidget.plan(this);
        web.clearSslPreferences(); // no proceed decision for the old certificate stays
        answer("{\"ok\":true,\"forgot\":" + Link.quote(host) + "}");
      });
    }).start();
  }

  /** Send the files shared to Forge to one paired computer's inbox, one by one. */
  void send(String host) {
    String pin = pinFor(host), token = token(host);
    List<Uri> files = new ArrayList<>(sharing);
    if (pin.isEmpty() || files.isEmpty()) return;
    ContentResolver cr = getContentResolver();
    new Thread(() -> {
      int done = 0;
      String error = null;
      for (Uri u : files) {
        String name = "shared-file";
        long size = -1;
        try (Cursor c = cr.query(u, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
          if (c != null && c.moveToFirst()) {
            if (!c.isNull(0)) name = c.getString(0);
            if (!c.isNull(1)) size = c.getLong(1);
          }
        } catch (RuntimeException e) {
          // a provider without names: the computer gets "shared-file"
        }
        answer("{\"sending\":" + (done + 1) + ",\"of\":" + files.size() + ",\"name\":" + Link.quote(name) + "}");
        try (InputStream in = cr.openInputStream(u)) {
          if (in == null) throw new IOException("unreadable");
          Link.Reply r = Link.upload(host, pin, token, name, in, size);
          if (r.status != 200) {
            error = Link.said(r);
            break;
          }
          done++;
        } catch (Link.Failure f) {
          error = f.getMessage();
          break;
        } catch (IOException | RuntimeException e) {
          error = "This device could not read " + name + ".";
          break;
        }
      }
      int sent = done;
      String why = error;
      runOnUiThread(() -> {
        // only the files this send took: a share that came in meanwhile stays
        sharing.removeAll(files.subList(0, why == null ? files.size() : sent));
        answer("{\"ok\":" + (why == null) + ",\"sent\":" + sent + ",\"host\":" + Link.quote(host)
            + (why == null ? "" : ",\"error\":" + Link.quote(why)) + "}");
      });
    }).start();
  }

  static WebResourceResponse json(String body) {
    WebResourceResponse r = reply(200, "application/json", body);
    Map<String, String> h = new HashMap<>();
    h.put("Cache-Control", "no-store");
    r.setResponseHeaders(h);
    return r;
  }

  /** The start page's list: paired computers (no pins), the owner's address, files waiting. */
  WebResourceResponse computersJson() {
    JSONArray list = computers(), out = new JSONArray();
    for (int i = 0; i < list.length(); i++) {
      JSONObject c = list.optJSONObject(i);
      if (c == null) continue;
      try {
        out.put(new JSONObject().put("host", c.optString("host")).put("name", c.optString("name"))
            .put("may", c.optJSONArray("may") == null ? new JSONArray() : c.optJSONArray("may")));
      } catch (JSONException e) {
        // skipped
      }
    }
    String body = "{\"computers\":" + out + ",\"owner\":" + Link.quote(laptop())
        + ",\"sharing\":" + sharing.size() + "}";
    return json(body);
  }

  /** One paired computer's view for the start page: its apps, name and what it lets this device do. */
  WebResourceResponse hubJson(String host) {
    // only the start page's own cards may start a look for a moved computer
    return json(hubBody(host, null, startShown));
  }

  /** Whether the start page is the page shown (set as each page starts loading). */
  volatile boolean startShown;

  /** hubJson's answer; `look`: when the computer does not answer, look for it at a new address. */
  String hubBody(String host, String known, boolean look) {
    JSONObject c = computer(host);
    if (c == null) return "{\"paired\":false}";
    String pin = c.optString("pin"), token = known != null ? known : token(host);
    String head = "{\"paired\":true,\"host\":" + Link.quote(host);
    if (token.isEmpty()) return head + ",\"signed_out\":true}";
    try {
      Link.Reply r = Link.get(host, pin, "/hub/api/apps", token);
      if (r.status == 401) return head + ",\"signed_out\":true}";
      if (r.status != 200) return head + ",\"error\":" + Link.quote(Link.said(r)) + "}";
      try { // the computer's own name and what its owner lets this device do, kept for offline use
        JSONObject hub = new JSONObject(r.body);
        JSONObject entry = computer(host);
        if (entry != null) {
          entry.put("name", hub.optString("computer", entry.optString("name")));
          entry.put("may", hub.optJSONArray("may") == null ? new JSONArray() : hub.optJSONArray("may"));
          putComputer(entry);
        }
      } catch (JSONException e) {
        // an older Forge: no name, nothing extra allowed
      }
      return head + ",\"hub\":" + r.body + "}";
    } catch (Link.Failure f) {
      String at = look ? movedTo(host, token) : null;
      if (at != null) return "{\"moved_from\":" + Link.quote(host) + "," + hubBody(at, token, false).substring(1);
      return head + ",\"error\":" + Link.quote(f.getMessage()) + "}";
    }
  }

  /** When each address was last looked for or moved to (once a minute at most). */
  private static final Map<String, Long> lookedFor = new HashMap<>();

  /**
   * A paired computer that no longer answers at its Wi-Fi address may have
   * been given another one by the router: look on this Wi-Fi for the same
   * certificate and move the pairing there (task 1322), so it reconnects with
   * no new pairing. Only for plain home-network IPv4 addresses (not a
   * Tailscale one: that computer is away, not moved). The new address, or null.
   */
  String movedTo(String host, String token) {
    JSONObject c = computer(host);
    byte[] b = Link.bytesOf(host);
    if (c == null || b == null || b.length != 4 || Link.homeAddress(host) == null) return null;
    if ((b[0] & 0xff) == 100 || (b[0] & 0xff) == 127) return null; // Tailscale, or loopback (tests)
    synchronized (lookedFor) {
      Long last = lookedFor.get(host);
      long now = android.os.SystemClock.elapsedRealtime();
      if (last != null && now - last < 60_000) return null;
      lookedFor.put(host, now);
    }
    OnWifi w = onWifi();
    if (w == null) return null;
    List<String> there = new ArrayList<>();
    for (String at : Link.scan(w.own, 400, w.net.getSocketFactory())) {
      if (!at.equals(host) && computer(at) == null) there.add(at);
    }
    String at = Link.findPinned(there, c.optString("pin"));
    if (at == null) return null;
    synchronized (lookedFor) { // no look straight back from the new address
      lookedFor.put(at, android.os.SystemClock.elapsedRealtime());
    }
    moveComputer(host, at, token);
    return at;
  }

  /** The pairing with `old` now lives at `at`: its entry (in its place), token and widgets. */
  void moveComputer(String old, String at, String token) {
    synchronized (this) {
      JSONArray list = computers(), out = new JSONArray();
      for (int i = 0; i < list.length(); i++) {
        JSONObject c = list.optJSONObject(i);
        if (c == null) continue;
        if (old.equals(c.optString("host"))) {
          try {
            c.put("host", at);
          } catch (JSONException e) {
            return;
          }
        }
        out.put(c);
      }
      saveComputers(out);
    }
    if (old.equals(laptop())) prefs.edit().putString("host", at).apply();
    CookieManager cm = CookieManager.getInstance();
    if (!token.isEmpty()) {
      cm.setCookie(Link.origin(at), Link.COOKIE + "=" + token
          + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=15552000");
    }
    dropToken(old);
    JobsWidget.moved(this, old, at, token);
  }

  /** This device's IPv4 address on the Wi-Fi (or a cable) and that network. */
  static final class OnWifi {
    final byte[] own;
    final Network net;

    OnWifi(byte[] own, Network net) {
      this.own = own;
      this.net = net;
    }
  }

  /**
   * The Wi-Fi (or a cable), not whatever network is active: with Tailscale or
   * another VPN on, or on mobile data, the active one is not the home network.
   */
  OnWifi onWifi() {
    try {
      ConnectivityManager cm = getSystemService(ConnectivityManager.class);
      for (Network n : cm.getAllNetworks()) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        LinkProperties lp = cm.getLinkProperties(n);
        if (caps == null || lp == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
        for (LinkAddress la : lp.getLinkAddresses()) {
          if (la.getAddress() instanceof Inet4Address) return new OnWifi(la.getAddress().getAddress(), n);
        }
      }
    } catch (RuntimeException e) {
      return null;
    }
    return null;
  }

  /** Addresses on this Wi-Fi that answer on the phone link's port (Link.scan); the probes go out on that network. */
  WebResourceResponse scanJson() {
    OnWifi w = onWifi();
    if (w == null) return json("{\"found\":[],\"error\":\"This device is not on a Wi-Fi network.\"}");
    JSONArray found = new JSONArray();
    for (String at : Link.scan(w.own, 400, w.net.getSocketFactory())) {
      JSONObject c = computer(at);
      try {
        found.put(new JSONObject().put("host", at).put("paired", c != null).put("name", c == null ? "" : c.optString("name")));
      } catch (JSONException e) {
        // skipped
      }
    }
    return json("{\"found\":" + found + ",\"own\":" + Link.quote(Link.written(w.own)) + "}");
  }

  /** The certificate's DER bytes (Android 10 has getX509Certificate, older ones the saved state). */
  static byte[] der(SslCertificate cert) {
    try {
      if (Build.VERSION.SDK_INT >= 29) return cert.getX509Certificate().getEncoded();
      return SslCertificate.saveState(cert).getByteArray("x509-certificate");
    } catch (Exception e) {
      return null;
    }
  }

  void download(String url, String agent, String disposition, String mime) {
    Uri uri = Uri.parse(url);
    if (!ours(uri)) return;
    if (computerAt(uri) != null) {
      homeDownload(url, disposition, mime);
      return;
    }
    DownloadManager.Request r = new DownloadManager.Request(uri);
    // The session cookie only goes with the hub's own file downloads, which
    // never redirect; DownloadManager would carry it across a redirect.
    if (uri.getHost().equals(laptop()) && uri.getPort() == HUB_PORT
        && "/hub/api/file".equals(uri.getPath())) {
      r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
    }
    r.addRequestHeader("User-Agent", agent);
    r.setMimeType(mime);
    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
    String name = uri.getQueryParameter("where");
    name = name == null ? "forge-download" : name.substring(name.lastIndexOf('/') + 1);
    try {
      if (Build.VERSION.SDK_INT >= 29) {
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
      } else { // Android 8 and 9 need a storage permission for the shared folder
        r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
      }
      ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
    } catch (RuntimeException e) {
      Toast.makeText(this, "The download could not start", Toast.LENGTH_LONG).show();
    }
  }

  /**
   * A file from the paired computer, fetched here over its pinned certificate
   * with its cookie (DownloadManager would trust neither) into Downloads.
   */
  void homeDownload(String url, String disposition, String mime) {
    String pin = pinFor(bare(Uri.parse(url).getHost())), cookie = CookieManager.getInstance().getCookie(url);
    String name = URLUtil.guessFileName(url, disposition, mime).replace('/', '_');
    Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
    new Thread(() -> {
      String said;
      try {
        HttpsURLConnection c = Link.open(url, pin);
        try {
          if (cookie != null) c.setRequestProperty("Cookie", cookie);
          if (c.getResponseCode() != 200) throw new IOException("answered " + c.getResponseCode());
          try (InputStream in = c.getInputStream()) {
            said = "Saved " + name + " in " + save(name, mime, in);
          }
        } finally {
          c.disconnect();
        }
      } catch (IOException | RuntimeException e) {
        said = "The download could not finish";
      }
      String done = said;
      runOnUiThread(() -> Toast.makeText(Main.this, done, Toast.LENGTH_LONG).show());
    }).start();
  }

  /** Write a download into the shared Downloads folder (Forge's own on Android 8 and 9); says where. */
  String save(String name, String mime, InputStream in) throws IOException {
    if (Build.VERSION.SDK_INT < 29) { // the shared folder needs a storage permission there
      File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
      if (dir == null) throw new IOException("no storage");
      try (OutputStream out = new FileOutputStream(new File(dir, name))) {
        copy(in, out);
      }
      return "Forge's files";
    }
    ContentResolver files = getContentResolver();
    ContentValues v = new ContentValues();
    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
    if (mime != null && !mime.isEmpty()) v.put(MediaStore.Downloads.MIME_TYPE, mime);
    v.put(MediaStore.Downloads.IS_PENDING, 1);
    Uri item = files.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
    if (item == null) throw new IOException("no Downloads entry");
    try (OutputStream out = files.openOutputStream(item)) {
      if (out == null) throw new IOException("no Downloads entry");
      copy(in, out);
    } catch (IOException e) {
      files.delete(item, null, null);
      throw e;
    }
    v.clear();
    v.put(MediaStore.Downloads.IS_PENDING, 0);
    files.update(item, v, null, null);
    return "Downloads";
  }

  static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buf = new byte[65536];
    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
  }

  static final String DOWNLOAD_LINKS = "window.forgeDownloads || (window.forgeDownloads = true,"
      + " document.addEventListener('click', (e) => {"
      + " const a = e.target.closest && e.target.closest('a[download][href]');"
      + " if (!a || a.origin !== location.origin || e.defaultPrevented) return;"
      + " e.preventDefault(); location.href = a.href; }))";

  class Client extends WebViewClient {
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      Uri uri = request.getUrl();
      String path = uri.getPath() == null ? "" : uri.getPath();
      if ("/forge-download".equals(path) && uri.getHost() != null && uri.getHost().endsWith(LOCAL)) {
        if (request.isForMainFrame() && sameOfflinePage(view, uri)) pickOfflineExport(uri);
        return true;
      }
      if (START.equals(uri.getHost())
          && path.matches("/(visualiser|theme|update-check|update-install|wall-live|wall-still|widget-add)")) {
        // the visualiser, wallpapers and widgets, and updates: from the start page only
        if (onStart(view) && request.isForMainFrame()) {
          if (path.equals("/visualiser")) openVisualiser();
          else if (path.equals("/theme")) openTheme();
          else if (path.equals("/update-check")) checkUpdates();
          else if (path.equals("/wall-live")) wallLive(uri.getQueryParameter("kind"), uri.getQueryParameter("id"));
          else if (path.equals("/wall-still")) wallStill(uri.getQueryParameter("id"), uri.getQueryParameter("where"));
          else if (path.equals("/widget-add")) widgetAdd(uri.getQueryParameter("kind"));
          else installUpdate();
        }
        return true;
      }
      if (START.equals(uri.getHost())
          && (path.equals("/pair") || path.equals("/open") || path.equals("/forget") || path.equals("/send"))) {
        // Only the start page itself may pair, open, forget or send to a computer.
        if (!onStart(view) || !request.isForMainFrame()) return true;
        String host = uri.getQueryParameter("host");
        if (path.equals("/pair")) {
          pair(host, uri.getQueryParameter("check"), uri.getQueryParameter("code"), uri.getQueryParameter("name"));
        } else if (computer(host) == null) {
          return true;
        } else if (path.equals("/forget")) {
          forget(host);
        } else if (path.equals("/send")) {
          send(host);
        } else {
          String to = uri.getQueryParameter("to");
          // the computer's own Forge page, at a section of it (#remote, #files, #send)
          String hash = to != null && to.matches("[a-z]{1,12}") ? "#" + to : "";
          view.loadUrl(Link.origin(host) + "/" + hash);
        }
        return true;
      }
      if (START.equals(uri.getHost()) && "/connect".equals(uri.getPath())) {
        // Only the start page's own form may set the laptop, and only to a
        // tailnet name: another page cannot point the app somewhere else.
        Uri from = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
        String host = uri.getQueryParameter("host");
        if (START.equals(from.getHost()) && request.isForMainFrame()
            && host != null && host.toLowerCase().matches("([a-z0-9-]{1,63}\\.){2,}ts\\.net")) {
          prefs.edit().putString("host", host.toLowerCase()).apply(); // the owner's way
          view.loadUrl("https://" + host.toLowerCase() + ":" + HUB_PORT + "/");
        }
        return true;
      }
      if (computerAt(uri) != null && onStart(view) && request.isForMainFrame()) {
        // From the start page the computer's cookie (SameSite=Strict) would not
        // go: a link there is cross-site. Opened by the app itself, it does.
        view.loadUrl(uri.toString());
        return true;
      }
      if (ours(uri)) return false;
      try {
        startActivity(new Intent(Intent.ACTION_VIEW, uri)); // anything else: the phone's browser
      } catch (ActivityNotFoundException e) {
        Toast.makeText(Main.this, "No app on this phone opens that link", Toast.LENGTH_LONG).show();
      }
      return true;
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
      startShown = url != null && START.equals(Uri.parse(url).getHost());
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
      startShown = url != null && START.equals(Uri.parse(url).getHost());
      showBar(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      showBar(url);
      // A download link (<a download>) on the paired computer's pages makes the
      // WebView fetch the file outside its pinned certificate, and it fails
      // without a word; opened as a plain link, the file comes to download().
      if (url != null && computerAt(Uri.parse(url)) != null) view.evaluateJavascript(DOWNLOAD_LINKS, null);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
      // Each paired computer's own certificate, at its own address, and no other.
      Uri uri = Uri.parse(error.getUrl() == null ? "" : error.getUrl());
      JSONObject c = computerAt(uri);
      String pin = c == null ? "" : c.optString("pin", "");
      byte[] der = error.getCertificate() == null ? null : der(error.getCertificate());
      if (!pin.isEmpty() && der != null
          && MessageDigest.isEqual(Link.fingerprint(der).getBytes(StandardCharsets.US_ASCII),
              pin.getBytes(StandardCharsets.US_ASCII))) {
        handler.proceed();
      } else {
        handler.cancel();
        if (c != null) {
          Toast.makeText(Main.this, "Not the certificate this device paired with: forget the computer and pair again.", Toast.LENGTH_LONG).show();
        }
      }
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
      Uri uri = request.getUrl();
      String host = uri.getHost();
      if (host == null || !host.endsWith(LOCAL)) return null;
      String app = host.substring(0, host.length() - LOCAL.length());
      String path = uri.getPath() == null ? "/" : uri.getPath();
      if (!app.matches("[a-z0-9-]+") || path.contains("..")) return reply(404, "text/plain", "Nothing here");
      if (app.equals("start") && path.equals("/computers.json")) return computersJson();
      if (app.equals("start") && path.equals("/hub.json")) return hubJson(uri.getQueryParameter("host"));
      if (app.equals("start") && path.equals("/scan.json")) return scanJson();
      if (app.equals("start") && path.equals("/update.json")) return updateJson();
      if (app.equals("start") && path.equals("/look.json")) return lookJson();
      if (app.equals("start") && path.equals("/walls.json")) return wallsJson();
      if (path.equals("/forge-public-fetch") && !app.equals("start")) return publicFetch(app, uri, request);
      if (path.startsWith("/api/")) return reply(503, "application/json", "{\"error\":\"offline\"}");
      if (path.endsWith("/")) path += "index.html";
      String base = app.equals("start") ? "start" : "offline/" + app + "/www";
      // the theme pictures (Forge Theme's walls) behind the home page's top, and the
      // visualiser's list of looks for its tile; themes and looks for Wallpapers
      if (app.equals("start") && (path.matches("/walls/[a-z0-9._-]+\\.(jpg|png|webp)")
          || path.equals("/visualiser/looks.json") || path.equals("/visualiser/presets.json")
          || path.equals("/themes.json"))) base = "";
      try {
        InputStream in = getAssets().open(base.isEmpty() ? path.substring(1) : base + path);
        WebResourceResponse r = new WebResourceResponse(type(path), "utf-8", in);
        Map<String, String> h = new HashMap<>();
        h.put("Content-Security-Policy", "connect-src 'self'; object-src 'none'");
        h.put("Cache-Control", "no-cache");
        // an offline app's own headers from phone_apps.json (build_offline.py writes
        // them to www/forge-offline-headers.json): the AI app's model download
        // (connect-src) and its threads (COOP/COEP), task 1325
        if (!app.equals("start")) h.putAll(appHeaders(app));
        r.setResponseHeaders(h);
        return r;
      } catch (IOException e) {
        return reply(404, "text/plain", "Nothing here");
      }
    }
  }

  final Map<String, Map<String, String>> headerCache = new HashMap<>();

  /** The extra response headers an offline app asks for (none when it has no file). */
  synchronized Map<String, String> appHeaders(String app) {
    Map<String, String> got = headerCache.get(app);
    if (got != null) return got;
    got = new HashMap<>();
    try (InputStream in = getAssets().open("offline/" + app + "/www/forge-offline-headers.json")) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
      JSONObject o = new JSONObject(buf.toString("UTF-8"));
      for (Iterator<String> k = o.keys(); k.hasNext(); ) {
        String name = k.next();
        got.put(name, o.getString(name));
      }
    } catch (IOException | JSONException e) {
      // no file: the default headers only
    }
    headerCache.put(app, got);
    return got;
  }

  final Map<String, JSONObject> nativeCache = new HashMap<>();

  /** What phone features an offline app's registry entry allows (build_offline.py writes
   * them to www/forge-offline-native.json); empty when it asks for none. */
  synchronized JSONObject nativeOptions(String app) {
    JSONObject got = nativeCache.get(app);
    if (got != null) return got;
    got = new JSONObject();
    try (InputStream in = getAssets().open("offline/" + app + "/www/forge-offline-native.json")) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
      got = new JSONObject(buf.toString("UTF-8"));
    } catch (IOException | JSONException e) {
      // no file: nothing native
    }
    nativeCache.put(app, got);
    return got;
  }

  /** A public page or picture for an app allowed public_fetch (Shelf saving an article):
   * the bytes, with the address it landed on and its type in X-Forge-Url and X-Forge-Type. */
  WebResourceResponse publicFetch(String app, Uri uri, WebResourceRequest request) {
    JSONObject allowed = nativeOptions(app).optJSONObject("public_fetch");
    if (allowed == null || !"GET".equals(request.getMethod()))
      return json(404, "This app cannot fetch web pages");
    // Only the app's own page may ask: another page's fetch or <img> cannot
    // send this header without a CORS preflight, which is never answered.
    Map<String, String> sent = request.getRequestHeaders();
    String site = null, mark = null;
    if (sent != null)
      for (Map.Entry<String, String> e : sent.entrySet()) {
        if ("x-forge-fetch".equalsIgnoreCase(e.getKey())) mark = e.getValue();
        if ("sec-fetch-site".equalsIgnoreCase(e.getKey())) site = e.getValue();
      }
    if (!"1".equals(mark) || (site != null && !"same-origin".equals(site)))
      return json(403, "Only this app's own page can fetch web pages");
    String address = uri.getQueryParameter("url");
    if (address == null || address.length() > 8000) return json(400, "That is not a web address");
    double mb = allowed.optDouble("max_mb", 8);
    try {
      double asked = Double.parseDouble(uri.getQueryParameter("max_mb"));
      if (asked > 0 && asked < mb) mb = asked;
    } catch (NullPointerException | NumberFormatException e) {
      // the app's own limit
    }
    try {
      PublicFetch.Got got = PublicFetch.fetch(address, (long) (mb * 1024 * 1024));
      WebResourceResponse r = new WebResourceResponse("application/octet-stream", null,
          new ByteArrayInputStream(got.body));
      Map<String, String> h = new HashMap<>();
      h.put("X-Forge-Url", got.url);
      h.put("X-Forge-Type", got.type);
      h.put("Cache-Control", "no-store");
      h.put("X-Content-Type-Options", "nosniff");
      r.setResponseHeaders(h);
      return r;
    } catch (PublicFetch.Refused e) {
      return json(e.status, e.getMessage());
    }
  }

  static WebResourceResponse json(int code, String detail) {
    WebResourceResponse r = new WebResourceResponse("application/json", "utf-8",
        new ByteArrayInputStream(("{\"detail\":" + JSONObject.quote(detail) + "}")
            .getBytes(StandardCharsets.UTF_8)));
    r.setStatusCodeAndReasonPhrase(code, code < 400 ? "OK" : "Refused");
    return r;
  }

  static WebResourceResponse reply(int code, String type, String body) {
    WebResourceResponse r = new WebResourceResponse(type, "utf-8",
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    r.setStatusCodeAndReasonPhrase(code, code == 503 ? "Offline" : "Not Found");
    return r;
  }

  static String type(String path) {
    String p = path.toLowerCase();
    if (p.endsWith(".html")) return "text/html";
    if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
    if (p.endsWith(".css")) return "text/css";
    if (p.endsWith(".json")) return "application/json";
    if (p.endsWith(".wasm")) return "application/wasm";
    if (p.endsWith(".svg")) return "image/svg+xml";
    if (p.endsWith(".png")) return "image/png";
    if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
    if (p.endsWith(".webp")) return "image/webp";
    if (p.endsWith(".woff2")) return "font/woff2";
    if (p.endsWith(".mp3")) return "audio/mpeg";
    if (p.endsWith(".ogg")) return "audio/ogg";
    if (p.endsWith(".wav")) return "audio/wav";
    return "application/octet-stream";
  }

  void leaveFull() {
    if (full == null) return;
    root.removeView(full);
    full = null;
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    web.setVisibility(View.VISIBLE);
    showBar(web.getUrl());
    if (fullDone != null) fullDone.onCustomViewHidden();
    fullDone = null;
  }

  /** How strong this phone is, as JSON in device.json's profile keys. */
  class Device {
    @JavascriptInterface
    public String profile() {
      StringBuilder out = new StringBuilder("{");
      ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
      ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
      am.getMemoryInfo(mem);
      out.append("\"memory_gb\":").append(Math.round(mem.totalMem / 1073741824.0 * 10) / 10.0);
      out.append(",\"cores\":").append(Runtime.getRuntime().availableProcessors());
      out.append(",\"low_ram\":").append(am.isLowRamDevice());
      PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
      out.append(",\"power_save\":").append(pm.isPowerSaveMode());
      if (Build.VERSION.SDK_INT >= 29) out.append(",\"thermal\":").append(pm.getCurrentThermalStatus());
      BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
      int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
      if (level >= 0 && level <= 100) {
        out.append(",\"battery\":").append(level);
        out.append(",\"charging\":").append(bm.isCharging());
      }
      out.append(",\"android\":").append(Build.VERSION.SDK_INT);
      // a tablet by Android's own rule (smallest width 600 dp), for the start page's words and layout
      out.append(",\"tablet\":").append(getResources().getConfiguration().smallestScreenWidthDp >= 600);
      return out.append("}").toString();
    }
  }

  class Chrome extends WebChromeClient {
    @Override
    public void onShowCustomView(View view, CustomViewCallback done) {
      if (full != null) {
        done.onCustomViewHidden();
        return;
      }
      full = view;
      fullDone = done;
      root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      web.setVisibility(View.INVISIBLE);
      bar.setVisibility(View.GONE);
      getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onHideCustomView() {
      leaveFull();
    }

    @Override
    public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
      if (picking != null) picking.onReceiveValue(null);
      picking = callback;
      try {
        startActivityForResult(params.createIntent(), 7);
      } catch (Exception e) {
        picking = null;
        return false;
      }
      return true;
    }
  }

  /**
   * Back from Android's live wallpaper screen (onResume; not a result, since
   * that screen may run in its own task and answer "cancelled" at once): if
   * the Forge wallpaper was not set after all, the pick goes back to what it
   * was, so the theme and the widgets that follow it do not change by
   * themselves.
   */
  void wallBack() {
    // task 1334: on now, or exactly where to tap again
    String said = app.forge.look.LiveWall.back(this);
    if (said != null) Toast.makeText(this, said, Toast.LENGTH_LONG).show();
    String[] asked = wallAsked;
    wallAsked = null;
    if (asked == null) return;
    boolean viz = "visualiser".equals(asked[0]);
    boolean active = viz ? app.forge.visualiser.WallPick.active(this) : app.forge.theme.Walls.active(this);
    if (active || asked[1] == null || asked[1].isEmpty()) return;
    if (viz) app.forge.visualiser.WallPick.use(this, asked[1]);
    else app.forge.theme.Walls.use(this, asked[1]);
  }

  @Override
  protected void onActivityResult(int code, int result, Intent data) {
    if (code == 7 && picking != null) {
      picking.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
      picking = null;
    }
    if (code == 8 && exportId != null) {
      if (result == RESULT_OK && data != null && data.getData() != null
          && exportPage.equals(web.getUrl())) {
        try {
          java.io.OutputStream output = getContentResolver().openOutputStream(data.getData(), "w");
          if (output == null) throw new java.io.IOException("No output stream");
          copyOfflineExport(output, exportId, exportPage, exportBytes, 0, 0);
        } catch (java.io.IOException | RuntimeException e) {
          Toast.makeText(this, "The file could not be saved. Try Download again.", Toast.LENGTH_LONG).show();
        }
      }
      exportId = null;
    }
    super.onActivityResult(code, result, data);
  }

  String exportId, exportPage;
  long exportBytes;

  boolean sameOfflinePage(WebView view, Uri destination) {
    Uri source = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
    return "https".equals(source.getScheme()) && "https".equals(destination.getScheme())
        && destination.getHost().equals(source.getHost()) && !START.equals(source.getHost());
  }

  /** The local page owns the bytes. Android's picker owns the destination, so
   * an offline app never receives permission to read arbitrary phone files. */
  void pickOfflineExport(Uri uri) {
    String id = uri.getQueryParameter("id"), name = uri.getQueryParameter("name");
    if (id == null || !id.matches("[a-f0-9]{32}") || name == null || name.length() > 160
        || name.contains("/") || name.contains("\\")) return;
    String type = uri.getQueryParameter("type");
    try {
      exportBytes = Long.parseLong(uri.getQueryParameter("bytes"));
      if (exportBytes <= 0 || exportBytes > 200L * 1024 * 1024) return;
    } catch (NumberFormatException e) { return; }
    Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType(type != null && type.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") ? type : "application/octet-stream")
        .putExtra(Intent.EXTRA_TITLE, name);
    exportId = id;
    exportPage = web.getUrl();
    try {
      startActivityForResult(pick, 8);
    } catch (ActivityNotFoundException e) {
      exportId = null;
      Toast.makeText(this, "This tablet has no file picker to save the export", Toast.LENGTH_LONG).show();
    }
  }

  void copyOfflineExport(java.io.OutputStream output, String id, String page, long expected, int chunk, long bytes) {
    if (!page.equals(web.getUrl())) {
      try { output.close(); } catch (java.io.IOException ignored) { }
      return;
    }
    String expression = "window.ForgeOffline ? ForgeOffline.exportChunk(" + JSONObject.quote(id) + "," + chunk + ") : null";
    web.evaluateJavascript(expression, value -> {
      try {
        if (value == null || "null".equals(value)) {
          if (bytes != expected) throw new java.io.IOException("Export incomplete");
          output.close();
          Toast.makeText(this, "File saved", Toast.LENGTH_LONG).show();
          return;
        }
        String encoded = new JSONArray("[" + value + "]").getString(0);
        byte[] part = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
        if (part.length > 49152 || bytes + part.length > expected) throw new java.io.IOException("Export too big");
        output.write(part);
        web.post(() -> copyOfflineExport(output, id, page, expected, chunk + 1, bytes + part.length));
      } catch (java.io.IOException | JSONException | IllegalArgumentException e) {
        try { output.close(); } catch (java.io.IOException ignored) { }
        Toast.makeText(this, "The file could not be saved. Try Download again.", Toast.LENGTH_LONG).show();
      }
    });
  }
}
