package io.lossai.clash.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum TroopType {
    BARBARIAN,
    ARCHER,
    GIANT;

    public String displayName() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public static Optional<TroopType> fromInput(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalized = input.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return Arrays.stream(values()).filter(value -> value.name().equals(normalized)).findFirst();
    }
}