# Attached scenes: running a scene outside the game process

## The problem

A scene body runs on the server thread, inline on the tick. That is what makes it exact — build
an arena, advance one tick, assert, and the whole sequence happens atomically between two ticks
on one thread — and it is also what puts several kinds of test permanently out of reach. A scene
cannot block, cannot outlive the process, cannot restart the game, and on a dedicated topology
cannot see a client at all.

Attached scenes add a third place for test code to run. **This adds a kind; it does not replace
one.** The determinism that makes in-process scenes worth having cannot be carried out of the
process, and the sections below are mostly about which half is which.

## Three homes, decided by where the code runs

Capability follows the process, not the language.

| Home | Written in | Runs on | Can do | Structurally cannot |
|---|---|---|---|---|
| **In-process scene** | Java (`@SceneDef`) or JavaScript under `config/stagewright/scenes` | the server thread, inline on the tick | arena allocation, forced chunks, the world pin, leak auditing, tick budgets, byte determinism | block, cross a process boundary, touch a client on a dedicated topology |
| **Client probe** | Java only | the client JVM's client thread | see what crosses the network boundary — what a payload decodes to | server-side state |
| **Attached script** | JavaScript, run by the command-line runner; or Java, through the JUnit attach module | its own JVM, everything over RPC | wait on wall clock, cross process boundaries, restart the game, drive menus, run under a debugger | arena allocation, the world pin, leak auditing, byte determinism, a per-scene origin |

The two entries in the third row are two front ends over one runtime, which is the answer to
"should the test mod have a part that is not on the server thread". It should not. The test mod
exists in order to touch `Level` and `ServerPlayer` directly and to be deterministic per tick;
leaving the server thread gives all of that up and buys nothing in exchange, because it is still
the same JVM. The work that genuinely needs to leave belongs to a different artifact, and that
artifact already exists.

## Where the code lives, and why

Two modules carry this, and neither of them is the engine.

**`:stagewright-attached`** (directory `attached/`, package `net.magicterra.stagewright.contract`)
holds everything that is true regardless of where a scene runs: the assertion vocabulary
(`Expect`), the outcome and skip and failure types, the scene options (`Clock`, `Terrain`,
`Canary`), the RPC client, and the Rhino plumbing that turns a `.js` file into a list of scenes.
It has no Minecraft, no loader and no loom, and it is scoped out of the root build's loom
pipeline by name — putting a Minecraft classpath under a module whose entire contract is not
having one would make it unconsumable by the command-line runner, which is a separate Gradle
build with no Minecraft anywhere.

**The command-line runner** (`cli/`) drives it. The out-of-process home needs a JavaScript
interpreter, and that dependency could not go in the engine. `stagewright-engine` lands on a
consumer's *buildscript* classpath through the Gradle plugin, where any third-party coordinate
has to resolve from whatever repositories that consumer happens to declare in
`pluginManagement` — and the reference consumer's list does not include Maven Central. The
constraint is inherited rather than chosen, and it is why the engine vendors a JSON parser
rather than depending on one. The runner has no such constraint: it is a fat jar whose audience
downloads one file, and it already resolves from Maven Central and mavenLocal.

The interpreter is a fork rather than the reference implementation, and that is deliberate. Both
homes must run the same interpreter before "the same file" means anything, and the existing
in-game code already relies on a behaviour specific to the fork. Swapping in a standard
implementation would change semantics silently. Because the coordinate is not on Maven Central,
both builds fence its repository to its own group — not tidiness: a repository whose domain had
expired and whose parking page answered every path with a 200 once black-holed nine healthy
repositories declared after it, and reported the damage as nine unparseable POMs.

### Sharing the vocabulary is the load-bearing decision

The obvious alternative — let each home keep its own copy of the assertion vocabulary — fails
for a reason that is not obvious until it has happened. JavaScript resolves method names at run
time, so a method present in one home and missing in the other produces no compile error
anywhere; the drift only surfaces as a scene failing in one home, months later, for a reason
that reads as a product bug.

There is a worked example in this project's own history: an early draft of this design used an
assertion method the vocabulary has never had. A vocabulary that a *design document* gets wrong
is not one that two hand-written implementations will keep in step.

So the vocabulary is one compiled artifact. `SceneReport` is the seam that makes that possible:
it is the small interface an assertion needs in order to report — record a violation, record a
measurement, fail, skip, register a cleanup — and both `SceneContext` (in-process, with the
world half) and `AttachedContext` (out-of-process) implement it. One `Expect` serves both, and
a method that exists in one home exists in the other by construction.

Two things are in that module for the same reason and would be easy to mistake for interpreter
details. The first is the prelude resource, which exists once rather than once per home. The
second is the exception unwrapping, and it deserves a paragraph.

Rhino wraps any Java exception thrown out of a Java method a script called, so testing the raw
throwable against the skip type is false for everything a facet raises. That bug lived a long
time because it is **invisible on a failure**: a wrapped failure still reaches the failure path
and still reports FAIL. It is visible only on a *skip*, where it inverts the framework's central
rule — a scene file asking for a player on a topology that has none was reported as FAILED, for
every absent mod, dimension and player in every pack. A second, hand-copied runner would
reintroduce it verbatim and it would hide for just as long. Sharing the code is what makes that
impossible rather than merely unlikely.

## What can be offered honestly, and what must refuse

`AttachedContext` keeps the same names and the same spellings as `SceneContext` for everything
it supports — that is the entire promise of a shared scene file, and it is why the class is not
called something like "remote context". What it does not support, it **refuses by name, listing
what is available**, following the rule the option parsers already obey: never degrade silently.

Supported:

| Verb | How |
|---|---|
| `command(...)` | The driver's run-command verb. |
| `mods()` | The driver's version verb, which carries the mod list. |
| `driver(method, params)` | The RPC call itself — the one door to the game out here. |
| `expect` / `check` / `record` / `fail` / `skip` / `cleanup` | The shared vocabulary, through `SceneReport`. |
| `waitUntil(condition, timeoutMs, pollMs)` | Attached-only; see below. |

Refused, in three groups with three different messages, because they have three different fixes.

**The world-shaping verbs** — `arena`, `origin`, `rel`, `setBlock`, `floor`, `level`, `server`,
`perf`. The tempting design lets `setBlock` through, since it really could be a command round
trip. It must not be. In process, an origin is a *grid-allocated* point: the harness gives each
scene its own plot, force-loads it, sweeps it afterwards and audits it for leaks. Out of process
there is no allocator, no force-load, no teardown and no audit, so every attached scene would
build on top of the previous one's rubble — which is precisely the failure that deleting the
world before every run exists to prevent, and which makes scenes fail in ways indistinguishable
from product bugs.

Note the asymmetry that decides this. `arena()` throwing is *safe*: it fails immediately, at the
line that asked. `setBlock` quietly succeeding is not: it poisons the next scene, which then
fails somewhere unrelated to the cause. So the read-only subset is not a first-version shortcut.
It is the only version that can report honestly until origin allocation has an answer out here —
either the harness learns to hand out origins to outside callers, or the attached home computes
them from the same grid constants and force-loads and sweeps for itself. The second needs no
change to the driver but does require the grid constants to move somewhere both homes can see
them, or they become one more hand-copied pair that drifts.

**The discovery verbs** — `capability`, `capabilities`, `probe`. These must refuse with a message
that says plainly that this is *not* the same as the mod being absent. Capability providers are
found by `ServiceLoader` on the **game's** classpath; the attached JVM does not have it, so the
scan out here would report an empty set for a pack that has every one of them — which is exactly
what an uninstalled mod also looks like. Reporting the framework's own structural limit as "this
pack does not have Mekanism" is a recurring species of bug in this project, and the whole reason
`Mods` raises rather than returning an empty list.

**The player facets** — `equip`, `items`, `menu`. These need the `ServerPlayer` instance itself,
which does not cross a socket. The player observation verb carries a snapshot, not the entity.

### Waiting is a different verb, deliberately

There is no `await(condition).within(ticks)` out of process. In process that is exact. Out here
every call is a round trip and the game ticks between any two of them, so a tick count cannot be
honoured — only approximated by wall clock, and the approximation is not even wrong by a constant
factor, because a server draining startup tick debt runs catch-up ticks at roughly 3 ms instead
of 50. A method that silently meant something different in each home would be worse than not
having one, so the wall-clock wait is named `waitUntil` and says what it is.

It records how many times it polled. A wait that made twelve thousand round trips is a fact about
the run that belongs in the results rather than being inferred from a wall-clock total, and the
in-process home has no equivalent cost to hide.

### Return values and exception types are part of the vocabulary

Two details are easy to change accidentally when crossing a process boundary, and both change
what a scene file means.

A command returns its numeric result **and every line of its feedback**. A scene that reads the
feedback must be able to read it in both homes; an implementation that dropped the output would
turn every such scene into one that silently reads an empty list.

A **rejected** command raises an illegal-argument exception, not a scene failure. That is not an
academic distinction: scenes legitimately use a rejection as an *answer* — querying an absent
data path means the slot is empty — and catch it to return false. An implementation that
normalised every RPC error to a scene failure would silently change what every one of those
catch blocks means. Silently, because both spellings throw.

## The author-facing surface

The same `scene()` keyword, the same assertion vocabulary, the same names for everything both
homes support. What differs is which verbs exist and how a wait is spelled.

Scene files are loaded from a directory in **file-name order**, not filesystem enumeration order,
so a pack's scene sequence is identical on every machine. A suite whose execution order drifts
between hosts produces failures that reproduce on one of them.

The registration pass — a file's top level — runs without an instruction cap. It is allowed to
build tables and loop over its own data; it runs once, at arm time. The cap belongs on the
*body*, and there it is the one guard neither home can do without: a Java body that loops forever
is caught in review by people who know it runs inline on the tick, but a pack author writing a
spin loop has no reason to know that, and in process the result is not a failed scene but a
server that stops ticking — at which point no timeout fires, because timeouts are counted in
ticks and ticks have stopped. The interpreter's instruction observer is the only thing that can
interrupt a running script from outside.

Two preconditions attach to running attached scenes at all, and both belong in the runner's help
text rather than only in prose here:

1. **The pack must contain the driver.** In-process scenes do not need it — their context is
   StageWright's own — but out here the driver's socket is the only door.
2. **The game must be started as a hold**, not with autorun. An autorun suite halts the server
   the moment its scenes drain, which takes the endpoint down underneath anything attached to it,
   mid-call.

The first of those was a stated precondition and not a *check*, and the difference cost an hour
per run: pointed at a pack with no driver, the game logged the exact diagnosis half a minute in
and the runner then waited out its entire timeout before reporting something vaguer. The runner
now watches for that line. Scraping a log is precise here rather than fragile, because it is our
own mod printing our own string.

## Results and verdicts

Attached scenes write a third results file in the run directory, in the **same format** as the
in-process suite and the companion client, so that the existing worst-of-several verdict judges
it without learning anything new. Four details of that format are not obvious for a home with no
game, and each is a way to be silently wrong:

- **No world-pin key.** Attached pins nothing. The key's "present only when it has a value"
  rule is what lets that be stated by omission rather than by writing something untrue.
- **The loader field is mandatory** and this JVM has no loader. It is copied from the endpoint
  descriptor, which carries it.
- **The tick count is a frozen field** and there are no ticks out here. It is written as zero,
  with the real elapsed time in the recorded data. Dividing wall clock by fifty to manufacture a
  tick count would produce a number wrong by a factor that varies with how far behind the server
  is — wrong in a way no reader could detect.
- **An empty registration list is a failure.** The companion path judges records without
  reconciling against a manifest, so a header, no scenes and a footer is a structurally perfect
  pass describing a run that executed nothing. Someone points the flag at the wrong folder and
  the gate congratulates them. The file is written first and the failure reported after, so the
  diagnosis is "it found no scenes" rather than "it produced nothing".

A scene declaring an option this home cannot honour — a terrain, a dimension, a clock other than
the default — is **refused as a failure rather than skipped**. A skip would be right if the option
were about something *absent from this runtime*; it is not. It is about this home being the wrong
place for this scene, which is an authoring mistake that should be fixed rather than tolerated on
every future run. The message names the directory where the harness would give the scene the
world it asked for.

One correctness fix had to land before a third results file could exist at all, and it applied
to the second one too: provisioning must clear **every** results file a run will be judged on,
not one name under the run directory. A companion's results live in the companion's own
directory, so a narrow signature was structurally unable to clear them — and a companion that
started and then died before writing its header left the previous run's complete, valid, green
file in place to be judged. The narrow signature was the bug: a caller cannot pass what the
parameter cannot express.

## Integrating with the checks, and the ordering that inverts easily

The whole chain has one correct order and it is not interchangeable.

```
provision
  → start the game as a HOLD
      → attach
      → run the attached scenes            (first)
      → trigger the in-process suite       (mc.test.run)
      → wait for the in-process footer
      → write the attached results
      → end the hold
  → judge every results file, worst wins
```

**Attached scenes run before the in-process suite, not after.** The suite pins the world for its
own duration — freezing the clock and three game rules on entry, releasing them on exit — and an
attached scene sandwiched inside that window would be asserting against a world being changed
around it. The failure would point at the scene rather than at the ordering, which is why this is
written down rather than left to be re-derived.

Getting it backwards is easy because every primitive involved reads as though order does not
matter, and there are two further traps in the same area.

**A hold's world is dead by default.** This inverts the natural reading of "attached scenes run
first, before the suite has disturbed anything". Undisturbed is true; *alive* is not. Vanilla
stops running the entity loop and block-entity ticking on a level nobody has been in for 300
consecutive ticks, a dedicated hold has no player by definition, and the runtime ticket that
pins an arena's chunks is not what vanilla's emptiness test reads. Fifteen seconds in, nothing
moves on its own — no falling entities, no furnaces lighting, no machines running — while the
server loop runs, chunks stay loaded, commands work, and blocks can be placed and read back.
Every chunk-status question answers true, correctly, because chunk status is not what is being
tested. A hold takes far longer than fifteen seconds to boot and become attachable, so every
attached scene would run in a static world.

The fix is vanilla's own escape hatch, called on every level every tick — but **where it is
called mattered more than what it called**. A first attempt put it in the harness, which under a
hold is not constructed at all until the suite is triggered; that fixed the path that did not
need fixing and left the hold untouched. Keeping a level awake is a property of "StageWright is
loaded", not of "a suite is running", and this mod is only ever present in a test run.

**A loaded chunk is not a ticking chunk.** The same symptom — nothing moves, every reading
normal — has a second cause at a different scale: the level is alive but a particular chunk is
outside the block-ticking range. In process the harness pins the arena; out of process there is
no harness, so an attached scene has to ask for what it needs. The two look identical from the
outside and need opposite fixes.

**Ending the hold.** A hold has no self-terminating path — outliving its suite is its entire
contract — so something has to end it. The command-line runner kills the process tree it
started. That is not a workaround there: it is the same shutdown path the timeout already uses,
it owns the tree, and it cannot leave an orphan holding a port. A Gradle chain has no such
owner, which is why extending this to the Gradle plugin needs a hidden verb that asks the game
to end its own hold. That verb does not exist yet, and neither does the plugin-side support for
attached scenes; the shape of the answer is known, the work is not done.

## The constraint this design now obeys

This design was corrected three times, and each correction was the same mistake: two things
shared a name, so they were assumed to share a meaning, and the assumption was written down
without being checked against the implementation. Origin and block placement looked portable and
were not. The tick-bounded wait looked like it mapped onto an existing polling verb and did not.
An entire layer of capability facets had been added to the in-process context and was simply
absent from the analysis.

Recording the count is not the point. The point is what it implies about the design's central
claim — **the same file runs in both homes** — because "the same" is a property that rots the
moment a human is responsible for maintaining it. A table in a document listing which verbs are
portable is exactly such a human responsibility, and it was wrong three times while being
reviewed each time.

So the load-bearing parts of this design are the three that a machine maintains:

- **One compiled `Expect`**, so a vocabulary that drifts fails at `javac` rather than at run time
  in one home only.
- **One shared exception unwrapping**, so a bug that is invisible except on skips cannot be
  reintroduced by a second copy.
- **A refusal that is thrown by code**, so "this verb does not work out of process" is determined
  by the implementation rather than by a list someone has to update.

The rule that follows, for anyone extending this: before writing a constraint into a table, ask
whether a machine could hold it instead. If it could, it should.

A fourth machine-maintained check is designed and not built: run the same file in both homes and
assert the two results records agree on outcome and reason. It is worth more than any hand-written
table of portable verbs, and it has a known boundary — it can only judge files that touch no
unportable verb, and "touches one" must be decided by the refusal the attached home throws, not
by a list. Until it exists, the tables in this document carry a risk this design otherwise
avoids.
