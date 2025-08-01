import java.awt.*;
import java.util.List;
public interface Weapon {
    void use(int x, int y, List<Enemy> enemy,List<Bullet> bullets, double[] direction);
    String getName();
    void draw(Graphics g, int cameraX, int cameraY, int ScreenWidth, int ScreenHeight);
}
