import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

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
  int WORLD_SIZE = 100_00;
  Random rand = new Random();
  List<Rectangle> occupied = new ArrayList<>();
  private boolean isOverlapping(Rectangle rect, List<Rectangle> others) {
    for (Rectangle other : others) {
      if (rect.intersects(other))
        return true;
    }
    return false;
  }
  private Player player;
  private List<Obstacle> obstacles = new ArrayList<>();
  private List<Enemy> enemies = new ArrayList<>();
  private List<Bullet> bullets = new ArrayList<>();
  private int cameraX = 0;
  private int cameraY = 0;
  private Point mousePos = new Point(0, 0);
  private final Set<Integer> pressedKeys = new HashSet<>();

  public GamePanel() {
    setFocusable(true); // allow key input
                        // Place obstacles
    for (int i = 0; i < 1000; i++) {
      int x, y;
      int OBSTACLE_SIZE = rand.nextInt(20, 100);
      Rectangle rect;
      do {
        x = rand.nextInt(WORLD_SIZE - OBSTACLE_SIZE);
        y = rand.nextInt(WORLD_SIZE - OBSTACLE_SIZE);
        rect = new Rectangle(x, y, OBSTACLE_SIZE, OBSTACLE_SIZE);
      } while (isOverlapping(rect, occupied));
      obstacles.add(new Obstacle(x, y, OBSTACLE_SIZE));
      occupied.add(rect);
    }

    // Place enemies
    for (int i = 0; i < 500; i++) {
      int x, y;
      int ENEMY_SIZE = rand.nextInt(10, 50);
      Rectangle rect;
      do {
        x = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        y = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        rect = new Rectangle(x, y, ENEMY_SIZE, ENEMY_SIZE);
      } while (isOverlapping(rect, occupied));
      enemies.add(new Enemy(x, y, ENEMY_SIZE));
      occupied.add(rect);
    }
    for (int i = 0; i < 500; i++) {
      int x, y;
      int ENEMY_SIZE = rand.nextInt(10, 50);
      Rectangle rect;
      do {
        x = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        y = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        rect = new Rectangle(x, y, ENEMY_SIZE, ENEMY_SIZE);
      } while (isOverlapping(rect, occupied));
      enemies.add(new Enemy_Archer(x, y, ENEMY_SIZE));
      occupied.add(rect);
    }

    player = new Player(WORLD_SIZE / 2, WORLD_SIZE / 2);
    // movement
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
        mousePos.x += cameraX; // adjust for camera position
        mousePos.y += cameraY; // adjust for camera position
      }
    });
    addMouseListener(new MouseAdapter() {
      public void mousePressed(MouseEvent e) {
        player.Attack(bullets, enemies, mousePos);
      }
    });
    addMouseWheelListener(new MouseWheelListener() {
      @Override
      public void mouseWheelMoved(MouseWheelEvent e) {
        int notches = e.getWheelRotation(); // positive = scroll down, negative
                                            // = scroll up
        player.changeWeapon(notches);
      }
    });
    // This is the game loop: it runs every 16ms (~60 FPS)
    javax.swing.Timer timer = new javax.swing.Timer(16, new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        int playerDx = 0, playerDy = 0;
        if (pressedKeys.contains(KeyEvent.VK_W))
          playerDy -= 1;
        if (pressedKeys.contains(KeyEvent.VK_S))
          playerDy += 1;
        if (pressedKeys.contains(KeyEvent.VK_A))
          playerDx -= 1;
        if (pressedKeys.contains(KeyEvent.VK_D))
          playerDx += 1;
        player.update(playerDx, playerDy, obstacles, enemies);
        for (Bullet bu : bullets) {
          bu.update(player, obstacles, enemies);
        }
        bullets.removeIf(bu -> bu.dead);
        for (Enemy ene : enemies) {
          ene.update(player, obstacles, bullets);
        }
        enemies.removeIf(enemy -> enemy.dead);

        cameraX = player.x - getWidth() / 2 + player.size / 2;
        cameraY = player.y - getHeight() / 2 + player.size / 2;
        repaint();
      }
    });
    timer.start();
  }
  // drawing stuff
  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    player.draw(g, cameraX, cameraY, getWidth(), getHeight());
    for (Bullet bu : bullets) {
      bu.draw(g, cameraX, cameraY);
    }
    for (Obstacle ob : obstacles) {
      ob.draw(g, cameraX, cameraY);
    }
    for (Enemy ene : enemies) {
      ene.draw(g, cameraX, cameraY);
    }
  }
}
