package phonon.nodes.contracts

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.inventory.ItemStack
import phonon.nodes.Nodes

/**
 * All runtime-configurable values for the contracts system.
 * Loaded from config.yml under the "contracts" key.
 *
 * Wire up in NodesPlugin.kt alongside MarketConfig:
 *
 *   ContractConfig.setDefaults(this.config)
 *   this.saveConfig()
 *   ContractConfig.load(this.config)
 */
object ContractConfig {

    // ── System ────────────────────────────────────────────────────

    /** Master on/off switch. When false the /t contracts command is blocked. */
    var enabled: Boolean = true

    // ── Cycle timing ──────────────────────────────────────────────

    /**
     * How long a contract cycle lasts in minutes.
     * Default 180 (3 hours) to align with mining outpost cooldown.
     */
    var cycleDurationMinutes: Long = 180L

    /** Derived millisecond value — set automatically when [cycleDurationMinutes] is loaded. */
    var cycleDurationMs: Long = cycleDurationMinutes * 60_000L
        private set

    // ── Slot counts ───────────────────────────────────────────────

    /** Number of tier-1 (common) contracts offered per cycle. */
    var slotsCommon: Int = 3

    /** Number of tier-2 (uncommon) contracts offered per cycle. */
    var slotsUncommon: Int = 2

    /** Number of tier-3 (rare) contracts offered per cycle. */
    var slotsRare: Int = 1

    /** Total slots — derived, not read from config. */
    val totalSlots: Int get() = slotsCommon + slotsUncommon + slotsRare

    // ── Reward multipliers ────────────────────────────────────────

    /**
     * Global multiplier applied to all contract reward amounts.
     * 1.0 = default, 2.0 = double rewards, 0.5 = half rewards.
     */
    var rewardMultiplier: Double = 1.0

    // ── Broadcasts ───────────────────────────────────────────────

    /** Broadcast a server-wide message when any player completes a contract. */
    var broadcastOnComplete: Boolean = false

    /** Format for the broadcast message.
     *  Placeholders: {player}, {town}, {contract} */
    var broadcastFormat: String = "{player} completed a contract for {town}: {contract}"

    // ── GUI appearance ────────────────────────────────────────────

    /** Title shown at the top of the contracts chest GUI. */
    var guiTitle: String = "§8§l⚑ §r§6§lTown Contracts §8§l⚑"

    /**
     * Border glass pane material for each tier.
     * Falls back to the defaults below if the config value is invalid.
     */
    var borderCommon: Material = Material.GRAY_STAINED_GLASS_PANE
    var borderUncommon: Material = Material.YELLOW_STAINED_GLASS_PANE
    var borderRare: Material = Material.PURPLE_STAINED_GLASS_PANE

    // ── Messages ──────────────────────────────────────────────────

    var msgNotInTown: String = "You must be in a town to view contracts."
    var msgAlreadyDone: String = "You already completed that contract this cycle."
    var msgMissingItems: String = "Missing items: {amount}x {material}"
    var msgSuccess: String = "Contract completed: {contract}!"
    var msgRewardAdded: String = "Rewards added to town income: {rewards}"
    var msgSystemDisabled: String = "Town contracts are currently disabled."

    // ── Custom contract pool override ─────────────────────────────

    /**
     * If true, ONLY the contracts defined under contracts.custom_contracts
     * in config.yml are used. The built-in [ContractPool] is ignored.
     */
    var useCustomContractsOnly: Boolean = false

    /**
     * Custom contracts parsed from config.yml.
     * Only populated when useCustomContractsOnly is true or when
     * contracts.custom_contracts entries exist.
     */
    var customContracts: List<Contract> = emptyList()

    // ──────────────────────────────────────────────────────────────
    // setDefaults — writes default keys so config.yml is self-documenting
    // ──────────────────────────────────────────────────────────────

    fun setDefaults(config: FileConfiguration) {
        // System
        config.addDefault("contracts.enabled", true)

        // Timing
        config.addDefault("contracts.cycle_duration_minutes", 180)

        // Slots
        config.addDefault("contracts.slots.common", 3)
        config.addDefault("contracts.slots.uncommon", 2)
        config.addDefault("contracts.slots.rare", 1)

        // Rewards
        config.addDefault("contracts.reward_multiplier", 1.0)

        // Broadcasts
        config.addDefault("contracts.broadcast_on_complete", false)
        config.addDefault(
            "contracts.broadcast_format",
            "{player} completed a contract for {town}: {contract}",
        )

        // GUI
        config.addDefault("contracts.gui.title", "§8§l⚑ §r§6§lTown Contracts §8§l⚑")
        config.addDefault("contracts.gui.border_common", "GRAY_STAINED_GLASS_PANE")
        config.addDefault("contracts.gui.border_uncommon", "YELLOW_STAINED_GLASS_PANE")
        config.addDefault("contracts.gui.border_rare", "PURPLE_STAINED_GLASS_PANE")

        // Messages
        config.addDefault("contracts.messages.not_in_town", "You must be in a town to view contracts.")
        config.addDefault("contracts.messages.already_done", "You already completed that contract this cycle.")
        config.addDefault("contracts.messages.missing_items", "Missing items: {amount}x {material}")
        config.addDefault("contracts.messages.success", "Contract completed: {contract}!")
        config.addDefault("contracts.messages.reward_added", "Rewards added to town income: {rewards}")
        config.addDefault("contracts.messages.system_disabled", "Town contracts are currently disabled.")

        // Custom contracts (empty by default — acts as documentation example)
        config.addDefault("contracts.use_custom_contracts_only", false)
        // Example custom contract entry (commented out by default via description only;
        // users uncomment/add their own under contracts.custom_contracts as a list)
    }

    // ──────────────────────────────────────────────────────────────
    // load — reads values from config.yml into the fields above
    // ──────────────────────────────────────────────────────────────

    fun load(config: FileConfiguration) {
        val logger = Nodes.logger

        // System
        enabled = config.getBoolean("contracts.enabled", true)

        // Timing
        cycleDurationMinutes = config.getLong("contracts.cycle_duration_minutes", 180L)
            .coerceAtLeast(1L)
        cycleDurationMs = cycleDurationMinutes * 60_000L

        // Slots
        slotsCommon = config.getInt("contracts.slots.common", 3).coerceAtLeast(0)
        slotsUncommon = config.getInt("contracts.slots.uncommon", 2).coerceAtLeast(0)
        slotsRare = config.getInt("contracts.slots.rare", 1).coerceAtLeast(0)

        val totalDeclared = slotsCommon + slotsUncommon + slotsRare
        if (totalDeclared > 6) {
            logger?.warning("[Contracts] Total slots ($totalDeclared) exceeds GUI capacity of 6. Extra contracts will not be shown.")
        }
        if (totalDeclared == 0) {
            logger?.warning("[Contracts] All slot counts are 0 — no contracts will be offered.")
        }

        // Rewards
        rewardMultiplier = config.getDouble("contracts.reward_multiplier", 1.0)
            .coerceAtLeast(0.0)

        // Broadcasts
        broadcastOnComplete = config.getBoolean("contracts.broadcast_on_complete", false)
        broadcastFormat = config.getString(
            "contracts.broadcast_format",
            "{player} completed a contract for {town}: {contract}",
        ) ?: broadcastFormat

        // GUI
        guiTitle = config.getString(
            "contracts.gui.title",
            "§8§l⚑ §r§6§lTown Contracts §8§l⚑",
        ) ?: guiTitle

        borderCommon = parseMaterial(
            config,
            "contracts.gui.border_common",
            Material.GRAY_STAINED_GLASS_PANE,
        )
        borderUncommon = parseMaterial(
            config,
            "contracts.gui.border_uncommon",
            Material.YELLOW_STAINED_GLASS_PANE,
        )
        borderRare = parseMaterial(
            config,
            "contracts.gui.border_rare",
            Material.PURPLE_STAINED_GLASS_PANE,
        )

        // Messages
        msgNotInTown = config.getString("contracts.messages.not_in_town", msgNotInTown) ?: msgNotInTown
        msgAlreadyDone = config.getString("contracts.messages.already_done", msgAlreadyDone) ?: msgAlreadyDone
        msgMissingItems = config.getString("contracts.messages.missing_items", msgMissingItems) ?: msgMissingItems
        msgSuccess = config.getString("contracts.messages.success", msgSuccess) ?: msgSuccess
        msgRewardAdded = config.getString("contracts.messages.reward_added", msgRewardAdded) ?: msgRewardAdded
        msgSystemDisabled = config.getString("contracts.messages.system_disabled", msgSystemDisabled) ?: msgSystemDisabled

        // Custom contracts
        useCustomContractsOnly = config.getBoolean("contracts.use_custom_contracts_only", false)
        customContracts = parseCustomContracts(config)

        if (useCustomContractsOnly && customContracts.isEmpty()) {
            logger?.warning("[Contracts] use_custom_contracts_only is true but no custom contracts were parsed. Falling back to built-in pool.")
        }

        logger?.info(
            "[Contracts] Loaded — enabled=$enabled, cycle=${cycleDurationMinutes}m, " +
                "slots=$slotsCommon/$slotsUncommon/$slotsRare, rewardMultiplier=$rewardMultiplier",
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun parseMaterial(config: FileConfiguration, key: String, default: Material): Material {
        val raw = config.getString(key) ?: return default
        return try {
            Material.valueOf(raw.uppercase())
        } catch (_: IllegalArgumentException) {
            Nodes.logger?.warning("[Contracts] Unknown material \"$raw\" at $key, using default ${default.name}")
            default
        }
    }

    /**
     * Parses optional custom contracts from config.yml.
     *
     * Expected YAML format:
     *
     * contracts:
     *   custom_contracts:
     *     - id: "my_contract"
     *       name: "Special Delivery"
     *       description: "Bring some special items."
     *       icon: CHEST
     *       tier: 2
     *       required:
     *         - material: DIAMOND
     *           amount: 5
     *       rewards:
     *         - material: EMERALD
     *           amount: 3
     */
    private fun parseCustomContracts(config: FileConfiguration): List<Contract> {
        val list = config.getMapList("contracts.custom_contracts")
        if (list.isEmpty()) return emptyList()

        val result = mutableListOf<Contract>()

        for ((index, raw) in list.withIndex()) {
            try {
                val id = raw["id"]?.toString() ?: "custom_$index"
                val name = raw["name"]?.toString() ?: "Custom Contract"
                val description = raw["description"]?.toString() ?: ""
                val tier = (raw["tier"] as? Int) ?: 1
                val iconStr = raw["icon"]?.toString() ?: "CHEST"
                val icon = try {
                    Material.valueOf(iconStr.uppercase())
                } catch (_: Exception) {
                    Material.CHEST
                }

                @Suppress("UNCHECKED_CAST")
                val requiredRaw = raw["required"] as? List<Map<*, *>> ?: emptyList()
                val required = requiredRaw.mapNotNull { entry ->
                    val mat = (entry["material"] as? String)?.let {
                        try {
                            Material.valueOf(it.uppercase())
                        } catch (_: Exception) {
                            null
                        }
                    } ?: return@mapNotNull null
                    val amount = (entry["amount"] as? Int) ?: 1
                    ItemStack(mat, amount)
                }

                @Suppress("UNCHECKED_CAST")
                val rewardsRaw = raw["rewards"] as? List<Map<*, *>> ?: emptyList()
                val rewards = rewardsRaw.mapNotNull { entry ->
                    val mat = (entry["material"] as? String)?.let {
                        try {
                            Material.valueOf(it.uppercase())
                        } catch (_: Exception) {
                            null
                        }
                    } ?: return@mapNotNull null
                    val amount = (entry["amount"] as? Int) ?: 1
                    ItemStack(mat, amount)
                }

                if (required.isEmpty()) {
                    Nodes.logger?.warning("[Contracts] Custom contract \"$id\" has no valid required items, skipping.")
                    continue
                }

                result.add(
                    Contract(
                        id = id,
                        displayName = name,
                        description = description,
                        icon = icon,
                        required = required,
                        rewards = rewards,
                        tier = tier.coerceIn(1, 3),
                    ),
                )
            } catch (e: Exception) {
                Nodes.logger?.warning("[Contracts] Failed to parse custom contract at index $index: ${e.message}")
            }
        }

        Nodes.logger?.info("[Contracts] Parsed ${result.size} custom contract(s)")
        return result
    }

    // ──────────────────────────────────────────────────────────────
    // Convenience — the effective contract pool respecting config
    // ──────────────────────────────────────────────────────────────

    /**
     * Returns the pool that [TownContractManager] should draw from,
     * respecting [useCustomContractsOnly] and [customContracts].
     */
    fun effectivePool(): List<Contract> = when {
        useCustomContractsOnly && customContracts.isNotEmpty() -> customContracts
        customContracts.isNotEmpty() -> ContractPool.all + customContracts
        else -> ContractPool.all
    }
}
