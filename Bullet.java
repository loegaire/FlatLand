import java.awt.*;
import java.util.List;

// Any flying weapon: arrows glide, axes/spears SPIN mid-flight. All stick
// (ground / obstacle / enemy) then fade. Sprite + spin come from the Item.
public class Bullet {
    int x, y, size, speed = 10, dmg = 10;
    // flight envelope: after maxRange px the projectile stalls, tips down, and
    // LANDS -- sticking where it falls before fading out
    int maxRange = 320;
    double travelled = 0;
    boolean dropping = false;      // losing lift, tipping nose-down
    boolean landed = false;        // stuck in the ground/obstacle
    boolean stuckInEnemy = false; // impaled: rides the enemy's body
    Entity stuckEnemy = null;      // whom we're stuck in
    int stuckOx, stuckOy;         // offset from that enemy's top-left
    double stuckAngle = 0;        // entry angle when it impaled
    boolean dead = false;
    double[] direction = new double[]{0, 0};
    // the projectile's own art (arrow / throwing axe / spear) + spin flag
    final Image sprite;
    final boolean spins;
    private static final long LINGER_MS = 4000;   // ground-stick time
    private static final long IMPALE_MS = 10000;  // stuck-in-enemy time
    private long landTime = 0, stickTime = 0;
    int dropTicks = 0;
    // owner scale: projectiles render 1.25x and grow with the shooter
    double holderScale = 1.0;

    public Bullet(int x, int y, int size, double[] direction) {
        this(SpriteAnim.load("assets/dungeon/weapon_arrow.png"), false, x, y, size, direction);
    }
    public Bullet(Image sprite, boolean spins, int x, int y, int size, double[] direction) {
        this.sprite = sprite;
        this.spins = spins;
        this.x = x; this.y = y; this.size = size;
        this.direction[0] = direction[0];
        this.direction[1] = direction[1];
    }

    public void Attack(Player player, List<Obstacle> obstacles, java.util.List<Entity> entities) {
        if (stuckInEnemy || landed) return; // spent: no more collisions
        for (Entity en : entities) {
            if (getBounds().intersects(en.getBounds())) {
                en.hurt(dmg, x, y);
                stuckInEnemy = true;
                stuckEnemy = en;
                stuckOx = x - en.x;
                stuckOy = y - en.y;
                stuckAngle = flightAngle();
                stickTime = System.currentTimeMillis();
                return;
            }
        }
        for (Obstacle ob : obstacles) {
            if (getBounds().intersects(ob.getBounds())) {
                land();
                return;
            }
        }
        if (getBounds().intersects(player.getBounds())) {
            player.hurt(dmg, x, y);
            dead = true;
        }
    }

    private void land() {
        landed = true;
        landTime = System.currentTimeMillis();
        Effects.stonePuff(x + size / 2.0, y + size / 2.0, 2);
    }

    // current visual angle: arrows aim along flight + tip-down when stalling;
    // spinning weapons rotate continuously (about 1 rev / 300ms)
    double flightAngle() {
        double base = Math.atan2(direction[1], direction[0]);
        if (spins) return (System.currentTimeMillis() % 100000) / 300.0 * Math.PI * 2;
        double ang = base + Math.PI / 2; // sprite points up
        if (dropping) ang += Math.toRadians(55 * Math.min(1, dropTicks / 10.0));
        return ang;
    }

    public void move() {
        if (landed) return;
        if (dropping) {
            x += (int) Math.round(direction[0] * speed * 0.35);
            y += (int) Math.round(direction[1] * speed * 0.35 + 5);
            if (dropTicks > 6) land();
            dropTicks++;
            return;
        }
        x += direction[0] * speed;
        y += direction[1] * speed;
        travelled += speed;
        if (travelled >= maxRange) {
            dropping = true;
            Effects.stonePuff(x + size / 2.0, y + size / 2.0, 2);
        }
    }

    public void update(Player player, List<Obstacle> obstacles, java.util.List<Entity> entities) {
        long now = System.currentTimeMillis();
        if (stuckInEnemy) {
            if (stuckEnemy == null || stuckEnemy.dead || now - stickTime > IMPALE_MS) dead = true;
            return;
        }
        if (landed) {
            if (now - landTime > LINGER_MS) dead = true;
            return;
        }
        move();
        if (landed || stuckInEnemy) return;
        Attack(player, obstacles, entities);
    }

    public Rectangle getBounds() { return new Rectangle(x, y, size, size); }

    public void draw(Graphics g, int cameraX, int cameraY) {
        long now = System.currentTimeMillis();
        if (dead) return;
        if (stuckInEnemy) {
            if (stuckEnemy == null) return;
            float fade = 1f;
            long rem = IMPALE_MS - (now - stickTime);
            if (rem < 900) fade = Math.max(0f, rem / 900f);
            drawProjectile(g, cameraX, cameraY, stuckEnemy.x + stuckOx, stuckEnemy.y + stuckOy,
                stuckAngle, fade);
            return;
        }
        if (landed) {
            float a = 1f - (float) (now - landTime) / LINGER_MS;
            if (a <= 0) return;
            double ang = spins ? stuckAngleLand() : flightAngle() + Math.toRadians(50 * Math.min(1, dropTicks / 8.0));
            drawProjectile(g, cameraX, cameraY, x, y, ang, a);
            return;
        }
        drawProjectile(g, cameraX, cameraY, x, y, flightAngle(), 1f);
    }

    double stuckAngleLand() { // a landed spinner lies at its last rotation
        return (landTime % 100000) / 300.0 * Math.PI * 2;
    }

    private void drawProjectile(Graphics g, int cameraX, int cameraY, int wx, int wy, double ang, float alpha) {
        if (sprite == null) {
            g.setColor(Color.BLUE);
            g.fillRect(wx - cameraX, wy - cameraY, size, size);
            return;
        }
        // 1.25x base scale, grown by the holder's size (size/30 ~ 1)
        double scale = 1.25 * holderScale;
        int h = (int) Math.round(size * 3 * scale);
        int w = Math.max(2, (int) Math.round(h * (sprite.getWidth(null) / (double) sprite.getHeight(null))));
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (alpha < 1f)
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g2.translate(wx - cameraX + size / 2.0, wy - cameraY + size / 2.0);
        g2.rotate(ang);
        g2.drawImage(sprite, -w / 2, -h, w, h, null);
        g2.dispose();
    }
}
