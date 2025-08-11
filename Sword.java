import java.awt.*;
import java.util.List;

public class Sword implements Items {
    public Rectangle LastAttack_for_Drawing = new Rectangle();
    private long LastAttackTime = 0;
    private long AttackCooldown = 500;
    int WeaponX = 0;
    int WeaponY = 0;
    int range = 50;
    int dmg = 20;
    public Sword() {
    }
    public String getName() {
        return "Sword";
    }
    public void use(int x, int y,List<Enemy> enemy,List<Bullet> bullets, double[] direction){
        Rectangle attack = new Rectangle(x -9 + (int)(direction[0]*(-range)),y -9 + (int)(direction[1]*(-range)),range,range);
        long now = System.currentTimeMillis();
            if (now - LastAttackTime < AttackCooldown) {
            return; // too soon to attack
        }
        for (Enemy e : enemy) {
            if (attack.intersects(e.getBounds())) {
                e.health -= dmg;
            }
        }
        LastAttackTime = now;
        LastAttack_for_Drawing = attack;//for drawing
        WeaponX = x;
        WeaponY = y;
    }
    public void draw(Graphics g, int cameraX, int cameraY, int ScreenWidth, int ScreenHeight) {
        // Always get the current time
        long now = System.currentTimeMillis();

        // Draw attack rectangle
        g.setColor(Color.BLACK);
        g.fillRect(LastAttack_for_Drawing.x - cameraX, LastAttack_for_Drawing.y - cameraY, LastAttack_for_Drawing.width, LastAttack_for_Drawing.height);

        // Draw cooldown bar background
        int barWidth = (int)AttackCooldown / 10;
        int barHeight = 4;
        int barX = WeaponX - cameraX;
        int barY = WeaponY - cameraY;
        int elapsed = (int)(now - LastAttackTime);
        int fillWidth = Math.min(Math.max(elapsed / 10, 0), barWidth);
        g.setColor(Color.BLACK);
        g.fillRect(barX, barY, barWidth, barHeight);
        // Calculate cooldown progress
        g.setColor(Color.CYAN);
        g.fillRect(WeaponX - cameraX, WeaponY - cameraY, fillWidth, barHeight);
    }
}
