package net.magicterra.stagewright.engine.supervise;

import java.lang.reflect.Method;

/**
 * A stand-in for a game, started with the real agent: it reports through {@code Control} the way the
 * mod will, then either ends or leaves a non-daemon thread behind.
 */
public final class FakeGame {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "clean";
        if (mode.equals("bare")) {
            // Started without the agent: it has nothing to report and is only here to be alive.
            Thread.sleep(60_000);
            return;
        }
        call("phase", String.class, "harness-ready");
        call("ticks", long.class, 7L);
        if (mode.equals("silent")) {
            // Reports once and then moves nothing: longer than any stall limit a test or a run applies.
            Thread.sleep(300_000);
            return;
        }
        if (mode.equals("held")) {
            // A hold: the results are complete and the JVM is meant to outlive them.
            Thread.sleep(1_500);
            call("resultsComplete", null, null);
            Thread.sleep(60_000);
            return;
        }
        if (mode.equals("leak")) {
            // A mod's threads start seconds into a run, long after the agent has begun recording who
            // starts threads; a thread started in the JVM's first instant would be missed.
            Thread.sleep(1_500);
            Thread pool = new Thread(new Holder(), "mod-pool-1");
            pool.setDaemon(false);
            pool.start();
        }
        // Long enough for the agent's first heartbeat to carry the phase and the ticks.
        Thread.sleep(1_500);
        call("done", null, null);
    }

    /** What a mod's scheduled executor looks like from a thread dump. */
    public static final class Holder implements Runnable {
        @Override
        public void run() {
            try {
                Thread.sleep(600_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void call(String method, Class<?> parameter, Object argument) throws Exception {
        Class<?> control = Class.forName("net.magicterra.stagewright.agent.Control", true,
                ClassLoader.getSystemClassLoader());
        Method m = parameter == null ? control.getMethod(method) : control.getMethod(method, parameter);
        if (parameter == null) m.invoke(null);
        else m.invoke(null, argument);
    }
}
