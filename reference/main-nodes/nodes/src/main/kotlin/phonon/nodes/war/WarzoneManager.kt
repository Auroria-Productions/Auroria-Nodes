package phonon.nodes.war

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.tasks.TaskCopyToDynmap
import phonon.nodes.utils.Color
import java.io.File
import java.util.UUID

public data class Warzone(
    val name: String,
    val nodeIds: LinkedHashSet<TerritoryId> = linkedSetOf(),
)

public data class WarzoneControlShare(
    val town: Town,
    val controlledNodes: Int,
)

public data class WarzonePointShare(
    val town: Town,
    val points: Long,
)

public data class WarzoneControlSummary(
    val warzone: Warzone,
    val totalNodes: Int,
    val totalControlledNodes: Int,
    val shares: List<WarzoneControlShare>,
    val leaders: List<WarzoneControlShare>,
) {
    val isContested: Boolean
        get() = shares.size > 1

    val isUncontrolled: Boolean
        get() = totalControlledNodes <= 0 || shares.isEmpty()
}

public object WarzoneManager {
    private const val BOSSBAR_UPDATE_INTERVAL_MS: Long = 3000L
    private const val CONTROL_CACHE_TTL_MS: Long = 3000L

    private val warzones: LinkedHashMap<String, Warzone> = linkedMapOf()
    private val nodeToWarzone: LinkedHashMap<TerritoryId, Warzone> = linkedMapOf()
    private val warzoneTownNames: LinkedHashSet<String> = linkedSetOf()
    private val pendingPointsByTownName: LinkedHashMap<String, Long> = linkedMapOf()
    private val pointsByTown: LinkedHashMap<Town, Long> = linkedMapOf()
    private val activeBossbars: LinkedHashMap<java.util.UUID, BossBar> = linkedMapOf()
    private var cachedControlSummaries: List<WarzoneControlSummary> = emptyList()
    private var controlCacheTime: Long = 0L
    private var cachedPointShares: List<WarzonePointShare> = emptyList()
    private var pointCacheDirty: Boolean = true
    private var enabled: Boolean = false
    private var durationMillis: Long = 0L
    private var endMillis: Long = 0L
    private var bossbarElapsedMs: Long = 0L

    private fun key(name: String): String = name.trim().lowercase()
    private fun backingTownName(warzone: Warzone): String = "_warzone_${warzone.name}"

    private fun dataFile(): File = File(Config.pathWarzones.toString())
    private fun runtimeStateFile(): File = File(Config.pathWarzoneState.toString())

    public fun load() {
        warzones.clear()
        nodeToWarzone.clear()
        warzoneTownNames.clear()
        invalidateCaches()

        val file = dataFile()
        migrateLegacyFile(file)
        if (!file.exists()) {
            createEmptyFile(file)
            return
        }

        val yaml = YamlConfiguration.loadConfiguration(file)

        for (entry in yaml.getMapList("warzones")) {
            val name = (entry["name"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) {
                continue
            }

            val rawNodes = entry["nodes"]
            val nodeIds = linkedSetOf<TerritoryId>()
            when (rawNodes) {
                is List<*> -> {
                    for (raw in rawNodes) {
                        val id = raw?.toString()?.toIntOrNull() ?: continue
                        nodeIds.add(TerritoryId(id))
                    }
                }
            }

            val warzone = Warzone(name, nodeIds)
            warzones[key(name)] = warzone
            warzoneTownNames.add(backingTownName(warzone).lowercase())
            for (nodeId in nodeIds) {
                nodeToWarzone.putIfAbsent(nodeId, warzone)
            }
        }

        syncBackingTowns()
    }

    public fun getWarzoneNames(): List<String> = warzones.values.map { it.name }

    public fun getWarzones(): List<Warzone> = warzones.values.toList()

    public fun isEventActive(): Boolean = enabled

    public fun getRemainingMillis(): Long {
        if (!enabled || endMillis <= 0L) {
            return 0L
        }
        return (endMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    public fun getPointShares(): List<WarzonePointShare> {
        if (pointCacheDirty) {
            cachedPointShares = pointsByTown.entries
                .map { WarzonePointShare(it.key, it.value) }
                .sortedWith(compareByDescending<WarzonePointShare> { it.points }.thenBy { it.town.name })
            pointCacheDirty = false
        }
        return cachedPointShares
    }

    public fun enable(durationMillis: Long): Result<Unit> {
        if (warzones.isEmpty()) {
            return Result.failure(IllegalStateException("No warzones defined"))
        }
        if (nodeToWarzone.isEmpty()) {
            return Result.failure(IllegalStateException("No warzone nodes defined"))
        }

        enabled = true
        this.durationMillis = durationMillis.coerceAtLeast(0L)
        endMillis = if (this.durationMillis > 0L) {
            System.currentTimeMillis() + this.durationMillis
        } else {
            0L
        }
        bossbarElapsedMs = 0L
        pointsByTown.clear()
        invalidateCaches()
        removeAllBossbars()
        saveRuntimeState()

        return Result.success(Unit)
    }

    public fun disable() {
        if (!enabled) {
            removeAllBossbars()
            return
        }

        finalizeOwnership()
        enabled = false
        durationMillis = 0L
        endMillis = 0L
        bossbarElapsedMs = 0L
        invalidateCaches()
        removeAllBossbars()
        saveRuntimeState()
    }

    public fun onWarDisabled() {
        disable()
    }

    public fun isWarzoneTown(town: Town?): Boolean {
        if (town == null) {
            return false
        }
        return warzoneTownNames.contains(town.name.lowercase())
    }

    public fun isWarzoneTerritory(territory: Territory?): Boolean {
        if (territory == null) {
            return false
        }
        return nodeToWarzone.containsKey(territory.id)
    }

    public fun createWarzone(name: String): Result<Warzone> {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            return Result.failure(IllegalArgumentException("Warzone name cannot be empty"))
        }

        val key = key(normalized)
        if (warzones.containsKey(key)) {
            return Result.failure(IllegalArgumentException("Warzone \"$normalized\" already exists"))
        }

        val warzone = Warzone(normalized)
        warzones[key] = warzone
        warzoneTownNames.add(backingTownName(warzone).lowercase())
        invalidateControlCache()
        save()
        return Result.success(warzone)
    }

    public fun addNodes(name: String, nodeIds: Collection<TerritoryId>): Result<Int> {
        val normalized = name.trim()
        val warzone = warzones[key(normalized)]
            ?: return Result.failure(IllegalArgumentException("Warzone \"$normalized\" does not exist"))

        for (nodeId in nodeIds) {
            val existingWarzone = nodeToWarzone[nodeId]
            if (existingWarzone != null && existingWarzone !== warzone) {
                return Result.failure(IllegalArgumentException("Territory ${nodeId.toInt()} already belongs to warzone \"${existingWarzone.name}\""))
            }
            val territory = Nodes.getTerritoryFromId(nodeId)
            if (territory != null) {
                val owner = territory.town
                if (owner != null && owner !== getBackingTown(warzone) && !isWarzoneTown(owner)) {
                    return Result.failure(IllegalArgumentException("Territory ${nodeId.toInt()} is already owned by town \"${owner.name}\""))
                }
            }
        }

        var added = 0
        for (nodeId in nodeIds) {
            if (warzone.nodeIds.add(nodeId)) {
                nodeToWarzone[nodeId] = warzone
                added += 1
            }
        }

        if (added > 0) {
            syncBackingTown(warzone)
            invalidateControlCache()
            save()
        }

        return Result.success(added)
    }

    public fun awardControlPoints() {
        if (!enabled || warzones.isEmpty()) {
            return
        }

        for (warzone in warzones.values) {
            for (nodeId in warzone.nodeIds) {
                val territory = Nodes.getTerritoryFromId(nodeId) ?: continue
                val controller = resolveController(territory) ?: continue
                pointsByTown[controller] = (pointsByTown[controller] ?: 0L) + 1L
            }
        }
        invalidatePointCache()
    }

    public fun loadRuntimeState() {
        pointsByTown.clear()
        pendingPointsByTownName.clear()
        enabled = false
        durationMillis = 0L
        endMillis = 0L
        bossbarElapsedMs = 0L
        removeAllBossbars()

        val file = runtimeStateFile()
        if (!file.exists()) {
            invalidateCaches()
            return
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        val savedEnabled = yaml.getBoolean("enabled", false)
        val savedDurationMillis = yaml.getLong("durationMillis", 0L).coerceAtLeast(0L)
        val savedEndMillis = yaml.getLong("endMillis", 0L).coerceAtLeast(0L)
        val now = System.currentTimeMillis()

        if (savedEnabled) {
            val isExpired = savedEndMillis > 0L && savedEndMillis <= now
            if (!isExpired) {
                enabled = true
                durationMillis = savedDurationMillis
                endMillis = savedEndMillis
            }
        }

        val pointsSection = if (savedEnabled) yaml.getConfigurationSection("points") else null
        if (pointsSection != null) {
            for (townName in pointsSection.getKeys(false)) {
                val points = pointsSection.getLong(townName, 0L)
                if (points > 0L) {
                    pendingPointsByTownName[townName] = points
                }
            }
        }

        for ((townName, points) in pendingPointsByTownName) {
            val town = Nodes.getTownFromName(townName) ?: continue
            pointsByTown[town] = points
        }

        if (!enabled && Nodes.war.enabled && FlagWar.sessionMode == FlagWar.SESSION_MODE_WARZONE) {
            Nodes.disableWar()
        } else if (enabled && !Nodes.war.enabled) {
            val remainingMillis = getRemainingMillis()
            Nodes.enableWar(
                false,
                false,
                Config.allowDestructionDuringSkirmish,
                if (endMillis > 0L) remainingMillis else durationMillis,
                FlagWar.SESSION_MODE_WARZONE,
            )
        }

        invalidateCaches()
        if (!enabled && (savedEnabled || pendingPointsByTownName.isNotEmpty())) {
            saveRuntimeState()
        }
    }

    public fun saveRuntimeState() {
        val file = runtimeStateFile()
        file.parentFile?.mkdirs()

        val yaml = YamlConfiguration()
        yaml.set("enabled", enabled)
        yaml.set("durationMillis", durationMillis)
        yaml.set("endMillis", endMillis)

        val points = linkedMapOf<String, Long>()
        for ((town, value) in pointsByTown) {
            if (enabled && value > 0L) {
                points[town.name] = value
            }
        }
        yaml.createSection("points", points)
        yaml.save(file)
    }

    public fun periodicTick(dt: Long) {
        if (!enabled) {
            if (activeBossbars.isNotEmpty()) {
                removeAllBossbars()
            }
            return
        }

        if (endMillis > 0L && System.currentTimeMillis() >= endMillis) {
            disable()
            if (Nodes.war.enabled) {
                Nodes.disableWar()
            }
            Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}Warzone event ended")
            return
        }

        bossbarElapsedMs += dt
        if (bossbarElapsedMs >= BOSSBAR_UPDATE_INTERVAL_MS) {
            bossbarElapsedMs %= BOSSBAR_UPDATE_INTERVAL_MS
            updateBossbars()
        }
    }

    public fun getControlSummaries(forceRefresh: Boolean = false): List<WarzoneControlSummary> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && controlCacheTime > 0L && (now - controlCacheTime) < CONTROL_CACHE_TTL_MS) {
            return cachedControlSummaries
        }

        cachedControlSummaries = warzones.values.map { warzone ->
            val sharesByTown: LinkedHashMap<Town, Int> = linkedMapOf()

            for (nodeId in warzone.nodeIds) {
                val territory = Nodes.getTerritoryFromId(nodeId) ?: continue
                val controller = resolveController(territory) ?: continue
                sharesByTown[controller] = (sharesByTown[controller] ?: 0) + 1
            }

            val shares = sharesByTown.entries
                .map { WarzoneControlShare(it.key, it.value) }
                .sortedWith(compareByDescending<WarzoneControlShare> { it.controlledNodes }.thenBy { it.town.name })

            val leadingCount = shares.firstOrNull()?.controlledNodes ?: 0
            val leaders = if (leadingCount > 0) {
                shares.filter { it.controlledNodes == leadingCount }
            } else {
                emptyList()
            }

            WarzoneControlSummary(
                warzone = warzone,
                totalNodes = warzone.nodeIds.size,
                totalControlledNodes = shares.sumOf { it.controlledNodes },
                shares = shares,
                leaders = leaders,
            )
        }
        controlCacheTime = now
        return cachedControlSummaries
    }

    public fun save() {
        val file = dataFile()
        file.parentFile?.mkdirs()

        val yaml = YamlConfiguration()
        val list = warzones.values.map { warzone ->
            linkedMapOf(
                "name" to warzone.name,
                "nodes" to warzone.nodeIds.map { it.toInt() },
            )
        }
        yaml.set("warzones", list)
        yaml.save(file)

        if (Nodes.dynmap || Config.dynmapCopyTowns) {
            TaskCopyToDynmap(Config.pathWarzones, Nodes.DYNMAP_DIR, Nodes.DYNMAP_PATH_WARZONES).run()
        }
    }

    public fun syncBackingTowns() {
        if (Nodes.territories.isEmpty()) {
            return
        }

        for (warzone in warzones.values) {
            syncBackingTown(warzone)
        }
        invalidateControlCache()
    }

    private fun createEmptyFile(file: File) {
        try {
            file.parentFile?.mkdirs()
            val yaml = YamlConfiguration()
            yaml.set("warzones", emptyList<Any>())
            yaml.save(file)
        } catch (err: Exception) {
            Nodes.logger?.warning("Failed to create warzones.yml: ${err.message}")
        }
    }

    private fun migrateLegacyFile(target: File) {
        if (target.exists()) {
            return
        }

        val legacy = File(Config.pathPlugin, "warzones.yml")
        if (!legacy.exists()) {
            return
        }

        try {
            target.parentFile?.mkdirs()
            legacy.copyTo(target, overwrite = true)
            legacy.delete()
            Nodes.logger?.info("Migrated legacy warzones.yml to ${target.path}")
        } catch (err: Exception) {
            Nodes.logger?.warning("Failed to migrate legacy warzones.yml: ${err.message}")
        }
    }

    private fun syncBackingTown(warzone: Warzone) {
        if (warzone.nodeIds.isEmpty()) {
            return
        }

        val town = ensureBackingTown(warzone) ?: return
        var changed = false

        for (nodeId in warzone.nodeIds) {
            val territory = Nodes.getTerritoryFromId(nodeId) ?: continue
            val currentOwner = territory.town
            if (currentOwner == null) {
                if (town.territories.add(territory.id) && Config.claimsPowerEnabled) {
                    town.claimsUsed += territory.cost
                }
                territory.town = town
                changed = true
            } else if (currentOwner === town) {
                if (town.territories.add(territory.id) && Config.claimsPowerEnabled) {
                    town.claimsUsed += territory.cost
                    changed = true
                }
            }
        }

        if (changed) {
            town.claimsMax = Nodes.calculateMaxClaims(town)
            town.needsUpdate()
            Nodes.needsSave = true
            invalidateControlCache()
            Nodes.renderMinimaps()
        }
    }

    private fun ensureBackingTown(warzone: Warzone): Town? {
        val existing = getBackingTown(warzone)
        if (existing != null) {
            return existing
        }

        val firstTerritory = warzone.nodeIds
            .asSequence()
            .mapNotNull { Nodes.getTerritoryFromId(it) }
            .firstOrNull()
            ?: return null

        val owner = firstTerritory.town
        if (owner != null && !owner.name.equals(backingTownName(warzone), ignoreCase = true)) {
            Nodes.logger?.warning("Cannot create warzone town for ${warzone.name}: territory ${firstTerritory.id.toInt()} is owned by ${owner.name}")
            return null
        }

        val town = Town(
            UUID.randomUUID(),
            backingTownName(warzone),
            firstTerritory.id,
            null,
            Nodes.getDefaultSpawnLocation(firstTerritory),
        )
        town.color = Color(143, 29, 29)
        town.claimsBonus = 1_000_000
        town.claimsUsed = if (Config.claimsPowerEnabled) firstTerritory.cost else 0
        town.claimsMax = Nodes.calculateMaxClaims(town)

        firstTerritory.town = town
        Nodes.towns[town.name] = town
        Nodes.needsSave = true
        invalidateControlCache()
        Nodes.renderMinimaps()
        return town
    }

    private fun getBackingTown(warzone: Warzone): Town? = Nodes.getTownFromName(backingTownName(warzone))

    public fun getWarzoneForTerritory(territory: Territory?): Warzone? {
        if (territory == null) {
            return null
        }
        return nodeToWarzone[territory.id]
    }

    private fun resolveController(territory: Territory): Town? {
        val occupier = territory.occupier
        if (occupier != null && !isWarzoneTown(occupier)) {
            return occupier
        }

        val owner = territory.town
        if (owner != null && !isWarzoneTown(owner)) {
            return owner
        }

        return null
    }

    public fun getMiningRateMultiplier(territory: Territory?): Double {
        if (!isWarzoneTerritory(territory)) {
            return 1.0
        }
        return 2.0
    }

    private fun finalizeOwnership() {
        val winner = resolveWinningTown()
        if (winner == null) {
            if (pointsByTown.isNotEmpty()) {
                Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}Warzone ended without a clear winner")
            }
            return
        }

        val touchedTowns = linkedSetOf<Town>()
        var changed = false

        for (warzone in warzones.values) {
            for (nodeId in warzone.nodeIds) {
                val territory = Nodes.getTerritoryFromId(nodeId) ?: continue
                if (assignTerritoryToWinner(territory, winner, touchedTowns)) {
                    changed = true
                }
            }
        }

        if (!changed) {
            return
        }

        for (town in touchedTowns) {
            town.claimsMax = Nodes.calculateMaxClaims(town)
            town.isOverClaimsMax = town.claimsUsed > town.claimsMax
            town.needsUpdate()
        }

        Nodes.needsSave = true
        invalidateControlCache()
        Nodes.renderMinimaps()
        Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}${winner.name} won the warzone event")
    }

    private fun resolveWinningTown(): Town? {
        val shares = getPointShares()
        val leader = shares.firstOrNull() ?: return null
        val second = shares.getOrNull(1)
        if (second != null && second.points == leader.points) {
            return null
        }
        return leader.town
    }

    private fun assignTerritoryToWinner(territory: Territory, winner: Town, touchedTowns: MutableSet<Town>): Boolean {
        var changed = false

        territory.occupier?.let { occupier ->
            touchedTowns.add(occupier)
            if (occupier.captured.remove(territory.id)) {
                changed = true
            }
            territory.occupier = null
            changed = true
        }

        territory.town?.let { owner ->
            touchedTowns.add(owner)
            if (owner.annexed.remove(territory.id)) {
                changed = true
            }
            if (owner.captured.remove(territory.id)) {
                changed = true
            }
            if (owner !== winner && owner.territories.remove(territory.id)) {
                if (Config.claimsPowerEnabled) {
                    owner.claimsUsed = (owner.claimsUsed - territory.cost).coerceAtLeast(0)
                }
                changed = true
            }
        }

        touchedTowns.add(winner)
        if (territory.town !== winner) {
            territory.town = winner
            changed = true
        }
        if (winner.territories.add(territory.id)) {
            if (Config.claimsPowerEnabled) {
                winner.claimsUsed += territory.cost
            }
            changed = true
        }
        if (winner.annexed.remove(territory.id)) {
            changed = true
        }
        if (winner.captured.remove(territory.id)) {
            changed = true
        }

        return changed
    }

    private fun updateBossbars() {
        val summariesByName = getControlSummaries(forceRefresh = true).associateBy { key(it.warzone.name) }
        val topPoints = getPointShares().firstOrNull()
        val remainingMillis = getRemainingMillis()
        val progress = if (endMillis > 0L && durationMillis > 0L) {
            (remainingMillis.toDouble() / durationMillis.toDouble()).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        val visibleBossbars = hashSetOf<UUID>()

        for (player in Bukkit.getOnlinePlayers()) {
            val territory = Nodes.getTerritoryFromCoord(phonon.nodes.objects.Coord.fromBlockCoords(player.location.blockX, player.location.blockZ))
            val warzone = getWarzoneForTerritory(territory)
            if (warzone == null) {
                removeBossbar(player)
                continue
            }

            val summary = summariesByName[key(warzone.name)]
            val holderText = when {
                summary == null || summary.isUncontrolled -> "Hold None"
                summary.leaders.size > 1 -> "Hold Contested"
                else -> "Hold ${summary.leaders.first().town.name}"
            }
            val pointsLeaderText = if (topPoints == null) {
                "Lead None 0p"
            } else {
                "Lead ${topPoints.town.name} ${topPoints.points}p"
            }
            val timerText = if (endMillis > 0L) {
                formatDuration(remainingMillis)
            } else {
                "Open"
            }
            val barColor = when {
                summary == null || summary.isUncontrolled -> BarColor.WHITE
                summary.leaders.size > 1 -> BarColor.YELLOW
                else -> BarColor.RED
            }
            val title = "${ChatColor.DARK_RED}${ChatColor.BOLD}${warzone.name}${ChatColor.GRAY} | ${ChatColor.WHITE}$holderText${ChatColor.GRAY} | ${ChatColor.GOLD}$pointsLeaderText${ChatColor.GRAY} | ${ChatColor.WHITE}$timerText"

            val bossbar = activeBossbars.getOrPut(player.uniqueId) {
                Bukkit.createBossBar(title, barColor, BarStyle.SOLID).also { it.addPlayer(player) }
            }
            if (!bossbar.players.contains(player)) {
                bossbar.addPlayer(player)
            }
            bossbar.setTitle(title)
            bossbar.color = barColor
            bossbar.style = BarStyle.SOLID
            bossbar.progress = progress
            visibleBossbars.add(player.uniqueId)
        }

        activeBossbars.keys.toList()
            .filter { uuid -> !visibleBossbars.contains(uuid) }
            .forEach { uuid ->
                val player = Bukkit.getPlayer(uuid)
                if (player != null) {
                    removeBossbar(player)
                } else {
                    activeBossbars.remove(uuid)?.removeAll()
                }
            }
    }

    public fun handlePlayerQuit(player: Player) {
        removeBossbar(player)
    }

    private fun removeBossbar(player: Player) {
        activeBossbars.remove(player.uniqueId)?.let { bossbar ->
            bossbar.removePlayer(player)
            bossbar.removeAll()
        }
    }

    private fun removeAllBossbars() {
        activeBossbars.values.forEach { it.removeAll() }
        activeBossbars.clear()
    }

    private fun invalidateCaches() {
        invalidateControlCache()
        invalidatePointCache()
    }

    private fun invalidateControlCache() {
        controlCacheTime = 0L
        cachedControlSummaries = emptyList()
    }

    private fun invalidatePointCache() {
        pointCacheDirty = true
        cachedPointShares = emptyList()
    }

    private fun formatDuration(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0L) {
            String.format("%dh %02dm", hours, minutes)
        } else {
            String.format("%02dm %02ds", minutes, seconds)
        }
    }
}
