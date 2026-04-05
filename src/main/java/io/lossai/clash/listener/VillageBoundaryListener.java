package io.lossai.clash.listener;

import io.lossai.clash.service.VillageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class VillageBoundaryListener implements Listener {

    private final VillageManager villageManager;

    public VillageBoundaryListener(VillageManager villageManager) {
        this.villageManager = villageManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !villageManager.isVillageWorld(to.getWorld())) {
            return;
        }

        if (villageManager.isInsideConstructionZone(to)) {
            Location safe = villageManager.nearestConstructionSafeLocation(to);
            if (safe != null) {
                event.setTo(safe);
            }
            return;
        }

        if (villageManager.isInsidePlayableArea(to)) {
            return;
        }

        Player player = event.getPlayer();
        Location safe = villageManager.nearestPlayableLocation(to.getWorld(), to);
        if (safe != null) {
            player.teleportAsync(safe);
        }
    }
}