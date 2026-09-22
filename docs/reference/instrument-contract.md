# Instrument contract

Two things, and they belong together because one is the evidence for the other.

The **instrumentation surface** is how code outside the game process reaches the running game:
an endpoint descriptor the game publishes when it is genuinely attachable, a WebSocket to the
driver's router, and a small set of hidden verbs that exist only for test code.

The **instrument suite** is the set of permanent assertions made through that surface, against a
game that is not running any scenes. It exists because the rest of the suite cannot verify
itself. Scene bodies run inside the runtime under test, on the server thread, through the very
setup and assertion machinery whose soundness is in question. So these assertions are made from
another process, against the driver surface StageWright depends on, deliberately outside
StageWright's own assertion stack. When they pass, a scene's setup and assertions can be
trusted; when they fail, nothing downstream of them means anything.

The suite lives in `junit/src/test/java/net/magicterra/stagewright/junit/`. The attach machinery
it uses is published as `mc_stagewright-junit` and is the same machinery any consumer would use
to write out-of-process tests of their own.

## The endpoint descriptor

A held run publishes one file, `stagewright-endpoint.json`, in its own run directory. The name
is defined once, in `RunDirectory.ENDPOINT_FILE`, and repeated nowhere.

**The game writes it, not the launcher.** Only the game's own JVM knows the moment the endpoint
is genuinely usable — the socket listening, a world loaded, a player in it — and only it knows
the port it actually got, which is not the port anyone asked for whenever the pinned one was
taken. A launcher polling a log file from outside was wrong about that moment often enough to
need a retry loop.

Publication is requested with `-Dstagewright.endpoint=<path>`; unset, nothing is written. The
`stagewright.topology` property is recorded verbatim so that a reader knows which face it is
holding. Ports are read from the files the driver wrote when it bound them —
`worlddriver-rpc.port` and `worlddriver-mcp.port` — rather than from the properties that
requested them, because the property is what was asked for and the file is what was bound.
Reading files also keeps the writer from linking against the driver, which is what lets
StageWright arm in a runtime that has no driver at all.

Writing is idempotent and never throws. A hold whose descriptor could not be written logs the
problem and keeps holding, because the game is still attachable by hand from the port file.

### Schema

Version 1. Eight required keys, two optional.

| Key | Type | Presence | Meaning |
|---|---|---|---|
| `version` | int | required | Schema version, currently `1`. |
| `topology` | string | required | Whatever `stagewright.topology` was set to, or `"unknown"`. |
| `loader` | string | required | `"fabric"` or `"neoforge"`, or `"unknown"`. |
| `rpcHost` | string | required | Host the socket bound to. |
| `rpcPort` | int | required | The RPC port **of the JVM that wrote this file**. |
| `worldName` | string | required | The world this endpoint is in. |
| `holdPid` | long | required | The writing process. Telemetry for a human; not a liveness signal. |
| `writtenAtEpochMs` | long | required | When it was written. Not a freshness test — see below. |
| `serverRpcPort` | int | optional | Reserved for a descriptor that carries both faces. Nothing writes it today. |
| `mcpPort` | int | optional | The MCP HTTP port, written when the driver bound one. |

A missing required key throws, naming the key, rather than defaulting. **Unknown keys are
tolerated**, so a future key does not break an older reader.

`topology` is a free-form string with no validation. Today's values are the topology names the
consuming build declared, with `-client` appended for a companion.

Two URIs are derived rather than stored. The WebSocket address is
`ws://<rpcHost>:<rpcPort>/rpc`, and the `/rpc` path is load-bearing: omitting it does not fail
the handshake, it hangs it, so the symptom is a timeout that reads as "the pack is still
starting". The MCP address is `http://<rpcHost>:<mcpPort>/mcp`, and asking for it when no
`mcpPort` was written throws rather than producing a URL to nothing.

The host in both is the recorded bind address made dialable. A wildcard (`0.0.0.0`, `::`) accepts
on every interface but is not itself an address, so it becomes the same family's loopback
(`127.0.0.1`, `[::1]`); any other IPv6 literal is bracketed, because `ws://::1:39801` cannot say
where the port starts. Host names are used as written.

### Lifecycle

**Written once a run is attachable, not once a process has started.** There are two call sites
and exactly one fires per JVM:

- A **dedicated server** writes a server-face descriptor when it reaches STARTED, after scenes
  are resolved and the `mc.test.*` verbs are installed.
- A **client** writes a client-face descriptor on the first tick it has a level. Deliberately
  not at client init: `mc.client.screen.tree` answers at the title screen too, and every UI test
  would then race the world it assumes it is standing in.

The integrated topology has both in one JVM, so the server side stands down and the client
writes the single descriptor — the same port either way, but later, when there is a world.

**Deleted at provision.** A stale descriptor is the most dangerous kind of leftover, because it
names a port: an out-of-process test would attach to whatever now answers there — nothing, or
somebody else's game — instead of failing fast on a descriptor that is not there yet. That covers
a held companion's descriptor too, which sits beside the companion's results file rather than in
the primary run directory.

**A liveness probe is the only proof an endpoint is usable.** `writtenAtEpochMs` is not a
freshness test, and a file existing with a recent timestamp guarantees nothing about the process
it names: a hold killed by an external signal never runs its cleanup. So attaching sends one
`mc.system.version` call with a five-second budget, and an endpoint that does not answer it is a
loud failure rather than a silent skip. The WebSocket handshake gets its own five-second budget
before that.

Every attach failure — no endpoint configured, descriptor file absent, descriptor corrupt,
connection refused, probe unanswered — carries the same hint naming the Gradle hold task that
would produce one.

## Reaching the game

`TESTKIT_ENDPOINT` names the descriptor file — an absolute path, not a URL and not a port. The
system property `stagewright.endpoint` is a fallback, read when the environment variable is
absent or blank.

`StageWright.attach()` returns a facade over the driver's router. It is a thin one by design: it
wraps a JSON-RPC envelope, unwraps a single-key `{"result": …}` reply, and adds the handful of
verbs a test reaches for constantly — running a command, observing the player, reading and
driving the client's screen, resetting client state, and polling a condition to a deadline.
Everything else is `call(method, params)` straight through, because the point of testing through
this surface is to test the surface a real consumer uses.

Three behaviours of that facade are contractual:

- **A command that Brigadier rejects throws.** Vanilla's own dispatcher reports errors to the
  command source and returns normally, so without this a typo in a test is a line that does
  nothing and passes.
- **A refused call surfaces as an exception carrying the method and the driver's error string**,
  so a test can assert on the refusal rather than on the absence of a result.
- **A timeout is not an assertion failure.** `StageWrightTimeoutException` is a
  `RuntimeException` and deliberately not an `AssertionError`, because "the condition never held"
  and "the condition held and was wrong" are different findings, and a canary asserts on exactly
  that distinction.

One attach per JVM, one call at a time, a single test thread. The extension caches the
connection and — importantly — caches the *failure* too, re-throwing the same exception rather
than retrying: an endpoint that was not there at the start of a class was not there for a
reason, and retrying it once per test buries that reason under a page of identical errors.

### The hidden verbs

StageWright registers three verbs into the driver's catalog, all under `mc.test.*`, all hidden
so that they never enter an MCP `tools/list` and never cost a language-model client a token of
prompt budget. They are registered through the driver's paired registration entry point, which
supplies a schema and a handler atomically, so a hidden verb is still schema-checked.

| Verb | Face | What it is for |
|---|---|---|
| `mc.test.run` | wherever a harness is armed | Trigger the in-process suite on demand. Answers `{accepted, scenes}`; a second call is refused, as is a call to a runtime that never armed or to a server that is stopping. |
| `mc.test.reset` | client | Return the client to a clean state between tests: close any screen, release held keys, clear chat. |
| `mc.test.input.heldKeys` | client | Read back which movement and action keys are currently down. |
| `mc.test.input.useOnBlock` | client | Right-click one block without moving, aiming or sneaking — enough to open a container, and nothing else. |

`mc.test.reset` and the two input verbs are client-only and fail loudly on a dedicated server
rather than doing nothing. The registration path deliberately never touches a client type until
the handler runs, so registering them on a server does not drag client classes onto its
classpath.

The reason `mc.test.input.heldKeys` exists is worth stating, because it is the shape of problem
this whole document is about. `mc.test.reset` reports what it did as a list of tokens, and the
`keys` token is appended unconditionally — it proves the release routine *ran*, not that any key
was *cleared*. A release routine that had become a no-op would produce exactly the same token,
so the assertion built on it asserted nothing. Reading the keys back turns it into a real
assertion.

## Faces

A test declares which kind of game it needs:

| Face | Meaning |
|---|---|
| `CLIENT` | This JVM runs a client, so the client-side verbs answer. |
| `SERVER` | This JVM is a dedicated server, so the client-side verbs are refused. |

**The face is detected by probe, not read from the descriptor's `topology` label.** The
extension calls a client-only verb and reads the answer: it succeeds on a client, and on a server
it fails with a specific error string. Any other failure is re-thrown, because a broken transport
must not be allowed to read as "this is a server". The result is cached for the JVM's lifetime.

`@RequiresFace(Face.X)` on a test class makes the extension compare. A mismatch **disables** the
class, naming which face is held and which was wanted, and pointing at the other topology's hold
task. An unannotated class runs against either.

### What happens when no endpoint is configured

Every live test class carries `@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches
= ".+")`. With the variable unset — or set to an empty string, which is what the Gradle test task
passes through when it is unset in the ambient environment — **the class is disabled at the
container level and never attaches at all.**

**A green `:stagewright-junit:test` with no `TESTKIT_ENDPOINT` is not coverage.** It is a run in
which every live assertion was skipped. This is the framework's own instance of the failure it
most often warns about, and the reason it is called out here rather than left to be discovered:
a silent skip is indistinguishable from a pass in the one place where the distinction matters
most.

When the variable *is* set, the extension refuses to make the run quieter. Attach failures
propagate out of the before-all hook as container errors, not skips.

The face gate and the environment gate are separate on purpose. The face check deliberately
returns "enabled, the environment gate decides" when no endpoint is configured, because probing
for a face means attaching, and attaching with no hold running would turn "nothing is held" into
an initialisation error on every class in the suite.

One consequence follows from having two faces and one attach per JVM: **no single topology runs
every live class.** A run attached to a server endpoint disables the user-interface tests; a run
attached to a client endpoint disables the instrument tests. Coverage of both requires two holds.

## The permanent assertions

Each of these is pinned because it failed once, or because something downstream of it would fail
silently if it regressed. They run against a dedicated server with no player, which is why the
server face is the instrument face.

### The router

| Assertion | What it establishes |
|---|---|
| The version verb answers with the expected mod id and a non-negative uptime | The identity baseline the whole trust chain starts from: the router answers at all, and answers as itself. |
| An unknown method is refused with an error naming it | Loud-failure baseline. An unknown method must not resolve to an empty result. |
| A call missing a required key is refused, naming the key | Closed-schema required-key validation cannot be bypassed. |
| A call with a wrongly typed key is refused | Type validation exists. |
| A call with an unexpected key is refused, naming the key | The closed-schema rule: an unrecognised key is rejected rather than quietly discarded. |
| A client-only verb on a dedicated server is refused, naming the restriction | A verb that cannot work here must say so rather than doing nothing. |
| The in-process script engine and the external socket return the same answer for the same call | Schema and routing are single-sourced. Two call paths into one router must not diverge. |

The last three are one family: each is a way for a call to be accepted and then to have no
effect, which is the failure that looks exactly like success.

### The schema catalog

Schemas are not reachable over bare RPC and are not reachable from the in-process script engine
either, because this Rhino fork strips the global that would let a script resolve a class by
name. The only honest route to a verb's declared shape is the MCP `tools/list` endpoint, which
renders from the same typed schema the router validates against.

| Assertion | What it establishes |
|---|---|
| The settings verb's input schema is an object, is closed, and declares at least a floor number of properties | The settings registry is single-sourced from the configuration fields rather than hand-listed. The floor catches the reflective completion silently dropping out, which would leave a schema that is closed and nearly empty. |
| An unknown settings key is refused by the *validator*, not by the side gate | Validation runs ahead of the client-only check, uniformly, for every transport. On a dedicated server an unknown key must produce an unexpected-key error, not a client-only one. |
| The reset verb is refused on a server as client-only, naming itself | Client-only verbs fail loudly server-side. |
| The reset verb with an unexpected key is refused by the validator first | Paired registration worked: the hidden verb has a schema, the schema is closed, and validation ordering is uniform. |
| The run verb is absent from `tools/list` yet still rejects an unexpected key | Hidden means hidden from the catalog, not exempt from validation. |

Closed objects are worth one note, because the assertion looks lax and is not. Rendering a closed
object omits the `additionalProperties` key entirely rather than writing `false`. The convention
the validator implements is therefore that *only* an explicit `true` means open, and absent or
`false` both mean closed — so the assertion is "not `true`" rather than "equals `false`".

### The world

| Assertion | What it establishes |
|---|---|
| A block placed by a vanilla command is visible to the driver's read path at the same coordinate | Cross-source agreement. The driver must read the game's state, not a mirror of its own. |
| A bulk fill reports the number of blocks it placed, and a query reads back the same number | Write counts and read counts agree in both directions. |
| A snapshot, a modification and a discarding restore leave nothing behind | Snapshot and restore are clean round trips. |

### Observation

| Assertion | What it establishes |
|---|---|
| A damaged tool in a container reads back its damage, maximum damage and remaining durability | Tool wear is observable as a triple rather than living only as internal state. |
| Observing the player on a playerless server answers "absent" and does not throw | The documented semantics for the case the instrument face is in. |
| A summoned entity is returned by an entity query with a positive health value | Entity observation works at all, which everything asserting about creatures depends on. |

### Events and waiting

| Assertion | What it establishes |
|---|---|
| A dispatched command emits a result event carrying its success flag | The event path is reached. Deliberately not through a block-placing command: that one takes a fast path which returns before the emit, so asserting through it would pass while proving nothing. |
| The event cursor strictly increases across a dispatched command | Cursor monotonicity, which every incremental event reader depends on for correctness. |
| A tick wait reports the ticks it waited and really blocks for them | A wait must not return immediately and claim to have waited. |
| A conditional wait resolves on a dotted field reaching a value | The value-matching semantics a strategy layer builds on. |

### The suite trigger

One assertion, ordered last in the class run because it is destructive by design — it starts a
real scene suite, which summons and kills entities, moves the clock and may bring a player in.
It asserts that the trigger is accepted once and reports a scene count, and that a second call is
refused. Triggering the suite is the only way to prove the idempotency guard, so this cannot be
one of the read-only checks.

### The client surface

Client-face assertions are a different kind of thing from the server-face ones. The server face
tests the *router*; the client face tests what only exists when a real client is in a world —
screens, keyboard and mouse input, chat — and what only a client can observe.

| Assertion | What it establishes |
|---|---|
| The chat key opens a chat screen, typed text lands in the focused text field, and reset closes the screen and clears the chat history | Input reaches the game through the real keybind path, and reset genuinely clears rather than reporting that it tried. The history is seeded first so that the "cleared" assertion is not vacuous. |
| The inventory key opens an inventory screen and reset closes it | The same, for the screen a scene most often needs. |
| An open screen's tree exposes its container slots, and after reset reports no screen and no slots | The screen tree is a real readback rather than a static description. |
| Right-clicking a placed furnace through the instrument input verb opens a furnace screen, and reset closes it | The one input path that opens a container without involving movement, aiming or pathfinding. It also pins both outcomes of the command helper: a command that succeeds must not throw, and a command that dispatches but whose predicate matches nothing must. |

The keybind assertions poll rather than reading once, because a keybind is consumed on the
client's next tick and a single read races it.

### Self-tests that need no game

A separate set covers the pure-JVM logic: descriptor parsing and round-tripping, the required-key
failure, unknown-key tolerance, envelope encoding and decoding, and the polling helper's timing
and exception type. These carry no environment gate and always run, and two of them are gated to
run *only* when no endpoint is configured, because the "nothing is held" branch is unreachable
otherwise.

One of them spells the attach hint out as a literal string rather than referencing the constant,
so that changing the hint is a deliberate two-file edit rather than something a test follows
silently.

## The canaries

A test framework needs tests that are expected to fail, for a reason no ordinary test covers: a
suite of passing tests proves the code works *only if the suite can still detect a failure*. An
assertion library that stopped throwing, a timeout that stopped firing, a refusal helper that
stopped checking — each of those turns the entire suite green and tells nobody. The green would
be honest, complete, and worthless.

So each mechanism the suite depends on has a test that deliberately triggers it. **"Expected to
fail" is expressed as an ordinary passing test containing an assertion that the mechanism throws**
— not as a disabled test, not as an expected-exception annotation, and not through any verdict
machinery. The canary bites inside the assertion, so the class is green while the teeth work and
red the moment one stops biting.

Server face:

| Canary | Proves |
|---|---|
| A deliberately wrong assertion throws, and the caught message names the deliberate wrongness | Assertions still bite — and that it was *this* assertion that bit, not an unrelated one. |
| A condition that never holds raises a timeout, and that timeout is not an assertion error | Timeouts still fire, and the two kinds of failure remain distinguishable. |
| The refusal helper, pointed at a call that actually succeeds, itself throws | Without this, a router that accepted every malformed call would turn every refusal assertion in the suite into a silent pass. |

Client face carries its own pair — a wrong assertion made against a real screen read, and a
timeout — because the server-face canary class is gated to the server and would leave a
client-face run with no self-check at all.

These are distinct from the scene canaries described in the
[orchestration contract](orchestration-contract.md), which are declared in the results file and
judged by exit code. Same idea, different layer: those prove the *scene harness* can still catch
a failure, these prove the *out-of-process test harness* can.

## Two sockets: a dedicated server with a client

The production topology runs two game processes from one command, and they face opposite ways.
The dedicated server runs the scenes; the client joins it as a real remote player and is the only
place a user-interface assertion can be made.

**There is no dual-port descriptor.** Each JVM writes its own file, with the same name, into its
own run directory:

- The run task is the dedicated server. Its descriptor lands in the topology's game directory
  and records the topology name. Its face is `SERVER`.
- The companion is the client. Its descriptor lands beside the companion's results file — the
  one piece of the companion's run directory the plugin is told about — and records the topology
  name with `-client` appended. Its face is `CLIENT`.

A topology that declares no companion results file gets no second endpoint. That is a topology
whose client is not addressable, rather than a failure.

So **choosing a face means choosing which of the two files `TESTKIT_ENDPOINT` points at**, and
the derived WebSocket address always uses that file's own `rpcPort`. The `serverRpcPort` key is
parsed and reserved for a descriptor that carries both faces at once; nothing writes it today,
and a reader must treat its absence as normal rather than as a malformed file.

Two readers of this schema exist in this repository and they differ deliberately. The attach
module requires all eight keys and throws on a malformed file, because a test attaching to a
half-written descriptor should stop. The command-line runner reads four keys and returns nothing
rather than throwing, because it polls the file *while the game is still writing it*, so a torn
read means "keep waiting" rather than "give up".
