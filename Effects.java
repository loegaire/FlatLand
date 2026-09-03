import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// Particle/effect system using 8-bit style SPRITE particles (assets/fx/),
// drawn with nearest-neighbour scaling -- no plain circles or squares.
//
// Layers:
//   drawUnder (floor level): FOOT traces, RIPPLE water rings
//   drawOver  (above entities): DROP/DEBRIS/SPARK/HIT/SLASH/POP + weather
// Weather particles (RAIN/SNOWFALL/WINDBLOW/FOG) are SCREEN-SPACE: spawned
// with view dimensions, never camera-shifted (that bug sent them off-screen).
public class Effects {
    static final int FOOT = 0, RIPPLE = 1, DROP = 2, DEBRIS = 3, SPARK = 4,
                     HIT = 5, POP = 6, SLASH = 7, RAIN = 8, SNOWFALL = 9,
                     WINDBLOW = 10, FOG = 11;
    static final double G = 260.0; // px/s^2 ballistic

    static final class Effect {
        final int type;
        final long t0, life;
        final double x, y, size;
        final Image img;
        final Color col;
        final double[] vx, vy, px, py, rot;
        final boolean flip;
        final boolean screenSpace; // weather overlays: coords are screen coords
        Effect(int type, long t0, long life, double x, double y, double size,
               Image img, Color col, double[] vx, double[] vy, double[] px, double[] py,
               double[] rot, boolean flip, boolean screenSpace) {
            this.type = type; this.t0 = t0; this.life = life; this.x = x; this.y = y;
            this.size = size; this.img = img; this.col = col;
            this.vx = vx; this.vy = vy; this.px = px; this.py = py; this.rot = rot;
            this.flip = flip; this.screenSpace = screenSpace;
        }
    }

    static final List<Effect> FX = new CopyOnWriteArrayList<>();

    // ---- sprites ----
    static final Image HIT_STAR = SpriteAnim.load("assets/fx/hit_star.png");
    static final Image PUFF = SpriteAnim.load("assets/fx/puff.png");
    static final Image DROP_SPR = SpriteAnim.load("assets/fx/drop.png");
    static final Image LEAF_SPR = SpriteAnim.load("assets/fx/leaf.png");
    static final Image GRASS_SPR = SpriteAnim.load("assets/fx/grass.png");
    static final Image SPARK_SPR = SpriteAnim.load("assets/fx/spark.png");
    static final Image SLASH_SPR = SpriteAnim.load("assets/fx/slash.png");
    static final Image SNOW_SPR = SpriteAnim.load("assets/fx/snow.png");
    static final Image RAIN_SPR = SpriteAnim.load("assets/fx/rain.png");
    static final Image[] FOG_SPR = {
        SpriteAnim.load("assets/fx/fog0.png"),
        SpriteAnim.load("assets/fx/fog1.png"),
        SpriteAnim.load("assets/fx/fog2.png"),
    };

    // ---- tints (fallbacks if a sprite is missing) ----
    static final Color FOAM = new Color(245, 252, 255);
    static final Color WATER = new Color(0, 149, 233);
    static final Color WATER_D = new Color(0, 109, 168);
    static final Color GRASS_D = new Color(38, 92, 66);
    static final Color DIRT_D = new Color(143, 96, 70);
    static final Color LEAF = new Color(62, 137, 72);
    static final Color BONE = new Color(253, 247, 237);
    static final Color BLOOD = new Color(255, 80, 80);
    static final Color SLIME = new Color(94, 190, 105);
    static final Color STONE = new Color(120, 130, 150);

    private static final java.util.Random R = new java.util.Random();

    // ---- world-space spawners ----------------------------------------------

    static void splash(double x, double y, double s, boolean droplets) {
        long now = System.currentTimeMillis();
        FX.add(new Effect(RIPPLE, now, 650, x, y, 6 * s, null, FOAM, null, null, null, null, null, false, false));
        FX.add(new Effect(RIPPLE, now + 90, 650, x, y, 4 * s, null, WATER, null, null, null, null, null, false, false));
        if (!droplets) return;
        int n = 5 + R.nextInt(3);
        FX.add(new Effect(DROP, now, 520, x, y, Math.max(1.2, 2.0 * s), DROP_SPR, FOAM,
            vel(n, 60, 90), velUp(n, 30, 90), off(n, 6 * s, 3 * s), off(n, 6 * s, 3 * s), rot(n), false, false));
    }

    static void ripple(double x, double y, double s) {
        long now = System.currentTimeMillis();
        FX.add(new Effect(RIPPLE, now, 750, x, y, 5 * s, null, FOAM, null, null, null, null, null, false, false));
    }

    static void footprint(double x, double y, Color col, boolean flip) {
        long now = System.currentTimeMillis();
        FX.add(new Effect(FOOT, now, 5000, x, y, 1, null, col, null, null, null, null, null, flip, false));
    }

    // entity takes a hit: TINY but noticeable -- a few small pixel stars
    // scattershot around the impact + a couple of puff chips. No big blobs.
    static void hit(double x, double y, double s, Color col) {
        long now = System.currentTimeMillis();
        int n = 3; // three little stars, jittered
        double[] vx = new double[n], vy = new double[n], px = new double[n], py = new double[n], rt = new double[n];
        for (int i = 0; i < n; i++) {
            double a = R.nextDouble() * Math.PI * 2;
            vx[i] = Math.cos(a) * (18 + R.nextDouble() * 26);
            vy[i] = Math.sin(a) * (18 + R.nextDouble() * 26) - 12;
            px[i] = (R.nextDouble() - 0.5) * 8 * s;
            py[i] = (R.nextDouble() - 0.5) * 8 * s;
            rt[i] = R.nextDouble() * Math.PI * 2;
        }
        // TINY hit stars (75% smaller than before) + small puff chips
        FX.add(new Effect(HIT, now, 320, x, y, Math.max(0.3, 0.12 * s), HIT_STAR, col,
            vx, vy, px, py, rt, false, false));
        int m = 4; // plus small material puff chips
        FX.add(new Effect(DEBRIS, now, 380, x, y, 0.25, PUFF, col,
            vel(m, 26, 55), velUp(m, 20, 60), off(m, 6 * s, 6 * s), off(m, 1, 1), rot(m), false, false));
    }

    static void debris(double x, double y, boolean tree, int n) {
        long now = System.currentTimeMillis();
        FX.add(new Effect(DEBRIS, now, 640, x, y, tree ? 1.6 : 1.3, tree ? LEAF_SPR : GRASS_SPR, LEAF,
            vel(n, 30, 50), velUp(n, 20, 50), off(n, 8, 10), off(n, 1, 1), rot(n), false, false));
    }

    static void stonePuff(double x, double y, int n) {
        long now = System.currentTimeMillis();
        FX.add(new Effect(DEBRIS, now, 480, x, y, 1.0, PUFF, STONE,
            vel(n, 25, 45), velUp(n, 15, 40), off(n, 4, 4), off(n, 1, 1), rot(n), false, false));
    }

    static void slashGlint(double x, double y, double ang, double s) {
        long now = System.currentTimeMillis();
        double[] vx = {Math.cos(ang) * 40}, vy = {Math.sin(ang) * 40};
        double[] px = {0}, py = {0}, rot = {ang};
        FX.add(new Effect(SLASH, now, 260, x, y, 2.2 * s, SLASH_SPR, FOAM, vx, vy, px, py, rot, false, false));
    }

    static void sparkle(double x, double y, Color col, int n) {
        long now = System.currentTimeMillis();
        FX.add(new Effect(SPARK, now, 750, x, y, 1.6, SPARK_SPR, col,
            vel(n, 0, 26), velUp(n, 18, 48), off(n, 14, 6), off(n, 1, 1), rot(n), false, false));
    }

    static void pop(double x, double y, double s, Color col) {
        long now = System.currentTimeMillis();
        int n = 8;
        // MASSIVELY smaller than before: cloud roughly matches the body it came
        // from (~0.12 scale units per px of entity size), not a screen-filling blob
        FX.add(new Effect(POP, now, 460, x, y, Math.max(1.0, 0.12 * s), PUFF, col,
            vel(n, 55, 120), velUp(n, 30, 90), off(n, 1, 1), off(n, 1, 1), rot(n), false, false));
    }

    // ---- screen-space weather spawners --------------------------------------

    // rain streaks falling across the view; slant matches the drift
    static void rainBurst(int w, int h, double intensity) {
        long now = System.currentTimeMillis();
        int n = (int) (16 * intensity);
        double[] vx = new double[n], vy = new double[n], px = new double[n], py = new double[n], rt = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = R.nextInt(Math.max(1, w));
            py[i] = -R.nextInt(Math.max(1, h / 2)) - 10; // enter from above the view
            vx[i] = 30 + R.nextDouble() * 25;
            vy[i] = 330 + R.nextDouble() * 130;
            rt[i] = 0.1; // matches vx/vy slant
        }
        FX.add(new Effect(RAIN, now, 900, 0, 0, 1.0, RAIN_SPR, WATER, vx, vy, px, py, rt, false, true));
    }

    static void snowBurst(int w, int h, double intensity) {
        long now = System.currentTimeMillis();
        int n = (int) (10 * intensity);
        double[] vx = new double[n], vy = new double[n], px = new double[n], py = new double[n], rt = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = R.nextInt(Math.max(1, w));
            py[i] = -R.nextInt(Math.max(1, h / 2)) - 10;
            vx[i] = (R.nextDouble() - 0.5) * 30;
            vy[i] = 45 + R.nextDouble() * 40;
            rt[i] = R.nextDouble() * Math.PI * 2;
        }
        FX.add(new Effect(SNOWFALL, now, 2600, 0, 0, 1, SNOW_SPR, FOAM, vx, vy, px, py, rt, false, true));
    }

    // windy day: leaves blowing across the view
    static void windBurst(int w, int h, double intensity) {
        long now = System.currentTimeMillis();
        int n = (int) (6 * intensity);
        double[] vx = new double[n], vy = new double[n], px = new double[n], py = new double[n], rt = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = -20 - R.nextInt(40);
            py[i] = R.nextInt(Math.max(1, h));
            vx[i] = 90 + R.nextDouble() * 70;
            vy[i] = (R.nextDouble() - 0.5) * 20;
            rt[i] = R.nextDouble() * Math.PI * 2;
        }
        FX.add(new Effect(WINDBLOW, now, 2200, 0, 0, 1.4, LEAF_SPR, LEAF, vx, vy, px, py, rt, false, true));
    }

    // fog: rolling pixel-art fog banks drifting across the view, two depth
    // bands (big slow low banks + smaller faster high wisps)
    static void fogBurst(int w, int h, double intensity) {
        long now = System.currentTimeMillis();
        int n = Math.max(2, (int) (3 * intensity));
        for (int k = 0; k < n; k++) {
            boolean lowBand = k % 2 == 0;
            Image spr = FOG_SPR[R.nextInt(FOG_SPR.length)];
            double scale = lowBand ? 3.2 + R.nextDouble() * 1.8 : 2.0 + R.nextDouble() * 1.2;
            double y = lowBand ? h * (0.45 + R.nextDouble() * 0.3) : h * (0.12 + R.nextDouble() * 0.25);
            boolean ltr = R.nextBoolean();
            double vx = (ltr ? 1 : -1) * (lowBand ? 8 + R.nextDouble() * 8 : 16 + R.nextDouble() * 12);
            double px = ltr ? -80 - R.nextInt(120) : w + R.nextInt(120);
            double[] vxx = {vx}, vyy = {0}, pxx = {px}, pyy = {y}, rt = {0};
            long life = 9000 + R.nextInt(5000);
            FX.add(new Effect(FOG, now, life, 0, 0, scale, spr, FOAM,
                vxx, vyy, pxx, pyy, rt, false, true));
        }
    }

    // ---- helpers ----
    private static double[] vel(int n, double min, double max) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = min + R.nextDouble() * (max - min);
        return v;
    }
    private static double[] velUp(int n, double min, double max) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = -(min + R.nextDouble() * (max - min));
        return v;
    }
    private static double[] off(int n, double spreadX, double spreadY) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = (R.nextDouble() - 0.5) * spreadX;
        return v;
    }
    private static double[] rot(int n) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = R.nextDouble() * Math.PI * 2;
        return v;
    }

    static void cull(long now) {
        FX.removeIf(e -> now - e.t0 >= e.life);
    }

    // ---- rendering -----------------------------------------------------------

    static void drawUnder(Graphics g, int camX, int camY, long now) {
        for (Effect e : FX) {
            long t = now - e.t0;
            if (t < 0 || t >= e.life) continue;
            double p = (double) t / e.life;
            int sx = (int) Math.round(e.x - camX), sy = (int) Math.round(e.y - camY);
            Graphics2D g2 = (Graphics2D) g.create();
            switch (e.type) {
                case FOOT: {
                    float a = 0.5f * (1 - (float) p);
                    g2.setColor(new Color(e.col.getRed(), e.col.getGreen(), e.col.getBlue(),
                        (int) (a * 255)));
                    int ox = e.flip ? -3 : 3;
                    g2.fillOval(sx - 5 + ox, sy - 2, 4, 2);
                    g2.fillOval(sx + 1 + ox, sy + 1, 4, 2);
                    break;
                }
                case RIPPLE: {
                    float a = (1 - (float) p) * 0.55f;
                    g2.setColor(new Color(e.col.getRed(), e.col.getGreen(), e.col.getBlue(),
                        (int) (a * 255)));
                    g2.setStroke(new BasicStroke(1.4f));
                    double r = e.size * (0.4 + p * 2.2);
                    double ry = r * 0.45;
                    g2.draw(new java.awt.geom.Ellipse2D.Double(sx - r, sy - ry, r * 2, ry * 2));
                    break;
                }
            }
            g2.dispose();
        }
    }

    static void drawOver(Graphics g, int camX, int camY, long now) {
        for (Effect e : FX) {
            long t = now - e.t0;
            if (t < 0 || t >= e.life) continue;
            double ts = t / 1000.0, p = (double) t / e.life;
            int sx, sy;
            if (e.screenSpace) { sx = (int) Math.round(e.x); sy = (int) Math.round(e.y); }
            else { sx = (int) Math.round(e.x - camX); sy = (int) Math.round(e.y - camY); }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            switch (e.type) {
                case DROP: case DEBRIS: case POP: {
                    float a = 1 - (float) p;
                    for (int i = 0; i < e.vx.length; i++) {
                        double gx = sx + e.px[i] + e.vx[i] * ts;
                        double gy = sy + e.py[i] + e.vy[i] * ts + 0.9 * G * ts * ts;
                        drawSprite(g2, e.img, e.col, gx, gy,
                            e.size * (e.type == POP ? (1 - p * 0.3) : 1),
                            e.rot[i % e.rot.length], a);
                    }
                    break;
                }
                case RAIN: case SNOWFALL: case WINDBLOW: case FOG: {
                    float a = e.type == FOG ? 1f : 0.85f;
                    if (e.type == FOG) { // banks fade in and out gently
                        a = (float) (p < 0.15 ? p / 0.15 : p > 0.8 ? (1 - p) / 0.2 : 1.0);
                    }
                    for (int i = 0; i < e.vx.length; i++) {
                        double gx = sx + e.px[i] + e.vx[i] * ts;
                        double gy = sy + e.py[i] + e.vy[i] * ts;
                        if (e.type == SNOWFALL || e.type == WINDBLOW)
                            gx += Math.sin(ts * 3 + i) * 10; // flutter
                        double sc = e.size;
                        if (e.type == RAIN) sc = e.size * (1 - p * 0.2);
                        drawSprite(g2, e.img, e.col, gx, gy, sc,
                            e.rot[i % e.rot.length], a);
                    }
                    break;
                }
                case SPARK: {
                    float a = 1 - (float) p;
                    for (int i = 0; i < e.vx.length; i++) {
                        double gx = sx + e.px[i] + e.vx[i] * ts + Math.sin(ts * 6 + i) * 2;
                        double gy = sy + e.py[i] + e.vy[i] * ts;
                        drawSprite(g2, e.img, e.col, gx, gy,
                            e.size * (0.6 + 0.4 * Math.sin(ts * 12 + i)), e.rot[i], a);
                    }
                    break;
                }
                case HIT: { // tiny stars flying outward, shrinking + fading
                    float a = 1 - (float) p;
                    for (int i = 0; i < e.vx.length; i++) {
                        double gx = sx + e.px[i] + e.vx[i] * ts;
                        double gy = sy + e.py[i] + e.vy[i] * ts + 120 * ts * ts;
                        drawSprite(g2, e.img, e.col, gx, gy,
                            e.size * (1 - p * 0.45), e.rot[i], a);
                    }
                    break;
                }
                case SLASH: {
                    float a = 1 - (float) p;
                    drawSprite(g2, e.img, e.col,
                        sx + e.vx[0] * ts, sy + e.vy[0] * ts, e.size * (1 - p * 0.3),
                        e.rot[0], a);
                    break;
                }
            }
            g2.dispose();
        }
    }

    // sprite particle centred at (x,y): rotation, alpha, scale. Fallback is a
    // tiny 2px square chip (still not a circle).
    private static void drawSprite(Graphics2D g2, Image img, Color fallback,
        double x, double y, double s, double ang, float alpha) {
        if (alpha <= 0.02) return;
        if (img == null) {
            g2.setColor(fallback);
            int sz = (int) Math.max(1, Math.round(2 * s));
            g2.fillRect((int) x - sz / 2, (int) y - sz / 2, sz, sz);
            return;
        }
        int w = img.getWidth(null), h = img.getHeight(null);
        int dw = (int) Math.max(1, Math.round(w * s * 0.9));
        int dh = (int) Math.max(1, Math.round(h * s * 0.9));
        g2.translate(x, y);
        if (ang != 0) g2.rotate(ang);
        if (alpha < 1f)
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g2.drawImage(img, -dw / 2, -dh / 2, dw, dh, null);
    }
}
