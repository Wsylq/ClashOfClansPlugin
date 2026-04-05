package io.lossai.clash.service;

import io.lossai.clash.model.BuildingType;

import java.util.EnumMap;
import java.util.Map;

public final class BalanceBook {

    public enum Currency {
        GOLD,
        ELIXIR,
        GEMS
    }

    public record BuildInfo(Currency currency, long cost, int buildSeconds) {
    }

    public record UpgradeInfo(Currency currency, long cost, int seconds, int nextLevel) {
    }

    private BalanceBook() {
    }

    public static int maxAtTownHall(BuildingType type, int townHallLevel) {
        int clamped = Math.max(0, Math.min(2, townHallLevel));
        return switch (type) {
            case BUILDER_HUT -> clamped == 0 ? 1 : (clamped == 1 ? 2 : 3);
            case GOLD_MINE -> clamped == 0 ? 1 : (clamped == 1 ? 2 : 3);
            case ELIXIR_COLLECTOR -> clamped <= 1 ? 1 : 2;
            case BARRACKS -> clamped >= 1 ? 1 : 0;
            case ARMY_CAMP -> clamped >= 1 ? 1 : 0;
            case CANNON -> clamped >= 1 ? 1 : 0;
            case ARCHER_TOWER -> clamped >= 1 ? 1 : 0;
        };
    }

    public static BuildInfo buildInfo(BuildingType type, int existingCount, int townHallLevel) {
        if (type == BuildingType.BUILDER_HUT) {
            int nextHutNumber = existingCount + 1;
            long gems = builderHutGemCost(nextHutNumber);
            return new BuildInfo(Currency.GEMS, gems, 10);
        }

        if (type == BuildingType.ELIXIR_COLLECTOR && townHallLevel <= 1) {
            // User-requested TH1 data: 150 gold and 10 seconds for the collector.
            return new BuildInfo(Currency.GOLD, 150L, 10);
        }

        if (type == BuildingType.GOLD_MINE && townHallLevel <= 1) {
            return new BuildInfo(Currency.ELIXIR, 150L, 10);
        }

        Map<BuildingType, BuildInfo> defaults = new EnumMap<>(BuildingType.class);
        defaults.put(BuildingType.BARRACKS, new BuildInfo(Currency.ELIXIR, 200L, 20));
        defaults.put(BuildingType.ARMY_CAMP, new BuildInfo(Currency.ELIXIR, 250L, 25));
        defaults.put(BuildingType.CANNON, new BuildInfo(Currency.GOLD, 250L, 20));
        defaults.put(BuildingType.ARCHER_TOWER, new BuildInfo(Currency.GOLD, 500L, 30));

        return defaults.getOrDefault(type, new BuildInfo(Currency.GOLD, 0L, 1));
    }

    public static UpgradeInfo upgradeInfo(BuildingType type, int currentLevel, int townHallLevel) {
        if (type == BuildingType.ELIXIR_COLLECTOR && currentLevel == 1 && townHallLevel >= 1) {
            return new UpgradeInfo(Currency.GOLD, 300L, 15, 2);
        }

        if (type == BuildingType.GOLD_MINE && currentLevel == 1 && townHallLevel >= 1) {
            return new UpgradeInfo(Currency.ELIXIR, 300L, 15, 2);
        }

        return null;
    }

    private static long builderHutGemCost(int builderHutNumber) {
        return switch (builderHutNumber) {
            case 1 -> 0;
            case 2 -> 250;
            case 3 -> 500;
            case 4 -> 1000;
            case 5, 6 -> 2000;
            default -> 2000;
        };
    }
}