package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What earlier versions of StageWright copied into a run directory's {@code mods/} folder.
 *
 * <p>Nothing copies anything there any more: the CLI hands its jars to the loader
 * ({@code -Dfabric.addMods}, {@code --fml.mods}), because its game directory may be a player's own
 * {@code .minecraft}. What is left is the copies older versions made, recorded by name in
 * {@link #LEDGER}. The CLI only names them ({@link #ledgered}), because a name is all the ledger kept,
 * and a pack may since have put its own jar under it.
 */
public final class ModInstall {

    /** Where earlier versions recorded what they put in mods/. */
    private static final String LEDGER = ".stagewright-installed";

    private ModInstall() {}

    /** The files an earlier version's ledger names that are still in {@code gameDir/mods}. */
    public static List<Path> ledgered(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return List.of();
        try {
            return previouslyInstalled(mods).stream().filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the ledger in " + mods, e);
        }
    }

    /** The files the ledger says an earlier install put in mods/. */
    private static Set<Path> previouslyInstalled(Path mods) throws IOException {
        Set<Path> ours = new LinkedHashSet<>();
        Path ledger = mods.resolve(LEDGER);
        if (!Files.isRegularFile(ledger)) return ours;
        for (String name : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            Path old = mods.resolve(trimmed);
            // A name carrying a separator would escape mods/; refuse rather than name it.
            if (!old.getParent().equals(mods)) continue;
            ours.add(old);
        }
        return ours;
    }
}
