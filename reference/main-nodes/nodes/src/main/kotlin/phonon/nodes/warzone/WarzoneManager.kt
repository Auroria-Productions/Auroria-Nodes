package phonon.nodes.warzone

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.warzone.WarzoneTownRegistry
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import kotlin.math.max

public enum class WarzoneChatInputType {
    START_TIME,
    NODE_LIST,
    NODE_REMOVE_LIST,
    NODE_REPLACE_LIST,
    WHITELIST,
    BLACKLIST,
    CREATE_NAME,
}

public data class PendingChatInput(
    val warzoneName: String,
    val type: WarzoneChatInputType,
)

public object WarzoneManager {
    private val warzones: LinkedHashMap<String, Warzone> = linkedMapOf()
    private val nameIndex: HashMap<String, Warzone> = hashMapOf()
    private val nodeIndex: HashMap<Int, Warzone> = hashMapOf()
    private val startWarningTasks: HashMap<String, MutableList<BukkitTask>> = hashMapOf()
    private val startWarningOffsets = listOf(
        3_600_000L,
        1_800_000L,
        900_000L,
        600_000L,
        300_000L,
        60_000L,
        30_000L,
        10_000L,
    )

    private var ticker: BukkitTask? = null
    private var plugin: Plugin? = null

    private var stateDirty = false
    private var lastStateSave: Long = 0L

    private val pendingInputs: HashMap<UUID, PendingChatInput> = hashMapOf()

    private val timezoneAliases: Map<String, String> = mapOf(
        "UTC" to "UTC",
        "EST" to "America/New_York",
        "EDT" to "America/New_York",
        "CST" to "America/Chicago",
        "CDT" to "America/Chicago",
        "MST" to "America/Denver",
        "MDT" to "America/Denver",
        "PST" to "America/Los_Angeles",
        "PDT" to "America/Los_Angeles",
    )

    private fun rebuildNodeIndex() {
        nodeIndex.clear()
        for (warzone in warzones.values) {
            for (node in warzone.nodes) {
                nodeIndex[node] = warzone
            }
        }
    }

    private fun cancelStartWarnings(name: String) {
        val tasks = startWarningTasks.remove(name) ?: return
        for (task in tasks) {
            task.cancel()
        }
    }

    private fun scheduleStartWarnings(warzone: Warzone) {
        val pluginRef = this.plugin ?: return
        cancelStartWarnings(warzone.name)
        val startTime = warzone.state.nextStartTime
        if (startTime <= 0L) {
            return
        }

        val now = System.currentTimeMillis()
        val tasks = mutableListOf<BukkitTask>()
        for (offset in startWarningOffsets) {
            val trigger = startTime - offset
            if (trigger <= now) {
                continue
            }
            val delayTicks = ((trigger - now) / 50L).coerceAtLeast(0L)
            val task = Bukkit.getScheduler().runTaskLater(
                pluginRef,
                Runnable {
                    Message.broadcast("${WarzoneText.prefix()} ${warzone.name} starts in ${WarzoneInfoPrinter.formatDuration(offset)}.")
                },
                delayTicks,
            )
            tasks.add(task)
        }
        if (tasks.isNotEmpty()) {
            startWarningTasks[warzone.name] = tasks
        }
    }

    public fun initialize(plugin: Plugin) {
        this.plugin = plugin
        loadConfigs()
        loadStates()
        startTicker()
        WarzoneBossBarManager.start(plugin)
    }

    public fun shutdown() {
        WarzoneBossBarManager.shutdown()
        ticker?.cancel()
        ticker = null
        saveState(force = true)
    }

    private fun startTicker() {
        val pluginRef = this.plugin ?: return
        ticker?.cancel()
        ticker = Bukkit.getScheduler().runTaskTimer(
            pluginRef,
            Runnable {
                tick()
            },
            20L,
            20L * 5,
        )
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        for (warzone in warzones.values) {
            manageWarzone(warzone, now)
        }
        maybePersistState(now)
    }

    private fun manageWarzone(warzone: Warzone, now: Long) {
        if (warzone.state.startTime > 0L && now >= warzone.state.endTime) {
            finishWarzone(warzone, now)
            return
        }

        if (warzone.isActive(now)) {
            updateControl(warzone, now)
            return
        }

        if (warzone.state.nextStartTime > 0L && now >= warzone.state.nextStartTime) {
            startWarzone(warzone, now)
        }
    }

    private fun maybePersistState(now: Long) {
        if (!stateDirty) {
            return
        }
        if (now - lastStateSave < 30_000L) {
            return
        }
        saveState(force = true)
    }

    private fun updateControl(warzone: Warzone, now: Long) {
        val lastUpdate = warzone.state.lastControlUpdate.takeIf { it > 0L } ?: now
        val delta = now - lastUpdate
        if (delta <= 0L) {
            warzone.state.lastControlUpdate = now
            return
        }

        val leader = determineControlLeader(warzone)
        if (leader != null) {
            val previous = warzone.state.controlTimes[leader] ?: 0L
            warzone.state.controlTimes[leader] = previous + delta
            if (warzone.state.lastLeader != leader) {
                warzone.state.lastLeader = leader
                warzone.state.leaderSince = now
            }
        } else {
            warzone.state.lastLeader = null
        }

        warzone.state.lastControlUpdate = now
        markStateDirty()
    }

    private fun startWarzone(warzone: Warzone, now: Long) {
        val duration = max(warzone.state.nextDurationMillis, warzone.config.durationMillis)
        if (duration <= 0L) {
            warzone.state.nextStartTime = 0L
            warzone.state.scheduleDescription = null
            return
        }

        cancelStartWarnings(warzone.name)

        warzone.state.nextDurationMillis = duration

        warzone.state.startTime = now
        warzone.state.activeDurationMillis = duration
        warzone.state.endTime = now + duration
        warzone.state.lastControlUpdate = now
        warzone.state.leaderSince = now
        warzone.state.lastLeader = null
        warzone.state.controlTimes.clear()
        warzone.state.rewardGiven = false
        warzone.state.nextStartTime = 0L

        markStateDirty()
        Message.broadcast("${WarzoneText.prefix()} ${warzone.name} started for ${formatDuration(duration)}.")
    }

    private fun finishWarzone(warzone: Warzone, now: Long) {
        updateControl(warzone, now)
        val winner = determineWinner(warzone)
        val outcome = applyWinOutcome(warzone, winner)
        val winnerName = winner?.let { getParticipantName(it) }

        warzone.state.lastWinner = winnerName
        warzone.state.startTime = 0L
        warzone.state.endTime = 0L
        warzone.state.activeDurationMillis = 0L
        warzone.state.lastLeader = null
        warzone.state.leaderSince = 0L
        warzone.state.lastControlUpdate = 0L
        warzone.state.rewardGiven = true
        warzone.state.scheduleDescription = null

        markStateDirty()
        val winnerLabel = winnerName ?: "No winner"
        val rewardLabel = if (warzone.config.winMode == WarzoneWinMode.REWARD || warzone.config.winMode == WarzoneWinMode.REWARD_AND_NODES) {
            if (outcome.rewardGranted) "" else " (no reward distributed)"
        } else {
            ""
        }
        val nodesLabel = if (warzone.config.winMode == WarzoneWinMode.NODES || warzone.config.winMode == WarzoneWinMode.REWARD_AND_NODES) {
            if (outcome.nodesAwarded > 0) " (nodes awarded)" else " (no nodes awarded)"
        } else {
            ""
        }
        Message.broadcast("${WarzoneText.prefix()} ${warzone.name} ended. Winner: $winnerLabel$rewardLabel$nodesLabel.")
        if (outcome.nodesAwarded > 0 && (warzone.config.winMode == WarzoneWinMode.NODES || warzone.config.winMode == WarzoneWinMode.REWARD_AND_NODES)) {
            Message.broadcast("${WarzoneText.prefix()} Awarded control of ${outcome.nodesAwarded} node(s) to $winnerLabel.")
        }
    }

    private data class WinOutcome(
        val rewardGranted: Boolean,
        val nodesAwarded: Int,
    )

    private fun applyWinOutcome(warzone: Warzone, town: Town?): WinOutcome {
        if (town == null) {
            return WinOutcome(rewardGranted = false, nodesAwarded = 0)
        }

        // Nation-based winners resolve to the nation's capital, so both outcomes use the same recipient.
        val rewardRecipient = town
        val nodesRecipient = town

        val rewardGranted = when (warzone.config.winMode) {
            WarzoneWinMode.REWARD, WarzoneWinMode.REWARD_AND_NODES -> awardWinner(warzone, rewardRecipient)
            WarzoneWinMode.NODES -> false
        }

        val nodesAwarded = when (warzone.config.winMode) {
            WarzoneWinMode.NODES, WarzoneWinMode.REWARD_AND_NODES -> awardNodesToWinner(warzone, nodesRecipient)
            WarzoneWinMode.REWARD -> 0
        }

        return WinOutcome(rewardGranted = rewardGranted, nodesAwarded = nodesAwarded)
    }

    // Award all warzone nodes by transferring actual territory ownership to the winner.
    // This clears occupier state and adds the node to the town's owned territory set.
    private fun awardNodesToWinner(warzone: Warzone, town: Town): Int {
        var changed = 0
        for (nodeId in warzone.nodes) {
            val territory = Nodes.getTerritoryFromId(TerritoryId(nodeId)) ?: continue
            val currentOwner = territory.town
            if (currentOwner === town && territory.occupier == null) {
                continue
            }

            // remove from previous owner bookkeeping (best-effort; warzone-towns may not track this cleanly)
            currentOwner?.let { oldTown ->
                oldTown.territories.remove(territory.id)
                oldTown.annexed.remove(territory.id)
                oldTown.captured.remove(territory.id)
                oldTown.needsUpdate()
            }

            // clear any capture state and set new ownership
            territory.occupier = null
            territory.town = town
            town.captured.remove(territory.id)
            town.territories.add(territory.id)
            town.annexed.add(territory.id)

            // Clear per-chunk occupier (node is now truly owned, not occupied).
            for (chunkCoord in territory.chunks) {
                Nodes.getTerritoryChunkFromCoord(chunkCoord)?.let { terrChunk ->
                    terrChunk.occupier = null
                }
            }
            changed += 1
        }

        if (changed > 0) {
            town.needsUpdate()
            Nodes.needsSave = true
            Nodes.renderMinimaps()

            // These nodes are no longer part of the warzone once ownership is transferred.
            // Remove them from the warzone config so map coloring/warzone lookups stop applying.
            warzone.config.nodes.clear()
            saveConfig()
            rebuildNodeIndex()
            WarzoneTownRegistry.sync(warzone) // will remove warzone-town if nodes list is now empty
            triggerWarzoneTownSave()
        }

        return changed
    }

    private fun markStateDirty() {
        stateDirty = true
    }

    private fun loadConfigs() {
        warzones.clear()
        nameIndex.clear()
        nodeIndex.clear()
        val file = Config.pathWarzones.toFile()
        if (!file.exists()) {
            return
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        val entries = yaml.getMapList("warzones")
        for (raw in entries) {
            val config = parseWarzoneConfig(raw)
            if (config == null) {
                continue
            }
            val normalized = config.name.lowercase(Locale.getDefault())
            if (nameIndex.containsKey(normalized)) {
                Nodes.logger?.warning("Duplicate warzone name \"${config.name}\" in ${Config.pathWarzones}")
                continue
            }
            val state = WarzoneState(nextDurationMillis = if (config.durationMillis > 0L) config.durationMillis else 0L)
            state.nextDurationMillis = max(state.nextDurationMillis, 0L)
            val warzone = Warzone(config, state)
            warzones[config.name] = warzone
            nameIndex[normalized] = warzone
        }

        // Required for all runtime lookups like /t map/minimap rendering and warzone flag placement.
        rebuildNodeIndex()
    }

    private fun loadStates() {
        val file = Config.pathWarzoneState.toFile()
        if (!file.exists()) {
            lastStateSave = System.currentTimeMillis()
            return
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        var migratedLegacyState = false
        for (warzone in warzones.values) {
            val section = yaml.getConfigurationSection("states.${warzone.name}") ?: continue
            val state = warzone.state
            state.startTime = section.getLong("startTime", state.startTime)
            state.endTime = section.getLong("endTime", state.endTime)
            state.nextStartTime = section.getLong("nextStartTime", state.nextStartTime)
            state.nextDurationMillis = section.getLong("nextDurationMillis", state.nextDurationMillis.takeIf { it > 0L } ?: warzone.config.durationMillis)
            state.activeDurationMillis = section.getLong("activeDurationMillis", state.activeDurationMillis)
            state.lastLeader = section.getString("lastLeader", state.lastLeader)
            state.leaderSince = section.getLong("leaderSince", state.leaderSince)
            state.lastWinner = section.getString("lastWinner", state.lastWinner)
            state.rewardGiven = section.getBoolean("rewardGiven", state.rewardGiven)
            state.scheduleDescription = section.getString("scheduleDescription", state.scheduleDescription)
            state.lastControlUpdate = section.getLong("lastControlUpdate", state.lastControlUpdate)

            state.controlTimes.clear()
            val controls = section.getConfigurationSection("controlTimes")
            if (controls !== null) {
                for (key in controls.getKeys(false)) {
                    state.controlTimes[key] = controls.getLong(key, 0L)
                }
            }

            if (migrateLegacyParticipantState(state)) {
                migratedLegacyState = true
            }
        }

        lastStateSave = System.currentTimeMillis()
        if (migratedLegacyState) {
            saveState(force = true)
        }
        for (warzone in warzones.values) {
            scheduleStartWarnings(warzone)
        }
    }

    private fun saveConfig() {
        val file = Config.pathWarzones.toFile()
        file.parentFile?.mkdirs()

        val yaml = YamlConfiguration()
        val serialized = warzones.values.map { serializeWarzoneConfig(it.config) }
        yaml.set("warzones", serialized)

        try {
            yaml.save(file)
            Nodes.saveWorldToDynmap(async = true)
        } catch (err: Exception) {
            Nodes.logger?.warning("Failed to save warzone config: ${err.message}")
        }
    }

    private fun saveState(force: Boolean = false) {
        if (!force && !stateDirty) {
            return
        }

        val file = Config.pathWarzoneState.toFile()
        file.parentFile?.mkdirs()

        val yaml = YamlConfiguration()
        for (warzone in warzones.values) {
            val section = yaml.createSection("states.${warzone.name}")
            val state = warzone.state
            section.set("startTime", state.startTime)
            section.set("endTime", state.endTime)
            section.set("nextStartTime", state.nextStartTime)
            section.set("nextDurationMillis", state.nextDurationMillis)
            section.set("activeDurationMillis", state.activeDurationMillis)
            section.set("lastLeader", state.lastLeader)
            section.set("leaderSince", state.leaderSince)
            section.set("lastWinner", state.lastWinner)
            section.set("rewardGiven", state.rewardGiven)
            section.set("scheduleDescription", state.scheduleDescription)
            section.set("lastControlUpdate", state.lastControlUpdate)

            val controls = section.createSection("controlTimes")
            for ((name, millis) in state.controlTimes) {
                controls.set(name, millis)
            }
        }

        try {
            yaml.save(file)
            stateDirty = false
            lastStateSave = System.currentTimeMillis()
        } catch (err: Exception) {
            Nodes.logger?.warning("Failed to save warzone state: ${err.message}")
        }
    }

    private fun parseWarzoneConfig(raw: Map<*, *>): WarzoneConfig? {
        val nameRaw = raw["name"] as? String ?: return null
        val name = nameRaw.trim()
        if (name.isEmpty()) {
            return null
        }

        val config = WarzoneConfig(name = name)
        config.nodes.clear()
        config.nodes.addAll(parseNodes(raw["nodes"]))
        config.durationMillis =
            when (val duration = raw["durationMillis"]) {
                is Number -> duration.toLong()
                is String -> duration.toLongOrNull() ?: 0L
                else -> 0L
            }
        config.useWhitelist = raw["useWhitelist"] as? Boolean ?: false
        config.whitelist.clear()
        config.whitelist.addAll(parseStringSet(raw["whitelist"]))
        config.useBlacklist = raw["useBlacklist"] as? Boolean ?: false
        config.blacklist.clear()
        config.blacklist.addAll(parseStringSet(raw["blacklist"]))
        config.rewardItems.clear()
        val rewardList = raw["reward"] as? List<*>
        rewardList?.forEach { element ->
            if (element is ItemStack) {
                if (element.type != null && element.type != org.bukkit.Material.AIR) {
                    config.rewardItems.add(element.clone())
                }
            }
        }

        config.winMode = parseWinMode(raw["winMode"])
        return config
    }

    private fun serializeWarzoneConfig(config: WarzoneConfig): Map<String, Any> {
        val serialized: MutableMap<String, Any> = linkedMapOf()
        serialized["name"] = config.name
        serialized["nodes"] = config.nodes
        serialized["durationMillis"] = config.durationMillis
        serialized["useWhitelist"] = config.useWhitelist
        serialized["whitelist"] = config.whitelist.toList()
        serialized["useBlacklist"] = config.useBlacklist
        serialized["blacklist"] = config.blacklist.toList()
        serialized["reward"] = config.rewardCopy()
        serialized["winMode"] = serializeWinMode(config.winMode)
        return serialized
    }

    private fun parseWinMode(raw: Any?): WarzoneWinMode {
        val token = (raw as? String)?.trim()?.lowercase(Locale.getDefault()) ?: return WarzoneWinMode.REWARD
        return when (token.replace("\\s+".toRegex(), "")) {
            "reward" -> WarzoneWinMode.REWARD
            "nodes" -> WarzoneWinMode.NODES
            "reward+nodes", "rewardandnodes", "reward_nodes", "reward&nodes" -> WarzoneWinMode.REWARD_AND_NODES
            else -> WarzoneWinMode.REWARD
        }
    }

    private fun serializeWinMode(mode: WarzoneWinMode): String = when (mode) {
        WarzoneWinMode.REWARD -> "reward"
        WarzoneWinMode.NODES -> "nodes"
        WarzoneWinMode.REWARD_AND_NODES -> "reward+nodes"
    }

    private fun determineControlLeader(warzone: Warzone): String? {
        val counts = mutableMapOf<String, Int>()
        for (nodeId in warzone.nodes) {
            val territory = Nodes.territories[TerritoryId(nodeId)] ?: continue
            val owner = territory.occupier ?: territory.town ?: continue
            if (WarzoneTownRegistry.isWarzoneTown(owner)) {
                continue
            }
            val participant = getParticipantName(owner)
            counts[participant] = counts.getOrDefault(participant, 0) + 1
        }
        return if (counts.isEmpty()) {
            null
        } else {
            counts.entries.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
        }
    }

    private fun determineWinner(warzone: Warzone): Town? {
        val entry = warzone.state.controlTimes
            .filter { it.value > 0L }
            .maxWithOrNull(compareBy({ it.value }, { it.key }))
        if (entry == null) {
            return null
        }
        return resolveParticipantTown(entry.key)
    }

    private fun getParticipantName(town: Town): String = town.nation?.name ?: town.name

    private fun resolveParticipantTown(name: String): Town? {
        val nation = Nodes.nations[name]
        if (nation != null) {
            return nation.capital
        }
        return Nodes.towns[name]
    }

    private fun migrateLegacyParticipantState(state: WarzoneState): Boolean {
        var changed = false

        val migratedControlTimes = linkedMapOf<String, Long>()
        for ((rawName, millis) in state.controlTimes) {
            val normalizedName = migrateLegacyParticipantName(rawName)
            if (normalizedName != rawName) {
                changed = true
            }
            val previous = migratedControlTimes[normalizedName] ?: 0L
            migratedControlTimes[normalizedName] = previous + millis
        }
        if (migratedControlTimes != state.controlTimes) {
            state.controlTimes.clear()
            state.controlTimes.putAll(migratedControlTimes)
            changed = true
        }

        val migratedLastLeader = state.lastLeader?.let { migrateLegacyParticipantName(it) }
        if (migratedLastLeader != state.lastLeader) {
            state.lastLeader = migratedLastLeader
            changed = true
        }

        val migratedLastWinner = state.lastWinner?.let { migrateLegacyParticipantName(it) }
        if (migratedLastWinner != state.lastWinner) {
            state.lastWinner = migratedLastWinner
            changed = true
        }

        if (changed) {
            markStateDirty()
        }
        return changed
    }

    private fun migrateLegacyParticipantName(name: String): String {
        val nation = findNationByLegacyName(name)
        if (nation != null) {
            return nation.name
        }

        val town = findTownByLegacyName(name)
        if (town != null) {
            return getParticipantName(town)
        }

        return name
    }

    private fun findNationByLegacyName(name: String) = Nodes.nations[name]
        ?: Nodes.nations.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun findTownByLegacyName(name: String) = Nodes.towns[name]
        ?: Nodes.towns.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    public fun reconcileLegacyParticipantStates(): Int {
        var migratedCount = 0
        for (warzone in warzones.values) {
            if (migrateLegacyParticipantState(warzone.state)) {
                migratedCount += 1
            }
        }
        if (migratedCount > 0) {
            saveState(force = true)
        }
        return migratedCount
    }

    private fun awardWinner(warzone: Warzone, town: Town?): Boolean {
        if (town == null) {
            return false
        }
        if (!townCanReceiveReward(warzone, town)) {
            return false
        }

        var granted = false
        for (item in warzone.config.rewardItems) {
            if (item.type == Material.AIR) {
                continue
            }
            if (item.amount <= 0) {
                continue
            }
            town.income.add(item.clone())
            granted = true
        }
        if (granted) {
            town.needsUpdate()
        }
        return granted
    }

    private fun townCanReceiveReward(warzone: Warzone, town: Town): Boolean {
        val nationName = town.nation?.name
        if (warzone.config.useWhitelist) {
            if (nationName == null || warzone.config.whitelist.none { it.equals(nationName, ignoreCase = true) }) {
                return false
            }
        }
        if (warzone.config.useBlacklist) {
            if (nationName != null && warzone.config.blacklist.any { it.equals(nationName, ignoreCase = true) }) {
                return false
            }
        }
        return true
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        return if (hours > 0L) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }

    private fun parseNodes(raw: Any?): List<Int> {
        val result = mutableListOf<Int>()
        when (raw) {
            is List<*> -> {
                for (value in raw) {
                    val num = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    }
                    if (num != null) {
                        result.add(num)
                    }
                }
            }
            is String -> {
                val tokens = raw.split("[,\\s]+".toRegex())
                for (token in tokens) {
                    token.toIntOrNull()?.let { result.add(it) }
                }
            }
        }
        return result.distinct()
    }

    private fun parseStringSet(raw: Any?): Set<String> {
        val result = mutableSetOf<String>()
        when (raw) {
            is List<*> -> {
                for (value in raw) {
                    if (value is String) {
                        result.add(value.trim())
                    }
                }
            }
            is String -> {
                raw.split("[,\\s]+".toRegex()).forEach { token ->
                    if (token.isNotBlank()) {
                        result.add(token.trim())
                    }
                }
            }
        }
        return result
    }

    private fun normalizeKey(name: String): String = name.trim().lowercase(Locale.getDefault())

    private fun lookupWarzone(name: String): Warzone? = nameIndex[normalizeKey(name)]

    public fun getWarzones(): List<Warzone> = warzones.values.toList()

    public fun refreshTownAssignments() {
        WarzoneTownRegistry.syncAll(warzones.values)
    }

    public fun findActiveWarzoneByNode(nodeId: Int, now: Long = System.currentTimeMillis()): Warzone? {
        if (nodeId < 0) {
            return null
        }
        val warzone = nodeIndex[nodeId] ?: return null
        return warzone.takeIf { it.isActive(now) }
    }

    public fun findActiveWarzoneForTerritoryId(id: TerritoryId, now: Long = System.currentTimeMillis()): Warzone? = findActiveWarzoneByNode(id.toInt(), now)

    public fun findWarzoneByTerritoryId(id: TerritoryId): Warzone? = nodeIndex[id.toInt()]

    public fun createWarzone(name: String): Warzone? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val key = normalizeKey(trimmed)
        if (nameIndex.containsKey(key)) {
            return null
        }
        val config = WarzoneConfig(name = trimmed)
        val state = WarzoneState(nextDurationMillis = config.durationMillis)
        val warzone = Warzone(config, state)
        warzones[trimmed] = warzone
        nameIndex[key] = warzone
        rebuildNodeIndex()
        saveConfig()
        saveState(force = true)
        WarzoneTownRegistry.sync(warzone)
        triggerWarzoneTownSave()
        return warzone
    }

    public fun deleteWarzone(name: String): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        cancelStartWarnings(warzone.name)
        if (warzone.isActive(System.currentTimeMillis())) {
            return false
        }
        val key = normalizeKey(warzone.name)
        warzones.remove(warzone.name)
        nameIndex.remove(key)
        rebuildNodeIndex()
        saveConfig()
        saveState(force = true)
        WarzoneTownRegistry.remove(warzone)
        triggerWarzoneTownSave()
        return true
    }

    public fun scheduleStart(name: String, startTimeMillis: Long, description: String?, durationMillis: Long? = null): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        warzone.state.nextStartTime = startTimeMillis
        warzone.state.scheduleDescription = description
        durationMillis?.takeIf { it > 0L }?.let { warzone.state.nextDurationMillis = it }
        warzone.state.rewardGiven = false
        markStateDirty()
        saveState(force = true)
        scheduleStartWarnings(warzone)
        return true
    }

    public fun setDuration(name: String, durationMillis: Long): Boolean {
        if (durationMillis < 0L) {
            return false
        }
        val warzone = lookupWarzone(name) ?: return false
        warzone.config.durationMillis = durationMillis
        if (!warzone.isActive(System.currentTimeMillis())) {
            warzone.state.nextDurationMillis = durationMillis
        }
        saveConfig()
        markStateDirty()
        return true
    }

    public fun setWinMode(name: String, mode: WarzoneWinMode): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        if (warzone.config.winMode == mode) {
            return true
        }
        warzone.config.winMode = mode
        saveConfig()
        return true
    }

    public fun clearDefaultDuration(name: String): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        warzone.config.durationMillis = 0L
        if (!warzone.isActive(System.currentTimeMillis())) {
            warzone.state.nextDurationMillis = 0L
        }
        saveConfig()
        markStateDirty()
        return true
    }

    public fun forceStart(name: String, durationMillis: Long?): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        val now = System.currentTimeMillis()
        if (warzone.isActive(now)) {
            return false
        }
        val duration = durationMillis?.takeIf { it > 0L } ?: max(warzone.state.nextDurationMillis, warzone.config.durationMillis)
        if (duration <= 0L) {
            return false
        }
        warzone.state.nextDurationMillis = duration
        startWarzone(warzone, now)
        return true
    }

    public fun extendActive(name: String, extraMillis: Long): Boolean {
        if (extraMillis <= 0L) {
            return false
        }
        val warzone = lookupWarzone(name) ?: return false
        if (!warzone.isActive(System.currentTimeMillis())) {
            warzone.state.nextDurationMillis += extraMillis
            markStateDirty()
            return true
        }
        warzone.state.endTime += extraMillis
        warzone.state.activeDurationMillis += extraMillis
        warzone.state.nextDurationMillis = warzone.state.activeDurationMillis
        markStateDirty()
        return true
    }

    public fun endWarzoneEarly(name: String): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        val now = System.currentTimeMillis()
        if (!warzone.isActive(now)) {
            return false
        }
        finishWarzone(warzone, now)
        return true
    }

    public fun addNodes(name: String, nodes: Collection<Int>): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        val originalSize = warzone.config.nodes.size
        val merged = LinkedHashSet<Int>(warzone.config.nodes)
        for (node in nodes) {
            if (node >= 0) {
                merged.add(node)
            }
        }
        if (merged.size == originalSize) {
            return false
        }
        warzone.config.nodes.clear()
        warzone.config.nodes.addAll(merged)
        rebuildNodeIndex()
        saveConfig()
        markStateDirty()
        WarzoneTownRegistry.sync(warzone)
        triggerWarzoneTownSave()
        return true
    }

    public fun removeNodes(name: String, nodes: Collection<Int>): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        val filtered = nodes.filter { it >= 0 }.toSet()
        if (filtered.isEmpty()) {
            return false
        }
        val originalSize = warzone.config.nodes.size
        warzone.config.nodes.removeAll { it in filtered }
        if (warzone.config.nodes.size == originalSize) {
            return false
        }
        rebuildNodeIndex()
        saveConfig()
        markStateDirty()
        WarzoneTownRegistry.sync(warzone)
        triggerWarzoneTownSave()
        return true
    }

    public fun replaceNodes(name: String, nodes: Collection<Int>): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        val normalized = nodes.filter { it >= 0 }.distinct()
        warzone.config.nodes.clear()
        warzone.config.nodes.addAll(normalized)
        rebuildNodeIndex()
        saveConfig()
        markStateDirty()
        WarzoneTownRegistry.sync(warzone)
        triggerWarzoneTownSave()
        return true
    }

    public fun setRewardItems(name: String, items: Collection<ItemStack>): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        warzone.config.rewardItems.clear()
        for (item in items) {
            if (item.type != Material.AIR && item.amount > 0) {
                warzone.config.rewardItems.add(item.clone())
            }
        }
        saveConfig()
        markStateDirty()
        triggerWarzoneTownSave()
        return true
    }

    public fun setWhitelist(name: String, enabled: Boolean, nations: Collection<String>): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        warzone.config.useWhitelist = enabled
        warzone.config.whitelist.clear()
        warzone.config.whitelist.addAll(nations.map { it.trim() }.filter { it.isNotBlank() })
        saveConfig()
        markStateDirty()
        triggerWarzoneTownSave()
        return true
    }

    public fun setBlacklist(name: String, enabled: Boolean, nations: Collection<String>): Boolean {
        val warzone = lookupWarzone(name) ?: return false
        warzone.config.useBlacklist = enabled
        warzone.config.blacklist.clear()
        warzone.config.blacklist.addAll(nations.map { it.trim() }.filter { it.isNotBlank() })
        saveConfig()
        markStateDirty()
        triggerWarzoneTownSave()
        return true
    }

    public fun promptChatInput(player: Player, warzoneName: String, type: WarzoneChatInputType) {
        if (type == WarzoneChatInputType.CREATE_NAME) {
            pendingInputs[player.uniqueId] = PendingChatInput("", type)
        } else {
            val warzone = lookupWarzone(warzoneName)
            if (warzone == null) {
                Message.error(player, "Unknown warzone \"${warzoneName}\"")
                return
            }
            pendingInputs[player.uniqueId] = PendingChatInput(warzone.name, type)
        }

        val prompt = when (type) {
            WarzoneChatInputType.START_TIME -> "Type the start time (e.g. \"3h\" or \"3:00 EST\") in chat."
            WarzoneChatInputType.NODE_LIST -> "Type node IDs separated by spaces or commas."
            WarzoneChatInputType.NODE_REMOVE_LIST -> "Type node IDs to remove, separated by spaces or commas."
            WarzoneChatInputType.NODE_REPLACE_LIST -> "Type the full node list to set, separated by spaces or commas."
            WarzoneChatInputType.WHITELIST -> "Type whitelist nations (comma separated) or leave blank to disable."
            WarzoneChatInputType.BLACKLIST -> "Type blacklist nations (comma separated) or leave blank to disable."
            WarzoneChatInputType.CREATE_NAME -> "Type the new warzone name in chat."
        }
        Message.print(player, prompt)
    }

    public fun handlePendingChat(player: Player, message: String): Boolean {
        val input = pendingInputs.remove(player.uniqueId) ?: return false
        when (input.type) {
            WarzoneChatInputType.START_TIME -> handleStartTimeInput(player, input.warzoneName, message)
            WarzoneChatInputType.NODE_LIST -> handleNodeListInput(player, input.warzoneName, message)
            WarzoneChatInputType.NODE_REMOVE_LIST -> handleNodeRemoveInput(player, input.warzoneName, message)
            WarzoneChatInputType.NODE_REPLACE_LIST -> handleNodeReplaceInput(player, input.warzoneName, message)
            WarzoneChatInputType.WHITELIST -> handleWhitelistInput(player, input.warzoneName, message)
            WarzoneChatInputType.BLACKLIST -> handleBlacklistInput(player, input.warzoneName, message)
            WarzoneChatInputType.CREATE_NAME -> handleCreateNameInput(player, message)
        }
        return true
    }

    private fun handleStartTimeInput(player: Player, warzoneName: String, message: String) {
        val warzone = lookupWarzone(warzoneName)
        if (warzone == null) {
            Message.error(player, "Warzone \"${warzoneName}\" no longer exists.")
            return
        }
        val parsed = parseStartTimeValue(message)
        if (parsed == null) {
            Message.error(player, "Invalid time format. Use something like \"3h\" or \"17:00 EST\".")
            return
        }
        scheduleStart(warzone.name, parsed.first, parsed.second)
        Message.print(player, "Scheduled ${warzone.name} to start ${parsed.second}.")
    }

    private fun handleNodeListInput(player: Player, warzoneName: String, message: String) {
        val warzone = lookupWarzone(warzoneName)
        if (warzone == null) {
            Message.error(player, "Warzone \"${warzoneName}\" no longer exists.")
            return
        }
        val nodes = parseNodes(message).distinct()
        if (nodes.isEmpty()) {
            Message.error(player, "No valid node IDs were provided.")
            return
        }
        val added = addNodes(warzone.name, nodes)
        if (added) {
            Message.print(player, "Added ${nodes.size} nodes to ${warzone.name}.")
        } else {
            Message.print(player, "No new nodes were added to ${warzone.name}.")
        }
    }

    private fun handleNodeRemoveInput(player: Player, warzoneName: String, message: String) {
        val warzone = lookupWarzone(warzoneName)
        if (warzone == null) {
            Message.error(player, "Warzone \"${warzoneName}\" no longer exists.")
            return
        }
        val nodes = parseNodes(message).distinct()
        if (nodes.isEmpty()) {
            Message.error(player, "No valid node IDs were provided.")
            return
        }
        val removed = removeNodes(warzone.name, nodes)
        if (removed) {
            Message.print(player, "Removed ${nodes.size} nodes from ${warzone.name}.")
        } else {
            Message.print(player, "No matching nodes were removed from ${warzone.name}.")
        }
    }

    private fun handleNodeReplaceInput(player: Player, warzoneName: String, message: String) {
        val warzone = lookupWarzone(warzoneName)
        if (warzone == null) {
            Message.error(player, "Warzone \"${warzoneName}\" no longer exists.")
            return
        }
        val nodes = parseNodes(message).distinct()
        replaceNodes(warzone.name, nodes)
        Message.print(player, "Set ${warzone.name} nodes to ${nodes.size} entries.")
    }

    private fun handleWhitelistInput(player: Player, warzoneName: String, message: String) {
        val warzone = lookupWarzone(warzoneName)
        if (warzone == null) {
            Message.error(player, "Warzone \"${warzoneName}\" no longer exists.")
            return
        }
        val nations = parseStringSet(message)
        val enabled = nations.isNotEmpty()
        setWhitelist(warzone.name, enabled, nations)
        if (enabled) {
            Message.print(player, "Whitelist updated for ${warzone.name}.")
        } else {
            Message.print(player, "Whitelist disabled for ${warzone.name}.")
        }
    }

    private fun handleBlacklistInput(player: Player, warzoneName: String, message: String) {
        val warzone = lookupWarzone(warzoneName)
        if (warzone == null) {
            Message.error(player, "Warzone \"${warzoneName}\" no longer exists.")
            return
        }
        val nations = parseStringSet(message)
        val enabled = nations.isNotEmpty()
        setBlacklist(warzone.name, enabled, nations)
        if (enabled) {
            Message.print(player, "Blacklist updated for ${warzone.name}.")
        } else {
            Message.print(player, "Blacklist disabled for ${warzone.name}.")
        }
    }

    private fun handleCreateNameInput(player: Player, message: String) {
        val name = message.trim()
        if (name.isEmpty()) {
            Message.error(player, "Warzone name cannot be blank.")
            return
        }
        val warzone = createWarzone(name)
        if (warzone == null) {
            Message.error(player, "A warzone named \"$name\" already exists.")
            return
        }
        Message.print(player, "Created warzone \"${warzone.name}\". You can edit it now.")
        WarzoneGui.openDetail(player, warzone)
    }

    private fun parseStartTimeValue(input: String): Pair<Long, String>? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val relative = Regex("^(\\d+)([smhdSMHD])$").matchEntire(trimmed)
        if (relative != null) {
            val amount = relative.groupValues[1].toLongOrNull() ?: return null
            val unit = relative.groupValues[2].lowercase(Locale.getDefault())
            val multiplier = when (unit) {
                "s" -> 1_000L
                "m" -> 60_000L
                "h" -> 3_600_000L
                "d" -> 86_400_000L
                else -> return null
            }
            val millis = amount * multiplier
            val description = "in ${amount}$unit"
            return Pair(System.currentTimeMillis() + millis, description)
        }

        val tokens = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return null
        }
        val timeToken = tokens[0]
        val zoneToken = tokens.getOrNull(1)
        val timePair = parseTimeToken(timeToken) ?: return null
        val zoneId = zoneToken?.let { resolveZone(it) } ?: ZoneId.systemDefault()

        val nowAtZone = ZonedDateTime.now(zoneId)
        var target = nowAtZone
            .withHour(timePair.first)
            .withMinute(timePair.second)
            .withSecond(0)
            .withNano(0)
        if (!target.isAfter(nowAtZone)) {
            target = target.plusDays(1)
        }

        val zoneLabel = zoneToken?.uppercase(Locale.getDefault()) ?: zoneId.id
        val description = "${timeToken.uppercase(Locale.getDefault())} $zoneLabel"

        return Pair(target.toInstant().toEpochMilli(), description)
    }

    private fun parseTimeToken(token: String): Pair<Int, Int>? {
        val regex = Regex("^(\\d{1,2})(?::(\\d{2}))?([ap]m)?\$", RegexOption.IGNORE_CASE)
        val match = regex.matchEntire(token)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase(Locale.getDefault())
            if (ampm == "am" && hour == 12) {
                hour = 0
            } else if (ampm == "pm" && hour < 12) {
                hour += 12
            }
            if (hour !in 0..23 || minute !in 0..59) {
                return null
            }
            return Pair(hour, minute)
        }
        val numeric = token.toIntOrNull()
        if (numeric != null && numeric in 0..23) {
            return Pair(numeric, 0)
        }
        return null
    }

    private fun resolveZone(raw: String): ZoneId {
        val alias = timezoneAliases[raw.uppercase(Locale.getDefault())]
        return try {
            if (alias != null) {
                ZoneId.of(alias)
            } else {
                ZoneId.of(raw)
            }
        } catch (err: Exception) {
            ZoneId.systemDefault()
        }
    }

    /*** public helpers to be fleshed out later ***/
    public fun getWarzone(name: String): Warzone? = nameIndex[name.lowercase(Locale.getDefault())]

    private fun triggerWarzoneTownSave() {
        Nodes.needsSave = true
        Nodes.saveWorld(checkIfNeedsSave = false, async = true)
    }
}
