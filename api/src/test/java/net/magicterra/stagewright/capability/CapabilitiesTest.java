package net.magicterra.stagewright.capability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.magicterra.stagewright.test.Game;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * What a test is told when the capability it asked for is not here.
 *
 * <p>An adapter that fails to load cannot say which capability it would have offered — the name is an instance method
 * on the object that never got built. So the reason it failed has to reach the abort through something other than that
 * name, or a broken adapter reads exactly like an absent mod.
 */
class CapabilitiesTest {

    /** Stands in for an adapter compiled against a mod this runtime does not have. */
    public static final class UnloadableAdapter implements CapabilityProvider {
        public UnloadableAdapter() {
            throw new NoClassDefFoundError("com/example/rituals/Altar");
        }

        @Override
        public String name() {
            return "example:rituals";
        }

        @Override
        public boolean availableIn(Game game) {
            return true;
        }

        @Override
        public Object facet(Game game) {
            return this;
        }
    }

    // Nothing here reaches the game: the shipped providers' availability is a class lookup.
    private final Capabilities capabilities = Capabilities.in(null);

    @BeforeEach
    void freshDiscovery() {
        Capabilities.reset();
        Mods.install(Map.of());
    }

    @AfterEach
    void forgetDiscovery() {
        Capabilities.reset();
    }

    @Test
    void anAdapterThatCouldNotLoadIsNamedInTheAbort() {
        TestAbortedException aborted =
                assertThrows(TestAbortedException.class, () -> capabilities.require("example:rituals"));

        assertTrue(aborted.getMessage().contains(UnloadableAdapter.class.getName()), aborted.getMessage());
        assertTrue(aborted.getMessage().contains("com/example/rituals/Altar"), aborted.getMessage());
    }

    @Test
    void theShippedProvidersAreRegisteredWhetherOrNotTheirModsAreHere() {
        assertTrue(
                capabilities.registered().containsAll(java.util.List.of("curios", "ftbquests", "mekanism")),
                capabilities.registered().toString());
        assertFalse(capabilities.has("curios"));
        assertFalse(capabilities.available().contains("ftbquests"));
    }
}
