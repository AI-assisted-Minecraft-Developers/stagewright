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

The outer tree is engine → scene → class → method. A class naming two scenes sits under both and runs in both,
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

## One channel back

The game reports through the supervision agent that already watches it: a `test` line per event, on the same
connection as heartbeats, crashes and `done`. Because it is one ordered stream, the engine can tell "the game crashed
during `smeltsIron`" from "the game crashed after its run". The channel is one-way; what the game should run is
decided before it starts, by system properties ([launch spec](../reference/launch-spec.md)).

When the game ends, everything it did not report is closed out as failed with the reason: the run's own error (a
`@DependsOn` cycle), a test it never discovered, or how the game ended — crashed, stalled, timed out, exited.

## Why a launch spec, not a Gradle task

The engine runs inside Gradle's test worker, which cannot run another task. The loader plugin is still the only thing
that knows how to start its game, so the Gradle plugin reads the topology's run task when the build is configured and
writes down what it would have executed — the same reading that already starts the companion client. The
command-line runner writes the same file from a game directory. The engine knows neither.

## Not yet

- Scene declarations apply `topology` and `world` ([scene file](../reference/scene-file.md)); `mods`, `files`,
  `clients` and `world.from` are refused rather than ignored, and the creation settings reach a dedicated server only.
- Scenes run one after another; tests within a game run one after another.
- A game is started once per scene and never restarted, so `afterRestart` and `store` wait for a channel into the game.
