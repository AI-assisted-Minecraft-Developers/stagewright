package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The last-known-position line, exercised against hand-written heartbeats.
 *
 * <p>Everything this reads is produced by a process that has already died, in a situation nobody can
 * reproduce on demand — which is precisely why it is worth testing here instead of waiting for the
 * next wedge to find out whether it says anything useful. The cases that matter are the degenerate
 * ones: a heartbeat that is absent, empty, or half-written, because a run killed mid-write is
 * exactly the run this exists to describe.
 */
class ProgressTest {

    private static Map<String, Object> rec(String type) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        return r;
    }

    private static final List<Map<String, Object>> DIED = List.of(rec("suite"), rec("scene"));
    private static final List<Map<String, Object>> FINISHED = List.of(rec("suite"), rec("scene"), rec("done"));

    /** Writes a heartbeat beside a results file and returns the results path. */
    private static Path rig(Path dir, String heartbeat) throws IOException {
        Path results = dir.resolve("stagewright-results.jsonl");
        Files.writeString(results, "", StandardCharsets.UTF_8);
        if (heartbeat != null) {
            Files.writeString(dir.resolve(RunDirectory.PROGRESS_FILE), heartbeat, StandardCharsets.UTF_8);
        }
        return results;
    }

    private static String beat(String scene, long ticks, long inWindow, long windowMs) {
        return "{\"type\":\"progress\",\"scene\":\"" + scene + "\",\"ticks\":" + ticks
                + ",\"ticksInWindow\":" + inWindow + ",\"windowMs\":" + windowMs
                + ",\"atMs\":" + System.currentTimeMillis() + "}\n";
    }

    // ---- when it must stay quiet ---------------------------------------------------------------

    /**
     * The heartbeat outlives a healthy run, so this is the case that decides whether the feature is
     * an aid or a red herring: on a run that finished, the last scene it names has nothing to do
     * with whichever scene actually failed its assertions.
     */
    @Test
    void saysNothingWhenTheRunReachedItsFooter(@TempDir Path dir) throws IOException {
        Path results = rig(dir, beat("wd.lastSceneOfTheSuite", 500_000, 1_800, 90_000));
        assertEquals(Optional.empty(), Progress.lastKnownPosition(results, FINISHED));
    }

    @Test
    void saysNothingWhenNoHeartbeatWasWritten(@TempDir Path dir) throws IOException {
        Path results = rig(dir, null);
        assertEquals(Optional.empty(), Progress.lastKnownPosition(results, DIED));
    }

    /**
     * A truncating writer killed between opening the file and finishing the line leaves exactly
     * this. It must degrade to silence: the run is already failing, and a parser blowing up here
     * would replace a RED that names the real problem with a stack trace that names this class.
     */
    @Test
    void saysNothingWhenTheHeartbeatIsHalfWritten(@TempDir Path dir) throws IOException {
        assertEquals(Optional.empty(),
                Progress.lastKnownPosition(rig(dir, "{\"type\":\"progress\",\"sce"), DIED));
    }

    @Test
    void saysNothingWhenTheHeartbeatIsEmpty(@TempDir Path dir) throws IOException {
        assertEquals(Optional.empty(), Progress.lastKnownPosition(rig(dir, ""), DIED));
    }

    // ---- what it says when it speaks -----------------------------------------------------------

    @Test
    void namesTheSceneAndTheTick(@TempDir Path dir) throws IOException {
        String line = Progress.lastKnownPosition(rig(dir, beat("wd.journey09Iron", 41_234, 3, 90_000)), DIED)
                .orElseThrow();
        assertTrue(line.contains("wd.journey09Iron"), line);
        assertTrue(line.contains("41234"), line);
    }

    /**
     * The three health readings are the point of carrying the raw pair rather than a rate: they send
     * the reader to different halves of the process, and a quotient rounds the first two together.
     */
    @Test
    void distinguishesAStoppedWorldFromACrawlingOne(@TempDir Path dir) throws IOException {
        assertTrue(Progress.lastKnownPosition(rig(dir, beat("wd.a", 10, 0, 90_000)), DIED)
                .orElseThrow().contains("the world had stopped"));

        assertTrue(Progress.lastKnownPosition(rig(dir, beat("wd.b", 10, 3, 90_000)), DIED)
                .orElseThrow().contains("the world was crawling"));
    }

    /**
     * The reading that redirects an investigation. A healthy tick rate at the moment of death means
     * the run was killed by something that is not a stall — a wall-clock ceiling, an OOM, a kill from
     * outside — and the operator should stop looking for a deadlock.
     */
    @Test
    void saysSoWhenTheWorldWasHealthyAtTheEnd(@TempDir Path dir) throws IOException {
        String line = Progress.lastKnownPosition(rig(dir, beat("wd.c", 10, 1_800, 90_000)), DIED).orElseThrow();
        assertTrue(line.contains("it was not the tick"), line);
        assertFalse(line.contains("crawling"), line);
    }

    /** Between scenes is a real position, not a missing one. */
    @Test
    void reportsAnEmptySceneNameAsBetweenScenes(@TempDir Path dir) throws IOException {
        assertTrue(Progress.lastKnownPosition(rig(dir, beat("", 7, 0, 90_000)), DIED)
                .orElseThrow().contains("between scenes"));
    }

    /**
     * A clock that disagrees must not produce a plausible-looking lie. The heartbeat carries the
     * game process's wall clock; if it reads later than the verdict's, the elapsed figure is dropped
     * rather than reported as a negative or a huge number.
     */
    @Test
    void omitsTheElapsedFigureWhenTheHeartbeatIsFromTheFuture(@TempDir Path dir) throws IOException {
        String future = "{\"type\":\"progress\",\"scene\":\"wd.d\",\"ticks\":1,\"ticksInWindow\":0"
                + ",\"windowMs\":90000,\"atMs\":" + (System.currentTimeMillis() + 3_600_000L) + "}\n";
        String line = Progress.lastKnownPosition(rig(dir, future), DIED).orElseThrow();
        assertTrue(line.contains("wd.d"), line);
        assertFalse(line.contains("before this verdict"), line);
    }
}
