package net.magicterra.stagewright.driver.verbs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.test.TestHarnessApi;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.BotsOp;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.BotsReply;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Clear;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Cleared;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Count;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Counted;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Pin;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Pinned;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Read;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.SettingsOp;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.SettingsReply;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Tick;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Ticked;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Unpin;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Unpinned;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Values;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Write;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Written;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.KeyedValueType.Entry;
import net.magicterra.worlddriver.api.thread.ServerThreadGuard;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.BotTicker;
import net.magicterra.worlddriver.bot.SettingsCommand;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.PlanningConfig;

/**
 * {@code mc.test.settings} and {@code mc.test.bots}, over the driver's own settings and tick list. Each runs on
 * the calling thread and answers within the call: a scene on the server thread goes on in the same tick with
 * the answer, which {@code mc.bot.setting} cannot give it when a client holds the settings.
 */
final class HarnessService implements TestHarnessApi {
    private final Map<Integer, Map<String, Object>> pins = new ConcurrentHashMap<>();
    private final AtomicInteger nextPin = new AtomicInteger();

    @Override
    public CompletionStage<SettingsReply> settings(SettingsOp op, CallContext ctx) {
        return now(() -> switch (op) {
            case Pin p -> {
                Map<String, Object> saved = BotConfig.snapshotAll();
                switch (p.baseline()) {
                    case TEST -> BotConfig.applyGameTestBaseline();
                    case COMPILED -> BotConfig.applyCompiledDefaults();
                    case CURRENT -> { }
                }
                if (p.untimed()) {
                    PlanningConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
                    ControlConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
                }
                int id = nextPin.incrementAndGet();
                pins.put(id, saved);
                yield new Pinned(id);
            }
            case Unpin u -> {
                Map<String, Object> saved = pins.remove(u.pin());
                if (saved == null) throw ApiException.invalidParam("/pin", ApiIssue.Code.INVALID_VALUE, "no pin " + u.pin() + " is held");
                BotConfig.restoreAll(saved);
                yield new Unpinned(u.pin());
            }
            case Write w -> {
                List<ApiIssue> issues = SettingsCommand.check(w.values(), "/values");
                if (!issues.isEmpty()) throw ApiException.invalidParams(issues);
                BotApi bot = BotHooks.impl();
                SettingsCommand.commit(bot, w.values());
                List<Entry> now = new ArrayList<>();
                for (Entry e : w.values()) now.add(new Entry(e.key(), SettingsCommand.read(bot, e.key())));
                yield new Written(now);
            }
            case Read r -> {
                BotApi bot = BotHooks.impl();
                List<Entry> now = new ArrayList<>();
                for (String k : r.keys()) now.add(new Entry(k, SettingsCommand.read(bot, k)));
                yield new Values(now);
            }
        });
    }

    @Override
    public CompletionStage<BotsReply> bots(BotsOp op, CallContext ctx) {
        return now(() -> {
            if (!ServerThreadGuard.onServerThread()) {
                throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "mc.test.bots runs on the server thread, the one that ticks the bots");
            }
            return switch (op) {
                case Tick t -> {
                    int n = 0;
                    for (; n < t.ticks(); n++) {
                        if (t.untilIdle() && BotTicker.activeCount() == 0) break;
                        BotTicker.tickAll();
                    }
                    yield new Ticked(n, BotTicker.activeCount());
                }
                case Count c -> new Counted(BotTicker.size(), BotTicker.activeCount());
                case Clear c -> {
                    int removed = BotTicker.size();
                    BotTicker.clear();
                    yield new Cleared(removed);
                }
            };
        });
    }

    private static <R> CompletionStage<R> now(Supplier<R> body) {
        try {
            return CompletableFuture.completedFuture(body.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
