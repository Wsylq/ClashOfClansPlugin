package io.lossai.clash.service;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Holds mortar-system configuration values read from config.yml.
 * Missing or invalid keys fall back to documented defaults with a logged warning.
 * Mirrors ArcherConfig exactly.
 */
public final class MortarConfig {

    // Documented defaults — sourced from MortarStats
    private static final int DEFAULT_DAMAGE        = MortarStats.damageAtLevel(1); // 40
    private static final int DEFAULT_COOLDOWN_TICKS = MortarStats.COOLDOWN_TICKS;  // 100
    private static final int DEFAULT_MIN_RANGE     = (int) MortarStats.MIN_RANGE;  // 4
    private static final int DEFAULT_MAX_RANGE     = (int) MortarStats.MAX_RANGE;  // 11

    private final int damage;
    private final int cooldownTicks;
    private final int minRange;
    private final int maxRange;

    public MortarConfig(FileConfiguration config, Logger logger) {
        // damage
        int dmg = DEFAULT_DAMAGE;
        if (!config.contains("mortar-system.damage")) {
            logger.warning("[MortarConfig] Missing 'mortar-system.damage', using default " + DEFAULT_DAMAGE);
        } else {
            int v = config.getInt("mortar-system.damage", -1);
            if (v <= 0) {
                logger.warning("[MortarConfig] Invalid 'mortar-system.damage' (" + v + "), using default " + DEFAULT_DAMAGE);
            } else {
                dmg = v;
            }
        }
        this.damage = dmg;

        // cooldown-ticks
        int cd = DEFAULT_COOLDOWN_TICKS;
        if (!config.contains("mortar-system.cooldown-ticks")) {
            logger.warning("[MortarConfig] Missing 'mortar-system.cooldown-ticks', using default " + DEFAULT_COOLDOWN_TICKS);
        } else {
            int v = config.getInt("mortar-system.cooldown-ticks", -1);
            if (v <= 0) {
                logger.warning("[MortarConfig] Invalid 'mortar-system.cooldown-ticks' (" + v + "), using default " + DEFAULT_COOLDOWN_TICKS);
            } else {
                cd = v;
            }
        }
        this.cooldownTicks = cd;

        // min-range
        int minR = DEFAULT_MIN_RANGE;
        if (!config.contains("mortar-system.min-range")) {
            logger.warning("[MortarConfig] Missing 'mortar-system.min-range', using default " + DEFAULT_MIN_RANGE);
        } else {
            int v = config.getInt("mortar-system.min-range", -1);
            if (v < 0) {
                logger.warning("[MortarConfig] Invalid 'mortar-system.min-range' (" + v + "), using default " + DEFAULT_MIN_RANGE);
            } else {
                minR = v;
            }
        }
        this.minRange = minR;

        // max-range
        int maxR = DEFAULT_MAX_RANGE;
        if (!config.contains("mortar-system.max-range")) {
            logger.warning("[MortarConfig] Missing 'mortar-system.max-range', using default " + DEFAULT_MAX_RANGE);
        } else {
            int v = config.getInt("mortar-system.max-range", -1);
            if (v <= 0) {
                logger.warning("[MortarConfig] Invalid 'mortar-system.max-range' (" + v + "), using default " + DEFAULT_MAX_RANGE);
            } else {
                maxR = v;
            }
        }
        this.maxRange = maxR;
    }

    public int getDamage()        { return damage; }
    public int getCooldownTicks() { return cooldownTicks; }
    public int getMinRange()      { return minRange; }
    public int getMaxRange()      { return maxRange; }

    public static MortarConfig load(FileConfiguration config, Logger logger) {
        return new MortarConfig(config, logger);
    }
}
