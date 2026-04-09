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
    /** True when this session created the registry (solo attack). False for shared /clash attack. */
    private final boolean ownsRegistry;

    public AttackSession(UUID attackerUuid, TestBaseRegistry registry, int barbCount, boolean ownsRegistry) {
        this.attackerUuid = attackerUuid;
        this.registry = registry;
        this.remainingBarbarians = barbCount;
        this.ownsRegistry = ownsRegistry;
    }

    public UUID getAttackerUuid() { return attackerUuid; }
    public TestBaseRegistry getRegistry() { return registry; }
    public int getRemainingBarbarians() { return remainingBarbarians; }
    public boolean ownsRegistry() { return ownsRegistry; }

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
