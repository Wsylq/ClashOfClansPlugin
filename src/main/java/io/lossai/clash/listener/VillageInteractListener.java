package io.lossai.clash.listener;

import io.lossai.clash.service.VillageManager;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public final class VillageInteractListener implements Listener {

    private final VillageManager villageManager;

    public VillageInteractListener(VillageManager villageManager) {
        this.villageManager = villageManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !villageManager.isVillageWorld(clicked.getWorld())) {
            return;
        }

        String message = villageManager.handleVillageInteract(event.getPlayer(), clicked);
        if (message != null && !message.isBlank()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.GRAY + message);
        }
    }
}