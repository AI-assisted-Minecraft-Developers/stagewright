package net.magicterra.stagewright.contract;

/**
 * The ground a scene's arena stands on.
 *
 * <p>A world has exactly one chunk generator, so "superflat or normal" cannot be a per-scene
 * property of the run's world — but it can be a per-scene choice of WHICH world. StageWright ships
 * a datapack registering two dimensions of its own, and a scene that asks for one gets its arena
 * built there instead of in the run's overworld. One server, one boot, both terrains available.
 *
 * <p>The two shipped dimensions are deliberately independent of the topology's {@code level-type}:
 * a scene that needs real hills must get them whether the run's own world is flat or not, and a
 * scene that needs a predictable plain must not start failing because someone changed the run's
 * world type underneath it.
 *
 * <p>{@link #RUN_WORLD} is the default and is not a dimension choice at all — it is the run's
 * overworld, at the grid's fixed {@code y=200}, which is where every scene ran before this existed.
 * The other two put the arena ON the ground: the harness resolves the arena origin from the surface
 * heightmap once the chunks are loaded, so {@code ctx.setBlock(0, 0, 0, ...)} lands at the player's
 * feet rather than 200 blocks above them.
 */
public enum Terrain {

    /** The run's own overworld at the grid altitude, untouched by worldgen. The default: an empty
     *  volume in the sky, where a scene builds exactly the terrain it asserts about and nothing
     *  else can be mistaken for it. */
    RUN_WORLD(null, false),

    /** A superflat plain — bedrock, dirt, grass — in StageWright's own dimension. For scenes that
     *  need solid predictable ground under them (walking, placing, mob spawning) without caring
     *  what it looks like. */
    SUPERFLAT("stagewright:superflat", true),

    /**
     * A normally-generated overworld: hills, caves, water, trees, ores, biomes. For scenes whose
     * subject IS the terrain — pathfinding over real ground, mining, surface navigation.
     *
     * <p>The world seed is fixed, so a given arena is the same landscape every run — but which
     * landscape is worldgen's answer, not a choice. Arenas march along a line 512 blocks apart, and
     * some of them are open ocean, some are a mountainside, some are a swamp. A scene that needs a
     * particular kind of ground must either assert its way out (skip when the ground is wrong) or
     * build what it needs on top of what it got; it must NOT assume dry flat land, because the slot
     * it lands on moves whenever the suite grows. Pin it with
     * {@link Scene#withOriginSlot(int)} if the landscape has to stay put.
     */
    GENERATED("stagewright:generated", true);

    private final String dimension;
    private final boolean onSurface;

    Terrain(String dimension, boolean onSurface) {
        this.dimension = dimension;
        this.onSurface = onSurface;
    }

    /** The dimension id this terrain lives in, or {@code null} for {@link #RUN_WORLD}. */
    public String dimension() { return dimension; }

    /** Whether the arena origin is resolved from the surface heightmap rather than pinned to the
     *  grid altitude. False only for {@link #RUN_WORLD}, whose whole point is the empty sky. */
    public boolean onSurface() { return onSurface; }

    /** Parse the name a scene file writes ({@code 'generated'}), case-insensitively. Unknown names
     *  throw with the full list — a typo that silently fell back to the default would build the
     *  scene an arena in the sky and then fail its terrain assertions for no visible reason. */
    public static Terrain parse(String s) {
        for (Terrain t : values()) {
            if (t.name().equalsIgnoreCase(s)) return t;
        }
        throw new IllegalArgumentException("unknown terrain '" + s + "' — expected one of "
                + java.util.Arrays.toString(values()));
    }
}
