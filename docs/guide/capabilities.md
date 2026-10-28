# Capabilities

The test API covers the vanilla server. Almost everything a modpack is *about* lives outside it —
accessory slots, a quest graph, a chemical tank, a stress network, a storage grid — and without a
seam, every one of those would cost a StageWright release and a version bump for everybody.

The seam is the capability system (`net.magicterra.stagewright.capability`). This guide covers it
from both ends: the test author asking for a capability, and the mod or pack author teaching the
framework a new one.

## Starting from what is installed

Before anything conditional, `Mods` answers what is actually in this run.

```java
Mods.loaded("mekanism")      // is it here
Mods.any("ae2", "appeng")    // a fork, a rename, a compat shim — one call holds them all
Mods.all("create", "curios")
Mods.version("create")       // "" when absent
Mods.ids()                   // every loaded id, sorted
Mods.count()                 // the one number that says which pack these results are about
Mods.require("ae2")          // the version, or the test is aborted naming the mod
```

This is the ground floor because it is the one thing a registry census cannot answer: a mod that adds
no content has no namespace to look for. For a whole test or class, `@Requires` does the same check
before the test starts.

The list is pushed in by each loader's entry point at startup rather than read out of the loader,
because the API compiles against neither loader. **A list that never arrived is a failure, not
an empty list.** An empty one would make every mod look uninstalled, so a pack's tests would all
abort citing a mod that is plainly there — the framework's own wiring failing while wearing an absent
mod's clothes.

## Asking for a capability

```java
game.capability("mymod:rituals")                    // the facet, or the test is aborted
game.capability("mymod:rituals", Rituals.class)     // the same, type-checked
game.capabilities().has("curios")                   // branch instead of aborting
game.capabilities().available()                     // what this game offers, sorted
game.capabilities().registered()                    // what LOADED, available or not, sorted
```

```javascript
t.capability('mymod:rituals').consecrate(t.player(), {x: 10, y: 64, z: -3});
```

**Absent aborts the test (`TestAbortedException`), it does not fail it.** A mod that is not
installed is not the pack's defect, and it must not read as a pass either. The message names what was
missing *and* what the game did offer instead, so the results explain themselves a week later.

The facet is the same object for the rest of the test, so whatever it changed is undone once, when the
test ends. The typed form earns its keep across a version bump: an adapter whose facet type changed
would otherwise surface as a cast failure inside the test, which names the test rather than the
adapter.

A script test gets the facet as an object of its public methods. Arguments are plain values,
converted to what each method takes: numbers, `{x, y, z}` for a position, and a `t.player()` or
`t.bot()` handle where a method takes a `Body`. Results come back as plain values; a method that
returns the facet itself (a chained call) returns the same object.

### `available()` and `registered()` are different questions

They are easy to conflate, and the difference is the one worth asserting on. A runtime where
discovery found nothing and a runtime where it found two providers whose mods are absent answer
identically to every `has` call. So a suite that only asks that stays green through a dropped
service file, a shadow merge that ate a `META-INF/services` entry, or a jar that shipped without it.

Registered-but-unavailable is a working framework reporting an absent mod. Nothing registered is a
broken framework reporting the same thing.

## Four ways a capability gets there

In ascending order of what it costs you.

### 1. It is already there

A pack configures nothing and still gets the big ecosystems detected, StageWright's own facets
(below), plus — on NeoForge — the three platform capabilities every tech mod is built on.

```java
BlockPos machine = plot.at(1, 0, 1);
game.capability("itemhandler", BlockInventory.class).insert(machine, 0, "minecraft:diamond", 3);
game.capability("energy", Energy.class).fill(machine);
game.capability("fluids", Fluids.class).fill(machine, "minecraft:water", 1000);
game.capability("mekanism", Detected.class).probe().callStatic("…");
```

Positions are world positions in the overworld, as `Plot.at` answers them.

`itemhandler`, `energy` and `fluids` are written against the **capability**, not against a mod. A
hundred tech mods share no API; what they share is the interface a hopper or a pipe talks to them
through. So one facet covers all of them, including mods written after the facet was. It also cannot
over-reach: a block that implements nothing reports that it implements nothing rather than guessing.

The interfaces live in the API and the implementations in the NeoForge module, which is both a
technical necessity — the API compiles against neither loader — and a standing invitation: implement
the same interfaces over Fabric's storage API and every existing test works unchanged. Until then, a
test asking for one of the three on Fabric is aborted with a reason that says so.

Vanilla containers implement `itemhandler` too, which is what lets the mechanism be proved with no
mod installed at all — a chest answers with twenty-seven slots. Only the *coverage* needs a modpack
to prove.

Two details of that implementation are worth knowing because they look like defects when you hit
them:

**A write tries `null` and then all six faces, stopping at the first that accepts.** Block
capabilities are queried with a direction, and `null` means "unspecified". Most mods answer `null`
with an internal view carrying the machine's real numbers, so *reading* is always correct, while
*insertion* is governed by the machine's side configuration and only permitted on faces configured as
inputs. Without the sweep, a test asserting "this machine can be charged" silently becomes a test
about that machine's side configuration, and nothing in the symptom points at the face. Handlers are
de-duplicated by reference, so a block exposing one handler on six faces is asked once.

**The shadow jar must merge service files, not replace them.** Adapters are registered from two
modules, and the default for a duplicate path is last-writer-wins. Without an explicit merge the jar
ships one of the two files and every capability in the other reports itself absent — which is
indistinguishable from an uninstalled mod.

The shipped descriptors are a deliberately short list in `data/stagewright/capabilities.json` inside
the framework jar. Each is gated on a **class**, not on a mod id: the class existing is exactly the
condition under which its probe works, so the mod id's spelling never enters the question. An entry
earns its place by naming a stable API root a test would otherwise hardcode. It is not an ecosystem
census; `Mods` already answers "is X here".

### 2. Declare one in a JSON file

For a mod nobody wrote an adapter for, when you have a `config/` folder and no build tool. Drop a
file in `config/stagewright/capabilities/` in the game directory (or in the directory the game's JVM names with
`-Dstagewright.scenesDir=<dir>`):

```json
// config/stagewright/capabilities/rituals.json
{
  "name": "mymod:rituals",
  "mods": ["mymod"],
  "probe": "com.mymod.RitualApi",
  "absent": "MyMod is not in this pack"
}
```

```javascript
t.capability('mymod:rituals').probe().callStatic('lookup');
```

Conditions may be any combination of `mods` (all must be loaded), `anyMods` (at least one),
`classes` (all must be in this JVM), `items` and `blocks` (all must be registered). A single entry
may be written bare rather than as a one-element array, and one file may hold a single object or an
array of them.

**At least one condition is required.** A descriptor stating none would report itself available in
every runtime, including ones without the thing it names, where tests gated on it would run and fail
for a reason that has nothing to do with the pack.

The registry conditions are the ones to reach for when a mod is present but its content is not: a mod
loaded with its feature switched off in config registers no items, and no amount of mod-list checking
notices.

Anything malformed fails loudly at load, naming the file. A descriptor that silently degraded to
"unavailable" would be indistinguishable from an absent mod, and a typo would then read as an
ordinary abort forever.

**Declaring a name StageWright ships replaces ours.** That is how a pack whose fork of a mod moved a
class corrects the framework without waiting for a release.

### 3. Ship a typed adapter

A mod that wants its own machinery testable ships one in its own jar:

```java
public final class RitualsCapability implements CapabilityProvider {
    @Override public String name()                   { return "mymod:rituals"; }
    @Override public boolean availableIn(Game game)  { return true; }
    @Override public Object facet(Game game)         { return new Rituals(game); }
    @Override public String absentReason()           { return "MyMod is not installed here"; }
}
```

Registered through `META-INF/services/net.magicterra.stagewright.capability.CapabilityProvider`. Any
test can then reach it, including a pack's script test — no new verb, nothing to import.

**Write it typed, against your mod's real classes.** An adapter for Curios imports Curios' classes
and therefore cannot even load in a runtime without Curios — which is correct, since it could not
have worked there either, and discovery is built for it. Each provider is instantiated inside its own
guard, so one that cannot load is recorded as absent *with the reason*, and its neighbours are
unaffected. An adapter that never loaded cannot say which name it would have answered to, so the
reason is attached to every abort for a capability nothing offers: a test asking for yours reads the
linkage error rather than a bare "nothing in this run offers it".

That is also why the Curios and FTB Quests adapters StageWright ships are reflective and yours should
not be. They ride in StageWright's own jar, which is present in every run whether or not the mods they adapt
are, so their classes have to load in a runtime that has neither.

Four contract points that nothing can check for you:

**`name()`.** Bare names are reserved for adapters StageWright ships; anything else is
`<modid>:<what>`. Two providers claiming one name is rejected loudly, naming both classes: one would
silently win, and a test would then test something it did not mean to.

**`availableIn()` is called more than once, possibly from several test threads at once**, so it must
be cheap, thread-safe and free of side effects. A provider whose class loaded at all can usually return
`true` — the class loading *is* the probe. Return false for the finer conditions: the mod is present
but its data has not loaded, the feature is off in config.

**The facet runs on test threads.** Every operation that reads or changes game state must hop to the
server thread itself, with `game.onServer(server -> …)` or, for something done to a player,
`body.onServer(player -> …)`. A method acting on a player takes the `Body` to act on.

**The facet's own surface must be safe to call from a script.** Minecraft's method names differ
between a development runtime and a production Fabric jar. So a facet's parameters and return values
must be StageWright types (`Body`), positions, string ids, or primitives — never an `ItemStack` the
test is then expected to call a method on.

`facet(game)` is resolved once per test and reused. A facet that changes shared state undoes it with
`game.afterTest(server -> …)`, called from the test's thread (capture the old state in `onServer`
first, then register the undo); it runs on the server thread when the test ends, passed or not.

### 4. Reflect into the mod directly

A pack author with no adapter still needs a way in. `Probe.of(className)` is guarded reflection into
a **mod's** API:

```java
Probe api = Probe.of("com.some.mod.Api");          // absent class => test aborted
int level = game.onServer(server -> api.callStatic("lookup").asInt());
```

`Probe.hasClass(className)` is the branching form. From a script test, a descriptor's
`probe()` is the way in.

**`net.minecraft.*` is refused, and this is correctness rather than caution.** A production Fabric jar
carries intermediary names, so a by-name call there resolves a completely different identifier — the
same line would pass on NeoForge and throw on Fabric. A mod's own class names are never remapped,
which is exactly what makes the technique sound for them. Passing a Minecraft object as an *argument*
is fine; that is holding, not calling.

Everything non-primitive comes back wrapped in another `Probe`, so a test never ends up holding a
bare Minecraft object it might then call a method on. Leaving the wrapper is explicit: `asString`,
`asInt`, `asDouble`, `asBoolean`, `asList`, `isNull`, `unwrapped`. `on`, `call`, `callStatic`,
`field` and `staticField` chain.

A miss names the alternatives on the class, because reflection that only says "no such method" sends
you to a decompiler.

The same reflection appearing three times in a pack is a `CapabilityProvider` waiting to be written.
This is a hatch, not an API.

## The facets StageWright ships

All registered through the same seam a third party uses. Each method that acts on a player takes the
`Body` (a `Bot` or the client's `Player`) as its first argument, and whatever a test changes on that
body is put back when the test ends.

| Capability | Facet | Reads | Drives |
|---|---|---|---|
| `items` | `Items` | `count`, `has`, `held`, `distinct`, `foodLevel`, `saturation`, `effects`, `hasEffect`, `effectDetails`, `worn` | `give`, `clear`, `hold`, `eat`, `hunger` |
| `menus` | `Menus` | `hasMenuAt`, `isOpen`, `title`, `slotCount`, `item`, `count`, `contents` | `openAt`, `openInventory`, `put`, `clear`, `click`, `shiftClick`, `close` |
| `equip` | `Equip` | `inArmor`, `attribute`, `curiosPresent` | `armor` |
| `curios` | `Equip` (the same object) | `curioSlots`, `inCurio`, `curioSlotCount` | `curio` |
| `ftbquests` | `Quests` | `loaded`, `chapters`, `questCount`, `allQuests`, `questsInChapter`, `dependenciesOf`, `isComplete`, `canStart` | `complete` (not undone) |

```java
Items items = game.capability("items", Items.class);
items.hunger(bot, 10, 0).give(bot, "minecraft:bread").eat(bot, "minecraft:bread");
assertTrue(items.foodLevel(bot) > 10);
```

`curios` and `ftbquests` are available only when their mods are. For FTB Quests, availability means
more than "the mod is here": a server with no loaded quest file answers every graph question with
nothing, which is not a fact about the pack's quests — so availability requires a loaded book, and the
absent message says which of the two is missing.

### Opening a menu

`openAt` throws when a block has no menu, which makes it useless for asking *whether* a block is
reachable — a pack author's first question. `hasMenuAt` exists for that, and the answer is neither
obvious nor uniform.

A block's own menu provider is authoritative: it is the route a right-click takes, and it is where the
conditional cases live. It very often answers nothing, and that is not the same as "this block has no
menu" — the default is to answer nothing, and vanilla blocks get an implementation only by extending a
base class that hands the question to the block entity. A mod with its own block hierarchy and its own
GUI-opening packet never overrides it, while its block entity *is* a menu provider. So the facet asks
the block, then falls through to the block entity, which is the object the mod's own open call passes
anyway. Nothing that answered is overridden, so no conditional provider is bypassed.

That is as far as a generic opener can go. A mod whose machines are not menu providers anywhere — and
at least one large tech mod is exactly that — cannot be opened this way at all, while its capabilities
stay perfectly readable. The two seams are independent.

Opening builds the menu **server-side** — the mod's own factory, its real slots, its real click
handler — and deliberately sends no open packet to the client. That packet asks the client to
reconstruct the menu from a data buffer whose shape only the opener knows, and a generic caller cannot
know it. Sending it empty is not a harmless no-op: one mod's generator read a position out of it, got
null, and took the client's whole packet listener down mid-run.

What is lost is the client screen, which this facet does not assert about. What is kept is everything
it does. The body is not moved to the block; put it there first (`body.moveTo`) if the menu checks
distance.

## Where to go next

[Getting started](getting-started.md#testing-a-modpack-with-no-build-tool) covers where a pack's tests go;
descriptor files go in its `config/stagewright/capabilities/`.
