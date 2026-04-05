package io.lossai.clash.service;

import io.lossai.clash.model.BuildingType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public final class RequirementBook {

    private static final Map<Integer, Map<BuildingType, Integer>> REQUIREMENTS;
    private static final Map<Integer, Map<BuildingType, Integer>> LEVEL_REQUIREMENTS;

    static {
        Map<Integer, Map<BuildingType, Integer>> editable = new HashMap<>();

        // Requirements to upgrade from Town Hall 0 to Town Hall 1
        Map<BuildingType, Integer> th0 = new EnumMap<>(BuildingType.class);
        th0.put(BuildingType.BUILDER_HUT, 1);
        th0.put(BuildingType.GOLD_MINE, 1);
        th0.put(BuildingType.ELIXIR_COLLECTOR, 1);
        editable.put(0, Collections.unmodifiableMap(th0));

        // Requirements to upgrade from Town Hall 1 to Town Hall 2
        Map<BuildingType, Integer> th1 = new EnumMap<>(BuildingType.class);
        th1.put(BuildingType.BUILDER_HUT, 2);
        th1.put(BuildingType.GOLD_MINE, 2);
        th1.put(BuildingType.ELIXIR_COLLECTOR, 1);
        th1.put(BuildingType.BARRACKS, 1);
        th1.put(BuildingType.ARMY_CAMP, 1);
        th1.put(BuildingType.CANNON, 1);
        th1.put(BuildingType.ARCHER_TOWER, 1);
        th1.put(BuildingType.GOLD_STORAGE, 1);
        th1.put(BuildingType.ELIXIR_STORAGE, 1);
        th1.put(BuildingType.LABORATORY, 1);
        editable.put(1, Collections.unmodifiableMap(th1));

        REQUIREMENTS = Collections.unmodifiableMap(editable);

        Map<Integer, Map<BuildingType, Integer>> levelEditable = new HashMap<>();
        Map<BuildingType, Integer> th1Levels = new EnumMap<>(BuildingType.class);

        // Based on TH1 behavior requested by user: single elixir collector should reach level 2 before TH2.
        th1Levels.put(BuildingType.ELIXIR_COLLECTOR, 2);
        levelEditable.put(1, Collections.unmodifiableMap(th1Levels));
        LEVEL_REQUIREMENTS = Collections.unmodifiableMap(levelEditable);
    }

    private RequirementBook() {
    }

    public static Map<BuildingType, Integer> requirementsForCurrentTownHall(int currentTownHallLevel) {
        return REQUIREMENTS.getOrDefault(currentTownHallLevel, Collections.emptyMap());
    }

    public static Map<BuildingType, Integer> levelRequirementsForCurrentTownHall(int currentTownHallLevel) {
        return LEVEL_REQUIREMENTS.getOrDefault(currentTownHallLevel, Collections.emptyMap());
    }
}