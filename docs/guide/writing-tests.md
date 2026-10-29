# Writing tests

A test runs inside the game named by its scene ([scene file](../reference/scene-file.md)). This guide covers Java tests
first, then the YAML and JavaScript forms, which offer the same things as plain values. The Java API is in
`net.magicterra.stagewright.test` (the `mc_stagewright-api` artifact); its Javadoc is the full reference.

## What a Java test receives

A test class carries `@Scene("<name>")` (several names run the class once in each game) and ordinary JUnit 5 `@Test`
methods. StageWright resolves these parameters:

| Parameter | What it is |
|---|---|
| `Game` | The game: WorldDriver's methods, the server thread, waits, soft checks, overrides. Also a constructor parameter. |
| `Plot` | A box of a dimension leased to this test, handed over empty (air, chunks loaded) and put back exactly as it was when the test ends, entities included. Plots never overlap. |
| `Bot` | A server-side player joined for this test: survival, empty inventory, full health, at the world spawn. It is on the player list like any player, and leaves when the test ends unless the test keeps it for a later one. |
| `Player` | The real client's player. Shared by every test of the run, and keeps what a test leaves on it. |
| `Client` | The real client itself, for WorldDriver's client methods. |

`Player` and `Client` exist only where a client is connected; a test taking them carries `@Requires(CLIENT)`.

A plot is a 16-block cube in the overworld unless its parameter says otherwise: `@Plot.Size(32)`, or each axis on its
own, `@Plot.Size(x = 120, y = 16, z = 8)` (up to 256 across and the dimension's height); `@Plot.Centered` puts relative
`(0, 0, 0)` in the middle; `@Plot.In("minecraft:the_nether")` leases it in another dimension. Coordinates passed to a
plot are relative, and `plot.at(x, y, z)` gives the world position. `plot.structure(id, x, y, z)` places a structure
template by id, or from a structure file.

A bot joins under a made-up name unless its parameter says `@Bot.Name("steve")`. A test can hand its bot on: with
`@Bot.Name("steve") @Bot.Keep` the bot stays when the test ends, parked where it stood with what it had, and a test
that `@DependsOn` this one takes it as `@Bot.Name("steve") @Bot.Kept` — the same player, back on the tick list. A test
that fails or is aborted removes its bot after all; a kept bot must stand outside the test's plots, which are put back
when the test ends; and a restart ends every kept bot, so a test after one joins its own.

## Calling the game

Tests run on a test thread, never the server thread, so every method may block and a test reads as the sequence of
things it does.

- **WorldDriver's methods** through their generated callers, answered on the test thread:
  `game.api(WorldBlockingApi::of).block(new BlockParams(at, false))`. A body is passed to them as `bot.ref()`.
- **Minecraft itself** on the server thread: `game.onServer(server -> …)` runs the work between two ticks and returns
  its result; `bot.onServer(player -> …)` does the same with the bot's `ServerPlayer`. The work must not call a blocking
  `Game` method.
- **Time**: `game.ticks(n)` lets `n` server ticks pass. `game.await().within(200).as("the door opens").until(() -> …)`
  checks a condition on the server thread after every tick and fails the test with that description when the budget,
  counted in ticks, runs out.
- **Soft checks**: `game.check(holds, "what")` or `game.check(() -> assertEquals(a, b))` keeps a failure and lets the
  test go on; every failed check is reported together when the test ends, and fails it.

```java
@Test
void aBotWalksWhereItIsSent(Game game, Plot plot, Bot bot) {
    plot.fill(0, 0, 0, 11, 0, 2, Blocks.STONE);
    bot.moveTo(plot.at(1, 1, 1));
    game.await().within(40).as("the bot lands").until(() -> bot.<Boolean>onServer(p -> p.onGround()));
    // … start a WorldDriver task with bot.ref(), then:
    BlockPos target = plot.at(10, 1, 1);
    game.await().within(400).as("the bot reaches " + target).until(() -> bot.blockPos().equals(target));
}
```

### Bodies

`Body` is what a `Bot` and the `Player` share: `name()`, `ref()`, `blockPos()`, `dimension()`, `moveTo(feet)` or
`moveTo(dimension, feet)`, `give(item, count)` and `count(item)`. `game.translate(pos, from, to)` maps a position between
dimensions as a portal does, so a test can say where a portal should have put a body.

`game.npc(EntityType.PIGLIN, feet)` spawns a mob and hands it to WorldDriver as a body, `mob:<name>`, for the rest of the
test. While WorldDriver drives it, the mob's own AI is off.

A bot and a mob can be ticked by hand. `park()` takes the body off the server's tick list (or `@Bot.Parked` joins a bot
already parked); then `tick(n)` runs whole ticks of that body alone, `tickUntilIdle(max)` runs until its task ends,
`step(n)` runs only its physics, `think()` only its decision, and `press(k -> k.move(0, 1).jump(true))` holds keys
until a later press changes them. `unpark()`, or starting a task on it, puts it back on the list. A parked mob is still
moved by the world: it falls, is pushed and keeps its momentum.

### Changing shared state

Tests in a game run concurrently by default, classes and methods alike. What two tests share is declared the JUnit way:

- A game rule, the time, the weather or the tick rate is changed with `game.override(rule, value)`,
  `overrideTime`, `overrideWeather` or `overrideTickRate`, which is undone when the test ends and is refused unless
  the test holds the matching lock read-write: `@ResourceLock(Locks.WORLD_RULES)`, `Locks.WORLD_CLOCK`,
  `Locks.WEATHER`, `Locks.TICK_RATE`. `Locks.player(name)` names one player.
- `@Isolated` on a class runs it with the game to itself; `@Execution(ExecutionMode.SAME_THREAD)` keeps a class's own
  methods one at a time. A `junit-platform.properties` on the game's class path changes the defaults.
- Anything else a test changes, it undoes with `game.afterTest(server -> …)`, which runs when the test ends, passed or
  not.

### Order, restarts and known failures

- `@DependsOn("otherTest")` runs a test after the named ones, on the world they leave; if one of them did not pass,
  this test is aborted rather than failed. `in = OtherClass.class` names another class, and
  `scripts = "stagewright/tests/setup.js#builds the base"` a script test.
- `@DependsOn(value = …, afterRestart = true)` runs the test after the game has stopped and started again on the same
  world. Values cross the restart in `game.store()`:

  ```java
  @Test
  void recordsTheMarker(Game game) {
      game.store().put("marker", "placed");
  }

  @Test
  @DependsOn(value = "recordsTheMarker", afterRestart = true)
  void readsItAfterTheRestart(Game game) {
      assertEquals("placed", game.store().get("marker"));
  }
  ```

- `@Requires(CLIENT)`, `@Requires(DEDICATED_SERVER)`, `@Requires(INTEGRATED_SERVER)` or `@Requires(mods = "id")` runs
  the test only in a game that has it; elsewhere it is reported as disabled. A test that no scene it names can run
  fails the run, since it would otherwise never run and never fail.
- `@KnownIssue("why")` keeps a failing test running: its failure is reported as aborted with the reason, and its
  passing as a failure, so the annotation is removed once the issue is fixed.

### Measuring performance

`game.perf(Perf.phases().phase("baseline", () -> {}).phase("loaded", () -> build(game)))` runs each phase's setup,
waits, and reports each phase's median window: ticks per second, tick intervals (mean and 99th percentile), retained
heap, CPU, and, on an integrated server, the client's frame rate. Compare a phase with the baseline taken in the same
run (`tpsDropVs`, `heapGrowthVs`, `cpuGrowthVs`, `fpsDropVs`), never with a constant.

## Script tests

YAML and JavaScript tests live under `stagewright/tests/` (in a Gradle project, `src/testmod/resources/stagewright/tests/`;
in a pack, `<server-dir>/stagewright/tests/`). They need no build, and they run beside the Java tests of the same
scene.

The test report lists a script test under its file's path from the test root, as if that were its class, and Gradle's
`--tests` matches it the same way: `--tests 'stagewright/tests/validation/42_combat.js'` runs that file's tests,
`--tests 'stagewright/tests/validation/42_combat.js.42_combat'` the one named `42_combat`, `--tests '*42_combat*'` any
file or test with that in its name, and `--tests '*JupiterBodyTest'` no script test at all. The game still runs every
test of a file it is given; only the selected ones are reported.

### JavaScript

A file registers tests at its top level; the registration must not touch the game, because the test engine reads the
file outside it to find the tests:

```javascript
test('name', { scene: 'mymod', requires: 'client', locks: ['weather'] }, (t) => { … });
```

The options are `scene`, `requires` (`client`, `dedicatedServer`, `integratedServer` or `mod:<id>`), `dependsOn` (a
test in the same file, `file.js#name`, or a Java test as `com.example.SomeTest#method`), `afterRestart`, `knownIssue`,
`locks` (the `Locks` names without `stagewright.`: `worldRules`, `worldClock`, `weather`, `tickRate`, `player:<name>`)
and `isolated`.

`t` offers `call(method, params)` for any WorldDriver method, `wait(ticks)`, `waitUntil(fn, {within, as})`,
`settle(fn, {ticks, within})`, `expect(value)` (`equals`, `contains`, `isEmpty`, `gt`, `lt`, `atLeast`, `atMost`),
`check`, `record(name, value)`, `step(name, args)`, `perf`, `store`, the handles `plot({size, x, y, z, centered,
dimension})`, `bot()` or `bot({name, parked, keep, kept})`, `player()`, `npc(type, pos)`, `client()`, `capability(name)`,
`translate(pos, from, to)`, and the overrides `override`, `overrideTime`, `overrideWeather`, `overrideTickRate`.
Positions are `{x, y, z}`; blocks and items are ids. The script also has WorldDriver's own `Driver.*`. A failed
`t.expect` cannot be caught by the script.

### YAML

A file has an optional `scene` for all its tests and a `tests` list. A test takes the same options as a script, plus
`plot: {size}`, and either `steps` or `given` / `when` / `then`. A step is one of:

- `call: <method>` with `params`;
- `wait: <ticks>`, `waitUntil:` or `settle:` a `{call, params, expect}` condition, with `within` and `ticks`;
- a built-in step: `place: {at, block, as}`, `insert: {into, items}`, `container: {of, …}`, `chunk.unload`,
  `chunk.reload`, `store.put`, `store.get`;
- a step a JavaScript file registered with `step(name, fn)`, or a Java method annotated `@Step("name")`, as
  `name: args`;
- `java: {class, method}` with `params`, calling a public static method;
- `perf: {phases, warmup, sample, repeat}`.

Any step may carry `expect` (operators such as `equals`, `contains`, `gt`, `anyOf`, or field paths such as
`light.sky: {atLeast: 0}`), `check` in the same form, which keeps going after a failure, and `save: name`, read later as
`${name}` or `${name.field}`. The leased plot is `${plot}`. There is no expression language.

The [test engine's design](../design/test-engine.md#script-tests) has the details of both forms: how steps are found,
how `knownIssues` takes known failures out of a result, and what each built-in step accepts.
