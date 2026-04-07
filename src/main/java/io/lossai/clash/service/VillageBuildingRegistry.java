package io.lossai.clash.service;

import io.lossai.clash.model.BuildingInstance;
import io.lossai.clash.model.BuildingType;
import io.lossai.clash.model.VillageData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BuildingRegistry backed by a real player village.
 *
 * Buildings are derived from VillageData + VillageManager's BUILDING_SLOTS at
 * construction time. HP is tracked in-memory for the lifetime of the raid.
 */
public class VillageBuildingRegistry implements BuildingRegistry {

    private static final int GROUND_Y = 64;

    /** 3×3 footprint offsets (dx, dz) relative to building centre — 9 pairs = 18 ints. */
    private static final int[] FOOTPRINT_3X3;

    static {
        int[] fp = new int[18];
        int idx = 0;
        for (int bx = -1; bx <= 1; bx++) {
            for (int bz = -1; bz <= 1; bz++) {
                fp[idx++] = bx;
                fp[idx++] = bz;
            }
        }
        FOOTPRINT_3X3 = fp;
    }

    private final World world;
    private final BarbConfig config;

    /** All buildings registered at construction time. */
    private final List<BuildingInstance> allBuildings;

    /** Live HP per building id. */
    private final Map<UUID, Integer> buildingHp = new HashMap<>();

    /**
     * @param world   the village world (coc_<uuid-prefix>)
     * @param village the village data (used to read building counts)
     * @param slots   the BUILDING_SLOTS map from VillageManager (passed in to avoid coupling)
     * @param config  barbarian config for initial HP values
     */
    public VillageBuildingRegistry(World world,
                                   VillageData village,
                                   Map<BuildingType, List<int[]>> slots,
                                   BarbConfig config) {
        this.world = world;
        this.config = config;
        this.allBuildings = buildInstances(village, slots);
    }

    private List<BuildingInstance> buildInstances(VillageData village,
                                                   Map<BuildingType, List<int[]>> slots) {
        List<BuildingInstance> list = new ArrayList<>();

        for (Map.Entry<BuildingType, List<int[]>> entry : slots.entrySet()) {
            BuildingType type = entry.getKey();
            List<int[]> typeSlots = entry.getValue();
            int count = Math.min(village.getBuildingCount(type), typeSlots.size());
            int initialHp = config.getHp(type);

            for (int i = 0; i < count; i++) {
                int[] slot = typeSlots.get(i);
                Location origin = new Location(world, slot[0], GROUND_Y, slot[1]);
                BuildingInstance inst = new BuildingInstance(
                        UUID.randomUUID(), type, origin, FOOTPRINT_3X3.clone());
                list.add(inst);
                buildingHp.put(inst.id(), initialHp);
            }
        }

        return Collections.unmodifiableList(list);
    }

    @Override
    public List<BuildingInstance> getSurvivingBuildings() {
        List<BuildingInstance> survivors = new ArrayList<>();
        for (BuildingInstance inst : allBuildings) {
            Integer hp = buildingHp.get(inst.id());
            if (hp != null && hp > 0) {
                survivors.add(inst);
            }
        }
        return survivors;
    }

    @Override
    public void damageBuilding(BuildingInstance instance, int damage) {
        Integer current = buildingHp.get(instance.id());
        if (current == null) return; // already destroyed

        int newHp = BuildingRegistry.applyDamage(current, damage);
        buildingHp.put(instance.id(), newHp);

        // CRIT particles at building origin
        Location origin = instance.origin();
        if (origin.getWorld() != null) {
            origin.getWorld().spawnParticle(
                    Particle.CRIT,
                    origin.clone().add(0.5, 1.0, 0.5),
                    8, 0.3, 0.3, 0.3, 0.1);
        }

        if (newHp <= 0) {
            destroyBuilding(instance);
        }
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
        int ox = (int) origin.getX();
        int oz = (int) origin.getZ();
        int[] offsets = instance.blockOffsets();
        for (int i = 0; i + 1 < offsets.length; i += 2) {
            world.getBlockAt(ox + offsets[i], GROUND_Y, oz + offsets[i + 1])
                 .setType(Material.AIR);
        }
    }

    /** Returns the current HP of a building, or -1 if not tracked. */
    public int getBuildingHp(BuildingInstance instance) {
        return buildingHp.getOrDefault(instance.id(), -1);
    }
}
