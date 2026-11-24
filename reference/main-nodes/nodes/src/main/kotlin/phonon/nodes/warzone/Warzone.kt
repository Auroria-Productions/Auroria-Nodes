package phonon.nodes.warzone

import org.bukkit.inventory.ItemStack

/**
 * Static configuration for a warzone.
 */
enum class WarzoneWinMode {
    REWARD,
    REWARD_AND_NODES,
    NODES,
}

data class WarzoneConfig(
    val name: String,
    val nodes: MutableList<Int> = mutableListOf(),
    var durationMillis: Long = 0L,
    var useWhitelist: Boolean = false,
    val whitelist: MutableSet<String> = mutableSetOf(),
    var useBlacklist: Boolean = false,
    val blacklist: MutableSet<String> = mutableSetOf(),
    val rewardItems: MutableList<ItemStack> = mutableListOf(),
    var winMode: WarzoneWinMode = WarzoneWinMode.REWARD,
) {
    public fun rewardCopy(): List<ItemStack> = this.rewardItems.map { it.clone() }
}

/**
 * Mutable runtime state for an individual warzone.
 */
data class WarzoneState(
    var startTime: Long = 0L,
    var endTime: Long = 0L,
    var nextStartTime: Long = 0L,
    var nextDurationMillis: Long = 0L,
    var activeDurationMillis: Long = 0L,
    var lastLeader: String? = null,
    var leaderSince: Long = 0L,
    val controlTimes: MutableMap<String, Long> = hashMapOf(),
    var lastWinner: String? = null,
    var rewardGiven: Boolean = false,
    var scheduleDescription: String? = null,
    var lastControlUpdate: Long = 0L,
)

/**
 * Runtime view of a configured warzone.
 */
class Warzone(
    val config: WarzoneConfig,
    val state: WarzoneState,
) {
    val name: String
        get() = this.config.name

    val nodes: List<Int>
        get() = this.config.nodes

    val whitelistNations: Set<String>
        get() = this.config.whitelist

    val blacklistNations: Set<String>
        get() = this.config.blacklist

    val nextStartTime: Long
        get() = this.state.nextStartTime

    val scheduleDescription: String?
        get() = this.state.scheduleDescription

    val lastWinnerName: String?
        get() = this.state.lastWinner

    val activeDurationMillis: Long
        get() = this.state.activeDurationMillis

    fun isActive(now: Long): Boolean = state.startTime > 0L && now < state.endTime

    fun isScheduled(): Boolean = state.nextStartTime > 0L

    fun remainingMillis(now: Long): Long = if (isActive(now)) {
        (state.endTime - now).coerceAtLeast(0L)
    } else {
        0L
    }

    fun durationPreview(): Long = if (state.nextDurationMillis > 0L) state.nextDurationMillis else config.durationMillis

    fun leaderboardSnapshot(): List<Pair<String, Long>> = state.controlTimes.entries
        .map { it.key to it.value }
        .sortedByDescending { it.second }
}
