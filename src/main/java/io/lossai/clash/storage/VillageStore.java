package io.lossai.clash.storage;

import io.lossai.clash.model.VillageData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class VillageStore {

    private final JavaPlugin plugin;
    private final File file;

    public VillageStore(JavaPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Unable to create plugin data folder.");
        }
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public Map<UUID, VillageData> loadAll() {
        if (!file.exists()) {
            return Collections.emptyMap();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return Collections.emptyMap();
        }

        Map<UUID, VillageData> result = new LinkedHashMap<>();
        for (String uuidKey : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid UUID entry in players.yml: " + uuidKey);
                continue;
            }

            ConfigurationSection section = players.getConfigurationSection(uuidKey);
            Map<String, Object> serialized = section == null ? Collections.emptyMap() : section.getValues(false);
            if (section != null) {
                ConfigurationSection buildings = section.getConfigurationSection("buildings");
                if (buildings != null) {
                    serialized.put("buildings", buildings.getValues(false));
                }

                ConfigurationSection buildingLevels = section.getConfigurationSection("buildingLevels");
                if (buildingLevels != null) {
                    serialized.put("buildingLevels", buildingLevels.getValues(false));
                }

                ConfigurationSection troops = section.getConfigurationSection("troops");
                if (troops != null) {
                    serialized.put("troops", troops.getValues(false));
                }

                ConfigurationSection troopLevels = section.getConfigurationSection("troopLevels");
                if (troopLevels != null) {
                    serialized.put("troopLevels", troopLevels.getValues(false));
                }

                ConfigurationSection wallSegmentLevels = section.getConfigurationSection("wallSegmentLevels");
                if (wallSegmentLevels != null) {
                    serialized.put("wallSegmentLevels", wallSegmentLevels.getValues(false));
                }
            }
            result.put(uuid, VillageData.deserialize(uuid, serialized));
        }

        return result;
    }

    public void saveAll(Map<UUID, VillageData> villages) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, VillageData> entry : villages.entrySet()) {
            yaml.createSection("players." + entry.getKey(), entry.getValue().serialize());
        }

        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save village data.", ex);
        }
    }
}