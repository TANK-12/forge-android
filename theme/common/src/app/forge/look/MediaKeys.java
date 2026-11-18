/* Play, pause and skip for whatever app is playing music, the way a
   headset's buttons do it: no permission and no media session needed. */
package app.forge.look;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

public final class MediaKeys {
  private MediaKeys() {}

  /** Press a media key, with real times (Relay task 1330). Android sends it to
      the app playing now, or else the one that played last; checked on the
      Android 14 emulator: MediaSessionService passes it to that app. (A note
      when nothing answers cannot be shown from here: Android drops a widget's
      toasts while Forge's notifications are off.) */
  public static void press(Context c, int code) {
    AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    if (audio == null || code == 0) return;
    long now = SystemClock.uptimeMillis();
    audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
    audio.dispatchMediaKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
  }
}
