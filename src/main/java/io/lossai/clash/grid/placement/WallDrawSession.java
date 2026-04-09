package io.lossai.clash.grid.placement;

import io.lossai.clash.grid.model.GridCoord;
import io.lossai.clash.grid.model.PlacedBuilding;
import io.lossai.clash.grid.occupancy.OccupancyMap;
import io.lossai.clash.model.BuildingType;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages a click-and-drag wall chain placement session.
 *
 * <p>Usage:
 * <ol>
 *   <li>Construct with a start tile — the first wall segment is added immediately.</li>
 *   <li>Call {@link #extend(GridCoord, OccupancyMap)} on each cursor move.</li>
 *   <li>Call {@link #commit(OccupancyMap)} to write all chain tiles, or
 *       {@link #cancel()} to discard them.</li>
 * </ol>
 *
 * <p>The chain is capped at {@value #MAX_CHAIN} segments (Requirement 7.6).
 */
public class WallDrawSession {

    /** Maximum number of wall segments in a single drag chain. */
    public static final int MAX_CHAIN = 44;

    /** The tile where the drag started. */
    public final GridCoord start;

    /**
     * Ordered list of tiles in the current chain, including {@link #start}.
     * Tiles are appended by {@link #extend} and cleared by {@link #commit}/{@link #cancel}.
     */
    public final List<GridCoord> chain = new ArrayList<>();

    /**
     * Creates a new session starting at {@code start}.
     * The start tile is added to the chain unconditionally (it was already validated
     * by the caller before the session was created).
     */
    public WallDrawSession(GridCoord start) {
        this.start = start;
        chain.add(start);
    }

    // -------------------------------------------------------------------------
    // Core operations
    // -------------------------------------------------------------------------

    /**
     * Extends the chain from the last tile in the chain (or {@link #start}) toward
     * {@code newCursor} using the Bresenham line algorithm.
     *
     * <p>For each tile produced by {@link #bresenham}, the tile is appended to the
     * chain only if ALL of the following hold:
     * <ol>
     *   <li>The tile is not already present in the chain.</li>
     *   <li>The tile is not occupied in {@code map}.</li>
     *   <li>The tile is within the {@code [0, 43]} grid boundary.</li>
     *   <li>The chain has not yet reached {@value #MAX_CHAIN} segments.</li>
     * </ol>
     *
     * @param newCursor the tile the cursor has moved to
     * @param map       the current occupancy map (read-only during this call)
     */
    public void extend(GridCoord newCursor, OccupancyMap map) {
        if (chain.size() >= MAX_CHAIN) {
            return;
        }

        GridCoord from = chain.get(chain.size() - 1);
        List<GridCoord> line = bresenham(from, newCursor);

        // Skip the first point — it is already the last tile in the chain.
        for (int i = 1; i < line.size(); i++) {
            if (chain.size() >= MAX_CHAIN) {
                break;
            }
            GridCoord tile = line.get(i);
            if (!inBounds(tile)) {
                continue;
            }
            if (chain.contains(tile)) {
                continue;
            }
            if (map.snapshot().containsKey(tile)) {
                continue;
            }
            chain.add(tile);
        }
    }

    /**
     * Writes all chain tiles to {@code map} as {@code WALL} {@link PlacedBuilding}s
     * at level 1, then clears the chain.
     *
     * <p>Each tile is placed only if {@link OccupancyMap#canPlace} still returns
     * {@code true} at commit time (the map may have changed since the last extend).
     */
    public void commit(OccupancyMap map) {
        for (GridCoord tile : chain) {
            if (map.canPlace(BuildingType.WALL, tile)) {
                map.place(new PlacedBuilding(BuildingType.WALL, tile, 1));
            }
        }
        chain.clear();
    }

    /**
     * Discards the current chain without writing anything to the map.
     */
    public void cancel() {
        chain.clear();
    }

    // -------------------------------------------------------------------------
    // Pure static algorithm
    // -------------------------------------------------------------------------

    /**
     * Integer Bresenham line algorithm.
     *
     * <p>Returns an ordered list of {@link GridCoord}s from {@code a} to {@code b}
     * (both endpoints inclusive). All consecutive pairs in the result are
     * 8-connected (differ by at most 1 on each axis).
     *
     * <p>This method is pure and has no external dependencies.
     *
     * @param a start coordinate
     * @param b end coordinate
     * @return list of tiles from a to b, inclusive
     */
    public static List<GridCoord> bresenham(GridCoord a, GridCoord b) {
        List<GridCoord> result = new ArrayList<>();

        int x0 = a.x(), z0 = a.z();
        int x1 = b.x(), z1 = b.z();

        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        while (true) {
            result.add(new GridCoord(x0, z0));
            if (x0 == x1 && z0 == z1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z0 += sz;
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean inBounds(GridCoord coord) {
        return coord.x() >= 0 && coord.x() < OccupancyMap.GRID_SIZE
                && coord.z() >= 0 && coord.z() < OccupancyMap.GRID_SIZE;
    }
}
