package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player's advancement progress — read, granted and revoked.
 *
 * <p>Reached as {@link SceneContext#advancements()}. This is the smallest facet in the conformance
 * set and the one the most rides on, because <b>a large mod's progression is usually not implemented
 * as "kill the boss"; it is implemented as "hold this advancement"</b>. Twilight Forest is the clean
 * example: {@code AdvancementLockedStructure.doesPlayerHaveRequiredAdvancements} decides whether a
 * player may break blocks in a structure, {@code ProgressionEvents} sweeps every 20 ticks for
 * players standing somewhere they have not unlocked, and {@code TFConfig.getPortalLockingAdvancement}
 * decides whether the portal works at all.
 *
 * <p>Which means the eight-boss ladder is testable without fighting eight bosses. Grant the
 * advancement, then assert that the thing it gates actually opened:
 *
 * <pre>{@code
 * s.expect(s.advancements().has("twilightforest:progress_naga")).isFalse();
 * s.advancements().grant("twilightforest:progress_naga");
 * s.expect(s.advancements().has("twilightforest:progress_naga")).isTrue();
 * // ...and now assert the gate this advancement controls has opened.
 * }</pre>
 *
 * That tests the mod's own gating logic, which is the part that breaks, rather than testing that a
 * boss has hit points.
 *
 * <h2>Everything granted is revoked at teardown</h2>
 *
 * Advancements are persisted per player and outlive the scene. A scene that granted
 * {@code progress_lich} and did not clean up would silently disable every LATER scene asserting
 * "this must still be locked" — and those scenes would fail pointing at the mod's gate rather than
 * at their predecessor. So {@link #grant} registers its own revoke, and revokes exactly the criteria
 * it awarded rather than all of them: an advancement the player had already earned legitimately must
 * survive this scene.
 */
public final class Advancements {

    private final SceneContext ctx;

    /** Advancements this facet has already arranged to undo, so a scene granting the same one twice
     *  does not stack two cleanups. */
    private final Set<ResourceLocation> undoing = new LinkedHashSet<>();

    Advancements(SceneContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Award every criterion this player has not yet met, completing the advancement.
     *
     * <p>Criterion-by-criterion rather than through {@code /advancement grant}: the command path
     * goes through Brigadier and a selector, which on a dedicated server with no player resolves to
     * nothing and reports success. This route has a player in hand and fails loudly without one.
     */
    public Advancements grant(String advancementId) {
        ServerPlayer player = ctx.player();
        AdvancementHolder holder = require(advancementId);
        PlayerAdvancements progress = player.getAdvancements();

        // Snapshot BEFORE awarding: these are exactly the criteria this scene is responsible for,
        // and the ones teardown must take back. Criteria the player already had are not in this list
        // and are therefore left alone.
        List<String> awarded = new ArrayList<>();
        for (String criterion : progress.getOrStartProgress(holder).getRemainingCriteria()) {
            awarded.add(criterion);
        }
        for (String criterion : awarded) {
            progress.award(holder, criterion);
        }
        if (undoing.add(holder.id())) {
            ctx.cleanup(() -> {
                for (String criterion : awarded) progress.revoke(holder, criterion);
            });
        }
        return this;
    }

    /** Take every criterion back, whether or not this scene was the one that awarded them. For a
     *  scene whose subject IS the locked state — it has to be able to get there from a world where
     *  something else already unlocked it. */
    public Advancements revoke(String advancementId) {
        ServerPlayer player = ctx.player();
        AdvancementHolder holder = require(advancementId);
        PlayerAdvancements progress = player.getAdvancements();
        List<String> completed = new ArrayList<>();
        for (String criterion : progress.getOrStartProgress(holder).getCompletedCriteria()) {
            completed.add(criterion);
        }
        for (String criterion : completed) progress.revoke(holder, criterion);
        return this;
    }

    /** Whether the player has completed this advancement. */
    public boolean has(String advancementId) {
        ServerPlayer player = ctx.player();
        return progressOf(player, advancementId).isDone();
    }

    /** Criteria still outstanding — what a partially-earned advancement is still waiting for. */
    public List<String> remaining(String advancementId) {
        ServerPlayer player = ctx.player();
        List<String> out = new ArrayList<>();
        for (String criterion : progressOf(player, advancementId).getRemainingCriteria()) out.add(criterion);
        return out;
    }

    /** Criteria already met. */
    public List<String> completed(String advancementId) {
        ServerPlayer player = ctx.player();
        List<String> out = new ArrayList<>();
        for (String criterion : progressOf(player, advancementId).getCompletedCriteria()) out.add(criterion);
        return out;
    }

    /**
     * Whether this advancement is registered at all — a question about the PACK, not about the
     * player.
     *
     * <p>Deliberately does not need a player, so a datapack-integrity scene ("every advancement my
     * quest book references still exists") runs on a bare dedicated server too.
     */
    public boolean registered(String advancementId) {
        ResourceLocation id = Ids.location(advancementId, "advancement");
        return ctx.server().getAdvancements().get(id) != null;
    }

    /**
     * The advancement this one hangs off, or {@code ""} when it is a root.
     *
     * <p>Progression in Minecraft is a tree, and the tree IS the design: which boss opens which
     * tier is stated nowhere except in these parent links. Without this a suite can only assert
     * that a list of ids exists, which stays green through exactly the change that matters most —
     * a tier reparented onto the wrong predecessor, so the mod is still complete and no longer
     * gated. Asserting the shape catches that; asserting the membership does not.
     *
     * <p>Empty string rather than null for the root, so a JS scene can compare it without having to
     * know whether Rhino hands null back as {@code null} or as {@code undefined}.
     */
    public String parentOf(String advancementId) {
        AdvancementHolder holder = require(advancementId);
        return holder.value().parent().map(ResourceLocation::toString).orElse("");
    }

    /** Every advancement id registered in this run, sorted. The raw material for an audit scene over
     *  a pack nobody has read all of. */
    public List<String> all() {
        List<String> out = new ArrayList<>();
        for (AdvancementHolder holder : ctx.server().getAdvancements().getAllAdvancements()) {
            out.add(holder.id().toString());
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Registered advancement ids under a namespace, sorted — e.g. every {@code twilightforest:} one. */
    public List<String> allIn(String namespace) {
        List<String> out = new ArrayList<>();
        for (AdvancementHolder holder : ctx.server().getAdvancements().getAllAdvancements()) {
            if (holder.id().getNamespace().equals(namespace)) out.add(holder.id().toString());
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Wait until the player earns this advancement, then run the continuation.
     *
     * <p>For the half of progression that is genuinely earned rather than granted — the scene does
     * the thing, and this is how it waits for the mod to notice. Registers an ordinary await step,
     * so the tick accounting is the same as every other wait in a scene.
     */
    public void awaitEarned(String advancementId, int withinTicks, Runnable then) {
        ctx.await(() -> has(advancementId)).within(withinTicks).then(then);
    }

    // ---- internals ----

    /** Resolve an id against the pack, loudly. Asks nothing of a player, so the reads that are
     *  about the DATA rather than about progress stay runnable on a bare dedicated server. */
    private AdvancementHolder require(String advancementId) {
        ResourceLocation id = Ids.location(advancementId, "advancement");
        AdvancementHolder holder = ctx.server().getAdvancements().get(id);
        if (holder == null) {
            throw new SceneFailure("no advancement is registered as '" + id + "'" + near(id));
        }
        return holder;
    }

    private AdvancementProgress progressOf(ServerPlayer player, String advancementId) {
        return player.getAdvancements().getOrStartProgress(require(advancementId));
    }

    /**
     * Registered advancements in the same namespace, as a message suffix.
     *
     * <p>Namespace rather than fuzzy path match, unlike {@link Ids}: advancement ids are structured
     * ({@code twilightforest:progress_naga}) and an author who got the namespace right and the path
     * wrong is best served by seeing that mod's actual ladder. An empty list is itself the answer —
     * the mod is not in this run.
     */
    private String near(ResourceLocation id) {
        List<String> sameMod = new ArrayList<>();
        for (AdvancementHolder holder : ctx.server().getAdvancements().getAllAdvancements()) {
            if (holder.id().getNamespace().equals(id.getNamespace())) sameMod.add(holder.id().toString());
        }
        if (sameMod.isEmpty()) {
            return " — and nothing at all is registered under '" + id.getNamespace()
                    + "', so that mod is not in this run";
        }
        sameMod.sort(String::compareTo);
        int shown = Math.min(sameMod.size(), 8);
        return " — '" + id.getNamespace() + "' registers " + sameMod.size() + ", including "
                + String.join(", ", sameMod.subList(0, shown));
    }
}
