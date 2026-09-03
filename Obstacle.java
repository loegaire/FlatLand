import java.awt.*;

// Base world object. ALL scenery shares this one disturb/shake implementation --
// subclasses only provide art + how strongly they react (reactivity 0..1).
//
// ART/BLOCK MATCHING (the core rule): the collision block is ground truth.
// The art's trunk+root is anchored so the ROOT BASE sits exactly on the block's
// bottom edge and the ROOT WIDTH is scaled to fill the block width exactly --
// measured per sprite (rootCx / rootBottom / anchorWidth in cell-local px).
// Blocking kinds quantize their block to whole tiles: big tree = 3x3, small
// tree/stone/chest = 1x1. Decor (plants/pebbles) never blocks; it just anchors
// its base on the ground point.
public abstract class Obstacle {
    static final int TS2 = 32; // tile size (matches MainGame.TS)
    int x, y;   // top-left of the collision block
    int size;   // block edge in px (tile-quantized for blocking kinds)

    // ---- shared disturb machinery (do NOT reimplement in subclasses) ----
    private long shakeStart = -100000;
    private double shakeAmp = 0;
    private long lastDisturb = -100000;   // cooldown: min ms between shakes
    private static final long DISTURB_COOLDOWN_MS = 500;
    private static final java.util.Random R = new java.util.Random();

    // how strongly this object reacts to disturbance (0 = immovable, 1 = full)
    abstract double reactivity();

    void disturb(double power) {
        long now = System.currentTimeMillis();
        if (now - lastDisturb < DISTURB_COOLDOWN_MS) return; // cooldown
        if (reactivity() <= 0) return;
        lastDisturb = now;
        shakeAmp = Math.min(1.2, power * 0.8 * reactivity());
        shakeStart = now;
        shedDebris();
    }

    // material-specific debris when shaken (trees shed leaves, stones puff dust)
    void shedDebris() {}

    // current wobble: [xOffsetPx, damp] or null when at rest
    final double[] wobble(long now) {
        long t = now - shakeStart;
        if (t < 0 || t >= 700 || shakeAmp <= 0.01) return null;
        double damp = Math.exp(-t / 260.0);
        double off = Math.sin(t / 1000.0 * Math.PI * 7) * shakeAmp * damp;
        return new double[]{ off, damp };
    }

    // ambient wind sway (idle animation for trees/plants)
    static volatile double windStrength = 0.3;
    static long windT0 = System.currentTimeMillis();
    static double windSway(long now) {
        return Math.sin((now - windT0) / 1000.0 * 0.9) * windStrength;
    }

    public void draw(Graphics g, int cameraX, int cameraY) {
        draw(g, cameraX, cameraY, System.currentTimeMillis());
    }

    public void draw(Graphics g, int cameraX, int cameraY, long now) {
        Image img = art();
        Rectangle src = srcRect();
        // block geometry (screen coords)
        int bcx = x - cameraX + size / 2;     // block centre x
        int bby = y - cameraY + size;         // block bottom edge (ground line)
        if (img == null) {
            g.setColor(fallbackColor());
            g.fillRect(x - cameraX, y - cameraY, size, size);
            return;
        }
        // root anchoring: scale so the trunk+root width fills the block
        double zoom = size / anchorWidth();
        int dw = (int) Math.round(src.width * zoom);
        int dh = (int) Math.round(src.height * zoom);
        // place the cell so the root base (anchorBottom row) lands ON the
        // block bottom and the root centre (anchorCx) lands on the block centre
        int dx0 = (int) Math.round(bcx - anchorCx() * zoom);
        int dy0 = (int) Math.round(bby - anchorBottom() * zoom);

        double[] wb = wobble(now);
        double sway = windSway(now) * reactivity();
        if (wb != null) sway += wb[0] * 2.0; // disturbed wobble adds to the sway
        if (Math.abs(sway) > 0.001) { // swing around the root base
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(bcx, bby);
            g2.rotate(Math.toRadians(sway * 2.0));
            g2.translate(-bcx, -bby);
            g2.drawImage(img, dx0, dy0, dx0 + dw, dy0 + dh,
                src.x, src.y, src.x + src.width, src.y + src.height, null);
            g2.dispose();
            return;
        }
        g.drawImage(img, dx0, dy0, dx0 + dw, dy0 + dh,
            src.x, src.y, src.x + src.width, src.y + src.height, null);
    }

    // ---- root anchor geometry, measured per sprite (cell-local px) ----
    // anchorCx: centre x of the trunk+root base within the source cell
    abstract double anchorCx();
    // anchorBottom: y of the root base row (bottom content edge + 1) in the cell
    abstract double anchorBottom();
    // anchorWidth: widest span of the trunk+root zone -- the block fills THIS
    abstract double anchorWidth();

    // ---- subclass art contract ----
    abstract Image art();
    abstract java.awt.Rectangle srcRect(); // source rect in the sheet
    abstract Color fallbackColor();

    // collision: only heavy objects block movement. Flowers/tall grass/small
    // rocks are pure decoration -- the player walks through them (they still
    // rustle via disturb()).
    public boolean blocks() {
        return this instanceof TreeBig || this instanceof TreeSmall
            || this instanceof Stone || this instanceof Chest;
    }

    public Rectangle getBounds() {
        return new Rectangle((int) (x + size * 0.1), (int) (y + size * 0.1),
            (int) (size - size * 0.2), (int) (size - size * 0.2));
    }

    // ---- concrete subclasses ---------------------------------------------
    // Anchor constants measured from the sheets (opaque-pixel analysis):
    //   Oak_Tree 64x80: content (11,9)-(51,71), root flare span 14..49 (36px)
    //     at y=59, centre x=31.5, root base row=72 (71+1)
    //   Oak_Tree_Small cell1 32x48: content (5,3)-(27,36), root span 9..22
    //     (14px), centre 15.5, base 37; cell2 (sapling) base span 9..25 (17px
    //     bushy base, 4px stem under it), centre 17, base 29
    //   Decor stones: big r2c2 (0,2)-(14,13) w15 c7 b14; mid r1c2 w13 c7 b12;
    //     pebble r2c1 w10 c6.5 b12
    //   Chest 16x16: full cell, w15 c8 b16

    static class TreeBig extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Oak_Tree.png"); // 64x80
        TreeBig(int x, int y, int size) { this.x = x; this.y = y; this.size = 3 * 32; }
        double reactivity() { return 0.55; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.4, true, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 64, 80); }
        Color fallbackColor() { return new Color(0x4E, 0x35, 0x24); }
        double anchorCx() { return 31.5; }
        double anchorBottom() { return 72; }
        double anchorWidth() { return 36; } // root flare -> fills the 3x3 block
    }

    // GIANT landmark tree: same oak art blown up onto a 5x5 tile block --
    // the canopy towers over everything (rare, ~handful per world)
    static class GiantTree extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Oak_Tree.png");
        GiantTree(int x, int y, int size) { this.x = x; this.y = y; this.size = 5 * 32; }
        double reactivity() { return 0.5; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.4, true, 3); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 64, 80); }
        Color fallbackColor() { return new Color(0x4E, 0x35, 0x24); }
        double anchorCx() { return 31.5; }
        double anchorBottom() { return 72; }
        double anchorWidth() { return 36; } // 36px flare -> 160px 5x5 block
        @Override public boolean blocks() { return true; }
    }

    static class TreeSmall extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Oak_Tree_Small.png"); // 96x48
        final int cell; // 1 = round small oak (blocking), 2 = sapling (decor)
        TreeSmall(int x, int y, int size, int cell) {
            this.x = x; this.y = y; this.size = 32; this.cell = cell; // 1x1 block
        }
        double reactivity() { return 0.75; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.4, true, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(cell * 32, 0, 32, 48); }
        Color fallbackColor() { return new Color(0x4E, 0x35, 0x24); }
        double anchorCx() { return cell == 1 ? 15.5 : 17.0; }
        double anchorBottom() { return cell == 1 ? 37 : 29; }
        double anchorWidth() { return cell == 1 ? 14 : 17; } // root fills the 1x1
        // the sapling's stem is only 4px art: it is decor you brush through,
        // never an invisible-barrier block
        @Override public boolean blocks() { return cell == 1; }
    }

    static class Plant extends Obstacle { // bushes / flowers / grass decor
        static final Image IMG = SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png");
        static final int[][] CELLS = {
            {0, 0}, {16, 0}, {32, 0},             // grass tufts
            {0, 16}, {16, 16}, {32, 16},           // bushes / plants
            // ({48,16},{64,16} REMOVED: those are SEED PACKETS/seedlings --
            //  white-outlined ITEMS by pack convention, never terrain props)
            {80, 16}, {96, 16},                    // flower bushes
            {32, 32}, {48, 32}, {64, 32},          // small flowers
            {80, 32},                               // mushrooms
        };
        // measured per cell: {anchorCx, anchorBottom, anchorWidth} in cell px
        static final double[][] ANCHORS = {
            {6.5, 10, 10}, {7.5, 10, 8}, {7.5, 12, 10},      // tufts
            {7.0, 15, 15}, {7.5, 12, 10}, {7.0, 12, 13},      // bushes
            {8.0, 11, 5}, {8.0, 11, 7}, {8.0, 12, 13}, {8.0, 14, 13},
            {7.0, 14, 15}, {8.0, 11, 7}, {7.5, 12, 10},       // flowers
            {8.0, 14, 13},                                     // mushrooms
        };
        final int cellIdx;
        Plant(int x, int y, int size, int cellIdx) {
            this.x = x; this.y = y; this.size = size; this.cellIdx = cellIdx;
        }
        double reactivity() { return 1.0; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.5, false, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(CELLS[cellIdx][0], CELLS[cellIdx][1], 16, 16); }
        Color fallbackColor() { return new Color(0x3E, 0x6B, 0x35); }
        double anchorCx() { return ANCHORS[cellIdx][0]; }
        double anchorBottom() { return ANCHORS[cellIdx][1]; }
        double anchorWidth() { return ANCHORS[cellIdx][2]; }
        // NEVER blocks: pure decoration the player brushes through
        @Override public boolean blocks() { return false; }
    }

    static class Stone extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png");
        Stone(int x, int y, int size, int cellIdx) {
            this.x = x; this.y = y; this.cellIdx = cellIdx;
            this.size = cellIdx == 2 ? size : 32; // pebble keeps its decor size
        }
        Stone(int x, int y, int size) { this(x, y, size, 0); }
        final int cellIdx;
        double reactivity() { return 0.12; } // heavy: barely a shiver
        void shedDebris() { Effects.stonePuff(x + size / 2.0, y + size * 0.5, 3); }
        Image art() { return IMG; }
        Rectangle srcRect() {
            return new Rectangle[]{new Rectangle(32, 32, 16, 16),
                new Rectangle(32, 16, 16, 16), new Rectangle(16, 32, 16, 16)}[cellIdx];
        }
        Color fallbackColor() { return new Color(0x78, 0x82, 0x96); }
        double anchorCx() { return new double[]{7, 7, 6.5}[cellIdx]; }
        double anchorBottom() { return new double[]{14, 12, 12}[cellIdx]; }
        double anchorWidth() { return new double[]{15, 13, 10}[cellIdx]; }
        // cell 0 = big blocking boulder; cell 1 = mid stone, decor-only (its
        // 13px art cannot fill a 1x1 block, so it never blocks); cell 2 = pebble
        @Override public boolean blocks() { return cellIdx == 0; }
    }

    static class Chest extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Chest.png"); // 16x16
        Chest(int x, int y, int size) { this.x = x; this.y = y; this.size = 32; }
        double reactivity() { return 0; } // chests never budge
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 16); }
        Color fallbackColor() { return new Color(0x7A, 0x52, 0x2B); }
        double anchorCx() { return 8; }
        double anchorBottom() { return 16; }
        double anchorWidth() { return 15; }
    }

    // ---- 0x72 pack: ruins, crates, dungeon props ----

    // broken ruin column (16x48 art on a 1x1 block, leans tall)
    static class RuinColumn extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/dungeon/column.png");
        final boolean full; // full column variant
        RuinColumn(int x, int y, int size, boolean full) {
            this.x = x; this.y = y; this.size = 32; this.full = full;
        }
        double reactivity() { return 0.08; } // stone: barely a shiver
        void shedDebris() { Effects.stonePuff(x + size / 2.0, y + size * 0.5, 3); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 48); }
        Color fallbackColor() { return new Color(0x78, 0x82, 0x96); }
        double anchorCx() { return 7.5; }
        double anchorBottom() { return full ? 48 : 39; }
        double anchorWidth() { return 16; }
        @Override public boolean blocks() { return true; }
    }

    // dungeon wall segments (plain / hole / goo variants) -- 1x1, cluster into ruins
    static class RuinWall extends Obstacle {
        static final Image[] IMGS = {
            SpriteAnim.load("assets/dungeon/wall_mid.png"),
            SpriteAnim.load("assets/dungeon/wall_hole_1.png"),
            SpriteAnim.load("assets/dungeon/wall_goo.png"),
        };
        final int variant;
        RuinWall(int x, int y, int size, int variant) {
            this.x = x; this.y = y; this.size = 32; this.variant = variant;
        }
        double reactivity() { return 0.08; }
        void shedDebris() { Effects.stonePuff(x + size / 2.0, y + size * 0.5, 3); }
        Image art() { return IMGS[variant % IMGS.length]; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 16); }
        Color fallbackColor() { return new Color(0x78, 0x82, 0x96); }
        double anchorCx() { return 7.5; }
        double anchorBottom() { return 16; }
        double anchorWidth() { return 16; }
        @Override public boolean blocks() { return true; }
    }

    // wooden crate (0x72): solid, blocks
    static class Crate extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/dungeon/crate.png"); // 16x24
        Crate(int x, int y, int size) { this.x = x; this.y = y; this.size = 32; }
        double reactivity() { return 0.1; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.6, false, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 24); }
        Color fallbackColor() { return new Color(0x8B, 0x5A, 0x2B); }
        double anchorCx() { return 7.5; }
        double anchorBottom() { return 23; }
        double anchorWidth() { return 14; }
        @Override public boolean blocks() { return true; }
    }

    // bone skull: small ground decor
    static class Skull extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/dungeon/skull.png");
        Skull(int x, int y, int size) { this.x = x; this.y = y; this.size = size; }
        double reactivity() { return 0; }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 16); }
        Color fallbackColor() { return new Color(0xE8, 0xE0, 0xC8); }
        double anchorCx() { return 7.5; }
        double anchorBottom() { return 13; }
        double anchorWidth() { return 6; }
        @Override public boolean blocks() { return false; }
    }

    // spinning coin pile (animated 4 frames): ground decor
    static class CoinPile extends Obstacle {
        static final Image[] FRAMES = {
            SpriteAnim.load("assets/dungeon/coin_anim_f0.png"),
            SpriteAnim.load("assets/dungeon/coin_anim_f1.png"),
            SpriteAnim.load("assets/dungeon/coin_anim_f2.png"),
            SpriteAnim.load("assets/dungeon/coin_anim_f3.png"),
        };
        CoinPile(int x, int y, int size) { this.x = x; this.y = y; this.size = size; }
        double reactivity() { return 0; }
        Image art() {
            long t = System.currentTimeMillis();
            Image f = FRAMES[(int) ((t / 160) % FRAMES.length)];
            return f != null ? f : FRAMES[0];
        }
        Rectangle srcRect() { return new Rectangle(0, 0, 6, 7); }
        Color fallbackColor() { return new Color(0xFF, 0xC8, 0x25); }
        double anchorCx() { return 2.5; }
        double anchorBottom() { return 7; }
        double anchorWidth() { return 6; }
        @Override public boolean blocks() { return false; }
    }

    // floor spikes (animated): flat hazard decor, non-blocking
    static class Spikes extends Obstacle {
        static final Image[] FRAMES = {
            SpriteAnim.load("assets/dungeon/floor_spikes_anim_f0.png"),
            SpriteAnim.load("assets/dungeon/floor_spikes_anim_f1.png"),
            SpriteAnim.load("assets/dungeon/floor_spikes_anim_f2.png"),
            SpriteAnim.load("assets/dungeon/floor_spikes_anim_f3.png"),
        };
        Spikes(int x, int y, int size) { this.x = x; this.y = y; this.size = 32; }
        double reactivity() { return 0; }
        Image art() {
            long t = System.currentTimeMillis();
            Image f = FRAMES[(int) ((t / 220) % FRAMES.length)];
            return f != null ? f : FRAMES[0];
        }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 16); }
        Color fallbackColor() { return new Color(0x9A, 0x9A, 0x9A); }
        double anchorCx() { return 7.5; }
        double anchorBottom() { return 16; }
        double anchorWidth() { return 16; }
        @Override public boolean blocks() { return false; }
    }

    // ---- cute pack extras ----

    // wood log pile (decor sheet row 7): solid, blocks
    static class WoodPile extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png");
        final int cellIdx; // 0, 1 = log stacks
        WoodPile(int x, int y, int size, int cellIdx) {
            this.x = x; this.y = y; this.size = 32; this.cellIdx = cellIdx;
        }
        double reactivity() { return 0.15; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.6, false, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(cellIdx * 16, 7 * 16, 16, 16); }
        Color fallbackColor() { return new Color(0x8B, 0x5A, 0x2B); }
        double anchorCx() { return cellIdx == 0 ? 6.5 : 7.5; }
        double anchorBottom() { return 14; }
        double anchorWidth() { return 12; }
        @Override public boolean blocks() { return true; }
    }

    // barrel (decor sheet row 7 c2): solid, blocks
    static class Barrel extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png");
        Barrel(int x, int y, int size) { this.x = x; this.y = y; this.size = 32; }
        double reactivity() { return 0.1; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.6, false, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(2 * 16, 7 * 16, 16, 16); }
        Color fallbackColor() { return new Color(0x8B, 0x5A, 0x2B); }
        double anchorCx() { return 8.0; }
        double anchorBottom() { return 13; }
        double anchorWidth() { return 8; }
        @Override public boolean blocks() { return true; }
    }

    // fence pieces (Fences.png): post / mid / end, blocking 1x1
    static class Fence extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Fences.png"); // 64x64, 16px cells
        // cells: post=r0c0, mid-span=r2c2, right-end=r2c3, left-end=r1c1
        static final int[][] CELLS = { {0,0}, {2,2}, {2,3}, {1,1} };
        final int piece;
        Fence(int x, int y, int size, int piece) {
            this.x = x; this.y = y; this.size = 32; this.piece = piece;
        }
        double reactivity() { return 0.2; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.6, false, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(CELLS[piece][0] * 16, CELLS[piece][1] * 16, 16, 16); }
        Color fallbackColor() { return new Color(0x8B, 0x5A, 0x2B); }
        double anchorCx() { return 7.5; }
        double anchorBottom() { return 16; }
        double anchorWidth() { return 16; } // zoom 2: art tile == block tile
        @Override public boolean blocks() { return true; }
    }

    // crop patch (decor rows 8-11): 4 growth stages x 2 variants, decor
    static class Crop extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png");
        final int row, cellIdx;
        // growth rows 8..11: row 11 = ripe (stage 3). Only ripe crops carry
        // produce; younger ones wobble but drop nothing yet.
        Crop(int x, int y, int size, int row, int cellIdx) {
            this.x = x; this.y = y; this.size = size;
            this.row = row; this.cellIdx = cellIdx;
        }
        double reactivity() { return 1.0; } // crops sway freely
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.5, false, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(cellIdx * 16, row * 16, 16, 16); }
        Color fallbackColor() { return new Color(0x3E, 0x6B, 0x35); }
        double anchorCx() { return 7.5; }
        double anchorBottom() { return 16; }
        double anchorWidth() { return 12; }
        @Override public boolean blocks() { return false; }
        // INTERACTABLE: a ripe crop drops its produce as a ground item.
        // Returns the food item produced, or null (not ripe / already picked).
        Item harvest() {
            if (row != 11 || cellIdx < 0) return null;
            // cells 0-1 = red berries bush; 2-3 = green wheat -> map to food
            return (cellIdx % 2 == 0) ? ItemCatalog.berries() : ItemCatalog.wheat();
        }
    }

    // wooden house (cute pack): big 4x4 landmark, blocks
    static class House extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/House_1_Wood_Base_Blue.png"); // 96x128
        House(int x, int y, int size) { this.x = x; this.y = y; this.size = 4 * 32; }
        double reactivity() { return 0; }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 96, 128); }
        Color fallbackColor() { return new Color(0x7A, 0x52, 0x2B); }
        double anchorCx() { return 47.5; }
        double anchorBottom() { return 115; }
        double anchorWidth() { return 70; }
        @Override public boolean blocks() { return true; }
    }

    // wooden bridge laid flat across water (decor, water stays walkable)
    static class Bridge extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Bridge_Wood.png"); // 144x64, content (5,0)-(133,58)
        Bridge(int x, int y, int size) {
            this.x = x; this.y = y; this.size = 4 * 32; // spans 4 water tiles
        }
        double reactivity() { return 0.3; }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 144, 64); }
        Color fallbackColor() { return new Color(0x8B, 0x5A, 0x2B); }
        double anchorCx() { return 69.0; }
        double anchorBottom() { return 59; }
        double anchorWidth() { return 127; }
        @Override public boolean blocks() { return false; }
        // true footprint: 4 tiles wide x 1 tile tall (the water run), so
        // placement/overlap checks match the visible planks exactly
        @Override public Rectangle getBounds() {
            return new Rectangle(x, y + TS2 * 3 / 2 - TS2 / 2, size - 2, TS2);
        }
    }

    // ---- village + dungeon dressing (decor sheet rows 3-6) ----

    // lantern post: 3-cell-tall art (r4-6 c4): lamp cage, pole, flared base.
    // Non-blocking streetlight with a warm glow disc at the lamp height.
    static class Lantern extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png");
        Lantern(int x, int y, int size) { this.x = x; this.y = y; this.size = TS2; }
        double reactivity() { return 0.3; } // swings a little when bumped
        void shedDebris() {}
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(4 * 16, 4 * 16, 16, 48); }
        Color fallbackColor() { return new Color(0x6C, 0x7C, 0x9D); }
        double anchorCx() { return 7.0; }
        double anchorBottom() { return 48; }
        double anchorWidth() { return 9; }
        @Override public boolean blocks() { return false; }
        @Override
        public void draw(Graphics g, int cameraX, int cameraY, long now) {
            super.draw(g, cameraX, cameraY, now);
            // warm glow at the lamp cage (art y ~10-14 of 48 -> screen ~ top quarter)
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
            g2.setColor(new Color(255, 210, 90));
            double zoom = size / anchorWidth();
            double lampY = y - cameraY + size - 12 * zoom; // lamp row on the pole
            double lampX = x - cameraX + anchorCx() * zoom;
            double r = 34;
            g2.fillOval((int) (lampX - r), (int) (lampY - r), (int) (2 * r), (int) (2 * r));
            g2.dispose();
        }
    }

    // gravestone: 4 variants (r3 c0-3), non-blocking dungeon decor
    static class Gravestone extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png");
        final int variant;
        Gravestone(int x, int y, int size, int variant) {
            this.x = x; this.y = y; this.size = TS2; this.variant = variant;
        }
        double reactivity() { return 0.05; } // heavy stone
        void shedDebris() { Effects.stonePuff(x + size / 2.0, y + size * 0.5, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(variant * 16, 3 * 16, 16, 16); }
        Color fallbackColor() { return new Color(0x6C, 0x7C, 0x9D); }
        double anchorCx() { return 8.0; }
        double anchorBottom() { return 16; }
        double anchorWidth() { return 14; }
        @Override public boolean blocks() { return false; }
    }

    // animated fountain: 3 frames (r4 c0-2), village centrepiece, non-blocking
    static class Fountain extends Obstacle {
        static final Image[] FRAMES = {
            SpriteAnim.load("assets/cute/Outdoor_Decor_Free.png"), // same sheet
        };
        // frames are cells r4c0, r4c1, r4c2 -> animated by shifting srcRect
        Fountain(int x, int y, int size) { this.x = x; this.y = y; this.size = 2 * TS2; }
        double reactivity() { return 0; }
        Image art() { return FRAMES[0]; }
        Rectangle srcRect() { // cycle c0/c1/c2 at ~3fps
            int f = (int) ((System.currentTimeMillis() / 330) % 3);
            return new Rectangle(f * 16, 4 * 16, 16, 16);
        }
        Color fallbackColor() { return new Color(0x8E, 0xC9, 0xE8); }
        double anchorCx() { return 7.5; }
        double anchorBottom() { return 15; }
        double anchorWidth() { return 14; }
        @Override public boolean blocks() { return false; }
    }

    // ---- semantic-map props (assets/map/*.png registry) ----

    // village well: solid stone well, blocks
    static class Well extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/map/well.png");
        Well(int x, int y, int size) { this.x = x; this.y = y; this.size = TS2; }
        double reactivity() { return 0.05; }
        void shedDebris() { Effects.stonePuff(x + size / 2.0, y + size * 0.5, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 16); }
        Color fallbackColor() { return new Color(0x8B, 0x7A, 0x5A); }
        double anchorCx() { return 8.5; }
        double anchorBottom() { return 16; }
        double anchorWidth() { return 14; }
        @Override public boolean blocks() { return true; }
    }

    // birdbath / small stone planter: village decor, blocks
    static class Birdbath extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/map/birdbath.png");
        Birdbath(int x, int y, int size) { this.x = x; this.y = y; this.size = TS2; }
        double reactivity() { return 0.05; }
        void shedDebris() { Effects.stonePuff(x + size / 2.0, y + size * 0.5, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 16); }
        Color fallbackColor() { return new Color(0xA0, 0x96, 0x80); }
        double anchorCx() { return 8.5; }
        double anchorBottom() { return 16; }
        double anchorWidth() { return 12; }
        @Override public boolean blocks() { return true; }
    }

    // signpost: village/dungeon waymarker, decorative (walk-through)
    static class Signpost extends Obstacle {
        static final Image IMG_R = SpriteAnim.load("assets/map/signpost_right.png");
        static final Image IMG_B = SpriteAnim.load("assets/map/signpost_blank.png");
        final boolean blank;
        Signpost(int x, int y, int size, boolean blank) {
            this.x = x; this.y = y; this.size = TS2; this.blank = blank;
        }
        double reactivity() { return 0.25; } // wobbles when bumped
        void shedDebris() {}
        Image art() { return blank ? IMG_B : IMG_R; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 16); }
        Color fallbackColor() { return new Color(0x8B, 0x5A, 0x2B); }
        double anchorCx() { return blank ? 7.5 : 6.5; }
        double anchorBottom() { return blank ? 14 : 12; }
        double anchorWidth() { return blank ? 14 : 10; }
        @Override public boolean blocks() { return false; }
    }

    // tree stump: forest floor decor, non-blocking
    static class Stump extends Obstacle {
        static final Image IMG = SpriteAnim.load("assets/map/stump_big.png");
        Stump(int x, int y, int size) { this.x = x; this.y = y; this.size = size; }
        double reactivity() { return 0.1; }
        void shedDebris() { Effects.debris(x + size / 2.0, y + size * 0.5, false, 2); }
        Image art() { return IMG; }
        Rectangle srcRect() { return new Rectangle(0, 0, 16, 16); }
        Color fallbackColor() { return new Color(0x6B, 0x4A, 0x2E); }
        double anchorCx() { return 8.0; }
        double anchorBottom() { return 14; }
        double anchorWidth() { return 9; }
        @Override public boolean blocks() { return false; }
    }

    // factory: heavy scenery (trees/stones/chests) + walk-through decor.
    // Blocking kinds normalize `size` to their tile block; the caller should
    // re-read ob.size for placement/occupancy.
    public static Obstacle create(int x, int y, int size) {
        int h = Math.floorMod(x * 73856093 ^ y * 19349663, 100);
        if (size < 26) { // small: flowers, tall grass, pebbles -- non-blocking
            if (h < 30) return new Plant(x, y, size, h % Plant.CELLS.length);
            if (h < 45) return new Stone(x, y, size, 2); // pebble
            return new Plant(x, y, size, h % Plant.CELLS.length);
        }
        if (size < 35) { // medium: bushes + plants (decor)
            return new Plant(x, y, size, h % Plant.CELLS.length);
        }
        // big: trees, blocking stones, rare chests + NEW ruin/crate/fence/
        // barrel/woodpile kinds -- both packs fully represented
        int k = h % 100;
        if (k < 6) return new Stone(x, y, size, 0);      // blocking boulder
        if (k < 9) return new Chest(x, y, size);          // rare treasure
        if (k < 13) return new Crate(x, y, size);          // 0x72 crate
        if (k < 17) return new Barrel(x, y, size);         // cute barrel
        if (k < 21) return new WoodPile(x, y, size, h % 2);// log pile
        if (k < 25) return new RuinColumn(x, y, size, h % 3 == 0); // ruin pillar
        if (k < 29) return new RuinWall(x, y, size, h % 3);         // ruin wall
        if (k < 33) return new Fence(x, y, size, h % 4);  // fence piece
        if (k < 56) return new TreeSmall(x, y, size, h % 4 == 0 ? 2 : 1); // 25% saplings
        if (k < 99) return new TreeBig(x, y, size);
        return new GiantTree(x, y, size);                 // ~3% of big trees: 5x5 giant
    }
}
