package io.lossai.clash.listener;

import io.lossai.clash.service.VillageManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

public final class VillageWorldProtectionListener implements Listener {

    private final VillageManager villageManager;

    public VillageWorldProtectionListener(VillageManager villageManager) {
        this.villageManager = villageManager;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!villageManager.isVillageWorld(event.getBlock().getWorld())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        player.sendMessage(ChatColor.RED + "You cannot break blocks in your village. Use /clash build and /clash upgrade.");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!villageManager.isVillageWorld(event.getBlock().getWorld())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        player.sendMessage(ChatColor.RED + "You cannot place blocks in your village. Use /clash build.");
    }
}