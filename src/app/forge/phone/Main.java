package app.forge.phone;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Forge on the phone: one WebView. The start page and the apps that run on
 * the phone alone come from this APK's own files, each on its own made-up
 * https origin (start.offline.forge, focus.offline.forge, ...) that never
 * reaches the network. The laptop's phone hub opens at the saved address;
 * its sign-in is the laptop's, nothing secret is kept in the app.
 */
public class Main extends Activity {
  static final String LOCAL = ".offline.forge";
  static final int HUB_PORT = 9464;
  WebView web;
  SharedPreferences prefs;
  ValueCallback<Uri[]> picking;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = getSharedPreferences("forge", MODE_PRIVATE);
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
    web.setDownloadListener((url, agent, disposition, mime, length) -> download(url, agent, mime));
    setContentView(web);
    if (saved != null) web.restoreState(saved);
    else web.loadUrl("https://start" + LOCAL + "/?host=" + Uri.encode(laptop()));
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    web.saveState(out);
  }

  @Override
  public void onBackPressed() {
    if (web.canGoBack()) web.goBack();
    else super.onBackPressed();
  }

  String laptop() {
    return prefs.getString("host", "");
  }

  boolean ours(Uri uri) {
    String host = uri.getHost();
    if (host == null || !"https".equals(uri.getScheme())) return false;
    return host.endsWith(LOCAL) || (!laptop().isEmpty() && host.equals(laptop()));
  }

  void download(String url, String agent, String mime) {
    Uri uri = Uri.parse(url);
    if (!ours(uri)) return;
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

  class Client extends WebViewClient {
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      Uri uri = request.getUrl();
      if (("start" + LOCAL).equals(uri.getHost()) && "/connect".equals(uri.getPath())) {
        // Only the start page's own form may set the laptop, and only to a
        // tailnet name: another page cannot point the app somewhere else.
        Uri from = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
        String host = uri.getQueryParameter("host");
        if (("start" + LOCAL).equals(from.getHost()) && request.isForMainFrame()
            && host != null && host.toLowerCase().matches("([a-z0-9-]{1,63}\\.){2,}ts\\.net")) {
          prefs.edit().putString("host", host.toLowerCase()).apply();
          view.loadUrl("https://" + host.toLowerCase() + ":" + HUB_PORT + "/");
        }
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
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
      Uri uri = request.getUrl();
      String host = uri.getHost();
      if (host == null || !host.endsWith(LOCAL)) return null;
      String app = host.substring(0, host.length() - LOCAL.length());
      String path = uri.getPath() == null ? "/" : uri.getPath();
      if (!app.matches("[a-z0-9-]+") || path.contains("..")) return reply(404, "text/plain", "Nothing here");
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

  class Chrome extends WebChromeClient {
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
