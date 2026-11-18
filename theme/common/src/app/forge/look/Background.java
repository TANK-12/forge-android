/* The picture under the particles: a Forge theme picture from the APK, a
   picture picked on the phone (a content address kept with permission), or
   the theme's own colours as a soft gradient. The picture is scaled once to
   cover the screen plus a little extra width, which the home-screen pages
   pan across (offset 0..1), and is dimmed by the Dim setting. */
package app.forge.look;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;

public final class Background {
  /** How much wider than the screen the picture is, for page panning. */
  static final float PAN = 0.18f;

  private final Context app;
  private Bitmap picture;
  private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final Paint shade = new Paint();
  private final Paint glow = new Paint();
  private final Paint fill = new Paint();
  private final Rect src = new Rect();
  private final RectF dst = new RectF();
  private int width, height, bg, panel, accent;
  private float dim, offset = 0.5f, brightness = 0f;

  public Background(Context context) {
    app = context.getApplicationContext();
  }

  /** Load what to show for this size. source: "theme", "none" or a content
      address; wall: the theme's asset path ("" when it has none). */
  public void load(int w, int h, String source, Themes.Theme theme, float dimAmount) {
    width = w;
    height = h;
    bg = theme.bg;
    panel = theme.panel;
    accent = theme.accent;
    dim = dimAmount;
    if (picture != null) {
      picture.recycle();
      picture = null;
    }
    if (w <= 0 || h <= 0) return;
    if (source.startsWith("content:")) picture = decodeUri(Uri.parse(source), w, h);
    else if ("theme".equals(source) && !theme.wall.isEmpty()) picture = decodeAsset(theme.wall, w, h);
    brightness = picture != null ? meanLuminance(picture) : Themes.luminance(bg);
    shade.setColor(Color.argb(Math.round(255 * Math.max(0f, Math.min(0.85f, dim))), 0, 0, 0));
    glow.setShader(new RadialGradient(w * 0.5f, h * 0.38f, Math.max(w, h) * 0.75f,
        Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)), 0,
        Shader.TileMode.CLAMP));
    fill.setShader(new LinearGradient(0, 0, 0, h, panel, bg, Shader.TileMode.CLAMP));
  }

  /** How light the picture is after dimming, 0..1 (particles draw
      differently over bright pictures, as on the desktop). */
  public float brightness() {
    return brightness * (1f - dim);
  }

  public void offset(float x) {
    offset = Math.max(0f, Math.min(1f, x));
  }

  public void draw(Canvas c) {
    if (picture == null) {
      c.drawRect(0, 0, width, height, fill);
      c.drawRect(0, 0, width, height, glow);
      return;
    }
    int spare = picture.getWidth() - width;
    int x = Math.round(spare * offset);
    src.set(x, 0, x + Math.min(width, picture.getWidth()), picture.getHeight());
    dst.set(0, 0, width, height);
    c.drawBitmap(picture, src, dst, plain);
    if (dim > 0.005f) c.drawRect(0, 0, width, height, shade);
  }

  public void release() {
    if (picture != null) picture.recycle();
    picture = null;
  }

  private Bitmap decodeAsset(String path, int w, int h) {
    try (InputStream probe = app.getAssets().open(path);
         InputStream in = app.getAssets().open(path)) {
      return decode(probe, in, w, h);
    } catch (IOException e) {
      return null;
    }
  }

  private Bitmap decodeUri(Uri uri, int w, int h) {
    try (InputStream probe = app.getContentResolver().openInputStream(uri);
         InputStream in = app.getContentResolver().openInputStream(uri)) {
      if (probe == null || in == null) return null;
      return decode(probe, in, w, h);
    } catch (IOException | SecurityException e) {
      return null; // the picture was deleted or its permission lapsed: colours instead
    }
  }

  // Decode at about the size needed, then scale to cover (w * (1 + PAN)) x h.
  private static Bitmap decode(InputStream probe, InputStream in, int w, int h) {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeStream(probe, null, o);
    if (o.outWidth <= 0 || o.outHeight <= 0) return null;
    int wantW = Math.round(w * (1f + PAN));
    int sample = 1;
    while (o.outWidth / (sample * 2) >= wantW && o.outHeight / (sample * 2) >= h) sample *= 2;
    BitmapFactory.Options d = new BitmapFactory.Options();
    d.inSampleSize = sample;
    Bitmap raw = BitmapFactory.decodeStream(in, null, d);
    if (raw == null) return null;
    float s = Math.max(wantW / (float) raw.getWidth(), h / (float) raw.getHeight());
    int sw = Math.max(1, Math.round(raw.getWidth() * s)), sh = Math.max(1, Math.round(raw.getHeight() * s));
    Bitmap scaled = Bitmap.createScaledBitmap(raw, sw, sh, true);
    if (scaled != raw) raw.recycle();
    // keep the middle (w * (1 + PAN)) x h
    int cx = Math.max(0, (sw - wantW) / 2), cy = Math.max(0, (sh - h) / 2);
    Bitmap out = Bitmap.createBitmap(scaled, cx, cy, Math.min(wantW, sw), Math.min(h, sh));
    if (out != scaled) scaled.recycle();
    return out;
  }

  private static float meanLuminance(Bitmap b) {
    Bitmap tiny = Bitmap.createScaledBitmap(b, 16, 16, true);
    float sum = 0;
    for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) sum += Themes.luminance(tiny.getPixel(x, y));
    if (tiny != b) tiny.recycle();
    return sum / 256f;
  }
}
