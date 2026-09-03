import java.awt.*;
import java.util.List;

// Legacy melee weapon. use() is invoked by Player.Attack; the damage is now
// applied through the legacy Enemy path AND any Entity inside the swing rect
// (the player can strike NPCs/villagers -- they are not special).
public class Sword implements Items {
    public Rectangle LastAttack_for_Drawing = new Rectangle();
    private long LastAttackTime = 0;
    private long AttackCooldown = 500;
    int WeaponX = 0;
    int WeaponY = 0;
    double aimAngle = 0; // radians, screen coords (y-down), toward mouse
    int range = 50;
    int dmg = 20;
    public Sword() {
    }
    public String getName() {
        return "Sword";
    }
    public long lastAttackTime() {
        return LastAttackTime;
    }
    public void use(int x, int y,List<Enemy> enemy,List<Bullet> bullets, double[] direction){
        Rectangle attack = new Rectangle(x -9 + (int)(direction[0]*(-range)),y -9 + (int)(direction[1]*(-range)),range,range);
        long now = System.currentTimeMillis();
            if (now - LastAttackTime < AttackCooldown) {
            return; // too soon to attack
        }
        for (Enemy e : enemy) {
            if (attack.intersects(e.getBounds())) {
                e.hurt(dmg, x, y); // damage + knockback away from the player
            }
        }
        aimAngle = Math.atan2(-direction[1], -direction[0]); // toward mouse
        LastAttackTime = now;
        LastAttack_for_Drawing = attack;//for drawing
        WeaponX = x;
        WeaponY = y;
    }
    public void draw(Graphics g, int cameraX, int cameraY, int ScreenWidth, int ScreenHeight) {
        long now = System.currentTimeMillis();
        double t = (now - LastAttackTime) / 250.0; // slash progress 0..1
        if (t < 1.0) {
            double cx = WeaponX - cameraX + 15.0;
            double cy = WeaponY - cameraY + 15.0;
            double phi = aimAngle; // screen angle of aim
            // AWT drawArc: 0 deg = 3 o'clock, positive = visually CW (y-down)
            double start = -Math.toDegrees(phi) - 35 + 120 * Math.min(t, 1);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = (int) Math.round(range * 1.6);
            float alpha = (float) (1.0 - t) * 0.85f;
            g2.setStroke(new BasicStroke(5.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(250, 252, 255, (int) (alpha * 255)));
            g2.drawArc((int) (cx - r), (int) (cy - r), 2 * r, 2 * r,
                    (int) Math.round(start), 70);
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(190, 215, 235, (int) (alpha * 180)));
            int r2 = (int) Math.round(range * 1.15);
            g2.drawArc((int) (cx - r2), (int) (cy - r2), 2 * r2, 2 * r2,
                    (int) Math.round(start + 8), 54);
            g2.dispose();
        }
    }
}
