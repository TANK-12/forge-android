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
      the app playing now, or else the one that played last. Keep the combined
      key for players that support only toggling; the separate Pause button
      sends a plain pause without guessing the session's playback state. */
  public static void press(Context c, int code) {
    if (code != KeyEvent.KEYCODE_MEDIA_PREVIOUS
        && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        && code != KeyEvent.KEYCODE_MEDIA_PAUSE
        && code != KeyEvent.KEYCODE_MEDIA_NEXT) return;
    AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    if (audio == null) return;
    long now = SystemClock.uptimeMillis();
    audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
    audio.dispatchMediaKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
  }
}
