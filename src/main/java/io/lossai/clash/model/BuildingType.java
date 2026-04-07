package io.lossai.clash.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum BuildingType {
    BUILDER_HUT,
    TOWNHALL,
    GOLD_MINE,
    ELIXIR_COLLECTOR,
    GOLD_STORAGE,
    ELIXIR_STORAGE,
    BARRACKS,
    ARMY_CAMP,
    LABORATORY,
    CANNON,
    ARCHER_TOWER,
    WALL;

    public String displayName() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public static Optional<BuildingType> fromInput(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        String normalized = input.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return Arrays.stream(values())
                .filter(value -> value.name().equals(normalized))
                .findFirst();
    }
}