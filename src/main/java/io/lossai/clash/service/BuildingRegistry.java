package io.lossai.clash.service;

import io.lossai.clash.model.BuildingInstance;

import java.util.List;

/**
 * Abstraction over any set of attackable buildings.
 * BarbAI talks only to this interface — it has no knowledge of villages,
 * test bases, or any other concrete building source.
 */
public interface BuildingRegistry {

    /**
     * Returns all BuildingInstances that are still alive (HP > 0).
     */
    List<BuildingInstance> getSurvivingBuildings();

    /**
     * Applies {@code damage} HP to the given instance.
     * Implementations are responsible for particles, sounds, and destruction.
     */
    void damageBuilding(BuildingInstance instance, int damage);

    /**
     * Pure HP arithmetic helper — shared by all implementations and tests.
     */
    static int applyDamage(int currentHp, int damage) {
        return Math.max(0, currentHp - damage);
    }

    /**
     * Returns the current HP of the given building, or -1 if not tracked.
     * Implementations should override this to return the actual HP.
     */
    default int getHp(BuildingInstance instance) {
        return -1;
    }
}
