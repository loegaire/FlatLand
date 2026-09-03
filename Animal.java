import java.awt.*;
import java.util.List;
import java.util.Random;

// Cute Fantasy animal critters (chicken/cow/pig/sheep): wander, flee the
// player when they get close, wade water with ripples like everything else.
// Art: 64x64 sheet, 32px cells (2x2): r0c0/r0c1 = idle-ish, r1c0/r1c1 = walk
public class Animal {
    int x, y, size;
    final String kind; // "chicken", "cow", "pig", "sheep"
    boolean dead = false;
    private static final Random R = new Random();
    // 2x2 cell sheet: [0]=idle frames (r0), [1]=move frames (r1)
    private final SheetAnim animIdle, animMove;
    private boolean moving = false;
    private boolean facingLeft = false;
    private int[] dir = {0, 0};
    private long nextThink = 0;
    private long fleeUntil = 0;
    boolean wasInWater = false; // panel-driven water state
    // flee vector when startled
    private double fx = 0, fy = 0;

    Animal(int x, int y, int size, String kind) {
        this.x = x; this.y = y; this.size = size; this.kind = kind;
        String file = "assets/cute/" + kind.substring(0,1).toUpperCase() + kind.substring(1) + ".png";
        this.animIdle = new SheetAnim(file, 32, 32, 0, 2, 420);
        this.animMove = new SheetAnim(file, 32, 32, 1, 2, 220);
    }

    void update(Player player, List<Obstacle> obstacles) {
        long now = System.currentTimeMillis();
        double d = Math.hypot(player.x - x, player.y - y);
        // startle: run away from the player
        if (d < 70) {
            double ux = x - player.x, uy = y - player.y;
            double len = Math.max(1, Math.hypot(ux, uy));
            fx = ux / len; fy = uy / len;
            fleeUntil = now + 900;
        }
        if (now < fleeUntil) {
            moving = true;
            facingLeft = fx < 0;
            int ox = x, oy = y;
            x += Math.round(fx * 2); y += Math.round(fy * 2);
            for (Obstacle ob : obstacles) {
                if (ob.blocks() && ob.getBounds().intersects(getBounds())) { x = ox; y = oy; break; }
            }
        } else if (now > nextThink) {
            nextThink = now + 1800 + R.nextInt(2500);
            if (R.nextInt(3) == 0) { dir[0] = 0; dir[1] = 0; }
            else { dir[0] = R.nextInt(3) - 1; dir[1] = R.nextInt(3) - 1; }
        }
        if (now >= fleeUntil) {
            moving = dir[0] != 0 || dir[1] != 0;
            facingLeft = dir[0] < 0 || (dir[0] == 0 && facingLeft);
            int ox = x, oy = y;
            x += dir[0]; y += dir[1];
            for (Obstacle ob : obstacles) {
                if (ob.blocks() && ob.getBounds().intersects(getBounds())) { x = ox; y = oy; moving = false; break; }
            }
        }
    }

    public Rectangle getBounds() {
        return new Rectangle((int) (x + size * 0.15), (int) (y + size * 0.15),
            (int) (size - size * 0.3), (int) (size - size * 0.3));
    }

    public void draw(Graphics g, int cameraX, int cameraY) {
        SheetAnim a = moving ? animMove : animIdle;
        double zoom = 2.0 * size / 30.0 * 0.8; // animals drawn a touch smaller
        a.draw(g, x - cameraX + size / 2, y - cameraY + size, zoom, facingLeft);
    }
}
