package app.forge.phone;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
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
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

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
 * takes its address, check code and pairing code (typed, pasted or from a
 * forge-phone://pair link the camera opens); Link.java pins the computer's
 * own certificate by the check code and pairs. The token lives only in the
 * WebView's cookie store, the pin in this app's private settings, and only
 * that one certificate is accepted for that address. The start page lists
 * the apps the computer offers (its /hub/api/apps, fetched here as
 * /hub.json). Owner: the architect's own laptop over Tailscale (.ts.net),
 * signed in with its password and authenticator code on the hub's page.
 */
public class Main extends Activity {
  static final String LOCAL = ".offline.forge";
  static final int HUB_PORT = 9464;
  WebView web;
  SharedPreferences prefs;
  ValueCallback<Uri[]> picking;
  FrameLayout root;
  View full;
  WebChromeClient.CustomViewCallback fullDone;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = getSharedPreferences("forge", MODE_PRIVATE);
    // loopback is the test computer only on an emulator (adb reverse); on a phone it is the phone
    Link.loopbackOk = "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE);
    web = new WebView(this);
    web.setBackgroundColor(Color.rgb(11, 13, 18));
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
    root = new FrameLayout(this);
    root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setContentView(root);
    if (saved != null) web.restoreState(saved);
    else web.loadUrl(startUrl(getIntent()));
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    if (pairLink(intent) != null) web.loadUrl(startUrl(intent));
  }

  /** A scanned or tapped forge-phone://pair?host=&check=&code= link, or null. */
  static Uri pairLink(Intent intent) {
    Uri data = intent == null ? null : intent.getData();
    if (data == null || !"forge-phone".equals(data.getScheme()) || !"pair".equals(data.getHost())) return null;
    return data;
  }

  /** The start page, with a pairing link's fields filled in (it never leaves the app). */
  String startUrl(Intent intent) {
    StringBuilder url = new StringBuilder("https://start" + LOCAL + "/?owner=" + Uri.encode(laptop()));
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
    else super.onBackPressed();
  }

  String laptop() {
    return prefs.getString("host", "");
  }

  /** The paired computer's address on the home network ("" when none). */
  String home() {
    return prefs.getString("home", "");
  }

  static String bare(String host) {
    return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  boolean atHome(Uri uri) {
    return !home().isEmpty() && home().equals(bare(uri.getHost()));
  }

  boolean ours(Uri uri) {
    String host = uri.getHost();
    if (host == null || !"https".equals(uri.getScheme())) return false;
    return host.endsWith(LOCAL) || (!laptop().isEmpty() && host.equals(laptop())) || atHome(uri);
  }

  /** The paired phone's token from the WebView's cookie store ("" when none). */
  String token() {
    String all = CookieManager.getInstance().getCookie(Link.origin(home()));
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
    return ("start" + LOCAL).equals(now.getHost());
  }

  /** Tell the start page how a pairing or a forget went. */
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
          dropToken(home()); // pairing another computer replaces the old one
          CookieManager cm = CookieManager.getInstance();
          cm.setCookie(Link.origin(p.host), Link.COOKIE + "=" + p.token
              + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=15552000");
          cm.flush();
          prefs.edit().putString("home", p.host).putString("pin", p.fingerprint).apply();
          answer("{\"ok\":true}");
        });
      } catch (Link.Failure f) {
        answer("{\"ok\":false,\"error\":" + Link.quote(f.getMessage()) + "}");
      }
    }).start();
  }

  void forget() {
    String host = home(), pin = prefs.getString("pin", ""), token = token();
    new Thread(() -> {
      try {
        if (!host.isEmpty() && !token.isEmpty()) Link.signOut(host, pin, token);
      } catch (Link.Failure f) {
        // the computer is away: it still lists the phone until Forget there
      }
      runOnUiThread(() -> {
        dropToken(host);
        prefs.edit().remove("home").remove("pin").apply();
        web.clearSslPreferences(); // no proceed decision for the old certificate stays
        web.clearHistory();
        answer("{\"ok\":true,\"forgot\":true}");
      });
    }).start();
  }

  /** The start page's view of the paired computer: its app list, or why not. */
  WebResourceResponse hubJson() {
    String host = home(), pin = prefs.getString("pin", ""), token = token();
    String out;
    if (host.isEmpty() || pin.isEmpty()) {
      out = "{\"paired\":false}";
    } else {
      String head = "{\"paired\":true,\"host\":" + Link.quote(host);
      if (token.isEmpty()) {
        out = head + ",\"signed_out\":true}";
      } else {
        try {
          Link.Reply r = Link.get(host, pin, "/hub/api/apps", token);
          if (r.status == 200) out = head + ",\"hub\":" + r.body + "}";
          else if (r.status == 401) out = head + ",\"signed_out\":true}";
          else out = head + ",\"error\":" + Link.quote(Link.said(r)) + "}";
        } catch (Link.Failure f) {
          out = head + ",\"error\":" + Link.quote(f.getMessage()) + "}";
        }
      }
    }
    WebResourceResponse r = reply(200, "application/json", out);
    Map<String, String> h = new HashMap<>();
    h.put("Cache-Control", "no-store");
    r.setResponseHeaders(h);
    return r;
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
    if (atHome(uri)) {
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
    String pin = prefs.getString("pin", ""), cookie = CookieManager.getInstance().getCookie(url);
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
      if (("start" + LOCAL).equals(uri.getHost())
          && (path.equals("/pair") || path.equals("/open") || path.equals("/forget"))) {
        // Only the start page itself may pair, open or forget the computer.
        if (!onStart(view) || !request.isForMainFrame()) return true;
        if (path.equals("/pair")) {
          pair(uri.getQueryParameter("host"), uri.getQueryParameter("check"),
              uri.getQueryParameter("code"), uri.getQueryParameter("name"));
        } else if (path.equals("/forget")) {
          forget();
        } else if (!home().isEmpty()) {
          view.loadUrl(Link.origin(home()) + "/");
        }
        return true;
      }
      if (("start" + LOCAL).equals(uri.getHost()) && "/connect".equals(uri.getPath())) {
        // Only the start page's own form may set the laptop, and only to a
        // tailnet name: another page cannot point the app somewhere else.
        Uri from = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
        String host = uri.getQueryParameter("host");
        if (("start" + LOCAL).equals(from.getHost()) && request.isForMainFrame()
            && host != null && host.toLowerCase().matches("([a-z0-9-]{1,63}\\.){2,}ts\\.net")) {
          prefs.edit().putString("host", host.toLowerCase()).apply(); // the owner's way
          view.loadUrl("https://" + host.toLowerCase() + ":" + HUB_PORT + "/");
        }
        return true;
      }
      if (atHome(uri) && onStart(view) && request.isForMainFrame()) {
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
    public void onPageFinished(WebView view, String url) {
      // A download link (<a download>) on the paired computer's pages makes the
      // WebView fetch the file outside its pinned certificate, and it fails
      // without a word; opened as a plain link, the file comes to download().
      if (url != null && atHome(Uri.parse(url))) view.evaluateJavascript(DOWNLOAD_LINKS, null);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
      // The paired computer's own certificate, at its own address, and no other.
      String pin = prefs.getString("pin", "");
      byte[] der = error.getCertificate() == null ? null : der(error.getCertificate());
      Uri uri = Uri.parse(error.getUrl() == null ? "" : error.getUrl());
      if (!pin.isEmpty() && der != null && "https".equals(uri.getScheme()) && atHome(uri)
          && MessageDigest.isEqual(Link.fingerprint(der).getBytes(StandardCharsets.US_ASCII),
              pin.getBytes(StandardCharsets.US_ASCII))) {
        handler.proceed();
      } else {
        handler.cancel();
        if (atHome(uri)) {
          Toast.makeText(Main.this, "Not the certificate this phone paired with: forget the computer and pair again.", Toast.LENGTH_LONG).show();
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
      if (app.equals("start") && path.equals("/hub.json")) return hubJson();
      if (path.startsWith("/api/")) return reply(503, "application/json", "{\"error\":\"offline\"}");
      if (path.endsWith("/")) path += "index.html";
      String base = app.equals("start") ? "start" : "offline/" + app + "/www";
      try {
        InputStream in = getAssets().open(base + path);
        WebResourceResponse r = new WebResourceResponse(type(path), "utf-8", in);
        Map<String, String> h = new HashMap<>();
        h.put("Content-Security-Policy", "connect-src 'self'; object-src 'none'");
        h.put("Cache-Control", "no-cache");
        r.setResponseHeaders(h);
        return r;
      } catch (IOException e) {
        return reply(404, "text/plain", "Nothing here");
      }
    }
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

  @Override
  protected void onActivityResult(int code, int result, Intent data) {
    if (code == 7 && picking != null) {
      picking.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
      picking = null;
    }
    super.onActivityResult(code, result, data);
  }
}
