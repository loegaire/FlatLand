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
    boolean dead = false;
    public Rectangle LastAttack = new Rectangle();
    public Enemy (int x, int y, int size){
        this.x = x;
        this.y = y;
        this.size = size;
    }
    public void Attack(Player player){
        long now = System.currentTimeMillis();
        Rectangle attack = new Rectangle(x + (int)(direction[0]*(-range)),y + (int)(direction[1]*(-range)),range,range);
            if (now - LastAttackTime < AttackCooldown) {
            return; // too soon to attack
        }
        if(attack.intersects(player.getBounds())){
            player.health -= dmg;
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
        if(now - patrolChangedDirection > patrolCooldown){
            patrol = new int[]{rand.nextInt(3) - 1,rand.nextInt(3) - 1};
            patrolChangedDirection = now;
        }
        
        if(!sight().intersects(player.getBounds())){
            x += patrol[0]*speed;
            y += patrol[1]*speed;
        }
        else {       
            int dx = x - player.x;
            int dy = y - player.y;
            double length = Math.sqrt(dy*dy + dx*dx);
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
            if (getBounds().intersects(ob.getBounds())){
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
        }
    }
    public void draw(Graphics g, int cameraX, int cameraY) {
        if (dead){
            return;
        }
        g.setColor(Color.RED);
        g.fillOval(x - cameraX, y - cameraY, size, size);
        g.setColor(new Color(255, 255, 0, 200)); 
        g.fillRect(LastAttack.x - cameraX, LastAttack.y - cameraY, LastAttack.width, LastAttack.height);
    }
}
