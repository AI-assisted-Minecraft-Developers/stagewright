# CHANGELOG

Why StageWright's behaviour changed. What each piece of code is shaped like and why lives in a
comment next to it; what is still open lives in `TODO.md`; this file is for changes a consumer would
notice.

Every entry says how far it was verified: **compiled** · **green in the self-test suite**
(worlddriver's `cap.*` / `wd.*`) · **green against a third party** (a mod or modpack this project
does not control, which is the only level that proves a claim about such code).

---

## 2026-09-22

### The CLI jar no longer carries Rhino's mod metadata · checked in the built jar

The fat jar merged Rhino's jar whole, so `fabric.mod.json`, `META-INF/mods.toml` and
`META-INF/neoforge.mods.toml` declaring the mod `rhino` sat at its root. Dropped into `mods/` by
mistake, `stagewright.jar` would load on either loader as a second Rhino beside the one
worlddriver nests. Loader metadata from merged libraries is now left out.

### The engine and CLI jars carry the notices of the third-party code inside them · checked in the built jars

The engine jar holds minimal-json's compiled classes, whose MIT headers exist only in the `.java`
sources, and the CLI's fat jar merges minimal-json, Rhino (MPL-2.0), Gson and Error Prone
annotations (Apache-2.0), none of which ships its own license file. Both jars were handed out
with no copyright or permission notice and no pointer to Rhino's source. The engine jar now
carries `META-INF/licenses/minimal-json-MIT.txt`; the CLI jar adds `META-INF/THIRD-PARTY-NOTICES`
and the MPL-2.0 and Apache-2.0 texts, and the packaging check fails a CLI jar holding classes
from a library it has no notice for.

### Every jar carries the LGPL text, and every POM declares the license · checked in the built jars and generated POMs

The relicense changed the headers but no build packaged `COPYING` or `COPYING.LESSER`, so every
mod jar, `-sources` jar, plain-JVM library and the CLI jar was conveyed without the license the
LGPL requires to travel with object code, and no POM named a license at all, so scanners
reported the artifacts as unknown. All four builds now put both files under `META-INF/` in every
jar and declare `LGPL-3.0-only`, the project URL and the SCM URL in every POM, from one
`gradle/license.gradle`. `scripts/check_packaging.py` inspects the built artifacts for it.

### A registry that cannot be built is RED, with its error · compiled, registry and verdict green in unit tests

A duplicate scene name, an illegal origin pin, a `SceneProvider` with no scenes, a `.js` file that
does not parse or scene files with no Rhino all threw during SERVER_STARTED, so no header was ever
written and the run was ENV — "the game never armed" — pointing the author at a missing mod jar. The
registry is now resolved before the harness is built, and a refusal is written as the run's
whole results file: a header registering nothing with `registryError`, then a footer. The verdict
reads that as RED with the message; under autorun the server then halts as after a finished suite.
The CLI's `--attached` half treats a scene file that does not load the same way: RED, not exit 3.

### `--attached` ends on the true label of its exit code · compiled, green in unit tests

The closing line printed RED for anything non-zero, so a DEAD in-process half (canary on the wrong
outcome, results void) or an ENV one (never armed) ended the output reading as a code defect while
the process exited 2 or 3. Both closing lines now carry the label of their code, and the run's line
reads `VERDICT (in-process and attached): <label>`.

### Coverage refuses filtered runs and does not count ENV_FAIL as execution · compiled, green in unit tests

`stagewrightCoverage` and `--coverage` reconciled whatever results were on disk, so after a
`-Pstagewright.scenes` iteration they reported "N of N executed" over the subset the pattern kept.
A results file whose header records a filter now makes coverage ENV, naming the file. And an
`ENV_FAIL` record — written before the body runs — no longer counts as the scene having executed,
so a scene whose arena never loads on any topology is reported UNCOVERED.

### `--with-client` judges the client's own probe · compiled, green in unit tests

The joined client always ran its probe and wrote `stagewright-client-results.jsonl`, and the CLI
never read it: a probe that failed or timed out still exited GREEN, where the same pair of files
under the Gradle plugin's `companionResultsFile` was RED. The CLI now judges that file with the
engine's companion rule — its own header, worst verdict wins, missing file ENV — and provisioning
clears it and its heartbeat, rather than a `stagewright-results.jsonl` the client never writes.

### The CLI refuses an empty expected-scenes manifest, as the plugin does · compiled, green in unit tests

A manifest holding only comments or blank lines was a hard error under Gradle and a silently
skipped reconciliation under `--expect`, so one file got two verdicts. The refusal now lives in
`Manifest.read`, which both front ends call, and the CLI reads the manifest before it starts the
game.

### The CLI refuses options it does not know · compiled, parser green in unit tests

Any `--name value` used to be accepted and stored, so a typo was a setting nobody read:
`--expected expected-scenes.txt` ran with manifest reconciliation off and could exit GREEN over a
provider whose scenes had vanished, and `--clean-wrold false` deleted the world. An unknown option
now prints the usage and exits 3, and `--clean-world` takes only `true` or `false`.

### `-Dstagewright.filter` is gone · compiled, registry green in a unit test

A second, older filter still narrowed the registry by glob, but it never reached the suite header,
so the verdict judged a narrowed run as the whole suite — no FILTERED label, and a pattern that
matched nothing came out as a canary-only GREEN. The property now does nothing. Narrow a run with
`-Pstagewright.scenes` (the game reads it as `-Dstagewright.scenes`), which the header records.
`@SceneDef(tags = …)` was documented as feeding that filter; nothing reads it.

### A filtered run keeps the framework canaries · compiled, filter and verdict green in unit tests

`-Pstagewright.scenes` used to filter the canaries out with everything else, so a narrow run
carried no proof that the harness could still catch a failure. `MUST_FAIL`, `MUST_TIMEOUT` and
`MUST_SWALLOW` scenes now survive every pattern and are judged as in a full run. Since the
registered list is then never empty, "the pattern matched nothing" is judged on the scenes other
than those canaries, and stays RED. `MUST_SKIP` scenes belong to the suite and are still selected
by name.

### A skip no longer hides a `check` that already failed · compiled, attached home green in its unit test

A body that recorded soft violations with `check(...)` and then skipped — explicitly, or through
`player()`, `capability()`, `probe()` or `mods().require()` finding nothing — was recorded as a PASS
skip, and the violations appeared nowhere. Both homes now record it as a FAIL whose reason lists the
violations followed by `then skipped: <reason>`. The wording is one shared function, so the two
homes cannot drift apart on it. The new built-in canary `canaryCheckThenSkipMustFail` holds the
in-process harness to this rule: if a skip wins again, the run is DEAD.

### `--mod` refuses to take over a jar the pack already ships · compiled, green in unit tests

A `--mod` jar whose file name was already in the pack's `mods/` overwrote it and was written to the
install ledger, so the next run's sweep deleted it and the pack lost a mod it shipped with. An
install whose target exists and is not in the ledger is now refused before anything is swept, so a
refused install leaves `mods/` exactly as it was.

### Teardown sweeps and audits the arena a terrain scene actually played in · compiled, awaiting the self-test suite

The entity sweep and the leak audit used the grid slot's box at y=200 while a terrain scene plays at
the surface, so its leftovers were neither removed nor reported. Both now use the scene's own
origin. Two built-in scenes, `arenaLeftoversAreSwept` and `arenaLeftoversStayGone`, pin slots 2002
and 2003 to prove it in every run.

### Provision deletes a companion's stale endpoint descriptor · compiled, green in unit tests

Only the run directory's own descriptor was deleted, so a companion hold restarted later could be
dialled at yesterday's port while it was still booting. The descriptor beside every stale results
file is now deleted too.

### A skip for a missing capability names adapters that failed to load · compiled, green in a unit test

An adapter that threw while loading — typically a linkage error after the mod it wraps changed —
left the skip reading "nothing in this run offers X", as if the mod were absent. Every skip where no
provider matches now lists the adapters that failed and why.

### Endpoint URIs bracket IPv6 and dial a wildcard bind as loopback · compiled, green in unit tests

`TESTKIT_ENDPOINT` readers built `ws://host:port` from the bind address verbatim, so `::` or
`0.0.0.0` produced a URI nothing could dial. A wildcard now becomes the same family's loopback, IPv6
literals are bracketed, and the descriptor carries `mcpHost` when the driver's MCP bind differs, so
the MCP URI no longer assumes the RPC host.

## 2026-09-20

### Relicensed from MIT to LGPL-3.0-only · compiled

The text is `COPYING.LESSER` (the additional permissions) over `COPYING` (the GPL-3.0 text they
modify), the two-file layout the LGPL itself prescribes. `mod_license` follows in
`gradle.properties`, and both loaders' generated jar metadata was checked to declare it.

What it means for a consumer, which is the part worth being precise about: declaring the
artifacts as dependencies, writing scenes against the SPI, and running the gates are all *use*
of the framework, not derivation from it — the mod under test carries whatever license it
likes, and nothing about running StageWright against a closed-source pack changes that. The
copyleft attaches to StageWright's own sources and to modified copies of them.

One directory is exempt and stays MIT: `engine/src/main/java/.../engine/json/`, minimal-json
0.9.5 vendored verbatim from EclipseSource. MIT composes into an LGPL work in this direction,
but those headers are upstream's terms rather than ours — see `VENDORED.md` beside them.

### Documentation split out of the README and rewritten · checked line by line against the source

`README.md` was 1415 lines carrying a landing page, a tutorial, three contracts, the design
rationale and a changelog at once. It is now a landing page, with the rest under `docs/`: guides
for doing something, reference for what the contracts are, design for why the framework is shaped
this way, and an archive for records that describe the past and say so. `docs/README.md` indexes
all of it, and `README-zh_CN.md` is a Chinese translation of the landing page.

Consumers should expect the old deep links to break. `docs/orchestration-contract-v0.md` and
`docs/instrument-contract-v0.md` are now `docs/reference/orchestration-contract.md` and
`docs/reference/instrument-contract.md`; the three design documents lost their `-design` suffix
and moved under `docs/design/`; the version suffix is gone because it named a freeze rather than a
revision, and there was never a version one to compare against.

Several statements did not survive verification, and a consumer may have been relying on them. The
published-artifact table named coordinates that do not exist and omitted three that do; the task
naming was attributed to the plugin when it comes from the topology names a consuming project
declares; the provisioning step was documented as writing server properties it does not write; the
endpoint descriptor's file name, its `topology` field and its process id were all described wrongly;
and the instrument checklist enumerated assertions belonging to runners deleted some time ago. Each
is corrected in the reference documents rather than carried forward.

## 2026-09-18

### A provisioned client no longer waits for the compositor · green in the self-test suite

`options.txt` is seeded with `enableVsync:false`. With vsync on, the client's single thread blocks
in `glfwSwapBuffers` until the compositor presents the window — and a compositor that is not
presenting it (screen asleep, another workspace, a remote session) hands out about one frame a
second. Minecraft runs at most ten game ticks per frame, so the client falls to ten ticks a second
while the integrated server keeps twenty, and every scene that drives a client body needs twice the
server ticks it budgeted for.

What that looks like from the outside is not a slow machine. It looks like the bodies are broken:
arrival one cell short, a bank climb that "stalls", a craft that times out, a retreat chain that
never releases the channel. One run came back with eight red scenes, all client-body, on a box with
a load average under one.

The measurement that named it: three `jstack`s of the render thread all sat in
`RenderSystem.flipFrame` having burned 0.04 ms of CPU between them, and the failing scenes came in
at almost exactly 2x their green tick counts — 59 → 118, 355 → 676, 127 → 306.

## 2026-09-15

### An arena is stalled only when the whole level has stopped loading around it · green in the self-test suite

Two integrated-NeoForge runs after it were GREEN with no ENV_FAIL, against 7 and 4 in the two before;
their longest PREPs were 670 and 637 ticks, all client-body scenes following another.


The integrated-NeoForge ENV_FAIL was not a stuck chunk system. The readings below had the arena's
neighbours at `spawn` behind a server thread whose ticks went to `ChunkMap.processUnloads`, which looked
like the unloads starving the chunk executor. Running that executor from PREP for part of every tick was
tried and taken out again: the executor answered about fifty polls a tick, arenas still waited up to
380 ticks, and it changed the order every scene's chunks load in for a queue that was only long. Of
74 stalled arenas across the integrated-NeoForge runs on hand, 49 came straight after a scene that
adopted the real player, which only 6 in 100 scenes follow. That scene sends the player back where it
came from, the chunks there reload ahead of the next arena's neighbours, and the arena stood still
past `PREP_STALL_TICKS` while they did. PREP now also counts the level's loaded chunks and the chunks
it has turned ticking as progress, so an arena waiting its turn is not a stall; one that waits past
`PREP_CEILING_TICKS` still fails, and a stall's message names both counts. The report also lists the
unloads that are not ready.

### A stalled arena's report says where the server thread spent the wait · compiled

The fourth reading had the chunk executor holding sorter tasks that never ran, the server's own queue
all but empty, and an average tick time of 49.9 ms, a whole tick's budget: the thread had no time left
for those tasks, and the report could not say what filled it. Half way to giving up, PREP now samples
the server thread's stack every 5 ms for two seconds on a daemon thread, and the ENV_FAIL ends with the
most frequent stacks and their sample counts. A sample pauses the thread for an instant; nothing else
changes.

### The same report names what waits in the chunk executor and the server's queue · compiled

The third reading had the sorter's main queue holding work while its main executor waited on a batch,
with two tasks sitting in the chunk executor. Vanilla runs chunk executor tasks only once the server's
own task queue is empty and the server has time before its next tick. The report now ends with both
queues, each with the classes of the tasks at its head (a server task also names the tick it was queued
on), the server's tick count, its average tick time and whether it is sprinting. It still only reads.

### The same report ends with the chunk task sorter's state · compiled

The second reading had every chunk within two of the arena that was not already FULL stopped at
`spawn`, one step short, with no generation task pending and two main-thread tasks. The `FULL` step
runs on the main thread through `ChunkMap`'s task sorter, which holds a chunk's tasks while an earlier
one is acquired and puts an executor to sleep until a release. The report now ends with the sorter's
debug line (each queue's acquired chunks and the sleeping count), whether it has work, and each queue's
first non-empty priority. The sorter and its queues are read by reflection on their Mojang names.

### The same report says how far generation got around the arena and what is queued · compiled

The arena-chunk report now also gives each arena chunk's ticking future, and for every chunk within
two of the arena its latest generation status and full-chunk future beside its ticket level, then the
number of pending generation tasks and main-thread tasks. The first reading on integrated NeoForge had
every arena ticket at entity-ticking level and every entity-ticking future pending. The promotion
behind that future waits for generation to FULL of every chunk within two and then for a main-thread
task, so these are the places it can be standing. The generation queue is read by reflection on its
Mojang name and prints `?` where that name is absent.

### An arena PREP gives up on says what the chunk system thinks of it · compiled

The ENV_FAIL for an arena that never became usable now ends with, per arena chunk, its ticket level,
full status and entity-ticking future (`pending`, `done`, or the failure it holds — a holder never
promoted still holds the unloaded result), and the ticket levels of every chunk within two of the
arena. It is for the integrated-NeoForge failure where every arena chunk is present, none ticks
entities, and the count never moves: the old message could say that much and not which of an
unticketed chunk, an unpromoted holder or a failed promotion it was. Reads only; nothing is loaded or
ticketed. The holder is found by reflection on its Mojang name; where that name is absent the chunk's
public debug line stands in, without the future.

## 2026-09-11

Everything below marked **green against a third party** was verified on a 262-mod NeoForge 21.1.248
pack nobody here maintains: server topology GREEN, a crashed client ending its run in about a minute
instead of forty-five, `--results` landing where the CLI then read it, and detection naming 21.1.248
out of the fourteen loaders installed beside it.

### `--results` renames the file the game WRITES, not only the one the verdict opens · green against a third party

The flag renamed the reader and nothing else. The harness kept writing
`stagewright-results.jsonl`, because no property, argument or file carried the name across the
process boundary — so `--results stagewright-server.jsonl` produced a run that executed every scene,
finished green, wrote a complete results file, and was judged `ENV — the run wrote no results`
against a path nothing was ever going to write. That is the same verdict a pack gets when the
framework jar failed to load, which is where it sent the reader.

The name now travels as `-Dstagewright.results`, passed on every run including the default one so
the game's command line records the file a reader will go looking for, and honoured by
`StageWrightCommon` when it builds the harness. The plugin's `resultsFile` gets the same treatment,
conditionally: a topology on the convention produces a byte-identical command line to before. A
StageWright jar older than the property ignores it and writes the convention, which is exactly the
old failure again — so the ENV verdict now names that possibility when the run was renamed.

### A run ends when its results file says so, not when the game's JVM exits · green against a third party

Those were the same statement for as long as every pack could close itself. A dedicated server halts
when the suite drains, so waiting for the process was waiting for the run. On a 262-mod pack it is
not: the suite finishes, the footer lands, the harness's exit watchdog warns and calls
`Runtime.halt(0)` — and the process is still there afterwards, in futex wait, unreachable by `jcmd`
and silent under `SIGQUIT`, which is what a JVM stuck inside `VM_Exit` looks like from outside.
`halt` is already the most forceful thing a JVM can do to itself; it still needs a safepoint, and a
process wedged in native code never reaches one. Nothing inside the game can improve on that.

So the supervisor is now the backstop. Every topology waits for the done footer, gives a finished
game 45 seconds to close itself — longer than the harness's own 30-second watchdog, so a JVM that
can go down always does — and then kills the process tree and judges the finished file. A pack that
cannot end its own process costs 45 seconds instead of the whole `--timeout`.

### A crash report ends the wait, and then ends the explaining · green against a third party

The other half of the same problem, and the reason the first one was hard to see: a crashed
Minecraft frequently does not exit either. One mod's un-shut-down thread pool keeps the JVM in the
process table long after the crash report is on disk and the window is gone, so `Process.isAlive`
stays true, the CLI waits out the full `--timeout`, and then reports "the run wrote no results" —
true, forty-five minutes late, and silent about the report that has been sitting in `crash-reports/`
since minute three.

The directory is snapshotted before the launch and polled beside the results file. Names rather than
timestamps decide what is new, because a pack directory is reused and its old reports would
otherwise fail every run before it started. The crash line then points at the launcher's own
output, which a `--display-client` run now writes to `stagewright-client-launch.log`
(`stagewright-headlessmc.log` stays the HeadlessMC launch's), so the pointer no longer names a
launcher the run never used.

The ENV verdict that follows now says the crash report and nothing else. It used to name the report
and then offer three guesses underneath it — the framework jar may have failed to load, an
`-lwjgl` stub may have handed a mod an all-zero image, you renamed the results file — so the last
three paragraphs a reader saw were all wrong about a run whose cause was already on disk, and one of
them was about a stub `--display-client` never uses. A report is the game's own account; the guesses
are still printed when there is none, which is the case they were written for.

### Loader detection reads the pack's own launch script · green against a third party

A pack directory holds every loader it has ever had installed — fourteen, in the one that found
this — and detection picked the newest by STRING compare. Two ways that is wrong, both live in a
real directory: `21.1.9` sorts above `21.1.117` because `'9' > '1'`, and a `21.5.34-beta` installed
beside the `21.1.248` a pack actually boots wins on every ordering rule there is. The failure is not
reported as a wrong loader; it comes back as a mod's dependency check — *"jei requires neoforge >=
21.1.248, current 21.5.34"* — which reads like an incompatible mod list.

Ordering was the wrong question. A NeoForge or Forge installer writes `run.sh` and `run.bat` naming
one argument file, and that version is by construction the one the pack boots. Detection reads them
first, on either platform (they differ only in path separator), and says so. Only when no script
names one does it guess at the newest, now by numeric segments with pre-release suffixes sorting
below the release they precede — and it says that it is guessing, and names the version it picked,
because "which one did you just launch" is the first question the resulting error raises.

### The client director waits for the first resource reload before it counts anything · compiled

A client ticks throughout its own loading: `Minecraft.run` calls `runTick` from the first frame and
`runTick` calls `tick` on the timer, so client-tick events fire while the loading overlay is up and
mods are still being constructed. The director was fed from tick one, which meant its title-screen
settle window — 40 ticks, an allowance for a screen to settle — was being spent inside a 262-mod
pack's load, and its "waiting for the title screen, currently on …" line was reporting on a game
that did not exist yet. It now returns until `Minecraft.isGameLoadFinished()` is true and no overlay
is up, and logs the transition once so a slow load is distinguishable from a hang.

This is a guard on what the DIRECTOR does and explicitly not a fix for what other mods do in that
window. A mod whose own client-tick handler reads a config value before its config is loaded crashes
on the first tick under any launcher, with no StageWright frame on the stack; the CLI now reports
that as the crash it is instead of waiting for it to become a timeout. Confirmed by re-running the
pack that raised it: the third-party handler still throws, from the loader's own game bus, where the
director has nothing to gate.

## 2026-09-06

### Provisioning forces `sync-chunk-writes=false` · green in the self-test suite

A dedicated-server gate could run out of heap a third of the way through the suite and die as a run
of `ENV_FAIL … only 0 reached entity-ticking` with an `OutOfMemoryError` above it — on NeoForge
every time, on Fabric only when the suite was long enough. Nothing in the scenes leaked. Every arena
is staged in fresh chunks, so a run generates a few hundred chunks per scene and hands each to the
level's single `IOWorker` thread; with vanilla's default `sync-chunk-writes=true` every region write
is an fsync, the thread never catches up, and the backlog sits on the heap as queued tasks and
unwritten chunk NBT. Sampled with `jcmd GC.class_histogram`: 170,000 queued writes and 9,000
unwritten chunks by scene 53, 460,000 and 29,000 by scene 125, on both loaders.

`provision` now forces the key off next to `online-mode` and `level-seed`. The same NeoForge suite
that died at scene 120 finishes GREEN with the live set under 1 GB at every sample and an empty
write queue. The world is deleted before the next run, so the durability the flag buys was never
worth anything here.

## 2026-08-10

### A run that dies without a verdict now says where it was · green in the self-test suite

"No done footer — the harness died mid-run" is a correct verdict and an unusable one. It is what an
operator gets when the process was killed rather than finished — the orchestrator's wall-clock
ceiling, an OOM, a `halt` from somewhere else, a GC spiral that starves even the stall watchdog —
and it names no scene, no tick and no time. The next move it leaves you is opening a multi-megabyte
game log and searching backwards for the last scene mentioned, which is a thing a machine should
have written down.

The watchdog now writes it down. Once a second, on healthy polls as much as on the poll that
declares a stall, it rewrites `<runDir>/stagewright-progress.json` in place with the running scene,
the tick count, and the ticks delivered in its open window. Truncated rather than appended, so a
suite of any length costs one line of disk and a reader needs no scan; kept out of the results file
for the same reason, since a heartbeat per second is thousands of lines in a long suite and would
bury the records the file exists for. `provision` deletes it beside the results, because a leftover
heartbeat still names a scene and would answer "where did it die" with the previous run's.

The verdict reports it **only when the footer is missing**. That restriction is the design, not
caution: the file outlives a healthy run, so on an ordinary RED it would point at whichever scene
happened to be last and invite an investigation into something unrelated to the failure. The rate is
carried as the raw pair rather than a quotient, because once divided a world that stopped and a
world that is crawling both round to zero and they are different bugs — and a third reading matters
just as much, since a healthy tick rate at the moment of death means the operator should stop
looking for a stall at all.

Verified as **green in the self-test suite**: nine unit cases over the degenerate heartbeats a
killed process actually leaves (absent, empty, half-written, from a skewed clock), plus the whole
path on real artifacts — a gate run wrote a heartbeat reading `2226 tick(s) in the preceding 111s`,
provision cleared a planted stale one, a GREEN run printed nothing, and the same results file with
its footer stripped produced:

```
no done footer — the harness died mid-run
last heartbeat: scene 'pack.runsAtTheClockItAsked' at tick 2226 — 2226 tick(s) in the preceding 111s
  (the world was healthy — whatever killed this run, it was not the tick), written 95s before this verdict
```

## 2026-08-09

### A starved scene now ends itself, and only an unrecoverable run is killed · green in the self-test suite

A frozen tick counter was the only stall this watchdog knew. Measured on worlddriver's playthrough
suite, three runs died a different way: the server sat at **0% CPU**, parked in
`MinecraftServer.waitUntilNextTick`, having burned 16 ms of CPU in 200 seconds — and the watchdog
said nothing, because the counter was still *creeping*. A server ticking once every few seconds is
wedged by every measure that matters here: no tick-counted budget can expire inside the run's
wall-clock timeout, nothing more is written, and the operator waits out the full timeout to learn
that something died somewhere. That is exactly the outcome this class exists to prevent.

So the same 90-second window now also carries a rate floor: fewer than **20 ticks** in it is a stall
even though the counter moved. The floor is deliberately absurd rather than merely low — nominal is
1 800 ticks per window and a run labouring through chunk generation still manages hundreds — so it
keeps the promise the "why a stalled counter and not a slow tick" note already made: never fire on
"busy". The verdict line names which of the two it saw, because a deadlock holding a lock and a
server being told it has time it does not are different halves of the process to go looking in.

That criterion is now checked in two places, and the second one is the point of this entry. The
watchdog kills the RUN, which is a last resort: whatever else was going to be tested that day is not
tested. But a stall is usually one scene's problem — a mine that will not finish, a pathfind into
something pathological — and the rest of the suite is perfectly capable of running. So the harness
checks the same criterion on the tick and ends the SCENE, recording a TIMEOUT row that names it and
letting everything after it run. The watchdog stays for the case the harness cannot cover: the check
lives on the tick, and the failure it looks for is the absence of ticks.

**The two must not share a deadline.** They ask one question and take opposite actions, so with one
threshold they race — and the watchdog wins it systematically, because its window opens once when
the run arms while the harness's reopens at every scene boundary. Measured, with the criterion
temporarily made impossible to satisfy: the watchdog fired first and the run died `DEAD` after one
scene, which is precisely the outcome the harness's check was added to replace. The run window is
therefore **three scene-windows**: the harness gets three turns to end a starved scene and let the
world recover, and only if none of them helps does the run end.

Verified as **green in the self-test suite**, in both directions. Firing was measured by making the
floor unsatisfiable for one throwaway run: the harness recorded `awaitTicks -> TIMEOUT` with the
starvation reason, **the next scene passed normally**, a scene starved before its context existed
recorded cleanly rather than throwing, and the watchdog took over at the third window. Not firing
was measured by restoring the thresholds: both loaders GREEN, zero starvation rows across 214
scenes.

## 2026-08-06

### A companion run now carries the environment its build system staged · green in the self-test suite

`stagewrightDedicatedServerWithClientNeoforge` had never been green. Its companion client wrote no
probe results, and the reason was one environment variable.

Both build systems stage some environment entries in a property rather than on the spec, and merge
them into the real environment in the first line of their own task action — which a companion never
reaches, because it is launched FROM the spec rather than by running the task. `SideProcesses` knew
ModDevGradle's staging property and not loom's.

What loom stages there is `MOD_CLASSES`: how NeoForge in dev learns which classes belong to which
mod. Without it FML still finds the mod file — the `neoforge.mods.toml` is in the resources output,
which IS on the classpath — so it reads the manifest, prints the mod in the mod list, attaches no
classes to it, finds no `@Mod` to construct, and carries on. **A mod with no code is a legal mod, so
nothing warns.** The driver was absent from a JVM that listed it.

The Fabric twin was unaffected throughout and that is why this looked like a NeoForge bug for two
sessions: fabric-loom passes the same information as a `-D` system property, which is on the command
line and survives being copied. Diagnosis was `Found supplied mod coordinates [{}]` in the companion's
debug log against `Got mod coordinates main%%…` in a run of the identical task started by Gradle.

All six worlddriver topologies are now green.

### A recipe can be RUN, not only read · green against a third party

Everything `s.recipes()` did was about recipe DATA — who declares what, whose ingredients resolve,
what the graph closes over. None of it executes anything, so none of it could see a recipe whose
`matches()` rejects its own declared ingredients or whose `assemble()` returns the wrong stack. Such
a recipe is registered, has resolvable ingredients, and `closureOf` walks straight through it: every
static check passes and the item is craftable by nobody.

`crafts(recipeId)` fills a recipe's grid from its own declaration and runs it. `craftAudit()` does it
to the whole run and returns a `CraftAudit`. Measured: vanilla **887 attempted, 887 succeeded**; All
the Mods 10 **45,115 attempted, 45,055 succeeded, 47,143 skipped**.

The interesting part is what "skipped" and "failed" turned out to mean, none of which was designed in
advance — each came from a run:

- **Not a crafting grid.** Only `CraftingInput` can be built from a recipe's own declaration. A
  smelting recipe, and every type a tech mod registers, needs an input this cannot construct.
- **Declares nothing.** Vanilla's map cloning, armour dyeing and firework assembly compute
  themselves from whatever is in the grid and declare no ingredients and no result. The first cut
  audited them anyway and **reported five vanilla recipes as broken on a clean install** — the most
  expensive kind of false positive a whole-pack audit can produce. Detected by what a recipe
  declares rather than by `isSpecial()` alone, so a mod that forgets the flag is still covered.
- **Declares everything and never matches, on purpose.** ComputerCraft ships 40 recipes of type
  `impostor_shaped` / `impostor_shapeless` so JEI can *display* a disk being dyed while the real
  crafting happens elsewhere. They genuinely do not make their output and nothing is wrong.

So `failures()` cannot be asserted empty on a pack. The line is the **serializer**:
`failuresInVanillaTypes()` is the assertable subset, because a `minecraft:crafting_shaped` recipe is
run by vanilla's own matcher and has no room for "I meant it not to work". Not a threshold, not a
baseline file, nothing to update when a pack adds mods. On ATM10 all 60 failures are mod serializers
and the vanilla count is 0.

That split was **keyed on `RecipeType` first, and was vacuous** — every crafting-grid recipe in the
game shares the one type `minecraft:crafting`, so it matched nothing, returned an empty list, and
read exactly like a clean pack while 60 recipes were failing. `failuresByType()` exists because of
it: a breakdown naming `computercraft:impostor_shapeless=16` beside a vanilla count of 0 shows the
split working, and the same 0 beside a breakdown naming `minecraft:crafting_shaped` would show it
broken. A filter that can never match must not be able to look like a clean run.

### Menus open on more of a modpack, and opening one no longer kills the client · green against a third party

`s.menu()` was written against Twilight Forest and worked. Pointed at All the Mods 10 from a `.js`
file it failed twice, and both failures were the facet's:

- **It asked only the block for a `MenuProvider`.** `BlockBehaviour#getMenuProvider` returns null by
  default; vanilla blocks get an implementation from `BaseEntityBlock`, which forwards to the block
  entity. A mod with its own block hierarchy never overrides it while its block entity often *is* a
  provider — so the facet found vanilla-shaped blocks and missed most of a modpack, which is
  backwards for a framework whose subject is packs. It now asks the block first (nothing that
  answers is overridden, so no conditional provider is bypassed) and falls through to the entity.
- **Opening sent the vanilla open packet, and that ended the run.** The client rebuilds the menu
  from a data buffer the opener is expected to have written, and a generic caller cannot know what a
  mod's factory reads. Actually Additions' coal generator read a `BlockPos` out of a null buffer and
  took the client's packet listener down mid-suite: 9 scenes of 33 ran, and the verdict blamed
  coverage. The facet now builds the menu server-side — the mod's own `createMenu`, its real slots,
  its real click handler — and opens no screen, which is the half it already documented as its
  scope. Guessing "probably a BlockPos" would have fixed one mod and broken the next.

`hasMenuAt(dx, dy, dz)` is new, and is the reason the limit is now visible rather than folklore:
`openAt` throws, so it cannot be used to ask *whether* a block is reachable, and that is a pack
author's first question. Measured on All the Mods 10: **of 14 surveyed blocks the pack has, 5 expose
a menu and 9 do not** — 3 of the 5 being vanilla. Mekanism is in the second group
(`TileEntityMekanism` implements seventeen interfaces and `MenuProvider` is not one), while its
capabilities stay perfectly readable. The two seams are independent.

Twilight Forest's uncrafting-table scenes pass unchanged on the new open path.

### A client probe with no driver reports that, instead of taking the client down · green against a third party

`ClientProbes.arm` dereferenced `WorldDriverCommon.api()` on the assumption the driver was up in the
client process. It is not, in the runtime this framework exists for: a third-party mod's client has
no worlddriver and never should. Two different runs paid for the same missing check —

- **NeoForge's joining client** listed the driver and constructed nothing, so `api()` was null, the
  probe threw out of the client tick, and the gate could only say "the companion client wrote no
  results". True, and it names nothing. (That one was worlddriver's own bug, and is fixed above.)
- **Twilight Forest's client** has no driver class at all, and the guard could not even run: a
  `NoClassDefFoundError` on the tick after joining. Null-checking a class is still a reference to it,
  and so is a field typed `Consumer<DriverEvent>`. The client died, the server's suite ran with no
  player, and **twelve scenes skipped** — reported as a coverage problem three log files away from
  its cause.

Every worlddriver reference now lives in `DriverFeed`, which names the class as a *string* to test
for it and is never loaded by a runtime that answers no. Absence is a **skip** — a PASS carrying the
`skipped:` prefix and the flag, judged by the same contract as any other, so it counts as untested
rather than as tested-and-fine. Where the driver IS present the probe still executes and still
asserts the damage source survived the wire; worlddriver's own client topology reports
`[source=outOfWorld, lost=2.0]` unchanged.

### A companion client gets the harness installed too · green against a third party

`installMods` copied the harness jars into the server's run directory and no other. A topology with a
`companionRunTask` therefore launched a second game whose `mods/` was empty — which is not an error
anywhere: the client boots, joins, ticks, never arms a probe, and the run hangs until the server's
own budget ends it. Twilight Forest's production topology did exactly that on its first run.

The provision task now installs into the companion's run directory as well, resolved from the
companion `JavaExec`'s own working directory rather than guessed from the task name.

Doing that resolution lazily is what surfaced the second half: holding a `JavaExec` to read later
captures a `Task`, which the configuration cache cannot serialise. The directory is now read in
`afterEvaluate` as a plain `File`. The **companion mechanism itself** — a run task that starts
another task's spec as a child process — has the same shape and always did, so the run task declares
`notCompatibleWithConfigurationCache` with that reason instead of failing the build with a stack
trace. Capturing the companion's whole command line as values at configuration time would lift it,
and is in `TODO.md`.

### A pack's client topology can run on a real account · green against a third party

`--account <id>` makes a HeadlessMC account primary before launching; `--online` uses whichever one
it already has selected. Both exist for one reason: `hmc.offline=true` is not only "no account", it
*forces* `-lwjgl`. So an account is not a nicety for a modpack — it is the switch that turns the stub
off, and with it the whole class of load-time failures the previous entry describes.

- **Logging in is not ours to do.** The CLI never prompts for credentials, never stores a token and
  never writes one into a results file or a log. HeadlessMC already has `login` (Microsoft
  device-code) and `account`; run them from the game dir and the credentials stay in that dir's
  `HeadlessMC/auth/.accounts.json`, out of the machine's real `.minecraft`.
- **The session is HeadlessMC's too.** An earlier cut of this built the session itself and threaded
  it into the launch arguments — deleted. Owning a third party's auth flow to hand it back its own
  argument list is a maintenance liability for nothing.
- **`--launcher-jvm "<args>"`** puts arguments on the launcher JVM, because **Java ignores
  `HTTPS_PROXY`** and behind a proxy the login times out with nothing to point at. The CLI now warns
  when the environment variable is set and no proxy property was passed.

Measured on All the Mods 10, from a launcher-installed instance: 480 mod jars, 520 in the loader's
list, nothing removed, GREEN. This also retires the derived client directory the previous entry used
— a client built out of the *server* pack needs jars pulled to boot at all (`IrisSearch` ships there
without `iris`), and each removal makes the runtime less the pack: 2,199 advancements against the
server's 2,490. The launcher-installed instance reports 2,490.

### A skip is no longer reported as a pass, and never running anywhere is now RED · green against a third party

A skipped scene resolved as PASS and printed as `pass:`, which is how the All the Mods 10 suite came
to report GREEN over two of the six subjects it exists to test. `atm.advancementUnlocksForAPlayer`
(advancement unlock) and `atm.aQuestCanBeClaimed` (quest claim) were registered, reconciled against
the manifest,
counted in the footer — and skipped for want of a player in every run that has ever existed, because
the pack has only ever been run on a dedicated server. Every one of those runs was honestly green.
Nothing anywhere said the two subjects were untested.

- **`skipped` is a field on the scene record**, stated by the two writers, no longer inferred from a
  `"skipped: "` prefix on a prose reason. Omitted when false, so a run where nothing skipped produces
  byte-identical lines to before. The reason prefix is still written and still read: the field alone
  would report every skip in a pre-existing results file as a scene that ran, and the prefix alone
  breaks the day someone rewords the message. Each fails toward a false green on its own, in
  different situations, so both are checked.
- **The verdict prints `skip:` and closes with a `COVERAGE:` census.** Twilight Forest's dedicated
  run now says `12 scene(s) executed, 12 skipped on this topology and tested nothing` next to its
  GREEN. Still GREEN — a topology without a player is not a defect in a scene that needs one.
- **`stagewrightCoverage` (Gradle) and `--coverage <files>` (CLI) reconcile the runs against each
  other**: every scene any run registers must have EXECUTED in at least one of them. This is not a
  hand-maintained list of what must run where — such a list would be updated by the same person who
  just forgot to. It needs no maintenance: add a scene and it is covered by the check the moment it
  exists. Twilight Forest's two topologies: GREEN, 24 of 27. All the Mods 10's one: **RED**, naming
  the two scenes above.
- **`@SceneDef(mustSkip = true)` is the only exception, and it is an assertion, not an excuse.** For
  the three self-test scenes whose subject IS the skip — a `nosuchmod:` capability, a dimension no mod
  registers. Until now those were green whether they skipped or executed, which made them the only
  scenes in the suite incapable of failing. The verdict now requires the skip, and calls a run **DEAD**
  if one executes: what broke in that case is the absence detection every other suite's skips are
  trusted through, so the run's greens stop being evidence. That is the MUST_SWALLOW argument, applied
  where it always belonged.

Deliberately not done: a manifest annotation for "this scene must run here". It would have to be
per-topology, Twilight Forest shares one manifest between two, and it is exactly the hand-maintained
table this repo keeps learning to distrust.

### The recipe closure can see into modded recipe types · green against a third party

`closureOf` walked eleven items down from the ATM Star and stopped, because
`Recipe#getIngredients()` is a default method returning an empty list and no mod adding its own
recipe type has any reason to override it. So the walk never left the star/star-block compression
loop, reported zero leaves and zero unresolvable slots, and looked entirely healthy doing it. "The
pack's goal item is reachable" was, in practice, "the pack's goal item exists".

Every recipe that loaded from a datapack has a codec that read it, and `Recipe.CODEC` is a dispatch
codec keyed on the recipe type — so the same codec writes it back out, custom type and all, with no
knowledge of the mod required. The walk now re-encodes an ingredient-less recipe and follows every
item id in the JSON. Measured on All the Mods 10: **11 items → 12,904, 22 recipes → 28,787**, depth
10 → 15, 0 leaves → 3,123, and `unresolvable` still 0 — which is now a statement about 28,787
recipes instead of 22.

- **A separate channel, not a better `ingredientsOf`.** A JSON field naming an item is not a slot
  requiring one: a `"group": "bucket"` lands there too, and nothing in the JSON distinguishes them.
  So mentions can grow the graph but can never report an unsatisfiable slot, which is the one thing
  `uncraftable()` exists for. `Closure#inferred()` names the items that arrived this way — 1,035 of
  the 12,904, so 92% of the graph still came in through declared ingredients.
- Tag references are normalised whether they arrive as vanilla's `"#c:ingots/iron"` or as a mod's
  own `{"tag": "c:ingots/iron"}`. Without carrying the key down, the second form reads as an item id
  that does not exist and is dropped silently.

### A modpack's client topology launches on the real display · green against a third party

`--display-client <version>` runs the client already installed in the game directory, with a real GL
context. HeadlessMC still installs it — that part needs no account and works — but its *launch* is
always `-lwjgl`, and that is not usable for a pack:

- `hmc.offline=true` **forces** the stub ("You are offline, game will start in headless mode!"), and
  a headed HeadlessMC launch needs a real Minecraft account, which CI cannot have.
- Under the stub, a mod that reads image pixels while loading sees an all-zero image and throws —
  Supplementaries reading a palette strip through Moonlight, measured. NeoForge then dispatches setup
  a second time trying to recover and the run dies in twenty "already registered" errors that name
  nothing relevant. Removing the mod only surfaces the next one.

What this bought, on All the Mods 10: **advancement unlock and quest claim executed for the first
time.**
`atm.advancementUnlocksForAPlayer` passes; `atm.aQuestCanBeClaimed` failed on its first ever
execution because three of the pack's 546 startable quests are already complete the moment a player
joins — FTB Quests finishes a quest as soon as its tasks are satisfied, and a welcome quest's task is
satisfied by existing. The scene picked the first startable quest and asserted it was unclaimed. Now
it picks the first startable and *unfinished* one, and records how many were already done.

Also fixed here: the CLI reported "did not finish within 90 minutes" for a client that had crashed in
three. The wait now distinguishes timed-out from died, because the two send you to different places.

### Scenes can run OUT of the game process · green against a third party

A third home for scenes, beside in-process (`config/stagewright/scenes`) and the JUnit attach module.
`--attached <dir>` runs `.js` files in the CLI's own JVM, over the driver's RPC socket, against a held
server. **This adds one, it does not replace one**: the arena, the world pin, the leak audit and
byte-determinism cannot leave the server thread, and nothing here pretends otherwise.

- **`:stagewright-attached`, the vocabulary both homes share as one compiled artifact.** `Expect`,
  `SceneFailure`, `SceneSkipped`, `SceneOutcome`, `Clock`, `Terrain`, `Canary`, the Rhino runner, the
  prelude and the RPC client. Assertions report through a new `SceneReport`, which `SceneContext`
  implements — so there is exactly one `Expect` and a method that exists in one home exists in the
  other by construction. The design that preceded this had an example calling `.isAbove(60)`, which
  has never existed; a vocabulary maintained as prose drifts, and this is the version javac checks.
- **A NEW package (`…stagewright.contract`), not a slice of `…stagewright.scene`.** api owns that
  package outright and says why: split across two jars it is a hard `ResolutionException` at FML boot.
  The cost was ~20 import lines, paid once, against a class of failure that only shows up as a dead
  game.
- **`Scripts.unwrapOurs` is shared, and that is the point of sharing it.** Rhino wraps anything a
  script's Java call throws, so `instanceof SceneSkipped` on the raw throwable is always false — the
  bug that reported every skip in every pack as FAILED. Copying the runner would have reintroduced it
  verbatim in the second home, and it would have hidden just as long, because it is invisible on a
  failure and only shows on a skip.
- **The out-of-process home refuses what it cannot do, by name and with the reason.** Arena verbs,
  world writes, and the whole capability surface. `setBlock` is refused specifically because letting
  it through would be quiet: there is no slot allocator, no force-load, no sweep and no audit out
  there, so every attached scene would build on the last one's rubble and fail somewhere unrelated.
  And a refused `capability()` says plainly that this is NOT the mod being absent — `ServiceLoader`
  scans the GAME's classpath, so out here an installed mod and an uninstalled one look identical, and
  reporting the framework's own limit as a missing mod is the species of bug this repo keeps catching.
- **Waiting is `waitUntil(...)`, not `await(...).within(ticks)`.** Out of process every call is a
  round trip and the game ticks between any two, so a tick budget cannot be honoured — only
  approximated by wall clock, and wrongly: catch-up ticks run in ~3ms, not 50ms. A method that meant
  something different in each home would be worse than not having one. Poll counts are recorded.
- **A scene declaring `terrain` / `clock` / `dimension` is refused out of process rather than run.**
  Those shape the world it stands in; running the body anyway would assert against whatever world
  happened to be loaded, which is a result with no relationship to what the author wrote.
- **Empty registration is RED**, checked before the verdict sees it. The companion path judges without
  reconciling an expected-scene list, so a header plus a `done` footer and nothing between is a
  structurally perfect GREEN for a run that executed nothing.
- Verified on All the Mods 10 with a driver installed (`--mod`): three attached scenes and thirty
  in-process ones from one command, worst-wins GREEN, exit 0. Getting there cost three real bugs,
  each of which is now something the code says out loud:
  - **`--attached` on a pack with no driver hung for the full timeout.** The game diagnosed it at
    second thirty ("worlddriver-rpc.port does not exist") and the CLI would have waited another
    fifty-nine minutes. It now watches for that line and stops, explaining that `--scenes` needs no
    driver and `--attached` does.
  - **The websocket URI needs its `/rpc` path**, and without it the handshake does not fail — it
    times out. Five minutes of retries reported "the pack is probably still starting", which was
    confident, well-phrased and wrong. `:stagewright-junit`'s reader always had the path; two readers
    of one descriptor each learned it separately.
  - **A loaded chunk is not a ticking chunk.** The liveness scene put a fuelled furnace at spawn and
    watched it stay unlit while every read answered normally. In-process the harness pins the arena;
    out here a scene has to ask, so it now `/forceload`s first — which is also what
    `getForcedChunks()` actually reads.

## 2026-08-05

### Nothing ticked in a player-less suite after the first fifteen seconds · green against a third party

- `ServerLevel.tick` computes `!players.isEmpty() || !getForcedChunks().isEmpty()`, and when that has
  been false for **300 consecutive ticks** it jumps over both the entity loop and
  `tickBlockEntities()`. A dedicated topology has no player by definition, and the arena's pin is a
  runtime `TicketType.FORCED` region ticket — which is **not** what `getForcedChunks()` returns; that
  reads the `/forceload` saved data. So the pin that keeps the arena loaded contributes nothing to the
  emptiness test, and fifteen seconds into a suite every remaining scene runs in a world where nothing
  moves on its own.
- Everything else about such a level looks healthy: the server loop runs, chunks stay loaded, commands
  work, blocks can be placed and read back, and every chunk-status question — `getFullStatus()`,
  `isPositionEntityTicking`, `shouldTickBlocksAt`, `areEntitiesLoaded` — answers **true** and is
  correct, because chunk status is not what is being tested.
- Which made one bug look like three, differing only in WHEN a scene runs. worlddriver's entity scene
  is seventh and passed inside the window; its block-entity probe is around the hundred-and-ninetieth
  and never ticked. All the Mods 10 boots slowly enough that even the entity scene landed outside the
  window, so there the ENTITY half failed too — and the two halves were investigated as different
  problems, one of them as "a modded machine refuses every write".
- `StageWrightCommon.onServerTick` now calls `resetEmptyTime()` on every level, every tick. That is
  vanilla's own escape hatch — what `ServerChunkCache` calls for a force-load through the supported
  path — and all levels rather than the arena's, because PREP generates terrain before a scene's
  level is chosen.
- **Where that call lives is half the fix.** The first version put it on
  `StageWrightHarness.observeServerTick`, which is only reached once a harness exists — and under
  `holding()` no harness is built at all until `mc.test.run` asks for one. So it fixed the path that
  was already about to run a suite and left the hold window untouched, which is the window
  `:stagewright-junit` attaches into, and the one attached scripts are designed around. Keeping a
  level awake is a property of "StageWright is loaded", not of "a suite is mid-run"; both loaders
  register the tick hook at mod init, so it now covers every tick of every run. Verified from outside
  the game the way an attached client sees it: on a live `…FabricHold`, over RPC only, an armor stand
  summoned at Y=220 falls to the ground and a fuelled furnace goes `Test failed` → `Test passed` on
  `execute if block … minecraft:furnace[lit=true]`, with `:stagewright-junit` green on the same hold
  (40 tests, 0 failures, 5 skipped — the client half, correctly, on a dedicated hold).
- The self-test gained a scene that fails if that call is removed, and it is `required`: a **furnace**
  given fuel and something smeltable, because a hopper — the obvious probe, and the first one written
  — sits behind an eight-tick cooldown, an `ENABLED` blockstate and a container lookup, so "it moved
  nothing" has four causes and the probe cannot say which. A furnace lights on its first tick or
  never.
- Verified across every topology and both third parties, because the bug's whole signature was that
  it hid on some of them: worlddriver dedicated (both loaders), integrated and with-client all GREEN;
  Twilight Forest dedicated and integrated GREEN; **All the Mods 10 GREEN at 30 scenes**, where
  `entitiesLiveInTheArena` had been the standing TIMEOUT and now records `lastTickCount=6`,
  `fellBy=1.15` — the same numbers a lean runtime gets. The new probe reports
  `furnace.ticksToLight=2, hopper.pulled=true` identically on Fabric and NeoForge.
- **A command string can be as loader-specific as a class name.** The probe first read the furnace's
  `BurnTime` NBT and passed on NeoForge while reporting "no such value" on Fabric — NeoForge widens
  furnace burn time to an `int` so a modded fuel can outlast a `short`, vanilla keeps the `short`, and
  `data get` prints a short with its type suffix (`0s`). Everything reachable through `data get`
  carries that hazard, so a scene asserting on NBT should assert on a **blockstate** where one exists:
  `lit` is spelled the same in both runtimes and is what a player would look at. This is the same
  species as `Probe` refusing `net.minecraft.*`, arriving through a string instead of a class name.

### Every results file the verdict reads is now cleared before a run · green in the self-test suite

- `RunDirectory.provision` took a single results-file NAME under the game directory, so it was
  structurally unable to clear the **companion client's** results — those live in the companion's own
  run directory, and the verdict judges them. A companion that starts and dies before writing its
  header left the previous run's file to be judged: a complete, valid, GREEN one.
- Not hypothetical. `fabric/run-stagewright-joining-client/stagewright-client-results.jsonl` was
  found sitting on disk hours stale, holding a header, one PASS and a `done` footer — exactly the
  shape the verdict accepts.
- Signature is now `List<Path>`, and the provision task is fed the same `companionResultsFile`
  property the verdict task reads, so the two cannot disagree about which files a run is judged on.

### A `.js` scene that skips was reported as FAILED · green against a third party

- Rhino wraps any Java exception thrown out of a Java method a script called, so the guard in
  `JsScenes.invoke` — an `instanceof` against the raw throwable — matched neither `SceneFailure` nor
  `SceneSkipped`. A wrapped SceneFailure still reached `ctx.fail` and still reported FAIL, which is
  why this hid for as long as it did. On a SKIP it inverted the framework's central rule: a scene
  file asking for a player on a topology without one was reported **FAILED**, with
  `Wrapped …SceneSkipped: no connected player` as the message. Every scene file in every pack, for
  every absent mod, absent dimension and absent player.
- Found on All the Mods 10 by the scene that claims a quest, which is exactly the scene that has to
  skip on a server. Unwrapping now walks `WrappedException.getWrappedException()` and `getCause()`.
- Also `Equip.attribute(id)`, so a suite can assert what equipment DOES rather than only where it
  went. Twilight Forest: bare 0 → knightmetal 8 armour and 1 toughness → naga 7 → 0 again. Note the
  read has to happen a tick after the set — `LivingEntity` applies an item's modifiers when it
  notices the equipment changed, so reading in the same tick reports 0 for every material and looks
  exactly like a mod whose armour values were wiped.

### Three ways to wire in a capability, not one · green both ways

The SPI below (same day) opened the door for mods. Pointing it at a 452-mod pack showed the gap it
left: a pack author with no adapter had to hardcode a class name in *every* scene, and "write an
adapter" is right for one mod and absurd for a whole class of them — Mekanism, Thermal, Powah and
Industrial Foregoing share no API, but they share a *capability*.

- **`s.mods()`** — the mod list, installed by each loader's entrypoint. The ground floor of every
  conditional scene: `loaded`, `version`, `require` (skips), `any`/`all`, `count`. Not installed is a
  **failure**, never an empty list — an empty list makes every mod look uninstalled, so a pack's
  scenes would all skip citing a mod that is plainly there. That is StageWright's own wiring failing
  while wearing an absent mod's clothes, and it is the seventh instance of that species in this file.
- **Declarative capabilities** — `config/stagewright/capabilities/*.json`, beside the pack's scenes,
  conditioned on any of `mods` / `anyMods` / `classes` / `items` / `blocks`. What it buys is the
  indirection: the class name lives in one file instead of in every scene that reaches for it. A
  descriptor stating **no** condition is rejected at load — it would report itself available in every
  runtime, so scenes gated on it would run against packs without the thing and fail for a reason that
  has nothing to do with the pack.
- **Auto-detection** — StageWright ships descriptors (`mekanism`, `ae2`, `create`, `ars_nouveau`,
  `apotheosis`, `mysticalagriculture`) in `data/stagewright/capabilities.json`. A pack configures
  nothing: the ones it has resolve, the ones it lacks skip with a reason. Each is gated on a **class**
  read out of the mod's own jar rather than on a mod id, because the class existing is exactly the
  condition under which its probe works. One resource file, not a resource directory — enumerating a
  classpath *folder* works from an exploded build and not from a jar, which is the shape of bug that
  is fine on the dev machine and broken for every user.
- **A pack descriptor with a built-in's name REPLACES it** — the opposite of the duplicate-name
  rejection for providers, and deliberately: two mods clashing is an accident, a pack correcting us is
  the only move it has when a fork moves a class.
- **Generic block capabilities on NeoForge** — `itemhandler`, `energy`, `fluids`, with the contracts
  (`BlockInventory`, `Energy`, `Fluids`) in `:stagewright-api` so a common-source scene can hold the
  type, and a Fabric implementation over that platform's storage API would satisfy existing scenes
  unchanged. Written against the *capability*, so one facet covers every tech mod including ones
  written after it. Vanilla containers implement `itemhandler`, which is what lets the mechanism be
  proved with no mod installed and only its reach need a pack.
- **A write through a block capability tries every side.** Found on ATM10, and the symptom names no
  cause: a Mekanism energy cube answered `capacity = 1_600_000` and `canReceive() = true`, and took 0
  of every 1000 offered — identical before ticking and ten ticks later, so the obvious theory (the
  block entity had not initialised) was wrong. A block capability is looked up with a `Direction`, and
  `null` — "no particular side" — usually returns an internal view carrying the machine's real
  numbers, which is why every READ worked, while insertion is governed by the machine's side
  configuration and granted only on the faces configured for input. Writes now walk the no-side view
  and the six faces, stopping at the first that accepts, so nothing is inserted twice. A scene
  asserting that a machine charges should not first have to be a scene about that machine's side
  configuration. Fluids went from taking 0 mB to taking 1000 the moment writes walked the sides.
  Mekanism's energy cube still refuses on all seven handlers, with `blacklistForge = false` in the
  pack's own config — that is one machine's policy rather than a defect in this facet, and the way to
  tell those apart is to assert insertion against two unrelated mods instead of one.
- **`mergeServiceFiles()` in both shadow jars.** `META-INF/services/…CapabilityProvider` now exists in
  two modules, and shadow's default is last-writer-wins per path — without it the jar ships one file
  and every capability in the other reports itself absent, indistinguishable from an uninstalled mod.
- One authored folder, split by extension: `.js` to `scenes/`, `.json` to `capabilities/`, in both the
  CLI and the Gradle plugin. Two flags and two directories would put a build tool's filing habits on
  somebody whose whole premise is not having one.
- Verified from both ends, because either alone proves nothing — always-available and never-available
  each pass one of them. worlddriver (46 mods, both loaders): all six shipped descriptors registered
  and none available, its own `pack:driver` descriptor resolving from a `.json` beside `pack.js`,
  `itemhandler` reading a vanilla chest on NeoForge and skipping with a reason on Fabric. ATM10 (452
  mods in `mods/`, 488 in the loader's list once nested jars are counted): five shipped descriptors
  detected with nothing configured, two pack descriptors resolving, a third overriding the shipped
  `mekanism`, `fluids` filling a Mekanism tank with 1000 mB, and `itemhandler` reading and refusing on
  a Mekanism energy cube's own slots. That cube's ENERGY insert is recorded rather than asserted —
  see above.

### Capability seam: mods and packs can wire in what Minecraft does not have · green both ways

- **Was:** Curios and FTB Quests were hardcoded reflection inside `:stagewright-api`. A third mod
  meant editing StageWright, releasing it, and every consumer bumping a version. A pack author with a
  mod we had never heard of had nothing at all.
- **Now:** `CapabilityProvider`, discovered by `ServiceLoader` exactly as `SceneProvider` is, so a mod
  ships its adapter in its own jar. Scenes reach it as `s.capability("mymod:rituals")` — Java or JS,
  same object, no prelude change and no new verb. Absent is a recorded **skip**, matching
  `SceneContext.player()`: an uninstalled mod is not the pack's defect and must not read as a pass.
- Discovery instantiates each provider inside its own try/catch on `Throwable`, so an adapter that
  cannot load because its mod is absent is recorded with the reason and its neighbours still load.
  That is what lets adapters be written **typed** against their mod's real classes. The two shipped
  ones (`curios`, `ftbquests`) stay reflective only because they ride in the harness jar, which is
  present whether or not those mods are.
- `s.probe(className)` is the hatch for a pack author with `.js` files and no build. It **refuses
  `net.minecraft.*`** — correctness, not caution: a production Fabric jar carries intermediary names,
  so a by-name call there would pass on NeoForge and throw on Fabric, while a mod's own class names
  are never remapped. Non-primitive returns come back wrapped in another `Probe`, so a scene cannot
  end up holding a bare Minecraft object to call a method on.
- Verified from both ends, because either alone proves nothing: worlddriver's suite has **neither**
  mod (registry works, absence skips), ATM10 has **both** (a `.js` file with no Java reaches
  adapters a mod shipped).
- `capabilities()` (available) and `capabilityProviders()` (loaded) are separate calls. Conflating
  them is a silent failure: a runtime where discovery found nothing answers every `hasCapability`
  question identically to one where it found providers whose mods are absent, so a dropped service
  file would look exactly like an uninstalled mod. The first version of the self-test had that hole.

### `installMods` on a topology: how the harness reaches the game · green against a third party

- **Was:** nothing. Under architectury-loom `modLocalRuntime` covers it, and worlddriver — the only
  consumer for a long time — is a loom build, so the hole was invisible.
- **Now:** each topology takes `installMods`, and the plugin's provision step copies those jars into
  the run directory's `mods/` using the same `engine/ModInstall` rules the CLI applies to a modpack
  (sweep what a previous install left, then copy, then record a ledger).
- The reason it must be `mods/` and not the classpath: ModDevGradle has no `modLocalRuntime`, and
  adding the harness to `runtimeClasspath` instead gets it discovered and then claimed as a plain
  game library. FML logs the jar by name, the mod never enters the mod list, and the run boots,
  ticks, writes nothing and reports ENV "the game never armed" over a log with no error in it. That
  cost a 20-minute gate run on the first ModDevGradle consumer.

### PREP waits on progress, not on a tick count · green against a third party

- **Was:** `PREP_BUDGET_TICKS = 200`, a fixed ceiling tuned on vanilla.
- **Now:** PREP gives up when the number of ready arena chunks has **stopped moving** for 200 ticks,
  with a 6000-tick backstop. How long an arena takes to generate is a property of the pack — 100k
  blocks out through fifty structure mods is not vanilla — so a fixed total budget was a statement
  about somebody else's mod list. All the Mods 10 ENV_FAILed on one run at ~10s and passed the next
  at ~9s, landing on whichever scene drew the slow arena, which reads as a different bug every time.
- The ENV_FAIL message now names how many of how many chunks were ready, how long that number had
  stood still, and where — because the old one said only that 200 ticks had passed.

### `Closure.opaque()`: the recipe walk states its own incompleteness · green against a third party

- `Recipe#getIngredients()` is a DEFAULT method returning an empty list, so a modded machine recipe
  answers "no ingredients" rather than refusing to answer. Walking down from ATM10's ATM Star reached
  11 items through 22 recipes with zero leaves and zero unresolvable slots — it had never left the
  star/star-block compression loop and looked entirely healthy doing it.
- Not fixable from outside (there is no second interface to ask), so it is reported: `opaque()` names
  the recipes the walk could not see through, and an empty list is what makes the rest of a closure
  mean anything.

### `Advancements.parentOf` · green in the self-test suite

- Progression is a tree and the tree is the design: which boss opens which tier is stated nowhere
  except in the parent links. A suite that only asks `registered()` stays green through a tier
  reparented onto the wrong predecessor — the mod still completes, and the gate it was supposed to be
  behind is simply open early.

### A menu no longer leaks into the next scene's inventory · green against a third party

- Closing a container returns its contents to the player, and half a menu's slots ARE the player's
  inventory — so a scene that only called `menu().put(0, …)` ended with the item in the player's bag,
  where the next scene inherited it. The arena audit could not see it: it audits the arena, and the
  player is not in it. Found in a Twilight Forest run, where the scene after an uncrafting-table
  scene reported `47=minecraft:dirt` among its menu contents.
- Opening a menu now takes the same inventory snapshot `Items` does, through `Items` so there is one
  rule rather than two that can disagree.
