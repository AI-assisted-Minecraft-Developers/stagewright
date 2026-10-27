# The test engine

Tests run inside the game. The test engine is what lets a build tool see them anyway: a JUnit Platform engine, id
`stagewright`, that Gradle's `test` task, an IDE, or the command-line runner loads like any other. It finds the tests,
starts a game for each scene, and reports the game's results as its own, so a failure inside the game is a failed test
in the build, with the game's stack trace.

## Two runs, one tree

The engine runs in a JVM without Minecraft. The tests run in the game's JVM, under a JUnit launcher embedded there.
Each side discovers the tests; the engine's tree is the one reported, and the game's is matched to it.

```
engine (outer)                          game
  scene "survival"            ──start──▶  JUnit launcher, classes = those of "survival"
    class FurnaceTest                       class FurnaceTest
      smeltsIron()           ◀──events──      smeltsIron()
```

The outer tree is engine → scene → class → method (a script file and its tests in place of a class and its methods). A class naming two scenes sits under both and runs in both,
which is what makes "this test ran in at least one scene" a question about one tree.

## Finding tests without loading them

A test class's signatures name Minecraft and the mods; loading one in the engine's JVM would fail, or worse,
partly succeed. So the engine reads class files with ASM: the `@Scene` values, the test methods (inherited ones
too), their parameter types spelled as JUnit's `MethodSource` spells them, and `@DisplayName`. That spelling is what
a game node is matched on: class, method name and parameter types.

The game then discovers for real. A test the engine found and the game did not is reported as failed, never as
skipped: the usual cause is a class that failed to load in the game, which is exactly the silent loss the old
expected-scenes manifest existed to catch. A parameterized test's invocations exist only in the game; the game
registers them as they appear, and the engine adds them to its tree as dynamic tests.

## Script tests

A test written in JavaScript lives under `stagewright/tests/` in the suite's resources and registers itself with
`test(name, options, body)`; `step(name, body)` registers a named step any test can take with `t.step`. The outer tree
is engine → scene → file → test, the scenes coming from each test's `options.scene`.

Gradle selects classes only, so the plugin tells the engine where the suite's roots are (`-Dstagewright.testRoots`)
and the engine walks them for `.js` files. It reads each one by evaluating it in a Rhino of its own, relocated into
its jar, with a `test()` that records the name and scenes and never runs the body. A file's registrations therefore
must not depend on the game, and a file whose top level reaches for it fails here, with the line. Both sides use the
same Rhino, so a file that parses here parses there.

In the game, the `stagewright-script` engine evaluates the file again through WorldDriver's `Scripting` (StageWright
links no Rhino of its own: the shipped WorldDriver relocates it) and keeps the tests of the scene the game was started
for. A test's `t` offers `call`, `step`, `wait`, `waitUntil`, `settle`, `expect` (`equals`, `contains`, `isEmpty`,
`gt`, `lt`, `atLeast`, `atMost`) and `record`; the handles `plot({size})`, `bot(name)`, `player()` and `client()`,
as plain objects (a position is `{x, y, z}`, a block or item its id); and `override(rule, value)`, `overrideTime`,
`overrideWeather` and `overrideTickRate`. What a test leases is given back and what it overrides undone when it
ends. The script also has WorldDriver's own `Driver.*`. A failed `t.expect` cannot be caught by the script, so a test
cannot swallow its own failure.

The options are `scene`, `requires` (`client`, `dedicatedServer`, `integratedServer` or `mod:<id>`, counted toward
coverage as `@Requires` is), `dependsOn` (a test's name in the same file, or `file.js#name`; a test runs after those
of its own file, and aborts when one did not pass) and `knownIssue` (the reason, read as `@KnownIssue` is). Script
tests run one at a time after the game's Java tests, so an override needs no lock. `waitUntil` and `settle` check
their condition on the test thread once a tick, since a condition usually calls WorldDriver.

A test written in YAML sits beside them, in a file with a `tests` list and an optional `scene` for all of them; a
test takes the same options, plus `plot: {size}`, and `given`/`when`/`then` or `steps`. A step is `call: method`
with `params`, `wait`, `waitUntil`/`settle` (a `{call, params, expect}` condition, with `within` and `ticks`), or a
step a script registered, `name: args`; JavaScript files load first so their steps are there. Any step may carry
`expect`, whose keys are operators (`equals`, `contains`, `empty`, `gt`, `lt`, `atLeast`, `atMost`, `anyOf`,
`allOf`) or field paths (`audit.count: {gt: 0}`), and `save: name`, read later as `${name}` or `${name.field}`;
the leased plot is `${plot}`. There is no expression language. The game reads YAML with a snakeyaml relocated into
StageWright's loader jars; the engine outside reads only the names and scenes.

## One channel back

The game reports through the supervision agent that already watches it: a `test` line per event, on the same
connection as heartbeats, crashes and `done`. Because it is one ordered stream, the engine can tell "the game crashed
during `smeltsIron`" from "the game crashed after its run". The channel is one-way; what the game should run is
decided before it starts, by system properties ([launch spec](../reference/launch-spec.md)).

When the game ends, everything it did not report is closed out as failed with the reason: the run's own error (a
`@DependsOn` cycle), a test it never discovered, or how the game ended — crashed, stalled, timed out, exited.

## Tests in a game run in parallel

The game's launcher runs classes and methods concurrently unless the run's own configuration — a
`junit-platform.properties` on the game's class path, or a `-D` — says otherwise; StageWright's defaults sit below
both. What two tests share is declared the JUnit way: `@ResourceLock` on the locks `Locks` names, read-only where a
test only reads, or `@Isolated` on a class that needs the game to itself. An override without its lock is refused.

`@DependsOn` holds in a parallel run by waiting: a dependent test blocks until each test it names has ended, every
invocation of it included, and the pool adds a thread for as long as it waits. A class whose tests depend on one
another runs them one at a time in dependency order, since a dependent that took a lock first and then waited for a
test needing the same lock would wait forever.

## Every test runs somewhere

`@Requires` is checked in the game, which disables what its game lacks with a reason beginning `@Requires: `.
The engine collects those skips across every scene of the run. A test some game started is covered; a test that
every scene it names disabled for `@Requires` never ran at all, and the engine itself finishes failed, listing each
such test with what every scene lacked. Other skips — `@Disabled`, a failed assumption — are the test author's
choice and do not count.

`@KnownIssue` changes only how one test's result is read: a failure becomes an abort carrying the reason, and a
pass becomes a failure, so the annotation comes off once the issue is fixed.

## Why a launch spec, not a Gradle task

The engine runs inside Gradle's test worker, which cannot run another task. The loader plugin is still the only thing
that knows how to start its game, so the Gradle plugin reads the topology's run task when the build is configured and
writes down what it would have executed — the same reading that already starts the companion client. The
command-line runner writes the same file from a game directory. The engine knows neither.

## Not yet

- Scene declarations apply `topology` and `world` ([scene file](../reference/scene-file.md)); `mods`, `files`,
  `clients` and `world.from` are refused rather than ignored, and the creation settings reach a dedicated server only.
- Scenes run one after another, and a game's script tests one after another.
- A script test cannot yet depend on a Java test, nor a Java test on a script test.
- YAML has no built-in steps yet (`place`, `insert`, `chunk.unload`, `container`), no `knownIssues` subtraction,
  no `java:` step and no `perf`.
- A game is started once per scene and never restarted, so `afterRestart` and `store` wait for a channel into the game.
