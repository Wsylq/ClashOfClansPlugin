package io.lossai.clash.service;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Holds archer-system configuration values read from config.yml.
 * Missing or invalid keys fall back to documented defaults with a logged warning.
 */
public final class ArcherConfig {

    // Documented defaults
    private static final int DEFAULT_DAMAGE = 8;
    private static final int DEFAULT_ATTACK_INTERVAL_TICKS = 25;
    private static final int DEFAULT_ATTACK_RANGE_BLOCKS = 10;
    private static final int DEFAULT_DRAW_TICKS = 10;

    private final int damage;
    private final int attackIntervalTicks;
    private final int attackRangeBlocks;
    private final int drawTicks;

    public ArcherConfig(FileConfiguration config, Logger logger) {
        // Load archer-damage
        int dmg = DEFAULT_DAMAGE;
        if (!config.contains("archer-system.archer-damage")) {
            logger.warning("[ArcherConfig] Missing config key 'archer-system.archer-damage', using default " + DEFAULT_DAMAGE);
        } else {
            int v = config.getInt("archer-system.archer-damage", -1);
            if (v <= 0) {
                logger.warning("[ArcherConfig] Invalid value for 'archer-system.archer-damage' (" + v + "), using default " + DEFAULT_DAMAGE);
            } else {
                dmg = v;
            }
        }
        this.damage = dmg;

        // Load attack-interval-ticks
        int interval = DEFAULT_ATTACK_INTERVAL_TICKS;
        if (!config.contains("archer-system.attack-interval-ticks")) {
            logger.warning("[ArcherConfig] Missing config key 'archer-system.attack-interval-ticks', using default " + DEFAULT_ATTACK_INTERVAL_TICKS);
        } else {
            int v = config.getInt("archer-system.attack-interval-ticks", -1);
            if (v <= 0) {
                logger.warning("[ArcherConfig] Invalid value for 'archer-system.attack-interval-ticks' (" + v + "), using default " + DEFAULT_ATTACK_INTERVAL_TICKS);
            } else {
                interval = v;
            }
        }
        this.attackIntervalTicks = interval;

        // Load attack-range-blocks
        int range = DEFAULT_ATTACK_RANGE_BLOCKS;
        if (!config.contains("archer-system.attack-range-blocks")) {
            logger.warning("[ArcherConfig] Missing config key 'archer-system.attack-range-blocks', using default " + DEFAULT_ATTACK_RANGE_BLOCKS);
        } else {
            int v = config.getInt("archer-system.attack-range-blocks", -1);
            if (v <= 0) {
                logger.warning("[ArcherConfig] Invalid value for 'archer-system.attack-range-blocks' (" + v + "), using default " + DEFAULT_ATTACK_RANGE_BLOCKS);
            } else {
                range = v;
            }
        }
        this.attackRangeBlocks = range;

        // Load draw-ticks
        int draw = DEFAULT_DRAW_TICKS;
        if (!config.contains("archer-system.draw-ticks")) {
            logger.warning("[ArcherConfig] Missing config key 'archer-system.draw-ticks', using default " + DEFAULT_DRAW_TICKS);
        } else {
            int v = config.getInt("archer-system.draw-ticks", -1);
            if (v <= 0) {
                logger.warning("[ArcherConfig] Invalid value for 'archer-system.draw-ticks' (" + v + "), using default " + DEFAULT_DRAW_TICKS);
            } else {
                draw = v;
            }
        }
        this.drawTicks = draw;
    }

    /**
     * Returns the damage an archer deals per attack cycle.
     */
    public int getDamage() {
        return damage;
    }

    /**
     * Returns the number of ticks between archer attack cycles.
     */
    public int getAttackIntervalTicks() {
        return attackIntervalTicks;
    }

    /**
     * Returns the attack range in blocks.
     */
    public int getAttackRangeBlocks() {
        return attackRangeBlocks;
    }

    /**
     * Returns the number of ticks the archer holds the draw before firing.
     */
    public int getDrawTicks() {
        return drawTicks;
    }

    /**
     * Factory method — convenience alias for the constructor.
     */
    public static ArcherConfig load(FileConfiguration config, Logger logger) {
        return new ArcherConfig(config, logger);
    }
}
