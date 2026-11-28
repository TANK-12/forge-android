/* Native Canvas visualiser scene for VizWallpaper (Relay task 1591):
   draws an audio-reactive spectrum, falling peak caps, oscilloscope wave and
   beat-reactive radial glow straight onto the wallpaper's SurfaceHolder via
   SceneLoop (the exact drawing path that works on tablets where WebView inside
   a VirtualDisplay Presentation fails or renders blank). Follows the chosen
   preset's colours, sensitivity setting and Pacer battery frame caps. */
package app.forge.visualiser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import app.forge.look.Pacer;
import app.forge.look.SceneLoop;
import app.forge.look.Sound;

final class NativeVizScene implements SceneLoop.Scene {
  private static final int BARS = 32;

  final Pacer pacer;
  final Sound sound;
  private final Context app;
  private final AudioManager audio;
  private final float[] smooth = new float[BARS];
  private final float[] peaks = new float[BARS];
  private final Paint bgPaint = new Paint();
  private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path wavePath = new Path();
  private final RectF barRect = new RectF();
  private final Matrix glowMatrix = new Matrix();
  private RadialGradient glowShader;

  private int width, height;
  private int bgCol = Color.rgb(5, 7, 11);
  private int primaryCol = Color.rgb(143, 184, 255);
  private int secondaryCol = Color.rgb(58, 111, 216);
  private float sensitivity = 1f;
  private float phase;
  private boolean reload = true, running;

  NativeVizScene(Context context, Sound sound, Pacer pacer) {
    this.app = context.getApplicationContext();
    this.sound = sound;
    this.pacer = pacer;
    this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    wavePaint.setStyle(Paint.Style.STROKE);
    wavePaint.setStrokeCap(Paint.Cap.ROUND);
    wavePaint.setStrokeJoin(Paint.Join.ROUND);
  }

  void settingsChanged() {
    reload = true;
  }

  @Override public void size(int w, int h) {
    width = w;
    height = h;
    reload = true;
  }

  @Override public void running(boolean on) {
    running = on;
    if (on) {
      sound.start(Prefs.source(app), Prefs.micAllowed(app),
          () -> audio != null && audio.isMusicActive());
    } else {
      sound.stop();
    }
  }

  private void apply() {
    reload = false;
    pacer.setWanted(Prefs.fps(app, true));
    Presets.Preset p = Presets.find(app, Prefs.get(app, Prefs.WALL));
    bgCol = p == null ? Color.rgb(5, 7, 11) : p.background;
    primaryCol = p == null ? Color.rgb(143, 184, 255) : p.primary;
    secondaryCol = p == null ? Color.rgb(58, 111, 216) : p.secondary;
    try {
      sensitivity = Math.max(0.3f, Math.min(2f, Float.parseFloat(Prefs.get(app, Prefs.SENSITIVITY))));
    } catch (NumberFormatException e) {
      sensitivity = 1f;
    }
    if (width > 0 && height > 0) {
      bgPaint.setShader(new LinearGradient(0, 0, 0, height,
          blend(bgCol, secondaryCol, 0.16f), bgCol, Shader.TileMode.CLAMP));
      glowShader = new RadialGradient(0f, 0f, 1f,
          withAlpha(secondaryCol, 255), withAlpha(bgCol, 0), Shader.TileMode.CLAMP);
      glowPaint.setShader(glowShader);
      barPaint.setShader(new LinearGradient(0, height * 0.88f, 0, height * 0.25f,
          secondaryCol, primaryCol, Shader.TileMode.CLAMP));
      peakPaint.setColor(blend(primaryCol, Color.WHITE, 0.45f));
      wavePaint.setColor(withAlpha(blend(primaryCol, Color.WHITE, 0.25f), 210));
      wavePaint.setStrokeWidth(Math.max(2f, Math.min(width, height) * 0.0045f));
    }
    if (running) {
      Sound.Mode want = Prefs.source(app);
      if (want == Sound.Mode.OFF) sound.stop();
      else if (sound.mode() != want) {
        sound.start(want, Prefs.micAllowed(app), () -> audio != null && audio.isMusicActive());
      }
    }
  }

  @Override public void frame(Canvas c, float dt) {
    if (width <= 0 || height <= 0) return;
    if (reload) apply();
    phase += dt;
    float level, bass, beat;
    boolean silent;
    synchronized (sound) {
      if (sound.mode() != Sound.Mode.OFF) sound.frame();
      level = sound.level;
      bass = sound.bass;
      beat = sound.beat;
      silent = sound.silent;
      float rise = 1f - (float) Math.exp(-dt / 0.045f);
      float fall = 1f - (float) Math.exp(-dt / 0.22f);
      float peakFall = dt * 0.42f;
      for (int i = 0; i < BARS; i++) {
        float v;
        if (silent) {
          // Calm ambient breath when nothing is playing so the wallpaper stays alive without noise.
          v = 0.06f + 0.045f * (0.5f + 0.5f * (float) Math.sin(phase * 1.15f + i * 0.34f));
        } else {
          int b0 = i * 2;
          float raw = 0.5f * (sound.bands[b0] + sound.bands[Math.min(Sound.BANDS - 1, b0 + 1)]);
          v = softCap(raw * sensitivity);
        }
        float cur = smooth[i];
        cur += (v - cur) * (v > cur ? rise : fall);
        smooth[i] = cur;
        peaks[i] = Math.max(cur, peaks[i] - peakFall);
      }

      // 1. Background gradient + beat-reactive radial aurora (reusing one shader via Matrix)
      c.drawRect(0, 0, width, height, bgPaint);
      if (glowShader != null) {
        float glowR = Math.max(1f, Math.max(width, height) * (0.48f + 0.16f * bass + 0.12f * beat));
        int glowAlpha = Math.min(180, Math.round(70 + 75 * level + 45 * beat));
        glowMatrix.setScale(glowR, glowR);
        glowMatrix.postTranslate(width * 0.5f, height * 0.52f);
        glowShader.setLocalMatrix(glowMatrix);
        glowPaint.setAlpha(glowAlpha);
        c.drawRect(0, 0, width, height, glowPaint);
      }

      // 2. Oscilloscope waveform across upper-middle band
      float waveMidY = height * 0.38f;
      float waveAmp = Math.min(width, height) * (silent ? 0.03f : 0.11f);
      wavePath.reset();
      int step = 4;
      for (int i = 0; i < Sound.WAVE; i += step) {
        float x = (i / (float) (Sound.WAVE - 1)) * width;
        float sample = silent
            ? (float) Math.sin(phase * 1.4f + x / Math.max(1f, width) * 9f) * 0.35f
            : sound.wave[i];
        float y = waveMidY - sample * waveAmp;
        if (i == 0) wavePath.moveTo(x, y);
        else wavePath.lineTo(x, y);
      }
      c.drawPath(wavePath, wavePaint);
    }

    // 3. Spectrum bars + falling peak caps along lower half
    float marginX = width * 0.06f;
    float usableW = width - marginX * 2f;
    float slot = usableW / BARS;
    float barW = Math.max(3f, slot * 0.68f);
    float gap = (slot - barW) * 0.5f;
    float baseY = height * 0.86f;
    float maxH = height * 0.42f;
    float radius = Math.min(barW * 0.45f, 8f);
    for (int i = 0; i < BARS; i++) {
      float x = marginX + i * slot + gap;
      float h = Math.max(4f, smooth[i] * maxH);
      barRect.set(x, baseY - h, x + barW, baseY);
      c.drawRoundRect(barRect, radius, radius, barPaint);
      float py = baseY - Math.max(h + 4f, peaks[i] * maxH + 4f);
      barRect.set(x, py - 3f, x + barW, py);
      c.drawRoundRect(barRect, 2f, 2f, peakPaint);
    }
  }

  private static float softCap(float v) {
    if (v <= 0f) return 0f;
    if (v <= 0.72f) return v;
    return 0.72f + 0.28f * (float) Math.tanh((v - 0.72f) / 0.28f);
  }

  private static int withAlpha(int col, int alpha) {
    return (col & 0x00ffffff) | (Math.max(0, Math.min(255, alpha)) << 24);
  }

  private static int blend(int a, int b, float t) {
    int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
    int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
    int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
    return Color.rgb(r, g, bl);
  }
}
