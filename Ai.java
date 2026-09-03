import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// ============================================================================
// AI: the NPC decision foundation.
//
//   NpcBrain.decide(selfState, attributes, perceived, inventory) -> Action
//
// Pipeline (spec): candidates -> P(action_type) -> P(params|type) -> sample.
// No hard-coded high-level behaviours: rescue/betray/flee Emerge from the
// 8 attributes + situation. The decision function is a replaceable object:
// a learned f(environment, attributes) -> distribution can swap in later.
// ============================================================================

// ---- 1. Action space: exactly five parameterized types ----
class Action {
    enum Type { MOVE, USE, INTERACT, SPEAK, DROP_ITEM }
    final Type type;
    final Object target;  // entity/point acted toward (null when unused)
    final Item object;    // inventory item for USE / DROP_ITEM (null otherwise)
    Action(Type type, Object target, Item object) {
        this.type = type; this.target = target; this.object = object;
    }
    static Action move(Object t) { return new Action(Type.MOVE, t, null); }
    static Action use(Item o, Object t) { return new Action(Type.USE, t, o); }
    static Action interact(Object t) { return new Action(Type.INTERACT, t, null); }
    static Action speak(Object t) { return new Action(Type.SPEAK, t, null); }
    static Action drop(Item o) { return new Action(Type.DROP_ITEM, null, o); }

    public String toString() {
        switch (type) {
            case MOVE: return "MOVE(" + target + ")";
            case USE: return "USE(" + (object == null ? "?" : object.name) + ", " + target + ")";
            case INTERACT: return "INTERACT(" + target + ")";
            case SPEAK: return "SPEAK(" + target + ")";
            case DROP_ITEM: return "DROP(" + (object == null ? "?" : object.name) + ")";
        }
        return "?";
    }
}

// ---- 2. Self state ----
class NpcState {
    double health;    // 0..100
    double hunger;    // 0..100 (100 = starving)
    double stamina;   // 0..100
    double x, y;      // position
    double vx, vy;    // velocity
    Action lastAction; // previous decision (for inertia; unused for now)
    NpcState(double x, double y) { this.x = x; this.y = y; health = 100; stamina = 100; hunger = 0; }
}

// ---- 3. Predisposition attributes: exactly 8, each in [0,1] ----
class Attributes {
    // index order: AGG, COU, EMP, LOY, GRE, CUR, SOC, IMP
    final double[] a = new double[8];
    static final String[] NAMES = {
        "aggression", "courage", "empathy", "loyalty",
        "greed", "curiosity", "sociability", "impulsiveness"
    };
    static final int AGG=0, COU=1, EMP=2, LOY=3, GRE=4, CUR=5, SOC=6, IMP=7;

    Attributes(double... vals) {
        for (int i = 0; i < 8; i++) a[i] = clamp01(vals[i]);
    }
    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    double aggression()  { return a[AGG]; }
    double courage()     { return a[COU]; }
    double empathy()     { return a[EMP]; }
    double loyalty()     { return a[LOY]; }
    double greed()       { return a[GRE]; }
    double curiosity()   { return a[CUR]; }
    double sociability() { return a[SOC]; }
    double impulsiveness() { return a[IMP]; }

    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 8; i++)
            sb.append(NAMES[i]).append(String.format("=%.2f", a[i])).append(i < 7 ? " " : "");
        return sb.append("}").toString();
    }
}

// ---- 4. Perceived entity + the observer's static belief ----
class Perceived {
    enum Kind { PLAYER, NPC, ENEMY, ANIMAL, ITEM, OBSTACLE }
    final Object entity;
    final Kind type;
    final double x, y;
    final Action currentAction;
    final Attributes guess; // guess = actual + noise, clamped to [0,1]; static
    Perceived(Object entity, Kind type, double x, double y, Action currentAction, Attributes guess) {
        this.entity = entity; this.type = type; this.x = x; this.y = y;
        this.currentAction = currentAction; this.guess = guess;
    }
    double dist(double px, double py) { return Math.hypot(x - px, y - py); }
}

// ---- 5. Replaceable decision-function interface ----
interface DecisionFunction {
    // returns a list of (Action, probability) pairs forming a distribution
    List<Candidate> decide(NpcState self, Attributes attr,
                           List<Perceived> perceived, Inventory inv);
}

// one concrete action + its probability
class Candidate {
    final Action action;
    final double p;
    Candidate(Action action, double p) { this.action = action; this.p = p; }
}

// ---- 6. Default hand-designed statistical decision function ----
// Shapes P(type) and P(params|type) from attributes + state + perception.
// Simple weighted heuristics -> normalized distribution -> caller samples.
class StatisticalBrain implements DecisionFunction {
    StatisticalBrain() {}

    @Override
    public List<Candidate> decide(NpcState self, Attributes attr,
                                  List<Perceived> perceived, Inventory inv) {
        List<Perceived> npcs = new ArrayList<>(); // ALL characters (villagers + monsters)
        List<Perceived> interactables = new ArrayList<>();
        Perceived player = null;
        for (Perceived p : perceived) {
            switch (p.type) {
                case NPC: npcs.add(p); break;
                case OBSTACLE: case ITEM: interactables.add(p); break;
                case PLAYER: player = p; break;
                default: break;
            }
        }
        // COMBAT TARGETS ARE BELIEFS, not types: a "hostile" is anyone this
        // entity BELIEVES is aggressive (guessed aggression >= 0.55). A
        // monster looks at a random-draw villager with aggression 0.9 and
        // correctly reads danger; a villager reads a monster the same way.
        List<Perceived> hostiles = new ArrayList<>();
        for (Perceived p : npcs)
            if (p.guess.aggression() >= 0.55) hostiles.add(p);
        // PREDATION: highly aggressive entities also treat ANY nearby target
        // (including the player) as prey. Monsters spawn biased to cross this.
        boolean predator = attr.aggression() >= 0.65;
        if (predator && hostiles.isEmpty() && !npcs.isEmpty()) hostiles.add(nearest(npcs, self));
        if (predator && hostiles.isEmpty() && player != null) hostiles.add(player);
        List<Perceived> enemies = hostiles; // the threat model below reads these
        List<Item> items = inv.list();

        // ---- P(action_type): emergent drives, no hard-coded behaviours ----
        // THREAT MODEL: danger = proximity x how scary the threat is believed
        // to be (guessed aggression + low empathy = scary). Bravery only
        // tempers danger once; a badly-outmatched brave NPC still feels it.
        double nearestEnemy = Double.MAX_VALUE;
        double worstThreat = 0;          // 0..~1+ how bad the situation is
        Perceived biggestThreat = null;
        for (Perceived e : enemies) {
            double d = e.dist(self.x, self.y);
            nearestEnemy = Math.min(nearestEnemy, d);
            double proximity = Math.max(0, 1 - d / SIGHT_DANGER);
            double scariness = 0.5 * e.guess.aggression() + 0.3 * (1 - e.guess.empathy()) + 0.2;
            double threat = proximity * scariness;
            if (threat > worstThreat) { worstThreat = threat; biggestThreat = e; }
        }
        // SELF-PRESERVATION PRESSURE: low health, hunger and being outmatched
        // all push toward survival behaviours. COURAGE resists the push;
        // being near death overrides most courage.
        double selfPreservation = worstThreat * (self.health / 100.0 * 0.5 + 0.5);
        selfPreservation += (1 - self.health / 100.0) * 0.4;      // hurt = cautious
        selfPreservation += (self.hunger / 100.0) * 0.25;          // starving = risk-averse
        double effectiveCourage = attr.courage() * (self.health / 100.0 * 0.4 + 0.6);
        double fear = Math.max(0, selfPreservation - effectiveCourage * 0.8);
        boolean inDanger = worstThreat > 0.15;
        boolean hurt = self.health < 45;
        boolean hungry = self.hunger > 60;
        boolean weak = self.health < 70;
        boolean tired = self.stamina < 25;

        double wMove = 0.30, wUse = 0.20, wInteract = 0.15, wSpeak = 0.20, wDrop = 0.10;
        // fear drives movement (flee-shaped MOVE targets appear below);
        // courage+aggression drives engagement (USE on the threat)
        wMove += fear * 0.45;
        wUse += worstThreat * attr.aggression() * effectiveCourage * 0.6;
        if (tired) wMove -= 0.10;
        if (!npcs.isEmpty() || player != null) wSpeak *= (0.4 + attr.sociability());
        else wSpeak = 0;
        if (!items.isEmpty()) {
            if (hurt || hungry || weak) wUse += 0.25;              // potions when needy
            if (!enemies.isEmpty()) wUse += attr.aggression() * 0.25;
        } else wUse = 0;
        wInteract *= (0.3 + attr.curiosity() * 1.4);
        double packFull = inv.capacity() > 0 ? inv.carriedWeight() / inv.capacity() : 0;
        wDrop = attr.impulsiveness() * (0.05 + packFull);
        wMove = Math.max(0.05, wMove);

        double sum = wMove + wUse + wInteract + wSpeak + wDrop;
        wMove /= sum; wUse /= sum; wInteract /= sum; wSpeak /= sum; wDrop /= sum;

        // ---- P(params | type) -> concrete candidates ----
        List<Candidate> out = new ArrayList<>();

        // MOVE toward/away from the threat: the split IS courage vs fear.
        // A brave+aggressive entity closes in (chase/approach); a fearful one
        // opens distance (flee/retreat). Cowardice and heroism emerge here.
        if (!enemies.isEmpty()) {
            Perceived ne = nearest(enemies, self);
            double approach = 0.2 + effectiveCourage * (0.3 + attr.aggression()) - fear * 0.5;
            double flee = 0.2 + fear * 1.4 + (1 - effectiveCourage) * 0.6;
            approach = Math.max(0.05, approach);
            out.add(new Candidate(Action.move(ne.entity), wMove * approach / (approach + flee)));
            out.add(new Candidate(Action.move(fleePoint(self, ne)), wMove * flee / (approach + flee)));
            // PROTECTIVE INSTINCT (emergent): if a believed-weak entity (villager,
            // player) is near the THREAT and I'm brave+empathetic, move to THEM --
            // that's how rescue/heroism appears without a RESCUE action.
            Perceived victim = nearestWeakAlly(npcs, player, ne);
            if (victim != null) {
                double protective = attr.empathy() * effectiveCourage * (1 - fear);
                if (protective > 0.05)
                    out.add(new Candidate(Action.move(victim.entity),
                        wMove * protective / (approach + flee + protective)));
            }
        }
        // SELF-PRESERVATION SHAPE 2: badly hurt -> strongly prefer distance
        // from ALL threats, not just the nearest (retreat)
        if (self.health < 30 && !enemies.isEmpty()) {
            double retreat = (1 - self.health / 30.0) * (0.5 + attr.impulsiveness());
            Perceived ne = nearest(enemies, self);
            out.add(new Candidate(Action.move(fleePoint(self, ne)), wMove * retreat));
        }
        // MOVE: wait, wander, toward player, toward an NPC
        double moveRest = wMove / 4;
        out.add(new Candidate(Action.move(selfPoint(self)), moveRest));      // wait
        out.add(new Candidate(Action.move(wanderPoint(self)), moveRest));   // wander
        if (player != null)
            out.add(new Candidate(Action.move(player.entity),
                moveRest * (0.5 + attr.sociability() + attr.curiosity())));
        for (Perceived n : npcs)
            out.add(new Candidate(Action.move(n.entity),
                moveRest * (0.3 + attr.curiosity()) / Math.max(1, npcs.size())));

        // USE: (item, target) pairs
        if (!items.isEmpty()) {
            double tot = 0;
            List<Candidate> uses = new ArrayList<>();
            for (Item it : items) {
                boolean weapon = it.damage > 0;
                double w = 0.08;
                Object target = null;
                if (weapon && !enemies.isEmpty()) {
                    Perceived ne = nearest(enemies, self);
                    w = 0.15 + attr.aggression() * (inDanger ? 0.6 : 0.3) + attr.courage() * 0.1;
                    target = ne.entity; // may be an Entity OR the Player
                } else if (weapon && predator && player != null && npcs.isEmpty()) {
                    w = 0.2 + attr.aggression() * 0.5;
                    target = player.entity; // hunt the player when alone with them
                } else if (!weapon && (hurt || hungry || weak)) {
                    w = 0.35 + (hurt ? 0.2 : 0);
                    // potions restore health; FOOD restores hunger -- prefer
                    // the one that matches the need (emergent foraging)
                    boolean isFood = it instanceof FoodItem;
                    if (hungry && !isFood) w *= 0.3;  // not what I need now
                    if (hurt && isFood && self.health > 60) w *= 0.3;
                    target = self; // consume on self
                }
                if (target == null) continue;
                uses.add(new Candidate(Action.use(it, target), w));
                tot += w;
            }
            for (Candidate c : uses) out.add(new Candidate(c.action, wUse * c.p / tot));
        }

        // INTERACT: nearby objects/ground items, curiosity-shaped
        for (Perceived p : interactables)
            out.add(new Candidate(Action.interact(p.entity),
                wInteract * (0.2 + attr.curiosity() * 0.8 + attr.greed() * 0.3)
                    / Math.max(1, interactables.size())));

        // SPEAK: visible NPCs + the player
        List<Perceived> speakables = new ArrayList<>(npcs);
        if (player != null) speakables.add(player);
        for (Perceived p : speakables)
            out.add(new Candidate(Action.speak(p.entity),
                wSpeak * (0.3 + attr.sociability() * 0.7
                    + (p.type == Perceived.Kind.PLAYER ? 0.05 : 0)) / Math.max(1, speakables.size())));

        // DROP_ITEM: drop the heaviest thing (frees capacity)
        Item heaviest = null;
        for (Item it : items) if (heaviest == null || it.weight > heaviest.weight) heaviest = it;
        if (heaviest != null)
            out.add(new Candidate(Action.drop(heaviest), wDrop));

        // ---- normalize into a true distribution ----
        double tot = 0;
        for (Candidate c : out) tot += c.p;
        if (tot <= 0) return List.of(new Candidate(Action.move(selfPoint(self)), 1.0));
        List<Candidate> dist = new ArrayList<>();
        for (Candidate c : out) dist.add(new Candidate(c.action, c.p / tot));
        return dist;
    }

    // ---- small helpers ----
    // the ally most in danger: within 100px of the threat and believed frail
    // (high empathy in the ally reads as "worth protecting" via low aggression)
    static Perceived nearestWeakAlly(List<Perceived> npcs, Perceived player, Perceived threat) {
        Perceived best = null; double bd = 110;
        java.util.List<Perceived> allies = new java.util.ArrayList<>(npcs);
        if (player != null) allies.add(player);
        for (Perceived a : allies) {
            double d = a.dist(threat.x, threat.y);
            if (d < bd && a.guess.aggression() < 0.5) { bd = d; best = a; }
        }
        return best;
    }

    // danger-radius used by the threat model (px)
    static final double SIGHT_DANGER = 200.0;

    static Perceived nearest(List<Perceived> ps, NpcState self) {
        Perceived best = null; double bd = Double.MAX_VALUE;
        for (Perceived p : ps) { double d = p.dist(self.x, self.y); if (d < bd) { bd = d; best = p; } }
        return best;
    }
    // "self" as a MOVE target means wait/stay in place
    static java.awt.Point selfPoint(NpcState self) { return new java.awt.Point((int) self.x, (int) self.y); }
    static java.awt.Point wanderPoint(NpcState self) {
        java.util.Random r = new java.util.Random();
        return new java.awt.Point((int) self.x + r.nextInt(-96, 96), (int) self.y + r.nextInt(-96, 96));
    }
    // flee = the point directly away from the threat, ~120px out
    static java.awt.Point fleePoint(NpcState self, Perceived threat) {
        double dx = self.x - threat.x, dy = self.y - threat.y;
        double len = Math.max(1, Math.hypot(dx, dy));
        return new java.awt.Point((int) (self.x + dx / len * 120), (int) (self.y + dy / len * 120));
    }
}
