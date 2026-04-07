package io.lossai.clash.service;

import io.lossai.clash.ClashPlugin;
import io.lossai.clash.model.BuildingInstance;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * Per-NPC behaviour controller for a Barbarian.
 *
 * Movement uses incremental teleportation every tick — Citizens2 velocity and
 * navigator APIs are both unreliable for static block targets on flat worlds.
 * Teleporting the underlying entity directly is the only approach that always works.
 */
public class BarbAI {

    public enum State { SEEKING, ATTACKING, IDLE }

    /** Blocks moved per tick while seeking (≈ 0.45 b/t = ~9 b/s, feels like a sprinting player) */
    private static final double MOVE_SPEED = 0.45;

    /** Horizontal distance at which the barb stops moving and starts attacking */
    private static final double ATTACK_RANGE = 2.0;

    private final ClashPlugin plugin;
    private final NPC npc;
    private final BuildingRegistry registry;

    private final int damage;
    private final int attackIntervalTicks;

    private State state = State.IDLE;
    private BuildingInstance target;
    private int attackTickCounter = 0;
    private BukkitTask task;
    private HealthBarManager healthBarManager;

    public BarbAI(ClashPlugin plugin, NPC npc, BuildingRegistry registry) {
        this.plugin = plugin;
        this.npc = npc;
        this.registry = registry;
        this.damage = plugin.getBarbConfig().getDamage();
        this.attackIntervalTicks = plugin.getBarbConfig().getAttackIntervalTicks();
    }

    public void setHealthBarManager(HealthBarManager healthBarManager) {
        this.healthBarManager = healthBarManager;
    }

    // -----------------------------------------------------------------------
    // Target selection
    // -----------------------------------------------------------------------

    public BuildingInstance selectTarget() {
        Entity entity = npc.getEntity();
        if (entity == null) return null;

        Location npcLoc = entity.getLocation();
        BuildingInstance nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (BuildingInstance inst : registry.getSurvivingBuildings()) {
            Location origin = inst.origin();
            if (origin.getWorld() == null || !origin.getWorld().equals(npcLoc.getWorld())) continue;
            double dx = npcLoc.getX() - origin.getX();
            double dz = npcLoc.getZ() - origin.getZ();
            double d = dx * dx + dz * dz;
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = inst;
            }
        }
        return nearest;
    }

    // -----------------------------------------------------------------------
    // Tick
    // -----------------------------------------------------------------------

    public void tick() {
        Entity entity = npc.getEntity();
        if (entity == null) {
            state = State.IDLE;
            if (healthBarManager != null) healthBarManager.remove(npc);
            stop();
            return;
        }

        switch (state) {
            case SEEKING  -> tickSeeking(entity);
            case ATTACKING -> {
                if (++attackTickCounter >= attackIntervalTicks) {
                    attackTickCounter = 0;
                    executeAttack(entity);
                }
            }
            case IDLE -> stop();
        }
    }

    private void tickSeeking(Entity entity) {
        if (target == null) {
            target = selectTarget();
            if (target == null) { state = State.IDLE; stop(); return; }
        }

        Location from = entity.getLocation();
        Location to   = target.origin();

        if (to.getWorld() == null || !to.getWorld().equals(from.getWorld())) {
            target = selectTarget();
            if (target == null) { state = State.IDLE; stop(); }
            return;
        }

        double dx = to.getX() + 0.5 - from.getX();
        double dz = to.getZ() + 0.5 - from.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq <= ATTACK_RANGE * ATTACK_RANGE) {
            attackTickCounter = 0;
            state = State.ATTACKING;
            return;
        }

        // Step toward target via teleport — immune to Citizens2 physics override
        double len  = Math.sqrt(distSq);
        double step = Math.min(MOVE_SPEED, len); // don't overshoot
        double nx   = from.getX() + (dx / len) * step;
        double nz   = from.getZ() + (dz / len) * step;
        float  yaw  = (float) Math.toDegrees(Math.atan2(-dx, dz));

        Location next = new Location(from.getWorld(), nx, from.getY(), nz, yaw, from.getPitch());
        entity.teleport(next);
    }

    // -----------------------------------------------------------------------
    // Attack
    // -----------------------------------------------------------------------

    private void executeAttack(Entity entity) {
        if (target == null) {
            target = selectTarget();
            if (target == null) { state = State.IDLE; stop(); return; }
            state = State.SEEKING;
            return;
        }

        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.0f);

        Location origin = target.origin();
        if (origin.getWorld() != null) {
            origin.getWorld().spawnParticle(
                    Particle.CRIT, origin.clone().add(0.5, 1.0, 0.5), 8, 0.3, 0.3, 0.3, 0.1);
        }

        registry.damageBuilding(target, damage);

        // Check if target was destroyed
        boolean alive = false;
        for (BuildingInstance b : registry.getSurvivingBuildings()) {
            if (b.id().equals(target.id())) { alive = true; break; }
        }

        if (!alive) {
            if (healthBarManager != null) healthBarManager.remove(target);
            target = selectTarget();
            if (target == null) {
                state = State.IDLE;
                stop();
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (npc.isSpawned()) {
                        if (healthBarManager != null) healthBarManager.remove(npc);
                        npc.despawn();
                    }
                }, 60L);
            } else {
                attackTickCounter = 0;
                state = State.SEEKING;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void start() {
        npc.getNavigator().cancelNavigation();
        target = selectTarget();
        if (target == null) { state = State.IDLE; return; }
        state = State.SEEKING;
        // Run every 2 ticks — smooth enough, half the server tick overhead of every-tick
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    public void stop() {
        if (task != null && !task.isCancelled()) task.cancel();
    }

    // -----------------------------------------------------------------------
    // Pure helper for property-based tests
    // -----------------------------------------------------------------------

    public static int indexOfNearest(double[] fromXZ, double[][] candidateXZ) {
        if (candidateXZ == null || candidateXZ.length == 0) return -1;
        int nearest = 0;
        double nearestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < candidateXZ.length; i++) {
            double dx = fromXZ[0] - candidateXZ[i][0];
            double dz = fromXZ[1] - candidateXZ[i][1];
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) { nearestDistSq = distSq; nearest = i; }
        }
        return nearest;
    }

    public State getState() { return state; }
}
