package io.lossai.clash.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum BuildingType {
    BUILDER_HUT,
    GOLD_MINE,
    ELIXIR_COLLECTOR,
    BARRACKS,
    ARMY_CAMP,
    CANNON,
    ARCHER_TOWER;

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