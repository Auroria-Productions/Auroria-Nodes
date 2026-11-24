/**
 * Resident
 * -----------------------------
 * Wrapper around Minecraft player, member
 * of a town.
 * Identified by name which equals unique Minecraft username.
 */

package phonon.nodes.objects

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.TerritoryCaptureNotificationMode
import phonon.nodes.chat.ChatMode
import phonon.nodes.killtracker.KillTrackerManager
import phonon.nodes.language.PlayerLanguage
import phonon.nodes.objects.MinimapOrientation
import phonon.nodes.serdes.SaveState
import phonon.nodes.war.AttackMessageMode
import java.util.UUID

public class Resident(val uuid: UUID, val name: String) {
    public class ResidentSaveState(r: Resident) : SaveState {
        public val uuid = r.uuid
        public val name = r.name
        public val town = r.town?.name
        public val nation = r.nation?.name
        public val claims = r.claims
        public val claimsTime = r.claimsTime
        public val prefix = r.prefix
        public val suffix = r.suffix
        public val trusted = r.trusted
        public val townCreateCooldown = r.townCreateCooldown
        public val townRejoinCooldown = r.townRejoinCooldown
        public val language = r.language?.code
        public val minimapOrientation = r.minimapOrientation.name
        public val chatMode = r.chatMode.name
        public val attackMessageMode = r.attackMessageMode.name
        public val territoryCaptureNotificationMode = r.territoryCaptureNotificationMode.name
        public val pointDisabled = r.isTownPointDisabled
        public val oreBlocksBroken = r.oreBlocksBroken

        public override var jsonString: String? = null

        public override fun createJsonString(): String {
            val jsonString = (
                "{" +
                    "\"name\":\"${this.name}\"," +
                    "\"town\":${ if (this.town !== null) "\"${this.town}\"" else null }," +
                    "\"nation\":${ if (this.nation !== null) "\"${this.nation}\"" else null }," +
                    "\"claims\":[${this.claims},${this.claimsTime}]," +
                    "\"prefix\":\"${this.prefix}\"," +
                    "\"suffix\":\"${this.suffix}\"," +
                    "\"trust\":${this.trusted}," +
                    "\"townCool\":${this.townCreateCooldown}," +
                    "\"townRejoinCool\":${this.townRejoinCooldown}," +
                    "\"lang\":${if (this.language != null) "\"${this.language}\"" else null}," +
                    "\"minimapOrientation\":\"${this.minimapOrientation}\"," +
                    "\"chatMode\":\"${this.chatMode}\"," +
                    "\"attackMessageMode\":\"${this.attackMessageMode}\"," +
                    "\"territoryCaptureNotificationMode\":\"${this.territoryCaptureNotificationMode}\"," +
                    "\"pointDisabled\":${this.pointDisabled}," +
                    "\"oreBlocksBroken\":${this.oreBlocksBroken}" +
                    "}"
                )
            return jsonString
        }
    }

    var town: Town? = null
    var nation: Nation? = null

    // claims power and current elapsed time until power added
    var claims: Int = 0
    var claimsTime: Long = 0L

    // town create cooldown listener
    var townCreateCooldown: Long = 0L

    // cooldown before player can re-join any town after leaving/being kicked
    var townRejoinCooldown: Long = 0L

    // flag that player trusted by town
    var trusted: Boolean = false

    // player is protecting chest with right click
    var isProtectingChests: Boolean = false

    // chat mode config
    var chatMode: ChatMode = ChatMode.GLOBAL
    var prefix: String = ""
    var suffix: String = ""
    var language: PlayerLanguage? = null

    var minimapOrientation: MinimapOrientation = MinimapOrientation.NORTH_UP
    var attackMessageMode: AttackMessageMode = AttackMessageMode.ALL
    var territoryCaptureNotificationMode: TerritoryCaptureNotificationMode = TerritoryCaptureNotificationMode.TITLE
    var isTownPointDisabled: Boolean = false
    var oreBlocksBroken: Long = 0L

    var townAttackMessagesOnly: Boolean
        get() = this.attackMessageMode == AttackMessageMode.TOWN
        set(value) {
            this.attackMessageMode = AttackMessageMode.fromLegacyTownOnly(value)
        }

    // town teleport thread and flag for outpost
    var teleportThread: ScheduledTask? = null
    var isTeleportingToOutpost: Boolean = false // if true, gives outpost teleport cost refund if teleport fails
    var isTeleportingToNationTown: Boolean = false // if true, gives nation town teleport cost refund if teleport fails

    // town invite
    var invitingNation: Nation? = null
    var invitingTown: Town? = null
    var invitingPlayer: Player? = null
    var inviteThread: ScheduledTask? = null

    // minimap
    var minimap: Minimap? = null

    // save state needs update flag
    private var saveState: ResidentSaveState

    @Suppress("PropertyName")
    private var _needsUpdate = false

    init {
        this.saveState = ResidentSaveState(this)
    }

    public override fun hashCode(): Int = this.uuid.hashCode()

    // returns player associated with resident
    // returns null when player is offline
    public fun player(): Player? = Bukkit.getPlayer(this.uuid)

    // ===================================
    // Minimap functions
    // each minimap attached to a resident
    // and only viewable by player
    // ===================================

    public fun createMinimap(player: Player, size: Int) {
        // remove any existing minimap
        this.destroyMinimap()

        // create new minimap
        this.minimap = Minimap(this, player, size)
    }

    public fun destroyMinimap() {
        val minimap = this.minimap
        if (minimap != null) {
            minimap.destroy()
            this.minimap = null
        }
    }

    // update player minimap if it exists
    public fun updateMinimap(coord: Coord) {
        this.minimap?.render(coord)
    }

    // ===================================

    // print resident info
    public fun printInfo(sender: CommandSender) {
        val townName = this.town?.name ?: "${ChatColor.GRAY}None"
        val nationName = this.nation?.name ?: "${ChatColor.GRAY}None"
        val currentTown = this.town

        val role = when {
            this === currentTown?.leader -> "Leader"
            currentTown != null && currentTown.generals.contains(this) -> "General"
            currentTown != null && currentTown.officers.contains(this) -> "Officer"
            currentTown != null -> "Member"
            else -> "${ChatColor.GRAY}None"
        }

        val stats = KillTrackerManager.getStats(this.uuid)
        val kdr = stats.kdr
        val ranking = KillTrackerManager.getRank(this.uuid)
        val kdrFormatted = String.format("%.2f", kdr)

        Message.print(sender, "${ChatColor.BOLD}Player ${this.name}:")
        Message.print(sender, "- Role${ChatColor.WHITE}: $role")
        Message.print(sender, "- Town${ChatColor.WHITE}: $townName")
        Message.print(sender, "- Nation${ChatColor.WHITE}: $nationName")
        Message.print(sender, "- Claim Power${ChatColor.WHITE}: ${this.claims}/${Config.playerClaimsMax}")
        Message.print(sender, "- Ore Blocks Broken${ChatColor.WHITE}: ${this.oreBlocksBroken}")
        Message.print(sender, "- Kills${ChatColor.WHITE}: ${stats.kills}")
        Message.print(sender, "- Deaths${ChatColor.WHITE}: ${stats.deaths}")
        Message.print(sender, "- KDR${ChatColor.WHITE}: $kdrFormatted")
        if (ranking != null) {
            Message.print(sender, "- KDR Rank${ChatColor.WHITE}: #$ranking")
        }
    }

    // function to let client flag this object as dirty
    public fun needsUpdate() {
        this._needsUpdate = true
    }

    // wrapper to return self as state
    // - returns memoized copy if needsUpdate false
    // - otherwise, parses self
    public fun getSaveState(): ResidentSaveState {
        if (this._needsUpdate) {
            this.saveState = ResidentSaveState(this)
            this._needsUpdate = false
        }
        return this.saveState
    }
}
