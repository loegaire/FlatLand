
import java.util.List;

public class Enemy_Archer extends Enemy{
    private long LastAttackTime = 0;
    private long AttackCooldown = 750;
    public Enemy_Archer(int x, int y, int size){
        super(x,y,size);
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
