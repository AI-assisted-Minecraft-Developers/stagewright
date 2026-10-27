package net.magicterra.stagewright.jupiter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import net.magicterra.stagewright.test.Await;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Perf;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;

/** The {@link Game} of the server this JVM runs. */
final class GameImpl implements Game {

    /** Wall time allowed per tick of a budget before a wait gives up on the server itself, plus a fixed margin. */
    private static final long WALL_MS_PER_TICK = 1_000;
    private static final long WALL_MARGIN_MS = 60_000;

    private final MinecraftServer server;

    GameImpl(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public ApiRegistry registry() {
        return WorldDriver.registry();
    }

    @Override
    public <T> T onServer(Function<MinecraftServer, T> work) {
        // Already there, as an await condition is: nothing to wait for, so run it now.
        if (server.isSameThread()) return work.apply(server);
        try {
            return server.submit(() -> work.apply(server)).join();
        } catch (CompletionException e) {
            throw rethrow(e.getCause() == null ? e : e.getCause());
        }
    }

    @Override
    public void ticks(int n) {
        waitFor(() -> false, n);
    }

    @Override
    public Await await() {
        return new AwaitImpl();
    }

    @Override
    public Map<String, Perf.Window> perf(Perf measurement) {
        return PerfSampler.measure(this, measurement);
    }

    @Override
    public void override(GameRules.Key<GameRules.BooleanValue> rule, boolean value) {
        TestScope scope = TestScope.current("game.override");
        scope.require(Locks.WORLD_RULES, "Locks.WORLD_RULES", "game.override(" + rule.getId() + ")");
        runOnServer(s -> {
            boolean old = s.getGameRules().getRule(rule).get();
            s.getGameRules().getRule(rule).set(value, s);
            scope.onEnd(at -> at.getGameRules().getRule(rule).set(old, at));
        });
    }

    @Override
    public void override(GameRules.Key<GameRules.IntegerValue> rule, int value) {
        TestScope scope = TestScope.current("game.override");
        scope.require(Locks.WORLD_RULES, "Locks.WORLD_RULES", "game.override(" + rule.getId() + ")");
        runOnServer(s -> {
            int old = s.getGameRules().getRule(rule).get();
            s.getGameRules().getRule(rule).set(value, s);
            scope.onEnd(at -> at.getGameRules().getRule(rule).set(old, at));
        });
    }

    @Override
    public void overrideTime(long dayTime) {
        TestScope scope = TestScope.current("game.overrideTime");
        scope.require(Locks.WORLD_CLOCK, "Locks.WORLD_CLOCK", "game.overrideTime");
        runOnServer(s -> {
            long old = s.overworld().getDayTime();
            s.overworld().setDayTime(dayTime);
            scope.onEnd(at -> at.overworld().setDayTime(old));
        });
    }

    @Override
    public void overrideWeather(boolean raining, boolean thundering) {
        TestScope scope = TestScope.current("game.overrideWeather");
        scope.require(Locks.WEATHER, "Locks.WEATHER", "game.overrideWeather");
        runOnServer(s -> {
            ServerLevelData data = s.getWorldData().overworldData();
            int clearTime = data.getClearWeatherTime();
            int rainTime = data.getRainTime();
            int thunderTime = data.getThunderTime();
            boolean wasRaining = data.isRaining();
            boolean wasThundering = data.isThundering();
            // Long enough that the weather cycle does not end it mid-test.
            int hold = 1_000_000;
            s.overworld().setWeatherParameters(raining ? 0 : hold, raining ? hold : 0, raining, thundering);
            scope.onEnd(at -> {
                ServerLevelData d = at.getWorldData().overworldData();
                d.setClearWeatherTime(clearTime);
                d.setRainTime(rainTime);
                d.setThunderTime(thunderTime);
                d.setRaining(wasRaining);
                d.setThundering(wasThundering);
            });
        });
    }

    @Override
    public void overrideTickRate(float ticksPerSecond) {
        TestScope scope = TestScope.current("game.overrideTickRate");
        scope.require(Locks.TICK_RATE, "Locks.TICK_RATE", "game.overrideTickRate");
        runOnServer(s -> {
            float old = s.tickRateManager().tickrate();
            s.tickRateManager().setTickRate(ticksPerSecond);
            scope.onEnd(at -> at.tickRateManager().setTickRate(old));
        });
    }

    /** Whether {@code condition} held within {@code budget} ticks. */
    private boolean waitFor(BooleanSupplier condition, int budget) {
        if (server.isSameThread()) {
            throw new IllegalStateException("a tick wait was started on the server thread, which would wait for itself");
        }
        CompletableFuture<Boolean> done = ServerTicks.await(condition, budget);
        try {
            return done.get(budget * WALL_MS_PER_TICK + WALL_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            done.cancel(false);
            throw new AssertionError("the server did not run " + budget + " ticks in time: it is stalled or stopped");
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for server ticks", e);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error err) throw err;
        return new IllegalStateException(t);
    }

    private final class AwaitImpl implements Await {
        private int within = 200;
        private String description = "the condition";

        @Override
        public Await within(int ticks) {
            this.within = ticks;
            return this;
        }

        @Override
        public Await as(String description) {
            this.description = description;
            return this;
        }

        @Override
        public void until(BooleanSupplier condition) {
            if (!waitFor(condition, within)) {
                throw new AssertionError(description + " did not hold within " + within + " ticks");
            }
        }
    }
}
