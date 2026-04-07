package io.lossai.clash.service;

import io.lossai.clash.ClashPlugin;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.ChatColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages spawning, commanding, and removal of Barbarian NPCs.
 *
 * On deploy, barbarians target the buildings of whichever player village
 * the attacker is currently standing in — no test base required.
 */
public class BarbManager {

    private static final String BARBARIAN_TEXTURE =
        "ewogICJ0aW1lc3RhbXAiIDogMTc3NTQ2ODY1MzYyOCwKICAicHJvZmlsZUlkIiA6ICI4YmM3MjdlYThjZjA0YWUzYTI4MDVhY2YzNjRjMmQyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJub3RpbnZlbnRpdmUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDIyN2ZlZDhjMTI2MzA2ZDM5Mzg0OTVhYmZhYzFmMWE2NGYzN2FiZjYyZTQ2OWMzYzk2OTdkMDRlZDk4NjA1NCIKICAgIH0KICB9Cn0=";

    private static final String BARBARIAN_SIGNATURE =
        "T6znl3CevzgqiaEvL/MJYXMXXJC4W5Q2WMTrkkUYxWIxvDr8g9zEviUAtcFiUOMwV7PP6AZU2OPAwTBHQbYPt9HD6YRUBQBRMR1X2vEXyDFBi05SzT0RWTNO9IDaVe0eCvue47Y9Kk1dzj19L9jw6dGy58x4ME6oXWk6WCdamtot3O9mcV4Pm1riyLAP1P2GCx4v/mgdrfBFe8ZlTHy56RSeZX7wIZTdRYHTxf//EPRUfplFm40xWQZ5+siCvlO3Q/AOtlWulIGXDHHud42OPvOKc9QgGCnTmMQfGHsCcTztbmZiE4wzfJPwmnZ0o6N/wO0A3e1LKqYSul6g7r+Gx6vQrEbdmPniZluspQQJGp1hX5xkEwaO26gR+asyxdjhH3ueioeKlNPquXn8t0FSHxhivR2HB6775BWVZkxb5QFCo796Y/NaFuQ5QZ9PNMGG1VuLQOoND68pZgaFaZkBvB6HYW3+UeU/LVlSi4j6glBwqPj4ROOhveldm+MqwBB9RmjxVFeHc3IN0MYPpGDke0Xg7jqwuvC0HljuXj7sccYdmEgQUlPD9PuP6v4nyKUbk1s4AmBZeQSLAFWLYzENlaSzxtYM1eFFTsKnAryE3Wh6OpfFsvdUn+DznkQH6VGcHUUPN8m6mq/thGqyjTPIpmRshI8kD7KqlA44bAO0whg=";

    /** NPC id → BarbAI controller */
    private final Map<Integer, BarbAI> activeBarbs = new HashMap<>();

    private final ClashPlugin plugin;
    private VillageManager villageManager;

    public BarbManager(ClashPlugin plugin) {
        this.plugin = plugin;
    }

    public void setVillageManager(VillageManager villageManager) {
        this.villageManager = villageManager;
    }

    /**
     * Validates count, finds the village the player is standing in,
     * then spawns {@code count} barbarian NPCs that attack its buildings.
     */
    public void deploy(Player player, int count) {
        if (count < 1 || count > 50) {
            player.sendMessage(ChatColor.RED + "Count must be between 1 and 50.");
            return;
        }

        // Find the village registry for the world the attacker is in (radius = village playable area)
        VillageBuildingRegistry registry = villageManager.findNearestRegistry(
                player.getLocation(), 200, plugin.getBarbConfig());

        if (registry == null || registry.getSurvivingBuildings().isEmpty()) {
            player.sendMessage(ChatColor.RED + "No village buildings found nearby to attack.");
            return;
        }

        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        for (int i = 0; i < count; i++) {
            NPC npc = npcRegistry.createNPC(EntityType.PLAYER, "Barbarian");
            npc.getOrAddTrait(SkinTrait.class)
               .setSkinPersistent("clash_barbarian", BARBARIAN_SIGNATURE, BARBARIAN_TEXTURE);
            npc.spawn(player.getLocation());

            BarbAI ai = new BarbAI(plugin, npc, registry);
            ai.start();
            activeBarbs.put(npc.getId(), ai);
        }

        player.sendMessage(ChatColor.GREEN + "Deployed " + count + " barbarians.");
    }

    /** Cancels all BarbAI tasks, despawns all NPCs, and clears state. */
    public void clear() {
        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        for (Map.Entry<Integer, BarbAI> entry : activeBarbs.entrySet()) {
            entry.getValue().stop();
            NPC npc = npcRegistry.getById(entry.getKey());
            if (npc != null) npc.destroy();
        }
        activeBarbs.clear();
    }

    public int getActiveBarbCount() {
        return activeBarbs.size();
    }

    /** Validates a deploy count: must be in [1, 50]. Used by property tests. */
    public static boolean isValidDeployCount(int count) {
        return count >= 1 && count <= 50;
    }
}
