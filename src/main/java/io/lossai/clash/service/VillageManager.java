package io.lossai.clash.service;

import io.lossai.clash.model.BuildingType;
import io.lossai.clash.model.VillageData;
import io.lossai.clash.storage.VillageStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class VillageManager {

    private static final int MAX_TOWN_HALL_LEVEL = 2;
    private static final int GROUND_Y = 64;
    private static final int PLAYABLE_RADIUS = 34;
    private static final int SCENERY_RADIUS = 56;
    private static final Map<BuildingType, List<int[]>> BUILDING_SLOTS = createBuildingSlots();

    private final JavaPlugin plugin;
    private final VillageStore store;
    private final Map<UUID, VillageData> villages;
    private final Map<UUID, Map<JobKey, ConstructionJob>> activeJobs = new HashMap<>();

    public VillageManager(JavaPlugin plugin, VillageStore store) {
        this.plugin = plugin;
        this.store = store;
        this.villages = new LinkedHashMap<>(store.loadAll());
    }

    public void setupVillageForPlayer(Player player) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            village = new VillageData(player.getUniqueId(), player.getName(), null, 0,
                    1000L, 1000L, 500L, false, false, Collections.emptyMap(), Collections.emptyMap());
            villages.put(player.getUniqueId(), village);
        }

        village.setPlayerName(player.getName());
        World villageWorld = getOrCreateVillageWorld(village);
        if (villageWorld != null) {
            if (!village.isStarterGenerated()) {
                village.addBuilding(BuildingType.BUILDER_HUT, 1);
                village.addBuilding(BuildingType.GOLD_MINE, 1);
                village.addBuilding(BuildingType.ELIXIR_COLLECTOR, 1);
                village.setStarterGenerated(true);
            }

            if (!village.isObstaclesGenerated()) {
                generateScenery(villageWorld, village.getPlayerId());
                village.setObstaclesGenerated(true);
            }

            renderVillage(villageWorld, village);
            renderActiveConstruction(village.getPlayerId(), villageWorld);
            updateResourceHud(player, village);
            player.teleportAsync(getSafeSpawn(villageWorld));
        }

        store.saveAll(villages);
    }

    public VillageData getVillage(UUID playerId) {
        return villages.get(playerId);
    }

    public Map<UUID, VillageData> getVillageSnapshot() {
        return Collections.unmodifiableMap(villages);
    }

    public String build(Player player, BuildingType type, int amount) {
        if (amount != 1) {
            return ChatColor.RED + "Build one structure per command for now. Example: /clash build " + type.name().toLowerCase() + " 1";
        }

        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized yet. Rejoin or run /clash tp.";
        }

        if (!isBuildAllowed(village.getTownHallLevel(), type)) {
            return ChatColor.RED + "You cannot build " + type.displayName() + " at Town Hall " + village.getTownHallLevel() + ".";
        }

        int current = village.getBuildingCount(type);
        int max = BalanceBook.maxAtTownHall(type, village.getTownHallLevel());
        if (current >= max) {
            return ChatColor.RED + "Max reached for " + type.displayName() + " at this Town Hall (" + max + ").";
        }

        if (isJobActive(village.getPlayerId(), type, JobKind.BUILD)) {
            return ChatColor.YELLOW + type.displayName() + " is already under construction.";
        }

        int availableBuilders = availableBuilders(village.getPlayerId(), village);
        if (availableBuilders <= 0) {
            return ChatColor.RED + "All builders are busy. Wait for a build to complete.";
        }

        int slotIndex = nextBuildSlotIndex(village.getPlayerId(), village, type);
        List<int[]> slots = BUILDING_SLOTS.getOrDefault(type, List.of());
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return ChatColor.RED + "No free build slot available for " + type.displayName() + ".";
        }

        BalanceBook.BuildInfo info = BalanceBook.buildInfo(type, current, village.getTownHallLevel());
        if (!takeCurrency(village, info.currency(), info.cost())) {
            return ChatColor.RED + "Not enough " + currencyName(info.currency()) + ". Need " + info.cost() + ".";
        }

        World world = getOrCreateVillageWorld(village);
        if (world == null) {
            return ChatColor.RED + "Could not load your village world.";
        }

        ConstructionJob job = createJob(type, JobKind.BUILD, slotIndex, info.buildSeconds(), village.getWorldName());
        putJob(village.getPlayerId(), job);
        spawnConstructionVisual(job, world);

        updateResourceHud(player, village);
        store.saveAll(villages);

        long delayTicks = Math.max(1L, info.buildSeconds() * 20L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            removeJobAndVisual(village.getPlayerId(), job.key);
            evacuatePlayersFromJobArea(job);
            village.addBuilding(type, 1);

            World villageWorld = getOrCreateVillageWorld(village);
            if (villageWorld != null) {
                renderVillage(villageWorld, village);
                renderActiveConstruction(village.getPlayerId(), villageWorld);
            }

            if (player.isOnline()) {
                updateResourceHud(player, village);
                player.sendMessage(ChatColor.GREEN + type.displayName() + " constructed.");
            }
            store.saveAll(villages);
        }, delayTicks);

        return ChatColor.GOLD + "Started " + type.displayName() + " (" + formatDuration(info.buildSeconds()) + ", "
                + info.cost() + " " + currencyName(info.currency()) + ").";
    }

    public String upgradeBuilding(Player player, BuildingType type) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized yet. Rejoin or run /clash tp.";
        }

        if (village.getBuildingCount(type) <= 0) {
            return ChatColor.RED + "You do not own " + type.displayName() + " yet.";
        }

        if (isJobActive(village.getPlayerId(), type, JobKind.UPGRADE)) {
            return ChatColor.YELLOW + type.displayName() + " is already upgrading.";
        }

        int availableBuilders = availableBuilders(village.getPlayerId(), village);
        if (availableBuilders <= 0) {
            return ChatColor.RED + "All builders are busy. Wait for an upgrade/build to complete.";
        }

        int currentLevel = village.getBuildingLevel(type);
        BalanceBook.UpgradeInfo info = BalanceBook.upgradeInfo(type, currentLevel, village.getTownHallLevel());
        if (info == null) {
            return ChatColor.RED + "No upgrade path available for " + type.displayName() + " at Town Hall " + village.getTownHallLevel() + ".";
        }

        if (!takeCurrency(village, info.currency(), info.cost())) {
            return ChatColor.RED + "Not enough " + currencyName(info.currency()) + ". Need " + info.cost() + ".";
        }

        int slotIndex = 0;
        World world = getOrCreateVillageWorld(village);
        if (world == null) {
            return ChatColor.RED + "Could not load your village world.";
        }

        ConstructionJob job = createJob(type, JobKind.UPGRADE, slotIndex, info.seconds(), village.getWorldName());
        putJob(village.getPlayerId(), job);
        spawnConstructionVisual(job, world);

        updateResourceHud(player, village);
        store.saveAll(villages);

        long delayTicks = Math.max(1L, info.seconds() * 20L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            removeJobAndVisual(village.getPlayerId(), job.key);
            evacuatePlayersFromJobArea(job);
            village.setBuildingLevel(type, info.nextLevel());

            World villageWorld = getOrCreateVillageWorld(village);
            if (villageWorld != null) {
                renderVillage(villageWorld, village);
                renderActiveConstruction(village.getPlayerId(), villageWorld);
            }

            if (player.isOnline()) {
                updateResourceHud(player, village);
                player.sendMessage(ChatColor.GREEN + type.displayName() + " upgraded to level " + info.nextLevel() + ".");
            }
            store.saveAll(villages);
        }, delayTicks);

        return ChatColor.GOLD + "Started upgrade for " + type.displayName() + " (" + formatDuration(info.seconds()) + ", "
                + info.cost() + " " + currencyName(info.currency()) + ").";
    }

    public String upgradeTownHall(Player player) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized yet. Rejoin or run /clash tp.";
        }

        int current = village.getTownHallLevel();
        if (current >= MAX_TOWN_HALL_LEVEL) {
            return ChatColor.YELLOW + "Town Hall is already max for this build (level " + MAX_TOWN_HALL_LEVEL + ").";
        }

        Map<BuildingType, Integer> requirements = RequirementBook.requirementsForCurrentTownHall(current);
        if (!village.meetsRequirements(requirements)) {
            List<String> missing = village.missingRequirements(requirements);
            return ChatColor.RED + "Missing required buildings: " + String.join(", ", missing);
        }

        Map<BuildingType, Integer> levelRequirements = RequirementBook.levelRequirementsForCurrentTownHall(current);
        if (!village.meetsLevelRequirements(levelRequirements)) {
            List<String> missing = village.missingLevelRequirements(levelRequirements);
            return ChatColor.RED + "Missing required levels: " + String.join(", ", missing);
        }

        village.setTownHallLevel(current + 1);
        World world = getOrCreateVillageWorld(village);
        if (world != null) {
            renderVillage(world, village);
            renderActiveConstruction(village.getPlayerId(), world);
        }
        updateResourceHud(player, village);

        store.saveAll(villages);
        return ChatColor.GOLD + "Town Hall upgraded to level " + village.getTownHallLevel() + "!";
    }

    public String teleportToVillage(Player player) {
        VillageData village = villages.computeIfAbsent(
                player.getUniqueId(),
                uuid -> new VillageData(uuid, player.getName(), null, 0,
                        1000L, 1000L, 500L, false, false, Collections.emptyMap(), Collections.emptyMap())
        );

        World world = getOrCreateVillageWorld(village);
        if (world == null) {
            return ChatColor.RED + "Could not create or load your village world.";
        }

        renderVillage(world, village);
        renderActiveConstruction(village.getPlayerId(), world);
        updateResourceHud(player, village);
        player.teleportAsync(getSafeSpawn(world));
        store.saveAll(villages);
        return ChatColor.GREEN + "Teleported to your village world: " + world.getName();
    }

    public List<String> describeVillage(Player player) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return List.of(ChatColor.RED + "Village not initialized yet.");
        }

        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.AQUA + "Village world: " + ChatColor.WHITE + village.getWorldName());
        lines.add(ChatColor.AQUA + "Town Hall level: " + ChatColor.WHITE + village.getTownHallLevel());
        lines.add(ChatColor.GOLD + "Gold: " + ChatColor.WHITE + village.getGold()
                + ChatColor.LIGHT_PURPLE + "  Elixir: " + ChatColor.WHITE + village.getElixir()
                + ChatColor.AQUA + "  Gems: " + ChatColor.WHITE + village.getGems());
        lines.add(ChatColor.AQUA + "Builders: " + ChatColor.WHITE + availableBuilders(village.getPlayerId(), village)
                + ChatColor.GRAY + "/" + totalBuilders(village));
        lines.add(ChatColor.AQUA + "Builders busy: " + ChatColor.WHITE + busyBuilders(village.getPlayerId()));

        lines.add(ChatColor.AQUA + "Buildings:");
        if (village.getBuildingsSnapshot().isEmpty()) {
            lines.add(ChatColor.GRAY + " - none");
        } else {
            for (Map.Entry<BuildingType, Integer> entry : village.getBuildingsSnapshot().entrySet()) {
                int level = village.getBuildingLevel(entry.getKey());
                lines.add(ChatColor.GRAY + " - " + entry.getKey().displayName() + ": " + entry.getValue() + " (lvl " + level + ")");
            }
        }

        Map<BuildingType, Integer> nextRequirements = RequirementBook.requirementsForCurrentTownHall(village.getTownHallLevel());
        Map<BuildingType, Integer> levelRequirements = RequirementBook.levelRequirementsForCurrentTownHall(village.getTownHallLevel());
        if (!nextRequirements.isEmpty() || !levelRequirements.isEmpty()) {
            lines.add(ChatColor.AQUA + "Next upgrade requirements:");
            for (Map.Entry<BuildingType, Integer> req : nextRequirements.entrySet()) {
                int current = village.getBuildingCount(req.getKey());
                String color = current >= req.getValue() ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
                lines.add(color + " - " + req.getKey().displayName() + ": " + current + "/" + req.getValue());
            }
            for (Map.Entry<BuildingType, Integer> req : levelRequirements.entrySet()) {
                int current = village.getBuildingLevel(req.getKey());
                String color = current >= req.getValue() ? ChatColor.GREEN.toString() : ChatColor.RED.toString();
                lines.add(color + " - " + req.getKey().displayName() + " level: " + current + "/" + req.getValue());
            }
        }

        Map<JobKey, ConstructionJob> jobs = activeJobs.getOrDefault(village.getPlayerId(), Collections.emptyMap());
        if (!jobs.isEmpty()) {
            lines.add(ChatColor.AQUA + "In construction:");
            for (ConstructionJob job : jobs.values()) {
                lines.add(ChatColor.GRAY + " - " + job.displayName + " (" + formatDuration(remainingSeconds(job)) + ")");
            }
        }
        return lines;
    }

    public Set<BuildingType> getBuildableTypes(int townHallLevel) {
        EnumSet<BuildingType> allowed = EnumSet.noneOf(BuildingType.class);
        for (BuildingType type : BuildingType.values()) {
            if (BalanceBook.maxAtTownHall(type, townHallLevel) > 0) {
                allowed.add(type);
            }
        }
        return Collections.unmodifiableSet(allowed);
    }

    public void tickResourceGeneration() {
        boolean changed = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            VillageData village = villages.get(player.getUniqueId());
            if (village == null) {
                continue;
            }

            long goldGain = (long) village.getBuildingCount(BuildingType.GOLD_MINE)
                    * (2 + village.getBuildingLevel(BuildingType.GOLD_MINE));
            long elixirGain = (long) village.getBuildingCount(BuildingType.ELIXIR_COLLECTOR)
                    * (2 + village.getBuildingLevel(BuildingType.ELIXIR_COLLECTOR));
            village.addGold(goldGain);
            village.addElixir(elixirGain);
            updateResourceHud(player, village);
            changed = true;
        }

        if (changed) {
            store.saveAll(villages);
        }
    }

    public void tickConstructionVisuals() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<JobKey, ConstructionJob>> entry : activeJobs.entrySet()) {
            UUID playerId = entry.getKey();
            Map<JobKey, ConstructionJob> jobs = entry.getValue();
            for (ConstructionJob job : jobs.values()) {
                if (job.hologram != null && !job.hologram.isDead()) {
                    long remaining = Math.max(0L, (job.endsAtMillis - now + 999L) / 1000L);
                    job.hologram.setCustomName(ChatColor.YELLOW + job.displayName + ChatColor.GRAY + " - " + formatDuration((int) remaining));
                }
                if (job.builderNpc != null && !job.builderNpc.isDead()) {
                    job.builderNpc.setAI(true);
                }
            }

            Player owner = Bukkit.getPlayer(playerId);
            VillageData village = villages.get(playerId);
            if (owner != null && owner.isOnline() && village != null) {
                updateResourceHud(owner, village);
            }
        }
    }

    public void tickArcherTowerDefense() {
        for (Player owner : Bukkit.getOnlinePlayers()) {
            VillageData village = villages.get(owner.getUniqueId());
            if (village == null) {
                continue;
            }
            World world = Bukkit.getWorld(village.getWorldName());
            if (world == null) {
                continue;
            }

            int count = village.getBuildingCount(BuildingType.ARCHER_TOWER);
            if (count <= 0) {
                continue;
            }

            List<int[]> slots = BUILDING_SLOTS.getOrDefault(BuildingType.ARCHER_TOWER, List.of());
            int limit = Math.min(count, slots.size());
            for (int i = 0; i < limit; i++) {
                int[] slot = slots.get(i);
                Location towerTop = new Location(world, slot[0] + 0.5, GROUND_Y + 7.6, slot[1] + 0.5);
                fireArcherTowerAtNearestMonster(world, towerTop, 18.0);
            }
        }
    }

    public void tickCannonDefense() {
        for (Player owner : Bukkit.getOnlinePlayers()) {
            VillageData village = villages.get(owner.getUniqueId());
            if (village == null) {
                continue;
            }
            World world = Bukkit.getWorld(village.getWorldName());
            if (world == null) {
                continue;
            }

            int count = village.getBuildingCount(BuildingType.CANNON);
            if (count <= 0) {
                continue;
            }

            List<int[]> slots = BUILDING_SLOTS.getOrDefault(BuildingType.CANNON, List.of());
            int limit = Math.min(count, slots.size());
            for (int i = 0; i < limit; i++) {
                int[] slot = slots.get(i);
                fireCannonAtNearestMonster(world, slot[0], slot[1], 14.0);
            }
        }
    }

    public boolean isVillageWorld(World world) {
        return world != null && world.getName().startsWith("coc_");
    }

    public boolean isInsidePlayableArea(Location location) {
        if (location == null || location.getWorld() == null || !isVillageWorld(location.getWorld())) {
            return true;
        }
        double x = location.getX();
        double z = location.getZ();
        return (x * x + z * z) <= (PLAYABLE_RADIUS * PLAYABLE_RADIUS);
    }

    public boolean isInsideConstructionZone(Location location) {
        if (location == null || location.getWorld() == null || !isVillageWorld(location.getWorld())) {
            return false;
        }

        UUID ownerId = findOwnerByWorld(location.getWorld().getName());
        if (ownerId == null) {
            return false;
        }

        Map<JobKey, ConstructionJob> jobs = activeJobs.getOrDefault(ownerId, Collections.emptyMap());
        for (ConstructionJob job : jobs.values()) {
            if (isInsideJobArea(location, job)) {
                return true;
            }
        }
        return false;
    }

    public Location nearestConstructionSafeLocation(Location from) {
        if (from == null || from.getWorld() == null) {
            return from;
        }

        UUID ownerId = findOwnerByWorld(from.getWorld().getName());
        if (ownerId == null) {
            return nearestPlayableLocation(from.getWorld(), from);
        }

        ConstructionJob nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (ConstructionJob job : activeJobs.getOrDefault(ownerId, Collections.emptyMap()).values()) {
            int[] slot = slotOf(job);
            double dx = from.getX() - (slot[0] + 0.5);
            double dz = from.getZ() - (slot[1] + 0.5);
            double d2 = dx * dx + dz * dz;
            if (d2 < nearestDist) {
                nearestDist = d2;
                nearest = job;
            }
        }

        if (nearest == null) {
            return nearestPlayableLocation(from.getWorld(), from);
        }

        int[] slot = slotOf(nearest);
        int radius = constructionRadius(nearest.type) + 2;
        Vector out = new Vector(from.getX() - (slot[0] + 0.5), 0.0, from.getZ() - (slot[1] + 0.5));
        if (out.lengthSquared() < 0.0001) {
            out = new Vector(1.0, 0.0, 0.0);
        }
        out.normalize().multiply(radius);

        Location candidate = new Location(from.getWorld(), slot[0] + 0.5 + out.getX(), GROUND_Y + 2.0,
                slot[1] + 0.5 + out.getZ(), from.getYaw(), from.getPitch());
        return nearestPlayableLocation(from.getWorld(), candidate);
    }

    public Location nearestPlayableLocation(World world, Location from) {
        if (world == null || from == null) {
            return null;
        }
        Vector flat = new Vector(from.getX(), 0.0, from.getZ());
        if (flat.lengthSquared() <= (PLAYABLE_RADIUS - 2.0) * (PLAYABLE_RADIUS - 2.0)) {
            return new Location(world, from.getX(), GROUND_Y + 2.0, from.getZ(), from.getYaw(), from.getPitch());
        }
        if (flat.lengthSquared() < 0.0001) {
            return getSafeSpawn(world);
        }

        flat.normalize().multiply(PLAYABLE_RADIUS - 2.0);
        return new Location(world, flat.getX() + 0.5, GROUND_Y + 2.0, flat.getZ() + 0.5, from.getYaw(), from.getPitch());
    }

    public void shutdown() {
        for (UUID playerId : new ArrayList<>(activeJobs.keySet())) {
            clearAllJobVisuals(playerId);
        }
        store.saveAll(villages);
    }

    private boolean isBuildAllowed(int townHallLevel, BuildingType type) {
        return BalanceBook.maxAtTownHall(type, townHallLevel) > 0;
    }

    private World getOrCreateVillageWorld(VillageData village) {
        String worldName = village.getWorldName();
        if (worldName == null || worldName.isBlank()) {
            worldName = "coc_" + village.getPlayerId().toString().substring(0, 8);
            village.setWorldName(worldName);
        }

        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            return existing;
        }

        WorldCreator creator = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false);
        World created = creator.createWorld();
        if (created != null) {
            created.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            created.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            created.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            created.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
            created.setTime(1000L);
            created.setStorm(false);
            created.setThundering(false);
            created.setSpawnLocation(0, GROUND_Y + 2, -10);

            WorldBorder border = created.getWorldBorder();
            border.setCenter(0.0, 0.0);
            border.setSize(140.0);
        }
        return created;
    }

    private void updateResourceHud(Player player, VillageData village) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }

        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("clashhud", "dummy", ChatColor.GOLD + "Clash Village");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int total = totalBuilders(village);
        int busy = busyBuilders(village.getPlayerId());
        int available = Math.max(0, total - busy);

        objective.getScore(ChatColor.YELLOW + "Town Hall: " + ChatColor.WHITE + village.getTownHallLevel()).setScore(7);
        objective.getScore(ChatColor.GOLD + "Gold: " + ChatColor.WHITE + village.getGold()).setScore(6);
        objective.getScore(ChatColor.LIGHT_PURPLE + "Elixir: " + ChatColor.WHITE + village.getElixir()).setScore(5);
        objective.getScore(ChatColor.AQUA + "Gems: " + ChatColor.WHITE + village.getGems()).setScore(4);
        objective.getScore(ChatColor.GREEN + "Builders free: " + ChatColor.WHITE + available + ChatColor.GRAY + "/" + total).setScore(3);
        objective.getScore(ChatColor.RED + "Builders busy: " + ChatColor.WHITE + busy).setScore(2);
        objective.getScore(ChatColor.DARK_GRAY + " ").setScore(1);

        player.setScoreboard(scoreboard);
    }

    private void renderVillage(World world, VillageData village) {
        clearAllJobVisuals(village.getPlayerId());
        flattenVillageCore(world);
        clearCoreAboveGround(world);
        placeBoundary(world);
        placeTownHall(world, village.getTownHallLevel());

        for (Map.Entry<BuildingType, Integer> entry : village.getBuildingsSnapshot().entrySet()) {
            placeBuildings(world, entry.getKey(), entry.getValue(), village.getBuildingLevel(entry.getKey()));
        }
    }

    private void flattenVillageCore(World world) {
        for (int x = -SCENERY_RADIUS; x <= SCENERY_RADIUS; x++) {
            for (int z = -SCENERY_RADIUS; z <= SCENERY_RADIUS; z++) {
                if ((x * x) + (z * z) > SCENERY_RADIUS * SCENERY_RADIUS) {
                    continue;
                }
                world.getBlockAt(x, GROUND_Y - 1, z).setType(Material.DIRT, false);
                world.getBlockAt(x, GROUND_Y, z).setType(Material.GRASS_BLOCK, false);
            }
        }
    }

    private void clearCoreAboveGround(World world) {
        for (int x = -PLAYABLE_RADIUS; x <= PLAYABLE_RADIUS; x++) {
            for (int z = -PLAYABLE_RADIUS; z <= PLAYABLE_RADIUS; z++) {
                if ((x * x) + (z * z) > PLAYABLE_RADIUS * PLAYABLE_RADIUS) {
                    continue;
                }
                for (int y = GROUND_Y + 1; y <= GROUND_Y + 12; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void placeBoundary(World world) {
        for (int x = -SCENERY_RADIUS; x <= SCENERY_RADIUS; x++) {
            for (int z = -SCENERY_RADIUS; z <= SCENERY_RADIUS; z++) {
                int d2 = x * x + z * z;
                if (d2 >= (PLAYABLE_RADIUS + 1) * (PLAYABLE_RADIUS + 1)
                        && d2 <= (PLAYABLE_RADIUS + 2) * (PLAYABLE_RADIUS + 2)) {
                    world.getBlockAt(x, GROUND_Y, z).setType(Material.COARSE_DIRT, false);
                }
                if (d2 >= (PLAYABLE_RADIUS + 3) * (PLAYABLE_RADIUS + 3)
                        && d2 <= (PLAYABLE_RADIUS + 4) * (PLAYABLE_RADIUS + 4)) {
                    for (int y = GROUND_Y + 1; y <= GROUND_Y + 3; y++) {
                        world.getBlockAt(x, y, z).setType(Material.BARRIER, false);
                    }
                }
            }
        }
    }

    private void generateScenery(World world, UUID seedSource) {
        Random random = new Random(seedSource.getMostSignificantBits() ^ seedSource.getLeastSignificantBits());
        for (int i = 0; i < 46; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = (PLAYABLE_RADIUS + 8.0) + random.nextDouble() * 14.0;
            int x = (int) Math.round(Math.cos(angle) * distance);
            int z = (int) Math.round(Math.sin(angle) * distance);
            int y = GROUND_Y + 1;

            int style = random.nextInt(3);
            if (style == 0) {
                placeTreeObstacle(world, x, y, z);
            } else if (style == 1) {
                placeRockObstacle(world, x, y, z);
            } else {
                placeBushObstacle(world, x, y, z);
            }
        }
    }

    private void placeTreeObstacle(World world, int x, int y, int z) {
        for (int i = 0; i < 3; i++) {
            world.getBlockAt(x, y + i, z).setType(Material.OAK_LOG, false);
        }
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                if (Math.abs(ox) + Math.abs(oz) <= 3) {
                    world.getBlockAt(x + ox, y + 3, z + oz).setType(Material.OAK_LEAVES, false);
                }
            }
        }
        world.getBlockAt(x, y + 4, z).setType(Material.OAK_LEAVES, false);
    }

    private void placeRockObstacle(World world, int x, int y, int z) {
        Material[] materials = new Material[]{Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE};
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (Math.abs(ox) + Math.abs(oz) <= 2) {
                    Material material = materials[Math.floorMod(x + z + ox + oz, materials.length)];
                    world.getBlockAt(x + ox, y, z + oz).setType(material, false);
                }
            }
        }
    }

    private void placeBushObstacle(World world, int x, int y, int z) {
        world.getBlockAt(x, y, z).setType(Material.FLOWERING_AZALEA_LEAVES, false);
        world.getBlockAt(x + 1, y, z).setType(Material.AZALEA_LEAVES, false);
        world.getBlockAt(x - 1, y, z).setType(Material.AZALEA_LEAVES, false);
        world.getBlockAt(x, y, z + 1).setType(Material.AZALEA_LEAVES, false);
        world.getBlockAt(x, y, z - 1).setType(Material.AZALEA_LEAVES, false);
    }

    private void placeTownHall(World world, int townHallLevel) {
        Material wall = switch (townHallLevel) {
            case 0 -> Material.OAK_PLANKS;
            case 1 -> Material.STONE_BRICKS;
            default -> Material.POLISHED_DEEPSLATE;
        };

        Material roof = switch (townHallLevel) {
            case 0 -> Material.DARK_OAK_STAIRS;
            case 1 -> Material.BRICKS;
            default -> Material.DEEPSLATE_TILES;
        };

        int baseY = GROUND_Y + 1;
        fillRect(world, -3, baseY, -3, 3, baseY, 3, Material.COBBLESTONE);
        hollowRect(world, -2, baseY + 1, -2, 2, baseY + 4, 2, wall);
        fillRect(world, -3, baseY + 5, -3, 3, baseY + 5, 3, roof);
        world.getBlockAt(0, baseY + 1, -2).setType(Material.AIR, false);
        world.getBlockAt(0, baseY + 2, -2).setType(Material.AIR, false);
    }

    private void placeBuildings(World world, BuildingType type, int count, int level) {
        List<int[]> slots = BUILDING_SLOTS.getOrDefault(type, List.of());
        int limit = Math.min(count, slots.size());
        for (int i = 0; i < limit; i++) {
            int[] offset = slots.get(i);
            int x = offset[0];
            int z = offset[1];
            switch (type) {
                case BUILDER_HUT -> placeBuilderHut(world, x, z);
                case GOLD_MINE -> placeGoldMine(world, x, z, level);
                case ELIXIR_COLLECTOR -> placeElixirCollector(world, x, z, level);
                case BARRACKS -> placeBarracks(world, x, z);
                case ARMY_CAMP -> placeArmyCamp(world, x, z);
                case CANNON -> placeCannon(world, x, z);
                case ARCHER_TOWER -> placeArcherTower(world, x, z);
            }
        }
    }

    private void placeBuilderHut(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fillRect(world, cx - 2, y, cz - 2, cx + 2, y, cz + 2, Material.OAK_PLANKS);
        hollowRect(world, cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Material.SPRUCE_PLANKS);
        fillRect(world, cx - 2, y + 3, cz - 2, cx + 2, y + 3, cz + 2, Material.SPRUCE_SLAB);
        world.getBlockAt(cx, y + 1, cz - 1).setType(Material.AIR, false);
    }

    private void placeGoldMine(World world, int cx, int cz, int level) {
        int y = GROUND_Y + 1;
        fillRect(world, cx - 2, y, cz - 2, cx + 2, y, cz + 2, Material.STONE_BRICKS);
        fillRect(world, cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Material.SMOOTH_STONE);
        world.getBlockAt(cx, y + 1, cz).setType(Material.HOPPER, false);
        world.getBlockAt(cx, y + 2, cz).setType(level >= 2 ? Material.RAW_GOLD_BLOCK : Material.GOLD_BLOCK, false);
        world.getBlockAt(cx + 2, y + 1, cz).setType(Material.TORCH, false);
        world.getBlockAt(cx - 2, y + 1, cz).setType(Material.TORCH, false);
    }

    private void placeElixirCollector(World world, int cx, int cz, int level) {
        int y = GROUND_Y + 1;
        fillRect(world, cx - 2, y, cz - 2, cx + 2, y, cz + 2, Material.POLISHED_ANDESITE);
        fillRect(world, cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Material.AMETHYST_BLOCK);
        world.getBlockAt(cx, y + 3, cz).setType(level >= 2 ? Material.PURPLE_STAINED_GLASS : Material.MAGENTA_STAINED_GLASS, false);
        world.getBlockAt(cx + 1, y + 1, cz).setType(Material.PURPLE_CANDLE, false);
        world.getBlockAt(cx - 1, y + 1, cz).setType(Material.PURPLE_CANDLE, false);
    }

    private void placeBarracks(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fillRect(world, cx - 3, y, cz - 3, cx + 3, y, cz + 3, Material.STONE_BRICKS);
        hollowRect(world, cx - 2, y + 1, cz - 2, cx + 2, y + 3, cz + 2, Material.BRICKS);
        fillRect(world, cx - 3, y + 4, cz - 3, cx + 3, y + 4, cz + 3, Material.RED_WOOL);
    }

    private void placeArmyCamp(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fillRect(world, cx - 3, y, cz - 3, cx + 3, y, cz + 3, Material.PACKED_MUD);
        for (int x = cx - 3; x <= cx + 3; x++) {
            world.getBlockAt(x, y + 1, cz - 3).setType(Material.OAK_FENCE, false);
            world.getBlockAt(x, y + 1, cz + 3).setType(Material.OAK_FENCE, false);
        }
        for (int z = cz - 3; z <= cz + 3; z++) {
            world.getBlockAt(cx - 3, y + 1, z).setType(Material.OAK_FENCE, false);
            world.getBlockAt(cx + 3, y + 1, z).setType(Material.OAK_FENCE, false);
        }
        world.getBlockAt(cx, y + 1, cz).setType(Material.CAMPFIRE, false);
    }

    private void placeCannon(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fillRect(world, cx - 1, y, cz - 1, cx + 1, y, cz + 1, Material.STONE);
        world.getBlockAt(cx, y + 1, cz).setType(Material.DISPENSER, false);
        world.getBlockAt(cx, y + 1, cz - 1).setType(Material.IRON_BARS, false);
        world.getBlockAt(cx, y + 1, cz + 1).setType(Material.STONE_BRICK_WALL, false);
    }

    private void placeArcherTower(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fillRect(world, cx - 1, y, cz - 1, cx + 1, y, cz + 1, Material.COBBLESTONE);
        for (int i = 1; i <= 4; i++) {
            world.getBlockAt(cx, y + i, cz).setType(Material.SPRUCE_LOG, false);
        }
        fillRect(world, cx - 1, y + 5, cz - 1, cx + 1, y + 5, cz + 1, Material.SPRUCE_PLANKS);
        world.getBlockAt(cx, y + 6, cz).setType(Material.SKELETON_SKULL, false);
    }

    private void fireArcherTowerAtNearestMonster(World world, Location source, double range) {
        Monster target = findNearestMonster(world, source, range, false);
        if (target == null) {
            return;
        }

        Vector flat = target.getLocation().toVector().subtract(source.toVector());
        flat.setY(0.0);
        if (flat.lengthSquared() < 0.0001) {
            flat = new Vector(1.0, 0.0, 0.0);
        }
        flat.normalize();

        // Move the launch point outside the tower cap so arrows do not clip into tower blocks.
        Location launch = source.clone().add(flat.clone().multiply(1.85)).add(0.0, 0.2, 0.0);
        Vector direction = target.getLocation().toVector().add(new Vector(0.0, 1.1, 0.0)).subtract(launch.toVector());
        if (direction.lengthSquared() < 0.0001) {
            return;
        }

        AbstractArrow arrow = world.spawnArrow(launch, direction.normalize(), 3.0f, 0.08f);
        arrow.setDamage(3.0);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
    }

    private void fireCannonAtNearestMonster(World world, int cx, int cz, double range) {
        Location pivot = new Location(world, cx + 0.5, GROUND_Y + 2.2, cz + 0.5);
        Monster target = findNearestMonster(world, pivot, range, true);
        if (target == null) {
            return;
        }

        Vector flat = target.getLocation().toVector().subtract(pivot.toVector());
        flat.setY(0.0);
        if (flat.lengthSquared() < 0.0001) {
            flat = new Vector(1.0, 0.0, 0.0);
        }
        rotateCannonBlock(world, cx, cz, faceFromVector(flat));

        Vector direction = target.getLocation().toVector().add(new Vector(0.0, 0.7, 0.0)).subtract(pivot.toVector());
        if (direction.lengthSquared() < 0.0001) {
            return;
        }

        Vector normalized = direction.normalize();
        Location launch = pivot.clone().add(normalized.clone().multiply(1.3));
        AbstractArrow shell = world.spawnArrow(launch, normalized, 3.5f, 0.02f);
        shell.setDamage(5.0);
        shell.setKnockbackStrength(1);
        shell.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
    }

    private Monster findNearestMonster(World world, Location source, double range, boolean groundOnly) {
        Monster nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : world.getNearbyEntities(source, range, range, range)) {
            if (!(entity instanceof Monster monster)) {
                continue;
            }
            if (monster.isDead() || !monster.isValid()) {
                continue;
            }
            if (groundOnly && !monster.isOnGround()) {
                continue;
            }

            double distance = entity.getLocation().distanceSquared(source);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = monster;
            }
        }
        return nearest;
    }

    private void rotateCannonBlock(World world, int cx, int cz, BlockFace face) {
        Block block = world.getBlockAt(cx, GROUND_Y + 2, cz);
        if (block.getType() != Material.DISPENSER) {
            return;
        }
        if (!(block.getBlockData() instanceof Directional directional)) {
            return;
        }
        directional.setFacing(face);
        block.setBlockData(directional, false);
    }

    private BlockFace faceFromVector(Vector vector) {
        if (Math.abs(vector.getX()) >= Math.abs(vector.getZ())) {
            return vector.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return vector.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void spawnConstructionVisual(ConstructionJob job, World world) {
        int[] center = slotOf(job);
        int cx = center[0];
        int cz = center[1];
        int y = GROUND_Y + 1;

        int radius = constructionRadius(job.type);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                boolean edge = x == cx - radius || x == cx + radius || z == cz - radius || z == cz + radius;
                if (!edge) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                if (block.getType() == Material.AIR) {
                    block.setType(Material.OAK_FENCE, false);
                    job.fenceBlocks.add(new BlockPos(x, y, z));
                }
            }
        }

        Location npcLocation = new Location(world, cx + 0.5, y + 0.0, cz + 0.5);
        Villager villager = world.spawn(npcLocation, Villager.class, spawned -> {
            spawned.setProfession(Villager.Profession.MASON);
            spawned.setAdult();
            spawned.setInvulnerable(true);
            spawned.setRemoveWhenFarAway(false);
            spawned.setCustomName(ChatColor.GOLD + "Builder");
            spawned.setCustomNameVisible(false);
        });
        job.builderNpc = villager;

        Location holoLocation = new Location(world, cx + 0.5, y + 4.2, cz + 0.5);
        ArmorStand stand = world.spawn(holoLocation, ArmorStand.class, spawned -> {
            spawned.setInvisible(true);
            spawned.setInvulnerable(true);
            spawned.setGravity(false);
            spawned.setMarker(true);
            spawned.setCustomNameVisible(true);
            spawned.setCustomName(ChatColor.YELLOW + job.displayName + ChatColor.GRAY + " - " + formatDuration(remainingSeconds(job)));
        });
        job.hologram = stand;
    }

    private void clearJobVisual(ConstructionJob job) {
        if (job.hologram != null && !job.hologram.isDead()) {
            job.hologram.remove();
        }
        if (job.builderNpc != null && !job.builderNpc.isDead()) {
            job.builderNpc.remove();
        }

        World world = Bukkit.getWorld(job.worldName);
        if (world != null) {
            for (BlockPos pos : job.fenceBlocks) {
                Block block = world.getBlockAt(pos.x, pos.y, pos.z);
                if (block.getType() == Material.OAK_FENCE) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        job.fenceBlocks.clear();
    }

    private void removeJobAndVisual(UUID playerId, JobKey key) {
        ConstructionJob removed = removeJob(playerId, key);
        if (removed != null) {
            clearJobVisual(removed);
        }
    }

    private void clearAllJobVisuals(UUID playerId) {
        Map<JobKey, ConstructionJob> jobs = activeJobs.get(playerId);
        if (jobs == null) {
            return;
        }
        for (ConstructionJob job : jobs.values()) {
            clearJobVisual(job);
        }
    }

    private void renderActiveConstruction(UUID playerId, World world) {
        Map<JobKey, ConstructionJob> jobs = activeJobs.get(playerId);
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        for (ConstructionJob job : jobs.values()) {
            spawnConstructionVisual(job, world);
        }
    }

    private void evacuatePlayersFromJobArea(ConstructionJob job) {
        World world = Bukkit.getWorld(job.worldName);
        if (world == null) {
            return;
        }

        int[] slot = slotOf(job);
        int radius = constructionRadius(job.type);
        for (Player player : world.getPlayers()) {
            Location location = player.getLocation();
            double dx = Math.abs(location.getX() - (slot[0] + 0.5));
            double dz = Math.abs(location.getZ() - (slot[1] + 0.5));
            if (dx <= radius + 0.4 && dz <= radius + 0.4 && location.getY() <= GROUND_Y + 8.0) {
                Location safe = nearestConstructionSafeLocation(location);
                if (safe == null) {
                    safe = getSafeSpawn(world);
                }
                player.teleportAsync(safe);
            }
        }
    }

    private ConstructionJob createJob(BuildingType type, JobKind kind, int slotIndex, int seconds, String worldName) {
        long endsAt = System.currentTimeMillis() + Math.max(1L, seconds) * 1000L;
        String action = kind == JobKind.BUILD ? "Building " : "Upgrading ";
        return new ConstructionJob(new JobKey(type, kind), type, slotIndex, worldName, action + type.displayName(), endsAt);
    }

    private int constructionRadius(BuildingType type) {
        return switch (type) {
            case BARRACKS, ARMY_CAMP -> 4;
            default -> 3;
        };
    }

    private UUID findOwnerByWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        for (VillageData village : villages.values()) {
            if (worldName.equals(village.getWorldName())) {
                return village.getPlayerId();
            }
        }
        return null;
    }

    private int[] slotOf(ConstructionJob job) {
        List<int[]> slots = BUILDING_SLOTS.getOrDefault(job.type, List.of(new int[]{0, 0}));
        int safeIndex = Math.max(0, Math.min(job.slotIndex, slots.size() - 1));
        return slots.get(safeIndex);
    }

    private boolean isInsideJobArea(Location location, ConstructionJob job) {
        if (!location.getWorld().getName().equals(job.worldName)) {
            return false;
        }
        int[] slot = slotOf(job);
        int radius = constructionRadius(job.type);
        double dx = Math.abs(location.getX() - (slot[0] + 0.5));
        double dz = Math.abs(location.getZ() - (slot[1] + 0.5));
        return dx <= radius + 0.4 && dz <= radius + 0.4 && location.getY() <= GROUND_Y + 8.0;
    }

    private int totalBuilders(VillageData village) {
        return Math.max(1, village.getBuildingCount(BuildingType.BUILDER_HUT));
    }

    private int busyBuilders(UUID playerId) {
        return activeJobs.getOrDefault(playerId, Collections.emptyMap()).size();
    }

    private int availableBuilders(UUID playerId, VillageData village) {
        return Math.max(0, totalBuilders(village) - busyBuilders(playerId));
    }

    private int nextBuildSlotIndex(UUID playerId, VillageData village, BuildingType type) {
        int finished = village.getBuildingCount(type);
        int inBuild = 0;
        for (JobKey key : activeJobs.getOrDefault(playerId, Collections.emptyMap()).keySet()) {
            if (key.type == type && key.kind == JobKind.BUILD) {
                inBuild++;
            }
        }
        return finished + inBuild;
    }

    private boolean isJobActive(UUID playerId, BuildingType type, JobKind kind) {
        return activeJobs.getOrDefault(playerId, Collections.emptyMap()).containsKey(new JobKey(type, kind));
    }

    private void putJob(UUID playerId, ConstructionJob job) {
        activeJobs.computeIfAbsent(playerId, key -> new LinkedHashMap<>()).put(job.key, job);
    }

    private ConstructionJob removeJob(UUID playerId, JobKey key) {
        Map<JobKey, ConstructionJob> jobs = activeJobs.get(playerId);
        if (jobs == null) {
            return null;
        }
        ConstructionJob removed = jobs.remove(key);
        if (jobs.isEmpty()) {
            activeJobs.remove(playerId);
        }
        return removed;
    }

    private long remainingSeconds(ConstructionJob job) {
        long millis = Math.max(0L, job.endsAtMillis - System.currentTimeMillis());
        return (millis + 999L) / 1000L;
    }

    private String formatDuration(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        long secs = seconds % 60;

        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0 || days > 0) {
            parts.add(hours + "h");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            parts.add(minutes + "m");
        }
        parts.add(secs + "s");
        return String.join(" ", parts);
    }

    private boolean takeCurrency(VillageData village, BalanceBook.Currency currency, long amount) {
        if (amount <= 0) {
            return true;
        }

        return switch (currency) {
            case GOLD -> village.takeGold(amount);
            case ELIXIR -> village.takeElixir(amount);
            case GEMS -> village.takeGems(amount);
        };
    }

    private String currencyName(BalanceBook.Currency currency) {
        return currency.name().toLowerCase();
    }

    private Location getSafeSpawn(World world) {
        return new Location(world, 0.5, GROUND_Y + 2.0, -10.5, 0F, 0F);
    }

    private void fillRect(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.getBlockAt(x, y, z).setType(material, false);
                }
            }
        }
    }

    private void hollowRect(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean wall = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    Block block = world.getBlockAt(x, y, z);
                    block.setType(wall ? material : Material.AIR, false);
                }
            }
        }
    }

    private static Map<BuildingType, List<int[]>> createBuildingSlots() {
        Map<BuildingType, List<int[]>> slots = new EnumMap<>(BuildingType.class);
        slots.put(BuildingType.BUILDER_HUT, List.of(new int[]{-14, -10}, new int[]{14, -10}, new int[]{-22, 8}, new int[]{22, 8}));
        slots.put(BuildingType.GOLD_MINE, List.of(new int[]{-12, 10}, new int[]{12, 10}, new int[]{-24, 0}, new int[]{24, 0}));
        slots.put(BuildingType.ELIXIR_COLLECTOR, List.of(new int[]{-10, 18}, new int[]{10, 18}, new int[]{-18, -18}, new int[]{18, -18}));
        slots.put(BuildingType.BARRACKS, List.of(new int[]{0, -20}, new int[]{0, 20}));
        slots.put(BuildingType.ARMY_CAMP, List.of(new int[]{-28, 14}, new int[]{28, 14}));
        slots.put(BuildingType.CANNON, List.of(new int[]{-26, -8}, new int[]{26, -8}));
        slots.put(BuildingType.ARCHER_TOWER, List.of(new int[]{-30, 0}, new int[]{30, 0}));
        return Collections.unmodifiableMap(slots);
    }

    private enum JobKind {
        BUILD,
        UPGRADE
    }

    private record JobKey(BuildingType type, JobKind kind) {
    }

    private record BlockPos(int x, int y, int z) {
    }

    private static final class ConstructionJob {
        private final JobKey key;
        private final BuildingType type;
        private final int slotIndex;
        private final String worldName;
        private final String displayName;
        private final long endsAtMillis;
        private final List<BlockPos> fenceBlocks = new ArrayList<>();
        private Villager builderNpc;
        private ArmorStand hologram;

        private ConstructionJob(JobKey key, BuildingType type, int slotIndex, String worldName, String displayName, long endsAtMillis) {
            this.key = key;
            this.type = type;
            this.slotIndex = slotIndex;
            this.worldName = worldName;
            this.displayName = displayName;
            this.endsAtMillis = endsAtMillis;
        }
    }
}