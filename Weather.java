import java.awt.*;
import java.util.Random;

// Day/night cycle + weather system. One instance per game; the panel ticks it
// every frame and reads: light overlay alpha, current weather, intensity, and
// spawns weather particles (rain/snow/wind-leaves) on a timer.
//
// Weather types: sunny, windy, stormy, rainy, snowy, foggy.
public class Weather {
    // ---- day/night ----
    // full cycle = DAY_MS; t in [0,1): 0.0 dawn -> 0.25 noon -> 0.5 dusk ->
    // 0.75 midnight. Light level from a smooth curve, 1 = bright noon,
    // 0.35 = deep night.
    static final long DAY_MS = 240_000; // 4 minutes per full day
    long worldStart = System.currentTimeMillis();
    double timeOfDay() {
        double t = ((System.currentTimeMillis() - worldStart) % DAY_MS) / (double) DAY_MS;
        return t;
    }
    // 0..1 light level
    double lightLevel() {
        double t = timeOfDay();
        // daylight hump between 0.9 -> 0.1 (wrapping) and night trough mid-cycle
        double l = 0.5 - 0.5 * Math.cos((t - 0.5) * Math.PI * 2); // 0 at t=0.5, 1 at 0/1... inverted below
        // want: bright around t=0 (noon), dark at t=0.5 (midnight)
        return 0.35 + 0.65 * l;
    }

    // ---- weather ----
    static final String[] WEATHERS = {"sunny", "windy", "stormy", "rainy", "snowy", "foggy"};
    String current = "sunny";
    double intensity = 1.0;      // 0.3..1.0 strength of the current weather
    long lastChange = System.currentTimeMillis();
    long weatherDuration = 60_000; // each weather lasts ~1 minute
    long lastParticle = 0;
    long lastFogSpawn = 0;
    final Random rand = new Random();
    long lightningFlash = -100000;

    void tick(GamePanelHost host, int viewW, int viewH) {
        long now = System.currentTimeMillis();
        if (now - lastChange > weatherDuration) {
            // roll a new weather, weighted: sunny most common
            String[] pool = {"sunny", "sunny", "windy", "rainy", "stormy", "snowy", "foggy"};
            String next = pool[rand.nextInt(pool.length)];
            if (!next.equals(current)) {
                current = next;
                intensity = 0.3 + rand.nextDouble() * 0.7;
                lastChange = now;
            } else {
                lastChange = now; // same weather: extend duration
            }
        }
        // storm lightning: random flashes
        if (current.equals("stormy") && now - lightningFlash > 2500 + rand.nextInt(4000)) {
            lightningFlash = now;
        }
        // weather particles
        if (now - lastParticle > 120) {
            lastParticle = now;
            switch (current) {
                case "rainy": case "stormy":
                    Effects.rainBurst(viewW, viewH, current.equals("stormy") ? intensity * 1.4 : intensity);
                    break;
                case "snowy":
                    Effects.snowBurst(viewW, viewH, intensity);
                    break;
                case "windy":
                    Effects.windBurst(viewW, viewH, intensity);
                    break;
                case "foggy":
                    if (now - lastFogSpawn > 2500) {
                        lastFogSpawn = now;
                        Effects.fogBurst(viewW, viewH, intensity);
                    }
                    break;
            }
        }
    }

    boolean isWet() { return current.equals("rainy") || current.equals("stormy"); }

    // ---- overlay rendering (called by the panel AFTER entities) ----
    void drawOverlay(Graphics2D g, int w, int h, int camX, int camY) {
        long now = System.currentTimeMillis();
        // day/night tint: warm dawn/dusk, blue night
        double light = lightLevel();
        double t = timeOfDay();
        boolean dawnDusk = (t > 0.85 || t < 0.15);
        if (light < 0.999) {
            int alpha = (int) Math.round((1 - light) * 110);
            Color tint = dawnDusk && alpha > 20
                ? new Color(255, 150, 60, alpha / 2)     // golden hour
                : new Color(20, 24, 70, alpha);          // night blue
            g.setColor(tint);
            g.fillRect(0, 0, w, h);
        }
        // weather overlays
        switch (current) {
            case "foggy":
                // faint atmospheric tint only -- the FOG ITSELF is drawn as
                // pixel-art banks by the particle system (Effects.fogBurst)
                g.setColor(new Color(220, 230, 240, 45));
                g.fillRect(0, 0, w, h);
                break;
            case "stormy": {
                // lightning flash: 2 frames of white
                long lt = now - lightningFlash;
                if (lt >= 0 && lt < 140) {
                    g.setColor(new Color(255, 255, 255, (int) (200 * (1 - lt / 140.0))));
                    g.fillRect(0, 0, w, h);
                }
                // storm darkening
                g.setColor(new Color(30, 34, 60, 60));
                g.fillRect(0, 0, w, h);
                break;
            }
            case "rainy":
                g.setColor(new Color(60, 80, 120, 40));
                g.fillRect(0, 0, w, h);
                break;
            case "snowy":
                g.setColor(new Color(200, 220, 255, 45));
                g.fillRect(0, 0, w, h);
                break;
        }
    }

    // weather name for the HUD
    String label() {
        double t = timeOfDay();
        String phase = t < 0.20 ? "morning" : t < 0.45 ? "noon" : t < 0.70 ? "evening" : "night";
        return current + " / " + phase;
    }

    // minimal host interface the Weather system needs from the panel
    interface GamePanelHost { }
}
