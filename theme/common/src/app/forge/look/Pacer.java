/* The frame-rate cap for whatever Forge draws on the phone, kept low on
   battery: the chosen rate (30 unless changed, 60 for the particles wallpaper since task 1330; up to 120 for a 90 or 120 Hz
   screen, Relay task 1320), at most 15 while Battery
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
    wanted = Math.max(5, Math.min(120, fps));
    checkedMs = 0;
  }

  /** Why the rate is lower than chosen, for the settings screen ("" when not). */
  public String why() {
    return why;
  }

  /** Pure battery frame-rate policy, shared by runtime and JVM tests. */
  public static int policyFps(int wanted, boolean powerSave, boolean severeThermal,
      int batteryPct, boolean charging) {
    int fps = Math.max(5, Math.min(120, wanted));
    if (batteryPct >= 0 && batteryPct < 15 && !charging) return 0;
    if (powerSave || severeThermal) fps = Math.min(fps, 15);
    else if (batteryPct >= 0 && batteryPct < 30 && !charging) fps = Math.min(fps, 30);
    return fps;
  }

  public int fps() {
    long now = SystemClock.elapsedRealtime();
    if (checkedMs != 0 && now - checkedMs < 5000) return cached;
    checkedMs = now;
    boolean powerSave = power != null && power.isPowerSaveMode();
    boolean hot = power != null && Build.VERSION.SDK_INT >= 29
        && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE;
    int pct = -1;
    boolean charging = false;
    Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (battery != null) {
      int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
      int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
      int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
      if (level >= 0 && scale > 0) pct = level * 100 / scale;
      charging = plugged != 0;
    }
    int fps = policyFps(wanted, powerSave, hot, pct, charging);
    String reason = "";
    if (fps == 0) {
      reason = "Battery under 15%: a still picture until it is charged";
    } else if (hot && fps < wanted) {
      reason = "The device is hot: 15 frames a second";
    } else if (powerSave && fps < wanted) {
      reason = "Battery Saver is on: 15 frames a second";
    } else if (pct >= 0 && pct < 30 && !charging && fps < wanted) {
      reason = "Battery under 30%: capped at 30 frames a second";
    }
    cached = fps;
    why = reason;
    return fps;
  }
}
