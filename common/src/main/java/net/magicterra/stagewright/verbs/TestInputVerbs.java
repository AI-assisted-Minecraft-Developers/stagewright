package net.magicterra.stagewright.verbs;

import java.util.Map;

import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.integer;
import static net.magicterra.worlddriver.mcp.schema.Schemas.object;
import static net.magicterra.worlddriver.mcp.schema.Schemas.stringEnum;
import static net.magicterra.worlddriver.mcp.schema.Schemas.tool;

/**
 * task#90 instrument-face gap closers — two hidden {@code mc.test.input.*} verbs registered
 * through the same paired SPI ({@link ToolCatalog#registerVerb}) as {@link TestResetVerb}, under
 * the granted {@code mc.test.*} namespace:
 *
 * <ul>
 *   <li>{@code mc.test.input.heldKeys} — a client-thread {@link net.minecraft.client.KeyMapping#isDown()}
 *       readback for the eight keymappings {@code BotInteract.releaseKeys()} clears. Closes the
 *       {@code reset.behavior} keys sub-assertion gap: the unconditional {@code reset[]} "keys" token
 *       proves {@code releaseKeys()} RAN, not that a key was actually down and got cleared. With this
 *       verb the instrument contract can press W → assert {@code up==true} → {@code mc.test.reset} →
 *       assert every key false, catching a real {@code releaseKeys()} no-op regression.</li>
 *   <li>{@code mc.test.input.useOnBlock} — an instrument-grade world right-click: synthesize a
 *       {@code BlockHitResult} at the target block and call {@code gameMode.useItemOn}, with NO
 *       movement / aiming / behaviour-face involvement. The only instrument route that opens a
 *       block-entity container screen (the {@code ui.containerFurnace} scene that was
 *       {@code @Disabled} for lack of exactly this verb — see that test's history).</li>
 * </ul>
 *
 * <h2>Boot placement &amp; client-only discipline</h2>
 * Registered from {@code WorldDriverCommon.ensureRpcUp}, right after {@link TestResetVerb#register()},
 * for the same reasons: {@link #register()} must run after the route sink is wired (a pre-boot
 * {@code registerVerb} throws) and it must run on the COMMON path so a dedicated server also carries
 * the route + schema. Each handler delegates through {@link BotHooks#impl()} — null on a dedicated
 * server, where the null check throws the established {@code mc.bot.*} "client only" phrasing BEFORE
 * any client type is touched — so registering these verbs on a server never drags {@code BotApiImpl}
 * (which references {@code net.minecraft.client.*}) onto the server class path. This class imports no
 * {@code net.minecraft.client.*} type.
 */
public final class TestInputVerbs {

    private TestInputVerbs() {}

    /** Hidden — no params. Client-thread {@code KeyMapping.isDown()} readback. */
    public static final ToolSchema HELD_KEYS = tool(
            "mc.test.input.heldKeys",
            "Instrument-grade held-key readback (dev/test harness verb; RPC-only). Reads "
            + "KeyMapping.isDown() on the client thread for the eight movement/action keymappings "
            + "BotInteract.releaseKeys() clears and returns {ok:true, keys:{up,down,left,right,jump,"
            + "sprint,attack,shift:bool}}. Pure observation. No params. Client-only — a dedicated "
            + "server rejects it loudly.",
            object().additionalProperties(false)).asHidden();

    /** Hidden — right-click a world block instrument-grade (no move/aim/sneak). */
    public static final ToolSchema USE_ON_BLOCK = tool(
            "mc.test.input.useOnBlock",
            "Instrument-grade world right-click (dev/test harness verb; RPC-only). Synthesizes a "
            + "BlockHitResult at block {x,y,z} (face nearest the player's eye, hit at that face's "
            + "centre) and calls gameMode.useItemOn(player, hand, hit) on the client thread — NO "
            + "movement, NO aiming, NO sneak toggle, NOT via the behaviour face. Opens a block-entity "
            + "container screen (e.g. a furnace) that no other instrument verb can reach. hand defaults "
            + "to main. Returns {ok, result:<InteractionResult>, consumed, hand, face}. Client-only — a "
            + "dedicated server rejects it loudly.",
            object()
                    .req("x", integer())
                    .req("y", integer())
                    .req("z", integer())
                    .prop("hand", stringEnum("main", "off"))
                    .additionalProperties(false)).asHidden();

    private static volatile boolean registered;

    /**
     * Register both {@code mc.test.input.*} verbs through the paired SPI. Idempotent (guarded).
     * Must run after the route sink is wired (see class javadoc) — a pre-boot call throws from
     * {@code registerVerb}.
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ToolCatalog.registerVerb(HELD_KEYS, TestInputVerbs::handleHeldKeys);
        ToolCatalog.registerVerb(USE_ON_BLOCK, TestInputVerbs::handleUseOnBlock);
    }

    /** Route handler: hop to the bot impl (client) or throw the established client-only error. */
    static Object handleHeldKeys(Map<String, Object> params) {
        BotApi bot = BotHooks.impl();
        if (bot == null) {
            throw new IllegalStateException(
                    "mc.test.input.heldKeys not available (client only; bot impl not registered)");
        }
        return bot.heldKeys();
    }

    /** Route handler: hop to the bot impl (client) or throw the established client-only error. */
    static Object handleUseOnBlock(Map<String, Object> params) {
        BotApi bot = BotHooks.impl();
        if (bot == null) {
            throw new IllegalStateException(
                    "mc.test.input.useOnBlock not available (client only; bot impl not registered)");
        }
        return bot.useOnBlock(params);
    }
}
