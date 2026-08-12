package net.magicterra.stagewright.scene;

/**
 * The two capability adapters StageWright ships, registered through the same seam a third party uses.
 *
 * <p>They existed before the seam did, as {@link Equip}'s Curios half and all of {@link Quests}, and
 * their code has not moved — {@link SceneContext#equip()} and {@link SceneContext#quests()} are still
 * how a scene reaches them. What registering them here buys is that they stop being two special
 * cases:
 *
 * <ul>
 *   <li>{@link SceneContext#capabilities()} reports them alongside anything a mod contributed, so a
 *       scene that skipped can record what the runtime actually had;</li>
 *   <li>"present means it works, absent means skip" becomes one rule with three implementations
 *       rather than three implementations of an unwritten rule;</li>
 *   <li>whoever writes the third integration has two worked examples that are not toys.</li>
 * </ul>
 *
 * <p>Both are reflective rather than typed, which {@link CapabilityProvider} tells third parties NOT
 * to copy. The reason is specific to shipping: these live in the harness jar, which is present in
 * every run whether or not Curios and FTB Quests are, so their classes must load in a runtime that
 * has neither. An adapter shipped in the mod it adapts has no such problem and should be written
 * against real types.
 */
public final class ShippedCapabilities {

    // Public because ServiceLoader instantiates the nested classes reflectively and a provider class
    // has to be publicly accessible. Nothing else should reference this type; scenes reach both
    // adapters through s.equip() / s.quests() as they always have.
    private ShippedCapabilities() {}

    /** Curios' accessory slots. The facet is {@link Equip}, whose vanilla-armour half works with or
     *  without Curios — so availability here is specifically about the accessory slots. */
    public static final class Curios implements CapabilityProvider {

        @Override
        public String name() {
            return "curios";
        }

        @Override
        public boolean availableIn(SceneContext ctx) {
            return ctx.equip().curiosPresent();
        }

        @Override
        public Object facet(SceneContext ctx) {
            return ctx.equip();
        }

        @Override
        public String absentReason() {
            return "this scene needs Curios accessory slots, and Curios is not in this runtime";
        }
    }

    /** The FTB Quests book. Available means both that the mod is here AND that a quest file loaded —
     *  a server with FTB Quests and no book answers every graph question with nothing, which is not
     *  a fact about the pack's quests. */
    public static final class FtbQuests implements CapabilityProvider {

        @Override
        public String name() {
            return "ftbquests";
        }

        @Override
        public boolean availableIn(SceneContext ctx) {
            return ctx.quests().loaded();
        }

        @Override
        public Object facet(SceneContext ctx) {
            return ctx.quests();
        }

        @Override
        public String absentReason() {
            return "this scene needs an FTB Quests book, and this runtime has no loaded quest file"
                    + " — either the mod is absent, or it is present with nothing under"
                    + " config/ftbquests/quests/";
        }
    }
}
