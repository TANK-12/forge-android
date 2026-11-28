package app.forge.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.KeyEvent;
import app.forge.look.MediaKeys;
import java.util.List;

/**
 * Event-driven Now Playing listener for MediaWidget (Relay task 1591).
 *
 * Battery rules:
 * - Zero polling loops or timers: listens only to active MediaController
 *   metadata and playback-state callbacks.
 * - Does zero work on regular notifications (onNotificationPosted is a no-op).
 * - Only attaches MediaController callbacks while at least one MediaWidget is
 *   placed on the home screen.
 * - Deduplicates updates by (title, artist, package, playing) so playback
 *   position ticks never trigger home-screen redraws.
 * - Completely optional: if Notification Access is not granted, MediaWidget
 *   still controls playback cleanly via MediaKeys.
 */
public final class MediaBridge extends NotificationListenerService {
  private static final String TAG = "ForgeMedia";
  static final String PREFS = "forge_media";

  private static volatile MediaBridge instance;
  private final Handler main = new Handler(Looper.getMainLooper());
  private MediaSessionManager sessionManager;
  private MediaController activeController;
  private String lastSignature = "";

  private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
      controllers -> bindBest(controllers);

  private final MediaController.Callback controllerCallback = new MediaController.Callback() {
    @Override public void onMetadataChanged(MediaMetadata metadata) {
      publish(activeController);
    }

    @Override public void onPlaybackStateChanged(PlaybackState state) {
      publish(activeController);
    }

    @Override public void onSessionDestroyed() {
      refreshSessions();
    }
  };

  static SharedPreferences store(Context c) {
    return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static boolean isAccessEnabled(Context c) {
    try {
      String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
      if (flat == null || flat.isEmpty()) return false;
      String me = new ComponentName(c, MediaBridge.class).flattenToString();
      String shortMe = new ComponentName(c, MediaBridge.class).flattenToShortString();
      return flat.contains(me) || flat.contains(shortMe);
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static Intent accessSettingsIntent() {
    return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  /** Sync binding state when MediaWidgets are added or removed. */
  public static void syncActive(Context c) {
    MediaBridge b = instance;
    if (b != null) b.main.post(b::syncBinding);
  }

  /** Send a media command via the active MediaController if bound, else MediaKeys. */
  public static boolean control(Context c, String op) {
    MediaBridge b = instance;
    MediaController ctrl = b == null ? null : b.activeController;
    if (ctrl != null) {
      try {
        MediaController.TransportControls tc = ctrl.getTransportControls();
        if ("prev".equals(op)) {
          tc.skipToPrevious();
          return true;
        } else if ("next".equals(op)) {
          tc.skipToNext();
          return true;
        } else if ("pause".equals(op)) {
          tc.pause();
          return true;
        } else if ("play".equals(op)) {
          PlaybackState st = ctrl.getPlaybackState();
          if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause();
          else tc.play();
          return true;
        }
      } catch (RuntimeException e) {
        Log.w(TAG, "TransportControls failed, falling back to MediaKeys", e);
      }
    }
    int code = "prev".equals(op) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
        : "next".equals(op) ? KeyEvent.KEYCODE_MEDIA_NEXT
        : "pause".equals(op) ? KeyEvent.KEYCODE_MEDIA_PAUSE
        : "play".equals(op) ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : 0;
    if (code != 0) {
      MediaKeys.press(c, code);
      return true;
    }
    return false;
  }

  @Override public void onListenerConnected() {
    super.onListenerConnected();
    instance = this;
    sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
    syncBinding();
  }

  @Override public void onListenerDisconnected() {
    detachAll();
    if (instance == this) instance = null;
    super.onListenerDisconnected();
  }

  @Override public void onDestroy() {
    detachAll();
    if (instance == this) instance = null;
    super.onDestroy();
  }

  @Override public void onNotificationPosted(StatusBarNotification sbn) {
    // Intentionally empty: we never read user notifications, only media sessions.
  }

  @Override public void onNotificationRemoved(StatusBarNotification sbn) {
    // Intentionally empty.
  }

  private void syncBinding() {
    if (sessionManager == null) return;
    if (!MediaWidget.hasWidgets(this) && !CustomWidget.hasMediaWidgets(this)) {
      detachAll();
      return;
    }
    ComponentName me = new ComponentName(this, MediaBridge.class);
    try {
      sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
      sessionManager.addOnActiveSessionsChangedListener(sessionsListener, me, main);
      bindBest(sessionManager.getActiveSessions(me));
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not bind media sessions", e);
    }
  }

  private void refreshSessions() {
    if (sessionManager == null || (!MediaWidget.hasWidgets(this) && !CustomWidget.hasMediaWidgets(this))) {
      detachController();
      return;
    }
    try {
      bindBest(sessionManager.getActiveSessions(new ComponentName(this, MediaBridge.class)));
    } catch (RuntimeException e) {
      detachController();
    }
  }

  private void bindBest(List<MediaController> list) {
    MediaController pick = null;
    if (list != null) {
      for (MediaController mc : list) {
        if (mc == null) continue;
        PlaybackState st = mc.getPlaybackState();
        if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
          pick = mc;
          break;
        }
        if (pick == null) pick = mc;
      }
    }
    if (pick != activeController) {
      detachController();
      activeController = pick;
      if (activeController != null) {
        try {
          activeController.registerCallback(controllerCallback, main);
        } catch (RuntimeException ignored) {
        }
      }
    }
    publish(activeController);
  }

  private void publish(MediaController ctrl) {
    String title = "";
    String artist = "";
    String pkg = "";
    String appName = "";
    boolean playing = false;
    boolean hasSession = ctrl != null;
    if (ctrl != null) {
      pkg = ctrl.getPackageName() == null ? "" : ctrl.getPackageName();
      appName = appLabel(this, pkg);
      MediaMetadata md = ctrl.getMetadata();
      if (md != null) {
        title = nonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            md.getString(MediaMetadata.METADATA_KEY_TITLE));
        artist = nonEmpty(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
            md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
      }
      PlaybackState st = ctrl.getPlaybackState();
      playing = st != null && (st.getState() == PlaybackState.STATE_PLAYING
          || st.getState() == PlaybackState.STATE_BUFFERING);
    }
    // Deduplicate: ignore position-only ticks so widgets redraw strictly on visible state changes.
    String sig = hasSession + "|" + playing + "|" + pkg + "|" + title + "|" + artist;
    if (sig.equals(lastSignature)) return;
    lastSignature = sig;
    store(this).edit()
        .putBoolean("has_session", hasSession)
        .putBoolean("playing", playing)
        .putString("pkg", pkg)
        .putString("app", appName)
        .putString("title", title)
        .putString("artist", artist)
        .putLong("at", System.currentTimeMillis())
        .apply();
    MediaWidget.drawAll(this);
    CustomWidget.redrawMediaWidgets(this);
  }

  private void detachController() {
    if (activeController != null) {
      try {
        activeController.unregisterCallback(controllerCallback);
      } catch (RuntimeException ignored) {
      }
      activeController = null;
    }
  }

  private void detachAll() {
    detachController();
    if (sessionManager != null) {
      try {
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private static String nonEmpty(String... vals) {
    for (String v : vals) {
      if (v != null && !v.trim().isEmpty()) return v.trim();
    }
    return "";
  }

  private static String appLabel(Context c, String pkg) {
    if (pkg == null || pkg.isEmpty()) return "";
    try {
      PackageManager pm = c.getPackageManager();
      ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
      CharSequence s = pm.getApplicationLabel(info);
      return s == null ? "" : s.toString().trim();
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      return "";
    }
  }
}
