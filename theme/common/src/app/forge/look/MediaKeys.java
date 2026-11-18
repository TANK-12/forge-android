/* Play, pause and skip for whatever app is playing music, the way a
   headset's buttons do it: no permission and no media session needed. */
package app.forge.look;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

public final class MediaKeys {
  private MediaKeys() {}

  public static void press(Context c, int code) {
    AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    if (audio == null || code == 0) return;
    audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
    audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
  }
}
