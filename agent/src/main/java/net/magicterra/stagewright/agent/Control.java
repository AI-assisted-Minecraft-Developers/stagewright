package net.magicterra.stagewright.agent;

/**
 * What the game tells the agent.
 *
 * <p>The agent's jar is appended to the system class path, so this class is reachable from anywhere
 * with {@code ClassLoader.getSystemClassLoader().loadClass(NAME)} — which is how the mod, loaded by a
 * loader's own class loader, calls it without linking against it. Every signature uses JDK types
 * only, for the same reason.
 *
 * <p>Every method does nothing when the JVM was not started with the agent, and none throws, so the
 * mod can call them unconditionally. That is the whole contract: a game started by hand has no agent
 * and behaves as it always did.
 */
public final class Control {

    public static final String NAME = "net.magicterra.stagewright.agent.Control";

    private Control() {}

    /** Where the game says it is: for instance {@code harness-ready} or {@code scenes-running}. */
    public static void phase(String name) {
        Agent agent = Agent.running();
        if (agent != null) agent.phase(name);
    }

    /** Server ticks delivered so far, carried on the next heartbeat. */
    public static void ticks(long ticks) {
        Agent agent = Agent.running();
        if (agent != null) agent.ticks(ticks);
    }

    /**
     * The run is over and its results are complete. The agent gives the JVM a grace period to end
     * on its own, and after it reports what held the JVM up and ends it.
     */
    public static void done() {
        Agent agent = Agent.running();
        if (agent != null) agent.done();
    }
}
