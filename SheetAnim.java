import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SheetAnim {
    // Player_Actions.png tool palette (steel + wood). These 6 colors never
    // appear on the player body (11 body colors, verified disjoint), so frames
    // can be filtered to show the body pose with the baked-in tool erased.
    private static final Set<Integer> TOOL_COLORS = new HashSet<>(Arrays.asList(
        0xFFC0CBDC, 0xFF8B9BB4, 0xFF5A6988,   // steel: 192,203,220 / 139,155,180 / 90,105,136
        0xFF743F39, 0xFFB86F50, 0xFFE4A672    // wood:  116,63,57 / 184,111,80 / 228,166,114
    ));

    private final Image sheet;
    private final int cw, ch, row, count;
    private final double padBottom;
    private final long frameMs;
    private int frame = 0;
    private long lastFlip = 0;

    public SheetAnim(String file, int cw, int ch, int row, int count, long frameMs) {
        this(file, cw, ch, row, count, frameMs, false);
    }

    // toolFilter: erase baked-in tool pixels (used for the potion-drink pose,
    // so the raised flask drawn by WeaponView replaces the erased tool)
    public SheetAnim(String file, int cw, int ch, int row, int count, long frameMs, boolean toolFilter) {
        Image img = SpriteAnim.load(file);
        if (toolFilter && img instanceof BufferedImage) {
            img = stripToolColors((BufferedImage) img, row, count, cw, ch);
        }
        this.sheet = img;
        this.cw = cw;
        this.ch = ch;
        this.row = row;
        this.count = count;
        this.frameMs = frameMs;
        this.padBottom = detectPad();
    }

    private static BufferedImage stripToolColors(BufferedImage src, int row, int count, int cw, int ch) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int y0 = row * ch;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int argb = src.getRGB(x, y);
                boolean inStrip = y >= y0 && y < y0 + ch && x < count * cw;
                boolean erase = inStrip && TOOL_COLORS.contains(argb);
                out.setRGB(x, y, erase ? 0x00000000 : argb);
            }
        }
        return out;
    }

    private double detectPad() {
        try {
            if (!(sheet instanceof BufferedImage)) return 0;
            BufferedImage bi = (BufferedImage) sheet;
            int y0 = row * ch;
            int maxBottom = -1;
            for (int c = 0; c < count; c++) {
                int x0 = c * cw;
                for (int y = y0 + ch - 1; y >= y0; y--) {
                    boolean content = false;
                    for (int x = x0; x < x0 + cw && x < bi.getWidth(); x++) {
                        if ((bi.getRGB(x, y) >>> 24) > 10) {
                            content = true;
                            break;
                        }
                    }
                    if (content) {
                        maxBottom = Math.max(maxBottom, y - y0 + 1);
                        break;
                    }
                }
            }
            return maxBottom >= 0 ? ch - maxBottom : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // zoom = screen pixels per art pixel (tiles are 16px art drawn at 32px => zoom 2)
    public void draw(Graphics g, int anchorCenterX, int anchorBottomY, double zoom, boolean flip) {
        if (sheet == null) {
            g.setColor(Color.LIGHT_GRAY);
            g.fillOval(anchorCenterX - (int) (16 * zoom), anchorBottomY - (int) (32 * zoom), (int) (32 * zoom), (int) (32 * zoom));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFlip >= frameMs) {
            frame = (frame + 1) % count;
            lastFlip = now;
        }
        drawFrame(g, frame % count, anchorCenterX, anchorBottomY, zoom, flip, -1);
    }

    // draw one explicit frame column; used for attack/bow sequences that are
    // clocked by the player rather than auto-advanced. padOverride >= 0 pins the
    // feet anchor (slash arcs reach the frame bottom, which would otherwise lift
    // the body off the ground); padOverride < 0 keeps the detected pad.
    public void drawFrame(Graphics g, int col, int anchorCenterX, int anchorBottomY,
                          double zoom, boolean flip, int padOverride) {
        if (sheet == null) return;
        double pad = padOverride >= 0 ? padOverride : padBottom;
        int w = (int) Math.round(cw * zoom);
        int h = (int) Math.round(ch * zoom);
        int x0 = (int) Math.round(anchorCenterX - (cw / 2.0) * zoom);
        int y0 = (int) Math.round(anchorBottomY - (ch - pad) * zoom);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int sx = col * cw, sy = row * ch;
        if (flip) {
            g2.drawImage(sheet, x0 + w, y0, x0, y0 + h, sx, sy, sx + cw, sy + ch, null);
        } else {
            g2.drawImage(sheet, x0, y0, x0 + w, y0 + h, sx, sy, sx + cw, sy + ch, null);
        }
        g2.dispose();
    }
}
