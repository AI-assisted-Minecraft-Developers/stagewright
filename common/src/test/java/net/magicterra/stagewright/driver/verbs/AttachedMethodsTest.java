package net.magicterra.stagewright.driver.verbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.magicterra.stagewright.contract.remote.Wire;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.Test;

/**
 * The {@code mc.test.attached.*} contracts as the registry sees them: they publish under the one owner
 * that may, they decode what the runner sends, and with no server armed they refuse by saying so
 * rather than by failing in a handler.
 */
class AttachedMethodsTest {

    private static ApiRegistry registry() {
        ApiRegistry api = new ApiRegistry();
        TestMethods.register(api);
        return api;
    }

    private static ApiException refusal(ApiRegistry api, String method, String params) {
        JsonElement tree = JsonParser.parseString(params);
        Throwable t = assertThrows(Throwable.class, () -> ApiRegistry.await(api.dispatch(method, tree,
                CallContext.standalone()), 0));
        ApiException found = ApiException.find(t instanceof CompletionException c && c.getCause() != null ? c.getCause() : t);
        assertTrue(found != null, "expected an ApiException, got " + t);
        return found;
    }

    @Test
    void allSevenArePublishedAndNoneIsListedForAgents() {
        ApiRegistry api = registry();

        for (String name : new String[] {Wire.BEGIN, Wire.END, Wire.OPEN, Wire.STATUS, Wire.CALL, Wire.ROUTE, Wire.CLOSE}) {
            var method = api.snapshot().methods().get(name);
            assertTrue(method != null, name + " is not registered");
            assertEquals(false, method.spec().listedInMcp(), name + " must stay out of the tool list");
        }
    }

    @Test
    void withNoServerArmedARequestThatDecodesIsRefusedAsUnavailable() {
        ApiRegistry api = registry();

        ApiException e = refusal(api, Wire.OPEN,
                "{\"scene\":\"pack.a\",\"terrain\":\"run_world\",\"clock\":\"midnight\",\"chunkRadius\":1}");

        assertEquals("capability_unavailable", e.error().code().wireName(), e.getMessage());
        assertTrue(e.getMessage().contains("not armed"), e.getMessage());
    }

    @Test
    void aRequestThatDoesNotDecodeIsRefusedBeforeAnyHandlerRuns() {
        ApiRegistry api = registry();

        ApiException e = refusal(api, Wire.CALL, "{\"session\":1,\"target\":0}");

        assertEquals("invalid_params", e.error().code().wireName(), e.getMessage());
    }

    @Test
    void aCallCarriesItsArgumentsAsOpenJson() {
        ApiRegistry api = registry();

        ApiException e = refusal(api, Wire.CALL, "{\"session\":1,\"target\":0,\"method\":\"setBlock\","
                + "\"args\":[1,2.5,\"x\",{\"$id\":\"block\",\"id\":\"minecraft:stone\"},[1,2],{\"$null\":true}]}");

        assertEquals("capability_unavailable", e.error().code().wireName(), e.getMessage());
    }
}
