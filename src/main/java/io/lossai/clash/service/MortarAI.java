package io.lossai.clash.service;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Snowball;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Mortar defense AI — fires a Snowball projectile at the nearest troop in range,
 * skipping targets inside the 4-tile blind spot. On landing, deals splash damage
 * to all troops within {@link MortarStats#SPLASH_RADIUS}.
 *
 * Mirrors ArcherAI structure: tick-driven, no Citizens navigator.
 */
public final class MortarAI {

    private final Plugin plugin;
    private final BuildingRegistry registry;
    private final MortarConfig config;

    /** World position of this mortar (centre of the 3×3 footprint). */
    private final Location origin;

    /** Ticks elapsed since last shot. */
    private int cooldownCounter = 0;

    public MortarAI(Plugin plugin, BuildingRegistry registry, MortarConfig config, Location origin) {
        this.plugin   = plugin;
        this.registry = registry;
        this.config   = config;
        this.origin   = origin;
    }

    // -----------------------------------------------------------------------
    // Tick — called every server tick by MortarManager
    // -----------------------------------------------------------------------

    public void tick() {
        if (++cooldownCounter < config.getCooldownTicks()) return;
        cooldownCounter = 0;

        Entity target = selectTarget();
        if (target == null) return;

        fire(target);
    }

    // -----------------------------------------------------------------------
    // Target selection
    // -----------------------------------------------------------------------

    /**
     * Returns the nearest living entity in [minRange, maxRange], or null.
     * Entities strictly closer than minRange are in the blind spot and skipped.
     */
    Entity selectTarget() {
        if (origin.getWorld() == null) return null;

        double minR = config.getMinRange();
        double maxR = config.getMaxRange();

        Entity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Entity e : origin.getWorld().getNearbyEntities(origin, maxR, maxR, maxR)) {
            if (!(e instanceof Monster)) continue;
            double dx = e.getLocation().getX() - origin.getX();
            double dz = e.getLocation().getZ() - origin.getZ();
            double distSq = dx * dx + dz * dz;
            double dist   = Math.sqrt(distSq);
            if (dist < minR || dist > maxR) continue; // blind spot or out of range
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = e;
            }
        }
        return nearest;
    }

    // -----------------------------------------------------------------------
    // Fire
    // -----------------------------------------------------------------------

    private void fire(Entity target) {
        if (origin.getWorld() == null) return;

        Location targetLoc = target.getLocation().clone().add(0, 0.5, 0);
        Vector dir = targetLoc.toVector().subtract(origin.toVector()).normalize();

        // Launch a Snowball as the mortar shell visual
        Snowball shell = origin.getWorld().spawn(origin.clone().add(0, 1.5, 0), Snowball.class);
        shell.setVelocity(dir.multiply(1.2));

        origin.getWorld().playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.5f);

        // On landing (after travel time), deal AOE splash damage
        final Location landLoc = targetLoc.clone();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            shell.remove();
            dealSplash(landLoc);
        }, 15L); // ~0.75 s travel time
    }

    /**
     * Deals {@code config.getDamage()} to every LivingEntity within
     * {@link MortarStats#SPLASH_RADIUS} of {@code centre}.
     */
    void dealSplash(Location centre) {
        if (centre.getWorld() == null) return;

        double splashR = MortarStats.SPLASH_RADIUS;

        centre.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER,
                centre.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
        centre.getWorld().playSound(centre, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);

        List<Entity> hit = new ArrayList<>(
                centre.getWorld().getNearbyEntities(centre, splashR, splashR, splashR));
        for (Entity e : hit) {
            if (e instanceof Monster monster) {
                monster.damage(config.getDamage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Pure static helper — mirrors BarbAI.indexOfNearest for testability
    // -----------------------------------------------------------------------

    /**
     * Returns the index of the nearest candidate to {@code fromXZ} that is
     * at least {@code minRange} away, or -1 if none qualify.
     * Pure function — no side effects.
     */
    public static int indexOfNearestInRange(double[] fromXZ, double[][] candidateXZ,
                                            double minRange, double maxRange) {
        if (candidateXZ == null || candidateXZ.length == 0) return -1;
        int nearest = -1;
        double nearestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < candidateXZ.length; i++) {
            double dx = fromXZ[0] - candidateXZ[i][0];
            double dz = fromXZ[1] - candidateXZ[i][1];
            double distSq = dx * dx + dz * dz;
            double dist   = Math.sqrt(distSq);
            if (dist < minRange || dist > maxRange) continue;
            if (distSq < nearestDistSq) { nearestDistSq = distSq; nearest = i; }
        }
        return nearest;
    }

    /**
     * Returns true iff {@code dist} is within the mortar's effective range
     * (i.e. NOT in the blind spot and NOT beyond max range).
     */
    public static boolean isInEffectiveRange(double dist, double minRange, double maxRange) {
        return dist >= minRange && dist <= maxRange;
    }

    /**
     * Returns true iff {@code dist} is within the splash radius of the impact point.
     */
    public static boolean isInSplashRadius(double dist, double splashRadius) {
        return dist <= splashRadius;
    }
}
