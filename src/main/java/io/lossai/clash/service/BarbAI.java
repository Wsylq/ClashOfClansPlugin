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
 * Drives a SEEKING → ATTACKING → IDLE state machine via a repeating BukkitTask.
 *
 * Talks only to {@link BuildingRegistry} — has no knowledge of villages,
 * test bases, or any other concrete building source.
 */
public class BarbAI {

    public enum State { SEEKING, ATTACKING, IDLE }

    private final ClashPlugin plugin;
    private final NPC npc;
    private final BuildingRegistry registry;

    private final int damage;
    private final int attackIntervalTicks;

    private State state = State.IDLE;
    private BuildingInstance target;
    private int attackTickCounter = 0;
    private BukkitTask task;

    public BarbAI(ClashPlugin plugin, NPC npc, BuildingRegistry registry) {
        this.plugin = plugin;
        this.npc = npc;
        this.registry = registry;
        this.damage = plugin.getBarbConfig().getDamage();
        this.attackIntervalTicks = plugin.getBarbConfig().getAttackIntervalTicks();
    }

    /** Picks the nearest surviving BuildingInstance by Euclidean distance. */
    public BuildingInstance selectTarget() {
        Entity entity = npc.getEntity();
        if (entity == null) return null;

        Location npcLoc = entity.getLocation();
        List<BuildingInstance> survivors = registry.getSurvivingBuildings();

        BuildingInstance nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BuildingInstance inst : survivors) {
            double d = npcLoc.distanceSquared(inst.origin());
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = inst;
            }
        }
        return nearest;
    }

    /** Called every tick by the scheduler. Drives state transitions and attack cycles. */
    public void tick() {
        Entity entity = npc.getEntity();
        if (entity == null) { state = State.IDLE; stop(); return; }

        switch (state) {
            case SEEKING -> {
                if (target == null) {
                    target = selectTarget();
                    if (target == null) { state = State.IDLE; stop(); return; }
                    startNavigating();
                }
                if (entity.getLocation().distance(target.origin()) <= 2.0) {
                    npc.getNavigator().cancelNavigation();
                    attackTickCounter = 0;
                    state = State.ATTACKING;
                }
            }
            case ATTACKING -> {
                if (++attackTickCounter >= attackIntervalTicks) {
                    attackTickCounter = 0;
                    executeAttack();
                }
            }
            case IDLE -> stop();
        }
    }

    private void executeAttack() {
        if (target == null) {
            target = selectTarget();
            if (target == null) { state = State.IDLE; stop(); return; }
            startNavigating();
            state = State.SEEKING;
            return;
        }

        Entity entity = npc.getEntity();
        if (entity != null) {
            entity.getWorld().playSound(
                    entity.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.0f);
        }

        Location origin = target.origin();
        if (origin.getWorld() != null) {
            origin.getWorld().spawnParticle(
                    Particle.CRIT, origin.clone().add(0.5, 1.0, 0.5), 8, 0.3, 0.3, 0.3, 0.1);
        }

        registry.damageBuilding(target, damage);

        // Re-select if target was destroyed
        if (!registry.getSurvivingBuildings().contains(target)) {
            target = selectTarget();
            if (target == null) {
                state = State.IDLE;
                stop();
            } else {
                attackTickCounter = 0;
                startNavigating();
                state = State.SEEKING;
            }
        }
    }

    private void startNavigating() {
        if (target != null) npc.getNavigator().setTarget(target.origin());
    }

    /** Selects an initial target and starts the repeating tick task. */
    public void start() {
        target = selectTarget();
        if (target == null) { state = State.IDLE; return; }
        state = State.SEEKING;
        startNavigating();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Cancels the tick task. */
    public void stop() {
        if (task != null && !task.isCancelled()) task.cancel();
    }

    // -----------------------------------------------------------------------
    // Pure helper for property-based tests (no Bukkit dependency)
    // -----------------------------------------------------------------------

    /**
     * Returns the index of the nearest [x,z] candidate to {@code fromXZ}.
     * Returns -1 for an empty array.
     */
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
