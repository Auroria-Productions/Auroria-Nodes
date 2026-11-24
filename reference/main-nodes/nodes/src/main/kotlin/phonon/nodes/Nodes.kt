/*
 * Nodes Engine/API
 */

package phonon.nodes

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Display
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import phonon.nodes.chat.ChatMode
import phonon.nodes.constants.DiplomaticRelationship
import phonon.nodes.constants.ErrorAlreadyAllies
import phonon.nodes.constants.ErrorAlreadyEnemies
import phonon.nodes.constants.ErrorAlreadyTruce
import phonon.nodes.constants.ErrorNationDoesNotHaveTown
import phonon.nodes.constants.ErrorNationExists
import phonon.nodes.constants.ErrorNotAllies
import phonon.nodes.constants.ErrorPlayerHasNation
import phonon.nodes.constants.ErrorPlayerHasTown
import phonon.nodes.constants.ErrorPlayerNotInTown
import phonon.nodes.constants.ErrorPortExists
import phonon.nodes.constants.ErrorPortInGroup
import phonon.nodes.constants.ErrorTerritoryHasClaim
import phonon.nodes.constants.ErrorTerritoryIsTownHome
import phonon.nodes.constants.ErrorTerritoryNotConnected
import phonon.nodes.constants.ErrorTerritoryNotInTown
import phonon.nodes.constants.ErrorTerritoryOwned
import phonon.nodes.constants.ErrorTooManyClaims
import phonon.nodes.constants.ErrorTownDoesNotExist
import phonon.nodes.constants.ErrorTownExists
import phonon.nodes.constants.ErrorTownHasNation
import phonon.nodes.constants.ErrorWarAllyOrTruce
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.event.AllianceCreatedEvent
import phonon.nodes.event.NodesTerritoriesLoadedEvent
import phonon.nodes.event.NodesWorldLoadedEvent
import phonon.nodes.event.TruceExpiredEvent
import phonon.nodes.language.Language
import phonon.nodes.language.PlayerLanguage
import phonon.nodes.nms.evictTeamFromPool
import phonon.nodes.objects.Coord
import phonon.nodes.objects.DefaultResourceAttributeLoader
import phonon.nodes.objects.MinimapOrientation
import phonon.nodes.objects.Nametag
import phonon.nodes.objects.Nation
import phonon.nodes.objects.NationPair
import phonon.nodes.objects.OreBlockCache
import phonon.nodes.objects.OreSampler
import phonon.nodes.objects.OutpostApplication
import phonon.nodes.objects.Port
import phonon.nodes.objects.PortApplication
import phonon.nodes.objects.PortGroup
import phonon.nodes.objects.PortTier
import phonon.nodes.objects.Resident
import phonon.nodes.objects.ResourceNode
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.TerritoryPreprocessing
import phonon.nodes.objects.TerritoryResources
import phonon.nodes.objects.Town
import phonon.nodes.objects.TownOutpost
import phonon.nodes.objects.TownPair
import phonon.nodes.plot.PlotManager
import phonon.nodes.serdes.Deserializer
import phonon.nodes.serdes.TownRecord
import phonon.nodes.tasks.SaveManager
import phonon.nodes.tasks.TaskCopyToDynmap
import phonon.nodes.tasks.TaskSaveDynmapClaimsConfig
import phonon.nodes.tasks.TaskSavePorts
import phonon.nodes.tasks.TaskSaveWorld
import phonon.nodes.utils.Color
import phonon.nodes.utils.sanitizeString
import phonon.nodes.utils.saveStringToFile
import phonon.nodes.war.AttackMessageMode
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Truce
import phonon.nodes.war.annexBlacklist
import phonon.nodes.war.blacklist
import phonon.nodes.war.whitelist
import phonon.nodes.warzone.WarzoneManager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.system.measureNanoTime

/**
 * Nodes container
 */
public object Nodes {

    // version string
    internal val version: String = "v1.15.5"

    // library of resource node definitions
    internal val resourceNodes: HashMap<String, ResourceNode> = hashMapOf()

    public data class TerritoryResourceSnapshot(
        val income: EnumMap<Material, Double>,
        val incomeSpawnEgg: EnumMap<EntityType, Double>,
        val oreSampler: OreSampler,
        val crops: EnumMap<Material, Double>,
        val animals: EnumMap<EntityType, Double>,
    )

    public fun getTerritoryResourceSnapshot(territory: Territory): TerritoryResourceSnapshot = if (Config.useTerritoryResourceValues) {
        TerritoryResourceSnapshot(
            income = territory.income,
            incomeSpawnEgg = territory.incomeSpawnEgg,
            oreSampler = territory.ores,
            crops = territory.crops,
            animals = territory.animals,
        )
    } else {
        buildResourceSnapshot(territory)
    }

    private fun buildResourceSnapshot(territory: Territory): TerritoryResourceSnapshot {
        var resources = Config.globalResources.copy()
        for (resourceName in territory.resourceNodes) {
            val node = Nodes.resourceNodes[resourceName]
            if (node != null) {
                resources = node.apply(resources)
            }
        }
        return TerritoryResourceSnapshot(
            income = resources.income,
            incomeSpawnEgg = resources.incomeSpawnEgg,
            oreSampler = OreSampler(ArrayList(resources.ores.values)),
            crops = resources.crops,
            animals = resources.animals,
        )
    }

    // world grid that maps coord -> territory chunk wrapper
    internal val territoryChunks: HashMap<Coord, TerritoryChunk> = hashMapOf()

    // map territory id -> Territory
    internal val territories: HashMap<TerritoryId, Territory> = hashMapOf()

    // list of all towns
    internal val towns: LinkedHashMap<String, Town> = LinkedHashMap()

    // list of all nations
    internal val nations: LinkedHashMap<String, Nation> = LinkedHashMap()

    // map player UUID -> Resident player wrapper
    internal val residents: LinkedHashMap<UUID, Resident> = LinkedHashMap()

    // ports system
    internal val ports: LinkedHashMap<String, Port> = LinkedHashMap()
    internal val portGroups: LinkedHashMap<String, PortGroup> = LinkedHashMap()
    internal val portApplications: LinkedHashMap<Int, PortApplication> = LinkedHashMap()
    internal val playerPortApplications: HashMap<UUID, Int> = hashMapOf()
    internal var nextPortApplicationId: Int = 1
    internal val outpostApplications: LinkedHashMap<Int, OutpostApplication> = LinkedHashMap()
    internal val playerOutpostApplications: HashMap<UUID, Int> = hashMapOf()
    internal var nextOutpostApplicationId: Int = 1

    // map of player -> task for warping
    public var playerWarpTasks: HashMap<UUID, ScheduledTask> = hashMapOf()

    // map chunk coords -> port, assumes one chunk only has 1 port
    public var chunkToPort: HashMap<List<Int>, Port> = hashMapOf()

    // last time backup occurred: NOTE this is accessed async
    internal var lastBackupTime: Long = 0 // milliseconds

    // last time income occurred: NOTE this is accessed async
    internal var lastIncomeTime: Long = 0 // milliseconds

    // war manager
    public val war = FlagWar

    @Volatile
    public var warDeclarationsEnabled: Boolean = true

    // flag that world was updated and needs save
    internal var needsSave: Boolean = false
    private val saveRequestVersion: AtomicLong = AtomicLong(0L)
    private val townFlushLock = Any()

    @Volatile
    private var townFlushQueued: Boolean = false

    @Volatile
    private var townFlushNeedsTruceSave: Boolean = false

    // set of invalid block locations for hidden ore drops
    internal val hiddenOreInvalidBlocks: OreBlockCache = OreBlockCache(2000)

    // hooks to other plugins
    internal var dynmap: Boolean = false // simple flag
    internal val DYNMAP_DIR: Path = Paths.get("plugins/dynmap/web/nodes")
    internal val DYNMAP_PATH_NODES_CONFIG: Path = Paths.get("plugins/dynmap/web/nodes/config.json")
    internal val DYNMAP_PATH_WORLD: Path = Paths.get("plugins/dynmap/web/nodes/world.json")
    internal val DYNMAP_PATH_TOWNS: Path = Paths.get("plugins/dynmap/web/nodes/towns.json")
    internal val DYNMAP_PATH_PORTS: Path = Paths.get("plugins/dynmap/web/nodes/ports.json")
    internal val DYNMAP_PATH_WARZONES: Path = Paths.get("plugins/dynmap/web/nodes/warzones.json")

    // minecraft plugin variable
    internal var plugin: Plugin? = null
    internal var logger: Logger? = null

    /**
     * External plugin hooks/integrations.
     *
     * These are best-effort references (may be null) and are refreshed on enable/reload.
     */
    @Volatile public var placeholderAPI: Plugin? = null

    @Volatile public var luckperms: Plugin? = null

    // Backwards-compatible alias (some call sites expect Nodes.luckPerms).
    public var luckPerms: Plugin?
        get() = luckperms
        set(value) {
            luckperms = value
        }

    @Volatile public var tab: Plugin? = null

    @Volatile public var superVanish: Plugin? = null

    @Volatile public var premiumVanish: Plugin? = null

    @Volatile public var dynmapPlugin: Plugin? = null

    @Volatile private var siegeChatFilterPlugin: Plugin? = null

    public var SiegeChatFilter: Plugin?
        get() = siegeChatFilterPlugin
        set(value) {
            siegeChatFilterPlugin = value
        }

    // Vault economy provider (null when Vault is absent or has no economy registered)
    @Volatile public var economy: net.milkbowl.vault.economy.Economy? = null

    public data class IntegrationStatus(
        val name: String,
        val present: Boolean,
        val enabled: Boolean,
        val version: String?,
    )

    public fun refreshIntegrationHooks() {
        val pluginManager = Bukkit.getPluginManager()
        placeholderAPI = pluginManager.getPlugin("PlaceholderAPI")
        luckperms = pluginManager.getPlugin("LuckPerms")
        tab = pluginManager.getPlugin("TAB")
        superVanish = pluginManager.getPlugin("SuperVanish")
        premiumVanish = pluginManager.getPlugin("PremiumVanish")
        dynmapPlugin = pluginManager.getPlugin("dynmap")
        SiegeChatFilter = pluginManager.getPlugin("SiegeChatFilter")

        // Hook Vault economy
        val vaultPlugin = pluginManager.getPlugin("Vault")
        economy = if (vaultPlugin != null && vaultPlugin.isEnabled) {
            Bukkit.getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy::class.java)
                ?.provider
        } else {
            null
        }
    }

    public fun getIntegrationStatuses(): List<IntegrationStatus> {
        fun status(name: String, plugin: Plugin?): IntegrationStatus = IntegrationStatus(
            name = name,
            present = plugin != null,
            enabled = plugin?.isEnabled == true,
            version = plugin?.description?.version,
        )

        return listOf(
            status("PlaceholderAPI", placeholderAPI),
            status("LuckPerms", luckperms),
            status("TAB", tab),
            status("SuperVanish", superVanish),
            status("PremiumVanish", premiumVanish),
            status("dynmap", dynmapPlugin),
            status("SiegeChatFilter", SiegeChatFilter),
        )
    }

    // flag that plugin successfully initialized
    internal var initialized: Boolean = false

    // flag that plugin is running
    internal var enabled: Boolean = false

    // initialization:
    // - set links to plugin variables
    internal fun initialize(plugin: Plugin) {
        Nodes.plugin = plugin
        Nodes.logger = plugin.getLogger()
    }

    /**
     * reload config file
     */
    internal fun reloadConfig() {
        val plugin = Nodes.plugin
        if (plugin === null) {
            return
        }

        val logger = plugin.getLogger()

        // get config file
        val configFile = File(plugin.getDataFolder().getPath(), "config.yml")
        if (!configFile.exists()) {
            logger.info("No config found: generating default config.yml")
            plugin.saveDefaultConfig()
        }

        val config = ConfigVersioning.loadMainConfig(plugin, configFile)
        Config.load(config)
        Language.load(Config.pathPlugin)
    }

    /**
     * Reload background managers/tasks
     */
    internal fun reloadManagers() {
        SaveManager.stop()
        PeriodicTickManager.stop()

        val plugin = Nodes.plugin
        if (plugin === null) {
            return
        }

        SaveManager.start(plugin, Config.savePeriod)
        PeriodicTickManager.start(plugin, Config.mainPeriodicTick)
    }

    // mark all current players in game as online
    // needed to correctly mark online players after reloading plugin
    internal fun initializeOnlinePlayers() {
        for (player in Bukkit.getOnlinePlayers()) {
            // create resident for player if it does not exist
            Nodes.createResident(player)

            // mark player online
            val resident = Nodes.getResident(player)!!
            Nodes.setResidentOnline(resident, player)
        }

        // update nametags
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateAllCaches()
    }

    // clean up any world details
    // run when plugin disabled
    internal fun cleanup() {
        // cleanup residents
        for (r in Nodes.residents.values) {
            // remove minimaps
            r.destroyMinimap()
        }

        // force push all town income items from inventory gui
        // back to storage data structure
        for (town in Nodes.towns.values) {
            val result = town.income.pushToStorage(true)
            if (result == true) { // has moved items
                town.needsUpdate()
            }
        }

        // cleanup war if its enabled
        if (Nodes.war.hasPersistentState()) {
            Nodes.war.cleanup()
        }

        // save backup, income current time
        val currTimeString = System.currentTimeMillis().toString()
        saveStringToFile(currTimeString, Config.pathLastBackupTime)
        saveStringToFile(currTimeString, Config.pathLastIncomeTime)
    }

    internal fun nextSaveRequestVersion(): Long = saveRequestVersion.incrementAndGet()

    internal fun latestSaveRequestVersion(): Long = saveRequestVersion.get()

    /**
     * Load resource nodes from json files and insert them into
     * the global `Nodes.resourceNodes` map.
     */
    internal fun loadResources(json: JsonObject) {
        // TODO: generalize to multipler loaders
        Nodes.resourceNodes.putAll(DefaultResourceAttributeLoader.load(HashMap(0), json))
    }

    /**
     * Load territories from json and insert into the global
     * `Nodes.territories` map. Raises exceptions if any territory ids
     * do not exist.
     */
    internal fun loadTerritories(json: JsonObject, ids: List<TerritoryId>? = null) {
        val territoryPreprocessing: List<TerritoryPreprocessing> = TerritoryPreprocessing.loadFromJson(json, ids)

        // first create intermediary resource graph before creating
        // final compiled territories.
        // - if ids == null, we are re-creating all territories, so this
        // will be populated with resources from all territories
        // - if ids != null, we are re-creating only a subset of territories,
        // so we need to also pre-populate this with resources from existing
        // territory neighbors AND NEIGHBOR's NEIGHBORS. This is because we cannot
        // know if neighbor's neighbors also have neighbor modifiers, so this
        // must pre-emptively load neighbor's neighbors resources.
        val terrResourceGraph: HashMap<TerritoryId, TerritoryResources> = HashMap(0)

        if (ids != null) { // add neighbor territories to terrResourceGraph
            val neighborResourcesToLoad = HashSet<TerritoryId>()

            for (id in ids) {
                val currTerr = Nodes.territories[id]
                if (currTerr != null) {
                    // add neighbor territory resources into territory resource graph
                    for (neighborId in currTerr.neighbors) {
                        val neighborTerr = Nodes.territories[neighborId]
                        if (neighborTerr != null) {
                            neighborResourcesToLoad.add(neighborId)
                            // also add neighbor's neighbor ids
                            for (nnId in neighborTerr.neighbors) {
                                neighborResourcesToLoad.add(nnId)
                            }
                        } else {
                            Nodes.logger!!.warning("`loadTerritories()` Territory $id neighbor $neighborId does not exist")
                        }
                    }
                } else {
                    Nodes.logger!!.warning("`loadTerritories()` reloading territory $id does not exist")
                }
            }

            for (id in neighborResourcesToLoad) {
                val neighborTerr = Nodes.territories[id]
                if (neighborTerr != null) {
                    val baseResources = neighborTerr.resourceNodes
                        .map { name -> Nodes.resourceNodes[name] ?: throw Exception("Resource node '$name' does not exist (for territory id=${neighborTerr.id})") }
                    val nodeTypeResources = neighborTerr.nodeTypes.mapNotNull { type -> Config.nodeTypes[type] }
                    val resources = (baseResources + nodeTypeResources).sortedBy { r -> r.priority }

                    terrResourceGraph[id] = resources.fold(Config.globalResources.copy(), { terr, r -> r.apply(terr) })
                } else {
                    Nodes.logger!!.warning("`loadTerritories()` neighbor territory $id does not exist")
                }
            }
        }

        // adds territories to be loaded to terrResourceGraph
        for (terr in territoryPreprocessing) {
            val baseResources = terr.resourceNodes
                .map { name -> Nodes.resourceNodes[name] ?: throw Exception("Resource node '$name' does not exist (for territory id=${terr.id})") }
            val nodeTypeResources = terr.nodeTypes.mapNotNull { type -> Config.nodeTypes[type] }
            val resources = (baseResources + nodeTypeResources).sortedBy { r -> r.priority }

            terrResourceGraph[terr.id] = resources.fold(Config.globalResources.copy(), { tr, r -> r.apply(tr) })
        }

        // Determine final territories to be built.
        // If ids == null, then all territories in preprocessing phase are built.
        // If ids != null, then all territories in preprocessing AND
        //    their neighbors IF territory has neighbor modifiers.
        val territoriesToBuild: List<TerritoryPreprocessing> = if (ids == null) {
            territoryPreprocessing
        } else {
            val neighborIdsToRebuild = HashSet<TerritoryId>()
            for (terr in territoryPreprocessing) {
                if (terrResourceGraph[terr.id]!!.hasNeighborModifier) {
                    for (neighborId in terr.neighbors) {
                        neighborIdsToRebuild.add(neighborId)
                    }
                }
            }

            // remove main reloaded territory ids to avoid double-counting
            for (terr in territoryPreprocessing) {
                neighborIdsToRebuild.remove(terr.id)
            }

            val terrNeighborsToRebuild = neighborIdsToRebuild
                .map { id -> Nodes.territories.get(id)?.toPreprocessing() }
                .filterNotNull()

            territoryPreprocessing + terrNeighborsToRebuild
        }

        // Apply neighboring territory modifier properties to territories to be built
        for (terr in territoriesToBuild) {
            val terrResources = terrResourceGraph[terr.id]
            if (terrResources != null) {
                var terrAfterNeighborModifiers: TerritoryResources = terrResources // required assignment to ensure terrAfterNeighborModifiers is not null
                for (neighborId in terr.neighbors) {
                    terrResourceGraph[neighborId]?.let { neighborResources ->
                        if (neighborResources.hasNeighborModifier) {
                            terrAfterNeighborModifiers = terrAfterNeighborModifiers.accumulateNeighborModifiers(neighborResources)
                        }
                    }
                }
                terrResourceGraph[terr.id] = terrAfterNeighborModifiers
            } else {
                Nodes.logger?.warning("`loadTerritories()` Invalid territory id: ${terr.id}")
            }
        }

        // merge territory structural properties and resources
        // to create final territory
        for (t in territoriesToBuild) {
            // ensure coreChunk inside chunks
            if (!t.chunks.contains(t.core)) {
                Nodes.logger?.warning("[Nodes] Territory ${t.id} chunk does not contain core, skipping")
                continue // was: return
            }

            // get resources and apply all accumulated neighbor modifiers
            val resources = terrResourceGraph[t.id]!!.applyNeighborModifiers()

            // sorted resource names
            val resourceNamesSorted = t.resourceNodes.sortedBy { name -> Nodes.resourceNodes[name]!!.priority }

            // node types in config (ignore undefined)
            val nodeTypesSorted = t.nodeTypes
                .filter { Config.nodeTypes.containsKey(it) }
                .sortedBy { name -> Config.nodeTypes[name]?.priority ?: 0 }

            // create OreSampler from ores map
            val ores = OreSampler(ArrayList(resources.ores.values))

            // calculate territory cost
            val cost = Nodes.calculateTerritoryCost(t.chunks.size, resourceNamesSorted)

            // create territory
            val territory = Territory(
                id = t.id,
                name = t.name,
                color = t.color,
                core = t.core,
                chunks = t.chunks,
                bordersWilderness = t.bordersWilderness,
                neighbors = t.neighbors,
                resourceNodes = resourceNamesSorted,
                nodeTypes = nodeTypesSorted,
                cost = cost,
                income = resources.income,
                incomeSpawnEgg = resources.incomeSpawnEgg,
                ores = ores,
                crops = resources.crops,
                animals = resources.animals,
                customProperties = resources.customProperties,
                attackerTimeMultiplier = resources.attackerTimeMultiplier,
                defenderTimeMultiplier = resources.defenderTimeMultiplier,
            )

            // if previous territory existed, first do cleanup and copy mutable ingame properties
            Nodes.territories[t.id]?.let { oldTerritory ->
                // remove old territory chunks
                oldTerritory.chunks.forEach { c -> Nodes.territoryChunks.remove(c) }
                // copy town and occupier
                territory.town = oldTerritory.town
                territory.occupier = oldTerritory.occupier
            }

            // set territory
            Nodes.territories.put(t.id, territory)

            // create territory chunks in world grid and map to territory
            t.chunks.forEach { c ->
                Nodes.territoryChunks.put(c, TerritoryChunk(c, territory))
            }
        }

        Bukkit.getPluginManager().callEvent(NodesTerritoriesLoadedEvent())
    }

    /**
     * Wrapper for reloading resources or territories.
     * Returns boolean if reload was successful.
     */
    internal fun reloadWorldJson(
        reloadResources: Boolean,
        reloadTerritories: Boolean,
        territoryIds: List<TerritoryId>? = null,
    ): Boolean {
        if (!Files.exists(Config.pathWorld)) {
            return false
        }

        return try {
            // streaming loader handles everything internally now
            Deserializer.worldFromJson(Config.pathWorld)

            // dynmap copy after successful load (async to avoid blocking main thread)
            if (Nodes.dynmap == true || Config.dynmapCopyTowns) {
                val plugin = Nodes.plugin
                if (plugin != null) {
                    Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
                        try {
                            TaskCopyToDynmap(Config.pathWorld, DYNMAP_DIR, DYNMAP_PATH_WORLD).run()
                        } catch (e: Exception) {
                            Bukkit.getLogger().warning("[Nodes] Failed to copy world.json to dynmap: ${e.message}")
                        }
                    }
                }
            }

            true
        } catch (e: Exception) {
            Bukkit.getLogger().severe("Failed to reload world.json: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    internal fun loadWorld(): Boolean {
        // clear storage
        Nodes.resourceNodes.clear()
        Nodes.territoryChunks.clear()
        Nodes.territories.clear()
        Nodes.towns.clear()
        Nodes.nations.clear()
        Nodes.residents.clear()
        Nodes.ports.clear()

        if (!Files.exists(Config.pathWorld)) {
            System.err.println("Failed to load world: ${Config.pathWorld}")
            return false
        }

        return try {
            Deserializer.worldFromJson(Config.pathWorld)

            // dynmap copy
            if (Nodes.dynmap == true || Config.dynmapCopyTowns) {
                TaskCopyToDynmap(
                    Config.pathWorld,
                    Nodes.DYNMAP_DIR,
                    Nodes.DYNMAP_PATH_WORLD,
                ).run()
            }

            // =========================
            // TOWNS
            // =========================
            if (Files.exists(Config.pathTowns)) {
                Deserializer.townsFromJson(Config.pathTowns)

                Nodes.residents.values.forEach { it.getSaveState() }
                Nodes.towns.values.forEach { it.getSaveState() }
                Nodes.nations.values.forEach { it.getSaveState() }

                if (Nodes.dynmap) {
                    Files.copy(
                        Config.pathTowns,
                        Nodes.DYNMAP_PATH_TOWNS,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }

                Nodes.war.load()

                val migrated = WarzoneManager.reconcileLegacyParticipantStates()
                if (migrated > 0) {
                    Nodes.logger?.info(
                        "Reconciled legacy warzone participant data for $migrated warzone(s)",
                    )
                }
            } else {
                System.err.println("No towns found: ${Config.pathTowns}")
                return true
            }

            // =========================
            // PORTS
            // =========================
            if (Files.exists(Config.pathPorts)) {
                Deserializer.portsFromJson(Config.pathPorts)

                Nodes.ports.values.forEach { it.getSaveState() }
                Nodes.portGroups.values.forEach { it.getSaveState() }

                if (Nodes.dynmap) {
                    Files.copy(
                        Config.pathPorts,
                        Nodes.DYNMAP_PATH_PORTS,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } else {
                System.err.println("No ports found: ${Config.pathPorts}")
                return true
            }

            // warzones dynmap sync
            if (Nodes.dynmap || Config.dynmapCopyTowns) {
                TaskCopyToDynmap(
                    Config.pathWarzones,
                    Nodes.DYNMAP_DIR,
                    Nodes.DYNMAP_PATH_WARZONES,
                ).run()
            }

            Bukkit.getPluginManager().callEvent(NodesWorldLoadedEvent())

            true
        } catch (e: Exception) {
            Nodes.logger?.severe("World load failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Load territories from an already-streamed list of TerritoryPreprocessing objects.
     * This is the streaming counterpart to loadTerritories(JsonObject) — call this
     * from Deserializer.worldFromJson() after token-by-token parsing of the
     * "territories" section, avoiding loading the entire JsonObject tree into memory.
     *
     * Only supports full reload (ids == null equivalent). Partial reloads by id
     * still go through loadTerritories(JsonObject, List<TerritoryId>).
     */
    internal fun loadTerritoriesFromList(territoryPreprocessing: List<TerritoryPreprocessing>) {
        val terrResourceGraph: HashMap<TerritoryId, TerritoryResources> = HashMap(0)

        // build resource graph for all territories
        for (terr in territoryPreprocessing) {
            val baseResources = terr.resourceNodes
                .map { name -> Nodes.resourceNodes[name] ?: throw Exception("Resource node '$name' does not exist (for territory id=${terr.id})") }
            val nodeTypeResources = terr.nodeTypes.mapNotNull { type -> Config.nodeTypes[type] }
            val resources = (baseResources + nodeTypeResources).sortedBy { r -> r.priority }
            terrResourceGraph[terr.id] = resources.fold(Config.globalResources.copy(), { tr, r -> r.apply(tr) })
        }

        // apply neighboring territory modifier properties
        for (terr in territoryPreprocessing) {
            val terrResources = terrResourceGraph[terr.id]
            if (terrResources != null) {
                var terrAfterNeighborModifiers: TerritoryResources = terrResources
                for (neighborId in terr.neighbors) {
                    terrResourceGraph[neighborId]?.let { neighborResources ->
                        if (neighborResources.hasNeighborModifier) {
                            terrAfterNeighborModifiers = terrAfterNeighborModifiers
                                .accumulateNeighborModifiers(neighborResources)
                                ?: terrAfterNeighborModifiers
                        }
                    }
                }
                terrResourceGraph[terr.id] = terrAfterNeighborModifiers // always TerritoryResources
            } else {
                Nodes.logger?.warning("`loadTerritoriesFromList()` Invalid territory id: ${terr.id}")
            }
        }

        // merge structural properties and resources into final territories
        for (t in territoryPreprocessing) {
            if (!t.chunks.contains(t.core)) {
                Nodes.logger?.warning("[Nodes] Territory ${t.id} chunk does not contain core, skipping")
                continue // was: return
            }

            val resources = terrResourceGraph[t.id]!!.applyNeighborModifiers()

            val resourceNamesSorted = t.resourceNodes.sortedBy { name -> Nodes.resourceNodes[name]!!.priority }
            val nodeTypesSorted = t.nodeTypes
                .filter { Config.nodeTypes.containsKey(it) }
                .sortedBy { name -> Config.nodeTypes[name]?.priority ?: 0 }

            val ores = OreSampler(ArrayList(resources.ores.values))
            val cost = Nodes.calculateTerritoryCost(t.chunks.size, resourceNamesSorted)

            val territory = Territory(
                id = t.id,
                name = t.name,
                color = t.color,
                core = t.core,
                chunks = t.chunks,
                bordersWilderness = t.bordersWilderness,
                neighbors = t.neighbors,
                resourceNodes = resourceNamesSorted,
                nodeTypes = nodeTypesSorted,
                cost = cost,
                income = resources.income,
                incomeSpawnEgg = resources.incomeSpawnEgg,
                ores = ores,
                crops = resources.crops,
                animals = resources.animals,
                customProperties = resources.customProperties,
                attackerTimeMultiplier = resources.attackerTimeMultiplier,
                defenderTimeMultiplier = resources.defenderTimeMultiplier,
            )

            Nodes.territories[t.id]?.let { oldTerritory ->
                oldTerritory.chunks.forEach { c -> Nodes.territoryChunks.remove(c) }
                territory.town = oldTerritory.town
                territory.occupier = oldTerritory.occupier
            }

            Nodes.territories.put(t.id, territory)
            t.chunks.forEach { c -> Nodes.territoryChunks.put(c, TerritoryChunk(c, territory)) }
        }

        Bukkit.getPluginManager().callEvent(NodesTerritoriesLoadedEvent())
    }

    /**
     * Save world to JSON storage.
     */
    internal fun saveWorld(
        checkIfNeedsSave: Boolean = true, // set to false to force save
        async: Boolean = false, // run serialization and file write asynchronously
    ) {
        // if we reached backup time interval, generate a backup millis
        // timestamp for save task, which will be used to create a
        // timestamped backup file
        val currTime = System.currentTimeMillis()
        val backup = currTime > Nodes.lastBackupTime + Config.backupPeriod
        val backupTimestamp = if (backup) {
            Nodes.lastBackupTime = currTime
            currTime
        } else {
            -1
        }

        val saveVersion = Nodes.nextSaveRequestVersion()

        // world pre-processing
        Nodes.saveWorldPreprocess()

        val timeUpdate = measureNanoTime {
            // create a snapshot of world objects state
            // always do synchronously on main thread to keep world consistent
            val residentsSnapshot = Nodes.residents.values.map { it.getSaveState() }
            val townsSnapshot = Nodes.towns.values.map { it.getSaveState() }
            val townRecords: List<TownRecord> = townsSnapshot
            val nationsSnapshot = Nodes.nations.values.map { it.getSaveState() }

            // whether should copy towns.json to dynmap folder
            val copyToDynmap = Nodes.dynmap || Config.dynmapCopyTowns

            // save task manages:
            // 1. serialize individual objects into json strings and combine into a full json string
            // 2. write file
            // 3. copy to dynmap folder when save done
            // 4. save backup if reached backup interval
            val taskSave = TaskSaveWorld(
                residentsSnapshot,
                townRecords,
                nationsSnapshot,
                saveVersion,
                Config.pathTowns,
                DYNMAP_DIR,
                DYNMAP_PATH_TOWNS,
                Config.pathBackup,
                Config.pathLastBackupTime,
                copyToDynmap,
                backupTimestamp,
            )

            if (async) {
                Bukkit.getAsyncScheduler().runNow(Nodes.plugin!!, { _ -> taskSave.run() })
            } else {
                taskSave.run()
            }

            Nodes.needsSave = false
        }

        Nodes.logger?.info("[Nodes] Saving world: ${timeUpdate}ns")

        // save ports
        // create a snapshot of port objects state
        val portGroupsSnapshot = Nodes.portGroups.values.map { it.getSaveState() }
        val portsSnapshot = Nodes.ports.values.map { it.getSaveState() }

        // whether should copy ports.json to dynmap folder
        val copyToDynmap = Nodes.dynmap || Config.dynmapCopyTowns

        val taskSavePorts = TaskSavePorts(
            portsSnapshot,
            portGroupsSnapshot,
            Config.pathPorts,
            DYNMAP_DIR,
            DYNMAP_PATH_PORTS,
            copyToDynmap,
        )

        if (async) {
            Bukkit.getAsyncScheduler().runNow(Nodes.plugin!!, { _ -> taskSavePorts.run() })
        } else {
            taskSavePorts.run()
        }
    }

    /**
     * Handle any pre-processing or finishing town/nation modifications before
     * saving to json.
     */
    internal fun saveWorldPreprocess() {
        // move all town income items from inventory gui
        // back to storage data structure
        for (town in Nodes.towns.values) {
            val result = town.income.pushToStorage(false)
            if (result == true) { // has moved items
                town.needsUpdate()
            }
        }
    }

    /**
     * Save config and world state to dynmap folder.
     * Should run when plugin enabled.
     */
    internal fun saveWorldToDynmap(async: Boolean) {
        // copy towns to dynmap folder
        if (Nodes.dynmap || Config.dynmapCopyTowns) {
            val taskSaveClaimsConfig = TaskSaveDynmapClaimsConfig(
                Config.territoryCostBase,
                Config.territoryCostScale,
                DYNMAP_PATH_NODES_CONFIG,
            )
            val taskSaveTowns = TaskCopyToDynmap(
                Config.pathTowns,
                Nodes.DYNMAP_DIR,
                Nodes.DYNMAP_PATH_TOWNS,
            )
            val taskSavePorts = TaskCopyToDynmap(
                Config.pathPorts,
                Nodes.DYNMAP_DIR,
                Nodes.DYNMAP_PATH_PORTS,
            )
            val taskSaveWarzones = TaskCopyToDynmap(
                Config.pathWarzones,
                Nodes.DYNMAP_DIR,
                Nodes.DYNMAP_PATH_WARZONES,
            )

            if (async) {
                Bukkit.getAsyncScheduler().runNow(Nodes.plugin!!, { _ -> taskSaveClaimsConfig.run() })
                Bukkit.getAsyncScheduler().runNow(Nodes.plugin!!, { _ -> taskSaveTowns.run() })
                Bukkit.getAsyncScheduler().runNow(Nodes.plugin!!, { _ -> taskSavePorts.run() })
                Bukkit.getAsyncScheduler().runNow(Nodes.plugin!!, { _ -> taskSaveWarzones.run() })
            } else {
                taskSaveClaimsConfig.run()
                taskSaveTowns.run()
                taskSavePorts.run()
                taskSaveWarzones.run()
            }
        }
    }

    // initialization function,
    // loads diplomatic relations (allies, enemies, truce)
    // requires all towns, nations already be created
    internal fun loadDiplomacy(
        towns: ArrayList<Town>,
        townAllies: ArrayList<ArrayList<String>>,
        townEnemies: ArrayList<ArrayList<String>>,
        nations: ArrayList<Nation>,
        nationAllies: ArrayList<ArrayList<String>>,
        nationEnemies: ArrayList<ArrayList<String>>,
    ) {
        // perform fixes on diplomacy during loading:
        // 1. if towns are in nation, ignore pairs between towns
        //    in nation. instead add nations to nationAlliancePairs
        // 2. if either town NOT in nation, add to townAlliancePairs

        val townAlliancePairs: HashSet<TownPair> = hashSetOf()
        val nationAlliancePairs: HashSet<NationPair> = hashSetOf()

        val townEnemyPairs: HashSet<TownPair> = hashSetOf()
        val nationEnemyPairs: HashSet<NationPair> = hashSetOf()

        for ((i, town) in towns.withIndex()) {
            val allies = townAllies[i]
            val enemies = townEnemies[i]

            val townNation = town.nation

            for (name in allies) {
                val other = Nodes.towns.get(name)
                if (other !== null) {
                    val otherNation = other.nation

                    // only add individual town pair if either does not have a nation
                    if (townNation === null || otherNation === null) {
                        townAlliancePairs.add(TownPair(town, other))
                    }
                    // add nation pairs
                    else if (town === townNation.capital && other === otherNation.capital) {
                        nationAlliancePairs.add(NationPair(townNation, otherNation))
                    }
                }
            }

            for (name in enemies) {
                val other = Nodes.towns.get(name)
                if (other !== null) {
                    val otherNation = other.nation

                    // only add individual town pair if either does not have a nation
                    if (townNation === null || otherNation === null) {
                        townEnemyPairs.add(TownPair(town, other))
                    }
                    // add nation pairs
                    else if (town === townNation.capital && other === otherNation.capital) {
                        nationEnemyPairs.add(NationPair(townNation, otherNation))
                    }
                }
            }
        }

        // apply ally pairs
        for (townPair in townAlliancePairs) {
            val town1 = townPair.town1
            val town2 = townPair.town2

            town1.allies.add(town2)
            town2.allies.add(town1)
        }

        for (nationPair in nationAlliancePairs) {
            val nation1 = nationPair.nation1
            val nation2 = nationPair.nation2

            nation1.allies.add(nation2)
            nation2.allies.add(nation1)

            for (town1 in nation1.towns) {
                for (town2 in nation2.towns) {
                    town1.allies.add(town2)
                    town2.allies.add(town1)
                }
            }
        }

        // apply enemy pairs
        for (townPair in townEnemyPairs) {
            val town1 = townPair.town1
            val town2 = townPair.town2

            town1.enemies.add(town2)
            town2.enemies.add(town1)
        }

        for (nationPair in nationEnemyPairs) {
            val nation1 = nationPair.nation1
            val nation2 = nationPair.nation2

            nation1.enemies.add(nation2)
            nation2.enemies.add(nation1)

            for (town1 in nation1.towns) {
                for (town2 in nation2.towns) {
                    town1.enemies.add(town2)
                    town2.enemies.add(town1)
                }
            }
        }

        // apply alliances between towns in nations
        // for ( nation in Nodes.nations.values ) {
        //     for ( town1 in nation.towns ) {
        //         for ( town2 in nation.towns ) {
        //             if ( town1 !== town2 ) {

        //             }
        //         }
        //     }
        // }

        // load truce from truce config file
        if (Files.exists(Config.pathTruce)) {
            try {
                val truceString = String(Files.readAllBytes(Config.pathTruce))
                Truce.fromJsonString(truceString)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    // ==============================================
    // Resource Node functions
    // ==============================================

    // return number of resource node types
    public fun getResourceNodeCount(): Int = Nodes.resourceNodes.size

    // return number of mining tower submissions
    public fun getMiningTowerCount(): Int = MiningTowerManager.getAllSubmissions().size

    // return number of plots
    public fun getPlotCount(): Int = PlotManager.allPlots().size

    public fun logLoadSummary(logger: Logger) {
        logger.info("=========================================")
        logger.info("- Nodes Version: ${Nodes.version}")
        logger.info("")
        logger.info("World Load Summary:")
        logger.info("- Mining Towers: ${getMiningTowerCount()}")
        logger.info("- Plots: ${getPlotCount()}")
        logger.info("- Resource Nodes: ${getResourceNodeCount()}")
        logger.info("- Territories: ${getTerritoryCount()}")
        logger.info("- Residents: ${getResidentCount()}")
        logger.info("- Towns: ${getTownCount()}")
        logger.info("- Nations: ${getNationCount()}")
        logger.info("")
        logger.info("Misc Summary:")
        logger.info("- Port Applications: ${getPortApplicationCount()}")
        logger.info("- Outpost Applications: ${getOutpostApplicationCount()}")
        logger.info("")
        logger.info("Ports and Outposts Summary:")
        logger.info("- Ports: ${getPortCount()}")
        logger.info("- Port Groups: ${getPortGroupCount()}")
        logger.info("- Outposts: ${getOutpostCount()}")
        logger.info("")
        logger.info("War Summary:")
        logger.info("- Warzones: ${getWarzoneCount()}")
        logger.info("- War whitelist: ${Nodes.war.whitelist.size}")
        logger.info("${Nodes.war.whitelist.list}")
        logger.info("- War blacklist: ${Nodes.war.blacklist.size}")
        logger.info("${Nodes.war.blacklist.list}")
        logger.info("- Annex blacklist: ${Nodes.war.annexBlacklist.size}")
        logger.info("${Nodes.war.annexBlacklist.list}")
        logger.info("")
        logger.info("Hooks Summary:")
        logger.info("- Dynmap: ${Nodes.dynmap}")
        logger.info("- PlaceholderAPI: ${Nodes.placeholderAPI}")
        logger.info("- Super Vanish: ${Nodes.superVanish}")
        logger.info("- Tab: ${Nodes.tab}")
        logger.info("- LuckPerms: ${Nodes.luckPerms}")
        logger.info("- PremiumVanish: ${Nodes.premiumVanish}")
        logger.info("- Kotlin: ${KotlinVersion.CURRENT}")
        logger.info("- SiegeChatFilter: ${Nodes.SiegeChatFilter}")
        logger.info("=========================================")
    }

    public fun getWarzoneCount(): Int = WarzoneManager.getWarzones().size

    // ==============================================
    // Territory Chunk functions
    // ==============================================
    public fun getTerritoryChunkFromBlock(blockX: Int, blockZ: Int): TerritoryChunk? {
        val coord = Coord.fromBlockCoords(blockX, blockZ)
        return Nodes.territoryChunks.get(coord)
    }

    public fun getTerritoryChunkFromCoord(coord: Coord): TerritoryChunk? = Nodes.territoryChunks.get(coord)

    // ==============================================
    // Territory functions
    // ==============================================

    // calculate territory cost from:
    // 1. territory size in chunks
    // 2. list of resource names
    public fun calculateTerritoryCost(size: Int, resourceNodes: List<String>): Int {
        if (!Config.territoryCostEnabled) {
            return 0
        }
        // initialize with global cost rates
        var costConstant = Config.territoryCostBase
        var costScale = Config.territoryCostScale

        for (type in resourceNodes) {
            val resource = Nodes.resourceNodes.get(type)
            if (resource != null) {
                costConstant += resource.costConstant
                costScale *= resource.costScale
            }
        }

        val cost = costConstant + Math.round(costScale * size.toDouble()).toInt()

        return cost
    }

    // return number of territories in world
    public fun getTerritoryCount(): Int = Nodes.territories.size

    public fun getTerritoryFromId(id: TerritoryId): Territory? = Nodes.territories.get(id)

    public fun getTerritoryFromBlock(blockX: Int, blockZ: Int): Territory? {
        val coord = Coord.fromBlockCoords(blockX, blockZ)
        return Nodes.territoryChunks.get(coord)?.territory
    }

    public fun getTerritoryFromPlayer(player: Player): Territory? {
        val loc = player.getLocation()
        val coord = Coord.fromBlockCoords(loc.x.toInt(), loc.z.toInt())
        return Nodes.territoryChunks.get(coord)?.territory
    }

    public fun getTerritoryFromCoord(coord: Coord): Territory? = Nodes.territoryChunks.get(coord)?.territory

    public fun getTerritoryFromChunk(chunk: Chunk): Territory? {
        val coord = Coord(chunk.x, chunk.z)
        return Nodes.territoryChunks.get(coord)?.territory
    }

    public fun getTerritoryFromChunkCoords(cx: Int, cz: Int): Territory? {
        val coord = Coord(cx, cz)
        return Nodes.territoryChunks.get(coord)?.territory
    }

    /**
     * Returns an iterable of all (terrId, territory) pairs in world.
     */
    public fun iterTerritories(): Iterable<kotlin.collections.Map.Entry<TerritoryId, Territory>> = Nodes.territories.asIterable()

    public fun getChunkFromCoord(coord: Coord, world: World): Chunk? = Bukkit.getWorld(world.name)?.getChunkAt(coord.x, coord.z)

    // default spawn location: returns region ~center of
    // core chunk of home territory
    // y-level is first empty air block
    public fun getDefaultSpawnLocation(territory: Territory): Location {
        // get from ~middle of territory home chunk
        val homeChunk = territory.core
        val x = homeChunk.x * 16 + 8
        val z = homeChunk.z * 16 + 8

        // iterate up in y to find first empty block
        val world = Bukkit.getWorlds().get(0)
        var y = 255
        while (y > 0) {
            if (!world.getBlockAt(x, y, z).isEmpty()) {
                break
            }
            y -= 1
        }

        return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    // ==============================================
    // Resident functions
    // ==============================================
    public fun createResident(player: Player) {
        val uuid = player.getUniqueId()
        if (!residents.containsKey(uuid)) {
            val resident = Resident(uuid, player.name)
            resident.language = PlayerLanguage.EN
            residents.put(uuid, resident)

            Nodes.needsSave = true
        }
    }

    /**
     * Resolve existing resident by name, or create one for names that never joined.
     * Uses Bukkit offline profile UUID so the resident can be referenced immediately.
     */
    public fun getOrCreateResidentByName(name: String): Resident {
        val existing = getResidentFromName(name)
        if (existing != null) {
            return existing
        }

        val onlinePlayer = Bukkit.getPlayer(name)
        if (onlinePlayer != null) {
            val resident = Resident(onlinePlayer.uniqueId, onlinePlayer.name)
            resident.language = PlayerLanguage.EN
            residents[onlinePlayer.uniqueId] = resident
            Nodes.needsSave = true
            return resident
        }

        val offline = Bukkit.getOfflinePlayer(name)
        val uuid = offline.uniqueId ?: UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray())
        val residentName = offline.name ?: name
        val resident = Resident(uuid, residentName)
        resident.language = PlayerLanguage.EN
        residents[uuid] = resident
        Nodes.needsSave = true
        return resident
    }

    // loads a resident from UUID and other parameters
    // used for deserializing from towns.json
    public fun loadResident(
        uuid: UUID,
        claims: Int,
        claimsTime: Long,
        prefix: String,
        suffix: String,
        trusted: Boolean,
        townCreateCooldown: Long,
        language: PlayerLanguage?,
        minimapOrientation: MinimapOrientation,
        chatMode: ChatMode,
        attackMessageMode: AttackMessageMode,
        territoryCaptureNotificationMode: TerritoryCaptureNotificationMode,
        pointDisabled: Boolean,
        oreBlocksBroken: Long,
        townRejoinCooldown: Long = 0L,
    ) {
        // get player name from UUID
        // use online player if exists, else get from OfflinePlayer
        var playerName: String? = Bukkit.getPlayer(uuid)?.let { player ->
            player.name
        } ?: run {
            Bukkit.getOfflinePlayer(uuid)?.getName()
        }

        // if player name null, player does not exist
        if (playerName == null) {
            return
        }

        // create and add resident
        val resident = Resident(uuid, playerName)
        resident.prefix = prefix
        resident.suffix = suffix
        resident.claims = Math.min(claims, Config.playerClaimsMax) // in case config changed
        resident.claimsTime = claimsTime

        // resident trusted status
        resident.trusted = trusted
        resident.language = language
        resident.minimapOrientation = minimapOrientation
        resident.chatMode = chatMode
        resident.attackMessageMode = attackMessageMode
        resident.territoryCaptureNotificationMode = territoryCaptureNotificationMode
        resident.isTownPointDisabled = pointDisabled
        resident.oreBlocksBroken = oreBlocksBroken

        // mark resident needs update
        resident.needsUpdate()

        // set resident town create cooldown
        resident.townCreateCooldown = townCreateCooldown
        resident.townRejoinCooldown = townRejoinCooldown

        residents.put(uuid, resident)
    }

    public fun getResidentCount(): Int = Nodes.residents.size

    public fun getResident(player: Player): Resident? = Nodes.residents.get(player.getUniqueId())

    public fun getResidentFromName(name: String): Resident? {
        // get player from bukkit
        val player = Bukkit.getPlayer(name)
        if (player != null) {
            Nodes.residents[player.uniqueId]?.let { resident ->
                return resident
            }
        }

        // search through residents and try to match name
        val playerNameLowercase = name.lowercase()
        for (r in Nodes.residents.values) {
            if (r.name.lowercase() == playerNameLowercase) {
                return r
            }
        }

        if (player != null) {
            val resident = Resident(player.uniqueId, player.name)
            resident.language = PlayerLanguage.EN
            Nodes.residents[player.uniqueId] = resident
            Nodes.needsSave = true
            return resident
        }

        return null
    }

    public fun getResidentFromUUID(uuid: UUID): Resident? = Nodes.residents.get(uuid)

    public fun setResidentPrefix(resident: Resident, s: String) {
        resident.prefix = sanitizeString(s)
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setResidentSuffix(resident: Resident, s: String) {
        resident.suffix = sanitizeString(s)
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    // marks player as online
    public fun setResidentOnline(resident: Resident, player: Player) {
        val town = resident.town
        if (town != null) {
            town.playersOnline.add(player)

            val nation = town.nation
            if (nation != null) {
                nation.playersOnline.add(player)
            }
        }
    }

    // marks player as offline
    // force player as input in case resident cannot access player
    // if player already offline
    public fun setResidentOffline(resident: Resident, player: Player) {
        val town = resident.town
        if (town != null) {
            town.playersOnline.remove(player)

            val nation = town.nation
            if (nation != null) {
                nation.playersOnline.remove(player)
            }
        }
    }

    // toggle chat mode:
    // if already in mode, return to default (global)
    // else, set to new mode
    // return chatmode this is set to
    public fun toggleChatMode(resident: Resident, mode: ChatMode): ChatMode {
        if (resident.chatMode == mode) {
            resident.chatMode = ChatMode.GLOBAL
        } else {
            resident.chatMode = mode
        }

        resident.needsUpdate()
        Nodes.needsSave = true

        return resident.chatMode
    }

    // set player's town create cooldown
    public fun setResidentTownCreateCooldown(resident: Resident, cooldown: Long) {
        resident.townCreateCooldown = cooldown
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    // set resident's current claims time progress
    public fun setResidentClaimTimer(resident: Resident, time: Long) {
        resident.claimsTime = time
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    // update players online in each town, nation
    public fun refreshPlayersOnline() {
        // remove players online in nations
        for (nation in Nodes.nations.values) {
            nation.playersOnline.clear()
        }

        for (town in Nodes.towns.values) {
            town.playersOnline.clear()
            for (r in town.residents) {
                val player = r.player()
                if (player !== null) {
                    town.playersOnline.add(player)

                    val nation = r.nation
                    if (nation !== null) {
                        nation.playersOnline.add(player)
                    }
                }
            }
        }
    }

    // forces re-render of online player minimaps
    public fun renderMinimaps() {
        for (player in Bukkit.getOnlinePlayers()) {
            val resident = Nodes.getResident(player)
            if (resident?.minimap != null) {
                // get current location coord
                val loc = player.getLocation()
                val coordX = kotlin.math.floor(loc.x).toInt()
                val coordZ = kotlin.math.floor(loc.z).toInt()
                val coord = Coord.fromBlockCoords(coordX, coordZ)
                resident.updateMinimap(coord)
            }
        }
    }

    // ==============================================
    // Town functions
    // ==============================================

    // create town at player location:
    // - player becomes town leader
    // - territory at player's location becomes town home
    public fun createTown(name: String, territory: Territory, leader: Resident?): Result<Town> {
        // get resident and spawn coordinate from leader if exists
        val leaderPlayer = leader?.player()
        var spawnpoint = if (leaderPlayer != null) {
            leaderPlayer.location
        } else {
            Nodes.getDefaultSpawnLocation(territory)
        }

        if (Nodes.getTownFromName(name) != null) {
            return Result.failure(ErrorTownExists)
        }

        if (territory.town != null) {
            return Result.failure(ErrorTerritoryOwned)
        }

        if (leader?.town != null) {
            return Result.failure(ErrorPlayerHasTown)
        }

        val town = Town(UUID.randomUUID(), name, territory.id, leader, spawnpoint)
        town.claimsUsed = if (Config.claimsPowerEnabled) territory.cost else 0

        // set home territory town
        territory.town = town

        addCoreNodeTypeToWorldJson(territory.id, territory)

        if (leader != null) {
            leader.town = town

            if (Config.claimsPowerEnabled) {
                // check how much player is over town claim limit
                val overClaimsPenalty: Int = Math.max(0, Config.initialOverClaimsAmountScale * (territory.cost - Config.townInitialClaims))

                // give initial leader their max claims
                leader.claims = Math.max(0, Math.max(Config.playerClaimsMax, Config.townInitialClaims) - overClaimsPenalty)
                leader.claimsTime = 0L
            }

            leader.needsUpdate()
        }

        town.claimsMax = Nodes.calculateMaxClaims(town)

        Nodes.towns.put(name, town)
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — new town created, team snapshot needs rebuild,
        // everyone needs burst to see the new town's color
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateAllCaches()
        Nametag.markBurstAll()
        Nametag.flushBurst()

        return Result.success(town)
    }

    private fun addCoreNodeTypeToWorldJson(territoryId: TerritoryId, territory: Territory) {
        val coreType = "core"
        if (!Config.nodeTypes.containsKey(coreType)) {
            return
        }
        if (!Files.exists(Config.pathWorld)) {
            return
        }

        // already has core node type, nothing to do
        if (territory.nodeTypes.contains(coreType)) {
            return
        }

        val updatedNodeTypes = (territory.nodeTypes + coreType)
            .filter { Config.nodeTypes.containsKey(it) }
            .sortedBy { name -> Config.nodeTypes[name]?.priority ?: 0 }

        val updatedTerritory = territory.copy(nodeTypes = updatedNodeTypes)
        Nodes.territories[territoryId] = updatedTerritory

        updatedTerritory.chunks.forEach { c ->
            Nodes.territoryChunks[c] = TerritoryChunk(c, updatedTerritory)
        }

        // update world.json on disk async so we don't block the main thread
        val plugin = Nodes.plugin ?: return
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                val gson = GsonBuilder().disableHtmlEscaping().create()
                val root = JsonParser.parseReader(Files.newBufferedReader(Config.pathWorld)).asJsonObject
                val territories = root.getAsJsonObject("territories") ?: return@runNow
                val terr = territories.getAsJsonObject(territoryId.toInt().toString()) ?: return@runNow

                val current = terr.getAsJsonArray("node_types")?.mapNotNull { it.asString }?.toMutableList() ?: mutableListOf()
                if (!current.contains(coreType)) {
                    current.add(coreType)
                    val newArray = JsonArray()
                    current.forEach { newArray.add(it) }
                    terr.add("node_types", newArray)
                    terr.remove("node_type")
                    Files.writeString(Config.pathWorld, gson.toJson(root))
                }

                // dynmap sync
                if (Nodes.dynmap == true || Config.dynmapCopyTowns) {
                    TaskCopyToDynmap(Config.pathWorld, Nodes.DYNMAP_DIR, Nodes.DYNMAP_PATH_WORLD).run()
                }
            } catch (e: Exception) {
                Bukkit.getLogger().warning("[Nodes] Failed to update world.json for territory $territoryId: ${e.message}")
            }
        }
    }

    // load town from data
    // used for deserializing from towns.json
    public fun loadTown(
        uuid: UUID,
        name: String,
        leader: UUID?,
        homeId: Int,
        spawn: Location?,
        color: Color?,
        residents: ArrayList<UUID>,
        generals: ArrayList<UUID>,
        officers: ArrayList<UUID>,
        territoryIds: ArrayList<Int>,
        capturedTerritoryIds: ArrayList<Int>,
        annexedTerritoryIds: ArrayList<Int>,
        claimsBonus: Int,
        claimsAnnexed: Int,
        claimsPenalty: Int,
        claimsPenaltyTime: Long,
        income: EnumMap<Material, Int>,
        incomeSpawnEgg: EnumMap<EntityType, Int>,
        incomeItems: MutableList<ItemStack>,
        permissions: EnumMap<TownPermissions, EnumSet<PermissionsGroup>>,
        isOpen: Boolean,
        protectedBlocks: HashSet<Block>,
        moveHomeCooldown: Long,
        outpostCredits: Int,
        townPoints: Long,
        outposts: HashMap<String, Triple<Int, Location, PortTier>>,
    ): Town? {
        val leaderAsResident = if (leader != null) {
            Nodes.getResidentFromUUID(leader)
        } else {
            null
        }

        // make sure home territory exists
        val home = Nodes.getTerritoryFromId(TerritoryId(homeId))
        if (home == null) {
            System.err.println("Failed to create town $name with home (id = $homeId)")
            return null
        }

        // get spawn point
        val spawnpoint = if (spawn != null) {
            spawn
        } else { // get default value
            val homeTerritory = Nodes.territories.get(TerritoryId(homeId))
            if (homeTerritory != null) {
                Nodes.getDefaultSpawnLocation(homeTerritory)
            } else {
                Location(Bukkit.getWorlds().get(0), 0.0, 255.0, 0.0)
            }
        }

        val town = Town(uuid, name, home.id, leaderAsResident, spawnpoint)
        leaderAsResident?.town = town

        // add residents
        for (id in residents) {
            Nodes.getResidentFromUUID(id)?.let { r ->
                town.residents.add(r)
                r.town = town
                r.needsUpdate()
            }
        }

        // add officers
        for (id in officers) {
            Nodes.getResidentFromUUID(id)?.let { r ->
                town.officers.add(r)
            }
        }

        // add generals (also officers by design)
        for (id in generals) {
            Nodes.getResidentFromUUID(id)?.let { r ->
                town.generals.add(r)
                town.officers.add(r)
            }
        }

        // add territory claims and claims power used
        for (id in territoryIds) {
            val terrId = TerritoryId(id)
            Nodes.getTerritoryFromId(terrId)?.let { terr ->
                town.territories.add(terrId)
                terr.town = town
                if (Config.claimsPowerEnabled) {
                    town.claimsUsed += terr.cost
                }
            }
        }

        // add annexed territories
        // (duplicated in territoryIds, so just add ids)
        for (id in annexedTerritoryIds) {
            val terrId = TerritoryId(id)
            Nodes.getTerritoryFromId(terrId)?.let { terr ->
                if (town.territories.contains(terrId)) {
                    town.annexed.add(terrId)
                    if (Config.claimsPowerEnabled) {
                        town.claimsUsed -= terr.cost
                    }
                }
            }
        }

        // add captured territories
        for (id in capturedTerritoryIds) {
            val terrId = TerritoryId(id)
            Nodes.getTerritoryFromId(terrId)?.let { terr ->
                // check if territory already occupied, remove current occupier
                // should never occur, but just in case
                val currentOccupier: Town? = terr.occupier
                if (currentOccupier != null) {
                    currentOccupier.captured.remove(terrId)
                }

                // capture territory for town
                town.captured.add(terrId)
                terr.occupier = town
            }
        }

        // add saved income
        town.income.storage.putAll(income)
        town.income.storageSpawnEgg.putAll(incomeSpawnEgg)
        town.income.storageCustom.addAll(incomeItems.map { it.clone() })

        // set town color
        if (color != null) {
            town.color = color
        }

        // add permission flags
        for ((type, groups) in permissions) {
            town.permissions[type].clear()
            town.permissions[type].addAll(groups)
        }

        // calculate max claims
        town.claimsBonus = claimsBonus
        town.claimsAnnexed = claimsAnnexed
        town.claimsPenalty = claimsPenalty
        town.claimsPenaltyTime = claimsPenaltyTime
        town.claimsMax = Nodes.calculateMaxClaims(town)

        // set isOpen
        town.isOpen = isOpen

        // add protected blocks
        town.protectedBlocks.addAll(protectedBlocks)

        // set move home cooldown
        town.moveHomeCooldown = moveHomeCooldown
        town.outpostCredits = outpostCredits
        town.townPoints = townPoints

        // load outposts
        for ((name, outpostData) in outposts) {
            val terrId = TerritoryId(outpostData.first)
            val spawn = outpostData.second
            val tier = outpostData.third
            val terr = Nodes.getTerritoryFromId(terrId)
            if (terr !== null && town.territories.contains(terrId)) {
                town.outposts.put(
                    name,
                    TownOutpost(
                        name,
                        terrId,
                        spawn,
                        tier,
                    ),
                )
            }
        }

        // save new town
        towns.put(name, town)

        // mark dirty
        town.needsUpdate()

        return town
    }

    public fun destroyTown(town: Town) {
        // remove town links backwards from creation:

        // check if town last in nation, destroy nation first if last
        val nation = town.nation
        if (nation !== null) {
            if (nation.towns.size == 1) { // last town in nation
                Nodes.destroyNation(nation)
            } else {
                Nodes.removeTownFromNation(nation, town)
            }
        }

        // remove territory claim links
        town.territories.forEach { terrId ->
            Nodes.getTerritoryFromId(terrId)?.town = null
        }

        // remove occupied territories
        town.captured.forEach { terrId ->
            Nodes.getTerritoryFromId(terrId)?.occupier = null
        }

        // remove resident town links
        town.residents.forEach { r ->
            r.town = null
            r.nation = null
            r.needsUpdate()

            // remove resident nametag, and remove from nation players online list
            val player = r.player()
            if (player !== null) {
                if (nation !== null) {
                    nation.playersOnline.remove(player)
                }
            }
        }

        // remove all town alliances
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }

        // remove all town enemies
        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }

        // remove town from global
        Nodes.towns.remove(town.name)

        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — town destroyed
        //
        // IMPORTANT: broadcast the team-remove packet FIRST, before
        // invalidateAllCaches() wipes the per-viewer caches. After the town
        // is pulled from Nodes.towns (line above), updateTextForPlayer()
        // has no way to detect the dead team as "was present, now gone" —
        // its stale-check walks cache.keys, and the cache would be empty
        // by the time it runs. Without this explicit broadcast, clients
        // keep the orphaned team in their scoreboard forever.
        Nametag.broadcastTeamRemove("t${town.townNametagId}")
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateAllCaches()
        Nametag.markBurstAll()
        Nametag.flushBurst()
        evictTeamFromPool("t${town.townNametagId}")
    }

    public fun getTownCount(): Int = Nodes.towns.size

    public fun getTownFromName(name: String): Town? = towns.get(name)

    public fun getTownFromId(uuid: UUID): Town? = towns.values.firstOrNull { it.uuid == uuid }

    public fun getTownFromPlayer(player: Player): Town? {
        // get resident
        val resident = Nodes.getResident(player)
        if (resident !== null) {
            return resident.town
        }

        return null
    }

    /**
     * If input is "*", return all towns. Otherwise, return list of
     * single town.
     */
    public fun matchTowns(name: String): List<Town> {
        val matchedTowns = ArrayList<Town>()

        if (name == "*") {
            matchedTowns.addAll(towns.values)
        } else {
            val town = getTownFromName(name)
            if (town !== null) {
                matchedTowns.add(town)
            }
        }

        return matchedTowns
    }

    /**
     * Return town that owns chunk if it exists
     */
    public fun getTownAtChunkCoord(cx: Int, cz: Int): Town? {
        val terr = Nodes.getTerritoryFromChunkCoords(cx, cz)
        if (terr !== null) {
            return terr.town
        }

        return null
    }

    // set town's bonus claims
    public fun setClaimsBonus(town: Town, num: Int) {
        town.claimsBonus = num
        town.claimsMax = Nodes.calculateMaxClaims(town)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    // set town's claims penalty
    public fun setClaimsPenalty(town: Town, num: Int) {
        town.claimsPenalty = num
        town.claimsMax = Nodes.calculateMaxClaims(town)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    // set town's annexed claims penalty
    public fun setClaimsAnnexed(town: Town, num: Int) {
        town.claimsAnnexed = num
        town.claimsMax = Nodes.calculateMaxClaims(town)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    // calculates and returns town's max claims
    // NOTE: DOES NOT actually set town's max claims
    // called should do:
    // town.claimsMax = Nodes.calculateMaxClaims(town)
    public fun calculateMaxClaims(town: Town): Int {
        if (!Config.claimsPowerEnabled) {
            town.isOverClaimsMax = false
            return 1_000_000_000
        }
        val numResidents = town.residents.size

        var maxClaims = Config.townClaimsBase - town.claimsPenalty
        for (r in town.residents) {
            maxClaims += r.claims
        }

        // apply bonus
        maxClaims += town.claimsBonus

        // penalty from annexed territories
        maxClaims -= town.claimsAnnexed

        // clamp to max
        if (Config.townClaimsMax > 0) {
            maxClaims = Math.min(Config.townClaimsMax, maxClaims)
        }

        // check if town over max claims
        town.isOverClaimsMax = town.claimsUsed > maxClaims

        return maxClaims
    }

    // reduces claim penalty from all towns by 1
    // scheduled by ClaimPenaltyDecayManager
    public fun claimsPenaltyDecay(dt: Long) {
        if (!Config.claimsPowerEnabled) {
            return
        }
        for (town in Nodes.towns.values) {
            if (town.claimsPenalty > 0) {
                // update tick, run update if passed period
                val elapsedTime = town.claimsPenaltyTime + dt
                if (elapsedTime >= Config.townClaimsPenaltyDecayPeriod) {
                    town.claimsPenaltyTime = 0L
                    town.claimsPenalty = Math.max(0, town.claimsPenalty - Config.townPenaltyDecay)
                    town.claimsMax = Nodes.calculateMaxClaims(town)
                    town.needsUpdate()
                    Nodes.needsSave = true
                } else {
                    town.claimsPenaltyTime = elapsedTime
                    town.needsUpdate()
                    Nodes.needsSave = true
                }
            }
        }
    }

    // tick player claim power contributions and
    // re-calculate town max claims
    // -> apply only for online players
    public fun claimsPowerRamp(dt: Long) {
        if (!Config.claimsPowerEnabled) {
            return
        }
        for (player in Bukkit.getOnlinePlayers()) {
            val resident = Nodes.getResident(player)
            val town = resident?.town
            if (town !== null && resident.claims < Config.playerClaimsMax) {
                // update tick, run update if passed period
                val elapsedTime = resident.claimsTime + dt
                if (elapsedTime >= Config.playerClaimsIncreasePeriod) {
                    resident.claimsTime = 0L
                    resident.claims = Math.min(Config.playerClaimsMax, resident.claims + Config.playerClaimsIncrease)
                    town.claimsMax = Nodes.calculateMaxClaims(town)
                    resident.needsUpdate()
                    town.needsUpdate()
                    Nodes.needsSave = true
                } else {
                    resident.claimsTime = elapsedTime
                    resident.needsUpdate()
                    Nodes.needsSave = true
                }
            }
        }
    }

    /**
     * Runs in bukkit task periodically, sends message to town
     * players if their town is over max claims
     */
    public fun overMaxClaimsReminder() {
        if (!Config.claimsPowerEnabled || !Config.overClaimsPenalty) { // skip reminder if over max claims disabled
            return
        }

        val resourcePenalty = "${(Config.overClaimsMaxPenalty * 100).toInt()}% resource penalty"

        for (town in Nodes.towns.values) {
            if (town.isOverClaimsMax) {
                val msg = "${ChatColor.DARK_RED}Your town is over max claims: ${town.claimsUsed}/${town.claimsMax}, you have a $resourcePenalty"
                for (r in town.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, msg)
                    }
                }
            }
        }
    }

    /**
     * Claim territory for a town. Returns result with either Territory
     * if successful, or an TerritoryClaim error status.
     */
    public fun claimTerritory(town: Town, territory: Territory): Result<Territory> {
        // check if territory already claimed
        if (territory.town != null) {
            return Result.failure(ErrorTerritoryHasClaim)
        }

        // check if territory is connected to town's existing territories
        // iterate this territory neighbors, check if any link to town
        var isNeighbor = false
        for (neighborId in territory.neighbors) {
            if (Nodes.getTerritoryFromId(neighborId)?.town === town) {
                isNeighbor = true
                break
            }
        }
        if (!isNeighbor) {
            return Result.failure(ErrorTerritoryNotConnected)
        }

        if (Config.claimsPowerEnabled) {
            val remaining = town.claimsMax - town.claimsUsed
            val wouldExceedBy = territory.cost - remaining
            if (wouldExceedBy > 0) {
                // Would push the town over its claims cap.
                //
                // Strict mode (overClaimsAllowClaim=false, default):
                //   reject unconditionally.
                //
                // Over-claim allowed (overClaimsAllowClaim=true):
                //   allow only if the final excess over claimsMax is within
                //   overClaimsMaxExcess. -1 means "unlimited excess" (old
                //   unbounded behaviour), any non-negative value is a hard cap.
                //
                // This fixes the bug where setting overClaimsAllowClaim=true
                // let a town already far over cap claim territories of any
                // size with no restriction at all.
                if (!Config.overClaimsAllowClaim) {
                    return Result.failure(ErrorTooManyClaims)
                }
                if (Config.overClaimsMaxExcess >= 0) {
                    val excessAfter = (town.claimsUsed + territory.cost) - town.claimsMax
                    if (excessAfter > Config.overClaimsMaxExcess) {
                        return Result.failure(ErrorTooManyClaims)
                    }
                }
            }
        }

        // passed checks, add territory to town
        town.territories.add(territory.id)
        territory.town = town

        if (Config.claimsPowerEnabled) {
            // increase claims power used
            town.claimsUsed += territory.cost
        }

        // mark dirty
        town.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        return Result.success(territory)
    }

    public fun unclaimTerritory(town: Town, territory: Territory): Result<Territory> {
        // check if town owns territory
        if (!town.territories.contains(territory.id)) {
            return Result.failure(ErrorTerritoryNotInTown)
        }

        // check if territory is town's home territory
        if (town.home == territory.id) {
            return Result.failure(ErrorTerritoryIsTownHome)
        }

        // passed checks, remove territory from town
        town.territories.remove(territory.id)
        territory.town = null

        // remove any outposts with this territory
        town.outposts.entries.removeAll({ (name, outpost) -> outpost.territory == territory.id })

        if (Config.claimsPowerEnabled) {
            // if territory was not annexed, remove territory cost
            // from claims used, add to penalty until it decays
            if (!town.annexed.contains(territory.id)) {
                town.claimsUsed -= territory.cost
                town.claimsPenalty += territory.cost
                town.claimsMax = Nodes.calculateMaxClaims(town)
            } else {
                town.annexed.remove(territory.id)
            }
        } else {
            town.annexed.remove(territory.id)
        }

        // mark dirty
        town.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        return Result.success(territory)
    }

    // adds a territory to town and bypasses standard claim checks
    // (e.g. territory must be connected, town has claims available, ...)
    // if successful, returns added territory
    public fun addTerritoryToTown(town: Town, territory: Territory): Result<Territory> {
        // check territory not already occupied
        if (territory.town != null) {
            return Result.failure(ErrorTerritoryOwned)
        }

        // add territory to town
        town.territories.add(territory.id)
        territory.town = town

        if (Config.claimsPowerEnabled) {
            // increase claims power used
            town.claimsUsed += territory.cost
        }

        // mark dirty
        town.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        return Result.success(territory)
    }

    // makes territory occupied by town
    public fun captureTerritory(town: Town, territory: Territory) {
        // check if territory already occupied, remove current occupier
        val currentOccupier: Town? = territory.occupier
        if (currentOccupier != null) {
            currentOccupier.captured.remove(territory.id)
            territory.occupier = null

            currentOccupier.needsUpdate()
        }

        // handle capturing enemy territory
        if (territory.town != town) {
            town.captured.add(territory.id)
            territory.occupier = town
        }

        town.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()
    }

    // release territory from town occupation
    public fun releaseTerritory(territory: Territory) {
        // check if territory currently occupied, remove current occupier
        val currentOccupier: Town? = territory.occupier
        if (currentOccupier != null) {
            currentOccupier.captured.remove(territory.id)
            territory.occupier = null

            currentOccupier.needsUpdate()
            Nodes.needsSave = true

            // re-render minimaps
            Nodes.renderMinimaps()
        }
    }

    /**
     * Town annexes a territory:
     * - add to town's territories and town's annexed territories
     * - does not cost claim power (no need to recalculate max claims)
     * Returns boolean on success
     */
    public fun annexTerritory(town: Town, territory: Territory, announce: Boolean = true): Boolean {
        val occupier: Town? = territory.occupier
        if (occupier !== town) {
            return false
        }

        val oldTown = territory.town
        if (oldTown === town) {
            return false
        }

        // remove from old town
        if (oldTown !== null) {
            // check if this is their home territory
            if (territory.id == oldTown.home) {
                // can only annex home territory last
                if (oldTown.territories.size > 1) {
                    return false
                }

                // destroy town and broadcast
                Nodes.destroyTown(oldTown)

                if (announce) {
                    Message.broadcastWarNotice(
                        "${ChatColor.DARK_RED}${ChatColor.BOLD}Town ${oldTown.name} was completely annexed by ${town.name}",
                        relatedTowns = listOf(town, oldTown),
                        isWarzone = WarzoneManager.findActiveWarzoneForTerritoryId(territory.id) != null,
                        territoryCapture = true,
                    )
                }
            }
            // else, just a normal territory
            else {
                if (oldTown.annexed.contains(territory.id)) {
                    oldTown.annexed.remove(territory.id)
                }
                // if territory was not an annexed territory, adjust claims
                else if (Config.claimsPowerEnabled) {
                    oldTown.claimsUsed -= territory.cost
                    oldTown.claimsAnnexed += territory.cost
                }

                // remove territory and re-calculate claims
                oldTown.territories.remove(territory.id)
                oldTown.claimsMax = Nodes.calculateMaxClaims(town)

                oldTown.needsUpdate()
            }
        }

        // add territory to town and annexed territories
        town.territories.add(territory.id)
        town.annexed.add(territory.id)
        town.captured.remove(territory.id)

        // update territory
        territory.town = town
        territory.occupier = null

        town.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        if (announce) {
            Message.broadcastWarNotice(
                "${ChatColor.DARK_RED}[War] ${town.name} annexed territory (id=${territory.id}) from ${oldTown?.name}!",
                relatedTowns = listOf(town, oldTown),
                isWarzone = WarzoneManager.findActiveWarzoneForTerritoryId(territory.id) != null,
                territoryCapture = true,
            )
        }

        return true
    }

    /**
     * Instantly annex all territories from `defeatedTown` into `conquerorTown`.
     * Returns number of territories successfully annexed.
     */
    public fun annexEntireTown(conquerorTown: Town, defeatedTown: Town): Int {
        if (conquerorTown === defeatedTown) {
            return 0
        }
        val defeatedTerritories = defeatedTown.territories.toList()
        if (defeatedTerritories.isEmpty()) {
            return 0
        }

        // Home must be annexed last, otherwise annexTerritory() rejects it.
        val ordered = ArrayList<TerritoryId>(defeatedTerritories.size)
        for (terrId in defeatedTerritories) {
            if (terrId != defeatedTown.home) {
                ordered.add(terrId)
            }
        }
        if (defeatedTerritories.contains(defeatedTown.home)) {
            ordered.add(defeatedTown.home)
        }

        var annexedCount = 0
        for (terrId in ordered) {
            val territory = Nodes.getTerritoryFromId(terrId) ?: continue
            if (territory.town !== defeatedTown) {
                continue
            }

            Nodes.captureTerritory(conquerorTown, territory)
            if (Nodes.annexTerritory(conquerorTown, territory, announce = false)) {
                annexedCount += 1
            }
        }

        return annexedCount
    }

    // adds items to town's income
    // used by taxation events in occupied/captured territories
    // TODO: cleanup + rename this to "townIncomeAdd"
    public fun addToIncome(town: Town, material: Material, amount: Int, meta: Int = 0) {
        town.income.add(material, amount, meta)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * Removes items from town's income. If Material is null, removes all
     * items. If amount < 0, removes all items of that type.
     */
    public fun townIncomeRemove(
        town: Town,
        material: Material?,
        amount: Int = -1,
    ) {
        town.income.pushToStorage(force = true) // push items to storage before removing

        if (material !== null) {
            if (amount >= 0) {
                val currAmount = town.income.storage.get(material) ?: 0
                if (currAmount > amount) {
                    town.income.storage.put(material, currAmount - amount)
                } else {
                    town.income.storage.remove(material)
                }
            } else { // remove all
                town.income.storage.remove(material)
            }
        } else {
            town.income.storage.clear()
        }
        town.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setTownColor(town: Town, r: Int, g: Int, b: Int) {
        town.color = Color(r, g, b)
        town.needsUpdate()
        Nodes.needsSave = true

        // The Town's pre-built nametag components also encode the colour;
        // rebuild them and invalidate every viewer's cached relation for
        // this team so the new colour reaches clients on the next drain.
        town.updateNametags()
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamInAllCaches("t${town.townNametagId}")
        Nametag.markBurstAll()
    }

    public fun setTownSpawn(town: Town, spawnpoint: Location): Boolean {
        // enforce spawnpoint in town's home territory
        val territory = Nodes.getTerritoryFromChunk(spawnpoint.chunk)
        if (territory === null || territory.id != town.home) {
            return false
        }

        town.spawnpoint = spawnpoint
        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    public fun addResidentToTown(town: Town, resident: Resident) {
        if (!phonon.nodes.core.CoreHooks.canJoin(resident, town)) return
        town.residents.add(resident)
        resident.town = town

        if (Config.claimsPowerEnabled) {
            // update resident max claims
            resident.claims = Config.playerClaimsInitial
            resident.claimsTime = 0L
            town.claimsMax = Nodes.calculateMaxClaims(town)
        }

        // initialize player as untrusted
        resident.trusted = false

        // add player to town online players
        val player = resident.player()
        if (player !== null) {
            town.playersOnline.add(player)
        }

        // add town nation to resident
        val nation = town.nation
        if (nation !== null) {
            resident.nation = nation
            nation.residents.add(resident)

            // add player to nation online players
            if (player !== null) {
                nation.playersOnline.add(player)
            }
        }

        town.needsUpdate()
        resident.needsUpdate()
        Nodes.needsSave = true

        // update nametags — player moved teams so rebuild snapshot first,
        // then send priority build to the affected player and burst to everyone else
        if (player !== null) {
            Nametag.invalidateViewerCache(player) // ← add this
            Nametag.rebuildTeamSnapshot()
            Nametag.invalidateTeamInAllCaches("t${town.townNametagId}")
            Nametag.markPriorityAndFlush(player)
            val others = Bukkit.getOnlinePlayers().filter { it.uniqueId != player.uniqueId }
            Nametag.markBurstAndFlush(others)
        } else {
            Nametag.rebuildTeamSnapshot()
            Nametag.invalidateTeamInAllCaches("t${town.townNametagId}")
        }
    }

    public fun removeResidentFromTown(town: Town, resident: Resident) {
        phonon.nodes.core.CoreHooks.left(town, resident)
        if (town.generals.contains(resident)) {
            town.generals.remove(resident)
        }
        if (town.officers.contains(resident)) {
            town.officers.remove(resident)
        }
        town.residents.remove(resident)
        resident.town = null

        val player = resident.player()

        // remove town nation from resident
        val nation = town.nation
        if (nation !== null) {
            resident.nation = null
            nation.residents.remove(resident)

            // remove player from nation online players
            if (player !== null) {
                nation.playersOnline.remove(player)
            }
        }

        if (Config.claimsPowerEnabled) {
            // update max claims
            town.claimsMax = Nodes.calculateMaxClaims(town)
        }

        // remove player to town online players
        if (player !== null) {
            town.playersOnline.remove(player)
        }

        town.needsUpdate()
        resident.needsUpdate()
        Nodes.needsSave = true

        // apply rejoin cooldown so player cannot immediately hop to another town
        if (Config.townRejoinCooldownEnabled) {
            resident.townRejoinCooldown = Config.townRejoinCooldown
        }

        // update nametags — player left a team so rebuild snapshot,
        // then priority for the affected player and burst for everyone else
        if (player !== null) {
            Nametag.invalidateViewerCache(player)
            Nametag.rebuildTeamSnapshot()
            Nametag.invalidateTeamInAllCaches("t${town.townNametagId}")
            Nametag.markPriorityAndFlush(player)
            val others = Bukkit.getOnlinePlayers().filter { it.uniqueId != player.uniqueId }
            Nametag.markBurstAndFlush(others)
        } else {
            Nametag.rebuildTeamSnapshot()
            Nametag.invalidateTeamInAllCaches("t${town.townNametagId}")
        }
    }

    // make player in town an officer
    public fun townAddOfficer(town: Town, resident: Resident): Boolean {
        if (resident.town !== town) {
            return false
        }

        if (town.officers.contains(resident)) {
            return true
        }

        town.officers.add(resident)

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    // remove player from officer status
    public fun townRemoveOfficer(town: Town, resident: Resident): Boolean {
        if (resident.town !== town) {
            return false
        }

        // generals are above officers and must be demoted first
        if (town.generals.contains(resident)) {
            return false
        }

        town.officers.remove(resident)

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    public fun townAddGeneral(town: Town, resident: Resident): Boolean {
        if (resident.town !== town) {
            return false
        }
        if (town.generals.contains(resident)) {
            return true
        }
        town.generals.add(resident)
        // general inherits all officer permissions
        town.officers.add(resident)
        town.needsUpdate()
        Nodes.needsSave = true
        return true
    }

    public fun townRemoveGeneral(town: Town, resident: Resident): Boolean {
        if (resident.town !== town) {
            return false
        }
        if (!town.generals.contains(resident)) {
            return true
        }
        town.generals.remove(resident)
        // keep officer role by default after demotion
        town.needsUpdate()
        Nodes.needsSave = true
        return true
    }

    public fun playerIsOfficer(town: Town, player: Player): Boolean {
        val resident = Nodes.getResident(player)
        if (resident === town.leader || town.officers.contains(resident)) {
            return true
        }
        return false
    }

    public fun playerIsGeneral(town: Town, player: Player): Boolean {
        val resident = Nodes.getResident(player)
        return resident === town.leader || town.generals.contains(resident)
    }

    /**
     * Set town's leader. If input resident is null, try to remove
     * current town leader.
     */
    public fun townSetLeader(town: Town, resident: Resident?) {
        if (resident !== null) {
            if (resident.town !== town) {
                // must first be part of town, skipping
                return
            }

            // same town leader, ignore
            if (town.leader === resident) {
                return
            }

            // remove resident from officers if there
            town.generals.remove(resident)
            town.officers.remove(resident)

            town.leader = resident
            town.needsUpdate()
            Nodes.needsSave = true
        } else {
            // no resident input: remove current town leader
            if (town.leader === null) {
                // no leader to remove, skipping
                return
            }

            town.leader = null
            town.needsUpdate()
            Nodes.needsSave = true
        }
    }

    public fun renameTown(town: Town, s: String): Boolean {
        // check that new name not used
        if (Nodes.towns.contains(s)) {
            return false
        }

        Nodes.towns.remove(town.name)
        town.name = s
        town.updateNametags()

        Nodes.towns.put(s, town)
        town.needsUpdate()

        // update residents in town and nation
        town.nation?.needsUpdate()
        for (r in town.residents) {
            r.needsUpdate()
        }

        // update town allies/enemies
        for (t in town.enemies) {
            t.needsUpdate()
        }
        for (t in town.allies) {
            t.needsUpdate()
        }

        Nodes.needsSave = true

        // update nametags — only this town's prefix string changed
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamInAllCaches("t${town.townNametagId}")
        Nametag.markBurstAll()
        Nametag.flushBurst()

        return true
    }

    // view town income inventory gui
    public fun getTownIncomeInventory(town: Town): Inventory {
        // mark dirty if inventory not empty (player could take items)
        if (!town.income.empty()) {
            town.needsUpdate()
        }
        return town.income.getInventory()
    }

    /**
     * Set town permissions
     */
    public fun setTownPermissions(town: Town, perm: TownPermissions, group: PermissionsGroup, flag: Boolean) {
        // add perms
        if (flag == true) {
            town.permissions[perm].add(group)
        } else { // remove perms
            town.permissions[perm].remove(group)
        }

        town.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * set town's home territory
     */
    public fun setTownHomeTerritory(town: Town, territory: Territory) {
        if (town !== territory.town) {
            return
        }
        if (town.home == territory.id) {
            return
        }

        // set town home
        town.home = territory.id

        // set town spawn to new home territory
        town.spawnpoint = Nodes.getDefaultSpawnLocation(territory)

        // set cooldown
        town.moveHomeCooldown = Config.townMoveHomeCooldown

        // re-render minimaps
        Nodes.renderMinimaps()

        town.needsUpdate()
        Nodes.needsSave = true
    }

    // set town's move home cooldown period
    public fun setTownHomeMoveCooldown(town: Town, time: Long) {
        town.moveHomeCooldown = time
        town.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * Create an outpost for a town
     * Returns true on success, false on failure
     */
    public fun createTownOutpost(
        town: Town,
        name: String,
        territory: Territory,
        tier: PortTier = PortTier.TIER1,
    ): Boolean {
        // town must own the territory
        if (!town.territories.contains(territory.id)) {
            return false
        }

        // town must not have outpost of same name
        if (town.outposts.contains(name)) {
            return false
        }

        val spawn = Nodes.getDefaultSpawnLocation(territory)

        town.outposts.put(
            name,
            TownOutpost(
                name,
                territory.id,
                spawn,
                tier,
            ),
        )

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    /**
     * Remove town outpost with given name
     * Returns true on success, false on failure
     */
    public fun destroyTownOutpost(town: Town, name: String): Boolean {
        val outpost = town.outposts.remove(name)
        if (outpost === null) {
            return false
        }

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    /**
     * Set a town outpost's spawn location.
     * Returns true on success, false on failure
     */
    public fun setOutpostSpawn(town: Town, outpost: TownOutpost, spawn: Location): Boolean {
        // verify location is inside territory
        if (Nodes.getTerritoryFromChunk(spawn.chunk)?.id != outpost.territory) {
            return false
        }

        outpost.spawn = spawn

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    // when inventory close, require save because items could have
    // been moved
    internal fun onTownIncomeInventoryClose() {
        Nodes.needsSave = true
    }

    // set town's isOpen state
    internal fun setTownOpen(town: Town, isOpen: Boolean) {
        town.isOpen = isOpen
        town.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * Run cooldown tick, with change in time dt
     */
    internal fun townMoveHomeCooldownTick(dt: Long) {
        // reduce town move home territory cooldown
        for (town in Nodes.towns.values) {
            if (town.moveHomeCooldown > 0) {
                town.moveHomeCooldown = Math.max(0, town.moveHomeCooldown - dt)

                town.needsUpdate()
                Nodes.needsSave = true
            }
        }
    }

    /**
     * Reduce resident town create cooldown tick by dt
     */
    internal fun residentTownCreateCooldownTick(dt: Long) {
        // reduce player create town cooldown and rejoin cooldown
        for (resident in Nodes.residents.values) {
            if (resident.townCreateCooldown > 0) {
                resident.townCreateCooldown = Math.max(0, resident.townCreateCooldown - dt)
                resident.needsUpdate()
                Nodes.needsSave = true
            }
            if (resident.townRejoinCooldown > 0) {
                resident.townRejoinCooldown = Math.max(0, resident.townRejoinCooldown - dt)
                resident.needsUpdate()
                Nodes.needsSave = true
            }
        }
    }

    /**
     * Add town points based on currently online players in each town.
     * This is called once per minute by the periodic tick manager.
     */
    internal fun townPointsMinuteTick() {
        val gainPerPlayer = Config.townPointsPerPlayerPerMinute
        if (gainPerPlayer <= 0L) {
            return
        }

        Nodes.refreshPlayersOnline()

        for (town in Nodes.towns.values) {
            val gain = town.playersOnline.size.toLong() * gainPerPlayer
            if (gain > 0L) {
                town.townPoints += gain
                town.needsUpdate()
                Nodes.needsSave = true
            }
        }
    }

    // ==============================================
    // Nation functions
    // ==============================================
    public fun createNation(name: String, town: Town, leader: Resident? = null): Result<Nation> {
        if (town.nation != null) {
            return Result.failure(ErrorTownHasNation)
        }

        if (leader?.nation != null) {
            return Result.failure(ErrorPlayerHasNation)
        }

        if (leader != null && !town.residents.contains(leader)) {
            return Result.failure(ErrorPlayerNotInTown)
        }

        if (Nodes.getNationFromName(name) != null) {
            return Result.failure(ErrorNationExists)
        }

        val nation = Nation(UUID.randomUUID(), name, town)
        nations.put(name, nation)

        // add town to nation
        nation.towns.add(town)
        town.nation = nation

        // remove all pre-existing town alliances, but do not change enemies
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        town.allies.clear()

        // add nation to all residents in creator's town
        for (r in town.residents) {
            r.nation = nation
            r.needsUpdate()
        }

        town.needsUpdate()
        nation.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — nation created, team snapshot changes, burst everyone
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateAllCaches()
        Nametag.markBurstAll()
        Nametag.flushBurst()

        return Result.success(nation)
    }

    // load nation from data
    // used for deserializing from towns.json
    public fun loadNation(
        uuid: UUID,
        name: String,
        capitalName: String, // name of capital city
        color: Color?,
        towns: ArrayList<String>,
    ): Nation {
        val capital = Nodes.getTownFromName(capitalName)
        if (capital == null) {
            throw ErrorTownDoesNotExist
        }

        val nation = Nation(uuid, name, capital)

        // set nation color
        if (color != null) {
            nation.color = color
        }

        // add towns
        for (townName in towns) {
            val town = Nodes.getTownFromName(townName)
            if (town != null) {
                nation.towns.add(town)
                town.nation = nation
                town.needsUpdate()

                for (r in town.residents) {
                    r.nation = nation
                    nation.residents.add(r)
                    r.needsUpdate()
                }
            }
        }

        // mark dirty
        nation.needsUpdate()

        // save new nation
        nations.put(name, nation)

        return nation
    }

    public fun destroyNation(nation: Nation) {
        // remove nation level alliances and enemies
        for (ally in nation.allies) {
            ally.allies.remove(nation)
            ally.needsUpdate()
        }
        for (enemy in nation.enemies) {
            enemy.enemies.remove(nation)
            enemy.needsUpdate()
        }

        // remove town links
        for (town in nation.towns) {
            // remove all residents links
            for (r in town.residents) {
                r.nation = null
                r.needsUpdate()
            }

            // remove all town alliances and enemies
            for (ally in town.allies) {
                ally.allies.remove(town)
                ally.needsUpdate()
            }
            town.allies.clear()

            for (enemy in town.enemies) {
                enemy.enemies.remove(town)
                enemy.needsUpdate()
            }
            town.enemies.clear()

            town.nation = null
            town.needsUpdate()
        }

        Nodes.nations.remove(nation.name)

        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — nation destroyed. Each town's nation link was
        // just cleared; rebuild every town's baked nametag components so
        // the NATION-relation prefix reflects the new (nationless) state,
        // then invalidate all viewer caches so everyone re-sends on the
        // next drain.
        for (town in nation.towns) town.updateNametags()
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateAllCaches()
        Nametag.markBurstAll()
        Nametag.flushBurst()

        for (town in nation.towns) {
            evictTeamFromPool("t${town.townNametagId}")
        }
    }

    public fun getNationCount(): Int = Nodes.nations.size

    public fun getNationFromName(name: String): Nation? = nations.get(name)

    public fun addTownToNation(nation: Nation, town: Town): Result<Town> {
        // check town does not belong to nation
        if (town.nation != null) {
            return Result.failure(ErrorTownHasNation)
        }

        // add town to nation
        nation.towns.add(town)
        town.nation = nation
        town.needsUpdate()

        // add nation to residents
        for (r in town.residents) {
            r.nation = nation
            nation.residents.add(r)
            val player = r.player()
            if (player !== null) {
                nation.playersOnline.add(player)
            }
            r.needsUpdate()
        }

        // remove current town alliances and enemies, set equal to nation capital
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }

        town.allies.clear()
        town.enemies.clear()

        for (ally in nation.capital.allies) {
            town.allies.add(ally)
            ally.allies.add(town)
            ally.needsUpdate()
        }
        for (enemy in nation.capital.enemies) {
            town.enemies.add(enemy)
            enemy.enemies.add(town)
            enemy.needsUpdate()
        }

        nation.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — all towns in the nation now share relations with the new member
        val teamIds = nation.towns.map { "t${it.townNametagId}" }
        for (t in nation.towns) t.updateNametags()
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
        Nametag.flushBurst()
        flushTownStateNow()

        return Result.success(town)
    }

    public fun removeTownFromNation(nation: Nation, town: Town): Result<Town> {
        // check town belongs to nation
        if (town.nation !== nation) {
            return Result.failure(ErrorNationDoesNotHaveTown)
        }

        // remove town to nation
        nation.towns.remove(town)
        town.nation = null

        // remove nation from residents
        for (r in town.residents) {
            r.nation = null
            nation.residents.remove(r)
            r.needsUpdate()
        }

        // if nation has no more towns, destroy nation
        // -> cleans up allies, enemies, etc... and global references
        if (nation.towns.size == 0) {
            Nodes.destroyNation(nation)
        }
        // set new leader for nation if this was capital
        else {
            if (town === nation.capital) {
                val newCapital: Town = nation.towns.first()
                nation.capital = newCapital
                // print message to players in new capital
                for (r in newCapital.residents) {
                    val player = r.player()
                    if (player !== null) {
                        Message.print(player, "Your town is now the capital of ${nation.name}")
                    }
                }
            }
        }

        // remove all town alliances and enemies
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        town.allies.clear()

        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }
        town.enemies.clear()

        town.needsUpdate()
        nation.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — remaining nation towns + the now-independent town all changed
        // nation.towns has already had `town` removed at this point
        val teamIds = (nation.towns + listOf(town)).map { "t${it.townNametagId}" }
        (nation.towns + listOf(town)).forEach { it.updateNametags() }
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
        Nametag.flushBurst()
        flushTownStateNow()

        return Result.success(town)
    }

    public fun setNationColor(nation: Nation, r: Int, g: Int, b: Int) {
        nation.color = Color(r, g, b)
        nation.needsUpdate()
        Nodes.needsSave = true

        // Every town in the nation needs its NATION-relation prefix
        // recomputed because the colour is shared across the nation.
        val teamIds = nation.towns.map { "t${it.townNametagId}" }
        for (t in nation.towns) t.updateNametags()
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
    }

    public fun renameNation(nation: Nation, s: String): Boolean {
        // check that new name not used
        if (Nodes.nations.contains(s)) {
            return false
        }

        Nodes.nations.remove(nation.name)
        nation.name = s
        Nodes.nations.put(s, nation)
        nation.needsUpdate()

        // update nation towns and residents
        for (town in nation.towns) {
            town.needsUpdate()
            for (r in town.residents) {
                r.needsUpdate()
            }
        }

        // update nation allies/enemies
        for (n in nation.enemies) {
            n.needsUpdate()
        }
        for (n in nation.allies) {
            n.needsUpdate()
        }

        Nodes.needsSave = true

        // update nametags — all towns in this nation have a changed prefix
        val teamIds = nation.towns.map { "t${it.townNametagId}" }
        for (t in nation.towns) t.updateNametags()
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
        Nametag.flushBurst()

        return true
    }

    /**
     * Set nation capital to a new town. Town must be in the nation already
     */
    public fun setNationCapital(nation: Nation, town: Town) {
        if (town.nation !== nation || nation.capital === town) {
            return
        }

        nation.capital = town

        nation.needsUpdate()
        Nodes.needsSave = true

        // update nametags — all towns in the nation may have changed color/prefix display
        val teamIds = nation.towns.map { "t${it.townNametagId}" }
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
        Nametag.flushBurst()
    }

    // ==============================================
    // Territory income cycle functions
    // ==============================================

    /**
     * System to run income from all town territories and deposit items into
     * a town's income inventory chest. For a town's occupied territories,
     * it gives the income to the occupier town. This must also handle
     * adjusting net income rates based on a town's over max claims penalty.
     * Strategy for income:
     *     for each town:
     *         // 1. construct hashmap of each town name mapped to an enum map of
     *         //    material mapped to net income to be given
     *         townIncomes = HashMap<Town, EnumMap<Material, Double>>
     *
     *         // 2. accumulate net income from each territory into the town
     *         //    incomes hashmap. for occupied territories, create a new
     *         //    town entry if it doesn't exist.
     *         for territory in town:
     *             doTownIncomeLogic(territory)
     *
     *         // 3. apply over max claims penalty, other modifiers, etc.
     *         //    to each town's income to be given
     *         townIncomes.forEach { town, income -> doTownIncomeModifiers(town, income) }
     *
     *         // 4. add incomes to each town's income chests
     *         townIncomes.forEach { town, income -> addTownIncomeToChest(town, income) }
     */
    public fun runIncome() {
        // Anti-dupe safety:
        // force-close income/deposit menus before processing so all items
        // are reconciled into backend storage before calculations start.
        for (town in Nodes.towns.values) {
            town.income._inventory.viewers.toList().forEach { viewer ->
                viewer.closeInventory()
            }
        }

        /**
         * Helper to convert an income item rate Double to a item count as Int.
         * The rate must be >0.0 but can have fractional parts which allow for
         * random rolls for item amount. Examples for handling rates:
         * - rate = 2.0 : 2 items
         * - rate = 2.5 : split into 2.0 + 0.5
         *      - 2.0 -> 2 items are guaranteed
         *      - 0.5 -> do random roll, if roll < 0.5, add 1 item (50% chance)
         */
        fun rateToAmount(rate: Double): Int {
            if (rate <= 0.0) {
                return 0
            }

            // determine integer part and fractional remainder for random roll
            val intPart = kotlin.math.floor(rate)
            val fracPart = kotlin.math.max(0.0, rate - intPart)

            val fracAmount = if (fracPart > 0.0) {
                val roll = ThreadLocalRandom.current().nextDouble()
                if (roll < fracPart) {
                    1
                } else {
                    0
                }
            } else {
                0
            }

            return intPart.toInt() + fracAmount
        }

        // tax and kept item rates for occupied territories
        val taxRate = Config.taxIncomeRate.coerceIn(0.0, 1.0)
        val keptRate = 1.0 - taxRate

        for (town in Nodes.towns.values) {
            try {
                val thisTownIncome = EnumMap<Material, Double>(Material::class.java) // hard-coded value for this town
                val townIncomes = HashMap<Town, EnumMap<Material, Double>>()

                // inject this town, to unify claims penalty logic later
                townIncomes[town] = thisTownIncome

                for (terrId in town.territories) {
                    val territory = Nodes.getTerritoryFromId(terrId)
                    if (territory === null) {
                        continue
                    }

                    val snapshot = Nodes.getTerritoryResourceSnapshot(territory)
                    val territoryIncome = snapshot.income
                    val occupier = territory.occupier
                    if (occupier != null) {
                        val occupierIncome = townIncomes.getOrPut(occupier) { EnumMap<Material, Double>(Material::class.java) }
                        // regular item income
                        for ((material, amount) in territoryIncome) {
                            occupierIncome[material] = (occupierIncome[material] ?: 0.0) + (amount * taxRate)
                            thisTownIncome[material] = (thisTownIncome[material] ?: 0.0) + (amount * keptRate)
                        }

                        // TODO: 1.12 compatibility? spawn egg income
                        // for ( (entityType, rate) in territory.incomeSpawnEgg ) {
                        //     TODO
                        // }
                    } else {
                        // regular item income
                        for ((material, amount) in territoryIncome) {
                            thisTownIncome[material] = (thisTownIncome[material] ?: 0.0) + amount
                        }

                        // TODO: 1.12 compatibility? spawn egg income
                        // for ( (entityType, rate) in territory.incomeSpawnEgg ) {
                        //     TODO
                        // }
                    }
                }

                // apply income modifiers for each town, then add items to town income chest
                for ((townForIncome, income) in townIncomes) {
                    var incomeModifier = 1.0

                    // over max claims penalty
                    if (Config.claimsPowerEnabled && Config.overClaimsPenalty && townForIncome.isOverClaimsMax == true) {
                        incomeModifier *= (1.0 - Config.overClaimsMaxPenalty)
                    }

                    // income claims power scaling
                    if (Config.claimsPowerEnabled && Config.incomeScaleByClaimPower && townForIncome.claimsUsed > 0) {
                        val claimsUsedRatio = townForIncome.claimsMax.toDouble() / townForIncome.claimsUsed.toDouble()
                        val incomeClaimsPowerScale = claimsUsedRatio.coerceIn(Config.incomeScaleMin, Config.incomeScaleMax)
                        incomeModifier *= incomeClaimsPowerScale
                    }

                    // we can do any other income modifiers here in the future

                    // add items to town income chest
                    for ((material, amount) in income) {
                        val amountInt = rateToAmount(amount * incomeModifier)
                        if (amountInt > 0) {
                            Nodes.addToIncome(town, material, amountInt)
                        }
                    }
                }

                // TODO: 1.12 compatibility? spawn egg income
                // for ( (entityType, rate) in territory.incomeSpawnEgg ) {
                //     var amount: Int = rateToAmount(rate)
                //     town.income.add(Material.MONSTER_EGG, amount, entityType.ordinal)
                // }
            } catch (err: Exception) {
                Nodes.logger?.severe("Error running income for town ${town.name}")
                err.printStackTrace()
            }
        }

        // message players ingame that income collected
        Message.broadcast("Towns have collected income (use \"/t income\" to get)")
    }

    // ==============================================
    // Handle war and diplomatic relations
    //
    // Rules for declaring war:
    // 1. who can declare war:
    //    - town without nation: town
    //    - town with nation:
    //       - capital town declare war against other towns
    //       - internal town declare war on other towns in nation
    //    - cannot war an ally or town/nation with truce
    // 2. war on enemy town:
    //    - if enemy town has no nation, only war against town
    //    - if enemy town has nation, war defaults against enemy nation
    //    - if enemy town is in your nation, civil war boogaloo
    // 3. war on enemy nation:
    //    - all towns in enemy nation become enemies of all
    //      towns in your nation
    //
    // Same rules apply for making allies.
    // ==============================================

    public fun enableWar(
        canAnnexTerritories: Boolean,
        canOnlyAttackBorders: Boolean,
        destructionEnabled: Boolean,
        durationMillis: Long = 0L,
        sessionMode: String = FlagWar.SESSION_MODE_NORMAL,
    ) {
        Nodes.war.enable(canAnnexTerritories, canOnlyAttackBorders, destructionEnabled, durationMillis, sessionMode)
    }

    public fun disableWar() {
        Nodes.war.disable()

        // re-render minimaps
        Nodes.renderMinimaps()
    }

    /**
     * Set two towns as enemies and their nations if needed, order does not matter.
     * This should be the main function used.
     */
    public fun addEnemy(town: Town, enemy: Town): Result<Boolean> {
        // make sure towns are not allies or in truce
        if (town.allies.contains(enemy) || Truce.contains(town, enemy)) {
            return Result.failure(ErrorWarAllyOrTruce)
        }

        // check if towns already enemies
        if (town.enemies.contains(enemy) || enemy.enemies.contains(town)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        val townNation = town.nation
        val enemyNation = enemy.nation

        if (townNation !== null) {
            // nation-nation war
            if (enemyNation !== null) {
                // civil war boogaloo
                if (enemyNation === townNation) {
                    town.enemies.add(enemy)
                    enemy.enemies.add(town)

                    // remove ally status
                    town.allies.remove(enemy)
                    enemy.allies.remove(town)
                }
                // default to nation-nation war
                else {
                    if (town === townNation.capital) { // only nation leading town declare war
                        return nationAddEnemy(townNation, enemyNation)
                    }
                }
            }
            // nation-town war
            else {
                for (t in townNation.towns) {
                    t.enemies.add(enemy)
                    enemy.enemies.add(t)

                    t.needsUpdate()
                }
            }
        }
        // town declaring war without nation
        else {
            // town-nation war
            if (enemyNation !== null) {
                for (t in enemyNation.towns) {
                    t.enemies.add(town)
                    town.enemies.add(t)

                    t.needsUpdate()
                }
            }
            // town-town war
            else {
                town.enemies.add(enemy)
                enemy.enemies.add(town)
            }
        }

        town.needsUpdate()
        enemy.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — only the two directly involved towns' relations changed
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(listOf("t${town.townNametagId}", "t${enemy.townNametagId}"))
        Nametag.markBurstAll()
        Nametag.flushBurst()
        flushTownStateNow()

        return Result.success(true)
    }

    /**
     * Removes enemy status between two towns (and nations if needed)
     * Order does not matter.
     */
    public fun removeEnemy(town: Town, enemy: Town): Result<Boolean> {
        val townNation = town.nation
        val enemyNation = enemy.nation

        if (townNation !== null) {
            // nation-nation war
            if (enemyNation !== null) {
                // civil war boogaloo
                if (enemyNation === townNation) {
                    town.enemies.remove(enemy)
                    enemy.enemies.remove(town)

                    town.allies.add(enemy)
                    enemy.allies.add(town)
                }
                // default to nation-nation war
                else {
                    return nationRemoveEnemy(townNation, enemyNation)
                }
            }
            // nation-town war
            else {
                for (t in townNation.towns) {
                    t.enemies.remove(enemy)
                    enemy.enemies.remove(t)

                    t.needsUpdate()
                }
            }
        }
        // town declaring war without nation
        else {
            // town-nation war
            if (enemyNation !== null) {
                for (t in enemyNation.towns) {
                    t.enemies.remove(town)
                    town.enemies.remove(t)

                    t.needsUpdate()
                }
            }
            // town-town war
            else {
                town.enemies.remove(enemy)
                enemy.enemies.remove(town)
            }
        }

        town.needsUpdate()
        enemy.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — only the two directly involved towns' relations changed
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(listOf("t${town.townNametagId}", "t${enemy.townNametagId}"))
        Nametag.markBurstAll()
        Nametag.flushBurst()
        flushTownStateNow()

        return Result.success(true)
    }

    /**
     * Set two towns as allies (bidirectional), order does not matter.
     * Handle town-town, town-nation, nation-town, nation-nation cases
     */
    public fun addAlly(town: Town, other: Town): Result<Boolean> {
        val pm = Bukkit.getPluginManager()

        // towns already allies
        if (town.allies.contains(other) && other.allies.contains(town)) {
            return Result.failure(ErrorAlreadyAllies)
        }

        // cannot ally enemies
        if (town.enemies.contains(other) || other.enemies.contains(town)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        val townNation = town.nation
        val otherNation = other.nation

        if (townNation !== null) {
            // nation-nation
            if (otherNation !== null && townNation !== otherNation) {
                Nodes.nationAddAlly(townNation, otherNation)
            }
            // nation-town
            else {
                for (t in townNation.towns) {
                    t.allies.add(other)
                    other.allies.add(town)

                    t.needsUpdate()
                    pm.callEvent(AllianceCreatedEvent(t, other))
                }
            }
        }
        // town allying without nation
        else {
            // town-nation
            if (otherNation !== null) {
                for (t in otherNation.towns) {
                    t.allies.add(town)
                    town.allies.add(t)

                    t.needsUpdate()
                    pm.callEvent(AllianceCreatedEvent(town, t))
                }
            }
            // town-town
            else {
                town.allies.add(other)
                other.allies.add(town)
                pm.callEvent(AllianceCreatedEvent(town, other))
            }
        }

        town.needsUpdate()
        other.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — only the two directly involved towns' relations changed
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(listOf("t${town.townNametagId}", "t${other.townNametagId}"))
        Nametag.markBurstAll()
        Nametag.flushBurst()
        flushTownStateNow()

        return Result.success(true)
    }

    public fun removeAlly(town: Town, other: Town): Result<Boolean> {
        // not currently allies
        if (!town.allies.contains(other) || !other.allies.contains(town)) {
            return Result.failure(ErrorNotAllies)
        }

        val townNation = town.nation
        val otherNation = other.nation

        if (townNation !== null) {
            // nation-nation
            if (otherNation !== null && townNation !== otherNation) {
                Nodes.nationRemoveAlly(townNation, otherNation)
            }
            // nation-town
            else {
                for (t in townNation.towns) {
                    t.allies.remove(other)
                    other.allies.remove(town)

                    t.needsUpdate()
                }
            }
        }
        // town allying without nation
        else {
            // town-nation
            if (otherNation !== null) {
                for (t in otherNation.towns) {
                    t.allies.remove(town)
                    town.allies.remove(t)

                    t.needsUpdate()
                }
            }
            // town-town
            else {
                town.allies.remove(other)
                other.allies.remove(town)
            }
        }

        town.needsUpdate()
        other.needsUpdate()
        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — only the two directly involved towns' relations changed
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(listOf("t${town.townNametagId}", "t${other.townNametagId}"))
        Nametag.markBurstAll()
        Nametag.flushBurst()
        flushTownStateNow()

        return Result.success(true)
    }

    public fun addTruce(town: Town, other: Town): Result<Boolean> {
        // towns already under truce
        if (Truce.contains(town, other)) {
            return Result.failure(ErrorAlreadyTruce)
        }

        // truce start time
        val time = System.currentTimeMillis()

        val townNation = town.nation
        val otherNation = other.nation

        if (townNation !== null) {
            // nation-nation
            if (otherNation !== null && townNation !== otherNation) {
                for (t in townNation.towns) {
                    for (other in otherNation.towns) {
                        Truce.create(t, other, time)
                    }
                }
            }
            // nation-town
            else {
                for (t in townNation.towns) {
                    Truce.create(t, other, time)
                }
            }
        }
        // town allying without nation
        else {
            // town-nation
            if (otherNation !== null) {
                for (t in otherNation.towns) {
                    Truce.create(town, t, time)
                }
            }
            // town-town
            else {
                Truce.create(town, other, time)
            }
        }

        flushTownStateNow(saveTruceState = true)
        return Result.success(true)
    }

    /**
     * Truces all handled between town pairs, so removeTruce should only
     * affect towns involved and not nations.
     *
     * addTruce creates truces for all town pairs in nations,
     * truce expiration tick will call this removeTruce for all those
     * town pairs individually.
     */
    public fun removeTruce(town: Town, other: Town): Result<Boolean> {
        Truce.remove(town, other)

        Bukkit.getPluginManager().callEvent(TruceExpiredEvent(town, other))

        // update nametags — only the two directly involved towns' relations changed
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(listOf("t${town.townNametagId}", "t${other.townNametagId}"))
        Nametag.markBurstAll()
        Nametag.flushBurst()
        flushTownStateNow(saveTruceState = true)

        return Result.success(true)
    }

    // set two nations as enemies (bidirectional), order does not matter
    // -> sets all towns in each nation as enemies
    // returns true on success
    private fun nationAddEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
        // make sure nations are not allies or in truce
        if (nation.allies.contains(enemy) || Truce.contains(nation.capital, enemy.capital)) {
            return Result.failure(ErrorWarAllyOrTruce)
        }

        // check if nations already enemies
        if (nation.enemies.contains(enemy) && enemy.enemies.contains(nation)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        nation.enemies.add(enemy)
        enemy.enemies.add(nation)

        // mark all towns in each nation as enemies
        for (nationTown in nation.towns) {
            for (enemyTown in enemy.towns) {
                nationTown.enemies.add(enemyTown)
                enemyTown.enemies.add(nationTown)

                enemyTown.needsUpdate()
            }
            nationTown.needsUpdate()
        }

        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — all towns in both nations have changed relations
        val teamIds = (nation.towns + enemy.towns).map { "t${it.townNametagId}" }
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
        Nametag.flushBurst()

        return Result.success(true)
    }

    private fun nationRemoveEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
        nation.enemies.remove(enemy)
        enemy.enemies.remove(nation)

        // remove enemy status between towns in each nation
        for (nationTown in nation.towns) {
            for (enemyTown in enemy.towns) {
                nationTown.enemies.remove(enemyTown)
                enemyTown.enemies.remove(nationTown)

                enemyTown.needsUpdate()
            }
            nationTown.needsUpdate()
        }

        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — all towns in both nations have changed relations
        val teamIds = (nation.towns + enemy.towns).map { "t${it.townNametagId}" }
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
        Nametag.flushBurst()

        return Result.success(true)
    }

    // add alliance between two nations and their towns (bidirectional)
    private fun nationAddAlly(nation: Nation, ally: Nation): Result<Boolean> {
        val pm = Bukkit.getPluginManager()

        // make sure nations are not enemies
        if (nation.enemies.contains(ally) || ally.enemies.contains(nation)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        // check if nations already have alliance
        if (nation.allies.contains(ally) && ally.allies.contains(nation)) {
            return Result.failure(ErrorAlreadyAllies)
        }

        nation.allies.add(ally)
        ally.allies.add(nation)

        // mark all towns in each nation as enemies
        for (nationTown in nation.towns) {
            for (allyTown in ally.towns) {
                nationTown.allies.add(allyTown)
                allyTown.allies.add(nationTown)

                allyTown.needsUpdate()

                pm.callEvent(AllianceCreatedEvent(nationTown, allyTown))
            }
            nationTown.needsUpdate()
        }

        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — all towns in both nations have changed relations
        val teamIds = (nation.towns + ally.towns).map { "t${it.townNametagId}" }
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
        Nametag.flushBurst()

        return Result.success(true)
    }

    private fun nationRemoveAlly(nation: Nation, ally: Nation): Result<Boolean> {
        nation.allies.remove(ally)
        ally.allies.remove(nation)

        // mark all towns in each nation as enemies
        for (nationTown in nation.towns) {
            for (allyTown in ally.towns) {
                nationTown.allies.remove(allyTown)
                allyTown.allies.remove(nationTown)

                allyTown.needsUpdate()
            }
            nationTown.needsUpdate()
        }

        Nodes.needsSave = true

        // re-render minimaps
        Nodes.renderMinimaps()

        // update nametags — all towns in both nations have changed relations
        val teamIds = (nation.towns + ally.towns).map { "t${it.townNametagId}" }
        Nametag.rebuildTeamSnapshot()
        Nametag.invalidateTeamsInAllCaches(teamIds)
        Nametag.markBurstAll()
        Nametag.flushBurst()

        return Result.success(true)
    }

    private fun flushTownStateNow(saveTruceState: Boolean = false) {
        if (!Nodes.initialized) {
            return
        }

        synchronized(townFlushLock) {
            if (saveTruceState || Truce.needsUpdate) {
                townFlushNeedsTruceSave = true
            }
            if (townFlushQueued) {
                return
            }
            townFlushQueued = true
        }

        val plugin = Nodes.plugin
        if (plugin == null) {
            synchronized(townFlushLock) {
                townFlushQueued = false
            }
            return
        }

        // Coalesce bursts of town/relation/member updates into a single save next tick.
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            val shouldSaveTruce = synchronized(townFlushLock) {
                val pending = townFlushNeedsTruceSave
                townFlushNeedsTruceSave = false
                townFlushQueued = false
                pending
            }

            Nodes.saveWorld(checkIfNeedsSave = false, async = true)
            if (shouldSaveTruce) {
                Nodes.saveTruce(async = true)
            }
        }, 1L)
    }

    internal fun flushTownStateNowImmediate(saveTruceState: Boolean = false) {
        if (!Nodes.initialized) {
            return
        }

        Nodes.saveWorld(checkIfNeedsSave = false, async = false)
        if (saveTruceState || Truce.needsUpdate) {
            Nodes.saveTruce(async = false)
        }
    }

    /**
     * Run the truce tick
     */
    public fun truceTick() {
        // current time
        val time = System.currentTimeMillis()

        // get list of expired truces
        val expired: List<TownPair> = Truce.truces.asSequence()
            .filter({ (towns, startTime) ->
                (time - startTime) > Config.trucePeriod
            })
            .map({ (towns, startTime) ->
                towns
            })
            .toList()

        // remove expired truces
        for (towns in expired) {
            Nodes.removeTruce(towns.town1, towns.town2)
        }

        // save truce.json file
        if (Truce.needsUpdate == true) {
            Bukkit.getAsyncScheduler().runNow(Nodes.plugin!!, { _ -> Truce.saveTask().run() })
            Truce.needsUpdate = false
        }
    }

    /**
     * Save truces to json file. This is separate from world state save
     * because truces only need to be saved infrequently on a separate
     * schedule than regular world save.
     */
    public fun saveTruce(async: Boolean = false) {
        if (async) {
            Bukkit.getAsyncScheduler().runNow(Nodes.plugin!!, { _ -> Truce.saveTask().run() })
        } else {
            Truce.saveTask().run()
        }
    }

    /**
     * Get diplomatic relationship between two towns
     */
    public fun getRelationshipOfTownToTown(playerTown: Town?, otherTown: Town?): DiplomaticRelationship {
        if (playerTown !== null && otherTown !== null) {
            if (playerTown === otherTown) {
                return DiplomaticRelationship.TOWN
            }

            val playerNation = playerTown.nation
            val otherNation = otherTown.nation
            if (playerNation !== null && playerNation === otherNation) {
                return DiplomaticRelationship.NATION
            }

            if (playerTown.allies.contains(otherTown)) {
                return DiplomaticRelationship.ALLY
            }

            if (playerTown.enemies.contains(otherTown)) {
                return DiplomaticRelationship.ENEMY
            }
        }

        return DiplomaticRelationship.NEUTRAL
    }

    public fun getRelationshipOfPlayerToTown(player: Player, otherTown: Town): DiplomaticRelationship {
        val playerTown = Nodes.getTownFromPlayer(player)
        return getRelationshipOfTownToTown(playerTown, otherTown)
    }

    public fun getRelationshipOfPlayerToPlayer(player: Player, other: Player): DiplomaticRelationship {
        val playerTown = Nodes.getTownFromPlayer(player)
        val otherTown = Nodes.getTownFromPlayer(other)
        return getRelationshipOfTownToTown(playerTown, otherTown)
    }

    // ==============================================
    // Chest protection functions
    // ==============================================

    /**
     * Sets resident trust
     */
    internal fun setResidentTrust(resident: Resident, trust: Boolean) {
        resident.trusted = trust
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * Add event listener for protecting/unprotecting chests
     * with mouse clicks
     */
    internal fun startProtectingChests(resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        val town = resident.town
        if (town === null) {
            return
        }

        // check that resident is leader or officer
        if (resident !== town.leader && !town.officers.contains(resident)) {
            return
        }

        resident.isProtectingChests = true
    }

    /**
     * Remove event listener for protecting chests
     */
    internal fun stopProtectingChests(resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        // remove resident links
        resident.isProtectingChests = false
    }

    /**
     * Mark town chest as protected by town
     * Handles checking for connected chests.
     * Protect: true/false setting for protecting or unprotecting
     */
    internal fun protectTownChest(town: Town, block: Block, protect: Boolean) {
        // get connected chest blocks
        fun getConnectedBlocks(block: Block): List<Block> {
            val type = block.type
            if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
                val blockState = block.getState()
                if (blockState is Chest) {
                    val chest = blockState as Chest
                    val inventory = chest.getInventory()
                    if (inventory is DoubleChestInventory) {
                        val doubleChest = inventory.getHolder() as DoubleChest

                        // get sides and add to blocks
                        val leftSide: Block = (doubleChest.getLeftSide() as Chest).block
                        val rightSide: Block = (doubleChest.getRightSide() as Chest).block

                        return listOf(leftSide, rightSide)
                    }
                }
            }

            return listOf(block)
        }

        // adding protection: get connected blocks, else only use block
        val blocks: List<Block> = if (protect == true) {
            getConnectedBlocks(block)
        } else {
            listOf(block)
        }

        if (protect == true) {
            for (block in blocks) {
                town.protectedBlocks.add(block)
            }
        } else {
            for (block in blocks) {
                town.protectedBlocks.remove(block)
            }
        }

        town.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * Generate particles at town's protected chests viewed by
     * input resident
     */
    internal fun showProtectedChests(town: Town, resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        val protectedBlocks = town.protectedBlocks

        // create repeating event to spawn particles each second
        val particle = Particle.HAPPY_VILLAGER
        val particleCount = 3
        val randomOffsetXZ = 0.05
        val randomOffsetY = 0.1

        val maxRuns = 10
        var runCount = 0

        var task: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null

        val runnable = object : Runnable {
            override fun run() {
                player.scheduler.run(
                    Nodes.plugin!!,
                    { _ ->
                        for (block in protectedBlocks) {
                            // corners
                            val location1 = Location(block.world, block.x.toDouble() + 0.1, block.y.toDouble() + 0.5, block.z.toDouble() + 0.1)
                            val location2 = Location(block.world, block.x.toDouble() + 0.1, block.y.toDouble() + 0.5, block.z.toDouble() + 0.9)
                            val location3 = Location(block.world, block.x.toDouble() + 0.9, block.y.toDouble() + 0.5, block.z.toDouble() + 0.1)
                            val location4 = Location(block.world, block.x.toDouble() + 0.9, block.y.toDouble() + 0.5, block.z.toDouble() + 0.9)

                            // centers
                            val location5 = Location(block.world, block.x.toDouble() + 0.5, block.y.toDouble() + 0.5, block.z.toDouble())
                            val location6 = Location(block.world, block.x.toDouble(), block.y.toDouble() + 0.5, block.z.toDouble() + 0.5)
                            val location7 = Location(block.world, block.x.toDouble() + 0.5, block.y.toDouble() + 0.5, block.z.toDouble() + 1.0)
                            val location8 = Location(block.world, block.x.toDouble() + 1.0, block.y.toDouble() + 0.5, block.z.toDouble() + 0.5)

                            player.spawnParticle(particle, location1, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                            player.spawnParticle(particle, location2, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                            player.spawnParticle(particle, location3, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                            player.spawnParticle(particle, location4, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                            player.spawnParticle(particle, location5, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                            player.spawnParticle(particle, location6, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                            player.spawnParticle(particle, location7, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                            player.spawnParticle(particle, location8, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                        }

                        runCount += 1
                        if (runCount > maxRuns) {
                            task?.cancel()
                        }
                    },
                    null,
                )
            }
        }

        task = Bukkit.getAsyncScheduler().runAtFixedRate(
            Nodes.plugin!!,
            { _ -> runnable.run() },
            1000,
            1000,
            TimeUnit.MILLISECONDS,
        )
    }

    // ==============================================
    // Port functions
    // ==============================================
    // load port from data
    // used for deserializing from ports.json
    public fun loadPort(
        name: String,
        locX: Int,
        locZ: Int,
        groups: HashSet<PortGroup>,
        isPublic: Boolean,
        tier: PortTier = PortTier.TIER1,
    ): Port? {
        val port = Port(name, locX, locZ, groups, isPublic, tier)

        // save new port
        ports.put(name, port)

        // for each port, map the chunk its in to it
        val chunk = listOf(Math.floorDiv(locX, 16), Math.floorDiv(locZ, 16))
        chunkToPort.put(chunk, port)

        // mark dirty
        port.needsUpdate()

        return port
    }

    public fun destroyPort(port: Port) {
        // remove from ports map
        Nodes.ports.remove(port.name)

        // remove chunk mappings
        val chunk = listOf(Math.floorDiv(port.locX, 16), Math.floorDiv(port.locZ, 16))
        chunkToPort.remove(chunk)

        Nodes.needsSave = true
    }

    public fun getPortCount(): Int = ports.size

    public fun getPortGroupCount(): Int = portGroups.size

    public fun getPortApplicationCount(): Int = portApplications.size

    public fun getOutpostApplicationCount(): Int = outpostApplications.size

    public fun getOutpostCount(): Int = towns.values.sumOf { it.outposts.size }

    public fun getPortFromName(name: String): Port? = ports.get(name)

    public fun getPortGroupFromName(name: String): PortGroup? = portGroups.get(name)

    public fun createPortGroup(name: String): Result<PortGroup> {
        if (portGroups.containsKey(name)) {
            return Result.failure(ErrorPortExists)
        }

        val portGroup = PortGroup(name)
        portGroups.put(name, portGroup)

        return Result.success(portGroup)
    }

    public fun destroyPortGroup(portGroup: PortGroup) {
        // remove from portGroups map
        Nodes.portGroups.remove(portGroup.name)

        Nodes.needsSave = true
    }

    public fun createPort(
        name: String,
        locX: Int,
        locZ: Int,
        groups: HashSet<PortGroup>,
        isPublic: Boolean,
        tier: PortTier = PortTier.TIER1,
    ): Result<Port> {
        // check if port already exists
        if (ports.containsKey(name)) {
            return Result.failure(ErrorPortExists)
        }

        val port = Port(name, locX, locZ, groups, isPublic, tier)

        // save new port
        ports.put(name, port)

        // for each port, map the chunk its in to it
        val chunk = listOf(Math.floorDiv(locX, 16), Math.floorDiv(locZ, 16))
        chunkToPort.put(chunk, port)

        // mark dirty
        port.needsUpdate()
        needsSave = true

        return Result.success(port)
    }

    public fun addPortToGroup(port: Port, group: PortGroup): Result<Port> {
        // check port is not already in this group
        if (port.groups.contains(group)) {
            return Result.failure(ErrorPortInGroup)
        }

        port.groups.add(group)
        port.needsUpdate()
        Nodes.needsSave = true

        return Result.success(port)
    }

    public fun setPortTier(port: Port, tier: PortTier): Port {
        if (port.tier == tier) {
            return port
        }

        val updatedPort = port.copy(tier = tier)
        ports[port.name] = updatedPort

        val chunk = listOf(Math.floorDiv(port.locX, 16), Math.floorDiv(port.locZ, 16))
        chunkToPort[chunk] = updatedPort

        updatedPort.needsUpdate()
        needsSave = true

        return updatedPort
    }

    public fun removePortFromGroup(port: Port, group: PortGroup) {
        // check port is in group
        if (!port.groups.contains(group)) {
            return
        }

        port.groups.remove(group)
        port.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * Get port owner based on who owns chunk
     * If no owner or if port is public, return null
     * If chunk is occupied, return occupier
     * Else, return territory town (may be null)
     */
    public fun getPortOwner(port: Port): Town? {
        if (port.isPublic) {
            return null
        }

        val chunk = getTerritoryChunkFromCoord(Coord(port.chunkX, port.chunkZ))
        if (chunk === null) {
            return null
        }

        val occupier = chunk.occupier
        if (occupier !== null) {
            return occupier
        }

        return chunk.territory.town
    }

    /**
     * Check if two ports share a group
     */
    fun sharePortGroups(port1: Port, port2: Port): Boolean = port1.groups.any { it in port2.groups }

    public fun createPortApplication(
        requester: Player,
        requestedPortName: String,
    ): Result<PortApplication> {
        val existingId = playerPortApplications[requester.uniqueId]
        if (existingId != null) {
            return Result.failure(IllegalStateException("You already have an open port request (#$existingId)"))
        }

        if (ports.containsKey(requestedPortName.lowercase())) {
            return Result.failure(IllegalStateException("A port with that name already exists"))
        }

        val id = nextPortApplicationId
        nextPortApplicationId += 1

        val app = PortApplication(
            id = id,
            requesterUuid = requester.uniqueId,
            requesterName = requester.name,
            requestedPortName = requestedPortName.lowercase(),
            applyLocation = requester.location.clone(),
        )

        portApplications[id] = app
        playerPortApplications[requester.uniqueId] = id

        return Result.success(app)
    }

    public fun getPortApplication(id: Int): PortApplication? = portApplications[id]

    public fun clearPortApplication(id: Int): PortApplication? {
        val app = portApplications.remove(id) ?: return null
        playerPortApplications.remove(app.requesterUuid)
        return app
    }

    public fun createOutpostApplication(
        requester: Player,
        requestedOutpostName: String,
    ): Result<OutpostApplication> {
        val resident = Nodes.getResident(requester)
            ?: return Result.failure(IllegalStateException("Could not resolve resident"))
        val town = resident.town
            ?: return Result.failure(IllegalStateException("You are not in a town"))

        val existingId = playerOutpostApplications[requester.uniqueId]
        if (existingId != null) {
            return Result.failure(IllegalStateException("You already have an open outpost request (#$existingId)"))
        }

        if (town.outposts.containsKey(requestedOutpostName)) {
            return Result.failure(IllegalStateException("Your town already has an outpost with that name"))
        }

        val territory = Nodes.getTerritoryFromPlayer(requester)
            ?: return Result.failure(IllegalStateException("You must stand in a claimed territory"))

        if (territory.town != town) {
            return Result.failure(IllegalStateException("You must stand in your own town territory"))
        }

        if (town.home == territory.id) {
            return Result.failure(IllegalStateException("You cannot create an outpost in your home territory"))
        }

        if (town.outposts.values.any { it.territory == territory.id }) {
            return Result.failure(IllegalStateException("Your town already has an outpost in this territory"))
        }

        val id = nextOutpostApplicationId
        nextOutpostApplicationId += 1

        val app = OutpostApplication(
            id = id,
            requesterUuid = requester.uniqueId,
            requesterName = requester.name,
            townName = town.name,
            requestedOutpostName = requestedOutpostName,
            territoryId = territory.id,
            applyLocation = requester.location.clone(),
        )

        outpostApplications[id] = app
        playerOutpostApplications[requester.uniqueId] = id

        return Result.success(app)
    }

    public fun getOutpostApplication(id: Int): OutpostApplication? = outpostApplications[id]

    public fun clearOutpostApplication(id: Int): OutpostApplication? {
        val app = outpostApplications.remove(id) ?: return null
        playerOutpostApplications.remove(app.requesterUuid)
        return app
    }

    // ==============================================
    // Hooks to external functions
    // ==============================================
    // just sets a flag that dynmap exists
    internal fun hookDynmap() {
        Nodes.dynmap = true
    }
}

// ==============================================
// NodesExtensions (merged from NodesExtensions.kt)
// ==============================================

/**
 * Compatibility bridge so imported extensions can run inside the main Nodes plugin.
 */
object NodesExtensions {
    val instance: JavaPlugin
        get() = (Nodes.plugin as? JavaPlugin) ?: error("Nodes plugin not initialized")
}

object MiningOutminerManager : Listener {

    var enabled: Boolean = true

    private const val SPAWN_BLOCK_RADIUS = 50
    private const val SPAWN_BLOCK_RADIUS_SQ = SPAWN_BLOCK_RADIUS * SPAWN_BLOCK_RADIUS
    private const val COOLDOWN_MS = 3L * 60L * 60L * 1000L
    private val DISPLAY_BLOCK = Material.BLAST_FURNACE

    private val plugin get() = NodesExtensions.instance
    private val dataFile get() = Config.pathMiningOutminerData.toFile()
    private val lock = Any()
    private val outminers = linkedMapOf<LocationKey, MiningOutminer>()

    @Volatile
    private var saveQueued = false

    @Volatile
    private var saveDirty = false
    private const val SAVE_DEBOUNCE_MS = 750L
    private const val HOLOGRAM_TAG = "nodes_mining_outpost_hologram"

    fun load() {
        clearManagedHolograms()
        synchronized(lock) {
            outminers.clear()
            saveDirty = false
        }
        if (!dataFile.exists()) {
            plugin.logger.info("[MiningOutminer] No data file found, starting fresh.")
            return
        }

        val config = YamlConfiguration.loadConfiguration(dataFile)
        config.getMapList("outminers").forEach { raw ->
            @Suppress("UNCHECKED_CAST")
            val entry = raw as? Map<String, Any> ?: return@forEach
            val world = entry["world"] as? String ?: return@forEach
            val x = (entry["x"] as? Number)?.toInt() ?: return@forEach
            val y = (entry["y"] as? Number)?.toInt() ?: return@forEach
            val z = (entry["z"] as? Number)?.toInt() ?: return@forEach
            val name = (entry["name"] as? String)?.trim().orEmpty()
            val lastCollectedBy = linkedMapOf<UUID, Long>()

            @Suppress("UNCHECKED_CAST")
            val rawCollectedBy = entry["lastCollectedBy"] as? Map<String, Any>
            rawCollectedBy?.forEach { (uuidText, value) ->
                val uuid = runCatching { UUID.fromString(uuidText) }.getOrNull() ?: return@forEach
                val timestamp = (value as? Number)?.toLong() ?: return@forEach
                if (timestamp > 0L) {
                    lastCollectedBy[uuid] = timestamp
                }
            }

            val resources = linkedMapOf<Material, Int>()

            @Suppress("UNCHECKED_CAST")
            val rawResources = entry["resources"] as? Map<String, Any>
            rawResources?.forEach { (materialName, amountValue) ->
                val material = Material.matchMaterial(materialName)
                val amount = (amountValue as? Number)?.toInt() ?: return@forEach
                if (material != null && material != Material.AIR && amount > 0) {
                    resources[material] = amount
                }
            }

            val key = LocationKey(world, x, y, z)
            synchronized(lock) {
                outminers[key] = MiningOutminer(
                    key = key,
                    name = name.ifEmpty { defaultNameForKey(key) },
                    lastCollectedBy = lastCollectedBy,
                    resources = resources,
                )
            }
            setDisplayBlock(key)
            upsertHologram(key)
        }

        plugin.logger.info("[MiningOutminer] Loaded ${outminers.size} mining outposts.")
    }

    fun save() {
        val snapshot = synchronized(lock) {
            saveDirty = false
            snapshotStateLocked()
        }
        writeSnapshot(snapshot)
    }

    fun getCount(): Int = outminers.size

    fun placeAt(block: Block, name: String?): String? {
        val key = LocationKey.from(block) ?: return "Block location is invalid"
        synchronized(lock) {
            if (outminers.containsKey(key)) {
                return "A Mining Outpost already exists at ${placementSummary(block)}"
            }
        }
        if (!block.type.isAir) {
            return "Target block must be empty"
        }

        synchronized(lock) {
            outminers[key] = MiningOutminer(
                key = key,
                name = name?.trim().orEmpty().ifEmpty { defaultNameForKey(key) },
            )
        }
        setDisplayBlock(key)
        upsertHologram(key)
        queueSave()
        return null
    }

    fun placementSummary(block: Block): String {
        val world = block.world?.name ?: "<unknown>"
        return "$world (${block.x}, ${block.y}, ${block.z})"
    }

    fun removeAt(block: Block): Boolean = LocationKey.from(block)?.let { removeByKey(it) } ?: false

    fun getRewardsAt(block: Block): Map<Material, Int>? = synchronized(lock) {
        getOutminer(block)?.resources?.toMap()
    }

    fun setResourceAt(block: Block, material: Material, amount: Int): String? {
        synchronized(lock) {
            val miner = getOutminer(block) ?: return "No Mining Outpost found on the targeted block"
            if (amount <= 0) {
                miner.resources.remove(material)
            } else {
                miner.resources[material] = amount
            }
        }
        queueSave()
        return null
    }

    fun listOutminers(): List<MiningOutminerSummary> {
        val snapshot = synchronized(lock) {
            outminers.values.toList()
        }
        return snapshot
            .sortedWith(compareBy<MiningOutminer>({ it.name.lowercase() }, { it.key.world }, { it.key.x }, { it.key.y }, { it.key.z }))
            .mapIndexed { index, miner ->
                val remainingMs = remainingCooldownMs(miner)
                MiningOutminerSummary(
                    index = index + 1,
                    name = miner.name,
                    world = miner.key.world,
                    x = miner.key.x,
                    y = miner.key.y,
                    z = miner.key.z,
                    resourceKinds = miner.resources.size,
                    totalItems = miner.resources.values.sum(),
                    ready = remainingMs <= 0L,
                    cooldownRemainingMs = remainingMs,
                )
            }
    }

    fun getSummaryAtIndex(index: Int): MiningOutminerSummary? = listOutminers().firstOrNull { it.index == index }

    fun removeAtIndex(index: Int): MiningOutminerSummary? {
        val summary = getSummaryAtIndex(index) ?: return null
        val key = LocationKey(summary.world, summary.x, summary.y, summary.z)
        if (!removeByKey(key)) {
            return null
        }
        return summary
    }

    fun isTownSpawnBlocked(location: Location): Boolean {
        val source = LocationKey.from(location) ?: return false
        return outminers.keys.any { it.world == source.world && it.squaredDistance(source) <= SPAWN_BLOCK_RADIUS_SQ }
    }

    fun isOutminerBlock(block: Block): Boolean = synchronized(lock) {
        getOutminer(block) != null
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        var enabled: Boolean = true

        if (event.block.type != DISPLAY_BLOCK) {
            return
        }

        val rewards = synchronized(lock) {
            val miner = getOutminer(event.block) ?: return@synchronized null
            val remainingMs = remainingCooldownMs(miner, event.player.uniqueId)
            if (remainingMs > 0L) {
                return@synchronized Pair(remainingMs, null)
            }

            miner.lastCollectedBy[event.player.uniqueId] = System.currentTimeMillis()
            Pair(0L, miner.resources.toList())
        } ?: return

        event.isCancelled = true
        event.isDropItems = false
        val remainingMs = rewards.first
        if (remainingMs > 0L) {
            Message.error(event.player, "Mining Outpost cooldown: ${formatDuration(remainingMs)} remaining")
            return
        }

        val player = event.player
        val dropLocation = event.block.location.clone().add(0.5, 0.5, 0.5)
        for ((material, amount) in rewards.second.orEmpty()) {
            var remaining = amount.coerceAtLeast(0)
            val stackSize = material.maxStackSize.coerceAtLeast(1)
            while (remaining > 0) {
                val stackAmount = minOf(stackSize, remaining)
                val overflow = player.inventory.addItem(ItemStack(material, stackAmount))
                overflow.values.forEach { leftover ->
                    event.block.world.dropItemNaturally(dropLocation, leftover)
                }
                remaining -= stackAmount
            }
        }

        queueSave()
        Message.print(player, "Collected Mining Outpost rewards. Next use in ${formatDuration(COOLDOWN_MS)}")
    }

    private fun getOutminer(block: Block): MiningOutminer? = LocationKey.from(block)?.let { outminers[it] }

    private fun removeByKey(key: LocationKey, clearBlock: Boolean = true): Boolean {
        val removed = synchronized(lock) {
            outminers.remove(key)
        } ?: return false
        if (clearBlock) {
            clearDisplayBlock(removed.key)
        }
        removeHologram(removed.key)
        queueSave()
        return true
    }

    private fun setDisplayBlock(key: LocationKey) {
        val block = key.toBlock() ?: return
        if (block.type != DISPLAY_BLOCK) {
            block.type = DISPLAY_BLOCK
        }
    }

    private fun clearDisplayBlock(key: LocationKey) {
        val block = key.toBlock() ?: return
        if (block.type == DISPLAY_BLOCK) {
            block.type = Material.AIR
        }
    }

    fun shutdown() {
        clearManagedHolograms()
    }

    fun regenerateHolograms(): Int {
        clearManagedHolograms()
        val keys = synchronized(lock) {
            outminers.keys.toList()
        }
        keys.forEach { key -> upsertHologram(key) }
        return keys.size
    }

    private fun upsertHologram(key: LocationKey) {
        val block = key.toBlock() ?: return
        removeHologram(key)
        val textComponent = buildHologramComponent()
        block.world.spawn(block.location.clone().add(0.5, 1.5, 0.5), TextDisplay::class.java) { text ->
            text.text(textComponent)
            text.billboard = Display.Billboard.CENTER
            text.isSeeThrough = true
            text.setShadowed(true)
            text.setDefaultBackground(false)
            text.setLineWidth(240)
            text.isGlowing = true
            text.addScoreboardTag(HOLOGRAM_TAG)
        }
    }

    private fun removeHologram(key: LocationKey) {
        val block = key.toBlock() ?: return
        val center = block.location.clone().add(0.5, 1.5, 0.5)
        block.world.getNearbyEntities(center, 0.3, 0.5, 0.3)
            .filterIsInstance<TextDisplay>()
            .filter { it.scoreboardTags.contains(HOLOGRAM_TAG) }
            .forEach { it.remove() }
        block.world.getNearbyEntities(center, 0.5, 0.7, 0.5)
            .filterIsInstance<ArmorStand>()
            .filter { isLegacyOutpostArmorStand(it) }
            .forEach { it.remove() }
    }

    private fun clearManagedHolograms() {
        Bukkit.getWorlds().forEach { world ->
            world.entities
                .filterIsInstance<TextDisplay>()
                .filter { it.scoreboardTags.contains(HOLOGRAM_TAG) }
                .forEach { it.remove() }
            world.entities
                .filterIsInstance<ArmorStand>()
                .filter { isLegacyOutpostArmorStand(it) }
                .forEach { it.remove() }
        }
    }

    private fun buildHologramComponent(): Component {
        val lines = Config.miningOutpostTextLines
            .ifEmpty { listOf("&bmining outpost") }
            .map { ChatColor.translateAlternateColorCodes('&', it) }
        return LegacyComponentSerializer.legacySection().deserialize(lines.joinToString("\n"))
    }

    private fun isLegacyOutpostArmorStand(stand: ArmorStand): Boolean {
        if (stand.scoreboardTags.contains(HOLOGRAM_TAG)) {
            return true
        }
        val raw = stand.customName ?: return false
        val normalized = ChatColor.stripColor(raw)?.trim() ?: return false
        return normalized.equals("mining outpost", ignoreCase = true)
    }

    private fun defaultNameForKey(key: LocationKey): String = "Mining Outpost ${key.x},${key.y},${key.z}"

    private fun remainingCooldownMs(miner: MiningOutminer): Long {
        val lastCollectedAt = miner.lastCollectedBy.values.maxOrNull() ?: 0L
        val nextUseAt = lastCollectedAt + COOLDOWN_MS
        return (nextUseAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun remainingCooldownMs(miner: MiningOutminer, playerId: UUID): Long {
        val lastCollectedAt = miner.lastCollectedBy[playerId] ?: 0L
        val nextUseAt = lastCollectedAt + COOLDOWN_MS
        return (nextUseAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format("%02dh %02dm %02ds", hours, minutes, seconds)
    }

    private fun queueSave() {
        synchronized(lock) {
            saveDirty = true
            if (saveQueued) {
                return
            }
            saveQueued = true
        }

        Bukkit.getAsyncScheduler().runDelayed(plugin, { _ ->
            while (true) {
                val snapshot = synchronized(lock) {
                    if (!saveDirty) {
                        saveQueued = false
                        null
                    } else {
                        saveDirty = false
                        snapshotStateLocked()
                    }
                } ?: break

                writeSnapshot(snapshot)
            }
        }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun snapshotStateLocked(): List<Map<String, Any>> = outminers.values.map { miner ->
        mapOf(
            "world" to miner.key.world,
            "x" to miner.key.x,
            "y" to miner.key.y,
            "z" to miner.key.z,
            "name" to miner.name,
            "lastCollectedBy" to miner.lastCollectedBy.entries.associate { (uuid, timestamp) -> uuid.toString() to timestamp },
            "resources" to miner.resources.entries.associate { (material, amount) -> material.name to amount },
        )
    }

    private fun writeSnapshot(snapshot: List<Map<String, Any>>) {
        dataFile.parentFile?.mkdirs()
        val config = YamlConfiguration()
        config.set("outminers", snapshot)
        config.save(dataFile)
    }
}

data class MiningOutminerSummary(
    val index: Int,
    val name: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val resourceKinds: Int,
    val totalItems: Int,
    val ready: Boolean,
    val cooldownRemainingMs: Long,
)

private data class MiningOutminer(
    val key: LocationKey,
    var name: String,
    val lastCollectedBy: MutableMap<UUID, Long> = linkedMapOf(),
    val resources: MutableMap<Material, Int> = linkedMapOf(),
)

private data class LocationKey(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun squaredDistance(other: LocationKey): Int {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    fun toBlock(): Block? {
        val bukkitWorld = Bukkit.getWorld(world) ?: return null
        return bukkitWorld.getBlockAt(x, y, z)
    }

    companion object {
        fun from(block: Block): LocationKey? {
            val worldName = block.world?.name ?: return null
            return LocationKey(worldName, block.x, block.y, block.z)
        }

        fun from(location: Location): LocationKey? {
            val worldName = location.world?.name ?: return null
            return LocationKey(worldName, location.blockX, location.blockY, location.blockZ)
        }
    }
}
