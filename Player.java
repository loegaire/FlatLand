import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ImageIcon;

public class Player {
    List<Weapon> weapons = new ArrayList<>();
    private Image sprite;
    int currentWeaponIndex = 1;
    int x,y;
    int speed = 2;
    int size = 30;
    int health = 100;
    double[] direction = new double[]{0,0};
    int range = 50;
    int dmg = 20;
    public Player(int x, int y) {
        weapons.add(new Sword()); 
        weapons.add(new Gun());
        this.x = x;
        this.y = y;
        sprite = new ImageIcon("assets/player.png").getImage();
    }
    public void Attack(List<Bullet> bullets,List<Enemy> enemies, Point mousePos){
        int dx = x - mousePos.x + 30; int dy = y - mousePos.y + 30;
        double length = Math.sqrt(dx*dx + dy*dy);
        if(length == 0){
            length = 1;
        }
        direction[0]=dx/length;
        direction[1]=dy/length;
        //dung vu khi:p 
        weapons.get(currentWeaponIndex).use(x, y, enemies,bullets, direction);        
    }

    public void draw(Graphics g, int cameraX, int cameraY, int ScreenWidth, int ScreenHeight) {
        g.setColor(Color.GREEN);
        g.fillOval(x - cameraX, y - cameraY, size, size);
        //g.drawImage(sprite, x-cameraX, y-cameraY,size,size, null);
        g.setColor(new Color(0,255,0)); 
        weapons.get(currentWeaponIndex).draw(g, cameraX, cameraY, ScreenWidth, ScreenHeight);
        g.setColor(Color.BLACK);
        g.fillRect(x - cameraX - 10, y + ScreenHeight/20 - cameraY, 50, 4);
        g.setColor(Color.RED);
        g.fillRect(x - cameraX - 10, y + ScreenHeight/20 - cameraY, health/2, 4);
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
    public void changeWeapon(int notches){
        if(notches > 0) {
            currentWeaponIndex = (currentWeaponIndex + 1) % weapons.size();
        } else if (notches < 0) {
            currentWeaponIndex = (currentWeaponIndex - 1 + weapons.size()) % weapons.size();
        }
    }
    public Rectangle getBounds(){
        return new Rectangle((int)(x + size*0.1),(int)(y + size*0.1),(int)(size - size*0.2),(int)(size - size*0.2));

    }
}
