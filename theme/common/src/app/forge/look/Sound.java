/* What the music sounds like right now, for the particles wallpaper and the
   visualiser (Forge Theme and Forge Visualiser, Relay task 1293).

   One frame has the shape the desktop visualiser uses (shared/visualiser,
   task 1294), so its look files run unchanged on the phone:
     bands   64 values 0..1, 40 Hz to 16 kHz spaced evenly in pitch, lows first
     wave    512 values -1..1
     level, bass, mid, treble   0..1
     beat    1 on a beat, falling to 0 in about 0.3 s
     silent  true after half a second of near silence

   Sources (Mode):
     PHONE  the phone's own sound output (android.media.audiofx.Visualizer on
            the output mix). Needs the microphone permission although it only
            reads what the phone plays; some phones give silence for other
            apps' sound, then status() says so.
     MIC    the microphone: whatever is playing in the room.
     DEMO   made-up music, for previews and checks without sound.
     OFF    always silent.
   start() and stop() follow the screen: nothing listens while nothing is
   drawn. frame() is called once per drawn frame from the drawing thread. */
package app.forge.look;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

public final class Sound {
  public enum Mode { OFF, PHONE, MIC, DEMO }

  public static final int BANDS = 64;
  public static final int WAVE = 512;
  static final float MIN_HZ = 40f, MAX_HZ = 16000f;

  /** The latest frame. Read it after frame(); it is reused, not copied. */
  public final float[] bands = new float[BANDS];
  public final float[] wave = new float[WAVE];
  public float level, bass, mid, treble, beat;
  public boolean silent = true;

  private Mode mode = Mode.OFF;
  private String status = "Not listening";
  private Visualizer vis;
  private Mic mic;
  private final byte[] fftBytes = new byte[1024];
  private final byte[] waveBytes = new byte[1024];
  private final float[] mags = new float[513];
  private final float[] raw = new float[BANDS];
  private float binHz = 44100f / 1024f;
  private long lastNs;
  private float bassAvg, quietFor = 1f, sinceBeat = 1f, peak = 0.3f;
  private float demoT;

  public synchronized Mode mode() { return mode; }

  /** A plain sentence for the settings screen: what is being heard. */
  public synchronized String status() { return status; }

  /** Start listening. PHONE falls back to MIC when the output cannot be read
      and the microphone is allowed; either failing leaves it silent. */
  public synchronized void start(Mode want, boolean micAllowed) {
    stop();
    mode = want;
    lastNs = 0;
    if (want == Mode.PHONE) {
      if (!micAllowed) {
        status = "Needs the microphone permission to hear this device's sound";
        mode = Mode.OFF;
        return;
      }
      try {
        vis = new Visualizer(0);
        vis.setEnabled(false);
        int[] range = Visualizer.getCaptureSizeRange();
        vis.setCaptureSize(Math.min(1024, range[1]));
        vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
        vis.setEnabled(true);
        binHz = vis.getSamplingRate() / 1000f / vis.getCaptureSize();
        status = "Listening to this device's own sound";
        return;
      } catch (RuntimeException e) {
        releaseVis();
        want = Mode.MIC;
        mode = Mode.MIC;
        status = "This device does not share its sound; using the microphone";
      }
    }
    if (want == Mode.MIC) {
      if (!micAllowed) {
        status = "Needs the microphone permission";
        mode = Mode.OFF;
        return;
      }
      mic = Mic.open();
      if (mic == null) {
        status = "The microphone is busy or missing";
        mode = Mode.OFF;
        return;
      }
      binHz = Mic.RATE / 1024f;
      if (!status.startsWith("This device")) status = "Listening with the microphone";
      return;
    }
    status = want == Mode.DEMO ? "Demo music (nothing is listening)" : "Not listening";
  }

  /** Stop listening and forget the mode, so the next start() really starts. */
  public synchronized void stop() {
    releaseVis();
    if (mic != null) {
      mic.close();
      mic = null;
    }
    mode = Mode.OFF;
    status = "Not listening";
  }

  private void releaseVis() {
    if (vis != null) {
      try {
        vis.setEnabled(false);
      } catch (RuntimeException ignored) {
      }
      vis.release();
      vis = null;
    }
  }

  /** Read the source and update the frame; cheap enough for every frame. */
  public synchronized void frame() {
    long now = SystemClock.elapsedRealtimeNanos();
    float dt = lastNs == 0 ? 1 / 30f : Math.min(0.2f, (now - lastNs) / 1e9f);
    lastNs = now;
    boolean got = false;
    if (vis != null) {
      try {
        got = vis.getFft(fftBytes) == Visualizer.SUCCESS
            && vis.getWaveForm(waveBytes) == Visualizer.SUCCESS;
      } catch (RuntimeException e) {
        got = false;
      }
      if (got) {
        int n = vis.getCaptureSize();
        fromVisualizer(n);
      }
    } else if (mic != null) {
      got = mic.read(mags, wave);
    } else if (mode == Mode.DEMO) {
      demo(dt);
      got = true;
    }
    if (!got) {
      java.util.Arrays.fill(mags, 0f);
      java.util.Arrays.fill(wave, 0f);
    }
    if (mode != Mode.DEMO) toBands();
    smooth(dt);
  }

  // Visualizer bytes: fft[0] is the DC part, fft[1] the top bin, then
  // (real, imaginary) pairs; the wave is unsigned 8-bit around 128.
  private void fromVisualizer(int n) {
    int half = Math.min(512, n / 2);
    mags[0] = Math.abs(fftBytes[0]);
    for (int k = 1; k < half; k++) {
      float re = fftBytes[2 * k], im = fftBytes[2 * k + 1];
      mags[k] = (float) Math.sqrt(re * re + im * im) / 128f;
    }
    mags[half] = Math.abs(fftBytes[1]) / 128f;
    int step = Math.max(1, n / WAVE);
    for (int i = 0; i < WAVE; i++) {
      int j = Math.min(n - 1, i * step);
      wave[i] = ((waveBytes[j] & 0xff) - 128) / 128f;
    }
  }

  // Magnitudes to 64 pitch-spaced bands, in decibels mapped to 0..1, with a
  // gentle lift for the highs so a normal song fills the picture evenly.
  private void toBands() {
    float lo = (float) Math.log(MIN_HZ), hi = (float) Math.log(MAX_HZ);
    float top = 0;
    for (int b = 0; b < BANDS; b++) {
      float f0 = (float) Math.exp(lo + (hi - lo) * b / BANDS);
      float f1 = (float) Math.exp(lo + (hi - lo) * (b + 1) / BANDS);
      float k0 = f0 / binHz, k1 = f1 / binHz;
      float m;
      if (k1 - k0 < 1f) {
        // narrower than one bin: read between the two nearest bins
        float k = (k0 + k1) / 2f;
        int i = Math.min(511, (int) k);
        float t = k - i;
        m = mags[i] * (1 - t) + mags[Math.min(512, i + 1)] * t;
      } else {
        m = 0;
        for (int k = (int) k0; k <= Math.min(512, (int) k1); k++) m = Math.max(m, mags[k]);
      }
      float tilt = 1f + 1.6f * b / BANDS;
      float db = 20f * (float) Math.log10(m * tilt + 1e-5f);
      raw[b] = clamp((db + 48f) / 44f);
      top = Math.max(top, raw[b]);
    }
    // a slow automatic gain so quiet and loud songs both fill the range
    peak = Math.max(top, peak * 0.995f);
    float gain = peak > 0.15f ? 0.92f / peak : 1f;
    for (int b = 0; b < BANDS; b++) raw[b] = clamp(raw[b] * gain);
  }

  private void smooth(float dt) {
    float up = 1f - (float) Math.exp(-dt / 0.03f), down = 1f - (float) Math.exp(-dt / 0.25f);
    float sb = 0, sm = 0, st = 0, sl = 0;
    for (int b = 0; b < BANDS; b++) {
      float v = bands[b], r = raw[b];
      v += (r - v) * (r > v ? up : down);
      bands[b] = v;
      sl += v;
      if (b < 12) sb += v; else if (b < 40) sm += v; else st += v;
    }
    level = sl / BANDS;
    bass = sb / 12f;
    mid = sm / 28f;
    treble = st / 24f;
    // beat: the low bands jumping well above their recent average
    float lowNow = 0;
    for (int b = 0; b < 9; b++) lowNow += raw[b];
    lowNow /= 9f;
    sinceBeat += dt;
    beat = Math.max(0f, beat - dt / 0.3f);
    if (lowNow > bassAvg * 1.4f && lowNow > 0.3f && sinceBeat > 0.2f) {
      beat = 1f;
      sinceBeat = 0f;
    }
    bassAvg += (lowNow - bassAvg) * (1f - (float) Math.exp(-dt / 1.0f));
    quietFor = level < 0.03f ? quietFor + dt : 0f;
    silent = quietFor > 0.5f;
  }

  // Made-up music for previews: a 118 bpm kick, a bass line and a slow
  // shimmer, written straight into raw bands and a wave.
  private void demo(float dt) {
    demoT += dt;
    float beatLen = 60f / 118f;
    float ph = (demoT % beatLen) / beatLen;
    float kick = (float) Math.exp(-ph * 9f);
    float bar = (demoT / (beatLen * 4)) % 4f;
    for (int b = 0; b < BANDS; b++) {
      float x = b / (float) BANDS;
      float body = 0.55f * (float) Math.exp(-x * 3.2f) * (0.6f + 0.4f * kick);
      float melody = 0.35f * (float) Math.exp(-Math.pow((x - 0.35f - 0.08f * (int) bar) * 9f, 2));
      float air = 0.18f * (0.5f + 0.5f * (float) Math.sin(demoT * 1.3f + b * 0.4f)) * x;
      raw[b] = clamp(body + kick * 0.45f * (float) Math.exp(-x * 10f) + melody + air);
    }
    for (int i = 0; i < WAVE; i++) {
      float t = i / (float) WAVE;
      wave[i] = 0.55f * (float) Math.sin(t * 6.283f * 3f + demoT * 2f) * (0.4f + 0.6f * kick)
          + 0.2f * (float) Math.sin(t * 6.283f * 17f + demoT * 5f);
    }
  }

  static float clamp(float v) {
    return v < 0 ? 0 : (v > 1 ? 1 : v);
  }

  /** The microphone: a small thread keeps the latest 1024 samples. */
  static final class Mic implements Runnable {
    static final int RATE = 44100;
    private final AudioRecord rec;
    private final short[] ring = new short[1024];
    private final float[] re = new float[1024], im = new float[1024];
    private int pos;
    private float wavePeak = 0.05f;
    private volatile boolean running = true;
    private final Thread thread;

    private Mic(AudioRecord rec) {
      this.rec = rec;
      thread = new Thread(this, "forge-mic");
      thread.start();
    }

    static Mic open() {
      try {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
          r.release();
          return null;
        }
        r.startRecording();
        return new Mic(r);
      } catch (RuntimeException e) {
        return null;
      }
    }

    @Override public void run() {
      short[] buf = new short[512];
      while (running) {
        int n = rec.read(buf, 0, buf.length);
        if (n <= 0) {
          SystemClock.sleep(20);
          continue;
        }
        synchronized (this) {
          for (int i = 0; i < n; i++) {
            ring[pos] = buf[i];
            pos = (pos + 1) & 1023;
          }
        }
      }
    }

    /** Magnitudes of the latest 1024 samples (Hann window) and its wave. */
    boolean read(float[] mags, float[] wave) {
      synchronized (this) {
        for (int i = 0; i < 1024; i++) {
          float s = ring[(pos + i) & 1023] / 32768f;
          float w = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / 1023);
          re[i] = s * w;
          im[i] = 0;
        }
      }
      float pk = 0;
      for (int i = 0; i < WAVE; i++) pk = Math.max(pk, Math.abs(re[512 + i - 256]));
      wavePeak = Math.max(pk, wavePeak * 0.98f);
      for (int i = 0; i < WAVE; i++) {
        float w = re[i + 256];
        wave[i] = Math.max(-1f, Math.min(1f, w / Math.max(0.02f, wavePeak)));
      }
      fft(re, im);
      for (int k = 0; k <= 512; k++) {
        mags[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / 64f;
      }
      return true;
    }

    void close() {
      running = false;
      try {
        rec.stop();
      } catch (RuntimeException ignored) {
      }
      try {
        thread.join(200);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      rec.release();
    }

    // In-place radix-2 FFT, n a power of two.
    static void fft(float[] re, float[] im) {
      int n = re.length;
      for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; (j & bit) != 0; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
          float t = re[i]; re[i] = re[j]; re[j] = t;
          t = im[i]; im[i] = im[j]; im[j] = t;
        }
      }
      for (int len = 2; len <= n; len <<= 1) {
        double ang = -2 * Math.PI / len;
        float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
        for (int i = 0; i < n; i += len) {
          float cr = 1, ci = 0;
          for (int k = 0; k < len / 2; k++) {
            int a = i + k, b = a + len / 2;
            float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
            re[b] = re[a] - xr; im[b] = im[a] - xi;
            re[a] += xr; im[a] += xi;
            float nr = cr * wr - ci * wi;
            ci = cr * wi + ci * wr;
            cr = nr;
          }
        }
      }
    }
  }
}
