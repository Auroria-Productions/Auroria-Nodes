package phonon.nodes.contracts

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import phonon.nodes.Nodes
import phonon.nodes.objects.Town

/**
 * Manages per-town contract state.
 * All tunable values come from [ContractConfig] — no magic numbers here.
 */
data class TownContractState(
    val contracts: List<Contract>,
    val completed: MutableSet<String> = mutableSetOf(),
    val cycleStart: Long = System.currentTimeMillis(),
) {
    fun isExpired(): Boolean = System.currentTimeMillis() - cycleStart >= ContractConfig.cycleDurationMs

    fun msUntilRefresh(): Long = (cycleStart + ContractConfig.cycleDurationMs - System.currentTimeMillis()).coerceAtLeast(0L)
}

object TownContractManager {

    private val townStates: HashMap<String, TownContractState> = hashMapOf()
    private var taskId: Int = -1

    // ── Lifecycle ─────────────────────────────────────────────────

    fun initialize() {
        val plugin = Nodes.plugin ?: return
        // Re-check expiry every 5 minutes
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
            expireStaleStates()
        }, 20L * 60 * 5, 20L * 60 * 5)
    }

    fun shutdown() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId)
            taskId = -1
        }
        townStates.clear()
    }

    // ── Public API ────────────────────────────────────────────────

    fun getOrCreateState(town: Town): TownContractState {
        val existing = townStates[town.name]
        if (existing != null && !existing.isExpired()) return existing
        return generateNewState(town)
    }

    fun complete(
        town: Town,
        contract: Contract,
        playerItems: Array<out ItemStack?>,
    ): ContractResult {
        val state = getOrCreateState(town)

        if (state.completed.contains(contract.id)) return ContractResult.AlreadyCompleted

        // Verify player holds all required items
        for (required in contract.required) {
            val held = playerItems
                .filterNotNull()
                .filter { it.type == required.type }
                .sumOf { it.amount }
            if (held < required.amount) return ContractResult.MissingItems(required)
        }

        state.completed.add(contract.id)

        // Apply reward multiplier from config, then inject directly into the
        // town's IncomeInventory — same storage used by /t income chest.
        val scaledRewards = contract.rewards.map { reward ->
            val scaled = (reward.amount * ContractConfig.rewardMultiplier).toInt().coerceAtLeast(1)
            ItemStack(reward.type, scaled)
        }
        for (reward in scaledRewards) {
            town.income.add(reward.type, reward.amount)
        }

        // Optional server-wide broadcast
        if (ContractConfig.broadcastOnComplete) {
            // placeholder replacement happens at call site (TownContractsGUI) where
            // the player object is available; we return the rewards so GUI can format it
        }

        return ContractResult.Success(scaledRewards)
    }

    fun msUntilRefresh(town: Town): Long = townStates[town.name]?.msUntilRefresh() ?: 0L

    // ── Internals ─────────────────────────────────────────────────

    private fun generateNewState(town: Town): TownContractState {
        val pool = ContractConfig.effectivePool()

        val tier1 = pool.filter { it.tier == 1 }.shuffled().take(ContractConfig.slotsCommon)
        val tier2 = pool.filter { it.tier == 2 }.shuffled().take(ContractConfig.slotsUncommon)
        val tier3 = pool.filter { it.tier == 3 }.shuffled().take(ContractConfig.slotsRare)

        val selected = (tier1 + tier2 + tier3).shuffled()
        val state = TownContractState(contracts = selected)
        townStates[town.name] = state
        return state
    }

    private fun expireStaleStates() {
        townStates.entries
            .filter { it.value.isExpired() }
            .map { it.key }
            .forEach { townStates.remove(it) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

sealed class ContractResult {
    data class Success(val rewards: List<ItemStack>) : ContractResult()
    data class MissingItems(val missing: ItemStack) : ContractResult()
    object AlreadyCompleted : ContractResult()
}
