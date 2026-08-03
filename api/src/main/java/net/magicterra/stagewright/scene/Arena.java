package net.magicterra.stagewright.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Builds a scene's arena from one text grid per Y layer, so the terrain a scene asserts about is
 * legible in the source rather than reconstructed from a run of {@code setBlock} calls.
 *
 * <pre>{@code
 * ctx.arena()
 *    .key('#', Blocks.STONE)
 *    .key('~', Blocks.WATER)
 *    .layer(0, """
 *        #######
 *        #~~~~~#
 *        #######
 *        """)
 *    .build();
 * }</pre>
 *
 * <p>Rows run along +Z, columns along +X, and the grid is centred on the scene origin, so the middle
 * character of the middle row sits at {@code rel(0, y, 0)}. {@link #at} pins the top-left corner
 * instead when a scene needs the origin off-centre.
 *
 * <p>Two characters are predefined and both are overridable: {@code '.'} places air, and a SPACE
 * leaves whatever is already there. Any other unmapped character is a hard error — a typo that
 * silently placed nothing would produce a scene asserting against terrain it never built.
 */
public final class Arena {

    private record Layer(int dy, String text) {}

    /** A block that must be placed, in origin-relative coordinates. */
    private record Cell(int dx, int dy, int dz, Block block) {}

    private static final char SKIP = ' ';

    private final SceneContext ctx;
    private final Map<Character, Block> keys = new HashMap<>();
    private final List<Layer> layers = new ArrayList<>();
    private Integer cornerX;
    private Integer cornerZ;

    Arena(SceneContext ctx) {
        this.ctx = ctx;
        keys.put('.', Blocks.AIR);
    }

    /** Map a character to a block. Re-mapping {@code '.'} or a space is allowed and deliberate. */
    public Arena key(char c, Block block) {
        keys.put(c, block);
        return this;
    }

    /** Add a layer at {@code dy}. Blank leading/trailing lines are ignored so a text block can be
     *  indented naturally; interior blank lines are NOT, because a blank row is a real row of skips. */
    public Arena layer(int dy, String text) {
        layers.add(new Layer(dy, text));
        return this;
    }

    /** Pin the grid's top-left character to this origin-relative column instead of centring. */
    public Arena at(int dx, int dz) {
        this.cornerX = dx;
        this.cornerZ = dz;
        return this;
    }

    /** Resolve every cell, validate, then place. Nothing is written until the whole grid parses, so
     *  a bad character cannot leave a half-built arena behind for the next assertion to trip over. */
    public void build() {
        List<Cell> cells = new ArrayList<>();
        for (Layer layer : layers) {
            List<String> rows = rowsOf(layer.text());
            int width = 0;
            for (String r : rows) width = Math.max(width, r.length());

            int x0 = cornerX != null ? cornerX : -(width / 2);
            int z0 = cornerZ != null ? cornerZ : -(rows.size() / 2);

            for (int row = 0; row < rows.size(); row++) {
                String line = rows.get(row);
                for (int col = 0; col < line.length(); col++) {
                    char c = line.charAt(col);
                    if (c == SKIP) continue;
                    Block block = keys.get(c);
                    if (block == null) {
                        throw new SceneFailure("arena layer dy=" + layer.dy() + " uses unmapped character '"
                                + c + "' at row " + row + ", column " + col
                                + " — add .key('" + c + "', <block>) or use a space to leave the block alone");
                    }
                    cells.add(new Cell(x0 + col, layer.dy(), z0 + row, block));
                }
            }
        }

        // Check the whole footprint before writing any of it. A cell outside the force-loaded window
        // writes into a chunk that may not be loaded: the write is silently dropped or the chunk is
        // loaded and immediately unloaded, and the scene then fails on an assertion about terrain
        // that never existed. That failure names the wrong culprit, so catch it here and name the
        // real one.
        for (Cell cell : cells) {
            if (ctx.outsideForcedChunks(cell.dx(), cell.dz())) {
                throw new SceneFailure("arena reaches rel(" + cell.dx() + ", " + cell.dy() + ", " + cell.dz()
                        + "), outside this scene's force-loaded window of chunkRadius="
                        + ctx.chunkRadius() + " — widen it with @SceneDef(chunkRadius = "
                        + (ctx.chunkRadius() + 1) + ") or move the arena in");
            }
        }

        for (Cell cell : cells) {
            ctx.setBlock(cell.dx(), cell.dy(), cell.dz(), cell.block());
        }
    }

    private static List<String> rowsOf(String text) {
        List<String> rows = new ArrayList<>(List.of(text.split("\n", -1)));
        while (!rows.isEmpty() && rows.get(0).isBlank()) rows.remove(0);
        while (!rows.isEmpty() && rows.get(rows.size() - 1).isBlank()) rows.remove(rows.size() - 1);
        return rows;
    }
}
