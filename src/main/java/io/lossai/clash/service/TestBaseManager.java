package io.lossai.clash.service;

import io.lossai.clash.model.BuildingType;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Snowball;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Manages the shared test enemy base world ("coc_testbase").
 * The base contains: Town Hall, Cannon, Archer Tower, Army Camp.
 * Each player gets a fresh {@link TestBaseRegistry} when they start an attack.
 */
public final class TestBaseManager {

    public static final String WORLD_NAME = "coc_testbase";
    private static final int GROUND_Y = 64;

    /** Fixed building positions [x, z] in the test base world */
    private static final Map<BuildingType, int[]> BASE_POSITIONS;

    static {
        Map<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        m.put(BuildingType.TOWNHALL,     new int[]{0,  0});
        m.put(BuildingType.CANNON,       new int[]{10, 5});
        m.put(BuildingType.ARCHER_TOWER, new int[]{-10, 5});
        m.put(BuildingType.MORTAR,       new int[]{0, 10});
        m.put(BuildingType.ARMY_CAMP,    new int[]{0, -12});
        BASE_POSITIONS = Collections.unmodifiableMap(m);
    }

    private final JavaPlugin plugin;
    private World testWorld;

    /** Active session registries — set by BarbManager so defense knows which buildings are alive */
    private final Map<UUID, TestBaseRegistry> activeRegistries = new HashMap<>();

    public void setActiveRegistry(UUID playerUuid, TestBaseRegistry registry) {
        if (registry == null) activeRegistries.remove(playerUuid);
        else activeRegistries.put(playerUuid, registry);
    }

    private TestBaseRegistry getAnyActiveRegistry() {
        for (TestBaseRegistry r : activeRegistries.values()) {
            if (!r.isDefeated()) return r;
        }
        return null;
    }

    private static final double CANNON_DAMAGE  = 6.0;
    private static final double ARCHER_DAMAGE  = 3.0;
    private static final double MORTAR_DAMAGE  = 40.0;
    private static final double CANNON_RANGE   = 16.0;
    private static final double ARCHER_RANGE   = 12.0;
    private static final double MORTAR_MIN_RANGE = 4.0;
    private static final double MORTAR_MAX_RANGE = 11.0;
    private static final double MORTAR_SPLASH  = 1.5;
    /** Mortar fires every N ticks of tickDefense (each tick = 20 server ticks = 1s). 5 = 5 seconds. */
    private int mortarCooldownCounter = 0;
    private static final int MORTAR_COOLDOWN_TICKS = 5;

    public TestBaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Returns (creating if needed) the test base world. */
    public World getOrCreateWorld() {
        if (testWorld != null && Bukkit.getWorld(WORLD_NAME) != null) {
            return testWorld;
        }
        World existing = Bukkit.getWorld(WORLD_NAME);
        if (existing != null) {
            testWorld = existing;
            buildBase(testWorld);
            return testWorld;
        }
        testWorld = new WorldCreator(WORLD_NAME)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .createWorld();
        if (testWorld == null) return null;

        testWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        testWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        testWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        testWorld.setTime(6000);

        buildBase(testWorld);

        // Start defense tick — cannon and archer tower shoot at barbarian NPCs
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickDefense, 20L, 20L);
        return testWorld;
    }

    /** Rebuilds all base structures (called on world load and after a raid resets). */
    public void buildBase(World world) {
        // Flatten ground
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                world.getBlockAt(x, GROUND_Y - 1, z).setType(Material.GRASS_BLOCK, false);
                world.getBlockAt(x, GROUND_Y, z).setType(Material.AIR, false);
                world.getBlockAt(x, GROUND_Y + 1, z).setType(Material.AIR, false);
                world.getBlockAt(x, GROUND_Y + 2, z).setType(Material.AIR, false);
            }
        }

        placeBuilding(world, BuildingType.TOWNHALL,     BASE_POSITIONS.get(BuildingType.TOWNHALL));
        placeBuilding(world, BuildingType.CANNON,       BASE_POSITIONS.get(BuildingType.CANNON));
        placeBuilding(world, BuildingType.ARCHER_TOWER, BASE_POSITIONS.get(BuildingType.ARCHER_TOWER));
        placeBuilding(world, BuildingType.MORTAR,       BASE_POSITIONS.get(BuildingType.MORTAR));
        placeBuilding(world, BuildingType.ARMY_CAMP,    BASE_POSITIONS.get(BuildingType.ARMY_CAMP));
    }

    private void placeBuilding(World world, BuildingType type, int[] pos) {
        int x = pos[0], z = pos[1];
        switch (type) {
            case TOWNHALL -> {
                // 3x3 stone brick base + gold block on top
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        world.getBlockAt(x + dx, GROUND_Y, z + dz).setType(Material.STONE_BRICKS, false);
                    }
                }
                world.getBlockAt(x, GROUND_Y + 1, z).setType(Material.GOLD_BLOCK, false);
                world.getBlockAt(x, GROUND_Y + 2, z).setType(Material.GOLD_BLOCK, false);
            }
            case CANNON -> {
                world.getBlockAt(x, GROUND_Y, z).setType(Material.STONE_BRICKS, false);
                Block dispenser = world.getBlockAt(x, GROUND_Y + 1, z);
                dispenser.setType(Material.DISPENSER, false);
                if (dispenser.getBlockData() instanceof Directional d) {
                    d.setFacing(BlockFace.SOUTH);
                    dispenser.setBlockData(d, false);
                }
            }
            case ARCHER_TOWER -> {
                world.getBlockAt(x, GROUND_Y, z).setType(Material.COBBLESTONE, false);
                world.getBlockAt(x, GROUND_Y + 1, z).setType(Material.COBBLESTONE, false);
                world.getBlockAt(x, GROUND_Y + 2, z).setType(Material.COBBLESTONE, false);
                world.getBlockAt(x, GROUND_Y + 3, z).setType(Material.STONE_BRICK_SLAB, false);
            }
            case MORTAR -> {
                // 3×3 stone brick base + cauldron on top as mortar bowl
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        world.getBlockAt(x + dx, GROUND_Y, z + dz).setType(Material.STONE_BRICKS, false);
                    }
                }
                world.getBlockAt(x, GROUND_Y + 1, z).setType(Material.CAULDRON, false);
            }
            case ARMY_CAMP -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        world.getBlockAt(x + dx, GROUND_Y, z + dz).setType(Material.OAK_PLANKS, false);
                    }
                }
                world.getBlockAt(x, GROUND_Y + 1, z).setType(Material.OAK_LOG, false);
            }
            default -> world.getBlockAt(x, GROUND_Y, z).setType(Material.STONE, false);
        }
    }

    /**
     * Creates a fresh {@link TestBaseRegistry} for a new attack session.
     * Also rebuilds the base blocks so the world is clean.
     */
    public TestBaseRegistry createFreshRegistry(BarbConfig config) {
        World world = getOrCreateWorld();
        if (world == null) return null;
        buildBase(world);
        return new TestBaseRegistry(world, BASE_POSITIONS, config);
    }

    /**
     * Fires every second. Cannon and Archer Tower shoot arrows at the nearest
     * barbarian NPC within range, dealing direct damage (no projectile physics needed).
     */
    private void tickDefense() {
        if (testWorld == null) return;

        // Get the active registry to check which buildings are still alive
        TestBaseRegistry registry = getAnyActiveRegistry();

        List<Entity> targets = new ArrayList<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) continue;
            Entity e = npc.getEntity();
            if (e != null && testWorld.equals(e.getWorld())) targets.add(e);
        }
        if (targets.isEmpty()) return;

        // Only shoot from buildings that are still alive (registry == null means no active session, skip)
        if (registry == null) return;

        boolean cannonAlive = registry.isBuildingAlive(BuildingType.CANNON);
        boolean archerAlive = registry.isBuildingAlive(BuildingType.ARCHER_TOWER);
        boolean mortarAlive = registry.isBuildingAlive(BuildingType.MORTAR);

        if (cannonAlive)  shootDefense(BuildingType.CANNON,       BASE_POSITIONS.get(BuildingType.CANNON),       CANNON_RANGE, CANNON_DAMAGE, targets);
        if (archerAlive)  shootDefense(BuildingType.ARCHER_TOWER, BASE_POSITIONS.get(BuildingType.ARCHER_TOWER), ARCHER_RANGE, ARCHER_DAMAGE, targets);
        if (mortarAlive) {
            if (++mortarCooldownCounter >= MORTAR_COOLDOWN_TICKS) {
                mortarCooldownCounter = 0;
                shootMortar(BASE_POSITIONS.get(BuildingType.MORTAR), targets);
            }
        }
    }

    private void shootDefense(BuildingType type, int[] pos, double range, double damage, List<Entity> targets) {
        if (pos == null) return;

        Location origin = new Location(testWorld, pos[0] + 0.5, GROUND_Y + 2.0, pos[1] + 0.5);

        // Find nearest NPC within range
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : targets) {
            double dx = e.getLocation().getX() - origin.getX();
            double dz = e.getLocation().getZ() - origin.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= range && dist < nearestDist) {
                nearestDist = dist;
                nearest = e;
            }
        }
        if (nearest == null) return;

        // Visual: shoot an arrow toward the target
        Location targetLoc = nearest.getLocation().add(0, 1, 0);
        Vector dir = targetLoc.toVector().subtract(origin.toVector()).normalize();

        Arrow arrow = testWorld.spawnArrow(origin, dir, 1.6f, 1.0f);
        arrow.setDamage(0); // visual only — damage applied directly below
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        // Remove arrow quickly so it can't hit other NPCs (friendly fire)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (arrow.isValid()) arrow.remove();
        }, 8L);

        // Play sound at the building
        testWorld.playSound(origin,
                type == BuildingType.CANNON ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_ARROW_SHOOT,
                0.8f, 1.0f);

        // Deal damage directly to the NPC entity after a short delay (arrow travel time)
        final Entity finalTarget = nearest;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (finalTarget.isValid()) {
                finalTarget.getWorld().spawnParticle(Particle.CRIT,
                        finalTarget.getLocation().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0.05);
                // Damage the NPC entity directly
                if (finalTarget instanceof org.bukkit.entity.LivingEntity living) {
                    living.damage(damage);
                }
            }
        }, 5L);
    }

    private void shootMortar(int[] pos, List<Entity> targets) {
        if (pos == null || testWorld == null) return;

        Location origin = new Location(testWorld, pos[0] + 0.5, GROUND_Y + 2.0, pos[1] + 0.5);

        // Find nearest NPC in [MORTAR_MIN_RANGE, MORTAR_MAX_RANGE] — skip blind spot
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : targets) {
            double dx = e.getLocation().getX() - origin.getX();
            double dz = e.getLocation().getZ() - origin.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < MORTAR_MIN_RANGE || dist > MORTAR_MAX_RANGE) continue;
            if (dist < nearestDist) { nearestDist = dist; nearest = e; }
        }
        if (nearest == null) return;

        Location targetLoc = nearest.getLocation().clone().add(0, 0.5, 0);
        Vector dir = targetLoc.toVector().subtract(origin.toVector()).normalize();

        Snowball shell = testWorld.spawn(origin.clone().add(0, 1.5, 0), Snowball.class);
        shell.setVelocity(dir.multiply(1.2));
        testWorld.playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.5f);

        final Location landLoc = targetLoc.clone();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            shell.remove();
            if (testWorld == null) return;
            testWorld.spawnParticle(Particle.EXPLOSION_EMITTER, landLoc.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            testWorld.playSound(landLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
            // AOE splash — damage all NPCs within splash radius
            for (Entity e : testWorld.getNearbyEntities(landLoc, MORTAR_SPLASH, MORTAR_SPLASH, MORTAR_SPLASH)) {
                if (e instanceof org.bukkit.entity.LivingEntity living) {
                    living.damage(MORTAR_DAMAGE);
                }
            }
        }, 15L);
    }

    public static Map<BuildingType, int[]> getBasePositions() {
        return BASE_POSITIONS;
    }

    public static int getGroundY() {
        return GROUND_Y;
    }
}
