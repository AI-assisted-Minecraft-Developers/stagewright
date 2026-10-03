# Running the checks

This guide is about running a scene suite and reading what comes back. It assumes the Gradle plugin
is applied and at least one topology is declared; see [the Gradle plugin](gradle-plugin.md) for that,
and [Topologies](topologies.md) for what each shape can establish.

## Where the checks live

**StageWright does not test itself, and there is nothing to run from its repository.** A check belongs
to the *consuming* project: it is a task in that project's build, declared by that project's
configuration, driving that project's run task, and judging that project's results against that
project's manifest. You run it from there.

```bash
cd <the mod or pack project>
./gradlew stagewrightDedicatedServer
```

## The tasks

Declaring a topology named `dedicatedServer` contributes three tasks; one more is contributed per
project. All four sit in Gradle's `verification` group.

| Task | What it does |
|---|---|
| `stagewrightDedicatedServer` | The check. Provisions a clean run directory, runs the topology's own dev-run task, and judges the results. |
| `stagewrightDedicatedServerProvision` | Clears the run directory so the next run cannot inherit a world. A dependency of the check; you rarely run it alone. |
| `stagewrightDedicatedServerHold` | Stands the same topology up and leaves it standing, for something outside the game to attach to. |
| `stagewrightCoverage` | Reconciles every declared topology's results against each other. One per project. |

The capitalised part is the topology's own name with its first letter uppercased, and nothing else.
There is no loader suffix mechanism: a project whose tasks are called
`stagewrightDedicatedServerFabric` and `stagewrightDedicatedServerNeoforge` has declared two
topologies with those names, because its run task, results directory and manifest all differ per
loader and both must be requestable in one invocation.

**Always go through the check task, never the bare dev-run task it drives.** The check's provision
step deletes the previous world first; the raw run task does not. A reused dirty world makes scenes
fail in ways that look like defects in the mod but are residue: blocks a previous run placed are
still there, so a scene asserting "this gap must require a placement" reports that nothing was
consumed, and terrain a previous run dug has moved under the scene that digs it. Which scenes fail
varies from run to run, which reads exactly like flakiness. It is not.

## What the check reports

The task logs a report line per judgement, each prefixed `[stagewright:<topology>]`, and closes with
a verdict line:

```
[stagewright:dedicatedServer] VERDICT: GREEN
```

The task **exits zero when the run is sound and every required scene passed**. Otherwise it fails the
build with a message naming the results file it judged. Four labels are possible, and the process
exit code follows the same ordering:

| Code | Label | Meaning |
|---|---|---|
| 0 | `GREEN` | A done footer is present, every registered non-canary scene passed or failed while marked optional, and every canary landed on the outcome it was declared to require. |
| 1 | `RED` | A required scene failed, was never recorded, or reconciliation against the manifest failed. |
| 2 | `DEAD` | A canary landed on the wrong outcome. The framework can no longer be trusted to catch a failure, so the run's results are void rather than merely bad. This is not a louder `RED`: a `RED` says the code is broken, a `DEAD` says the measurement is. |
| 3 | `ENV` | No suite header — the game never armed. Deliberately never reported as `RED`, because "the server did not start" must not read as "your code is broken". |

The game's own exit code is never consulted. Only the results file decides.

A run narrowed by a scene filter has ` (FILTERED — not a gate result)` appended to its label. See
[running one scene](#running-one-scene-while-you-write-it).

Where a topology declares a companion client, the companion's results are judged as a suite of their
own, its report lines are prefixed `client:`, and it closes with `CLIENT VERDICT:`. The **worse of
the two codes** decides the build.

## The failure modes that are not a failing scene

Several judgements print **above** the verdict line and each can fail a run on its own. If the colour
of a run disagrees with what you expected from the scene outcomes, read upward from the verdict
rather than re-reading the failure rows.

**`UNDECLARED:`** — a scene is registered but is not in the expected-scenes manifest. Add it in the
same commit that registers it. The check is scoped to the namespaces the manifest itself already
uses, so StageWright's own built-ins never count against a consumer.

**`MISSING-EXPECTED:`** — the opposite direction: a name in the manifest that no provider registered.
Usually a typo or broken service wiring.

**`COVERAGE:`** — the census of what this topology tested nothing about. On its own it is
informational; the cross-topology check is a separate task.

**`DEAD:` on a canary** — the framework's own self-check landed on the wrong outcome. Nothing else in
that run's results can be believed. Four canaries ship with StageWright: one that must be reported
as a failure, one that must be reported as a timeout, one that must never be executed at all, and one
that fails a soft check and then skips, which must be reported as a failure rather than a skip. A
further kind is available to consumers for a scene whose subject is a recorded skip.

**`SWALLOWED:`** — a scene is registered and no record of it exists. It did not run and nothing said
so.

**`DUPLICATE:`** — two records share one scene name. Any last-wins map over those records can hide a
failure behind a later pass, so the run refuses to judge them.

**`DRIFTED:`** — a record exists for a name nothing registered.

**`REGISTRY:`** — the game armed but could not assemble the suite, so nothing ran: a duplicate scene
name, an illegal origin pin, a provider with no scenes, a scene file that does not parse, scene files
with no Rhino. The line carries the error. It is RED rather than an environment failure, because
the fix is in the scenes, not the host.

**`TRUNCATED:`** — the footer's scene count disagrees with the number of records present.

**`WORLD:`** — not a failure. It prints on every run, including a clean one, and states what the run
held still about the world. A run that does not say what world it was green in is claiming more than
it proved.

When a run produces no results file at all, the task fails with an environment message naming the
path it expected and the three usual causes: the framework's mod jar is not on that run's runtime
classpath, the run does not arm the harness, or mod loading failed before the server reached its
first tick — and the run's own log says which.

When a run produced a suite header but no done footer, the verdict reads the heartbeat file written
beside the results and prints a `last heartbeat:` line naming the scene it was on, how many ticks
passed in the preceding seconds, and how long before the verdict it was written. That distinguishes a
world that had stopped from one that was healthy when whatever killed the run killed it.

## Never truncate a run's output

**Do not pipe a run through `tail` or `head`.** The verdict is at the end, which makes truncation look
sufficient — until a run dies before producing one and the error was in the part you discarded. The
judgements that fail a run print above the verdict line, so a truncation that keeps only the tail can
also cut off the reason.

Redirect the whole thing instead:

```bash
./gradlew stagewrightDedicatedServer > run.log 2>&1
```

## How the harness reaches the game

The framework has to be a **mod** in the run. Declare it as your loader plugin declares any other
runtime-only mod, and the loader discovers it on the run's classpath:

```groovy
configurations {
    runtimeClasspath.extendsFrom localRuntime   // ModDevGradle; the NeoForge MDK declares it so
}
dependencies {
    modLocalRuntime 'net.magicterra:mc_stagewright-fabric:0.1.0-build.0+1.21.1'   // architectury-loom
    localRuntime    'net.magicterra:mc_stagewright-neoforge:0.1.0-build.0+1.21.1' // ModDevGradle
}
```

Not `runtimeOnly` under ModDevGradle: that is published with your mod as a runtime dependency, so
everything that depends on your mod would load the test harness too.

A companion client is started from its own run task's classpath, so it gets the same framework
build — which it must, because a loader that finds a different mod list on each end refuses the
connection.

Nothing is copied into `mods/`. Earlier versions of this plugin copied the framework there through a
topology's `installMods`, and recorded what they copied in a ledger inside `mods/`; provisioning takes
back what that ledger names, once, so the old copy does not load beside the new one as a duplicate.

## What provisioning does to the run directory

Before every run, the provision step:

- deletes the previous `world` and `saves` directories, unless the topology asks it not to;
- deletes the results file it is about to judge, the heartbeat beside it, and any stale endpoint
  descriptor, both in the run directory and beside a companion client's results file;
- writes `eula.txt`;
- forces three keys in `server.properties` and leaves every other line alone: online mode off, a
  fixed level seed, and chunk-write syncing off;
- clears `config/stagewright/scenes` and `config/stagewright/capabilities`, then repopulates them
  from the topology's declared scene-script directory if it has one;
- takes back any jar an earlier version of the plugin copied into `mods/`.

The scenes-and-capabilities step clears those two directories **whether or not** a scene-script
directory is declared. A project that authors files there by hand should declare the directory they
live in rather than writing into the run directory directly.

`options.txt` is not written. A client StageWright directs switches off focus-pausing, the
accessibility onboarding prompt, the narrator and vertical sync in memory as its options load, and
puts the file's own lines for them back whenever the game saves it.

Vertical sync is on that list for a reason worth knowing. With it on, a client blocks in the buffer
swap until the compositor presents its window, and a compositor that is not presenting it — screen
asleep, another workspace, a remote session — hands out about one frame a second. Minecraft runs at
most ten game ticks per frame, so the client falls to ten ticks a second while the server keeps
twenty, and every scene that drives a client player needs twice the server ticks it budgeted for.

A topology whose run task is a client (`client = true`), and every companion client, is refused
before it starts on Linux when neither `DISPLAY` nor `WAYLAND_DISPLAY` is set, or when the X server
on a local `DISPLAY` is not there or refuses the cookie in `XAUTHORITY`. The plugin does not
start a display: on a headless machine, run the build under `xvfb-run` or in an image that provides
one. A client that passes this check and still cannot open its window is not watched for here:
NeoForge opens a dialog nobody will click, and a gate lasts until `timeoutMinutes` ends it — a hold,
which has no timeout, until you stop it. The command-line runner kills such a client as soon as it
logs `glfwInit failed`.

## Reconciling the topologies against each other

```bash
./gradlew stagewrightCoverage
```

Run the topologies first, then run this. It deliberately does **not** declare a dependency on them,
for two reasons: the runs bind a fixed port and are already sequenced by the host build, and a
topology whose check fails aborts the build — which is exactly when this report has the most to say.

It answers the question no single run can be asked: **did every scene this suite registers execute in
at least one of the topologies?** A scene skipping is fine in one run and a hole across all of them.
Scenes declared as must-skip are excluded, as are canaries.

An uncovered scene is reported by name, with what each run said about it instead, and the summary
counts how many of the registered scenes executed across how many runs. A declared results file that
is not there is reported rather than skipped: dropping it would shrink the union of executed scenes
and blame the runs that did happen.

Two inputs are not evidence, and are treated that way. A results file whose header says it was
filtered — the last run of that topology was a `-Pstagewright.scenes` iteration — makes the whole
reconciliation ENV, naming the file: its registered list is whatever the pattern kept, so any count
over it describes a subset. And a scene recorded `ENV_FAIL` did not execute, because that outcome is
written before the body runs; it counts as a hole exactly as a skip does. So does any record written
before the body was entered — a `TIMEOUT` during preparation, or an attached scene refused because
out of process cannot honour its terrain, clock or dimension — which the record marks with
`"bodyRan":false`.

When the results files register no scene between them — none armed, none could assemble its
registry, or the ones that did registered nothing — the reconciliation is ENV too, rather than a
pass over zero scenes.

A topology with a `companionResultsFile` is reconciled with it, labelled `<topology>-client`, so a
client probe that skipped on every topology is reported like any other uncovered scene. A topology
whose own name is another topology's companion label is refused, since one of the two results files
would otherwise drop out of the reconciliation.

Results from different code are not reconciled. Each gate passes the game a build id taken from the
git work tree once per build, when its first run that git gives one to starts — HEAD, plus a digest
of uncommitted changes to tracked files — and the header records it; every run and companion in one
build gets the same id. One that starts after the tree changed since then, or when git cannot read
it, is judged by what it loads: if nothing on its classpath or in the class directories its
`MOD_CLASSES` names was written since the tree last read as
the build's id, its code was built from that tree, and it gets the build's id. Otherwise it may
have loaded a change or not, so it records a build that names both states and matches no other run,
and coverage stays ENV until the gate is re-run on a tree nobody edits meanwhile. So an edit to a
note or a doc mid-gate costs nothing, while one followed by a compile the later run loads does. When
the files disagree, the reconciliation is ENV and lists each file's build, so a topology last run a
week ago is re-run rather than counted. The id covers what git tracks, including edits inside
submodules and edits to or deletions of files marked `assume-unchanged` or `skip-worktree`, which
are compared as though unmarked, and one change gets one id on every machine whatever the settings
the build id pins, except where a hunk's function-name context or a file's diffability comes from
the machine: a function-name pattern a machine's config gives a diff driver, the built-in patterns
of another git version, or a machine's own `-diff` or `binary` attribute can still make git print
one change its own way, which gives that change a second id and so reads as MIXED BUILDS, never as
a match. A `skip-worktree` file a sparse checkout leaves out is not a deletion. A change only to an
untracked file does not change it. A project git does not track records no build, so coverage cannot tell its
runs apart by code: files with no build match each other, and a file with no build is never
reconciled with one that has a build.

A tracked file that the game or a build task rewrites, such as `server.properties` under a game
directory or a checked-in source a task generates, changes the tree each gate starts from. Within a
build that costs nothing unless a later run's classpath was written since, as it is when the file is
a generated source. Across gates, one rewritten with the same bytes each time matches from the next
gate on, once the tree already holds them; one rewritten differently each time, as
`server.properties` is with the date the game writes into it, gives every gate an id of its own and
keeps coverage across them ENV however often they are re-run. Keep game directories and generated
files out of git.

A topology whose run task is not a `JavaExec` records no build: the plugin has no JVM command line
to put the id on, and says so when it wires the run. A `JavaExec` companion of that run still records
one, so the topology's own two results files never reconcile, and neither does its run's file with
that of any topology that records a build: coverage over them is ENV under `MIXED BUILDS` however
often they are re-run. Make the run task a `JavaExec` to reconcile it. It is not exempted from the
comparison, because that would count a run of unknown code as a run of this one.

The build id does not see everything. These can give two different trees one id:

- A submodule's changes are printed without the pinned flags, so a diff driver or `diff.external`
  configured for it can give two different edits there one id, and a file marked
  `assume-unchanged` or `skip-worktree` inside it is not read at all.
- A `skip-worktree` file missing from a sparse checkout is taken as left out, even where the sparse
  rules include it.
- A gate started from a git hook takes the id of the repository the hook exported to it, such as
  the index a commit is being built in, rather than that of the project.
- A run started after the tree changed is checked against its classpath and the class directories
  `MOD_CLASSES` names, so code it loads from anywhere else, a mods folder named on its command line
  say, can change unseen.
- An edit compiled into a later run's classes and then undone before any run read the tree is not
  seen: the next read finds the tree as it was, and vouches for everything written until then.

These only give one tree a second id, or none, which reads as ENV: the diff settings a submodule's
changes are printed with, and the commits naming a moved submodule, which `core.abbrev` or the size
of the repository shortens; `GIT_DIFF_OPTS` in the environment; a clean filter a marked file's
attributes name, which the diff now runs as for any file, failing on a machine without it, which
gives no build; a `skip-worktree` file of a sparse checkout whose name is not UTF-8, taken as present
and so, left out, read as deleted; a run started after the tree changed whose classpath was written
since for some other reason, such as a compile of a file the edit did not touch; and a git from MSYS
or Cygwin, whose work-tree path the JVM cannot use.

For a pack tested through the standalone command-line runner, the same reconciliation is available
without a build tool:

```
java -jar stagewright.jar --coverage run-a/stagewright/stagewright-results.jsonl,run-b/stagewright/stagewright-results.jsonl
```

The runner reconciles exactly the files it is given. List the attached half's
`stagewright-attached-results.jsonl` and any client results file too, or their scenes are not part of
the claim.

The runner records no build, so it cannot tell runs of a pack whose mods changed in between from
runs of the same pack. Reconcile files from runs you know tested the same mods.

## Running one scene while you write it

```bash
./gradlew stagewrightDedicatedServer -Pstagewright.scenes=sb.magnetPulls
./gradlew stagewrightDedicatedServer "-Pstagewright.scenes=sb.client*,pack.*"
```

The plugin forwards the pattern to the game as a system property. Entries are comma-separated and a
scene runs if it matches any of them. `*` is the only metacharacter and matches any run of
characters; everything else is literal, so a name containing a `.` needs no escaping.

The cost of a run is a game boot you pay either way, plus the scenes. On a suite of any size a filter
is therefore the difference between iterating on a scene and batching guesses at it.

**A filtered run is not a check result, and everything says so.** The pattern is written into the
results header, the game logs it, the plugin logs it, and the verdict label carries the `FILTERED`
suffix. Expected-scenes reconciliation is skipped, because under a filter every unmatched scene is
legitimately absent and reporting the whole manifest as missing would bury the outcome you asked for.
The framework canaries are never filtered out, so even a one-scene run proves the harness can still
catch a failure.

A pattern that matches **nothing** fails the run rather than reporting an empty success — the
canaries it kept do not count as a match. That typo is otherwise the failure that looks most like a
pass.

One more thing the filter is not good for. Arena slots are assigned from the scene list *after*
filtering, so a scene that sits sixth in the full suite runs first when it runs alone — several
hundred blocks away, in a different chunk, on different ground. It passing alone is therefore not
evidence about the run it failed in. Reproduce with the full suite; filter while you write.

## Holding a topology open

```bash
./gradlew stagewrightDedicatedServerHold      # Ctrl-C ends it
```

The hold runs the same topology with the suite armed but never started, no timeout, and the client —
if the topology has one — never closing itself. Once the game is genuinely in a world it publishes an
endpoint descriptor into the run directory naming the port it actually bound.

A hold exists for everything that has to assert from **outside** the game: an out-of-process test
suite, an interactive session, a check that must not run through the harness it is checking. Those
cannot be scenes, because a scene body runs inside the runtime under test.

A hold has no results file and no verdict. What it produces is an endpoint; the verdict belongs to
whatever attaches. See [JUnit attach](junit-attach.md).

A topology declaring a companion client is held on both halves, with one descriptor per run
directory — the server's beside its results, the client's beside the companion's. Which file you
point the attaching process at is the whole choice: client-only calls answer at one and nowhere else.

The hold's properties are deliberately separate from the run configuration's own arming switch rather
than an override of it. Overriding would mean two definitions of one key on one command line and a
silent dependence on which the JVM reads last. When that was the design, a held game ran its entire
suite underneath the tests that had attached to it, and every one of them failed intermittently on
the world moving under it.
