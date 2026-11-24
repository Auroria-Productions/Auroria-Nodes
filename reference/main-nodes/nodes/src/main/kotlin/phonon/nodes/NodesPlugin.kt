/*
 * Implement bukkit plugin interface
 */

package phonon.nodes

import org.bukkit.Bukkit
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import phonon.nodes.commands.AllyChatCommand
import phonon.nodes.commands.AllyCommand
import phonon.nodes.commands.CapTimeCommand
import phonon.nodes.commands.GlobalChatCommand
import phonon.nodes.commands.IncomeInfoCommand
import phonon.nodes.commands.KdrCommand
import phonon.nodes.commands.LanguageCommand
import phonon.nodes.commands.LocalChatCommand
import phonon.nodes.commands.MarketCommand
import phonon.nodes.commands.MiningTowerDebugCommand
import phonon.nodes.commands.NationChatCommand
import phonon.nodes.commands.NationCommand
import phonon.nodes.commands.NodesAdminCommand
import phonon.nodes.commands.NodesCommand
import phonon.nodes.commands.NodesOptionsCommand
import phonon.nodes.commands.NodesSweepCommand
import phonon.nodes.commands.PeaceCommand
import phonon.nodes.commands.PlayerCommand
import phonon.nodes.commands.PortCommand
import phonon.nodes.commands.ReportCommand
import phonon.nodes.commands.TerritoryCommand
import phonon.nodes.commands.TownChatCommand
import phonon.nodes.commands.TownCommand
import phonon.nodes.commands.TownControlCommand
import phonon.nodes.commands.TruceCommand
import phonon.nodes.commands.UnallyCommand
import phonon.nodes.commands.WarCommand
import phonon.nodes.contracts.ContractConfig
import phonon.nodes.contracts.TownContractManager
import phonon.nodes.contracts.TownContractsGUI
import phonon.nodes.killtracker.KillTrackerManager
import phonon.nodes.language.Language
import phonon.nodes.listeners.DisabledWorldListener
import phonon.nodes.listeners.KillTrackerListener
import phonon.nodes.listeners.NodesBlockGrowListener
import phonon.nodes.listeners.NodesChatListener
import phonon.nodes.listeners.NodesChestProtectionDestroyListener
import phonon.nodes.listeners.NodesChestProtectionListener
import phonon.nodes.listeners.NodesDiplomacyAllianceListener
import phonon.nodes.listeners.NodesDiplomacyTruceExpiredListener
import phonon.nodes.listeners.NodesEntityBreedListener
import phonon.nodes.listeners.NodesGuiListener
import phonon.nodes.listeners.NodesIncomeInventoryListener
import phonon.nodes.listeners.NodesLanguageSelectionListener
import phonon.nodes.listeners.NodesPlayerAFKKickListener
import phonon.nodes.listeners.NodesPlayerDamageListener
import phonon.nodes.listeners.NodesPlayerJoinQuitListener
import phonon.nodes.listeners.NodesPlayerMoveListener
import phonon.nodes.listeners.NodesPlotListener
import phonon.nodes.listeners.NodesRedstoneTickListener
import phonon.nodes.listeners.NodesSheepShearListener
import phonon.nodes.listeners.NodesWarFlagArmorStandListener
import phonon.nodes.listeners.NodesWorldListener
import phonon.nodes.market.MarketConfig
import phonon.nodes.market.MarketGUI
import phonon.nodes.market.MarketManager
import phonon.nodes.miningtower.MiningRateListener
import phonon.nodes.miningtower.MiningTowerCommand
import phonon.nodes.miningtower.MiningTowerConfig
import phonon.nodes.miningtower.MiningTowerPendingGUI
import phonon.nodes.miningtower.MiningTowerRatingGUI
import phonon.nodes.miningtower.MiningTowerSubmissionGUI
import phonon.nodes.nametags.NametagListener
import phonon.nodes.nodesaddons.NodesMineBuff
import phonon.nodes.overview.MarketOverviewService
import phonon.nodes.overview.RallyOverviewService
import phonon.nodes.overview.WebOverviewService
import phonon.nodes.placeholders.NodesExpansion
import phonon.nodes.plot.PlotManager
import phonon.nodes.tab.LuckPermsHook
import phonon.nodes.tab.TabList
import phonon.nodes.tab.TabListConfig
import phonon.nodes.tab.TabListListener
import phonon.nodes.tasks.SaveManager
import phonon.nodes.towncontrol.TownCommandBlocker
import phonon.nodes.towncontrol.TownControlConfig
import phonon.nodes.utils.leaderlaunch.LeaderLaunchManager
import phonon.nodes.utils.loadLongFromFile
import phonon.nodes.war.FlagWar
import phonon.nodes.war.WarAccessListManager
import phonon.nodes.warzone.WarzoneChatListener
import phonon.nodes.warzone.WarzoneCommand
import phonon.nodes.warzone.WarzoneManager
import phonon.nodes.warzone.WarzoneRewardListener

public class NodesPlugin : JavaPlugin() {

    companion object {
        /**
         * Singleton reference used by tablist (and any other subsystem that
         * needs a plugin handle without a direct constructor injection).
         * Set at the very start of onEnable, before any subsystem is started.
         */
        lateinit var plugin: NodesPlugin
            private set
    }

    override fun onEnable() {
        // Expose singleton before anything else touches it
        plugin = this

        // measure load time
        val timeStart = System.currentTimeMillis()

        val logger = this.getLogger()
        val pluginManager = this.getServer().getPluginManager()

        // initialize nodes
        Nodes.initialize(this)
        Nodes.refreshIntegrationHooks()

        if (Nodes.placeholderAPI != null) {
            NodesExpansion().register()
        }

        // ===================================
        // save hooks to external plugins
        // ===================================
        // dynmap hook, just flag that dynmap exists
        val dynmap = Nodes.dynmapPlugin ?: pluginManager.getPlugin("dynmap")
        if (dynmap !== null) {
            Nodes.hookDynmap()
            logger.info("Using Dynmap v${dynmap.getDescription().getVersion()}")
        }

        // ===================================
        // load config
        // ===================================
        Nodes.reloadConfig()
        WarAccessListManager.initialize()
        LeaderLaunchManager.load()
        KillTrackerManager.load()
        KillTrackerManager.initializeAutoSave()
        PlotManager.load()
        TownContractManager.initialize()
        PlotManager.initializeAutoSave()

        Nodes.war.initialize(Config.flagMaterials)
        WarzoneManager.initialize(this)
        NodesMineBuff.initialize()

        // try load world
        val pluginPath = Config.pathPlugin
        logger.info("Loading world from: $pluginPath")
        try {
            if (Nodes.loadWorld() == true) { // successful load
                Nodes.logLoadSummary(logger)
                WarzoneManager.refreshTownAssignments()
                WarAccessListManager.refreshTownNames()
            } else {
                logger.severe("Error loading world: Invalid world file at $pluginPath/${Config.pathWorld}")
                if (Config.disableWorldWhenLoadFails) {
                    logger.severe("Disabling world interactions due to world load error")
                    pluginManager.registerEvents(DisabledWorldListener(), this)
                    return
                }
            }
        } catch (err: Exception) {
            err.printStackTrace()
            logger.severe("Error loading world: $err")
            if (Config.disableWorldWhenLoadFails) {
                logger.severe("Disabling world interactions due to world load error")
                pluginManager.registerEvents(DisabledWorldListener(), this)
                return
            }
        }

        // register listeners
        pluginManager.registerEvents(KillTrackerListener(), this)
        pluginManager.registerEvents(NodesBlockGrowListener(), this)
        pluginManager.registerEvents(NodesRedstoneTickListener(), this)
        pluginManager.registerEvents(NodesChatListener(), this)
        pluginManager.registerEvents(NodesChestProtectionListener(), this)
        pluginManager.registerEvents(NodesChestProtectionDestroyListener(), this)
        pluginManager.registerEvents(NodesDiplomacyAllianceListener(), this)
        pluginManager.registerEvents(NodesDiplomacyTruceExpiredListener(), this)
        pluginManager.registerEvents(NodesEntityBreedListener(), this)
        pluginManager.registerEvents(NodesGuiListener(), this)
        pluginManager.registerEvents(TownContractsGUI, this)
        pluginManager.registerEvents(NodesIncomeInventoryListener(), this)
        pluginManager.registerEvents(NodesLanguageSelectionListener(), this)
        pluginManager.registerEvents(NodesWorldListener(), this)
        pluginManager.registerEvents(NodesPlayerAFKKickListener(), this)
        pluginManager.registerEvents(NametagListener(this), this)
        pluginManager.registerEvents(NodesPlayerJoinQuitListener(), this)
        pluginManager.registerEvents(NodesPlayerMoveListener(), this)
        pluginManager.registerEvents(NodesPlotListener(), this)
        pluginManager.registerEvents(NodesSheepShearListener(), this)
        pluginManager.registerEvents(NodesWarFlagArmorStandListener(), this)
        pluginManager.registerEvents(NodesPlayerDamageListener(), this)
        pluginManager.registerEvents(WarzoneChatListener(), this)
        pluginManager.registerEvents(WarzoneRewardListener, this)

        fun registerCommand(name: String, executor: CommandExecutor) {
            val command = this.getCommand(name) ?: return
            command.setExecutor(executor)
            if (executor is TabCompleter) {
                command.setTabCompleter(executor)
            }
        }

        // register commands
        registerCommand("town", TownCommand())
        registerCommand("nation", NationCommand())
        registerCommand("nodes", NodesCommand())
        registerCommand("noptions", NodesOptionsCommand())
        registerCommand("nodesweep", NodesSweepCommand())
        registerCommand("minebuff", NodesMineBuff)
        registerCommand("minebufftown", NodesMineBuff)
        registerCommand("nodesadmin", NodesAdminCommand())
        registerCommand("ally", AllyCommand())
        registerCommand("unally", UnallyCommand())
        registerCommand("war", WarCommand())
        registerCommand("peace", PeaceCommand())
        registerCommand("truce", TruceCommand())
        registerCommand("globalchat", GlobalChatCommand())
        registerCommand("townchat", TownChatCommand())
        registerCommand("nationchat", NationChatCommand())
        registerCommand("allychat", AllyChatCommand())
        registerCommand("localchat", LocalChatCommand())
        registerCommand("player", PlayerCommand())
        registerCommand("territory", TerritoryCommand())
        registerCommand("port", PortCommand())
        registerCommand("report", ReportCommand())
        registerCommand("language", LanguageCommand())
        registerCommand("nekdr", KdrCommand())
        registerCommand("warzone", WarzoneCommand())
        registerCommand("captime", CapTimeCommand())

        // override command aliases tab complete if they exist
        this.getCommand("t")?.setTabCompleter(this.getCommand("town")?.getExecutor() as TabCompleter)
        this.getCommand("n")?.setTabCompleter(this.getCommand("nation")?.getExecutor() as TabCompleter)
        this.getCommand("nd")?.setTabCompleter(this.getCommand("nodes")?.getExecutor() as TabCompleter)
        this.getCommand("nda")?.setTabCompleter(this.getCommand("nodesadmin")?.getExecutor() as TabCompleter)
        this.getCommand("gc")?.setTabCompleter(this.getCommand("globalchat")?.getExecutor() as TabCompleter)
        this.getCommand("p")?.setTabCompleter(this.getCommand("player")?.getExecutor() as TabCompleter)

        // load current income tick
        val currTime = System.currentTimeMillis()
        Nodes.lastBackupTime = loadLongFromFile(Config.pathLastBackupTime) ?: currTime
        Nodes.lastIncomeTime = loadLongFromFile(Config.pathLastIncomeTime) ?: currTime

        // run background schedulers/tasks
        Nodes.saveWorldToDynmap(async = true)
        Nodes.reloadManagers()
        WebOverviewService.start()
        RallyOverviewService.start()

        // ===================================
        // extensions bootstrap
        // ===================================
        this.config.options().copyDefaults(true)
        TownControlConfig.setDefaults(this.config)
        ContractConfig.setDefaults(this.config)
        MarketConfig.setDefaults(this.config)
        TabListConfig.setDefaults(this.config)
        this.saveConfig()

        TownControlConfig.load(this.config)
        MarketConfig.load(this.config)
        MiningTowerConfig.load(this.config)
        ContractConfig.load(this.config)
        TabListConfig.load(this.config.getConfigurationSection("tablist"))

        MarketManager.load()
        MiningOutminerManager.load()
        MiningTowerManager.load()

        // ── Tablist ──────────────────────────────────────────────────────────
        LuckPermsHook.init()
        pluginManager.registerEvents(TabListListener(), this)

        if (TabListConfig.enabled) {
            // Periodic full refresh — re-sorts teams AND refreshes header/footer
            // for every player on the configured interval.
            val intervalTicks = TabListConfig.updateIntervalTicks.coerceAtLeast(20L)
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                this,
                { _ -> TabList.pipelinedUpdateAll() },
                intervalTicks,
                intervalTicks,
            )
        }
        // ────────────────────────────────────────────────────────────────────

        pluginManager.registerEvents(TownCommandBlocker(), this)
        pluginManager.registerEvents(MarketGUI, this)
        pluginManager.registerEvents(MiningOutminerManager, this)
        pluginManager.registerEvents(MiningTowerSubmissionGUI, this)
        pluginManager.registerEvents(MiningTowerPendingGUI, this)
        pluginManager.registerEvents(MiningTowerRatingGUI, this)
        pluginManager.registerEvents(MiningRateListener(), this)

        registerCommand("neincome", IncomeInfoCommand())
        registerCommand("nemarket", MarketCommand())
        registerCommand("miningtower", MiningTowerCommand())
        this.getCommand("miningtowerdebug")?.setExecutor(MiningTowerDebugCommand())
        registerCommand("netowncontrol", TownControlCommand())
        MarketOverviewService.start()

        // initialize all players online
        Nodes.initializeOnlinePlayers()
        RallyOverviewService.recordSnapshot(System.currentTimeMillis())

        // set final initialized flag
        Nodes.initialized = true

        FlagWar.plugin = this

        Nodes.enabled = true

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        logger.info("Enabled in ${timeLoad}ms")

        // print success message
        phonon.nodes.core.CoreHooks.enable(this)
        logger.info("now this is epic")
    }

    override fun onDisable() {
        phonon.nodes.core.CoreHooks.disable()
        logger.info("=========================================")
        logger.info("Nodes:")
        logger.info("- Nodes is disabling, goodbye!")
        logger.info("wtf i hate xeth now")
        logger.info("=========================================")

        Nodes.enabled = false

        // Stop tablist before scheduler shutdown so no orphaned tasks remain

        SaveManager.stop()
        PeriodicTickManager.stop()

        if (Nodes.initialized) {
            try {
                Nodes.saveWorld(checkIfNeedsSave = false, async = false)
                Nodes.saveTruce(async = false)
                MarketManager.save()
                MiningOutminerManager.save()
                MiningOutminerManager.shutdown()
                MiningTowerManager.save()
                logger.info("Pre-shutdown save completed")
            } catch (err: Exception) {
                err.printStackTrace()
                logger.severe("Pre-shutdown save failed: $err")
            }
        } else {
            MarketManager.save()
            MiningOutminerManager.save()
            MiningOutminerManager.shutdown()
            MiningTowerManager.save()
        }

        WebOverviewService.stop()
        RallyOverviewService.stop()
        MarketOverviewService.stop()
        NodesMineBuff.shutdown()
        WarzoneManager.shutdown()
        WarAccessListManager.shutdown()
        KillTrackerManager.shutdown()
        PlotManager.shutdown()
        Language.shutdown()
        LeaderLaunchManager.shutdown()
        TownContractManager.shutdown()

        // world cleanup
        Nodes.cleanup()

        // final synchronous save of world
        if (Nodes.initialized) {
            Nodes.saveWorld(checkIfNeedsSave = false, async = false)
            Nodes.saveTruce(async = false)
            logger.info("World saved successfully")
        }
    }
}
