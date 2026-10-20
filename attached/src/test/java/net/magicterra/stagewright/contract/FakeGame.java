package net.magicterra.stagewright.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.contract.remote.Wire;

/**
 * The game's half of the arena protocol, as much of it as a test of the runner needs: it hands out
 * sessions, says they are ready and lets them go, and passes every other driver call to the driver
 * the test brought.
 *
 * <p>Not the game. What a call to a session's context does is the game-side module's business and is
 * tested there against the real dispatch; here a test that wants a particular answer supplies it.
 */
final class FakeGame implements DriverBinding {

    /** One answer to a {@link Wire#CALL}, or a failure to give. */
    @FunctionalInterface
    interface Calls {
        Object answer(int target, String method, List<?> args);
    }

    private final DriverBinding delegate;
    private int sessions;

    /** Every protocol call that reached the game, in order, as {@code method} or {@code method:detail}. */
    final List<String> wire = new ArrayList<>();
    /** The options of the latest {@link Wire#OPEN}. */
    Map<String, Object> opened = Map.of();
    /** What {@link Wire#STATUS} says; ready unless a test changes it. */
    String state = Wire.READY;
    String message = "";
    /** What {@link Wire#CLOSE} answers. */
    Map<String, Object> closed = Map.of("leaks", List.of(), "records", Map.of(), "softViolations", List.of());
    /** What {@link Wire#BEGIN} answers when it does not throw. */
    String pin = "world pinned";
    RuntimeException beginRefusal;
    Calls calls = (target, method, args) -> {
        throw new IllegalStateException("this test supplied no answer for " + method);
    };

    FakeGame(DriverBinding delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object route(String method, Map<String, Object> params) {
        switch (method) {
            case Wire.BEGIN -> {
                wire.add("begin");
                if (beginRefusal != null) throw beginRefusal;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("pinned", true);
                out.put("description", pin);
                return out;
            }
            case Wire.END -> {
                wire.add("end");
                return Map.of("released", true);
            }
            case Wire.OPEN -> {
                wire.add("open:" + params.get("scene"));
                opened = params;
                return Map.of("session", ++sessions);
            }
            case Wire.STATUS -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("state", state);
                out.put("message", message);
                return out;
            }
            case Wire.CLOSE -> {
                wire.add("close");
                return closed;
            }
            case Wire.CALL -> {
                Object args = params.get("args");
                Object answer;
                try {
                    answer = calls.answer(((Number) params.get("target")).intValue(),
                            String.valueOf(params.get("method")), (List<?>) args);
                } catch (RuntimeException e) {
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("ok", false);
                    failed.put("kind", e instanceof SceneSkipped ? Wire.KIND_SKIPPED
                            : e instanceof SceneFailure ? Wire.KIND_FAILED
                            : e instanceof IllegalArgumentException ? Wire.KIND_ILLEGAL_ARGUMENT
                            : Wire.KIND_ERROR);
                    failed.put("message", e.getMessage());
                    return failed;
                }
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                ok.put("value", answer);
                return ok;
            }
            default -> {
                return delegate.route(method, params);
            }
        }
    }
}
