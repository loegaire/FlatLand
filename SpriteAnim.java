import java.awt.*;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class SpriteAnim {
    private static final Map<String, Image> CACHE = new HashMap<>();
    private static File BASE_DIR = null; // dir containing this .class file (works from any CWD)
    public static int loadedCount = 0;
    public static int failedCount = 0;

    static {
        try {
            File loc = new File(SpriteAnim.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            BASE_DIR = loc.isDirectory() ? loc : loc.getParentFile();
        } catch (Exception e) {
            BASE_DIR = null;
        }
    }

    static File resolve(String path) {
        File f = new File(path);
        if (f.exists()) return f;
        if (BASE_DIR != null) {
            File f2 = new File(BASE_DIR, path);
            if (f2.exists()) return f2;
        }
        return f; // caller handles failure via null image
    }

    public static Image load(String path) {
        synchronized (CACHE) {
            Image img = CACHE.get(path);
            if (img == null && !CACHE.containsKey(path)) {
                try {
                    img = ImageIO.read(resolve(path));
                } catch (Exception e) {
                    img = null;
                }
                if (img != null) loadedCount++; else failedCount++;
                CACHE.put(path, img);
            }
            return img;
        }
    }
    Image[] idle;
    Image[] run;
    int frame = 0;
    long lastFlip = 0;
    private long idleMs = 150;
    private long runMs = 90;

    // per-instance animation pacing (NPCs idling slower/faster)
    public SpriteAnim setIdleMs(long ms) { this.idleMs = ms; return this; }
    public SpriteAnim setRunMs(long ms) { this.runMs = ms; return this; }

    public SpriteAnim(String base, int idleCount, int runCount) {
        idle = new Image[idleCount];
        for (int i = 0; i < idleCount; i++)
            idle[i] = load("assets/dungeon/" + base + "_idle_anim_f" + i + ".png");
        if (runCount > 0) {
            run = new Image[runCount];
            for (int i = 0; i < runCount; i++)
                run[i] = load("assets/dungeon/" + base + "_run_anim_f" + i + ".png");
        } else {
            run = idle;
        }
    }

    private SpriteAnim(Image[] idle, Image[] run) {
        this.idle = idle;
        this.run = run;
    }

    public static SpriteAnim single(String file) {
        Image[] a = new Image[]{load("assets/dungeon/" + file)};
        return new SpriteAnim(a, a);
    }

    // cached shared animator per base kind (goblin, orc_shaman, ...).
    // Kind instances draw from it with a SHARED clock -- every goblin on
    // screen animates in lockstep (cheap + visually consistent).
    private static final java.util.Map<String, SpriteAnim> KINDS = new java.util.HashMap<>();
    public static SpriteAnim kind(String base) {
        synchronized (KINDS) {
            SpriteAnim a = KINDS.get(base);
            if (a == null) KINDS.put(base, a = new SpriteAnim(base, 4, 4));
            return a;
        }
    }

    public static void drawKind(Graphics g, String base, int cx, int by,
                                int drawWidth, boolean flip, boolean moving) {
        SpriteAnim a = kind(base);
        Image[] frames = moving ? a.run : a.idle; // direct field access (same file)
        long now = System.currentTimeMillis();
        long dur = moving ? 110 : 150;
        if (now - a.lastFlip >= dur) {
            a.frame = (a.frame + 1) % frames.length;
            a.lastFlip = now;
        }
        Image img = frames[a.frame % frames.length];
        if (img == null) {
            g.setColor(Color.LIGHT_GRAY);
            g.fillOval(cx - drawWidth / 2, by - drawWidth, drawWidth, drawWidth);
            return;
        }
        int fw = img.getWidth(null), fh = img.getHeight(null);
        double scale = drawWidth / 16.0;
        int w = (int) Math.round(fw * scale), h = (int) Math.round(fh * scale);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int x0 = cx - w / 2, y0 = by - h;
        if (flip) g2.drawImage(img, x0 + w, y0, -w, h, null);
        else g2.drawImage(img, x0, y0, w, h, null);
        g2.dispose();
    }

    public void draw(Graphics g, int anchorCenterX, int anchorBottomY, int drawWidth, boolean flip, boolean moving) {
        Image[] frames = moving ? run : idle;
        Image img = frames[frame % frames.length];
        long now = System.currentTimeMillis();
        long dur = moving ? runMs : idleMs;
        if (now - lastFlip >= dur) {
            frame = (frame + 1) % frames.length;
            lastFlip = now;
        }
        if (img == null) {
            g.setColor(Color.LIGHT_GRAY);
            g.fillOval(anchorCenterX - drawWidth / 2, anchorBottomY - drawWidth, drawWidth, drawWidth);
            return;
        }
        int fw = img.getWidth(null), fh = img.getHeight(null);
        double scale = drawWidth / 16.0;
        int w = (int) Math.round(fw * scale);
        int h = (int) Math.round(fh * scale);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int x0 = anchorCenterX - w / 2;
        int y0 = anchorBottomY - h;
        if (flip) {
            g2.drawImage(img, x0 + w, y0, -w, h, null);
        } else {
            g2.drawImage(img, x0, y0, w, h, null);
        }
        g2.dispose();
    }
}
