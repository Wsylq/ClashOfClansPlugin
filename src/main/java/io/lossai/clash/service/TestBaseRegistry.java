package io.lossai.clash.service;

import io.lossai.clash.model.BuildingInstance;
import io.lossai.clash.model.BuildingType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;

import java.util.*;

/**
 * BuildingRegistry backed by the static test enemy base.
 * HP is tracked in-memory for the lifetime of one attack session.
 */
public final class TestBaseRegistry implements BuildingRegistry {

    private static final int GROUND_Y = TestBaseManager.getGroundY();

    /** 3×3 footprint offsets */
    private static final int[] FOOTPRINT_3X3;
    static {
        int[] fp = new int[18];
        int idx = 0;
        for (int bx = -1; bx <= 1; bx++) {
            for (int bz = -1; bz <= 1; bz++) { fp[idx++] = bx; fp[idx++] = bz; }
        }
        FOOTPRINT_3X3 = fp;
    }

    private final World world;
    private final List<BuildingInstance> allBuildings;
    private final Map<UUID, Integer> buildingHp = new HashMap<>();

    public TestBaseRegistry(World world, Map<BuildingType, int[]> positions, BarbConfig config) {
        this.world = world;
        this.allBuildings = buildInstances(positions, config);
    }

    private List<BuildingInstance> buildInstances(Map<BuildingType, int[]> positions, BarbConfig config) {
        List<BuildingInstance> list = new ArrayList<>();
        for (Map.Entry<BuildingType, int[]> entry : positions.entrySet()) {
            BuildingType type = entry.getKey();
            int[] pos = entry.getValue();
            Location origin = new Location(world, pos[0], GROUND_Y, pos[1]);
            BuildingInstance inst = new BuildingInstance(UUID.randomUUID(), type, origin, FOOTPRINT_3X3.clone());
            list.add(inst);
            buildingHp.put(inst.id(), config.getHp(type));
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public List<BuildingInstance> getSurvivingBuildings() {
        List<BuildingInstance> survivors = new ArrayList<>();
        for (BuildingInstance inst : allBuildings) {
            if (buildingHp.getOrDefault(inst.id(), 0) > 0) survivors.add(inst);
        }
        return survivors;
    }

    @Override
    public void damageBuilding(BuildingInstance instance, int damage) {
        Integer current = buildingHp.get(instance.id());
        if (current == null) return;
        int newHp = BuildingRegistry.applyDamage(current, damage);
        buildingHp.put(instance.id(), newHp);

        Location origin = instance.origin();
        if (origin.getWorld() != null) {
            origin.getWorld().spawnParticle(Particle.CRIT,
                    origin.clone().add(0.5, 1.0, 0.5), 8, 0.3, 0.3, 0.3, 0.1);
        }
        if (newHp <= 0) destroyBuilding(instance);
    }

    private void destroyBuilding(BuildingInstance instance) {
        Location origin = instance.origin();
        if (origin.getWorld() != null) {
            clearBlocks(instance);
            origin.getWorld().playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        }
        buildingHp.remove(instance.id());
    }

    private void clearBlocks(BuildingInstance instance) {
        Location origin = instance.origin();
        int ox = (int) origin.getX(), oz = (int) origin.getZ();
        int[] offsets = instance.blockOffsets();
        for (int i = 0; i + 1 < offsets.length; i += 2) {
            world.getBlockAt(ox + offsets[i], GROUND_Y, oz + offsets[i + 1]).setType(Material.AIR);
            world.getBlockAt(ox + offsets[i], GROUND_Y + 1, oz + offsets[i + 1]).setType(Material.AIR);
            world.getBlockAt(ox + offsets[i], GROUND_Y + 2, oz + offsets[i + 1]).setType(Material.AIR);
            world.getBlockAt(ox + offsets[i], GROUND_Y + 3, oz + offsets[i + 1]).setType(Material.AIR);
        }
    }

    public boolean isDefeated() {
        return getSurvivingBuildings().isEmpty();
    }

    /** Returns true if at least one building of the given type is still alive. */
    public boolean isBuildingAlive(BuildingType type) {
        for (BuildingInstance inst : allBuildings) {
            if (inst.type() == type && buildingHp.getOrDefault(inst.id(), 0) > 0) return true;
        }
        return false;
    }
}
