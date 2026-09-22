# Orchestration contract

The interface between whatever launches the game and the scene harness inside it. Two
independent programs meet here: a supervisor — the Gradle plugin, the command-line runner, or
a continuous-integration script — and the harness that runs inside the Minecraft JVM. They
share no code and no classpath, so everything they agree on is stated here as literal file
names, literal JSON keys and literal exit codes.

Both halves of the reference implementation are in this repository. The harness is
`common/src/main/java/net/magicterra/stagewright/harness/`, and the supervisor's side is
`engine/src/main/java/net/magicterra/stagewright/engine/`, whose `Verdict`, `Manifest`,
`Coverage` and `RunDirectory` classes are shared verbatim by the Gradle plugin and the CLI so
that two front ends cannot judge the same run differently.

## The run directory

A run happens in one directory, which is the game process's working directory. The supervisor
prepares it before the game starts; `RunDirectory.provision` is that preparation.

Provisioning deletes the world (`world/` for a dedicated server, `saves/` for a client), every
results file the run will be judged on, the heartbeat and endpoint descriptor beside each of
them, and the endpoint descriptor in the run directory itself. Deleting the world is not tidiness. A reused world
still holds the blocks a previous run placed and the shafts a previous run dug, so scenes fail
in ways that are indistinguishable from defects in the code under test, and which scenes fail
varies between runs, so it reads as flakiness.

Provisioning then forces a small number of settings, and each is forced rather than defaulted
because a scene run cannot be correct without it:

| File | Key | Value | Why |
|---|---|---|---|
| `eula.txt` | `eula` | `true` | Under loom a fresh server directory otherwise exits before any mod initialises. |
| `server.properties` | `online-mode` | `false` | A dev client has no Mojang session, so an online-mode server rejects its login forever. |
| `server.properties` | `level-seed` | a fixed value | Without it the world is a different world every run, and any scene standing on generated ground asserts about whichever landscape it got. |
| `server.properties` | `sync-chunk-writes` | `false` | With the vanilla default every region write is an fsync; the level's IO thread falls behind from the first scene and the backlog is held on the heap until the run dies of `OutOfMemoryError` reported as a scene failure. |
| `options.txt` | `pauseOnLostFocus` | `false` | Vanilla singleplayer pauses on window deactivation, and the pause screen then sits underneath every later assertion. |
| `options.txt` | `onboardAccessibility` | `false` | A fresh game directory otherwise opens accessibility onboarding ahead of the title screen. |
| `options.txt` | `narrator` | `0` | Nothing should attempt text-to-speech on a CI machine. |
| `options.txt` | `enableVsync` | `false` | With vsync on, a client whose window is not being presented gets frames at about 1 Hz, and Minecraft runs at most ten game ticks per frame — so the client ticks at half the server's rate and tick-budgeted scenes fail in a body-shaped way. |

Every other line in those files is left exactly as it was. The seed constant is written twice —
once in `RunDirectory` and once in `ClientDirector` — because the two live in builds that cannot
see each other, one having no Minecraft on its classpath and the other unable to run outside the
game. That the two agree is the contract; the value itself is arbitrary.

Provisioning also installs authored content by extension: `.js` files from a supervisor-named
source directory go to `config/stagewright/scenes`, `.json` files to
`config/stagewright/capabilities`. Both targets are cleared first, so a file deleted from the
source stops being loaded.

## Starting the game and arming the harness

The supervisor does not build the launch. It runs a task the host build already declares — the
dev bootstrap for NeoForge under ModDevGradle, for Fabric under loom — because that plugin is
the only thing that knows how to start the game it supports. What the supervisor contributes is
a set of JVM system properties.

| Property | Effect |
|---|---|
| `stagewright.autorun=true` | Build the harness as soon as the server reaches STARTED and run the suite. |
| `stagewright.awaitPlayer=true` | With autorun, hold the suite back until a player has joined, then grant every player on the server operator rights and start. For the topology where a real client joins a real dedicated server. |
| `stagewright.hold=true` | Arm everything and run nothing. The suite waits to be triggered, and does not end the process when it finishes. |
| `stagewright.results=<name>` | The file name the harness writes, relative to the run directory. |
| `stagewright.scenes=<patterns>` | Narrow the run — see *Filtered runs*. |
| `stagewright.endpoint=<path>` | Publish an endpoint descriptor at that path once the run is genuinely attachable. See the [instrument contract](instrument-contract.md). |
| `stagewright.topology=<name>` | Recorded verbatim in the descriptor so an attached test knows which face it holds. |

Hold is a separate property rather than `stagewright.autorun=false` because the two are set by
different people. Autorun belongs to a checked-in line in the host build's run configuration;
a hold is a decision made at a command line about a run configuration nobody is editing.
Expressing one as the negation of the other puts two definitions of one key on one command line
and makes the outcome depend on which the JVM reads last — which does not fail, it runs the
whole suite underneath whatever had attached.

With neither `stagewright.autorun` nor `stagewright.hold` set, the runtime arms and waits for
the `mc.test.run` verb, which is how a supervisor triggers a suite from outside the process.
That verb answers `{accepted: true, scenes: N}` and is idempotent: a second call throws rather
than running the suite twice, and the results file's footer remains the only completion signal.
The verb is registered only where the driver is present; a runtime without it is autorun-only.

The harness halts the server once its registry drains, so under autorun "the run task returned"
is a real completion signal rather than a timeout. Under a hold the harness deliberately does
not halt: a hold outlives its suite, and halting would take the endpoint down underneath
whatever attached to it.

**The game's exit code is never a verdict.** A dedicated server that halts cleanly exits zero
whether every scene passed or every scene failed. The results file is the only thing that
carries an outcome.

## Scene discovery

`Scenes.all()` builds the registry in three concatenated parts, and the concatenation order is
the execution order:

1. **Built-ins.** A short fixed list compiled into the harness: a handful of walking-skeleton
   and self-check scenes plus the canaries.
2. **Service providers.** Every `net.magicterra.stagewright.scene.SceneProvider` found by
   `ServiceLoader`, in discovery order, each contributing its `scenes()`. Registration is a line
   in `META-INF/services/net.magicterra.stagewright.scene.SceneProvider` naming the
   implementation class. A provider that declares no `@SceneDef` methods and does not override
   `scenes()` throws rather than contributing nothing quietly.
3. **Script scenes.** Every `.js` file under `config/stagewright/scenes`, in file-name order.
   Last, so that adding a pack file cannot shift the origin slots of the compiled scenes it runs
   beside. If that directory holds files and Rhino is not on the classpath, the run fails rather
   than reporting green over scenes that never executed.

The registry is resolved once, on the server thread, so that `ServiceLoader` sees the correct
context class loader.

**Scene names are globally unique.** The harness rejects a duplicate before writing the suite
header, because the verdict keeps a last-wins map of scene records and a duplicate would let a
later record mask an earlier FAIL as a pass. The rejection throws during harness construction,
so the game dies before any header exists and the supervisor reports ENV rather than RED.

## Coordinate pinning

Every scene gets its own plot on a fixed grid, far from spawn so that a flat world's spawn
chunks cannot overlap an arena:

```
origin(slot) = (100000 + slot × 512, 200, 100000)
```

Slots are assigned in two passes. Explicitly pinned scenes claim their slot first; the rest are
given the lowest unclaimed slot in registry order. With no pins in the registry this reduces to
slot equals registry index.

`Scene.withOriginSlot(int)` — or `@SceneDef(originSlot = n)` — pins a scene to a fixed slot, and
**an explicit slot must be at least 1024.** A low pin would displace the automatic slots it was
meant to be independent of, so the harness rejects it during construction, as it does a
collision between two explicit pins.

Pinning exists because automatic slots move. Adding or removing any scene shifts every later
automatically-assigned scene to a different origin, and for a scene whose result depends on
double-precision physics that is a change in the measurement. For most scenes it does not matter
— an arena is self-contained, and which grid cell it occupies changes nothing — so pinning is
opt-in and the reader should treat an explicit slot as a statement that this scene's numbers are
positional.

Once a pinned scene's baseline is used as a regression threshold, moving its slot is a breaking
change: the baseline must be re-measured in the same commit.

## Chunk-radius declaration

`Scene.withChunkRadius(int r)` — or `@SceneDef(chunkRadius = n)`, default `1` — declares how
large a force-loaded window the scene needs. The window is `(2r+1)²` chunks centred on the
origin's chunk. Because the grid step is a multiple of sixteen, an origin sits on a chunk corner,
so the default `r = 1` covers relative coordinates `[-16, +31]` in both horizontal axes rather
than a symmetric range around zero.

The declaration is not inferred. The harness does not guess a scene's footprint, and a scene
that builds or walks beyond its window may read air from a chunk that has not arrived — a
failure that looks like physics drift or an environment problem rather than a loading race.
`Arena` checks a whole footprint before writing any of it and names the `chunkRadius` that
would fix it; a scene writing block by block outside the window gets no such warning.

The ticket the harness places is wider than the declared radius, because a region ticket assigns
a chunk's neighbours a lower level than the chunk itself and only the interior reaches
entity-ticking. Getting the radius wrong therefore surfaces as that scene failing PREP, not as a
scene that runs against half-loaded ground.

## Timing guarantees a scene can rely on

**Results are written only at scene boundaries** — suite start, after a scene completes, suite
end. Never during a scene's ticks. Synchronous file IO on the server thread has broken a
byte-deterministic arena before.

**Tick budgets start after the tick cadence settles.** A freshly-started `MinecraftServer`
carries accumulated tick debt and runs unthrottled catch-up ticks at roughly 3 ms each until it
is caught up, instead of the steady 50 ms rhythm. A wall-clock-bound wait costs two to three
times as many ticks inside that burst as it does outside, which makes every tick budget written
against normal cadence fragile. So `StageWrightCommon.onServerTick` withholds ticks from the
harness until ten consecutive server ticks have been at least 40 ms apart, then logs
`testkit: tick cadence settled after <N> server ticks (tick debt drained)`. If that has not
happened within 1200 observed ticks the harness arms anyway with a warning, so a pathological
host cannot hang the suite forever.

Only the forwarding is gated. Harness construction, the tick-pure `within` contract and the PREP
budget are untouched — because `harness.tick()` is simply never called before settling, every
budget naturally counts from the first settled tick. The only visible difference is that one
extra log line, on both loaders.

**A scene's arena is entity-ticking before its body runs.** PREP waits for it. What PREP measures
is *progress*, not elapsed time: how long an arena takes to generate is a property of the pack
being tested, so a fixed total budget would be a statement about somebody else's mod list. While
chunks keep arriving, waiting costs only wall clock; once the count has not moved for 200 ticks,
more waiting cannot help and the scene is recorded `ENV_FAIL` with a report of what the chunk
system was holding. A ceiling of 6000 ticks backstops a chunk system that dribbles forever, and
is deliberately far above any real arena.

A scene may opt out of that wait with `Scene.withArena(false)`, for a scene that plays in the
live world and never visits its plot. The arena is still allocated, force-loaded and audited;
only the wait is dropped.

**Levels keep ticking.** Vanilla stops running the entity loop and `tickBlockEntities()` on a
`ServerLevel` that nobody has been in for 300 consecutive ticks, and a dedicated topology has no
player by definition. Nothing about such a level looks stopped: the server loop runs, chunks stay
loaded, commands work, blocks can be placed and read back. StageWright calls vanilla's own
`resetEmptyTime()` on every level every tick, for as long as the mod is loaded — a property of
"StageWright is installed" rather than of "a suite is running", because a hold takes far longer
than fifteen seconds to become attachable and everything that attaches to one would otherwise
drive a world in which nothing moves.

**The world is held still for the suite.** Before the header is written, `WorldPin` freezes the
clock at midnight and turns off `doDaylightCycle`, `doWeatherCycle` and `doMobSpawning`, clearing
the weather. A scene can ask for a different time with `Scene.withClock`. The pin is released
after the footer is written, so a held server hands the world back to whatever is using it. What
was pinned is recorded in the suite header, because a run that froze the clock and three game
rules and then reported a bare pass would be claiming more than it proved.

**A world that stops delivering ticks ends the scene anyway.** Every other budget here counts
ticks, which is right for everything a scene asserts about and wrong for the one failure a scene
cannot survive: a tick budget in a stopped world never expires, so no record is ever written and
the run dies as a wall-clock kill with no verdict and no name. `TickStarvation` re-opens a
wall-clock window at every scene boundary and ends a starved scene as TIMEOUT, naming why. A
separate watchdog thread — which holds no lock the tick needs — covers the case where the last
tick has already happened and nothing on the server thread will ever run again: it writes the
running scene as TIMEOUT with a thread dump in the reason, writes a matching footer, and halts.

## The results file

One JSON object per line, UTF-8, in the run directory. The name is
`stagewright-results.jsonl` unless the supervisor renamed it with `stagewright.results`.

Renaming has to reach the writer. A supervisor flag that changed only which file the verdict
opened produced a complete, green results file and a verdict of "the run wrote no results"
pointed at a path nothing was ever going to write — which is also the verdict a pack gets when
the framework jar failed to load, and sent every reader to check mod loading.

### The header

Exactly one, first:

```json
{"type":"suite","loader":"neoforge","registered":[{"name":"...","required":true,"canary":"NONE"}]}
```

| Key | Type | Presence | Meaning |
|---|---|---|---|
| `type` | string | always | `"suite"` |
| `loader` | string | always | `"fabric"` or `"neoforge"` |
| `registered` | array | always | Every scene this run means to execute, in execution order. |
| `registered[].name` | string | always | The scene's globally unique name. |
| `registered[].required` | boolean | always | `false` means a FAIL or TIMEOUT is recorded without failing the run. |
| `registered[].canary` | string | always | `NONE`, `MUST_FAIL`, `MUST_TIMEOUT`, `MUST_SWALLOW` or `MUST_SKIP`. |
| `filter` | string | only when narrowed | The pattern this run was narrowed by. |
| `worldPin` | string | only when pinned | One line naming the world state held still. |

The two optional keys appear only when they have a value, so a run that pins nothing and filters
nothing produces a byte-identical header to one written before either existed. A stream that
does not pin a world — an out-of-process run — states that by omitting the key rather than by
writing something untrue.

Every string field is escaped, including the ones that carry a constrained vocabulary today. A
corrupt header is the worst line to produce, because the verdict drops lines it cannot decode
and the run then reports "the game never armed" instead of naming the real problem.

### Scene records

One per executed scene:

```json
{"type":"scene","name":"...","outcome":"PASS","ticks":41,"wallMs":2130,"reason":""}
```

| Key | Type | Presence | Meaning |
|---|---|---|---|
| `type` | string | always | `"scene"` |
| `name` | string | always | Matches a `registered[].name`. |
| `outcome` | string | always | `PASS`, `FAIL`, `TIMEOUT` or `ENV_FAIL`. |
| `ticks` | number | always | Ticks the scene body observed. |
| `wallMs` | number | always | Wall-clock milliseconds. |
| `reason` | string | always | Free text; empty on an unremarkable pass. |
| `skipped` | boolean | only when true | The scene resolved without testing its subject. |
| `data` | object | only when non-empty | Values the scene attached with `SceneContext.record`. |

`reason` is free text and the harness feeds raw exception messages into it, so it is escaped:
an unescaped newline would split one record across two physical lines and break a line-oriented
parser.

`data` values are rendered as JSON numbers when they are numeric and as strings otherwise — a
measurement that arrives quoted cannot be compared or plotted without every consumer re-parsing
it, which is the reason a scene recorded it. `NaN` and the infinities are not JSON and are
written as strings, because emitting them raw produces a line the verdict drops, which silently
turns a scene record into a report about a different problem.

**A skip is a PASS.** An absent mod, dimension or player is not a failure, so a skipped scene
resolves as `"outcome":"PASS"`. That means `outcome` alone cannot distinguish a scene that ran
from one that did not, and **a consumer reading these files by `outcome` alone counts skips as
coverage.** Two discriminators exist and both must be read, because each fails toward a false
green in a different situation: the `skipped` field is what the writers state outright, but it
is omitted when false, so a file written before the field existed is indistinguishable from one
where nothing skipped; the `"skipped: "` prefix on `reason` is the older contract and survives
that, but it is prose, so rewording the message would silently turn every skip into apparent
coverage.

### The footer

Exactly one, last:

```json
{"type":"done","scenes":<count>}
```

`scenes` is the number of scenes the harness executed, which excludes any `MUST_SWALLOW` canary.
**A missing footer means the harness died mid-run and is judged RED.** A footer whose count
disagrees with the number of scene records on disk is `TRUNCATED` — a file-level integrity
check, distinct from the name-level one, which catches a record that was counted but never
reached the disk.

A verdict implementation that finds no `scenes` field in the footer skips that check rather than
failing: the contract does not oblige a third-party harness to carry it.

Failure to write the results file throws. A run whose outcomes are not being recorded must never
be allowed to look green.

## The heartbeat file

`stagewright-progress.json`, beside the results file. A single JSON object, **rewritten in place
every second** rather than appended:

```json
{"type":"progress","scene":"wd.journey09Iron","ticks":41234,"ticksInWindow":3,"windowMs":90000,"atMs":1786350908936}
```

It answers the question "no done footer — the harness died mid-run" cannot: which scene was
running, whether the world was still ticking when it stopped, and how long before the kill it
last said so. Without it the next step is to open a multi-megabyte game log and search backwards
for the last scene mentioned.

`ticksInWindow` and `windowMs` are written as the raw pair rather than as a rate, because the
quotient destroys the distinction that matters: a world delivering nothing and a world delivering
a trickle both round to roughly zero, and they are different faults in different halves of the
process.

Three rules follow from what this file is for:

- **It is truncated, never appended.** A run of any length costs a constant amount of disk, and
  reading it needs no scan — whatever is in it is the last thing that was true.
- **It is read only when the footer is missing.** The file outlives a healthy run, so after a
  complete suite it names whichever scene happened to be last. Printed on an ordinary RED it
  would point at a scene with no connection to the failure.
- **A failure to write it is swallowed**, which is the opposite of the results file's rule and
  for the opposite reason: it carries no verdict, so a full disk must not be allowed to end a run
  that is otherwise fine.

It is deleted at provision along with the results file it sits beside. A leftover heartbeat is
worse than none, because it still names a scene — the previous run's.

## Reconciliation

A run is judged against the header it wrote, and optionally against a manifest checked into the
repository.

### Against the header

These checks need no manifest and always run.

| Report | Trigger | Verdict |
|---|---|---|
| `SWALLOWED` | A registered scene has no record. | RED |
| `DRIFTED` | A record names a scene that is not registered. | RED |
| `DUPLICATE` | Two records share a name; last-wins could mask a FAIL. | RED |
| `TRUNCATED` | The footer's count disagrees with the records on disk. | RED |
| `FAIL` | A required scene did not pass. | RED |
| `fail(optional)` | A scene declared `required = false` did not pass. | unaffected |
| `COVERAGE` | Some scene skipped; names how many executed and which did not. | unaffected |

Being swallowed REDs a run whatever the scene's `required` flag says. An optional scene is
allowed to fail; it is not allowed to vanish, because that is a framework integrity violation
rather than a test result.

### Against the manifest

The manifest — conventionally `scripts/stagewright/expected-scenes-<loader>.txt` — is a checked-in
list of the scene names a run must contain. One name per line; `#` starts a comment and the rest
of the line is discarded; blank lines are ignored; commas separate several names on one line, so
the two spellings cannot disagree. An empty manifest is refused rather than treated as "expect
nothing", because a gate armed with an expectation of nothing would pass any run.

Reconciliation runs in both directions, and the second half is the one that closes the hole:

- **`MISSING-EXPECTED`** — a manifest name is not in `registered[]`. This catches the assembly
  layer itself. Scene providers arrive through `ServiceLoader`, and if that thread breaks —
  the services file was left out of the jar, the jar is not on the classpath, a typo in the
  class name — the downstream scenes vanish from the registry *entirely*, and the suite still
  runs the scenes it did find and reports a self-consistent pass. Nothing inside the game can
  notice that a provider it never heard of was not discovered, which is why this assertion is
  made from outside.
- **`UNDECLARED`** — a scene is registered but is not in the manifest. Without it, a scene added
  to a provider and forgotten in the manifest would be accepted silently, which is precisely the
  omission the manifest exists to catch.

`UNDECLARED` is scoped to the namespaces the manifest itself uses — the prefixes before the first
dot in its own names — so that StageWright's own built-ins stay out of a consumer's verdict
without the consumer configuring anything. One consequence is worth stating: the *first* scene
under a new prefix is reconciled in neither direction, because neither it nor its prefix is in the
manifest. It still REDs if it fails, so this is an accounting gap rather than a correctness one.

**The manifest anchoring rule: a scene's name is added to the manifest in the same commit that
registers the scene.** The manifest and the scene are then reviewed together, and a run whose
provider silently stopped contributing is caught by the direction that does not depend on anyone
remembering. A manifest edited at judging time is a judge changed mid-trial.

### Across runs

One run cannot answer whether a scene ever executed anywhere. Leaving skips green is right for a
single run — a scene that needs a player is not broken because the dedicated topology has none —
but the same reasoning makes a skip invisible across all of them, and a suite whose player scenes
skip on every topology it runs reports a pass over subjects it has never once exercised.

`stagewrightCoverage` reconciles every topology's results at once against one invariant: **every
scene registered by any run must have executed in at least one run.** Deliberately not a
hand-maintained list of what must run where — such a list would have to be updated by the same
person who just forgot to. Add a scene and it is covered the moment it exists; add a topology and
the check gets easier to satisfy; delete the only topology that could run something and the check
REDs naming it.

The single exemption is declared at the scene, by the author who knows why:
`@SceneDef(mustSkip = true)`. That is an assertion rather than an excuse — the verdict then
*requires* the scene to skip and calls the run DEAD if it executes.

The coverage task deliberately does not depend on the topologies' run tasks. A topology that
REDs aborts the build with an exception, and that is exactly when this cross-run report has the
most to say. A missing results file is reported rather than passed over: passing over it would
shrink the union of "ever executed" and turn one run's absence into an accusation against the
runs that did happen.

## Exit codes

The four codes are distinct on purpose and are ordered by severity, so that a topology with two
results files can take the worse of the two.

| Code | Label | Meaning |
|---|---|---|
| 0 | GREEN | Header and footer present, every required non-canary scene passed or failed while optional, every canary landed on the outcome it declared. |
| 1 | RED | A required scene failed, a scene was never recorded, records drifted or duplicated, the footer disagreed with the file, or reconciliation against the manifest failed. Also: no footer. |
| 2 | DEAD | A canary landed on the wrong outcome. The framework can no longer be trusted to catch failures, so the whole run's results are void rather than merely bad. |
| 3 | ENV | No suite header — the game never armed. Also reported when the results file is absent entirely. |

DEAD is not a louder RED. **A RED says the code is broken; a DEAD says the measurement is.** ENV
is never reported as RED for the same kind of reason: "the server did not start" must not read
as "your code is broken".

The canary rules that produce DEAD:

- A `MUST_FAIL` or `MUST_TIMEOUT` canary with no record at all, or with any other outcome.
- A `MUST_SWALLOW` canary that produced a record. It is registered and deliberately never
  executed, so the reconciler must flag exactly that omission; a record means the gate that
  detects a swallowed scene is not working.
- A `MUST_SKIP` canary that executed instead of skipping. What broke is the absence detection
  every other suite's skips are trusted through, so no skip anywhere in the run can be believed.
  (A `MUST_SKIP` canary with no record at all is RED, not DEAD: a skip *is* a record, so no
  record means it never ran.)

When a topology declares a companion client that writes its own results, the two files are judged
separately — each is a self-contained stream with its own header, footer and scene list, and
concatenating them would produce something the contract has no meaning for — and the worse code
wins. A declared companion file that is absent is ENV rather than a pass: a client launched to
assert something and then silent is indistinguishable from one that never started.

## Filtered runs

`-Pstagewright.scenes=<patterns>` narrows a run to the scenes whose names match, so that
iterating on one scene does not cost a whole suite. Comma-separated; `*` is the only
metacharacter and everything else is literal, so a name containing a dot needs no escaping.

The narrowing happens before the harness exists, so the header's `registered` list is the
narrowed one and every rule above keeps working against what the run meant to do. The header
also carries the pattern, and the verdict reads it back:

- Manifest reconciliation is **skipped**. Under a filter every unmatched name is legitimately
  absent, so reconciling would report the whole manifest as missing and bury the real outcome.
- The verdict label is suffixed `FILTERED — not a gate result`, because a filtered run's exit
  code is about the scenes it chose to run and a zero here does not mean the suite passed.
- **A pattern that matched nothing is RED.** That is the most dangerous typo in the system:
  otherwise it would be a green run of an empty suite, the failure that looks most like success.

Two further facts make a filtered run unsuitable as evidence. Canaries are filtered like
anything else, so a narrow run usually carries no self-check at all. And origin slots are
assigned *after* filtering, so a scene that sits at slot 6 in the full suite runs at slot 0
alone — several thousand blocks away, on different ground. A scene passing alone is therefore not
evidence about the run it failed in. Reproduce with the full suite; filter to iterate on a scene
you are writing.
