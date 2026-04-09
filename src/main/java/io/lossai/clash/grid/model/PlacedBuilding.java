package io.lossai.clash.grid.model;

import io.lossai.clash.model.BuildingType;

/**
 * Immutable record representing a building placed on the grid.
 * The anchor is the NW (min-x, min-z) corner of the building's footprint.
 */
public record PlacedBuilding(BuildingType type, GridCoord anchor, int level) {}
