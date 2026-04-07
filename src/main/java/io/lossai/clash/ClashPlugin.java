package io.lossai.clash;

import io.lossai.clash.command.BarbCommand;
import io.lossai.clash.command.ClashCommand;
import io.lossai.clash.listener.AttackListener;
import io.lossai.clash.listener.PlayerJoinListener;
import io.lossai.clash.listener.InfoInventoryListener;
import io.lossai.clash.listener.VillageBoundaryListener;
import io.lossai.clash.listener.VillageInteractListener;
import io.lossai.clash.listener.VillageWorldProtectionListener;
import io.lossai.clash.service.BarbConfig;
import io.lossai.clash.service.BarbManager;
import io.lossai.clash.service.HealthBarConfig;
import io.lossai.clash.service.HealthBarManager;
import io.lossai.clash.service.TestBaseManager;
import io.lossai.clash.service.VillageManager;
import io.lossai.clash.storage.VillageStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClashPlugin extends JavaPlugin {

    private VillageStore villageStore;
    private VillageManager villageManager;
    private BarbManager barbManager;
    private BarbConfig barbConfig;
    private TestBaseManager testBaseManager;
    private HealthBarManager healthBarManager;

    @Override
    public void onEnable() {
        this.villageStore = new VillageStore(this);
        this.villageManager = new VillageManager(this, villageStore);

        saveDefaultConfig();
        this.barbConfig = BarbConfig.load(getConfig(), getLogger());
        HealthBarConfig healthBarConfig = HealthBarConfig.load(getConfig(), getLogger());

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

        ClashCommand executor = new ClashCommand(villageManager, this);
        clashCommand.setExecutor(executor);
        clashCommand.setTabCompleter(executor);

        getServer().getScheduler().runTaskTimer(this, villageManager::tickResourceGeneration, 100L, 100L);
        getServer().getScheduler().runTaskTimer(this, villageManager::tickConstructionVisuals, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, villageManager::tickArcherTowerDefense, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, villageManager::tickCannonDefense, 20L, 20L);

        // Barbarian system — requires Citizens2
        if (getServer().getPluginManager().getPlugin("Citizens") != null) {
            this.testBaseManager = new TestBaseManager(this);
            this.barbManager = new BarbManager(this);
            barbManager.setVillageManager(villageManager);
            barbManager.setTestBaseManager(testBaseManager);

            this.healthBarManager = new HealthBarManager(this, healthBarConfig);
            barbManager.setHealthBarManager(healthBarManager);

            getServer().getPluginManager().registerEvents(new AttackListener(this, barbManager), this);

            PluginCommand barbCommand = getCommand("barbarian");
            if (barbCommand != null) {
                BarbCommand barbExecutor = new BarbCommand(barbManager);
                barbCommand.setExecutor(barbExecutor);
                barbCommand.setTabCompleter(barbExecutor);
            }

            getLogger().info("Barbarian system enabled (Citizens2 found).");
        } else {
            getLogger().warning("Citizens2 not found — barbarian system disabled.");
        }

        getLogger().info("ClashVillages enabled.");
    }

    @Override
    public void onDisable() {
        if (villageStore != null && villageManager != null) {
            villageManager.shutdown();
            villageStore.saveAll(villageManager.getVillageSnapshot());
        }
        if (barbManager != null) {
            barbManager.clear();
        }
        if (healthBarManager != null) {
            healthBarManager.shutdown();
        }
    }

    public BarbConfig getBarbConfig() {
        return barbConfig;
    }

    public BarbManager getBarbManager() {
        return barbManager;
    }

    public TestBaseManager getTestBaseManager() {
        return testBaseManager;
    }

    public HealthBarManager getHealthBarManager() {
        return healthBarManager;
    }

    public void reloadBarbConfig() {
        reloadConfig();
        this.barbConfig = BarbConfig.load(getConfig(), getLogger());
        getLogger().info("BarbConfig reloaded.");
    }
}