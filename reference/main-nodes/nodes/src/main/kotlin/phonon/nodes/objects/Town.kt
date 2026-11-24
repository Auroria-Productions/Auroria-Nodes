/**
 * Town
 *
 */

package phonon.nodes.objects

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.serdes.SaveState
import phonon.nodes.serdes.TownRecord
import phonon.nodes.utils.Color
import phonon.nodes.utils.EnumArrayMap
import phonon.nodes.utils.ItemStackCodec
import phonon.nodes.utils.createEnumArrayMap
import phonon.nodes.utils.string.stringMapFromMap
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Wrapper type for town uuid.
 */
@JvmInline
value class TownId(private val id: UUID) {
    override fun toString(): String = id.toString()
    fun toUUID(): UUID = id
}

// internal town id counter
private var townNametagIdCounter: Int = 0

public class Town(
    val uuid: UUID,
    var name: String,
    var home: TerritoryId, // main territory owned by town
    var leader: Resident?,
    var spawnpoint: Location,
) {
    // town numeric id, not saved, can change on reload
    // used by nametag scoreboard system (cannot use name because 16 char team limit)
    val townNametagId: Int

    // residents belong to town
    val residents: HashSet<Resident> = hashSetOf()

    // officer rank players (assistants to leader)
    val officers: HashSet<Resident> = hashSetOf()

    // general rank players (above officer, manages officers)
    val generals: HashSet<Resident> = hashSetOf()

    // territories owned by town
    // this includes annexed territories
    val territories: HashSet<TerritoryId> = hashSetOf(home)

    // separate set of all annexed territories
    // config: for these to count towards total claims
    val annexed: HashSet<TerritoryId> = hashSetOf()

    // territories captured by town (but not annexed)
    val captured: HashSet<TerritoryId> = hashSetOf()

    // outposts in town, players can /t spawn [output]
    // map name -> town outpost
    val outposts: HashMap<String, TownOutpost> = hashMapOf()

    // shared outpost credits for this town
    var outpostCredits: Int = 0

    // progression currency accumulated over time
    var townPoints: Long = 0L

    // nation for town
    var nation: Nation? = null

    // town's diplomatic relations: allies, enemies, truce
    // these determine who town can attack during war
    val allies: HashSet<Town> = hashSetOf()
    val enemies: HashSet<Town> = hashSetOf()

    // players currently online in town
    // must be Set to satisfy bukkit interface in Chat.kt
    val playersOnline: MutableSet<Player> = mutableSetOf()

    // territory claims power
    var claimsUsed: Int = 0 // territory claim power used
    var claimsMax: Int = 0 // calculated total claim power
    var claimsBonus: Int = 0 // manual adjust add/subtract claim power
    var claimsPenalty: Int = 0 // subtracted from max power, decay over time
    var claimsPenaltyTime: Long = 0 // time progress until penalty reduced
    var claimsAnnexed: Int = 0 // claims penalty from territories that were annexed

    // flag that town is over max claims
    var isOverClaimsMax: Boolean = false

    // income storage container from territory income
    // map material -> current amount of it
    val income: IncomeInventory = IncomeInventory()

    // permission flags, map of
    // town permissions category -> set of allowed groups in (town, ally, nation, outsider)
    // TODO: replace with EnumArrayMap custom data structure
    val permissions: EnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>>

    // protected chest blocks in town (for leader, officers, + trusted players)
    val protectedBlocks: HashSet<Block> = hashSetOf()

    // color for displaying on map
    var color: Color

    // re-usable nametag strings, for each diplomatic relation type
    var nametagTown: String // nametag seen by players in town
    var nametagNation: String // nametag seen by players in same nation
    var nametagNeutral: String // nametag seen by players neutral to this town
    var nametagAlly: String // nametag seen by allies of this town
    var nametagEnemy: String // nametag seen by enemies of this town

    // pre-built NMS Component objects for each relation — rebuilt whenever strings change
    // avoids allocating Component.literal() on every packet send in the hot path
    var nametagTownComponent: net.minecraft.network.chat.Component = net.minecraft.network.chat.Component.empty()
    var nametagNationComponent: net.minecraft.network.chat.Component = net.minecraft.network.chat.Component.empty()
    var nametagNeutralComponent: net.minecraft.network.chat.Component = net.minecraft.network.chat.Component.empty()
    var nametagAllyComponent: net.minecraft.network.chat.Component = net.minecraft.network.chat.Component.empty()
    var nametagEnemyComponent: net.minecraft.network.chat.Component = net.minecraft.network.chat.Component.empty()

    // flag that anyone can join town
    var isOpen: Boolean = false

    // players applying to town and their scheduledtasks
    val applications: HashMap<Resident, ScheduledTask> = hashMapOf()

    // cooldown timer for moving town home territory
    var moveHomeCooldown: Long = 0L

    // json string and memoization flag
    private var saveState: TownSaveState

    @Suppress("PropertyName")
    private var _needsUpdate = false

    init {
        this.townNametagId = townNametagIdCounter
        townNametagIdCounter += 1

        // default /t permissions:
        // town members are allowed to build, destroy, interact, use chests and items
        this.permissions = createEnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>>({ _ ->
            EnumSet.noneOf(PermissionsGroup::class.java)
        })
        this.permissions[TownPermissions.BUILD].add(PermissionsGroup.TOWN)
        this.permissions[TownPermissions.DESTROY].add(PermissionsGroup.TOWN)
        this.permissions[TownPermissions.INTERACT].add(PermissionsGroup.TOWN)
        this.permissions[TownPermissions.CHESTS].add(PermissionsGroup.TOWN)
        this.permissions[TownPermissions.USE_ITEMS].add(PermissionsGroup.TOWN)

        if (leader != null) {
            // add creator to residents list
            this.residents.add(leader!!)

            // add creator as online
            if (this.leader!!.player()?.isOnline == true) {
                this.playersOnline.add(leader!!.player()!!)
            }
        }

        // assign town random color
        val random = ThreadLocalRandom.current()
        this.color = Color(
            random.nextInt(256),
            random.nextInt(256),
            random.nextInt(256),
        )

        // generate nametags from name
        this.nametagTown = "${ChatColor.GREEN}[${this.name}] "
        this.nametagNation = "${ChatColor.DARK_GREEN}[${this.name}] "
        this.nametagNeutral = "${ChatColor.GOLD}[${this.name}] "
        this.nametagAlly = "${ChatColor.BLUE}[${this.name}] "
        this.nametagEnemy = "${ChatColor.RED}[${this.name}] "
        this.nametagTownComponent = net.minecraft.network.chat.Component.literal(this.nametagTown)
        this.nametagNationComponent = net.minecraft.network.chat.Component.literal(this.nametagNation)
        this.nametagNeutralComponent = net.minecraft.network.chat.Component.literal(this.nametagNeutral)
        this.nametagAllyComponent = net.minecraft.network.chat.Component.literal(this.nametagAlly)
        this.nametagEnemyComponent = net.minecraft.network.chat.Component.literal(this.nametagEnemy)

        // generate initial json string
        this.saveState = TownSaveState(this)
    }

    fun Color.toChatColor(): ChatColor {
        val r = this.r
        val g = this.g
        val b = this.b

        return when {
            r >= 170 && g < 85 && b < 85 -> ChatColor.RED
            r < 85 && g >= 170 && b < 85 -> ChatColor.GREEN
            r < 85 && g < 85 && b >= 170 -> ChatColor.BLUE
            r >= 170 && g >= 170 && b < 100 -> ChatColor.YELLOW
            r >= 170 && g < 100 && b >= 170 -> ChatColor.LIGHT_PURPLE
            r < 100 && g >= 170 && b >= 170 -> ChatColor.AQUA
            r >= 200 && g >= 200 && b >= 200 -> ChatColor.WHITE
            r <= 60 && g <= 60 && b <= 60 -> ChatColor.BLACK
            r >= 128 && g >= 128 && b < 80 -> ChatColor.GOLD
            r < 128 && g < 128 && b < 128 -> ChatColor.GRAY
            r >= 170 && g >= 85 && b >= 85 -> ChatColor.RED // reddish
            else -> ChatColor.GRAY
        }
    }

    // You can also add helpers like:
    fun Color.darken(factor: Double = 0.7): Color = Color(
        (this.r * factor).toInt().coerceIn(0, 255),
        (this.g * factor).toInt().coerceIn(0, 255),
        (this.b * factor).toInt().coerceIn(0, 255),
    )

    // Optional helpers if you want better matching
    private fun Color.isDark() = (r + g + b) < 180
    private fun Color.isBright() = (r + g + b) > 650

    public override fun hashCode(): Int = this.uuid.hashCode()

    public fun updateNametags() {
        val prefix = "[${this.name}] "

        // Fixed diplomatic colors:
        // Ally = blue, Enemy = red, Neutral = orange/gold, Own Town = green, Nation = dark green.
        this.nametagTown = "${ChatColor.GREEN}$prefix"
        this.nametagNation = "${ChatColor.DARK_GREEN}$prefix"
        this.nametagNeutral = "${ChatColor.GOLD}$prefix"
        this.nametagAlly = "${ChatColor.BLUE}$prefix"
        this.nametagEnemy = "${ChatColor.RED}$prefix"
        this.nametagTownComponent = net.minecraft.network.chat.Component.literal(this.nametagTown)
        this.nametagNationComponent = net.minecraft.network.chat.Component.literal(this.nametagNation)
        this.nametagNeutralComponent = net.minecraft.network.chat.Component.literal(this.nametagNeutral)
        this.nametagAllyComponent = net.minecraft.network.chat.Component.literal(this.nametagAlly)
        this.nametagEnemyComponent = net.minecraft.network.chat.Component.literal(this.nametagEnemy)
    }

    // prints out nation object info
    public fun printInfo(sender: CommandSender) {
        val nation = this.nation?.name ?: "${ChatColor.GRAY}None"
        val leader = this.leader?.name ?: "${ChatColor.GRAY}None"
        val officers = if (this.officers.size > 0) {
            this.officers.map { r -> r.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }
        val generals = if (this.generals.size > 0) {
            this.generals.map { r -> r.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }
        val residents = if (this.residents.size > 0) {
            this.residents.map { r -> r.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }
        val allies = if (this.allies.size > 0) {
            this.allies.map { it -> it.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }
        val enemies = if (this.enemies.size > 0) {
            this.enemies.map { it -> it.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }

        // make max claims red if town has penalty
        val claimsMaxColor = if (claimsPenalty > 0) {
            "${ChatColor.RED}"
        } else {
            ""
        }
        // used claims dark red if over max
        val claimsUsedColor = if (isOverClaimsMax) {
            "${ChatColor.DARK_RED}"
        } else {
            ""
        }

        Message.print(sender, "${ChatColor.BOLD}Town ${this.name}:")
        Message.print(sender, "- Home${ChatColor.WHITE}: Territory (id = ${this.home})")
        Message.print(sender, "- Territories${ChatColor.WHITE}: ${this.territories.size}")
        Message.print(sender, "- Claim Power${ChatColor.WHITE}: ${claimsUsedColor}${this.claimsUsed}/${claimsMaxColor}${this.claimsMax}")
        Message.print(sender, "- Nation${ChatColor.WHITE}: $nation")
        Message.print(sender, "- Allies${ChatColor.WHITE}: $allies")
        Message.print(sender, "- Enemies${ChatColor.WHITE}: $enemies")
        Message.print(sender, "- Leader${ChatColor.WHITE}: $leader")
        Message.print(sender, "- Generals[${this.generals.size}]${ChatColor.WHITE}: $generals")
        Message.print(sender, "- Officers[${this.officers.size}]${ChatColor.WHITE}: $officers")
        Message.print(sender, "- Residents[${this.residents.size}]${ChatColor.WHITE}: $residents")
        Message.print(sender, "- Outpost Credits${ChatColor.WHITE}: ${this.outpostCredits}")
        Message.print(sender, "- Town Points${ChatColor.WHITE}: ${this.townPoints}")
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    public class TownSaveState(t: Town) :
        SaveState,
        TownRecord {
        public val uuid = t.uuid
        public override val name = t.name
        public val leader = t.leader?.uuid
        public val home = t.home
        public val spawnpoint = doubleArrayOf(t.spawnpoint.x, t.spawnpoint.y, t.spawnpoint.z)
        public val color = intArrayOf(t.color.r, t.color.g, t.color.b)
        public val permissions = t.permissions.copyOf()
        public val residents = t.residents.map { x -> x.uuid }
        public val generals = t.generals.map { x -> x.uuid }
        public val officers = t.officers.map { x -> x.uuid }
        public val claimsBonus = t.claimsBonus
        public val claimsAnnexed = t.claimsAnnexed
        public val claimsPenalty = t.claimsPenalty
        public val claimsPenaltyTime = t.claimsPenaltyTime
        public val territories = t.territories.toList()
        public val annexed = t.annexed.toList()
        public val captured = t.captured.toList()
        public val outposts: HashMap<String, TownOutpost> = HashMap(t.outposts)
        public val outpostCredits = t.outpostCredits
        public val townPoints = t.townPoints
        public val allies = t.allies.map { x -> x.name }
        public val enemies = t.enemies.map { x -> x.name }
        public val income = t.income.storage.clone()
        public val incomeEgg = t.income.storageSpawnEgg.clone()
        public val incomeItems = t.income.storageCustom.mapNotNull {
            try {
                ItemStackCodec.encode(it)
            } catch (_: Exception) {
                null
            }
        }
        public val isOpen = t.isOpen
        public val protectedBlocks: HashSet<Block> = HashSet(t.protectedBlocks)
        public val moveHomeCooldown = t.moveHomeCooldown

        public override var jsonString: String? = null

        public override fun createJsonString(): String {
            val leaderUUID = if (this.leader != null) "\"${this.leader}\"" else null
            val generals = this.generals.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val officers = this.officers.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val residents = this.residents.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val territories = this.territories.joinToString(",", "[", "]")
            val annexed = this.annexed.joinToString(",", "[", "]")
            val captured = this.captured.joinToString(",", "[", "]")
            val allies = this.allies.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val enemies = this.enemies.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val income = stringMapFromMap<Material, Int>(
                this.income,
                { k -> "\"$k\"" },
                { v -> "$v" },
            )
            val incomeSpawnEgg = stringMapFromMap<EntityType, Int>(
                this.incomeEgg,
                { k -> "\"$k\"" },
                { v -> "$v" },
            )
            val incomeItems = this.incomeItems.joinToString(",", "[", "]") { value -> "\"$value\"" }

            val col = this.color
            val spawn = "[${this.spawnpoint[0]},${this.spawnpoint[1]},${this.spawnpoint[2]}]"

            val permissions = permissionsToJsonString(this.permissions)

            val jsonStrong = (
                "{" +
                    "\"uuid\":\"${this.uuid}\"," +
                    "\"leader\":$leaderUUID," +
                    "\"home\":${this.home}," +
                    "\"spawn\":$spawn," +
                    "\"color\":[${col[0]},${col[1]},${col[2]}]," +
                    "\"perms\":$permissions," +
                    "\"residents\":$residents," +
                    "\"generals\":$generals," +
                    "\"officers\":$officers," +
                    "\"claimsBonus\":${this.claimsBonus}," +
                    "\"claimsAnnexed\":${this.claimsAnnexed}," +
                    "\"claimsPenalty\":[${this.claimsPenalty},${this.claimsPenaltyTime}]," +
                    "\"territories\":$territories," +
                    "\"annexed\":$annexed," +
                    "\"captured\":$captured," +
                    "\"outpost\":${townOutpostsToJsonString(this.outposts)}," +
                    "\"outpostCredits\":${this.outpostCredits}," +
                    "\"townPoints\":${this.townPoints}," +
                    "\"allies\":$allies," +
                    "\"enemies\":$enemies," +
                    "\"income\":$income," +
                    "\"incomeEgg\":$incomeSpawnEgg," +
                    "\"incomeItems\":$incomeItems," +
                    "\"open\":${this.isOpen}," +
                    "\"protect\":${blocksToJsonString(this.protectedBlocks)}," +
                    "\"homeCool\":${this.moveHomeCooldown}" +
                    "}"
                )

            return jsonStrong
        }
    }

    // function to let client flag this object as dirty
    public fun needsUpdate() {
        this._needsUpdate = true
    }

    // wrapper to return self as savestate
    // always regenerate a fresh snapshot to ensure town data is written
    public fun getSaveState(): TownSaveState {
        this.saveState = TownSaveState(this)
        this._needsUpdate = false
        return this.saveState
    }
}
