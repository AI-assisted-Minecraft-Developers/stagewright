package net.magicterra.stagewright.capability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import net.magicterra.stagewright.test.Game;
import org.opentest4j.TestAbortedException;

/**
 * The capabilities a game offers, as {@link Game#capabilities()} answers them. The providers are discovered once per
 * JVM, on first use, by whichever test thread gets there first.
 *
 * <h2>Discovery cannot be allowed to fail</h2>
 *
 * <p>An adapter written against Curios imports Curios' classes, so in a runtime without Curios it throws
 * {@link NoClassDefFoundError} the moment {@code ServiceLoader} instantiates it. That is why this iterates
 * {@link ServiceLoader#stream()} and wraps each {@code get()}: a provider that cannot load is recorded as absent with
 * the reason, and its neighbours are unaffected.
 */
public final class Capabilities {

    /** The descriptors StageWright ships — the auto-detected ones, needing no configuration. */
    private static final String BUILT_IN_RESOURCE = "/data/stagewright/capabilities.json";

    /**
     * What discovery found: providers that loaded, by name in discovery order, and why each other one failed. A list
     * rather than a map by name: that name comes from the instance that never got built.
     */
    private record Found(Map<String, CapabilityProvider> providers, List<String> loadFailures) {}

    private static volatile Found found;

    private final Game game;

    private Capabilities(Game game) {
        this.game = game;
    }

    /** The capabilities {@code game} offers. */
    public static Capabilities in(Game game) {
        return new Capabilities(game);
    }

    /** Names this game offers, sorted — worth recording beside an aborted test. */
    public List<String> available() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, CapabilityProvider> e : found().providers().entrySet()) {
            if (e.getValue().availableIn(game)) out.add(e.getKey());
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Names of every provider that LOADED, available or not, sorted.
     *
     * <p>Not the same question as {@link #available()}: a runtime where discovery found nothing and one where it found
     * providers whose mods are absent answer "no" to every {@link #has} alike, so a suite asking only that stays green
     * through a dropped service file. Nothing registered is a broken framework, not an absent mod.
     */
    public List<String> registered() {
        List<String> out = new ArrayList<>(found().providers().keySet());
        out.sort(String::compareTo);
        return out;
    }

    /** Whether this game offers the capability, for a test that wants to branch rather than abort. */
    public boolean has(String name) {
        CapabilityProvider provider = found().providers().get(name);
        return provider != null && provider.availableIn(game);
    }

    /**
     * The provider of an available capability, for a {@link Game} implementation to build and cache its facet.
     *
     * @throws TestAbortedException naming what is missing when it is not available: a mod that is not installed is not
     *     the pack's defect, but it must not read as a pass either
     */
    public CapabilityProvider require(String name) {
        CapabilityProvider provider = found().providers().get(name);
        if (provider == null || !provider.availableIn(game)) throw new TestAbortedException(reasonFor(name, provider));
        return provider;
    }

    /**
     * Why a capability could not be handed over. Three different situations: the provider is here and says no (the mod
     * is absent — normal); the provider is not here but others are (the name is wrong, or that adapter did not ship);
     * nothing is here at all (the framework's own service file did not make it into the jar).
     */
    private String reasonFor(String name, CapabilityProvider provider) {
        if (provider != null) return provider.absentReason();
        Found f = found();
        String head = "this test needs the '" + name + "' capability, which nothing in this run offers";
        String failures = f.loadFailures().isEmpty()
                ? ""
                : ". " + f.loadFailures().size()
                        + " capability adapter(s) failed to load, and any of them may be the one that offers it: "
                        + String.join("; ", f.loadFailures());
        if (f.providers().isEmpty()) {
            return head
                    + (f.loadFailures().isEmpty()
                            ? " — and NO capability provider loaded at all, not even the ones StageWright ships, so this is"
                                    + " the framework's service file missing from the jar rather than a mod missing from the pack"
                            : " — and no capability provider loaded at all" + failures);
        }
        List<String> present = available();
        return head + " — registered here: " + String.join(", ", registered())
                + (present.isEmpty()
                        ? ", none of them available in this runtime"
                        : "; available: " + String.join(", ", present))
                + failures;
    }

    private static Found found() {
        Found f = found;
        if (f != null) return f;
        synchronized (Capabilities.class) {
            if (found == null) found = discover();
            return found;
        }
    }

    private static Found discover() {
        Map<String, CapabilityProvider> providers = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();

        for (ServiceLoader.Provider<CapabilityProvider> handle :
                ServiceLoader.load(CapabilityProvider.class).stream().toList()) {
            String className = handle.type().getName();
            CapabilityProvider provider;
            try {
                provider = handle.get();
            } catch (Throwable t) {
                // Throwable, not Exception: the expected failure is NoClassDefFoundError. Only the cause of what
                // ServiceLoader wraps names the class that was missing.
                Throwable cause = t;
                while (cause instanceof ServiceConfigurationError && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                failed.add("the capability adapter " + className + " could not load in this runtime ("
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                        + "), which normally means the mod it adapts is not installed here");
                continue;
            }
            String name = provider.name();
            CapabilityProvider clash = providers.put(name, provider);
            if (clash != null) {
                throw new IllegalStateException("two capability providers both claim the name '" + name + "': "
                        + clash.getClass().getName() + " and " + className + " — one silently wins, and a test asking"
                        + " for it then tests whichever loaded second. Third-party names must be '<modid>:<what>'.");
            }
        }

        // Descriptors after the ServiceLoader pass, and pack files after built-ins: later wins here, unlike the clash
        // above, so a pack whose fork moved a class can correct a built-in without waiting for a release.
        for (CapabilityDescriptor descriptor : builtInDescriptors()) providers.put(descriptor.name(), descriptor);
        for (CapabilityDescriptor descriptor : packDescriptors()) providers.put(descriptor.name(), descriptor);

        // Never written after this, so the threads that read it through the volatile need no further guard.
        return new Found(java.util.Collections.unmodifiableMap(providers), List.copyOf(failed));
    }

    /**
     * From one resource rather than a directory: enumerating a resource folder works from an exploded build and not
     * from a jar.
     */
    private static List<CapabilityDescriptor> builtInDescriptors() {
        try (InputStream in = Capabilities.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (in == null) return List.of();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parseAll(JsonParser.parseReader(reader), BUILT_IN_RESOURCE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + BUILT_IN_RESOURCE + " out of the StageWright jar", e);
        }
    }

    /** The pack's own, in name order so a collision between two files resolves the same way on every machine. */
    private static List<CapabilityDescriptor> packDescriptors() {
        Path dir = PackFiles.capabilities();
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
            String source = file.toString();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                out.addAll(parseAll(JsonParser.parseReader(reader), source));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read the capability descriptor " + source, e);
            }
        }
        return out;
    }

    /** One descriptor or an array of them. */
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
            throw new IllegalStateException(source + " must be a capability object or an array of them");
        }
        return out;
    }

    /** Forget the discovered set, for tests that install providers dynamically. */
    static void reset() {
        synchronized (Capabilities.class) {
            found = null;
        }
    }
}
