# Attached protocol

The agreement between the runner that executes a scene file out of the game process and the game
that holds the scene's arena. Seven hidden methods, all under `mc.test.attached.`, callable on any
transport and left out of the MCP tool list. Why it is shaped this way is in
[Attached scenes](../design/attached-scenes.md); this page states the names and the values.

Protocol version: **1**. A runner sends it in `begin`; a game that speaks another version refuses.

## The run

```
begin → ( open → status* → call* / route* → close )* → end
```

A run is one at a time per server. `begin` refuses when the in-game suite has been started or
another run is open, because both freeze the world and neither can give it back for the other.
`mc.test.run` is refused while a run is open. A run that makes no request for ten minutes is ended
by the game, so a runner that died does not leave the world frozen.

All methods are answered after the game has handled them on the server thread, between ticks.

## Methods

| Method | Params | Answer |
|---|---|---|
| `mc.test.attached.begin` | `protocol` (int) | `pinned` (true), `description` (what was frozen, as the in-game results header states it) |
| `mc.test.attached.end` | none | `released` (whether a run was open) |
| `mc.test.attached.open` | `scene`, `terrain` (`run_world` · `superflat` · `generated`), `clock` (`midnight` · `noon` · `running`), `dimension` (optional), `chunkRadius` (0–8) | `session` (int, from 1) |
| `mc.test.attached.status` | `session` | `state`, `message` |
| `mc.test.attached.call` | `session`, `target`, `method`, `args` | an *answer*, below |
| `mc.test.attached.route` | `session`, `method`, `params` (object) | an *answer* |
| `mc.test.attached.close` | `session` | `leaks`, `records`, `softViolations`, `cleanupFailures` |

**`open` answers at once; the arena takes ticks to arrive.** The runner polls `status` until the
state is final. `state` is one of:

- `preparing` — keep asking.
- `ready` — the context exists; `call` and `route` are allowed.
- `skipped` — the scene named a dimension this runtime does not have. Final; the session is gone
  once this is read. The runner records the scene as skipped, carrying `message`.
- `failed` — the arena never became usable, or a terrain dimension StageWright should have
  provided is missing. Final, as `skipped`. The runner records a failure whose body never ran.

Give up on stalled progress, not on elapsed time: the game ends the wait itself when no chunk in
the arena or its level has moved for a fixed number of ticks, and says so in `message`.

**`call`** runs a method by name on `target`, on the server thread. `target` 0 is the scene's
`SceneContext`; any other number is an object an earlier answer handed out. The method is looked up
by name and argument count on the object's own class when the call arrives. Overloads are settled
by how exactly the values fit the parameters; a tie is refused, naming both. A method declared by
`java.lang.Object` is refused.

**`route`** calls a driver method on the server thread, as the in-game `driver(...)` does. It
refuses `mc.test.attached.*` and `mc.test.run`.

**`close`** runs the cleanups the game holds for the scene, reads the context's records and soft
violations, sweeps leftover entities, releases the chunks and audits the world. `leaks` is one line
per way the world differs from the baseline taken when the arena arrived. `cleanupFailures` is one
line per cleanup that threw. It also ends a session that never became ready.

## Answers

`call` and `route` answer `{ok, value?, kind?, message?}`. A refusal is an answer with `ok: false`,
not a protocol error, so that the runner can raise the exception an in-process scene would have
caught. `kind` is one of:

| `kind` | The runner raises |
|---|---|
| `skipped` | a skip, with `message` |
| `failed` | a scene failure |
| `illegalArgument` | an illegal-argument exception (a rejected value, an unknown id) |
| `unsupported` | a scene failure saying the name cannot be called from out of process |
| `error` | a scene failure carrying the exception's class and message |

A protocol error (an unknown session, a call before `begin`, a version mismatch) is an ordinary
driver error, not an answer.

After every `call` the game checks that the context has no pending `await` step, and fails the
call if it does.

## Values

JSON, with three markers. A key that begins with `$` is never plain data.

| Form | Meaning |
|---|---|
| number, string, boolean, array, object | itself. A whole number is sent as an integer, anything else as a double. |
| `{"$id": "block", "id": "minecraft:stone"}` | a registry entry. Kinds: `block`, `item`. An id that names nothing is refused with the registry's words. |
| `{"$handle": 7, "type": "CommandResult"}` | an object kept in the game under that number, for the length of the scene. `type` is the simple class name, for messages. |
| `{"$null": true}` | a null argument. A call's argument list cannot hold a bare `null`. |
| `{"$map": {…}}` | an object whose own keys begin with `$`, wrapped so it cannot be taken for a marker. |

An answer's `null` is `value` left out.

Arrays and collections are sent as arrays. Records and every other object are held, not copied.
Functions cannot be sent; the runner refuses them before the call, naming the argument. At most
10,000 objects are held per scene.

A parameter of an enum type takes the constant's name, matched exactly and then ignoring case. A
whole number is passed to the narrowest parameter that fits it, `int` before `long`, as a script's
integer would be.

## Where the arena is

Each scene gets a slot in the grid of the level its terrain names, on its own row far from the
in-game suite's. Slots are not reused within a server's life, so a scene never inherits the blocks
an earlier one left. The chunks are forced and the arena waits for them with the same code the
in-game scheduler uses.
