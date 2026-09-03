import java.awt.*;
import java.util.List;
import java.util.Random;

public class Enemy {
    int x,y,size,sight = 300, speed = 1,range = 20,dmg = 10,health = 100;
    double[] direction = new double[]{0,0};
    private long LastAttackTime = 0;
    private long AttackCooldown = 1000;
    private long patrolCooldown = 1000;
    private long patrolChangedDirection = 0;
    Random rand = new Random();
    int[] patrol = new int[]{rand.nextInt(3) - 1,rand.nextInt(3) - 1};
    List<Items> items;
    boolean dead = false;
    // water interaction state (driven by the panel each tick)
    boolean wasInWater = false;
    long lastRipple = 0;
    // hurt feedback: white flash + squash while the knockback plays
    long hurtStart = -100000;
    double kbx = 0, kby = 0; // knockback velocity, px/tick, decays
    // Cute Fantasy sheets: rows 0-2 walk (down/up/side), rows 3-5 idle
    protected SheetAnim[] walk = {
        new SheetAnim("assets/cute/Skeleton.png", 32, 32, 0, 6, 120),
        new SheetAnim("assets/cute/Skeleton.png", 32, 32, 1, 6, 120),
        new SheetAnim("assets/cute/Skeleton.png", 32, 32, 2, 6, 120),
    };
    protected SheetAnim[] idle = {
        new SheetAnim("assets/cute/Skeleton.png", 32, 32, 3, 6, 200),
        new SheetAnim("assets/cute/Skeleton.png", 32, 32, 4, 6, 200),
        new SheetAnim("assets/cute/Skeleton.png", 32, 32, 5, 6, 200),
    };
    protected boolean moving = false;
    protected boolean facingLeft = false;
    protected int facing = 0; // 0 down, 1 up, 2 side
    protected boolean chasing = false;
    // puff colour when this enemy dies (skeleton bone / slime green)
    protected Color deathColor = Effects.BONE;
    public Rectangle LastAttack = new Rectangle();
    public long lastAttackTime() {
        return LastAttackTime;
    }
    public Enemy (int x, int y, int size){
        this.x = x;
        this.y = y;
        this.items = new java.util.ArrayList<>();
        this.items.add(new Sword());
        this.size = size;
    }
    // called by damage sources (sword, bullets): flash + knock away from (fx,fy)
    public void hurt(int dmg, double fx, double fy) {
        health -= dmg;
        hurtStart = System.currentTimeMillis();
        double dx = x + size / 2.0 - fx, dy = y + size / 2.0 - fy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) len = 1;
        double power = 5.0 + Math.min(4.0, dmg * 0.1);
        kbx = dx / len * power;
        kby = dy / len * power;
        Effects.hit(x + size / 2.0, y + size / 2.0, size * 0.55, Effects.BLOOD);
    }
    public void Attack(Player player){
        long now = System.currentTimeMillis();
        Rectangle attack = new Rectangle(x + (int)(direction[0]*(-range)),y + (int)(direction[1]*(-range)),range,range);
            if (now - LastAttackTime < AttackCooldown) {
            return; // too soon to attack
        }
        if(attack.intersects(player.getBounds())){
            player.hurt(dmg, x + size / 2.0, y + size / 2.0); // dmg + knockback
            LastAttackTime = now;
        }
        LastAttack = attack;//for drawing

    }
    public Rectangle sight(){
        return new Rectangle(x - sight/2, y - sight/2,sight,sight);
    };
    public Rectangle getBounds(){
        return new Rectangle((int)(x + size*0.1),(int)(y + size*0.1),(int)(size - size*0.2),(int)(size - size*0.2));
    }
    public void move(Player player, List<Obstacle> obstacles ){
        long now = System.currentTimeMillis();
        int enemyX = x, enemyY = y;
        // knockback: pushes through first, decays fast; blocked by geometry
        if (Math.abs(kbx) >= 0.2 || Math.abs(kby) >= 0.2) {
            x += (int) Math.round(kbx);
            y += (int) Math.round(kby);
            for (Obstacle ob : obstacles) {
                if (ob.blocks() && getBounds().intersects(ob.getBounds())) { x = enemyX; y = enemyY; break; }
            }
            kbx *= 0.72; kby *= 0.72; // decay per tick
        }
        if(now - patrolChangedDirection > patrolCooldown){
            patrol = new int[]{rand.nextInt(3) - 1,rand.nextInt(3) - 1};
            patrolChangedDirection = now;
        }
        if (patrol[1] != 0) facing = patrol[1] > 0 ? 0 : 1;
        else if (patrol[0] != 0) { facing = 2; facingLeft = patrol[0] < 0; }
        
        if(!sight().intersects(player.getBounds())){
            chasing = false;
            moving = patrol[0] != 0 || patrol[1] != 0;
            x += patrol[0]*speed;
            y += patrol[1]*speed;
        }
        else {
            chasing = true;
            moving = true;
            int dx = x - player.x;
            int dy = y - player.y;
            if (Math.abs(dx) > Math.abs(dy)) {
                facing = 2;
                facingLeft = dx > 0;
            } else {
                facing = dy > 0 ? 0 : 1;
            }
            double length = Math.sqrt(dy*dy+dx*dx);
            if(length == 0){length = 1;}
            direction[0] = dx / length;
            direction[1] = dy / length;
            if (x - player.x > 0){
                x -= speed;
            } else x += speed;
            if (y - player.y > 0){
                y -= speed;
            } else y += speed;
        }
        if (getBounds().intersects(player.getBounds())){
            x = enemyX; y = enemyY;
        }
        for (Obstacle ob : obstacles){
            if (ob.blocks() && getBounds().intersects(ob.getBounds())){
                x = enemyX; y = enemyY;
            }
        }
    }

    public void update(Player player, List<Obstacle> obstacles, List<Bullet> bullets){
        if(dead){
            return;
        }
        move(player, obstacles);
        Attack(player);
        if (health <= 0){
            dead = true;    
            // death puff at the body centre
            Effects.pop(x + size / 2.0, y + size / 2.0, size, deathColor);
        }
    }
    public void draw(Graphics g, int cameraX, int cameraY) {
        if (dead){
            return;
        }
        long now = System.currentTimeMillis();
        long ht = now - hurtStart;
        boolean hurting = ht >= 0 && ht < 260;
        if (hurting) { // hurt anim: squash (flatter + wider) + white flash
            anim_draw(g, cameraX, cameraY, 1.18, 0.82);
            flashComposite(g, cameraX, cameraY, (1 - (float) ht / 260f) * 0.55f,
                new Color(255, 120, 120));
        } else {
            anim_draw(g, cameraX, cameraY, 1.0, 1.0);
        }
        drawWeapon(g, cameraX, cameraY);
        double t = (now - LastAttackTime) / 260.0; // telegraph progress
        if (t < 1.0 && LastAttack.width > 0) {
            double cx = x - cameraX + size / 2.0;
            double cy = y - cameraY + size / 2.0;
            double phi = Math.atan2(-direction[1], -direction[0]); // toward player
            double start = -Math.toDegrees(phi) - 35 + 120 * Math.min(t, 1);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = (int) Math.round(size * 1.3);
            float alpha = (float) (1.0 - t) * 0.8f;
            g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(255, 205, 60, (int) (alpha * 255)));
            g2.drawArc((int) (cx - r), (int) (cy - r), 2 * r, 2 * r,
                    (int) Math.round(start), 70);
            g2.dispose();
        }
    }
    // draw a translucent colour rectangle over this enemy's sprite bounds
    private void flashComposite(Graphics g, int cameraX, int cameraY, float alpha, Color col) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(),
            (int) (alpha * 255)));
        g2.fillRect(x - cameraX, y - cameraY, size, size);
        g2.dispose();
    }
    protected void anim_draw(Graphics g, int cameraX, int cameraY) {
        anim_draw(g, cameraX, cameraY, 1.0, 1.0);
    }
    // stretchX/stretchY: squash-and-stretch scale applied to the sprite only
    protected void anim_draw(Graphics g, int cameraX, int cameraY, double stretchX, double stretchY) {
        double zoom = 2.0 * size / 30.0 * stretchY;
        SheetAnim a = (moving ? walk : idle)[facing];
        int ax = (int) (x - cameraX + size / 2.0 + (stretchX - 1.0) * size * 0.25);
        a.draw(g, ax, y - cameraY + size, zoom, facingLeft);
    }
    protected void drawWeapon(Graphics g, int cameraX, int cameraY) {
        double hx = x - cameraX + size / 2.0;
        double hy = y - cameraY + size * 0.55;
        double s = size / 30.0;
        double angle = Math.atan2(-(playerDirY()), playerDirX());
        if (chasing) {
            WeaponView.draw(g, 0, hx, hy, angle, 1.0, s * 1.2);
        } else {
            WeaponView.draw(g, 0, hx, hy, facing == 0 ? Math.PI : 0, 1.0, s * 1.2);
        }
    }
    double playerDirX() {
        return direction[0];
    }
    double playerDirY() {
        return direction[1];
    }
}
