package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.TerritoryId
import phonon.nodes.utils.string.filterByStart
import kotlin.math.floor

public class CapTimeCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            Message.error(sender, "This command can only be used in-game.")
            return true
        }

        val resident = Nodes.getResident(sender)
        val town = resident?.town
        if (town == null) {
            Message.error(sender, "You must be in a town to use /captime.")
            return true
        }

        if (args.size != 1) {
            Message.error(sender, "Usage: /captime [nodeId]")
            return true
        }

        val nodeId = args[0].toIntOrNull()
        if (nodeId == null) {
            Message.error(sender, "Invalid node id \"${args[0]}\"")
            return true
        }

        val territory = Nodes.getTerritoryFromId(TerritoryId(nodeId))
        if (territory == null) {
            Message.error(sender, "Node id $nodeId does not exist")
            return true
        }

        val coreChunk = Nodes.getTerritoryChunkFromCoord(territory.core)
        if (coreChunk == null) {
            Message.error(sender, "Node id $nodeId has no core chunk")
            return true
        }

        val ticks = Nodes.war.computeChunkAttackTimeTicks(coreChunk, town)
        val totalSeconds = ticks.toDouble() / 20.0
        val minutes = floor(totalSeconds / 60.0).toInt()
        val seconds = totalSeconds - (minutes * 60)
        val secondsFormatted = String.format("%.1f", seconds)

        Message.print(
            sender,
            "${ChatColor.GOLD}${ChatColor.BOLD}For node ${ChatColor.AQUA}$nodeId${ChatColor.GOLD}, a capture takes ${ChatColor.GREEN}$minutes${ChatColor.YELLOW} min ${ChatColor.GOLD}and ${ChatColor.GREEN}$secondsFormatted${ChatColor.YELLOW} seconds${ChatColor.GOLD}.",
        )
        if (Nodes.war.canOnlyAttackBorders) {
            Message.print(sender, "${ChatColor.YELLOW}Skirmish mode is enabled: only one active node attack per player.")
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): MutableList<String> {
        if (args.size == 1) {
            val ids = Nodes.territories.keys.map { it.toString() }
            return filterByStart(ids, args[0]).toMutableList()
        }
        return mutableListOf()
    }
}
