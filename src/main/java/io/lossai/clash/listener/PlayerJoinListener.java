package io.lossai.clash.listener;

import io.lossai.clash.service.VillageManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final VillageManager villageManager;

    public PlayerJoinListener(JavaPlugin plugin, VillageManager villageManager) {
        this.plugin = plugin;
        this.villageManager = villageManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getServer().getScheduler().runTaskLater(plugin, () -> {
            villageManager.setupVillageForPlayer(player);
            player.sendMessage(ChatColor.GOLD + "Welcome to your Clash village.");
            player.sendMessage(ChatColor.GRAY + "You were moved to a safe spawn in your flat private village world.");
            player.sendMessage(ChatColor.GRAY + "Use /clash village, /clash build <building>, /clash upgrade <building>, /clash overview, and /clash upgrade townhall.");
        }, 10L);
    }
}