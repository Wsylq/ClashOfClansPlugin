package io.lossai.clash.service;

import io.lossai.clash.model.TroopType;

public final class TroopBook {

    public record TroopInfo(long trainElixir, int trainSeconds, int housingSpace, int barracksLevelRequired) {
    }

    private TroopBook() {
    }

    public static TroopInfo info(TroopType type) {
        return switch (type) {
            case BARBARIAN -> new TroopInfo(25L, 20, 1, 1);
            case ARCHER -> new TroopInfo(50L, 25, 1, 1);
            case GIANT -> new TroopInfo(500L, 120, 5, 1);
        };
    }
}