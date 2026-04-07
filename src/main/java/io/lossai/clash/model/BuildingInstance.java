package io.lossai.clash.model;

import org.bukkit.Location;
import java.util.UUID;

public record BuildingInstance(
    UUID id,
    BuildingType type,
    Location origin,
    int[] blockOffsets
) {}
