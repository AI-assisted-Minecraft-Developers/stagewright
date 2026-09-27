package net.magicterra.stagewright.cli.install;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.magicterra.stagewright.cli.install.Downloader.Artifact;

/**
 * Installs a client — vanilla, then its loader, then everything the resulting version names — into a
 * directory laid out the way the official launcher lays out {@code .minecraft}.
 *
 * <p>Idempotent: a second install of the same {@link ClientSpec} verifies what is there and fetches
 * nothing. Installs into one directory are serialised on {@code .stagewright.lock}, because two runs
 * starting together would otherwise both run NeoForge's installer into the same libraries.
 */
public final class Installer {

    private static final URI MANIFEST =
            URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final String LIBRARIES = "https://libraries.minecraft.net/";
    private static final String ASSETS = "https://resources.download.minecraft.net/";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/";
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/";
    private static final String STAGING_PREFIX = ".stagewright-neoforge-";

    private final Path installDir;
    private final Downloader downloader;
    private final HmclCache cache;
    private final String javaBinary;
    private final Consumer<String> log;

    public Installer(Path installDir, Downloader downloader, String javaBinary, Consumer<String> log) {
        // Absolute: NeoForge's installer runs with its own working directory, where a relative path
        // to its jar or its target resolves to nothing.
        this.installDir = installDir.toAbsolutePath().normalize();
        this.downloader = downloader;
        this.cache = new HmclCache(this.installDir);
        this.javaBinary = javaBinary;
        this.log = log;
    }

    /** Install {@code spec} and return the version id to launch. */
    public String install(ClientSpec spec) throws IOException, InterruptedException {
        Files.createDirectories(installDir);
        try (FileChannel channel = FileChannel.open(installDir.resolve(".stagewright.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lock(channel)) {
            JsonObject vanilla = vanilla(spec.minecraft());
            String id = switch (spec.loader()) {
                case "neoforge" -> neoforge(spec);
                case "fabric" -> fabric(spec);
                default -> spec.minecraft();
            };
            List<JsonObject> chain = Versions.chain(installDir, id);
            JsonObject root = chain.get(chain.size() - 1);
            String rootId = root.get("id").getAsString();
            if (!spec.minecraft().equals(rootId)) {
                throw new IllegalArgumentException(spec + " is built on Minecraft " + rootId + ", not "
                        + spec.minecraft() + " — fix the Minecraft version in --client");
            }
            libraries(chain);
            assets(root);
            if (!id.equals(spec.minecraft())) gameJar(id, vanilla);
            log.accept("client " + spec + " ready as " + id + " in " + installDir);
            return id;
        }
    }

    private FileLock lock(FileChannel channel) throws IOException {
        FileLock lock = channel.tryLock();
        if (lock != null) return lock;
        log.accept("another stagewright is installing into " + installDir + " — waiting for it");
        return channel.lock();
    }

    private JsonObject vanilla(String minecraft) throws IOException {
        Path json = Versions.json(installDir, minecraft);
        JsonObject entry = null;
        try {
            JsonObject manifest = JsonParser.parseString(downloader.text(MANIFEST)).getAsJsonObject();
            for (JsonElement element : manifest.getAsJsonArray("versions")) {
                JsonObject version = element.getAsJsonObject();
                if (minecraft.equals(version.get("id").getAsString())) entry = version;
            }
            if (entry == null) {
                throw new IllegalArgumentException("Minecraft " + minecraft
                        + " is not in Mojang's version manifest");
            }
        } catch (IOException e) {
            // Offline with the version already here is a rerun, not a failure.
            if (!Files.isRegularFile(json)) throw e;
            log.accept("cannot reach Mojang's version manifest (" + e.getMessage()
                    + ") — using the " + minecraft + " already installed");
        }
        // Checked against the manifest's sha1 rather than trusted by name: Mojang rewrites old version
        // files in place (1.21.1's changed on 2026-09-22), and the official launcher replaces them.
        if (entry != null) {
            downloader.fetch(new Artifact(URI.create(entry.get("url").getAsString()), json,
                    entry.get("sha1").getAsString(), -1, false, false));
        }
        JsonObject data = Versions.read(json);
        JsonObject client = data.getAsJsonObject("downloads").getAsJsonObject("client");
        downloader.fetch(artifact(client, Versions.jar(installDir, minecraft), false, true));
        return data;
    }

    /**
     * NeoForge's own installer, run as a child process — its processors (deobfuscation, binary
     * patches) are the part of an install that is actually hard, and it already does them.
     *
     * <p>Run into a staging directory inside the install directory, never into the install directory
     * itself: the installer refuses to run without a {@code launcher_profiles.json} and ADDS a profile
     * to the one it finds, which in a real {@code .minecraft} would put a NeoForge entry into the
     * user's own launcher. Staging inside rather than under the system temp directory keeps the
     * vanilla files hard-linkable into it and the finished libraries renamable out of it.
     */
    private String neoforge(ClientSpec spec) throws IOException, InterruptedException {
        String version = spec.loaderVersion();
        String id = spec.versionId();
        // The patched client jar is a processor output: no version file names it, so its presence is
        // what says a previous install ran to the end rather than dying mid-processor.
        Path patched = installDir.resolve("libraries/net/neoforged/neoforge/" + version
                + "/neoforge-" + version + "-client.jar");
        if (Files.isRegularFile(Versions.json(installDir, id)) && Files.isRegularFile(patched)) {
            return id;
        }

        URI url = URI.create(NEOFORGE_MAVEN + "net/neoforged/neoforge/" + version
                + "/neoforge-" + version + "-installer.jar");
        String sha1;
        try {
            sha1 = sidecar(url);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("NeoForge " + version + " does not exist — no " + url);
        }

        // A failed install leaves its staging directory to be read; under the lock, nothing else is
        // using one, so the next install is where it goes.
        try (Stream<Path> stale = Files.list(installDir)) {
            for (Path old : stale.filter(p -> p.getFileName().toString()
                    .startsWith(STAGING_PREFIX)).toList()) {
                deleteTree(old);
            }
        }
        Path staging = Files.createTempDirectory(installDir, STAGING_PREFIX);
        boolean done = false;
        try {
            Path installer = staging.resolve("neoforge-" + version + "-installer.jar");
            downloader.fetch(new Artifact(url, installer, sha1, -1, false, true));
            Files.writeString(staging.resolve("launcher_profiles.json"), "{}", StandardCharsets.UTF_8);
            // Shared rather than fetched again: the installer skips files that are already in place.
            String minecraft = spec.minecraft();
            HmclCache.place(Versions.json(installDir, minecraft), Versions.json(staging, minecraft));
            HmclCache.place(Versions.jar(installDir, minecraft), Versions.jar(staging, minecraft));

            Path installerLog = staging.resolve("installer.log");
            List<String> command = new ArrayList<>(List.of(javaBinary));
            command.addAll(Downloader.proxyJvmArgs(System.getenv()));
            command.addAll(List.of("-jar", installer.toString(), "--install-client", staging.toString()));
            log.accept("running NeoForge " + version + "'s installer — it fetches its libraries and"
                    + " patches the game, a minute or two the first time");
            Process process = new ProcessBuilder(command)
                    .directory(staging.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(installerLog.toFile())
                    .start();
            int code = process.waitFor();
            if (code != 0 || !Files.isRegularFile(Versions.json(staging, id))) {
                throw new IOException("NeoForge's installer failed (exit " + code + ") — its log is "
                        + installerLog + "; the staging directory is kept until the next install");
            }

            merge(staging.resolve("libraries"), installDir.resolve("libraries"));
            // Last, so a version file in the install directory never names a library that is not.
            HmclCache.place(Versions.json(staging, id), Versions.json(installDir, id));
            done = true;
            return id;
        } finally {
            if (done) deleteTree(staging);
        }
    }

    /** Move every file the installer produced into the install directory, keeping identical ones. */
    private void merge(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(from)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            Path target = to.resolve(from.relativize(file).toString());
            String sha1 = Sha1.of(file);
            if (!Sha1.matches(target, sha1, Files.size(file))) {
                Files.createDirectories(target.getParent());
                HmclCache.moveOver(file, target);
            }
            cache.store(target, sha1);
        }
    }

    /** Fabric publishes the finished version file itself; its libraries are ordinary downloads. */
    private String fabric(ClientSpec spec) throws IOException {
        URI url = URI.create(FABRIC_META + spec.minecraft() + "/" + spec.loaderVersion() + "/profile/json");
        String profile;
        try {
            profile = downloader.text(url);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("Fabric has no loader " + spec.loaderVersion()
                    + " for Minecraft " + spec.minecraft() + " — no " + url);
        }
        String id = JsonParser.parseString(profile).getAsJsonObject().get("id").getAsString();
        Path json = Versions.json(installDir, id);
        byte[] bytes = profile.getBytes(StandardCharsets.UTF_8);
        if (!Sha1.matches(json, Sha1.of(bytes), bytes.length)) {
            Files.createDirectories(json.getParent());
            Path temp = json.resolveSibling(json.getFileName() + ".part");
            Files.write(temp, bytes);
            HmclCache.moveOver(temp, json);
        }
        return id;
    }

    private void libraries(List<JsonObject> chain) throws IOException {
        List<Artifact> artifacts = new ArrayList<>();
        for (JsonObject library : Versions.libraries(chain)) {
            String name = library.get("name").getAsString();
            Path target = Versions.libraryPath(installDir, name);
            JsonObject downloads = library.getAsJsonObject("downloads");
            if (downloads != null) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                // A natives-only entry from before 1.19; nothing on the classpath comes from it.
                if (artifact == null) continue;
                if (!artifact.has("url") || artifact.get("url").getAsString().isBlank()) {
                    // Produced locally by a loader's installer; there is nowhere to fetch it from.
                    if (!Sha1.matches(target, string(artifact, "sha1"), size(artifact))) {
                        throw new IOException(name + " should have been produced by the loader's"
                                + " installer and is not at " + target);
                    }
                    continue;
                }
                artifacts.add(artifact(artifact, target, false, true));
            } else {
                // Fabric's shape: a repository root and a coordinate. asm and mixin carry a sha1;
                // fabric-loader and intermediary do not, so theirs comes from the maven sidecar.
                String base = library.has("url") ? library.get("url").getAsString() : LIBRARIES;
                if (!base.endsWith("/")) base += "/";
                URI url = URI.create(base + Versions.mavenPath(name));
                String sha1 = string(library, "sha1");
                if (sha1 == null && !Files.isRegularFile(target)) sha1 = sidecarOrNull(url);
                artifacts.add(new Artifact(url, target, sha1, size(library), false, true));
            }
        }
        int fetched = downloader.fetchAll(artifacts);
        log.accept("libraries: " + artifacts.size() + " in place, " + fetched + " downloaded");
    }

    private void assets(JsonObject root) throws IOException {
        JsonObject index = root.getAsJsonObject("assetIndex");
        Path indexFile = installDir.resolve("assets/indexes/" + index.get("id").getAsString() + ".json");
        downloader.fetch(artifact(index, indexFile, false, false));
        List<Artifact> objects = new ArrayList<>();
        for (var entry : Versions.read(indexFile).getAsJsonObject("objects").entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = object.get("hash").getAsString();
            String relative = hash.substring(0, 2) + "/" + hash;
            objects.add(new Artifact(URI.create(ASSETS + relative),
                    installDir.resolve("assets/objects/" + relative), hash,
                    object.get("size").getAsLong(), true, false));
        }
        int fetched = downloader.fetchAll(objects);
        log.accept("assets: " + objects.size() + " in place, " + fetched + " downloaded");
    }

    /**
     * The launched version's own copy of the game jar.
     *
     * <p>NeoForge's JVM arguments carry {@code -DignoreList=client-extra,${version_name}.jar}, which keeps
     * exactly a jar named after the LAUNCHED version off the module path. Put {@code 1.21.1.jar} on the
     * classpath instead and it becomes an automatic module exporting the same packages as NeoForge's
     * patched {@code minecraft} module — a ResolutionException naming some JFR event package.
     */
    private void gameJar(String id, JsonObject vanilla) throws IOException {
        JsonObject client = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
        Path target = Versions.jar(installDir, id);
        if (!Sha1.matches(target, client.get("sha1").getAsString(), client.get("size").getAsLong())) {
            HmclCache.place(Versions.jar(installDir, vanilla.get("id").getAsString()), target);
        }
    }

    private String sidecar(URI url) throws IOException {
        return downloader.text(URI.create(url + ".sha1")).trim().split("\\s+")[0];
    }

    private String sidecarOrNull(URI url) throws IOException {
        try {
            return sidecar(url);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    private static Artifact artifact(JsonObject download, Path target, boolean trustSize,
                                     boolean cacheable) {
        return new Artifact(URI.create(download.get("url").getAsString()), target,
                string(download, "sha1"), size(download), trustSize, cacheable);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).getAsString().isBlank()
                ? object.get(key).getAsString() : null;
    }

    private static long size(JsonObject object) {
        return object.has("size") ? object.get("size").getAsLong() : -1;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
