package io.lossai.clash.service;

import io.lossai.clash.model.BuildingType;
import io.lossai.clash.model.TroopType;
import io.lossai.clash.model.VillageData;
import io.lossai.clash.storage.VillageStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class VillageManager {

    private static final int MAX_TOWN_HALL_LEVEL = 2;
    private static final int GROUND_Y = 64;
    private static final int PLAYABLE_RADIUS = 34;
    private static final int SCENERY_RADIUS = 56;
    private static final int ARMY_CAP_PER_CAMP = 20;

    private static final Set<Material> OBSTACLE_BLOCKS = EnumSet.of(
            Material.OAK_LOG, Material.OAK_LEAVES, Material.AZALEA_LEAVES,
            Material.FLOWERING_AZALEA_LEAVES, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE
    );

    private static final Map<BuildingType, List<int[]>> BUILDING_SLOTS = createBuildingSlots();
    private static final List<int[]> WALL_SLOTS = createWallSlots();

    private final JavaPlugin plugin;
    private final VillageStore store;
    private final Map<UUID, VillageData> villages;
    private final Map<UUID, Map<String, ConstructionJob>> activeJobs = new HashMap<>();
    private final Map<UUID, List<TrainingJob>> trainingJobs = new HashMap<>();
    private final Map<UUID, ResearchJob> activeResearch = new HashMap<>();
    private final Map<UUID, Location> overviewReturnLocations = new HashMap<>();

    public VillageManager(JavaPlugin plugin, VillageStore store) {
        this.plugin = plugin;
        this.store = store;
        this.villages = new LinkedHashMap<>(store.loadAll());
    }

    public void setupVillageForPlayer(Player player) {
        VillageData village = villages.computeIfAbsent(player.getUniqueId(),
                id -> new VillageData(id, player.getName(), null, 0, 1000L, 1000L, 500L,
                        0L, 0L, false, false, Collections.emptyMap(), Collections.emptyMap(),
                        Collections.emptyMap(), Collections.emptyMap()));

        village.setPlayerName(player.getName());
        World world = getOrCreateVillageWorld(village);
        if (world == null) {
            return;
        }

        if (!village.isStarterGenerated()) {
            village.addBuilding(BuildingType.BUILDER_HUT, 1);
            village.addBuilding(BuildingType.GOLD_MINE, 1);
            village.addBuilding(BuildingType.ELIXIR_COLLECTOR, 1);
            village.setStarterGenerated(true);
        }
        if (!village.isObstaclesGenerated()) {
            generateScenery(world, village.getPlayerId());
            village.setObstaclesGenerated(true);
        }

        enforceResourceCaps(village);

        renderVillage(world, village);
        renderAllConstruction(village.getPlayerId(), world);
        updateResourceHud(player, village);
        player.teleportAsync(getSafeSpawn(world));
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
            return ChatColor.RED + "Build one structure per command.";
        }
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }
        if (type == BuildingType.WALL) {
            return buildWallInstant(player, village);
        }
        if (availableBuilders(village.getPlayerId(), village) <= 0) {
            return ChatColor.RED + "All builders are busy.";
        }
        if (BalanceBook.maxAtTownHall(type, village.getTownHallLevel()) <= 0) {
            return ChatColor.RED + "This building is locked at your Town Hall level.";
        }
        int current = village.getBuildingCount(type);
        int max = BalanceBook.maxAtTownHall(type, village.getTownHallLevel());
        if (current >= max) {
            return ChatColor.RED + "Max reached for " + type.displayName() + ".";
        }

        BalanceBook.BuildInfo info = BalanceBook.buildInfo(type, current, village.getTownHallLevel());
        if (!takeCurrency(village, info.currency(), info.cost())) {
            return ChatColor.RED + "Not enough " + currencyName(info.currency()) + ". Need " + info.cost() + ".";
        }

        int slotIndex = current + activeBuildJobsForType(village.getPlayerId(), type);
        if (type == BuildingType.WALL) {
            slotIndex = current;
        }
        if (!hasSlot(type, slotIndex)) {
            return ChatColor.RED + "No free slot available for " + type.displayName() + ".";
        }

        World world = getOrCreateVillageWorld(village);
        if (world == null) {
            return ChatColor.RED + "Could not load world.";
        }

        ConstructionJob job = createJob(village.getWorldName(), type, JobKind.BUILD, slotIndex, info.buildSeconds());
        addJob(village.getPlayerId(), job);
        spawnConstructionVisual(job, world);

        player.sendMessage(ChatColor.GOLD + "Started " + type.displayName() + " (" + formatDuration(info.buildSeconds()) + ")");
        updateResourceHud(player, village);
        store.saveAll(villages);

        scheduleJobCompletion(village.getPlayerId(), village, job, () -> {
            village.addBuilding(type, 1);
            renderVillage(world, village);
        });
        return ChatColor.GREEN + "Builder assigned to " + type.displayName() + ".";
    }

    public String upgradeBuilding(Player player, BuildingType type) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }
        if (type == BuildingType.WALL) {
            return upgradeWallInstant(player, village);
        }
        if (village.getBuildingCount(type) <= 0) {
            return ChatColor.RED + "You do not own " + type.displayName() + ".";
        }
        if (availableBuilders(village.getPlayerId(), village) <= 0) {
            return ChatColor.RED + "All builders are busy.";
        }
        if (hasActiveJob(village.getPlayerId(), type, JobKind.UPGRADE)) {
            return ChatColor.YELLOW + "Upgrade already in progress for " + type.displayName() + ".";
        }

        BalanceBook.UpgradeInfo info = BalanceBook.upgradeInfo(type, village.getBuildingLevel(type), village.getTownHallLevel());
        if (info == null) {
            return ChatColor.RED + "No upgrade available for " + type.displayName() + ".";
        }
        if (!takeCurrency(village, info.currency(), info.cost())) {
            return ChatColor.RED + "Not enough " + currencyName(info.currency()) + ".";
        }

        World world = getOrCreateVillageWorld(village);
        if (world == null) {
            return ChatColor.RED + "Could not load world.";
        }

        ConstructionJob job = createJob(village.getWorldName(), type, JobKind.UPGRADE, 0, info.seconds());
        addJob(village.getPlayerId(), job);
        spawnConstructionVisual(job, world);
        updateResourceHud(player, village);
        store.saveAll(villages);

        scheduleJobCompletion(village.getPlayerId(), village, job, () -> {
            village.setBuildingLevel(type, info.nextLevel());
            renderVillage(world, village);
        });

        return ChatColor.GREEN + "Started upgrade for " + type.displayName() + ".";
    }

    private String buildWallInstant(Player player, VillageData village) {
        int current = village.getBuildingCount(BuildingType.WALL);
        int max = BalanceBook.maxAtTownHall(BuildingType.WALL, village.getTownHallLevel());
        if (current >= max) {
            return ChatColor.RED + "Max reached for wall.";
        }
        if (!hasSlot(BuildingType.WALL, current)) {
            return ChatColor.RED + "No free wall slot available.";
        }
        BalanceBook.BuildInfo info = BalanceBook.buildInfo(BuildingType.WALL, current, village.getTownHallLevel());
        if (!takeCurrency(village, info.currency(), info.cost())) {
            return ChatColor.RED + "Not enough gold. Need " + info.cost() + ".";
        }

        village.addBuilding(BuildingType.WALL, 1);
        village.setWallSegmentLevel(current, 1);
        World world = getOrCreateVillageWorld(village);
        if (world != null) {
            renderVillage(world, village);
            world.spawnParticle(Particle.BLOCK, new Location(world, slotList(BuildingType.WALL).get(current)[0] + 0.5,
                    GROUND_Y + 1.2, slotList(BuildingType.WALL).get(current)[1] + 0.5),
                    14, 0.2, 0.2, 0.2, Material.COBBLESTONE.createBlockData());
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_PLACE, 0.8f, 1.0f);
        }
        updateResourceHud(player, village);
        store.saveAll(villages);
        return ChatColor.GREEN + "Wall placed instantly.";
    }

    private String upgradeWallInstant(Player player, VillageData village) {
        int slotIndex = village.nextUpgradeableWallIndex();
        if (slotIndex < 0) {
            return ChatColor.RED + "Build a wall first.";
        }
        int currentLevel = village.getWallSegmentLevel(slotIndex);
        int maxLevel = BalanceBook.wallMaxLevel(village.getTownHallLevel());
        if (currentLevel >= maxLevel) {
            return ChatColor.YELLOW + "All walls are at max level for your Town Hall.";
        }
        BalanceBook.UpgradeInfo info = BalanceBook.upgradeInfo(BuildingType.WALL, currentLevel, village.getTownHallLevel());
        if (info == null || !takeCurrency(village, info.currency(), info.cost())) {
            long need = info == null ? BalanceBook.wallUpgradeCost(currentLevel) : info.cost();
            return ChatColor.RED + "Not enough gold. Need " + need + ".";
        }

        village.setWallSegmentLevel(slotIndex, currentLevel + 1);
        World world = getOrCreateVillageWorld(village);
        if (world != null) {
            int[] slot = slotList(BuildingType.WALL).get(slotIndex);
            placeWall(world, slot[0], slot[1], currentLevel + 1);
            world.spawnParticle(Particle.BLOCK, new Location(world, slot[0] + 0.5, GROUND_Y + 1.2, slot[1] + 0.5),
                    18, 0.2, 0.2, 0.2, wallMaterial(currentLevel + 1).createBlockData());
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.6f);
        }
        updateResourceHud(player, village);
        store.saveAll(villages);
        return ChatColor.GREEN + "Wall upgraded to level " + (currentLevel + 1) + " instantly.";
    }

    public String upgradeTownHall(Player player) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }
        if (village.getTownHallLevel() >= MAX_TOWN_HALL_LEVEL) {
            return ChatColor.YELLOW + "Town Hall is maxed for now.";
        }

        Map<BuildingType, Integer> req = RequirementBook.requirementsForCurrentTownHall(village.getTownHallLevel());
        if (!village.meetsRequirements(req)) {
            return ChatColor.RED + "Missing required buildings: " + String.join(", ", village.missingRequirements(req));
        }

        Map<BuildingType, Integer> levelReq = RequirementBook.levelRequirementsForCurrentTownHall(village.getTownHallLevel());
        if (!village.meetsLevelRequirements(levelReq)) {
            return ChatColor.RED + "Missing required levels: " + String.join(", ", village.missingLevelRequirements(levelReq));
        }

        village.setTownHallLevel(village.getTownHallLevel() + 1);
        World world = getOrCreateVillageWorld(village);
        if (world != null) {
            renderVillage(world, village);
        }
        updateResourceHud(player, village);
        store.saveAll(villages);
        return ChatColor.GOLD + "Town Hall upgraded to " + village.getTownHallLevel() + ".";
    }

    public String teleportToVillage(Player player) {
        VillageData village = villages.computeIfAbsent(player.getUniqueId(),
                id -> new VillageData(id, player.getName(), null, 0, 1000L, 1000L, 500L,
                        0L, 0L, false, false, Collections.emptyMap(), Collections.emptyMap(),
                        Collections.emptyMap(), Collections.emptyMap()));
        World world = getOrCreateVillageWorld(village);
        if (world == null) {
            return ChatColor.RED + "Could not load world.";
        }
        renderVillage(world, village);
        renderAllConstruction(village.getPlayerId(), world);
        enforceResourceCaps(village);
        updateResourceHud(player, village);
        player.teleportAsync(getSafeSpawn(world));
        return ChatColor.GREEN + "Teleported to " + village.getWorldName() + ".";
    }

    public String openOverview(Player player) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }
        World world = getOrCreateVillageWorld(village);
        if (world == null) {
            return ChatColor.RED + "Could not load world.";
        }

        overviewReturnLocations.put(player.getUniqueId(), player.getLocation().clone());
        Location topDown = new Location(world, 0.5, GROUND_Y + 55.0, 0.5, -180f, 90f);
        player.teleportAsync(topDown);
        player.sendMessage(ChatColor.AQUA + "Base overview enabled. Use /clash overview exit to return.");
        giveOverviewMap(player, world, village);
        return ChatColor.GREEN + "Overview opened.";
    }

    public String closeOverview(Player player) {
        Location returnLocation = overviewReturnLocations.remove(player.getUniqueId());
        VillageData village = villages.get(player.getUniqueId());
        if (returnLocation == null) {
            if (village != null) {
                World world = Bukkit.getWorld(village.getWorldName());
                if (world != null) {
                    player.teleportAsync(getSafeSpawn(world));
                }
            }
            return ChatColor.YELLOW + "Overview was not active.";
        }
        player.teleportAsync(returnLocation);
        return ChatColor.GREEN + "Returned from overview.";
    }

    public List<String> describeVillage(Player player) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return List.of(ChatColor.RED + "Village not initialized.");
        }
        List<String> lines = new ArrayList<>();
        enforceResourceCaps(village);
        lines.add(ChatColor.AQUA + "Village: " + ChatColor.WHITE + village.getWorldName());
        lines.add(ChatColor.AQUA + "Town Hall: " + ChatColor.WHITE + village.getTownHallLevel());
        lines.add(ChatColor.GOLD + "Gold: " + ChatColor.WHITE + village.getGold() + ChatColor.GRAY + " (stored)");
        lines.add(ChatColor.LIGHT_PURPLE + "Elixir: " + ChatColor.WHITE + village.getElixir() + ChatColor.GRAY + " (stored)");
        lines.add(ChatColor.YELLOW + "Collectable gold: " + ChatColor.WHITE + village.getPendingGold());
        lines.add(ChatColor.LIGHT_PURPLE + "Collectable elixir: " + ChatColor.WHITE + village.getPendingElixir());
        lines.add(ChatColor.AQUA + "Gems: " + ChatColor.WHITE + village.getGems());
        lines.add(ChatColor.GREEN + "Builders: " + availableBuilders(village.getPlayerId(), village) + "/" + totalBuilders(village));
        lines.add(ChatColor.AQUA + "Army: " + armyHousingUsed(village) + "/" + armyCapacity(village));
        for (Map.Entry<TroopType, Integer> troop : village.getTroopsSnapshot().entrySet()) {
            if (troop.getValue() > 0) {
                lines.add(ChatColor.GRAY + " - " + troop.getKey().displayName() + " x" + troop.getValue()
                        + " (lvl " + village.getTroopLevel(troop.getKey()) + ")");
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

    public List<String> getBuildTabSuggestions(Player player) {
        VillageData village = villages.get(player.getUniqueId());
        int townHallLevel = village == null ? 0 : village.getTownHallLevel();
        Set<BuildingType> allowed = getBuildableTypes(townHallLevel);

        List<String> names = new ArrayList<>();
        if (village != null) {
            Map<BuildingType, Integer> requirements = RequirementBook.requirementsForCurrentTownHall(townHallLevel);
            for (Map.Entry<BuildingType, Integer> entry : requirements.entrySet()) {
                BuildingType type = entry.getKey();
                if (!allowed.contains(type)) {
                    continue;
                }
                if (village.getBuildingCount(type) < entry.getValue()) {
                    names.add(type.name().toLowerCase(Locale.ROOT));
                }
            }
        }

        for (BuildingType type : allowed) {
            String name = type.name().toLowerCase(Locale.ROOT);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public String trainTroop(Player player, TroopType type, int amount) {
        if (amount <= 0) {
            return ChatColor.RED + "Amount must be greater than 0.";
        }
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }
        if (village.getBuildingCount(BuildingType.BARRACKS) <= 0) {
            return ChatColor.RED + "Build a Barracks first.";
        }
        TroopBook.TroopInfo info = TroopBook.info(type);
        if (village.getBuildingLevel(BuildingType.BARRACKS) < info.barracksLevelRequired()) {
            return ChatColor.RED + "Barracks level too low for this troop.";
        }

        int queueSpace = armyCapacity(village) - (armyHousingUsed(village) + queuedHousing(player.getUniqueId()));
        int possible = Math.min(amount, queueSpace / info.housingSpace());
        if (possible <= 0) {
            return ChatColor.RED + "Army camps are full or queue is full.";
        }

        long cost = info.trainElixir() * possible;
        if (!village.takeElixir(cost)) {
            return ChatColor.RED + "Not enough elixir. Need " + cost + ".";
        }

        long start = System.currentTimeMillis();
        List<TrainingJob> queue = trainingJobs.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>());
        long lastEnd = queue.isEmpty() ? start : queue.get(queue.size() - 1).endsAtMillis;
        for (int i = 0; i < possible; i++) {
            lastEnd += info.trainSeconds() * 1000L;
            queue.add(new TrainingJob(type, lastEnd));
        }
        updateResourceHud(player, village);
        store.saveAll(villages);
        return ChatColor.GREEN + "Training " + possible + " " + type.displayName() + ".";
    }

    public String startResearch(Player player, TroopType type) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }
        if (village.getBuildingCount(BuildingType.LABORATORY) <= 0) {
            return ChatColor.RED + "Build Laboratory first.";
        }
        if (activeResearch.containsKey(player.getUniqueId())) {
            return ChatColor.YELLOW + "Laboratory is busy.";
        }
        int currentLevel = village.getTroopLevel(type);
        if (currentLevel >= 2) {
            return ChatColor.YELLOW + "Research maxed for this demo.";
        }
        long cost = 1000L;
        if (!village.takeElixir(cost)) {
            return ChatColor.RED + "Need " + cost + " elixir for research.";
        }
        long end = System.currentTimeMillis() + 60000L;
        activeResearch.put(player.getUniqueId(), new ResearchJob(type, currentLevel + 1, end));
        updateResourceHud(player, village);
        store.saveAll(villages);
        return ChatColor.GREEN + "Research started for " + type.displayName() + ".";
    }

    public String finishNow(Player player, String target) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }
        String key = target.toLowerCase(Locale.ROOT);

        if (key.equals("training")) {
            List<TrainingJob> queue = trainingJobs.getOrDefault(player.getUniqueId(), List.of());
            if (queue.isEmpty()) {
                return ChatColor.YELLOW + "No troop training in progress.";
            }
            long remaining = Math.max(0L, queue.get(queue.size() - 1).endsAtMillis - System.currentTimeMillis());
            long gems = Math.max(1L, (remaining + 59999L) / 60000L);
            if (!village.takeGems(gems)) {
                return ChatColor.RED + "Need " + gems + " gems.";
            }
            for (TrainingJob job : queue) {
                village.addTroops(job.type, 1);
            }
            queue.clear();
            updateResourceHud(player, village);
            store.saveAll(villages);
            return ChatColor.GREEN + "Training finished instantly for " + gems + " gems.";
        }

        if (key.equals("research")) {
            ResearchJob job = activeResearch.get(player.getUniqueId());
            if (job == null) {
                return ChatColor.YELLOW + "No research active.";
            }
            long remaining = Math.max(0L, job.endsAtMillis - System.currentTimeMillis());
            long gems = Math.max(1L, (remaining + 59999L) / 60000L);
            if (!village.takeGems(gems)) {
                return ChatColor.RED + "Need " + gems + " gems.";
            }
            village.setTroopLevel(job.troop, job.nextLevel);
            activeResearch.remove(player.getUniqueId());
            updateResourceHud(player, village);
            store.saveAll(villages);
            return ChatColor.GREEN + "Research finished instantly.";
        }

        BuildingType type = BuildingType.fromInput(target).orElse(null);
        if (type == null) {
            return ChatColor.RED + "Unknown finish target.";
        }

        ConstructionJob job = findFirstJobByType(player.getUniqueId(), type);
        if (job == null) {
            return ChatColor.YELLOW + "No active build/upgrade for " + type.displayName() + ".";
        }
        long remaining = Math.max(0L, job.endsAtMillis - System.currentTimeMillis());
        long gems = Math.max(1L, (remaining + 59999L) / 60000L);
        if (!village.takeGems(gems)) {
            return ChatColor.RED + "Need " + gems + " gems.";
        }

        completeJob(player.getUniqueId(), village, job, true);
        if (job.kind == JobKind.BUILD) {
            village.addBuilding(job.type, 1);
        } else if (job.kind == JobKind.UPGRADE) {
            village.setBuildingLevel(job.type, village.getBuildingLevel(job.type) + 1);
        }
        World world = Bukkit.getWorld(village.getWorldName());
        if (world != null) {
            renderVillage(world, village);
            renderAllConstruction(player.getUniqueId(), world);
        }
        updateResourceHud(player, village);
        store.saveAll(villages);
        return ChatColor.GREEN + "Finished " + type.displayName() + " instantly for " + gems + " gems.";
    }

    public String collectResources(Player player) {
        return collectResources(player, null);
    }

    public String collectResources(Player player, BuildingType sourceType) {
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }
        long beforeGold = village.getGold();
        long beforeElixir = village.getElixir();
        if (sourceType == BuildingType.GOLD_MINE) {
            village.collectGold(goldStorageCap(village));
        } else if (sourceType == BuildingType.ELIXIR_COLLECTOR) {
            village.collectElixir(elixirStorageCap(village));
        } else {
            village.collectGold(goldStorageCap(village));
            village.collectElixir(elixirStorageCap(village));
        }
        village.clampStoredResources(goldStorageCap(village), elixirStorageCap(village));
        long gainedGold = village.getGold() - beforeGold;
        long gainedElixir = village.getElixir() - beforeElixir;
        if (gainedGold > 0 || gainedElixir > 0) {
            World world = Bukkit.getWorld(village.getWorldName());
            if (world != null) {
                world.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 12, 0.35, 0.35, 0.35, 0.0);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.1f);
            }
        }
        refreshCollectorVisuals(village);
        updateResourceHud(player, village);
        store.saveAll(villages);
        return ChatColor.GREEN + "Collected " + gainedGold + " gold and " + gainedElixir + " elixir.";
    }

    public String handleVillageInteract(Player player, Block clicked) {
        if (clicked == null || !isVillageWorld(clicked.getWorld())) {
            return null;
        }
        VillageData village = villages.get(player.getUniqueId());
        if (village == null) {
            return ChatColor.RED + "Village not initialized.";
        }

        if (OBSTACLE_BLOCKS.contains(clicked.getType()) && isScenery(clicked.getLocation())) {
            int removed = clearObstacleCluster(clicked);
            if (removed > 0) {
                int gems = 1 + new Random().nextInt(4);
                village.addGems(gems);
                updateResourceHud(player, village);
                store.saveAll(villages);
                return ChatColor.GREEN + "Obstacle removed. Gems found: " + gems;
            }
        }

        BuildingHit hit = resolveBuildingHit(village, clicked.getLocation());
        if (hit == null) {
            return null;
        }

        if (hit.type == BuildingType.GOLD_MINE || hit.type == BuildingType.ELIXIR_COLLECTOR) {
            String collected = collectResources(player, hit.type);
            openBuildingInfo(player, village, hit);
            return collected;
        }

        openBuildingInfo(player, village, hit);
        return null;
    }

    public void tickResourceGeneration() {
        boolean changed = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            VillageData village = villages.get(player.getUniqueId());
            if (village == null) {
                continue;
            }

            long goldRate = (long) village.getBuildingCount(BuildingType.GOLD_MINE)
                    * (village.getBuildingLevel(BuildingType.GOLD_MINE) <= 1 ? 1L : 2L);
            long elixirRate = (long) village.getBuildingCount(BuildingType.ELIXIR_COLLECTOR)
                    * (village.getBuildingLevel(BuildingType.ELIXIR_COLLECTOR) <= 1 ? 1L : 2L);

            village.addPendingGold(goldRate, mineCollectorCap(village, BuildingType.GOLD_MINE));
            village.addPendingElixir(elixirRate, mineCollectorCap(village, BuildingType.ELIXIR_COLLECTOR));
            village.clampPendingResources(mineCollectorCap(village, BuildingType.GOLD_MINE),
                    mineCollectorCap(village, BuildingType.ELIXIR_COLLECTOR));
            long overflow = village.clampStoredResources(goldStorageCap(village), elixirStorageCap(village));
            if (overflow > 0) {
                player.sendActionBar(ChatColor.RED + "Storages full: overflow prevented.");
            }
            refreshCollectorVisuals(village);
            updateResourceHud(player, village);
            changed = true;
        }
        if (changed) {
            store.saveAll(villages);
        }
    }

    public void tickConstructionVisuals() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Map<String, ConstructionJob>> entry : activeJobs.entrySet()) {
            UUID ownerId = entry.getKey();
            VillageData village = villages.get(ownerId);
            for (ConstructionJob job : entry.getValue().values()) {
                if (job.hologram != null && !job.hologram.isDead()) {
                    long remain = Math.max(0L, (job.endsAtMillis - now + 999L) / 1000L);
                    job.hologram.setCustomName(ChatColor.YELLOW + job.displayName + ChatColor.GRAY + " - " + formatDuration(remain));
                }
            }
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null && village != null) {
                updateResourceHud(owner, village);
            }
        }

        for (Map.Entry<UUID, List<TrainingJob>> entry : trainingJobs.entrySet()) {
            VillageData village = villages.get(entry.getKey());
            if (village == null) {
                continue;
            }
            List<TrainingJob> queue = entry.getValue();
            int trained = 0;
            while (!queue.isEmpty() && queue.get(0).endsAtMillis <= now) {
                TrainingJob done = queue.remove(0);
                village.addTroops(done.type, 1);
                trained++;
            }
            if (trained > 0) {
                Player owner = Bukkit.getPlayer(entry.getKey());
                if (owner != null) {
                    owner.sendMessage(ChatColor.GREEN + "Troops ready: +" + trained);
                    updateResourceHud(owner, village);
                }
            }
        }

        for (Map.Entry<UUID, ResearchJob> entry : new HashMap<>(activeResearch).entrySet()) {
            if (entry.getValue().endsAtMillis > now) {
                continue;
            }
            VillageData village = villages.get(entry.getKey());
            if (village == null) {
                continue;
            }
            ResearchJob job = entry.getValue();
            village.setTroopLevel(job.troop, job.nextLevel);
            activeResearch.remove(entry.getKey());
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner != null) {
                owner.sendMessage(ChatColor.GREEN + job.troop.displayName() + " upgraded to level " + job.nextLevel + ".");
                updateResourceHud(owner, village);
            }
        }

        store.saveAll(villages);
    }

    public void tickArcherTowerDefense() {
        for (VillageData village : villages.values()) {
            World world = Bukkit.getWorld(village.getWorldName());
            if (world == null) {
                continue;
            }
            int count = Math.min(village.getBuildingCount(BuildingType.ARCHER_TOWER), slotList(BuildingType.ARCHER_TOWER).size());
            for (int i = 0; i < count; i++) {
                int[] slot = slotList(BuildingType.ARCHER_TOWER).get(i);
                Location source = new Location(world, slot[0] + 0.5, GROUND_Y + 7.2, slot[1] + 0.5);
                fireArcherTower(source, 18.0);
            }
        }
    }

    public void tickCannonDefense() {
        for (VillageData village : villages.values()) {
            World world = Bukkit.getWorld(village.getWorldName());
            if (world == null) {
                continue;
            }
            int count = Math.min(village.getBuildingCount(BuildingType.CANNON), slotList(BuildingType.CANNON).size());
            for (int i = 0; i < count; i++) {
                int[] slot = slotList(BuildingType.CANNON).get(i);
                fireCannon(world, slot[0], slot[1], 14.0);
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
        return location.getX() * location.getX() + location.getZ() * location.getZ() <= PLAYABLE_RADIUS * PLAYABLE_RADIUS;
    }

    public boolean isInsideConstructionZone(Location location) {
        if (location == null || location.getWorld() == null || !isVillageWorld(location.getWorld())) {
            return false;
        }
        UUID owner = findOwnerByWorld(location.getWorld().getName());
        if (owner == null) {
            return false;
        }
        for (ConstructionJob job : activeJobs.getOrDefault(owner, Collections.emptyMap()).values()) {
            if (isInsideJob(job, location)) {
                return true;
            }
        }
        return false;
    }

    public Location nearestConstructionSafeLocation(Location from) {
        if (from == null || from.getWorld() == null) {
            return from;
        }
        UUID owner = findOwnerByWorld(from.getWorld().getName());
        if (owner == null) {
            return nearestPlayableLocation(from.getWorld(), from);
        }
        ConstructionJob nearest = null;
        double dist = Double.MAX_VALUE;
        for (ConstructionJob job : activeJobs.getOrDefault(owner, Collections.emptyMap()).values()) {
            Location center = jobCenter(from.getWorld(), job);
            double d2 = center.distanceSquared(from);
            if (d2 < dist) {
                dist = d2;
                nearest = job;
            }
        }
        if (nearest == null) {
            return nearestPlayableLocation(from.getWorld(), from);
        }
        Location center = jobCenter(from.getWorld(), nearest);
        Vector out = from.toVector().subtract(center.toVector()).setY(0);
        if (out.lengthSquared() < 0.001) {
            out = new Vector(1, 0, 0);
        }
        out.normalize().multiply(constructionRadius(nearest.type) + 2);
        return nearestPlayableLocation(from.getWorld(), center.clone().add(out).add(0, 1, 0));
    }

    public Location nearestPlayableLocation(World world, Location from) {
        if (world == null || from == null) {
            return null;
        }
        Vector v = new Vector(from.getX(), 0, from.getZ());
        double max = PLAYABLE_RADIUS - 2.0;
        if (v.lengthSquared() > max * max) {
            v.normalize().multiply(max);
        }
        return new Location(world, v.getX() + 0.5, GROUND_Y + 2.0, v.getZ() + 0.5, from.getYaw(), from.getPitch());
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(activeJobs.keySet())) {
            for (ConstructionJob job : activeJobs.getOrDefault(id, Collections.emptyMap()).values()) {
                clearJobVisual(job);
            }
        }
        store.saveAll(villages);
    }

    private void scheduleJobCompletion(UUID playerId, VillageData village, ConstructionJob job, Runnable apply) {
        long delayTicks = Math.max(1L, ((job.endsAtMillis - System.currentTimeMillis()) / 50L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!hasJob(playerId, job.id)) {
                return;
            }
            completeJob(playerId, village, job, false);
            apply.run();
            World world = Bukkit.getWorld(village.getWorldName());
            if (world != null) {
                renderVillage(world, village);
                renderAllConstruction(playerId, world);
            }
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                updateResourceHud(online, village);
            }
            store.saveAll(villages);
        }, delayTicks);
    }

    private void completeJob(UUID playerId, VillageData village, ConstructionJob job, boolean immediate) {
        removeJob(playerId, job.id);
        clearJobVisual(job);
        evacuatePlayersFromArea(job);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(ChatColor.GREEN + (immediate ? "Instantly finished " : "Completed ") + job.type.displayName() + ".");
        }
    }

    private void fireArcherTower(Location source, double range) {
        Monster target = findNearestMonster(source.getWorld(), source, range, false);
        if (target == null) {
            return;
        }
        Vector flat = target.getLocation().toVector().subtract(source.toVector()).setY(0);
        if (flat.lengthSquared() < 0.001) {
            flat = new Vector(1, 0, 0);
        }
        flat.normalize();
        Location launch = source.clone().add(flat.multiply(2.3)).add(0, 0.2, 0);
        Vector dir = target.getLocation().add(0, 1.1, 0).toVector().subtract(launch.toVector()).normalize();
        AbstractArrow arrow = source.getWorld().spawnArrow(launch, dir, 3.2f, 0.0f);
        arrow.setDamage(3.0);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        target.damage(1.0);
    }

    private void fireCannon(World world, int cx, int cz, double range) {
        Location source = new Location(world, cx + 0.5, GROUND_Y + 2.5, cz + 0.5);
        Monster target = findNearestMonster(world, source, range, true);
        if (target == null) {
            return;
        }
        Vector flat = target.getLocation().toVector().subtract(source.toVector()).setY(0);
        if (flat.lengthSquared() < 0.001) {
            flat = new Vector(1, 0, 0);
        }
        rotateCannonBlock(world, cx, cz, faceFromVector(flat));
        Vector dir = target.getLocation().add(0, 0.7, 0).toVector().subtract(source.toVector()).normalize();
        Location launch = source.clone().add(dir.clone().multiply(1.8));
        AbstractArrow shell = world.spawnArrow(launch, dir, 3.8f, 0.01f);
        shell.setDamage(6.0);
        shell.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        target.damage(4.0);
    }

    private Monster findNearestMonster(World world, Location source, double range, boolean groundOnly) {
        if (world == null) {
            return null;
        }
        Monster nearest = null;
        double nearestD2 = Double.MAX_VALUE;
        for (Entity entity : world.getNearbyEntities(source, range, range, range)) {
            if (!(entity instanceof Monster monster)) {
                continue;
            }
            if (groundOnly && !monster.isOnGround()) {
                continue;
            }
            double d2 = monster.getLocation().distanceSquared(source);
            if (d2 < nearestD2) {
                nearestD2 = d2;
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
        if (block.getBlockData() instanceof Directional directional) {
            directional.setFacing(face);
            block.setBlockData(directional, false);
        }
    }

    private BlockFace faceFromVector(Vector vector) {
        if (Math.abs(vector.getX()) >= Math.abs(vector.getZ())) {
            return vector.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return vector.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void updateResourceHud(Player player, VillageData village) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective("clashhud", "dummy", ChatColor.GOLD + "Clash Village");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int totalBuilders = totalBuilders(village);
        int busy = busyBuilders(village.getPlayerId());
        int free = Math.max(0, totalBuilders - busy);

        objective.getScore(ChatColor.YELLOW + "Town Hall: " + ChatColor.WHITE + village.getTownHallLevel()).setScore(10);
        objective.getScore(ChatColor.GOLD + "Gold: " + ChatColor.WHITE + village.getGold() + "/" + goldStorageCap(village)).setScore(9);
        objective.getScore(ChatColor.LIGHT_PURPLE + "Elixir: " + ChatColor.WHITE + village.getElixir() + "/" + elixirStorageCap(village)).setScore(8);
        objective.getScore(ChatColor.GRAY + "Mine bank: " + village.getPendingGold()).setScore(7);
        objective.getScore(ChatColor.GRAY + "Collector bank: " + village.getPendingElixir()).setScore(6);
        objective.getScore(ChatColor.AQUA + "Gems: " + ChatColor.WHITE + village.getGems()).setScore(5);
        objective.getScore(ChatColor.GREEN + "Builders free: " + ChatColor.WHITE + free + ChatColor.GRAY + "/" + totalBuilders).setScore(4);
        objective.getScore(ChatColor.RED + "Builders busy: " + ChatColor.WHITE + busy).setScore(3);
        objective.getScore(ChatColor.BLUE + "Army: " + ChatColor.WHITE + armyHousingUsed(village) + "/" + armyCapacity(village)).setScore(2);
        objective.getScore(ChatColor.DARK_GRAY + " ").setScore(1);
        player.setScoreboard(board);
        updateTabOverlay(player, village);
    }

    private void updateTabOverlay(Player player, VillageData village) {
        Map<BuildingType, Integer> req = RequirementBook.requirementsForCurrentTownHall(village.getTownHallLevel());
        Map<BuildingType, Integer> levelReq = RequirementBook.levelRequirementsForCurrentTownHall(village.getTownHallLevel());

        StringBuilder footer = new StringBuilder();
        footer.append(ChatColor.YELLOW).append("TH ")
                .append(village.getTownHallLevel())
                .append(" -> ")
                .append(village.getTownHallLevel() + 1)
                .append(ChatColor.GRAY)
                .append(" requirements");

        if (req.isEmpty() && levelReq.isEmpty()) {
            footer.append("\n").append(ChatColor.GREEN).append("No requirements left.");
        } else {
            for (Map.Entry<BuildingType, Integer> entry : req.entrySet()) {
                int have = village.getBuildingCount(entry.getKey());
                boolean done = have >= entry.getValue();
                footer.append("\n")
                        .append(done ? ChatColor.GREEN : ChatColor.RED)
                        .append(entry.getKey().displayName())
                        .append(ChatColor.GRAY)
                        .append(" ")
                        .append(have)
                        .append("/")
                        .append(entry.getValue());
            }
            for (Map.Entry<BuildingType, Integer> entry : levelReq.entrySet()) {
                int have = village.getBuildingLevel(entry.getKey());
                boolean done = village.getBuildingCount(entry.getKey()) > 0 && have >= entry.getValue();
                footer.append("\n")
                        .append(done ? ChatColor.GREEN : ChatColor.RED)
                        .append(entry.getKey().displayName())
                        .append(ChatColor.GRAY)
                        .append(" lvl ")
                        .append(have)
                        .append("/")
                        .append(entry.getValue());
            }
        }

        String header = ChatColor.GOLD + "Clash Village" + ChatColor.GRAY
                + " | TH " + village.getTownHallLevel()
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Builders "
                + availableBuilders(village.getPlayerId(), village) + "/" + totalBuilders(village);
        player.setPlayerListHeaderFooter(header, footer.toString());
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
        World created = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .createWorld();
        if (created == null) {
            return null;
        }
        created.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        created.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        created.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        created.setTime(1000L);
        created.setSpawnLocation(0, GROUND_Y + 2, -10);
        WorldBorder border = created.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(140.0);
        return created;
    }

    private void renderVillage(World world, VillageData village) {
        clearAbove(world);
        flatten(world);
        placeBoundary(world);
        placeTownHall(world, village.getTownHallLevel());
        for (Map.Entry<BuildingType, Integer> entry : village.getBuildingsSnapshot().entrySet()) {
            placeBuildings(world, village, entry.getKey(), entry.getValue(), village.getBuildingLevel(entry.getKey()));
        }
        refreshCollectorVisuals(village);
    }

    private void renderAllConstruction(UUID playerId, World world) {
        for (ConstructionJob job : activeJobs.getOrDefault(playerId, Collections.emptyMap()).values()) {
            spawnConstructionVisual(job, world);
        }
    }

    private void flatten(World world) {
        for (int x = -SCENERY_RADIUS; x <= SCENERY_RADIUS; x++) {
            for (int z = -SCENERY_RADIUS; z <= SCENERY_RADIUS; z++) {
                if (x * x + z * z > SCENERY_RADIUS * SCENERY_RADIUS) {
                    continue;
                }
                world.getBlockAt(x, GROUND_Y - 1, z).setType(Material.DIRT, false);
                world.getBlockAt(x, GROUND_Y, z).setType(Material.GRASS_BLOCK, false);
            }
        }
    }

    private void clearAbove(World world) {
        for (int x = -PLAYABLE_RADIUS; x <= PLAYABLE_RADIUS; x++) {
            for (int z = -PLAYABLE_RADIUS; z <= PLAYABLE_RADIUS; z++) {
                if (x * x + z * z > PLAYABLE_RADIUS * PLAYABLE_RADIUS) {
                    continue;
                }
                for (int y = GROUND_Y + 1; y <= GROUND_Y + 14; y++) {
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

    private void generateScenery(World world, UUID seed) {
        Random random = new Random(seed.getMostSignificantBits() ^ seed.getLeastSignificantBits());
        for (int i = 0; i < 50; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = PLAYABLE_RADIUS + 8 + random.nextDouble() * 14;
            int x = (int) Math.round(Math.cos(angle) * radius);
            int z = (int) Math.round(Math.sin(angle) * radius);
            int style = random.nextInt(3);
            if (style == 0) {
                placeTree(world, x, z);
            } else if (style == 1) {
                placeRock(world, x, z);
            } else {
                placeBush(world, x, z);
            }
        }
    }

    private void placeTree(World world, int x, int z) {
        int y = GROUND_Y + 1;
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
    }

    private void placeRock(World world, int x, int z) {
        int y = GROUND_Y + 1;
        world.getBlockAt(x, y, z).setType(Material.COBBLESTONE, false);
        world.getBlockAt(x + 1, y, z).setType(Material.MOSSY_COBBLESTONE, false);
        world.getBlockAt(x, y, z + 1).setType(Material.STONE, false);
    }

    private void placeBush(World world, int x, int z) {
        int y = GROUND_Y + 1;
        world.getBlockAt(x, y, z).setType(Material.FLOWERING_AZALEA_LEAVES, false);
        world.getBlockAt(x + 1, y, z).setType(Material.AZALEA_LEAVES, false);
        world.getBlockAt(x - 1, y, z).setType(Material.AZALEA_LEAVES, false);
    }

    private void placeTownHall(World world, int level) {
        Material wall = switch (level) {
            case 0 -> Material.OAK_PLANKS;
            case 1 -> Material.STONE_BRICKS;
            default -> Material.POLISHED_DEEPSLATE;
        };
        Material roof = switch (level) {
            case 0 -> Material.DARK_OAK_STAIRS;
            case 1 -> Material.BRICKS;
            default -> Material.DEEPSLATE_TILES;
        };
        int y = GROUND_Y + 1;
        fill(world, -3, y, -3, 3, y, 3, Material.COBBLESTONE);
        hollow(world, -2, y + 1, -2, 2, y + 4, 2, wall);
        fill(world, -3, y + 5, -3, 3, y + 5, 3, roof);
        world.getBlockAt(0, y + 1, -2).setType(Material.AIR, false);
        world.getBlockAt(0, y + 2, -2).setType(Material.AIR, false);
    }

    private void placeBuildings(World world, VillageData village, BuildingType type, int count, int level) {
        List<int[]> slots = slotList(type);
        int limit = Math.min(count, slots.size());
        for (int i = 0; i < limit; i++) {
            int[] slot = slots.get(i);
            int x = slot[0];
            int z = slot[1];
            switch (type) {
                case BUILDER_HUT -> placeBuilderHut(world, x, z);
                case GOLD_MINE -> placeGoldMine(world, x, z, level, i, village);
                case ELIXIR_COLLECTOR -> placeElixirCollector(world, x, z, level, i, village);
                case GOLD_STORAGE -> placeGoldStorage(world, x, z, level);
                case ELIXIR_STORAGE -> placeElixirStorage(world, x, z, level);
                case BARRACKS -> placeBarracks(world, x, z);
                case ARMY_CAMP -> placeArmyCamp(world, x, z);
                case LABORATORY -> placeLaboratory(world, x, z);
                case CANNON -> placeCannon(world, x, z);
                case ARCHER_TOWER -> placeArcherTower(world, x, z);
                case WALL -> placeWall(world, x, z, village.getWallSegmentLevel(i));
            }
        }
    }

    private void placeBuilderHut(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fill(world, cx - 2, y, cz - 2, cx + 2, y, cz + 2, Material.OAK_PLANKS);
        hollow(world, cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Material.SPRUCE_PLANKS);
        fill(world, cx - 2, y + 3, cz - 2, cx + 2, y + 3, cz + 2, Material.SPRUCE_SLAB);
    }

    private void placeGoldMine(World world, int cx, int cz, int level, int slotIndex, VillageData village) {
        int y = GROUND_Y + 1;
        fill(world, cx - 2, y, cz - 2, cx + 2, y, cz + 2, Material.STONE_BRICKS);
        fill(world, cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Material.SMOOTH_STONE);
        world.getBlockAt(cx, y + 2, cz).setType(level >= 2 ? Material.RAW_GOLD_BLOCK : Material.GOLD_BLOCK, false);
        updateMineFillVisual(world, cx, cz, slotIndex, village);
    }

    private void placeElixirCollector(World world, int cx, int cz, int level, int slotIndex, VillageData village) {
        int y = GROUND_Y + 1;
        fill(world, cx - 2, y, cz - 2, cx + 2, y, cz + 2, Material.POLISHED_ANDESITE);
        fill(world, cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Material.AMETHYST_BLOCK);
        world.getBlockAt(cx, y + 3, cz).setType(level >= 2 ? Material.PURPLE_STAINED_GLASS : Material.MAGENTA_STAINED_GLASS, false);
        updateCollectorFillVisual(world, cx, cz, slotIndex, village);
    }

    private void placeGoldStorage(World world, int cx, int cz, int level) {
        int y = GROUND_Y + 1;
        fill(world, cx - 2, y, cz - 2, cx + 2, y, cz + 2, Material.SMOOTH_SANDSTONE);
        fill(world, cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Material.CHEST);
        world.getBlockAt(cx, y + 3, cz).setType(level >= 2 ? Material.GOLD_BLOCK : Material.COPPER_BLOCK, false);
    }

    private void placeElixirStorage(World world, int cx, int cz, int level) {
        int y = GROUND_Y + 1;
        fill(world, cx - 2, y, cz - 2, cx + 2, y, cz + 2, Material.CALCITE);
        fill(world, cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1, Material.GLASS);
        world.getBlockAt(cx, y + 3, cz).setType(level >= 2 ? Material.PURPLE_CONCRETE : Material.MAGENTA_CONCRETE, false);
    }

    private void placeBarracks(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fill(world, cx - 3, y, cz - 3, cx + 3, y, cz + 3, Material.STONE_BRICKS);
        hollow(world, cx - 2, y + 1, cz - 2, cx + 2, y + 3, cz + 2, Material.BRICKS);
        fill(world, cx - 3, y + 4, cz - 3, cx + 3, y + 4, cz + 3, Material.RED_WOOL);
    }

    private void placeArmyCamp(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fill(world, cx - 3, y, cz - 3, cx + 3, y, cz + 3, Material.PACKED_MUD);
        for (int x = cx - 3; x <= cx + 3; x++) {
            world.getBlockAt(x, y + 1, cz - 3).setType(Material.OAK_FENCE, false);
            world.getBlockAt(x, y + 1, cz + 3).setType(Material.OAK_FENCE, false);
        }
    }

    private void placeLaboratory(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fill(world, cx - 3, y, cz - 3, cx + 3, y, cz + 3, Material.QUARTZ_BLOCK);
        hollow(world, cx - 2, y + 1, cz - 2, cx + 2, y + 3, cz + 2, Material.WHITE_STAINED_GLASS);
        world.getBlockAt(cx, y + 4, cz).setType(Material.ENCHANTING_TABLE, false);
    }

    private void placeCannon(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fill(world, cx - 1, y, cz - 1, cx + 1, y, cz + 1, Material.STONE);
        world.getBlockAt(cx, y + 1, cz).setType(Material.DISPENSER, false);
        world.getBlockAt(cx, y + 1, cz - 1).setType(Material.IRON_BARS, false);
        world.getBlockAt(cx, y + 1, cz + 1).setType(Material.STONE_BRICK_WALL, false);
    }

    private void placeArcherTower(World world, int cx, int cz) {
        int y = GROUND_Y + 1;
        fill(world, cx - 1, y, cz - 1, cx + 1, y, cz + 1, Material.COBBLESTONE);
        for (int i = 1; i <= 4; i++) {
            world.getBlockAt(cx, y + i, cz).setType(Material.SPRUCE_LOG, false);
        }
        fill(world, cx - 1, y + 5, cz - 1, cx + 1, y + 5, cz + 1, Material.SPRUCE_PLANKS);
    }

    private void placeWall(World world, int cx, int cz, int level) {
        world.getBlockAt(cx, GROUND_Y + 1, cz).setType(wallMaterial(level), false);
    }

    private Material wallMaterial(int level) {
        return switch (Math.max(1, level)) {
            case 1 -> Material.COBBLESTONE_WALL;
            case 2 -> Material.STONE_BRICK_WALL;
            case 3 -> Material.MOSSY_STONE_BRICK_WALL;
            case 4 -> Material.DEEPSLATE_BRICK_WALL;
            case 5 -> Material.POLISHED_BLACKSTONE_BRICK_WALL;
            default -> Material.NETHER_BRICK_WALL;
        };
    }

    private void refreshCollectorVisuals(VillageData village) {
        World world = Bukkit.getWorld(village.getWorldName());
        if (world == null) {
            return;
        }
        int mineCount = Math.min(village.getBuildingCount(BuildingType.GOLD_MINE), slotList(BuildingType.GOLD_MINE).size());
        for (int i = 0; i < mineCount; i++) {
            int[] slot = slotList(BuildingType.GOLD_MINE).get(i);
            updateMineFillVisual(world, slot[0], slot[1], i, village);
        }
        int collectorCount = Math.min(village.getBuildingCount(BuildingType.ELIXIR_COLLECTOR), slotList(BuildingType.ELIXIR_COLLECTOR).size());
        for (int i = 0; i < collectorCount; i++) {
            int[] slot = slotList(BuildingType.ELIXIR_COLLECTOR).get(i);
            updateCollectorFillVisual(world, slot[0], slot[1], i, village);
        }
    }

    private void updateMineFillVisual(World world, int cx, int cz, int slotIndex, VillageData village) {
        long totalCap = Math.max(1L, mineCollectorCap(village, BuildingType.GOLD_MINE));
        int mineCount = Math.max(1, village.getBuildingCount(BuildingType.GOLD_MINE));
        long perCap = Math.max(1L, totalCap / mineCount);
        long pendingPerMine = village.getPendingGold() / mineCount;
        double ratio = Math.min(1.0, (double) pendingPerMine / (double) perCap);
        Material fill = ratio >= 0.85 ? Material.RAW_GOLD_BLOCK : (ratio >= 0.5 ? Material.GOLD_BLOCK : Material.YELLOW_STAINED_GLASS);
        world.getBlockAt(cx + 1, GROUND_Y + 3, cz).setType(fill, false);
    }

    private void updateCollectorFillVisual(World world, int cx, int cz, int slotIndex, VillageData village) {
        long totalCap = Math.max(1L, mineCollectorCap(village, BuildingType.ELIXIR_COLLECTOR));
        int collectorCount = Math.max(1, village.getBuildingCount(BuildingType.ELIXIR_COLLECTOR));
        long perCap = Math.max(1L, totalCap / collectorCount);
        long pendingPerCollector = village.getPendingElixir() / collectorCount;
        double ratio = Math.min(1.0, (double) pendingPerCollector / (double) perCap);
        Material fill = ratio >= 0.85 ? Material.PURPLE_STAINED_GLASS : (ratio >= 0.5 ? Material.MAGENTA_STAINED_GLASS : Material.PINK_STAINED_GLASS);
        world.getBlockAt(cx - 1, GROUND_Y + 3, cz).setType(fill, false);
    }

    private void spawnConstructionVisual(ConstructionJob job, World world) {
        Location center = jobCenter(world, job);
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
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

        job.builderNpc = world.spawn(new Location(world, cx + 0.5, y, cz + 0.5), Villager.class, npc -> {
            npc.setInvulnerable(true);
            npc.setAI(true);
            npc.setProfession(Villager.Profession.MASON);
            npc.setCustomName(ChatColor.GOLD + "Builder");
            npc.setCustomNameVisible(false);
            npc.setRemoveWhenFarAway(false);
        });
        job.hologram = world.spawn(new Location(world, cx + 0.5, y + 4.2, cz + 0.5), ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setCustomNameVisible(true);
            stand.setCustomName(ChatColor.YELLOW + job.displayName + ChatColor.GRAY + " - " + formatDuration(remainingSeconds(job)));
        });
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

    private void evacuatePlayersFromArea(ConstructionJob job) {
        World world = Bukkit.getWorld(job.worldName);
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (isInsideJob(job, player.getLocation())) {
                player.teleportAsync(nearestPlayableLocation(world, player.getLocation()));
            }
        }
    }

    private boolean isInsideJob(ConstructionJob job, Location location) {
        if (location == null || location.getWorld() == null || !Objects.equals(location.getWorld().getName(), job.worldName)) {
            return false;
        }
        Location center = jobCenter(location.getWorld(), job);
        int radius = constructionRadius(job.type);
        return Math.abs(location.getX() - center.getX()) <= radius + 0.5
                && Math.abs(location.getZ() - center.getZ()) <= radius + 0.5
                && location.getY() <= GROUND_Y + 8;
    }

    private Location jobCenter(World world, ConstructionJob job) {
        if (job.customX != null && job.customZ != null) {
            return new Location(world, job.customX + 0.5, GROUND_Y + 1, job.customZ + 0.5);
        }
        int[] slot = slotList(job.type).get(Math.max(0, Math.min(job.slotIndex, slotList(job.type).size() - 1)));
        return new Location(world, slot[0] + 0.5, GROUND_Y + 1, slot[1] + 0.5);
    }

    private ConstructionJob createJob(String worldName, BuildingType type, JobKind kind, int slotIndex, int seconds) {
        long ends = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        String id = kind.name() + ":" + type.name() + ":" + slotIndex;
        return new ConstructionJob(id, type, kind, slotIndex, null, null, worldName,
                (kind == JobKind.BUILD ? "Building " : "Upgrading ") + type.displayName(), ends);
    }

    private void addJob(UUID playerId, ConstructionJob job) {
        activeJobs.computeIfAbsent(playerId, id -> new LinkedHashMap<>()).put(job.id, job);
    }

    private void removeJob(UUID playerId, String id) {
        Map<String, ConstructionJob> jobs = activeJobs.get(playerId);
        if (jobs == null) {
            return;
        }
        jobs.remove(id);
        if (jobs.isEmpty()) {
            activeJobs.remove(playerId);
        }
    }

    private boolean hasJob(UUID playerId, String id) {
        return activeJobs.getOrDefault(playerId, Collections.emptyMap()).containsKey(id);
    }

    private ConstructionJob findFirstJobByType(UUID playerId, BuildingType type) {
        for (ConstructionJob job : activeJobs.getOrDefault(playerId, Collections.emptyMap()).values()) {
            if (job.type == type) {
                return job;
            }
        }
        return null;
    }

    private boolean hasActiveJob(UUID playerId, BuildingType type, JobKind kind) {
        for (ConstructionJob job : activeJobs.getOrDefault(playerId, Collections.emptyMap()).values()) {
            if (job.type == type && job.kind == kind) {
                return true;
            }
        }
        return false;
    }

    private int activeBuildJobsForType(UUID playerId, BuildingType type) {
        int count = 0;
        for (ConstructionJob job : activeJobs.getOrDefault(playerId, Collections.emptyMap()).values()) {
            if (job.type == type && job.kind == JobKind.BUILD) {
                count++;
            }
        }
        return count;
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

    private void enforceResourceCaps(VillageData village) {
        village.clampPendingResources(mineCollectorCap(village, BuildingType.GOLD_MINE),
                mineCollectorCap(village, BuildingType.ELIXIR_COLLECTOR));
        village.clampStoredResources(goldStorageCap(village), elixirStorageCap(village));
    }

    private long goldStorageCap(VillageData village) {
        long thBase = switch (village.getTownHallLevel()) {
            case 0 -> 1000L;
            case 1 -> 2000L;
            default -> 3500L;
        };
        int level = village.getBuildingLevel(BuildingType.GOLD_STORAGE);
        long storage = level >= 2 ? 3000L : (level >= 1 ? 1500L : 0L);
        return thBase + storage;
    }

    private long elixirStorageCap(VillageData village) {
        long thBase = switch (village.getTownHallLevel()) {
            case 0 -> 1000L;
            case 1 -> 2000L;
            default -> 3500L;
        };
        int level = village.getBuildingLevel(BuildingType.ELIXIR_STORAGE);
        long storage = level >= 2 ? 3000L : (level >= 1 ? 1500L : 0L);
        return thBase + storage;
    }

    private long mineCollectorCap(VillageData village, BuildingType type) {
        int count = village.getBuildingCount(type);
        int level = village.getBuildingLevel(type);
        long each = level >= 2 ? 2000L : 1000L;
        return Math.max(0L, count * each);
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
        return currency.name().toLowerCase(Locale.ROOT);
    }

    private int constructionRadius(BuildingType type) {
        return switch (type) {
            case WALL -> 1;
            case BARRACKS, ARMY_CAMP, LABORATORY -> 4;
            default -> 3;
        };
    }

    private long remainingSeconds(ConstructionJob job) {
        return Math.max(0L, (job.endsAtMillis - System.currentTimeMillis() + 999L) / 1000L);
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

    private List<int[]> slotList(BuildingType type) {
        return type == BuildingType.WALL ? WALL_SLOTS : BUILDING_SLOTS.getOrDefault(type, List.of());
    }

    private boolean hasSlot(BuildingType type, int index) {
        List<int[]> slots = slotList(type);
        return index >= 0 && index < slots.size();
    }

    private int armyCapacity(VillageData village) {
        return village.getBuildingCount(BuildingType.ARMY_CAMP) * ARMY_CAP_PER_CAMP;
    }

    private int troopHousing(TroopType type) {
        return TroopBook.info(type).housingSpace();
    }

    private int armyHousingUsed(VillageData village) {
        int used = 0;
        for (Map.Entry<TroopType, Integer> troop : village.getTroopsSnapshot().entrySet()) {
            used += troop.getValue() * troopHousing(troop.getKey());
        }
        return used;
    }

    private int queuedHousing(UUID playerId) {
        int used = 0;
        for (TrainingJob job : trainingJobs.getOrDefault(playerId, List.of())) {
            used += troopHousing(job.type);
        }
        return used;
    }

    private Location getSafeSpawn(World world) {
        return new Location(world, 0.5, GROUND_Y + 2.0, -10.5, 0f, 0f);
    }

    private UUID findOwnerByWorld(String worldName) {
        for (VillageData village : villages.values()) {
            if (worldName.equals(village.getWorldName())) {
                return village.getPlayerId();
            }
        }
        return null;
    }

    private boolean isScenery(Location location) {
        double d2 = location.getX() * location.getX() + location.getZ() * location.getZ();
        return d2 > (PLAYABLE_RADIUS + 2.0) * (PLAYABLE_RADIUS + 2.0);
    }

    private int clearObstacleCluster(Block start) {
        Set<BlockPos> visited = new java.util.HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(new BlockPos(start.getX(), start.getY(), start.getZ()));
        int removed = 0;
        while (!queue.isEmpty() && removed < 40) {
            BlockPos pos = queue.poll();
            if (!visited.add(pos)) {
                continue;
            }
            Block block = start.getWorld().getBlockAt(pos.x, pos.y, pos.z);
            if (!OBSTACLE_BLOCKS.contains(block.getType())) {
                continue;
            }
            block.setType(Material.AIR, false);
            removed++;
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (Math.abs(ox) + Math.abs(oy) + Math.abs(oz) != 1) {
                            continue;
                        }
                        queue.add(new BlockPos(pos.x + ox, pos.y + oy, pos.z + oz));
                    }
                }
            }
        }
        return removed;
    }

    private BuildingHit resolveBuildingHit(VillageData village, Location location) {
        if (location == null) {
            return null;
        }

        if (Math.abs(location.getX()) <= 4 && Math.abs(location.getZ()) <= 4 && location.getY() >= GROUND_Y + 1) {
            return new BuildingHit(BuildingType.BUILDER_HUT, -1, 1, "Town Hall");
        }

        for (Map.Entry<BuildingType, Integer> entry : village.getBuildingsSnapshot().entrySet()) {
            BuildingType type = entry.getKey();
            int count = Math.min(entry.getValue(), slotList(type).size());
            int radius = switch (type) {
                case WALL -> 0;
                case BARRACKS, ARMY_CAMP, LABORATORY -> 4;
                default -> 3;
            };
            for (int i = 0; i < count; i++) {
                int[] slot = slotList(type).get(i);
                if (Math.abs(location.getX() - slot[0]) <= radius && Math.abs(location.getZ() - slot[1]) <= radius
                        && location.getY() >= GROUND_Y + 1 && location.getY() <= GROUND_Y + 8) {
                    int level = type == BuildingType.WALL ? village.getWallSegmentLevel(i) : village.getBuildingLevel(type);
                    return new BuildingHit(type, i, level, type.displayName());
                }
            }
        }
        return null;
    }

    private void openBuildingInfo(Player player, VillageData village, BuildingHit hit) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.GOLD + "Building Info");
        inv.setItem(11, infoItem(Material.BOOK, ChatColor.YELLOW + capitalize(hit.name), List.of(
                ChatColor.GRAY + "Level: " + ChatColor.WHITE + hit.level,
                ChatColor.GRAY + "Type: " + ChatColor.WHITE + hit.type.name().toLowerCase(Locale.ROOT),
                ChatColor.GRAY + "Use /clash upgrade " + hit.type.name().toLowerCase(Locale.ROOT)
        )));

        if (hit.type == BuildingType.GOLD_MINE || hit.type == BuildingType.ELIXIR_COLLECTOR) {
            inv.setItem(13, infoItem(Material.HOPPER, ChatColor.GREEN + "Collect", List.of(
                    ChatColor.GRAY + "Pending gold: " + village.getPendingGold(),
                    ChatColor.GRAY + "Pending elixir: " + village.getPendingElixir(),
                    ChatColor.GRAY + "Right click collector/mine to collect"
            )));
        }

        if (hit.type == BuildingType.BARRACKS) {
            inv.setItem(15, infoItem(Material.IRON_SWORD, ChatColor.AQUA + "Training", List.of(
                    ChatColor.GRAY + "/clash train barbarian 5",
                    ChatColor.GRAY + "/clash train archer 5",
                    ChatColor.GRAY + "/clash train giant 2"
            )));
        }

        player.openInventory(inv);
    }

    private void giveOverviewMap(Player player, World world, VillageData village) {
        MapView view = Bukkit.createMap(world);
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(renderer);
        }
        view.setTrackingPosition(false);
        view.setLocked(true);
        view.setCenterX(0);
        view.setCenterZ(0);
        view.setScale(MapView.Scale.FARTHEST);
        view.addRenderer(new VillageOverviewRenderer(village));

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.setDisplayName(ChatColor.AQUA + "Village Overview");
            mapItem.setItemMeta(meta);
        }
        player.getInventory().addItem(mapItem);
    }

    private ItemStack infoItem(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private void fill(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.getBlockAt(x, y, z).setType(material, false);
                }
            }
        }
    }

    private void hollow(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean wall = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    world.getBlockAt(x, y, z).setType(wall ? material : Material.AIR, false);
                }
            }
        }
    }

    private static Map<BuildingType, List<int[]>> createBuildingSlots() {
        Map<BuildingType, List<int[]>> slots = new EnumMap<>(BuildingType.class);
        slots.put(BuildingType.BUILDER_HUT, List.of(new int[]{-14, -10}, new int[]{14, -10}, new int[]{-22, 8}, new int[]{22, 8}));
        slots.put(BuildingType.GOLD_MINE, List.of(new int[]{-12, 10}, new int[]{12, 10}, new int[]{-24, 0}, new int[]{24, 0}));
        slots.put(BuildingType.ELIXIR_COLLECTOR, List.of(new int[]{-10, 18}, new int[]{10, 18}, new int[]{-18, -18}, new int[]{18, -18}));
        slots.put(BuildingType.GOLD_STORAGE, List.of(new int[]{-20, 16}, new int[]{20, 16}));
        slots.put(BuildingType.ELIXIR_STORAGE, List.of(new int[]{-20, -16}, new int[]{20, -16}));
        slots.put(BuildingType.BARRACKS, List.of(new int[]{0, -20}, new int[]{0, 20}));
        slots.put(BuildingType.ARMY_CAMP, List.of(new int[]{-28, 14}, new int[]{28, 14}));
        slots.put(BuildingType.LABORATORY, List.of(new int[]{0, 26}));
        slots.put(BuildingType.CANNON, List.of(new int[]{-26, -8}, new int[]{26, -8}));
        slots.put(BuildingType.ARCHER_TOWER, List.of(new int[]{-30, 0}, new int[]{30, 0}));
        return Collections.unmodifiableMap(slots);
    }

    private static List<int[]> createWallSlots() {
        List<int[]> slots = new ArrayList<>();
        int r = 12;
        for (int x = -r; x <= r; x++) {
            slots.add(new int[]{x, -r});
            slots.add(new int[]{x, r});
        }
        for (int z = -r + 1; z <= r - 1; z++) {
            slots.add(new int[]{-r, z});
            slots.add(new int[]{r, z});
        }
        return Collections.unmodifiableList(slots);
    }

    private static final class VillageOverviewRenderer extends MapRenderer {
        private final VillageData village;
        private boolean rendered;

        private VillageOverviewRenderer(VillageData village) {
            this.village = village;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (rendered) {
                return;
            }
            rendered = true;
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    canvas.setPixel(x, y, MapPalette.matchColor(java.awt.Color.decode("#6BBF59")));
                }
            }
            drawVillageIcon(canvas, 64, 64, java.awt.Color.decode("#5E3B23")); // Town Hall
            drawBuiltBuildings(canvas, village, BuildingType.BUILDER_HUT, java.awt.Color.decode("#8B5A2B"));
            drawBuiltBuildings(canvas, village, BuildingType.GOLD_MINE, java.awt.Color.decode("#E1B12C"));
            drawBuiltBuildings(canvas, village, BuildingType.ELIXIR_COLLECTOR, java.awt.Color.decode("#D980FA"));
            drawBuiltBuildings(canvas, village, BuildingType.CANNON, java.awt.Color.decode("#7F8C8D"));
            drawBuiltBuildings(canvas, village, BuildingType.ARCHER_TOWER, java.awt.Color.decode("#2E86C1"));
            drawBuiltBuildings(canvas, village, BuildingType.WALL, java.awt.Color.decode("#4B6584"));
            canvas.drawText(4, 4, MinecraftFont.Font, "TH" + village.getTownHallLevel());
            canvas.drawText(4, 14, MinecraftFont.Font, "W:" + village.getBuildingCount(BuildingType.WALL));
            MapCursorCollection cursors = new MapCursorCollection();
            canvas.setCursors(cursors);
        }

        private void drawBuiltBuildings(MapCanvas canvas, VillageData village, BuildingType type, java.awt.Color color) {
            List<int[]> slots = type == BuildingType.WALL ? WALL_SLOTS : BUILDING_SLOTS.getOrDefault(type, List.of());
            int count = Math.min(village.getBuildingCount(type), slots.size());
            for (int i = 0; i < count; i++) {
                int[] slot = slots.get(i);
                int x = Math.max(0, Math.min(127, 64 + (slot[0] * 2)));
                int y = Math.max(0, Math.min(127, 64 + (slot[1] * 2)));
                drawVillageIcon(canvas, x, y, color);
            }
        }

        private void drawVillageIcon(MapCanvas canvas, int x, int y, java.awt.Color color) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int px = x + dx;
                    int py = y + dy;
                    if (px >= 0 && px < 128 && py >= 0 && py < 128) {
                        canvas.setPixel(px, py, MapPalette.matchColor(color));
                    }
                }
            }
        }
    }

    private enum JobKind {
        BUILD,
        UPGRADE
    }

    private record BlockPos(int x, int y, int z) {
    }

    private record BuildingHit(BuildingType type, int slotIndex, int level, String name) {
    }

    private static final class ConstructionJob {
        private final String id;
        private final BuildingType type;
        private final JobKind kind;
        private final int slotIndex;
        private final Integer customX;
        private final Integer customZ;
        private final String worldName;
        private final String displayName;
        private final long endsAtMillis;
        private final List<BlockPos> fenceBlocks = new ArrayList<>();
        private Villager builderNpc;
        private ArmorStand hologram;

        private ConstructionJob(String id, BuildingType type, JobKind kind, int slotIndex, Integer customX, Integer customZ,
                                String worldName, String displayName, long endsAtMillis) {
            this.id = id;
            this.type = type;
            this.kind = kind;
            this.slotIndex = slotIndex;
            this.customX = customX;
            this.customZ = customZ;
            this.worldName = worldName;
            this.displayName = displayName;
            this.endsAtMillis = endsAtMillis;
        }
    }

    private static final class TrainingJob {
        private final TroopType type;
        private final long endsAtMillis;

        private TrainingJob(TroopType type, long endsAtMillis) {
            this.type = type;
            this.endsAtMillis = endsAtMillis;
        }
    }

    private static final class ResearchJob {
        private final TroopType troop;
        private final int nextLevel;
        private final long endsAtMillis;

        private ResearchJob(TroopType troop, int nextLevel, long endsAtMillis) {
            this.troop = troop;
            this.nextLevel = nextLevel;
            this.endsAtMillis = endsAtMillis;
        }
    }
}