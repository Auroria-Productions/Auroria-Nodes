package phonon.nodes.listeners

import org.bukkit.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLoginEvent
import phonon.nodes.utils.leaderlaunch.LeaderLaunchManager

class NodesLeaderLaunchListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerLogin(event: PlayerLoginEvent) {
        val player = event.player

        if (player.hasPermission("nodes.leaderlaunch.bypass")) {
            return
        }

        if (LeaderLaunchManager.canJoin(player.uniqueId)) {
            return
        }

        event.disallow(
            PlayerLoginEvent.Result.KICK_OTHER,
            buildBlockedMessage(),
        )
    }

    private fun buildBlockedMessage(): String = listOf(
        "${ChatColor.DARK_RED}${ChatColor.BOLD}Leader Launch Active",
        "${ChatColor.RED}You cannot join the server right now.",
        "",
        "${ChatColor.GOLD}Need access?",
        "${ChatColor.YELLOW}Buy a Leader Launch pass or a rank in the store.",
        "${ChatColor.AQUA}Discord: ${ChatColor.WHITE}https://discord.gg/zWZrAGbcQX",
    ).joinToString("\n")
}
