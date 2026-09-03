import java.awt.*;
import java.util.List;

// Fast melee enemy (goblin/imp/etc.): 0x72 frame art, chases quicker than the
// base skeleton, lower HP, higher speed.
class Enemy_Brute extends Enemy {
    private static final String KIND = "goblin";
    Enemy_Brute(int x, int y, int size) {
        super(x, y, size);
        this.health = 60;
        this.speed = 2; // fast
        this.dmg = 8;
        this.sight = 340;
    }
    @Override
    protected void anim_draw(Graphics g, int cameraX, int cameraY, double sx, double sy) {
        SpriteAnim.drawKind(g, KIND, x - cameraX + size / 2, y - cameraY + size,
            (int) Math.round(size * 2.0), facingLeft, moving);
    }
    @Override
    protected void drawWeapon(Graphics g, int cameraX, int cameraY) { /* unarmed claws */ }
}

// Caster enemy (orc_shaman): keeps distance, fires bolts.
class Enemy_Caster extends Enemy {
    private static final String KIND = "orc_shaman";
    private long LastShotTime = 0;
    private static final long ShotCooldown = 1400;
    Enemy_Caster(int x, int y, int size) {
        super(x, y, size);
        this.health = 50;
        this.speed = 1;
        this.dmg = 12;
        this.sight = 380;
        this.deathColor = new Color(120, 200, 120);
    }
    @Override
    protected void anim_draw(Graphics g, int cameraX, int cameraY, double sx, double sy) {
        SpriteAnim.drawKind(g, KIND, x - cameraX + size / 2, y - cameraY + size,
            (int) Math.round(size * 2.0), facingLeft, moving);
    }
    @Override
    protected void drawWeapon(Graphics g, int cameraX, int cameraY) {
        double hx = x - cameraX + size / 2.0, hy = y - cameraY + size * 0.55;
        double s = size / 30.0;
        double angle = Math.atan2(-(playerDirY()), playerDirX());
        WeaponView.draw(g, 1, hx, hy, chasing ? angle : (facing == 0 ? Math.PI : 0), 1.0, s * 1.1);
    }
    @Override
    public void update(Player player, List<Obstacle> obstacles, List<Bullet> bullets) {
        super.update(player, obstacles, bullets);
        if (dead) return;
        long now = System.currentTimeMillis();
        if (chasing && now - LastShotTime >= ShotCooldown
            && Math.hypot(player.x - x, player.y - y) < 320) {
            LastShotTime = now;
            double dx = player.x - x, dy = player.y - y;
            double len = Math.max(1, Math.hypot(dx, dy));
            double[] d = {dx / len, dy / len};
            bullets.add(new Bullet(x + size / 2 + (int)(d[0] * 20), y + size / 2 + (int)(d[1] * 20), 7, d));
        }
    }
}

// Heavy brute (ogre): slow, tanky, big knockback hit.
class Enemy_Ogre extends Enemy {
    private static final String KIND = "ogre";
    Enemy_Ogre(int x, int y, int size) {
        super(x, y, size);
        this.health = 220;
        this.speed = 1;
        this.dmg = 20;
        this.range = 28;
        this.sight = 300;
        this.deathColor = new Color(150, 130, 100);
    }
    @Override
    protected void anim_draw(Graphics g, int cameraX, int cameraY, double sx, double sy) {
        SpriteAnim.drawKind(g, KIND, x - cameraX + size / 2, y - cameraY + size,
            (int) Math.round(size * 2.2), facingLeft, moving);
    }
    @Override
    protected void drawWeapon(Graphics g, int cameraX, int cameraY) { /* fists */ }
}
