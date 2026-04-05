package io.lossai.clash.listener;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class InfoInventoryListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title == null) {
            return;
        }
        if (ChatColor.stripColor(title).equalsIgnoreCase("Building Info")) {
            event.setCancelled(true);
        }
    }
}