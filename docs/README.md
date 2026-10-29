# StageWright documentation

StageWright is an in-game test framework for Minecraft mods and modpacks. Tests — JUnit 5 classes, or YAML and
JavaScript files — run inside a live game; a scene file says which game they need, and StageWright starts it, runs the
tests in it and reports them as ordinary JUnit results.

If you have not used it before, start with the [repository README](../README.md) and then
[Getting started](guide/getting-started.md).

The documentation is in four parts, and the difference between them is what kind of question each answers.

## Guides — how to do something

Written for someone with a task in front of them.

| Document | Read it when |
|---|---|
| [Getting started](guide/getting-started.md) | You want a first passing run, whether you build a mod with Gradle or ship a modpack and have no build tool. |
| [Writing tests](guide/writing-tests.md) | You are writing tests: what a test can ask for, waiting, sharing the game with other tests, the YAML and JavaScript forms. |
| [Capabilities](guide/capabilities.md) | A test needs to reach machinery the base game has no concept of, or you want to teach the framework about your own. |
| [Topologies](guide/topologies.md) | You need to know which kind of game can establish the fact you are trying to establish. |
| [The Gradle plugin](guide/gradle-plugin.md) | You are wiring StageWright into a project: test suites, topologies, the source-set convention. |
| [Moving from scenes to tests](guide/migrating-from-scenes.md) | Your project still has tests for the old scene way — `@SceneDef`, `scene()`, `expected-scenes.txt`, the topology tasks — and you are moving them to the new one. |

## Reference — what the contracts are

Written to be correct rather than readable in order. These are the agreements two separately built programs rely on,
so they are stated as literal names, keys and codes.

| Document | Covers |
|---|---|
| [Scene file](reference/scene-file.md) | A scene's declaration: its topology, its mods and files, and the world it runs on — what applies at creation, what is set once loaded, and the defaults. |
| [Launch spec](reference/launch-spec.md) | The file that tells the test engine how to start one topology's games, and what the engine adds to the command. |
| [Supervision protocol](reference/supervision-protocol.md) | What the agent in a game's JVM sends its supervisor, the conclusions drawn from it, the test events it carries, and how the mod talks to the agent. |
| [Publishing and artifacts](reference/publishing.md) | What is published, which coordinate to depend on for which job, the order to publish in from nothing, and why the wrong coordinate fails to link rather than failing to resolve. |

## Design — why it is shaped this way

Written for someone who wants to change the framework, or who disagrees with a decision and wants to know what it was
weighed against.

| Document | Decision |
|---|---|
| [The test engine](design/test-engine.md) | Tests run inside the game and are reported by an outer JUnit engine: finding them without loading them, matching the two trees, script tests, running in parallel, restarts, and why it reads a launch spec instead of a Gradle task. |
| [Supervision](design/supervision.md) | How the test engine learns that a game finished, stalled, crashed or cannot exit: an agent inside the game's JVM reporting over a local channel, instead of reading files from outside. |
| [Capability extensions](design/capability-extensions.md) | The two routes by which a third-party mod or a modpack teaches the framework something new, and the rule that makes the reflective route sound. |
| [Test queries](design/test-queries.md) | StageWright's own `mc.test.*` questions — chunks, recipes, loot, advancements, structures: one method per subject, generated callers, and what is an answer rather than a failure. |

## Archive — historical records

[`archive/`](archive/README.md) holds documents kept for provenance. Each describes the project as it was when written
and none of it documents current behaviour.

| Document | What it was |
|---|---|
| [Adoption feedback, 2026-08-03](archive/feedback/2026-08-03-stonewrit-adoption.md) | A report from the first external project to adopt the framework. |
| [Scene capabilities](archive/scene-capabilities.md) | The design of the item, recipe, loot, structure, menu, equipment and quest facets on the old scene context, and the traps in Minecraft's lookup APIs it ran into. The facets now live in the capability layer and in `mc.test.*`. |

## Maintaining this index

Every document under `docs/` appears in exactly one table above, and a new document is added to the index in the same
change that creates it — an unindexed document is one nobody finds and nobody updates.

Two rules have been expensive to learn here and are worth restating. First, a claim is verified against the code, not
against the previous version of the sentence; most errors found in this documentation were inherited by copying, and
several had survived more than one revision that way. Second, do not write a count that will be wrong next month —
point at the file that holds the answer.
