import java.util.List;
import java.util.Random;

// ============================================================================
// NpcBrain: owns the decide->sample->execute cycle for ONE agent.
// The rest of the game only sees: state -> brain.decide() -> Action -> act().
// The DecisionFunction inside is swappable (hand-designed today, learned later).
// ============================================================================
class NpcBrain {
    final DecisionFunction f;    // replaceable decision core
    final Random rng;            // per-agent stochastic sampling
    NpcBrain(DecisionFunction f, long seed) {
        this.f = f;
        this.rng = new Random(seed);
    }

    // sample one concrete action from the distribution the function produced
    Action sample(List<Candidate> dist) {
        double r = rng.nextDouble();
        double acc = 0;
        for (Candidate c : dist) {
            acc += c.p;
            if (r < acc) return c.action;
        }
        return dist.get(dist.size() - 1).action; // float edge
    }

    Action decide(NpcState self, Attributes attr, List<Perceived> perceived, Inventory inv) {
        List<Candidate> dist = f.decide(self, attr, perceived, inv);
        return sample(dist);
    }
}
