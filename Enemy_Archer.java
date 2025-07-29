
import java.util.List;

public class Enemy_Archer extends Enemy{
    public Enemy_Archer(int x, int y, int size){
        super(x,y,size);
    }

    @Override
    public void Attack(Player player){
        return;
    }
    @Override 
    public void update(Player player, List<Obstacle> obstacles, List<Bullet> bullets){
        ranged_Attack(direction, bullets);
    }
    public void ranged_Attack(double[] direction,List<Bullet> bullets){
         bullets.add(new Bullet(x, y, 30, direction));
    }
}
