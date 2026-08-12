// Loaded into the scope of every scene file under config/stagewright/scenes/ before that file
// runs. Defines the registration surface and the sugar that makes a scene readable; everything
// else a scene body touches is the Java SceneContext, reached directly through Rhino.
//
// Kept deliberately small. Each addition here is a thing a pack author has to learn that a mod
// author does not, and the point of the JS surface is that it is the SAME framework — same
// harness, same canaries, same results file — not a second one with its own vocabulary.

var __scenes = [];

// scene(name, budgetTicks, body[, options])
//
// budgetTicks is how long the scene may take in SERVER TICKS, not milliseconds — a scene is not a
// test thread, it runs inline on the tick loop. Anything that needs time to happen goes through
// s.await(...), never a loop.
//
// options.terrain picks the ground the arena is built on:
//   'run_world'  (default) empty sky at the grid altitude — you build every block you assert about
//   'superflat'  a bedrock/dirt/grass plain, arena at the surface
//   'generated'  a normally-generated overworld — hills, caves, water, trees — arena at the surface
// The last two are separate dimensions StageWright ships, so they are the same whatever world type
// the run itself was launched with, and 'generated' uses a fixed seed so the landscape under a
// given scene is the same every run.
//
// options.clock picks the time of day. Every scene runs in a world the run holds still — see the
// pin line the run logs and writes into its results header — and the default is night:
//   'midnight'   (default) frozen at 18000; nothing sun-sensitive can ignite
//   'noon'       frozen at 6000, for a scene whose subject IS daylight
//   'running'    starts at midnight with the daylight cycle actually advancing, for a scene whose
//                subject is the passage of time
//
// options.dimension runs the scene in a dimension one of the PACK's mods registers, e.g.
// 'twilightforest:twilight_forest'. It is an alternative to terrain, not an addition: the terrains
// above are dimensions StageWright ships, so asking for both asks for the arena to be in two places
// and is rejected when the scene is built. A dimension the pack does not have makes the scene
// record a skip naming it — the mod is not installed, which is not the pack's bug to fail on.
function scene(name, budgetTicks, body, options) {
    if (typeof name !== 'string' || !name) throw new Error('scene() needs a name');
    if (typeof budgetTicks !== 'number') throw new Error("scene '" + name + "' needs a tick budget");
    if (typeof body !== 'function') throw new Error("scene '" + name + "' needs a body function");
    var opts = options || {};
    if (opts.dimension && opts.terrain) {
        throw new Error("scene '" + name + "' asks for both terrain '" + opts.terrain + "' and"
            + " dimension '" + opts.dimension + "' — a terrain IS a dimension StageWright ships,"
            + " so the two are alternatives");
    }
    __scenes.push({
        name: name, budgetTicks: budgetTicks | 0, body: body, optional: false,
        terrain: opts.terrain ? String(opts.terrain) : 'run_world',
        clock: opts.clock ? String(opts.clock) : 'midnight',
        dimension: opts.dimension ? String(opts.dimension) : null
    });
}

// A scene that may fail without failing the run. For a defect you have accepted and want watched
// rather than fixed — it still runs, still reports, and still shows up in the results.
scene.optional = function (name, budgetTicks, body, options) {
    scene(name, budgetTicks, body, options);
    __scenes[__scenes.length - 1].optional = true;
};

// console.log inside a scene body goes to the server log, prefixed with the scene's name. There is
// no captured-log envelope here the way there is for an ad-hoc eval: a scene reports through its
// assertions and its recorded values, and a log line is for the human reading the run afterwards.
var console = {
    log:   function (m) { __bridge.log(String(m)); },
    error: function (m) { __bridge.log('[err] ' + String(m)); }
};

// Blocks are named, never constructed: block('minecraft:stone'). Unknown ids fail loudly at the
// point of use rather than resolving to air, which is what a silent lookup would do and is
// indistinguishable from "the scene placed nothing".
function block(id) {
    return __bridge.block(String(id));
}

// Reaching a mod's own capabilities — nothing to define here, which is the point.
//
// `s` is the Java SceneContext, so anything a compiled scene can call, a scene file can call:
//
//   s.mods().loaded('mekanism')         what is installed, and at which version
//   s.mods().version('create')          '' when absent
//   s.mods().require('ae2')             or record a skip that names the mod
//   s.mods().count()                    the one number that says which pack these results are about
//
//   s.capabilities()                    what this runtime offers, e.g. ['curios', 'ftbquests']
//   s.hasCapability('curios')           branch instead of skipping
//   s.capability('curios').worn()       the facet itself; absent => this scene records a skip
//
// Three ways a capability gets here, in ascending order of what it costs you:
//
//  1. It is already there. StageWright ships descriptors for the big ecosystems and, on NeoForge,
//     the three block capabilities every tech mod is built on:
//
//       s.capability('itemhandler').insert(0, 0, 0, 0, 'minecraft:diamond', 3)
//       s.capability('energy').fill(0, 0, 0)
//       s.capability('fluids').fill(0, 0, 0, 'minecraft:water', 1000)
//       s.capability('mekanism').probe().callStatic('...')
//
//  2. You declare one, in a .json file beside these scenes — no build tool, same folder:
//
//       { "name": "mymod:rituals", "mods": ["mymod"], "probe": "com.mymod.RitualApi" }
//
//     Then s.capability('mymod:rituals').probe() — and the class name lives in one file rather
//     than in every scene. Declaring a name that StageWright ships REPLACES ours, which is how a
//     pack whose fork moved a class fixes it without waiting for a release.
//
//  3. A mod ships a typed adapter (CapabilityProvider) in its own jar, and it shows up here with no
//     change to StageWright and none to this file.
//
// When there is no adapter, no descriptor, and you want none:
//
//   s.hasClass('com.simibubi.create.Create')
//   s.probe('com.some.mod.Api').callStatic('lookup', s.player()).asInt()
//
// probe() is refused for net.minecraft.* — and would not have worked: those names are remapped in a
// production Fabric jar, so getX is method_10263 there. A mod's own class names are not remapped,
// which is what makes reflecting into THEM sound. Passing a Minecraft object as an argument is fine;
// calling a method on one is the thing that breaks.
