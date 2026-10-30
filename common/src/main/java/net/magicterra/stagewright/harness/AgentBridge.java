package net.magicterra.stagewright.harness;

import java.lang.reflect.Method;

/**
 * What the game tells the supervision agent, when the JVM was started with one.
 *
 * <p>The agent's jar is on the system class path and this mod is loaded by the loader's own class loader, so nothing
 * here may link against an agent class: the agent's {@code Control} class is looked up by name in the system loader and
 * called by reflection, with JDK types in every signature. When the JVM has no agent — a game started by hand — the
 * lookup fails once and every call does nothing. See {@code docs/reference/supervision-protocol.md}.
 */
public final class AgentBridge {

    private static final String CONTROL = "net.magicterra.stagewright.agent.Control";

    private static final AgentBridge SYSTEM = bind(ClassLoader.getSystemClassLoader(), CONTROL);

    private final Method phase;
    private final Method ticks;
    private final Method done;
    private final Method test;

    private AgentBridge(Method phase, Method ticks, Method done, Method test) {
        this.phase = phase;
        this.ticks = ticks;
        this.done = done;
        this.test = test;
    }

    /** The bridge to this JVM's agent, or an inert one when there is none. */
    public static AgentBridge system() {
        return SYSTEM;
    }

    /** Package-private so a test can bind a stand-in for {@code Control}. */
    static AgentBridge bind(ClassLoader loader, String className) {
        try {
            Class<?> control = Class.forName(className, true, loader);
            return new AgentBridge(
                    control.getMethod("phase", String.class),
                    control.getMethod("ticks", long.class),
                    control.getMethod("done"),
                    optional(control, "test", String.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return new AgentBridge(null, null, null, null);
        }
    }

    /** A method an older agent may not have: its absence loses that one report, not the agent. */
    private static Method optional(Class<?> control, String name, Class<?>... parameters) {
        try {
            return control.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Whether this JVM has an agent, and so someone who will end it and say what held it up. */
    public boolean attached() {
        return done != null;
    }

    /** Where the game says it is, such as {@code armed}. */
    public void phase(String name) {
        call(phase, name);
    }

    /** Ticks delivered so far: the server's, or the client's where the JVM is one. */
    public void ticks(long delivered) {
        call(ticks, delivered);
    }

    /** The run is over and this JVM is about to be stopped. */
    public void done() {
        call(done);
    }

    /** One event of the in-game test run, a JSON object, relayed to whoever started this game. */
    public void test(String json) {
        call(test, json);
    }

    private static void call(Method method, Object... arguments) {
        if (method == null) return;
        try {
            method.invoke(null, arguments);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The agent is a convenience for whoever supervises this game; nothing it does may fail the run.
        }
    }
}
