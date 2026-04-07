package io.lossai.clash.service;

import io.lossai.clash.ClashPlugin;
import io.lossai.clash.model.BuildingInstance;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ---------------------------------------------------------------------------
// TargetKey — sealed hierarchy identifying a health-bar target
// ---------------------------------------------------------------------------

sealed interface TargetKey permits BuildingKey, TroopKey {}

record BuildingKey(UUID buildingId) implements TargetKey {}

record TroopKey(int npcId) implements TargetKey {}

// ---------------------------------------------------------------------------
// HealthBarEntry — mutable so decay counter can be updated
// ---------------------------------------------------------------------------

final class HealthBarEntry {

    private final ArmorStand stand;
    private final UUID sessionId;   // nullable
    private int ticksSinceLastDamage;

    HealthBarEntry(ArmorStand stand, UUID sessionId) {
        this.stand = stand;
        this.sessionId = sessionId;
        this.ticksSinceLastDamage = 0;
    }

    ArmorStand stand()         { return stand; }
    UUID sessionId()           { return sessionId; }
    int ticksSinceLastDamage() { return ticksSinceLastDamage; }
    void resetDecay()          { ticksSinceLastDamage = 0; }
    void incrementDecay()      { ticksSinceLastDamage++; }
}

// ---------------------------------------------------------------------------
// HealthBarManager — public API
// ---------------------------------------------------------------------------

public final class HealthBarManager {

    private final ClashPlugin plugin;
    private final HealthBarConfig config;

    private final Map<TargetKey, HealthBarEntry> displays = new HashMap<>();
    private final Map<TargetKey, Integer> maxHpMap = new HashMap<>();
    private final Map<TroopKey, NPC> npcMap = new HashMap<>();

    public HealthBarManager(ClashPlugin plugin, HealthBarConfig config) {
        this.plugin = plugin;
        this.config = config;
        long interval = config.getDisplayIntervalTicks();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    // -----------------------------------------------------------------------
    // register
    // -----------------------------------------------------------------------

    public void register(BuildingInstance building, int maxHp, UUID sessionId) {
        BuildingKey key = new BuildingKey(building.id());
        if (displays.containsKey(key)) return;

        Location loc = building.origin().clone().add(0.5, 2.5, 0.5);
        ArmorStand stand = spawnStand(loc, buildBarComponent(maxHp, maxHp));
        displays.put(key, new HealthBarEntry(stand, sessionId));
        maxHpMap.put(key, maxHp);
    }

    public void register(NPC npc, int maxHp, UUID sessionId) {
        TroopKey key = new TroopKey(npc.getId());
        if (displays.containsKey(key)) return;

        // Player NPC height ~1.8 blocks; place name tag just above head
        Location loc = npc.getEntity().getLocation().clone().add(0, 2.1, 0);
        ArmorStand stand = spawnStand(loc, buildBarComponent(maxHp, maxHp));
        displays.put(key, new HealthBarEntry(stand, sessionId));
        maxHpMap.put(key, maxHp);
        npcMap.put(key, npc);
    }

    // -----------------------------------------------------------------------
    // damage
    // -----------------------------------------------------------------------

    public void damage(BuildingInstance building, int currentHp, int maxHp) {
        BuildingKey key = new BuildingKey(building.id());
        HealthBarEntry entry = displays.get(key);
        if (entry == null) return;
        applyBar(entry, currentHp, maxHp);
    }

    public void damage(NPC npc, int currentHp, int maxHp) {
        TroopKey key = new TroopKey(npc.getId());
        HealthBarEntry entry = displays.get(key);
        if (entry == null) return;
        applyBar(entry, currentHp, maxHp);
    }

    /** Called from EntityDamageEvent — looks up NPC by its Bukkit entity UUID. */
    public void damageByEntityId(UUID entityUuid, double currentHp, double maxHp) {
        for (Map.Entry<TroopKey, NPC> e : npcMap.entrySet()) {
            NPC npc = e.getValue();
            if (npc.isSpawned() && npc.getEntity() != null
                    && npc.getEntity().getUniqueId().equals(entityUuid)) {
                damage(npc, (int) Math.ceil(currentHp), (int) Math.ceil(maxHp));
                return;
            }
        }
    }

    // -----------------------------------------------------------------------
    // remove
    // -----------------------------------------------------------------------

    public void remove(BuildingInstance building) {
        removeByKey(new BuildingKey(building.id()));
    }

    public void remove(NPC npc) {
        TroopKey key = new TroopKey(npc.getId());
        removeByKey(key);
        npcMap.remove(key);
    }

    // -----------------------------------------------------------------------
    // clearSession / shutdown
    // -----------------------------------------------------------------------

    public void clearSession(UUID sessionId) {
        if (sessionId == null) return;
        List<TargetKey> toRemove = new ArrayList<>();
        for (Map.Entry<TargetKey, HealthBarEntry> e : displays.entrySet()) {
            if (sessionId.equals(e.getValue().sessionId())) toRemove.add(e.getKey());
        }
        for (TargetKey key : toRemove) {
            removeByKey(key);
            if (key instanceof TroopKey tk) npcMap.remove(tk);
        }
    }

    public void shutdown() {
        List<TargetKey> keys = new ArrayList<>(displays.keySet());
        for (TargetKey key : keys) {
            removeByKey(key);
            if (key instanceof TroopKey tk) npcMap.remove(tk);
        }
    }

    // -----------------------------------------------------------------------
    // Tick
    // -----------------------------------------------------------------------

    private void tick() {
        List<Map.Entry<TargetKey, HealthBarEntry>> snapshot = new ArrayList<>(displays.entrySet());
        for (Map.Entry<TargetKey, HealthBarEntry> e : snapshot) {
            TargetKey key = e.getKey();
            HealthBarEntry entry = e.getValue();

            if (!entry.stand().isValid()) {
                displays.remove(key);
                maxHpMap.remove(key);
                if (key instanceof TroopKey tk) npcMap.remove(tk);
                continue;
            }

            if (key instanceof TroopKey tk) {
                NPC npc = npcMap.get(tk);
                if (npc != null && npc.isSpawned() && npc.getEntity() != null) {
                    entry.stand().teleport(npc.getEntity().getLocation().clone().add(0, 2.1, 0));
                }
            }

            entry.incrementDecay();
            if (HealthBarRenderer.isDecayed(entry.ticksSinceLastDamage(), config.getDecayDelayTicks())) {
                // Hide by making the name invisible rather than empty string
                entry.stand().setCustomNameVisible(false);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void applyBar(HealthBarEntry entry, int currentHp, int maxHp) {
        entry.stand().customName(buildBarComponent(currentHp, maxHp));
        entry.stand().setCustomNameVisible(true);
        entry.resetDecay();
    }

    /**
     * Builds an Adventure Component for the health bar using colored block characters.
     * Uses Adventure API directly so Paper 1.21 renders it correctly.
     */
    private Component buildBarComponent(int currentHp, int maxHp) {
        int barLength = config.getBarLength();
        double ratio = (maxHp <= 0) ? 0.0 : Math.max(0.0, Math.min(1.0, (double) currentHp / maxHp));
        int filled = (int) Math.round(ratio * barLength);
        int empty = barLength - filled;

        TextColor color;
        if (ratio > 0.50) color = NamedTextColor.GREEN;
        else if (ratio > 0.25) color = NamedTextColor.YELLOW;
        else color = NamedTextColor.RED;

        String bar = "█".repeat(filled) + ChatColor.DARK_GRAY + "░".repeat(empty);
        return Component.text(bar).color(color);
    }

    private ArmorStand spawnStand(Location location, Component name) {
        return location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setSmall(true);
            stand.setInvulnerable(true);
            stand.setMarker(true);          // no hitbox at all — can't be accidentally selected
            stand.customName(name);
            stand.setCustomNameVisible(true);
        });
    }

    private void removeByKey(TargetKey key) {
        HealthBarEntry entry = displays.remove(key);
        maxHpMap.remove(key);
        if (entry != null && entry.stand().isValid()) {
            entry.stand().remove();
        }
    }
}
