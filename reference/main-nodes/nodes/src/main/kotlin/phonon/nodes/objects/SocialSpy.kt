package phonon.nodes.objects

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.chat.ChatMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

public object SocialSpy {
    private val chatWatchers: MutableMap<ChatMode, MutableSet<UUID>> =
        ChatMode.values().associateWith { ConcurrentHashMap.newKeySet<UUID>() }.toMutableMap()
    private val diplomacyWatchers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    public fun toggleMode(player: Player, mode: ChatMode): Boolean {
        val uuid = player.uniqueId
        val watchers = chatWatchers[mode] ?: return false
        val isEnabled = watchers.contains(uuid)
        if (isEnabled) {
            watchers.remove(uuid)
        } else {
            watchers.add(uuid)
        }
        updateDiplomacyWatchers(uuid)
        return !isEnabled
    }

    public fun toggleAll(player: Player): Boolean {
        val uuid = player.uniqueId
        val currentlyAll = ChatMode.values().all { chatWatchers[it]?.contains(uuid) == true }
        if (currentlyAll) {
            ChatMode.values().forEach { chatWatchers[it]?.remove(uuid) }
        } else {
            ChatMode.values().forEach { chatWatchers[it]?.add(uuid) }
        }
        updateDiplomacyWatchers(uuid)
        return !currentlyAll
    }

    private fun updateDiplomacyWatchers(uuid: UUID) {
        val hasAny = chatWatchers.values.any { it.contains(uuid) }
        if (hasAny) {
            diplomacyWatchers.add(uuid)
        } else {
            diplomacyWatchers.remove(uuid)
        }
    }

    public fun notifyChat(mode: ChatMode, message: String) {
        val prefix = "${ChatColor.GRAY}[SocialSpy ${mode.name.lowercase()}]"
        val builder = StringBuilder(prefix.length + 1 + message.length + 1)
        builder.append(prefix)
        builder.append(' ')
        builder.append(message)

        val text = builder.toString()
        val watchers = chatWatchers[mode]?.toList() ?: return
        for (uuid in watchers) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null && player.isOnline) {
                Message.print(player, text)
            }
        }
    }

    public fun notifyDiplomacy(message: String) {
        val text = "${ChatColor.DARK_AQUA}[SocialSpy Diplomacy] $message"
        for (uuid in diplomacyWatchers.toList()) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null && player.isOnline) {
                Message.print(player, text)
            }
        }
    }
}
