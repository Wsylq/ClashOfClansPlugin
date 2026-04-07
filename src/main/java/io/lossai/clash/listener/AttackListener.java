package io.lossai.clash.listener;

import io.lossai.clash.ClashPlugin;
import io.lossai.clash.service.ArcherManager;
import io.lossai.clash.service.AttackSession;
import io.lossai.clash.service.BarbManager;
import io.lossai.clash.service.TestBaseManager;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.ChatColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listens for right-click with the Barbarian Head item to deploy one barbarian
 * at a time during an active attack session.
 * Also listens for entity damage to update NPC health bars.
 */
public final class AttackListener implements Listener {

    private final ClashPlugin plugin;
    private final BarbManager barbManager;
    private final ArcherManager archerManager;

    public AttackListener(ClashPlugin plugin, BarbManager barbManager, ArcherManager archerManager) {
        this.plugin = plugin;
        this.barbManager = barbManager;
        this.archerManager = archerManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (BarbManager.isBarbHeadItem(item)) {
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
        } else if (ArcherManager.isArcherHeadItem(item)) {
            AttackSession session = archerManager.getSession(player.getUniqueId());
            if (session != null) {
                archerManager.deployOneFromSession(player, session);
            }
            event.setCancelled(true);
        }
    }

    /**
     * Updates the NPC health bar whenever a Citizens NPC takes damage.
     * Runs MONITOR priority so we see the final post-damage HP value.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (plugin.getHealthBarManager() == null) return;
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        if (!event.getEntity().getWorld().getName().equals(TestBaseManager.WORLD_NAME)) return;

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc == null) return;

        double maxHp = living.getMaxHealth();
        double currentHp = Math.max(0, living.getHealth() - event.getFinalDamage());
        plugin.getHealthBarManager().damageByEntityId(event.getEntity().getUniqueId(), currentHp, maxHp);
    }
}
