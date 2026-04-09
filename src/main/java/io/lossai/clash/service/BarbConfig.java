package io.lossai.clash.service;

import io.lossai.clash.model.BuildingType;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Holds barbarian-system configuration values read from config.yml.
 * Missing or invalid keys fall back to documented defaults with a logged warning.
 */
public final class BarbConfig {

    // Documented defaults
    private static final int DEFAULT_DAMAGE = 10;
    private static final int DEFAULT_INTERVAL = 20;

    private static final Map<BuildingType, Integer> DEFAULT_HP;

    static {
        DEFAULT_HP = new EnumMap<>(BuildingType.class);
        DEFAULT_HP.put(BuildingType.TOWNHALL, 300);
        DEFAULT_HP.put(BuildingType.CANNON, 150);
        DEFAULT_HP.put(BuildingType.ARCHER_TOWER, 120);
        DEFAULT_HP.put(BuildingType.MORTAR, 400);
        DEFAULT_HP.put(BuildingType.GOLD_MINE, 80);
        DEFAULT_HP.put(BuildingType.ELIXIR_COLLECTOR, 80);
        DEFAULT_HP.put(BuildingType.WALL, 50);
    }

    /** Config key → BuildingType mapping for the barbarian-system.building-hp section. */
    private static final Map<String, BuildingType> KEY_TO_TYPE = Map.of(
            "townhall", BuildingType.TOWNHALL,
            "cannon", BuildingType.CANNON,
            "archer_tower", BuildingType.ARCHER_TOWER,
            "mortar", BuildingType.MORTAR,
            "gold_mine", BuildingType.GOLD_MINE,
            "elixir_collector", BuildingType.ELIXIR_COLLECTOR,
            "wall", BuildingType.WALL
    );

    private final Map<BuildingType, Integer> hpMap;
    private final int damage;
    private final int attackIntervalTicks;

    public BarbConfig(FileConfiguration config, Logger logger) {
        this.hpMap = new EnumMap<>(BuildingType.class);

        // Load per-building HP values
        for (Map.Entry<String, BuildingType> entry : KEY_TO_TYPE.entrySet()) {
            String key = "barbarian-system.building-hp." + entry.getKey();
            BuildingType type = entry.getValue();
            int defaultHp = DEFAULT_HP.getOrDefault(type, 50);

            if (!config.contains(key)) {
                logger.warning("[BarbConfig] Missing config key '" + key + "', using default " + defaultHp);
                hpMap.put(type, defaultHp);
            } else {
                int value = config.getInt(key, -1);
                if (value <= 0) {
                    logger.warning("[BarbConfig] Invalid value for '" + key + "' (" + value + "), using default " + defaultHp);
                    hpMap.put(type, defaultHp);
                } else {
                    hpMap.put(type, value);
                }
            }
        }

        // Load barbarian-damage
        int dmg = DEFAULT_DAMAGE;
        if (!config.contains("barbarian-system.barbarian-damage")) {
            logger.warning("[BarbConfig] Missing config key 'barbarian-system.barbarian-damage', using default " + DEFAULT_DAMAGE);
        } else {
            int v = config.getInt("barbarian-system.barbarian-damage", -1);
            if (v <= 0) {
                logger.warning("[BarbConfig] Invalid value for 'barbarian-system.barbarian-damage' (" + v + "), using default " + DEFAULT_DAMAGE);
            } else {
                dmg = v;
            }
        }
        this.damage = dmg;

        // Load attack-interval-ticks
        int interval = DEFAULT_INTERVAL;
        if (!config.contains("barbarian-system.attack-interval-ticks")) {
            logger.warning("[BarbConfig] Missing config key 'barbarian-system.attack-interval-ticks', using default " + DEFAULT_INTERVAL);
        } else {
            int v = config.getInt("barbarian-system.attack-interval-ticks", -1);
            if (v <= 0) {
                logger.warning("[BarbConfig] Invalid value for 'barbarian-system.attack-interval-ticks' (" + v + "), using default " + DEFAULT_INTERVAL);
            } else {
                interval = v;
            }
        }
        this.attackIntervalTicks = interval;
    }

    /**
     * Returns the configured HP for the given BuildingType, or 50 if not mapped.
     */
    public int getHp(BuildingType type) {
        return hpMap.getOrDefault(type, DEFAULT_HP.getOrDefault(type, 50));
    }

    /**
     * Returns the damage a barbarian deals per attack cycle.
     */
    public int getDamage() {
        return damage;
    }

    /**
     * Returns the number of ticks between barbarian attack cycles.
     */
    public int getAttackIntervalTicks() {
        return attackIntervalTicks;
    }

    /**
     * Factory method — convenience alias for the constructor.
     */
    public static BarbConfig load(FileConfiguration config, Logger logger) {
        return new BarbConfig(config, logger);
    }
}
