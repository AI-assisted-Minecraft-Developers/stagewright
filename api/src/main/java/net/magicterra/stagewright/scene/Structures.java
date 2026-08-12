package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Worldgen structures — is one registered, did one generate here, and where is the nearest.
 *
 * <p>Reached as {@link SceneContext#structures()}. A content mod's structures are usually where its
 * progression, its loot and its bosses all live, so "does the Naga Courtyard still generate" is a
 * more load-bearing question than any assertion about the blocks it is made of.
 *
 * <h2>{@link #locate} is expensive, and says so</h2>
 *
 * Finding the nearest structure scans and generates chunks. A scene body runs inline on the server
 * tick, so a locate that takes eight seconds has spent 160 ticks of everybody's budget — including
 * the budget of scenes that come after it. So this facet:
 *
 * <ul>
 *   <li>records how long every locate took, under {@code locate.<id>.ms}, whether it succeeded or
 *       not — a slow search that found nothing is exactly the one nobody would otherwise notice;</li>
 *   <li>takes an explicit chunk radius rather than defaulting to something generous, because the
 *       right radius is a property of the structure's spacing and the author knows it;</li>
 *   <li>answers {@code null} rather than failing when nothing is found. "This structure does not
 *       generate within N chunks" is a legitimate finding, and often the assertion itself.</li>
 * </ul>
 *
 * <p>{@link #generatedAt} is cheap by comparison — it asks the structure manager about chunks that
 * are already loaded — and is the right tool whenever the scene put itself where the structure
 * should be.
 */
public final class Structures {

    private final SceneContext ctx;

    Structures(SceneContext ctx) {
        this.ctx = ctx;
    }

    /** Whether this structure is registered in this run at all. Cheap: a registry lookup, no
     *  worldgen. The first thing to assert when a structure-shaped scene fails. */
    public boolean registered(String structureId) {
        return registry().containsKey(Ids.location(structureId, "structure"));
    }

    /** Every structure id registered in this run, sorted. */
    public List<String> all() {
        List<String> out = new ArrayList<>();
        registry().keySet().forEach(id -> out.add(id.toString()));
        out.sort(String::compareTo);
        return out;
    }

    /** Registered structure ids under one namespace, sorted — e.g. every {@code twilightforest:} one. */
    public List<String> allIn(String namespace) {
        List<String> out = new ArrayList<>();
        registry().keySet().forEach(id -> {
            if (id.getNamespace().equals(namespace)) out.add(id.toString());
        });
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Whether a structure of this kind covers an origin-relative position.
     *
     * <p>Reads the structure manager for chunks that are already loaded, so it is cheap and it does
     * NOT generate anything — which also means it only answers honestly inside the scene's forced
     * window. Outside it, "no" may mean "not generated yet" rather than "not there".
     */
    public boolean generatedAt(String structureId, int dx, int dy, int dz) {
        Structure structure = require(structureId);
        BlockPos pos = ctx.rel(dx, dy, dz);
        StructureStart start = ctx.level().structureManager().getStructureAt(pos, structure);
        return start != null && start.isValid();
    }

    /** The ids of every structure covering an origin-relative position — for a scene that wants to
     *  report what it landed in rather than guess what to ask about. */
    public List<String> at(int dx, int dy, int dz) {
        BlockPos pos = ctx.rel(dx, dy, dz);
        List<String> out = new ArrayList<>();
        Registry<Structure> registry = registry();
        for (ResourceLocation id : registry.keySet()) {
            Structure structure = registry.get(id);
            if (structure == null) continue;
            StructureStart start = ctx.level().structureManager().getStructureAt(pos, structure);
            if (start != null && start.isValid()) out.add(id.toString());
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * The nearest structure of this kind, as {@code "x,y,z"}, or {@code null} when none is found
     * within {@code chunkRadius}.
     *
     * <p>A string rather than a {@code BlockPos} so a JavaScript scene can read it — the rule on
     * {@link SceneContext#originX()} — and because the useful assertions about it are "was one
     * found" and "how far away", both of which {@link #distanceTo} answers directly.
     *
     * <p>Generates chunks. See the class documentation before reaching for this in a suite that
     * cares about its wall clock.
     */
    public String locate(String structureId, int chunkRadius) {
        BlockPos found = locatePos(structureId, chunkRadius);
        return found == null ? null : found.getX() + "," + found.getY() + "," + found.getZ();
    }

    /**
     * Distance from this scene's origin to the nearest structure of this kind, or {@code -1} when
     * none is found within {@code chunkRadius}.
     *
     * <p>{@code -1} rather than an exception because "there is none near here" is an answer a scene
     * often wants to assert, and because a structure that fails to generate is precisely the
     * regression this facet exists to catch — it must be reportable, not fatal.
     */
    public double distanceTo(String structureId, int chunkRadius) {
        BlockPos found = locatePos(structureId, chunkRadius);
        if (found == null) return -1;
        return Math.sqrt(ctx.origin().distSqr(found));
    }

    // ---- internals ----

    private BlockPos locatePos(String structureId, int chunkRadius) {
        Structure structure = require(structureId);
        if (chunkRadius < 1) {
            throw new SceneFailure("a locate radius must be at least 1 chunk, got " + chunkRadius);
        }
        Holder<Structure> holder = registry().wrapAsHolder(structure);
        long startedAt = System.currentTimeMillis();
        try {
            var found = ctx.level().getChunkSource().getGenerator().findNearestMapStructure(
                    ctx.level(), HolderSet.direct(holder), ctx.origin(), chunkRadius, false);
            return found == null ? null : found.getFirst();
        } finally {
            // Recorded on every path, success or not: a search that scanned for eight seconds and
            // found nothing has still spent 160 ticks of the suite's budget, and that cost is
            // invisible in a passing run unless it is written down.
            ctx.record("locate." + structureId + ".ms", System.currentTimeMillis() - startedAt);
        }
    }

    private Registry<Structure> registry() {
        return ctx.level().registryAccess().registryOrThrow(Registries.STRUCTURE);
    }

    private Structure require(String structureId) {
        ResourceLocation id = Ids.location(structureId, "structure");
        Structure structure = registry().get(id);
        if (structure == null) {
            List<String> sameMod = allIn(id.getNamespace());
            throw new SceneFailure("no structure is registered as '" + id + "'"
                    + (sameMod.isEmpty()
                        ? " — and nothing at all is registered under '" + id.getNamespace()
                          + "', so that mod is not in this run"
                        : " — '" + id.getNamespace() + "' registers " + sameMod.size() + ", including "
                          + String.join(", ", sameMod.subList(0, Math.min(sameMod.size(), 8)))));
        }
        return structure;
    }
}
