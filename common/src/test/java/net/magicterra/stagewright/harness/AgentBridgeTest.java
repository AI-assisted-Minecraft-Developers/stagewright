package net.magicterra.stagewright.harness;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentBridgeTest {

    /** Stands in for the agent's {@code Control}: same names, same JDK-only signatures. */
    public static final class FakeControl {
        static final List<String> CALLS = new ArrayList<>();

        public static void phase(String name) {
            CALLS.add("phase:" + name);
        }

        public static void ticks(long ticks) {
            CALLS.add("ticks:" + ticks);
        }

        public static void done() {
            CALLS.add("done");
        }
    }

    @BeforeEach void clear() {
        FakeControl.CALLS.clear();
    }

    @Test void callsReachTheAgentByName() {
        AgentBridge bridge = AgentBridge.bind(getClass().getClassLoader(), FakeControl.class.getName());
        assertTrue(bridge.attached());
        bridge.phase("harness-ready");
        bridge.ticks(42);
        bridge.done();
        assertEquals(List.of("phase:harness-ready", "ticks:42", "done"), FakeControl.CALLS);
    }

    @Test void aJvmWithoutAnAgentIsInertAndNeverThrows() {
        AgentBridge bridge = AgentBridge.bind(getClass().getClassLoader(), "no.such.Control");
        assertFalse(bridge.attached());
        assertDoesNotThrow(() -> {
            bridge.phase("harness-ready");
            bridge.ticks(1);
            bridge.done();
        });
        assertEquals(List.of(), FakeControl.CALLS);
    }

    @Test void aClassWithTheWrongShapeIsTreatedAsNoAgent() {
        AgentBridge bridge = AgentBridge.bind(getClass().getClassLoader(), String.class.getName());
        assertFalse(bridge.attached());
    }

    @Test void thisTestJvmHasNoAgent() {
        assertFalse(AgentBridge.system().attached());
    }
}
