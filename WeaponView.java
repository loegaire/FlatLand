import java.awt.*;
import java.awt.image.BufferedImage;

public class WeaponView {
    static final Image SWORD = SpriteAnim.load("assets/dungeon/weapon_regular_sword.png");
    static final Image STAFF = SpriteAnim.load("assets/dungeon/weapon_red_magic_staff.png");
    static final Image ARROW = SpriteAnim.load("assets/dungeon/weapon_arrow.png");
    static final Image BOW = SpriteAnim.load("assets/dungeon/weapon_bow.png");
    static final Image FLASK_RED = SpriteAnim.load("assets/dungeon/flask_red.png");
    static final Image FLASK_BLUE = SpriteAnim.load("assets/dungeon/flask_blue.png");

    // Bow: held PERPENDICULAR to the aim, string toward the archer, pivot at
    // the grip (rot = angle maps the sprite's string side [-x] onto -aim).
    // The nocked arrow lives in its own frame (rot = angle + PI/2, sprite tip
    // along aim) and slides back toward the archer during the draw.
    private static void drawBow(Graphics g, double hx, double hy, double angle,
                                 double swing01, double scale, Image img) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.translate(hx, hy);
        g2.rotate(angle);
        int w = img.getWidth(null), h = img.getHeight(null);
        int dw = (int) Math.round(w * scale), dh = (int) Math.round(h * scale);
        // grip pivot: image coords (10.5, 13) of the 14x26 sprite
        double gripX = 10.5 / 14.0, gripY = 13.0 / 26.0;
        // slight recoil after release
        double draw01 = Math.max(0, Math.min(1, swing01 / 0.3));
        double pull = draw01 * 7.0 * scale; // string hand moves toward archer
        if (swing01 >= 0.3 && swing01 < 0.55) {
            double t = (swing01 - 0.3) / 0.25;
            g2.translate(0, -(1 - t) * 1.5 * scale); // tiny kick along -string
        }
        g2.drawImage(img, (int) Math.round(-gripX * dw), (int) Math.round(-gripY * dh), dw, dh, null);
        if (swing01 < 0.3) { // arrow nocked: own frame, tip along aim, slides back
            Graphics2D g3 = (Graphics2D) g2.create();
            g3.rotate(Math.PI / 2); // from bow frame to arrow-along-aim frame
            int aw = (int) Math.round(ARROW.getWidth(null) * scale * 0.9);
            int ah = (int) Math.round(ARROW.getHeight(null) * scale * 0.9);
            // nock point: string line (x ≈ 7/14 of sprite) at grip height, pulled back
            double nockY = pull;
            g3.drawImage(ARROW, -aw / 2, (int) Math.round(-ah * 0.62 + nockY), aw, ah, null);
            g3.dispose();
        }
        g2.dispose();
    }

    // GENERIC item draw: any pack sprite (spear/katana/knife/food/flask...)
    // held with a bottom-pivot at the hand, aimed along `angle`, swinging
    // -1.2..+1.2 rad across the attack window (swing01 in 0..1; 1 = at rest).
    // This is the same animation every entity uses for its held item.
    public static void drawItem(Graphics g, Image img, double hx, double hy,
                                double angle, double swing01, double scale) {
        if (img == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.translate(hx, hy);
        g2.rotate(angle + Math.PI / 2.0);
        double swing = 0;
        double t = Math.min(Math.max(swing01, 0), 1);
        if (t < 1.0) swing = -1.2 + 2.4 * t;
        g2.rotate(swing);
        int w = img.getWidth(null), h = img.getHeight(null);
        int dw = (int) Math.round(w * scale), dh = (int) Math.round(h * scale);
        if (img instanceof BufferedImage) { // pivot at opaque bottom (the grip)
            BufferedImage bi = (BufferedImage) img;
            int bottom = 0;
            for (int y = h - 1; y >= 0; y--) {
                boolean content = false;
                for (int x = 0; x < w; x++) {
                    if ((bi.getRGB(x, y) >>> 24) > 10) { content = true; break; }
                }
                if (content) { bottom = y + 1; break; }
            }
            g2.drawImage(img, -dw / 2, -(int) Math.round(bottom * scale), dw, dh, null);
        } else {
            g2.drawImage(img, -dw / 2, -dh, dw, dh, null);
        }
        g2.dispose();
    }

    // kind: 0 sword, 1 staff, 2 bow, 3 flask red, 4 flask blue
    // angle: radians, 0 = pointing right; swing01: attack progress (0 start, 1 done)
    public static void draw(Graphics g, int kind, double hx, double hy, double angle, double swing01, double scale) {
        Image img;
        switch (kind) {
            case 0: img = SWORD; break;
            case 1: img = STAFF; break;
            case 2: img = BOW; break;
            case 3: img = FLASK_RED; break;
            default: img = FLASK_BLUE; break;
        }
        if (img == null) return;
        double rot = angle + Math.PI / 2.0;
        double swing = 0;
        if (kind == 0 && swing01 < 1.0) {
            swing = -1.2 + 2.4 * Math.min(Math.max(swing01, 0), 1);
        }
        if (kind == 1 && swing01 < 0.35) {
            rot -= (0.35 - swing01) * 1.2; // staff recoil flick
        }
        if (kind == 2) {
            drawBow(g, hx, hy, angle, swing01, scale, img);
            return;
        }
        if (kind >= 3) { // held flask: idle bob + sway; during the drink window it
            // rises from the hand to overhead (raised-hands pose) and tips up to
            // drink, then lowers back. rise matches the arms-up pose hand height.
            double t = System.currentTimeMillis() / 1000.0;
            rot = Math.sin(t * 2.1) * 0.14;
            hy += Math.sin(t * 2.6) * 2.0;
            if (swing01 < 0.5) {
                double d01 = Math.max(0, Math.min(1, swing01 / 0.25)); // 0..1 raise
                hy -= d01 * 23.0 * scale;   // hand -> overhead hands (arms-up pose)
                rot = d01 * 0.3;            // slight tipsy tilt while gulping
            } else if (swing01 < 1.0) {
                double back = Math.min(1, (swing01 - 0.5) / 0.35);    // lower back down
                hy -= (1 - back) * 23.0 * scale;
                rot = (1 - back) * 0.3;
            }
        }        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.translate(hx, hy);
        g2.rotate(rot + swing);
        int w = img.getWidth(null), h = img.getHeight(null);
        int dw = (int) Math.round(w * scale), dh = (int) Math.round(h * scale);
        if (img instanceof BufferedImage) { // pivot at opaque handle (bottom of content)
            BufferedImage bi = (BufferedImage) img;
            int bottom = 0;
            for (int y = h - 1; y >= 0; y--) {
                boolean content = false;
                for (int x = 0; x < w; x++) {
                    if ((bi.getRGB(x, y) >>> 24) > 10) { content = true; break; }
                }
                if (content) { bottom = y + 1; break; }
            }
            g2.drawImage(img, -dw / 2, -(int) Math.round(bottom * scale), dw, dh, null);
            if (kind == 1) { // arrow nocked on the staff, slides toward hand during recoil
                double pull = swing01 < 0.35 ? (0.35 - swing01) / 0.35 * 4.0 * scale : 0;
                int aw = (int) Math.round(ARROW.getWidth(null) * scale * 0.9);
                int ah = (int) Math.round(ARROW.getHeight(null) * scale * 0.9);
                g2.drawImage(ARROW, -aw / 2, (int) Math.round(-dh * 0.55 + pull - ah / 2.0), aw, ah, null);
            }
        } else {
            g2.drawImage(img, -dw / 2, -dh, dw, dh, null);
        }
        g2.dispose();
    }
}
