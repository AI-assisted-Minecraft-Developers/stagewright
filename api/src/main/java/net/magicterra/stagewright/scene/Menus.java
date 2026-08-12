package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.Clock;
import net.magicterra.stagewright.contract.SceneFailure;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Container menus, driven server-side.
 *
 * <p>Reached as {@link SceneContext#menu()}. This is the honest half of "GUI testing", and the line
 * is worth stating because the phrase invites scope creep:
 *
 * <ul>
 *   <li><b>In scope — the menu.</b> Slots, what may go in them, what comes out, what a click does,
 *       how a recipe-shaped menu reacts to its inputs changing. This is an
 *       {@link AbstractContainerMenu}, it lives on the server, and it is where essentially all of a
 *       modded GUI's behaviour actually is. Twilight Forest's Uncrafting Table is the clean example:
 *       the screen draws it, but {@code UncraftingMenu} decides what a sword comes apart into.</li>
 *   <li><b>Out of scope — the screen.</b> Pixels, layout, tooltips, whether a button is visible.
 *       Those live in the client JVM and are reached from a client probe or an attached script
 *       through {@code mc.client.screen.*}; a scene body runs on the server thread and has no
 *       screen to look at. A facet that pretended otherwise would be asserting about a
 *       reconstruction rather than about the game.</li>
 *   <li><b>Out of reach — a mod that does not use the seam.</b> Everything here goes through
 *       {@code MenuProvider} / {@code player.openMenu}, which is what vanilla and most mods use. A
 *       mod that opens its GUI by sending its own packet has no {@code MenuProvider} to hand and
 *       cannot be opened from here without an adapter for that mod. Measured on All the Mods 10:
 *       Mekanism's machines are in this category, and its capabilities are still perfectly readable
 *       through {@link SceneContext#capability} — the two seams are independent. Ask
 *       {@link #hasMenuAt} before assuming; it is not a property you can tell from the block.</li>
 * </ul>
 *
 * <pre>{@code
 * s.setBlock(0, 1, 0, block);                  // the block whose menu is under test
 * s.menu().openAt(0, 1, 0);
 * s.menu().put(0, "twilightforest:ironwood_sword", 1);
 * s.expect(s.menu().item(2)).as("what the table offers back").isEqualTo("twilightforest:ironwood_ingot");
 * }</pre>
 *
 * <h2>It needs a player, and it always closes</h2>
 *
 * A menu is opened FOR somebody, so this facet routes through {@link SceneContext#playerHere()} and
 * {@code skip}s on a bare dedicated server. Opening registers a {@link SceneContext#cleanup} that
 * closes it, because a player left holding an open container carries it into the next scene — and
 * the next scene's own {@code openAt} would then be replacing a menu rather than opening one, which
 * fails in a way that names neither scene.
 */
public final class Menus {

    private final SceneContext ctx;

    /** The menu opened by this facet, or null when nothing is open. */
    private AbstractContainerMenu open;

    /** Set once a close-cleanup is registered, so repeated open/close cycles register one. */
    private boolean closing;

    /** The container id every scene-opened menu carries. Any non-zero constant does: zero is the
     *  player's own inventory menu, and no client ever opened this one, so nothing compares ids
     *  against it except the slot packets the client is right to ignore. */
    private static final int SCENE_CONTAINER_ID = 119;

    /** Swallows the packets a screen would have wanted. Nothing is listening: this menu was built
     *  on the server and never announced to a client. */
    private static final ContainerSynchronizer SILENT = new ContainerSynchronizer() {
        @Override public void sendInitialData(AbstractContainerMenu menu,
                NonNullList<ItemStack> items, ItemStack carried, int[] data) { }
        @Override public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) { }
        @Override public void sendCarriedChange(AbstractContainerMenu menu, ItemStack carried) { }
        @Override public void sendDataChange(AbstractContainerMenu menu, int id, int value) { }
    };

    private String title = "";

    Menus(SceneContext ctx) {
        this.ctx = ctx;
    }

    // ---- opening ----

    /**
     * Open the menu of the block at an origin-relative position, for the scene's player.
     *
     * <p><b>The block first, then its block entity.</b> The block's own {@code MenuProvider} is the
     * authoritative one — it is the route a right-click takes, and it is where the conditional cases
     * live (a chest block resolves a double chest there; a locked one refuses there). Whatever it
     * answers is used unchanged.
     *
     * <p>It very often answers nothing, and that is not the same as "this block has no menu".
     * {@code BlockBehaviour#getMenuProvider} returns null by default; vanilla blocks get an
     * implementation by extending {@code BaseEntityBlock}, which hands the question to the block
     * entity. A mod with its own block hierarchy and its own GUI-opening packet never overrides it,
     * while its block entity <i>is</i> a {@code MenuProvider} — Mekanism is the measured case, and
     * it is the shape most tech mods take. Asking only the block finds vanilla-shaped blocks and
     * misses most of a modpack, which is backwards for a framework whose subject is packs. So a null
     * answer falls through to the block entity, which is the object the mod's own open call passes
     * anyway. Nothing that answered is overridden, so no conditional provider is bypassed.
     *
     * <p>A block with no menu on either fails here rather than leaving the scene asserting against
     * whatever container the player happened to have open.
     */
    public Menus openAt(int dx, int dy, int dz) {
        ServerPlayer player = ctx.playerHere();
        BlockPos pos = ctx.rel(dx, dy, dz);
        BlockState state = ctx.level().getBlockState(pos);
        MenuProvider provider = providerAt(pos);
        if (provider == null) {
            Object entity = ctx.level().getBlockEntity(pos);
            throw new SceneFailure("the block at rel(" + dx + ", " + dy + ", " + dz + ") is "
                    + state.getBlock() + " and has no menu — it offers no MenuProvider, and its"
                    + " block entity is "
                    + (entity == null ? "absent" : entity.getClass().getName() + ", which is not one")
                    + ". Either the wrong block is there, or this block has no GUI, or its mod opens"
                    + " GUIs through its own packet instead of the vanilla seam — see hasMenuAt.");
        }
        return openProvider(player, provider, state.getBlock().toString());
    }

    /**
     * Whether the block at an origin-relative position offers a menu this facet can open.
     *
     * <p>Exists because {@link #openAt} throws, and a pack author's first question is not "assert
     * this machine's menu" but "which of my machines can I test this way at all?" — which a throwing
     * call cannot be used to ask. The answer is not obvious and not uniform: a mod that opens its
     * GUI through its own packet rather than {@code player.openMenu} has no {@code MenuProvider}
     * anywhere, and no adapter-free way to be opened. Mekanism is the measured case — its
     * {@code TileEntityMekanism} implements seventeen interfaces and {@code MenuProvider} is not one
     * of them — so a suite that assumed every machine in a tech pack was reachable here would be
     * asserting about a seam half the pack does not use.
     */
    public boolean hasMenuAt(int dx, int dy, int dz) {
        return providerAt(ctx.rel(dx, dy, dz)) != null;
    }

    /** The block's own provider, else its block entity if that is one, else null. */
    private MenuProvider providerAt(BlockPos pos) {
        MenuProvider provider = ctx.level().getBlockState(pos).getMenuProvider(ctx.level(), pos);
        if (provider != null) return provider;
        return ctx.level().getBlockEntity(pos) instanceof MenuProvider fromEntity ? fromEntity : null;
    }

    /** Open the player's own inventory menu — the one every player always has. Mostly useful as a
     *  control: a scene that can drive this but not a mod's menu has narrowed the problem. */
    public Menus openInventory() {
        ServerPlayer player = ctx.playerHere();
        closeQuietly(player);
        open = player.inventoryMenu;
        title = "inventory";
        registerClose(player);
        return this;
    }

    /**
     * Build the menu on the server and make it the player's, without opening a screen.
     *
     * <p>This is {@code ServerPlayer.openMenu} minus the {@code ClientboundOpenScreenPacket}, and
     * the omission is the point. That packet asks the CLIENT to construct the same menu from a data
     * buffer the opener is expected to have written — a mod's own open call writes what its factory
     * reads, and a generic caller cannot know what that is. Sending it with nothing attached is not
     * a harmless no-op: Actually Additions' coal generator reads a {@code BlockPos} out of it, got
     * null, and took the client's whole packet listener down with it, ending a 33-scene run after
     * nine. Guessing "probably a BlockPos" would fix that one mod and break the next.
     *
     * <p>What is lost is the client screen, which this facet does not assert about and says so at
     * the top. What is kept is everything it does assert about: the mod's own {@code createMenu}
     * runs, its slots are real, and clicks go through its real handler. The synchronizer is attached
     * exactly as vanilla attaches it, so {@code broadcastChanges} behaves; the client ignores slot
     * packets for a container id it never opened.
     */
    private Menus openProvider(ServerPlayer player, MenuProvider provider, String what) {
        closeQuietly(player);
        AbstractContainerMenu built = provider.createMenu(SCENE_CONTAINER_ID, player.getInventory(), player);
        if (built == null) {
            throw new SceneFailure("the menu of " + what + " declined to open for this player —"
                    + " createMenu answered null, which usually means the menu refused this player"
                    + " (a lock, an owner check) or its block entity was not ready");
        }
        player.containerMenu = built;
        // Vanilla's initMenu is private, and what it installs — the player's own listener and
        // synchronizer — exists to talk to a screen that was never opened. A no-op synchronizer is
        // what this menu actually needs: broadcastChanges() calls into it unconditionally, so the
        // alternative to supplying one is an NPE on the first put().
        built.setSynchronizer(SILENT);
        open = built;
        title = provider.getDisplayName().getString();
        registerClose(player);
        return this;
    }

    // ---- reading ----

    /** Whether a menu opened by this facet is still the player's current one. */
    public boolean isOpen() {
        return open != null && ctx.player().containerMenu == open;
    }

    /** The menu's display title, as text. */
    public String title() {
        return title;
    }

    /** How many slots the menu has, the player's own inventory slots included — menus append those,
     *  so this is larger than the block's own slot count and that is not a bug. */
    public int slotCount() {
        return require().slots.size();
    }

    /** The item id in a slot, or {@code ""} when the slot is empty. */
    public String item(int slot) {
        return Ids.idOf(slotAt(slot).getItem());
    }

    /** How many items are in a slot. */
    public int count(int slot) {
        return slotAt(slot).getItem().getCount();
    }

    /** Every non-empty slot, as {@code "<index>=<item id> x<count>"} — for a scene that wants to
     *  report what a menu actually contained rather than guess which index to look at. */
    public List<String> contents() {
        AbstractContainerMenu menu = require();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            out.add(i + "=" + Ids.idOf(stack) + " x" + stack.getCount());
        }
        return out;
    }

    // ---- writing ----

    /**
     * Put an item directly into a slot, then tell the menu its inputs changed.
     *
     * <p>The notification is the load-bearing half. A recipe-shaped menu — a crafting table, a
     * furnace, Twilight Forest's uncrafting table — recomputes its output in {@code slotsChanged},
     * so a scene that only set the stack would read a stale output slot and conclude the menu was
     * broken.
     */
    public Menus put(int slot, String itemId, int count) {
        AbstractContainerMenu menu = require();
        Slot target = slotAt(slot);
        target.set(Ids.stack(itemId, count));
        menu.slotsChanged(target.container);
        menu.broadcastChanges();
        return this;
    }

    /** {@link #put(int, String, int)} with a count of one. */
    public Menus put(int slot, String itemId) {
        return put(slot, itemId, 1);
    }

    /** Empty a slot, with the same change notification {@link #put} sends. */
    public Menus clear(int slot) {
        AbstractContainerMenu menu = require();
        Slot target = slotAt(slot);
        target.set(ItemStack.EMPTY);
        menu.slotsChanged(target.container);
        menu.broadcastChanges();
        return this;
    }

    /**
     * Left-click a slot, through the menu's real click handler.
     *
     * <p>{@code AbstractContainerMenu.clicked} is what a packet from a real client reaches, so a
     * menu that implements take-restrictions, shift-click behaviour or a result slot's
     * "onTake" side effects runs all of it.
     */
    public Menus click(int slot) {
        return click(slot, 0, "pickup");
    }

    /** Shift-click ({@code quick_move}) a slot — the interaction most result slots are actually
     *  used with, and the one that moves a stack into the player's inventory. */
    public Menus shiftClick(int slot) {
        return click(slot, 0, "quick_move");
    }

    /**
     * Click a slot with an explicit button and click type.
     *
     * <p>Type names are the vanilla {@link ClickType} constants, lowercased. An unknown one lists
     * the whole set rather than defaulting, on the same reasoning as {@code Clock.parse}: a
     * defaulted click would exercise a different interaction than the scene wrote.
     */
    public Menus click(int slot, int button, String clickType) {
        AbstractContainerMenu menu = require();
        slotAt(slot);                       // bounds-check with this facet's message, not an AIOOBE
        menu.clicked(slot, button, clickTypeOf(clickType), ctx.player());
        menu.broadcastChanges();
        return this;
    }

    /** Close the menu. Safe to call when nothing is open. */
    public Menus close() {
        ServerPlayer player = ctx.player();
        if (open != null && player.containerMenu == open) player.closeContainer();
        open = null;
        title = "";
        return this;
    }

    // ---- internals ----

    private AbstractContainerMenu require() {
        if (open == null) {
            throw new SceneFailure("no menu is open — call s.menu().openAt(dx, dy, dz) first");
        }
        AbstractContainerMenu current = ctx.player().containerMenu;
        if (current != open) {
            throw new SceneFailure("the menu this scene opened ('" + title + "') is no longer the"
                    + " player's current one — something closed or replaced it, so any assertion"
                    + " about its slots would be about a different menu");
        }
        return open;
    }

    private Slot slotAt(int slot) {
        AbstractContainerMenu menu = require();
        if (slot < 0 || slot >= menu.slots.size()) {
            throw new SceneFailure("slot " + slot + " is out of range for '" + title + "', which has "
                    + menu.slots.size() + " slots (the menu's own slots come first, the player's"
                    + " inventory is appended after them)");
        }
        return menu.slots.get(slot);
    }

    /** Close whatever is open before opening something else, so an unbalanced scene cannot leave a
     *  menu stacked under the one it is about to assert on. */
    private void closeQuietly(ServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        open = null;
    }

    /**
     * Close the menu at teardown, and give the player back the inventory they had.
     *
     * <p>Both, because closing is not enough. Vanilla returns a container's contents to the player
     * when it closes, and half the menu's slots ARE the player's inventory to begin with — so a
     * scene that only ever called {@code put(0, ...)} on a machine still ends with an item in the
     * player's bag. That item is then sitting in the NEXT scene's inventory, which nothing notices:
     * the arena audit looks at the arena, and the player is not in it.
     *
     * <p>Found exactly that way. A scene put one dirt into an uncrafting table and asserted the
     * table refused it; the scene after it opened its own menu and reported {@code 47=minecraft:dirt}
     * among the contents. Nothing failed — this time. The restore is delegated to {@link Items} so
     * there is one snapshot and one rule about the player's inventory rather than two that can
     * disagree, and it is idempotent, so a scene using both facets still pins once.
     */
    private void registerClose(ServerPlayer player) {
        if (closing) return;
        closing = true;
        ctx.items().pinInventory();
        ctx.cleanup(() -> {
            if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        });
    }

    private static ClickType clickTypeOf(String name) {
        if (name != null) {
            for (ClickType candidate : ClickType.values()) {
                if (candidate.name().equalsIgnoreCase(name)) return candidate;
            }
        }
        List<String> names = new ArrayList<>();
        for (ClickType candidate : ClickType.values()) names.add(candidate.name().toLowerCase(java.util.Locale.ROOT));
        throw new SceneFailure("'" + name + "' is not a click type — the types are "
                + String.join(", ", names));
    }
}
