import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

public class MainGame {
    public static void main(String[] args) {
        JFrame frame = new JFrame("Main");
        GamePanel panel = new GamePanel();
        frame.add(panel);
        frame.setSize(300, 200);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }
}

class GamePanel extends JPanel {
    private Player player;
    private List<Obstacle> obstacles = new ArrayList<>();
    private List<Enemy> enemies = new ArrayList<>();
    private List<Bullet> bullets = new ArrayList<>();

    private boolean GameOver = false;
    private Point mousePos = new Point(0, 0);
    private final Set<Integer> pressedKeys = new HashSet<>();

    public GamePanel() {
        setFocusable(true); // allow key input
        // adding stuff to the map
        obstacles.add(new Obstacle(100,30,50));
        obstacles.add(new Obstacle(100,100,70));
        enemies.add(new Enemy(30,200,30));
        enemies.add(new Enemy(200,200,50));
        enemies.add(new Enemy_Archer(500,500 ,10));
        player = new Player(300,300);   
        //movement
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                pressedKeys.add(e.getKeyCode());
            }
        
            @Override
            public void keyReleased(KeyEvent e) {
                pressedKeys.remove(e.getKeyCode());
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                mousePos = e.getPoint();
            }
        });
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                for (Enemy ene : enemies) {
                    player.Attack(ene, mousePos);
                }
            }
        });
        // This is the game loop: it runs every 16ms (~60 FPS)
    javax.swing.Timer timer = new javax.swing.Timer(16, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int playerDx = 0, playerDy = 0;
                if (pressedKeys.contains(KeyEvent.VK_W)) playerDy -= 1;
                if (pressedKeys.contains(KeyEvent.VK_S)) playerDy += 1;
                if (pressedKeys.contains(KeyEvent.VK_A)) playerDx -= 1;
                if (pressedKeys.contains(KeyEvent.VK_D)) playerDx += 1;
                player.move(playerDx, playerDy, obstacles, enemies);
                for (Bullet bu : bullets){
                    bu.update(player,obstacles, enemies);
                }
                for (Enemy ene : enemies){
                    ene.update(player,obstacles,bullets);
                }
                enemies.removeIf(enemy -> enemy.dead);
                if (player.health <= 0){
                    GameOver = true;
                }
                repaint();
            }
        });
        timer.start();
    }
    // drawing stuff
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        player.draw(g);
        for (Bullet bu : bullets){
            bu.draw(g);
        }
        for (Obstacle ob : obstacles) {
            ob.draw(g);
        }
        for (Enemy ene : enemies){
            ene.draw(g);
        }
        if (GameOver){
            g.setFont(new Font("Arial", Font.BOLD, 48));
            g.drawString("Game Over", getWidth() / 2 - 140, getHeight() / 2);
        }
    }

}
