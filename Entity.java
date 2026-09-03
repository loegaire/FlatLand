import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// ============================================================================
// Entity: ONE agent architecture for every character in the world --
// the player, villagers, and monsters. Behaviour differences come ONLY from
// the randomly assigned parameters (8 predisposition attributes) and the
// situation; monsters spawn with an aggressive-biased attribute draw.
//
//   NpcState (self) + Perceived list + Attributes + Inventory
//        -> DecisionFunction (statistical, swappable)
//        -> sampled Action
//        -> execute on the real world
// ============================================================================
public class Entity {
    int x, y, size;
    final String name;
    boolean dead = false;

    // ---- agent state ----
    final NpcState state;
    final Attributes attrs;
    final Inventory inventory;
    final NpcBrain brain;
    Action lastAction = null;
    long lastDecision = 0;
    static final long DECIDE_EVERY_MS = 900;

    // ---- combat/physical ----
    double health = 100, maxHealth = 100;
    long speedBoostUntil = 0, damageBoostUntil = 0;
    long hurtStart = -100000;
    double kbx = 0, kby = 0;
    int baseSpeed = 1;

    // ---- perception ----
    static final double SIGHT = 260.0;
    // held-item animation (same system as the player's swing)
    long attackStart = -100000;
    long attackDur = 280;
    double attackAngle = Math.PI / 2;

    // water bookkeeping (panel-driven, like the old enemy loop)
    boolean wasInWater = false;
    long lastRipple = 0;
    // monster flag: only the biased spawn -- no behaviour branches on it
    final boolean monsterBias;

    // ---- movement/visual ----
    int homeX, homeY, leash;
    protected int[] step = {0, 0};
    protected boolean moving = false;
    protected boolean facingLeft = false;
    protected int facing = 0;
    protected final SpriteAnim frames; // 0x72 idle+run set (all entities)
    long speakStart = -100000;
    static final long SPEAK_MS = 1800;
    String lastSaid = null;

    // projectiles entities throw this tick (panel drains it into the world list)
    static final java.util.List<Bullet> thrownBullets = new java.util.ArrayList<>();

    // ---- ground-drop registry (world level) ----
    static final List<GroundItem> groundItems = new ArrayList<>();
    static final class GroundItem {
        final Item item; final int x, y; final long droppedAt;
        GroundItem(Item item, int x, int y) {
            this.item = item; this.x = x; this.y = y;
            this.droppedAt = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------------
    // THE spawn path: one constructor, random stats (user requirement).
    // `monsterBias` shifts the attribute distribution -- it does NOT add any
    // new behaviour: a monster is just an entity likely to draw aggression.
    // ------------------------------------------------------------------------
    Entity(int x, int y, int size, String name, String artBase,
           Random rng, boolean monsterBias) {
        this.monsterBias = monsterBias;
        this.x = x; this.y = y; this.size = size; this.name = name;
        this.homeX = x; this.homeY = y; this.leash = monsterBias ? 320 : 160;
        this.frames = new SpriteAnim(artBase, 4, 4);
        this.frames.setIdleMs(120).setRunMs(70);

        // random 8-attribute personality (biased draw for monsters)
        double[] a = new double[8];
        for (int i = 0; i < 8; i++) a[i] = rng.nextDouble();
        if (monsterBias) {
            a[Attributes.AGG] = Attributes.clamp01(a[Attributes.AGG] * 0.5 + 0.5); // aggressive
            a[Attributes.COU] = Attributes.clamp01(a[Attributes.COU] * 0.4 + 0.6); // courageous
            a[Attributes.EMP] = Attributes.clamp01(a[Attributes.EMP] * 0.4);       // cold
            a[Attributes.LOY] = Attributes.clamp01(a[Attributes.LOY] * 0.5);       // treacherous
            a[Attributes.GRE] = Attributes.clamp01(a[Attributes.GRE] * 0.5 + 0.5);  // greedy
            a[Attributes.SOC] = Attributes.clamp01(a[Attributes.SOC] * 0.3);       // loners
        }
        this.attrs = new Attributes(a);
        this.state = new NpcState(x, y);
        this.state.hunger = rng.nextDouble() * 50;
        this.state.stamina = 60 + rng.nextDouble() * 40;
        this.inventory = new Inventory(); // base 60 strength
        spawnKit(rng);
        this.brain = new NpcBrain(new StatisticalBrain(), rng.nextLong());

        // monsters are physically varied
        if (monsterBias) {
            this.health = this.maxHealth = 60 + rng.nextInt(80);
            this.baseSpeed = 1 + rng.nextInt(2);
        }
    }

    // random starting kit; monsters carry weapons, villagers carry goods
    void spawnKit(Random rng) {
        inventory.add(ItemCatalog.randomWeapon(rng));
        if (rng.nextInt(2) == 0) inventory.add(ItemCatalog.randomPotion(rng));
        if (!isMonster() && rng.nextInt(2) == 0) inventory.add(ItemCatalog.randomFood(rng));
        if (rng.nextInt(3) == 0) inventory.add(ItemCatalog.coin());
    }

    // true when this entity spawned with the aggressive-biased draw
    boolean isMonster() { return monsterBias; }

    void setHome(int hx, int hy, int leashPx) {
        this.homeX = hx; this.homeY = hy; this.leash = leashPx;
    }

    // ---- combat API (everyone can be hurt and can hurt) ----
    double maxHealth() { return maxHealth; }
    void hurt(double dmg, double fx, double fy) {
        health -= dmg;
        hurtStart = System.currentTimeMillis();
        double dx = x + size / 2.0 - fx, dy = y + size / 2.0 - fy;
        double len = Math.max(1, Math.hypot(dx, dy));
        kbx = dx / len * 4.0; kby = dy / len * 4.0;
        Effects.hit(x + size / 2.0, y + size / 2.0, size * 0.12, bloodColor());
        if (health <= 0) die();
    }
    Color bloodColor() { return Effects.BLOOD; }
    void die() {
        dead = true;
        Effects.pop(x + size / 2.0, y + size / 2.0, size, bloodColor());
        // drop everything on death -- loot for survivors
        for (Item it : new ArrayList<>(inventory.list())) {
            inventory.remove(it);
            groundItems.add(new GroundItem(it, x + size / 2, y + size / 2));
        }
    }

    // ---- perception: entities + ground items + obstacles in range ----
    List<Perceived> perceive(Player player, List<Entity> all, List<Obstacle> obstacles) {
        List<Perceived> out = new ArrayList<>();
        if (player != null && !player.GameOver && Math.hypot(player.x - x, player.y - y) < SIGHT)
            out.add(new Perceived(player, Perceived.Kind.PLAYER, player.x, player.y, null,
                playerGuesses)); // player is just another perceived target
        for (Entity o : all) {
            if (o == this || o.dead) continue;
            if (Math.hypot(o.x - x, o.y - y) < SIGHT) {
                // static guesses: actual + noise, clamped (spec)
                double[] g = new double[8];
                Random rng = new Random(o.hashCode() ^ name.hashCode());
                for (int i = 0; i < 8; i++)
                    g[i] = Attributes.clamp01(o.attrs.a[i] + (rng.nextDouble() - 0.5) * 0.15);
                out.add(new Perceived(o, Perceived.Kind.NPC, o.x, o.y, o.lastAction, new Attributes(g)));
            }
        }
        for (GroundItem gi : groundItems)
            if (Math.hypot(gi.x - x, gi.y - y) < SIGHT)
                out.add(new Perceived(gi, Perceived.Kind.ITEM, gi.x, gi.y, null,
                    new Attributes(0.1, 0.3, 0.2, 0.2, 0.9, 0.8, 0.3, 0.4)));
        for (Obstacle ob : obstacles)
            if (ob.reactivity() > 0 && Math.hypot(ob.x - x, ob.y - y) < SIGHT / 2)
                out.add(new Perceived(ob, Perceived.Kind.OBSTACLE, ob.x, ob.y, null,
                    new Attributes(0, 0, 0, 0, 0, 0, 0, 0)));
        return out;
    }
    // what other entities guess about the PLAYER: neutral-pessimistic draw
    static final Attributes playerGuesses = new Attributes(0.5, 0.5, 0.5, 0.5, 0.4, 0.5, 0.5, 0.5);

    // ---- the brain tick ----
    void update(Player player, List<Obstacle> obstacles, List<Entity> all, Random rng) {
        long now = System.currentTimeMillis();
        state.x = x; state.y = y;
        state.health = health;
        // survival: hunger rises; starving entities lose health; fed ones regen
        state.hunger = Math.min(100, state.hunger + 0.004);
        if (state.hunger >= 100) {
            health -= 0.02; // starving: slow decay
            if (health <= 0) die();
        } else if (state.hunger < 50 && health < maxHealth) {
            health = Math.min(maxHealth, health + 0.01); // regen when fed
        }
        state.stamina = Math.max(0, state.stamina - (moving ? 0.02 : -0.03));
        state.lastAction = lastAction;

        // ---- automatic pickup: walk over an item and it's yours (if it fits) ----
        for (int i = groundItems.size() - 1; i >= 0; i--) {
            GroundItem gi = groundItems.get(i);
            if (Math.hypot(gi.x - (x + size / 2.0), gi.y - (y + size / 2.0)) < 24
                && inventory.canPickUp(gi.item)) {
                inventory.add(gi.item);
                groundItems.remove(i);
                lastSaid = "got " + gi.item.name;
                speakStart = now;
                Effects.sparkle(gi.x, gi.y, Effects.FOAM, 3);
            }
        }
        if (now - lastDecision > DECIDE_EVERY_MS) {
            lastDecision = now;
            List<Perceived> perceived = perceive(player, all, obstacles);
            Action a = brain.decide(state, attrs, perceived, inventory);
            execute(a, player, obstacles, rng);
            lastAction = a;
        }
        walkTick(obstacles);
    }

    // ---- action execution on the real world ----
    private void execute(Action a, Player player, List<Obstacle> obstacles, Random rng) {
        switch (a.type) {
            case MOVE: {
                if (a.target instanceof java.awt.Point) {
                    java.awt.Point p = (java.awt.Point) a.target;
                    if (p.x == x && p.y == y) step = new int[]{0, 0}; // wait
                    else step = new int[]{ Integer.signum(p.x - x), Integer.signum(p.y - y) };
                } else if (a.target instanceof Entity) {
                    Entity t = (Entity) a.target;
                    step = new int[]{ Integer.signum(t.x - x), Integer.signum(t.y - y) };
                } else if (a.target instanceof Player) {
                    Player t = (Player) a.target;
                    step = new int[]{ Integer.signum(t.x - x), Integer.signum(t.y - y) };
                }
                int nx = x + step[0] * 2, ny = y + step[1] * 2;
                if (Math.hypot(nx - homeX, ny - homeY) > leash) step = new int[]{0, 0};
                break;
            }
            case USE: {
                if (a.object != null && a.target != null) {
                    // SELF-USE NORMALIZATION: the brain addresses itself as
                    // its NpcState -- map that back to THIS entity so potions
                    // and food actually reach the use() implementation
                    Object target = (a.target == state || a.target == this) ? this : a.target;
                    // aim dir = toward the target (for knockback source)
                    double[] aim = {0, 0};
                    if (target instanceof Entity) {
                        Entity t = (Entity) target;
                        double len = Math.max(1, Math.hypot(t.x - x, t.y - y));
                        aim = new double[]{ (t.x - x) / len, (t.y - y) / len };
                    } else if (target instanceof Player) {
                        Player t = (Player) target;
                        double len = Math.max(1, Math.hypot(t.x - x, t.y - y));
                        aim = new double[]{ (t.x - x) / len, (t.y - y) / len };
                    }
                    String res = a.object.use(target, aim);
                    lastSaid = res;
                    if (target == this) { // consumables vanish on self-use
                        inventory.remove(a.object);
                        speakStart = System.currentTimeMillis();
                    } else if (a.object instanceof ProjectileItem) {
                        // thrown: leaves the hand, flies spinning
                        Bullet b = new Bullet(a.object.sprite, true,
                            x + 10 + (int)(aim[0] * 15), y + 10 + (int)(aim[1] * 15),
                            10, new double[]{aim[0], aim[1]});
                        b.holderScale = 1.25 * size / 30.0;
                        thrownBullets.add(b);
                        attackStart = System.currentTimeMillis();
                        attackDur = 350; // throw wind-up
                        attackAngle = Math.atan2(aim[1], aim[0]);
                    } else if (a.object.damage > 0 && target != this) {
                        // weapon swing: visible animation window (see draw())
                        attackStart = System.currentTimeMillis();
                        attackDur = 280;
                        attackAngle = Math.atan2(aim[1], aim[0]);
                        // the SAME arc glint the player's swing produces
                        double gl = size * 1.4;
                        Effects.slashGlint(x + size / 2.0 + aim[0] * gl,
                                           y + size / 2.0 + aim[1] * gl,
                                           Math.atan2(-aim[1], -aim[0]), 1.0);
                    }
                }
                break;
            }
            case INTERACT: {
                if (a.target instanceof GroundItem) {
                    GroundItem gi = (GroundItem) a.target;
                    if (inventory.canPickUp(gi.item)) {
                        inventory.add(gi.item);
                        groundItems.remove(gi);
                        lastSaid = "picked up " + gi.item.name;
                    } else lastSaid = "too heavy!";
                } else if (a.target instanceof Obstacle) {
                    Obstacle ob = (Obstacle) a.target;
                    if (ob instanceof Obstacle.Crop) { // harvest ripe crops
                        Item produce = ((Obstacle.Crop) ob).harvest();
                        if (produce != null && inventory.canPickUp(produce)) {
                            inventory.add(produce);
                            lastSaid = "harvested " + produce.name;
                            speakStart = System.currentTimeMillis();
                            Effects.debris(ob.x + ob.size / 2.0, ob.y + ob.size * 0.5, false, 3);
                        } else if (produce != null) { // can't carry: drop on the ground
                            groundItems.add(new Entity.GroundItem(produce,
                                ob.x + ob.size / 2, ob.y + ob.size));
                            lastSaid = "harvested (bag full)";
                        } else {
                            ob.disturb(0.8);
                            lastSaid = "not ripe yet";
                        }
                    } else {
                        ob.disturb(0.6);
                        lastSaid = "poked";
                    }
                }
                break;
            }
            case SPEAK: {
                speakStart = System.currentTimeMillis();
                lastSaid = a.target instanceof Entity ? ((Entity) a.target).name + "!"
                    : a.target instanceof Player ? pickGreeting(rng) : "...";
                if (a.target instanceof Entity) { // target shows a bubble back
                    Entity t = (Entity) a.target;
                    if (Math.hypot(t.x - x, t.y - y) < 150) t.speakStart = System.currentTimeMillis();
                }
                break;
            }
            case DROP_ITEM: {
                if (a.object != null && inventory.remove(a.object)) {
                    groundItems.add(new GroundItem(a.object, x + size / 2, y + size));
                    lastSaid = "dropped " + a.object.name;
                }
                break;
            }
        }
    }

    // hard-coded greeting pool (dialog system comes later, as its own module)
    static final String[] GREETINGS = {
        "Hello!", "Fine day!", "Hail!", "Greetings!", "Good morrow!", "Hey there!"
    };
    static String pickGreeting(Random rng) {
        return GREETINGS[rng.nextInt(GREETINGS.length)];
    }

    private void walkTick(List<Obstacle> obstacles) {
        moving = step[0] != 0 || step[1] != 0;
        facingLeft = step[0] < 0 || (step[0] == 0 && facingLeft);
        if (step[1] != 0) facing = step[1] > 0 ? 0 : 1;
        else if (step[0] != 0) facing = 2;
        int ox = x, oy = y;
        // knockback first
        if (Math.abs(kbx) >= 0.2 || Math.abs(kby) >= 0.2) {
            x += (int) Math.round(kbx); y += (int) Math.round(kby);
            kbx *= 0.7; kby *= 0.7;
        }
        int spd = baseSpeed + (System.currentTimeMillis() < speedBoostUntil ? 2 : 0);
        x += step[0] * spd; y += step[1] * spd;
        for (Obstacle ob : obstacles) {
            if (ob.blocks() && ob.getBounds().intersects(getBounds())) { x = ox; y = oy; moving = false; break; }
        }
    }

    public Rectangle getBounds() {
        return new Rectangle((int) (x + size * 0.1), (int) (y + size * 0.1),
            (int) (size - size * 0.2), (int) (size - size * 0.2));
    }

    public void draw(Graphics g, int cameraX, int cameraY) {
        int ax = x - cameraX + size / 2;
        int by = y - cameraY + size;
        // hurt squash + flash
        long ht = System.currentTimeMillis() - hurtStart;
        boolean hurting = ht >= 0 && ht < 300;
        if (frames != null)
            frames.draw(g, ax, by, (int) Math.round(size * 2.0), facingLeft, moving);
        if (hurting) {
            Graphics2D gf = (Graphics2D) g.create();
            gf.setColor(new Color(255, 80, 80, (int) ((1 - ht / 300.0) * 110)));
            gf.fillRect(x - cameraX, y - cameraY, size, size);
            gf.dispose();
        }
        // ---- held item: the entity's first weapon, drawn + swung like the player's
        Item held = null;
        for (Item it : inventory.list()) if (it instanceof WeaponItem) { held = it; break; }
        if (held != null && held.sprite != null) {
            long at = System.currentTimeMillis() - attackStart;
            double swing01 = (at >= 0 && at < attackDur) ? (double) at / attackDur : 1.0;
            double hs = size / 30.0 * 1.25; // 1.25x + holder scaling (player-parity)
            double hx = ax, hy = by - size * 0.45;
            WeaponView.drawItem(g, held.sprite, hx, hy, attackAngle, swing01, hs * 1.5);
        }
        // stat bars above head: hearts (health) + wheat (hunger) + flasks (mana)
        drawStats(g, ax, by, (int) Math.round(health), (int) Math.round(maxHealth),
            (int) state.hunger, (int) state.stamina);
        // held-item badge: a small pack-palette box above the stat bars showing
        // the entity's first weapon (what it is "currently holding")
        drawHeldBadge(g, ax, by);
        // speech/action bubble
        long gt = System.currentTimeMillis() - speakStart;
        String msg = gt >= 0 && gt < SPEAK_MS ? (lastSaid != null ? lastSaid : pickGreeting(new Random())) : null;
        if (msg != null) drawBubble(g, msg, ax, by);
    }

    // ---- stat bar sprites: STRICTLY pack assets ----
    // health: 0x72 ui_heart_* | hunger: pack wheat crop | mana: 0x72 blue flask
    static final Image HEART_FULL = SpriteAnim.load("assets/dungeon/ui_heart_full.png");
    static final Image HEART_HALF = SpriteAnim.load("assets/dungeon/ui_heart_half.png");
    static final Image HEART_EMPTY = SpriteAnim.load("assets/dungeon/ui_heart_empty.png");
    static final Image WHEAT = SpriteAnim.load("assets/map/crop_s3v0.png");     // ripe wheat
    static final Image FLASK_MANA = SpriteAnim.load("assets/dungeon/flask_blue.png");
    static final Image FLASK_MANA_EMPTY = SpriteAnim.load("assets/dungeon/flask_blue_empty.png");
    static final int HEART_W = 18; // 2x size (user): stats must be readable

    // three rows above the head: hearts = health, wheat = fed-ness (inverted
    // hunger), flasks = mana/stamina. Full/empty states are the REAL sprites
    // (empty flask = liquid recoloured to glass, derived from the pack asset;
    // drained wheat drawn dimmed -- same sprite, low alpha, no fake tinting).
    public static void drawStats(Graphics g, int cx, int by, int hp, int maxHp,
                                 int hunger, int stamina) {
        int hearts = Math.max(1, (int) Math.ceil(maxHp / 20.0));
        int full = hp / 20, rem = hp % 20;
        int rowH = HEART_W + 3;
        int y0 = by - 3 * rowH - 16;
        int x0 = cx - hearts * HEART_W / 2;
        // row 1: health hearts
        for (int i = 0; i < hearts; i++) {
            Image im = i < full ? HEART_FULL : (i == full && rem >= 10 ? HEART_HALF : HEART_EMPTY);
            if (im != null) g.drawImage(im, x0 + i * HEART_W, y0, HEART_W, HEART_W, null);
        }
        // row 2: hunger as wheat pips. fed = 100-hunger; each pip = 20.
        int fed = Math.max(0, 100 - hunger);
        int pips = 5, pipFull = fed / 20, pipRem = fed % 20;
        int wx0 = cx - pips * HEART_W / 2;
        for (int i = 0; i < pips; i++) {
            if (WHEAT == null) break;
            boolean isFull = i < pipFull || (i == pipFull && pipRem >= 10);
            Graphics2D g2 = (Graphics2D) g.create();
            if (!isFull) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
            g2.drawImage(WHEAT, wx0 + i * HEART_W, y0 + rowH, HEART_W, HEART_W, null);
            g2.dispose();
        }
        // row 3: mana/stamina as flasks (full blue / empty glass)
        int flasks = 5, flFull = Math.max(0, stamina) / 20, flRem = Math.max(0, stamina) % 20;
        int fx0 = cx - flasks * HEART_W / 2;
        for (int i = 0; i < flasks; i++) {
            Image im = i < flFull ? FLASK_MANA
                : (i == flFull && flRem >= 10 ? FLASK_MANA : FLASK_MANA_EMPTY);
            if (im == null) im = FLASK_MANA_EMPTY;
            g.drawImage(im, fx0 + i * HEART_W, y0 + 2 * rowH, HEART_W, HEART_W, null);
        }
    }

    // badge slot above the stat rows showing the held item's sprite
    static final int BADGE = 22;
    void drawHeldBadge(Graphics g, int cx, int by) {
        Item held = null;
        for (Item it : inventory.list()) if (it instanceof WeaponItem) { held = it; break; }
        if (held == null || held.sprite == null) return;
        int bx = cx - BADGE / 2;
        int byy = by - 3 * (HEART_W + 3) - 16 - BADGE - 4;
        g.setColor(new Color(0x2E, 0x2B, 0x26));
        g.fillRect(bx, byy, BADGE, BADGE);
        g.setColor(new Color(0xE4, 0xA6, 0x72));
        g.drawRect(bx, byy, BADGE, BADGE);
        // sprite fitted inside (max 16x16, keeps aspect)
        int sw = held.sprite.getWidth(null), sh = held.sprite.getHeight(null);
        double fit = Math.min(16.0 / sw, 16.0 / sh);
        int dw = (int) Math.round(sw * fit), dh = (int) Math.round(sh * fit);
        g.drawImage(held.sprite, bx + (BADGE - dw) / 2, byy + (BADGE - dh) / 2, dw, dh, null);
    }

    static void drawBubble(Graphics g, String msg, int ax, int by) {
        Font f = new Font("Monospaced", Font.BOLD, 12);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics(f);
        int w = fm.stringWidth(msg) + 12;
        int bx = ax - w / 2, byy = by - 3 * HEART_W - 28; // above the 2x stat rows
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(255, 255, 255, 230));
        g2.fillRoundRect(bx, byy, w, 20, 8, 8);
        g2.setColor(new Color(50, 42, 36));
        g2.drawString(msg, bx + 6, byy + 14);
        g2.dispose();
    }

    static void drawGroundItems(Graphics g, int cameraX, int cameraY) {
        for (GroundItem gi : groundItems) {
            if (gi.item.sprite == null) continue;
            int bob = (int) (Math.sin((System.currentTimeMillis() - gi.droppedAt) / 300.0) * 2);
            // drop shadow so items read as lying on the ground
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(new Color(0, 0, 0, 60));
            g2.fillOval(gi.x - 7 - cameraX, gi.y - 2 - cameraY, 14, 5);
            g2.dispose();
            int gw = (int) Math.round(16 * 1.25), gh = (int) Math.round(16 * 1.25);
            g.drawImage(gi.item.sprite, gi.x - gw / 2 - cameraX, gi.y - gh - 2 - cameraY + bob, gw, gh, null);
        }
    }
}
