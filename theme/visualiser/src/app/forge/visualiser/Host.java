/* The WebView that runs the visualiser page, for the player and for the
   wallpaper. Its pages come only from the APK's assets, under a made-up
   https origin (no file:// and no network): host/ is the page itself,
   visualiser/ the shared looks (make_assets.py copies shared/visualiser).
   Any other address gets nothing, so the looks work offline and nothing
   else can reach the bridge. */
package app.forge.visualiser;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

final class Host {
  static final String HOST = "forge.visualiser";
  static final String PAGE = "https://" + HOST + "/host/index.html?mode=";

  private Host() {}

  /** A WebView on the visualiser page; gone runs (on the main thread) if the
      system stops the page's renderer, after which this view is dead. */
  static WebView make(Context ctx, String mode, Bridge bridge, Runnable gone) {
    WebView w = new WebView(ctx);
    WebSettings s = w.getSettings();
    s.setJavaScriptEnabled(true);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMediaPlaybackRequiresUserGesture(true);
    s.setSupportZoom(false);
    s.setSafeBrowsingEnabled(false);
    s.setCacheMode(WebSettings.LOAD_NO_CACHE);
    w.setBackgroundColor(Color.BLACK);
    w.setVerticalScrollBarEnabled(false);
    w.setHorizontalScrollBarEnabled(false);
    w.setOverScrollMode(View.OVER_SCROLL_NEVER);
    w.addJavascriptInterface(bridge, "ForgeAndroid");
    w.setWebViewClient(new Assets(ctx.getAssets(), gone));
    w.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onConsoleMessage(ConsoleMessage m) {
        Log.i("ForgeViz", m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
        return true;
      }
    });
    w.loadUrl(PAGE + mode);
    return w;
  }

  /** Call host.<code> in the page once it has loaded (a no-op before). */
  static void js(WebView w, String code) {
    if (w != null) w.evaluateJavascript("window.host && host." + code, null);
  }

  static String mime(String path) {
    if (path.endsWith(".html")) return "text/html";
    if (path.endsWith(".js")) return "text/javascript";
    if (path.endsWith(".json")) return "application/json";
    if (path.endsWith(".css")) return "text/css";
    if (path.endsWith(".png")) return "image/png";
    if (path.endsWith(".svg")) return "image/svg+xml";
    return "application/octet-stream";
  }

  /** The asset path an address maps to, or null when it is not one of ours. */
  static String assetPath(Uri u) {
    if (!"https".equals(u.getScheme()) || !HOST.equals(u.getHost())) return null;
    String path = u.getPath();
    if (path == null || !path.startsWith("/")) return null;
    path = path.substring(1);
    if (path.isEmpty() || path.contains("..") || path.contains("//") || path.contains("\\")) {
      return null;
    }
    return path;
  }

  static final class Assets extends WebViewClient {
    private final AssetManager assets;
    private final Runnable gone;
    private final Map<String, String> headers = new HashMap<>();

    Assets(AssetManager assets, Runnable gone) {
      this.assets = assets;
      this.gone = gone;
      headers.put("Cache-Control", "no-store");
    }

    @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
      String path = assetPath(r.getUrl());
      if (path != null) {
        try {
          InputStream in = assets.open(path);
          return new WebResourceResponse(mime(path), "utf-8", 200, "OK", headers, in);
        } catch (IOException e) {
          // not in the APK: falls through to nothing
        }
      }
      return new WebResourceResponse("text/plain", "utf-8", 404, "Not here", headers,
          new ByteArrayInputStream(new byte[0]));
    }

    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
      return true; // the page never navigates away
    }

    @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
      Log.w("ForgeViz", "page renderer stopped" + (d.didCrash() ? " (crashed)" : " (memory)"));
      gone.run();
      return true;
    }
  }
}
