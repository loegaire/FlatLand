import java.awt.*;
import java.util.List;
public class Player {
    int x,y;
    int speed = 2;
    int size = 30;
    int health = 100;
    double[] direction = new double[]{0,0};
    private long LastAttackTime = 0;
    private long AttackCooldown = 1000;
    int range = 50;
    int dmg = 20;
    public Rectangle LastAttack = new Rectangle();
    public Player(int x, int y) {
        this.x = x;
        this.y = y;
    }
    public void Attack(Enemy enemy, Point mousePos){
        int dx = x - mousePos.x + 30; int dy = y - mousePos.y + 30;
        double length = Math.sqrt(dx*dx + dy*dy);
        if(length == 0){
            length = 1;
        }
        direction[0]=dx/length;
        direction[1]=dy/length;
        Rectangle attack = new Rectangle(x -9 + (int)(direction[0]*(-range)),y -9 + (int)(direction[1]*(-range)),range,range);
        long now = System.currentTimeMillis();
            if (now - LastAttackTime < AttackCooldown) {
            return; // too soon to attack
        }
        if(attack.intersects(enemy.getBounds())){
            enemy.health -= dmg;
            LastAttackTime = now;
        }
        LastAttack = attack;//for drawing
    }
    public void draw(Graphics g) {
        g.setColor(Color.GREEN);
        g.fillOval(x, y, size, size);
        g.setColor(new Color(0,255,0)); 
        g.setColor(Color.BLACK);
        g.fillRect(LastAttack.x, LastAttack.y, LastAttack.width, LastAttack.height);
    }
    public void move(int dx, int dy, List<Obstacle> obstacles, List<Enemy> enemies){
        int currentX = x;
        int currentY = y;
        double len = Math.sqrt(dx*dx + dy*dy);
        x += (int)(speed*dx/len);
        y += (int)(speed*dy/len);
        for(Obstacle ob : obstacles){
            if (ob.getBounds().intersects(getBounds())){
                x = currentX;
                y = currentY;
            }
        }
        for(Enemy en : enemies){
            if (en.getBounds().intersects(getBounds())){
                x = currentX;
                y = currentY;
            }
        }
    }
    public Rectangle getBounds(){
        return new Rectangle((int)(x + size*0.1),(int)(y + size*0.1),(int)(size - size*0.2),(int)(size - size*0.2));
    }
}
