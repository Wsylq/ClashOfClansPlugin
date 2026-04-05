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
    private long pendingGold;
    private long pendingElixir;
    private boolean starterGenerated;
    private boolean obstaclesGenerated;
    private final EnumMap<BuildingType, Integer> buildings;
    private final EnumMap<BuildingType, Integer> buildingLevels;
    private final Map<Integer, Integer> wallSegmentLevels;
    private final EnumMap<TroopType, Integer> troops;
    private final EnumMap<TroopType, Integer> troopLevels;

    public VillageData(UUID playerId, String playerName, String worldName, int townHallLevel, long gold, long elixir,
                       long gems, long pendingGold, long pendingElixir, boolean starterGenerated,
                       boolean obstaclesGenerated, Map<BuildingType, Integer> buildings,
                       Map<BuildingType, Integer> buildingLevels, Map<TroopType, Integer> troops,
                       Map<TroopType, Integer> troopLevels) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.worldName = worldName;
        this.townHallLevel = Math.max(0, townHallLevel);
        this.gold = Math.max(0L, gold);
        this.elixir = Math.max(0L, elixir);
        this.gems = Math.max(0L, gems);
        this.pendingGold = Math.max(0L, pendingGold);
        this.pendingElixir = Math.max(0L, pendingElixir);
        this.starterGenerated = starterGenerated;
        this.obstaclesGenerated = obstaclesGenerated;
        this.buildings = new EnumMap<>(BuildingType.class);
        this.buildingLevels = new EnumMap<>(BuildingType.class);
        this.wallSegmentLevels = new HashMap<>();
        this.troops = new EnumMap<>(TroopType.class);
        this.troopLevels = new EnumMap<>(TroopType.class);
        if (buildings != null) {
            this.buildings.putAll(buildings);
        }
        if (buildingLevels != null) {
            this.buildingLevels.putAll(buildingLevels);
        }
        if (troops != null) {
            this.troops.putAll(troops);
        }
        if (troopLevels != null) {
            this.troopLevels.putAll(troopLevels);
        }

        for (Map.Entry<BuildingType, Integer> entry : this.buildings.entrySet()) {
            if (entry.getValue() > 0 && this.buildingLevels.getOrDefault(entry.getKey(), 0) <= 0) {
                this.buildingLevels.put(entry.getKey(), 1);
            }
        }
        for (TroopType type : TroopType.values()) {
            this.troopLevels.putIfAbsent(type, 1);
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

    public long getElixir() {
        return elixir;
    }

    public long getGems() {
        return gems;
    }

    public long getPendingGold() {
        return pendingGold;
    }

    public long getPendingElixir() {
        return pendingElixir;
    }

    public void addPendingGold(long amount, long cap) {
        if (amount <= 0) {
            return;
        }
        pendingGold = Math.min(Math.max(0L, cap), pendingGold + amount);
    }

    public void addPendingElixir(long amount, long cap) {
        if (amount <= 0) {
            return;
        }
        pendingElixir = Math.min(Math.max(0L, cap), pendingElixir + amount);
    }

    public long collectGold(long storageCap) {
        long space = Math.max(0L, storageCap - gold);
        long moved = Math.min(space, pendingGold);
        gold += moved;
        pendingGold -= moved;
        return moved;
    }

    public long collectElixir(long storageCap) {
        long space = Math.max(0L, storageCap - elixir);
        long moved = Math.min(space, pendingElixir);
        elixir += moved;
        pendingElixir -= moved;
        return moved;
    }

    public void addGems(long amount) {
        if (amount > 0) {
            gems += amount;
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

    public boolean setBuildingLevel(BuildingType type, int newLevel) {
        if (newLevel <= 0 || getBuildingCount(type) <= 0) {
            return false;
        }
        buildingLevels.put(type, newLevel);
        return true;
    }

    public long clampStoredResources(long goldCap, long elixirCap) {
        long oldGold = gold;
        long oldElixir = elixir;
        gold = Math.max(0L, Math.min(gold, Math.max(0L, goldCap)));
        elixir = Math.max(0L, Math.min(elixir, Math.max(0L, elixirCap)));
        return (oldGold - gold) + (oldElixir - elixir);
    }

    public void clampPendingResources(long pendingGoldCap, long pendingElixirCap) {
        pendingGold = Math.max(0L, Math.min(pendingGold, Math.max(0L, pendingGoldCap)));
        pendingElixir = Math.max(0L, Math.min(pendingElixir, Math.max(0L, pendingElixirCap)));
    }

    public int getWallSegmentLevel(int index) {
        return wallSegmentLevels.getOrDefault(index, 1);
    }

    public void setWallSegmentLevel(int index, int level) {
        if (index < 0) {
            return;
        }
        wallSegmentLevels.put(index, Math.max(1, level));
    }

    public int nextUpgradeableWallIndex() {
        int built = getBuildingCount(BuildingType.WALL);
        if (built <= 0) {
            return -1;
        }
        int selected = -1;
        int selectedLevel = Integer.MAX_VALUE;
        for (int i = 0; i < built; i++) {
            int level = getWallSegmentLevel(i);
            if (level < selectedLevel) {
                selectedLevel = level;
                selected = i;
            }
        }
        return selected;
    }

    public int getTroopCount(TroopType type) {
        return troops.getOrDefault(type, 0);
    }

    public void addTroops(TroopType type, int amount) {
        if (amount <= 0) {
            return;
        }
        troops.put(type, getTroopCount(type) + amount);
    }

    public boolean takeTroops(TroopType type, int amount) {
        int have = getTroopCount(type);
        if (amount <= 0 || have < amount) {
            return false;
        }
        troops.put(type, have - amount);
        return true;
    }

    public int getTroopLevel(TroopType type) {
        return troopLevels.getOrDefault(type, 1);
    }

    public void setTroopLevel(TroopType type, int level) {
        if (level > 0) {
            troopLevels.put(type, level);
        }
    }

    public Map<BuildingType, Integer> getBuildingsSnapshot() {
        return Collections.unmodifiableMap(buildings);
    }

    public Map<TroopType, Integer> getTroopsSnapshot() {
        return Collections.unmodifiableMap(troops);
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
                missing.add((entry.getValue() - have) + "x " + entry.getKey().displayName());
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

    public Map<String, Object> serialize() {
        Map<String, Object> data = new HashMap<>();
        data.put("playerName", playerName);
        data.put("worldName", worldName);
        data.put("townHallLevel", townHallLevel);
        data.put("gold", gold);
        data.put("elixir", elixir);
        data.put("gems", gems);
        data.put("pendingGold", pendingGold);
        data.put("pendingElixir", pendingElixir);
        data.put("starterGenerated", starterGenerated);
        data.put("obstaclesGenerated", obstaclesGenerated);

        Map<String, Integer> serializedBuildings = new HashMap<>();
        for (Map.Entry<BuildingType, Integer> entry : buildings.entrySet()) {
            serializedBuildings.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        data.put("buildings", serializedBuildings);

        Map<String, Integer> serializedBuildingLevels = new HashMap<>();
        for (Map.Entry<BuildingType, Integer> entry : buildingLevels.entrySet()) {
            serializedBuildingLevels.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        data.put("buildingLevels", serializedBuildingLevels);

        Map<String, Integer> serializedWallLevels = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : wallSegmentLevels.entrySet()) {
            serializedWallLevels.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        data.put("wallSegmentLevels", serializedWallLevels);

        Map<String, Integer> serializedTroops = new HashMap<>();
        for (Map.Entry<TroopType, Integer> entry : troops.entrySet()) {
            serializedTroops.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        data.put("troops", serializedTroops);

        Map<String, Integer> serializedTroopLevels = new HashMap<>();
        for (Map.Entry<TroopType, Integer> entry : troopLevels.entrySet()) {
            serializedTroopLevels.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        data.put("troopLevels", serializedTroopLevels);
        return data;
    }

    @SuppressWarnings("unchecked")
    public static VillageData deserialize(UUID playerId, Map<String, Object> rawData) {
        if (rawData == null) {
            return new VillageData(playerId, "unknown", null, 0, 0L, 0L, 0L, 0L, 0L,
                    false, false, new EnumMap<>(BuildingType.class), new EnumMap<>(BuildingType.class),
                    new EnumMap<>(TroopType.class), new EnumMap<>(TroopType.class));
        }

        String playerName = String.valueOf(rawData.getOrDefault("playerName", "unknown"));
        String worldName = (String) rawData.get("worldName");
        int townHallLevel = (rawData.get("townHallLevel") instanceof Number n) ? n.intValue() : 0;
        long gold = (rawData.get("gold") instanceof Number n) ? Math.max(0L, n.longValue()) : 0L;
        long elixir = (rawData.get("elixir") instanceof Number n) ? Math.max(0L, n.longValue()) : 0L;
        long gems = (rawData.get("gems") instanceof Number n) ? Math.max(0L, n.longValue()) : 0L;
        long pendingGold = (rawData.get("pendingGold") instanceof Number n) ? Math.max(0L, n.longValue()) : 0L;
        long pendingElixir = (rawData.get("pendingElixir") instanceof Number n) ? Math.max(0L, n.longValue()) : 0L;
        boolean starterGenerated = Boolean.TRUE.equals(rawData.get("starterGenerated"));
        boolean obstaclesGenerated = Boolean.TRUE.equals(rawData.get("obstaclesGenerated"));

        EnumMap<BuildingType, Integer> buildings = new EnumMap<>(BuildingType.class);
        EnumMap<BuildingType, Integer> buildingLevels = new EnumMap<>(BuildingType.class);
        EnumMap<TroopType, Integer> troops = new EnumMap<>(TroopType.class);
        EnumMap<TroopType, Integer> troopLevels = new EnumMap<>(TroopType.class);

        Object buildingObj = rawData.get("buildings");
        if (buildingObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                BuildingType.fromInput(String.valueOf(entry.getKey())).ifPresent(type -> {
                    if (entry.getValue() instanceof Number n) {
                        buildings.put(type, Math.max(0, n.intValue()));
                    }
                });
            }
        }

        Object levelObj = rawData.get("buildingLevels");
        if (levelObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                BuildingType.fromInput(String.valueOf(entry.getKey())).ifPresent(type -> {
                    if (entry.getValue() instanceof Number n && n.intValue() > 0) {
                        buildingLevels.put(type, n.intValue());
                    }
                });
            }
        }

        Object troopObj = rawData.get("troops");
        if (troopObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                TroopType.fromInput(String.valueOf(entry.getKey())).ifPresent(type -> {
                    if (entry.getValue() instanceof Number n) {
                        troops.put(type, Math.max(0, n.intValue()));
                    }
                });
            }
        }

        Object troopLevelObj = rawData.get("troopLevels");
        if (troopLevelObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                TroopType.fromInput(String.valueOf(entry.getKey())).ifPresent(type -> {
                    if (entry.getValue() instanceof Number n && n.intValue() > 0) {
                        troopLevels.put(type, n.intValue());
                    }
                });
            }
        }

        Map<Integer, Integer> wallLevels = new HashMap<>();
        Object wallLevelObj = rawData.get("wallSegmentLevels");
        if (wallLevelObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                try {
                    int slotIndex = Integer.parseInt(String.valueOf(entry.getKey()));
                    if (slotIndex >= 0 && entry.getValue() instanceof Number n && n.intValue() > 0) {
                        wallLevels.put(slotIndex, n.intValue());
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed keys in persisted data.
                }
            }
        }

        VillageData data = new VillageData(playerId, playerName, worldName, townHallLevel, gold, elixir, gems, pendingGold,
                pendingElixir, starterGenerated, obstaclesGenerated, buildings, buildingLevels, troops, troopLevels);
        for (Map.Entry<Integer, Integer> entry : wallLevels.entrySet()) {
            data.setWallSegmentLevel(entry.getKey(), entry.getValue());
        }
        return data;
    }
}