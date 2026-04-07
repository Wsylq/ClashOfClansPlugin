package io.lossai.clash.service;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Holds health-bar configuration values read from config.yml.
 * Missing or non-positive keys fall back to documented defaults with a logged warning.
 */
public final class HealthBarConfig {

    public static final int DEFAULT_BAR_LENGTH       = 10;
    public static final int DEFAULT_DECAY_DELAY      = 60;
    public static final int DEFAULT_DISPLAY_INTERVAL = 2;

    private final int barLength;
    private final int decayDelayTicks;
    private final int displayIntervalTicks;

    private HealthBarConfig(int barLength, int decayDelayTicks, int displayIntervalTicks) {
        this.barLength            = barLength;
        this.decayDelayTicks      = decayDelayTicks;
        this.displayIntervalTicks = displayIntervalTicks;
    }

    /**
     * Reads health-bar settings from the given FileConfiguration.
     * Falls back to defaults and logs a warning for absent or non-positive values.
     */
    public static HealthBarConfig load(FileConfiguration config, Logger logger) {
        int barLength            = readPositive(config, logger, "health-bar.bar-length",            DEFAULT_BAR_LENGTH);
        int decayDelayTicks      = readPositive(config, logger, "health-bar.decay-delay-ticks",      DEFAULT_DECAY_DELAY);
        int displayIntervalTicks = readPositive(config, logger, "health-bar.display-interval-ticks", DEFAULT_DISPLAY_INTERVAL);
        return new HealthBarConfig(barLength, decayDelayTicks, displayIntervalTicks);
    }

    private static int readPositive(FileConfiguration config, Logger logger, String key, int defaultValue) {
        if (!config.contains(key)) {
            logger.warning("[HealthBarConfig] Missing config key '" + key + "', using default " + defaultValue);
            return defaultValue;
        }
        int value = config.getInt(key, -1);
        if (value <= 0) {
            logger.warning("[HealthBarConfig] Invalid value for '" + key + "' (" + value + "), using default " + defaultValue);
            return defaultValue;
        }
        return value;
    }

    public int getBarLength() {
        return barLength;
    }

    public int getDecayDelayTicks() {
        return decayDelayTicks;
    }

    public int getDisplayIntervalTicks() {
        return displayIntervalTicks;
    }
}
