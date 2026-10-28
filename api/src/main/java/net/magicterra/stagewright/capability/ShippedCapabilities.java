package net.magicterra.stagewright.capability;

import net.magicterra.stagewright.test.Game;

/**
 * The capability adapters StageWright ships, registered through the same seam a third party uses: {@code items}
 * ({@link Items}), {@code menus} ({@link Menus}), {@code equip} ({@link Equip}) everywhere, and {@code curios} (the
 * same {@link Equip}, available only with Curios) and {@code ftbquests} ({@link Quests}) when their mods are here.
 *
 * <p>The Curios and FTB Quests adapters are reflective rather than typed, which {@link CapabilityProvider} tells third
 * parties NOT to copy: they live in the harness jar, present in every run whether or not those mods are, so their
 * classes must load in a runtime that has neither.
 */
public final class ShippedCapabilities {

    // Public because ServiceLoader instantiates the nested classes reflectively.
    private ShippedCapabilities() {}

    /** A player's inventory, hunger and effects. */
    public static final class Items implements CapabilityProvider {
        @Override public String name() { return "items"; }
        @Override public boolean availableIn(Game game) { return true; }
        @Override public Object facet(Game game) { return new net.magicterra.stagewright.capability.Items(game); }
    }

    /** Container menus, driven server-side. */
    public static final class Menus implements CapabilityProvider {
        @Override public String name() { return "menus"; }
        @Override public boolean availableIn(Game game) { return true; }
        @Override public Object facet(Game game) { return new net.magicterra.stagewright.capability.Menus(game); }
    }

    /** Equipment slots: vanilla armour always, Curios' slots when Curios is here. */
    public static final class Equipment implements CapabilityProvider {
        @Override public String name() { return "equip"; }
        @Override public boolean availableIn(Game game) { return true; }
        @Override public Object facet(Game game) { return new Equip(game); }
    }

    /** Curios' accessory slots. The facet is the test's {@code equip}, so the two names share one restore. */
    public static final class Curios implements CapabilityProvider {

        @Override
        public String name() {
            return "curios";
        }

        @Override
        public boolean availableIn(Game game) {
            return Equip.curiosInstalled();
        }

        @Override
        public Object facet(Game game) {
            return game.capability("equip");
        }

        @Override
        public String absentReason() {
            return "this test needs Curios accessory slots, and Curios is not in this runtime";
        }
    }

    /** The FTB Quests book. Available means both that the mod is here AND that a quest file loaded — a server with
     *  FTB Quests and no book answers every graph question with nothing. */
    public static final class FtbQuests implements CapabilityProvider {

        @Override
        public String name() {
            return "ftbquests";
        }

        @Override
        public boolean availableIn(Game game) {
            return Quests.bookLoaded();
        }

        @Override
        public Object facet(Game game) {
            return new Quests(game);
        }

        @Override
        public String absentReason() {
            return "this test needs an FTB Quests book, and this runtime has no loaded quest file"
                    + " — either the mod is absent, or it is present with nothing under"
                    + " config/ftbquests/quests/";
        }
    }
}
