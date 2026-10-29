package net.magicterra.stagewright.test.methods;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/** {@code mc.test.mods}: the mods this game loaded, as its loader lists them. */
public final class ModsContracts {
    private ModsContracts() {}

    @Tag("op") @Named("TestModsParams")
    public sealed interface ModsOp permits ListMods, Has {}

    @Variant("list")
    public record ListMods() implements ModsOp {}

    @Variant("has")
    public record Has(@Doc("Mod id, e.g. worlddriver.") @Length(min = 1, max = 128) String id) implements ModsOp {}

    /** One loaded mod. */
    @Named("TestMod")
    public record Mod(
            @Length(max = 128) String id,
            @Doc("As the loader reports it.") @Length(max = 256) String version) {}

    @Tag("op") @Named("TestModsReply")
    public sealed interface ModsReply permits ModList, Present {}

    @Variant("list")
    public record ModList(
            @Doc("Mods loaded, the loader, Minecraft and Java included where the loader lists them.")
            @Range(min = 0) int count,
            @Doc("Each one, sorted by id.") @Length(max = 10000) List<Mod> mods) implements ModsReply {}

    @Variant("has")
    public record Present(
            boolean has,
            @Doc("Its version; absent when it is not loaded.") Optional<@Length(max = 256) String> version)
            implements ModsReply {}

    public static final UnionType<ModsOp> MODS_PARAMS = ApiTypes.union(ModsOp.class);

    public static final UnionType<ModsReply> MODS_REPLY = ApiTypes.union(ModsReply.class);

    public static final MethodSpec<ModsOp, ModsReply> MODS = MethodSpec.of("mc.test.mods", MODS_PARAMS, MODS_REPLY,
            "list names every mod this game loaded, with its version; has says whether one mod id is loaded.")
            .hidden();
}
