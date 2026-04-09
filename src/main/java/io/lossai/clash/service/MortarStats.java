package io.lossai.clash.service;

/**
 * Immutable per-level stats for the Mortar defense.
 * All numeric constants live here — nowhere else.
 *
 * TH3 accurate (TH5 in CoC terms, mapped to TH3 in this plugin):
 *   Unlocked at TH3, 1 instance, 3 upgrade levels
 *   Footprint: 3×3
 *   Range: 4–11 tiles (4-tile blind spot)
 *   Splash radius: 1.5 tiles
 *   Attack speed: 1 shot per 5 seconds (= 100 ticks)
 */
public final class MortarStats {

    /** Minimum range in blocks — targets closer than this are in the blind spot. */
    public static final double MIN_RANGE = 4.0;

    /** Maximum range in blocks. */
    public static final double MAX_RANGE = 11.0;

    /** Splash damage radius in blocks. */
    public static final double SPLASH_RADIUS = 1.5;

    /** Attack cooldown in ticks (5 seconds × 20 ticks/s). */
    public static final int COOLDOWN_TICKS = 100;

    /** Per-level damage values (index 0 = level 1). */
    private static final int[] DAMAGE_BY_LEVEL = {40, 50, 60};

    /** Maximum upgrade level. */
    public static final int MAX_LEVEL = DAMAGE_BY_LEVEL.length;

    private MortarStats() {
        throw new UnsupportedOperationException("MortarStats is a utility class");
    }

    /**
     * Returns the splash damage for the given level (1-indexed).
     * Clamps to valid range.
     */
    public static int damageAtLevel(int level) {
        int idx = Math.max(1, Math.min(level, MAX_LEVEL)) - 1;
        return DAMAGE_BY_LEVEL[idx];
    }
}
