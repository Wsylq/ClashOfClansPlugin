package io.lossai.clash.grid.renderer;

import io.lossai.clash.grid.model.GridCoord;
import io.lossai.clash.grid.model.PlacedBuilding;
import io.lossai.clash.grid.occupancy.FootprintRegistry;
import io.lossai.clash.grid.occupancy.OccupancyMap;
import io.lossai.clash.model.BuildingType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Collection;
import java.util.Map;

/**
 * Materialises an {@link OccupancyMap} into real Minecraft blocks.
 *
 * <p>Blocks are placed at {@code y = GROUND_Y + 1} (one block above the flat grid surface).
 * The grid origin (world X, Z of tile 0,0) is supplied at construction time.</p>
 */
public class VillageRenderer {

    /** Y-level of the flat grid surface. Building blocks are placed at GROUND_Y + 1. */
    public static final int GROUND_Y = 64;

    private final int originX;
    private final int originZ;

    /**
     * @param originX world X coordinate of grid tile (0, 0)
     * @param originZ world Z coordinate of grid tile (0, 0)
     */
    public VillageRenderer(int originX, int originZ) {
        this.originX = originX;
        this.originZ = originZ;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Full regeneration: clears the entire 44×44 area at GROUND_Y+1, then places
     * a block for every occupied tile in {@code map}.
     */
    public void renderAll(World world, OccupancyMap map, FootprintRegistry registry) {
        int y = GROUND_Y + 1;

        // Clear the entire 44×44 area first
        for (int x = 0; x < OccupancyMap.GRID_SIZE; x++) {
            for (int z = 0; z < OccupancyMap.GRID_SIZE; z++) {
                world.getBlockAt(originX + x, y, originZ + z).setType(Material.AIR);
            }
        }

        // Place blocks for every occupied tile
        for (Map.Entry<GridCoord, PlacedBuilding> entry : map.snapshot().entrySet()) {
            GridCoord tile = entry.getKey();
            BuildingType type = entry.getValue().type();
            world.getBlockAt(originX + tile.x(), y, originZ + tile.z()).setType(blockFor(type));
        }
    }

    /**
     * Incremental update: for each tile in {@code tiles}, sets the block to the
     * appropriate material if occupied, or AIR if not.
     */
    public void renderTiles(World world, Collection<GridCoord> tiles, OccupancyMap map, FootprintRegistry registry) {
        int y = GROUND_Y + 1;
        Map<GridCoord, PlacedBuilding> snapshot = map.snapshot();

        for (GridCoord tile : tiles) {
            PlacedBuilding building = snapshot.get(tile);
            Material material = (building != null) ? blockFor(building.type()) : Material.AIR;
            world.getBlockAt(originX + tile.x(), y, originZ + tile.z()).setType(material);
        }
    }

    /**
     * Pure helper: converts a grid tile to a world {@link Location}.
     * The returned Location has a {@code null} world reference so it can be used
     * in tests without a live Bukkit server.
     *
     * @param tile    the grid coordinate to convert
     * @param originX world X of grid tile (0, 0)
     * @param originZ world Z of grid tile (0, 0)
     * @return a Location at (originX + tile.x(), GROUND_Y + 1, originZ + tile.z())
     */
    public static Location tileToWorld(GridCoord tile, int originX, int originZ) {
        return new Location(null, originX + tile.x(), GROUND_Y + 1, originZ + tile.z());
    }

    // -------------------------------------------------------------------------
    // Internal mapping
    // -------------------------------------------------------------------------

    private static Material blockFor(BuildingType type) {
        return switch (type) {
            case TOWNHALL         -> Material.GOLD_BLOCK;
            case CANNON           -> Material.IRON_BLOCK;
            case ARCHER_TOWER     -> Material.STONE_BRICKS;
            case MORTAR           -> Material.GRAY_CONCRETE;
            case BARRACKS         -> Material.BRICKS;
            case ARMY_CAMP        -> Material.MOSSY_COBBLESTONE;
            case GOLD_MINE        -> Material.YELLOW_CONCRETE;
            case ELIXIR_COLLECTOR -> Material.PURPLE_CONCRETE;
            case GOLD_STORAGE     -> Material.GOLD_ORE;
            case ELIXIR_STORAGE   -> Material.AMETHYST_BLOCK;
            case LABORATORY       -> Material.CYAN_CONCRETE;
            case BUILDER_HUT      -> Material.OAK_PLANKS;
            case WALL             -> Material.COBBLESTONE_WALL;
        };
    }
}
