import java.util.List;
import java.awt.*;

public class Potions implements Items {
    Player player;
    Color color = Color.GRAY; 
    int type;
    List<long[]> effects; 
    public Potions(Player player, int type) {
        this.player = player;
        this.type = type;
        this.effects = new java.util.ArrayList<>(); // Initialize the effects list
    }
    public void use(int x, int y, List<Enemy> enemy,List<Bullet> bullets, double[] direction){
        switch (type) {
            case 0: 
                Heal();
                break;
            case 1: 
                SpeedBoost(true);
                break;
            case 2:
                DamageBoost(true);
                break;
            case 3:
                SizeBoost();
                break;
            // Add more cases for different potion types as needed
            default:
                color = Color.GRAY; // Default color for unknown potion type
        }
    };
    
    public String getName(){
        return "placeholder for potion name"; // You can customize this based on type
    };
    public void Heal(){
        color = Color.RED; // Example color for healing potion
        player.health += 20; // Example healing amount
        return;
    }
    public void SpeedBoost(boolean active){
        if (active){
            long startTime = System.currentTimeMillis();
            long duration = 5000; // Example duration for speed boost in milliseconds
            long endTime = startTime + duration;
            color = Color.BLUE; // Example color for speed boost potion
            player.speed += 10;
            effects.add(new long[]{1, endTime});
        } // Store the effect duration
        else{
            player.speed -= 10; // Revert speed boost
            color = Color.GRAY; // Reset color when speed boost is deactivated
        }
    }
    public void DamageBoost(boolean active){
        if(active){
        long startTime = System.currentTimeMillis();
        long duration = 5000; // Example duration for speed boost in milliseconds
        long endTime = startTime + duration;
        effects.add(new long[]{2, endTime});
        color = Color.ORANGE; // Example color for damage boost potion
        player.dmg += 5;
        }
        else{
        player.dmg -= 5; // Revert damage boost
        color = Color.GRAY; // Reset color when damage boost is deactivated
        } // Example damage boost amount
    }
    public void SizeBoost(){
        color = Color.pink; // Example color for size boost potion
        player.size += 5; // Example size boost amount
    }
    public void update(){
    java.util.Iterator<long[]> it = effects.iterator();
        while (it.hasNext()) {
            long[] i = it.next();
            if (player.now > i[1]) { // Check if the effect duration has ended
                switch ((int)i[0]) {
                    case 1: // Speed Boost
                        SpeedBoost(false); // Deactivate speed boost
                        break;
                    case 2: // Damage Boost
                        DamageBoost(false); // Deactivate damage boost
                        break;
                    // Add more cases for other effects as needed
                }
                it.remove();
            }
        }
    }
    public void draw(Graphics g, int cameraX, int cameraY, int ScreenWidth, int ScreenHeight){
        int effectX = player.x - cameraX + player.size/2 - 10;
        int effectY = player.y - cameraY - 20;
        g.setColor(color);
        g.fillOval(effectX, effectY, 20, 20);
        // draw a "+" sign
        g.setColor(Color.WHITE);
        g.drawLine(effectX + 10, effectY + 5, effectX + 10, effectY + 15);
        g.drawLine(effectX + 5, effectY + 10, effectX + 15, effectY + 10);
    };
}
