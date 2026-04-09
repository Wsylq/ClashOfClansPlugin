package io.lossai.clash.grid.placement;

import io.lossai.clash.grid.model.GridCoord;
import io.lossai.clash.grid.model.PlacedBuilding;
import io.lossai.clash.grid.occupancy.FootprintRegistry;
import io.lossai.clash.grid.occupancy.OccupancyMap;
import io.lossai.clash.grid.renderer.VillageRenderer;
import io.lossai.clash.model.BuildingType;
import io.lossai.clash.model.VillageData;
import io.lossai.clash.service.BalanceBook;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Per-player state object active while a player is in Edit Mode.
 *
 * <p>All input handlers ({@link #onLeftClick}, {@link #onSneak}) branch exclusively
 * on {@link #mode} — no nested null-checks on multiple fields.</p>
 *
 * <p>The working {@link OccupancyMap} is a mutable copy; the
 * {@link #persistedSnapshot} is a read-only baseline used for disconnect rollback.</p>
 */
public class PlacementSession {

    // -------------------------------------------------------------------------
    // Session mode
    // -------------------------------------------------------------------------

    /** Explicit state enum — all input handlers branch on this field only. */
    public enum SessionMode {
        /** No building selected; cursor moves freely. */
        IDLE,
        /** A building type has been selected for new placement. */
        PLACING,
        /** An existing building has been lifted and is being repositioned. */
        MOVING,
        /** A wall drag chain is in progress. */
        WALL_DRAW
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID playerId;

    /** Current snapped cursor tile. Initialised to (0,0); updated every tick. */
    private GridCoord cursor = new GridCoord(0, 0);

    /** Non-null in PLACING mode: the type the player has selected to place. */
    @Nullable private BuildingType heldBuilding;

    /** Non-null in MOVING mode: the original anchor before the lift. */
    @Nullable private GridCoord liftedFrom;

    /** The building that was lifted in MOVING mode (stored for cancel/restore). */
    @Nullable private PlacedBuilding liftedBuilding;

    /** Working copy of the layout — mutated on every confirmed action. */
    private final OccupancyMap occupancyMap;

    /**
     * Immutable baseline snapshot taken at session start.
     * Used to roll back on disconnect (never mutated after construction).
     */
    private final OccupancyMap persistedSnapshot;

    /** Non-null in WALL_DRAW mode. */
    @Nullable private WallDrawSession wallSession;

    private SessionMode mode = SessionMode.IDLE;

    /** Ghost preview renderer for this session. */
    private final GhostPreview ghost;

    /** Renderer used for incremental tile updates after confirmed placements. */
    private final VillageRenderer renderer;

    /**
     * World X coordinate of grid tile (0,0). Used to translate world coords to grid coords
     * in the raycast. Must match the VillageRenderer origin passed at construction.
     */
    private final int gridOriginX;

    /**
     * World Z coordinate of grid tile (0,0). Used to translate world coords to grid coords
     * in the raycast. Must match the VillageRenderer origin passed at construction.
     */
    private final int gridOriginZ;

    /**
     * Village data used to enforce per-type building count limits.
     * May be null in tests; when null, count enforcement is skipped.
     */
    @Nullable private final VillageData villageData;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param playerId        UUID of the owning player
     * @param occupancyMap    working copy of the layout (will be mutated)
     * @param persistedSnapshot immutable baseline for rollback on disconnect
     * @param renderer        village renderer for incremental block updates
     */
    public PlacementSession(UUID playerId,
                            OccupancyMap occupancyMap,
                            OccupancyMap persistedSnapshot,
                            VillageRenderer renderer) {
        this(playerId, occupancyMap, persistedSnapshot, renderer, new GhostPreview(), null, 0, 0);
    }

    public PlacementSession(UUID playerId,
                            OccupancyMap occupancyMap,
                            OccupancyMap persistedSnapshot,
                            VillageRenderer renderer,
                            VillageData villageData) {
        this(playerId, occupancyMap, persistedSnapshot, renderer, new GhostPreview(), villageData, 0, 0);
    }

    public PlacementSession(UUID playerId,
                            OccupancyMap occupancyMap,
                            OccupancyMap persistedSnapshot,
                            VillageRenderer renderer,
                            VillageData villageData,
                            int gridOriginX,
                            int gridOriginZ) {
        this(playerId, occupancyMap, persistedSnapshot, renderer,
                new GhostPreview(gridOriginX, gridOriginZ), villageData, gridOriginX, gridOriginZ);
    }

    /**
     * Constructor for testing — allows injecting a custom {@link GhostPreview}
     * (e.g. a no-op stub) to avoid PacketEvents dependencies in unit tests.
     */
    public PlacementSession(UUID playerId,
                     OccupancyMap occupancyMap,
                     OccupancyMap persistedSnapshot,
                     VillageRenderer renderer,
                     GhostPreview ghost) {
        this(playerId, occupancyMap, persistedSnapshot, renderer, ghost, null, 0, 0);
    }

    public PlacementSession(UUID playerId,
                     OccupancyMap occupancyMap,
                     OccupancyMap persistedSnapshot,
                     VillageRenderer renderer,
                     GhostPreview ghost,
                     @Nullable VillageData villageData) {
        this(playerId, occupancyMap, persistedSnapshot, renderer, ghost, villageData, 0, 0);
    }

    public PlacementSession(UUID playerId,
                     OccupancyMap occupancyMap,
                     OccupancyMap persistedSnapshot,
                     VillageRenderer renderer,
                     GhostPreview ghost,
                     @Nullable VillageData villageData,
                     int gridOriginX,
                     int gridOriginZ) {
        this.playerId = playerId;
        this.occupancyMap = occupancyMap;
        this.persistedSnapshot = persistedSnapshot;
        this.renderer = renderer;
        this.ghost = ghost;
        this.villageData = villageData;
        this.gridOriginX = gridOriginX;
        this.gridOriginZ = gridOriginZ;
    }

    // -------------------------------------------------------------------------
    // Tick (called every server tick by the BukkitRunnable in EditCommand)
    // -------------------------------------------------------------------------

    /**
     * Raycasts from the player's eye to {@code y = GROUND_Y}, snaps to the nearest
     * integer tile, and updates the cursor and ghost preview if the tile changed.
     * Keeps the last valid cursor on a miss (Requirement 2.2).
     */
    public void tick(Player player) {
        GridCoord newCursor = raycastCursor(player);
        if (newCursor == null) {
            // Miss — keep last valid cursor, no ghost update needed
            return;
        }
        if (newCursor.equals(cursor)) {
            return; // No change
        }
        cursor = newCursor;
        refreshGhost(player);
    }

    // -------------------------------------------------------------------------
    // Input handlers
    // -------------------------------------------------------------------------

    /**
     * Handles a left-click event from the player.
     *
     * <ul>
     *   <li>WALL_DRAW — extend the wall chain to the current cursor.</li>
     *   <li>PLACING   — confirm placement if ghost is green; reject if red.</li>
     *   <li>MOVING    — confirm the move to the current cursor.</li>
     *   <li>IDLE      — enter MOVING mode for the building under the cursor.</li>
     * </ul>
     */
    public void onLeftClick(Player player) {
        switch (mode) {
            case WALL_DRAW -> handleWallClick(player);
            case PLACING   -> handlePlacingClick(player);
            case MOVING    -> handleMovingClick(player);
            case IDLE      -> handleIdleClick(player);
        }
    }

    /**
     * Handles a sneak (shift) event from the player.
     *
     * <ul>
     *   <li>WALL_DRAW — commit the chain and return to IDLE.</li>
     *   <li>PLACING   — cancel selection, clear ghost, return to IDLE.</li>
     *   <li>MOVING    — cancel move, restore building to original position, return to IDLE.</li>
     *   <li>IDLE      — exit edit mode (handled by EditCommand via the listener).</li>
     * </ul>
     */
    public void onSneak(Player player) {
        switch (mode) {
            case WALL_DRAW -> cancelWall(player);
            case PLACING   -> cancelPlacing(player);
            case MOVING    -> cancelMoving(player);
            case IDLE      -> { /* EditCommand handles exit */ }
        }
    }

    // -------------------------------------------------------------------------
    // Public API for EditCommand
    // -------------------------------------------------------------------------

    /**
     * Selects a building type for placement, entering PLACING or WALL_DRAW mode.
     * Clears any existing ghost first.
     */
    public void selectBuilding(Player player, BuildingType type) {
        clearGhost(player);
        heldBuilding = type;
        liftedFrom = null;
        liftedBuilding = null;
        wallSession = null;
        mode = (type == BuildingType.WALL) ? SessionMode.WALL_DRAW : SessionMode.PLACING;
        // Start wall session immediately if WALL selected (first click starts the chain)
        if (mode == SessionMode.WALL_DRAW) {
            // Wall session is created on first left-click, not on select
            mode = SessionMode.PLACING; // stay in PLACING until first click
        }
        refreshGhost(player);
    }

    /**
     * Removes the building under the current cursor from the occupancy map and
     * triggers an incremental render update.
     */
    public void removeUnderCursor(Player player) {
        PlacedBuilding building = occupancyMap.snapshot().get(cursor);
        player.sendMessage("§7[debug] remove cursor=" + cursor.x() + "," + cursor.z()
                + " mapSize=" + occupancyMap.snapshot().size()
                + " hit=" + (building != null ? building.type() : "null"));
        if (building == null) {
            player.sendMessage("§cNo building at cursor.");
            return;
        }
        List<GridCoord> tiles = FootprintRegistry.get(building.type()).tiles(building.anchor());
        occupancyMap.remove(cursor);
        renderTiles(player.getWorld(), tiles);
        clearGhost(player);
    }

    /** Clears the ghost preview and resets to IDLE. Called on edit mode exit. */
    public void exitCleanup(Player player) {
        clearGhost(player);
        mode = SessionMode.IDLE;
        heldBuilding = null;
        liftedFrom = null;
        liftedBuilding = null;
        wallSession = null;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getPlayerId()              { return playerId; }
    public SessionMode getMode()           { return mode; }
    public GridCoord getCursor()           { return cursor; }
    public OccupancyMap getOccupancyMap()  { return occupancyMap; }
    public OccupancyMap getPersistedSnapshot() { return persistedSnapshot; }

    // -------------------------------------------------------------------------
    // Private — mode-specific click handlers
    // -------------------------------------------------------------------------

    private void handleWallClick(Player player) {
        if (wallSession == null) {
            // Start a new wall chain at the current cursor
            if (!occupancyMap.canPlace(BuildingType.WALL, cursor)) {
                player.sendMessage("§cCannot start wall here.");
                return;
            }
            wallSession = new WallDrawSession(cursor);
        } else {
            // Extend the existing chain toward the cursor
            wallSession.extend(cursor, occupancyMap);
        }
    }

    private void handlePlacingClick(Player player) {
        if (heldBuilding == null) {
            mode = SessionMode.IDLE;
            return;
        }
        if (heldBuilding == BuildingType.WALL) {
            // Transition to WALL_DRAW on first click
            mode = SessionMode.WALL_DRAW;
            handleWallClick(player);
            return;
        }
        if (occupancyMap.canPlace(heldBuilding, cursor)) {
            confirmPlacement(player);
        } else {
            player.sendMessage("§cCannot place " + heldBuilding.displayName() + " here.");
        }
    }

    private void handleMovingClick(Player player) {
        if (liftedBuilding == null) {
            mode = SessionMode.IDLE;
            return;
        }
        // Try to place the lifted building at the current cursor
        if (occupancyMap.canPlace(liftedBuilding.type(), cursor)) {
            PlacedBuilding placed = new PlacedBuilding(liftedBuilding.type(), cursor, liftedBuilding.level());
            occupancyMap.place(placed);
            List<GridCoord> newTiles = FootprintRegistry.get(placed.type()).tiles(placed.anchor());
            renderTiles(player.getWorld(), newTiles);
            clearGhost(player);
            liftedFrom = null;
            liftedBuilding = null;
            heldBuilding = null;
            mode = SessionMode.IDLE;
        } else {
            player.sendMessage("§cCannot place " + liftedBuilding.type().displayName() + " here.");
        }
    }

    private void handleIdleClick(Player player) {
        // Debug: always show what tile the cursor is on and what's in the map
        PlacedBuilding atCursor = occupancyMap.snapshot().get(cursor);
        player.sendMessage("§7[debug] cursor=" + cursor.x() + "," + cursor.z()
                + " mode=" + mode
                + " mapSize=" + occupancyMap.snapshot().size()
                + " hit=" + (atCursor != null ? atCursor.type() : "null"));

        PlacedBuilding building = atCursor;
        if (building == null) {
            player.sendMessage("§7[debug] No building at cursor tile.");
            return; // Nothing to pick up
        }
        // Lift the building — free its tiles from the map
        liftedFrom = building.anchor();
        liftedBuilding = building;
        heldBuilding = building.type();
        List<GridCoord> oldTiles = FootprintRegistry.get(building.type()).tiles(building.anchor());
        occupancyMap.remove(cursor);
        renderTiles(player.getWorld(), oldTiles);
        mode = SessionMode.MOVING;
        player.sendMessage("§aLifted " + building.type().displayName() + " — move cursor and left-click to place.");
        refreshGhost(player);
    }

    // -------------------------------------------------------------------------
    // Private — mode-specific sneak handlers
    // -------------------------------------------------------------------------

    private void cancelWall(Player player) {
        if (wallSession != null) {
            wallSession.cancel();
            wallSession = null;
        }
        clearGhost(player);
        heldBuilding = null;
        mode = SessionMode.IDLE;
    }

    private void cancelPlacing(Player player) {
        clearGhost(player);
        heldBuilding = null;
        mode = SessionMode.IDLE;
    }

    private void cancelMoving(Player player) {
        if (liftedBuilding != null && liftedFrom != null) {
            // Restore the building to its original position
            if (occupancyMap.canPlace(liftedBuilding.type(), liftedFrom)) {
                occupancyMap.place(liftedBuilding);
                List<GridCoord> restoredTiles = FootprintRegistry.get(liftedBuilding.type()).tiles(liftedFrom);
                renderTiles(player.getWorld(), restoredTiles);
            }
        }
        clearGhost(player);
        liftedFrom = null;
        liftedBuilding = null;
        heldBuilding = null;
        mode = SessionMode.IDLE;
    }

    // -------------------------------------------------------------------------
    // Private — placement confirmation
    // -------------------------------------------------------------------------

    private void confirmPlacement(Player player) {
        // Enforce per-type count limit against VillageData
        if (villageData != null) {
            int placed = (int) occupancyMap.snapshot().values().stream()
                    .filter(b -> b.anchor().equals(b.anchor())) // all entries
                    .map(PlacedBuilding::type)
                    .filter(t -> t == heldBuilding)
                    .distinct() // count by anchor, not by tile
                    .count();
            // Count distinct anchors for this type
            long anchorCount = occupancyMap.snapshot().values().stream()
                    .filter(b -> b.type() == heldBuilding)
                    .map(PlacedBuilding::anchor)
                    .distinct()
                    .count();
            int max = BalanceBook.maxAtTownHall(heldBuilding, villageData.getTownHallLevel());
            if (anchorCount >= max) {
                player.sendMessage("§cYou can only place " + max + " " + heldBuilding.displayName()
                        + " at your Town Hall level.");
                return;
            }
        }
        PlacedBuilding building = new PlacedBuilding(heldBuilding, cursor, 1);
        occupancyMap.place(building);
        List<GridCoord> tiles = FootprintRegistry.get(heldBuilding).tiles(cursor);
        renderTiles(player.getWorld(), tiles);
        clearGhost(player);
        heldBuilding = null;
        mode = SessionMode.IDLE;
    }

    // -------------------------------------------------------------------------
    // Private — ghost helpers
    // -------------------------------------------------------------------------

    private void refreshGhost(Player player) {
        BuildingType type = (mode == SessionMode.MOVING && liftedBuilding != null)
                ? liftedBuilding.type()
                : heldBuilding;
        if (type == null) {
            return;
        }
        ghost.update(player, type, cursor, occupancyMap, null);
    }

    private void clearGhost(Player player) {
        ghost.clear(player, occupancyMap, null);
    }

    // -------------------------------------------------------------------------
    // Static helpers (pure Java, no Bukkit — testable without a live server)
    // -------------------------------------------------------------------------

    /**
     * Snaps a floating-point world (x, z) coordinate to the nearest integer tile.
     *
     * <p>The result satisfies: {@code |x - result.x()| <= 0.5} and
     * {@code |z - result.z()| <= 0.5} (Property 1).</p>
     *
     * @param x world X coordinate
     * @param z world Z coordinate
     * @return the snapped {@link GridCoord}
     */
    public static GridCoord snapToGrid(double x, double z) {
        return new GridCoord((int) Math.round(x), (int) Math.round(z));
    }

    // -------------------------------------------------------------------------
    // Private — cursor raycast
    // -------------------------------------------------------------------------

    /**
     * Raycasts from the player's eye toward the grid surface at {@code y = GROUND_Y}.
     * Returns the snapped {@link GridCoord}, or {@code null} on a miss.
     *
     * <p>The ray is traced up to 100 blocks. The intersection with the horizontal
     * plane {@code y = GROUND_Y} is computed analytically.</p>
     */
    @Nullable
    private GridCoord raycastCursor(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // Raycast against actual blocks first (handles 3D structures of any height)
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, dir, 100.0);
        if (hit != null && hit.getHitBlock() != null) {
            int worldX = hit.getHitBlock().getX();
            int worldZ = hit.getHitBlock().getZ();
            int tileX = worldX - gridOriginX;
            int tileZ = worldZ - gridOriginZ;
            if (tileX >= 0 && tileX < OccupancyMap.GRID_SIZE
                    && tileZ >= 0 && tileZ < OccupancyMap.GRID_SIZE) {
                return new GridCoord(tileX, tileZ);
            }
        }

        // Fallback: intersect with the ground plane at GROUND_Y + 1
        if (Math.abs(dir.getY()) < 1e-6) {
            return null;
        }
        double targetY = VillageRenderer.GROUND_Y + 1;
        double t = (targetY - eye.getY()) / dir.getY();
        if (t <= 0 || t > 100) {
            return null;
        }
        double worldX = eye.getX() + dir.getX() * t;
        double worldZ = eye.getZ() + dir.getZ() * t;
        int tileX = (int) Math.round(worldX) - gridOriginX;
        int tileZ = (int) Math.round(worldZ) - gridOriginZ;
        if (tileX < 0 || tileX >= OccupancyMap.GRID_SIZE
                || tileZ < 0 || tileZ >= OccupancyMap.GRID_SIZE) {
            return null;
        }
        return new GridCoord(tileX, tileZ);
    }

    // -------------------------------------------------------------------------
    // Private — renderer helper
    // -------------------------------------------------------------------------

    private void renderTiles(World world, List<GridCoord> tiles) {
        renderer.renderTiles(world, tiles, occupancyMap, null);
    }
}
