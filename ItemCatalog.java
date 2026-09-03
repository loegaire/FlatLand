import java.util.HashMap;
import java.util.Map;

// ============================================================================
// Item catalog: maps every item NAME to its pack sprite + stats.
// This is the single text->visual mapping for all items (user requirement).
//
// All weights >= 1 (no 0-weight items -> capacity can never be infinite).
// Sprites are the real 0x72 pack files; nothing here is reused as obstacle art.
// ============================================================================
public class ItemCatalog {
    // name -> {weight, damage, sprite}
    static final Object[][] DEFS = {
        // ---- weapons (0x72 weapon_*.png) ----
        {"Sword",          4.0, 20, "assets/dungeon/weapon_regular_sword.png"},
        {"Katana",         3.0, 18, "assets/dungeon/weapon_katana.png"},
        {"Knife",          1.0,  8, "assets/dungeon/weapon_knife.png"},
        {"Cleaver",        4.0, 15, "assets/dungeon/weapon_cleaver.png"},
        {"War Axe",        6.0, 17, "assets/dungeon/weapon_waraxe.png"},
        {"Mace",          4.0, 14, "assets/dungeon/weapon_mace.png"},
        {"Spear",          3.0, 12, "assets/dungeon/weapon_spear.png"},
        {"Axe",            5.0, 15, "assets/dungeon/weapon_throwing_axe.png"},
        {"Hammer",         6.0, 12, "assets/dungeon/weapon_hammer.png"},
        {"Bow",            2.5, 10, "assets/dungeon/weapon_bow.png"},
        // ---- potions (0x72 flask_*.png) ----
        {"Healing Potion",   1.5,  0, "assets/dungeon/flask_red.png"},
        {"Big Healing",      3.0,  0, "assets/dungeon/flask_big_red.png"},
        {"Swiftness Potion", 1.5,  0, "assets/dungeon/flask_blue.png"},
        {"Big Swiftness",    3.0,  0, "assets/dungeon/flask_big_blue.png"},
        {"Strength Potion",  1.5,  0, "assets/dungeon/flask_green.png"},
        // ---- food (pack crop sprites): recovers hunger ----
        {"Wheat",    1.0,  0, "assets/map/crop_s3v0.png"},
        {"Berries",  1.0,  0, "assets/map/crop_s2v0.png"},
        {"Herbs",    1.0,  0, "assets/map/crop_s1v0.png"},
        // ---- treasure / junk (0x72 coin + skull) ----
        {"Coin",           1.0,  0, "assets/dungeon/coin_anim_f0.png"},
        {"Skull",          1.0,  0, "assets/dungeon/skull.png"},
        // small field rocks: pack rock sprite; ground items, not obstacles
        {"Pebble",         1.0,  0, "assets/map/rock_small.png"},
        {"Stone Chunk",    2.0,  0, "assets/map/rock_mossy.png"},
    };

    static final Map<String, Item> BY_NAME = new HashMap<>();
    static {
        for (Object[] d : DEFS)
            BY_NAME.put((String) d[0], make((String) d[0], (Double) d[1], (Integer) d[2], (String) d[3]));
    }

    static Item make(String name, double weight, int damage, String sprite) {
        if (name.contains("Potion") || name.contains("Healing") || name.contains("Swiftness"))
            return new PotionItem(name, weight, sprite);
        if (name.equals("Axe") || name.equals("Spear"))
            return new ProjectileItem(name, weight, damage, sprite);
        if (name.equals("Wheat")) return new FoodItem(name, weight, sprite, 40);
        if (name.equals("Berries")) return new FoodItem(name, weight, sprite, 30);
        if (name.equals("Herbs")) return new FoodItem(name, weight, sprite, 20);
        if (name.equals("Coin") || name.equals("Skull"))
            return new JunkItem(name, weight, sprite);
        return new WeaponItem(name, weight, damage, sprite);
    }

    // accessors used everywhere ("give me a sword")
    public static Item get(String name) {
        Item it = BY_NAME.get(name);
        return it == null ? null : it.fresh();
    }
    public static Item sword()  { return get("Sword"); }
    public static Item katana() { return get("Katana"); }
    public static Item knife()  { return get("Knife"); }
    public static Item axe()    { return get("Axe"); }
    public static Item hammer() { return get("Hammer"); }
    public static Item spear()  { return get("Spear"); }
    public static Item mace()   { return get("Mace"); }
    public static Item cleaver(){ return get("Cleaver"); }
    public static Item warAxe() { return get("War Axe"); }
    public static Item bow()    { return get("Bow"); }
    public static Item heal()   { return get("Healing Potion"); }
    public static Item bigHeal(){ return get("Big Healing"); }
    public static Item speed()  { return get("Swiftness Potion"); }
    public static Item bigSpeed(){ return get("Big Swiftness"); }
    public static Item strength(){ return get("Strength Potion"); }
    public static Item wheat()  { return get("Wheat"); }
    public static Item berries(){ return get("Berries"); }
    public static Item herbs()  { return get("Herbs"); }
    public static Item randomFood(java.util.Random r) {
        return get(new String[]{"Wheat", "Berries", "Herbs"}[r.nextInt(3)]);
    }
    public static Item coin()   { return get("Coin"); }
    public static Item pebble() { return get("Pebble"); }
    public static Item stoneChunk() { return get("Stone Chunk"); }
    public static Item skull()  { return get("Skull"); }

    // a random weapon for kits/loot
    static final String[] WEAPONS = { "Sword", "Katana", "Knife", "Cleaver",
        "War Axe", "Mace", "Spear", "Axe", "Hammer", "Bow" };
    public static Item randomWeapon(java.util.Random r) { return get(WEAPONS[r.nextInt(WEAPONS.length)]); }
    public static Item randomPotion(java.util.Random r) {
        return get(new String[]{"Healing Potion","Swiftness Potion","Strength Potion"}[r.nextInt(3)]);
    }
}
