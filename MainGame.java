import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public class MainGame {
  public static void main(String[] args) {
    JFrame frame = new JFrame("FlatLand");
    GamePanel panel = new GamePanel();
    frame.add(panel);
    frame.setSize(900, 600);
    frame.setLocationRelativeTo(null);
    frame.setAlwaysOnTop(true);
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setVisible(true);
    // let the first few timer ticks run, then report + self-verify on screen
    javax.swing.Timer t = new javax.swing.Timer(3000, e -> {
      System.err.println("[FlatLand] sprites loaded=" + SpriteAnim.loadedCount
          + " failed=" + SpriteAnim.failedCount
          + " assetsDir=" + SpriteAnim.resolve("assets/dungeon")
          + " paintCalls=" + panel.paintCalls
          + " entities=" + panel.entities.size() + " obstacles=" + panel.obstacles.size());
      panel.selfCheck();
    });
    t.setRepeats(false);
    t.start();
  }
}

class GamePanel extends JPanel {
  static final int WORLD_SIZE = 100_00;
  static final int TS = 32; // 16px tiles scaled x2
  static final int MAP_TILES = WORLD_SIZE / TS + 1;
  // ---- world text map ----
  // world generation is text-first: generateWorldText() builds one big chunk of
  // characters ('g' grass, 'w' water, 'p' path) up front, and drawFloor() maps
  // every char to its tile art via a text-to-tile mapping. No pixel probing.
  private final char[][] worldText = new char[MAP_TILES][MAP_TILES];
  // live ground state per tile: entity occupancy + weather reaction fields
  private final Ground[][] ground = new Ground[MAP_TILES][MAP_TILES];
  Random rand = new Random();
  List<Rectangle> occupied = new ArrayList<>();
  // ---- Cute Fantasy level art ----
  // grass: procedural speckle variants (free pack's grass tile is a flat color;
  // we texture it with the pack's own palette at load time)
  private static final Image[] GRASS = makeGrass();
  // transition sheets (16px cells): rows 0-2 ring autotile, (0,3)(1,3)(0,4)(1,4)
  // inner corners (diag BR/BL/TL/TR land), row 5 + (1,1) pure interiors
  private static final Image[][] PATH_C = sheetGrid(SpriteAnim.load("assets/cute/Path_Tile.png"));
  private static final Image[][] WATER_C = sheetGrid(SpriteAnim.load("assets/cute/Water_Tile.png"));
  // Outdoor_Decor_Free.png 16px cells: natural flowers/bushes/plants only
  // (row 0 cols 3-6 of the sheet are man-made seed bags/signs - excluded)
  private static final int[][] DECOR = { // {sx, sy}
      {0, 0}, {16, 0}, {32, 0},             // grass tufts
      {0, 16}, {16, 16}, {32, 16},          // bushes / plants
      {48, 16}, {64, 16}, {80, 16}, {96, 16}, // blue/purple flower bushes
      {32, 32}, {48, 32}, {64, 32},         // small flowers
  };
  private static final Image DECOR_SHEET = SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png");
  private static final Image CHEST = SpriteAnim.load("assets/cute/Chest.png");
  // deterministic tests: -Dflatland.seed=123 pins the world text
  private final long worldSeed = System.getProperty("flatland.seed") != null
      ? Long.parseLong(System.getProperty("flatland.seed")) : System.currentTimeMillis();
  private static final int SPAWN_TX = 100_00 / 2 / 32; // player spawn tile (world centre)
  int paintCalls = 0;
  private boolean isOverlapping(Rectangle rect, List<Rectangle> others) {
    for (Rectangle other : others) {
      if (rect.intersects(other))
        return true;
    }
    return false;
  }
  // rejects rects covering water or path tiles (checked against the world text)
  boolean onSpecialTile(Rectangle rect) {
    int tx0 = Math.floorDiv(rect.x, TS), ty0 = Math.floorDiv(rect.y, TS);
    int tx1 = Math.floorDiv(rect.x + rect.width, TS), ty1 = Math.floorDiv(rect.y + rect.height, TS);
    for (int ty = ty0; ty <= ty1; ty++)
      for (int tx = tx0; tx <= tx1; tx++)
        if (tileAt(tx, ty) != 'g') return true;
    return false;
  }
  private Player player;
  List<Obstacle> obstacles = new ArrayList<>();
  List<Entity> entities = new ArrayList<>(); // ALL characters: villagers + monsters + the player's foes
  // (villagers + monsters all live in `entities`)
  // structure sites: villages attract NPCs + animals; dungeons garrison enemies
  final java.util.List<Point> villages = new ArrayList<>();
  final java.util.List<Point> dungeons = new ArrayList<>();
  List<Animal> animals = new ArrayList<>();
  private List<Bullet> bullets = new ArrayList<>();
  private final Weather weather = new Weather();
  // respawn: brief game-freeze overlay, then the player pops back at spawn
  private long respawnAt = -1;
  private static final long RESPAWN_HOLD_MS = 1200;
  private int cameraX = 0;
  private int cameraY = 0;
  private Point mousePos = new Point(0, 0);
  private final Set<Integer> pressedKeys = new HashSet<>();
  private boolean wasPlayerInWater = false;
  private boolean showInventory = false;
  private long lastPlayerRipple = 0, lastFootprint = 0, lastVegCheck = 0;
  // plants the player is currently inside the reach of (edge-triggered shakes:
  // a plant re-arms only after the player fully exits its reach)
  private final java.util.Set<Obstacle> vegArmed = new java.util.HashSet<>();

  public GamePanel() {
    generateWorldText(); // text map first: obstacles/enemies avoid water via text
    // ---- REAL MAP: site placement + road network (carves 'p' roads) ----
    MapGen mapgen = new MapGen(worldText, worldSeed);
    List<MapGen.Site> sites = mapgen.generate(5, 4);
    // roads carved by MapGen can create 1-wide path channels the original
    // representability pass never saw -> run it again AFTER road carving so
    // every fringe tile stays unambiguously drawable
    cleanupRepresentability();
    // ground grid AFTER road carving so path tiles are tracked
    for (int ty = 0; ty < MAP_TILES; ty++)
      for (int tx = 0; tx < MAP_TILES; tx++)
        ground[ty][tx] = new Ground(worldText[ty][tx]);
    setFocusable(true); // allow key input
    setBackground(new Color(0x1C, 0x1C, 0x1D)); // never show white while loading
    // ---- STRUCTURED SITES from MapGen: village streets + dungeon plans ----
    String[] npcNames = {"Aldric", "Maethor", "Ida", "Brokkr", "Ssara",
        "Mortimer", "Seraphine", "Brynn", "Zephyr", "Sylvar", "Tomas",
        "Greta", "Finn", "Petra", "Otto"};
    String[] npcKinds = {"knight_m", "elf_m", "wizzard_f", "dwarf_m",
        "lizard_f", "doc", "angel", "knight_f", "wizzard_m", "elf_f",
        "knight_m", "wizzard_f", "elf_f", "dwarf_f", "doc"};
    int npcIdx = 0;
    for (MapGen.Site site : sites) {
      int sx = site.x, sy = site.y; // top-left tile
      if (site.kind.equals("village")) {
        // === VILLAGE: 12x10 tiles with a street grid ===
        // main street: horizontal road across the middle (row sy+5),
        // cross street: vertical (col sx+6); plaza at their crossing
        for (int i = 0; i < 12; i++) worldText[sy + 5][sx + i] = 'p';
        for (int j = 0; j < 10; j++) worldText[sy + j][sx + 6] = 'p';
        // plaza: 3x3 road square at the crossing
        for (int j = 4; j <= 6; j++)
          for (int i = 5; i <= 7; i++)
            worldText[sy + j][sx + i] = 'p';
        // name the streets: lanterns along the main street every 3 tiles
        for (int i = 1; i < 12; i += 3)
          obstacles.add(new Obstacle.Lantern((sx + i) * TS, (sy + 4) * TS, TS));
        for (int i = 1; i < 12; i += 3)
          obstacles.add(new Obstacle.Lantern((sx + i) * TS, (sy + 7) * TS, TS));
        // fountain at the plaza centre + birdbaths at corners
        obstacles.add(new Obstacle.Fountain((sx + 6) * TS, (sy + 5) * TS, TS));
        obstacles.add(new Obstacle.Birdbath((sx + 4) * TS, (sy + 3) * TS, TS));
        obstacles.add(new Obstacle.Birdbath((sx + 8) * TS, (sy + 3) * TS, TS));
        obstacles.add(new Obstacle.Birdbath((sx + 8) * TS, (sy + 4) * TS, TS));
        // well by the plaza
        obstacles.add(new Obstacle.Well((sx + 3) * TS, (sy + 4) * TS, TS));
        // signpost at the village entrance (road enters at west edge)
        obstacles.add(new Obstacle.Signpost((sx - 1) * TS, (sy + 4) * TS, TS, false));
        // houses facing the streets, clear of the cross-street column sx+6:
        // north side rows sy+0..3, south side rows sy+6..9, cols sx+0..3 + sx+8..11
        obstacles.add(new Obstacle.House((sx + 0) * TS, (sy + 0) * TS, TS));
        obstacles.add(new Obstacle.House((sx + 8) * TS, (sy + 0) * TS, TS));
        obstacles.add(new Obstacle.House((sx + 0) * TS, (sy + 6) * TS, TS));
        obstacles.add(new Obstacle.House((sx + 8) * TS, (sy + 6) * TS, TS));
        // fenced farm field SE (4x4 at sx+8..11 x sy+6..9): ring fence, 2x2 crops
        // village farms are HARVESTABLE: most crops ripe (row 11) so villagers
        // + the player can gather food from them
        for (int j = 0; j < 4; j++)
          for (int i = 0; i < 4; i++) {
            if (i == 0 || i == 3 || j == 0 || j == 3)
              obstacles.add(new Obstacle.Fence((sx + 8 + i) * TS, (sy + 6 + j) * TS, TS, 1));
            else { // crops inside: 70% ripe, 30% growing
              boolean ripe = rand.nextInt(10) < 7;
              obstacles.add(new Obstacle.Crop((sx + 8 + i) * TS, (sy + 6 + j) * TS, TS,
                  ripe ? 11 : 8 + rand.nextInt(3), rand.nextInt(4)));
            }
          }
        // yard dressing + village chest near the well
        obstacles.add(new Obstacle.Barrel((sx + 1) * TS, (sy + 4) * TS, TS));
        obstacles.add(new Obstacle.Barrel((sx + 1) * TS, (sy + 5) * TS, TS));
        obstacles.add(new Obstacle.WoodPile((sx + 8) * TS, (sy + 1) * TS, TS, rand.nextInt(2)));
        obstacles.add(new Obstacle.Chest((sx + 9) * TS, (sy + 3) * TS, TS));
        // villagers + livestock (spawned before zone is reserved)
        for (int i = 0; i < 2 + rand.nextInt(2) && npcIdx < npcNames.length; i++, npcIdx++) {
          int nx = (sx + 6) * TS + rand.nextInt(-2 * TS, 2 * TS);
          int ny = (sy + 5) * TS + rand.nextInt(-2 * TS, 2 * TS);
          // ONE spawn path for ALL entities: random stats inside
          Entity npc = new Entity(nx, ny, 26, npcNames[npcIdx], npcKinds[npcIdx], rand, false);
          npc.setHome((sx + 6) * TS, (sy + 5) * TS, 4 * TS);
          entities.add(npc);
        }
        for (int i = 0; i < 2 + rand.nextInt(3); i++)
          animals.add(new Animal((sx + 6) * TS + rand.nextInt(-4 * TS, 4 * TS),
              (sy + 5) * TS + rand.nextInt(-4 * TS, 4 * TS),
              rand.nextInt(14, 20), rand.nextBoolean() ? "chicken" : "pig"));
        villages.add(new Point((sx + 6) * TS, (sy + 5) * TS));
        occupied.add(new Rectangle((sx - 1) * TS, (sy - 1) * TS, 14 * TS, 12 * TS));
      } else {
        // === DUNGEON: 13x11 floor plan: 2x2 room grid + corridors ===
        // rooms: NW(sx+1..5, sy+1..4), NE(sx+7..11, sy+1..4),
        //        SW(sx+1..5, sy+6..9), SE(sx+7..11, sy+6..9)
        // outer wall ring
        for (int i = 0; i < 13; i++) {
          obstacles.add(new Obstacle.RuinWall((sx + i) * TS, (sy + 0) * TS, TS, rand.nextInt(3)));
          obstacles.add(new Obstacle.RuinWall((sx + i) * TS, (sy + 10) * TS, TS, rand.nextInt(3)));
        }
        for (int j = 1; j < 10; j++) {
          obstacles.add(new Obstacle.RuinWall((sx + 0) * TS, (sy + j) * TS, TS, rand.nextInt(3)));
          obstacles.add(new Obstacle.RuinWall((sx + 12) * TS, (sy + j) * TS, TS, rand.nextInt(3)));
        }
        // interior cross walls with door gaps at (sx+6, sy+2..3) and (sx+6, sy+7..8)
        for (int j = 1; j < 10; j++) {
          if (j == 2 || j == 3 || j == 7 || j == 8) continue; // vertical door gaps
          obstacles.add(new Obstacle.RuinWall((sx + 6) * TS, (sy + j) * TS, TS, rand.nextInt(3)));
        }
        for (int i = 1; i < 12; i++) {
          if (i == 3 || i == 4 || i == 6 || i == 9 || i == 10) continue; // door gaps + centre road
          obstacles.add(new Obstacle.RuinWall((sx + i) * TS, (sy + 5) * TS, TS, rand.nextInt(3)));
        }
        // gate on the west wall (road entrance): clear 2 tiles
        // (carved as absence of wall at sy+4..5 -- skip adding there)
        // gatehouse columns flanking the gate
        obstacles.add(new Obstacle.RuinColumn((sx + 0) * TS, (sy + 3) * TS, TS, true));
        obstacles.add(new Obstacle.RuinColumn((sx + 0) * TS, (sy + 6) * TS, TS, true));
        // NW room = graveyard
        for (int i = 0; i < 5; i++)
          obstacles.add(new Obstacle.Gravestone((sx + 1 + rand.nextInt(4)) * TS,
              (sy + 1 + rand.nextInt(3)) * TS, TS, rand.nextInt(4)));
        // bones + loot are real ITEMS on the ground now (pickable, weighted)
        Entity.groundItems.add(new Entity.GroundItem(ItemCatalog.skull(), (sx + 3) * TS + 8, (sy + 2) * TS + 8));
        Entity.groundItems.add(new Entity.GroundItem(ItemCatalog.skull(), (sx + 2) * TS + 8, (sy + 3) * TS + 8));
        // NE room = garrison barracks: spikes + enemies (4+ guaranteed)
        obstacles.add(new Obstacle.Spikes((sx + 8) * TS, (sy + 2) * TS, TS));
        obstacles.add(new Obstacle.Spikes((sx + 10) * TS, (sy + 3) * TS, TS));
        int garrison = 0;
        for (int i = 0; i < 8 && garrison < 4; i++) {
          int gx = (sx + 7 + rand.nextInt(4)) * TS, gy = (sy + 1 + rand.nextInt(3)) * TS;
          if (onSpecialTile(new Rectangle(gx, gy, 24, 24))) continue;
          int k = rand.nextInt(3);
          entities.add(k == 0 ? new Entity(gx, gy, 22, "Goblin", "goblin", rand, true)
              : k == 1 ? new Entity(gx, gy, 20, "Shaman", "orc_shaman", rand, true)
              : new Entity(gx, gy, 34, "Ogre", "ogre", rand, true));
          garrison++;
        }
        while (garrison < 4) { // deterministic fill if random draws all clashed
          int gx = (sx + 8) * TS, gy = (sy + 2 + garrison) * TS;
          entities.add(new Entity(gx, gy, 22, "Goblin", "goblin", rand, true));
          garrison++;
        }
        // SW room = rubble: broken columns + crates + barrels
        for (int i = 0; i < 3; i++)
          obstacles.add(new Obstacle.RuinColumn((sx + 1 + rand.nextInt(5)) * TS,
              (sy + 6 + rand.nextInt(4)) * TS, TS, false));
        obstacles.add(new Obstacle.Crate((sx + 2) * TS, (sy + 8) * TS, TS));
        obstacles.add(new Obstacle.Barrel((sx + 4) * TS, (sy + 7) * TS, TS));
        // SE room = treasure vault: chest + coin ring + guards
        obstacles.add(new Obstacle.Chest((sx + 9) * TS, (sy + 8) * TS, TS));
        for (int i = 0; i < 6; i++) {
          double a = i / 6.0 * Math.PI * 2;
          Entity.groundItems.add(new Entity.GroundItem(ItemCatalog.coin(),
              (sx + 9) * TS + 16 + (int) Math.round(Math.cos(a) * 44),
              (sy + 8) * TS + 16 + (int) Math.round(Math.sin(a) * 44)));
        }
        for (int i = 0; i < 2; i++) {
          int gx = (sx + 7 + rand.nextInt(4)) * TS, gy = (sy + 6 + rand.nextInt(3)) * TS;
          entities.add(new Entity(gx, gy, 34, "Ogre", "ogre", rand, true));
        }
        // signpost outside the gate pointing at the dungeon -- snap it to the
        // nearest grass tile west of the compound (may be on any side)
        int sgx = sx - 2, sgy = sy + 5;
        if (!isGrassTile(sgx, sgy)) {
          int[][] tryd = {{-1,0},{1,0},{0,-1},{0,1},{-2,1},{-1,-1},{1,1},{1,-1}};
          for (int[] d : tryd) {
            if (isGrassTile(sx - 2 + d[0], sy + 5 + d[1])) { sgx = sx - 2 + d[0]; sgy = sy + 5 + d[1]; break; }
          }
        }
        obstacles.add(new Obstacle.Signpost(sgx * TS, sgy * TS, TS, true));
        dungeons.add(new Point((sx + 6) * TS, (sy + 5) * TS));
        occupied.add(new Rectangle((sx - 2) * TS, (sy - 2) * TS, 17 * TS, 15 * TS));
      }
    }

                        // Place obstacles
    for (int i = 0; i < 1000; i++) {
      int x, y;
      int OBSTACLE_SIZE = rand.nextInt(20, 100);
      Obstacle ob;
      Rectangle block;
      do {
        x = rand.nextInt(WORLD_SIZE - OBSTACLE_SIZE);
        y = rand.nextInt(WORLD_SIZE - OBSTACLE_SIZE);
        ob = Obstacle.create(x, y, OBSTACLE_SIZE);
        // the art-true block: blocking kinds quantize to tiles (tree big 3x3,
        // small/stone/chest 1x1); validate THAT, not the random probe rect
        block = ob.blocks()
            ? new Rectangle(ob.x, ob.y, ob.size, ob.size)
            : new Rectangle(x, y, OBSTACLE_SIZE, OBSTACLE_SIZE);
      } while (isOverlapping(block, occupied) || onSpecialTile(block));
      obstacles.add(ob);
      if (ob.blocks()) occupied.add(block); // decor doesn't reserve space
    }
    // extra lively decor: flowers, tall grass, pebbles scattered on EVERY
    // grassy tile stretch (walk-through decoration, not in `occupied`)
    java.util.Random decorRand = new java.util.Random(worldSeed);
    for (int i = 0; i < 2600; i++) {
      int SZ = decorRand.nextInt(12, 22);
      int x = decorRand.nextInt(WORLD_SIZE - SZ);
      int y = decorRand.nextInt(WORLD_SIZE - SZ);
      if (onSpecialTile(new Rectangle(x, y, SZ, SZ))) continue; // grass only
      obstacles.add(Obstacle.create(x, y, SZ)); // small sizes -> plants/pebbles
    }
    // scatter heavy stones: validate the FINAL 32px block (the constructor
    // quantizes, so the block may differ from the probe rect)
    for (int i = 0; i < 60; i++) {
      int x, y, SZ = rand.nextInt(24, 42);
      Rectangle block;
      do {
        x = rand.nextInt(WORLD_SIZE - SZ);
        y = rand.nextInt(WORLD_SIZE - SZ);
        // replicate the quantization: cellIdx != 2 -> size = 32
        block = new Rectangle(x, y, 32, 32);
      } while (isOverlapping(block, occupied) || onSpecialTile(block));
      Obstacle.Stone st = new Obstacle.Stone(x, y, SZ);
      obstacles.add(st);
      occupied.add(new Rectangle(st.x, st.y, st.size, st.size)); // real 1x1 block
    }

    // village streets + dungeon compounds carved their own 'p' tiles -- run
    // the representability fixpoint once more so every fringe stays drawable
    cleanupRepresentability();

    // ---- ROADS-ARE-SACRED cleanup (fixpoint): no BLOCKING prop on a road.
    // Roads double as village streets + dungeon entries; blocking props that
    // landed on one are nudged to the nearest free tile instead of deleted,
    // so villages keep all their houses no matter how roads route.
    for (int pass = 0; pass < 4; pass++) {
      boolean moved = false;
      for (int i = 0; i < obstacles.size(); i++) {
        Obstacle ob = obstacles.get(i);
        if (!ob.blocks() || !coversRoad(ob.x, ob.y, ob.size)) continue;
        // try shifting in 8 directions by one tile until off-road + on grass
        int[][] dxy = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}};
        for (int[] d : dxy) {
          int nx = ob.x + d[0] * TS, ny = ob.y + d[1] * TS;
          // accept only positions whose every covered tile is pure grass
          if (allGrass(nx, ny, ob.size)
              && !blockedByObstacle(nx, ny, ob)) {
            ob.x = nx; ob.y = ny;
            moved = true;
            break;
          }
        }
      }
      if (!moved) break;
    }
    // last resort: drop any still-on-road blockers (should be none now)
    obstacles.removeIf(ob -> ob.blocks() && coversRoad(ob.x, ob.y, ob.size));

    // Place enemies
    for (int i = 0; i < 120; i++) {
      int x, y;
      int ENEMY_SIZE = rand.nextInt(10, 50);
      Rectangle rect;
      do {
        x = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        y = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        rect = new Rectangle(x, y, ENEMY_SIZE, ENEMY_SIZE);
      } while (isOverlapping(rect, occupied) || onSpecialTile(rect));
      entities.add(new Entity(x, y, ENEMY_SIZE, "Skeleton", "skelet", rand, true));
      occupied.add(rect);
    }
    for (int i = 0; i < 120; i++) {
      int x, y;
      int ENEMY_SIZE = rand.nextInt(10, 50);
      Rectangle rect;
      do {
        x = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        y = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        rect = new Rectangle(x, y, ENEMY_SIZE, ENEMY_SIZE);
      } while (isOverlapping(rect, occupied) || onSpecialTile(rect));
      entities.add(new Entity(x, y, ENEMY_SIZE, "Imp", "imp", rand, true));
      occupied.add(rect);
    }
    // bestiary: fast goblins, caster shamans, heavy ogres (0x72 frames)
    String[] kinds = {"goblin", "imp", "masked_orc"};
    for (int i = 0; i < 90; i++) {
      int x, y;
      int ENEMY_SIZE = rand.nextInt(14, 30);
      Rectangle rect;
      do {
        x = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        y = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        rect = new Rectangle(x, y, ENEMY_SIZE, ENEMY_SIZE);
      } while (isOverlapping(rect, occupied) || onSpecialTile(rect));
      int k = rand.nextInt(3);
      if (k == 0) entities.add(new Entity(x, y, ENEMY_SIZE, "Goblin", "goblin", rand, true));
      else if (k == 1) entities.add(new Entity(x, y, ENEMY_SIZE, "Shaman", "orc_shaman", rand, true));
      else entities.add(new Entity(x, y, ENEMY_SIZE, "Imp", "imp", rand, true));
      occupied.add(rect);
    }
    for (int i = 0; i < 25; i++) { // rarer heavies
      int x, y;
      int ENEMY_SIZE = rand.nextInt(30, 46);
      Rectangle rect;
      do {
        x = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        y = rand.nextInt(WORLD_SIZE - ENEMY_SIZE);
        rect = new Rectangle(x, y, ENEMY_SIZE, ENEMY_SIZE);
      } while (isOverlapping(rect, occupied) || onSpecialTile(rect));
      entities.add(new Entity(x, y, ENEMY_SIZE, "Ogre", "ogre", rand, true));
      occupied.add(rect);
    }

    // animals: wild herds (cows/sheep) roam the world at large
    String[] animalKinds = {"chicken", "cow", "pig", "sheep"};
    for (int i = 0; i < 60; i++) {
      int x, y;
      int SZ = rand.nextInt(14, 26);
      Rectangle rect;
      do {
        x = rand.nextInt(WORLD_SIZE - SZ);
        y = rand.nextInt(WORLD_SIZE - SZ);
        rect = new Rectangle(x, y, SZ, SZ);
      } while (isOverlapping(rect, occupied) || onSpecialTile(rect));
      animals.add(new Animal(x, y, SZ, animalKinds[rand.nextInt(4)]));
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
        if (e.getButton() == MouseEvent.BUTTON3) { // RIGHT CLICK: interact
          interactAt(e.getX() + cameraX, e.getY() + cameraY);
          return;
        }
        player.Attack(bullets, entities, obstacles, mousePos);
        // sword swings / arrow shots shake vegetation along the attack path
        double pcx = player.x + player.size / 2.0, pcy = player.y + player.size * 0.7;
        double[] dir = player.aimDirection();
        for (Obstacle ob : obstacles) {
          if (ob.reactivity() <= 0) continue;
          double ox = ob.x + ob.size / 2.0, oy = ob.y + ob.size * 0.55;
          // projection of (obstacle - player) onto the attack ray
          double rel = (ox - pcx) * dir[0] + (oy - pcy) * dir[1];
          if (rel < 0 || rel > player.range * 2.2) continue;
          double perp = Math.abs((ox - pcx) * -dir[1] + (oy - pcy) * dir[0]);
          if (perp < ob.size * 0.8 + 10) ob.disturb(1.0);
        }
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
        if (pressedKeys.contains(KeyEvent.VK_SPACE))
          player.tryJump(); // cosmetic hop
        if (pressedKeys.contains(KeyEvent.VK_Q)) {
          pressedKeys.remove(KeyEvent.VK_Q); // one drop per key press
          player.dropSelectedItem();
        }
        if (pressedKeys.contains(KeyEvent.VK_E)) {
          pressedKeys.remove(KeyEvent.VK_E);
          showInventory = !showInventory;
        }
        // ---- respawn handling: freeze the world briefly, then revive ----
        if (respawnAt > 0 && System.currentTimeMillis() >= respawnAt) {
          respawnAt = -1;
          player.respawn(WORLD_SIZE / 2, WORLD_SIZE / 2);
          Effects.pop(player.x + player.size / 2.0, player.y + player.size / 2.0,
              player.size * 1.3, Effects.FOAM);
        }
        boolean frozen = respawnAt > 0;
        long nowMs = System.currentTimeMillis();
        if (!frozen && player.GameOver && respawnAt < 0) {
          respawnAt = System.currentTimeMillis() + RESPAWN_HOLD_MS;
        }
        if (!frozen) {
        // ---- obstacle sliding: never hard-stop; slide along the blocking edge ----
        player.updateWithSliding(playerDx, playerDy, obstacles, entities);
        // swim anim: does the player stand on a water tile?
        player.setOnWater(tileAt(Math.floorDiv(player.x + player.size / 2, TS),
                                Math.floorDiv(player.y + player.size / 2, TS)) == 'w');
        // splash: player entering / moving in water
        if (player.onWater()) {
          if (!wasPlayerInWater) { // entering: big splash
            Effects.splash(player.x + player.size / 2.0, player.y + player.size * 0.8,
                player.size / 30.0, true);
            wasPlayerInWater = true;
          } else if (playerDx != 0 || playerDy != 0) { // swimming: periodic ripples
            if (nowMs - lastPlayerRipple > 380) {
              Effects.ripple(player.x + player.size / 2.0, player.y + player.size * 0.8,
                  player.size / 30.0);
              lastPlayerRipple = nowMs;
            }
          }
        } else {
          if (wasPlayerInWater) { // exiting: small splash
            Effects.splash(player.x + player.size / 2.0, player.y + player.size * 0.8,
                player.size / 30.0 * 0.7, true);
          }
          wasPlayerInWater = false;
        }
        // footprints: leave traces on grass/path while walking
        if (!player.onWater() && (playerDx != 0 || playerDy != 0)
            && nowMs - lastFootprint > 240) {
          char t = tileAt(Math.floorDiv(player.x + player.size / 2, TS),
                          Math.floorDiv(player.y + player.size / 2, TS));
          Color col = t == 'p' ? Effects.DIRT_D : Effects.GRASS_D;
          Effects.footprint(player.x + player.size / 2.0, player.y + player.size,
              col, playerDx < 0);
          lastFootprint = nowMs;
        }
        // vegetation reacts to the player brushing past + projectiles. The
        // shake is EDGE-TRIGGERED per plant: entering its reach while moving
        // shakes it once; the plant only re-arms after the player fully LEAVES
        // its reach (standing still nearby never re-shakes it).
        if (nowMs - lastVegCheck > 100) {
          lastVegCheck = nowMs;
          double pcx = player.x + player.size / 2.0, pcy = player.y + player.size * 0.7;
          boolean moving = playerDx != 0 || playerDy != 0;
          for (Obstacle ob : obstacles) {
            if (ob.reactivity() <= 0) continue;
            double ox = ob.x + ob.size / 2.0, oy = ob.y + ob.size * 0.55;
            double dist = Math.hypot(pcx - ox, pcy - oy);
            double reach = ob.size * 0.9 + 14;
            boolean inside = dist < reach;
            boolean wasInside = vegArmed.contains(ob);
            if (inside && moving && !wasInside) {
              ob.disturb(0.5);           // brushing INTO the plant: one shake
              vegArmed.add(ob);          // stays armed until the player leaves
            } else if (!inside && wasInside) {
              vegArmed.remove(ob);       // left the reach: re-arm for next entry
            }
          }
          for (Bullet bu : bullets) {
            if (bu.dead) continue;
            for (Obstacle ob : obstacles) {
              if (ob.reactivity() <= 0) continue;
              double ox = ob.x + ob.size / 2.0, oy = ob.y + ob.size * 0.55;
              if (Math.hypot(bu.x - ox, bu.y - oy) < ob.size * 0.9 + 10) {
                ob.disturb(0.9); // arrow rips through foliage
              }
            }
          }
        }
        player.update(playerDx, playerDy, obstacles, entities);
        } // end !frozen
        bullets.addAll(Entity.thrownBullets); // entity-thrown axes/spears
        Entity.thrownBullets.clear();
        for (Bullet bu : bullets) {
          bu.update(player, obstacles, entities);
        }
        bullets.removeIf(bu -> bu.dead);
        Effects.cull(nowMs);
        for (Entity ene : entities) {
          ene.update(player, obstacles, entities, rand);
          // wading splash + ripples (local state; Entity keeps its own bookkeeping)
          int etx = Math.floorDiv(ene.x + ene.size / 2, TS), ety = Math.floorDiv(ene.y + ene.size / 2, TS);
          boolean inWater = tileAt(etx, ety) == 'w';
          if (inWater && !ene.wasInWater) {
            Effects.splash(ene.x + ene.size / 2.0, ene.y + ene.size * 0.8,
                ene.size / 30.0, true);
          } else if (inWater && ene.moving && nowMs - ene.lastRipple > 420) {
            Effects.ripple(ene.x + ene.size / 2.0, ene.y + ene.size * 0.8, ene.size / 30.0);
            ene.lastRipple = nowMs;
          }
          ene.wasInWater = inWater;
        }
        entities.removeIf(ent -> ent.dead);
        // NPCs + animals live their own lives (frozen during respawn too)
        if (!frozen) {
          for (Entity ent : entities) if (!ent.isMonster()) ent.update(player, obstacles, entities, rand);
          for (Animal an : animals) an.update(player, obstacles);
          // animals fleeing into water splash
          for (Animal an : animals) {
            int atx = Math.floorDiv(an.x + an.size / 2, TS), aty = Math.floorDiv(an.y + an.size / 2, TS);
            boolean inW = tileAt(atx, aty) == 'w';
            if (inW && !an.wasInWater)
              Effects.splash(an.x + an.size / 2.0, an.y + an.size * 0.8, an.size / 30.0, true);
            an.wasInWater = inW;
          }
        }
        // ---- ambient wind drives the idle sway of trees/plants ----
        double wind = 0.25; // sunny breeze
        if (weather.current.equals("windy")) wind = 0.9;
        else if (weather.current.equals("stormy")) wind = 1.3;
        else if (weather.current.equals("snowy")) wind = 0.4;
        else if (weather.current.equals("rainy")) wind = 0.55;
        Obstacle.windStrength = wind;
        // ---- ground state: stamp entities + weather per tile ----
        stampGround();
        // weather particles + tick
        weather.tick(null, getWidth(), getHeight());

        cameraX = player.x - getWidth() / 2 + player.size / 2;
        cameraY = player.y - getHeight() / 2 + player.size / 2;
        repaint();
      }
    });
    timer.start();
  }
    // would a block at (nx,ny) overlap any existing blocking obstacle?
  boolean blockedByObstacle(int nx, int ny, Obstacle self) {
    Rectangle r = new Rectangle(nx, ny, self.size, self.size);
    for (Obstacle ob : obstacles) {
      if (ob == self || !ob.blocks()) continue;
      if (ob.getBounds().intersects(r)) return true;
    }
    return false;
  }

  boolean isGrassTile(int tx, int ty) { return tileAt(tx, ty) == 'g'; }

  // every tile covered by a block at (x,y,size) is grass (no road/water/path)
  boolean allGrass(int x, int y, int size) {
    int tx0 = Math.floorDiv(x, TS), ty0 = Math.floorDiv(y, TS);
    int tx1 = Math.floorDiv(x + size - 1, TS), ty1 = Math.floorDiv(y + size - 1, TS);
    for (int ty = ty0; ty <= ty1; ty++)
      for (int tx = tx0; tx <= tx1; tx++)
        if (tileAt(tx, ty) != 'g') return false;
    return true;
  }

  // does a block at (x,y,size) cover any road ('p') tile?
  boolean coversRoad(int x, int y, int size) {
    int tx0 = Math.floorDiv(x, TS), ty0 = Math.floorDiv(y, TS);
    int tx1 = Math.floorDiv(x + size - 1, TS), ty1 = Math.floorDiv(y + size - 1, TS);
    for (int ty = ty0; ty <= ty1; ty++)
      for (int tx = tx0; tx <= tx1; tx++)
        if (tileAt(tx, ty) == 'p') return true;
    return false;
  }

  // find a tile-aligned all-grass rectangle away from the map border.
    // returns {x, y, zoneX, zoneY, zoneW, zoneH} px or null
    private int[] findGrassSite(int wTiles, int hTiles, int margin, int tries) {
        for (int t = 0; t < tries; t++) {
            int tx = rand.nextInt(margin, MAP_TILES - margin - wTiles);
            int ty = rand.nextInt(margin, MAP_TILES - margin - hTiles);
            boolean ok = true;
            for (int y = ty - 1; y <= ty + hTiles && ok; y++)
                for (int x = tx - 1; x <= tx + wTiles && ok; x++)
                    ok &= tileAt(x, y) == 'g';
            if (!ok) continue;
            Rectangle zone = new Rectangle((tx - 1) * TS, (ty - 1) * TS,
                (wTiles + 2) * TS, (hTiles + 2) * TS);
            if (isOverlapping(zone, occupied)) continue;
            return new int[]{tx * TS, ty * TS, (tx - 1) * TS, (ty - 1) * TS,
                (wTiles + 2) * TS, (hTiles + 2) * TS};
        }
        return null;
    }

  // drawing stuff
  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    paintCalls++;
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    drawFloor(g2);
    drawWaterGlints(g2);
    Entity.drawGroundItems(g2, cameraX, cameraY);
    Effects.drawUnder(g2, cameraX, cameraY, System.currentTimeMillis());
    player.draw(g, cameraX, cameraY, getWidth(), getHeight());
    for (Bullet bu : bullets) {
      bu.draw(g, cameraX, cameraY);
    }
    for (Obstacle ob : obstacles) {
      ob.draw(g, cameraX, cameraY, System.currentTimeMillis());
    }
    for (Animal an : animals) {
      an.draw(g, cameraX, cameraY);
    }
    for (Entity npc : entities) {
      npc.draw(g, cameraX, cameraY);
    }
    for (Entity ene : entities) {
      ene.draw(g, cameraX, cameraY);
    }
    Effects.drawOver(g2, cameraX, cameraY, System.currentTimeMillis());
    // day/night + weather tint sits above everything but the HUD
    weather.drawOverlay(g2, getWidth(), getHeight(), cameraX, cameraY);
    // respawn / death overlay
    if (respawnAt > 0 || player.GameOver) {
      g2.setColor(new Color(40, 0, 0, 120));
      g2.fillRect(0, 0, getWidth(), getHeight());
      g2.setColor(Color.WHITE);
      g2.setFont(new Font("Arial", Font.BOLD, 40));
      g2.drawString("You died — respawning...", getWidth() / 2 - 190, getHeight() / 2);
    }
    // HUD overlay: proves this window is painting and shows sprite load status
    g2.setColor(new Color(255, 255, 255, 190));
    g2.fillRect(4, 4, 360, 16);
    g2.setColor(Color.BLACK);
    g2.setFont(new Font("Monospaced", Font.BOLD, 12));
    g2.drawString("FlatLand | " + weather.label() + " | paints=" + paintCalls
        + " sprites=" + SpriteAnim.loadedCount + " ok/" + SpriteAnim.failedCount + " fail", 8, 16);
    if (showInventory) drawInventoryPanel(g2);
  }

  // stamp entity occupancy + weather onto the Ground grid (cheap: only
  // visible-area tiles + entity tiles)
  private long groundStampBlink = 0;
  private void stampGround() {
    long now = System.currentTimeMillis();
    // player tile
    int ptx = Math.floorDiv(player.x + player.size / 2, TS), pty = Math.floorDiv(player.y + player.size / 2, TS);
    clearStamps();
    if (ptx >= 0 && pty >= 0 && ptx < MAP_TILES && pty < MAP_TILES)
      ground[pty][ptx].setPlayerOn(true);
    for (Entity ene : entities) {
      int tx = Math.floorDiv(ene.x + ene.size / 2, TS), ty = Math.floorDiv(ene.y + ene.size / 2, TS);
      if (tx >= 0 && ty >= 0 && tx < MAP_TILES && ty < MAP_TILES)
        ground[ty][tx].setEnemyOn(true); // monsters are entities too
    }
    // weather label onto visible tiles (cheap loop)
    String w = weather.current;
    int x0 = Math.max(0, Math.floorDiv(cameraX, TS)), y0 = Math.max(0, Math.floorDiv(cameraY, TS));
    int x1 = Math.min(MAP_TILES - 1, x0 + getWidth() / TS + 1);
    int y1 = Math.min(MAP_TILES - 1, y0 + getHeight() / TS + 1);
    for (int ty = y0; ty <= y1; ty++)
      for (int tx = x0; tx <= x1; tx++)
        ground[ty][tx].setWeather(w);
  }
  private boolean stampsClear = false;
  private void clearStamps() {
    if (stampsClear) return;
    for (Ground[] row : ground) for (Ground g : row) { g.playerOn = false; g.enemyOn = false; }
    stampsClear = true;
  }

  // animated water: shimmering glints over interior water tiles using the
  // fx glint frames, phase-shifted per tile so it doesn't strobe uniformly
  private static final Image[] GLINTS = {
      SpriteAnim.load("assets/fx/glint0.png"),
      SpriteAnim.load("assets/fx/glint1.png"),
      SpriteAnim.load("assets/fx/glint2.png"),
  };
  private void drawWaterGlints(Graphics2D g) {
    if (GLINTS[0] == null) return;
    long now = System.currentTimeMillis();
    int frame = (int) ((now / 420) % GLINTS.length);
    int startX = Math.floorDiv(cameraX, TS) * TS;
    int startY = Math.floorDiv(cameraY, TS) * TS;
    for (int wx = startX; wx < cameraX + getWidth(); wx += TS) {
      for (int wy = startY; wy < cameraY + getHeight(); wy += TS) {
        int tx = Math.floorDiv(wx, TS), ty = Math.floorDiv(wy, TS);
        if (tileAt(tx, ty) != 'w') continue;
        // interior-only: skip fringe tiles (all 4 neighbours water)
        if (tileAt(tx, ty - 1) != 'w' || tileAt(tx, ty + 1) != 'w'
            || tileAt(tx - 1, ty) != 'w' || tileAt(tx + 1, ty) != 'w') continue;
        int ph = Math.floorMod(tx * 31 + ty * 17, 3); // per-tile phase
        Image gl = GLINTS[(frame + ph) % GLINTS.length];
        if (gl == null) continue;
        int dx = wx - cameraX, dy = wy - cameraY;
        g.drawImage(gl, dx, dy, TS, TS, null);
      }
    }
  }

  // ---- RIGHT-CLICK interaction ----------------------------------------
  // Resolves what the click hit (in reach order: ground items first, then
  // interactable obstacles: crops to harvest, chests to open, plants to
  // rustle, trees to shake). Range-gated to the player's reach so it stays
  // a local action, not a world-editing tool.
  void interactAt(int wx, int wy) {
    double reach = 64 + player.size; // ~2 tiles from the player
    double pdx = player.x + player.size / 2.0, pdy = player.y + player.size / 2.0;
    if (Math.hypot(wx - pdx, wy - pdy) > reach) return; // too far away
    // 1) ground items: pick up directly into the bag (weight-gated)
    for (int i = Entity.groundItems.size() - 1; i >= 0; i--) {
      Entity.GroundItem gi = Entity.groundItems.get(i);
      if (Math.hypot(gi.x - wx, gi.y - wy) < 20) {
        if (player.bag.canPickUp(gi.item)) {
          player.bag.add(gi.item);
          Entity.groundItems.remove(i);
          Effects.sparkle(gi.x, gi.y, Effects.FOAM, 3);
        } else Effects.sparkle(gi.x, gi.y, new Color(200, 60, 60), 3);
        return;
      }
    }
    // 2) interactable obstacles: crops (harvest -> food item on the ground),
    //    chests (pop 1-2 loot items), everything reactive (shake/disturb)
    double bestD = 24;
    Obstacle best = null;
    for (Obstacle ob : obstacles) {
      double d = Math.hypot(ob.x + ob.size / 2.0 - wx, ob.y + ob.size / 2.0 - wy);
      if (d < bestD) { bestD = d; best = ob; }
    }
    if (best != null) {
      if (best instanceof Obstacle.Crop) {
        Item produce = ((Obstacle.Crop) best).harvest();
        if (produce != null) {
          // harvested produce pops OUT of the crop, then auto-pickup takes it
          Entity.groundItems.add(new Entity.GroundItem(produce,
              best.x + best.size / 2, best.y + best.size));
          obstacles.remove(best);
          Effects.debris(best.x + best.size / 2.0, best.y + best.size * 0.5, false, 4);
        } else best.disturb(0.9); // not ripe: rustle
      } else if (best instanceof Obstacle.Chest) {
        // chest opens: 1-2 loot items pop out onto the ground
        int n = 1 + rand.nextInt(2);
        for (int i = 0; i < n; i++) {
          Item loot = rand.nextBoolean() ? ItemCatalog.coin() : ItemCatalog.randomPotion(rand);
          Entity.groundItems.add(new Entity.GroundItem(loot,
              best.x + best.size / 2 + rand.nextInt(-12, 12),
              best.y + best.size + rand.nextInt(8)));
        }
        Effects.sparkle(best.x + best.size / 2.0, best.y + best.size / 2.0, new Color(255, 200, 37), 5);
        obstacles.remove(best); // opened: the chest is spent
      } else {
        best.disturb(1.0); // trees/plants/barrels: shake + shed
      }
      return;
    }
    // 3) nothing hit: a small dust poke so the click still reads
    Effects.stonePuff(wx, wy, 2);
  }

  // ---- inventory UI (E): pack-palette panel, item sprites in slots, weight
  // bar, and a capacity gauge. The player's selected slot is highlighted.
  private void drawInventoryPanel(Graphics2D g) {
    java.util.List<Item> items = player.bag.list();
    int W = getWidth(), H = getHeight();
    int cols = 5, rows = 2;
    int slot = 44, pad = 6;
    int gridW = cols * slot + (cols - 1) * pad;
    int panelW = gridW + 36, panelH = 190;
    int px = W / 2 - panelW / 2, py = H / 2 - panelH / 2;
    // frame: dark wood base + lighter wood border (Kenmi palette)
    g.setColor(new Color(0x2E, 0x2B, 0x26));
    g.fillRoundRect(px, py, panelW, panelH, 12, 12);
    g.setColor(new Color(0xE4, 0xA6, 0x72));
    g.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 10, 10);
    g.setColor(new Color(0xFD, 0xF7, 0xED));
    g.setFont(new Font("Monospaced", Font.BOLD, 14));
    g.drawString("INVENTORY  [E] close  [Q] drop", px + 18, py + 26);
    // slots
    for (int i = 0; i < cols * rows; i++) {
      int cx = px + 18 + (i % cols) * (slot + pad);
      int cy = py + 40 + (i / cols) * (slot + pad);
      boolean sel = i == player.currentWeaponIndex;
      g.setColor(new Color(0x1C, 0x1A, 0x17, 220));
      g.fillRect(cx, cy, slot, slot);
      g.setColor(sel ? new Color(0xFF, 0xC8, 0x25) : new Color(0x8B, 0x5A, 0x2B));
      g.drawRect(cx, cy, slot, slot);
      if (i < items.size()) {
        Item it = items.get(i);
        if (it.sprite != null)
          g.drawImage(it.sprite, cx + 6, cy + 6, 32, 32, null);
        g.setColor(new Color(0xFD, 0xF7, 0xED));
        g.setFont(new Font("Monospaced", Font.PLAIN, 9));
        String label = it.name.length() > 9 ? it.name.substring(0, 9) : it.name;
        g.drawString(label, cx + 3, cy + slot - 4);
        g.setColor(new Color(0xBD, 0xBD, 0xA5));
        g.drawString(it.weight + "kg", cx + 3, cy + 10);
      }
    }
    // capacity gauge: carried / 60
    double carried = player.bag.carriedWeight();
    double cap = player.bag.capacity();
    int gy = py + panelH - 34;
    g.setColor(new Color(0x1C, 0x1A, 0x17));
    g.fillRect(px + 18, gy, gridW, 12);
    g.setColor(carried / cap > 0.85 ? new Color(0xE7, 0x4C, 0x3C) : new Color(0x5A, 0xC5, 0x4F));
    g.fillRect(px + 18, gy, (int) (gridW * carried / cap), 12);
    g.setColor(new Color(0xFD, 0xF7, 0xED));
    g.setFont(new Font("Monospaced", Font.BOLD, 11));
    g.drawString(String.format("carry %.1f / %.0f", carried, cap), px + 20, gy + 26);
    // selected-item hint
    if (player.currentWeaponIndex < items.size()) {
      Item sel = items.get(player.currentWeaponIndex);
      g.drawString(sel.interactHint(), px + 220, gy + 26);
    }
  }

  // captures this window's pixels straight off the display; proves it is not blank
  void selfCheck() {
    try {
      Point loc = getLocationOnScreen();
      Rectangle r = new Rectangle(loc, getSize());
      BufferedImage shot = new Robot().createScreenCapture(r);
      int black = 0, floor = 0, white = 0;
      for (int y = 0; y < shot.getHeight(); y++)
        for (int x = 0; x < shot.getWidth(); x++) {
          int c = shot.getRGB(x, y);
          int rr = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, bb = c & 0xFF;
          if (rr < 15 && gg < 15 && bb < 15) black++;
          else if (rr < 80 && gg < 80 && bb < 80) floor++;
          if (rr > 240 && gg > 240 && bb > 240) white++;
        }
      ImageIO.write(shot, "png", new File("/tmp/opencode/scratch/selfcheck.png"));
      System.err.println("[FlatLand] selfCheck: blackPx=" + black + " floorPx=" + floor
          + " whitePx=" + white + " -> " + (floor > 1000 ? "RENDERING" : "BLANK/BLACK"));
    } catch (Exception ex) {
      System.err.println("[FlatLand] selfCheck failed: " + ex);
    }
  }

  // renders the pre-generated world text: one char per tile, mapped to art
  private void drawFloor(Graphics2D g) {
    boolean hasArt = GRASS[0] != null;
    int startX = Math.floorDiv(cameraX, TS) * TS;
    int startY = Math.floorDiv(cameraY, TS) * TS;
    for (int wx = startX; wx < cameraX + getWidth(); wx += TS) {
      for (int wy = startY; wy < cameraY + getHeight(); wy += TS) {
        int tx = Math.floorDiv(wx, TS);
        int ty = Math.floorDiv(wy, TS);
        int dx = wx - cameraX, dy = wy - cameraY;
        if (!hasArt) {
          g.setColor(new Color(0x2E, 0x4B, 0x2A));
          g.fillRect(dx, dy, TS, TS);
          continue;
        }
        char t = tileAt(tx, ty);
        int hash = Math.floorMod((tx * 73856093) ^ (ty * 19349663) ^ (int) worldSeed, 100);
        if (t == 'w' && WATER_C != null) {
          // grass underlay so transparent fringe pixels can't leak the background
          g.drawImage(GRASS[hash % GRASS.length], dx, dy, TS, TS, null);
          // water draws its own grass bank at any land edge, incl. path
          g.drawImage(blobCell(WATER_C, (a, b) -> tileAt(a, b) == 'w', tx, ty), dx, dy, TS, TS, null);
        } else if (t == 'p' && PATH_C != null) {
          g.drawImage(GRASS[hash % GRASS.length], dx, dy, TS, TS, null);
          // dirt runs flush to the water edge; water counts as same blob layer
          g.drawImage(blobCell(PATH_C, (a, b) -> tileAt(a, b) != 'g', tx, ty), dx, dy, TS, TS, null);
        } else {
          Image tile = GRASS[hash % GRASS.length];
          g.drawImage(tile, dx, dy, TS, TS, null);
          if (t == 'g' && hash >= 97 && DECOR_SHEET != null) { // ~3% decor cells
            int[] c = DECOR[hash % DECOR.length];
            g.drawImage(DECOR_SHEET, dx, dy, dx + TS, dy + TS, c[0], c[1], c[0] + 16, c[1] + 16, null);
          }
        }
      }
    }
  }

  // text-to-tile lookup with edge clamp (outside the map is grass)
  char tileAt(int tx, int ty) {
    if (tx < 0 || ty < 0 || tx >= MAP_TILES || ty >= MAP_TILES) return 'g';
    return worldText[ty][tx];
  }

  // fixpoint pass: trim tiles that can't be drawn unambiguously (rerun after
  // MapGen carves roads)
  void cleanupRepresentability() {
boolean changed = true;
    while (changed) {
      changed = false;
      for (int ty = 0; ty < MAP_TILES; ty++)
        for (int tx = 0; tx < MAP_TILES; tx++) {
          char t = worldText[ty][tx];
          if (t != 'w' && t != 'p') continue;
          boolean u = blobSame(t, tx, ty - 1), d = blobSame(t, tx, ty + 1);
          boolean l = blobSame(t, tx - 1, ty), r = blobSame(t, tx + 1, ty);
          if ((u && d && l && r) || ((u || d) && (l || r))) continue;
          boolean diag = blobSame(t, tx - 1, ty - 1) || blobSame(t, tx + 1, ty - 1)
              || blobSame(t, tx - 1, ty + 1) || blobSame(t, tx + 1, ty + 1);
          // diagonal-ONLY contact renders as a corner wedge and is kept. A
          // tile with orthogonal contact must still satisfy the 2-wide rule
          // (diagonals never rescue a 1-wide pinch channel).
          if (!u && !d && !l && !r && diag) continue;
          boolean nearPath = tileAt(tx + 1, ty) == 'p' || tileAt(tx - 1, ty) == 'p'
              || tileAt(tx, ty + 1) == 'p' || tileAt(tx, ty - 1) == 'p';
          worldText[ty][tx] = (t == 'w' && nearPath) ? 'p' : 'g';
          changed = true;
        }
    }
  }

  // the world generator: one big chunk of text built up in layers
  // '.' -> 'w'/'p'/'g' shaped by noise, then text-level cleanups
  void generateWorldText() {
    for (char[] row : worldText) Arrays.fill(row, 'g');
    // 1) shape layer: same fields as before, identical shapes
    for (int ty = 0; ty < MAP_TILES; ty++)
      for (int tx = 0; tx < MAP_TILES; tx++)
        if (waterField(tx, ty)) worldText[ty][tx] = 'w';
        else if (pathField(tx, ty)) worldText[ty][tx] = 'p';
    // 2) swim-free clearing around the spawn point
    for (int ty = SPAWN_TX - 10; ty <= SPAWN_TX + 10; ty++)
      for (int tx = SPAWN_TX - 10; tx <= SPAWN_TX + 10; tx++) {
        double dx = tx - SPAWN_TX, dy = ty - SPAWN_TX;
        if (dx * dx + dy * dy <= 36 && tileAt(tx, ty) == 'w') worldText[ty][tx] = 'g';
      }
    // 3) representability cleanup (shared with post-road pass)
    cleanupRepresentability();
  }

  // blob membership used by both cleanup and autotiling: water only joins water,
  // path joins path and water (dirt runs flush to the shoreline)
  boolean blobSame(char t, int tx, int ty) {
    char n = tileAt(tx, ty);
    return t == 'w' ? n == 'w' : n != 'g';
  }

  char[][] worldMap() { return worldText; }

  // ---- algorithmic autotiling -------------------------------------------------
  // Each 16px sheet cell is measured once into a 4x4 "terrain quadrant" bitmap:
  // bit qy*4+qx set = terrain art, clear = grass fringe, and ambiguous
  // quadrants are don't-care. For a tile's 8-neighbour mask the ideal bitmap is
  // derived (terrain continues iff the neighbour is the same terrain) and the
  // cell with the fewest quadrant mismatches is chosen. No hand-mapped cases:
  // the table is computed from the sheet pixels, so every boundary tile draws
  // terrain exactly where the blob continues and fringe exactly where it ends
  // -- one fringe per boundary, never two.
  private static final Map<Image[][], int[][]> AUTOTILE = new IdentityHashMap<>();

  // per sheet: [0] = mask->cell index table (row*cols+col), [1] = cell feature
  // bitmaps, [2] = cell "decided" masks, [3] = pure-interior cell indices
  private static int[][] autotile(Image[][] c) {
    int[][] a = AUTOTILE.get(c);
    if (a == null) AUTOTILE.put(c, a = buildAutotile(c));
    return a;
  }

  // terrain art = opaque pixel that is not grass-green (the pack's fringe green)
  private static boolean featurePx(int rgb) {
    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
    return ((rgb >>> 24) & 0xFF) > 60 && !(g > r && g > b && g > 100);
  }

  private static int[][] buildAutotile(Image[][] c) {
    int cols = c[0].length, n = c.length * cols;
    int[] feat = new int[n], care = new int[n];
    boolean[] usable = new boolean[n];
    for (int i = 0; i < n; i++) {
      if (!(c[i / cols][i % cols] instanceof BufferedImage)) continue;
      int[] px = ((BufferedImage) c[i / cols][i % cols]).getRGB(0, 0, 16, 16, null, 0, 16);
      for (int qy = 0; qy < 4; qy++)
        for (int qx = 0; qx < 4; qx++) {
          int f = 0, gr = 0;
          for (int py = qy * 4; py < qy * 4 + 4; py++)
            for (int pxx = qx * 4; pxx < qx * 4 + 4; pxx++) {
              int rgb = px[py * 16 + pxx];
              if (((rgb >>> 24) & 0xFF) <= 60) continue;
              if (featurePx(rgb)) f++; else gr++;
            }
          if (f >= 5 && f > gr * 2) { feat[i] |= 1 << (qy * 4 + qx); care[i] |= 1 << (qy * 4 + qx); }
          else if (gr >= 5 && gr > f * 2) care[i] |= 1 << (qy * 4 + qx);
        }
      boolean center = true;
      for (int qy = 1; qy <= 2 && center; qy++)
        for (int qx = 1; qx <= 2; qx++)
          if ((feat[i] & (1 << (qy * 4 + qx))) == 0) { center = false; break; }
      // usable = paints terrain and is mostly decided; ring-corner cells are
      // grass-centred with a single terrain wedge, so requiring full centre
      // terrain would drop them. Cells that barely decide anything (empty or
      // mostly transparent) must never win a mask, hence the care floor.
      usable[i] = feat[i] != 0 && Integer.bitCount(care[i]) >= 8;
    }
    int[] inter = new int[n];
    int ni = 0;
    for (int i = 0; i < n; i++)
      if (usable[i] && feat[i] == 0xFFFF && care[i] == 0xFFFF) inter[ni++] = i;
    if (ni == 0) for (int i = 0; i < n; i++) if (usable[i]) inter[ni++] = i;
    int[] table = new int[256];
    Arrays.fill(table, cols + 1); // c[1][1] fallback
    for (int mask = 0; mask < 256; mask++) {
      boolean U = (mask & 1) != 0, D = (mask & 2) != 0, L = (mask & 4) != 0, R = (mask & 8) != 0;
      int best = -1, bestScore = Integer.MAX_VALUE;
      for (int i = 0; i < n; i++) {
        if (!usable[i]) continue;
        int score = 0;
        for (int q = 0; q < 16 && score < bestScore; q++) {
          int qx = q & 3, qy = q >> 2, ideal;
          if (qx >= 1 && qx <= 2 && qy >= 1 && qy <= 2) ideal = 1;
          else if (qx >= 1 && qx <= 2) ideal = (qy == 0 ? U : D) ? 1 : 0;
          else if (qy >= 1 && qy <= 2) ideal = (qx == 0 ? L : R) ? 1 : 0;
          else { // corner quadrant: decided only when both flanking edges agree
            boolean side = qx == 0 ? L : R, cap = qy == 0 ? U : D;
            if (side && cap) ideal = (mask & (qx == 0 ? (qy == 0 ? 16 : 64) : (qy == 0 ? 32 : 128))) != 0 ? 1 : 0;
            else if (!side && !cap) ideal = 0;
            else continue; // mixed edges -> no corner art constraint
          }
          // undecided (soft-edge) quadrants are the sheet's intentional
          // ambiguity and cost nothing; only decided quadrants can mismatch
          if ((care[i] & (1 << q)) != 0 && ((feat[i] & (1 << q)) != 0) != (ideal == 1)) score++;
        }
        if (score < bestScore) { bestScore = score; best = i; }
      }
      if (best >= 0) table[mask] = best;
    }
    return new int[][] { table, feat, care, Arrays.copyOf(inter, ni) };
  }

  private static Image blobCell(Image[][] c, java.util.function.BiFunction<Integer, Integer, Boolean> same,
      int tx, int ty) {
    int[][] a = autotile(c);
    int mask = (same.apply(tx, ty - 1) ? 1 : 0) | (same.apply(tx, ty + 1) ? 2 : 0)
        | (same.apply(tx - 1, ty) ? 4 : 0) | (same.apply(tx + 1, ty) ? 8 : 0)
        | (same.apply(tx - 1, ty - 1) ? 16 : 0) | (same.apply(tx + 1, ty - 1) ? 32 : 0)
        | (same.apply(tx - 1, ty + 1) ? 64 : 0) | (same.apply(tx + 1, ty + 1) ? 128 : 0);
    if (mask == 0xFF && a[3].length > 0) { // plain interior: keep hash variety
      int v = Math.floorMod((tx * 92837111) ^ (ty * 689287499), a[3].length);
      return c[a[3][v] / c[0].length][a[3][v] % c[0].length];
    }
    // Hand-mapped corner geometry (pixel-verified in an earlier session).
    // The algorithmic scorer under-fits wedge cells: a 2-edge corner mask
    // (e.g. land N + land E) must draw a corner tile, not a straight border.
    int corner = cornerCell(c, mask);
    if (corner >= 0) return c[corner / c[0].length][corner % c[0].length];
    int idx = a[0][mask];
    return c[idx / c[0].length][idx % c[0].length];
  }

  // mask bits (bit set = SAME terrain continues there): U=1 D=2 L=4 R=8
  // TL=16 TR=32 BL=64 BR=128. Hand-mapped corner geometry, pixel-verified in
  // an earlier session (ring: wedge cells r0c0/r0c2/r2c0/r2c2 = water poke in
  // one quarter; inner-corner cells r3c0/r3c1/r4c0/r4c1 = grass poke in one
  // quadrant) + diagonal-chain extension. Returns cell INDEX (row*cols+col).
  private static int cornerCell(Image[][] c, int mask) {
    boolean U = (mask & 1) != 0, D = (mask & 2) != 0, L = (mask & 4) != 0, R = (mask & 8) != 0;
    boolean TL = (mask & 16) != 0, TR = (mask & 32) != 0, BL = (mask & 64) != 0, BR = (mask & 128) != 0;
    int cols = c[0].length;
    // DIAGONAL-ONLY contact (checkerboard/diagonal chains): the blob touches
    // its partner through one corner -- render as a water wedge poking toward
    // the partner, grass on the outer two edges (the user-facing fix)
    if (mask == 128) return 0;             // partner SE -> water poke BR (r0c0)
    if (mask == 64) return 2;               // partner SW -> poke BL (r0c2)
    if (mask == 32) return 2 * cols;       // partner NE -> poke TR (r2c0)
    if (mask == 16) return 2 * cols + 2;   // partner NW -> poke TL (r2c2)
    // OUTER corners: land on two adjacent edges (regardless of diagonals --
    // the diagonal-only partner case must also land here, e.g. mask 149 =
    // land E+S with water diag SE). Wedge cell pokes water into the far
    // corner, grass fringes exactly along the two land edges.
    if (!U && !L && D && R) return 0;            // land N+W -> poke BR
    if (!U && !R && D && L) return 2;              // land N+E -> poke BL
    if (!D && !L && U && R) return 2 * cols;       // land S+W -> poke TR
    if (!D && !R && U && L) return 2 * cols + 2;  // land S+E -> poke TL
    // INNER corners: blob continues on all 4 edges, land pokes in from one
    // diagonal -> grass quadrant reaching toward the land corner
    if (U && D && L && R && !BR) return 3 * cols;      // land diag SE -> grass BR (r3c0)
    if (U && D && L && R && !BL) return 3 * cols + 1;  // land diag SW -> grass BL (r3c1)
    if (U && D && L && R && !TR) return 4 * cols;      // land diag NE -> grass TR (r4c0)
    if (U && D && L && R && !TL) return 4 * cols + 1;  // land diag NW -> grass TL (r4c1)
    return -1;
  }

  // slice a tile sheet into 16px cells
  private static Image[][] sheetGrid(Image sheet) {
    if (!(sheet instanceof BufferedImage)) return null;
    BufferedImage bi = (BufferedImage) sheet;
    Image[][] g = new Image[bi.getHeight() / 16][bi.getWidth() / 16];
    for (int cy = 0; cy < g.length; cy++)
      for (int cx = 0; cx < g[cy].length; cx++)
        g[cy][cx] = bi.getSubimage(cx * 16, cy * 16, 16, 16);
    return g;
  }

  // deterministic hash -> 0..1
  private static int h2(int x, int y, int salt, long seed) {
    int h = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791) ^ (int) seed;
    h ^= h >>> 16; h *= 0x7feb352d; h ^= h >>> 15; h *= 0x846ca68b; h ^= h >>> 16;
    return h;
  }

  // value noise (bilinear + smoothstep over hash grid)
  private static double vnoise(double fx, double fy, int salt, long seed) {
    int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
    double u = fx - x0, v = fy - y0;
    u = u * u * (3 - 2 * u);
    v = v * v * (3 - 2 * v);
    double a = (h2(x0, y0, salt, seed) >>> 8) / 16777216.0;
    double b = (h2(x0 + 1, y0, salt, seed) >>> 8) / 16777216.0;
    double c = (h2(x0, y0 + 1, salt, seed) >>> 8) / 16777216.0;
    double d = (h2(x0 + 1, y0 + 1, salt, seed) >>> 8) / 16777216.0;
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
  }

  // smooth noise field over the tile grid
  private double field(int tx, int ty, int salt, double scale) {
    return vnoise(tx / scale, ty / scale, salt, worldSeed) * 0.75
        + vnoise(tx / (scale * 0.4), ty / (scale * 0.4), salt + 100, worldSeed) * 0.25;
  }

  // noise field probes used only by generateWorldText()
  private boolean waterField(int tx, int ty) {
    double dx = tx - SPAWN_TX, dy = ty - SPAWN_TX;
    double clear = Math.max(0, 1.0 - Math.sqrt(dx * dx + dy * dy) / 10.0) * 0.6;
    return field(tx, ty, 11, 9.0) + clear < 0.29;
  }

  private boolean pathField(int tx, int ty) {
    return field(tx, ty, 23, 7.0) > 0.75;
  }

  // speckle the pack's flat grass color with its own palette shades
  private static Image[] makeGrass() {
    Random r = new Random(12345);
    Color base = new Color(0x3E, 0x89, 0x48);
    Color[] darks = {new Color(0x34, 0x74, 0x3D), new Color(0x26, 0x5C, 0x42)};
    Color[] lights = {new Color(0x5A, 0xC5, 0x4F), new Color(0x4B, 0xA5, 0x4E)};
    Image[] tiles = new Image[4];
    for (int v = 0; v < tiles.length; v++) {
      BufferedImage b = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
      Graphics2D gg = b.createGraphics();
      gg.setColor(base);
      gg.fillRect(0, 0, 16, 16);
      for (int i = 0; i < 24; i++) { // dark blades
        gg.setColor(darks[r.nextInt(darks.length)]);
        gg.fillRect(r.nextInt(16), r.nextInt(16), 1, r.nextBoolean() ? 2 : 1);
      }
      for (int i = 0; i < 12; i++) { // light speckles
        gg.setColor(lights[r.nextInt(lights.length)]);
        gg.fillRect(r.nextInt(16), r.nextInt(16), 1, 1);
      }
      gg.dispose();
      tiles[v] = b;
    }
    return tiles;
  }
}
