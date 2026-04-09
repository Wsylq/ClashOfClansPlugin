package io.lossai.clash.grid.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Footprint dimensions (in tiles) for a building type.
 * The anchor is always the NW (min-x, min-z) corner.
 */
public record Footprint(int width, int depth) {

    /**
     * Returns all GridCoords covered when {@code anchor} is the NW corner.
     * Expands to {@code width * depth} distinct GridCoords.
     */
    public List<GridCoord> tiles(GridCoord anchor) {
        List<GridCoord> result = new ArrayList<>(width * depth);
        for (int dz = 0; dz < depth; dz++) {
            for (int dx = 0; dx < width; dx++) {
                result.add(new GridCoord(anchor.x() + dx, anchor.z() + dz));
            }
        }
        return result;
    }
}
