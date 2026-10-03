package net.magicterra.stagewright.driver.verbs;

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
 * Two hidden {@code mc.test.input.*} verbs that the instrument face was missing, registered
 * through the same paired SPI ({@link ToolCatalog#registerVerb}) as {@link TestResetVerb}, under
 * the granted {@code mc.test.*} namespace:
 *
 * <ul>
 *   <li>{@code mc.test.input.heldKeys} — a client-thread {@link net.minecraft.client.KeyMapping#isDown()}
 *       readback for the eight keymappings {@code BotInteract.releaseKeys()} clears. The
 *       {@code reset[]} tokens are the reset's own account of what it released; this reads the keys
 *       from outside it, so a test can press W → see {@code up==true} → {@code mc.test.reset} → see
 *       every key false, and a {@code releaseKeys()} that stopped working cannot vouch for itself.</li>
 *   <li>{@code mc.test.input.useOnBlock} — an instrument-grade world right-click: synthesize a
 *       {@code BlockHitResult} at the target block and call {@code gameMode.useItemOn}, with NO
 *       movement / aiming / behaviour-face involvement. The only instrument route that opens a
 *       block-entity container screen, which is what the {@code ui.containerFurnace} scene
 *       drives it for.</li>
 * </ul>
 *
 * <h2>Boot placement &amp; client-only discipline</h2>
 * Registered from StageWright's DriverRuntime, right after {@link TestResetVerb#register()},
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


    /**
     * Register both {@code mc.test.input.*} verbs through the paired SPI, guarded by DriverRuntime.
     * Must run after the route sink is wired (see class javadoc) — a pre-boot call throws from
     * {@code registerVerb}.
     */
    public static synchronized void register() {
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
