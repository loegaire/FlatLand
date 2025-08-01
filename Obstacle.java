import java.awt.*;
public class Obstacle {
    int x, y;
    int size;
    public Obstacle (int x, int y, int size){
        this.x=x;
        this.y=y;
        this.size=size;
    }
    public void draw(Graphics g, int cameraX, int cameraY){
        g.setColor(Color.GRAY);
        g.fillOval(x - cameraX, y - cameraY, size, size);
    }
    public Rectangle getBounds(){
        return new Rectangle((int)(x + size*0.1),(int)(y + size*0.1),(int)(size - size*0.2),(int)(size - size*0.2));
    }
}
