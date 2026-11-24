package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.killtracker.KillTrackerManager
import java.text.DecimalFormat

public class KdrCommand :
    CommandExecutor,
    TabCompleter {
    private val df = DecimalFormat("#.##")

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.isEmpty()) {
            if (sender !is Player) {
                Message.error(sender, "Console must specify a player name")
                return true
            }
            showPlayerStats(sender, sender.name)
            return true
        }

        if (args[0].equals("top", ignoreCase = true)) {
            showLeaderboard(sender)
            return true
        }

        showPlayerStats(sender, args[0])
        return true
    }

    private fun showPlayerStats(sender: CommandSender, playerName: String) {
        val result = KillTrackerManager.getStatsByName(playerName)
        if (result == null) {
            Message.error(sender, "Player not found: $playerName")
            return
        }

        val (stats, name) = result

        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}------------------------------")
        Message.print(sender, "${ChatColor.YELLOW}${ChatColor.BOLD}Kill/Death Stats for ${ChatColor.WHITE}$name")
        Message.print(sender, "${ChatColor.GREEN}Kills: ${ChatColor.WHITE}${stats.kills}")
        Message.print(sender, "${ChatColor.RED}Deaths: ${ChatColor.WHITE}${stats.deaths}")
        Message.print(sender, "${ChatColor.GOLD}KDR: ${ChatColor.WHITE}${df.format(stats.kdr)}")
        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}------------------------------")
    }

    private fun showLeaderboard(sender: CommandSender) {
        val topPlayers = KillTrackerManager.getTopPlayers(10)
        if (topPlayers.isEmpty()) {
            Message.error(sender, "No kill/death data available yet")
            return
        }

        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}------------------------------")
        Message.print(sender, "${ChatColor.YELLOW}${ChatColor.BOLD}Top 10 Players by KDR")

        topPlayers.forEachIndexed { index, (stats, name) ->
            val rank = index + 1
            val rankColor = when (rank) {
                1 -> "${ChatColor.GOLD}${ChatColor.BOLD}"
                2 -> "${ChatColor.GRAY}${ChatColor.BOLD}"
                3 -> "${ChatColor.DARK_RED}${ChatColor.BOLD}"
                else -> "${ChatColor.WHITE}"
            }
            Message.print(
                sender,
                "$rankColor$rank. ${ChatColor.WHITE}$name ${ChatColor.GRAY}- " +
                    "${ChatColor.GREEN}K: ${stats.kills} ${ChatColor.RED}D: ${stats.deaths} ${ChatColor.GOLD}KDR: ${df.format(stats.kdr)}",
            )
        }

        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}------------------------------")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = when (args.size) {
        1 -> {
            val query = args[0].lowercase()
            val suggestions = mutableListOf("top")
            suggestions.addAll(sender.server.onlinePlayers.map { it.name })
            suggestions.filter { it.lowercase().startsWith(query) }
        }
        else -> emptyList()
    }
}
