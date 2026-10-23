package net.magicterra.stagewright.harness;

import java.lang.reflect.Method;

/**
 * What the harness tells the supervision agent, when the JVM was started with one.
 *
 * <p>The agent's jar is on the system class path and this mod is loaded by the loader's own class
 * loader, so nothing here may link against an agent class: the agent's {@code Control} class is
 * looked up by name in the system loader and called by reflection, with JDK types in every
 * signature. When the JVM has no agent — a game started by hand, a JUnit attach — the lookup fails
 * once, every call does nothing, and the harness behaves as it always did. See
 * {@code docs/reference/supervision-protocol.md}.
 */
public final class AgentBridge {

    private static final String CONTROL = "net.magicterra.stagewright.agent.Control";

    private static final AgentBridge SYSTEM = bind(ClassLoader.getSystemClassLoader(), CONTROL);

    private final Method phase;
    private final Method ticks;
    private final Method done;

    private AgentBridge(Method phase, Method ticks, Method done) {
        this.phase = phase;
        this.ticks = ticks;
        this.done = done;
    }

    /** The bridge to this JVM's agent, or an inert one when there is none. */
    public static AgentBridge system() {
        return SYSTEM;
    }

    /** Package-private so a test can bind a stand-in for {@code Control}. */
    static AgentBridge bind(ClassLoader loader, String className) {
        try {
            Class<?> control = Class.forName(className, true, loader);
            return new AgentBridge(control.getMethod("phase", String.class),
                    control.getMethod("ticks", long.class), control.getMethod("done"));
        } catch (ReflectiveOperationException | LinkageError e) {
            return new AgentBridge(null, null, null);
        }
    }

    /** Whether this JVM has an agent, and so someone who will end it and say what held it up. */
    boolean attached() {
        return done != null;
    }

    /** Where the game says it is, such as {@code harness-ready}. */
    public void phase(String name) {
        call(phase, name);
    }

    /** Server ticks delivered so far. */
    void ticks(long delivered) {
        call(ticks, delivered);
    }

    /** The results file is complete and this JVM is about to be stopped. */
    void done() {
        call(done);
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
