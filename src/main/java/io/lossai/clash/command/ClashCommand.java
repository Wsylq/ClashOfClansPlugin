package io.lossai.clash.command;

import io.lossai.clash.ClashPlugin;
import io.lossai.clash.grid.command.EditCommand;
import io.lossai.clash.model.BuildingType;
import io.lossai.clash.model.TroopType;
import io.lossai.clash.service.TestBaseRegistry;
import io.lossai.clash.service.VillageManager;
import io.lossai.clash.model.VillageData;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ClashCommand implements CommandExecutor, TabCompleter {

    private final VillageManager villageManager;
    private final ClashPlugin plugin;
    private final EditCommand editCommand;

    public ClashCommand(VillageManager villageManager, ClashPlugin plugin, EditCommand editCommand) {
        this.villageManager = villageManager;
        this.plugin = plugin;
        this.editCommand = editCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "tp" -> player.sendMessage(villageManager.teleportToVillage(player));
            case "village" -> villageManager.describeVillage(player).forEach(player::sendMessage);
            case "build" -> handleBuild(player, args);
            case "upgrade" -> handleUpgrade(player, args);
            case "collect" -> player.sendMessage(villageManager.collectResources(player));
            case "overview" -> handleOverview(player, args);
            case "train" -> handleTrain(player, args);
            case "research" -> handleResearch(player, args);
            case "finish" -> handleFinish(player, args);
            case "reload" -> handleReload(player);
            case "attack" -> handleAttack(player);
            case "edit" -> {
                // Strip "edit" and pass remaining args to EditCommand
                String[] editArgs = new String[args.length - 1];
                System.arraycopy(args, 1, editArgs, 0, editArgs.length);
                editCommand.onCommand(sender, command, label, editArgs);
            }
            default -> sendHelp(player);
        }

        return true;
    }

    private void handleBuild(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /clash build <building> [amount]");
            return;
        }

        BuildingType type = BuildingType.fromInput(args[1]).orElse(null);
        if (type == null) {
            player.sendMessage(ChatColor.RED + "Unknown building. Try: " + String.join(", ", displayBuildingNames()));
            return;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                player.sendMessage(ChatColor.RED + "Amount must be a number.");
                return;
            }
        }

        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "Amount must be greater than 0.");
            return;
        }

        player.sendMessage(villageManager.build(player, type, amount));
    }

    private void handleUpgrade(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /clash upgrade <townhall|building>");
            return;
        }

        if (args[1].equalsIgnoreCase("townhall")) {
            player.sendMessage(villageManager.upgradeTownHall(player));
            return;
        }

        BuildingType type = BuildingType.fromInput(args[1]).orElse(null);
        if (type == null) {
            player.sendMessage(ChatColor.RED + "Unknown upgrade target.");
            return;
        }

        player.sendMessage(villageManager.upgradeBuilding(player, type));
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "Clash commands:");
        player.sendMessage(ChatColor.GRAY + " - /clash tp");
        player.sendMessage(ChatColor.GRAY + " - /clash village");
        player.sendMessage(ChatColor.GRAY + " - /clash build <building> [amount]");
        player.sendMessage(ChatColor.GRAY + " - /clash upgrade townhall");
        player.sendMessage(ChatColor.GRAY + " - /clash upgrade <building>");
        player.sendMessage(ChatColor.GRAY + " - /clash collect");
        player.sendMessage(ChatColor.GRAY + " - /clash overview [exit]");
        player.sendMessage(ChatColor.GRAY + " - /clash train <troop> [amount]");
        player.sendMessage(ChatColor.GRAY + " - /clash research <troop>");
        player.sendMessage(ChatColor.GRAY + " - /clash finish <building|training|research>");
        player.sendMessage(ChatColor.GRAY + " - /clash attack");
        player.sendMessage(ChatColor.GRAY + " - /clash reload");
    }

    private void handleOverview(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("exit")) {
            player.sendMessage(villageManager.closeOverview(player));
            return;
        }
        player.sendMessage(villageManager.openOverview(player));
    }

    private void handleTrain(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /clash train <troop> [amount]");
            return;
        }
        TroopType type = TroopType.fromInput(args[1]).orElse(null);
        if (type == null) {
            player.sendMessage(ChatColor.RED + "Unknown troop.");
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                player.sendMessage(ChatColor.RED + "Amount must be a number.");
                return;
            }
        }
        player.sendMessage(villageManager.trainTroop(player, type, amount));
    }

    private void handleResearch(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /clash research <troop>");
            return;
        }
        TroopType type = TroopType.fromInput(args[1]).orElse(null);
        if (type == null) {
            player.sendMessage(ChatColor.RED + "Unknown troop.");
            return;
        }
        player.sendMessage(villageManager.startResearch(player, type));
    }

    private void handleFinish(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /clash finish <building|training|research>");
            return;
        }
        player.sendMessage(villageManager.finishNow(player, args[1]));
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("clash.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to reload the config.");
            return;
        }
        plugin.reloadBarbConfig();
        player.sendMessage(ChatColor.GREEN + "ClashVillages config reloaded.");
    }

    private void handleAttack(Player player) {
        if (plugin.getBarbManager() == null) {
            player.sendMessage(ChatColor.RED + "Troop system is not available (Citizens2 required).");
            return;
        }

        VillageData village = villageManager.getVillage(player.getUniqueId());
        if (village == null) {
            player.sendMessage(ChatColor.RED + "Village not initialized.");
            return;
        }

        int barbCount  = village.getTroopCount(TroopType.BARBARIAN);
        int archerCount = village.getTroopCount(TroopType.ARCHER);

        if (barbCount <= 0 && archerCount <= 0) {
            player.sendMessage(ChatColor.RED + "You have no troops trained. Use /clash train <barbarian|archer> <amount>.");
            return;
        }

        if (plugin.getTestBaseManager() == null) {
            player.sendMessage(ChatColor.RED + "Test base system is not available.");
            return;
        }

        // Create ONE shared registry for this attack
        TestBaseRegistry registry = plugin.getTestBaseManager().createFreshRegistry(plugin.getBarbConfig());
        if (registry == null) {
            player.sendMessage(ChatColor.RED + "Could not load test base world.");
            return;
        }
        if (plugin.getHealthBarManager() != null) {
            registry.setHealthBarManager(plugin.getHealthBarManager());
            registry.setSessionId(player.getUniqueId());
        }
        plugin.getTestBaseManager().setActiveRegistry(player.getUniqueId(), registry);

        // Enlist each troop type that has units trained
        boolean anyJoined = false;
        if (barbCount > 0) {
            anyJoined |= plugin.getBarbManager().joinAttackSession(player, registry);
        }
        if (archerCount > 0 && plugin.getArcherManager() != null) {
            anyJoined |= plugin.getArcherManager().joinAttackSession(player, registry);
        }

        if (!anyJoined) {
            player.sendMessage(ChatColor.RED + "Failed to start attack session.");
            return;
        }

        // Teleport to test base
        org.bukkit.World testWorld = plugin.getTestBaseManager().getOrCreateWorld();
        if (testWorld != null) {
            player.teleportAsync(new org.bukkit.Location(testWorld, 0.5,
                    io.lossai.clash.service.TestBaseManager.getGroundY() + 2.0, -18.5, 0f, 0f));
        }

        player.sendMessage(ChatColor.GREEN + "Attack started!"
                + (barbCount  > 0 ? ChatColor.WHITE + " Barbarians: " + ChatColor.YELLOW + barbCount  : "")
                + (archerCount > 0 ? ChatColor.WHITE + " Archers: "    + ChatColor.YELLOW + archerCount : ""));
        player.sendMessage(ChatColor.GRAY + "Right-click with a troop head to deploy.");
    }

    private List<String> displayBuildingNames() {
        List<String> names = new ArrayList<>();
        for (BuildingType value : BuildingType.values()) {
            names.add(value.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(List.of("tp", "village", "build", "upgrade", "collect", "overview", "train", "research", "finish", "attack", "edit", "reload"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("overview")) {
            return filterByPrefix(List.of("exit"), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("build")) {
            return filterByPrefix(villageManager.getBuildTabSuggestions(player), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("upgrade")) {
            List<String> upgradeTargets = new ArrayList<>();
            upgradeTargets.add("townhall");
            for (BuildingType type : BuildingType.values()) {
                upgradeTargets.add(type.name().toLowerCase(Locale.ROOT));
            }
            return filterByPrefix(upgradeTargets, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("train")) {
            List<String> troops = new ArrayList<>();
            for (TroopType type : TroopType.values()) {
                troops.add(type.name().toLowerCase(Locale.ROOT));
            }
            return filterByPrefix(troops, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("research")) {
            List<String> troops = new ArrayList<>();
            for (TroopType type : TroopType.values()) {
                troops.add(type.name().toLowerCase(Locale.ROOT));
            }
            return filterByPrefix(troops, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("finish")) {
            List<String> finishTargets = new ArrayList<>();
            finishTargets.add("training");
            finishTargets.add("research");
            for (BuildingType type : BuildingType.values()) {
                finishTargets.add(type.name().toLowerCase(Locale.ROOT));
            }
            return filterByPrefix(finishTargets, args[1]);
        }

        if (args[0].equalsIgnoreCase("edit")) {
            // Delegate to EditCommand.onTabComplete with args shifted by 1
            String[] editArgs = new String[args.length - 1];
            System.arraycopy(args, 1, editArgs, 0, editArgs.length);
            return editCommand.onTabComplete(sender, command, alias, editArgs);
        }

        return Collections.emptyList();
    }

    private List<String> filterByPrefix(List<String> values, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lowered)) {
                matches.add(value);
            }
        }
        return matches;
    }
}