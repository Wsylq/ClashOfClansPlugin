package io.lossai.clash.listener;

import io.lossai.clash.ClashPlugin;
import io.lossai.clash.service.AttackSession;
import io.lossai.clash.service.BarbManager;
import io.lossai.clash.service.TestBaseManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listens for right-click with the Barbarian Head item to deploy one barbarian
 * at a time during an active attack session.
 */
public final class AttackListener implements Listener {

    private final ClashPlugin plugin;
    private final BarbManager barbManager;

    public AttackListener(ClashPlugin plugin, BarbManager barbManager) {
        this.plugin = plugin;
        this.barbManager = barbManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!BarbManager.isBarbHeadItem(item)) {
            return;
        }

        event.setCancelled(true);

        // Block deployment outside the test base world entirely
        if (!player.getWorld().getName().equals(TestBaseManager.WORLD_NAME)) {
            player.sendMessage(ChatColor.RED + "You can only deploy barbarians in the test base. Use /clash attack first.");
            return;
        }

        AttackSession session = barbManager.getSession(player.getUniqueId());
        if (session == null) {
            player.sendMessage(ChatColor.RED + "No active attack session. Use /clash attack to start.");
            return;
        }

        if (session.isFinished()) {
            barbManager.endSession(player);
            return;
        }

        barbManager.deployOneFromSession(player, session);
    }
}
