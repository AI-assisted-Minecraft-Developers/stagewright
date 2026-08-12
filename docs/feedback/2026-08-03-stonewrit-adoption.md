# Feedback — migrating a 132-scene suite from mc-testkit to StageWright

> Date: 2026-08-03 · **Revisited: 2026-08-08** against `80c74b9` plus its working tree
> Consumer: `stonewrit` (NeoForge-only ModDevGradle 2.0.141 mod, MC 1.21.1, NeoForge 21.1.230)
> 132 `sw.` scenes, previously GREEN on `mc_testkit` 0.1.0+1.21.1
> Goal: move an existing consumer onto the post-split artifacts — `mc_stagewright` 0.1.0+1.21.1
> and the `net.magicterra.stagewright` 0.1.0 plugin.
> Author: Claude (agent), driving the migration end to end.

Net-positive: the API is unchanged, the migration was almost entirely a rename, and the
suite went GREEN. **But the first run on a clean run directory crashed after ~96 s with a
`ServerHangWatchdog` crash report naming no scene.** That is finding 1, and on the revisit it
is **fixed at the root** — better than the fix this report asked for. Two of the eight remain
open, and they are now the same failure chain.

## Status at 2026-08-08

Re-verified against a fresh `mavenLocal` publish of the current tree. The 132-scene suite is
GREEN on it with **zero source changes** — the contract-package move
(`Canary`/`Clock`/`Expect`/`SceneFailure`/`SceneOutcome`/`SceneSkipped`/`Terrain` →
`net.magicterra.stagewright.contract` in the new `attached` module) did not reach us, because
132 scene files import exactly three types between them: `Scene`, `SceneContext`,
`SceneProvider`.

| # | finding | status |
|---|---|---|
| 1 | `provision` dropped four of `t0.py`'s six keys | **closed** — root-caused differently, see below |
| 2+3 | a crashed run yields no verdict; vanilla's watchdog guarantees the crash | **open**, and now one chain |
| 4 | `outsideForcedChunks` javadoc wrong for 1.21.1 | **open** — text unchanged |
| 5 | `floor()` / `Arena` symmetric over an asymmetric window | **half closed** — `Arena` guards, `floor()` does not |
| 6 | README's plugin sections describe the retired implementation | **closed** |
| 7 | no mc-testkit → StageWright migration note | **open**, and there is now a second migration to document |
| 8 | smaller notes | Rhino one **closed**; mod id still `mc_testkit`; the recipe this cited no longer exists |

### What got better since, beyond the findings

- **`Terrain`** (`9dcae02`) — ground as a per-scene choice of *dimension* rather than a
  property of the run world. This is the right shape and it is why finding 1's suggested fix
  is withdrawn below.
- **`WorldPin`** (`80c74b9`) — the suite header now carries
  `"worldPin":"clock=frozen@midnight doDaylightCycle=false …"`. A scene run that states the
  world it ran in is a scene run whose results mean something a week later.
- **The COVERAGE line.** `138 scene(s) executed, 1 skipped on this topology and tested nothing
  — [remotePlayerIsPresent]`. Naming what ran *nowhere* is the one gap a green verdict
  structurally cannot report, and now something does.

## What worked (no friction), as of the original migration

- **The scene API is source-compatible 1:1.** 132 scene bodies compiled with zero changes
  beyond `net.magicterra.testkit.scene` → `net.magicterra.stagewright.scene`. Nothing in
  `Scene` / `SceneContext` / `SceneProvider` moved or changed signature — and five days of
  upstream development later, that is still true.
- **The whole migration is five renames**: plugin id, artifact coordinates, the
  `stagewright.autorun` system property, the SPI package (and its services filename), and
  the results filename. The only structural change is the extension shape.
- **The plugin resolved from `mavenLocal()` and registered its tasks with no ceremony**, and
  it is configuration-cache clean for the plain server topology — this build has
  `org.gradle.configuration-cache=true` and never had to disable it.
- **No Gradle-inside-Gradle.** The old `testkit { scriptsDir = … }` pointed at a machine-local
  Python checkout, which was the single ugliest line in our `gradle.properties`. Deleting it
  and pointing a topology at a run task we already declared is a clear improvement.
- **The exit watchdog earned its keep immediately.** This mod's dev runtime carries Impactor
  and BlueMap, both of which leave non-daemon threads; the run ends on time.
- **`Verdict`'s namespace-scoped reverse reconciliation is the right call.** Our manifest is
  132 `sw.` names while a run now registers 142 scenes (132 + 3 canaries + 7 framework
  built-ins, up from 3); the built-ins stay out of our verdict without us configuring
  anything, and the count growing under us cost us nothing — which is the test of that design.
- 132 scenes in **36 s** on 08-03, **56 s** on 08-08.

## Findings

### 1. `RunDirectory.provision` dropped four of the six settings `t0.py` used to write

**Status: closed — fixed at the root, and the fix is better than what this asked for.**

The original report, kept because it is the reproduction record:

`scripts/t0.py::provision` wrote **six** keys:

```python
f.write("server-port=25599\nlevel-type=minecraft\\:flat\nonline-mode=false\n"
        "spawn-protection=0\nsync-chunk-writes=false\nmotd=stagewright T0\n")
```

`RunDirectory.provision` (commit `112ca5b`) carried over `eula.txt`, `online-mode` and
`options.txt` — but **not `level-type`, `spawn-protection`, or `sync-chunk-writes`**. On a
fresh run directory the server generated `minecraft:normal`, and PREP blocked the server
thread generating terrain:

```
java.lang.Error: ServerHangWatchdog detected that a single server tick took 60000192.00 seconds
    at ...ServerChunkCache.getChunk(ServerChunkCache.java:159)
    at ...ServerLevel.setChunkForced(ServerLevel.java:1423)
    at net.magicterra.stagewright.harness.StageWrightHarness.forceChunks(StageWrightHarness.java:310)
    at net.magicterra.stagewright.harness.StageWrightHarness.tick(StageWrightHarness.java:159)
```

**What actually fixed it** (`9fff0c9`, "pin the arena with one region ticket instead of
twenty-five blocking chunk loads", plus `7b50adc` for the entity-ticking margin): the arena is
pinned with one non-blocking `addRegionTicket`, and PREP — which was already waiting — waits
through the delivery. The commit message names our exact symptom. Fixing the mechanism rather
than the world type is the correct call, and it fixes it for run directories nobody
provisioned, which the suggested fix would not have.

**Verified here, not assumed.** We emptied our whole `doFirst` workaround, `rm -rf`'d the run
directory, and ran the 132-scene suite against vanilla's defaults — `minecraft:normal`, no
seeded keys at all. **GREEN.** The `PREP_STALL_TICKS` / `PREP_CEILING_TICKS` pair is what makes
that survivable rather than merely slower: a generator that is working is waited on, a
generator that has stopped is called stuck.

**The suggested fix is withdrawn**, and `Terrain` is why: a scene that needs ground now asks
for `SUPERFLAT` or `GENERATED` and gets its own dimension, deliberately independent of the
topology's `level-type`. Seeding `level-type` in `provision` would have made the run world
load-bearing again, in the exact way that enum was written to stop.

**One correction we owe you.** This report claimed `spawn-protection=0` was needed because a
non-op scene actor cannot edit blocks within 16 of spawn. We have now run the whole suite with
vanilla's default of 16 and nothing failed — arenas sit 100 000 blocks out, so it never
applied. That claim was wrong; we have dropped the key from our own build.

**What is left is a cost, not a bug, and may be worth one line in an adoption note.** Same
suite, same machine, back to back, each from a deleted run directory:

| run world | wall clock | per scene |
|---|---|---|
| `level-type=flat` | **1m 02s** (56 s on a warm repeat) | ~130 ms |
| `minecraft:normal` (vanilla default) | **3m 07s** | ~900–1900 ms |

All of the difference is worldgen for arenas that no `RUN_WORLD` scene asserts anything about
— `RUN_WORLD` is empty sky at y=200 by definition. We keep `level-type=flat` in our build for
that 3× and nothing else, and our comment now says so. A sentence in the recipe — *"scenes at
`RUN_WORLD` never look at the ground; a flat run world makes the gate ~3× faster"* — would let
the next consumer make that choice deliberately instead of inheriting it from a crash.

### 2+3. A crashed run produces no verdict, and vanilla's watchdog is what produces the crash

**Status: open, both halves. Filed separately on 08-03; they are one chain and are worth
fixing as one.**

*The chain.* A wedged tick → vanilla's `ServerWatchdog` fires at 60 s, 30 s before
`StallWatchdog.STALL_MS` → the JVM dies non-zero with a crash report → Gradle fails the run
task → the verdict task, wired with `task.dependsOn(run)` (`StageWrightPlugin:128`, unchanged),
**never runs** → the partial results file is never read. Every diagnostic the framework built
is bypassed by the one mechanism it did not stand down.

*Half one — the verdict.* What we got on 08-03 was:

```
> Task :runStagewrightServer FAILED
> Process 'command '…java.exe'' finished with non-zero exit value 1
```

No `[stagewright:…]` line, no ENV/DEAD verdict, and ~21 KB of partial results on disk never
read — the case where the framework has the most to say and says nothing. The partial file
names every scene that passed, so "died after N scenes, last entered X" was available and
discarded. It also means `StageWrightVerdictTask`'s ENV message — whose three listed causes are
a missing mod jar, a run that does not set autorun, and failed mod loading — can only ever be
seen when the run task *succeeded*, so none of those three is the common way to get no results.

**Suggested fix:** `finalizedBy` rather than `dependsOn` (or `ignoreExitValue` plus an explicit
check), and teach `Verdict` to report a footer-less file as "died after N scenes, last entered
X".

*Half two — the watchdog.* `StallWatchdog.STALL_MS` is 90 s. `DedicatedServerProperties`
defaults `max-tick-time` to 60 s and `DedicatedServer` starts vanilla's watchdog whenever
`getMaxTickLength() > 0`. On any dedicated-server topology with a default `server.properties`,
**vanilla always wins by 30 s** — and `StallWatchdog` is strictly the better instrument: it
names the running scene, runs `findDeadlockedThreads`, dumps the server thread's stack, writes
a real TIMEOUT record plus a matching footer, and halts 0 so the results file carries the
verdict.

**Suggested fix:** have `provision` write `max-tick-time=0`, the documented way to stand
vanilla's watchdog down. (Lowering `STALL_MS` under 60 s would work too, but the 90 s value is
well argued in that class's javadoc and single ticks legitimately run for seconds.) Nothing in
the tree mentions `max-tick-time` today.

**Why this got *more* worth fixing, not less.** Finding 1 removed the common way to trigger
this chain — a fresh run directory. What remains is the uncommon way: a real deadlock in a mod
under test, on somebody else's machine, once. That is precisely the failure nobody has a
playbook for, and it is the one `StallWatchdog` was written for.

### 4. `SceneContext.outsideForcedChunks` javadoc is wrong for 1.21.1

**Status: open — the method is now called (see finding 5), but its text is unchanged.**

> *"Writes there are silently lost, and the scene then fails on an assertion about terrain that
> never existed."*

Writes there are **not** lost. `SceneContext.setBlock` → `Level.setBlockAndUpdate` →
`Level.setBlock` → `getChunkAt` → `Level.getChunk(x, z, FULL, requireChunk = true)`, and
`requireChunk = true` blocks the server thread and *generates* the chunk. Verified against the
1.21.1 NeoForge sources.

Both the hazard and its symptom differ from what is documented: the scene does not fail on a
missing-terrain assertion, it **passes** — and something unrelated dies later. Now that the
arena's own chunks arrive through a non-blocking ticket, this is the *only* remaining path by
which a scene can block the server thread on worldgen, which makes the sentence more worth
correcting than it was, not less. `Arena`'s new message gets the mechanism right; the javadoc
one layer down still contradicts it.

### 5. `floor()` and `Arena` are symmetric APIs over an asymmetric window

**Status: half closed.**

`Arena.build` now checks the whole footprint before writing any of it
(`Arena.java:110`), and the failure message names `chunkRadius`, the current value, and the
`@SceneDef(chunkRadius = n+1)` that fixes it. That is the fix this asked for and then some.

`SceneContext.floor(size)` still expands `size/2` in **both** directions over a window that is
`[-16, 31]` for `r=1` — the grid origin sits on a chunk corner — so `floor(35)` writes at
`dx = -17`, outside it, and per finding 4 force-generates a chunk rather than failing. The
predicate is now proven in use one file away; routing `floor()` through it is a one-liner.

### 6. The README's Gradle-plugin sections describe the retired implementation

**Status: closed.** "Gradle plugin: task entry points" is now marked *Superseded — see Gates
above*, with an accurate two-sentence description of what the plugin actually does and the
`stagewright<Topology><Loader>` naming. The remaining Python mentions elsewhere in the README
all read in the past tense and are fine.

### 7. There is no mc-testkit → StageWright migration note

**Status: open — and there is now a second migration for it to cover.**

Every consumer of the old artifacts needs the same table, and none of it is written down.
Ours, for the record:

| | before | after |
|---|---|---|
| plugin | `net.magicterra.mc-testkit` | `net.magicterra.stagewright` |
| plugin version | in `plugins {}` | must move to `settings.gradle` `pluginManagement` to be a property |
| runtime artifact | `net.magicterra:mc_testkit-neoforge` | `net.magicterra:mc_stagewright-neoforge` |
| arm property | `-Dtestkit.autorun` | `-Dstagewright.autorun` |
| SPI package | `net.magicterra.testkit.scene` | `net.magicterra.stagewright.scene` |
| services file | `…services/net.magicterra.testkit.scene.SceneProvider` | `…scene.SceneProvider`, renamed to match |
| results file | `testkit-results.jsonl` | `stagewright-results.jsonl` |
| extension | `testkit { loader, scriptsDir, serverRunTask, serverResults, serverExpectFile }` | `stagewright { topologies { <name> { runTask, gameDirectory, expectFile } } }` |
| gate task | `testkitServer` | `stagewright<Topology>` |
| gone | `testkit_scripts_dir` | — |

The one thing that is *not* a rename, and which a migration note should lead with, is that
**StageWright now depends on WorldDriver**. It took reading `StageWrightCommon.installVerbHooks`
to establish that the dependency is a by-name probe and that a driver-less runtime still arms.
For a consumer whose gate deliberately runs one mod plus a harness, that is the first question
asked and the answer is good news — it should not be buried in an implementation class.

**New since:** the contract types moved out of `…stagewright.scene` into
`…stagewright.contract` in the new `attached` module. It cost us nothing, but only because our
132 scenes happen to import three types and none of the moved ones; a consumer that had reached
for `Expect` or thrown a bare `SceneFailure` gets a compile error with no note to look up. Same
document, one more row.

### 8. Smaller notes

- **`sceneScripts` + no Rhino — closed.** `JsScenes` now documents that absent Rhino the class
  is never loaded and scene files are *reported as ignored*, which was the whole ask: a pack
  author who wrote scenes and got a green run that never executed them is now told so.
- **The mod id is still `mc_testkit`** (`StageWrightCommon.MOD_ID`, and the shipped
  `neoforge.mods.toml`), with `displayName="stagewright"`. A consumer's mod list and every log
  line still say `mc_testkit`. If that is deliberate compatibility it deserves a comment next
  to it; if it is leftover, it is the last visible piece of the old name.
- **The one-coordinate note has lost its home.** This originally pointed at adoption-recipe
  step 5 in `conformance-mods/README.md`, which no longer exists. The substance still holds and
  belongs wherever finding 7's note lands: for a NeoForge-only ModDevGradle consumer, one
  coordinate does both jobs — the shaded `-neoforge` jar carries
  `net/magicterra/stagewright/scene/*` and is already Mojang-mapped (checked: 0 of 45 classes
  carried an intermediary name), so it links against a mojmap compile classpath. The `:dev`
  caveat is a Fabric/loom concern.
- **UNDECLARED is namespace-scoped**, which is right for framework built-ins but means the
  *first* consumer scene under a new prefix is reconciled in neither direction — it is not in
  the manifest, and its prefix is not in the manifest either, so nothing flags it. It still
  REDs if it fails, so this is a coverage-accounting gap, not a correctness one. Worth one
  sentence wherever bidirectional reconciliation is claimed.
- **A skip is `"outcome":"PASS"` with `"skipped":true`** alongside it. The discriminator is
  there and COVERAGE reads it, so this is not a bug — but any consumer-side tooling that reads
  these files by `outcome` alone counts skips as passes. One sentence in the results-format
  documentation would prevent that; ours read the field before we noticed.

## Appendix: what we ended up with

```groovy
// settings.gradle — the version has to live here to be interpolatable
pluginManagement {
    repositories { mavenLocal(); gradlePluginPortal() }
    plugins { id 'net.magicterra.stagewright' version "${stagewright_plugin_version}" }
}

// build.gradle
neoForge.runs {
    stagewrightServer {
        server()
        programArgument '--nogui'
        gameDirectory = file('run/stagewright')
        systemProperty 'stagewright.autorun', 'true'
    }
}
stagewright {
    topologies {
        server {
            runTask = 'runStagewrightServer'
            gameDirectory = file('run/stagewright')
            expectFile = file('scripts/stagewright/expected-scenes-neoforge.txt')
        }
    }
}
tasks.named('runStagewrightServer') {
    dependsOn 'testmodClasses'
    // Not correctness any more (finding 1 is fixed upstream) — 1m02s against 3m07s, all of it
    // worldgen for arenas no RUN_WORLD scene looks at.
    def propsFile = file('run/stagewright/server.properties')
    doFirst {
        def forced = ['level-type': 'flat', 'sync-chunk-writes': 'false']
        propsFile.parentFile.mkdirs()
        def props = new Properties()
        if (propsFile.isFile()) { propsFile.withInputStream { props.load(it) } }
        forced.each { k, v -> props.setProperty(k, v) }
        propsFile.withOutputStream { props.store(it, 'stagewright gate') }
    }
}
```

`dependsOn 'testmodClasses'` was also needed: attaching the source set through
`neoForge.mods { … sourceSet(sourceSets.testmod) }` is a classpath edge, not a task edge, so
without it an edited scene runs as its previously compiled self. Worth a line in the recipe —
ModDevGradle consumers will all need it.
