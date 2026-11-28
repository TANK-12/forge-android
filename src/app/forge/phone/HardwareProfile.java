package app.forge.phone;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ConfigurationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.audiofx.Visualizer;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import app.forge.look.LiveWall;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Hardware, screen, GPU, sensor, audio-capture, thermal and battery detection
 * for Forge for Android 2 (Relay task 1592).
 *
 * Battery discipline:
 * - Static hardware facts (RAM, CPU cores/ABI/MHz, OpenGL ES, Vulkan, sensors,
 *   audio capabilities) are probed once per process and cached.
 * - Sensor presence uses {@link SensorManager#getDefaultSensor} metadata only;
 *   no continuous SensorEventListener is ever registered.
 * - Battery, charging, Battery Saver, screen-off and thermal changes are
 *   100% event-driven via {@link Monitor}, attached strictly between
 *   {@code Main.onResume()} and {@code Main.onPause()} with zero timers.
 */
public final class HardwareProfile {

  /** Pure input snapshot for deterministic adaptive-quality calculation and JVM tests. */
  public static final class Snapshot {
    public double memoryGb;
    public double availGb;
    public boolean lowRam;
    public int memoryClassMb;
    public int cores;
    public String abi = "arm64-v8a";
    public int cpuMaxMhz;
    public int sdkInt;
    public String release = "";
    public int glesRaw = 0x00030000;
    public boolean vulkan;
    public int widthDp;
    public int heightDp;
    public int smallestWidthDp;
    public int densityDpi;
    public float refreshHz = 60f;
    public float maxRefreshHz = 60f;
    public boolean hdr;
    public boolean wideColor;
    public boolean hasAccel;
    public boolean hasGyro;
    public boolean hasLight;
    public boolean hasHinge;
    public boolean micFeature;
    public boolean micGranted;
    public boolean lowLatencyAudio;
    public boolean proAudio;
    public boolean outputCaptureSupported;
    public boolean liveWallpaperSupported;
    public boolean widgetPinSupported;
    public int battery = -1;
    public boolean charging;
    public String plugged = "none";
    public double batteryTempC;
    public boolean powerSave;
    public int thermal;
    public boolean ignoringBatteryOpt;
  }

  /** Adaptive quality & layout recommendation derived from hardware + live battery state. */
  public static final class Adaptive {
    public final String tier;         // "strong" | "medium" | "weak"
    public final String qualityMode;  // "full" | "balanced" | "lite" | "saver"
    public final String form;         // "phone" | "tablet" | "large_tablet" | "foldable"
    public final int panes;           // 1, 2, or 3
    public final boolean glassBlur;
    public final double particleScale;
    public final int targetFps;
    public final double vizScale;
    public final boolean prefer2dViz;
    public final List<String> reasons;

    Adaptive(String tier, String qualityMode, String form, int panes, boolean glassBlur,
        double particleScale, int targetFps, double vizScale, boolean prefer2dViz,
        List<String> reasons) {
      this.tier = tier;
      this.qualityMode = qualityMode;
      this.form = form;
      this.panes = panes;
      this.glassBlur = glassBlur;
      this.particleScale = particleScale;
      this.targetFps = targetFps;
      this.vizScale = vizScale;
      this.prefer2dViz = prefer2dViz;
      this.reasons = reasons;
    }
  }

  private static volatile StaticCache cachedStatic;

  private static final class StaticCache {
    final double totalMemGb;
    final boolean lowRam;
    final int memoryClassMb;
    final int cores;
    final String abi;
    final int cpuMaxMhz;
    final int glesRaw;
    final boolean vulkan;
    final boolean hasAccel, hasGyro, hasLight, hasHinge;
    final boolean micFeature, lowLatencyAudio, proAudio, outputCaptureSupported;

    StaticCache(Context app) {
      ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      if (am != null) am.getMemoryInfo(mi);
      this.totalMemGb = Math.round((mi.totalMem / 1073741824.0) * 10.0) / 10.0;
      this.lowRam = am != null && am.isLowRamDevice();
      this.memoryClassMb = am != null ? am.getMemoryClass() : 128;
      ConfigurationInfo ci = am != null ? am.getDeviceConfigurationInfo() : null;
      this.glesRaw = ci != null && ci.reqGlEsVersion > 0 ? ci.reqGlEsVersion : 0x00020000;
      this.cores = Runtime.getRuntime().availableProcessors();
      this.abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
          ? Build.SUPPORTED_ABIS[0] : "unknown";
      this.cpuMaxMhz = readCpuMaxMhz();

      PackageManager pm = app.getPackageManager();
      this.vulkan = Build.VERSION.SDK_INT >= 24
          && pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);
      this.micFeature = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
      this.lowLatencyAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY);
      this.proAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO);

      SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
      this.hasAccel = sm != null && sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
      this.hasGyro = sm != null && sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
      this.hasLight = sm != null && sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
      this.hasHinge = Build.VERSION.SDK_INT >= 30 && sm != null
          && sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null;

      boolean capOk = false;
      try {
        int[] range = Visualizer.getCaptureSizeRange();
        capOk = range != null && range.length >= 2 && range[1] >= 128;
      } catch (Throwable ignored) {
        capOk = false;
      }
      this.outputCaptureSupported = capOk;
    }
  }

  private HardwareProfile() {}

  private static int readCpuMaxMhz() {
    int bestKhz = 0;
    for (int cpu = 0; cpu < 8; cpu++) {
      String p = "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq";
      try (BufferedReader br = new BufferedReader(new FileReader(p))) {
        String line = br.readLine();
        if (line != null) {
          int khz = Integer.parseInt(line.trim());
          if (khz > bestKhz) bestKhz = khz;
        }
      } catch (IOException | NumberFormatException | SecurityException ignored) {
      }
    }
    return bestKhz > 0 ? Math.round(bestKhz / 1000f) : 0;
  }

  public static String formatGles(int raw) {
    int major = (raw & 0xffff0000) >>> 16;
    int minor = raw & 0xffff;
    if (major <= 0) return "2.0";
    return major + "." + minor;
  }

  public static String thermalWord(int status) {
    return switch (status) {
      case 0 -> "cool";
      case 1 -> "nominal";
      case 2 -> "warm";
      case 3 -> "hot";
      default -> status >= 4 ? "critical" : "cool";
    };
  }

  /** Deterministic adaptive tier and quality calculation from a hardware + battery snapshot. */
  public static Adaptive computeAdaptive(Snapshot s) {
    List<String> reasons = new ArrayList<>();
    int glesMajor = (s.glesRaw & 0xffff0000) >>> 16;

    // 1. Form factor & multi-pane geometry
    String form;
    if (s.smallestWidthDp >= 840) {
      form = "large_tablet";
    } else if (s.smallestWidthDp >= 600) {
      form = s.hasHinge ? "foldable" : "tablet";
    } else {
      form = "phone";
    }
    int panes = s.widthDp >= 1020 ? 3 : (s.smallestWidthDp >= 600 || s.widthDp >= 760 ? 2 : 1);

    // 2. Base hardware strength tier (matches shared/perf/device.json thresholds + GPU check)
    String tier;
    if (!s.lowRam && s.memoryGb >= 7.0 && s.cores >= 6 && glesMajor >= 3) {
      tier = "strong";
      reasons.add(String.format(Locale.ROOT, "%.1f GB RAM, %d cores, OpenGL ES %s",
          s.memoryGb, s.cores, formatGles(s.glesRaw)));
    } else if (!s.lowRam && s.memoryGb >= 3.5 && s.cores >= 4 && glesMajor >= 3) {
      tier = "medium";
      reasons.add(String.format(Locale.ROOT, "Balanced hardware (%.1f GB RAM, %d cores)", s.memoryGb, s.cores));
    } else {
      tier = "weak";
      if (s.lowRam) reasons.add("Android marks this device as low-memory");
      else if (glesMajor < 3) reasons.add("Older graphics level (OpenGL ES " + formatGles(s.glesRaw) + ")");
      else reasons.add(String.format(Locale.ROOT, "Lighter hardware (%.1f GB RAM, %d cores)", s.memoryGb, s.cores));
    }

    // 3. Dynamic battery & thermal caps
    boolean criticalBattery = s.battery >= 0 && s.battery <= 15 && !s.charging;
    boolean lowBattery = s.battery >= 0 && s.battery <= 25 && !s.charging;
    boolean hot = s.thermal >= 3 || s.batteryTempC >= 41.0;
    boolean warm = s.thermal == 2 || s.batteryTempC >= 38.0;

    String qualityMode;
    boolean glassBlur;
    double particleScale;
    int targetFps;
    double vizScale;
    boolean prefer2dViz = "weak".equals(tier) || glesMajor < 3 || s.lowRam;

    if (criticalBattery || s.powerSave || hot) {
      qualityMode = "saver";
      if ("strong".equals(tier)) tier = "medium";
      glassBlur = false;
      particleScale = criticalBattery ? 0.0 : 0.25;
      targetFps = criticalBattery ? 0 : 15;
      vizScale = 0.5;
      prefer2dViz = true;
      if (criticalBattery) reasons.add("Battery at " + s.battery + "%: paused background motion");
      if (s.powerSave) reasons.add("Battery Saver is on: capped to 15 fps and crisp solid cards");
      if (hot) reasons.add("Device is running hot: scaled down graphics and frame rate");
    } else if ("weak".equals(tier) || lowBattery || warm) {
      qualityMode = "lite";
      if (warm && "strong".equals(tier)) tier = "medium";
      glassBlur = false;
      particleScale = 0.4;
      targetFps = 24;
      vizScale = 0.65;
      if (lowBattery) reasons.add("Battery under 25%: lighter effects to save charge");
      if (warm) reasons.add("Device is warm: reduced frame rate and blur");
    } else if ("medium".equals(tier)) {
      qualityMode = "balanced";
      glassBlur = true;
      particleScale = 0.7;
      targetFps = 30;
      vizScale = 0.75;
    } else {
      qualityMode = "full";
      glassBlur = true;
      particleScale = 1.0;
      targetFps = (s.maxRefreshHz >= 88f && (s.charging || s.battery < 0 || s.battery >= 35)) ? 60 : 45;
      vizScale = 1.0;
    }

    if (panes >= 2) {
      reasons.add((panes == 3 ? "Wide tablet 3-zone" : "Tablet multi-pane") + " layout ("
          + s.widthDp + "×" + s.heightDp + " dp)");
    }
    return new Adaptive(tier, qualityMode, form, panes, glassBlur, particleScale,
        targetFps, vizScale, prefer2dViz, reasons);
  }

  /** Capture current static + dynamic state on an Android Context. */
  public static Snapshot capture(Context context) {
    Context app = context.getApplicationContext();
    StaticCache sc = cachedStatic;
    if (sc == null) {
      sc = new StaticCache(app);
      cachedStatic = sc;
    }
    Snapshot s = new Snapshot();
    s.memoryGb = sc.totalMemGb;
    ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
    if (am != null) {
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      am.getMemoryInfo(mi);
      s.availGb = Math.round((mi.availMem / 1073741824.0) * 10.0) / 10.0;
    }
    s.lowRam = sc.lowRam;
    s.memoryClassMb = sc.memoryClassMb;
    s.cores = sc.cores;
    s.abi = sc.abi;
    s.cpuMaxMhz = sc.cpuMaxMhz;
    s.sdkInt = Build.VERSION.SDK_INT;
    s.release = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
    s.glesRaw = sc.glesRaw;
    s.vulkan = sc.vulkan;
    s.hasAccel = sc.hasAccel;
    s.hasGyro = sc.hasGyro;
    s.hasLight = sc.hasLight;
    s.hasHinge = sc.hasHinge;
    s.micFeature = sc.micFeature;
    s.micGranted = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
    s.lowLatencyAudio = sc.lowLatencyAudio;
    s.proAudio = sc.proAudio;
    s.outputCaptureSupported = sc.outputCaptureSupported;

    Configuration cfg = context.getResources().getConfiguration();
    DisplayMetrics dm = context.getResources().getDisplayMetrics();
    s.widthDp = cfg.screenWidthDp > 0 ? cfg.screenWidthDp : Math.round(dm.widthPixels / dm.density);
    s.heightDp = cfg.screenHeightDp > 0 ? cfg.screenHeightDp : Math.round(dm.heightPixels / dm.density);
    s.smallestWidthDp = cfg.smallestScreenWidthDp > 0
        ? cfg.smallestScreenWidthDp : Math.min(s.widthDp, s.heightDp);
    s.densityDpi = dm.densityDpi;
    if (Build.VERSION.SDK_INT >= 26) {
      s.hdr = cfg.isScreenHdr();
      s.wideColor = cfg.isScreenWideColorGamut();
    }

    Display display = resolveDisplay(context);
    if (display != null) {
      s.refreshHz = Math.round(display.getRefreshRate());
      float maxHz = s.refreshHz;
      for (Display.Mode m : display.getSupportedModes()) {
        if (m.getRefreshRate() > maxHz) maxHz = m.getRefreshRate();
      }
      s.maxRefreshHz = Math.round(maxHz);
    }

    try {
      s.liveWallpaperSupported = LiveWall.way(app,
          new ComponentName(app, Main.THEME_WALL)) != LiveWall.NONE;
    } catch (RuntimeException ignored) {
      s.liveWallpaperSupported = false;
    }
    AppWidgetManager awm = app.getSystemService(AppWidgetManager.class);
    s.widgetPinSupported = awm != null && awm.isRequestPinAppWidgetSupported();

    PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
    if (pm != null) {
      s.powerSave = pm.isPowerSaveMode();
      if (Build.VERSION.SDK_INT >= 29) s.thermal = pm.getCurrentThermalStatus();
      s.ignoringBatteryOpt = pm.isIgnoringBatteryOptimizations(app.getPackageName());
    }

    Intent sticky = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (sticky != null) {
      int lvl = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
      int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
      if (lvl >= 0 && scale > 0) s.battery = Math.round(lvl * 100f / scale);
      int plug = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
      int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
      s.charging = plug != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING;
      s.plugged = plug == BatteryManager.BATTERY_PLUGGED_AC ? "ac"
          : plug == BatteryManager.BATTERY_PLUGGED_USB ? "usb"
          : plug == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : "none";
      int tempTenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
      if (tempTenths > 0) s.batteryTempC = Math.round(tempTenths) / 10.0;
    }
    if (s.battery < 0) {
      BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
      if (bm != null) {
        int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (cap >= 0 && cap <= 100) s.battery = cap;
        s.charging = bm.isCharging();
      }
    }
    return s;
  }

  @SuppressWarnings("deprecation")
  private static Display resolveDisplay(Context context) {
    try {
      if (Build.VERSION.SDK_INT >= 30 && context instanceof Activity) {
        return context.getDisplay();
      }
      WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
      return wm == null ? null : wm.getDefaultDisplay();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** JSON representation compatible with ForgeDevice.sense() AND extended for Forge 2. */
  public static JSONObject toJson(Snapshot s, Adaptive a) {
    JSONObject out = new JSONObject();
    try {
      // Backward-compatible top-level keys expected by shared/forge_device.js:
      out.put("memory_gb", s.memoryGb)
          .put("avail_gb", s.availGb)
          .put("cores", s.cores)
          .put("low_ram", s.lowRam)
          .put("power_save", s.powerSave)
          .put("thermal", s.thermal)
          .put("thermal_word", thermalWord(s.thermal))
          .put("android", s.sdkInt)
          .put("android_release", s.release)
          .put("tablet", s.smallestWidthDp >= 600)
          .put("kind", s.smallestWidthDp >= 600 ? "tablet" : "phone")
          .put("os", "android");
      if (s.battery >= 0 && s.battery <= 100) {
        out.put("battery", s.battery).put("charging", s.charging);
      }
      out.put("plugged", s.plugged);
      if (s.batteryTempC > 0) out.put("battery_temp_c", s.batteryTempC);

      // Rich hardware & capability sub-objects for Forge for Android 2:
      out.put("cpu", new JSONObject()
          .put("cores", s.cores)
          .put("abi", s.abi)
          .put("max_mhz", s.cpuMaxMhz));
      out.put("gpu", new JSONObject()
          .put("gles", formatGles(s.glesRaw))
          .put("gles_major", (s.glesRaw & 0xffff0000) >>> 16)
          .put("vulkan", s.vulkan)
          .put("memory_class_mb", s.memoryClassMb));
      out.put("screen", new JSONObject()
          .put("width_dp", s.widthDp)
          .put("height_dp", s.heightDp)
          .put("smallest_width_dp", s.smallestWidthDp)
          .put("density_dpi", s.densityDpi)
          .put("refresh_hz", Math.round(s.refreshHz))
          .put("max_refresh_hz", Math.round(s.maxRefreshHz))
          .put("hdr", s.hdr)
          .put("wide_color", s.wideColor));
      out.put("sensors", new JSONObject()
          .put("accelerometer", s.hasAccel)
          .put("gyroscope", s.hasGyro)
          .put("light", s.hasLight)
          .put("hinge", s.hasHinge));
      out.put("audio", new JSONObject()
          .put("microphone", s.micFeature)
          .put("mic_permission", s.micGranted)
          .put("low_latency", s.lowLatencyAudio)
          .put("pro_audio", s.proAudio)
          .put("output_capture", s.outputCaptureSupported));
      out.put("capabilities", new JSONObject()
          .put("live_wallpaper", s.liveWallpaperSupported)
          .put("widget_pin", s.widgetPinSupported)
          .put("battery_unrestricted", s.ignoringBatteryOpt));
      out.put("adaptive", new JSONObject()
          .put("tier", a.tier)
          .put("quality_mode", a.qualityMode)
          .put("form", a.form)
          .put("panes", a.panes)
          .put("glass_blur", a.glassBlur)
          .put("particle_scale", a.particleScale)
          .put("target_fps", a.targetFps)
          .put("viz_scale", a.vizScale)
          .put("prefer_2d_viz", a.prefer2dViz)
          .put("reasons", new JSONArray(a.reasons)));
    } catch (JSONException ignored) {
    }
    return out;
  }

  public static JSONObject currentJson(Context context) {
    Snapshot s = capture(context);
    return toJson(s, computeAdaptive(s));
  }

  /**
   * Event-driven foreground listener for battery, power-save and thermal changes.
   * Registered only while Main is resumed on screen; zero background wakeups.
   */
  public static final class Monitor {
    public interface Listener {
      void onChanged(JSONObject profileJson);
    }

    private final Context context;
    private final Listener listener;
    private boolean attached;
    private String lastSig = "";
    private PowerManager.OnThermalStatusChangedListener thermalListener;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent intent) {
        emitIfChanged();
      }
    };

    public Monitor(Context context, Listener listener) {
      this.context = context;
      this.listener = listener;
    }

    public void attach() {
      if (attached) return;
      attached = true;
      IntentFilter f = new IntentFilter();
      f.addAction(Intent.ACTION_BATTERY_CHANGED);
      f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
      f.addAction(Intent.ACTION_POWER_CONNECTED);
      f.addAction(Intent.ACTION_POWER_DISCONNECTED);
      context.registerReceiver(receiver, f);
      if (Build.VERSION.SDK_INT >= 29) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
          thermalListener = status -> emitIfChanged();
          try {
            pm.addThermalStatusListener(context.getMainExecutor(), thermalListener);
          } catch (RuntimeException ignored) {
          }
        }
      }
      emitIfChanged();
    }

    public void detach() {
      if (!attached) return;
      attached = false;
      try {
        context.unregisterReceiver(receiver);
      } catch (RuntimeException ignored) {
      }
      if (Build.VERSION.SDK_INT >= 29 && thermalListener != null) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
          try {
            pm.removeThermalStatusListener(thermalListener);
          } catch (RuntimeException ignored) {
          }
        }
        thermalListener = null;
      }
    }

    public void emitIfChanged() {
      Snapshot s = capture(context);
      Adaptive a = computeAdaptive(s);
      String sig = s.battery + "|" + s.charging + "|" + s.powerSave + "|" + s.thermal
          + "|" + a.qualityMode + "|" + s.widthDp + "x" + s.heightDp + "|" + s.micGranted;
      if (sig.equals(lastSig)) return;
      lastSig = sig;
      if (listener != null) listener.onChanged(toJson(s, a));
      CustomWidget.redrawHardwareWidgets(context);
    }
  }
}
