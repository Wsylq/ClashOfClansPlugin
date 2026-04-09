package io.lossai.clash.grid.model;

/**
 * Immutable value type for a single grid tile.
 * Origin (0,0) is the NW corner of the 44×44 grid.
 * Valid range: x ∈ [0, 43], z ∈ [0, 43].
 */
public record GridCoord(int x, int z) {}
