package io.lossai.clash.grid.command;

import io.lossai.clash.grid.model.GridCoord;
import io.lossai.clash.grid.model.PlacedBuilding;
import io.lossai.clash.grid.occupancy.FootprintRegistry;
import io.lossai.clash.grid.persistence.LayoutSerializer;
import io.lossai.clash.grid.placement.PlacementSession;
import io.lossai.clash.grid.renderer.VillageRenderer;
import io.lossai.clash.grid.occupancy.OccupancyMap;
import io.lossai.clash.model.BuildingType;
import io.lossai.clash.model.VillageData;
import io.lossai.clash.service.VillageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the {@code /clash edit} sub-command family.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code /clash edit}              — enter edit mode</li>
 *   <li>{@code /clash edit exit}         — exit edit mode</li>
 *   <li>{@code /clash edit select <type>}— select a building type</li>
 *   <li>{@code /clash edit remove}       — remove building under cursor</li>
 * </ul>
 * </p>
 */
public class EditCommand implements CommandExecutor, TabCompleter {

    // -------------------------------------------------------------------------
    // Inner record: per-session state held by EditCommand
    // -------------------------------------------------------------------------

    private static final class SessionEntry {
        final PlacementSession session;
        final Location previousLocation;
        final BukkitTask tickTask;

        SessionEntry(PlacementSession session, Location previousLocation, BukkitTask tickTask) {
            this.session = session;
            this.previousLocation = previousLocation;
            this.tickTask = tickTask;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final JavaPlugin plugin;
    private final VillageManager villageManager;
    private final LayoutSerializer layoutSerializer;

    /** Active edit sessions keyed by player UUID. */
    private final Map<UUID, SessionEntry> sessions = new HashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public EditCommand(JavaPlugin plugin, VillageManager villageManager, LayoutSerializer layoutSerializer) {
        this.plugin = plugin;
        this.villageManager = villageManager;
        this.layoutSerializer = layoutSerializer;
    }

    // -------------------------------------------------------------------------
    // CommandExecutor
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // args here are the sub-args after "edit" (ClashCommand strips the first token)
        if (args.length == 0) {
            handleEnter(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "exit"   -> handleExit(player);
            case "select" -> handleSelect(player, args);
            case "remove" -> handleRemove(player);
            default       -> player.sendMessage("§cUsage: /clash edit [exit|select <type>|remove]");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Sub-command handlers
    // -------------------------------------------------------------------------

    private void handleEnter(Player player) {
        UUID id = player.getUniqueId();

        // Guard: already in edit mode
        if (sessions.containsKey(id)) {
            player.sendMessage("You are already in edit mode.");
            return;
        }

        // Guard: player must own the village world they are in
        World currentWorld = player.getWorld();
        UUID worldOwner = villageManager.getWorldOwner(currentWorld);
        if (worldOwner == null || !worldOwner.equals(id)) {
            player.sendMessage("You can only edit your own village.");
            return;
        }

        // Load occupancy map
        OccupancyMap occupancyMap = layoutSerializer.load(id, null);

        // If no layout has been saved yet, seed from VillageData so existing buildings appear
        VillageData villageData = villageManager.getVillage(id);
        if (occupancyMap.snapshot().isEmpty() && villageData != null) {
            seedFromVillageData(occupancyMap, villageData);
        }

        // Snapshot for rollback on disconnect
        OccupancyMap snapshot = rebuildSnapshot(occupancyMap);

        // Create renderer — origin at (-32,-32) so grid tile (0,0) maps to world (-32,-32),
        // covering the full [-32,+31] world range used by VillageManager slots
        VillageRenderer renderer = new VillageRenderer(-32, -32);

        PlacementSession session = new PlacementSession(id, occupancyMap, snapshot, renderer, villageData, -32, -32);

        // Save previous location
        Location previousLocation = player.getLocation().clone();

        // Enable fly
        player.setAllowFlight(true);
        player.setFlying(true);

        // Teleport to 20 blocks above grid centre (world 0,0 = grid centre)
        Location editLocation = new Location(
                currentWorld,
                0.5,
                VillageRenderer.GROUND_Y + 20.0,
                0.5,
                player.getLocation().getYaw(),
                90f   // looking straight down
        );
        player.teleportAsync(editLocation);

        // Schedule 1-tick repeating tick task
        BukkitTask tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                session.tick(player);
            }
        }.runTaskTimer(plugin, 1L, 1L);

        sessions.put(id, new SessionEntry(session, previousLocation, tickTask));
        villageManager.enterEditMode(id);
        player.sendMessage("§aEntered base edit mode. Sneak to exit.");
    }

    private void handleExit(Player player) {
        UUID id = player.getUniqueId();
        SessionEntry entry = sessions.remove(id);
        if (entry == null) {
            player.sendMessage("§cYou are not in edit mode.");
            return;
        }
        doExit(player, entry, true);
    }

    private void handleSelect(Player player, String[] args) {
        UUID id = player.getUniqueId();
        SessionEntry entry = sessions.get(id);
        if (entry == null) {
            player.sendMessage("§cYou are not in edit mode.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /clash edit select <type>");
            return;
        }
        BuildingType type = BuildingType.fromInput(args[1]).orElse(null);
        if (type == null) {
            player.sendMessage("§cUnknown building type: " + args[1]);
            return;
        }
        entry.session.selectBuilding(player, type);
        player.sendMessage("§aSelected §e" + type.displayName() + "§a. Left-click to place.");
    }

    private void handleRemove(Player player) {
        UUID id = player.getUniqueId();
        SessionEntry entry = sessions.get(id);
        if (entry == null) {
            player.sendMessage("§cYou are not in edit mode.");
            return;
        }
        entry.session.removeUnderCursor(player);
    }

    // -------------------------------------------------------------------------
    // Public API for listener routing
    // -------------------------------------------------------------------------

    /**
     * Routes a player interact event to the active session.
     *
     * @param player      the interacting player
     * @param isLeftClick {@code true} for left-click, {@code false} for right-click
     */
    public void handleInteract(Player player, boolean isLeftClick) {
        SessionEntry entry = sessions.get(player.getUniqueId());
        if (entry == null) return;
        if (isLeftClick) {
            entry.session.onLeftClick(player);
        }
    }

    /**
     * Routes a sneak event to the active session.
     * If the session is in IDLE mode, exits edit mode.
     */
    public void handleSneak(Player player) {
        UUID id = player.getUniqueId();
        SessionEntry entry = sessions.get(id);
        if (entry == null) return;

        if (entry.session.getMode() == PlacementSession.SessionMode.IDLE) {
            // Sneak in IDLE = exit edit mode
            sessions.remove(id);
            doExit(player, entry, true);
        } else {
            entry.session.onSneak(player);
        }
    }

    /**
     * Called from {@code PlayerQuitEvent}: discards the session without saving
     * (rolls back to the persisted snapshot).
     */
    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        SessionEntry entry = sessions.remove(id);
        if (entry == null) return;
        // Cancel tick task; do NOT save — discard in-memory changes
        entry.tickTask.cancel();
        entry.session.exitCleanup(player);
        villageManager.exitEditMode(player.getUniqueId());
        // Restore fly state silently (player is disconnecting, but just in case)
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    /**
     * Returns {@code true} if the given player currently has an active edit session.
     */
    public boolean isInEditMode(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    // -------------------------------------------------------------------------
    // TabCompleter
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // args here are the sub-args after "edit"
        if (args.length == 1) {
            return filterByPrefix(List.of("exit", "select", "remove"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("select")) {
            List<String> types = new ArrayList<>();
            for (BuildingType type : BuildingType.values()) {
                types.add(type.name().toLowerCase(Locale.ROOT));
            }
            return filterByPrefix(types, args[1]);
        }
        return Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Performs the full exit sequence.
     *
     * @param save {@code true} to persist the layout; {@code false} to discard (rollback)
     */
    private void doExit(Player player, SessionEntry entry, boolean save) {
        UUID id = player.getUniqueId();

        // Cancel tick task
        entry.tickTask.cancel();

        // Cleanup ghost / session state
        entry.session.exitCleanup(player);

        // Disable fly
        player.setAllowFlight(false);
        player.setFlying(false);

        // Restore previous location
        player.teleportAsync(entry.previousLocation);

        // Always unregister from edit mode so renderVillage resumes
        villageManager.exitEditMode(id);

        if (save) {
            layoutSerializer.save(id, entry.session.getOccupancyMap(), null);
            applyLayoutToVillageData(id, entry.session.getOccupancyMap());
            // Delay re-render by 5 ticks to let teleportAsync complete first
            final Player p = player;
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() {
                    villageManager.clearPlayableArea(p);
                    villageManager.rerenderVillage(p);
                }
            }.runTaskLater(plugin, 5L);
            player.sendMessage("§aExited base edit mode. Layout saved.");
        }
    }

    /**
     * Builds a fresh {@link OccupancyMap} that is a deep copy of {@code source},
     * used as the immutable rollback snapshot.
     */
    private static OccupancyMap rebuildSnapshot(OccupancyMap source) {
        OccupancyMap copy = new OccupancyMap();
        source.snapshot().values().stream()
                .distinct()
                .forEach(building -> {
                    if (copy.canPlace(building.type(), building.anchor())) {
                        copy.place(building);
                    }
                });
        return copy;
    }

    private static List<String> filterByPrefix(List<String> values, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lowered)) {
                matches.add(value);
            }
        }
        return matches;
    }

    /**
     * Reads the final OccupancyMap and pushes per-player slot overrides to VillageManager
     * so that renderVillage places 3D structures at the new positions.
     * Grid coords are translated back to world coords using the -32 origin.
     */
    private void applyLayoutToVillageData(UUID playerId, OccupancyMap map) {
        final int GRID_ORIGIN = -32;
        Map<BuildingType, List<int[]>> overrides = new java.util.EnumMap<>(BuildingType.class);

        // Group distinct anchors by building type.
        // VillageManager place* methods expect the CENTRE of the building,
        // but OccupancyMap stores the NW corner (anchor). Add half-footprint to convert.
        map.snapshot().values().stream()
                .distinct()
                .forEach(building -> {
                    GridCoord anchor = building.anchor();
                    io.lossai.clash.grid.model.Footprint fp = FootprintRegistry.get(building.type());
                    // Centre = anchor + (footprint - 1) / 2  (integer, rounds down for odd sizes)
                    int centreX = anchor.x() + (fp.width() - 1) / 2;
                    int centreZ = anchor.z() + (fp.depth() - 1) / 2;
                    int worldX = centreX + GRID_ORIGIN;
                    int worldZ = centreZ + GRID_ORIGIN;
                    overrides.computeIfAbsent(building.type(), t -> new ArrayList<>())
                             .add(new int[]{worldX, worldZ});
                });

        villageManager.setPlayerSlotOverrides(playerId, overrides);
    }

    /**
     * Seeds an empty {@link OccupancyMap} from the player's {@link VillageData}
     * building counts, using the same slot positions as {@code VillageManager}.
     * Called on first edit session when no layout JSON exists yet.
     *
     * <p>VillageManager uses world-centred coords (e.g. cannon at -26,-8).
     * The OccupancyMap grid is [0,43] with origin at the NW corner, which maps
     * to world coords [-22, 21]. Translation: gridX = worldX + 22.</p>
     */
    private static void seedFromVillageData(OccupancyMap map, VillageData village) {
        // VillageManager world origin (0,0) maps to grid tile (32,32)
        final int GRID_OFFSET = 32;
        Map<BuildingType, List<int[]>> slots = VillageManager.getBuildingSlots();
        for (Map.Entry<BuildingType, Integer> entry : village.getBuildingsSnapshot().entrySet()) {
            BuildingType type = entry.getKey();
            int count = entry.getValue();
            List<int[]> typeSlots = slots.get(type);
            if (typeSlots == null) continue;
            int level = village.getBuildingLevel(type);
            io.lossai.clash.grid.model.Footprint fp = FootprintRegistry.get(type);
            for (int i = 0; i < Math.min(count, typeSlots.size()); i++) {
                int[] slot = typeSlots.get(i); // slot is the CENTRE in world coords
                // Convert centre → NW anchor: subtract half-footprint, then translate to grid
                int anchorX = slot[0] - (fp.width() - 1) / 2 + GRID_OFFSET;
                int anchorZ = slot[1] - (fp.depth() - 1) / 2 + GRID_OFFSET;
                GridCoord anchor = new GridCoord(anchorX, anchorZ);
                if (map.canPlace(type, anchor)) {
                    map.place(new PlacedBuilding(type, anchor, level));
                }
            }
        }
    }
}
