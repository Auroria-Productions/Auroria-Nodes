package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import phonon.nodes.Message
import phonon.nodes.war.WarzoneManager

public class WarzoneCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        if (args.isNotEmpty() && !args[0].equals("help", ignoreCase = true)) {
            Message.error(sender, "Usage: /warzone")
            return true
        }

        val summaries = WarzoneManager.getControlSummaries()
        if (summaries.isEmpty()) {
            Message.print(sender, "No warzones defined.")
            return true
        }

        val eventStatus = if (WarzoneManager.isEventActive()) {
            "${ChatColor.GREEN}ACTIVE"
        } else {
            "${ChatColor.GRAY}INACTIVE"
        }
        Message.print(sender, "${ChatColor.BOLD}Warzones${ChatColor.WHITE} ${ChatColor.GRAY}(${summaries.size}) ${ChatColor.DARK_GRAY}| $eventStatus")

        for (summary in summaries) {
            val holder = when {
                summary.isUncontrolled -> "${ChatColor.GRAY}None"
                summary.leaders.size > 1 -> "${ChatColor.YELLOW}Contested"
                else -> "${ChatColor.GREEN}${summary.leaders.first().town.name}"
            }

            Message.print(
                sender,
                "${ChatColor.DARK_RED}${summary.warzone.name}${ChatColor.WHITE} ${ChatColor.DARK_GRAY}-> ${ChatColor.GRAY}${summary.totalControlledNodes}/${summary.totalNodes}${ChatColor.DARK_GRAY} | ${ChatColor.WHITE}Holder: $holder",
            )
        }

        val pointShares = WarzoneManager.getPointShares()
        if (pointShares.isEmpty()) {
            Message.print(sender, "${ChatColor.GRAY}Points: none")
        } else {
            val scoreLine = pointShares.joinToString(", ") { share ->
                "${share.town.name} ${share.points}p"
            }
            Message.print(sender, "${ChatColor.GOLD}Points${ChatColor.DARK_GRAY}: ${ChatColor.WHITE}$scoreLine")
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> = listOf()
}
