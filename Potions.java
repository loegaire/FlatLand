import java.util.List;
import java.awt.*;

public class Potions implements Items {
    Player player;
    public Potions(Player player) {
        this.player = player;
    }
    public void use(int x, int y, List<Enemy> enemy,List<Bullet> bullets, double[] direction){
        player.health += 20; // Heal the player by 20 health points    
    };
    
    public String getName(){
        return "Heal";
    };
    
    public void draw(Graphics g, int cameraX, int cameraY, int ScreenWidth, int ScreenHeight){

    };
}
