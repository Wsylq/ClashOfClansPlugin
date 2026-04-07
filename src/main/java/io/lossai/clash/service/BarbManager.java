package io.lossai.clash.service;

import io.lossai.clash.ClashPlugin;
import io.lossai.clash.model.TroopType;
import io.lossai.clash.model.VillageData;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages barbarian NPCs and attack sessions.
 */
public class BarbManager {

    private static final String BARBARIAN_TEXTURE =
        "ewogICJ0aW1lc3RhbXAiIDogMTc3NTQ2ODY1MzYyOCwKICAicHJvZmlsZUlkIiA6ICI4YmM3MjdlYThjZjA0YWUzYTI4MDVhY2YzNjRjMmQyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJub3RpbnZlbnRpdmUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDIyN2ZlZDhjMTI2MzA2ZDM5Mzg0OTVhYmZhYzFmMWE2NGYzN2FiZjYyZTQ2OWMzYzk2OTdkMDRlZDk4NjA1NCIKICAgIH0KICB9Cn0=";

    private static final String BARBARIAN_SIGNATURE =
        "T6znl3CevzgqiaEvL/MJYXMXXJC4W5Q2WMTrkkUYxWIxvDr8g9zEviUAtcFiUOMwV7PP6AZU2OPAwTBHQbYPt9HD6YRUBQBRMR1X2vEXyDFBi05SzT0RWTNO9IDaVe0eCvue47Y9Kk1dzj19L9jw6dGy58x4ME6oXWk6WCdamtot3O9mcV4Pm1riyLAP1P2GCx4v/mgdrfBFe8ZlTHy56RSeZX7wIZTdRYHTxf//EPRUfplFm40xWQZ5+siCvlO3Q/AOtlWulIGXDHHud42OPvOKc9QgGCnTmMQfGHsCcTztbmZiE4wzfJPwmnZ0o6N/wO0A3e1LKqYSul6g7r+Gx6vQrEbdmPniZluspQQJGp1hX5xkEwaO26gR+asyxdjhH3ueioeKlNPquXn8t0FSHxhivR2HB6775BWVZkxb5QFCo796Y/NaFuQ5QZ9PNMGG1VuLQOoND68pZgaFaZkBvB6HYW3+UeU/LVlSi4j6glBwqPj4ROOhveldm+MqwBB9RmjxVFeHc3IN0MYPpGDke0Xg7jqwuvC0HljuXj7sccYdmEgQUlPD9PuP6v4nyKUbk1s4AmBZeQSLAFWLYzENlaSzxtYM1eFFTsKnAryE3Wh6OpfFsvdUn+DznkQH6VGcHUUPN8m6mq/thGqyjTPIpmRshI8kD7KqlA44bAO0whg=";

    /** NBT key stored in item lore to identify the barbarian head deploy item */
    public static final String BARB_HEAD_LORE_TAG = "§8[clash:barb_deploy]";

    /** NPC id → BarbAI controller */
    private final Map<Integer, BarbAI> activeBarbs = new HashMap<>();

    /** player UUID → active attack session */
    private final Map<UUID, AttackSession> sessions = new HashMap<>();

    /** NPC ids deployed in the current session per player — for defeat detection */
    private final Map<UUID, java.util.Set<Integer>> sessionNpcIds = new HashMap<>();

    private final ClashPlugin plugin;
    private VillageManager villageManager;
    private TestBaseManager testBaseManager;
    private HealthBarManager healthBarManager;

    /** Max HP for a barbarian troop (standard CoC value). */
    private static final int BARBARIAN_MAX_HP = 45;

    public BarbManager(ClashPlugin plugin) {
        this.plugin = plugin;
        // Start a repeating task to monitor active sessions
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickSessions, 20L, 20L);
    }

    public void setVillageManager(VillageManager villageManager) {
        this.villageManager = villageManager;
    }

    public void setTestBaseManager(TestBaseManager testBaseManager) {
        this.testBaseManager = testBaseManager;
    }

    public void setHealthBarManager(HealthBarManager healthBarManager) {
        this.healthBarManager = healthBarManager;
    }

    // -----------------------------------------------------------------------
    // Attack session management
    // -----------------------------------------------------------------------

    /**
     * Starts an attack session against the test base.
     * Checks that the player has barbarians trained and is NOT in their own village.
     */
    public void startAttackSession(Player player) {
        VillageData village = villageManager.getVillage(player.getUniqueId());
        if (village == null) {
            player.sendMessage(ChatColor.RED + "Village not initialized.");
            return;
        }

        int barbCount = village.getTroopCount(TroopType.BARBARIAN);
        if (barbCount <= 0) {
            player.sendMessage(ChatColor.RED + "You have no barbarians trained. Train some with /clash train barbarian <amount>.");
            return;
        }

        if (testBaseManager == null) {
            player.sendMessage(ChatColor.RED + "Test base system is not available.");
            return;
        }

        // End any existing session
        if (sessions.containsKey(player.getUniqueId())) {
            endSession(player);
        }

        TestBaseRegistry registry = testBaseManager.createFreshRegistry(plugin.getBarbConfig());
        if (registry == null) {
            player.sendMessage(ChatColor.RED + "Could not load test base world.");
            return;
        }

        // Wire health bar manager into the registry so buildings show health bars
        if (healthBarManager != null) {
            registry.setHealthBarManager(healthBarManager);
            registry.setSessionId(player.getUniqueId());
        }

        // Deduct troops from village
        village.takeTroops(TroopType.BARBARIAN, barbCount);

        AttackSession session = new AttackSession(player.getUniqueId(), registry, barbCount);
        sessions.put(player.getUniqueId(), session);
        sessionNpcIds.put(player.getUniqueId(), new java.util.HashSet<>());
        testBaseManager.setActiveRegistry(player.getUniqueId(), registry);

        // Teleport player to test base
        org.bukkit.World testWorld = testBaseManager.getOrCreateWorld();
        if (testWorld != null) {
            player.teleportAsync(new org.bukkit.Location(testWorld, 0.5, TestBaseManager.getGroundY() + 2.0, -18.5, 0f, 0f));
        }

        // Give barbarian head item with count = barbCount
        giveBarbHeadItem(player, barbCount);

        player.sendMessage(ChatColor.GREEN + "Attack started! You have " + barbCount + " barbarians.");
        player.sendMessage(ChatColor.YELLOW + "Right-click with the Barbarian Head to deploy one at a time.");
    }

    /**
     * Joins an already-created shared attack session (registry created externally).
     * Used when multiple troop types attack together via /clash attack.
     * Returns true if the session was registered successfully.
     */
    public boolean joinAttackSession(Player player, TestBaseRegistry registry) {
        VillageData village = villageManager.getVillage(player.getUniqueId());
        if (village == null) return false;

        int barbCount = village.getTroopCount(TroopType.BARBARIAN);
        if (barbCount <= 0) return false;

        if (sessions.containsKey(player.getUniqueId())) {
            endSession(player);
        }

        village.takeTroops(TroopType.BARBARIAN, barbCount);

        AttackSession session = new AttackSession(player.getUniqueId(), registry, barbCount);
        sessions.put(player.getUniqueId(), session);
        sessionNpcIds.put(player.getUniqueId(), new java.util.HashSet<>());

        giveBarbHeadItem(player, barbCount);
        return true;
    }

    /**
     * Deploys one barbarian from the session at the player's current location.
     */
    public void deployOneFromSession(Player player, AttackSession session) {
        if (!session.consumeOne()) {
            player.sendMessage(ChatColor.RED + "No barbarians left.");
            endSession(player);
            return;
        }

        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        NPC npc = npcRegistry.createNPC(EntityType.PLAYER, "Barbarian");
        npc.getOrAddTrait(SkinTrait.class)
           .setSkinPersistent("clash_barbarian", BARBARIAN_SIGNATURE, BARBARIAN_TEXTURE);
        // Allow external teleportation (needed for our tick-based movement)
        npc.setProtected(false);
        npc.spawn(player.getLocation());

        BarbAI ai = new BarbAI(plugin, npc, session.getRegistry());
        ai.start();
        activeBarbs.put(npc.getId(), ai);

        if (healthBarManager != null) {
            ai.setHealthBarManager(healthBarManager);
            healthBarManager.register(npc, BARBARIAN_MAX_HP, player.getUniqueId());
        }

        // Track this NPC for defeat detection
        java.util.Set<Integer> ids = sessionNpcIds.get(player.getUniqueId());
        if (ids != null) ids.add(npc.getId());

        // Update item count in hand
        int remaining = session.getRemainingBarbarians();
        updateBarbHeadCount(player, remaining);

        if (remaining > 0) {
            player.sendMessage(ChatColor.GRAY + "Deployed 1 barbarian. " + remaining + " remaining.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "All barbarians deployed! They will continue fighting.");
        }
    }

    /**
     * Ends the attack session, removes the barbarian head item, and clears NPCs.
     */
    public void endSession(Player player) {
        UUID playerUuid = player.getUniqueId();
        sessions.remove(playerUuid);
        sessionNpcIds.remove(playerUuid);
        if (testBaseManager != null) testBaseManager.setActiveRegistry(playerUuid, null);
        if (healthBarManager != null) healthBarManager.clearSession(playerUuid);
        removeBarbHeadItem(player);
        player.sendMessage(ChatColor.GOLD + "Attack session ended.");
    }

    public AttackSession getSession(UUID playerUuid) {
        return sessions.get(playerUuid);
    }

    /**
     * Called every second to check if any sessions should auto-end (all buildings destroyed).
     */
    private void tickSessions() {
        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        for (Map.Entry<UUID, AttackSession> entry : new HashMap<>(sessions).entrySet()) {
            UUID uuid = entry.getKey();
            AttackSession session = entry.getValue();
            org.bukkit.entity.Player player = plugin.getServer().getPlayer(uuid);

            if (player == null || !player.isOnline()) {
                sessions.remove(uuid);
                sessionNpcIds.remove(uuid);
                continue;
            }

            // Victory: all buildings destroyed
            if (session.getRegistry().isDefeated()) {
                player.sendMessage(ChatColor.GREEN + "★ Victory! All buildings destroyed! ★");
                endSession(player);
                continue;
            }

            // Defeat: no barbs left to deploy AND all deployed barbs are dead/despawned
            if (session.getRemainingBarbarians() <= 0) {
                java.util.Set<Integer> ids = sessionNpcIds.getOrDefault(uuid, java.util.Collections.emptySet());
                boolean anyAlive = false;
                for (int id : ids) {
                    NPC npc = npcRegistry.getById(id);
                    if (npc != null && npc.isSpawned()) { anyAlive = true; break; }
                }
                if (!anyAlive) {
                    player.sendMessage(ChatColor.RED + "✗ Defeat! All your barbarians have fallen.");
                    endSession(player);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Legacy deploy (admin command) — kept for /barbarian deploy
    // -----------------------------------------------------------------------

    /**
     * Admin deploy: spawns barbarians targeting the nearest village.
     * Prevents deploying into the attacker's own village.
     */
    public void deploy(Player player, int count) {
        if (count < 1 || count > 50) {
            player.sendMessage(ChatColor.RED + "Count must be between 1 and 50.");
            return;
        }

        VillageBuildingRegistry registry = villageManager.findNearestRegistry(
                player.getLocation(), 200, plugin.getBarbConfig());

        if (registry == null || registry.getSurvivingBuildings().isEmpty()) {
            player.sendMessage(ChatColor.RED + "No village buildings found nearby to attack.");
            return;
        }

        // Prevent self-attack
        UUID worldOwner = villageManager.getWorldOwner(player.getWorld());
        if (player.getUniqueId().equals(worldOwner)) {
            player.sendMessage(ChatColor.RED + "You cannot deploy barbarians into your own village.");
            return;
        }

        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        for (int i = 0; i < count; i++) {
            NPC npc = npcRegistry.createNPC(EntityType.PLAYER, "Barbarian");
            npc.getOrAddTrait(SkinTrait.class)
               .setSkinPersistent("clash_barbarian", BARBARIAN_SIGNATURE, BARBARIAN_TEXTURE);
            npc.setProtected(false);
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

    public static boolean isValidDeployCount(int count) {
        return count >= 1 && count <= 50;
    }

    // -----------------------------------------------------------------------
    // Barbarian Head item helpers
    // -----------------------------------------------------------------------

    /**
     * Creates the barbarian head deploy item with the given count as item amount.
     * A hidden lore tag is used to identify it.
     */
    public static ItemStack createBarbHeadItem(int count) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, Math.max(1, Math.min(64, count)));
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setDisplayName(ChatColor.GOLD + "Barbarian");
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(ChatColor.GRAY + "Right-click to deploy");
            lore.add(BARB_HEAD_LORE_TAG); // hidden identifier tag
            skullMeta.setLore(lore);

            // Apply barbarian skin texture
            try {
                PlayerProfile profile = org.bukkit.Bukkit.createPlayerProfile(UUID.randomUUID(), "clash_barb");
                PlayerTextures textures = profile.getTextures();
                // Use a known barbarian skin URL
                textures.setSkin(new URL("http://textures.minecraft.net/texture/d227fed8c126306d3938495abfac1f1a64f37abf62e469c3c9697d04ed986054"));
                profile.setTextures(textures);
                skullMeta.setOwnerProfile(profile);
            } catch (MalformedURLException ignored) {
                // Skin URL failed — item will still work, just show default head
            }

            item.setItemMeta(skullMeta);
        }
        return item;
    }

    /** Returns true if the given item is the barbarian head deploy item. */
    public static boolean isBarbHeadItem(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        java.util.List<String> lore = meta.getLore();
        return lore != null && lore.contains(BARB_HEAD_LORE_TAG);
    }

    private void giveBarbHeadItem(Player player, int count) {
        // Remove any existing barb head first
        removeBarbHeadItem(player);
        ItemStack item = createBarbHeadItem(count);
        player.getInventory().addItem(item);
    }

    private void updateBarbHeadCount(Player player, int count) {
        if (count <= 0) {
            removeBarbHeadItem(player);
            return;
        }
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isBarbHeadItem(item)) {
                item.setAmount(Math.min(64, count));
                return;
            }
        }
    }

    private void removeBarbHeadItem(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isBarbHeadItem(item)) {
                player.getInventory().setItem(i, null);
                return;
            }
        }
    }
}
