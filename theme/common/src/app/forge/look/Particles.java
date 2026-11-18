/*
 * Forge particles for Android: lights and weather drawn over a picture, for
 * the live wallpaper and the home-screen widget. A port of the desktop's
 * shared/forge_particles.js (v2): the same kinds, spawn rules, per-second
 * motion, depth (d.z: nearer ones bigger, quicker, brighter, and shifted
 * further by parallax), colours and pre-drawn sprites, stamped with
 * drawBitmap, so a frame costs a few hundred bitmap stamps and no blur.
 *
 * Units: everything inside works in CSS pixels, as the JS does, and draw()
 * scales the canvas by the display density, so a tablet at density 2 looks
 * like the desktop at 1x. Velocities are per 60th of a second, as in the JS;
 * step(dt) turns seconds into that factor (k). Call everything from one
 * thread (the wallpaper's drawing thread).
 *
 * Adding a kind: one entry in KINDS below (its key, count, spawn rule, draw
 * rule and any palette, size or gust), plus, if it needs a new look, a spawn
 * case in Layer.spawn(), a motion case in Layer.move(), a drawing case in
 * Layer.paint() and a closed path in Layer.place() for the widget loop. Keep
 * the key in step with shared/forge_particles.js and forge_theme.py.
 *
 * What the desktop has that this does not: the hub's look harmony (tone(),
 * eased amounts), the "auto" kind (it needs the wallpaper's ambience), the
 * power tier and frame caps (the host paces frames here).
 */
package app.forge.look;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.Arrays;
import java.util.Random;

public final class Particles {

    // ---- the kinds (shared/forge_particles.js KINDS, same order) ----

    private static final int S_MOTE = 0, S_EMBER = 1, S_STAR = 2, S_PETAL = 3, S_RAIN = 4, S_SNOW = 5,
            S_FLY = 6, S_DUST = 7, S_BUBBLE = 8, S_TWINKLE = 9, S_GLITTER = 10, S_LANTERN = 11,
            S_WISP = 12, S_GALAXY = 13;
    private static final int D_MOTES = 0, D_EMBERS = 1, D_STARS = 2, D_PETALS = 3, D_RAIN = 4, D_SNOW = 5,
            D_RAYS = 6, D_FLIES = 7, D_DUST = 8, D_BUBBLES = 9, D_TWINKLE = 10, D_GLITTER = 11,
            D_LANTERNS = 12, D_WISPS = 13, D_GALAXY = 14;

    private static final class Kind {
        final String key;
        final int count, spawn, draw;
        final boolean flat;
        final int[] palette;
        final float size, gust;

        Kind(String key, int count, int spawn, int draw, boolean flat, int[] palette, float size, float gust) {
            this.key = key;
            this.count = count;
            this.spawn = spawn;
            this.draw = draw;
            this.flat = flat;
            this.palette = palette;
            this.size = size;
            this.gust = gust;
        }
    }

    private static Kind kind(String key, int count, int spawn, int draw, boolean flat) {
        return new Kind(key, count, spawn, draw, flat, null, 1f, 1f);
    }

    private static final int[] EMBER = {0xffff8a3d, 0xffffc56b};
    private static final int[] PETAL = {0xffffc6d9, 0xffffb0c8, 0xffffe3ec, 0xfff7a1bd};
    private static final int[] LEAVES = {0xffd9822b, 0xffc2571a, 0xffe8b04a, 0xff8f4a1c};
    private static final int WHITE = 0xffffffff;

    private static final Kind[] KINDS = {
        kind("soft", 45, S_MOTE, D_MOTES, false),
        kind("lively", 110, S_MOTE, D_MOTES, false),
        kind("embers", 90, S_EMBER, D_EMBERS, false),
        kind("constellation", 95, S_STAR, D_STARS, true),
        kind("petals", 55, S_PETAL, D_PETALS, false),
        kind("rain", 170, S_RAIN, D_RAIN, false),
        kind("snow", 130, S_SNOW, D_SNOW, true),
        kind("rays", 40, S_MOTE, D_RAYS, false),
        kind("fireflies", 40, S_FLY, D_FLIES, false),
        kind("dust", 80, S_DUST, D_DUST, false),
        new Kind("leaves", 45, S_PETAL, D_PETALS, false, LEAVES, 1.6f, 1f),
        new Kind("petalstorm", 170, S_PETAL, D_PETALS, false, null, 1f, 2.2f),
        kind("bubbles", 50, S_BUBBLE, D_BUBBLES, false),
        kind("stars", 160, S_TWINKLE, D_TWINKLE, true),
        kind("emberstorm", 200, S_EMBER, D_EMBERS, false),
        kind("glitter", 90, S_GLITTER, D_GLITTER, false),
        kind("lanterns", 26, S_LANTERN, D_LANTERNS, true),
        kind("wisps", 34, S_WISP, D_WISPS, false),
        kind("galaxy", 320, S_GALAXY, D_GALAXY, true),
    };

    private static final float TAU = (float) (Math.PI * 2);
    // the desktop screen the counts were tuned on: a smaller canvas draws a
    // square root of its share (the JS preview()'s area rule), never under 0.2
    private static final float REF_AREA = 1920f * 1080f;
    // opacity steps for batched lines, as the JS STEPS
    private static final int STEPS = 6;
    // home-screen pages: the whole swipe moves a depth-1 particle this share of the width
    private static final float PAGE_TRAVEL = 0.06f;
    // kinds the JS draws without parallax (still stars, the galaxy) still shift this much
    private static final float FLAT_PAGE = 0.35f;
    // the widget loop: its nominal length (for how many twinkles fit) and its seed
    private static final float LOOP_MS = 3000f;
    private static final long LOOP_SEED = 1293L;
    private static final int SPARK_CAP = 340;

    private static final PorterDuffXfermode ADD_MODE = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    // ---- settings ----

    private int kindIndex = 0; // -1 is off
    private int accent = 0xff8ab4ff, accent2 = 0xff8ab4ff;
    private boolean bright = false;
    private float amount = 1f, size = 1f, speed = 1f;
    private float density = 1f;

    // ---- shared drawing state (one thread, reused every frame) ----

    private final Paint addPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint cur = addPaint;
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final float[] mv = new float[9];
    private final LineBuf[][] lineSets = new LineBuf[4][STEPS];
    private final Bitmap[] tintBmps = new Bitmap[4];
    private final int[] tintCols = new int[4];
    private float clipW, clipH; // the canvas in CSS pixels, for skipping stamps off it

    // sprite caches, by colour: glows (64 x 64) and petals (32 x 64)
    private final int[] glowKeys = new int[40];
    private final Bitmap[] glowBmps = new Bitmap[40];
    private int glowN = 0;
    private final int[] petalKeys = new int[16];
    private final Bitmap[] petalBmps = new Bitmap[16];
    private int petalN = 0;
    private Bitmap lanternBmp;
    private final Bitmap[] beamBmps = new Bitmap[2];
    private int beamColour = 0;

    // ---- live state ----

    private final Layer live = new Layer(false, new Random());
    private final Layer loop = new Layer(true, new Random(LOOP_SEED));
    private int loopKey = 0;
    private double clockMs = 0;
    private boolean pointerDown = false;
    private float pointerX, pointerY; // CSS pixels on screen
    private boolean pageOn = false;
    private float pageTarget = 0.5f;

    // music: targets from sound(), smoothed in step()
    private float levelIn, bassIn, levelS, bassS, kickS;
    private boolean beatWas, beatPending;

    // sparks (spark()): a fixed pool, struct of arrays
    private final float[] sx = new float[SPARK_CAP], sy = new float[SPARK_CAP], svx = new float[SPARK_CAP],
            svy = new float[SPARK_CAP], slife = new float[SPARK_CAP], sr = new float[SPARK_CAP];
    private final int[] scol = new int[SPARK_CAP];
    private int sparkN = 0, sparkNext = 0;
    private final Random sparkRnd = new Random();

    public Particles() {
        addPaint.setXfermode(ADD_MODE);
        linePaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1.2f);
        shinePaint.setStyle(Paint.Style.FILL);
        shinePaint.setColor(WHITE);
        for (LineBuf[] set : lineSets) {
            for (int i = 0; i < STEPS; i++) set[i] = new LineBuf();
        }
    }

    // ---- public API ----

    public static String[] kinds() {
        String[] out = new String[KINDS.length];
        for (int i = 0; i < KINDS.length; i++) out[i] = KINDS[i].key;
        return out;
    }

    public void setKind(String kind) {
        int idx = 0;
        if ("off".equals(kind)) {
            idx = -1;
        } else if (kind != null) {
            for (int i = 0; i < KINDS.length; i++) {
                if (KINDS[i].key.equals(kind)) idx = i;
            }
        }
        if (idx != kindIndex) {
            kindIndex = idx;
            live.reseed();
        }
    }

    public String kind() {
        return kindIndex < 0 ? "off" : KINDS[kindIndex].key;
    }

    public void setColours(int accent, int accent2, boolean brightBackground) {
        this.accent = accent | 0xff000000;
        this.accent2 = accent2 | 0xff000000;
        this.bright = brightBackground;
    }

    public void setAmount(float amount) {
        float a = clamp(Float.isNaN(amount) ? 1f : amount, 0.25f, 2.5f);
        if (a != this.amount) {
            this.amount = a;
            live.fill();
        }
    }

    public void setSize(float size) {
        float s = clamp(Float.isNaN(size) ? 1f : size, 0.5f, 2f);
        if (s != this.size) {
            this.size = s;
            live.reseed();
        }
    }

    public void setSpeed(float speed) {
        float s = clamp(Float.isNaN(speed) ? 1f : speed, 0.25f, 3f);
        if (s != this.speed) {
            this.speed = s;
            live.reseed();
        }
    }

    public void resize(int width, int height, float density) {
        this.density = density > 0 ? density : 1f;
        float w = Math.max(1, width) / this.density, h = Math.max(1, height) / this.density;
        // a turn of the tablet or a new screen reseeds; a small change (a bar
        // showing) keeps them, and ones outside are born again as they leave
        boolean big = live.W <= 0 || Math.abs(w - live.W) > live.W * 0.2f || Math.abs(h - live.H) > live.H * 0.2f;
        live.W = w;
        live.H = h;
        if (big) live.reseed();
        else live.fill();
    }

    public void step(float dt) {
        if (!(dt > 0)) dt = 0;
        if (dt > 0.1f) dt = 0.1f;
        float k = dt * 60f;
        clockMs += dt * 1000.0;

        // music, smoothed: up fast, down slow, and exactly zero when it stops
        levelS = follow(levelS, levelIn, dt);
        bassS = follow(bassS, bassIn, dt);
        if (beatPending) {
            beatPending = false;
            kickS = Math.max(kickS, 0.35f + 0.4f * levelS);
        }
        // a kick eases back as the JS surge does (0.88 a 60th: about a tenth left at 0.3 s)
        kickS = kickS > 0.02f ? kickS * (float) Math.pow(0.88, k) : 0f;

        // parallax: the finger's place (as the JS pointer) and the home-screen page
        Layer L = live;
        float px = pointerDown && L.W > 0 ? (pointerX / L.W - 0.5f) * -18f : 0f;
        float py = pointerDown && L.H > 0 ? (pointerY / L.H - 0.5f) * -12f : 0f;
        float ease = 1f - (float) Math.pow(0.94, k);
        L.ox += (px - L.ox) * ease;
        L.oy += (py - L.oy) * ease;
        float pageGoal = pageOn ? (0.5f - pageTarget) * PAGE_TRAVEL * L.W : 0f;
        L.pageOx += (pageGoal - L.pageOx) * (1f - (float) Math.pow(0.85, k));
        L.stretch = pageOn ? 1f + PAGE_TRAVEL * 1.3f : 1f;
        L.stretchFlat = pageOn ? 1f + PAGE_TRAVEL * FLAT_PAGE : 1f;
        L.hasPointer = pointerDown;
        L.smx = pointerX;
        L.smy = pointerY;
        // the finger in the particles' own place (before the parallax shift)
        L.pmx = L.W * 0.5f + (pointerX - L.W * 0.5f - L.ox - L.pageOx) / L.stretch;
        L.pmy = pointerY - L.oy;

        // music quickens motion a little (up to 40%); the twinkle clock stays real
        L.k = k * (1f + 0.4f * levelS);
        if (kindIndex >= 0 && L.count > 0 && k > 0) {
            if (kickS > 0.02f) L.kick(kickS * 3f * k);
            L.move();
        }
        stepSparks(k);
    }

    public void draw(Canvas c) {
        boolean dots = kindIndex >= 0 && live.count > 0;
        if (!dots && sparkN == 0) return;
        c.save();
        c.scale(density, density);
        clipW = live.W;
        clipH = live.H;
        // music: a little brighter with the level, a brief lift on a beat
        live.gain = 1f + 0.35f * levelS + 0.25f * kickS;
        live.lineGain = 1f + 0.6f * bassS;
        if (dots) live.paint(c);
        drawSparks(c, live.gain);
        c.restore();
    }

    public void pointer(float x, float y) {
        pointerDown = true;
        pointerX = x / density;
        pointerY = y / density;
    }

    public void pointerUp() {
        pointerDown = false;
    }

    public void spark(float x, float y) {
        if (kindIndex < 0) return; // as the JS burst(): nothing while off
        float px = x / density, py = y / density;
        Random r = sparkRnd;
        for (int i = 0; i < 34; i++) {
            float ang = r.nextFloat() * TAU, sp = 1.5f + r.nextFloat() * 5f;
            int s;
            if (sparkN < SPARK_CAP) {
                s = sparkN++;
            } else {
                s = sparkNext;
                sparkNext = (sparkNext + 1) % SPARK_CAP;
            }
            sx[s] = px;
            sy[s] = py;
            svx[s] = (float) Math.cos(ang) * sp;
            svy[s] = (float) Math.sin(ang) * sp - 1.4f;
            slife[s] = 1f;
            sr[s] = 1.5f + r.nextFloat() * 2.5f;
            scol[s] = r.nextFloat() < 0.35f ? EMBER[i % 2] : accent;
        }
    }

    public void parallax(float offset) {
        pageOn = true;
        pageTarget = Float.isNaN(offset) ? 0.5f : clamp(offset, 0f, 1f);
    }

    public void sound(float level, float bass, boolean beat) {
        levelIn = Float.isNaN(level) ? 0f : clamp(level, 0f, 1f);
        bassIn = Float.isNaN(bass) ? 0f : clamp(bass, 0f, 1f);
        if (beat && !beatWas) beatPending = true;
        beatWas = beat;
    }

    public void drawLoopFrame(Canvas c, int width, int height, float density, float phase) {
        if (kindIndex < 0) return;
        float d = density > 0 ? density : 1f;
        float w = Math.max(1, width) / d, h = Math.max(1, height) / d;
        int key = ((kindIndex * 31 + Float.floatToIntBits(size)) * 31 + Float.floatToIntBits(amount)) * 31
                + Float.floatToIntBits(speed);
        Layer L = loop;
        if (key != loopKey || L.W != w || L.H != h) {
            loopKey = key;
            L.W = w;
            L.H = h;
            L.buildLoop();
        }
        L.phase = (float) (phase - Math.floor(phase));
        L.place();
        L.gain = 1f;
        L.lineGain = 1f;
        c.save();
        c.scale(d, d);
        clipW = w;
        clipH = h;
        L.paint(c);
        c.restore();
    }

    // ---- small helpers ----

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static float follow(float now, float goal, float dt) {
        float tau = goal > now ? 0.06f : 0.6f;
        float next = now + (goal - now) * (1f - (float) Math.exp(-dt / tau));
        return goal == 0f && next < 0.002f ? 0f : next;
    }

    private static float wrap(float v, float lo, float hi) {
        float span = hi - lo, u = (v - lo) % span;
        if (u < 0) u += span;
        return lo + u;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xff000000 | (r << 16) | (g << 8) | bl;
    }

    private static int stepOf(float a) {
        return Math.max(0, Math.min(STEPS - 1, Math.round(a * (STEPS - 1))));
    }

    /** Glows add light on a dark picture; on a bright one they are painted over it. */
    private void blend() {
        cur = bright ? overPaint : addPaint;
    }

    private void over() {
        cur = overPaint;
    }

    private void stamp(Canvas c, Bitmap img, float x, float y, float radius, float alpha) {
        if (alpha > 1f) alpha = 1f;
        if (alpha * 255f < 0.5f || radius <= 0) return;
        if (x + radius < 0 || y + radius < 0 || x - radius > clipW || y - radius > clipH) return;
        cur.setAlpha(Math.round(alpha * 255f));
        rect.set(x - radius, y - radius, x + radius, y + radius);
        c.drawBitmap(img, null, rect, cur);
    }

    private void strokeLines(Canvas c, LineBuf[] groups, int colour, float width, boolean additive,
            boolean round, float gain) {
        linePaint.setStrokeWidth(width);
        linePaint.setStrokeCap(round ? Paint.Cap.ROUND : Paint.Cap.BUTT);
        linePaint.setXfermode(additive && !bright ? ADD_MODE : null);
        linePaint.setColor(colour);
        for (int s = 1; s < STEPS; s++) {
            LineBuf b = groups[s];
            if (b.n == 0) continue;
            linePaint.setAlpha(Math.round(Math.min(1f, s / (STEPS - 1f) * gain) * 255f));
            c.drawLines(b.pts, 0, b.n, linePaint);
        }
    }

    private static void clearLines(LineBuf[] groups) {
        for (LineBuf b : groups) b.n = 0;
    }

    /** Line ends for one opacity step, grown only when a frame needs more. */
    private static final class LineBuf {
        float[] pts = new float[512];
        int n = 0;

        void add(float x1, float y1, float x2, float y2) {
            if (n + 4 > pts.length) pts = Arrays.copyOf(pts, pts.length * 2);
            pts[n] = x1;
            pts[n + 1] = y1;
            pts[n + 2] = x2;
            pts[n + 3] = y2;
            n += 4;
        }
    }

    // ---- sprites, drawn once per colour and stamped after ----

    /** One glow, the colour fading out from the middle (the JS sprite()). */
    private Bitmap glow(int colour) {
        colour |= 0xff000000;
        for (int i = 0; i < glowN; i++) {
            if (glowKeys[i] == colour) return glowBmps[i];
        }
        if (glowN == glowKeys.length) glowN = 0; // full: start the cache again
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        int rgb = colour & 0xffffff;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(32, 32, 32,
                new int[] {0xff000000 | rgb, 0xbf000000 | rgb, 0x2e000000 | rgb, rgb},
                new float[] {0f, 0.18f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, 64, 64, p);
        glowKeys[glowN] = colour;
        glowBmps[glowN++] = b;
        return b;
    }

    /** A petal: two curves and a faint vein, 32 x 64. */
    private Bitmap petal(int colour) {
        colour |= 0xff000000;
        for (int i = 0; i < petalN; i++) {
            if (petalKeys[i] == colour) return petalBmps[i];
        }
        if (petalN == petalKeys.length) petalN = 0;
        Bitmap b = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        Path path = new Path();
        path.moveTo(16, 1);
        path.quadTo(31, 26, 16, 63);
        path.quadTo(1, 26, 16, 1);
        g.drawPath(path, p);
        p.setColor(WHITE);
        p.setAlpha(Math.round(0.25f * 255));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        g.drawLine(16, 6, 16, 54, p);
        petalKeys[petalN] = colour;
        petalBmps[petalN++] = b;
        return b;
    }

    /** One lantern body, 64 wide and 96 tall, the paper in 64 x 86. */
    private Bitmap lantern() {
        if (lanternBmp != null) return lanternBmp;
        Bitmap b = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 5, 0, 91, new int[] {0xffffcf7a, 0xffff9a3c, 0xffd8582a},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        g.drawRoundRect(0, 5, 64, 91, 22, 22, p);
        p.setShader(null);
        // ribs, and the dark rims top and bottom
        p.setColor(0xff7a3010);
        p.setAlpha(Math.round(0.18f * 255));
        for (int y = 18; y < 86; y += 12) g.drawRect(2, y, 62, y + 1.5f, p);
        p.setColor(0xff5a2a14);
        g.drawRect(14, 0, 50, 8, p);
        g.drawRect(14, 88, 50, 96, p);
        return lanternBmp = b;
    }

    /**
     * The two light beams of rays (narrow and wide), drawn once per colour:
     * the beam runs along x from (0, 32), fading to nothing at x = 512, and
     * is stamped rotated and scaled to the screen.
     */
    private void beams(int warm) {
        if (beamBmps[0] != null && beamColour == warm) return;
        beamColour = warm;
        for (int i = 0; i < 2; i++) {
            float spread = 0.03f + i * 0.025f;
            Bitmap b = Bitmap.createBitmap(512, 64, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 32, 512, 32, warm | 0xff000000, warm & 0xffffff,
                    Shader.TileMode.CLAMP));
            Path path = new Path();
            path.moveTo(0, 32);
            path.lineTo(512 * (float) Math.cos(spread), 32 - 512 * (float) Math.sin(spread));
            path.lineTo(512 * (float) Math.cos(spread), 32 + 512 * (float) Math.sin(spread));
            path.close();
            new Canvas(b).drawPath(path, p);
            beamBmps[i] = b;
        }
    }

    // ---- sparks ----

    private void stepSparks(float k) {
        if (sparkN == 0 || k <= 0) return;
        float f = (float) Math.pow(0.955, k);
        for (int i = 0; i < sparkN; i++) {
            svx[i] *= f;
            svy[i] = svy[i] * f + 0.09f * k;
            sx[i] += svx[i] * k;
            sy[i] += svy[i] * k;
            slife[i] -= 0.022f * k;
            if (slife[i] <= 0) {
                int last = --sparkN;
                sx[i] = sx[last];
                sy[i] = sy[last];
                svx[i] = svx[last];
                svy[i] = svy[last];
                slife[i] = slife[last];
                sr[i] = sr[last];
                scol[i] = scol[last];
                i--;
            }
        }
        if (sparkNext >= Math.max(1, sparkN)) sparkNext = 0;
    }

    private void drawSparks(Canvas c, float gain) {
        if (sparkN == 0) return;
        blend();
        for (int i = 0; i < sparkN; i++) {
            float life = slife[i];
            stamp(c, glow(scol[i]), sx[i], sy[i], sr[i] * 5f * (0.5f + life / 2f), life * gain);
        }
    }

    // ---- one particle, reused: respawning sets its fields again ----

    private static final class Dot {
        float x, y, r, vx, vy, a, tw, z, life, sp, spin, rot, ang, tick, R, th, lift;
        int c;
        boolean shoot;
        final float[] trail = new float[16];
        // the widget loop's closed path
        float x0, y0, th0, rot0, amp;
        int n, turns;

        void reset() {
            x = y = r = vx = vy = a = tw = spin = rot = ang = tick = R = th = lift = 0f;
            z = -1f;
            life = 1f;
            sp = 1f;
            c = 0;
            shoot = false;
        }

        void copyFrom(Dot o) {
            x = o.x;
            y = o.y;
            r = o.r;
            vx = o.vx;
            vy = o.vy;
            a = o.a;
            tw = o.tw;
            z = o.z;
            life = o.life;
            sp = o.sp;
            spin = o.spin;
            rot = o.rot;
            ang = o.ang;
            tick = o.tick;
            R = o.R;
            th = o.th;
            lift = o.lift;
            c = o.c;
            shoot = o.shoot;
            System.arraycopy(o.trail, 0, trail, 0, trail.length);
        }
    }

    // ---- a layer of particles: the live wallpaper's, or the widget loop's ----

    private final class Layer {
        final boolean loopMode;
        final Random rnd;
        Dot[] dots = new Dot[0];
        int count = 0;
        float W = 0, H = 0;
        float k = 1f; // this step's length in 60ths of a second
        float ox, oy, pageOx; // parallax: the finger's (eased) and the page's
        float stretch = 1f, stretchFlat = 1f;
        boolean hasPointer;
        float pmx, pmy, smx, smy; // the finger: in particle space, and on screen
        float gain = 1f, lineGain = 1f;
        float phase = 0f; // the loop's 0..1

        Layer(boolean loopMode, Random rnd) {
            this.loopMode = loopMode;
            this.rnd = rnd;
        }

        Kind spec() {
            return KINDS[kindIndex < 0 ? 0 : kindIndex];
        }

        float rand() {
            return rnd.nextFloat();
        }

        int want() {
            if (kindIndex < 0 || W <= 0 || H <= 0) return 0;
            float area = clamp((float) Math.sqrt(W * H / REF_AREA), 0.2f, 1f);
            return Math.round(spec().count * amount * area);
        }

        void grow(int n) {
            if (dots.length >= n) return;
            int old = dots.length;
            dots = Arrays.copyOf(dots, n);
            for (int i = old; i < n; i++) dots[i] = new Dot();
        }

        /** Every particle born again (a new kind, size or speed). */
        void reseed() {
            count = 0;
            fill();
        }

        /** Born or dropped to match the count for this size and amount. */
        void fill() {
            int n = want();
            grow(n);
            for (int i = count; i < n; i++) spawn(dots[i], true);
            count = n;
        }

        // where a particle is drawn: its place plus the parallax for its depth
        float X(Dot d) {
            return W * 0.5f + (d.x - W * 0.5f) * stretch + (ox + pageOx) * d.z;
        }

        float Y(Dot d) {
            return d.y + oy * d.z;
        }

        // still stars (no parallax in the JS): only the page shifts them, a little
        float FX(Dot d) {
            return W * 0.5f + (d.x - W * 0.5f) * stretchFlat + pageOx * FLAT_PAGE;
        }

        /** sin(t / periodMs + ph); in the loop a whole number of turns per loop. */
        float osc(float periodMs, float ph) {
            if (loopMode) {
                int n = Math.max(1, Math.min(2, Math.round(LOOP_MS / (TAU * periodMs))));
                return (float) Math.sin(TAU * phase * n + ph);
            }
            return (float) Math.sin(clockMs / periodMs + ph);
        }

        float oscCos(float periodMs, float ph) {
            return osc(periodMs, ph + TAU / 4f);
        }

        float damp(float f) {
            return k == 1f ? f : (float) Math.pow(f, k);
        }

        void push(Dot d, float reach, float force) {
            if (!hasPointer) return;
            float dx = d.x - pmx, dy = d.y - pmy, d2 = dx * dx + dy * dy;
            if (d2 < reach * reach && d2 > 0.01f) {
                float f = force * k / (float) Math.sqrt(d2);
                d.vx += dx * f;
                d.vy += dy * f;
            }
        }

        /** A beat's kick: near ones move further along their own way for a moment. */
        void kick(float s) {
            boolean flat = spec().flat;
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                float near = flat ? 0.5f : clamp((d.z - 0.55f) / 0.75f, 0f, 1f);
                d.x += d.vx * s * near;
                d.y += d.vy * s * near;
            }
        }

        // ---- birth (the JS spawn*) ----

        void spawn(Dot d, boolean anywhere) {
            Kind kd = spec();
            float w = W, h = H;
            d.reset();
            switch (kd.spawn) {
                case S_MOTE: {
                    float y = anywhere ? rand() * h : h + 10;
                    float s = kd.key.equals("lively") ? 0.35f : 0.14f;
                    d.x = rand() * w;
                    d.y = y;
                    d.r = 1 + rand() * 2.2f;
                    d.vx = (rand() - 0.5f) * s;
                    d.vy = -(0.2f + rand()) * s;
                    d.a = 0.25f + rand() * 0.5f;
                    d.tw = rand() * 6.28f;
                    break;
                }
                case S_EMBER: {
                    d.y = anywhere ? rand() * h : h + 10;
                    d.x = rand() * w;
                    d.r = 1.4f + rand() * 3;
                    d.vx = (rand() - 0.5f) * 0.4f;
                    d.vy = -(0.35f + rand() * 1.1f);
                    d.a = 0.55f + rand() * 0.45f;
                    d.tw = rand() * 6.28f;
                    d.life = 0.6f + rand() * 0.4f;
                    d.c = rand() < 0.2f ? -1 : rand() < 0.6f ? 0 : 1;
                    break;
                }
                case S_STAR:
                    d.x = rand() * w;
                    d.y = rand() * h;
                    d.r = 1.2f + rand() * 2;
                    d.vx = (rand() - 0.5f) * 0.3f;
                    d.vy = (rand() - 0.5f) * 0.3f;
                    d.a = 0.35f + rand() * 0.5f;
                    d.tw = rand() * 6.28f;
                    break;
                case S_PETAL: {
                    float sz = kd.size, gust = kd.gust;
                    d.x = rand() * (w + 200) - 100;
                    d.y = anywhere ? rand() * h : -20;
                    d.r = (5 + rand() * 5) * sz;
                    d.vx = (0.3f + rand() * 0.5f) * gust;
                    d.vy = (0.5f + rand() * 0.7f) * (1 + (gust - 1) * 0.4f);
                    d.a = 0.6f + rand() * 0.35f;
                    d.tw = rand() * 6.28f;
                    d.spin = (rand() - 0.5f) * 0.05f;
                    d.rot = rand() * 6.28f;
                    d.c = (int) Math.floor(rand() * 4);
                    break;
                }
                case S_RAIN:
                    d.x = rand() * (w + 300);
                    d.y = anywhere ? rand() * h : -30;
                    d.r = 12 + rand() * 18;
                    d.vx = -2.2f;
                    d.vy = 13 + rand() * 6;
                    d.a = 0.12f + rand() * 0.2f;
                    break;
                case S_SNOW: {
                    float depth = 0.4f + rand() * 0.8f;
                    d.x = rand() * w;
                    d.y = anywhere ? rand() * h : -10;
                    d.r = 1 + depth * 2.4f;
                    d.vy = 0.4f + depth * 0.9f;
                    d.a = 0.4f + depth * 0.45f;
                    d.tw = rand() * 6.28f;
                    d.z = depth;
                    break;
                }
                case S_FLY:
                    d.x = rand() * w;
                    d.y = h * 0.3f + rand() * h * 0.7f;
                    d.r = 1.4f + rand() * 1.6f;
                    d.vx = (rand() - 0.5f) * 0.6f;
                    d.vy = (rand() - 0.5f) * 0.6f;
                    d.a = 0.5f + rand() * 0.5f;
                    d.tw = rand() * 6.28f;
                    break;
                case S_DUST:
                    d.x = anywhere ? rand() * w : -10;
                    d.y = rand() * h;
                    d.r = 0.6f + rand() * 1.4f;
                    d.vx = 0.15f + rand() * 0.35f;
                    d.vy = (rand() - 0.5f) * 0.12f;
                    d.a = 0.25f + rand() * 0.45f;
                    d.tw = rand() * 6.28f;
                    break;
                case S_BUBBLE: {
                    d.x = rand() * w;
                    d.y = anywhere ? rand() * h : h + 30;
                    float q = rand();
                    d.r = 3 + q * q * 16;
                    d.vy = -(0.3f + rand() * 0.6f);
                    d.a = 0.25f + rand() * 0.35f;
                    d.tw = rand() * 6.28f;
                    break;
                }
                case S_TWINKLE: {
                    d.x = rand() * w;
                    d.y = rand() * h;
                    float q = rand();
                    d.r = 0.5f + q * q * q * 2.2f;
                    d.a = 0.3f + rand() * 0.6f;
                    d.tw = rand() * 6.28f;
                    d.sp = 0.4f + rand() * 1.6f;
                    break;
                }
                case S_GLITTER:
                    d.x = rand() * w;
                    d.y = anywhere ? rand() * h : -10;
                    d.r = 0.8f + rand() * 1.8f;
                    d.vx = (rand() - 0.5f) * 0.2f;
                    d.vy = 0.15f + rand() * 0.35f;
                    d.a = 0.5f + rand() * 0.5f;
                    d.tw = rand() * 6.28f;
                    d.sp = 0.8f + rand() * 2.5f;
                    d.c = (int) Math.floor(rand() * 4);
                    break;
                case S_LANTERN: {
                    float depth = 0.4f + rand() * 0.8f;
                    d.x = rand() * w;
                    d.y = anywhere ? rand() * h : h + 40;
                    d.r = 7 + depth * 9;
                    d.vy = -(0.12f + depth * 0.3f);
                    d.a = 0.45f + depth * 0.4f;
                    d.tw = rand() * 6.28f;
                    d.z = depth;
                    break;
                }
                case S_WISP:
                    d.x = anywhere ? rand() * w : rand() < 0.5f ? -20 : w + 20;
                    d.y = rand() * h;
                    d.r = 2 + rand() * 2.5f;
                    d.a = 0.5f + rand() * 0.45f;
                    d.tw = rand() * 6.28f;
                    d.ang = rand() * 6.28f;
                    Arrays.fill(d.trail, Float.NaN);
                    d.tick = 0;
                    d.c = rand() < 0.5f ? 0 : 1;
                    break;
                case S_GALAXY: {
                    // most stars out along the two arms, a dense few in the core
                    boolean core = rand() < 0.18f;
                    float q = rand();
                    float R = core ? q * q * 0.18f : 0.1f + (float) Math.pow(rand(), 1.15) * 0.9f;
                    float arm = rand() < 0.5f ? 0f : (float) Math.PI;
                    float rr = rand();
                    d.r = 0.6f + rr * rr * 1.8f;
                    d.a = 0.35f + rand() * 0.6f;
                    d.tw = rand() * 6.28f;
                    d.z = 1;
                    d.R = R;
                    d.th = core ? rand() * 6.28f : arm + R * 5.6f + (rand() - 0.5f) * (0.28f + R * 0.35f);
                    d.lift = (rand() - 0.5f) * 0.12f * (1 - R);
                    d.c = rand() < 0.25f + (1 - R) * 0.5f ? 0 : rand() < 0.6f ? 1 : 2;
                    break;
                }
                default:
                    break;
            }
            // depth: near ones bigger, quicker and brighter; flat kinds have their own
            if (d.z < 0) {
                d.z = kd.flat ? 1f : 0.55f + rand() * 0.75f;
                if (!kd.flat) {
                    d.r *= (float) Math.pow(d.z, 0.8);
                    d.vx *= d.z;
                    d.vy *= d.z;
                    d.a = Math.min(1f, d.a * (0.75f + 0.25f * d.z));
                }
            }
            // Particle size and Particle speed scale every kind
            if (size != 1f) d.r *= size;
            if (speed != 1f) {
                d.vx *= speed;
                d.vy *= speed;
                d.sp *= speed;
            }
        }

        // ---- motion, live only (the first half of each JS draw*) ----

        void move() {
            double tm = clockMs;
            Kind kd = spec();
            float w = W, h = H;
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                boolean out = false;
                switch (kd.draw) {
                    case D_MOTES: {
                        float f = damp(0.995f);
                        push(d, 140, 0.02f);
                        d.vx *= f;
                        d.vy = d.vy * f - 0.0005f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.y < -10 || d.x < -10 || d.x > w + 10;
                        break;
                    }
                    case D_EMBERS:
                        push(d, 160, 0.05f);
                        d.vx = d.vx * damp(0.985f) + (float) Math.sin(tm / 700 + d.tw) * 0.012f * k;
                        d.vy = d.vy * damp(0.992f) - 0.004f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        d.life -= 0.0016f * k;
                        out = d.life <= 0 || d.y < -20 || d.x < -20 || d.x > w + 20;
                        break;
                    case D_STARS: {
                        float f = damp(0.999f);
                        d.vx *= f;
                        d.vy *= f;
                        if (d.vx * d.vx + d.vy * d.vy < 0.0025f) {
                            d.vx += (rand() - 0.5f) * 0.05f;
                            d.vy += (rand() - 0.5f) * 0.05f;
                        }
                        push(d, 90, 0.015f);
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        if (d.x < -5) d.x = w + 5;
                        else if (d.x > w + 5) d.x = -5;
                        if (d.y < -5) d.y = h + 5;
                        else if (d.y > h + 5) d.y = -5;
                        break;
                    }
                    case D_PETALS: {
                        float f = damp(0.99f);
                        push(d, 120, 0.04f);
                        d.vx = d.vx * f + (0.006f + (float) Math.sin(tm / 1300 + d.tw) * 0.012f) * k;
                        d.vy = d.vy * f + 0.006f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        d.rot += (d.spin + (float) Math.sin(tm / 900 + d.tw) * 0.01f) * k;
                        out = d.y > h + 20 || d.x > w + 40;
                        break;
                    }
                    case D_RAIN:
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.y > h + 30 || d.x < -40;
                        break;
                    case D_SNOW:
                        push(d, 110, 0.03f);
                        d.vx = d.vx * damp(0.98f) + (float) Math.sin(tm / 1600 + d.tw) * 0.02f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.y > h + 10 || d.x < -20 || d.x > w + 20;
                        break;
                    case D_RAYS: {
                        float f = damp(0.99f);
                        d.vx = d.vx * f + (float) Math.sin(tm / 2000 + d.tw) * 0.004f * k;
                        d.vy = d.vy * f + (float) Math.cos(tm / 2300 + d.tw) * 0.004f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.y < -10 || d.x < -10 || d.x > w + 10 || d.y > h + 10;
                        break;
                    }
                    case D_FLIES: {
                        if (hasPointer) {
                            float dx = pmx - d.x, dy = pmy - d.y, dist = (float) Math.hypot(dx, dy);
                            if (dist < 260 && dist > 30) {
                                d.vx += dx / dist * 0.01f * k;
                                d.vy += dy / dist * 0.01f * k;
                            }
                        }
                        float f = damp(0.985f);
                        d.vx = d.vx * f + (float) Math.sin(tm / 1100 + d.tw * 3) * 0.012f * k;
                        d.vy = d.vy * f + (float) Math.cos(tm / 1300 + d.tw * 2) * 0.012f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.y < -20 || d.x < -20 || d.x > w + 20 || d.y > h + 20;
                        break;
                    }
                    case D_DUST:
                        push(d, 120, 0.02f);
                        d.vx = d.vx * damp(0.995f) + 0.001f * k;
                        d.vy = d.vy * damp(0.99f) + (float) Math.sin(tm / 1800 + d.tw) * 0.003f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.x > w + 10 || d.y < -10 || d.y > h + 10;
                        break;
                    case D_BUBBLES:
                        push(d, 110, 0.05f);
                        d.vx = d.vx * damp(0.95f) + (float) Math.sin(tm / 600 + d.tw) * 0.03f * k;
                        d.vy = d.vy * damp(0.995f) - 0.002f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.y < -40;
                        break;
                    case D_TWINKLE:
                        if (d.shoot) {
                            d.x += d.vx * k;
                            d.y += d.vy * k;
                            d.life -= k;
                            out = d.life <= 0 || d.x > w + 50 || d.y > h + 50;
                        } else if (rand() < 0.00004f * k) {
                            // now and then one shoots across
                            d.shoot = true;
                            d.vx = 9 + rand() * 6;
                            d.vy = 3 + rand() * 3;
                            d.life = 60;
                        }
                        break;
                    case D_GLITTER:
                        push(d, 120, 0.03f);
                        d.vx = d.vx * damp(0.99f) + (float) Math.sin(tm / 1400 + d.tw) * 0.004f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.y > h + 10 || d.x < -10 || d.x > w + 10;
                        break;
                    case D_LANTERNS:
                        push(d, 130, 0.02f);
                        d.vx = d.vx * damp(0.98f) + (float) Math.sin(tm / 2200 + d.tw) * 0.01f * k;
                        d.x += d.vx * k;
                        d.y += d.vy * k;
                        out = d.y < -60 || d.x < -40 || d.x > w + 40;
                        break;
                    case D_WISPS: {
                        // a smooth current that turns them; the finger draws them in a little
                        float turn = (float) (Math.sin(d.x * 0.0035 + tm * 0.00025 + d.tw)
                                + Math.cos(d.y * 0.004 - tm * 0.0002));
                        d.ang += turn * 0.018f * k;
                        float sp = 0.9f * d.z;
                        d.x += ((float) Math.cos(d.ang) * sp + d.vx) * k;
                        d.y += ((float) Math.sin(d.ang) * sp * 0.7f + d.vy) * k;
                        float f = damp(0.95f);
                        d.vx *= f;
                        d.vy *= f;
                        push(d, 180, -0.012f);
                        out = d.x < -60 || d.x > w + 60 || d.y < -60 || d.y > h + 60;
                        if (!out && (d.tick += k) >= 7) {
                            // the trail keeps a point every few frames, oldest last
                            d.tick = 0;
                            System.arraycopy(d.trail, 0, d.trail, 2, 14);
                            d.trail[0] = d.x;
                            d.trail[1] = d.y;
                        }
                        break;
                    }
                    case D_GALAXY:
                        d.th += (0.0009f / (0.25f + d.R)) * k * speed; // inner stars turn faster
                        break;
                    default:
                        break;
                }
                if (out) spawn(d, false);
            }
        }

        // ---- drawing, shared by the live layer and the loop (the second half of each JS draw*) ----

        void paint(Canvas c) {
            Kind kd = spec();
            switch (kd.draw) {
                case D_MOTES: {
                    Bitmap img = glow(accent), white = glow(WHITE);
                    blend();
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        float g = d.a * (0.6f + 0.4f * osc(900, d.tw)) * gain, x = X(d), y = Y(d);
                        stamp(c, img, x, y, d.r * 5, g);
                        stamp(c, white, x, y, d.r * 1.2f, g);
                    }
                    break;
                }
                case D_EMBERS: {
                    Bitmap e0 = glow(EMBER[0]), e1 = glow(EMBER[1]), ea = glow(accent), core = glow(0xfffff4d6);
                    blend();
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        float flicker = 0.55f + 0.45f * osc(90, d.tw * 7);
                        float fade = Math.min(1f, d.life * 3), x = X(d), y = Y(d);
                        stamp(c, d.c < 0 ? ea : d.c == 0 ? e0 : e1, x, y, d.r * 7, d.a * fade * flicker * gain);
                        stamp(c, core, x, y, d.r * 1.8f, d.a * fade * gain);
                    }
                    break;
                }
                case D_STARS:
                    paintStars(c);
                    break;
                case D_PETALS: {
                    int[] pal = kd.palette != null ? kd.palette : PETAL;
                    for (int n = 0; n < 4; n++) tintBmps[n] = petal(n == 3 ? mix(pal[n], accent, 0.4f) : pal[n]);
                    over();
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        // the petal turning edge-on
                        float flat = 0.35f + 0.65f * Math.abs(oscCos(700, d.tw));
                        float cos = (float) Math.cos(d.rot), sin = (float) Math.sin(d.rot);
                        float x = X(d), y = Y(d);
                        if (x < -d.r * 2 || x > clipW + d.r * 2 || y < -d.r * 2 || y > clipH + d.r * 2) continue;
                        // the JS transform (cos, sin, -sin*flat, cos*flat, x, y), then the
                        // 32 x 64 sprite drawn into (-r/2, -r, r, 2r)
                        float a = cos, b = sin, cc = -sin * flat, dd = cos * flat, s = d.r / 32f;
                        mv[0] = a * s;
                        mv[1] = cc * s;
                        mv[2] = x - a * d.r * 0.5f - cc * d.r;
                        mv[3] = b * s;
                        mv[4] = dd * s;
                        mv[5] = y - b * d.r * 0.5f - dd * d.r;
                        mv[6] = 0;
                        mv[7] = 0;
                        mv[8] = 1;
                        matrix.setValues(mv);
                        cur.setAlpha(Math.round(clamp(d.a, 0f, 1f) * 255f));
                        c.drawBitmap(tintBmps[d.c & 3], matrix, cur);
                    }
                    break;
                }
                case D_RAIN: {
                    LineBuf[] thin = lineSets[0], thick = lineSets[1];
                    clearLines(thin);
                    clearLines(thick);
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        float x = X(d), y = Y(d);
                        (d.r > 22 ? thick : thin)[stepOf(d.a)].add(x, y, x - d.vx * d.r / d.vy, y - d.r);
                    }
                    strokeLines(c, thin, 0xffdce8ff, 0.9f, false, true, 1f);
                    strokeLines(c, thick, 0xffdce8ff, 1.3f, false, true, 1f);
                    break;
                }
                case D_SNOW: {
                    // the JS draws snow over the picture, not added to it
                    Bitmap img = glow(WHITE);
                    over();
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        stamp(c, img, X(d), Y(d), d.r * 2.2f, d.a * gain);
                    }
                    break;
                }
                case D_RAYS:
                    paintRays(c);
                    break;
                case D_FLIES: {
                    Bitmap img = glow(mix(0xffd9ff7a, accent, 0.25f)), core = glow(0xfffbffe0);
                    blend();
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        float s = Math.max(0f, osc(700, d.tw * 5)), blink = s * s * s, x = X(d), y = Y(d);
                        stamp(c, img, x, y, d.r * 9, d.a * blink * gain);
                        stamp(c, core, x, y, d.r * 1.6f, d.a * (0.3f + blink * 0.7f) * gain);
                    }
                    break;
                }
                case D_DUST: {
                    Bitmap img = glow(mix(0xffffd8a8, accent, 0.2f));
                    blend();
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        stamp(c, img, X(d), Y(d), d.r * 3, d.a * (0.6f + 0.4f * osc(1100, d.tw)) * gain);
                    }
                    break;
                }
                case D_BUBBLES: {
                    // a thin rim and a highlight, at the JS's six opacity steps
                    rimPaint.setColor(mix(0xffbfefff, accent, 0.3f));
                    rimPaint.setXfermode(null);
                    shinePaint.setXfermode(null);
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        float x = X(d), y = Y(d);
                        int rs = stepOf(d.a), ss = stepOf(d.a * 0.8f);
                        if (rs > 0) {
                            rimPaint.setAlpha(Math.round(rs / (STEPS - 1f) * 255f));
                            c.drawCircle(x, y, d.r, rimPaint);
                        }
                        if (ss > 0) {
                            shinePaint.setAlpha(Math.round(ss / (STEPS - 1f) * 255f));
                            c.drawCircle(x - d.r * 0.35f, y - d.r * 0.35f, Math.max(0.8f, d.r * 0.18f), shinePaint);
                        }
                    }
                    break;
                }
                case D_TWINKLE: {
                    Bitmap img = glow(mix(WHITE, accent, 0.15f));
                    blend();
                    for (int i = 0; i < count; i++) {
                        Dot d = dots[i];
                        float x = FX(d);
                        if (d.shoot) {
                            linePaint.setStrokeWidth(1.5f);
                            linePaint.setStrokeCap(Paint.Cap.BUTT);
                            linePaint.setXfermode(bright ? null : ADD_MODE);
                            linePaint.setColor(WHITE);
                            linePaint.setAlpha(Math.round(Math.min(1f, d.life / 20f) * 255f));
                            c.drawLine(x, d.y, x - d.vx * 12, d.y - d.vy * 12, linePaint);
                            continue;
                        }
                        float tw = 0.55f + 0.45f * osc(1000f / d.sp, d.tw);
                        stamp(c, img, x, d.y, d.r * 4, d.a * tw * gain);
                    }
                    break;
                }
                case D_GLITTER:
                    paintGlitter(c);
                    break;
                case D_LANTERNS:
                    paintLanterns(c);
                    break;
                case D_WISPS:
                    paintWisps(c);
                    break;
                case D_GALAXY:
                    paintGalaxy(c);
                    break;
                default:
                    break;
            }
        }

        // drifting stars joined by faint lines, and to the finger
        void paintStars(Canvas c) {
            LineBuf[] lines = lineSets[0];
            clearLines(lines);
            float reach = 165, hand = 200, r2 = reach * reach, h2 = hand * hand;
            for (int i = 0; i < count; i++) {
                Dot a = dots[i];
                float ax = FX(a), ay = a.y;
                for (int j = i + 1; j < count; j++) {
                    Dot b = dots[j];
                    float bx = FX(b), dx = ax - bx, dy = ay - b.y, d2 = dx * dx + dy * dy;
                    if (d2 < r2) lines[stepOf((1 - (float) Math.sqrt(d2) / reach) * 0.6f)].add(ax, ay, bx, b.y);
                }
                if (hasPointer) {
                    float dx = ax - smx, dy = ay - smy, d2 = dx * dx + dy * dy;
                    if (d2 < h2) lines[stepOf((1 - (float) Math.sqrt(d2) / hand) * 0.8f)].add(ax, ay, smx, smy);
                }
            }
            // music: the lines brighten with the bass
            strokeLines(c, lines, accent, 1.2f, false, false, lineGain);
            Bitmap img = glow(accent), white = glow(WHITE);
            blend();
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                float tw = 0.6f + 0.4f * osc(700, d.tw), x = FX(d);
                stamp(c, img, x, d.y, d.r * 6, d.a * tw * gain);
                stamp(c, white, x, d.y, d.r * 1.6f, Math.min(1f, d.a * tw * 1.4f) * gain);
            }
        }

        // beams from the top corner, slowly swinging, with dust turning in them
        void paintRays(Canvas c) {
            blend();
            int warm = mix(0xfffff2cf, accent, 0.3f);
            beams(warm);
            float len = (float) Math.hypot(W, H) * 1.1f, bx = -W * 0.05f, by = -H * 0.1f;
            for (int n = 0; n < 5; n++) {
                float swing = osc(6000, n * 1.7f) * 0.05f;
                float ang = 0.55f + n * 0.11f + swing;
                float a = 0.05f + 0.03f * osc(2500, n);
                float s = len / 512f;
                matrix.setTranslate(0, -32);
                matrix.postScale(s, s);
                matrix.postRotate((float) Math.toDegrees(ang));
                matrix.postTranslate(bx, by);
                cur.setAlpha(Math.round(Math.min(1f, a * 4 * gain) * 255f));
                c.drawBitmap(beamBmps[n % 2], matrix, cur);
            }
            Bitmap img = glow(0xfffff4d6);
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                stamp(c, img, X(d), Y(d), d.r * 2.5f, d.a * (0.5f + 0.5f * osc(800, d.tw)) * gain);
            }
        }

        // tiny sparkles falling slowly, each flashing into a four-point star
        void paintGlitter(Canvas c) {
            tintCols[0] = WHITE;
            tintCols[1] = mix(0xffffd6f5, accent, 0.3f);
            tintCols[2] = mix(0xffc9f1ff, accent, 0.3f);
            tintCols[3] = accent;
            for (int n = 0; n < 4; n++) {
                tintBmps[n] = glow(tintCols[n]);
                clearLines(lineSets[n]);
            }
            blend();
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                float s = Math.max(0f, osc(1000f / d.sp, d.tw)), s2 = s * s, flash = s2 * s2 * s2;
                float x = X(d), y = Y(d);
                stamp(c, tintBmps[d.c & 3], x, y, d.r * (2 + flash * 4), d.a * (0.35f + flash * 0.65f) * gain);
                if (flash > 0.25f) {
                    float len = d.r * (3 + flash * 7);
                    LineBuf b = lineSets[d.c & 3][stepOf(d.a * flash)];
                    b.add(x - len, y, x + len, y);
                    b.add(x, y - len, x, y + len);
                }
            }
            for (int n = 0; n < 4; n++) strokeLines(c, lineSets[n], tintCols[n], 1f, true, false, 1f);
        }

        // warm paper lanterns rising and swaying, their light breathing
        void paintLanterns(Canvas c) {
            Bitmap glowImg = glow(mix(0xffffb45c, accent, 0.2f)), core = glow(0xfffff1c9), body = lantern();
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                float breathe = 0.8f + 0.2f * osc(900, d.tw), x = X(d), y = Y(d);
                blend();
                stamp(c, glowImg, x, y, d.r * 4.5f, d.a * 0.55f * breathe * gain);
                over();
                float tilt = osc(1600, d.tw) * 0.08f, cos = (float) Math.cos(tilt), sin = (float) Math.sin(tilt);
                float s = d.r / 32f, a = cos * s, b = sin * s, cc = -sin * s, dd = cos * s;
                // the JS transform (a, b, cc, dd, x, y), body drawn at (-32, -48)
                mv[0] = a;
                mv[1] = cc;
                mv[2] = x - 32 * a - 48 * cc;
                mv[3] = b;
                mv[4] = dd;
                mv[5] = y - 32 * b - 48 * dd;
                mv[6] = 0;
                mv[7] = 0;
                mv[8] = 1;
                matrix.setValues(mv);
                cur.setAlpha(Math.round(clamp(d.a * breathe, 0f, 1f) * 255f));
                c.drawBitmap(body, matrix, cur);
                blend();
                stamp(c, core, x, y, d.r * 1.3f, d.a * breathe * gain);
            }
        }

        // spirit lights wandering on a slow current, trailing light
        void paintWisps(Canvas c) {
            Bitmap t0 = glow(accent), t1 = glow(mix(0xff9ffcff, accent, 0.35f)), core = glow(WHITE);
            blend();
            float sx = (ox + pageOx), sy = oy;
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                Bitmap img = d.c == 0 ? t0 : t1;
                float pulse = 0.75f + 0.25f * osc(600, d.tw);
                for (int n = 5; n >= 1; n--) {
                    float tx = d.trail[n * 2], ty = d.trail[n * 2 + 1];
                    if (Float.isNaN(tx)) continue; // not filled yet
                    float fade = 1 - n / 6f;
                    float px = W * 0.5f + (tx - W * 0.5f) * stretch + sx * d.z;
                    stamp(c, img, px, ty + sy * d.z, d.r * (2.5f + 3.5f * fade), d.a * 0.55f * fade * pulse * gain);
                }
                float x = X(d), y = Y(d);
                stamp(c, img, x, y, d.r * 8, Math.min(1f, d.a * 0.9f * pulse) * gain);
                stamp(c, core, x, y, d.r * 1.4f, d.a * pulse * gain);
            }
        }

        // a slowly turning two-armed spiral of stars, tilted, round a bright core
        void paintGalaxy(Canvas c) {
            float cx = W * 0.5f + ox * 2 + pageOx * FLAT_PAGE, cy = H * 0.46f + oy * 2;
            float sz = Math.min(W, H) * 0.62f * (float) Math.sqrt(size);
            Bitmap g0 = glow(0xfffff3dd), g1 = glow(accent), g2 = glow(mix(0xffff9ad5, accent, 0.3f));
            float tilt = -0.38f, ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
            blend();
            stamp(c, g0, cx, cy, sz * 0.28f, 0.24f * gain);
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                float ex = (float) Math.cos(d.th) * d.R * sz;
                float ey = ((float) Math.sin(d.th) * d.R * 0.42f + d.lift) * sz;
                float x = cx + ex * ct - ey * st, y = cy + ex * st + ey * ct;
                float tw = 0.6f + 0.4f * osc(900, d.tw);
                stamp(c, d.c == 0 ? g0 : d.c == 1 ? g1 : g2, x, y, d.r * 4.2f, Math.min(1f, d.a * tw * 1.2f) * gain);
            }
        }

        // ---- the widget loop: a fixed set, each on a closed path ----

        void buildLoop() {
            rnd.setSeed(LOOP_SEED + kindIndex * 7919L);
            ox = oy = pageOx = 0;
            stretch = stretchFlat = 1f;
            hasPointer = false;
            Kind kd = spec();
            int n = want();
            if (kd.draw == D_GALAXY) n &= ~1; // pairs, half a turn apart
            grow(n);
            count = n;
            int born = kd.draw == D_GALAXY ? n / 2 : n;
            for (int i = 0; i < born; i++) {
                Dot d = dots[i];
                spawn(d, true);
                d.shoot = false;
                d.x0 = d.x;
                d.y0 = d.y;
                d.th0 = d.th;
                d.rot0 = d.rot;
                d.n = kd.draw == D_RAIN ? 2 : 1;
                // whole turns of spin over a nominal loop, as the live spin would turn
                d.turns = Math.round(d.spin * 60f * (LOOP_MS / 1000f) / TAU);
                d.amp = 0;
                switch (kd.draw) {
                    case D_EMBERS:
                    case D_GLITTER:
                        d.amp = 6;
                        break;
                    case D_PETALS:
                    case D_BUBBLES:
                    case D_LANTERNS:
                        d.amp = 10;
                        break;
                    case D_SNOW:
                        d.amp = 14;
                        break;
                    case D_STARS:
                        d.amp = 10;
                        break;
                    case D_RAYS:
                    case D_DUST:
                    case D_MOTES:
                        d.amp = 12;
                        break;
                    case D_FLIES:
                        d.amp = 28;
                        break;
                    case D_WISPS:
                        d.amp = 60;
                        break;
                    default:
                        break;
                }
            }
            if (kd.draw == D_GALAXY) {
                // the second half is the first turned half a turn, so turning the
                // whole by half a turn over the loop lands on the same picture
                for (int i = born; i < n; i++) {
                    Dot d = dots[i];
                    d.copyFrom(dots[i - born]);
                    d.th0 = d.th + (float) Math.PI;
                    d.lift = -d.lift;
                }
            }
        }

        /** Every particle's place at this phase: phase 1 is the same as phase 0. */
        void place() {
            Kind kd = spec();
            float p = phase, m = 40f, w = W, h = H;
            for (int i = 0; i < count; i++) {
                Dot d = dots[i];
                float dir = d.tw > Math.PI ? -1f : 1f;
                switch (kd.draw) {
                    case D_MOTES:
                    case D_EMBERS:
                    case D_BUBBLES:
                    case D_LANTERNS:
                        // rising: a whole trip round the canvas per loop, along its own slant
                        driftY(d, -1f, p, m, 0.5f);
                        if (kd.draw == D_EMBERS) d.life = 0.15f + 0.85f * (d.y + m) / (h + 2 * m);
                        break;
                    case D_PETALS:
                    case D_RAIN:
                    case D_SNOW:
                    case D_GLITTER:
                        driftY(d, 1f, p, m, 1.5f);
                        d.rot = d.rot0 + TAU * p * d.turns;
                        break;
                    case D_DUST: {
                        float x = wrap(d.x0 + (w + 2 * m) * p, -m, w + m);
                        float slope = Math.abs(d.vx) > 1e-3f ? clamp(d.vy / d.vx, -0.5f, 0.5f) : 0f;
                        d.x = x;
                        d.y = wrap(d.y0 + slope * (x - d.x0) + d.amp * (float) Math.sin(TAU * p + d.tw), -m, h + m);
                        break;
                    }
                    case D_STARS:
                    case D_FLIES:
                    case D_RAYS:
                        // wanderers: a small ellipse round where they were born
                        d.x = d.x0 + d.amp * (float) Math.sin(dir * TAU * p + d.tw);
                        d.y = d.y0 + d.amp * 0.7f * (float) Math.cos(dir * TAU * p + d.tw * 1.7f);
                        break;
                    case D_WISPS:
                        // a figure of eight, the trail the same path a moment behind
                        for (int n = 5; n >= 0; n--) {
                            float q = p - n * 0.035f;
                            d.trail[n * 2] = d.x0 + d.amp * (float) Math.sin(dir * TAU * q + d.tw);
                            d.trail[n * 2 + 1] = d.y0 + d.amp * 0.6f * (float) Math.sin(2 * dir * TAU * q + d.tw * 1.3f);
                        }
                        d.x = d.trail[0];
                        d.y = d.trail[1];
                        break;
                    case D_GALAXY:
                        d.th = d.th0 + (float) Math.PI * p;
                        break;
                    default:
                        break; // still stars stay put
                }
            }
        }

        /**
         * Up (dir -1) or down (dir 1) through the whole canvas and its margin,
         * d.n times a loop; sideways along its own slant (x tied to the wrapped y,
         * so it jumps only as it wraps, like a particle born again) plus a sway.
         */
        void driftY(Dot d, float dir, float p, float m, float maxSlope) {
            float span = H + 2 * m;
            float y = wrap(d.y0 + dir * d.n * span * p, -m, H + m);
            float slope = Math.abs(d.vy) > 1e-3f ? clamp(d.vx / d.vy, -maxSlope, maxSlope) : 0f;
            float x = d.x0 + slope * (y - d.y0) + d.amp * (float) Math.sin(TAU * p + d.tw);
            d.x = wrap(x, -m, W + m);
            d.y = y;
        }
    }
}
