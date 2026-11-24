package phonon.nodes.miningtower

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import phonon.nodes.NodesExtensions
import java.io.File

/**
 * Configuration holder for mining tower settings.
 *
 * Loads from a dedicated  plugins/NodesExtensions/miningtower.yml  file.
 * If that file does not exist (or the tiers section is empty), it is created
 * automatically with 5 sensible default tiers so the feature works out of the
 * box without any manual config editing.
 */
object MiningTowerConfig {

    // Cooldown settings
    var submissionCooldownHours: Int = 24

    // Permissions
    var submitPermission: String = "nodesextensions.miningtower.submit"
    var ratePermission: String = "nodesextensions.miningtower.rate"

    // Tiers — private backing map, read-only public access
    private val _tiers = mutableMapOf<String, TierConfig>()
    val tiers: Map<String, TierConfig> get() = _tiers

    // GUI titles
    var submissionGuiTitle: String = "\u00a76Submit Mining Tower"
    var ratingGuiTitle: String = "\u00a76Rate Mining Tower"
    var pendingGuiTitle: String = "\u00a76Pending Mining Towers"

    // Messages
    var submitSuccessMsg: String = "\u00a7aYour mining tower has been submitted for review!"
    var submitCooldownMsg: String = "\u00a7cYou must wait {time} before submitting another mining tower!"
    var noPermissionSubmitMsg: String = "\u00a7cYou don't have permission to submit mining towers!"
    var noPermissionRateMsg: String = "\u00a7cYou don't have permission to rate mining towers!"
    var noPendingMsg: String = "\u00a7cThere are no pending mining tower submissions!"
    var ratedSuccessMsg: String = "\u00a7aMining tower rated as {tier}!"
    var rejectedSuccessMsg: String = "\u00a7cMining tower submission rejected!"
    var teleportSuccessMsg: String = "\u00a7aTeleporting to mining tower..."
    var noTownMsg: String = "\u00a7cYou must be in a town to submit a mining tower!"

    // Mining rate buff settings
    var showMiningBuffMessage: Boolean = true
    var miningBuffRadius: Double = 100.0

    // Territory entry notification settings
    var showTerritoryEntryMessage: Boolean = true
    var territoryEntryMessage: String = "\u00a76\u26cf \u00a7eEntering Mining Tower Territory \u00a76\u26cf"
    var territoryEntrySubtitle: String = "\u00a77{tier} \u00a78| \u00a76{multiplier}x Mining Rate"
    var showTerritoryEntryBossbar: Boolean = true
    var territoryEntryBossbarText: String = "\u00a76\u26cf \u00a7e{town} Mining Tower \u00a78| \u00a77{tier} \u00a78| \u00a76{multiplier}x"
    var territoryEntryBossbarColor: String = "YELLOW"
    var territoryEntryBossbarStyle: String = "SOLID"
    var territoryEntryBossbarDuration: Int = 5 // seconds

    // Debug settings
    var debugEnabled: Boolean = false

    // -------------------------------------------------------------------------
    // File helpers
    // -------------------------------------------------------------------------

    /** The dedicated config file for the mining-tower feature. */
    private val configFile: File
        get() = File(NodesExtensions.instance.dataFolder, "miningtower.yml")

    /**
     * Writes a full default miningtower.yml if the file does not already exist,
     * or if it exists but has no tiers defined.
     */
    private fun generateDefaultConfig() {
        val file = configFile
        val logger = NodesExtensions.instance.logger

        // Check whether the file already has tiers; if so, leave it alone.
        if (file.exists()) {
            val existing = YamlConfiguration.loadConfiguration(file)
            val tiersSection = existing.getConfigurationSection("mining-tower.tiers")
            if (tiersSection != null && tiersSection.getKeys(false).isNotEmpty()) {
                return // tiers already present, nothing to do
            }
            logger.warning("[MiningTower] miningtower.yml exists but has no tiers — regenerating defaults.")
        } else {
            logger.info("[MiningTower] miningtower.yml not found — generating default config.")
        }

        file.parentFile?.mkdirs()

        // Write the default config as a plain text YAML so comments are preserved.
        file.writeText(
            """
# ============================================================
#  Mining Tower Configuration
#  Auto-generated — edit to customise, then reload the plugin.
# ============================================================

mining-tower:

  # Hours a town must wait before submitting another tower
  submission-cooldown: 24

  # Permission nodes
  submit-permission: nodesextensions.miningtower.submit
  rate-permission:   nodesextensions.miningtower.rate

  # ---- Tiers ------------------------------------------------
  # Add as many tiers as you like.  The key (tier1, tier2, …)
  # is used internally; display-name is shown to players.
  # income-bonus         → fraction added to town income  (0.10 = +10 %)
  # mining-rate-multiplier → ore-drop multiplier when mining in this territory
  # icon-material        → optional inventory icon (Bukkit Material name)
  # ----------------------------------------------------------------
  tiers:
    tier1:
      display-name: "&7[Tier 1] Basic Tower"
      description:
        - "&7A humble beginning."
        - "&7Small income and mining bonus."
      income-bonus: 0.05
      mining-rate-multiplier: 1.25
      icon-material: WOODEN_PICKAXE

    tier2:
      display-name: "&a[Tier 2] Stone Tower"
      description:
        - "&7A solid foundation."
        - "&7Moderate bonuses."
      income-bonus: 0.10
      mining-rate-multiplier: 1.50
      icon-material: STONE_PICKAXE

    tier3:
      display-name: "&b[Tier 3] Iron Tower"
      description:
        - "&7Built with iron resolve."
        - "&7Good income and mining boost."
      income-bonus: 0.15
      mining-rate-multiplier: 1.75
      icon-material: IRON_PICKAXE

    tier4:
      display-name: "&6[Tier 4] Gold Tower"
      description:
        - "&7Gleaming with prosperity."
        - "&7Strong bonuses across the board."
      income-bonus: 0.20
      mining-rate-multiplier: 2.00
      icon-material: GOLDEN_PICKAXE

    tier5:
      display-name: "&b[Tier 5] Diamond Tower"
      description:
        - "&7The pinnacle of engineering."
        - "&7Maximum income and mining power."
      income-bonus: 0.25
      mining-rate-multiplier: 2.50
      icon-material: DIAMOND_PICKAXE

  # ---- GUI --------------------------------------------------
  gui:
    submission-title: "&6Submit Mining Tower"
    rating-title:     "&6Rate Mining Tower"
    pending-title:    "&6Pending Mining Towers"

  # ---- Messages ---------------------------------------------
  messages:
    submit-success:      "&aYour mining tower has been submitted for review!"
    submit-cooldown:     "&cYou must wait {time} before submitting another mining tower!"
    no-permission-submit: "&cYou don't have permission to submit mining towers!"
    no-permission-rate:  "&cYou don't have permission to rate mining towers!"
    no-pending:          "&cThere are no pending mining tower submissions!"
    rated-success:       "&aMining tower rated as {tier}!"
    rejected-success:    "&cMining tower submission rejected!"
    teleport-success:    "&aTeleporting to mining tower..."
    no-town:             "&cYou must be in a town to submit a mining tower!"

  # ---- Mining-rate buff -------------------------------------
  mining-rate:
    show-buff-message: true
    buff-radius: 100.0

  # ---- Territory entry notifications -----------------------
  territory-entry:
    show-message: true
    message:  "&6⛏ &eEntering Mining Tower Territory &6⛏"
    subtitle: "&7{tier} &8| &6{multiplier}x Mining Rate"
    show-bossbar: true
    bossbar-text:     "&6⛏ &e{town} Mining Tower &8| &7{tier} &8| &6{multiplier}x"
    bossbar-color:    YELLOW
    bossbar-style:    SOLID
    bossbar-duration: 5

  # ---- Debug -----------------------------------------------
  debug:
    enabled: false
            """.trimIndent(),
        )

        logger.info("[MiningTower] Default miningtower.yml written to ${file.path}")
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Generates the default file if needed, then loads all settings from
     * plugins/NodesExtensions/miningtower.yml.
     *
     * Pass the main plugin config only if you still want to allow overrides
     * from config.yml (optional — can be null).
     */
    fun load(mainConfig: FileConfiguration? = null) {
        generateDefaultConfig()

        val config = YamlConfiguration.loadConfiguration(configFile)
        val logger = NodesExtensions.instance.logger

        submissionCooldownHours = config.getInt("mining-tower.submission-cooldown", 24)

        submitPermission = config.getString("mining-tower.submit-permission", "nodesextensions.miningtower.submit")!!
        ratePermission = config.getString("mining-tower.rate-permission", "nodesextensions.miningtower.rate")!!

        // ---- Tiers ----------------------------------------------------------
        _tiers.clear()
        val tiersSection = config.getConfigurationSection("mining-tower.tiers")
        if (tiersSection != null) {
            for (tierKey in tiersSection.getKeys(false)) {
                val tierPath = "mining-tower.tiers.$tierKey"
                val displayName = config.getString("$tierPath.display-name", "\u00a7aTier")!!
                    .replace("&", "\u00a7")
                val description = config.getStringList("$tierPath.description")
                    .map { it.replace("&", "\u00a7") }
                val incomeBonus = config.getDouble("$tierPath.income-bonus", 0.0)
                val miningRateMultiplier = config.getDouble("$tierPath.mining-rate-multiplier", 1.0)
                val iconMaterial = config.getString("$tierPath.icon-material")

                _tiers[tierKey] = TierConfig(tierKey, displayName, description, incomeBonus, miningRateMultiplier, iconMaterial)
            }
        }

        if (_tiers.isEmpty()) {
            logger.severe("[MiningTower] No tiers loaded — check miningtower.yml!")
        }

        // ---- GUI ------------------------------------------------------------
        submissionGuiTitle = config.getString("mining-tower.gui.submission-title", submissionGuiTitle)!!
            .replace("&", "\u00a7")
        ratingGuiTitle = config.getString("mining-tower.gui.rating-title", ratingGuiTitle)!!
            .replace("&", "\u00a7")
        pendingGuiTitle = config.getString("mining-tower.gui.pending-title", pendingGuiTitle)!!
            .replace("&", "\u00a7")

        // ---- Messages -------------------------------------------------------
        submitSuccessMsg = config.getString("mining-tower.messages.submit-success", submitSuccessMsg)!!
            .replace("&", "\u00a7")
        submitCooldownMsg = config.getString("mining-tower.messages.submit-cooldown", submitCooldownMsg)!!
            .replace("&", "\u00a7")
        noPermissionSubmitMsg = config.getString("mining-tower.messages.no-permission-submit", noPermissionSubmitMsg)!!
            .replace("&", "\u00a7")
        noPermissionRateMsg = config.getString("mining-tower.messages.no-permission-rate", noPermissionRateMsg)!!
            .replace("&", "\u00a7")
        noPendingMsg = config.getString("mining-tower.messages.no-pending", noPendingMsg)!!
            .replace("&", "\u00a7")
        ratedSuccessMsg = config.getString("mining-tower.messages.rated-success", ratedSuccessMsg)!!
            .replace("&", "\u00a7")
        rejectedSuccessMsg = config.getString("mining-tower.messages.rejected-success", rejectedSuccessMsg)!!
            .replace("&", "\u00a7")
        teleportSuccessMsg = config.getString("mining-tower.messages.teleport-success", teleportSuccessMsg)!!
            .replace("&", "\u00a7")
        noTownMsg = config.getString("mining-tower.messages.no-town", noTownMsg)!!
            .replace("&", "\u00a7")

        // ---- Mining-rate buff -----------------------------------------------
        showMiningBuffMessage = config.getBoolean("mining-tower.mining-rate.show-buff-message", true)
        miningBuffRadius = config.getDouble("mining-tower.mining-rate.buff-radius", 100.0)

        // ---- Territory-entry notifications ----------------------------------
        showTerritoryEntryMessage = config.getBoolean("mining-tower.territory-entry.show-message", true)
        territoryEntryMessage = config.getString("mining-tower.territory-entry.message", territoryEntryMessage)!!
            .replace("&", "\u00a7")
        territoryEntrySubtitle = config.getString("mining-tower.territory-entry.subtitle", territoryEntrySubtitle)!!
            .replace("&", "\u00a7")
        showTerritoryEntryBossbar = config.getBoolean("mining-tower.territory-entry.show-bossbar", true)
        territoryEntryBossbarText = config.getString("mining-tower.territory-entry.bossbar-text", territoryEntryBossbarText)!!
            .replace("&", "\u00a7")
        territoryEntryBossbarColor = config.getString("mining-tower.territory-entry.bossbar-color", "YELLOW")!!
        territoryEntryBossbarStyle = config.getString("mining-tower.territory-entry.bossbar-style", "SOLID")!!
        territoryEntryBossbarDuration = config.getInt("mining-tower.territory-entry.bossbar-duration", 5)

        // ---- Debug ----------------------------------------------------------
        debugEnabled = config.getBoolean("mining-tower.debug.enabled", false)

        logger.info("[MiningTower] Loaded ${_tiers.size} tier(s): ${_tiers.keys.joinToString()}")
        logger.info("[MiningTower] Mining buff radius: $miningBuffRadius blocks")
        logger.info("[MiningTower] Debug mode: ${if (debugEnabled) "ENABLED" else "disabled"}")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns tiers sorted by their key (tier1 < tier2 < …). */
    fun getOrderedTiers(): List<TierConfig> = _tiers.values.sortedBy { it.id }
}

/**
 * Configuration for a single tier.
 */
data class TierConfig(
    val id: String,
    val displayName: String,
    val description: List<String>,
    val incomeBonus: Double,
    val miningRateMultiplier: Double = 1.0,
    /** Optional Bukkit Material name for the inventory icon. Null → positional default. */
    val iconMaterial: String? = null,
)
