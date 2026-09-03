import java.util.ArrayList;
import java.util.List;

// ============================================================================
// Inventory: weight-gated item storage.
//
// Capacity model (user spec):
//   carryingStrength: baseline 60. Every agent has one.
//   carried weight = sum of item weights.
//   canPickUp(item)  <=>  carried + item.weight <= carryingStrength
//   (new-strength rule: strength - weight >= 0 -- equivalent, kept explicit
//    below so the spec's wording maps 1:1 to the code)
// No item has weight 0, so capacity can never be infinite.
// ============================================================================
public class Inventory {
    static final double BASE_STRENGTH = 60.0;

    private final double carryingStrength; // total capacity of this agent
    private final List<Item> items = new ArrayList<>();

    Inventory() { this(BASE_STRENGTH); }
    Inventory(double carryingStrength) { this.carryingStrength = carryingStrength; }

    // ---- the spec's core rule ----
    public double remainingStrength() {
        double carried = 0;
        for (Item it : items) carried += it.weight;
        return carryingStrength - carried; // "new strength = current - weight"
    }

    public boolean canPickUp(Item item) {
        return remainingStrength() - item.weight >= 0;
    }

    // returns false (and does not take the item) if too heavy
    public boolean add(Item item) {
        if (!canPickUp(item)) return false;
        items.add(item);
        return true;
    }

    // remove + return (for USE/DROP); null if not present
    public Item take(String name) {
        for (int i = 0; i < items.size(); i++)
            if (items.get(i).name.equals(name)) return items.remove(i);
        return null;
    }

    public boolean remove(Item item) { return items.remove(item); }

    public List<Item> list() { return items; }
    public double capacity() { return carryingStrength; }
    public double carriedWeight() { return carryingStrength - remainingStrength(); }

    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (Item it : items) sb.append(it.name).append(" ");
        return sb.append("] ").append(carriedWeight()).append("/").append(carryingStrength).toString();
    }
}
