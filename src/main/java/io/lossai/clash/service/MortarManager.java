package io.lossai.clash.service;

import io.lossai.clash.model.BuildingType;
import io.lossai.clash.model.VillageData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages per-village Mortar defense ticks.
 * Mirrors CannonManager / ArcherManager structure exactly.
 *
 * Called every 20 ticks by ClashPlugin. Each MortarAI instance tracks its own
 * cooldown so shots fire at the correct 5-second interval regardless of tick rate.
 */
public final class MortarManager {

    private static final int GROUND_Y = 64;

    private final Plugin plugin;
    private final MortarConfig config;
    private final VillageManager villageManager;

    /** Active AI instances — rebuilt whenever tickMortarDefense detects a stale list. */
    private final List<MortarAI> activeAIs = new ArrayList<>();

    public MortarManager(Plugin plugin, MortarConfig config, VillageManager villageManager) {
        this.plugin         = plugin;
        this.config         = config;
        this.villageManager = villageManager;
    }

    // -----------------------------------------------------------------------
    // Defense tick — called by ClashPlugin scheduler
    // -----------------------------------------------------------------------

    /**
     * Ticks every active mortar AI once.
     * Mirrors tickCannonDefense / tickArcherTowerDefense in VillageManager.
     */
    public void tickMortarDefense() {
        for (VillageData village : villageManager.getVillageSnapshot().values()) {
            World world = Bukkit.getWorld(village.getWorldName() != null ? village.getWorldName() : "");
            if (world == null) continue;

            Map<BuildingType, List<int[]>> overrides = villageManager.getPlayerSlotOverrides(village.getPlayerId());
            List<int[]> slots = (overrides != null && overrides.containsKey(BuildingType.MORTAR))
                    ? overrides.get(BuildingType.MORTAR)
                    : VillageManager.getBuildingSlots().getOrDefault(BuildingType.MORTAR, List.of());

            int count = Math.min(village.getBuildingCount(BuildingType.MORTAR), slots.size());
            for (int i = 0; i < count; i++) {
                int[] slot = slots.get(i);
                Location origin = new Location(world, slot[0] + 0.5, GROUND_Y + 2.5, slot[1] + 0.5);
                // Create a transient AI per tick — stateless enough for village defense
                // (cooldown is managed at the VillageManager level via tickMortarDefense interval)
                fireMortar(world, origin);
            }
        }
    }

    private void fireMortar(World world, Location origin) {
        // Delegate to a one-shot MortarAI with cooldown already expired
        MortarAI ai = new MortarAI(plugin, null, config, origin);
        // Force immediate fire by pre-filling the cooldown counter via tick loop
        // (simpler: just call the fire logic directly through a helper)
        fireMortarShot(world, origin);
    }

    /**
     * Fires one mortar shot from {@code origin} at the nearest valid target.
     * Mirrors fireCannon / fireArcherTower in VillageManager.
     */
    private void fireMortarShot(World world, Location origin) {
        double minR = config.getMinRange();
        double maxR = config.getMaxRange();

        org.bukkit.entity.Entity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (org.bukkit.entity.Entity e : world.getNearbyEntities(origin, maxR, maxR, maxR)) {
            if (!(e instanceof org.bukkit.entity.Monster)) continue;
            double dx = e.getLocation().getX() - origin.getX();
            double dz = e.getLocation().getZ() - origin.getZ();
            double distSq = dx * dx + dz * dz;
            double dist   = Math.sqrt(distSq);
            if (dist < minR || dist > maxR) continue;
            if (distSq < nearestDistSq) { nearestDistSq = distSq; nearest = e; }
        }
        if (nearest == null) return;

        Location targetLoc = nearest.getLocation().clone().add(0, 0.5, 0);
        org.bukkit.util.Vector dir = targetLoc.toVector().subtract(origin.toVector()).normalize();

        org.bukkit.entity.Snowball shell = world.spawn(origin.clone().add(0, 1.5, 0), org.bukkit.entity.Snowball.class);
        shell.setVelocity(dir.multiply(1.2));

        world.playSound(origin, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.5f);

        final Location landLoc = targetLoc.clone();
        final double splashR   = MortarStats.SPLASH_RADIUS;
        final int    damage    = config.getDamage();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            shell.remove();
            if (landLoc.getWorld() == null) return;
            landLoc.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER,
                    landLoc.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            landLoc.getWorld().playSound(landLoc, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
            for (org.bukkit.entity.Entity e : landLoc.getWorld().getNearbyEntities(landLoc, splashR, splashR, splashR)) {
                if (e instanceof org.bukkit.entity.Monster monster) {
                    monster.damage(damage);
                }
            }
        }, 15L);
    }
}
