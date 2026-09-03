import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class Player {
    // (the legacy `items` list is retired -- `bag` is the player's inventory)
    long now = System.currentTimeMillis();
    boolean GameOver = false; // Assuming this is a static variable in the game class
    // Cute Fantasy player sheet, 6x10 grid of 32px frames:
    //   rows 0-2 idle loops (subtle bob), rows 3-5 run loops (body bounce),
    //   rows 6-8 sword slash, 4 frames (down / side / up), row 9 SWIM loop,
    //   4 frames (body sinks 2px/frame, arm stroke in the last frame)
    // sheet rows VERIFIED by pixel analysis: run = 3 down / 4 SIDE / 5 up;
    // idle = 0 down / 1 SIDE / 2 up (face-skin clusters + leg shapes).
    // facing index: 0 down, 1 up, 2 side  ->  row map: down->3, up->5, side->4
    private final SheetAnim[] walk = {
        new SheetAnim("assets/cute/Player.png", 32, 32, 3, 6, 80), // facing 0 down
        new SheetAnim("assets/cute/Player.png", 32, 32, 5, 6, 80), // facing 1 up  (row 5)
        new SheetAnim("assets/cute/Player.png", 32, 32, 4, 6, 80), // facing 2 side (row 4)
    };
    private final SheetAnim[] idle = {
        new SheetAnim("assets/cute/Player.png", 32, 32, 0, 6, 180), // down
        new SheetAnim("assets/cute/Player.png", 32, 32, 2, 6, 180), // up   (row 2)
        new SheetAnim("assets/cute/Player.png", 32, 32, 1, 6, 180), // side (row 1)
    };
    private final SheetAnim[] slash = { // index = facing: 0 down, 1 up, 2 side
        new SheetAnim("assets/cute/Player.png", 32, 32, 6, 4, 70), // down (row 6)
        new SheetAnim("assets/cute/Player.png", 32, 32, 8, 4, 70), // up   (row 8)
        new SheetAnim("assets/cute/Player.png", 32, 32, 7, 4, 70), // side (row 7)
    };
    // swim loop: row 9 of the main sheet (was wrongly used as "bow" before)
    private final SheetAnim swim = new SheetAnim("assets/cute/Player.png", 32, 32, 9, 4, 220);
    // potion-drink pose: Player_Actions.png sheet-row 1 col 0 (48px frames),
    // arms raised overhead; the baked-in tool is erased via color filter and
    // the held flask is drawn over the raised hands by WeaponView
    private final SheetAnim drink = new SheetAnim("assets/cute/Player_Actions.png", 48, 48, 1, 1, 600, true);
    private int facing = 0; // 0 down, 1 up, 2 side
    private boolean facingLeft = false;
    private boolean movingVisual = false;
    private boolean onWater = false; // player stands on a 'w' tile -> swim anim
    private double lastAimAngle = Math.PI / 2; // visual aim for held weapon
    // one-shot attack animation: kind + start time + total duration
    // kinds: -1 none, 0 sword slash (row by facing), 1 bow shot (bow+arrow pull),
    // 2 potion drink (arms-up pose + flask raise)
    private long attackStart = -100000;
    private int attackKind = -1;
    private long attackDur = 280;
    // cosmetic jump (Space): parabolic hop, ~0.5s, no gameplay effect
    private long jumpStart = -100000;
    int currentWeaponIndex = 0;
    double hunger = 0; // rises to 100; at 100 health decays
    double stamina = 100; // "mana" bar: drains while moving, refills at rest
    double staminaBar() { return stamina; }
    long speedBoostUntil = 0, damageBoostUntil = 0; // from PotionItem (same as entities)
    int effectiveSpeed() { return speed + (System.currentTimeMillis() < speedBoostUntil ? 4 : 0); }
    int effectiveDamage(int base) { return base + (System.currentTimeMillis() < damageBoostUntil ? 5 : 0); }
    int x,y;
    int speed = 2;
    int size = 30;
    int health = 100;
    double[] direction = new double[]{0,0};
    int range = 50;
    int dmg = 20;
    // the player is an agent too: weight-gated Item inventory (base 60)
    public final Inventory bag = new Inventory();

    public Player(int x, int y) {
        this.x = x;
        this.y = y;
        // seed the bag via the item catalog (the text->sprite mapping)
        bag.add(ItemCatalog.sword());
        bag.add(ItemCatalog.bow());
        bag.add(ItemCatalog.heal());
        bag.add(ItemCatalog.speed());
        bag.add(ItemCatalog.strength());
        bag.add(ItemCatalog.knife());
        bag.add(ItemCatalog.coin());
        bag.add(ItemCatalog.coin());
        bag.add(ItemCatalog.coin());
    }

    // Q: drop the currently selected weapon/item -- it lands on the ground
    // next to the player and any NPC (or the player, via walk-over) can
    // pick it up later. Weight-gate check happens on pickup.
    public void dropSelectedItem() {
        java.util.List<Item> bagItems = bag.list();
        if (bagItems.isEmpty()) return;
        int slot = Math.min(currentWeaponIndex, bagItems.size() - 1);
        Item toDrop = bagItems.get(slot);
        if (!bag.remove(toDrop)) return;
        Entity.groundItems.add(new Entity.GroundItem(toDrop, x + size / 2, y + size));
    }
    public void Attack(List<Bullet> bullets, java.util.List<Entity> entities, List<Obstacle> obstacles, Point mousePos){
        int dx = x - mousePos.x + 30; int dy = y - mousePos.y + 30;
        double length = Math.sqrt(dx*dx + dy*dy);
        if(length == 0){
            length = 1;
        }
        direction[0]=dx/length;
        direction[1]=dy/length;
        lastAimAngle = Math.atan2(-direction[1], -direction[0]);
        if (Math.abs(dx) > Math.abs(dy)) {
            facing = 2;
            facingLeft = dx > 0;
        } else {
            facing = dy > 0 ? 0 : 1;
        }
        // ---- use the SELECTED BAG ITEM (the same Item system NPCs use) ----
        java.util.List<Item> bagItems = bag.list();
        if (bagItems.isEmpty()) return;
        int slot = Math.min(currentWeaponIndex, bagItems.size() - 1);
        Item sel = bagItems.get(slot);
        attackStart = now;
        if (sel instanceof WeaponItem) {
            boolean bow = sel.name.equals("Bow");
            boolean thrown = sel instanceof ProjectileItem;
            attackKind = (bow || thrown) ? 1 : 0;
            attackDur = (bow || thrown) ? 350 : 280;
            if (thrown) { // axes/spears FLY and SPIN via the shared Bullet system
                Bullet b = new Bullet(sel.sprite, true,
                    x + 10 + (int)(-direction[0] * 15),
                    y + 10 + (int)(-direction[1] * 15),
                    10, new double[]{-direction[0], -direction[1]});
                b.holderScale = 1.25 * size / 30.0;
                bullets.add(b);
            } else if (bow) { // arrows fly via the Bullet system
                bullets.add(new Bullet(
                    x + 10 + (int)(-direction[0] * 15),
                    y + 10 + (int)(-direction[1] * 15),
                    10, new double[]{-direction[0], -direction[1]}));
            } else { // melee: sweep EVERY entity in the swing arc (nobody exempt)
                java.awt.Rectangle sweep = new java.awt.Rectangle(
                    x - 9 + (int)(direction[0] * -range), y - 9 + (int)(direction[1] * -range),
                    range, range);
                for (Entity en : entities) {
                    if (en.getBounds().intersects(sweep)) en.hurt(effectiveDamage(sel.damage), x, y);
                }
                // swinging at a ripe crop harvests its produce to the ground
                for (int i = obstacles.size() - 1; i >= 0; i--) {
                    Obstacle ob = obstacles.get(i);
                    if (ob instanceof Obstacle.Crop && ob.getBounds().intersects(sweep)) {
                        Item produce = ((Obstacle.Crop) ob).harvest();
                        if (produce != null) {
                            Entity.groundItems.add(new Entity.GroundItem(produce,
                                ob.x + ob.size / 2, ob.y + ob.size));
                            Effects.debris(ob.x + ob.size / 2.0, ob.y + ob.size * 0.5, false, 3);
                            obstacles.remove(i); // picked clean
                        } else ob.disturb(0.8); // not ripe yet: just rustles
                    }
                }
                Effects.slashGlint(x - cameralessOffset() + (int)(-direction[0] * range),
                    y - cameralessOffset() + (int)(-direction[1] * range),
                    lastAimAngle, 1.0);
            }
        } else if (sel instanceof PotionItem || sel instanceof FoodItem) {
            attackKind = 2; // drink/eat pose
            attackDur = 600;
            sel.use(this, direction);
            bag.remove(sel); // consumed
        } else {
            attackKind = -1; // junk: no animation
        }
    }
    void setOnWater(boolean onWater) {
        this.onWater = onWater;
    }
    boolean onWater() {
        return onWater;
    }
    // unit vector of the last attack aim in SCREEN coords (y-down), toward
    // the mouse. lastAimAngle = atan2(ty, tx) of the toward-mouse vector, so
    // (cos, sin) of it is exactly that unit vector.
    public double[] aimDirection() {
        return new double[]{Math.cos(lastAimAngle), Math.sin(lastAimAngle)};
    }
    public void tryJump() {
        if (onWater) return; // no hop while swimming
        if (now - jumpStart >= 520) jumpStart = now;
    }
    private double jumpOffset() {
        long t = now - jumpStart;
        return t >= 0 && t < 520 ? -Math.sin(Math.PI * t / 520.0) * 44 : 0;
    }
    public void update(int playerDx, int playerDy,List<Obstacle> obstacles,java.util.List<Entity> entities){
        now = System.currentTimeMillis();
        survivalTick();
        autoPickup();
        move(playerDx, playerDy, obstacles, entities);
        movingVisual = (playerDx != 0 || playerDy != 0);
        if (movingVisual) {
            if (playerDy > 0) facing = 0;
            else if (playerDy < 0) facing = 1;
            else { facing = 2; facingLeft = playerDx < 0; }
            if (playerDx != 0) facingLeft = playerDx < 0;
        }
        if(health <= 0){
            die();
        }
    }

    // Smooth obstacle handling: try the full move; if blocked, retry each axis
    // separately so the player SLIDES along walls/rocks instead of sticking.
    public void updateWithSliding(int playerDx, int playerDy, List<Obstacle> obstacles, java.util.List<Entity> entities) {
        now = System.currentTimeMillis();
        survivalTick();
        autoPickup();
        // 1) full diagonal move
        if (!tryMove(playerDx, playerDy, obstacles, entities)) {
            // 2) blocked: slide on whichever axis still works
            boolean movedX = false;
            if (playerDx != 0) movedX = tryMove(playerDx, 0, obstacles, entities);
            if (playerDy != 0) tryMove(0, playerDy, obstacles, entities);
            movingVisual = movedX || playerDy != 0;
        } else {
            movingVisual = (playerDx != 0 || playerDy != 0);
        }
        if (movingVisual) { // face along the ACTUAL slide direction
            int fdx = playerDx, fdy = playerDy;
            if (fdy > 0) facing = 0;
            else if (fdy < 0) facing = 1;
            else { facing = 2; facingLeft = fdx < 0; }
            if (fdx != 0) facingLeft = fdx < 0;
        }
        if(health <= 0){
            die();
        }
    }

    // attempt a move; returns true if it was not blocked
    // Effects.slashGlint is world-space; Attack() has no camera, so the panel
    // passes camera via this holder each tick (simplest non-invasive seam)
    int camX, camY;
    private int cameralessOffset() { return 0; }

    // survival: hunger rises; starving drains health; fed regenerates
    private void survivalTick() {
        hunger = Math.min(100, hunger + 0.004);
        stamina = Math.max(0, Math.min(100, stamina + (movingVisual ? -0.03 : 0.05)));
        if (hunger >= 100) {
            health -= 0.02;
            if (health <= 0) die();
        } else if (hunger < 50 && health < 100) {
            health = (int) Math.min(100, health + 0.01);
        }
    }

    // walk over a dropped item -> into the bag (weight-gated)
    private void autoPickup() {
        for (int i = Entity.groundItems.size() - 1; i >= 0; i--) {
            Entity.GroundItem gi = Entity.groundItems.get(i);
            if (Math.hypot(gi.x - (x + size / 2.0), gi.y - (y + size / 2.0)) < 24
                && bag.canPickUp(gi.item)) {
                bag.add(gi.item);
                Entity.groundItems.remove(i);
                Effects.sparkle(gi.x, gi.y, Effects.FOAM, 3);
            }
        }
    }

    private boolean tryMove(int dx, int dy, List<Obstacle> obstacles, java.util.List<Entity> entities) {
        int currentX = x, currentY = y;
        // knockback first (decays, blocked like normal movement)
        if (Math.abs(kbx) >= 0.2 || Math.abs(kby) >= 0.2) {
            x += (int) Math.round(kbx);
            y += (int) Math.round(kby);
            for (Obstacle ob : obstacles) {
                if (ob.blocks() && ob.getBounds().intersects(getBounds())) { x = currentX; y = currentY; break; }
            }
            for (Entity en : entities) {
                if (en.getBounds().intersects(getBounds())) { x = currentX; y = currentY; break; }
            }
            kbx *= 0.7; kby *= 0.7;
        }
        if (dx == 0 && dy == 0) return true;
        double len = Math.sqrt(dx*dx + dy*dy);
        x += (int)(effectiveSpeed()*dx/len);
        y += (int)(effectiveSpeed()*dy/len);
        for(Obstacle ob : obstacles){
            if (ob.blocks() && ob.getBounds().intersects(getBounds())){
                x = currentX; y = currentY;
                return false;
            }
        }
        for(Entity en : entities){
            if (en.getBounds().intersects(getBounds())){
                x = currentX; y = currentY;
                return false;
            }
        }
        return true;
    }


    public void die(){
        GameOver = true;
        Effects.pop(x + size / 2.0, y + size / 2.0, size * 1.4, Effects.BLOOD);
    }
    // respawn: pop back at spawn with full health, clear combat state
    public void respawn(int sx, int sy) {
        x = sx; y = sy;
        health = 100;
        GameOver = false;
        hurtStart = -100000;
        attackStart = -100000;
        attackKind = -1;
        kbx = 0; kby = 0;
        jumpStart = -100000;
        onWater = false;
    }
    // hurt feedback: red flash + squash + knockback away from (fx,fy)
    long hurtStart = -100000;
    double kbx = 0, kby = 0;
    public void hurt(int dmg, double fx, double fy) {
        health -= dmg;
        hurtStart = System.currentTimeMillis();
        double dx = x + size / 2.0 - fx, dy = y + size / 2.0 - fy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) len = 1;
        kbx = dx / len * 4.0;
        kby = dy / len * 4.0;
        Effects.hit(x + size / 2.0, y + size / 2.0, size * 0.6, Effects.BLOOD);
        if (health <= 0) die(); // death can trigger from any damage source
    }
    public void draw(Graphics g, int cameraX, int cameraY, int ScreenWidth, int ScreenHeight) {
        if (GameOver){
            g.setFont(new Font("Arial", Font.BOLD, 48));
            g.drawString("Game Over", ScreenWidth / 2 - 140, ScreenHeight / 2);
        }
        double jumpOff = jumpOffset();
        double zoom = 2.0 * size / 30.0; // tiles are 16px art drawn at 32px (zoom 2)
        int ax = x - cameraX + size / 2;
        int by = (int) Math.round(y - cameraY + size + jumpOff);
        long at = now - attackStart;
        boolean attacking = attackKind >= 0 && at >= 0 && at < attackDur;
        long ht = now - hurtStart;
        boolean hurting = ht >= 0 && ht < 300 && !GameOver;
        if (attacking && attackKind == 0) { // one-shot sword slash body anim
            int col = (int) Math.min(at * 4 / attackDur, 3);
            // pad 7 pins the feet: slash arcs reach the frame bottom
            slash[facing].drawFrame(g, col, ax, by, zoom, facingLeft, 7);
        } else if (attacking && attackKind == 2) { // potion drink: arms-up pose,
            // flask rises overhead (WeaponView); 48px frame shares the feet line
            drink.drawFrame(g, 0, ax, by, zoom, facingLeft, 15);
        } else if (onWater) { // swim loop replaces walk/idle while in water
            swim.draw(g, ax, by, zoom, facingLeft);
        } else {
            SheetAnim a = movingVisual ? walk[facing] : idle[facing];
            int hx0 = ax, hy0 = by;
            if (hurting) { // squash: flatter, wider
                a.draw(g, (int) (ax + (1.18 - 1.0) * size * 0.25), by, zoom * 0.84, facingLeft);
            } else {
                a.draw(g, ax, by, zoom, facingLeft);
            }
        }
        if (hurting) { // red flash over the body
            Graphics2D gf = (Graphics2D) g.create();
            gf.setColor(new Color(255, 90, 90, (int) ((1 - (float) ht / 300f) * 120)));
            gf.fillRect(x - cameraX, y - cameraY + ScreenHeight/20 - 6, size, size);
            gf.dispose();
        }
        drawWeapon(g, cameraX, cameraY, zoom, jumpOff, attacking ? (double) at / attackDur : 1.0);
        // (the legacy weapon cooldown HUD is retired -- hearts + bag panel cover it)
        // hearts above head (same UI as every entity); the old bar is gone
        Entity.drawStats(g, x - cameraX + size / 2, y - cameraY + size,
            (int) Math.round(health), 100, (int) hunger, (int) Math.round(staminaBar()));
    }
    private void drawWeapon(Graphics g, int cameraX, int cameraY, double zoom, double jumpOff, double attackT) {
        double hx = x - cameraX + size / 2.0;
        double hy = y - cameraY + size * 0.55 + jumpOff;
        double s = zoom / 2.0;
        java.util.List<Item> bagItems = bag.list();
        if (bagItems.isEmpty()) return;
        int slot = Math.min(currentWeaponIndex, bagItems.size() - 1);
        Item sel = bagItems.get(slot);
        if (sel == null || sel.sprite == null) return;
        double hs = s * 1.25; // every item 1.25x, then scaled by holder size via `s`
        if (sel instanceof WeaponItem && sel.name.equals("Bow")) {
            // bow keeps its bespoke draw-pull animation
            WeaponView.draw(g, 2, hx, hy, lastAimAngle, attackT, hs * 1.1);
        } else if (sel instanceof WeaponItem) {
            // generic held-weapon draw: sprite aimed at the mouse, swings on attack
            WeaponView.drawItem(g, sel.sprite, hx, hy, lastAimAngle, attackT, hs * 1.5);
        } else {
            // consumable held low; lifts overhead while drinking (attackT<1)
            double fs = attackT < 1.0 ? hs * 1.6 : hs * 1.1;
            double lift = attackT < 1.0 ? 23.0 * s : 0;
            WeaponView.drawItem(g, sel.sprite, hx, hy - lift, 0, 1.0, fs);
        }
    }
    public void move(int dx, int dy, List<Obstacle> obstacles, java.util.List<Entity> entities){
        int currentX = x;
        int currentY = y;
        // knockback first: decays fast; blocked by geometry like normal movement
        if (Math.abs(kbx) >= 0.2 || Math.abs(kby) >= 0.2) {
            x += (int) Math.round(kbx);
            y += (int) Math.round(kby);
            for(Obstacle ob : obstacles){
                if (ob.getBounds().intersects(getBounds())){ x = currentX; y = currentY; break; }
            }
            for(Entity en : entities){
                if (en.getBounds().intersects(getBounds())){ x = currentX; y = currentY; break; }
            }
            kbx *= 0.7; kby *= 0.7;
        }
        double len = Math.sqrt(dx*dx + dy*dy);
        x += (int)(effectiveSpeed()*dx/len);
        y += (int)(effectiveSpeed()*dy/len);
        for(Obstacle ob : obstacles){
            if (ob.blocks() && ob.getBounds().intersects(getBounds())){
                x = currentX;
                y = currentY;
            }
        }
        for(Entity en : entities){
            if (en.getBounds().intersects(getBounds())){
                x = currentX;
                y = currentY;
            }
        }
    }
    // mouse wheel cycles the BAG (the real Item system -- same as NPCs)
    public void changeWeapon(int notches){
        if (bag.list().isEmpty()) return;
        if(notches > 0) {
            currentWeaponIndex = (currentWeaponIndex + 1) % bag.list().size();
        }
        else if (notches < 0) {
            currentWeaponIndex = (currentWeaponIndex - 1 + bag.list().size()) % bag.list().size();
        }
    }
    public Rectangle getBounds(){
        return new Rectangle((int)(x + size*0.1),(int)(y + size*0.1),(int)(size - size*0.2),(int)(size - size*0.2));
    }
}
