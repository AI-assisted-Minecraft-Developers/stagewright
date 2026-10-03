package net.magicterra.stagewright.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class EndpointDescriptorTest {
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

    @Test void aNewWorldPublishesItsOwnEndpoints() throws IOException {
        Path target = directory.resolve("endpoint.json");
        System.setProperty(EndpointDescriptor.PROPERTY, target.toString());
        EndpointDescriptor.writeIfRequested("fabric", "first", "127.0.0.1", 41234, "localhost", 41235);
        // Duplicate lifecycle notifications must leave the current descriptor alone.
        EndpointDescriptor.writeIfRequested("fabric", "duplicate", "localhost", 1, null, null);
        JsonObject first = read(target);
        assertEquals("first", first.get("worldName").getAsString());
        assertEquals(41234, first.get("rpcPort").getAsInt());
        assertEquals("localhost", first.get("mcpHost").getAsString());
        assertEquals(41235, first.get("mcpPort").getAsInt());

        EndpointDescriptor.reset();
        EndpointDescriptor.writeIfRequested("fabric", "second", "localhost", 42345, null, null);
        JsonObject second = read(target);
        assertEquals("second", second.get("worldName").getAsString());
        assertEquals(42345, second.get("rpcPort").getAsInt());
        assertFalse(second.has("mcpPort"));
        assertFalse(second.has("mcpHost"));
    }

    @Test void failedWriteDoesNotLatchPublication() throws IOException {
        Path blocker = directory.resolve("parent");
        Files.writeString(blocker, "not a directory");
        Path target = blocker.resolve("endpoint.json");
        System.setProperty(EndpointDescriptor.PROPERTY, target.toString());
        EndpointDescriptor.writeIfRequested("fabric", "first", "localhost", 41234, null, null);
        Files.delete(blocker);
        EndpointDescriptor.writeIfRequested("fabric", "retry", "localhost", 42345, null, null);
        assertEquals("retry", read(target).get("worldName").getAsString());
        assertEquals(42345, read(target).get("rpcPort").getAsInt());
    }

    @Test void pendingUntilThisWorldPublishes() throws IOException {
        System.clearProperty(EndpointDescriptor.PROPERTY);
        assertFalse(EndpointDescriptor.pending(), "nothing asked for a descriptor");

        Path target = directory.resolve("endpoint.json");
        System.setProperty(EndpointDescriptor.PROPERTY, target.toString());
        assertTrue(EndpointDescriptor.pending());
        // A refusal leaves the next tick free to retry.
        EndpointDescriptor.cannotPublish("WorldDriver RPC is not listening");
        EndpointDescriptor.cannotPublish("WorldDriver RPC is not listening");
        assertTrue(EndpointDescriptor.pending());

        EndpointDescriptor.writeIfRequested("fabric", "first", "localhost", 41234, null, null);
        assertFalse(EndpointDescriptor.pending());
        EndpointDescriptor.reset();
        assertTrue(EndpointDescriptor.pending(), "a new world publishes again");
    }

    private static JsonObject read(Path target) throws IOException {
        return JsonParser.parseString(Files.readString(target)).getAsJsonObject();
    }
}
