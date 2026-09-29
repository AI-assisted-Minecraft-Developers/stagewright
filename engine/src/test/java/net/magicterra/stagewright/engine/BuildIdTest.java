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

    @Test
    void anEditToAFileGitIsToldToOverlookStillChangesTheId(@TempDir Path root) throws Exception {
        // git diff takes such a file at its index content, so the edit alone would read as clean.
        Path repo = committedRepo(root);
        Files.writeString(repo.resolve("Assumed.java"), "class Assumed {}");
        Files.writeString(repo.resolve("Skipped.java"), "class Skipped {}");
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "more"));
        assertEquals(0, git(repo, "update-index", "--assume-unchanged", "Assumed.java"));
        assertEquals(0, git(repo, "update-index", "--skip-worktree", "Skipped.java"));
        String clean = BuildId.ofGitWorkTree(repo);
        assertTrue(clean.startsWith("git:") && !clean.contains("+"), "marked but unedited is clean: " + clean);

        Files.writeString(repo.resolve("Assumed.java"), "class Assumed { int x; }");
        String assumed = BuildId.ofGitWorkTree(repo);
        assertTrue(assumed.startsWith(clean + "+"), assumed);

        Files.writeString(repo.resolve("Skipped.java"), "class Skipped { int x; }");
        String skipped = BuildId.ofGitWorkTree(repo);
        assertTrue(skipped.startsWith(clean + "+"), skipped);
        assertNotEquals(assumed, skipped);
    }

    @Test
    void aProjectBelowTheTopOfItsWorkTreeTakesTheWholeTree(@TempDir Path root) throws Exception {
        Path repo = committedRepo(root);
        Path project = Files.createDirectories(repo.resolve("mods/scene"));
        Files.writeString(project.resolve("Mod.java"), "class Mod {}");
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "mod"));
        assertEquals(0, git(repo, "update-index", "--assume-unchanged", "Scene.java"));
        Files.writeString(repo.resolve("Scene.java"), "class Scene { int x; }");
        String fromTop = BuildId.ofGitWorkTree(repo);
        assertTrue(fromTop.contains("+"), fromTop);
        assertEquals(fromTop, BuildId.ofGitWorkTree(project));
    }

    @Test
    void deletingAFileGitIsToldToAssumeUnchangedChangesTheId(@TempDir Path root) throws Exception {
        Path repo = committedRepo(root);
        String clean = BuildId.ofGitWorkTree(repo);
        assertEquals(0, git(repo, "update-index", "--assume-unchanged", "Scene.java"));
        Files.delete(repo.resolve("Scene.java"));
        String deleted = BuildId.ofGitWorkTree(repo);
        assertTrue(deleted.startsWith(clean + "+"), deleted);
        Files.createDirectory(repo.resolve("Scene.java"));
        String replaced = BuildId.ofGitWorkTree(repo);
        assertTrue(replaced.startsWith(clean + "+"), replaced);
    }

    @Test
    void aSkipWorktreeFileASparseCheckoutLeftOutIsNotAnEdit(@TempDir Path root) throws Exception {
        Path repo = committedRepo(root);
        Files.writeString(repo.resolve("Elsewhere.java"), "class Elsewhere {}");
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "more"));
        String clean = BuildId.ofGitWorkTree(repo);
        assertEquals(0, git(repo, "sparse-checkout", "set", "--no-cone", "/Scene.java"));
        assertTrue(Files.notExists(repo.resolve("Elsewhere.java")));
        assertEquals(clean, BuildId.ofGitWorkTree(repo));
    }

    @Test
    void deletingASkipWorktreeFileOutsideASparseCheckoutChangesTheId(@TempDir Path root) throws Exception {
        Path repo = committedRepo(root);
        String clean = BuildId.ofGitWorkTree(repo);
        assertEquals(0, git(repo, "update-index", "--skip-worktree", "Scene.java"));
        Files.delete(repo.resolve("Scene.java"));
        String deleted = BuildId.ofGitWorkTree(repo);
        assertTrue(deleted.startsWith(clean + "+"), deleted);
    }

    @Test
    void aMarkedFileWhoseNameStartsWithAQuoteIsStillHashed(@TempDir Path root) throws Exception {
        // One name per line on stdin, git would read it as a quoted name and fail or hash another file.
        Path repo = committedRepo(root);
        Path quoted;
        try {
            quoted = Files.writeString(repo.resolve("\"quoted.txt"), "one");
        } catch (java.nio.file.InvalidPathException e) {
            assumeTrue(false, "this file system has no such name");
            return;
        }
        assertEquals(0, git(repo, "add", "."));
        assertEquals(0, git(repo, "commit", "-qm", "quoted"));
        assertEquals(0, git(repo, "update-index", "--assume-unchanged", "\"quoted.txt"));
        String clean = BuildId.ofGitWorkTree(repo);
        Files.writeString(quoted, "two");
        String edited = BuildId.ofGitWorkTree(repo);
        assertTrue(edited != null && edited.startsWith(clean + "+"), String.valueOf(edited));
    }

    @Test
    void anEditToAMarkedFileWhoseNameIsNotUtf8StillChangesTheId(@TempDir Path root) throws Exception {
        // Its name reaches git as the bytes git listed, never decoded on the way.
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "needs a shell and byte file names");
        Path repo = committedRepo(root);
        assertEquals(0, sh(repo, "f=$(printf 'caf\\351.cfg') && echo a > \"$f\" && git add -- \"$f\""
                + " && git -c user.name=t -c user.email=t@t -c commit.gpgsign=false commit -qm latin"
                + " && git update-index --assume-unchanged -- \"$f\""));
        String clean = BuildId.ofGitWorkTree(repo);
        assertTrue(clean != null && !clean.contains("+"), String.valueOf(clean));
        assertEquals(0, sh(repo, "echo b > \"$(printf 'caf\\351.cfg')\""));
        String edited = BuildId.ofGitWorkTree(repo);
        assertTrue(edited != null && edited.startsWith(clean + "+"), String.valueOf(edited));
    }

    @Test
    void aMarkedFileCommittedWithCrlfIsNotAnEditWhereAutocrlfIsOn(@TempDir Path root) throws Exception {
        // git diff leaves a file alone whose index content already has CRLF; so must the id.
        Path repo = committedRepo(root);
        assertEquals(0, git(repo, "config", "core.autocrlf", "false"));
        Files.writeString(repo.resolve("Windows.txt"), "one\r\ntwo\r\n");
        assertEquals(0, git(repo, "add", "Windows.txt"));
        assertEquals(0, git(repo, "commit", "-qm", "crlf"));
        String clean = BuildId.ofGitWorkTree(repo);
        assertEquals(0, git(repo, "config", "core.autocrlf", "true"));
        assertEquals(0, git(repo, "update-index", "--assume-unchanged", "Windows.txt"));
        assertEquals(clean, BuildId.ofGitWorkTree(repo));
    }

    @Test
    void aMarkedFileWhoseModeOrLinkTargetChangedChangesTheId(@TempDir Path root) throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "needs mode bits and symlinks");
        Path repo = committedRepo(root);
        Files.createSymbolicLink(repo.resolve("link"), Path.of("Scene.java"));
        assertEquals(0, git(repo, "add", "link"));
        assertEquals(0, git(repo, "commit", "-qm", "link"));
        assertEquals(0, git(repo, "update-index", "--assume-unchanged", "Scene.java", "link"));
        String clean = BuildId.ofGitWorkTree(repo);
        assertTrue(!clean.contains("+"), clean);

        assertTrue(repo.resolve("Scene.java").toFile().setExecutable(true));
        String executable = BuildId.ofGitWorkTree(repo);
        assertTrue(executable.startsWith(clean + "+"), executable);

        Files.delete(repo.resolve("link"));
        Files.createSymbolicLink(repo.resolve("link"), Path.of("elsewhere"));
        String relinked = BuildId.ofGitWorkTree(repo);
        assertTrue(relinked.startsWith(clean + "+") && !relinked.equals(executable), relinked);
    }

    @Test
    void takingTheIdLeavesTheRepositorysOwnMarksAlone(@TempDir Path root) throws Exception {
        Path repo = committedRepo(root);
        assertEquals(0, git(repo, "update-index", "--assume-unchanged", "Scene.java"));
        Files.writeString(repo.resolve("Scene.java"), "class Scene { int x; }");
        assertTrue(BuildId.ofGitWorkTree(repo).contains("+"));
        assertEquals("h Scene.java", new String(BuildId.run(repo, Duration.ofSeconds(30),
                List.of("git", "ls-files", "-v")), java.nio.charset.StandardCharsets.UTF_8).trim());
    }

    private static int sh(Path dir, String script) throws Exception {
        return new ProcessBuilder("sh", "-c", script).directory(dir.toFile()).inheritIO().start().waitFor();
    }

    @Test
    void aNameTheFileSystemCannotHoldResolvesToNothing(@TempDir Path root) {
        // Thrown instead, it would fail the run task rather than leave the id to what git can say.
        assertNull(BuildId.resolved(root, "a\0b"));
        assertEquals(root.resolve("a/b"), BuildId.resolved(root, "a/b"));
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
