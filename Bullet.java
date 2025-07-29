import java.awt.*;
import java.util.List;;

public class Bullet {
    int x,y,size,speed=10,dmg = 10;
    boolean dead = false;
    double[] direction = new double[]{0,0};
    public Bullet(int x, int y,int size, double[] direction){
        this.x = x;
        this.y = y;
        this.size = size;
        this.direction[0] = direction[0];
        this.direction[1] = direction[1];
    }
    public void Attack(Player player,List<Obstacle> obstacles, List<Enemy> enemies){
        for(Enemy en : enemies){
            if(getBounds().intersects(en.getBounds())){
                en.health -= dmg;
                dead = true;
            }
        }
        for(Obstacle ob : obstacles){
            if(getBounds().intersects(ob.getBounds())){
                dead = true;
            }
        }
        if(getBounds().intersects(player.getBounds())){
            player.health -= dmg;
            dead = true;
        }
    }
    public void move(){
        x += direction[0]*speed;
        y += direction[1]*speed;
    }
    public void update(Player player,List<Obstacle> obstacles, List<Enemy> enemies){
        move();
        Attack(player,obstacles, enemies);
    }
    public Rectangle getBounds(){
        return new Rectangle(x,y,size,size);
    }
    public void draw(Graphics g){
        g.setColor(Color.PINK);
        g.fillRect(x , y, size, size);
    }
}
