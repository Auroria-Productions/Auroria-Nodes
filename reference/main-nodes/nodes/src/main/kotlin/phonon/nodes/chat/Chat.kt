/**
 * Basic  tuff shit idkchat pub/sub channels manager.
 */

package phonon.nodes.chat

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerChatEvent
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Resident
import phonon.nodes.objects.SocialSpy
import phonon.nodes.utils.LuckPermsPrefix

public enum class ChatMode {
    GLOBAL,
    TOWN,
    NATION,
    ALLY,
    LOCAL,
    ;

    public companion object {
        public fun fromName(value: String?): ChatMode {
            if (value == null) {
                return GLOBAL
            }

            return try {
                valueOf(value.uppercase())
            } catch (_: IllegalArgumentException) {
                GLOBAL
            }
        }
    }
}

private const val LOCAL_CHAT_DISTANCE_SQ = 10000.0

public object Chat {

    val playersMuteGlobal: HashSet<Player> = hashSetOf()

    var colorDefault = ChatColor.WHITE
    val colorGreen = ChatColor.GREEN

    var colorTown = ChatColor.DARK_AQUA
    var colorNation = ChatColor.GOLD
    var colorAlly = ChatColor.GREEN
    var colorLocal = ChatColor.LIGHT_PURPLE

    var colorPlayerTownless = ChatColor.GRAY
    var colorPlayerOp = ChatColor.DARK_RED
    var colorPlayerTownOfficer = ChatColor.WHITE
    var colorPlayerTownLeader = ChatColor.BOLD
    var colorPlayerNationLeader = "${ChatColor.GOLD}${ChatColor.BOLD}"

    public fun process(event: AsyncPlayerChatEvent) {
        // FIRST MOST IMPORTANT: APPLY GREENTEXT
        val msg = event.getMessage()
        if (msg.get(0) == '>') {
            event.setMessage("${colorGreen}$msg")
        }

        // get player chat mode
        val player = event.getPlayer()
        val fetchResident = Nodes.getResident(player)
        val resident: Resident = if (fetchResident != null) {
            fetchResident
        } else { // print normal message...
            return
        }

        val chatMode = resident.chatMode

        when (chatMode) {
            ChatMode.GLOBAL -> {
                // remove players who muted global
                val recipients = event.getRecipients()
                for (p in Chat.playersMuteGlobal) {
                    recipients.remove(p)
                }
                event.setFormat(formatMsgGlobal(resident))
            }
            ChatMode.TOWN -> {
                val town = resident.town
                if (town == null) {
                    event.setCancelled(true)
                    return
                }
                event.getRecipients().clear()
                event.getRecipients().addAll(town.playersOnline)
                event.setFormat(formatMsgTown(resident))
            }
            ChatMode.NATION -> {
                val nation = resident.nation
                if (nation == null) {
                    event.setCancelled(true)
                    return
                }
                event.getRecipients().clear()
                event.getRecipients().addAll(nation.playersOnline)
                event.setFormat(formatMsgNation(resident))
            }
            ChatMode.ALLY -> {
                val town = resident.town
                if (town == null) {
                    event.setCancelled(true)
                    return
                }
                event.getRecipients().clear()
                event.getRecipients().addAll(town.playersOnline)
                for (allyTown in town.allies) {
                    event.getRecipients().addAll(allyTown.playersOnline)
                }
                event.setFormat(formatMsgAlly(resident))
            }
            ChatMode.LOCAL -> {
                val senderLoc = player.location
                event.getRecipients().clear()
                for (target in Bukkit.getOnlinePlayers()) {
                    if (target.world !== player.world) {
                        continue
                    }

                    val delta = target.location.distanceSquared(senderLoc)
                    if (delta <= LOCAL_CHAT_DISTANCE_SQ) {
                        event.getRecipients().add(target)
                    }
                }
                event.setFormat(formatMsgLocal(resident))
            }
        }

        val formattedMessage = String.format(event.format, player.name, event.message)
        SocialSpy.notifyChat(chatMode, formattedMessage)
    }

    // unmute global chat for player
    public fun enableGlobalChat(player: Player) {
        Chat.playersMuteGlobal.remove(player)
    }

    // mute global chat for player
    public fun disableGlobalChat(player: Player) {
        Chat.playersMuteGlobal.add(player)
    }

    public fun isMuted(player: Player): Boolean {
        // TODO: hook into litebans, check muted player
        return false
    }

    public fun formatResidentName(resident: Resident): String {
        val town = resident.town
        val nation = resident.nation
        val chatPrefix = resolveChatPrefix(resident)

        // get player name color
        val color = if (resident.player()?.isOp() == true) {
            colorPlayerOp
        } else if (town == null) {
            colorPlayerTownless
        } else { // town != null
            if (resident === nation?.capital?.leader) {
                colorPlayerNationLeader
            } else if (resident.uuid == town.leader?.uuid) {
                colorPlayerTownLeader
            } else if (town.officers.contains(resident)) {
                colorPlayerTownOfficer
            } else {
                colorDefault
            }
        }

        if (chatPrefix != "" && resident.suffix != "") {
            return "${color}$chatPrefix $color%1\$s ${color}${resident.suffix}"
        } else if (chatPrefix != "") {
            return "${color}$chatPrefix $color%1\$s"
        } else if (resident.suffix != "") {
            return "$color%1\$s ${resident.suffix}"
        } else {
            return "$color%1\$s"
        }
    }

    private fun resolveChatPrefix(resident: Resident): String {
        val player = resident.player() ?: return normalizePrefixForChat(resident.prefix)
        if (!shouldUseLuckPrefix(resident)) {
            return normalizePrefixForChat(resident.prefix)
        }
        val resolved = LuckPermsPrefix.resolve(
            player,
            Config.nametagLuckPermsPrefixPlaceholder,
            Config.nametagLuckPermsPrefixCacheMs,
        )
        if (resolved.isNotBlank()) {
            return normalizePrefixForChat(resolved)
        }
        return normalizePrefixForChat(resident.prefix)
    }

    private fun shouldUseLuckPrefix(resident: Resident): Boolean {
        val town = resident.town ?: return true
        if (resident === town.leader) {
            return true
        }
        if (town.generals.contains(resident)) {
            return true
        }
        if (town.officers.contains(resident)) {
            return false
        }
        return true
    }

    private fun normalizePrefixForChat(prefix: String): String {
        if (prefix.isBlank()) {
            return ""
        }
        val plain = ChatColor.stripColor(prefix)?.trim() ?: prefix.trim()
        if (plain == "[.]") {
            return ""
        }
        return prefix
    }

    public fun formatMsgGlobal(resident: Resident): String {
        // format player name
        val formattedResidentName = formatResidentName(resident)

        // format town, nation
        val formattedResidentAllegience = if (resident.town != null && resident.nation != null) {
            "[${colorNation}${resident.nation?.name}$colorDefault|${colorTown}${resident.town?.name}$colorDefault] "
        } else if (resident.town != null) {
            "[${colorTown}${resident.town?.name}$colorDefault] "
        } else {
            ""
        }

        return "${formattedResidentAllegience}${formattedResidentName}$colorDefault: %2\$s"
    }

    public fun formatMsgTown(resident: Resident): String {
        // format player name
        val formattedResidentName = formatResidentName(resident)

        return "$colorTown[Town] ${formattedResidentName}$colorTown: %2\$s"
    }

    public fun formatMsgNation(resident: Resident): String {
        // format player name
        val formattedResidentName = formatResidentName(resident)

        return "$colorNation[Nation] ${formattedResidentName}$colorNation: %2\$s"
    }

    public fun formatMsgAlly(resident: Resident): String {
        // format player name
        val formattedResidentName = formatResidentName(resident)

        return "$colorAlly[Ally] ${formattedResidentName}$colorAlly: %2\$s"
    }

    public fun formatMsgLocal(resident: Resident): String {
        val formattedResidentName = formatResidentName(resident)

        return "$colorLocal[Local] ${formattedResidentName}$colorLocal: %2\$s"
    }
}
