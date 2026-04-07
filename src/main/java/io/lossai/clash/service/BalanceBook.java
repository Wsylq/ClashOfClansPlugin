package io.lossai.clash.service;

import io.lossai.clash.model.BuildingType;

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
        int th = Math.max(0, Math.min(2, townHallLevel));
        return switch (type) {
            case BUILDER_HUT -> th == 0 ? 1 : (th == 1 ? 2 : 3);
            case TOWNHALL -> 1;
            case GOLD_MINE -> th == 0 ? 1 : (th == 1 ? 2 : 3);
            case ELIXIR_COLLECTOR -> th <= 1 ? 1 : 2;
            case GOLD_STORAGE, ELIXIR_STORAGE -> th >= 1 ? 1 : 0;
            case BARRACKS, ARMY_CAMP, CANNON, ARCHER_TOWER, LABORATORY -> th >= 1 ? 1 : 0;
            case WALL -> th == 0 ? 8 : (th == 1 ? 20 : 40);
        };
    }

    public static BuildInfo buildInfo(BuildingType type, int existingCount, int townHallLevel) {
        if (type == BuildingType.BUILDER_HUT) {
            return new BuildInfo(Currency.GEMS, builderHutGemCost(existingCount + 1), 10);
        }
        if (type == BuildingType.ELIXIR_COLLECTOR && townHallLevel <= 1) {
            return new BuildInfo(Currency.GOLD, 150L, 10);
        }
        if (type == BuildingType.GOLD_MINE && townHallLevel <= 1) {
            return new BuildInfo(Currency.ELIXIR, 150L, 10);
        }
        return switch (type) {
            case GOLD_STORAGE -> new BuildInfo(Currency.GOLD, 300L, 15);
            case ELIXIR_STORAGE -> new BuildInfo(Currency.ELIXIR, 300L, 15);
            case BARRACKS -> new BuildInfo(Currency.ELIXIR, 200L, 20);
            case ARMY_CAMP -> new BuildInfo(Currency.ELIXIR, 250L, 25);
            case CANNON -> new BuildInfo(Currency.GOLD, 250L, 20);
            case ARCHER_TOWER -> new BuildInfo(Currency.GOLD, 500L, 30);
            case LABORATORY -> new BuildInfo(Currency.ELIXIR, 500L, 30);
            case WALL -> new BuildInfo(Currency.GOLD, 50L, 2);
            default -> new BuildInfo(Currency.GOLD, 100L, 10);
        };
    }

    public static UpgradeInfo upgradeInfo(BuildingType type, int currentLevel, int townHallLevel) {
        if (currentLevel <= 0) {
            return null;
        }
        if (type == BuildingType.WALL) {
            if (currentLevel >= wallMaxLevel(townHallLevel)) {
                return null;
            }
            return new UpgradeInfo(Currency.GOLD, wallUpgradeCost(currentLevel), 0, currentLevel + 1);
        }
        if (type == BuildingType.ELIXIR_COLLECTOR && currentLevel == 1 && townHallLevel >= 1) {
            return new UpgradeInfo(Currency.GOLD, 300L, 15, 2);
        }
        if (type == BuildingType.GOLD_MINE && currentLevel == 1 && townHallLevel >= 1) {
            return new UpgradeInfo(Currency.ELIXIR, 300L, 15, 2);
        }
        if (type == BuildingType.GOLD_STORAGE && currentLevel == 1 && townHallLevel >= 1) {
            return new UpgradeInfo(Currency.GOLD, 350L, 20, 2);
        }
        if (type == BuildingType.ELIXIR_STORAGE && currentLevel == 1 && townHallLevel >= 1) {
            return new UpgradeInfo(Currency.ELIXIR, 350L, 20, 2);
        }
        return null;
    }

    private static long builderHutGemCost(int number) {
        return switch (number) {
            case 1 -> 0;
            case 2 -> 250;
            case 3 -> 500;
            case 4 -> 1000;
            case 5, 6 -> 2000;
            default -> 2000;
        };
    }

    public static int wallMaxLevel(int townHallLevel) {
        return switch (Math.max(0, Math.min(2, townHallLevel))) {
            case 0 -> 2;
            case 1 -> 4;
            default -> 6;
        };
    }

    public static long wallUpgradeCost(int currentLevel) {
        return switch (Math.max(1, currentLevel)) {
            case 1 -> 1_000L;
            case 2 -> 5_000L;
            case 3 -> 10_000L;
            case 4 -> 20_000L;
            case 5 -> 50_000L;
            default -> 100_000L;
        };
    }
}