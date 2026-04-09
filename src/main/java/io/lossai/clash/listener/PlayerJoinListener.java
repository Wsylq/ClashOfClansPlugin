package io.lossai.clash.listener;

import io.lossai.clash.grid.occupancy.OccupancyMap;
import io.lossai.clash.grid.persistence.LayoutSerializer;
import io.lossai.clash.grid.renderer.VillageRenderer;
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
    private final LayoutSerializer layoutSerializer;

    public PlayerJoinListener(JavaPlugin plugin, VillageManager villageManager, LayoutSerializer layoutSerializer) {
        this.plugin = plugin;
        this.villageManager = villageManager;
        this.layoutSerializer = layoutSerializer;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Rebuild slot overrides from saved layout BEFORE setupVillageForPlayer
            // so renderVillage uses the correct positions on first render
            OccupancyMap occupancyMap = layoutSerializer.load(player.getUniqueId(), null);
            if (!occupancyMap.snapshot().isEmpty()) {
                villageManager.rebuildSlotOverridesFromLayout(player.getUniqueId(), occupancyMap);
            }

            villageManager.setupVillageForPlayer(player);

            player.sendMessage(ChatColor.GOLD + "Welcome to your Clash village.");
            player.sendMessage(ChatColor.GRAY + "You were moved to a safe spawn in your flat private village world.");
            player.sendMessage(ChatColor.GRAY + "Use /clash village, /clash build <building>, /clash upgrade <building>, /clash overview, and /clash upgrade townhall.");
        }, 10L);
    }
}