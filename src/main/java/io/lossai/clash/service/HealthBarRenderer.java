package io.lossai.clash.service;

import org.bukkit.ChatColor;

public final class HealthBarRenderer {

    private HealthBarRenderer() {}

    /**
     * Returns the ChatColor for the given HP ratio (0.0–1.0).
     * ratio > 0.50  → GREEN
     * ratio > 0.25  → YELLOW
     * ratio <= 0.25 → RED
     */
    public static ChatColor colorFor(double ratio) {
        if (ratio > 0.50) return ChatColor.GREEN;
        if (ratio > 0.25) return ChatColor.YELLOW;
        return ChatColor.RED;
    }

    /**
     * Renders a text bar of exactly barLength characters.
     * Filled characters: '█', empty characters: '░'.
     * Returns the colored string ready for a name tag.
     * If maxHp <= 0, ratio is clamped to 0.0 (empty bar).
     */
    public static String renderBar(int currentHp, int maxHp, int barLength) {
        double ratio = (maxHp <= 0) ? 0.0 : (double) currentHp / maxHp;
        // Clamp to [0, 1]
        ratio = Math.max(0.0, Math.min(1.0, ratio));

        int filled = (int) Math.round(ratio * barLength);
        int empty = barLength - filled;

        ChatColor color = colorFor(ratio);
        StringBuilder sb = new StringBuilder();
        sb.append(color.toString());
        for (int i = 0; i < filled; i++) sb.append('█');
        for (int i = 0; i < empty; i++) sb.append('░');
        return sb.toString();
    }

    /**
     * Returns true if the health bar should be hidden due to inactivity.
     * Decayed when ticksSinceLastDamage >= decayDelayTicks.
     */
    public static boolean isDecayed(int ticksSinceLastDamage, int decayDelayTicks) {
        return ticksSinceLastDamage >= decayDelayTicks;
    }
}
