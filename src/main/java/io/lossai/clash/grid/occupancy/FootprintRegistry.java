package io.lossai.clash.grid.occupancy;

import io.lossai.clash.grid.model.Footprint;
import io.lossai.clash.model.BuildingType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Central registry mapping each {@link BuildingType} to its {@link Footprint}.
 * This is the only location where BuildingType-to-Footprint mappings are defined.
 * Non-instantiable utility class.
 */
public final class FootprintRegistry {

    private static final Map<BuildingType, Footprint> REGISTRY = new EnumMap<>(BuildingType.class);

    static {
        REGISTRY.put(BuildingType.TOWNHALL,         new Footprint(4, 4));
        REGISTRY.put(BuildingType.CANNON,           new Footprint(3, 3));
        REGISTRY.put(BuildingType.ARCHER_TOWER,     new Footprint(3, 3));
        REGISTRY.put(BuildingType.BARRACKS,         new Footprint(3, 3));
        REGISTRY.put(BuildingType.ARMY_CAMP,        new Footprint(4, 4));
        REGISTRY.put(BuildingType.GOLD_MINE,        new Footprint(3, 3));
        REGISTRY.put(BuildingType.ELIXIR_COLLECTOR, new Footprint(3, 3));
        REGISTRY.put(BuildingType.GOLD_STORAGE,     new Footprint(3, 3));
        REGISTRY.put(BuildingType.ELIXIR_STORAGE,   new Footprint(3, 3));
        REGISTRY.put(BuildingType.LABORATORY,       new Footprint(3, 3));
        REGISTRY.put(BuildingType.BUILDER_HUT,      new Footprint(2, 2));
        REGISTRY.put(BuildingType.WALL,             new Footprint(1, 1));
    }

    private FootprintRegistry() {
        throw new UnsupportedOperationException("FootprintRegistry is a utility class");
    }

    /**
     * Returns the {@link Footprint} registered for the given {@link BuildingType}.
     *
     * @param type the building type to look up
     * @return the registered footprint
     * @throws IllegalArgumentException if no footprint is registered for the given type
     */
    public static Footprint get(BuildingType type) {
        Footprint footprint = REGISTRY.get(type);
        if (footprint == null) {
            throw new IllegalArgumentException(
                    "No footprint registered for BuildingType: " + type.name());
        }
        return footprint;
    }
}
