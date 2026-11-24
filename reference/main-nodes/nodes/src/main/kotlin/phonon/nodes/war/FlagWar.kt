/**
 * Flag war implementation conceptually based on
 * Towny flag war:
 * https://github.com/TownyAdvanced/Towny/tree/master/src/com/palmergames/bukkit/towny/war/flagwar
 *
 * War handled by placing a "flag" block onto a chunk to start
 * a "conquer" timer. When timer ends, the chunk is claimed
 * by the attacker's town.
 *
 * When a territory's core is taken, the territory is converted
 * to "occupied" status by the attacking town.
 *
 * Flag block object:
 *     i       <- torch for light (so players can see it)
 *    [ ]      <- wool beacon block (destroy to cancel)
 *     |       <- initial item placed to start claim
 *
 * ----
 * i dont even fully understand save architecture anymore after 6 months
 * hope this doesnt break during war time :^)
 * t. xeth
 */

package phonon.nodes.war

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.ErrorAlreadyCaptured
import phonon.nodes.constants.ErrorAlreadyUnderAttack
import phonon.nodes.constants.ErrorAttackCustomCancel
import phonon.nodes.constants.ErrorChunkNotEdge
import phonon.nodes.constants.ErrorFlagTooHigh
import phonon.nodes.constants.ErrorFlagTooLow
import phonon.nodes.constants.ErrorNotBorderTerritory
import phonon.nodes.constants.ErrorNotEnemy
import phonon.nodes.constants.ErrorSkyBlocked
import phonon.nodes.constants.ErrorTooManyAttacks
import phonon.nodes.constants.ErrorTownBlacklisted
import phonon.nodes.constants.ErrorTownNotWhitelisted
import phonon.nodes.event.WarAttackCancelEvent
import phonon.nodes.event.WarAttackFinishEvent
import phonon.nodes.event.WarAttackStartEvent
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.TerritoryResources
import phonon.nodes.objects.Town
import phonon.nodes.warzone.WarzoneManager
import phonon.nodes.warzone.WarzoneTownRegistry
import java.nio.file.Files
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

public object FlagWar {
    internal const val SESSION_MODE_NORMAL: String = "normal"
    internal const val SESSION_MODE_WARZONE: String = "warzone"

    // ============================================
    // war settings
    // ============================================
    internal var enabled: Boolean = false
    internal var canAnnexTerritories: Boolean = false
    internal var canOnlyAttackBorders: Boolean = false
    internal var destructionEnabled: Boolean = false
    internal var sessionMode: String = SESSION_MODE_NORMAL

    // debug mode — enabled via config war-mode-debug: true
    internal var debugMode: Boolean = false

    // ticks for the save task
    public var saveTaskPeriod: Long = 20
    // ============================================

    internal lateinit var plugin: JavaPlugin

    internal val flagMaterials: EnumSet<Material> = EnumSet.noneOf(Material::class.java)

    // map attacker UUID -> List of Attack instances
    internal val attackers: HashMap<UUID, ArrayList<Attack>> = hashMapOf()

    // map chunk -> Attack instance
    internal val chunkToAttacker: ConcurrentHashMap<Coord, Attack> = ConcurrentHashMap()

    // Skirmish tracking:
    // attacker side key ("nation:<uuid>" or "town:<uuid>") -> defender side keys already capped this session.
    private val skirmishCappedOpponents: ConcurrentHashMap<String, MutableSet<String>> = ConcurrentHashMap()

    // map flag block -> Attack instance (for cancelling attacks)
    internal val blockToAttacker: HashMap<Block, Attack> = hashMapOf()

    // set of all occupied chunks
    internal val occupiedChunks: MutableSet<Coord> = ConcurrentHashMap.newKeySet()

    // attack/flag update tick interval
    internal val ATTACK_TICK: Long = 20

    // flag that save required
    internal var needsSave: Boolean = false

    // periodic task to check for save
    internal var saveTask: ScheduledTask? = null

    // optional global war timer
    internal var warEndMillis: Long = 0L
    internal var warDurationMillis: Long = 0L
    private var warBossBar: BossBar? = null
    private var warBossBarTask: BukkitRunnable? = null

    // -----------------------------------------------------------------------
    // Debug logging helper — only logs when war-mode-debug: true in config
    // -----------------------------------------------------------------------
    internal fun debug(msg: String) {
        if (FlagWar.debugMode) {
            Nodes.logger?.info("[FlagWar DEBUG] $msg")
        }
    }

    public fun initialize(flagMaterials: EnumSet<Material>) {
        FlagWar.flagMaterials.addAll(flagMaterials)
        debug("initialize() — ${flagMaterials.size} flag materials loaded, debugMode=${FlagWar.debugMode}")
    }

    /**
     * Print info to sender about current war state
     */
    public fun printInfo(sender: CommandSender, detailed: Boolean = false) {
        val status = if (Nodes.war.enabled == true) "enabled" else "${ChatColor.GRAY}disabled"
        Message.print(sender, "${ChatColor.BOLD}Nodes war status: $status")
        if (Nodes.war.enabled) {
            Message.print(sender, "- Can Annex Territories${ChatColor.WHITE}: ${Nodes.war.canAnnexTerritories}")
            Message.print(sender, "- Can Only Attack Borders${ChatColor.WHITE}: ${Nodes.war.canOnlyAttackBorders}")
            Message.print(sender, "- Destruction Enabled${ChatColor.WHITE}: ${Nodes.war.destructionEnabled}")
            Message.print(sender, "- Debug Mode${ChatColor.WHITE}: ${FlagWar.debugMode}")
            if (warEndMillis > 0L) {
                val remaining = (warEndMillis - System.currentTimeMillis()).coerceAtLeast(0L)
                Message.print(sender, "- War Timer${ChatColor.WHITE}: ${formatDuration(remaining)} remaining")
            } else {
                Message.print(sender, "- War Timer${ChatColor.WHITE}: ${ChatColor.GRAY}none")
            }
            if (detailed) {
                Message.print(sender, "- Using Towns Whitelist${ChatColor.WHITE}: ${WarAccessListManager.hasWhitelist()}")
                Message.print(sender, "- Can leave town${ChatColor.WHITE}: ${Config.canLeaveTownDuringWar}")
                Message.print(sender, "- Can create town${ChatColor.WHITE}: ${Config.canCreateTownDuringWar}")
                Message.print(sender, "- Can destroy town${ChatColor.WHITE}: ${Config.canDestroyTownDuringWar}")
                Message.print(sender, "- Annex disabled${ChatColor.WHITE}: ${Config.annexDisabled}")
            }
        }
    }

    internal fun hasPersistentState(): Boolean = FlagWar.enabled || FlagWar.chunkToAttacker.isNotEmpty() || FlagWar.occupiedChunks.isNotEmpty()

    private fun ensureSaveTaskRunning() {
        if (FlagWar.saveTask != null) {
            return
        }
        val pluginRef = Nodes.plugin ?: return
        FlagWar.saveTask = Bukkit.getAsyncScheduler().runAtFixedRate(
            pluginRef,
            { _ -> FlagWar.SaveLoop.run() },
            FlagWar.saveTaskPeriod * 50L,
            FlagWar.saveTaskPeriod * 50L,
            TimeUnit.MILLISECONDS,
        )
        debug("ensureSaveTaskRunning() — save task started, period=${FlagWar.saveTaskPeriod} ticks")
    }

    private fun markNeedsSave() {
        FlagWar.needsSave = true
        ensureSaveTaskRunning()
    }

    public fun cleanupAttackHolograms(): Int {
        var removed = 0

        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if ((entity.type == EntityType.TEXT_DISPLAY || entity.type == EntityType.ARMOR_STAND) &&
                    (
                        entity.persistentDataContainer.has(NODES_WAR_DISPLAY_KEY, PersistentDataType.INTEGER) ||
                            entity.persistentDataContainer.has(NODES_ARMORSTAND_KEY, PersistentDataType.INTEGER)
                        )
                ) {
                    entity.remove()
                    removed += 1
                }
            }
        }

        for (attack in chunkToAttacker.values) {
            attack.textDisplay.respawn()
        }

        debug("cleanupAttackHolograms() — removed=$removed stale entities, respawned=${chunkToAttacker.size} active displays")
        return removed
    }

    /**
     * Async save loop task
     */
    internal object SaveLoop : Runnable {
        public override fun run() {
            if (FlagWar.needsSave) {
                FlagWar.needsSave = false
                FlagWar.debug("SaveLoop.run() — saving war state to disk")
                WarSerializer.save(true)
            }
        }
    }

    /**
     * Load war state from .json file
     */
    internal fun load() {
        debug("load() — clearing all war state maps")
        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()

        if (Files.exists(Config.pathWar)) {
            debug("load() — war.json found at ${Config.pathWar}, deserializing")
            WarDeserializer.fromJson(Config.pathWar)
        } else {
            debug("load() — no war.json found, starting fresh")
        }

        debug("load() — after load: attacks=${FlagWar.chunkToAttacker.size}, occupiedChunks=${FlagWar.occupiedChunks.size}, enabled=${FlagWar.enabled}, sessionMode=${FlagWar.sessionMode}")

        if (FlagWar.enabled) {
            if (FlagWar.sessionMode == SESSION_MODE_WARZONE) {
                debug("load() — sessionMode=warzone, skipping global broadcast")
                return
            }
            if (FlagWar.canOnlyAttackBorders) {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishing enabled")
            } else {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")
            }
        }
    }

    /**
     * Load an occupied chunk from json
     */
    internal fun loadOccupiedChunk(townName: String, coord: Coord) {
        debug("loadOccupiedChunk() — townName=$townName coord=(${coord.x},${coord.z})")
        val town = Nodes.towns.get(townName)
        if (town == null) {
            debug("loadOccupiedChunk() — SKIP: town '$townName' not found in world")
            return
        }
        val terrChunk = Nodes.getTerritoryChunkFromCoord(coord)
        if (terrChunk == null) {
            debug("loadOccupiedChunk() — SKIP: no TerritoryChunk at (${coord.x},${coord.z})")
            return
        }
        terrChunk.occupier = town
        FlagWar.occupiedChunks.add(terrChunk.coord)
        debug("loadOccupiedChunk() — OK: chunk (${coord.x},${coord.z}) marked occupied by ${town.name}")
    }

    // load an in-progress attack from json
    internal fun loadAttack(
        attacker: UUID,
        coord: Coord,
        flagBase: Block,
        progress: Long,
    ) {
        debug("loadAttack() — attacker=$attacker coord=(${coord.x},${coord.z}) flagBase=(${flagBase.x},${flagBase.y},${flagBase.z}) progress=$progress")

        val attackerResident = Nodes.getResidentFromUUID(attacker)
        if (attackerResident == null) {
            debug("loadAttack() — SKIP: resident not found for UUID $attacker")
            return
        }
        val attackingTown = attackerResident.town
        if (attackingTown == null) {
            debug("loadAttack() — SKIP: resident ${attackerResident.name} has no town")
            return
        }
        val chunk = Nodes.getTerritoryChunkFromCoord(coord)
        if (chunk == null) {
            debug("loadAttack() — SKIP: no TerritoryChunk at (${coord.x},${coord.z})")
            return
        }

        debug("loadAttack() — restoring attack: town=${attackingTown.name} territory=${chunk.territory.id}")
        FlagWar.createAttack(attacker, attackingTown, chunk, flagBase, progress)
    }

    // cleanup when Nodes plugin disabled
    internal fun cleanup() {
        debug("cleanup() — stopping save task and war timer")
        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = null
        FlagWar.stopWarTimer()

        debug("cleanup() — removing progress bars from ${FlagWar.chunkToAttacker.size} active attacks")
        for (attack in FlagWar.chunkToAttacker.values) {
            attack.progressBar.removeAll()
        }

        if (Nodes.initialized && FlagWar.hasPersistentState()) {
            debug("cleanup() — saving persistent war state before shutdown")
            WarSerializer.save(false)
        }

        FlagWar.enabled = false

        debug("cleanup() — cancelling ${FlagWar.chunkToAttacker.size} active attacks")
        for ((coord, attack) in FlagWar.chunkToAttacker) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.thread.cancel()
            FlagWar.cancelAttack(attack)
        }

        debug("cleanup() — clearing ${FlagWar.occupiedChunks.size} occupied chunks")
        for (coord in FlagWar.occupiedChunks) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()
        FlagWar.skirmishCappedOpponents.clear()
        debug("cleanup() — done, all maps cleared")
    }

    /**
     * Enable war, set war state flags
     */
    internal fun enable(
        canAnnexTerritories: Boolean,
        canOnlyAttackBorders: Boolean,
        destructionEnabled: Boolean,
        durationMillis: Long = 0L,
        sessionMode: String = SESSION_MODE_NORMAL,
    ) {
        debug("enable() — canAnnex=$canAnnexTerritories canOnlyBorders=$canOnlyAttackBorders destruction=$destructionEnabled durationMillis=$durationMillis sessionMode=$sessionMode")
        FlagWar.enabled = true
        FlagWar.canAnnexTerritories = canAnnexTerritories
        FlagWar.canOnlyAttackBorders = canOnlyAttackBorders
        FlagWar.destructionEnabled = destructionEnabled
        FlagWar.sessionMode = if (sessionMode == SESSION_MODE_WARZONE) SESSION_MODE_WARZONE else SESSION_MODE_NORMAL
        FlagWar.skirmishCappedOpponents.clear()

        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = null
        ensureSaveTaskRunning()

        FlagWar.startWarTimer(durationMillis)
        debug("enable() — war enabled successfully")
    }

    /**
     * Disable war, cleanup war state
     */
    internal fun disable() {
        debug("disable() — disabling war and clearing all state")
        FlagWar.enabled = false
        FlagWar.canAnnexTerritories = false
        FlagWar.canOnlyAttackBorders = false
        FlagWar.destructionEnabled = false
        FlagWar.sessionMode = SESSION_MODE_NORMAL
        FlagWar.stopWarTimer()

        // FIX Bug 4: clear skirmish state on disable so stale cap records
        // don't persist into the next war session.
        FlagWar.skirmishCappedOpponents.clear()

        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = null

        debug("disable() — cancelling ${FlagWar.chunkToAttacker.size} active attacks")
        for ((coord, attack) in FlagWar.chunkToAttacker) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.thread.cancel()
            FlagWar.cancelAttack(attack)
        }

        debug("disable() — clearing ${FlagWar.occupiedChunks.size} occupied chunks")
        for (coord in FlagWar.occupiedChunks) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()

        WarSerializer.save(true)
        debug("disable() — done, war.json saved empty")
    }

    // initiate attack on a territory chunk:
    // 1. check chunk is valid target, flag placement valid, and player can attack
    // 2. create and run attack timer thread
    internal fun beginAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: Block): Result<Attack> {
        val world = flagBase.getWorld()
        val flagBaseX = flagBase.x
        val flagBaseY = flagBase.y
        val flagBaseZ = flagBase.z
        val territory = chunk.territory
        val territoryTown = territory.town
        val activeWarzone = WarzoneManager.findActiveWarzoneForTerritoryId(territory.id)

        debug("beginAttack() — attacker=$attacker attackingTown=${attackingTown.name} chunk=(${chunk.coord.x},${chunk.coord.z}) territory=${territory.id} territoryTown=${territoryTown?.name} warzone=${activeWarzone?.name}")

        if (territoryTown === null) {
            debug("beginAttack() — DENY: territory ${territory.id} has no town (wilderness)")
            return Result.failure(ErrorNotEnemy)
        }
        if (WarAccessListManager.isTownBlacklisted(territoryTown)) {
            debug("beginAttack() — DENY: town ${territoryTown.name} is blacklisted")
            return Result.failure(ErrorTownBlacklisted)
        }

        if (WarAccessListManager.hasWhitelist()) {
            if (!WarAccessListManager.isTownWhitelisted(territoryTown) || (Config.onlyWhitelistCanClaim && !WarAccessListManager.isTownWhitelisted(attackingTown))) {
                debug("beginAttack() — DENY: whitelist check failed (defender=${territoryTown.name} whitelisted=${WarAccessListManager.isTownWhitelisted(territoryTown)}, attacker=${attackingTown.name} whitelisted=${WarAccessListManager.isTownWhitelisted(attackingTown)})")
                return Result.failure(ErrorTownNotWhitelisted)
            }
        }

        if (chunk.attacker !== null) {
            debug("beginAttack() — DENY: chunk (${chunk.coord.x},${chunk.coord.z}) already under attack by ${chunk.attacker?.name}")
            return Result.failure(ErrorAlreadyUnderAttack)
        }

        val alreadyCaptured = chunkAlreadyCaptured(chunk, territory, attackingTown)
        if (alreadyCaptured) {
            debug("beginAttack() — DENY: chunkAlreadyCaptured=true for ${attackingTown.name} on (${chunk.coord.x},${chunk.coord.z})")
            return Result.failure(ErrorAlreadyCaptured)
        }

        val isEnemy = chunkIsEnemy(chunk, territory, attackingTown)
        if (isEnemy) {
            if (activeWarzone == null) {
                if (FlagWar.canOnlyAttackBorders) {
                    val territoryOwner = territory.town
                    if (territoryOwner != null && territoryOwner.home == territory.id) {
                        debug("beginAttack() — DENY: skirmish mode, cannot cap home territory of ${territoryOwner.name}")
                        return Result.failure(Exception("[War] In skirmish, you cannot capture a town's main node (home territory)."))
                    }

                    val attackerSideKey = skirmishSideKey(attackingTown)
                    val defenderTown = territoryOwner
                    if (defenderTown != null) {
                        val defenderSideKey = skirmishSideKey(defenderTown)
                        debug("beginAttack() — skirmish check: attackerKey=$attackerSideKey defenderKey=$defenderSideKey previouslyCapped=${FlagWar.skirmishCappedOpponents[attackerSideKey]?.contains(defenderSideKey)}")
                        if (FlagWar.skirmishCappedOpponents[attackerSideKey]?.contains(defenderSideKey) == true) {
                            val defenderLabel = skirmishSideLabel(defenderTown)
                            debug("beginAttack() — DENY: already capped a node from $defenderLabel this skirmish session")
                            return Result.failure(
                                Exception("[War] You already capped a node from $defenderLabel; you cannot attack more nodes from them in this skirmish."),
                            )
                        }

                        for (existingAttack in FlagWar.chunkToAttacker.values) {
                            if (skirmishSideKey(existingAttack.town) != attackerSideKey) continue
                            val existingTerritory = Nodes.getTerritoryFromCoord(existingAttack.coord) ?: continue
                            val existingDefenderTown = existingTerritory.town ?: continue
                            val existingDefenderSideKey = skirmishSideKey(existingDefenderTown)
                            if (existingDefenderSideKey == defenderSideKey && existingTerritory.id != territory.id) {
                                val defenderLabel = skirmishSideLabel(defenderTown)
                                debug("beginAttack() — DENY: already attacking $defenderLabel at node ${existingTerritory.id} (${existingAttack.coord.x},${existingAttack.coord.z})")
                                return Result.failure(
                                    Exception("[War] You are already attacking $defenderLabel at node ${existingTerritory.id} (${existingAttack.coord.x}, ${existingAttack.coord.z})."),
                                )
                            }
                        }
                    }
                }
                if (FlagWar.canOnlyAttackBorders && !FlagWar.isBorderTerritory(territory)) {
                    debug("beginAttack() — DENY: territory ${territory.id} is not a border territory (canOnlyAttackBorders=true)")
                    return Result.failure(ErrorNotBorderTerritory)
                }
            }

            val isEdge = if (activeWarzone == null) {
                FlagWar.chunkIsAtEdge(chunk, attackingTown)
            } else {
                FlagWar.chunkIsAtEdgeWarzone(chunk, attackingTown)
            }
            debug("beginAttack() — chunkIsAtEdge=$isEdge (warzone=${activeWarzone?.name})")
            if (!isEdge) {
                debug("beginAttack() — DENY: chunk (${chunk.coord.x},${chunk.coord.z}) is not at an attackable edge")
                return Result.failure(ErrorChunkNotEdge)
            }

            if (flagBaseY < Config.flagPlacementMinHeight) {
                debug("beginAttack() — DENY: flag y=$flagBaseY below min=${Config.flagPlacementMinHeight}")
                return Result.failure(ErrorFlagTooLow)
            }
            val maxAllowedBase = min(Config.flagPlacementMaxHeight, 252)
            if (flagBaseY > maxAllowedBase) {
                debug("beginAttack() — DENY: flag y=$flagBaseY above max=$maxAllowedBase")
                return Result.failure(ErrorFlagTooHigh)
            }

            for (y in flagBaseY + 1..255) {
                if (!world.getBlockAt(flagBaseX, y, flagBaseZ).isEmpty()) {
                    debug("beginAttack() — DENY: sky blocked at ($flagBaseX,$y,$flagBaseZ)")
                    return Result.failure(ErrorSkyBlocked)
                }
            }

            val maxConcurrentAttacks = Config.maxPlayerChunkAttacks.coerceAtLeast(1)
            var currentAttacks = FlagWar.attackers.get(attacker)
            if (currentAttacks == null) {
                currentAttacks = ArrayList(maxConcurrentAttacks)
                FlagWar.attackers.put(attacker, currentAttacks)
            } else if (currentAttacks.size >= maxConcurrentAttacks) {
                debug("beginAttack() — DENY: attacker $attacker at ${currentAttacks.size}/$maxConcurrentAttacks concurrent attack limit")
                return Result.failure(ErrorTooManyAttacks)
            }

            val event = WarAttackStartEvent(attacker, attackingTown, territory, flagBase)
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled()) {
                debug("beginAttack() — DENY: WarAttackStartEvent cancelled by external plugin")
                return Result.failure(ErrorAttackCustomCancel)
            }

            debug("beginAttack() — all checks passed, creating attack on (${chunk.coord.x},${chunk.coord.z}) for ${attackingTown.name}")
            val attack = createAttack(attacker, attackingTown, chunk, flagBase, 0L)
            FlagWar.markNeedsSave()
            return Result.success(attack)
        } else {
            debug("beginAttack() — DENY: chunkIsEnemy=false for ${attackingTown.name} on (${chunk.coord.x},${chunk.coord.z}), territory.town=${territoryTown.name} territory.occupier=${territory.occupier?.name}")
            return Result.failure(ErrorNotEnemy)
        }
    }

    public fun computeChunkAttackTimeTicks(chunk: TerritoryChunk, attackingTown: Town, logClamp: Boolean = false): Long {
        val territory = chunk.territory
        var attackTime = Config.chunkAttackTime.toDouble()
        if (territory.bordersWilderness) {
            attackTime *= Config.chunkAttackFromWastelandMultiplier
        }

        val terrTown = territory.town
        val (attackerMultiplier, defenderMultiplier) = computeLiveTerritoryTimeMultipliers(territory)
        if (terrTown !== null) {
            if (territory.id == terrTown.home) {
                if (Config.chunkAttackHomeTime > 0L) {
                    attackTime = Config.chunkAttackHomeTime.toDouble()
                } else {
                    attackTime *= Config.chunkAttackHomeMultiplier
                }
            }
            if (terrTown.uuid == attackingTown.uuid || terrTown.allies.contains(attackingTown)) {
                if (defenderMultiplier > 0.0) attackTime *= defenderMultiplier // guard
            } else {
                if (attackerMultiplier > 0.0) attackTime *= attackerMultiplier // guard
            }
        }

        val computedAttackTime = attackTime.toLong()
        val safeAttackTime = computedAttackTime.coerceAtLeast(1L)
        if (logClamp && safeAttackTime != computedAttackTime) {
            Nodes.logger?.warning("[War] Computed non-positive chunk attack time ($computedAttackTime) for ${chunk.coord}; clamping to 1 tick.")
        }
        debug("computeChunkAttackTimeTicks() — chunk=(${chunk.coord.x},${chunk.coord.z}) base=${Config.chunkAttackTime} bordersWilderness=${territory.bordersWilderness} attackerMult=$attackerMultiplier defenderMult=$defenderMultiplier final=$safeAttackTime ticks")
        return safeAttackTime
    }

    private fun computeLiveTerritoryTimeMultipliers(territory: Territory): Pair<Double, Double> {
        fun compileResourcesFor(current: Territory): TerritoryResources {
            val baseResources = current.resourceNodes.mapNotNull { name -> Nodes.resourceNodes[name] }
            val nodeTypeResources = current.nodeTypes.mapNotNull { type -> Config.nodeTypes[type] }
            val resources = (baseResources + nodeTypeResources).sortedBy { r -> r.priority }
            return resources.fold(TerritoryResources()) { tr, r -> r.apply(tr) }
        }

        val ownResources = compileResourcesFor(territory)

        var maxNeighborAttackerTimeMultiplier = 1.0
        for (neighborId in territory.neighbors) {
            val neighbor = Nodes.territories[neighborId] ?: continue
            val neighborResources = compileResourcesFor(neighbor)
            val neighborMultiplier = neighborResources.neighborAttackerTimeMultiplier
            if (neighborMultiplier != null && neighborMultiplier > maxNeighborAttackerTimeMultiplier) {
                maxNeighborAttackerTimeMultiplier = neighborMultiplier
            }
        }

        val attacker = (ownResources.attackerTimeMultiplier ?: 1.0) * maxNeighborAttackerTimeMultiplier
        val defender = ownResources.defenderTimeMultiplier ?: 1.0
        return Pair(attacker, defender)
    }

    // actually creturn resources.fold(Config.globalResources.copy()) { tr, r -> r.apply(tr) }reates attack instance
    internal fun createAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: Block,
        progress: Long,
    ): Attack {
        val world = flagBase.world
        val flagBaseX = flagBase.x
        val flagBaseY = flagBase.y
        val flagBaseZ = flagBase.z
        val territory = chunk.territory

        debug("createAttack() — attacker=$attacker town=${attackingTown.name} coord=(${chunk.coord.x},${chunk.coord.z}) flagBase=($flagBaseX,$flagBaseY,$flagBaseZ) progress=$progress")

        val flagBlock = world.getBlockAt(flagBaseX, flagBaseY + 1, flagBaseZ)
        val flagTorch = world.getBlockAt(flagBaseX, flagBaseY + 2, flagBaseZ)
        val progressBar = Bukkit.getServer().createBossBar("", BarColor.YELLOW, BarStyle.SOLID)

        val safeAttackTime = computeChunkAttackTimeTicks(chunk, attackingTown, logClamp = true)

        if (!Config.flagMaterials.contains(flagBase.type)) {
            flagBase.setType(Config.flagMaterialDefault)
        }
        flagBlock.setType(Config.flagTopBlockMaterial)
        flagTorch.setType(Material.TORCH)

        debug("createAttack() — attackTime=$safeAttackTime ticks, placing flag at y=$flagBaseY/${flagBaseY + 1}/${flagBaseY + 2}")

        val attack = Attack(
            attacker,
            attackingTown,
            chunk.coord,
            flagBase,
            flagBlock,
            flagTorch,
            progressBar,
            safeAttackTime,
            progress,
        )
        progressBar.setTitle(buildAttackBossBarTitle(territory, flagBaseX, flagBaseY, flagBaseZ, attack.attackTime, attack.progress))

        chunk.attacker = attackingTown

        val player = Bukkit.getPlayer(attacker)
        if (player != null) {
            attack.progressBar.addPlayer(player)
            debug("createAttack() — progress bar sent to online player ${player.name}")
        } else {
            debug("createAttack() — attacker $attacker offline, progress bar deferred until rejoin")
        }

        var currentAttacks = FlagWar.attackers.get(attacker)
        if (currentAttacks == null) {
            currentAttacks = ArrayList(Config.maxPlayerChunkAttacks)
            FlagWar.attackers.put(attacker, currentAttacks)
        }
        currentAttacks.add(attack)

        FlagWar.chunkToAttacker.put(chunk.coord, attack)
        FlagWar.blockToAttacker.put(flagBlock, attack)

        debug("createAttack() — attack registered. Total active attacks: ${FlagWar.chunkToAttacker.size}")
        return attack
    }

    // check if territory is a border territory of a town
    internal fun isBorderTerritory(territory: Territory): Boolean {
        val territoryTown = territory.town
        if (territoryTown !== null && territoryTown.home == territory.id) {
            debug("isBorderTerritory(${territory.id}) — FALSE: this is ${territoryTown.name}'s home territory")
            return false
        }
        if (territory.bordersWilderness) {
            debug("isBorderTerritory(${territory.id}) — TRUE: borders wilderness")
            return true
        }
        for (neighborTerritoryId in territory.neighbors) {
            val neighborTerritory = Nodes.territories[neighborTerritoryId]
            if (neighborTerritory !== null && neighborTerritory.town !== territoryTown) {
                debug("isBorderTerritory(${territory.id}) — TRUE: neighbor $neighborTerritoryId owned by ${neighborTerritory.town?.name}")
                return true
            }
        }
        debug("isBorderTerritory(${territory.id}) — FALSE: fully surrounded by own town")
        return false
    }

    // check if chunk was already captured by attacking town or allies
    // FIX: use chunkIsEnemy to properly handle nation-level enemy relationships,
    // so a friendly-occupied territory with an enemy chunk occupier is not
    // incorrectly blocked as "already captured".
    internal fun chunkAlreadyCaptured(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        val territoryOccupier = territory.occupier
        val chunkOccupier = chunk.occupier

        debug("chunkAlreadyCaptured() — chunk=(${chunk.coord.x},${chunk.coord.z}) territoryOccupier=${territoryOccupier?.name} chunkOccupier=${chunkOccupier?.name} attackingTown=${attackingTown.name}")

        if (territoryOccupier === attackingTown || attackingTown.allies.contains(territoryOccupier)) {
            if (chunkOccupier === null || !chunkIsEnemy(chunk, territory, attackingTown)) {
                debug("chunkAlreadyCaptured() — TRUE: territory occupied by friendly and chunk has no enemy occupier")
                return true
            }
            debug("chunkAlreadyCaptured() — territory occupied by friendly BUT chunk has enemy occupier — continuing to isEnemy check")
        }

        if (chunkOccupier !== null) {
            if (chunkOccupier === attackingTown || attackingTown.allies.contains(chunkOccupier)) {
                debug("chunkAlreadyCaptured() — TRUE: chunk directly occupied by attacker or ally (${chunkOccupier.name})")
                return true
            }
            val attackingNation = attackingTown.nation
            if (attackingNation !== null && attackingNation === chunkOccupier.nation) {
                debug("chunkAlreadyCaptured() — TRUE: chunk occupied by same nation as attacker (${chunkOccupier.name} nation=${attackingNation.name})")
                return true
            }
        }

        debug("chunkAlreadyCaptured() — FALSE")
        return false
    }

    // check chunk belongs to an enemy and can be attacked
    // FIX: use nation-level enemy check so occupied-node recapture works when
    // the occupier's town is not directly in attackingTown.enemies but their
    // nation is at war.
    internal fun chunkIsEnemy(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        debug("chunkIsEnemy() — chunk=(${chunk.coord.x},${chunk.coord.z}) territory=${territory.id} territory.town=${territory.town?.name} territory.occupier=${territory.occupier?.name} chunk.occupier=${chunk.occupier?.name} attackingTown=${attackingTown.name} attackingNation=${attackingTown.nation?.name}")

        if (WarzoneTownRegistry.isWarzoneTown(territory.town) || WarzoneTownRegistry.isWarzoneTown(territory.occupier)) {
            debug("chunkIsEnemy() — TRUE: warzone town (territory.town=${territory.town?.name} territory.occupier=${territory.occupier?.name})")
            return true
        }
        if (WarzoneManager.findActiveWarzoneForTerritoryId(territory.id) != null) {
            debug("chunkIsEnemy() — TRUE: territory ${territory.id} is inside an active warzone")
            return true
        }

        if (attackingTown.enemies.contains(territory.town)) {
            debug("chunkIsEnemy() — TRUE: territory.town ${territory.town?.name} is a direct enemy of ${attackingTown.name}")
            return true
        }

        val attackingNation = attackingTown.nation
        val territoryNation = territory.town?.nation

        // helper: treat a town as enemy if directly listed OR if any nation-member lists them
        fun isEnemy(target: Town?): Boolean {
            if (target === null) return false
            if (attackingTown.enemies.contains(target)) return true
            if (attackingNation !== null && attackingNation.towns.any { it.enemies.contains(target) }) return true
            return false
        }

        // your town, nation, or ally town chunk occupied by enemy
        if ((territory.town === attackingTown) ||
            (attackingNation !== null && attackingNation === territoryNation) ||
            (attackingTown.allies.contains(territory.town))
        ) {
            if (isEnemy(territory.occupier)) {
                debug("chunkIsEnemy() — TRUE: friendly territory occupied by enemy at territory level (occupier=${territory.occupier?.name})")
                return true
            }
            if (isEnemy(chunk.occupier)) {
                debug("chunkIsEnemy() — TRUE: friendly territory chunk occupied by enemy (chunk.occupier=${chunk.occupier?.name})")
                return true
            }
        }

        // your occupied territory or ally's occupied territory, chunk occupied by enemy
        val occupier = territory.occupier
        val occupierNation = occupier?.nation
        if (occupier === attackingTown ||
            (attackingNation !== null && attackingNation === occupierNation) ||
            attackingTown.allies.contains(occupier)
        ) {
            if (isEnemy(chunk.occupier)) {
                debug("chunkIsEnemy() — TRUE: attacker-occupied territory has enemy chunk occupier (${chunk.occupier?.name})")
                return true
            }
        }

        debug("chunkIsEnemy() — FALSE: no enemy condition matched for ${attackingTown.name} on chunk=(${chunk.coord.x},${chunk.coord.z})")
        return false
    }

    // check that chunk is at edge (next to wilderness or friendly-occupied chunk)
    internal fun chunkIsAtEdge(chunk: TerritoryChunk, attackingTown: Town): Boolean {
        val coord = chunk.coord

        val chunkNorth = Nodes.getTerritoryChunkFromCoord(Coord(coord.x, coord.z - 1))
        val chunkSouth = Nodes.getTerritoryChunkFromCoord(Coord(coord.x, coord.z + 1))
        val chunkWest = Nodes.getTerritoryChunkFromCoord(Coord(coord.x - 1, coord.z))
        val chunkEast = Nodes.getTerritoryChunkFromCoord(Coord(coord.x + 1, coord.z))

        val fromN = canAttackFromNeighborChunk(chunkNorth, attackingTown)
        val fromS = canAttackFromNeighborChunk(chunkSouth, attackingTown)
        val fromW = canAttackFromNeighborChunk(chunkWest, attackingTown)
        val fromE = canAttackFromNeighborChunk(chunkEast, attackingTown)

        debug("chunkIsAtEdge() — chunk=(${coord.x},${coord.z}) N=$fromN(${chunkNorth?.territory?.town?.name}) S=$fromS(${chunkSouth?.territory?.town?.name}) W=$fromW(${chunkWest?.territory?.town?.name}) E=$fromE(${chunkEast?.territory?.town?.name})")
        return fromN || fromS || fromW || fromE
    }

    /**
     * Warzone-specific edge check.
     */
    internal fun chunkIsAtEdgeWarzone(chunk: TerritoryChunk, attackingTown: Town): Boolean {
        val coord = chunk.coord

        val chunkNorth = Nodes.getTerritoryChunkFromCoord(Coord(coord.x, coord.z - 1))
        val chunkSouth = Nodes.getTerritoryChunkFromCoord(Coord(coord.x, coord.z + 1))
        val chunkWest = Nodes.getTerritoryChunkFromCoord(Coord(coord.x - 1, coord.z))
        val chunkEast = Nodes.getTerritoryChunkFromCoord(Coord(coord.x + 1, coord.z))

        val fromN = canAttackFromNeighborChunkWarzone(chunkNorth, attackingTown)
        val fromS = canAttackFromNeighborChunkWarzone(chunkSouth, attackingTown)
        val fromW = canAttackFromNeighborChunkWarzone(chunkWest, attackingTown)
        val fromE = canAttackFromNeighborChunkWarzone(chunkEast, attackingTown)

        debug("chunkIsAtEdgeWarzone() — chunk=(${coord.x},${coord.z}) N=$fromN S=$fromS W=$fromW E=$fromE")
        return fromN || fromS || fromW || fromE
    }

    private fun canAttackFromNeighborChunkWarzone(neighborChunk: TerritoryChunk?, attacker: Town): Boolean {
        if (neighborChunk === null) return true

        val neighborTerritory = neighborChunk.territory
        val neighborTown = neighborTerritory.town

        if (!WarzoneTownRegistry.isWarzoneTown(neighborTown)) return true

        val attackerNation = attacker.nation
        val neighborTerritoryOccupier = neighborTerritory.occupier
        val neighborChunkOccupier = neighborChunk.occupier

        if (neighborTerritoryOccupier === attacker || neighborChunkOccupier === attacker) return true
        if (attacker.allies.contains(neighborTerritoryOccupier) || attacker.allies.contains(neighborChunkOccupier)) return true
        if (attackerNation !== null) {
            if (attackerNation === neighborTerritoryOccupier?.nation || attackerNation === neighborChunkOccupier?.nation) return true
        }

        return false
    }

    /**
     * conditions for attacking a chunk relative to a neighbor chunk
     */
    internal fun canAttackFromNeighborChunk(neighborChunk: TerritoryChunk?, attacker: Town): Boolean {
        if (neighborChunk === null) return true

        val attackerNation = attacker.nation
        val neighborTerritory = neighborChunk.territory
        val neighborTown = neighborTerritory.town
        val neighborTerritoryOccupier = neighborTerritory.occupier
        val neighborChunkOccupier = neighborChunk.occupier

        if (neighborTown === null) return true

        if (neighborTown === attacker) {
            if (neighborTerritoryOccupier === null) {
                return true
            } else if (attacker.allies.contains(neighborTerritoryOccupier)) {
                return true
            }
        }

        if (neighborTerritoryOccupier === attacker || attacker.allies.contains(neighborTerritoryOccupier)) return true
        if (neighborChunkOccupier === attacker || attacker.allies.contains(neighborChunkOccupier) == true) return true

        if (attackerNation !== null) {
            val neighborNation = neighborTown.nation
            val neighborTerritoryOccupierNation = neighborTerritoryOccupier?.nation
            val neighborChunkOccupierNation = neighborChunk.occupier?.nation

            if (neighborTown === attacker && neighborNation === neighborTerritoryOccupierNation) return true

            if (attackerNation === neighborNation) {
                if (neighborTerritoryOccupier === null) {
                    return true
                } else if (attacker.allies.contains(neighborTerritoryOccupier)) {
                    return true
                }
            }

            if (attackerNation === neighborTerritoryOccupierNation) return true
            if (attackerNation === neighborChunkOccupierNation) return true
        }

        return false
    }

    // cleanup attack instance, dispatch signal that attack was cancelled (defended)
    internal fun cancelAttack(attack: Attack) {
        debug("cancelAttack() — coord=(${attack.coord.x},${attack.coord.z}) attacker=${attack.attacker} town=${attack.town.name} progress=${attack.progress}/${attack.attackTime}")

        val chunk = Nodes.getTerritoryChunkFromCoord(attack.coord)
        chunk?.attacker = null

        attack.progressBar.removeAll()
        attack.flagTorch.setType(Material.AIR)
        attack.flagBlock.setType(Material.AIR)
        attack.flagBase.setType(Material.AIR)
        attack.textDisplay.remove()

        FlagWar.attackers.get(attack.attacker)?.remove(attack)
        FlagWar.chunkToAttacker.remove(attack.coord)
        FlagWar.blockToAttacker.remove(attack.flagBlock)

        FlagWar.markNeedsSave()
        Nodes.needsSave = true

        if (chunk !== null) {
            val event = WarAttackCancelEvent(attack.attacker, attack.town, chunk.territory, attack.flagBase)
            Bukkit.getPluginManager().callEvent(event)
        }

        debug("cancelAttack() — done. Remaining active attacks: ${FlagWar.chunkToAttacker.size}")
    }

    /**
     * Finish attack instance and capture chunk.
     * Different results:
     *   1. attacking enemy chunk -> capture chunk
     *   2. attacking enemy home chunk -> capture territory
     *   3. attacking town/ally occupied chunk -> capture chunk
     *   4. attacking town/ally home chunk -> recapture territory
     */
    internal fun finishAttack(attack: Attack) {
        debug("finishAttack() — coord=(${attack.coord.x},${attack.coord.z}) attacker=${attack.attacker} town=${attack.town.name}")

        attack.progressBar.removeAll()
        attack.flagTorch.setType(Material.AIR)
        attack.flagBlock.setType(Material.AIR)
        attack.flagBase.setType(Material.AIR)
        attack.textDisplay.remove()

        FlagWar.attackers.get(attack.attacker)?.remove(attack)
        FlagWar.chunkToAttacker.remove(attack.coord)
        FlagWar.blockToAttacker.remove(attack.flagBlock)

        FlagWar.markNeedsSave()

        val chunk = Nodes.getTerritoryChunkFromCoord(attack.coord)
        if (chunk == null) {
            Nodes.logger?.severe("finishAttack(): TerritoryChunk at ${attack.coord} is null")
            return
        }

        val event = WarAttackFinishEvent(attack.attacker, attack.town, chunk.territory, attack.flagBase)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled()) {
            debug("finishAttack() — WarAttackFinishEvent cancelled by external plugin")
            chunk.attacker = null
            return
        }

        val isWarzoneNotice = WarzoneManager.findActiveWarzoneForTerritoryId(chunk.territory.id) != null
        val isCore = chunk.coord == chunk.territory.core
        debug("finishAttack() — isCore=$isCore territory=${chunk.territory.id} territoryTown=${chunk.territory.town?.name} territoryOccupier=${chunk.territory.occupier?.name} chunkOccupier=${chunk.occupier?.name} isWarzoneNotice=$isWarzoneNotice")

        var needsMinimapUpdate = false

        if (isCore) {
            val territory = chunk.territory
            val territoryTown = territory.town
            val attacker = Nodes.getResidentFromUUID(attack.attacker)
            val attackerTown = attack.town
            val attackerNation = attackerTown.nation

            debug("finishAttack() — CORE CAP: territory=${territory.id} territoryTown=${territoryTown?.name} attackerTown=${attackerTown.name} attackerNation=${attackerNation?.name}")

            // clear all chunk states in this territory
            for (coord in territory.chunks) {
                val territoryChunk = Nodes.getTerritoryChunkFromCoord(coord)
                if (territoryChunk != null) {
                    FlagWar.chunkToAttacker.get(territoryChunk.coord)?.cancel()
                    territoryChunk.attacker = null
                    territoryChunk.occupier = null
                    FlagWar.occupiedChunks.remove(territoryChunk.coord)
                }
            }
            debug("finishAttack() — cleared all chunk states for territory ${territory.id} (${territory.chunks.size} chunks)")

            if (territoryTown === attackerTown ||
                (attackerNation !== null && attackerNation === territoryTown?.nation) ||
                attackerTown.allies.contains(territoryTown)
            ) {
                val occupier = territory.occupier
                debug("finishAttack() — LIBERATION: ${attacker?.name} liberated territory ${territory.id} from ${occupier?.name}")
                Nodes.releaseTerritory(territory)
                Message.broadcastWarNotice(
                    "${ChatColor.DARK_RED}[War] ${attacker?.name} liberated territory (id=${territory.id}) from ${occupier?.name}!",
                    relatedTowns = listOf(attackerTown, territoryTown, occupier),
                    isWarzone = isWarzoneNotice,
                    territoryCapture = true,
                )
            } else {
                val capturedTown = territoryTown
                debug("finishAttack() — CAPTURE: ${attacker?.name} captured territory ${territory.id} from ${capturedTown?.name}")
                Nodes.captureTerritory(attackerTown, territory)

                if (FlagWar.canOnlyAttackBorders && !isWarzoneNotice && capturedTown != null) {
                    val attackerKey = skirmishSideKey(attackerTown)
                    val defenderKey = skirmishSideKey(capturedTown)
                    val set = FlagWar.skirmishCappedOpponents.computeIfAbsent(attackerKey) { ConcurrentHashMap.newKeySet<String>() }
                    set.add(defenderKey)
                    // FIX Bug 2: record cap in both directions for symmetric skirmish limit
                    val inverseSet = FlagWar.skirmishCappedOpponents.computeIfAbsent(defenderKey) { ConcurrentHashMap.newKeySet<String>() }
                    inverseSet.add(attackerKey)
                    debug("finishAttack() — skirmish cap recorded both directions: $attackerKey <-> $defenderKey")
                }

                Message.broadcastWarNotice(
                    "${ChatColor.DARK_RED}[War] ${attacker?.name} captured territory (id=${territory.id}) from ${capturedTown?.name}!",
                    relatedTowns = listOf(attackerTown, capturedTown),
                    isWarzone = isWarzoneNotice,
                    territoryCapture = true,
                )

                val capturedBy = attackerNation?.name ?: attackerTown.name
                if (capturedTown !== null) {
                    val isMainNodeCap = !WarzoneTownRegistry.isWarzoneTown(capturedTown) && territory.id == capturedTown.home
                    debug("finishAttack() — isMainNodeCap=$isMainNodeCap capturedTown=${capturedTown.name} capturedTown.home=${capturedTown.home}")
                    if (!isWarzoneNotice && isMainNodeCap) {
                        broadcastTownCappedTitle(capturedTown, attackerTown, capturedBy, isWarzoneNotice)
                    }
                    if (!WarzoneTownRegistry.isWarzoneTown(capturedTown) && territory.id == capturedTown.home) {
                        debug("finishAttack() — HOME NODE CAPPED: annexing entire town ${capturedTown.name}")
                        val annexedCount = Nodes.annexEntireTown(attackerTown, capturedTown)
                        Message.broadcastWarNotice(
                            "${ChatColor.DARK_RED}${ChatColor.BOLD}${capturedTown.name} was instantly annexed by ${attackerTown.name} ($annexedCount territories)",
                            relatedTowns = listOf(attackerTown, capturedTown),
                            isWarzone = isWarzoneNotice,
                            territoryCapture = true,
                        )
                        debug("finishAttack() — annexed $annexedCount territories from ${capturedTown.name}")
                    }
                }
            }

            needsMinimapUpdate = true
        } else {
            // non-core chunk cases
            val town = chunk.territory.town
            val occupier = chunk.territory.occupier
            val attacker = Nodes.getResidentFromUUID(attack.attacker)

            debug("finishAttack() — NON-CORE: chunk=(${chunk.coord.x},${chunk.coord.z}) town=${town?.name} territoryOccupier=${occupier?.name} chunkOccupier=${chunk.occupier?.name} attackTown=${attack.town.name}")

            chunk.attacker = null

            if (town === attack.town) {
                // FIX Bug 1: re-capturing a chunk from an enemy occupier.
                // Was incorrectly setting chunk.occupier = town (self-occupation).
                // Correct: clear occupier entirely — chunk is liberated.
                if (occupier !== null) {
                    debug("finishAttack() — LIBERATE CHUNK from territory occupier: ${attacker?.name} cleared occupier ${occupier.name} from (${chunk.coord.x},${chunk.coord.z})")
                    chunk.occupier = null
                    FlagWar.occupiedChunks.remove(chunk.coord)
                    Message.broadcastWarNotice(
                        "${ChatColor.DARK_RED}[War] ${attacker?.name} liberated chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${occupier.name}!",
                        relatedTowns = listOf(attack.town, town, occupier),
                        isWarzone = isWarzoneNotice,
                    )
                } else {
                    val chunkOccupier = chunk.occupier
                    debug("finishAttack() — DEFEND CHUNK: ${attacker?.name} cleared chunk occupier ${chunkOccupier?.name} at (${chunk.coord.x},${chunk.coord.z})")
                    chunk.occupier = null
                    FlagWar.occupiedChunks.remove(chunk.coord)
                    if (chunkOccupier !== null) {
                        Message.broadcastWarNotice(
                            "${ChatColor.DARK_RED}[War] ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${chunkOccupier.name}!",
                            relatedTowns = listOf(attack.town, chunkOccupier),
                            isWarzone = isWarzoneNotice,
                        )
                    }
                }
            } else if (occupier === attack.town && chunk.occupier !== null) {
                val defendedAgainst = chunk.occupier
                debug("finishAttack() — DEFEND OCCUPIED CHUNK: ${attacker?.name} cleared ${defendedAgainst?.name} from (${chunk.coord.x},${chunk.coord.z})")
                chunk.occupier = null
                FlagWar.occupiedChunks.remove(chunk.coord)
                Message.broadcastWarNotice(
                    "${ChatColor.DARK_RED}[War] ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${defendedAgainst?.name}!",
                    relatedTowns = listOf(attack.town, defendedAgainst, town),
                    isWarzone = isWarzoneNotice,
                )
            } else {
                val previousOccupier = chunk.occupier
                debug("finishAttack() — CAPTURE CHUNK: ${attacker?.name} set chunk (${chunk.coord.x},${chunk.coord.z}) occupier to ${attack.town.name} (was ${previousOccupier?.name ?: "empty"})")
                chunk.occupier = attack.town
                FlagWar.occupiedChunks.add(chunk.coord)
                Message.broadcastWarNotice(
                    "${ChatColor.DARK_RED}[War] ${attacker?.name} captured chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${chunk.territory.town?.name}!",
                    relatedTowns = listOf(attack.town, chunk.territory.town, previousOccupier),
                    isWarzone = isWarzoneNotice,
                )
            }

            needsMinimapUpdate = true
        }

        if (needsMinimapUpdate) {
            Nodes.renderMinimaps()
        }

        debug("finishAttack() — done. Active attacks: ${FlagWar.chunkToAttacker.size}, occupiedChunks: ${FlagWar.occupiedChunks.size}")
    }

    // update tick for attack instance
    internal fun attackTick(attack: Attack) {
        val progress = attack.progress + FlagWar.ATTACK_TICK

        if (progress >= attack.attackTime) {
            debug("attackTick() — COMPLETE on (${attack.coord.x},${attack.coord.z}) progress=$progress/${attack.attackTime}")
            attack.thread.cancel()
            FlagWar.finishAttack(attack)
        } else {
            attack.progress = progress

            val progressNormalized = normalizedAttackProgress(progress, attack.attackTime)
            attack.progressBar.setProgress(progressNormalized)
            attack.progressBar.setTitle(buildAttackBossBarTitle(attack.coord, attack.flagBase, attack.attackTime, progress))

            if (attack.textDisplay.isValid()) {
                try {
                    attack.textDisplay.sendPackets()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Nodes.logger?.warning("Error updating war flag text display: ${e.message}")
                }
            } else {
                debug("attackTick() — text display invalid on (${attack.coord.x},${attack.coord.z}), respawning")
                attack.textDisplay.respawn()
            }
        }
    }

    private fun startWarTimer(durationMillis: Long) {
        stopWarTimer()

        if (durationMillis <= 0L) {
            warDurationMillis = 0L
            warEndMillis = 0L
            debug("startWarTimer() — durationMillis<=0, no timer set")
            return
        }

        warDurationMillis = durationMillis
        warEndMillis = System.currentTimeMillis() + durationMillis
        debug("startWarTimer() — timer set for ${formatDuration(durationMillis)}, ends at $warEndMillis")

        warBossBar = Bukkit.getServer().createBossBar("", BarColor.RED, BarStyle.SOLID)
        Bukkit.getOnlinePlayers().forEach { warBossBar?.addPlayer(it) }

        updateWarBossBar(force = true)
        warBossBarTask = object : BukkitRunnable() {
            override fun run() {
                if (!FlagWar.enabled) {
                    stopWarTimer()
                    return
                }

                val remaining = (warEndMillis - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remaining <= 0L) {
                    Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}War time ended. War disabled.")
                    Nodes.disableWar()
                    stopWarTimer()
                    return
                }

                updateWarBossBar()
            }
        }
        warBossBarTask?.runTaskTimer(Nodes.plugin!!, 20L, 20L)
    }

    private fun stopWarTimer() {
        warBossBarTask?.cancel()
        warBossBarTask = null
        warBossBar?.removeAll()
        warBossBar = null
        warEndMillis = 0L
        warDurationMillis = 0L
        debug("stopWarTimer() — timer stopped and boss bar removed")
    }

    private fun updateWarBossBar(force: Boolean = false) {
        val bar = warBossBar ?: return
        val remaining = (warEndMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val total = warDurationMillis.coerceAtLeast(1L)
        val progress = (remaining.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        bar.progress = progress

        if (!force && remaining > 9L * 60L * 1000L) {
            val remainingSeconds = (remaining / 1000L).toLong()
            if (remainingSeconds % 60L != 0L) return
        }

        bar.setTitle("${ChatColor.DARK_RED}War ends in ${ChatColor.WHITE}${formatDuration(remaining)}")
    }

    private fun buildAttackBossBarTitle(coord: Coord, flagBase: Block, attackTime: Long, progress: Long): String {
        val territory = Nodes.getTerritoryFromCoord(coord) ?: return ""
        return buildAttackBossBarTitle(territory, flagBase.x, flagBase.y, flagBase.z, attackTime, progress)
    }

    private fun buildAttackBossBarTitle(
        territory: Territory,
        x: Int,
        y: Int,
        z: Int,
        attackTime: Long,
        progress: Long,
    ): String {
        val baseTitle = "Attacking ${territory.town!!.name} at ($x, $y, $z)"
        if (!Config.attackBossBarShowTimeRemaining) {
            return baseTitle
        }
        val remainingTicks = (attackTime - progress).coerceAtLeast(0L)
        val remainingSeconds = (remainingTicks / 20).toInt()
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        val timeString = String.format("%02d:%02d", minutes, seconds)
        return "$baseTitle ($timeString)"
    }

    public fun sendTimedWarBossBarToPlayer(player: Player) {
        warBossBar?.addPlayer(player)
    }

    private fun formatDuration(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (remainingMillis > 9L * 60L * 1000L) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }

    public fun sendWarProgressBarToPlayer(player: Player) {
        val uuid = player.getUniqueId()
        val currentAttacks = FlagWar.attackers.get(uuid)
        if (currentAttacks != null) {
            for (attack in currentAttacks) {
                attack.progressBar.addPlayer(player)
            }
        }
    }

    private fun broadcastTownCappedTitle(capturedTown: Town, attackerTown: Town, nationOrTownName: String, isWarzone: Boolean) {
        val title = "${ChatColor.RED}${capturedTown.name} got capped"
        val subtitle = "${ChatColor.GOLD}by $nationOrTownName"
        Message.territoryCapture(title, subtitle, "$title ${ChatColor.RESET}$subtitle", 10, 70, 20, listOf(capturedTown, attackerTown), isWarzone)
    }

    private fun normalizedAttackProgress(progress: Long, attackTime: Long): Double {
        if (attackTime <= 0L) return 0.0
        val normalized = progress.toDouble() / attackTime.toDouble()
        if (!normalized.isFinite()) return 0.0
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun skirmishSideKey(town: Town): String {
        val nation = town.nation
        return if (nation != null) "nation:${nation.uuid}" else "town:${town.uuid}"
    }

    private fun skirmishSideLabel(town: Town): String {
        val nation = town.nation
        return if (nation != null) "nation ${nation.name}" else "town ${town.name}"
    }
}
