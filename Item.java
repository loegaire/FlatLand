import java.awt.*;

// ============================================================================
// Item: base of all holdable/useable/droppable world objects.
//   identity     -- name + sprite (see ItemCatalog: the text->visual map)
//   physical     -- weight >= 1 (never 0: capacity can't be infinite)
//   function     -- use(target, aimDir) performs the effect
//   interaction  -- interactHint() describes the effect (humans + AI read it)
// ============================================================================
public abstract class Item {
    public final String name;
    public final double weight;
    public final int damage;
    public final Image sprite;
    final String spritePath; // kept so fresh() can rebuild identical items

    Item(String name, double weight, int damage, String spritePath) {
        this.name = name;
        this.weight = Math.max(1.0, weight);
        this.damage = damage;
        this.spritePath = spritePath;
        this.sprite = SpriteAnim.load(spritePath);
    }

    // effect of using this item on a target (Entity, GroundItem, Obstacle...)
    public abstract String use(Object target, double[] aimDir);

    public String interactHint() { return "use " + name; }

    // fresh copy with the same identity (inventories hold instances)
    public abstract Item fresh();

    public String toString() { return name + "(" + weight + "kg)";
    }
    public String toStringFull() { return name; }
}

// ---- weapons: hurt Entities ----
class WeaponItem extends Item {
    WeaponItem(String name, double weight, int damage, String spritePath) {
        super(name, weight, damage, spritePath);
    }
    @Override
    public String use(Object target, double[] aimDir) {
        if (target instanceof Entity) {
            Entity t = (Entity) target;
            double sx = t.x, sy = t.y;
            if (aimDir != null && aimDir.length >= 2) { sx = t.x - aimDir[0] * 8; sy = t.y - aimDir[1] * 8; }
            t.hurt(damage, sx, sy);
            return "hit " + target + " with " + name;
        }
        if (target instanceof Player) { // the player is a target like any other
            Player p = (Player) target;
            p.hurt(damage, p.x - (aimDir == null ? 0 : aimDir[0] * 8),
                          p.y - (aimDir == null ? 0 : aimDir[1] * 8));
            return "hit the player with " + name;
        }
        if (target instanceof Obstacle) {
            ((Obstacle) target).disturb(0.8);
            return "struck";
        }
        return "swung " + name;
    }
    @Override
    public String interactHint() { return damage > 0 ? "attack (" + damage + " dmg)" : "no combat use"; }
    @Override
    public Item fresh() { return new WeaponItem(name, weight, damage, spritePath); }
}

// ---- thrown weapons: fly, SPIN, and stick like arrows ----
class ProjectileItem extends WeaponItem {
    // spinning in flight: shared-clock rotation like the pack's coin anim
    static long clock() { return System.currentTimeMillis(); }
    ProjectileItem(String name, double weight, int damage, String spritePath) {
        super(name, weight, damage, spritePath);
    }
    @Override
    public Item fresh() { return new ProjectileItem(name, weight, damage, spritePath); }
    @Override
    public String interactHint() { return "throw (spins, sticks)"; }
}

// ---- potions: heal / buff the drinker ----
class PotionItem extends Item {
    public enum Kind { HEAL, SPEED, STRENGTH }
    public final Kind kind;
    final boolean big; // big flasks: stronger effect, more weight
    PotionItem(String name, double weight, String spritePath) {
        super(name, weight, 0, spritePath);
        this.kind = name.contains("Heal") ? Kind.HEAL
            : name.contains("Swift") ? Kind.SPEED : Kind.STRENGTH;
        this.big = name.startsWith("Big");
    }
    @Override
    public String use(Object target, double[] aimDir) {
        if (target instanceof Entity) {
            Entity e = (Entity) target;
            switch (kind) {
                case HEAL: e.health = Math.min(e.maxHealth(), e.health + (big ? 40 : 20)); break;
                case SPEED: e.speedBoostUntil = System.currentTimeMillis() + 5000; break;
                case STRENGTH: e.damageBoostUntil = System.currentTimeMillis() + 5000; break;
            }
            Effects.sparkle(e.x + e.size / 2.0, e.y + e.size / 2.0, Effects.FOAM, 4);
            return e + " drank " + name;
        }
        if (target instanceof Player) { // the player drinks too (same effects)
            Player p = (Player) target;
            switch (kind) {
                case HEAL: p.health = (int) Math.min(100, p.health + (big ? 40 : 20)); break;
                case SPEED: p.speedBoostUntil = System.currentTimeMillis() + 5000; break;
                case STRENGTH: p.damageBoostUntil = System.currentTimeMillis() + 5000; break;
            }
            Effects.sparkle(p.x + p.size / 2.0, p.y + p.size / 2.0, Effects.FOAM, 4);
            return "drank " + name;
        }
        return "spilled " + name;
    }
    @Override
    public String interactHint() {
        return kind == Kind.HEAL ? "heal +" + (big ? 40 : 20)
            : kind == Kind.SPEED ? "speed boost 5s" : "strength boost 5s";
    }
    @Override
    public Item fresh() { return new PotionItem(name, weight, spritePath); }
}

// ---- food: recovers HUNGER (the survival stat). Pack crop sprites. ----
class FoodItem extends Item {
    final int hungerRestore;
    FoodItem(String name, double weight, String spritePath, int hungerRestore) {
        super(name, weight, 0, spritePath);
        this.hungerRestore = hungerRestore;
    }
    @Override
    public String use(Object target, double[] aimDir) {
        if (target instanceof Entity) {
            Entity e = (Entity) target;
            e.state.hunger = Math.max(0, e.state.hunger - hungerRestore);
            e.health = Math.min(e.maxHealth(), e.health + hungerRestore * 0.25); // food heals a bit
            Effects.sparkle(e.x + e.size / 2.0, e.y + e.size / 2.0, new Color(254, 240, 159), 4);
            return e + " ate " + name;
        }
        if (target instanceof Player) {
            Player p = (Player) target;
            p.hunger = Math.max(0, p.hunger - hungerRestore);
            p.health = (int) Math.min(100, p.health + hungerRestore * 0.25);
            Effects.sparkle(p.x + p.size / 2.0, p.y + p.size / 2.0, new Color(254, 240, 159), 4);
            return "ate " + name;
        }
        return "dropped " + name;
    }
    @Override
    public String interactHint() { return "eat (-" + hungerRestore + " hunger, +" + (int)(hungerRestore * 0.25) + " hp)"; }
    @Override
    public Item fresh() { return new FoodItem(name, weight, spritePath, hungerRestore); }
}

// ---- treasure/junk: no active effect; carried for value ----
class JunkItem extends Item {
    JunkItem(String name, double weight, String spritePath) {
        super(name, weight, 0, spritePath);
    }
    @Override
    public String use(Object target, double[] aimDir) { return "held " + name; }
    @Override
    public String interactHint() { return "treasure"; }
    @Override
    public Item fresh() { return new JunkItem(name, weight, spritePath); }
}
