package io.lossai.clash.grid.occupancy;

import io.lossai.clash.grid.model.GridCoord;
import io.lossai.clash.grid.model.PlacedBuilding;
import io.lossai.clash.model.BuildingType;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the village layout.
 * Every tile of a multi-tile building has its own {@link GridCoord} entry
 * pointing to the same {@link PlacedBuilding} instance.
 *
 * <p>All mutating operations ({@link #place}, {@link #remove}, {@link #move}) are atomic:
 * they build the full set of changes first, then apply them in one pass.
 * If any pre-condition fails the map is left unmodified.</p>
 */
public class OccupancyMap {

    /** Side length of the square village grid (tiles). Valid coords: [0, GRID_SIZE - 1]. */
    public static final int GRID_SIZE = 64;

    private final HashMap<GridCoord, PlacedBuilding> map = new HashMap<>();

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} iff every tile in the building's footprint is:
     * <ol>
     *   <li>within {@code [0, GRID_SIZE - 1]} on both axes, and</li>
     *   <li>not already present in this map.</li>
     * </ol>
     */
    public boolean canPlace(BuildingType type, GridCoord anchor) {
        List<GridCoord> tiles = FootprintRegistry.get(type).tiles(anchor);
        for (GridCoord tile : tiles) {
            if (!inBounds(tile) || map.containsKey(tile)) {
                return false;
            }
        }
        return true;
    }

    /** @deprecated Use {@link #canPlace(BuildingType, GridCoord)} instead. */
    @Deprecated
    public boolean canPlace(BuildingType type, GridCoord anchor, FootprintRegistry registry) {
        return canPlace(type, anchor);
    }

    // -------------------------------------------------------------------------
    // Mutations
    // -------------------------------------------------------------------------

    /**
     * Atomically adds all footprint tiles for {@code building}.
     *
     * @throws IllegalStateException if {@link #canPlace} returns {@code false}
     */
    public void place(PlacedBuilding building) {
        if (!canPlace(building.type(), building.anchor())) {
            throw new IllegalStateException(
                    "Cannot place " + building.type() + " at " + building.anchor()
                    + ": tiles are occupied or out of bounds");
        }
        List<GridCoord> tiles = FootprintRegistry.get(building.type()).tiles(building.anchor());
        for (GridCoord tile : tiles) {
            map.put(tile, building);
        }
    }

    /** @deprecated Use {@link #place(PlacedBuilding)} instead. */
    @Deprecated
    public void place(PlacedBuilding building, FootprintRegistry registry) {
        place(building);
    }

    /**
     * Atomically removes all tiles belonging to the building found at {@code anyTile}.
     * Does nothing if {@code anyTile} is not occupied.
     */
    public void remove(GridCoord anyTile) {
        PlacedBuilding building = map.get(anyTile);
        if (building == null) {
            return;
        }
        // Remove every entry that points to this exact building instance.
        map.entrySet().removeIf(e -> e.getValue() == building);
    }

    /**
     * Atomically moves the building at {@code from} to {@code newAnchor}.
     *
     * <p>The old tiles are treated as free when validating the new position,
     * so a building can be placed adjacent to (or overlapping) its original footprint.</p>
     *
     * @throws IllegalStateException if {@code from} is unoccupied, or if the new
     *                               position is invalid after freeing the old tiles
     */
    public void move(GridCoord from, GridCoord newAnchor) {
        PlacedBuilding building = map.get(from);
        if (building == null) {
            throw new IllegalStateException("No building at " + from);
        }

        // Temporarily remove old entries so canPlace sees them as free.
        map.entrySet().removeIf(e -> e.getValue() == building);

        PlacedBuilding moved = new PlacedBuilding(building.type(), newAnchor, building.level());

        if (!canPlace(moved.type(), moved.anchor())) {
            // Restore original entries before throwing.
            List<GridCoord> originalTiles = FootprintRegistry.get(building.type()).tiles(building.anchor());
            for (GridCoord tile : originalTiles) {
                map.put(tile, building);
            }
            throw new IllegalStateException(
                    "Cannot move " + building.type() + " to " + newAnchor
                    + ": tiles are occupied or out of bounds");
        }

        List<GridCoord> newTiles = FootprintRegistry.get(moved.type()).tiles(moved.anchor());
        for (GridCoord tile : newTiles) {
            map.put(tile, moved);
        }
    }

    /** @deprecated Use {@link #move(GridCoord, GridCoord)} instead. */
    @Deprecated
    public void move(GridCoord from, GridCoord newAnchor, FootprintRegistry registry) {
        move(from, newAnchor);
    }

    /**
     * Returns a defensive, unmodifiable snapshot of the current map.
     * The returned map will not reflect subsequent mutations.
     */
    public Map<GridCoord, PlacedBuilding> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(map));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean inBounds(GridCoord coord) {
        return coord.x() >= 0 && coord.x() < GRID_SIZE
                && coord.z() >= 0 && coord.z() < GRID_SIZE;
    }
}
