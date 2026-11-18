/* The frame-rate cap for whatever Forge draws on the phone, kept low on
   battery: the chosen rate (30 unless changed), at most 15 while Battery
   Saver is on or the phone is getting hot, and 0 (one still frame) when the
   battery is under 15% and not charging. The battery is read at most every
   5 seconds, from the system's last battery report (no listener kept). */
package app.forge.look;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

public final class Pacer {
  private final Context app;
  private final PowerManager power;
  private volatile int wanted = 30;
  private long checkedMs;
  private int cached = 30;
  private String why = "";

  public Pacer(Context context) {
    app = context.getApplicationContext();
    power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
  }

  public void setWanted(int fps) {
    wanted = Math.max(5, Math.min(60, fps));
    checkedMs = 0;
  }

  /** Why the rate is lower than chosen, for the settings screen ("" when not). */
  public String why() {
    return why;
  }

  public int fps() {
    long now = SystemClock.elapsedRealtime();
    if (checkedMs != 0 && now - checkedMs < 5000) return cached;
    checkedMs = now;
    int fps = wanted;
    String reason = "";
    if (power != null && power.isPowerSaveMode()) {
      fps = Math.min(fps, 15);
      reason = "Battery Saver is on: 15 frames a second";
    }
    if (power != null && Build.VERSION.SDK_INT >= 29
        && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
      fps = Math.min(fps, 15);
      reason = "The phone is hot: 15 frames a second";
    }
    Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (battery != null) {
      int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
      int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
      int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
      if (level >= 0 && scale > 0 && level * 100 / scale < 15 && plugged == 0) {
        fps = 0;
        reason = "Battery under 15%: a still picture until it is charged";
      }
    }
    cached = fps;
    why = reason;
    return fps;
  }
}
