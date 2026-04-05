package io.lossai.clash.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class VillageData {

    private final UUID playerId;
    private String playerName;
    private String worldName;
    private int townHallLevel;
    private long gold;
    private long elixir;
    private long gems;
    private boolean starterGenerated;
    private boolean obstaclesGenerated;
    private final EnumMap<BuildingType, Integer> buildings;
    private final EnumMap<BuildingType, Integer> buildingLevels;

    public VillageData(UUID playerId, String playerName, String worldName, int townHallLevel, long gold, long elixir,
                       long gems, boolean starterGenerated, boolean obstaclesGenerated, Map<BuildingType, Integer> buildings,
                       Map<BuildingType, Integer> buildingLevels) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.worldName = worldName;
        this.townHallLevel = Math.max(0, townHallLevel);
        this.gold = Math.max(0L, gold);
        this.elixir = Math.max(0L, elixir);
        this.gems = Math.max(0L, gems);
        this.starterGenerated = starterGenerated;
        this.obstaclesGenerated = obstaclesGenerated;
        this.buildings = new EnumMap<>(BuildingType.class);
        this.buildingLevels = new EnumMap<>(BuildingType.class);
        if (buildings != null) {
            this.buildings.putAll(buildings);
        }
        if (buildingLevels != null) {
            this.buildingLevels.putAll(buildingLevels);
        }

        // Keep data coherent if a building exists but no level was saved yet.
        for (Map.Entry<BuildingType, Integer> entry : this.buildings.entrySet()) {
            if (entry.getValue() > 0 && this.buildingLevels.getOrDefault(entry.getKey(), 0) <= 0) {
                this.buildingLevels.put(entry.getKey(), 1);
            }
        }
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public int getTownHallLevel() {
        return townHallLevel;
    }

    public void setTownHallLevel(int townHallLevel) {
        this.townHallLevel = Math.max(0, townHallLevel);
    }

    public long getGold() {
        return gold;
    }

    public void addGold(long amount) {
        if (amount > 0) {
            this.gold += amount;
        }
    }

    public long getElixir() {
        return elixir;
    }

    public void addElixir(long amount) {
        if (amount > 0) {
            this.elixir += amount;
        }
    }

    public long getGems() {
        return gems;
    }

    public void addGems(long amount) {
        if (amount > 0) {
            this.gems += amount;
        }
    }

    public boolean takeGems(long amount) {
        if (amount <= 0 || gems < amount) {
            return false;
        }

        gems -= amount;
        return true;
    }

    public boolean takeGold(long amount) {
        if (amount <= 0 || gold < amount) {
            return false;
        }

        gold -= amount;
        return true;
    }

    public boolean takeElixir(long amount) {
        if (amount <= 0 || elixir < amount) {
            return false;
        }

        elixir -= amount;
        return true;
    }

    public boolean isStarterGenerated() {
        return starterGenerated;
    }

    public void setStarterGenerated(boolean starterGenerated) {
        this.starterGenerated = starterGenerated;
    }

    public boolean isObstaclesGenerated() {
        return obstaclesGenerated;
    }

    public void setObstaclesGenerated(boolean obstaclesGenerated) {
        this.obstaclesGenerated = obstaclesGenerated;
    }

    public int getBuildingCount(BuildingType type) {
        return buildings.getOrDefault(type, 0);
    }

    public void addBuilding(BuildingType type, int amount) {
        if (amount <= 0) {
            return;
        }

        int current = buildings.getOrDefault(type, 0);
        buildings.put(type, current + amount);
        if (current + amount > 0 && buildingLevels.getOrDefault(type, 0) <= 0) {
            buildingLevels.put(type, 1);
        }
    }

    public int getBuildingLevel(BuildingType type) {
        return buildingLevels.getOrDefault(type, 0);
    }

    public boolean hasBuildingLevel(BuildingType type, int level) {
        return getBuildingCount(type) > 0 && getBuildingLevel(type) >= level;
    }

    public boolean setBuildingLevel(BuildingType type, int newLevel) {
        if (newLevel <= 0 || getBuildingCount(type) <= 0) {
            return false;
        }

        buildingLevels.put(type, newLevel);
        return true;
    }

    public boolean meetsRequirements(Map<BuildingType, Integer> requirements) {
        for (Map.Entry<BuildingType, Integer> entry : requirements.entrySet()) {
            if (getBuildingCount(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }

        return true;
    }

    public List<String> missingRequirements(Map<BuildingType, Integer> requirements) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<BuildingType, Integer> entry : requirements.entrySet()) {
            int have = getBuildingCount(entry.getKey());
            if (have < entry.getValue()) {
                int left = entry.getValue() - have;
                missing.add(left + "x " + entry.getKey().displayName());
            }
        }

        return missing;
    }

    public List<String> missingLevelRequirements(Map<BuildingType, Integer> levelRequirements) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<BuildingType, Integer> entry : levelRequirements.entrySet()) {
            int have = getBuildingLevel(entry.getKey());
            if (getBuildingCount(entry.getKey()) <= 0 || have < entry.getValue()) {
                missing.add(entry.getKey().displayName() + " lvl " + entry.getValue());
            }
        }
        return missing;
    }

    public boolean meetsLevelRequirements(Map<BuildingType, Integer> levelRequirements) {
        return missingLevelRequirements(levelRequirements).isEmpty();
    }

    public Map<BuildingType, Integer> getBuildingsSnapshot() {
        return Collections.unmodifiableMap(buildings);
    }

    public Map<String, Object> serialize() {
        Map<String, Object> data = new HashMap<>();
        data.put("playerName", playerName);
        data.put("worldName", worldName);
        data.put("townHallLevel", townHallLevel);

        Map<String, Integer> serializedBuildings = new HashMap<>();
        for (Map.Entry<BuildingType, Integer> entry : buildings.entrySet()) {
            serializedBuildings.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }

        data.put("buildings", serializedBuildings);
        Map<String, Integer> serializedLevels = new HashMap<>();
        for (Map.Entry<BuildingType, Integer> entry : buildingLevels.entrySet()) {
            serializedLevels.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        data.put("buildingLevels", serializedLevels);
        data.put("gold", gold);
        data.put("elixir", elixir);
        data.put("gems", gems);
        data.put("starterGenerated", starterGenerated);
        data.put("obstaclesGenerated", obstaclesGenerated);
        return data;
    }

    @SuppressWarnings("unchecked")
    public static VillageData deserialize(UUID playerId, Map<String, Object> rawData) {
        if (rawData == null) {
            return new VillageData(playerId, "unknown", null, 0, 0L, 0L, 0L, false, false,
                    new EnumMap<>(BuildingType.class), new EnumMap<>(BuildingType.class));
        }

        String playerName = String.valueOf(rawData.getOrDefault("playerName", "unknown"));
        String worldName = (String) rawData.get("worldName");
        int townHallLevel = (rawData.get("townHallLevel") instanceof Number number) ? number.intValue() : 0;
        long gold = (rawData.get("gold") instanceof Number number) ? Math.max(0L, number.longValue()) : 0L;
        long elixir = (rawData.get("elixir") instanceof Number number) ? Math.max(0L, number.longValue()) : 0L;
        long gems = (rawData.get("gems") instanceof Number number) ? Math.max(0L, number.longValue()) : 0L;
        boolean starterGenerated = Boolean.TRUE.equals(rawData.get("starterGenerated"));
        boolean obstaclesGenerated = Boolean.TRUE.equals(rawData.get("obstaclesGenerated"));

        EnumMap<BuildingType, Integer> buildings = new EnumMap<>(BuildingType.class);
        EnumMap<BuildingType, Integer> buildingLevels = new EnumMap<>(BuildingType.class);
        Object buildingObj = rawData.get("buildings");
        if (buildingObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                BuildingType.fromInput(String.valueOf(entry.getKey())).ifPresent(type -> {
                    int amount = 0;
                    Object value = entry.getValue();
                    if (value instanceof Number number) {
                        amount = Math.max(0, number.intValue());
                    }

                    buildings.put(type, amount);
                });
            }
        }

        Object levelObj = rawData.get("buildingLevels");
        if (levelObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                BuildingType.fromInput(String.valueOf(entry.getKey())).ifPresent(type -> {
                    int level = 0;
                    Object value = entry.getValue();
                    if (value instanceof Number number) {
                        level = Math.max(0, number.intValue());
                    }
                    if (level > 0) {
                        buildingLevels.put(type, level);
                    }
                });
            }
        }

        return new VillageData(playerId, playerName, worldName, townHallLevel, gold, elixir, gems,
                starterGenerated, obstaclesGenerated, buildings, buildingLevels);
    }
}