package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientHandleTest {

    /** Stands for the joined client's connection: answers every name, and keeps the names it was asked for. */
    private final List<String> sent = new ArrayList<>();

    private final BiFunction<String, JsonObject, JsonElement> client = (method, params) -> {
        sent.add(method);
        return new JsonPrimitive("from the client");
    };

    @AfterEach
    void forget() {
        ClientHandle.end(null);
    }

    @Test
    void aClientThatHostsTheGameRefusesToLeaveIt() {
        ClientHandle hosting = new ClientHandle(null, new ApiRegistry(), null);
        IllegalStateException refused = assertThrows(IllegalStateException.class, hosting::disconnect);
        assertTrue(refused.getMessage().contains("joins a dedicated server"), refused.getMessage());
        assertThrows(IllegalStateException.class, hosting::reconnect);
    }

    @Test
    void aClientInTheGameRefusesToJoinItAgain() {
        ClientHandle joined = new ClientHandle(null, new ApiRegistry(), client);
        IllegalStateException refused = assertThrows(IllegalStateException.class, joined::reconnect);
        assertTrue(refused.getMessage().contains("disconnect() first"), refused.getMessage());
    }

    @Test
    void aTestThatLeftTheClientInTheGameRejoinsNothing() throws Throwable {
        // A game would be asked for, and there is none: nothing is called when nothing was disconnected.
        new ClientHandle(null, new ApiRegistry(), client).rejoinAtTheEnd().close();
        ClientHandle.rejoinIfLeftBy(null, null);
    }

    @Test
    void aMethodTheServerLacksGoesToAJoinedClientByName() {
        ClientHandle joined = new ClientHandle(null, new ApiRegistry(), client);
        assertEquals(new JsonPrimitive("from the client"), joined.call("mymod.read", new JsonObject()));
        assertEquals(List.of("mymod.read"), sent);
    }

    @Test
    void anUnknownDriverNameOrNoNameIsAnsweredByTheRegistry() {
        ClientHandle joined = new ClientHandle(null, new ApiRegistry(), client);
        assertThrows(ApiException.class, () -> joined.call("mc.no.such", new JsonObject()));
        assertThrows(ApiException.class, () -> joined.call(null, new JsonObject()));
        assertEquals(List.of(), sent);
    }

    @Test
    void aClientThatHostsTheGameIsItsOwnRegistry() {
        ClientHandle hosting = new ClientHandle(null, new ApiRegistry(), null);
        assertThrows(ApiException.class, () -> hosting.call("mymod.read", new JsonObject()));
    }
}
