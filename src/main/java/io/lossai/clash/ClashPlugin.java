package io.lossai.clash;

import io.lossai.clash.command.ClashCommand;
import io.lossai.clash.listener.PlayerJoinListener;
import io.lossai.clash.listener.InfoInventoryListener;
import io.lossai.clash.listener.VillageBoundaryListener;
import io.lossai.clash.listener.VillageInteractListener;
import io.lossai.clash.listener.VillageWorldProtectionListener;
import io.lossai.clash.service.VillageManager;
import io.lossai.clash.storage.VillageStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClashPlugin extends JavaPlugin {

    private VillageStore villageStore;
    private VillageManager villageManager;

    @Override
    public void onEnable() {
        this.villageStore = new VillageStore(this);
        this.villageManager = new VillageManager(this, villageStore);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, villageManager), this);
        getServer().getPluginManager().registerEvents(new VillageWorldProtectionListener(villageManager), this);
        getServer().getPluginManager().registerEvents(new VillageBoundaryListener(villageManager), this);
        getServer().getPluginManager().registerEvents(new VillageInteractListener(villageManager), this);
        getServer().getPluginManager().registerEvents(new InfoInventoryListener(), this);

        PluginCommand clashCommand = getCommand("clash");
        if (clashCommand == null) {
            getLogger().severe("Command 'clash' missing from plugin.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ClashCommand executor = new ClashCommand(villageManager);
        clashCommand.setExecutor(executor);
        clashCommand.setTabCompleter(executor);

        // Resource generators tick every 5 seconds for online players.
        getServer().getScheduler().runTaskTimer(this, villageManager::tickResourceGeneration, 100L, 100L);
        // Construction holograms and builder HUD are refreshed every second.
        getServer().getScheduler().runTaskTimer(this, villageManager::tickConstructionVisuals, 20L, 20L);
        // Archer towers scan and fire every second.
        getServer().getScheduler().runTaskTimer(this, villageManager::tickArcherTowerDefense, 20L, 20L);
        // Cannons scan, rotate, and fire every second.
        getServer().getScheduler().runTaskTimer(this, villageManager::tickCannonDefense, 20L, 20L);

        getLogger().info("ClashVillages enabled.");
    }

    @Override
    public void onDisable() {
        if (villageStore != null && villageManager != null) {
            villageManager.shutdown();
            villageStore.saveAll(villageManager.getVillageSnapshot());
        }
    }
}