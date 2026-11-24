/**
 * Config
 *
 * Contains global config state variables read in from
 * plugin config.yml file
 */

package phonon.nodes

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import phonon.nodes.Nodes
import phonon.nodes.objects.OreDeposit
import phonon.nodes.objects.ResourceAttribute
import phonon.nodes.objects.ResourceAttributeAnimals
import phonon.nodes.objects.ResourceAttributeAttackerTimeMultiplier
import phonon.nodes.objects.ResourceAttributeCrops
import phonon.nodes.objects.ResourceAttributeIncome
import phonon.nodes.objects.ResourceAttributeNeighborAttackerTimeMultiplier
import phonon.nodes.objects.ResourceAttributeNeighborTotalAnimalsMultiplier
import phonon.nodes.objects.ResourceAttributeNeighborTotalCropsMultiplier
import phonon.nodes.objects.ResourceAttributeNeighborTotalIncomeMultiplier
import phonon.nodes.objects.ResourceAttributeNeighborTotalOreMultiplier
import phonon.nodes.objects.ResourceAttributeOre
import phonon.nodes.objects.ResourceAttributeTotalAnimalsMultiplier
import phonon.nodes.objects.ResourceAttributeTotalCropsMultiplier
import phonon.nodes.objects.ResourceAttributeTotalIncomeMultiplier
import phonon.nodes.objects.ResourceAttributeTotalOreMultiplier
import phonon.nodes.objects.ResourceNode
import phonon.nodes.objects.TerritoryResources
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID
import kotlin.Comparator

public enum class TerritoryCaptureNotificationMode {
    TITLE,
    CHAT,

    ;

    public companion object {
        public fun fromName(raw: String?, fallback: TerritoryCaptureNotificationMode = TITLE): TerritoryCaptureNotificationMode = when (raw?.trim()?.lowercase()) {
            "title", "titles" -> TITLE
            "chat", "message", "messages" -> CHAT
            else -> fallback
        }
    }
}

public object Config {
    private fun nodeTypesPathFor(fileName: String) = Paths.get(pathPlugin, "world", fileName).normalize()
    private fun townShopPathFor(fileName: String) = Paths.get(pathPlugin, "town", fileName).normalize()
    private fun webOverviewPath() = Paths.get(pathPlugin, "web", "web_overview.yml").normalize()
    private fun killTrackerPath() = Paths.get(pathPlugin, "stats", "killtracker.yml").normalize()
    private fun warzonesPath() = Paths.get(pathPlugin, "war", "warzones.yml").normalize()
    private fun warzoneStatePath() = Paths.get(pathPlugin, "war", "warzone_state.yml").normalize()
    private fun miningOutminerPath() = Paths.get(pathPlugin, "mining-outminers").normalize()

    // ===================================
    // engine configs
    // ===================================
    // main plugin path for config and saves
    public var pathPlugin = "plugins/nodes"

    // folder for backups of json state files
    public var pathBackup = Paths.get("plugins/nodes/backup").normalize()

    // file names for world, towns, war json files
    public var pathWorld = Paths.get(pathPlugin, "world.json").normalize()
    public var pathTowns = Paths.get(pathPlugin, "towns.json").normalize()
    public var pathWar = Paths.get(pathPlugin, "war.json").normalize()
    public var pathTruce = Paths.get(pathPlugin, "truce.json").normalize()
    public var pathPorts = Paths.get(pathPlugin, "ports.json").normalize()
    public var pathLastBackupTime = Paths.get(pathPlugin, "lastBackupTime.txt").normalize()
    public var pathLastIncomeTime = Paths.get(pathPlugin, "lastIncomeTime.txt").normalize()
    public var pathLanguage = Paths.get(pathPlugin, "language").normalize()
    public var pathMarket = Paths.get(pathPlugin, "market").normalize()
    public var pathRally = Paths.get(pathPlugin, "rally").normalize()
    public var pathRallyHistory = pathRally.resolve("rally_history.json").normalize()
    public var pathMarketData = pathMarket.resolve("market_data.yml").normalize()
    public var pathMarketSalesHistory = pathMarket.resolve("market_sales_history.json").normalize()
    public var pathTownShop = townShopPathFor("town_shop.yml")
    public var pathWebOverview = webOverviewPath()
    public var pathNodeTypes = nodeTypesPathFor("nodeType.yml")
    public var pathKillTracker = killTrackerPath()
    public var pathWarzones = warzonesPath()
    public var pathWarzoneState = warzoneStatePath()
    public var pathMiningOutminer = miningOutminerPath()
    public var pathMiningOutminerData = pathMiningOutminer.resolve("outminers.yml").normalize()
    public var miningOutpostTextLines: List<String> = listOf("&bmining outpost")

    // disable world when nodes world.json or towns.json fails due to errors
    public var disableWorldWhenLoadFails = true

    // period for running world save
    public var savePeriod: Long = 600L

    // all long tick cycle values
    // 1 hour = 3600000 ms
    // 2 hour = 7200000 ms
    public var backupPeriod: Long = 3600000L // 1 hour

    // rally cap settings
    public var rallyCapEnabled: Boolean = true
    public var rallyCapSize: Int = 10
    public var rallyCapApplyToNations: Boolean = true // Apply to nations or just towns

    // main tick period check for backup, income, town + resident cooldown counters
    public var mainPeriodicTick: Long = 1200L

    // period to send reminder to players that town over max claims
    public var overMaxClaimsReminderPeriod: Long = 24000L

    // rally overview web endpoint + historical tracking
    public var rallyOverviewEnabled: Boolean = true
    public var rallyOverviewBindAddress: String = "0.0.0.0"
    public var rallyOverviewWebPort: Int = 8096
    public var rallyOverviewSamplePeriodMs: Long = 60000L
    public var rallyOverviewHistoryDays: Int = 30
    public var rallyOverviewPageTitle: String = "Nation Rally Stats"
    public var rallyOverviewDefaultLastHours: Int = 24
    public var rallyOverviewMaxRows: Int = 200
    public var rallyOverviewAutoRefreshSeconds: Int = 30
    public var rallyOverviewSortBy: String = "avg"
    public var rallyOverviewResponseCacheMs: Long = 1500L

    // market overview web endpoint + sales analytics tracking
    public var marketOverviewEnabled: Boolean = true
    public var marketOverviewBindAddress: String = "0.0.0.0"
    public var marketOverviewWebPort: Int = 8097
    public var marketOverviewHistoryDays: Int = 60
    public var marketOverviewPageTitle: String = "Town Market Overview"
    public var marketOverviewDefaultLastHours: Int = 24
    public var marketOverviewMaxRows: Int = 200
    public var marketOverviewAutoRefreshSeconds: Int = 30
    public var marketOverviewSortBy: String = "units"
    public var marketOverviewResponseCacheMs: Long = 1500L

    // integrated nodes web portal
    public var webOverviewEnabled: Boolean = true
    public var webOverviewBindAddress: String = "0.0.0.0"
    public var webOverviewWebPort: Int = 8095
    public var webOverviewPageTitle: String = "Nodes Web Portal"
    public var webOverviewServerName: String = "Siege Nodes"
    public var webOverviewSubtitle: String = "Everything in one place: map, shop, rally, market, discord"
    public var webOverviewFooterText: String = "Powered by Nodes"
    public var webOverviewHeroBackgroundUrl: String = ""
    public var webOverviewMainImageUrl: String = ""
    public var webOverviewPrimaryColor: String = "#3ba9ff"
    public var webOverviewSecondaryColor: String = "#0f2e5f"
    public var webOverviewAccentColor: String = "#8ad4ff"
    public var webOverviewBackgroundTop: String = "#071124"
    public var webOverviewBackgroundBottom: String = "#030913"
    public val webOverviewButtons: LinkedHashMap<String, WebButtonConfig> = linkedMapOf()
    public val webOverviewNews: ArrayList<WebNewsItem> = arrayListOf()
    public val webOverviewTips: ArrayList<String> = arrayListOf()
    public val webOverviewFeatures: ArrayList<WebFeatureItem> = arrayListOf()
    public var webOverviewResponseCacheMs: Long = 1500L

    // nametag update period
    //
    // The old two-tier priority/burst pipeline has been replaced by a single
    // dirty queue drained at a fixed rate. nametagUpdatesPerTick is the
    // number of viewers refreshed each server tick — at 20 per tick, a
    // full 700-player refresh completes in 35 ticks (1.75s).
    //
    // The old fields (nametagPipelineTicks, nametagPriorityTicks,
    // nametagBurstTicks) are retained as no-ops for config-file backward
    // compatibility; they are no longer read.
    public var nametagUpdatesPerTick: Int = 20
    public var nametagPipelineTicks: Int = 16 // DEPRECATED — no longer used
    public var nametagPriorityTicks: Int = 20 // DEPRECATED — no longer used
    public var nametagBurstTicks: Int = 200 // DEPRECATED — no longer used
    public var nametagMinUpdateIntervalMs: Long = 1000L
    public var nametagLuckPermsPrefixEnabled: Boolean = false
    public var nametagLuckPermsPrefixStaffOnly: Boolean = false
    public var nametagLuckPermsPrefixPlaceholder: String = "%luckperms_prefix%"
    public var nametagLuckPermsPrefixCacheMs: Long = 30000L
    public var nametagProximityEnabled: Boolean = false
    public var nametagProximityRadius: Double = 176.0
    public var nametagProximityRadiusSquared: Double = 176.0 * 176.0
    public var nametagProximityRefreshTicks: Long = 100L

    // Colour overrides for each nametag relation.
    //
    // Each Town ships with pre-built Components whose colours are baked in
    // by the Town class itself. Those defaults are fine for TOWN (green) and
    // ENEMY (red) but historically NATION was also white — indistinguishable
    // from NEUTRAL. These overrides let you force a specific ChatFormatting
    // colour per relation, regardless of the Town's baked defaults.
    //
    // Valid values are the ChatFormatting enum names:
    //   BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE,
    //   GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE,
    //   YELLOW, WHITE, RESET.
    //
    // An empty string means "use the Town's native component as-is"
    // (no override). An unrecognised value is treated as empty.
    //
    // Defaults:
    //   TOWN    — GREEN       (your own town)
    //   NATION  — DARK_GREEN  (allied by nation membership)
    //   ALLY    — AQUA        (formal ally, distinct from nation)
    //   ENEMY   — RED         (at war)
    //   NEUTRAL — YELLOW      (no relation)
    public var nametagTownColor: String = "GREEN"
    public var nametagNationColor: String = "DARK_GREEN"
    public var nametagAllyColor: String = "AQUA"
    public var nametagEnemyColor: String = "RED"
    public var nametagNeutralColor: String = "YELLOW"

    // tab list integration
    public var tabEnabled: Boolean = true
    public var tabUseLayout: Boolean = true
    public var tabRefreshTicks: Int = 40
    public var tabSkipUnchangedUpdates: Boolean = true
    public var tabUnchangedForceRefreshMs: Long = 30000L
    public var tabHighLoadPlayerThreshold: Int = 220
    public var tabHighLoadMinIntervalMs: Long = 5000L
    public var tabStaffWeightThreshold: Int = 60
    public var tabStaffWeightPlaceholder: String = "%luckperms_meta_weight%"
    public var tabStaffWeightCacheMs: Long = 30000L
    public var tabStaffPrefixPlaceholder: String = "%luckperms_prefix%"
    public var tabStaffFallbackPermission: String = "nodes.staff"
    public var tabColumnWidth: Int = 24
    public var tabMaxEntriesPerColumn: Int = 8
    public var tabFooterRows: Int = 18
    public var tabFallbackSpacerRows: Int = 8

    // minimap settings
    public var minimapSizeDefault: Int = 5
    public var minimapSizeMin: Int = 3
    public var minimapSizeMax: Int = 5
    public var minimapOnlineScalingEnabled: Boolean = false
    public var minimapOnlineScalingRules: List<MinimapSizeRule> = listOf()

    // force copy to dynmap folder
    public var dynmapCopyTowns: Boolean = false

    // map nation name -> webhook url
    public var discordWebhooks: HashMap<String, String> = hashMapOf()

    // cooldown for /report command in milliseconds (default 300s)
    public var reportCooldown: Long = 300000L

    // ===================================
    // resource configs
    // ===================================
    // territory do income enabled and income time
    public var incomeEnabled: Boolean = true
    public var incomePeriod: Long = 3600000L

    // enable income rate scaling based on percent of town claim power used
    // e.g. if 100 claim power is needed for all territories
    // and town players have total of 60 claim power
    // then income rate is scaled by,
    //     r = (sum of player claim power) / (sum of territory claim)
    //       = 60 / 100
    //       = 0.6
    public var incomeScaleByClaimPower: Boolean = true
    public var incomeScaleMin: Double = 0.1 // minimum income scaling factor
    public var incomeScaleMax: Double = 1.0 // maximum income scaling factor, >1.0 allowed

    // global resource node in all territories
    public var globalResources = TerritoryResources()
    public var useTerritoryResourceValues: Boolean = true

    // node types (special modifiers), loaded from nodeType.yml
    public var nodeTypes: HashMap<String, ResourceNode> = hashMapOf()

    // hidden ore blocks, stone only
    public var oreBlocks = EnumSet.of(
        Material.STONE, // note: granite, diorite, and andesite are variants of stone in 1.12.2
        Material.ANDESITE,
        Material.GRANITE,
        Material.DIORITE,
        Material.DEEPSLATE,
        Material.TUFF,
        Material.CALCITE,
        Material.DRIPSTONE_BLOCK,
    )

    // harvestable crop types affected by occupied territory tax

    // version 1.12
    // public var cropTypes = EnumSet.of(
    //     Material.CROPS, // wheat
    //     Material.POTATO,
    //     Material.CARROT,
    //     Material.BEETROOT_BLOCK,
    //     Material.PUMPKIN,
    //     Material.MELON_BLOCK,
    //     Material.SUGAR_CANE_BLOCK,
    //     Material.COCOA,
    //     Material.CACTUS
    // )

    // version 1.16
    public var cropTypes = EnumSet.of(
        Material.BEETROOTS,
        Material.CACTUS,
        Material.CARROTS,
        Material.KELP,
        Material.COCOA,
        Material.MELON,
        Material.NETHER_WART,
        Material.POTATOES,
        Material.PUMPKIN,
        Material.SUGAR_CANE,
        Material.SWEET_BERRY_BUSH,
        Material.WHEAT,
    )

    // map block type -> alternative editor name
    public var cropAlternativeNames: EnumMap<Material, Material> = {
        val alternativeNames: EnumMap<Material, Material> = EnumMap<Material, Material>(Material::class.java)

        alternativeNames.put(Material.MELON_STEM, Material.MELON)
        alternativeNames.put(Material.PUMPKIN_STEM, Material.PUMPKIN)
        alternativeNames.put(Material.SWEET_BERRY_BUSH, Material.SWEET_BERRIES)

        alternativeNames
    }()

    // allow mining/harvesting/breeding in unowned territories
    public var allowOreInWilderness: Boolean = false
    public var allowCropsInWilderness: Boolean = false
    public var allowBreedingInWilderness: Boolean = false

    // allow getting ore in captured territory
    public var allowOreInCaptured: Boolean = true

    // allow mining in other towns in nation
    public var allowOreInNationTowns: Boolean = true

    // required sky light for crops and animal breeding (set = 0 to disable)
    public var cropsMinSkyLight: Int = 14
    public var breedingMinSkyLight: Int = 14

    // min/max y levels allowed for crop growth and animal breeding
    public var cropsMinYHeight: Int = 10
    public var cropsMaxYHeight: Int = 255
    public var breedingMinYHeight: Int = 10
    public var breedingMaxYHeight: Int = 255

    // ===================================
    // afk kick time
    // ===================================
    // time in milliseconds for afk time before player kicked
    // adjusts claims progress as penalty
    // 10 min = 600000 ms
    // 20 min = 1200000 ms
    public var afkKickTime: Long = 900000L

    // ===================================
    // permissions
    // ===================================
    // interact in area with NO TERRITORIES (build, destroy, etc...)
    public var canInteractInEmpty: Boolean = true

    // interact in territory without town (build, destroy, etc...)
    public var canInteractInUnclaimed: Boolean = true

    // only allow shearing in sheep node
    public var requireSheepNodeToShear: Boolean = true

    // punish block placement when lacking permissions
    public var punishBlockGlitching: Boolean = false
    public var punishBlockGlitchingDurationTicks: Int = 60 // 3 seconds
    public var punishBlockGlitchingPoisonAmplifier: Int = 0
    public var punishBlockGlitchingBlindnessAmplifier: Int = 0
    public var punishBlockGlitchingDarknessAmplifier: Int = 0
    public var punishBlockGlitchingAttempts: Int = 3
    public var punishBlockGlitchingWindowTicks: Int = 60
    public var disableRedstoneTicking: Boolean = false

    // attack boss bar options
    public var attackBossBarShowTimeRemaining: Boolean = false

    // ===================================
    // town cooldowns
    // ===================================
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    public var townCreateCooldown: Long = 172800000L

    public var townMoveHomeCooldown: Long = 172800000L

    // ===================================
    // town creation cost (Vault economy)
    // ===================================
    // totalCost = townCreateCostFlatFee + territory.cost
    // territory.cost is already calculated from:
    //   territoryCostBase + territoryCostScale * chunks.size
    // e.g. flat=$500, territory.cost=30 → total = $530
    public var townCreateCostEnabled: Boolean = false
    public var townCreateCostFlatFee: Double = 500.0

    // ===================================
    // claiming system selector
    // ===================================
    // Top-level switch between the two claiming models. On config load this
    // string is used to derive the fine-grained flags below; users who want
    // full manual control can set this to "CUSTOM" and configure the flags
    // directly. Any other value is treated as "POWER".
    //
    //   POWER   — the original claim-power budget system. claimsUsed must
    //             fit under town.claimsMax. No money is charged.
    //             Derived:  claimsPowerEnabled=true
    //                       townClaimCostEnabled=false
    //
    //   VAULT   — money only. Each claim costs territory.cost in Vault
    //             money; claim power is disabled entirely so a town with
    //             money and residents can claim without a power budget.
    //             Pair with townClaimPlayerRatioEnabled to cap size.
    //             Derived:  claimsPowerEnabled=false
    //                       townClaimCostEnabled=true
    //
    //   HYBRID  — both. Power budget AND money charge. The claim is
    //             rejected (and money refunded) if either check fails.
    //             Derived:  claimsPowerEnabled=true
    //                       townClaimCostEnabled=true
    //
    //   CUSTOM  — do not derive anything; the flags below are used exactly
    //             as you set them. Use this if you were already on the old
    //             config layout or want to configure an unusual combo.
    public var townClaimSystem: String = "POWER"

    // ===================================
    // town claim cost (Vault economy)
    // ===================================
    // When enabled, claiming a territory costs Vault money equal to
    // territory.cost (= territoryCostBase + territoryCostScale * chunks.size).
    //
    // This flag is derived from townClaimSystem unless townClaimSystem is
    // set to CUSTOM. If you set it manually while townClaimSystem is
    // POWER/VAULT/HYBRID, your value will be overwritten at config load.
    public var townClaimCostEnabled: Boolean = false

    // ===================================
    // town claim rate limit (cooldown)
    // ===================================
    // When enabled, a town may only claim up to townClaimCooldownMaxClaims
    // territories within a rolling townClaimCooldownPeriod window.
    // After hitting the cap, they must wait for the window to expire.
    // e.g. maxClaims=3, period=86400000 (24h) → 3 claims per day
    public var townClaimCooldownEnabled: Boolean = false
    public var townClaimCooldownPeriod: Long = 86400000L // 24 hours in ms
    public var townClaimCooldownMaxClaims: Int = 3

    // ===================================
    // town claim player ratio
    // ===================================
    // When enabled, limits how many territories a town can hold based on
    // resident count. Checked before every /town claim.
    //   maxTerritories = residents.size * townClaimPlayerRatioPerPlayer
    // e.g. ratio=30, 1 player → max 30 territories; 3 players → max 90
    //
    // Pair with townClaimCostEnabled=true and claimsPowerEnabled=false to
    // use money as the sole claiming system instead of claim power.
    public var townClaimPlayerRatioEnabled: Boolean = false
    public var townClaimPlayerRatioPerPlayer: Int = 30 // territories per resident

    // ===================================
    // town rejoin cooldown
    // ===================================
    // When enabled, players who leave or are kicked must wait before
    // joining any town again. Prevents hopping to inflate claim power.
    // 24h = 86400000 | 48h = 172800000
    public var townRejoinCooldownEnabled: Boolean = false
    public var townRejoinCooldown: Long = 86400000L

    // ===================================
    // town claim configs
    // ===================================
    // territory cost = base + scale * chunks
    public var territoryCostBase: Int = 10
    public var territoryCostScale: Double = 0.25

    // claim power and territory cost systems
    public var claimsPowerEnabled: Boolean = true
    public var territoryCostEnabled: Boolean = true

    // initial player given on town creation
    public var townInitialClaims: Int = 25

    // penalty scale factor when over initial claim allowed:
    // penalty = scale * (territory.cost - initialAllowed) if cost > allowed
    public var initialOverClaimsAmountScale: Int = 2

    // base number of claim power per town
    public var townClaimsBase: Int = 20

    // max claim power per town (-1 for unlimited)
    public var townClaimsMax: Int = -1

    // town penalty decay amount
    public var townPenaltyDecay: Int = 2

    // claim power per player
    public var playerClaimsInitial: Int = 0 // initial player claims on town join
    public var playerClaimsMax: Int = 20 // max player claims for town
    public var playerClaimsIncrease: Int = 1 // claims increase per tick period

    // time periods for town claims penalty decay and player power gain, milliseconds
    // 1 hour = 3600000 ms
    // 2 hour = 7200000 ms
    public var townClaimsPenaltyDecayPeriod: Long = 3600000L
    public var playerClaimsIncreasePeriod: Long = 3600000L

    // flag for enforcing over max claims
    // for now, this affects all systems (income, hidden ore, crops,
    // breeding, etc...)
    // TODO: make fine grained over claims penalty for each system
    public var overClaimsPenalty: Boolean = true

    // reduced resource rate when over max claim (runs Math.random() < rate)
    public var overClaimsMaxPenalty: Double = 0.5

    // Over-max-claim behaviour.
    //
    // overClaimsAllowClaim (default false):
    //   false — STRICT MODE (default, enforced). A claim is rejected if it
    //           would push claimsUsed past claimsMax. If the town is already
    //           over max (e.g. after losing residents), every further claim
    //           is rejected regardless of size.
    //   true  — the claim is allowed to push claimsUsed past claimsMax,
    //           bounded by overClaimsMaxExcess (see below).
    //
    // overClaimsMaxExcess (default -1):
    //   Only meaningful when overClaimsAllowClaim = true. Bounds how far
    //   past claimsMax the town can reach via claims. After the claim,
    //   claimsUsed must be <= claimsMax + overClaimsMaxExcess, otherwise
    //   the claim is rejected.
    //     -1   — unlimited excess (old unbounded behaviour; can claim
    //            arbitrarily large nodes while already far over the cap).
    //      0   — equivalent to overClaimsAllowClaim=false in steady state:
    //            no excess beyond claimsMax is ever allowed.
    //      N>0 — the town may go up to N over claimsMax.
    //
    // If you previously saw "you can still claim huge nodes while over max"
    // with overClaimsAllowClaim=true, set overClaimsMaxExcess to a small
    // positive value (or 0) to bound it.
    public var overClaimsAllowClaim: Boolean = false
    public var overClaimsMaxExcess: Int = -1

    // annexation settings
    // only allow annexing during war time
    public var canOnlyAnnexDuringWar: Boolean = true

    // ===================================
    // town settings
    // ===================================
    // town spawn timer in seconds (converted to ticks by num * 20)
    public var townSpawnTime: Int = 10

    // outpost configs
    public val outpostTeleportCost: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)

    // inline string list of outpost teleport cost
    public var outpostTeleportCostString: String = ""

    // outpost warp time in ticks, selected by outpost tier
    public var outpostWarpTimeTier1: Double = 60.0 * 20.0
    public var outpostWarpTimeTier2: Double = 30.0 * 20.0
    public var outpostWarpTimeTier3: Double = 15.0 * 20.0

    // town points progression
    public var townPointsPerPlayerPerMinute: Long = 1L

    // town shop file name under plugins/nodes
    public var townShopFile: String = "town_shop.yml"
    public val townShopEntries: LinkedHashMap<String, TownShopEntry> = linkedMapOf()
    public var nodeTypeFile: String = "nodeType.yml"

    // ===================================
    // nation settings
    // ===================================
    // allow spawning in nation towns
    public var allowNationTownSpawn: Boolean = false

    // cost for nation town spawn
    public val nationTownTeleportCost: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)

    // inline string list of outpost teleport cost
    public var nationTownTeleportCostString: String = ""

    // allow members of the same nation to attack eachother
    public var allowNationFriendlyFire: Boolean = false

    // ===================================
    // alliance settings
    // ===================================
    // allow allied nations to attack eachother
    public var allowAllyFriendlyFire: Boolean = true

    // ===================================
    // captured territory tax rates:
    // (taxation is theft)
    // ===================================
    // fraction of territory income that goes to occupier
    public var taxIncomeRate: Double = 0.2

    // probability that a hidden ore event resources go to occupier
    public var taxMineRate: Double = 0.2

    // probability that all of a crop's harvest items go to occupier
    public var taxFarmRate: Double = 0.2

    // probability that a new animal goes to occupier (as a spawn egg item)
    public var taxAnimalRate: Double = 0.2

    // ===================================
    // war configs
    // ===================================
    // Nodes internal explosion block damage restrictions
    public var restrictExplosions: Boolean = true
    public var onlyAllowExplosionsDuringWar: Boolean = true

    public var flagMaterialDefault: Material = Material.OAK_FENCE

    public var flagMaterials: EnumSet<Material> = EnumSet.of(
        Material.ACACIA_FENCE,
        Material.BIRCH_FENCE,
        Material.BAMBOO_FENCE,
        Material.CHERRY_FENCE,
        Material.DARK_OAK_FENCE,
        Material.JUNGLE_FENCE,
        Material.MANGROVE_FENCE,
        Material.OAK_FENCE,
        Material.SPRUCE_FENCE,
        Material.CRIMSON_FENCE,
        Material.WARPED_FENCE,
        Material.PALE_OAK_FENCE,
        Material.CHERRY_FENCE,
        Material.SNIFFER_EGG, // easter egg hehe
    )

    // disable building within this distance of flag (square range)
    public var flagNoBuildDistance: Int = 1

    // disable building for y >= flag base block + flagNoBuildYOffset
    public var flagNoBuildYOffset: Int = -1

    // ticks required to capture chunk
    public var chunkAttackTime: Long = 200

    // multiplier for chunk attacks
    public var chunkAttackFromWastelandMultiplier: Double = 2.0 // territory next to wilderness
    public var chunkAttackHomeMultiplier: Double = 2.0
    public var chunkAttackHomeTime: Long = -1L // if > 0, overrides base*multiplier for home chunks

    // number of chunks a player can attack at same time
    public var maxPlayerChunkAttacks: Int = 1

    // allow breaking allies war flags
    public var allowBreakingAlliesFlags = false

    // block directly below torch in active flag marker
    public var flagTopBlockMaterial: Material = Material.CRYING_OBSIDIAN

    // flag sky beacon config
    public var flagBeaconSize: Int = 6 // be in range [2, 16]
    public var flagBeaconMinSkyLevel: Int = 100 // minimum height level in sky
    public var flagBeaconSkyLevel: Int = 50 // height level above blocks
    public var flagPlacementMinHeight: Int = 0 // minimum Y for placing a war flag
    public var flagPlacementMaxHeight: Int = 252 // maximum Y for placing a war flag (needs space for flag + torch)

    // allow war permissions during skirmish mode
    public var allowDestructionDuringSkirmish: Boolean = false

    // bypass permissions and allow extended ally interactions in towns
    public var warPermissions: Boolean = true

    // allow leaving towns/natiosn during war
    public var canLeaveTownDuringWar: Boolean = true

    // allow creating towns, nation stuff during war
    public var canCreateTownDuringWar: Boolean = false
    public var canDestroyTownDuringWar: Boolean = false
    public var canLeaveNationDuringWar: Boolean = false

    // global disable annexing
    public var annexDisabled: Boolean = false

    public var pathWarAccessLists: Path = Paths.get(pathPlugin, "war", "war_access.json").normalize()

    // Leaderlaunch slot defaults (+clamp)
    public var leaderLaunchDefaultSlots: Int = 10
    public var leaderLaunchMaxSlots: Int = 30

    // annex blacklist: cannot annex these towns, only occupy
    // whitelist settings

    // only towns in whitelist can annex territories (from other whitelist territories)
    public var onlyWhitelistCanAnnex: Boolean = true
    public var onlyWhitelistCanClaim: Boolean = true

    // multiplier for warping home when occupied
    public var occupiedHomeTeleportMultiplier: Double = 12.0

    // List of town UUIDs to allow building in occupied territory.
    // War whitelist often used to create AI towns that can be attacked
    // by anyone. People want to build in occupied territory from these
    // towns during non-war time. This list allows building/interacting
    // in these occupied towns.
    public var allowControlInOccupiedTownList: HashSet<UUID> = hashSetOf()

    // ===================================
    // truce configs
    // ===================================

    // truce period in milliseconds
    // (truce counter uses System.currentTimeMillis)
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    public var trucePeriod: Long = 172800000L

    // ===================================
    // port configs
    // ===================================
    public var seaLevel: Double = 62.0
    public var portWarpTime: Double = 5.0 * 20.0 // in ticks
    public var portWarpTimeTier1: Double = 30.0 * 20.0 // in ticks
    public var portWarpTimeTier2: Double = 15.0 * 20.0 // in ticks
    public var portWarpTimeTier3: Double = 5.0 * 20.0 // in ticks
    public var allowPortWarpWithoutBoat: Boolean = false

    // ===================================================
    // Load config
    // ===================================================
    public fun load(config: FileConfiguration) {
        phonon.nodes.core.CoreHooks.prepare(config)
        // engine settings
        Config.disableWorldWhenLoadFails = config.getBoolean("disableWorldWhenLoadFails", Config.disableWorldWhenLoadFails)
        Config.savePeriod = config.getLong("savePeriod", Config.savePeriod)
        Config.backupPeriod = config.getLong("backupPeriod", Config.backupPeriod)
        Config.mainPeriodicTick = config.getLong("mainPeriodicTick", Config.mainPeriodicTick)
        Config.overMaxClaimsReminderPeriod = config.getLong("overMaxClaimsReminderPeriod", Config.overMaxClaimsReminderPeriod)
        Config.nodeTypeFile = config.getString("nodeTypeFile", Config.nodeTypeFile)?.trim().orEmpty().ifEmpty { "nodeType.yml" }
        Config.pathLanguage = Paths.get(Config.pathPlugin, "language").normalize()
        Config.pathMarket = Paths.get(Config.pathPlugin, "market").normalize()
        Config.pathRally = Paths.get(Config.pathPlugin, "rally").normalize()
        Config.pathRallyHistory = Config.pathRally.resolve("rally_history.json").normalize()
        Config.pathMarketData = Config.pathMarket.resolve("market_data.yml").normalize()
        Config.pathMarketSalesHistory = Config.pathMarket.resolve("market_sales_history.json").normalize()
        Config.pathNodeTypes = nodeTypesPathFor(Config.nodeTypeFile)
        Config.pathKillTracker = killTrackerPath()
        Config.pathWarzones = warzonesPath()
        Config.pathMiningOutminer = miningOutminerPath()
        Config.pathMiningOutminerData = Config.pathMiningOutminer.resolve("outminers.yml").normalize()
        val miningOutpostSection = config.getConfigurationSection("miningoutpostText")
        if (miningOutpostSection != null) {
            val ordered = miningOutpostSection.getKeys(false)
                .sortedWith(
                    Comparator { a, b ->
                        val na = Regex("\\d+").find(a)?.value?.toIntOrNull() ?: Int.MAX_VALUE
                        val nb = Regex("\\d+").find(b)?.value?.toIntOrNull() ?: Int.MAX_VALUE
                        if (na != nb) na - nb else a.compareTo(b)
                    },
                )
            val lines = ordered.mapNotNull { key ->
                miningOutpostSection.getString(key)?.trim()
            }.filter { it.isNotEmpty() && !it.startsWith("MemorySection[path='miningoutpostText'") }
            if (lines.isNotEmpty()) {
                Config.miningOutpostTextLines = lines
            }
        } else {
            val legacyText = config.getString("miningoutpostText")?.trim()
            if (!legacyText.isNullOrEmpty()) {
                Config.miningOutpostTextLines = listOf(legacyText)
            }
        }
        val rallySection = config.getConfigurationSection("rallyOverview")
            ?: config.getConfigurationSection("rallyOverwie")
        if (rallySection != null) {
            Config.rallyOverviewEnabled = rallySection.getBoolean("enabled", Config.rallyOverviewEnabled)
            Config.rallyOverviewBindAddress = rallySection.getString("bindAddress", Config.rallyOverviewBindAddress) ?: Config.rallyOverviewBindAddress
            Config.rallyOverviewWebPort = rallySection.getInt("webPort", Config.rallyOverviewWebPort)
            Config.rallyOverviewSamplePeriodMs = rallySection.getLong("samplePeriodMs", Config.rallyOverviewSamplePeriodMs)
            Config.rallyOverviewHistoryDays = rallySection.getInt("historyDays", Config.rallyOverviewHistoryDays)
            Config.rallyOverviewPageTitle = rallySection.getString("pageTitle", Config.rallyOverviewPageTitle) ?: Config.rallyOverviewPageTitle
            Config.rallyOverviewDefaultLastHours = rallySection.getInt("defaultLastHours", Config.rallyOverviewDefaultLastHours)
            Config.rallyOverviewMaxRows = rallySection.getInt("maxRows", Config.rallyOverviewMaxRows)
            Config.rallyOverviewAutoRefreshSeconds = rallySection.getInt("autoRefreshSeconds", Config.rallyOverviewAutoRefreshSeconds)
            Config.rallyOverviewSortBy = rallySection.getString("sortBy", Config.rallyOverviewSortBy) ?: Config.rallyOverviewSortBy
            Config.rallyOverviewResponseCacheMs = rallySection.getLong("responseCacheMs", Config.rallyOverviewResponseCacheMs)
        } else {
            // legacy flat keys fallback
            Config.rallyOverviewEnabled = config.getBoolean("rallyOverviewEnabled", Config.rallyOverviewEnabled)
            Config.rallyOverviewBindAddress = config.getString("rallyOverviewBindAddress", Config.rallyOverviewBindAddress) ?: Config.rallyOverviewBindAddress
            Config.rallyOverviewWebPort = config.getInt("rallyOverviewWebPort", Config.rallyOverviewWebPort)
            Config.rallyOverviewSamplePeriodMs = config.getLong("rallyOverviewSamplePeriodMs", Config.rallyOverviewSamplePeriodMs)
            Config.rallyOverviewHistoryDays = config.getInt("rallyOverviewHistoryDays", Config.rallyOverviewHistoryDays)
            Config.rallyOverviewPageTitle = config.getString("rallyOverviewPageTitle", Config.rallyOverviewPageTitle) ?: Config.rallyOverviewPageTitle
            Config.rallyOverviewDefaultLastHours = config.getInt("rallyOverviewDefaultLastHours", Config.rallyOverviewDefaultLastHours)
            Config.rallyOverviewMaxRows = config.getInt("rallyOverviewMaxRows", Config.rallyOverviewMaxRows)
            Config.rallyOverviewAutoRefreshSeconds = config.getInt("rallyOverviewAutoRefreshSeconds", Config.rallyOverviewAutoRefreshSeconds)
            Config.rallyOverviewSortBy = config.getString("rallyOverviewSortBy", Config.rallyOverviewSortBy) ?: Config.rallyOverviewSortBy
            Config.rallyOverviewResponseCacheMs = config.getLong("rallyOverviewResponseCacheMs", Config.rallyOverviewResponseCacheMs)
        }
        Config.rallyOverviewWebPort = Config.rallyOverviewWebPort.coerceIn(1, 65535)
        Config.rallyOverviewSamplePeriodMs = Config.rallyOverviewSamplePeriodMs.coerceAtLeast(1000L)
        Config.rallyOverviewHistoryDays = Config.rallyOverviewHistoryDays.coerceAtLeast(1)
        Config.rallyOverviewDefaultLastHours = Config.rallyOverviewDefaultLastHours.coerceAtLeast(1)
        Config.rallyOverviewMaxRows = Config.rallyOverviewMaxRows.coerceIn(1, 2000)
        Config.rallyOverviewAutoRefreshSeconds = Config.rallyOverviewAutoRefreshSeconds.coerceAtLeast(0)
        Config.rallyOverviewResponseCacheMs = Config.rallyOverviewResponseCacheMs.coerceAtLeast(0L)
        Config.rallyOverviewSortBy = Config.rallyOverviewSortBy.lowercase().let {
            if (it == "avg" || it == "peak" || it == "current") it else "avg"
        }

        val marketOverviewSection = config.getConfigurationSection("marketOverview")
        if (marketOverviewSection != null) {
            Config.marketOverviewEnabled = marketOverviewSection.getBoolean("enabled", Config.marketOverviewEnabled)
            Config.marketOverviewBindAddress = marketOverviewSection.getString("bindAddress", Config.marketOverviewBindAddress) ?: Config.marketOverviewBindAddress
            Config.marketOverviewWebPort = marketOverviewSection.getInt("webPort", Config.marketOverviewWebPort)
            Config.marketOverviewHistoryDays = marketOverviewSection.getInt("historyDays", Config.marketOverviewHistoryDays)
            Config.marketOverviewPageTitle = marketOverviewSection.getString("pageTitle", Config.marketOverviewPageTitle) ?: Config.marketOverviewPageTitle
            Config.marketOverviewDefaultLastHours = marketOverviewSection.getInt("defaultLastHours", Config.marketOverviewDefaultLastHours)
            Config.marketOverviewMaxRows = marketOverviewSection.getInt("maxRows", Config.marketOverviewMaxRows)
            Config.marketOverviewAutoRefreshSeconds = marketOverviewSection.getInt("autoRefreshSeconds", Config.marketOverviewAutoRefreshSeconds)
            Config.marketOverviewSortBy = marketOverviewSection.getString("sortBy", Config.marketOverviewSortBy) ?: Config.marketOverviewSortBy
            Config.marketOverviewResponseCacheMs = marketOverviewSection.getLong("responseCacheMs", Config.marketOverviewResponseCacheMs)
        } else {
            // legacy flat keys fallback
            Config.marketOverviewEnabled = config.getBoolean("marketOverviewEnabled", Config.marketOverviewEnabled)
            Config.marketOverviewBindAddress = config.getString("marketOverviewBindAddress", Config.marketOverviewBindAddress) ?: Config.marketOverviewBindAddress
            Config.marketOverviewWebPort = config.getInt("marketOverviewWebPort", Config.marketOverviewWebPort)
            Config.marketOverviewHistoryDays = config.getInt("marketOverviewHistoryDays", Config.marketOverviewHistoryDays)
            Config.marketOverviewPageTitle = config.getString("marketOverviewPageTitle", Config.marketOverviewPageTitle) ?: Config.marketOverviewPageTitle
            Config.marketOverviewDefaultLastHours = config.getInt("marketOverviewDefaultLastHours", Config.marketOverviewDefaultLastHours)
            Config.marketOverviewMaxRows = config.getInt("marketOverviewMaxRows", Config.marketOverviewMaxRows)
            Config.marketOverviewAutoRefreshSeconds = config.getInt("marketOverviewAutoRefreshSeconds", Config.marketOverviewAutoRefreshSeconds)
            Config.marketOverviewSortBy = config.getString("marketOverviewSortBy", Config.marketOverviewSortBy) ?: Config.marketOverviewSortBy
            Config.marketOverviewResponseCacheMs = config.getLong("marketOverviewResponseCacheMs", Config.marketOverviewResponseCacheMs)
        }
        Config.marketOverviewWebPort = Config.marketOverviewWebPort.coerceIn(1, 65535)
        Config.marketOverviewHistoryDays = Config.marketOverviewHistoryDays.coerceAtLeast(1)
        Config.marketOverviewDefaultLastHours = Config.marketOverviewDefaultLastHours.coerceAtLeast(1)
        Config.marketOverviewMaxRows = Config.marketOverviewMaxRows.coerceIn(1, 2000)
        Config.marketOverviewAutoRefreshSeconds = Config.marketOverviewAutoRefreshSeconds.coerceAtLeast(0)
        Config.marketOverviewResponseCacheMs = Config.marketOverviewResponseCacheMs.coerceAtLeast(0L)
        Config.marketOverviewSortBy = Config.marketOverviewSortBy.lowercase().let {
            if (it == "units" || it == "transactions" || it == "revenue") it else "units"
        }

        Config.nametagUpdatesPerTick = config.getInt("nametagUpdatesPerTick", Config.nametagUpdatesPerTick)
        Config.nametagPipelineTicks = config.getInt("nametagPipelineTicks", Config.nametagPipelineTicks)
        Config.nametagPriorityTicks = config.getInt("nametagPriorityTicks", Config.nametagPriorityTicks)
        Config.nametagBurstTicks = config.getInt("nametagBurstTicks", Config.nametagBurstTicks)
        Config.nametagMinUpdateIntervalMs = config.getLong("nametagMinUpdateIntervalMs", Config.nametagMinUpdateIntervalMs)
        Config.nametagLuckPermsPrefixEnabled = config.getBoolean("nametagLuckPermsPrefixEnabled", Config.nametagLuckPermsPrefixEnabled)
        Config.nametagLuckPermsPrefixStaffOnly = config.getBoolean("nametagLuckPermsPrefixStaffOnly", Config.nametagLuckPermsPrefixStaffOnly)
        Config.nametagLuckPermsPrefixPlaceholder =
            config.getString("nametagLuckPermsPrefixPlaceholder", Config.nametagLuckPermsPrefixPlaceholder)
                ?: Config.nametagLuckPermsPrefixPlaceholder
        Config.nametagLuckPermsPrefixCacheMs = config.getLong("nametagLuckPermsPrefixCacheMs", Config.nametagLuckPermsPrefixCacheMs)
        Config.nametagProximityEnabled = config.getBoolean("nametagProximityEnabled", Config.nametagProximityEnabled)
        Config.nametagProximityRadius = config.getDouble("nametagProximityRadius", Config.nametagProximityRadius)
        Config.nametagProximityRefreshTicks = config.getLong("nametagProximityRefreshTicks", Config.nametagProximityRefreshTicks)
        Config.nametagTownColor = config.getString("nametagTownColor", Config.nametagTownColor) ?: Config.nametagTownColor
        Config.nametagNationColor = config.getString("nametagNationColor", Config.nametagNationColor) ?: Config.nametagNationColor
        Config.nametagAllyColor = config.getString("nametagAllyColor", Config.nametagAllyColor) ?: Config.nametagAllyColor
        Config.nametagEnemyColor = config.getString("nametagEnemyColor", Config.nametagEnemyColor) ?: Config.nametagEnemyColor
        Config.nametagNeutralColor = config.getString("nametagNeutralColor", Config.nametagNeutralColor) ?: Config.nametagNeutralColor
        Config.tabEnabled = config.getBoolean("tabEnabled", Config.tabEnabled)
        Config.tabUseLayout = config.getBoolean("tabUseLayout", Config.tabUseLayout)
        Config.tabRefreshTicks = config.getInt("tabRefreshTicks", Config.tabRefreshTicks)
        Config.tabSkipUnchangedUpdates = config.getBoolean("tabSkipUnchangedUpdates", Config.tabSkipUnchangedUpdates)
        Config.tabUnchangedForceRefreshMs = config.getLong("tabUnchangedForceRefreshMs", Config.tabUnchangedForceRefreshMs)
        Config.tabHighLoadPlayerThreshold = config.getInt("tabHighLoadPlayerThreshold", Config.tabHighLoadPlayerThreshold)
        Config.tabHighLoadMinIntervalMs = config.getLong("tabHighLoadMinIntervalMs", Config.tabHighLoadMinIntervalMs)
        Config.tabStaffWeightThreshold = config.getInt("tabStaffWeightThreshold", Config.tabStaffWeightThreshold)
        Config.tabStaffWeightPlaceholder = config.getString("tabStaffWeightPlaceholder", Config.tabStaffWeightPlaceholder) ?: Config.tabStaffWeightPlaceholder
        Config.tabStaffWeightCacheMs = config.getLong("tabStaffWeightCacheMs", Config.tabStaffWeightCacheMs)
        Config.tabStaffPrefixPlaceholder = config.getString("tabStaffPrefixPlaceholder", Config.tabStaffPrefixPlaceholder) ?: Config.tabStaffPrefixPlaceholder
        Config.tabStaffFallbackPermission = config.getString("tabStaffFallbackPermission", Config.tabStaffFallbackPermission) ?: Config.tabStaffFallbackPermission
        Config.tabColumnWidth = config.getInt("tabColumnWidth", Config.tabColumnWidth)
        Config.tabMaxEntriesPerColumn = config.getInt("tabMaxEntriesPerColumn", Config.tabMaxEntriesPerColumn)
        Config.tabFooterRows = config.getInt("tabFooterRows", Config.tabFooterRows)
        Config.tabFallbackSpacerRows = config.getInt("tabFallbackSpacerRows", Config.tabFallbackSpacerRows)
        Config.webOverviewResponseCacheMs = config.getLong("webOverviewResponseCacheMs", Config.webOverviewResponseCacheMs)
        Config.nametagUpdatesPerTick = Config.nametagUpdatesPerTick.coerceIn(1, 500)
        Config.nametagPipelineTicks = Config.nametagPipelineTicks.coerceIn(1, 200)
        Config.nametagPriorityTicks = Config.nametagPriorityTicks.coerceIn(1, 100)
        Config.nametagBurstTicks = Config.nametagBurstTicks.coerceIn(20, 500)
        Config.nametagMinUpdateIntervalMs = Config.nametagMinUpdateIntervalMs.coerceAtLeast(0L)
        Config.nametagLuckPermsPrefixCacheMs = Config.nametagLuckPermsPrefixCacheMs.coerceAtLeast(1000L)
        Config.nametagProximityRadius = Config.nametagProximityRadius.coerceIn(16.0, 512.0)
        Config.nametagProximityRadiusSquared = Config.nametagProximityRadius * Config.nametagProximityRadius
        Config.nametagProximityRefreshTicks = Config.nametagProximityRefreshTicks.coerceIn(20L, 1200L)
        Config.tabRefreshTicks = Config.tabRefreshTicks.coerceIn(20, 20 * 60)
        Config.tabUnchangedForceRefreshMs = Config.tabUnchangedForceRefreshMs.coerceAtLeast(1000L)
        Config.tabHighLoadPlayerThreshold = Config.tabHighLoadPlayerThreshold.coerceAtLeast(50)
        Config.tabHighLoadMinIntervalMs = Config.tabHighLoadMinIntervalMs.coerceAtLeast(1000L)
        Config.tabStaffWeightCacheMs = Config.tabStaffWeightCacheMs.coerceAtLeast(1000L)
        Config.webOverviewResponseCacheMs = Config.webOverviewResponseCacheMs.coerceAtLeast(0L)
        Config.dynmapCopyTowns = config.getBoolean("dynmapCopyTowns", Config.dynmapCopyTowns)

        // minimap settings
        val minimapSection = config.getConfigurationSection("minimap")
        Config.minimapOnlineScalingRules = listOf()
        if (minimapSection != null) {
            Config.minimapSizeDefault = minimapSection.getInt("defaultSize", Config.minimapSizeDefault)
            Config.minimapSizeMin = minimapSection.getInt("minSize", Config.minimapSizeMin)
            Config.minimapSizeMax = minimapSection.getInt("maxSize", Config.minimapSizeMax)
            val scalingSection = minimapSection.getConfigurationSection("onlineScaling")
            if (scalingSection != null) {
                Config.minimapOnlineScalingEnabled = scalingSection.getBoolean("enabled", Config.minimapOnlineScalingEnabled)
                val rulesList = scalingSection.getMapList("rules")
                if (rulesList.isNotEmpty()) {
                    val parsedRules = mutableListOf<MinimapSizeRule>()
                    for (rule in rulesList) {
                        val minPlayers = rule["minPlayers"]?.toString()?.toIntOrNull()
                        val minSize = rule["minSize"]?.toString()?.toIntOrNull()
                        val maxSize = rule["maxSize"]?.toString()?.toIntOrNull()

                        if (minPlayers == null || minSize == null || maxSize == null) {
                            continue
                        }

                        parsedRules.add(MinimapSizeRule(minPlayers, minSize, maxSize))
                    }

                    if (parsedRules.isNotEmpty()) {
                        Config.minimapOnlineScalingRules = parsedRules.sortedBy { it.minPlayers }
                    }
                }
            }
        }

        // afk kick time
        Config.afkKickTime = config.getLong("afkKickTime", Config.afkKickTime)

        // generic permissions
        Config.canInteractInEmpty = config.getBoolean("canInteractInEmpty", Config.canInteractInEmpty)
        Config.canInteractInUnclaimed = config.getBoolean("canInteractInUnclaimed", Config.canInteractInUnclaimed)
        Config.requireSheepNodeToShear = config.getBoolean("requireSheepNodeToShear", Config.requireSheepNodeToShear)
        Config.punishBlockGlitching = config.getBoolean("punishBlockGlitching", Config.punishBlockGlitching)
        Config.punishBlockGlitchingDurationTicks = config.getInt("punishBlockGlitchingDurationTicks", Config.punishBlockGlitchingDurationTicks)
        Config.punishBlockGlitchingPoisonAmplifier = config.getInt("punishBlockGlitchingPoisonAmplifier", Config.punishBlockGlitchingPoisonAmplifier)
        Config.punishBlockGlitchingBlindnessAmplifier = config.getInt("punishBlockGlitchingBlindnessAmplifier", Config.punishBlockGlitchingBlindnessAmplifier)
        Config.punishBlockGlitchingDarknessAmplifier = config.getInt("punishBlockGlitchingDarknessAmplifier", Config.punishBlockGlitchingDarknessAmplifier)
        Config.punishBlockGlitchingAttempts = config.getInt("punishBlockGlitchingAttempts", Config.punishBlockGlitchingAttempts)
        Config.punishBlockGlitchingWindowTicks = config.getInt("punishBlockGlitchingWindowTicks", Config.punishBlockGlitchingWindowTicks)
        Config.disableRedstoneTicking = config.getBoolean("disableRedstoneTicking", Config.disableRedstoneTicking)
        Config.attackBossBarShowTimeRemaining = config.getBoolean("attackBossBarShowTimeRemaining", Config.attackBossBarShowTimeRemaining)

        // webhooks (blame turgangs)
        Config.discordWebhooks.clear()
        val webhooksSection = config.getConfigurationSection("discordWebhooks")
        if (webhooksSection != null) {
            for (key in webhooksSection.getKeys(false)) {
                val url = webhooksSection.getString(key)
                if (url != null) {
                    Config.discordWebhooks[key] = url
                }
            }
        }
        Config.reportCooldown = config.getLong("reportCooldown", Config.reportCooldown)

        // town cooldown configs
        Config.townCreateCooldown = config.getLong("townCreateCooldown", Config.townCreateCooldown)
        Config.townMoveHomeCooldown = config.getLong("townMoveHomeCooldown", Config.townMoveHomeCooldown)

        // town creation cost (Vault economy)
        Config.townCreateCostEnabled = config.getBoolean("townCreateCostEnabled", Config.townCreateCostEnabled)
        Config.townCreateCostFlatFee = config.getDouble("townCreateCostFlatFee", Config.townCreateCostFlatFee)

        // claim-system top-level selector — read BEFORE the individual flags
        // so the derivation (performed later in this loader after
        // claimsPowerEnabled is also read) can overwrite them correctly.
        Config.townClaimSystem = config.getString("townClaimSystem", Config.townClaimSystem) ?: Config.townClaimSystem
        Config.townClaimCostEnabled = config.getBoolean("townClaimCostEnabled", Config.townClaimCostEnabled)

        // town claim rate limit (cooldown)
        Config.townClaimCooldownEnabled = config.getBoolean("townClaimCooldownEnabled", Config.townClaimCooldownEnabled)
        Config.townClaimCooldownPeriod = config.getLong("townClaimCooldownPeriod", Config.townClaimCooldownPeriod)
        Config.townClaimCooldownMaxClaims = config.getInt("townClaimCooldownMaxClaims", Config.townClaimCooldownMaxClaims)

        // town claim player ratio
        Config.townClaimPlayerRatioEnabled = config.getBoolean("townClaimPlayerRatioEnabled", Config.townClaimPlayerRatioEnabled)
        Config.townClaimPlayerRatioPerPlayer = config.getInt("townClaimPlayerRatioPerPlayer", Config.townClaimPlayerRatioPerPlayer)

        // town rejoin cooldown
        Config.townRejoinCooldownEnabled = config.getBoolean("townRejoinCooldownEnabled", Config.townRejoinCooldownEnabled)
        Config.townRejoinCooldown = config.getLong("townRejoinCooldown", Config.townRejoinCooldown)

        // resource configs
        Config.incomeEnabled = config.getBoolean("incomeEnabled", Config.incomeEnabled)
        Config.incomePeriod = config.getLong("incomePeriod", Config.incomePeriod)
        Config.incomeScaleByClaimPower = config.getBoolean("incomeScaleByClaimPower", Config.incomeScaleByClaimPower)
        Config.incomeScaleMin = config.getDouble("incomeScaleMin", Config.incomeScaleMin)
        Config.incomeScaleMax = config.getDouble("incomeScaleMax", Config.incomeScaleMax)
        Config.allowOreInWilderness = config.getBoolean("allowOreInWilderness", Config.allowOreInWilderness)
        Config.allowCropsInWilderness = config.getBoolean("allowCropsInWilderness", Config.allowCropsInWilderness)
        Config.allowBreedingInWilderness = config.getBoolean("allowBreedingInWilderness", Config.allowBreedingInWilderness)
        Config.allowOreInCaptured = config.getBoolean("allowOreInCaptured", Config.allowOreInCaptured)
        Config.allowOreInNationTowns = config.getBoolean("allowOreInNationTowns", Config.allowOreInNationTowns)
        Config.cropsMinSkyLight = config.getInt("cropsMinSkyLight", Config.cropsMinSkyLight)
        Config.breedingMinSkyLight = config.getInt("breedingMinSkyLight", Config.breedingMinSkyLight)
        Config.cropsMinYHeight = config.getInt("cropsMinYHeight", Config.cropsMinYHeight)
        Config.cropsMaxYHeight = config.getInt("cropsMaxYHeight", Config.cropsMaxYHeight)
        Config.breedingMinYHeight = config.getInt("breedingMinYHeight", Config.breedingMinYHeight)
        Config.breedingMaxYHeight = config.getInt("breedingMaxYHeight", Config.breedingMaxYHeight)

        // global resources in all territories
        val globalResourcesSection = config.getConfigurationSection("globalResources")
        Config.globalResources = if (globalResourcesSection !== null) {
            parseGlobalResources(globalResourcesSection)
        } else {
            Nodes.logger?.info("[Nodes] config.yml missing globalResources section; using built-in defaults.")
            defaultGlobalResources()
        }

        Config.useTerritoryResourceValues = config.getBoolean("useTerritoryResourceValues", Config.useTerritoryResourceValues)

        // node types
        Config.nodeTypes = loadNodeTypesConfigFile(config)

        // town claims
        Config.territoryCostBase = config.getInt("territoryCostBase", Config.territoryCostBase)
        Config.territoryCostScale = config.getDouble("territoryCostScale", Config.territoryCostScale)
        Config.claimsPowerEnabled = config.getBoolean("claimsPowerEnabled", Config.claimsPowerEnabled)
        Config.territoryCostEnabled = config.getBoolean("territoryCostEnabled", Config.territoryCostEnabled)
        Config.townInitialClaims = config.getInt("townInitialClaims", Config.townInitialClaims)
        Config.initialOverClaimsAmountScale = config.getInt("initialOverClaimsAmountScale", Config.initialOverClaimsAmountScale)
        Config.townClaimsBase = config.getInt("townClaimsBase", Config.townClaimsBase)
        Config.townClaimsMax = config.getInt("townClaimsMax", Config.townClaimsMax)

        Config.playerClaimsInitial = config.getInt("playerClaimsInitial", Config.playerClaimsInitial)
        Config.playerClaimsMax = config.getInt("playerClaimsMax", Config.playerClaimsMax)
        Config.playerClaimsIncrease = config.getInt("playerClaimsIncrease", Config.playerClaimsIncrease)

        Config.rallyCapEnabled = config.getBoolean("rallyCapEnabled", Config.rallyCapEnabled)
        Config.rallyCapSize = config.getInt("rallyCapSize", Config.rallyCapSize)
        Config.rallyCapApplyToNations = config.getBoolean("rallyCapApplyToNations", Config.rallyCapApplyToNations)

        Config.townPenaltyDecay = config.getInt("townPenaltyDecay", Config.townPenaltyDecay)
        Config.townClaimsPenaltyDecayPeriod = config.getLong("townClaimsPenaltyDecayPeriod", Config.townClaimsPenaltyDecayPeriod)
        Config.playerClaimsIncreasePeriod = config.getLong("playerClaimsIncreasePeriod", Config.playerClaimsIncreasePeriod)
        Config.overClaimsPenalty = config.getBoolean("overClaimsPenalty", Config.overClaimsPenalty)
        Config.overClaimsMaxPenalty = config.getDouble("overClaimsMaxPenalty", Config.overClaimsMaxPenalty)
        Config.overClaimsAllowClaim = config.getBoolean("overClaimsAllowClaim", Config.overClaimsAllowClaim)
        Config.overClaimsMaxExcess = config.getInt("overClaimsMaxExcess", Config.overClaimsMaxExcess)

        // ─── Apply townClaimSystem derivation ───────────────────────────────
        // Runs after both claimsPowerEnabled and townClaimCostEnabled have
        // been read. Overwrites them unless the mode is CUSTOM, in which
        // case the user's manual values are preserved.
        when (Config.townClaimSystem.trim().uppercase()) {
            "POWER" -> {
                Config.claimsPowerEnabled = true
                Config.townClaimCostEnabled = false
            }
            "VAULT" -> {
                Config.claimsPowerEnabled = false
                Config.townClaimCostEnabled = true
            }
            "HYBRID" -> {
                Config.claimsPowerEnabled = true
                Config.townClaimCostEnabled = true
            }
            "CUSTOM" -> {
                // leave both flags as the user configured them
            }
            else -> {
                // Unknown value → default to POWER and warn via plugin logger later
                Config.claimsPowerEnabled = true
                Config.townClaimCostEnabled = false
            }
        }

        Config.canOnlyAnnexDuringWar = config.getBoolean("canOnlyAnnexDuringWar", Config.canOnlyAnnexDuringWar)

        // town settings
        Config.townSpawnTime = config.getInt("townSpawnTime", Config.townSpawnTime)
        Config.outpostWarpTimeTier1 = config.getDouble("outpostWarpTimeTier1", Config.outpostWarpTimeTier1 / 20.0) * 20.0
        Config.outpostWarpTimeTier2 = config.getDouble("outpostWarpTimeTier2", Config.outpostWarpTimeTier2 / 20.0) * 20.0
        Config.outpostWarpTimeTier3 = config.getDouble("outpostWarpTimeTier3", Config.outpostWarpTimeTier3 / 20.0) * 20.0
        Config.townPointsPerPlayerPerMinute = config.getLong("townPointsPerPlayerPerMinute", Config.townPointsPerPlayerPerMinute).coerceAtLeast(0L)
        Config.townShopFile = config.getString("townShopFile", Config.townShopFile)?.trim().orEmpty().ifEmpty { "town_shop.yml" }
        Config.pathTownShop = townShopPathFor(Config.townShopFile)
        loadTownShopConfigFile()
        Config.pathLanguage = Paths.get(Config.pathPlugin, "language").normalize()
        Config.pathMarket = Paths.get(Config.pathPlugin, "market").normalize()
        Config.pathRally = Paths.get(Config.pathPlugin, "rally").normalize()
        Config.pathRallyHistory = Config.pathRally.resolve("rally_history.json").normalize()
        Config.pathMarketData = Config.pathMarket.resolve("market_data.yml").normalize()
        Config.pathMarketSalesHistory = Config.pathMarket.resolve("market_sales_history.json").normalize()
        Config.pathNodeTypes = nodeTypesPathFor(Config.nodeTypeFile)
        Config.pathWebOverview = webOverviewPath()
        Config.pathKillTracker = killTrackerPath()
        Config.pathWarzones = warzonesPath()
        loadWebOverviewConfigFile()

        Config.outpostTeleportCost.clear()
        Config.outpostTeleportCostString = ""
        val outpostTeleportCostSection = config.getConfigurationSection("outpostTeleportCost")
        if (outpostTeleportCostSection !== null) {
            Config.outpostTeleportCost.putAll(parseTeleportCost(outpostTeleportCostSection))
            Config.outpostTeleportCostString = teleportCostToString(Config.outpostTeleportCost)
        }

        // nation settings
        Config.allowNationTownSpawn = config.getBoolean("allowNationTownSpawn", Config.allowNationTownSpawn)

        Config.nationTownTeleportCost.clear()
        Config.nationTownTeleportCostString = ""
        val nationTeleportCostSection = config.getConfigurationSection("nationTownTeleportCost")
        if (nationTeleportCostSection !== null) {
            Config.nationTownTeleportCost.putAll(parseTeleportCost(nationTeleportCostSection))
            Config.nationTownTeleportCostString = teleportCostToString(Config.nationTownTeleportCost)
        }
        Config.allowNationFriendlyFire = config.getBoolean("allowNationFriendlyFire", Config.allowNationFriendlyFire)

        // ally settings
        Config.allowAllyFriendlyFire = config.getBoolean("allowAllyFriendlyFire", Config.allowAllyFriendlyFire)

        // tax
        Config.taxIncomeRate = config.getDouble("taxIncomeRate", Config.taxIncomeRate)
        Config.taxMineRate = config.getDouble("taxMineRate", Config.taxMineRate)
        Config.taxFarmRate = config.getDouble("taxFarmRate", Config.taxFarmRate)
        Config.taxAnimalRate = config.getDouble("taxAnimalRate", Config.taxAnimalRate)

        // ======================
        // war
        // ======================
        Config.restrictExplosions = config.getBoolean("restrictExplosions", Config.restrictExplosions)
        Config.onlyAllowExplosionsDuringWar = config.getBoolean("onlyAllowExplosionsDuringWar", Config.onlyAllowExplosionsDuringWar)
        Config.flagNoBuildDistance = config.getInt("flagNoBuildDistance", Config.flagNoBuildDistance)
        Config.flagNoBuildYOffset = config.getInt("flagNoBuildYOffset", Config.flagNoBuildYOffset)
        Config.chunkAttackTime = config.getLong("chunkAttackTime", Config.chunkAttackTime)
        Config.chunkAttackHomeMultiplier = config.getDouble("chunkAttackHomeMultiplier", Config.chunkAttackHomeMultiplier)
        Config.chunkAttackHomeTime = config.getLong("chunkAttackHomeTime", Config.chunkAttackHomeTime)
        Config.chunkAttackFromWastelandMultiplier = config.getDouble("chunkAttackFromWastelandMultiplier", Config.chunkAttackFromWastelandMultiplier)
        Config.maxPlayerChunkAttacks = config.getInt("maxPlayerChunkAttacks", Config.maxPlayerChunkAttacks)
        Config.allowBreakingAlliesFlags = config.getBoolean("allowBreakingAlliesFlags", Config.allowBreakingAlliesFlags)
        val flagTopBlockName = config.getString("flagTopBlockMaterial", Config.flagTopBlockMaterial.name) ?: Config.flagTopBlockMaterial.name
        val parsedFlagTopBlock = Material.matchMaterial(flagTopBlockName) ?: Material.matchMaterial(flagTopBlockName.uppercase())
        if (parsedFlagTopBlock != null && parsedFlagTopBlock.isBlock) {
            Config.flagTopBlockMaterial = parsedFlagTopBlock
        }
        Config.flagBeaconSize = config.getInt("flagBeaconSize", Config.flagBeaconSize)
        Config.flagBeaconMinSkyLevel = config.getInt("flagBeaconMinSkyLevel", Config.flagBeaconMinSkyLevel)
        Config.flagBeaconSkyLevel = config.getInt("flagBeaconSkyLevel", Config.flagBeaconSkyLevel)
        Config.flagPlacementMinHeight = config.getInt("flagPlacementMinHeight", Config.flagPlacementMinHeight)
        Config.flagPlacementMaxHeight = config.getInt("flagPlacementMaxHeight", Config.flagPlacementMaxHeight)

        if (Config.flagPlacementMinHeight > Config.flagPlacementMaxHeight) {
            val tmp = Config.flagPlacementMinHeight
            Config.flagPlacementMinHeight = Config.flagPlacementMaxHeight
            Config.flagPlacementMaxHeight = tmp
        }

        Config.flagPlacementMinHeight = Config.flagPlacementMinHeight.coerceIn(0, 255)
        Config.flagPlacementMaxHeight = Config.flagPlacementMaxHeight.coerceIn(0, 255)
        Config.onlyAllowExplosionsDuringWar = config.getBoolean("onlyAllowExplosionsDuringWar", Config.onlyAllowExplosionsDuringWar)
        Config.warPermissions = config.getBoolean("warPermissions", Config.warPermissions)

        Config.allowDestructionDuringSkirmish = config.getBoolean("allowDestructionDuringSkirmish", Config.allowDestructionDuringSkirmish)
        Config.canLeaveTownDuringWar = config.getBoolean("canLeaveTownDuringWar", Config.canLeaveTownDuringWar)
        Config.canCreateTownDuringWar = config.getBoolean("canCreateTownDuringWar", Config.canCreateTownDuringWar)
        Config.canDestroyTownDuringWar = config.getBoolean("canDestroyTownDuringWar", Config.canDestroyTownDuringWar)
        Config.canLeaveNationDuringWar = config.getBoolean("canLeaveNationDuringWar", Config.canLeaveNationDuringWar)
        Config.annexDisabled = config.getBoolean("annexDisabled", Config.annexDisabled)

        Config.onlyWhitelistCanAnnex = config.getBoolean("onlyWhitelistCanAnnex", Config.onlyWhitelistCanAnnex)
        Config.onlyWhitelistCanClaim = config.getBoolean("onlyWhitelistCanClaim", Config.onlyWhitelistCanClaim)
        Config.leaderLaunchDefaultSlots = config.getInt("leaderlaunch.defaultSlots", Config.leaderLaunchDefaultSlots)
        Config.leaderLaunchMaxSlots = config.getInt("leaderlaunch.maxSlots", Config.leaderLaunchMaxSlots)

        Config.occupiedHomeTeleportMultiplier = config.getDouble("occupiedHomeTeleportMultiplier", Config.occupiedHomeTeleportMultiplier)

        Config.allowControlInOccupiedTownList = parseUUIDSet(config, "allowControlInOccupiedTownList")
        // ======================

        // truce
        Config.trucePeriod = config.getLong("trucePeriod", Config.trucePeriod)

        // ports
        Config.seaLevel = config.getDouble("seaLevel", Config.seaLevel)
        Config.portWarpTime = config.getDouble("portWarpTime", Config.portWarpTime) * 20.0 // time is in seconds, convert to ticks
        Config.portWarpTimeTier1 = config.getDouble("portWarpTimeTier1", Config.portWarpTimeTier1 / 20.0) * 20.0
        Config.portWarpTimeTier2 = config.getDouble("portWarpTimeTier2", Config.portWarpTimeTier2 / 20.0) * 20.0
        Config.portWarpTimeTier3 = config.getDouble("portWarpTimeTier3", Config.portWarpTimeTier3 / 20.0) * 20.0
        Config.allowPortWarpWithoutBoat = config.getBoolean("allowPortWarpWithoutBoat", Config.allowPortWarpWithoutBoat)
    }

    public fun getMinimapSizeLimits(onlinePlayers: Int): Pair<Int, Int> {
        var minSize = minimapSizeMin
        var maxSize = minimapSizeMax

        if (minimapOnlineScalingEnabled && minimapOnlineScalingRules.isNotEmpty()) {
            val rule = minimapOnlineScalingRules.lastOrNull { onlinePlayers >= it.minPlayers }
            if (rule != null) {
                minSize = rule.minSize
                maxSize = rule.maxSize
            }
        }

        if (minSize > maxSize) {
            val tmp = minSize
            minSize = maxSize
            maxSize = tmp
        }

        return Pair(minSize, maxSize)
    }
}

public data class MinimapSizeRule(
    val minPlayers: Int,
    val minSize: Int,
    val maxSize: Int,
)

public data class TownShopEntry(
    val id: String,
    val item: Material,
    val amount: Int,
    val townPointsCost: Long,
    val outpostCreditsCost: Int,
    val enabled: Boolean,
)

public data class WebButtonConfig(
    val id: String,
    val enabled: Boolean,
    val label: String,
    val description: String,
    val url: String,
)

public data class WebNewsItem(
    val date: String,
    val title: String,
    val content: String,
    val link: String,
    val pinned: Boolean,
)

public data class WebFeatureItem(
    val title: String,
    val description: String,
)

private fun migrateLegacyYaml(target: File, legacyName: String) {
    if (target.exists()) {
        return
    }

    val legacy = File(Config.pathPlugin, legacyName)
    if (!legacy.exists() || legacy.absolutePath == target.absolutePath) {
        return
    }

    try {
        target.parentFile?.mkdirs()
        legacy.copyTo(target, overwrite = true)
        legacy.delete()
        Nodes.logger?.info("Migrated legacy $legacyName to ${target.path}")
    } catch (err: Exception) {
        Nodes.logger?.warning("Failed to migrate legacy $legacyName: ${err.message}")
    }
}

private fun loadNodeTypesConfigFile(mainConfig: FileConfiguration): HashMap<String, ResourceNode> {
    val file = File(Config.pathNodeTypes.toString())
    migrateLegacyYaml(file, Config.nodeTypeFile)
    if (!file.exists()) {
        file.parentFile?.mkdirs()

        val yaml = YamlConfiguration()
        val legacyNodeTypes = mainConfig.get("nodeTypes")
        if (legacyNodeTypes != null) {
            yaml.set("nodeTypes", legacyNodeTypes)
        } else {
            yaml.createSection("nodeTypes")
            yaml.set("nodeTypes.core.priority", 10)
            yaml.set("nodeTypes.core.icon", "diamond")
            yaml.set("nodeTypes.core.income_total_multiplier", 1.1)
            yaml.set("nodeTypes.core.attacker_time_multiplier", 0.8)
        }

        try {
            yaml.save(file)
            Nodes.logger?.info("Created ${Config.nodeTypeFile} from config.yml nodeTypes")
        } catch (err: Exception) {
            Nodes.logger?.warning("Failed to create ${Config.nodeTypeFile}: ${err.message}")
        }
    }

    val yaml = YamlConfiguration.loadConfiguration(file)
    val section = yaml.getConfigurationSection("nodeTypes")
    return parseNodeTypes(
        section ?: mainConfig.getConfigurationSection("nodeTypes"),
    )
}

private fun loadTownShopConfigFile() {
    val file = File(Config.pathTownShop.toString())
    migrateLegacyYaml(file, Config.townShopFile)
    if (!file.exists()) {
        file.parentFile?.mkdirs()
        val example = StringBuilder()
        example.appendLine("# Nodes Town Shop")
        example.appendLine("# Edit this file to add or remove shop entries.")
        example.appendLine("# Costs are placeholders and can be changed freely.")
        example.appendLine("items:")
        example.appendLine("  example_speed:")
        example.appendLine("    enabled: false")
        example.appendLine("    item: SUGAR")
        example.appendLine("    amount: 1")
        example.appendLine("    townPointsCost: 100")
        example.appendLine("    outpostCreditsCost: 0")
        file.writeText(example.toString())
    }

    val yaml = YamlConfiguration.loadConfiguration(file)
    Config.townShopEntries.clear()
    val items = yaml.getConfigurationSection("items") ?: return

    for (id in items.getKeys(false)) {
        val section = items.getConfigurationSection(id) ?: continue
        val materialName = section.getString("item", "STONE") ?: "STONE"
        val material = Material.matchMaterial(materialName) ?: Material.matchMaterial(materialName.uppercase()) ?: continue
        val amount = section.getInt("amount", 1).coerceAtLeast(1)
        val townPointsCost = section.getLong("townPointsCost", 0L).coerceAtLeast(0L)
        val outpostCreditsCost = section.getInt("outpostCreditsCost", 0).coerceAtLeast(0)
        val enabled = section.getBoolean("enabled", true)

        Config.townShopEntries[id] = TownShopEntry(
            id = id,
            item = material,
            amount = amount,
            townPointsCost = townPointsCost,
            outpostCreditsCost = outpostCreditsCost,
            enabled = enabled,
        )
    }
}

private fun loadWebOverviewConfigFile() {
    val file = File(Config.pathWebOverview.toString())
    migrateLegacyYaml(file, "web_overview.yml")
    if (!file.exists()) {
        file.parentFile?.mkdirs()
        val example = StringBuilder()
        example.appendLine("# Nodes Web Overview")
        example.appendLine("# This file controls the integrated web portal.")
        example.appendLine("web:")
        example.appendLine("  enabled: true")
        example.appendLine("  bindAddress: \"0.0.0.0\"")
        example.appendLine("  webPort: 8095")
        example.appendLine("  pageTitle: \"Siege Nodes Portal\"")
        example.appendLine("  serverName: \"Siege Napoleonic Era\"")
        example.appendLine("  subtitle: \"Map, shop, rally, market and all important links in one place.\"")
        example.appendLine("  footerText: \"Powered by Nodes\"")
        example.appendLine("  heroBackgroundUrl: \"\"")
        example.appendLine("  mainImageUrl: \"\"")
        example.appendLine("  primaryColor: \"#3ba9ff\"")
        example.appendLine("  secondaryColor: \"#0f2e5f\"")
        example.appendLine("  accentColor: \"#8ad4ff\"")
        example.appendLine("  backgroundTop: \"#071124\"")
        example.appendLine("  backgroundBottom: \"#030913\"")
        example.appendLine("buttons:")
        example.appendLine("  map:")
        example.appendLine("    enabled: true")
        example.appendLine("    label: \"Map\"")
        example.appendLine("    description: \"Live territory map\"")
        example.appendLine("    url: \"http://127.0.0.1:8123\"")
        example.appendLine("  shop:")
        example.appendLine("    enabled: true")
        example.appendLine("    label: \"Shop\"")
        example.appendLine("    description: \"Server web shop\"")
        example.appendLine("    url: \"https://store.example.com\"")
        example.appendLine("  rally:")
        example.appendLine("    enabled: true")
        example.appendLine("    label: \"Rally\"")
        example.appendLine("    description: \"Nation rally overview\"")
        example.appendLine("    url: \"http://127.0.0.1:8096\"")
        example.appendLine("  market:")
        example.appendLine("    enabled: true")
        example.appendLine("    label: \"Market\"")
        example.appendLine("    description: \"Town market analytics\"")
        example.appendLine("    url: \"http://127.0.0.1:8097\"")
        example.appendLine("  discord:")
        example.appendLine("    enabled: true")
        example.appendLine("    label: \"Discord\"")
        example.appendLine("    description: \"Join the community\"")
        example.appendLine("    url: \"https://discord.gg/zWZrAGbcQX\"")
        example.appendLine("  bans:")
        example.appendLine("    enabled: true")
        example.appendLine("    label: \"Bans\"")
        example.appendLine("    description: \"Public bans list\"")
        example.appendLine("    url: \"https://example.com/bans\"")
        example.appendLine("features:")
        example.appendLine("  - title: \"War Ready\"")
        example.appendLine("    description: \"Track wars, diplomacy and caps from one central dashboard.\"")
        example.appendLine("  - title: \"Economy Insights\"")
        example.appendLine("    description: \"See active markets, top selling towns and cheapest offers quickly.\"")
        example.appendLine("  - title: \"Clear Navigation\"")
        example.appendLine("    description: \"Players can access all core systems with one click.\"")
        example.appendLine("tips:")
        example.appendLine("  - \"Use /town pointshop to spend your town points.\"")
        example.appendLine("  - \"Use /port apply <town> to request a port teleport.\"")
        example.appendLine("  - \"Use /town outpost apply to request outpost travel.\"")
        example.appendLine("news:")
        example.appendLine("  - date: \"2026-02-27\"")
        example.appendLine("    title: \"Portal Released\"")
        example.appendLine("    content: \"The new integrated portal is now live.\"")
        example.appendLine("    link: \"\"")
        example.appendLine("    pinned: true")
        example.appendLine("  - date: \"2026-02-27\"")
        example.appendLine("    title: \"Rally + Market\"")
        example.appendLine("    content: \"Use the buttons above to open the latest rally and market overviews.\"")
        example.appendLine("    link: \"\"")
        example.appendLine("    pinned: false")
        file.writeText(example.toString())
    }

    val yaml = YamlConfiguration.loadConfiguration(file)
    val web = yaml.getConfigurationSection("web")

    Config.webOverviewEnabled = web?.getBoolean("enabled", Config.webOverviewEnabled) ?: Config.webOverviewEnabled
    Config.webOverviewBindAddress = web?.getString("bindAddress", Config.webOverviewBindAddress) ?: Config.webOverviewBindAddress
    Config.webOverviewWebPort = (web?.getInt("webPort", Config.webOverviewWebPort) ?: Config.webOverviewWebPort).coerceIn(1, 65535)
    Config.webOverviewPageTitle = web?.getString("pageTitle", Config.webOverviewPageTitle) ?: Config.webOverviewPageTitle
    Config.webOverviewServerName = web?.getString("serverName", Config.webOverviewServerName) ?: Config.webOverviewServerName
    Config.webOverviewSubtitle = web?.getString("subtitle", Config.webOverviewSubtitle) ?: Config.webOverviewSubtitle
    Config.webOverviewFooterText = web?.getString("footerText", Config.webOverviewFooterText) ?: Config.webOverviewFooterText
    Config.webOverviewHeroBackgroundUrl = web?.getString("heroBackgroundUrl", Config.webOverviewHeroBackgroundUrl) ?: Config.webOverviewHeroBackgroundUrl
    Config.webOverviewMainImageUrl = web?.getString("mainImageUrl", Config.webOverviewMainImageUrl) ?: Config.webOverviewMainImageUrl
    Config.webOverviewPrimaryColor = web?.getString("primaryColor", Config.webOverviewPrimaryColor) ?: Config.webOverviewPrimaryColor
    Config.webOverviewSecondaryColor = web?.getString("secondaryColor", Config.webOverviewSecondaryColor) ?: Config.webOverviewSecondaryColor
    Config.webOverviewAccentColor = web?.getString("accentColor", Config.webOverviewAccentColor) ?: Config.webOverviewAccentColor
    Config.webOverviewBackgroundTop = web?.getString("backgroundTop", Config.webOverviewBackgroundTop) ?: Config.webOverviewBackgroundTop
    Config.webOverviewBackgroundBottom = web?.getString("backgroundBottom", Config.webOverviewBackgroundBottom) ?: Config.webOverviewBackgroundBottom

    Config.webOverviewButtons.clear()
    val buttonSection = yaml.getConfigurationSection("buttons")
    if (buttonSection != null) {
        for (key in buttonSection.getKeys(false)) {
            val section = buttonSection.getConfigurationSection(key) ?: continue
            Config.webOverviewButtons[key] = WebButtonConfig(
                id = key,
                enabled = section.getBoolean("enabled", true),
                label = section.getString("label", key.replaceFirstChar { it.uppercase() }) ?: key,
                description = section.getString("description", "") ?: "",
                url = section.getString("url", "#") ?: "#",
            )
        }
    }
    if (Config.webOverviewButtons.isEmpty()) {
        Config.webOverviewButtons["map"] = WebButtonConfig("map", true, "Map", "Live territory map", "http://127.0.0.1:8123")
        Config.webOverviewButtons["shop"] = WebButtonConfig("shop", true, "Shop", "Server web shop", "https://store.example.com")
        Config.webOverviewButtons["rally"] = WebButtonConfig("rally", true, "Rally", "Nation rally overview", "http://127.0.0.1:8096")
        Config.webOverviewButtons["market"] = WebButtonConfig("market", true, "Market", "Town market analytics", "http://127.0.0.1:8097")
        Config.webOverviewButtons["discord"] = WebButtonConfig("discord", true, "Discord", "Join the community", "https://discord.gg/zWZrAGbcQX")
        Config.webOverviewButtons["bans"] = WebButtonConfig("bans", true, "Bans", "Public bans list", "https://example.com/bans")
    } else {
        if (!Config.webOverviewButtons.containsKey("bans")) {
            Config.webOverviewButtons["bans"] = WebButtonConfig("bans", true, "Bans", "Public bans list", "https://example.com/bans")
        }
    }

    Config.webOverviewFeatures.clear()
    for (entry in yaml.getMapList("features")) {
        val title = (entry["title"] as? String)?.trim().orEmpty()
        val description = (entry["description"] as? String)?.trim().orEmpty()
        if (title.isNotEmpty()) {
            Config.webOverviewFeatures.add(WebFeatureItem(title = title, description = description))
        }
    }
    if (Config.webOverviewFeatures.isEmpty()) {
        Config.webOverviewFeatures.add(WebFeatureItem("War Ready", "Track wars, diplomacy and caps from one central dashboard."))
        Config.webOverviewFeatures.add(WebFeatureItem("Economy Insights", "See active markets, top selling towns and cheapest offers quickly."))
        Config.webOverviewFeatures.add(WebFeatureItem("Clear Navigation", "Players can access all core systems with one click."))
    }

    Config.webOverviewTips.clear()
    yaml.getStringList("tips").forEach { tip ->
        val t = tip.trim()
        if (t.isNotEmpty()) {
            Config.webOverviewTips.add(t)
        }
    }
    if (Config.webOverviewTips.isEmpty()) {
        Config.webOverviewTips.add("Use /town pointshop to spend your town points.")
        Config.webOverviewTips.add("Use /port apply <town> to request a port teleport.")
        Config.webOverviewTips.add("Use /town outpost apply to request outpost travel.")
    }

    Config.webOverviewNews.clear()
    for (entry in yaml.getMapList("news")) {
        val date = (entry["date"] as? String)?.trim().orEmpty()
        val title = (entry["title"] as? String)?.trim().orEmpty()
        val content = (entry["content"] as? String)?.trim().orEmpty()
        val link = (entry["link"] as? String)?.trim().orEmpty()
        val pinned = (entry["pinned"] as? Boolean) ?: false
        if (title.isNotEmpty()) {
            Config.webOverviewNews.add(
                WebNewsItem(
                    date = date.ifEmpty { "unknown" },
                    title = title,
                    content = content,
                    link = link,
                    pinned = pinned,
                ),
            )
        }
    }
    if (Config.webOverviewNews.isEmpty()) {
        Config.webOverviewNews.add(
            WebNewsItem(
                date = "2026-02-27",
                title = "Portal Released",
                content = "The new integrated portal is now live.",
                link = "",
                pinned = true,
            ),
        )
    }
}

// parse global resources section in config.yml
private fun parseGlobalResources(globalResourcesSection: ConfigurationSection): TerritoryResources {
    val income: EnumMap<Material, Double> = EnumMap<Material, Double>(Material::class.java)
    val incomeSpawnEgg: EnumMap<EntityType, Double> = EnumMap<EntityType, Double>(EntityType::class.java)
    val ores: EnumMap<Material, OreDeposit> = EnumMap<Material, OreDeposit>(Material::class.java)
    val crops: EnumMap<Material, Double> = EnumMap<Material, Double>(Material::class.java)
    val animals: EnumMap<EntityType, Double> = EnumMap<EntityType, Double>(EntityType::class.java)

    globalResourcesSection.getConfigurationSection("income")?.let { section ->
        for (item in section.getKeys(false)) {
            val itemName = item.uppercase()
            // spawn egg
            if (itemName.startsWith("SPAWN_EGG_")) {
                val entityType = EntityType.valueOf(itemName.replace("SPAWN_EGG_", ""))
                if (entityType !== null) {
                    incomeSpawnEgg.put(entityType, section.getDouble(item))
                }
            }
            // regular material
            else {
                val material = Material.matchMaterial(item)
                if (material !== null) {
                    income.put(material, section.getDouble(item))
                }
            }
        }
    }

    globalResourcesSection.getConfigurationSection("ore")?.let { section ->
        for (item in section.getKeys(false)) {
            val material = Material.matchMaterial(item)
            if (material !== null) {
                // list format [chance, min, max]
                if (section.isList(item)) {
                    val list = section.getDoubleList(item)
                    if (list.size === 3) {
                        val chance = list[0]
                        val min = list[1].toInt()
                        val max = list[2].toInt()
                        ores.put(material, OreDeposit(material, chance, min, max))
                    }
                }
                // single chance number (implicit min = max = 1)
                else {
                    val chance = section.getDouble(item)
                    ores.put(material, OreDeposit(material, chance, 1, 1))
                }
            }
        }
    }

    globalResourcesSection.getConfigurationSection("crops")?.let { section ->
        for (item in section.getKeys(false)) {
            val material = Material.matchMaterial(item)
            if (material !== null) {
                crops.put(material, section.getDouble(item))
            }
        }
    }

    globalResourcesSection.getConfigurationSection("animals")?.let { section ->
        for (item in section.getKeys(false)) {
            try {
                val entityType = EntityType.valueOf(item.uppercase())
                animals.put(entityType, section.getDouble(item))
            } catch (err: Exception) {
                err.printStackTrace()
            }
        }
    }

    return TerritoryResources(
        income = income,
        incomeSpawnEgg = incomeSpawnEgg,
        ores = ores,
        crops = crops,
        animals = animals,
    )
}

private fun defaultGlobalResources(): TerritoryResources {
    val ores = EnumMap<Material, OreDeposit>(Material::class.java)
    ores[Material.COAL] = OreDeposit(Material.COAL, 0.03, 1, 3)
    ores[Material.IRON_ORE] = OreDeposit(Material.IRON_ORE, 0.015, 1, 2)
    ores[Material.GOLD_ORE] = OreDeposit(Material.GOLD_ORE, 0.015, 1, 1)
    ores[Material.DIAMOND] = OreDeposit(Material.DIAMOND, 0.007, 1, 1)

    val crops = EnumMap<Material, Double>(Material::class.java)
    crops[Material.WHEAT] = 0.25
    crops[Material.POTATO] = 0.25

    return TerritoryResources(
        income = EnumMap(Material::class.java),
        incomeSpawnEgg = EnumMap(EntityType::class.java),
        ores = ores,
        crops = crops,
        animals = EnumMap(EntityType::class.java),
    )
}

// parse node types from config.yml
private fun parseNodeTypes(nodeTypesSection: ConfigurationSection?): HashMap<String, ResourceNode> {
    val nodeTypes: HashMap<String, ResourceNode> = hashMapOf()
    if (nodeTypesSection == null) {
        return nodeTypes
    }

    for (name in nodeTypesSection.getKeys(false)) {
        val section = nodeTypesSection.getConfigurationSection(name) ?: continue
        val attributes: ArrayList<ResourceAttribute> = arrayListOf()

        // icon + priority
        val icon = section.getString("icon")
        val priority = section.getInt("priority", 0)

        // base resources
        section.getConfigurationSection("income")?.let { incomeSection ->
            val (income, incomeSpawnEgg) = parseIncomeSection(incomeSection)
            if (income.isNotEmpty() || incomeSpawnEgg.isNotEmpty()) {
                attributes.add(ResourceAttributeIncome(income, incomeSpawnEgg))
            }
        }
        section.getConfigurationSection("ore")?.let { oreSection ->
            val ores = parseOreSection(oreSection)
            if (ores.isNotEmpty()) {
                attributes.add(ResourceAttributeOre(ores))
            }
        }
        section.getConfigurationSection("crops")?.let { cropsSection ->
            val crops = parseMaterialDoubleSection(cropsSection)
            if (crops.isNotEmpty()) {
                attributes.add(ResourceAttributeCrops(crops))
            }
        }
        section.getConfigurationSection("animals")?.let { animalsSection ->
            val animals = parseEntityDoubleSection(animalsSection)
            if (animals.isNotEmpty()) {
                attributes.add(ResourceAttributeAnimals(animals))
            }
        }

        // total multipliers
        if (section.contains("income_total_multiplier")) {
            attributes.add(ResourceAttributeTotalIncomeMultiplier(section.getDouble("income_total_multiplier")))
        }
        if (section.contains("ore_total_multiplier")) {
            attributes.add(ResourceAttributeTotalOreMultiplier(section.getDouble("ore_total_multiplier")))
        }
        if (section.contains("crops_total_multiplier")) {
            attributes.add(ResourceAttributeTotalCropsMultiplier(section.getDouble("crops_total_multiplier")))
        }
        if (section.contains("animals_total_multiplier")) {
            attributes.add(ResourceAttributeTotalAnimalsMultiplier(section.getDouble("animals_total_multiplier")))
        }

        // time modifier
        if (section.contains("attacker_time_multiplier")) {
            attributes.add(ResourceAttributeAttackerTimeMultiplier(section.getDouble("attacker_time_multiplier")))
        }

        // neighbor total multipliers
        if (section.contains("neighbor_income_total_multiplier")) {
            attributes.add(ResourceAttributeNeighborTotalIncomeMultiplier(section.getDouble("neighbor_income_total_multiplier")))
        }
        if (section.contains("neighbor_ore_total_multiplier")) {
            attributes.add(ResourceAttributeNeighborTotalOreMultiplier(section.getDouble("neighbor_ore_total_multiplier")))
        }
        if (section.contains("neighbor_crops_total_multiplier")) {
            attributes.add(ResourceAttributeNeighborTotalCropsMultiplier(section.getDouble("neighbor_crops_total_multiplier")))
        }
        if (section.contains("neighbor_animals_total_multiplier")) {
            attributes.add(ResourceAttributeNeighborTotalAnimalsMultiplier(section.getDouble("neighbor_animals_total_multiplier")))
        }
        if (section.contains("neighbor_attacker_time_multiplier")) {
            attributes.add(ResourceAttributeNeighborAttackerTimeMultiplier(section.getDouble("neighbor_attacker_time_multiplier")))
        }

        nodeTypes.put(
            name,
            ResourceNode(
                name = name,
                icon = icon,
                costConstant = 0,
                costScale = 1.0,
                priority = priority,
                attributes = attributes,
            ),
        )
    }

    return nodeTypes
}

private fun parseIncomeSection(section: ConfigurationSection): Pair<EnumMap<Material, Double>, EnumMap<EntityType, Double>> {
    val income = EnumMap<Material, Double>(Material::class.java)
    val incomeSpawnEgg = EnumMap<EntityType, Double>(EntityType::class.java)

    for (key in section.getKeys(false)) {
        val value = section.getDouble(key)
        val itemName = key.uppercase()
        if (itemName.startsWith("SPAWN_EGG_")) {
            val entityType = EntityType.valueOf(itemName.replace("SPAWN_EGG_", ""))
            incomeSpawnEgg.put(entityType, value)
        } else {
            val material = Material.matchMaterial(key)
            if (material !== null) {
                income.put(material, value)
            }
        }
    }

    return Pair(income, incomeSpawnEgg)
}

private fun parseOreSection(section: ConfigurationSection): EnumMap<Material, OreDeposit> {
    val ores = EnumMap<Material, OreDeposit>(Material::class.java)

    for (key in section.getKeys(false)) {
        val material = Material.matchMaterial(key) ?: continue
        val value = section.get(key)
        if (value is List<*> && value.size == 3) {
            val chance = (value[0] as Number).toDouble()
            val min = (value[1] as Number).toInt()
            val max = (value[2] as Number).toInt()
            ores.put(material, OreDeposit(material, chance, min, max))
        } else {
            val chance = section.getDouble(key)
            ores.put(material, OreDeposit(material, chance, 1, 1))
        }
    }

    return ores
}

private fun parseMaterialDoubleSection(section: ConfigurationSection): EnumMap<Material, Double> {
    val map = EnumMap<Material, Double>(Material::class.java)
    for (key in section.getKeys(false)) {
        val material = Material.matchMaterial(key)
        if (material !== null) {
            map.put(material, section.getDouble(key))
        }
    }
    return map
}

private fun parseEntityDoubleSection(section: ConfigurationSection): EnumMap<EntityType, Double> {
    val map = EnumMap<EntityType, Double>(EntityType::class.java)
    for (key in section.getKeys(false)) {
        try {
            val entityType = EntityType.valueOf(key.uppercase())
            map.put(entityType, section.getDouble(key))
        } catch (_: Exception) {
            // ignore invalid entries
        }
    }
    return map
}

// parse teleport material cost list:
// material: amount
private fun parseTeleportCost(section: ConfigurationSection): EnumMap<Material, Int> {
    val materials: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)

    for (item in section.getKeys(false)) {
        val material = Material.matchMaterial(item)
        if (material !== null) {
            materials.put(material, section.getInt(item))
        }
    }

    return materials
}

// string format of teleport cost item list
private fun teleportCostToString(materials: EnumMap<Material, Int>): String {
    var s = ""

    var index = 0
    for ((mat, amount) in materials) {
        s += "$amount $mat"

        if (index < materials.size - 1) {
            s += ", "
        }

        index += 1
    }

    return s
}

/**
 * Load yaml uuid string list into a hashset of uuid
 */
private fun parseUUIDSet(config: ConfigurationSection, listName: String): HashSet<UUID> {
    val uuids: HashSet<UUID> = hashSetOf()

    if (config.isList(listName)) {
        val uuidList = config.getStringList(listName)
        if (uuidList !== null) {
            for (uuidString in uuidList) {
                val uuid = try {
                    UUID.fromString(uuidString)
                } catch (err: Exception) {
                    System.err.println("[Config] Invalid UUID: $uuidString")
                    continue
                }

                uuids.add(uuid)
            }
        }
    }

    return uuids
}
