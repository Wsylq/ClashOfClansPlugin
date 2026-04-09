package io.lossai.clash;

import io.lossai.clash.command.BarbCommand;
import io.lossai.clash.command.ClashCommand;
import io.lossai.clash.grid.command.EditCommand;
import io.lossai.clash.grid.persistence.LayoutSerializer;
import io.lossai.clash.listener.AttackListener;
import io.lossai.clash.listener.PlayerJoinListener;
import io.lossai.clash.listener.InfoInventoryListener;
import io.lossai.clash.listener.VillageBoundaryListener;
import io.lossai.clash.listener.VillageInteractListener;
import io.lossai.clash.listener.VillageWorldProtectionListener;
import io.lossai.clash.service.ArcherConfig;
import io.lossai.clash.service.ArcherManager;
import io.lossai.clash.service.BarbConfig;
import io.lossai.clash.service.BarbManager;
import io.lossai.clash.service.HealthBarConfig;
import io.lossai.clash.service.HealthBarManager;
import io.lossai.clash.service.TestBaseManager;
import io.lossai.clash.service.VillageManager;
import io.lossai.clash.storage.VillageStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClashPlugin extends JavaPlugin {

    private VillageStore villageStore;
    private VillageManager villageManager;
    private EditCommand editCommand;
    private BarbManager barbManager;
    private BarbConfig barbConfig;
    private ArcherManager archerManager;
    private TestBaseManager testBaseManager;
    private HealthBarManager healthBarManager;

    @Override
    public void onEnable() {
        this.villageStore = new VillageStore(this);
        this.villageManager = new VillageManager(this, villageStore);

        saveDefaultConfig();
        this.barbConfig = BarbConfig.load(getConfig(), getLogger());
        HealthBarConfig healthBarConfig = HealthBarConfig.load(getConfig(), getLogger());

        // Edit mode
        LayoutSerializer layoutSerializer = new LayoutSerializer(getDataFolder(), getLogger());
        this.editCommand = new EditCommand(this, villageManager, layoutSerializer);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, villageManager, layoutSerializer), this);
        getServer().getPluginManager().registerEvents(new VillageWorldProtectionListener(villageManager), this);
        getServer().getPluginManager().registerEvents(new VillageBoundaryListener(villageManager), this);
        getServer().getPluginManager().registerEvents(new VillageInteractListener(villageManager), this);
        getServer().getPluginManager().registerEvents(new InfoInventoryListener(), this);

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent e) {
                editCommand.handleQuit(e.getPlayer());
            }

            @EventHandler
            public void onInteract(PlayerInteractEvent e) {
                Action action = e.getAction();
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    if (editCommand.isInEditMode(e.getPlayer().getUniqueId())) {
                        e.getPlayer().sendMessage("§7[debug] left-click received in edit mode");
                    }
                    editCommand.handleInteract(e.getPlayer(), true);
                }
            }

            @EventHandler
            public void onSneak(PlayerToggleSneakEvent e) {
                if (e.isSneaking()) {
                    editCommand.handleSneak(e.getPlayer());
                }
            }
        }, this);

        PluginCommand clashCommand = getCommand("clash");
        if (clashCommand == null) {
            getLogger().severe("Command 'clash' missing from plugin.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ClashCommand executor = new ClashCommand(villageManager, this, editCommand);
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

            ArcherConfig archerConfig = ArcherConfig.load(getConfig(), getLogger());
            this.archerManager = new ArcherManager(this, archerConfig, villageManager,
                    testBaseManager, healthBarManager, null);
            villageManager.setArcherManager(archerManager);

            getServer().getPluginManager().registerEvents(new AttackListener(this, barbManager, archerManager), this);

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
        if (archerManager != null) {
            archerManager.clear();
        }
        if (healthBarManager != null) {
            healthBarManager.shutdown();
        }
    }

    public BarbConfig getBarbConfig() {
        return barbConfig;
    }

    public EditCommand getEditCommand() {
        return editCommand;
    }

    public ArcherManager getArcherManager() {
        return archerManager;
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