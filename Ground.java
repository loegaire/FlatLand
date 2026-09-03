import java.awt.*;

// One ground tile (grass / path / water) with reactive state fields.
// The world keeps a Ground[TILES][TILES] grid; entities stamp themselves on
// it every tick, and weather reacts through the weather field.
public class Ground {
    // static terrain type from world gen (immutable)
    final char terrain;          // 'g' grass, 'p' path, 'w' water
    // ---- live state ----
    boolean playerOn;            // player stands on this tile
    boolean enemyOn;             // >= 1 enemy stands on this tile
    String weather = "sunny";    // weather condition affecting this tile
    // cosmetic state derived from the above:
    // - trampledTimer: pressed grass springs back over time
    long trampledSince = -1;     // when trampling started (-1 = pristine)
    long lastDisturbed = -1;     // last water-splash / grass-rustle here

    Ground(char terrain) { this.terrain = terrain; }

    boolean isWater() { return terrain == 'w'; }
    boolean isPath() { return terrain == 'p'; }
    boolean isGrass() { return terrain == 'g'; }

    // entities stamping themselves onto the tile
    void setPlayerOn(boolean on) {
        if (on && !playerOn) markTrampled();
        playerOn = on;
    }
    void setEnemyOn(boolean on) {
        if (on && !enemyOn) markTrampled();
        enemyOn = on;
    }
    private void markTrampled() {
        long now = System.currentTimeMillis();
        trampledSince = now;
    }
    void setWeather(String w) { weather = w; }

    // trample age in ms (Long.MAX_VALUE when pristine)
    long trampleAge(long now) {
        return trampledSince < 0 ? Long.MAX_VALUE : now - trampledSince;
    }
    boolean trampled(long now) { return trampleAge(now) < 4000; }

    // tint applied when drawing this tile (trampled grass slightly darker)
    Color trampleTint(long now) {
        if (terrain == 'g' && trampled(now)) {
            float k = 1f - Math.min(1f, trampleAge(now) / 4000f); // 1 fresh -> 0 recovered
            return new Color(0f, 0f, 0f, 0.12f * k);
        }
        return null;
    }
}
