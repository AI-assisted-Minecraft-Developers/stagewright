package net.magicterra.stagewright.scene;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Every {@link CapabilityProvider} this runtime offers, discovered once and shared by all scenes.
 *
 * <p>Resolved lazily on first use and then held, because {@link ServiceLoader} discovery is not free
 * and because the answer cannot change inside a run: a mod is either in this JVM or it is not.
 *
 * <h2>Discovery cannot be allowed to fail</h2>
 *
 * <p>An adapter written against Curios imports Curios' classes, so in a runtime without Curios it
 * throws {@link NoClassDefFoundError} the moment {@code ServiceLoader} instantiates it — and a plain
 * {@code for (CapabilityProvider p : ServiceLoader.load(...))} would take every other provider down
 * with it, including ones that would have worked. That is why this iterates
 * {@link ServiceLoader#stream()} and wraps each {@code get()}: a provider that cannot load is
 * recorded as absent with the reason, and its neighbours are unaffected.
 *
 * <p>Which also means an adapter can be written the natural, typed way. Not loading in a runtime
 * without its mod is the correct behaviour, not a failure to handle.
 */
final class Capabilities {

    /** The descriptors StageWright ships — the auto-detected ones, needing no configuration. */
    private static final String BUILT_IN_RESOURCE = "/data/stagewright/capabilities.json";

    /** Where a pack keeps its own, beside its scenes ({@code config/stagewright/scenes}). */
    private static final String PACK_DIR = "config/stagewright/capabilities";

    /** Providers that loaded, by name, in discovery order. */
    private static Map<String, CapabilityProvider> providers;

    /** Why each provider that did not load failed, so a skip can say more than "absent". A list
     *  rather than a map by capability name: that name comes from the instance that never got
     *  built, so a failure can only be shown to every lookup that finds no provider. */
    private static List<String> loadFailures;

    /** Facets already built for the scene currently running, so an adapter that registers a cleanup
     *  registers exactly one. Cleared whenever the context changes. */
    private final Map<String, Object> facets = new LinkedHashMap<>();

    private final SceneContext ctx;

    Capabilities(SceneContext ctx) {
        this.ctx = ctx;
    }

    /** Names this runtime offers, sorted — the raw material for a record that makes a skip
     *  traceable ("skipped because curios was absent; present: ftbquests"). */
    List<String> available() {
        discover();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, CapabilityProvider> e : providers.entrySet()) {
            if (e.getValue().availableIn(ctx)) out.add(e.getKey());
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Names of every provider that LOADED, available or not, sorted.
     *
     * <p>A different question from {@link #available()} and the two are easy to conflate — which is
     * the bug this exists to make visible. A runtime where discovery found nothing and a runtime
     * where it found two providers whose mods are absent both answer "no" to every
     * {@link #has} call, so a suite asking only that stays green through a dropped service file, a
     * resource a shadow merge swallowed, or a jar that shipped without its META-INF. Registered but
     * unavailable is a working framework reporting an absent mod; nothing registered is a broken
     * framework reporting the same thing.
     */
    List<String> registered() {
        discover();
        List<String> out = new ArrayList<>(providers.keySet());
        out.sort(String::compareTo);
        return out;
    }

    boolean has(String name) {
        discover();
        CapabilityProvider provider = providers.get(name);
        return provider != null && provider.availableIn(ctx);
    }

    /**
     * The facet for this capability, or a recorded skip naming what is missing.
     *
     * <p>Skip rather than fail, matching {@link SceneContext#player()} and the two shipped probes: a
     * mod not being installed is not the pack's defect, but it must not look like a pass either.
     */
    Object get(String name) {
        discover();
        CapabilityProvider provider = providers.get(name);
        if (provider == null || !provider.availableIn(ctx)) {
            ctx.skip(reasonFor(name, provider));
            return null;                      // unreachable: skip throws
        }
        return facets.computeIfAbsent(name, key -> provider.facet(ctx));
    }

    /**
     * Why a capability could not be handed over, in the terms whoever reads the results needs.
     *
     * <p>Three genuinely different situations, and collapsing any two of them wastes somebody's
     * afternoon: the provider is here and says no (the mod is absent — normal); the provider is not
     * here but others are (this name is wrong, or that adapter did not ship); nothing is here at all
     * (the framework's own service file did not make it into the jar, which is a StageWright bug
     * wearing an absent mod's clothes).
     */
    private String reasonFor(String name, CapabilityProvider provider) {
        if (provider != null) return provider.absentReason();

        String head = "this scene needs the '" + name + "' capability, which nothing in this run"
                + " offers";
        String failures = loadFailures.isEmpty() ? "" : ". " + loadFailures.size()
                + " capability adapter(s) failed to load, and any of them may be the one that offers"
                + " it: " + String.join("; ", loadFailures);
        if (providers.isEmpty()) {
            return head + (loadFailures.isEmpty()
                    ? " — and NO capability provider loaded at all, not even the ones StageWright"
                            + " ships, so this is the framework's service file missing from the jar"
                            + " rather than a mod missing from the pack"
                    : " — and no capability provider loaded at all" + failures);
        }
        List<String> present = available();
        return head + " — registered here: " + String.join(", ", registered())
                + (present.isEmpty()
                        ? ", none of them available in this runtime"
                        : "; available: " + String.join(", ", present))
                + failures;
    }

    /**
     * Load the providers once.
     *
     * <p>Not synchronized and not volatile on purpose: scene bodies run inline on the server thread,
     * which is the only thread that ever reaches this. A lock here would be protecting against a
     * caller that would already be violating the harness's own threading rule.
     */
    private static void discover() {
        if (providers != null) return;
        Map<String, CapabilityProvider> found = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();

        for (ServiceLoader.Provider<CapabilityProvider> handle
                : ServiceLoader.load(CapabilityProvider.class).stream().toList()) {
            String className = handle.type().getName();
            CapabilityProvider provider;
            try {
                provider = handle.get();
            } catch (Throwable t) {
                // Throwable, not Exception: the expected failure here is NoClassDefFoundError, an
                // Error. Catching Exception would let exactly the case this exists for through.
                // ServiceLoader wraps what the constructor threw, and only the cause names the class
                // that was missing.
                Throwable cause = t;
                while (cause instanceof ServiceConfigurationError && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                failed.add("the capability adapter " + className + " could not load in"
                        + " this runtime (" + cause.getClass().getSimpleName() + ": "
                        + cause.getMessage()
                        + "), which normally means the mod it adapts is not installed here");
                continue;
            }
            String name = provider.name();
            CapabilityProvider clash = found.put(name, provider);
            if (clash != null) {
                throw new IllegalStateException("two capability providers both claim the name '"
                        + name + "': " + clash.getClass().getName() + " and " + className
                        + " — one silently wins, and a scene asking for it then tests whichever"
                        + " loaded second. Third-party names must be '<modid>:<what>'.");
            }
        }

        // Descriptors after the ServiceLoader pass, and pack files after built-ins, because later
        // wins here — deliberately, unlike the clash above. A typed adapter beats a declared one
        // only if it shipped; a pack whose fork moved a class must be able to correct a built-in
        // without waiting for a release, and telling it "that name is taken" would leave it no move.
        for (CapabilityDescriptor descriptor : builtInDescriptors()) {
            found.put(descriptor.name(), descriptor);
        }
        for (CapabilityDescriptor descriptor : packDescriptors()) {
            found.put(descriptor.name(), descriptor);
        }

        loadFailures = failed;
        providers = found;
    }

    /** Descriptors StageWright ships, from one resource rather than a directory: enumerating a
     *  resource FOLDER on the classpath works from an exploded build and not from a jar, which is
     *  precisely the difference between a dev run and every user's. */
    private static List<CapabilityDescriptor> builtInDescriptors() {
        try (InputStream in = Capabilities.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (in == null) return List.of();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parseAll(JsonParser.parseReader(reader), BUILT_IN_RESOURCE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + BUILT_IN_RESOURCE
                    + " out of the StageWright jar", e);
        }
    }

    /** Descriptors the pack itself declared, beside its scenes. Name order, so a name collision
     *  between two pack files resolves the same way on every machine. */
    private static List<CapabilityDescriptor> packDescriptors() {
        Path dir = Path.of(PACK_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.list(dir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + dir.toAbsolutePath(), e);
        }
        files.sort(Path::compareTo);

        List<CapabilityDescriptor> out = new ArrayList<>();
        for (Path file : files) {
            String source = PACK_DIR + "/" + file.getFileName();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                out.addAll(parseAll(JsonParser.parseReader(reader), source));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read the capability descriptor " + source, e);
            }
        }
        return out;
    }

    /** One descriptor or an array of them — a pack keeping all of its own in one file is the normal
     *  case, and making it write one file each would be a rule with no reason behind it. */
    private static List<CapabilityDescriptor> parseAll(JsonElement root, String source) {
        List<CapabilityDescriptor> out = new ArrayList<>();
        if (root.isJsonObject()) {
            out.add(CapabilityDescriptor.parse((JsonObject) root, source));
        } else if (root.isJsonArray()) {
            for (JsonElement element : (JsonArray) root) {
                if (!element.isJsonObject()) {
                    throw new IllegalStateException(source + " must contain capability objects");
                }
                out.add(CapabilityDescriptor.parse((JsonObject) element, source));
            }
        } else {
            throw new IllegalStateException(source
                    + " must be a capability object or an array of them");
        }
        return out;
    }

    /** Forget the discovered set. For tests that install providers dynamically; never called by the
     *  harness, which wants exactly one discovery per JVM. */
    static void reset() {
        providers = null;
        loadFailures = null;
    }
}
