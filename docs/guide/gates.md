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

The framework has to be a **mod** in the run, not a library on its classpath. `installMods` copies
jars into the run directory's `mods/` before the game starts, normally just the framework's own
loader jar resolved from a dependency configuration.

Under architectury-loom you do not need it: that plugin already puts a mod jar in front of the
loader. Under ModDevGradle you do, and leaving it out does not look like a mistake. Putting the
harness on the runtime classpath instead — the obvious alternative — gets it discovered and then
claimed as a plain game library. The loader logs the jar by name, the mod never enters the mod list,
and the run boots, ticks, writes no results, and reports an environment failure over a log containing
no error and no mention of StageWright at all.

`mods/` is also where the loader looks in every run, development or production, so this is the only
delivery that puts the exact artifact a player would install into the run.

Jars a previous install left behind are swept first. The install keeps a ledger inside `mods/` and
deletes what it lists, plus any jar whose name marks it as a framework build. Filenames carry
versions, so without the sweep an upgrade lands *beside* its predecessor and the loader arms one of
the two — reporting the old code's behaviour as the new code's.

## What provisioning does to the run directory

Before every run, the provision step:

- deletes the previous `world` and `saves` directories, unless the topology asks it not to;
- deletes the results file it is about to judge, the heartbeat beside it, and any stale endpoint
  descriptor;
- writes `eula.txt`;
- forces three keys in `server.properties` and leaves every other line alone: online mode off, a
  fixed level seed, and chunk-write syncing off;
- writes `options.txt` with focus-pausing, the accessibility onboarding prompt, the narrator and
  vertical sync all switched off;
- clears `config/stagewright/scenes` and `config/stagewright/capabilities`, then repopulates them
  from the topology's declared scene-script directory if it has one.

That last step clears those two directories **whether or not** a scene-script directory is declared.
A project that authors files there by hand should declare the directory they live in rather than
writing into the run directory directly.

Vertical sync is on that list for a reason worth knowing. With it on, a client blocks in the buffer
swap until the compositor presents its window, and a compositor that is not presenting it — screen
asleep, another workspace, a remote session — hands out about one frame a second. Minecraft runs at
most ten game ticks per frame, so the client falls to ten ticks a second while the server keeps
twenty, and every scene that drives a client body needs twice the server ticks it budgeted for.

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
written before the body runs; it counts as a hole exactly as a skip does.

For a pack tested through the standalone command-line runner, the same reconciliation is available
without a build tool:

```
java -jar stagewright.jar --coverage run-a/stagewright-results.jsonl,run-b/stagewright-results.jsonl
```

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
