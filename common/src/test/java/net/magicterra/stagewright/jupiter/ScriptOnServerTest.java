package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.magicterra.worlddriver.api.thread.ServerThreadGuard;
import net.magicterra.worlddriver.script.HostedScripts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;

/** {@code t.onServer}: a script function as one server task, against WorldDriver's own {@link Scripting}. */
class ScriptOnServerTest {

    private final Scripting scripting = new HostedScripts(new ApiRegistry());
    private final ExecutorService serverThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "server"));
    private final List<Integer> waited = new ArrayList<>();
    private final Map<String, Scripting.Function> steps = new HashMap<>();
    private ScriptContext t;

    @BeforeEach
    void setUp() {
        ServerThreadGuard.install(() -> Thread.currentThread().getName().equals("server"));
        t = new ScriptContext(game(), steps, JavaSteps.none(), descriptor());
    }

    @AfterEach
    void tearDown() {
        ServerThreadGuard.uninstall();
        serverThread.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private Game game() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "onServer" -> {
                try {
                    yield serverThread
                            .submit(() -> ((Function<Object, ?>) args[0]).apply(null))
                            .get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }
            case "ticks" -> {
                waited.add((Integer) args[0]);
                yield null;
            }
            case "registry" -> new ApiRegistry();
            case "freezeTicks" -> {
                waited.add(-1);
                yield null;
            }
            default -> {
                if (method.isDefault()) {
                    yield InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        };
        return (Game) Proxy.newProxyInstance(Game.class.getClassLoader(), new Class<?>[] {Game.class}, handler);
    }

    private static TestDescriptor descriptor() {
        return new AbstractTestDescriptor(UniqueId.forEngine("t").append("test", "onServer"), "onServer") {
            @Override
            public Type getType() {
                return Type.TEST;
            }
        };
    }

    private Object run(String body) {
        Scripting.Function test =
                (Scripting.Function) scripting.evaluate("t.js", "(function (t) { " + body + " })", Map.of());
        return test.call(t.asScript());
    }

    @Test
    void theFunctionAndTheStepsItCallsRunOnTheServerThreadAndItsAnswerComesBack() {
        List<String> threads = new ArrayList<>();
        steps.put("where", (Scripting.Function) a -> {
            threads.add(Thread.currentThread().getName());
            return null;
        });
        Object answer = run("return t.onServer(function () { t.step('where'); t.step('where'); return 'done'; });");
        assertEquals("done", answer);
        assertEquals(List.of("server", "server"), threads);
    }

    @Test
    void aWaitInsideIsRefusedAndOutsideItWaits() {
        RuntimeException refused =
                assertThrows(RuntimeException.class, () -> run("t.onServer(function () { t.wait(3); });"));
        assertTrue(
                String.valueOf(rootMessage(refused)).contains("t.wait cannot be called inside t.onServer"),
                String.valueOf(rootMessage(refused)));
        assertEquals(List.of(), waited);
        run("t.wait(3);");
        assertEquals(List.of(3), waited);
    }

    @Test
    void anythingElseThatWouldWaitIsRefusedToo() {
        for (String call : List.of(
                "t.waitUntil(function () { return true; })",
                "t.plot()",
                "t.bot()",
                "t.settle(function () { return true; })")) {
            RuntimeException refused =
                    assertThrows(RuntimeException.class, () -> run("t.onServer(function () { " + call + "; });"), call);
            assertTrue(
                    String.valueOf(rootMessage(refused)).contains("inside t.onServer"),
                    call + ": " + rootMessage(refused));
        }
    }

    @Test
    void freezeTicksIsTheGamesAndTheWaitsAfterItAreTooWhichStepThem() {
        run("t.freezeTicks(); t.wait(3);");
        // -1 marks the freeze; GameImpl's ticks steps a frozen world, which needs a server to show.
        assertEquals(List.of(-1, 3), waited);
    }

    @Test
    void aFunctionIsRequired() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run("t.onServer(3);"));
        assertSame(IllegalArgumentException.class, e.getClass());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
