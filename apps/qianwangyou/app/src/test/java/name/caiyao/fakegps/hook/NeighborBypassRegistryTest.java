package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NeighborBypassRegistryTest {

    @Test
    public void membershipSurvivesMutableHashCode() {
        NeighborBypassRegistry registry = new NeighborBypassRegistry();
        MutableValue value = new MutableValue(1);

        registry.add(value);
        value.hash = 99;

        assertTrue(registry.contains(value));
    }

    @Test
    public void membershipUsesIdentityRatherThanValueEquality() {
        NeighborBypassRegistry registry = new NeighborBypassRegistry();
        MutableValue stored = new MutableValue(7);
        MutableValue equalButDifferent = new MutableValue(7);

        registry.add(stored);

        assertTrue(stored.equals(equalButDifferent));
        assertFalse(registry.contains(equalButDifferent));
    }

    private static final class MutableValue {
        int hash;

        MutableValue(int hash) {
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MutableValue
                    && ((MutableValue) other).hash == hash;
        }
    }
}
