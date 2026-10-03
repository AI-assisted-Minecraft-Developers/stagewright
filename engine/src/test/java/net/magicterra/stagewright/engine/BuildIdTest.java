package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What makes two runs count as having tested the same code. */
class BuildIdTest {

    @Test
    void aTrackedEditChangesTheIdAndAnUntrackedFileDoesNot(@TempDir Path repo) throws Exception {
        assumeTrue(git(repo, "init", "-q") == 0, "git is not available");
        Files.writeString(repo.resolve("Scene.java"), "class Scene {}");
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "c"));

        String clean = BuildId.ofGitWorkTree(repo);
        assertTrue(clean.startsWith("git:") && !clean.contains("+"), clean);

        // A run directory nobody ignored fills up while the suite runs; that is not new code.
        Files.writeString(repo.resolve("stagewright-results.jsonl"), "{}");
        assertEquals(clean, BuildId.ofGitWorkTree(repo));

        Files.writeString(repo.resolve("Scene.java"), "class Scene { int x; }");
        String edited = BuildId.ofGitWorkTree(repo);
        assertTrue(edited.startsWith(clean + "+"), edited);
    }

    @Test
    void anExternalDiffDriverCannotHideAnEdit(@TempDir Path repo) throws Exception {
        // `true` prints nothing: through it, a changed tree would read as clean.
        assumeTrue(git(repo, "init", "-q") == 0, "git is not available");
        assertEquals(0, git(repo, "config", "diff.external", "true"));
        Files.writeString(repo.resolve("Scene.java"), "class Scene {}");
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "c"));
        String clean = BuildId.ofGitWorkTree(repo);

        Files.writeString(repo.resolve("Scene.java"), "class Scene { int x; }");
        String edited = BuildId.ofGitWorkTree(repo);
        assertTrue(edited.startsWith(clean + "+"), edited);
    }

    @Test
    void twoDifferentEditsInsideASubmoduleHaveDifferentIds(@TempDir Path root) throws Exception {
        Path lib = committedRepo(root.resolve("lib"));
        Path repo = committedRepo(root.resolve("app"));
        assertEquals(0, git(repo, "submodule", "add", "-q", lib.toString(), "lib"));
        assertEquals(0, git(repo, "commit", "-qm", "sub"));
        // Configured to be ignored, so only the command line can bring it back.
        assertEquals(0, git(repo, "config", "diff.ignoreSubmodules", "all"));

        Files.writeString(repo.resolve("lib/Scene.java"), "class Scene { int one; }");
        String one = BuildId.ofGitWorkTree(repo);
        Files.writeString(repo.resolve("lib/Scene.java"), "class Scene { int two; }");
        String two = BuildId.ofGitWorkTree(repo);
        assertTrue(one.contains("+") && two.contains("+"), one + " / " + two);
        assertNotEquals(one, two);
    }

    @Test
    void aRelativeDiffConfigCannotHideAnEditOutsideTheProjectDirectory(@TempDir Path root) throws Exception {
        Path repo = committedRepo(root);
        Path project = Files.createDirectories(repo.resolve("project"));
        Files.writeString(project.resolve("Build.java"), "class Build {}");
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "project"));
        assertEquals(0, git(repo, "config", "diff.relative", "true"));
        String clean = BuildId.ofGitWorkTree(project);

        Files.writeString(repo.resolve("Scene.java"), "class Scene { int x; }");
        String edited = BuildId.ofGitWorkTree(project);
        assertTrue(edited.startsWith(clean + "+"), edited);
    }

    @Test
    void diffSettingsDoNotChangeTheIdOfOneChange(@TempDir Path root) throws Exception {
        // Each file below prints differently under one of the settings configured afterwards.
        Path repo = committedRepo(root.resolve("app"));
        Files.writeString(repo.resolve("Scene.java"), "a\n\nb\nc\nd\ne\nf\ng\nh\ni\nj\n");
        Files.writeString(repo.resolve("Old.java"), "class Old { int x; int y; int z; }\n");
        Files.writeString(repo.resolve("Szene-ü.txt"), "eins\n");
        Files.writeString(repo.resolve("histogram.txt"), "a\nb\nc\na\nb\nc\n");
        Files.writeString(repo.resolve("indent.txt"), "int b() {\n{\nint b() {\n  y();\n");
        Files.writeString(repo.resolve("big.txt"), "line\n".repeat(600));
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "files"));

        Files.writeString(repo.resolve("Scene.java"), "A\n\nb\nc\nd\ne\nf\ng\nh\ni\nJ\n");
        assertEquals(0, git(repo, "mv", "Old.java", "New.java"));
        Files.writeString(repo.resolve("Szene-ü.txt"), "zwei\n");
        Files.writeString(repo.resolve("histogram.txt"), "c\nb\na\nb\nc\n");
        Files.writeString(repo.resolve("indent.txt"), "int b() {\n{\nint b() {\nint b() {\n  y();\n");
        Files.writeString(repo.resolve("big.txt"), "line\n".repeat(300) + "changed\n" + "line\n".repeat(299));
        String plain = BuildId.ofGitWorkTree(repo);
        assertTrue(plain.contains("+"), plain);

        Path order = root.resolve("order");
        Files.writeString(order, "indent.txt\n");
        // A machine's own attributes binding a diff driver with an algorithm of its own.
        Path attributes = root.resolve("attributes");
        Files.writeString(attributes, "*.txt diff=local\n");
        for (String[] setting : new String[][] {
                {"diff.noprefix", "true"}, {"diff.mnemonicPrefix", "true"},
                {"diff.srcPrefix", "x/"}, {"diff.dstPrefix", "y/"},
                {"diff.context", "10"}, {"diff.interHunkContext", "5"},
                {"diff.algorithm", "histogram"}, {"diff.indentHeuristic", "false"},
                {"diff.renames", "false"}, {"diff.orderFile", order.toString()},
                {"diff.suppressBlankEmpty", "true"}, {"core.quotePath", "false"},
                {"core.abbrev", "12"}, {"core.attributesFile", attributes.toString()},
                {"diff.local.algorithm", "histogram"}, {"core.bigFileThreshold", "1k"}}) {
            assertEquals(0, git(repo, "config", setting[0], setting[1]));
        }
        assertEquals(plain, BuildId.ofGitWorkTree(repo));
    }

    @Test
    void aBinaryChangeGetsOneIdWhateverGitCompressesWith(@TempDir Path root) throws Exception {
        Path repo = committedRepo(root);
        byte[] bytes = new byte[4096];
        new java.util.Random(1).nextBytes(bytes);
        Files.write(repo.resolve("texture.bin"), bytes);
        assertEquals(0, git(repo, "add", "texture.bin"));
        assertEquals(0, git(repo, "commit", "-qm", "binary"));
        new java.util.Random(2).nextBytes(bytes);
        Files.write(repo.resolve("texture.bin"), bytes);
        assertEquals(0, git(repo, "config", "core.compression", "0"));
        String stored = BuildId.ofGitWorkTree(repo);
        assertEquals(0, git(repo, "config", "core.compression", "9"));
        assertEquals(stored, BuildId.ofGitWorkTree(repo));
        new java.util.Random(3).nextBytes(bytes);
        Files.write(repo.resolve("texture.bin"), bytes);
        assertNotEquals(stored, BuildId.ofGitWorkTree(repo));
    }

    @Test
    void outputLargerThanOneChunkIsHashedWhole() throws Exception {
        // Hashed a chunk at a time rather than read whole, so every chunk has to reach the digest.
        byte[] output = new byte[5_000_007];
        new java.util.Random(7).nextBytes(output);
        java.security.MessageDigest streamed = BuildId.digest();
        assertEquals(output.length,
                BuildId.hashingInto(streamed).read(new java.io.ByteArrayInputStream(output)));
        assertEquals(java.util.HexFormat.of().formatHex(BuildId.digest().digest(output)),
                java.util.HexFormat.of().formatHex(streamed.digest()));
    }

    private static Path committedRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        assumeTrue(git(repo, "init", "-q") == 0, "git is not available");
        Files.writeString(repo.resolve("Scene.java"), "class Scene {}");
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "c"));
        return repo;
    }

    @Test
    void aCommandThatHangsIsGivenUpOnAtTheTimeout(@TempDir Path dir) {
        long start = System.nanoTime();
        assertNull(BuildId.run(dir, Duration.ofMillis(500), List.of("sleep", "30")));
        long waitedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(waitedMs < 10_000, "waited " + waitedMs + " ms for a 500 ms timeout");
    }

    @Test
    void aProjectItsEnclosingRepositoryDoesNotTrackHasNoId(@TempDir Path repo) throws Exception {
        assumeTrue(git(repo, "init", "-q") == 0, "git is not available");
        Files.writeString(repo.resolve("notes.txt"), "x");
        Files.writeString(repo.resolve(".gitignore"), "project/\n");
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "c"));
        Path project = Files.createDirectories(repo.resolve("project"));
        Files.writeString(project.resolve("Scene.java"), "class Scene {}");
        assertNull(BuildId.ofGitWorkTree(project));
    }

    @Test
    void aRepositoryWithNoCommitYetHasNoId(@TempDir Path repo) throws Exception {
        assumeTrue(git(repo, "init", "-q") == 0, "git is not available");
        assertNull(BuildId.ofGitWorkTree(repo));
    }

    @Test
    void aDirectoryOutsideGitHasNoId(@TempDir Path dir) {
        assertNull(BuildId.ofGitWorkTree(dir));
    }

    /** Pinned so a machine that signs commits, or has no identity, cannot turn these red. */
    private static int git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "commit.gpgsign=false",
                "-c", "user.name=t", "-c", "user.email=t@t", "-c", "protocol.file.allow=always"));
        command.addAll(List.of(args));
        try {
            return new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            return -1;
        }
    }
}
