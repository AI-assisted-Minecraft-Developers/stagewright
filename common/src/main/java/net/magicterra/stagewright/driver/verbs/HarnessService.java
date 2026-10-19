package net.magicterra.stagewright.driver.verbs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Park;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Part;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Parked;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Pressed;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Unpark;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts.HeldKeys;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.Roster;
import net.magicterra.worlddriver.bot.ServerBot;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.ServerPlayerBody;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
 * {@code mc.test.settings}, {@code mc.test.bots} and {@code mc.test.input.press}, over the driver's own settings and tick list. Each runs on
 * the calling thread and answers within the call: a scene on the server thread goes on in the same tick with
 * the answer, which {@code mc.bot.setting} cannot give it when a client holds the settings. The one exception is a
 * press on self, which runs on the client thread and answers once it has.
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
            onServerThread("mc.test.bots");
            return switch (op) {
                case Tick t -> new Ticked(tick(t), BotTicker.activeCount());
                case Count c -> new Counted(BotTicker.size(), BotTicker.activeCount());
                case Clear c -> {
                    Set<Bot> gone = new HashSet<>();
                    for (Bot b : BotRegistry.all()) {
                        if (BotRegistry.isSelf(b.id())) continue;
                        gone.add(b);
                        Roster.remove(b.id());
                    }
                    // A bot put on the tick list without joining the roster leaves with the rest.
                    for (Bot b : BotTicker.all()) gone.add(b);
                    BotTicker.clear();
                    yield new Cleared(gone.size());
                }
                case Park p -> {
                    BotTicker.unregister(named(p.bot(), "/bot"));
                    yield new Parked(p.bot(), true);
                }
                case Unpark u -> {
                    BotTicker.register(named(u.bot(), "/bot"));
                    yield new Parked(u.bot(), false);
                }
            };
        });
    }

    private static int tick(Tick t) {
        if (t.bot().isEmpty()) {
            if (t.part() != Part.ALL) throw ApiException.invalidParam("/part", ApiIssue.Code.INVALID_VALUE, "part " + t.part().wireName() + " needs a bot");
            int n = 0;
            for (; n < t.ticks(); n++) {
                if (t.untilIdle() && BotTicker.activeCount() == 0) break;
                BotTicker.tickAll();
            }
            return n;
        }
        Bot bot = named(t.bot().get(), "/bot");
        if (!(bot instanceof ServerBot server)) {
            throw ApiException.invalidParam("/bot", ApiIssue.Code.INVALID_VALUE, t.bot().get() + " is not ticked by the server");
        }
        int n = 0;
        for (; n < t.ticks(); n++) {
            if (t.untilIdle() && !bot.busy()) break;
            switch (t.part()) {
                case ALL -> server.tick();
                case BODY -> server.stepBody();
                case BRAIN -> server.think();
            }
        }
        return n;
    }

    @Override
    public CompletionStage<Pressed> press(Press press, CallContext ctx) {
        if (BotRegistry.isSelf(press.bot())) {
            BotApi self = BotHooks.impl();
            if (self == null) {
                return CompletableFuture.failedFuture(ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE,
                        "self is this client's own player, and there is no client here; name a server bot"));
            }
            return self.clientHop().submit(() -> {
                Body body = self.clientAvatar();
                if (body == null) {
                    throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE,
                            "the client has no player now: it is loading, dead or changing dimension");
                }
                return press(press, body);
            }, ctx);
        }
        return now(() -> {
            onServerThread("mc.test.input.press");
            return press(press, named(press.bot(), "/bot").body());
        });
    }

    /** {@code press} on {@code body}, on the thread that owns it. */
    private static Pressed press(Press press, Body body) {
        if (press.forward().isPresent() || press.left().isPresent()) {
            float forward = press.forward().map(Double::floatValue).orElse(forward(body));
            float left = press.left().map(Double::floatValue).orElse(left(body));
            body.commandMove(left, forward);
        }
        press.jump().ifPresent(body::commandJump);
        press.sneak().ifPresent(body::commandSneak);
        press.sprint().ifPresent(body::commandSprint);
        press.toolFor().ifPresent(c -> body.equipment().ifPresent(e -> e.selectTool(cell(c))));
        press.aimAt().ifPresent(c -> body.aimAtBlock(cell(c)));
        press.attack().ifPresent(v -> body.blockInteraction()
                .orElseThrow(() -> ApiException.invalidParam("/attack", ApiIssue.Code.INVALID_VALUE, press.bot() + " cannot dig"))
                .breakHold(v));
        press.use().ifPresent(v -> body.equipment()
                .orElseThrow(() -> ApiException.invalidParam("/use", ApiIssue.Code.INVALID_VALUE, press.bot() + " has no hands"))
                .commandUseItem(v));
        press.hit().ifPresent(id -> {
            Entity target = body.entity().level().getEntity(id);
            if (target == null) throw ApiException.invalidParam("/hit", ApiIssue.Code.INVALID_VALUE, "no entity " + id + " in the bot's level");
            body.attack()
                    .orElseThrow(() -> ApiException.invalidParam("/hit", ApiIssue.Code.INVALID_VALUE, press.bot() + " cannot attack"))
                    .attackEntityUnchecked(target);
        });
        return new Pressed(press.bot(), held(body));
    }

    private static HeldKeys held(Body body) {
        float forward = forward(body);
        float left = left(body);
        boolean attack = body.blockInteraction().map(b -> b.breakHeld()).orElse(false);
        boolean sprint = body.entity() != null && body.entity().isSprinting();
        return new HeldKeys(forward > 0, forward < 0, left > 0, left < 0, body.dbgJumping(), sprint, attack, body.dbgSneak());
    }

    private static float forward(Body body) {
        if (body instanceof ServerPlayerBody p) return p.pendingForward();
        return body.dbgForwardImpulse() ? 1f : 0f;
    }

    private static float left(Body body) {
        return body instanceof ServerPlayerBody p ? p.pendingLeft() : 0f;
    }

    private static BlockPos cell(BlockPosition p) { return new BlockPos(p.x(), p.y(), p.z()); }

    private static Bot named(String ref, String path) {
        Bot bot = BotRegistry.get(ref);
        if (bot == null) throw ApiException.invalidParam(path, ApiIssue.Code.INVALID_VALUE, "no bot named " + ref);
        return bot;
    }

    private static void onServerThread(String method) {
        if (!ServerThreadGuard.onServerThread()) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, method + " runs on the server thread, the one that ticks the bots");
        }
    }

    private static <R> CompletionStage<R> now(Supplier<R> body) {
        try {
            return CompletableFuture.completedFuture(body.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
