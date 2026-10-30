package net.magicterra.stagewright.capability;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Game;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Container menus, driven server-side. The capability {@code menus}.
 *
 * <ul>
 *   <li><b>In scope — the menu.</b> Slots, what may go in them, what comes out, what a click does. This is an
 *       {@link AbstractContainerMenu}, it lives on the server, and it is where essentially all of a modded GUI's
 *       behaviour is.
 *   <li><b>Out of scope — the screen.</b> Pixels and layout live in the client; reach them through
 *       {@link net.magicterra.stagewright.test.Client}.
 *   <li><b>Out of reach — a mod that does not use the seam.</b> Everything here goes through {@code MenuProvider}. A
 *       mod that opens its GUI with its own packet has none to hand (Mekanism is the measured case); ask
 *       {@link #hasMenuAt} before assuming.
 * </ul>
 *
 * <pre>{@code
 * Menus menus = game.capability("menus", Menus.class);
 * menus.openAt(bot, plot.at(1, 0, 1)).put(bot, 0, "twilightforest:ironwood_sword", 1);
 * assertEquals("twilightforest:ironwood_ingot", menus.item(bot, 2));
 * }</pre>
 *
 * <p>Positions are world positions in the overworld, such as {@link net.magicterra.stagewright.test.Plot#at} answers
 * ({@code {x, y, z}} from a script test). A menu is opened for a body, which is not moved: put it near the block first
 * if the menu checks distance. Whatever a test opens is closed when it ends, and the body's inventory restored through
 * {@link Items}, because closing a container hands its contents back to the player.
 */
public final class Menus {

    /** Any non-zero constant: zero is the player's own inventory menu, and no client ever opened this one. */
    private static final int TEST_CONTAINER_ID = 119;

    /** Swallows the packets a screen would have wanted: this menu was never announced to a client. */
    private static final ContainerSynchronizer SILENT = new ContainerSynchronizer() {
        @Override
        public void sendInitialData(
                AbstractContainerMenu menu, NonNullList<ItemStack> items, ItemStack carried, int[] data) {}

        @Override
        public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {}

        @Override
        public void sendCarriedChange(AbstractContainerMenu menu, ItemStack carried) {}

        @Override
        public void sendDataChange(AbstractContainerMenu menu, int id, int value) {}
    };

    /** What this facet opened for one body. */
    private record Open(AbstractContainerMenu menu, String title) {}

    private final Game game;
    /** By body name; written on the server thread, read on either. */
    private final Map<String, Open> open = new ConcurrentHashMap<>();
    /** Bodies whose close is registered. Touched on the test thread. */
    private final Set<String> closing = new HashSet<>();

    Menus(Game game) {
        this.game = game;
    }

    // ---- opening ----

    /**
     * Open the menu of the block at a position, for the body.
     *
     * <p>The block's own {@code MenuProvider} first — the route a right-click takes, where conditional cases such as a
     * double chest live — and, when it answers nothing, the block entity if that is a {@code MenuProvider}: a mod with
     * its own block hierarchy never overrides the block's, while its block entity is one. A block with neither fails
     * here.
     */
    public Menus openAt(Body body, BlockPos pos) {
        registerClose(body);
        body.runOnServer(player -> {
            ServerLevel level = player.server.overworld();
            BlockState state = level.getBlockState(pos);
            MenuProvider provider = providerAt(level, pos);
            if (provider == null) {
                Object entity = level.getBlockEntity(pos);
                throw new IllegalStateException("the block at " + pos.toShortString() + " is " + state.getBlock()
                        + " and has no menu — it offers no MenuProvider, and its block entity is "
                        + (entity == null ? "absent" : entity.getClass().getName() + ", which is not one")
                        + ". Either the wrong block is there, or this block has no GUI, or its mod opens GUIs through"
                        + " its own packet instead of the vanilla seam — see hasMenuAt.");
            }
            openProvider(body, player, provider, state.getBlock().toString());
        });
        return this;
    }

    /**
     * Whether the block at a position offers a menu this facet can open — the question {@link #openAt} cannot answer,
     * since it throws.
     */
    public boolean hasMenuAt(BlockPos pos) {
        return game.onServer(server -> providerAt(server.overworld(), pos) != null);
    }

    /** Open the body's own inventory menu. Mostly a control: drivable here but not a mod's menu narrows the problem. */
    public Menus openInventory(Body body) {
        registerClose(body);
        body.runOnServer(player -> {
            closeQuietly(body, player);
            open.put(body.name(), new Open(player.inventoryMenu, "inventory"));
        });
        return this;
    }

    // ---- reading ----

    /** Whether a menu this facet opened is still the body's current one. */
    public boolean isOpen(Body body) {
        return body.onServer(player -> {
            Open o = open.get(body.name());
            return o != null && player.containerMenu == o.menu();
        });
    }

    /** The menu's display title, as text; {@code ""} when nothing is open. */
    public String title(Body body) {
        Open o = open.get(body.name());
        return o == null ? "" : o.title();
    }

    /** How many slots the menu has, the player's own inventory slots included. */
    public int slotCount(Body body) {
        return body.onServer(player -> require(body, player).slots.size());
    }

    /** The item id in a slot, or {@code ""} when the slot is empty. */
    public String item(Body body, int slot) {
        return body.onServer(player -> Ids.idOf(slotAt(body, player, slot).getItem()));
    }

    /** How many items are in a slot. */
    public int count(Body body, int slot) {
        return body.onServer(player -> slotAt(body, player, slot).getItem().getCount());
    }

    /** Every non-empty slot, as {@code "<index>=<item id> x<count>"}. */
    public List<String> contents(Body body) {
        return body.onServer(player -> {
            AbstractContainerMenu menu = require(body, player);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < menu.slots.size(); i++) {
                ItemStack stack = menu.slots.get(i).getItem();
                if (!stack.isEmpty()) {
                    out.add(i + "=" + Ids.idOf(stack) + " x" + stack.getCount());
                }
            }
            return out;
        });
    }

    // ---- writing ----

    /**
     * Put an item directly into a slot, then tell the menu its inputs changed — a recipe-shaped menu recomputes its
     * output in {@code slotsChanged}, so without that the output slot reads stale.
     */
    public Menus put(Body body, int slot, String itemId, int count) {
        body.runOnServer(player -> {
            AbstractContainerMenu menu = require(body, player);
            Slot target = slotAt(body, player, slot);
            target.set(Ids.stack(itemId, count));
            menu.slotsChanged(target.container);
            menu.broadcastChanges();
        });
        return this;
    }

    /** {@link #put(Body, int, String, int)} with a count of one. */
    public Menus put(Body body, int slot, String itemId) {
        return put(body, slot, itemId, 1);
    }

    /** Empty a slot, with the same change notification {@link #put} sends. */
    public Menus clear(Body body, int slot) {
        body.runOnServer(player -> {
            AbstractContainerMenu menu = require(body, player);
            Slot target = slotAt(body, player, slot);
            target.set(ItemStack.EMPTY);
            menu.slotsChanged(target.container);
            menu.broadcastChanges();
        });
        return this;
    }

    /** Left-click a slot through the menu's real click handler, what a packet from a real client reaches. */
    public Menus click(Body body, int slot) {
        return click(body, slot, 0, "pickup");
    }

    /** Shift-click ({@code quick_move}) a slot. */
    public Menus shiftClick(Body body, int slot) {
        return click(body, slot, 0, "quick_move");
    }

    /** Click a slot with an explicit button and click type, a vanilla {@link ClickType} name in lower case. */
    public Menus click(Body body, int slot, int button, String clickType) {
        ClickType type = clickTypeOf(clickType);
        body.runOnServer(player -> {
            AbstractContainerMenu menu = require(body, player);
            slotAt(body, player, slot); // bounds-checked with this facet's message
            menu.clicked(slot, button, type, player);
            menu.broadcastChanges();
        });
        return this;
    }

    /** Close the menu. Safe to call when nothing is open. */
    public Menus close(Body body) {
        body.runOnServer(player -> {
            Open o = open.remove(body.name());
            if (o != null && player.containerMenu == o.menu()) {
                player.closeContainer();
            }
        });
        return this;
    }

    // ---- internals ----

    /** The block's own provider, else its block entity if that is one, else null. */
    private static MenuProvider providerAt(ServerLevel level, BlockPos pos) {
        MenuProvider provider = level.getBlockState(pos).getMenuProvider(level, pos);
        if (provider != null) {
            return provider;
        }
        return level.getBlockEntity(pos) instanceof MenuProvider fromEntity ? fromEntity : null;
    }

    /**
     * Build the menu on the server and make it the player's, without the {@code ClientboundOpenScreenPacket}: that
     * packet asks the client to rebuild the menu from a buffer only the mod's own opener knows how to write, and an
     * empty one has taken a client's packet listener down. What is lost is the screen, which this does not assert
     * about.
     */
    private void openProvider(Body body, ServerPlayer player, MenuProvider provider, String what) {
        closeQuietly(body, player);
        AbstractContainerMenu built = provider.createMenu(TEST_CONTAINER_ID, player.getInventory(), player);
        if (built == null) {
            throw new IllegalStateException("the menu of " + what + " declined to open for this player — createMenu"
                    + " answered null, which usually means the menu refused this player (a lock, an owner check) or"
                    + " its block entity was not ready");
        }
        player.containerMenu = built;
        // broadcastChanges() calls into a synchronizer unconditionally; vanilla's initMenu is private.
        built.setSynchronizer(SILENT);
        open.put(body.name(), new Open(built, provider.getDisplayName().getString()));
    }

    private AbstractContainerMenu require(Body body, ServerPlayer player) {
        Open o = open.get(body.name());
        if (o == null) {
            throw new IllegalStateException("no menu is open for " + body.name() + " — call openAt first");
        }
        if (player.containerMenu != o.menu()) {
            throw new IllegalStateException("the menu this test opened ('" + o.title() + "') is no longer "
                    + body.name() + "'s current one — something closed or replaced it");
        }
        return o.menu();
    }

    private Slot slotAt(Body body, ServerPlayer player, int slot) {
        AbstractContainerMenu menu = require(body, player);
        if (slot < 0 || slot >= menu.slots.size()) {
            throw new IllegalStateException("slot " + slot + " is out of range for '"
                    + open.get(body.name()).title()
                    + "', which has " + menu.slots.size() + " slots (the menu's own slots come first, the player's"
                    + " inventory after them)");
        }
        return menu.slots.get(slot);
    }

    /** Close whatever is open before opening something else. */
    private void closeQuietly(Body body, ServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        open.remove(body.name());
    }

    /**
     * Close the menu when the test ends, and give the body back the inventory it had: vanilla returns a container's
     * contents to the player on close, so an item put into a machine would otherwise end in the next test's bag.
     * Registered after the inventory pin, so it runs before that restore: the last registered runs first.
     */
    private void registerClose(Body body) {
        if (!closing.add(body.name())) {
            return;
        }
        game.capability("items", Items.class).pinInventory(body);
        ServerPlayer player = body.onServer(p -> p);
        game.afterTest(server -> {
            open.remove(body.name());
            if (player.containerMenu != player.inventoryMenu) {
                player.closeContainer();
            }
        });
    }

    private static ClickType clickTypeOf(String name) {
        if (name != null) {
            for (ClickType candidate : ClickType.values()) {
                if (candidate.name().equalsIgnoreCase(name)) {
                    return candidate;
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (ClickType candidate : ClickType.values()) {
            names.add(candidate.name().toLowerCase(Locale.ROOT));
        }
        throw new IllegalStateException(
                "'" + name + "' is not a click type — the types are " + String.join(", ", names));
    }
}
