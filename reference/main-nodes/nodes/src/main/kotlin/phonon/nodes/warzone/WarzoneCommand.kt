package phonon.nodes.warzone

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message

public class WarzoneCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player && sender.hasPermission("nodes.admin")) {
                WarzoneGui.openMain(sender)
                return true
            }

            val warzones = WarzoneManager.getWarzones()
            if (warzones.isEmpty()) {
                Message.print(sender, "${WarzoneText.prefix()} No warzones are configured.")
            } else {
                Message.print(sender, "${WarzoneText.prefix()} Warzones: ${warzones.joinToString(", ") { it.name }}")
                Message.print(sender, "Use /warzone <name> to see details.")
            }
            return true
        }

        if (args[0].equals("edit", ignoreCase = true)) {
            if (!sender.hasPermission("nodes.admin")) {
                Message.error(sender, "You do not have permission to manage warzones.")
                return true
            }
            if (sender !is Player) {
                Message.error(sender, "Use this command in-game so the warzone menu can open.")
                return true
            }

            val name = args.drop(1).joinToString(" ").trim()
            if (name.isEmpty()) {
                WarzoneGui.openMain(sender)
                return true
            }

            val warzone = WarzoneManager.getWarzone(name)
            if (warzone == null) {
                Message.error(sender, "Unknown warzone \"$name\".")
                return true
            }

            WarzoneGui.openDetail(sender, warzone)
            return true
        }

        val name = args.joinToString(" ").trim()
        val warzone = WarzoneManager.getWarzone(name)
        if (warzone == null) {
            Message.error(sender, "Unknown warzone \"$name\".")
            return true
        }

        WarzoneInfoPrinter.printSummary(sender, warzone)
        return true
    }

    override fun onTabComplete(sender: CommandSender, cmd: Command, alias: String, args: Array<String>): MutableList<String> {
        if (args.isEmpty()) {
            return mutableListOf()
        }

        if (args.size == 1) {
            val current = args[0].lowercase()
            val suggestions = mutableListOf<String>()
            if (sender.hasPermission("nodes.admin") && "edit".startsWith(current)) {
                suggestions.add("edit")
            }
            suggestions.addAll(
                WarzoneManager.getWarzones()
                    .map { it.name }
                    .filter { it.lowercase().startsWith(current) },
            )
            return suggestions.distinct().toMutableList()
        }

        if (args[0].equals("edit", ignoreCase = true) && sender.hasPermission("nodes.admin")) {
            val current = args.drop(1).joinToString(" ").trim().lowercase()
            return WarzoneManager.getWarzones()
                .map { it.name }
                .filter { it.lowercase().startsWith(current) }
                .toMutableList()
        }

        return mutableListOf()
    }
}
