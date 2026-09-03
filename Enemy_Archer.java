
import java.awt.*;
import java.util.List;

public class Enemy_Archer extends Enemy{
    private long LastAttackTime = 0;
    private long AttackCooldown = 750;
    public Enemy_Archer(int x, int y, int size){
        super(x,y,size);
        // Slime_Green: 64x64 cells, row 0 idle (4f), row 1 move (8f); no directional rows
        SheetAnim[] slWalk = {
            new SheetAnim("assets/cute/Slime_Green.png", 64, 64, 1, 8, 130),
            new SheetAnim("assets/cute/Slime_Green.png", 64, 64, 1, 8, 130),
            new SheetAnim("assets/cute/Slime_Green.png", 64, 64, 1, 8, 130),
        };
        SheetAnim[] slIdle = {
            new SheetAnim("assets/cute/Slime_Green.png", 64, 64, 0, 4, 220),
            new SheetAnim("assets/cute/Slime_Green.png", 64, 64, 0, 4, 220),
            new SheetAnim("assets/cute/Slime_Green.png", 64, 64, 0, 4, 220),
        };
        this.walk = slWalk;
        this.idle = slIdle;
        this.deathColor = Effects.SLIME;
    }
    @Override
    protected void anim_draw(Graphics g, int cameraX, int cameraY, double stretchX, double stretchY) {
        // slime body fills most of its 64px cell; draw slightly smaller than skeleton zoom.
        // hurt squash: slimes squish flat (heavy Y squash, slight X bulge)
        double zoom = 1.1 * size / 30.0 * stretchY;
        int ax = (int) (x - cameraX + size / 2.0 + (stretchX - 1.0) * size * 0.25);
        (moving ? walk : idle)[facing].draw(g, ax, y - cameraY + size, zoom, facingLeft);
    }
    @Override
    protected void drawWeapon(Graphics g, int cameraX, int cameraY) {
        // slimes are unarmed
    }
    @Override
    public void Attack(Player player){
        return;
    }
    @Override 
    public void update(Player player, List<Obstacle> obstacles, List<Bullet> bullets){
        if(dead){
            return;
        }
        move(player, obstacles);
        ranged_Attack(player,direction, bullets);
        if (health<=0){
            dead = true;
        }
    }
    public void ranged_Attack(Player player,double[] direction, List<Bullet> bullets){
        if(sight().intersects(player.getBounds())){
            long now = System.currentTimeMillis();
            if(now - LastAttackTime>=AttackCooldown){
                direction[0] *= -1;
                direction[1] *= -1;
                bullets.add(new Bullet(x + (int)(size*direction[0]), y + (int)(size*direction[1]), 10, direction));
                LastAttackTime = now;
            }
        }
    }
}
