# Writing a scene

A scene is one test. It builds a situation in a live Minecraft world, drives it, and asserts an
outcome. This guide is the author-facing reference: what runs when, how a scene says what it needs,
how it asserts, how the runtime finds it, and what is frozen about the world it runs in.

If you have not run a scene yet, start with [Getting started](getting-started.md).

## The lifecycle

A scene body is a `Consumer<SceneContext>`. The harness calls it **once**, synchronously, on the
scene's first tick, and then calls `advance()` once per tick until the scene resolves.

1. The harness allocates the scene an origin on a grid, force-loads a window of chunks around it,
   and applies the scene's clock.
2. The body runs. It builds its arena, asserts whatever is already true, and registers any
   continuations it needs.
3. Every tick after that, the harness drains the scene's await steps. A step whose condition is
   already true fires immediately and the next step is examined in the same tick.
4. When the last step has drained, any deferred `check` violations are collected into a single
   failure. Otherwise the scene passes.
5. Teardown registered with `cleanup` runs, in reverse order of registration, whatever the outcome.

Two consequences follow from "the body runs inline on the server tick", and they are the rules that
matter most:

**A body never blocks and never sleeps.** There is no test thread. A body that waits in a loop
stops the server, and a stopped server does not advance the tick counter the scene's own timeout is
measured in — so nothing times out and the run hangs rather than failing. Anything that takes time
goes through `await`.

**A body's own loops must be bounded.** A loop that drives something for a fixed number of
iterations is fine; a loop that waits for a condition is the bug above.

Scene files written in JavaScript are additionally capped at ten million Rhino instructions per body
call, because a pack author has no reason to know the rule above. Compiled scenes have no such net.

## Declaring a scene

The short form annotates a holder class and its methods. The method must be `static`, take exactly
one `SceneContext`, and return `void`; anything else is rejected loudly when the provider is scanned,
because a scene that silently fails to register shrinks the suite without shrinking the verdict.

```java
@SceneSet("sb")
public final class MagnetScenes implements SceneProvider {

    @SceneDef(budget = 200)
    static void pullsItemsWithinRadius(SceneContext s) {
        s.floor(5, Blocks.STONE);
        s.expectBlock(0, 0, 0).isEqualTo(Blocks.STONE);
    }
}
```

That registers one scene named `sb.pullsItemsWithinRadius`.

The long form — overriding `SceneProvider#scenes()` and building `Scene` records by hand — stays
first-class. Use it to register canaries, to aggregate several holder classes through
`Stages.scan(Class...)`, or to decide at run time which scenes this runtime can support.

```java
public final class MagnetScenes implements SceneProvider {
    @Override public List<Scene> scenes() {
        return List.of(Scene.of("sb.pullsItemsWithinRadius", 200, MagnetScenes::pulls));
    }
}
```

Prefer the annotation where you can. Under `Scene.of` the scene's name is written twice — once as a
string the run reconciles against a manifest, once as a method reference the compiler checks — and a
rename moves only the second.

A `SceneProvider` that declares no `@SceneDef` methods **and** does not override `scenes()` throws.
A provider that contributes nothing is a service entry that looks wired and adds no coverage.

### Names and namespaces

A scene's name is its method name prefixed by its class's `@SceneSet` value and a dot.

The prefix is not decoration. Manifest reconciliation derives which scenes a consumer's run is
entitled to judge from the prefixes present in its expected-scenes file, which is what keeps
StageWright's own built-ins — which carry no dot — out of a consumer's verdict. A consumer that
omits `@SceneSet` gets bare method names that no manifest can bound.

Names must be globally unique across every provider in the run. A duplicate is rejected before the
suite header is written: two records under one name let the later one overwrite the earlier in any
last-wins map, which turns a real failure into a pass.

### Options a scene declares

Every option below is an `@SceneDef` attribute and has a `Scene#with…` counterpart for the long form.

| Option | Default | What it does |
|---|---|---|
| `budget` | `200` | Tick budget. Exceeding it is a TIMEOUT, which the run reports separately from a FAIL. |
| `chunkRadius` | `1` | The force-loaded window, `(2r+1)²` chunks around the origin. `r=1` covers `dx`/`dz` in `[-16, 31]`. |
| `originSlot` | `-1` (automatic) | Pin the scene to a fixed grid slot. Required for anything whose result depends on position, because automatic slots shift as the suite grows and double-precision physics differs by position. |
| `required` | `true` | `false` records a FAIL or TIMEOUT without breaking the run. For a faithful sensor of a defect you have accepted and want watched rather than fixed. |
| `terrain` | `Terrain.RUN_WORLD` | The ground under the arena. `SUPERFLAT` and `GENERATED` are dimensions StageWright ships, so they are the same whatever world type the run was launched with; `GENERATED` uses a fixed seed. |
| `clock` | `Clock.MIDNIGHT` | The time of day, applied per scene. See [the world a scene runs in](#the-world-a-scene-runs-in). |
| `dimension` | `""` | Run the arena in a dimension some other mod registers, named as `"twilightforest:twilight_forest"`. |
| `mustSkip` | `false` | This scene's subject *is* the skip. See [asserting a skip](#asserting-a-skip). |
| `tags` | none | Free-form labels. Not part of the name and not reconciled. |

`terrain` and `dimension` are alternatives, not an addition: a terrain **is** a dimension StageWright
ships, so asking for both asks for the arena to be in two places, and `Scene`'s constructor rejects
it rather than silently honouring one.

The two absences mean different things, and the framework keeps them apart. A missing `terrain`
dimension means StageWright's own datapack failed to load, which is an environment failure. A missing
mod dimension means that mod is not in this runtime, which is a recorded skip naming it.

`Scene#withArena(false)` has no annotation counterpart. It drops the *wait* for the arena to become
entity-ticking, for a scene that plays in the live world and never sets foot in its plot. The context
is still built and the arena is still force-loaded and audited.

## Building the world

Scene code never sees absolute coordinates. Everything is relative to the origin the harness picked,
through `rel(dx, dy, dz)` and the block verbs that take the same three offsets.

`arena()` builds terrain from one text grid per Y layer, which keeps the shape legible in the source
instead of reconstructed from a run of `setBlock` calls:

```java
s.arena()
 .key('#', Blocks.STONE)
 .key('~', Blocks.WATER)
 .layer(0, """
     #######
     #~~~~~#
     #######
     """)
 .build();
```

Rows run along `+Z` and columns along `+X`, and the grid is centred on the origin unless `at(dx, dz)`
pins its top-left corner instead. `'.'` places air and a space leaves whatever is already there; both
are overridable with `key`. Any other unmapped character is a hard error, because a typo that placed
nothing would leave a scene asserting against terrain it never built.

Nothing is written until the whole grid parses and every cell is confirmed inside the force-loaded
window, so a bad character cannot leave a half-built arena behind, and a cell outside the window is
reported as such rather than vanishing into an unloaded chunk.

For simpler needs, `setBlock(dx, dy, dz, block)` places one block and `floor(size, block)` lays a
`size × size` pad at `dy = 0` with four layers of air above it. `setBlock` registers teardown for
anything carrying a block entity: releasing the force-load does not stop a hopper, and one left in a
resolved scene's arena spends tick budget for the rest of the suite.

`command("setblock ~ ~ ~ minecraft:diamond_block")` runs a command at the scene's origin, so `~ ~ ~`
is this arena and not the world origin. It returns the command's numeric result and the lines it
printed, which makes `data get` a way to read state no block lookup reaches, and it throws on any
command the game rejects — vanilla reports command errors to the source and returns normally, so
without that a typo in a scene would be a line that does nothing and passes.

Commands are the widest surface a scene has, and the only one where nothing goes through a method
name that the two loaders spell differently.

## Asserting

`expect(value)` fails the scene at the first violation. `check(value)` records the violation and
carries on, and every violation collected that way is reported together when the scene finishes —
the scene still fails. Prefer `check` whenever a body probes a set of things, because the first
offender is rarely the informative one.

```java
s.expect(uids).as("the database").contains(waystone.uid());
s.check(slots).as("the machine's inventory").hasSize(9);
```

`as(label)` names what is being asserted; the label is what a reader sees in the failure. The
vocabulary is the usual one — `isEqualTo`, `isNotNull`, `isTrue`, `isAtLeast`, `isBetween`,
`isCloseTo`, `contains`, `hasSize`, `isEmpty`, `satisfies(description, predicate)` and their
negations. `expectBlock(dx, dy, dz)` and `checkBlock(dx, dy, dz)` are the block forms, pre-labelled
with the position.

`fail(reason)` fails outright, for a condition no assertion expresses cleanly.

### `record`, and why it is framework machinery

`record(key, value)` attaches a value to the scene's line in the results file **and** appends it to
every failure message the scene produces afterwards.

```java
s.record("startY", startY);
s.record("onGroundAtSummon", stand.onGround());
```

This exists rather than leaving scenes to assemble their own evidence into a failure string because
the failure reason is the only diagnostic channel that reliably survives a full run: the game's
asynchronous logger drops bursts precisely when a long suite is finishing.

Record the state going *in* before an `await`, not inside its continuation. A timeout has no
continuation to record from, so a scene that only reports on success reports "it did not happen" and
nothing else — which is the same line whether the thing never started or ran perfectly and was
undone by something else.

`passNote(note)` attaches a visible note to a PASS. Use it for a deliberate, auditable trivial pass,
such as a body that has nothing to do on this topology. It is not a way to hide one.

## Waiting for something to happen

`await(condition).within(ticks).then(action)` is the only way a scene spans ticks.

```java
s.await(() -> stand.getY() < startY - 1)
 .within(60)
 .then(() -> s.record("fellBy", startY - stand.getY()));
```

Steps run in the order they were registered. Each step's `within` budget — 100 ticks if you do not
set one — is counted from the tick that step becomes current, not from the start of the scene. A
step that never comes true within its budget resolves the scene as a TIMEOUT naming the budget it
overran. Several steps can fire in the same tick if their conditions are already true.

The condition itself runs every tick, which makes it the right place to keep a record fresh: records
travel with the scene whatever the outcome, so a condition that records as it polls gives a timeout
the entity's last state instead of only its first.

## Cleaning up

`cleanup(runnable)` registers teardown that runs on PASS, FAIL and TIMEOUT alike, in reverse order of
registration. Anything server-wide that outlives the scene's own chunks belongs here: a database row,
a player's inventory, a config override, a spawned entity. Without it, the next scene inherits it.

Two cleanups are automatic. `playerHere()` restores the player to where they were, and `setBlock`
reverts any block entity it placed.

## Players, and what a skip means

`players()` is everyone connected. `playerOrNull()` is the first of them or `null`.

`player()` is the first connected player, or a recorded **skip** out of the body when there is none.
`playerHere()` is the same, teleported into this scene's arena and restored afterwards — reach for it
whenever the interaction under test cares about proximity, which most do.

A skip resolves the scene as a PASS carrying the reason. That is deliberate: a topology without a
player is not a defect in the scene, and a mod that is not installed is not the pack's defect. But a
skip must not read as a pass either, so the outcome is recorded, counted, reconciled against the
manifest, and reported separately from a real pass. The rule is uniform — `player()`,
`mods().require(id)`, `capability(name)` and `probe(className)` all skip the same way, with a reason
that names what was missing.

What a scene proves by skipping is nothing. See [Topologies](topologies.md#a-skip-is-not-coverage)
for the check that stops a suite whose every player scene skips everywhere from reporting success.

### Asserting a skip

`@SceneDef(mustSkip = true)` inverts the rule: the scene's subject *is* the skip, the run requires
it, and a scene that executes instead is judged as a framework failure rather than a pass. What broke
in that case is the absence detection every other scene's skips are trusted through.

Only for scenes that can never be satisfied where they live — a deliberately non-existent mod id, a
facet whose mod the suite's own runtime excludes. It is not a way to excuse a scene that skips
because the topology is thin: that scene should execute somewhere, and marking it here suppresses
exactly the report that would have said so.

## How a scene reaches the runtime

The harness discovers providers through `java.util.ServiceLoader`. Ship a file named
`META-INF/services/net.magicterra.stagewright.scene.SceneProvider` whose lines name your
implementation classes:

```
com.example.mymod.scenes.MagnetScenes
com.example.mymod.scenes.RitualScenes
```

Execution order is StageWright's built-in scenes, then each provider's scenes in discovery order,
then any JavaScript scene files. Within a provider using the annotation form, scenes are ordered by
name rather than by declaration: reflection cannot recover source order, so the alternative is an
order that varies between JVMs — and grid slots are assigned in registry order, which would move
every arena between runs.

**Keep exactly one service file per provider across all source sets.** A copy in a loader module
alongside one in a shared module registers the provider twice on that loader's classpath and trips
the duplicate-name check.

If your scenes live in a source set that is folded into the same mod as your production code, they
must occupy a package your production code does not also populate. A package owned by two source
sets that feed one module is a split package, and the loader's module layer rejects it outright.

## A scene and its manifest entry land together

A run reconciles the scenes it registered against a checked-in manifest — one scene name per line,
`#` comments and comma-separated names allowed. The manifest is named by the consuming project's
topology declaration; see [the Gradle plugin](gradle-plugin.md).

**Add a scene's name to the manifest in the same commit that registers the scene.** A scene that is
registered but not declared fails the run with an `UNDECLARED:` line, and a name in the manifest that
no provider registered fails it too. Both directions are checked, because the hole this closes is a
scene that is missing from the manifest *and* from the registry — broken service wiring, a typo in a
class name — which would otherwise leave the suite self-consistently reporting success over fewer
scenes than anybody intended.

An empty resolved manifest fails the run rather than degrading to "expect nothing".

## The world a scene runs in

Every scene in a run shares one world, and left alone that world moves: the clock advances, weather
rolls, mobs spawn. What a scene saw would then depend on how long the scenes before it took, which is
a property of the machine rather than of the test. So a run holds four things still, announces them
in the log at suite start, and writes them into the results header:

| Pinned | Why |
|---|---|
| `dayTime`, at the scene's clock — midnight by default | A suite of short scenes finishes within a couple of hundred ticks of where it started. Starting at dawn puts every scene in the band where sky brightness crosses the threshold vanilla rolls against to decide whether a sun-sensitive mob ignites, which makes that a coin flip decided by tick alignment. Midnight is the only value that is *decided* rather than merely fixed: it is not day, so the roll never happens. |
| `doDaylightCycle=false` | Setting the time is not enough on its own. A long scene drifts back into the band it was moved out of. |
| `doMobSpawning=false` | Required *by* the choice of night rather than independent of it. Arenas tick entities and the arena audit reports an entity increase inside the box as a leak, so natural hostile spawning would become a fresh source of false failures. |
| `doWeatherCycle=false`, weather cleared | The audit already treats a rain or thunder flip as a leak, so weather straddling a scene would accuse that scene. |

`randomTickSpeed`, `doFireTick` and `mobGriefing` are deliberately **not** pinned. A crop that grows,
a fire that spreads and a creeper that craters are things a pack's own scenes legitimately test, and
nothing changes them unless a scene does.

The pin is applied when a suite actually starts, not when the runtime merely arms, so a held game
that nobody has asked to run anything keeps its ordinary world. It is released when the suite
finishes, which matters on a held game and nowhere else.

A scene whose subject *is* the time of day says so, and the clock is applied per scene, so one
scene's time is never a function of the scene before it:

```java
@SceneDef(budget = 200, clock = Clock.NOON)
static void phantomsBurnAtDawn(SceneContext s) { … }
```

`Clock.MIDNIGHT` is `dayTime` 18000 with the cycle frozen, `Clock.NOON` is 6000 with the cycle
frozen, and `Clock.RUNNING` starts at midnight with the daylight cycle actually advancing, for a
scene whose subject is the passage of time. The harness restores the frozen default before the arena
audit's closing snapshot, so a `RUNNING` scene is not reported for a gamerule the harness changed on
its behalf.

### The world is the same world on every topology

A topology that differs in game mode or difficulty is not a second topology, it is a second product,
and every disagreement it reports is about itself rather than about the mod. So the game client that
hosts its own integrated server creates that world with the settings a dedicated server boots with —
survival, easy, cheats enabled — and not the creative-and-peaceful pair a sandbox would reach for.

That mattered once, and not as a warning: with a creative peaceful world, hostile mobs never spawned
and the player was immune to the damage several scenes asserted on, so those scenes reported timeouts
and refused damage and read exactly like defects in the mod under test.

Provisioning also forces a fixed world seed and turns the server's online mode off, so a run's
landscape is the same landscape every time. [Topologies](topologies.md) has the rest, including what
keeps the two ends of a two-process run identical.

## Scenes written in JavaScript

A modpack author with a `config/` folder and no build tool writes the same scenes in JavaScript. They
register into the same registry, run under the same harness, and land in the same results file:

```javascript
scene('pack.furnaceSmelts', 200, function (s) {
    s.floor(5, block('minecraft:stone'));
    s.expect(s.mods().loaded('mymod')).isTrue();
}, { clock: 'noon' });
```

`scene.optional(name, budget, body, options)` is the `required = false` form. Options are `terrain`
(`'run_world'`, `'superflat'`, `'generated'`), `clock` (`'midnight'`, `'noon'`, `'running'`) and
`dimension`. `block(id)` resolves a block by id and fails loudly on an unknown one; `console.log`
goes to the server log prefixed with the scene's name.

`s` is the same `SceneContext` a compiled scene receives, so anything documented above is available.
One rule is specific to this surface and is absolute: **a scene file may hold a Minecraft object and
pass it around, but must never call a method on one.** A production Fabric jar carries intermediary
names, so a by-name call that works on NeoForge throws on Fabric with a message that names neither
the cause nor the fix. StageWright's own names are not remapped, which is why everything a scene
legitimately needs from a Minecraft type is reachable through an accessor — `s.originX()` rather than
`s.origin().getX()`.

Scene files live in `config/stagewright/scenes/` and are loaded in file-name order. They need Rhino
on the runtime's classpath; where it is absent, the run fails and says so rather than reporting a
suite that quietly contained none of them.

## Reaching what the base game has no concept of

Everything above is the vanilla server API. For a mod's own machinery — a quest graph, an energy
network, accessory slots — see [Capabilities](capabilities.md).
