package io.lossai.clash.service;

import io.lossai.clash.model.BuildingInstance;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.UUID;

/**
 * Per-NPC behaviour controller for an Archer.
 *
 * Mirrors BarbAI closely, adding a DRAWING state between SEEKING and ATTACKING
 * to model the bow pull-back animation. Movement uses incremental teleportation
 * every tick — identical approach to BarbAI.
 */
public class ArcherAI {

    public enum State { IDLE, SEEKING, DRAWING, ATTACKING }

    /** Blocks moved per tick while seeking (0.45 b/t, same as BarbAI) */
    private static final double MOVE_SPEED = 0.45;

    private final Plugin plugin;
    private final NPC npc;
    private final BuildingRegistry buildingRegistry;
    private final ArcherConfig config;
    private final UUID sessionOwner;
    private final ArcherManager archerManager;

    private State state = State.IDLE;
    private BuildingInstance target;
    private int drawTickCounter = 0;
    private int attackTickCounter = 0;
    private BukkitTask task;
    private HealthBarManager healthBarManager;

    public ArcherAI(NPC npc, BuildingRegistry buildingRegistry, ArcherConfig config,
                    Plugin plugin, UUID sessionOwner, ArcherManager archerManager) {
        this.npc = npc;
        this.buildingRegistry = buildingRegistry;
        this.config = config;
        this.plugin = plugin;
        this.sessionOwner = sessionOwner;
        this.archerManager = archerManager;
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

        for (BuildingInstance inst : buildingRegistry.getSurvivingBuildings()) {
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
            case SEEKING   -> tickSeeking(entity);
            case DRAWING   -> tickDrawing(entity);
            case ATTACKING -> tickAttacking(entity);
            case IDLE      -> stop();
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
        double range = config.getAttackRangeBlocks();

        if (distSq <= range * range) {
            // In range — begin drawing
            drawTickCounter = 0;
            state = State.DRAWING;
            return;
        }

        // Step toward target via teleport
        double len  = Math.sqrt(distSq);
        double step = Math.min(MOVE_SPEED, len);
        double nx   = from.getX() + (dx / len) * step;
        double nz   = from.getZ() + (dz / len) * step;
        float  yaw  = (float) Math.toDegrees(Math.atan2(-dx, dz));

        Location next = new Location(from.getWorld(), nx, from.getY(), nz, yaw, from.getPitch());
        entity.teleport(next);
    }

    private void tickDrawing(Entity entity) {
        // Face the target every tick while drawing
        if (target != null) faceTarget(entity, target.origin());

        // Keep sending hand-active metadata so the bow-draw pose persists
        sendHandActiveMetadata(entity.getEntityId(), true);

        if (++drawTickCounter >= config.getDrawTicks()) {
            attackTickCounter = config.getAttackIntervalTicks() - 1; // fire on next tick
            state = State.ATTACKING;
        }
    }

    private void tickAttacking(Entity entity) {
        // Face the target while attacking
        if (target != null) faceTarget(entity, target.origin());

        if (++attackTickCounter >= config.getAttackIntervalTicks()) {
            attackTickCounter = 0;
            // Send "release" frame — clears the drawn-bow pose so client sees the shot
            sendHandActiveMetadata(entity.getEntityId(), false);
            executeAttack(entity);
            // Transition back to DRAWING for the next shot cycle
            if (state == State.ATTACKING) {
                drawTickCounter = 0;
                state = State.DRAWING;
            }
        }
    }

    /** Rotates the NPC entity to face a target location using packets (bypasses Citizens override). */
    private void faceTarget(Entity entity, Location targetLoc) {
        Location from = entity.getLocation().add(0, 1.5, 0); // eye height
        double dx = targetLoc.getX() + 0.5 - from.getX();
        double dy = targetLoc.getY() + 1.0 - from.getY(); // aim at mid-height of building
        double dz = targetLoc.getZ() + 0.5 - from.getZ();

        float yaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        try {
            int eid = entity.getEntityId();
            // Send rotation packet (body + head)
            com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation rotPacket =
                new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation(eid, yaw, pitch, true);
            com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook headPacket =
                new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook(eid, yaw);

            for (org.bukkit.entity.Player viewer : entity.getWorld().getPlayers()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, rotPacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, headPacket);
            }
        } catch (Exception ignored) {
            // PacketEvents not available — fall back to teleport
            Location loc = entity.getLocation();
            entity.teleport(new Location(loc.getWorld(), loc.getX(), loc.getY(), loc.getZ(), yaw, pitch));
        }
    }

    private void sendHandActiveMetadata(int entityId, boolean active) {
        try {
            // Entity metadata index 8 = hand states byte for LivingEntity
            // Bit 0: isHandActive, Bit 1: activeHand (0=main, 1=off)
            byte handState = active ? (byte) 0x01 : (byte) 0x00;
            java.util.List<EntityData> metadata = java.util.List.of(
                new EntityData(8, EntityDataTypes.BYTE, handState)
            );
            WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(entityId, metadata);
            // Broadcast to all players in the same world
            if (npc.getEntity() != null) {
                for (org.bukkit.entity.Player viewer : npc.getEntity().getWorld().getPlayers()) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
                }
            }
        } catch (Exception ignored) {
            // PacketEvents not available — fall back silently
        }
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

        Location npcLoc = entity.getLocation().add(0, 1.5, 0); // eye height
        Location targetLoc = target.origin().clone().add(0.5, 1.0, 0.5);

        // Direction vector toward target
        Vector direction = targetLoc.toVector().subtract(npcLoc.toVector()).normalize();

        // Spawn arrow and remove it after 1 tick (visual only — damage applied directly)
        Arrow arrow = entity.getWorld().spawnArrow(npcLoc, direction, 1.5f, 1.0f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (arrow.isValid()) arrow.remove();
        }, 1L);

        // Play shoot sound
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.0f);

        // Apply damage directly
        buildingRegistry.damageBuilding(target, config.getDamage());

        // Check if target was destroyed
        boolean alive = false;
        List<BuildingInstance> surviving = buildingRegistry.getSurvivingBuildings();
        for (BuildingInstance b : surviving) {
            if (b.id().equals(target.id())) { alive = true; break; }
        }

        if (!alive) {
            sendHandActiveMetadata(entity.getEntityId(), false); // clear bow-draw pose
            if (healthBarManager != null) healthBarManager.remove(target);
            target = selectTarget();
            if (target == null) {
                state = State.IDLE;
                stop();
                if (archerManager != null) archerManager.onArcherDespawn(npc, sessionOwner);
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
        // Run every 2 ticks — same cadence as BarbAI
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    public void stop() {
        if (task != null && !task.isCancelled()) task.cancel();
        // Clear bow-draw animation if still active
        Entity entity = npc.getEntity();
        if (entity != null) sendHandActiveMetadata(entity.getEntityId(), false);
    }

    // -----------------------------------------------------------------------
    // Pure static helper — identical contract to BarbAI.indexOfNearest
    // -----------------------------------------------------------------------

    /**
     * Returns the index of the nearest candidate to {@code fromXZ}, or -1 for an empty array.
     * Pure function — no side effects.
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

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public State getState() { return state; }
}
