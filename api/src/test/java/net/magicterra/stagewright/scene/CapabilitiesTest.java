package net.magicterra.stagewright.scene;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import net.magicterra.stagewright.contract.SceneSkipped;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a scene is told when the capability it asked for is not here.
 *
 * <p>An adapter that fails to load cannot say which capability it would have offered — the name is
 * an instance method on the object that never got built. So the reason it failed has to reach a
 * skip through something other than that name, or a broken adapter reads exactly like an absent mod.
 */
class CapabilitiesTest {

    /** Stands in for an adapter compiled against a mod this runtime does not have. */
    public static final class UnloadableAdapter implements CapabilityProvider {
        public UnloadableAdapter() {
            throw new NoClassDefFoundError("com/example/rituals/Altar");
        }

        @Override public String name() { return "example:rituals"; }
        @Override public boolean availableIn(SceneContext ctx) { return true; }
        @Override public Object facet(SceneContext ctx) { return this; }
    }

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
    void anAdapterThatCouldNotLoadIsNamedInTheSkip() {
        SceneContext ctx = new SceneContext(null, null);

        SceneSkipped skipped = assertThrows(SceneSkipped.class, () -> ctx.capability("example:rituals"));

        assertTrue(skipped.getMessage().contains(UnloadableAdapter.class.getName()), skipped.getMessage());
        assertTrue(skipped.getMessage().contains("com/example/rituals/Altar"), skipped.getMessage());
    }
}
