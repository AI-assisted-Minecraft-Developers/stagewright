package net.magicterra.stagewright.driver.endpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import net.magicterra.stagewright.harness.EndpointDescriptor;
import net.magicterra.worlddriver.WorldDriverCommon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DriverEndpointTest {
    @TempDir Path directory;
    private String previousTarget;

    @BeforeEach void prepare() {
        previousTarget = System.getProperty(EndpointDescriptor.PROPERTY);
        EndpointDescriptor.reset();
    }

    @AfterEach void restore() {
        EndpointDescriptor.reset();
        if (previousTarget == null) System.clearProperty(EndpointDescriptor.PROPERTY);
        else System.setProperty(EndpointDescriptor.PROPERTY, previousTarget);
    }

    @Test void nothingIsOwedWhenNoDescriptorWasAskedFor() {
        System.clearProperty(EndpointDescriptor.PROPERTY);
        assertTrue(DriverEndpoint.publish("neoforge", "world"));
    }

    @Test void aDriverThatIsNotListeningLeavesTheDescriptorOwed() {
        assertTrue(WorldDriverCommon.rpcPort() < 1, "no RPC server is bound in a unit test");
        Path target = directory.resolve("endpoint.json");
        System.setProperty(EndpointDescriptor.PROPERTY, target.toString());
        assertFalse(DriverEndpoint.publish("neoforge", "world"));
        assertFalse(Files.exists(target));
    }
}
