package io.lossai.clash.grid.placement;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import io.lossai.clash.grid.model.GridCoord;
import io.lossai.clash.grid.occupancy.FootprintRegistry;
import io.lossai.clash.grid.occupancy.OccupancyMap;
import io.lossai.clash.model.BuildingType;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends fake block-change packets to a player to show a ghost preview of a
 * building footprint at the cursor position.
 *
 * <p>No real blocks are ever placed. All visual changes are client-side only,
 * sent via PacketEvents {@link WrapperPlayServerBlockChange}.
 *
 * <p>The class tracks the previous set of preview tiles so it can restore them
 * before sending new preview packets (Requirement 3.4).
 *
 * <p>Grid-to-world mapping: the village world is centred at (0, 0), so
 * {@code worldX = tile.x()} and {@code worldZ = tile.z()}.
 * Preview blocks are placed at {@code y = GROUND_Y + 1 = 65}.
 */
public class GhostPreview {

    /** Y-level of the flat grid surface. Preview blocks sit one above it. */
    private static final int GROUND_Y = 64;

    /** Tiles currently shown as ghost preview (may be empty). */
    private List<GridCoord> currentTiles = new ArrayList<>();

    /** World X coordinate of grid tile (0,0). Applied when converting grid→world. */
    private final int gridOriginX;

    /** World Z coordinate of grid tile (0,0). Applied when converting grid→world. */
    private final int gridOriginZ;

    public GhostPreview() {
        this(0, 0);
    }

    public GhostPreview(int gridOriginX, int gridOriginZ) {
        this.gridOriginX = gridOriginX;
        this.gridOriginZ = gridOriginZ;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Updates the ghost preview for the given building type at the cursor.
     *
     * <ol>
     *   <li>Restores the previous preview tiles to their actual block state.</li>
     *   <li>Computes the new footprint tiles at {@code cursor}.</li>
     *   <li>Determines validity via {@link OccupancyMap#canPlace}.</li>
     *   <li>Sends LIME_STAINED_GLASS (valid) or RED_STAINED_GLASS (invalid)
     *       packets for all new tiles.</li>
     * </ol>
     *
     * @param player   the player to send packets to
     * @param type     the building type being previewed
     * @param cursor   the anchor (NW corner) tile for the preview
     * @param map      the current occupancy map (read-only)
     * @param registry the footprint registry (read-only)
     */
    public void update(Player player, BuildingType type, GridCoord cursor,
                       OccupancyMap map, FootprintRegistry registry) {
        // Step 1: restore previous tiles to actual block state
        restoreTiles(player, currentTiles);

        // Step 2: compute new footprint tiles
        List<GridCoord> newTiles = FootprintRegistry.get(type).tiles(cursor);

        // Step 3: determine validity
        boolean valid = map.canPlace(type, cursor);

        // Step 4: send preview packets
        WrappedBlockState previewState = valid
                ? WrappedBlockState.getDefaultState(StateTypes.LIME_STAINED_GLASS)
                : WrappedBlockState.getDefaultState(StateTypes.RED_STAINED_GLASS);

        for (GridCoord tile : newTiles) {
            sendBlockChange(player, tile, previewState);
        }

        // Track the new set of preview tiles
        currentTiles = new ArrayList<>(newTiles);
    }

    /**
     * Clears the ghost preview by restoring all current preview tiles to their
     * actual block state, then clears the tracked tile set.
     *
     * @param player   the player to send restore packets to
     * @param map      unused (kept for API symmetry with future use)
     * @param registry unused (kept for API symmetry with future use)
     */
    public void clear(Player player, OccupancyMap map, FootprintRegistry registry) {
        restoreTiles(player, currentTiles);
        currentTiles = new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sends a block-change packet to restore each tile to the actual block
     * present in the player's world at that position.
     */
    private void restoreTiles(Player player, List<GridCoord> tiles) {
        if (tiles.isEmpty()) {
            return;
        }
        World world = player.getWorld();
        for (GridCoord tile : tiles) {
            int worldX = tile.x() + gridOriginX;
            int worldZ = tile.z() + gridOriginZ;
            int worldY = GROUND_Y + 1;

            org.bukkit.block.Block block = world.getBlockAt(worldX, worldY, worldZ);
            String blockKey = block.getType().getKey().toString();
            WrappedBlockState actualState = WrappedBlockState.getByString(blockKey);
            if (actualState == null) {
                actualState = WrappedBlockState.getDefaultState(StateTypes.AIR);
            }
            sendBlockChange(player, tile, actualState);
        }
    }

    private void sendBlockChange(Player player, GridCoord tile, WrappedBlockState state) {
        Vector3i pos = new Vector3i(tile.x() + gridOriginX, GROUND_Y + 1, tile.z() + gridOriginZ);
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(pos, state.getGlobalId());
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }
}
