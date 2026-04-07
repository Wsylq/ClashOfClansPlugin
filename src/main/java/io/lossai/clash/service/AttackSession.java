package io.lossai.clash.service;

import io.lossai.clash.model.TroopType;

import java.util.UUID;

/**
 * Tracks an active attack session for one player against the test base.
 */
public final class AttackSession {

    private final UUID attackerUuid;
    private final TestBaseRegistry registry;
    private int remainingBarbarians;

    public AttackSession(UUID attackerUuid, TestBaseRegistry registry, int barbCount) {
        this.attackerUuid = attackerUuid;
        this.registry = registry;
        this.remainingBarbarians = barbCount;
    }

    public UUID getAttackerUuid() { return attackerUuid; }
    public TestBaseRegistry getRegistry() { return registry; }
    public int getRemainingBarbarians() { return remainingBarbarians; }

    /** Consumes one barbarian. Returns false if none left. */
    public boolean consumeOne() {
        if (remainingBarbarians <= 0) return false;
        remainingBarbarians--;
        return true;
    }

    public boolean isFinished() {
        return remainingBarbarians <= 0 || registry.isDefeated();
    }
}
