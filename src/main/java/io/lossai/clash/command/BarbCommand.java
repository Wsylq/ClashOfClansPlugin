package io.lossai.clash.command;

import io.lossai.clash.service.BarbManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles /barbarian deploy <count> and /barbarian clear.
 */
public class BarbCommand implements CommandExecutor, TabCompleter {

    private final BarbManager barbManager;

    public BarbCommand(BarbManager barbManager) {
        this.barbManager = barbManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 8.1 — only players
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        // 8.1 — permission check
        if (!player.hasPermission("clash.admin")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "Usage: /barbarian <deploy <count>|clear>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "deploy" -> {
                // 8.2 — parse count
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /barbarian deploy <count>");
                    return true;
                }
                int count;
                try {
                    count = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Usage: /barbarian deploy <count>");
                    return true;
                }
                barbManager.deploy(player, count);
            }
            case "clear" -> {
                // 8.3 — get count before clearing, then clear
                int count = barbManager.getActiveBarbCount();
                barbManager.clear();
                if (count > 0) {
                    player.sendMessage(ChatColor.GREEN + "Cleared " + count + " barbarians.");
                } else {
                    player.sendMessage(ChatColor.YELLOW + "No barbarians to clear.");
                }
            }
            default -> player.sendMessage(ChatColor.RED + "Usage: /barbarian <deploy <count>|clear>");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // 8.4 — tab completion
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String sub : List.of("deploy", "clear")) {
                if (sub.startsWith(prefix)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("deploy")) {
            String prefix = args[1];
            for (String hint : List.of("1", "5", "10")) {
                if (hint.startsWith(prefix)) {
                    completions.add(hint);
                }
            }
        }

        return completions;
    }
}
