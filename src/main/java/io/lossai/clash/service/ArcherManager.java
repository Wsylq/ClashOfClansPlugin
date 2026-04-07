package io.lossai.clash.service;

import io.lossai.clash.ClashPlugin;
import io.lossai.clash.model.TroopType;
import io.lossai.clash.model.VillageData;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Manages archer NPCs and attack sessions.
 * Mirrors BarbManager's structure closely.
 */
public class ArcherManager {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Max HP for an archer troop. */
    static final int ARCHER_MAX_HP = 65;

    /** Hidden lore tag used to identify the Archer Head deploy item. */
    static final String ARCHER_HEAD_LORE_TAG = "§8[clash:archer_deploy]";

    /**
     * Archer skin username — Citizens will fetch this player's skin asynchronously.
     * Replace with any Minecraft username that wears the desired archer skin.
     */
    private static final String ARCHER_SKIN_NAME = "BarbarianHut";

    /**
     * Archer texture URL for the deploy head item skull.
     * Currently reuses the barbarian texture as a placeholder.
     */
    private static final String ARCHER_TEXTURE_URL =
        "https://textures.minecraft.net/texture/b58226c8c7a4da39e44aa4da6ccf514312f003c4db58959873bdbafaa1964ba";

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    /** npcId → ArcherAI controller */
    private final Map<Integer, ArcherAI> activeArchers = new HashMap<>();

    /** playerId → active attack session */
    private final Map<UUID, AttackSession> sessions = new HashMap<>();

    /** playerId → set of NPC ids deployed in the current session (for defeat detection) */
    private final Map<UUID, Set<Integer>> sessionNpcIds = new HashMap<>();

    /** playerId → set of idle NPC ids (for idle visual management) */
    private final Map<UUID, Set<Integer>> idleNpcIds = new HashMap<>();

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final Plugin plugin;
    private final ArcherConfig config;
    private final VillageManager villageManager;
    private final TestBaseManager testBaseManager;
    private final HealthBarManager healthBarManager;
    private final BuildingRegistry buildingRegistry;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public ArcherManager(Plugin plugin, ArcherConfig config, VillageManager villageManager,
                         TestBaseManager testBaseManager, HealthBarManager healthBarManager,
                         BuildingRegistry buildingRegistry) {
        this.plugin = plugin;
        this.config = config;
        this.villageManager = villageManager;
        this.testBaseManager = testBaseManager;
        this.healthBarManager = healthBarManager;
        this.buildingRegistry = buildingRegistry;

        // Start a repeating task to monitor active sessions (victory/defeat)
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickSessions, 20L, 20L);
    }

    // -----------------------------------------------------------------------
    // Attack session management
    // -----------------------------------------------------------------------

    /**
     * Starts an attack session for the player using their trained archers.
     * Gives the player the Archer_Head_Item. If 0 archers, sends a red error.
     */
    public void startAttackSession(Player player) {
        VillageData village = villageManager.getVillage(player.getUniqueId());
        if (village == null) {
            player.sendMessage(ChatColor.RED + "Village not initialized.");
            return;
        }

        int archerCount = village.getTroopCount(TroopType.ARCHER);
        if (archerCount <= 0) {
            player.sendMessage(ChatColor.RED + "You have no archers trained. Train some with /clash train archer <amount>.");
            return;
        }

        if (testBaseManager == null) {
            player.sendMessage(ChatColor.RED + "Test base system is not available.");
            return;
        }

        // End any existing session first
        if (sessions.containsKey(player.getUniqueId())) {
            endSession(player);
        }

        BarbConfig barbConfig = (plugin instanceof ClashPlugin cp) ? cp.getBarbConfig() : null;
        TestBaseRegistry registry = testBaseManager.createFreshRegistry(barbConfig);
        if (registry == null) {
            player.sendMessage(ChatColor.RED + "Could not load test base world.");
            return;
        }

        // Wire health bar manager into the registry
        if (healthBarManager != null) {
            registry.setHealthBarManager(healthBarManager);
            registry.setSessionId(player.getUniqueId());
        }

        // Deduct troops from village
        village.takeTroops(TroopType.ARCHER, archerCount);

        AttackSession session = new AttackSession(player.getUniqueId(), registry, archerCount);
        sessions.put(player.getUniqueId(), session);
        sessionNpcIds.put(player.getUniqueId(), new HashSet<>());
        testBaseManager.setActiveRegistry(player.getUniqueId(), registry);

        // Teleport player to test base
        World testWorld = testBaseManager.getOrCreateWorld();
        if (testWorld != null) {
            player.teleportAsync(new Location(testWorld, 0.5, TestBaseManager.getGroundY() + 2.0, -18.5, 0f, 0f));
        }

        // Give archer head item
        giveArcherHeadItem(player, archerCount);

        player.sendMessage(ChatColor.GREEN + "Attack started! You have " + archerCount + " archers.");
        player.sendMessage(ChatColor.YELLOW + "Right-click with the Archer Head to deploy one at a time.");
    }

    /**
     * Joins an already-created shared attack session (registry created externally).
     * Used when multiple troop types attack together via /clash attack.
     * Returns true if the session was registered successfully.
     */
    public boolean joinAttackSession(Player player, TestBaseRegistry registry) {
        VillageData village = villageManager.getVillage(player.getUniqueId());
        if (village == null) return false;

        int archerCount = village.getTroopCount(TroopType.ARCHER);
        if (archerCount <= 0) return false;

        if (sessions.containsKey(player.getUniqueId())) {
            endSession(player);
        }

        village.takeTroops(TroopType.ARCHER, archerCount);

        AttackSession session = new AttackSession(player.getUniqueId(), registry, archerCount);
        sessions.put(player.getUniqueId(), session);
        sessionNpcIds.put(player.getUniqueId(), new HashSet<>());

        giveArcherHeadItem(player, archerCount);
        return true;
    }

    /**
     * Deploys one archer from the session at the player's current location.
     * If outside the test-base world, cancels and sends a red error.
     */
    public void deployOneFromSession(Player player, AttackSession session) {
        // Guard: must be in the test base world
        if (!player.getWorld().getName().equals(TestBaseManager.WORLD_NAME)) {
            player.sendMessage(ChatColor.RED + "You can only deploy archers in the test base.");
            return;
        }

        if (!session.consumeOne()) {
            player.sendMessage(ChatColor.RED + "No archers left.");
            endSession(player);
            return;
        }

        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        NPC npc = npcRegistry.createNPC(EntityType.PLAYER, "Archer");

        // Apply archer skin
        npc.getOrAddTrait(SkinTrait.class).setSkinName(ARCHER_SKIN_NAME);

        // Allow external teleportation and damage
        npc.setProtected(false);
        npc.spawn(player.getLocation());

        // Equip BOW in main hand
        if (npc.getEntity() instanceof org.bukkit.entity.Player npcPlayer) {
            npcPlayer.getInventory().setItemInMainHand(new ItemStack(Material.BOW));
        }

        // Register with HealthBarManager
        if (healthBarManager != null) {
            healthBarManager.register(npc, ARCHER_MAX_HP, player.getUniqueId());
        }

        // Create and start ArcherAI
        ArcherAI ai = new ArcherAI(npc, session.getRegistry(), config, plugin,
                                   player.getUniqueId(), this);
        if (healthBarManager != null) {
            ai.setHealthBarManager(healthBarManager);
        }
        ai.start();
        activeArchers.put(npc.getId(), ai);

        // Track NPC id for defeat detection
        Set<Integer> ids = sessionNpcIds.get(player.getUniqueId());
        if (ids != null) ids.add(npc.getId());

        // Update item stack amount
        int remaining = session.getRemainingBarbarians();
        updateArcherHeadCount(player, remaining);

        if (remaining > 0) {
            player.sendMessage(ChatColor.GRAY + "Deployed 1 archer. " + remaining + " remaining.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "All archers deployed! They will continue fighting.");
        }
    }

    /**
     * Ends the attack session: removes Archer_Head_Item, clears session NPC ids,
     * calls HealthBarManager.clearSession.
     */
    public void endSession(Player player) {
        UUID playerUuid = player.getUniqueId();
        sessions.remove(playerUuid);
        sessionNpcIds.remove(playerUuid);
        if (testBaseManager != null) testBaseManager.setActiveRegistry(playerUuid, null);
        if (healthBarManager != null) healthBarManager.clearSession(playerUuid);
        removeArcherHeadItem(player);
    }

    /**
     * Returns the active AttackSession for the given player, or null.
     */
    public AttackSession getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    // -----------------------------------------------------------------------
    // Session tick — victory / defeat detection
    // -----------------------------------------------------------------------

    private void tickSessions() {
        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        for (Map.Entry<UUID, AttackSession> entry : new HashMap<>(sessions).entrySet()) {
            UUID uuid = entry.getKey();
            AttackSession session = entry.getValue();
            Player player = plugin.getServer().getPlayer(uuid);

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

            // Defeat: no archers left to deploy AND all deployed archers are dead/despawned
            if (session.getRemainingBarbarians() <= 0) {
                Set<Integer> ids = sessionNpcIds.getOrDefault(uuid, Collections.emptySet());
                boolean anyAlive = false;
                for (int id : ids) {
                    NPC npc = npcRegistry.getById(id);
                    if (npc != null && npc.isSpawned()) { anyAlive = true; break; }
                }
                if (!anyAlive) {
                    player.sendMessage(ChatColor.RED + "✗ Defeat! All your archers have fallen.");
                    endSession(player);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Called by ArcherAI on despawn
    // -----------------------------------------------------------------------

    /**
     * Called by ArcherAI when an archer NPC despawns (no targets remain).
     * Checks victory/defeat conditions.
     */
    public void onArcherDespawn(NPC npc, UUID sessionOwner) {
        if (sessionOwner == null) return;

        AttackSession session = sessions.get(sessionOwner);
        if (session == null) return;

        Player player = plugin.getServer().getPlayer(sessionOwner);
        if (player == null || !player.isOnline()) return;

        // Victory check
        if (session.getRegistry().isDefeated()) {
            player.sendMessage(ChatColor.GREEN + "★ Victory! All buildings destroyed! ★");
            endSession(player);
            return;
        }

        // Defeat check: remaining == 0 and this was the last alive NPC
        if (session.getRemainingBarbarians() <= 0) {
            Set<Integer> ids = sessionNpcIds.getOrDefault(sessionOwner, Collections.emptySet());
            // Remove the despawned NPC from tracking
            ids.remove(npc.getId());
            boolean anyAlive = false;
            NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
            for (int id : ids) {
                NPC other = npcRegistry.getById(id);
                if (other != null && other.isSpawned()) { anyAlive = true; break; }
            }
            if (!anyAlive) {
                player.sendMessage(ChatColor.RED + "✗ Defeat! All your archers have fallen.");
                endSession(player);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Idle visuals
    // -----------------------------------------------------------------------

    /**
     * Spawns up to 3 stationary Archer NPCs within 3 blocks of armyCampOrigin.
     * Uses same skin/bow. npc.setProtected(true). NOT registered with HealthBarManager.
     */
    public void spawnIdleArchers(World world, VillageData village, Location armyCampOrigin) {
        UUID playerId = village.getPlayerId();
        // Despawn existing idle archers first to prevent duplicates
        despawnIdleArchers(playerId);

        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        Set<Integer> ids = new HashSet<>();
        Random random = new Random();

        int count = Math.min(3, village.getTroopCount(TroopType.ARCHER));
        for (int i = 0; i < count; i++) {
            // Random offset within 3 blocks
            double offsetX = (random.nextDouble() * 6.0) - 3.0;
            double offsetZ = (random.nextDouble() * 6.0) - 3.0;
            Location spawnLoc = armyCampOrigin.clone().add(offsetX, 0, offsetZ);

            NPC npc = npcRegistry.createNPC(EntityType.PLAYER, "Archer");
            npc.getOrAddTrait(SkinTrait.class).setSkinName(ARCHER_SKIN_NAME);
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
            npc.data().set(NPC.Metadata.COLLIDABLE, false);
            npc.setProtected(true);
            npc.spawn(spawnLoc);

            // Equip BOW in main hand
            if (npc.getEntity() instanceof org.bukkit.entity.Player npcPlayer) {
                npcPlayer.getInventory().setItemInMainHand(new ItemStack(Material.BOW));
            }

            // Random wander within camp bounds — same pattern as barbarian idle visuals
            final double campX = armyCampOrigin.getX();
            final double campZ = armyCampOrigin.getZ();
            final NPC finalNpc = npc;
            plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
                if (finalNpc.isSpawned()) {
                    double wx = campX + (random.nextDouble() * 6.0) - 3.0;
                    double wz = campZ + (random.nextDouble() * 6.0) - 3.0;
                    finalNpc.getNavigator().setTarget(new Location(world, wx, armyCampOrigin.getY(), wz));
                } else {
                    task.cancel();
                }
            }, 20L, 60L);

            ids.add(npc.getId());
        }

        idleNpcIds.put(playerId, ids);
    }

    /**
     * Despawns all idle Archer NPCs for the given player.
     */
    public void despawnIdleArchers(UUID playerId) {
        Set<Integer> ids = idleNpcIds.remove(playerId);
        if (ids == null) return;
        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();
        for (int id : ids) {
            NPC npc = npcRegistry.getById(id);
            if (npc != null) npc.destroy();
        }
    }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /**
     * Returns true iff count is in [1, 50].
     */
    public static boolean isValidDeployCount(int count) {
        return count >= 1 && count <= 50;
    }

    /**
     * Creates the Archer Head deploy item with the given count as item amount.
     * A hidden lore tag identifies it.
     */
    public static ItemStack createArcherHeadItem(int count) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, Math.max(1, Math.min(64, count)));
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setDisplayName(ChatColor.AQUA + "Archer");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Right-click to deploy");
            lore.add(ARCHER_HEAD_LORE_TAG);
            skullMeta.setLore(lore);

            // Apply archer skin texture
            try {
                PlayerProfile profile = org.bukkit.Bukkit.createPlayerProfile(UUID.randomUUID(), "clash_archer");
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(new URL(ARCHER_TEXTURE_URL));
                profile.setTextures(textures);
                skullMeta.setOwnerProfile(profile);
            } catch (MalformedURLException ignored) {
                // Skin URL failed — item still works, just shows default head
            }

            item.setItemMeta(skullMeta);
        }
        return item;
    }

    /**
     * Returns true if the given item is the Archer Head deploy item.
     */
    public static boolean isArcherHeadItem(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        List<String> lore = meta.getLore();
        return lore != null && lore.contains(ARCHER_HEAD_LORE_TAG);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Despawns all NPCs, clears all maps. Called on plugin disable.
     */
    public void clear() {
        NPCRegistry npcRegistry = CitizensAPI.getNPCRegistry();

        // Stop and destroy all active archer NPCs
        for (Map.Entry<Integer, ArcherAI> entry : activeArchers.entrySet()) {
            entry.getValue().stop();
            NPC npc = npcRegistry.getById(entry.getKey());
            if (npc != null) npc.destroy();
        }
        activeArchers.clear();

        // Destroy all idle archer NPCs
        for (Set<Integer> ids : idleNpcIds.values()) {
            for (int id : ids) {
                NPC npc = npcRegistry.getById(id);
                if (npc != null) npc.destroy();
            }
        }
        idleNpcIds.clear();

        sessions.clear();
        sessionNpcIds.clear();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void giveArcherHeadItem(Player player, int count) {
        removeArcherHeadItem(player);
        ItemStack item = createArcherHeadItem(count);
        player.getInventory().addItem(item);
    }

    private void updateArcherHeadCount(Player player, int count) {
        if (count <= 0) {
            removeArcherHeadItem(player);
            return;
        }
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isArcherHeadItem(item)) {
                item.setAmount(Math.min(64, count));
                return;
            }
        }
    }

    private void removeArcherHeadItem(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isArcherHeadItem(item)) {
                player.getInventory().setItem(i, null);
                return;
            }
        }
    }
}
