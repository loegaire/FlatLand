import java.util.List;
import java.awt.*;

public class Gun implements Items{
    private int LastAttackTime = 0;
    private int dmg = 10;
    private int range = 100;
    private int speed = 10;
    int WeaponX;
    int WeaponY;
    int AttackCooldown = 500;
    public Gun() {
    }
    public String getName() {
        return "Gun";
    }
    public void use(int x, int y, List<Enemy> enemy,List<Bullet> bullets, double[] direction) {
        int now = (int)System.currentTimeMillis();
        if (now - LastAttackTime < AttackCooldown) {
            return;
        }
        LastAttackTime = now;
        // Normalize direction (in case it's not already)
        double len = Math.sqrt(direction[0]*direction[0] + direction[1]*direction[1]);
        double[] ProcessedDirection = new double[]{-direction[0] / len, -direction[1] / len};

        // Offset bullet spawn by 10 pixels in the direction of fire
        int bulletX = (int)(x + 10+ ProcessedDirection[0] * 15);
        int bulletY = (int)(y + 10 + ProcessedDirection[1] * 15);
        bullets.add(new Bullet(bulletX, bulletY, speed, ProcessedDirection));
        WeaponX = x;
        WeaponY = y;
    }
    public void draw(Graphics g, int cameraX, int cameraY, int ScreenWidth, int ScreenHeight) {
        int now = (int)System.currentTimeMillis();
        // Draw cooldown bar background
        int barWidth = AttackCooldown / 10;
        int barHeight = 4;
        int barX = WeaponX - cameraX;
        int barY = WeaponY - cameraY;
        
        g.setColor(Color.BLACK);
        g.fillRect(barX, barY, barWidth, barHeight);

        // Calculate cooldown progress
        int elapsed = now - LastAttackTime;
        int fillWidth = Math.min(Math.max(elapsed / 10, 0), barWidth);
        g.setColor(Color.CYAN);
        g.fillRect(WeaponX - cameraX, WeaponY - cameraY, fillWidth, barHeight);
    }
}