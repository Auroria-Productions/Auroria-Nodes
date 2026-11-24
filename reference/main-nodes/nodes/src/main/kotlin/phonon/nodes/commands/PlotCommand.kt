package phonon.nodes.commands

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Town
import phonon.nodes.plot.PlotManager
import phonon.nodes.plot.PlotPermissionGroup
import phonon.nodes.plot.PlotPermissionType
import phonon.nodes.utils.string.filterByStart
import phonon.nodes.utils.stringInputIsValid

private val PLOT_COMMANDS = listOf("select", "create", "delete", "owner", "permission", "permissions", "list", "near", "info", "show")

public class PlotCommand :
    CommandExecutor,
    TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            help(sender)
            return true
        }

        when (args[0].lowercase()) {
            "select" -> select(sender)
            "create" -> create(sender, args)
            "delete" -> delete(sender, args)
            "owner" -> owner(sender, args)
            "permission", "permissions" -> permission(sender, args)
            "list" -> list(sender, args)
            "near" -> near(sender)
            "info" -> info(sender, args)
            "show" -> show(sender)
            else -> Message.error(sender, "Invalid plot command")
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> = when (args.size) {
        1 -> filterByStart(PLOT_COMMANDS, args[0])
        2 -> when (args[0].lowercase()) {
            "delete", "info", "owner", "permission", "permissions" -> filterByStart(PlotManager.allPlots().map { it.name }, args[1])
            "list" -> filterByStart(listOf("player", "town", "all"), args[1])
            else -> emptyList()
        }
        3 -> when (args[0].lowercase()) {
            "permission", "permissions" -> filterByStart(listOf("all", "build", "chests", "destroy", "interact", "use_items"), args[2])
            else -> emptyList()
        }
        4 -> when (args[0].lowercase()) {
            "permission", "permissions" -> {
                val targets = listOf("ally", "nation", "officer", "othertown", "outsider", "owner", "town", "trusted", "member") +
                    Bukkit.getOnlinePlayers().map { it.name }
                filterByStart(targets.distinct(), args[3])
            }
            else -> emptyList()
        }
        5 -> when (args[0].lowercase()) {
            "permission", "permissions" -> filterByStart(listOf("allow", "deny"), args[4])
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun help(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}[Plot] Commands:")
        Message.print(sender, "${ChatColor.AQUA}/t plot select${ChatColor.WHITE} - toggle selection mode, then left/right click blocks")
        Message.print(sender, "${ChatColor.AQUA}/t plot create <name>${ChatColor.WHITE} - create plot")
        Message.print(sender, "${ChatColor.AQUA}/t plot delete <name>${ChatColor.WHITE} - delete plot")
        Message.print(sender, "${ChatColor.AQUA}/t plot owner <plot> <player>${ChatColor.WHITE} - set owner")
        Message.print(sender, "${ChatColor.AQUA}/t plot permissions <plot> <type> <group|player> <allow|deny>${ChatColor.WHITE} - set permissions")
        Message.print(sender, "${ChatColor.AQUA}/t plot list player <name>${ChatColor.WHITE} - list player plots")
        Message.print(sender, "${ChatColor.AQUA}/t plot list town|all${ChatColor.WHITE} - list town/all plots")
        Message.print(sender, "${ChatColor.AQUA}/t plot near${ChatColor.WHITE} - show nearby plot")
        Message.print(sender, "${ChatColor.AQUA}/t plot info <name>${ChatColor.WHITE} - plot details")
        Message.print(sender, "${ChatColor.AQUA}/t plot show${ChatColor.WHITE} - show plot borders with particles for 5 seconds")
    }

    private fun select(sender: CommandSender) {
        val player = sender as? Player ?: run {
            Message.error(sender, "Only players can use this")
            return
        }

        val now = !PlotManager.isSelectionMode(player.uniqueId)
        PlotManager.setSelectionMode(player.uniqueId, now)
        if (now) {
            Message.print(player, "${ChatColor.AQUA}[Plot] Selection mode enabled: left click = pos1, right click = pos2")
        } else {
            Message.print(player, "${ChatColor.YELLOW}[Plot] Selection mode disabled")
        }
    }

    private fun create(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player ?: run {
            Message.error(sender, "Only players can use this")
            return
        }

        if (args.size < 2) {
            Message.error(player, "Usage: /plot create <name>")
            return
        }

        val name = args[1]
        if (!stringInputIsValid(name, 32)) {
            Message.error(player, "Invalid plot name")
            return
        }

        val resident = Nodes.getResident(player) ?: run {
            Message.error(player, "Could not resolve resident")
            return
        }
        val town = resident.town ?: run {
            Message.error(player, "You must be in a town")
            return
        }
        if (!canManageTown(resident, town)) {
            Message.error(player, "Only town leader/officers can manage plots")
            return
        }

        val selection = PlotManager.getSelection(player.uniqueId) ?: run {
            Message.error(player, "Set both positions first with /plot select + wooden axe")
            return
        }

        PlotManager.createPlot(name, town, resident, selection)
            .onSuccess { plot ->
                Message.print(player, "${ChatColor.GREEN}[Plot] Created '${plot.name}' for town ${town.name}")
                PlotManager.clearSelection(player.uniqueId)
            }
            .onFailure { err ->
                Message.error(player, err.message ?: "Could not create plot")
            }
    }

    private fun delete(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player ?: run {
            Message.error(sender, "Only players can use this")
            return
        }
        if (args.size < 2) {
            Message.error(player, "Usage: /plot delete <name>")
            return
        }

        val resident = Nodes.getResident(player) ?: run {
            Message.error(player, "Could not resolve resident")
            return
        }
        val plot = PlotManager.getPlot(args[1]) ?: run {
            Message.error(player, "Plot not found")
            return
        }
        val town = resident.town
        if (town == null || !plot.townName.equals(town.name, ignoreCase = true) || !canManageTown(resident, town)) {
            Message.error(player, "You can only delete plots of your own town (leader/officer)")
            return
        }

        if (PlotManager.deletePlot(args[1])) {
            Message.print(player, "${ChatColor.GREEN}[Plot] Deleted '${args[1]}'")
        } else {
            Message.error(player, "Plot not found")
        }
    }

    private fun owner(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player ?: run {
            Message.error(sender, "Only players can use this")
            return
        }
        if (args.size < 3) {
            Message.error(player, "Usage: /plot owner <plot> <player>")
            return
        }

        val plot = PlotManager.getPlot(args[1]) ?: run {
            Message.error(player, "Plot not found")
            return
        }
        val resident = Nodes.getResident(player) ?: run {
            Message.error(player, "Could not resolve resident")
            return
        }
        val town = resident.town
        if (town == null || !plot.townName.equals(town.name, ignoreCase = true) || !canManageTown(resident, town)) {
            Message.error(player, "You can only change owner for your own town plots (leader/officer)")
            return
        }

        val targetResident = Nodes.getOrCreateResidentByName(args[2])
        PlotManager.setOwner(plot, targetResident)
        Message.print(player, "${ChatColor.GREEN}[Plot] Owner of '${plot.name}' set to ${targetResident.name}")
    }

    private fun permission(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player ?: run {
            Message.error(sender, "Only players can use this")
            return
        }
        if (args.size < 5) {
            Message.error(player, "Usage: /t plot permissions <plot> <type> <group|player> <allow|deny>")
            return
        }

        val plot = PlotManager.getPlot(args[1]) ?: run {
            Message.error(player, "Plot not found")
            return
        }
        val resident = Nodes.getResident(player) ?: run {
            Message.error(player, "Could not resolve resident")
            return
        }
        val town = resident.town
        if (town == null || !plot.townName.equals(town.name, ignoreCase = true) || !canManageTown(resident, town)) {
            Message.error(player, "You can only change permissions for your own town plots (leader/officer)")
            return
        }

        val typeRaw = args[2].lowercase()
        val type = when (typeRaw) {
            "chests", "containers" -> PlotPermissionType.CONTAINERS
            "destroy", "break" -> PlotPermissionType.DESTROY
            "interact" -> PlotPermissionType.INTERACT
            "use_items", "items" -> PlotPermissionType.ITEMS
            "build" -> PlotPermissionType.BUILD
            else -> null
        }

        if (typeRaw != "all" && type == null) {
            Message.error(player, "Invalid type. Use: all, build, chests, destroy, interact, use_items")
            return
        }

        val group = when (args[3].lowercase()) {
            "owner" -> PlotPermissionGroup.OWNER
            "officer" -> PlotPermissionGroup.OFFICER
            "member" -> PlotPermissionGroup.MEMBER
            "town" -> PlotPermissionGroup.TOWN
            "trusted" -> PlotPermissionGroup.TRUSTED
            "nation" -> PlotPermissionGroup.NATION
            "ally" -> PlotPermissionGroup.ALLY
            "othertown" -> PlotPermissionGroup.OTHERTOWN
            "outsider" -> PlotPermissionGroup.OUTSIDER
            else -> null
        }

        val targetResident = if (group == null) {
            val targetName = args[3]
            if (!stringInputIsValid(targetName, 32)) {
                Message.error(player, "Invalid player name")
                return
            }
            Nodes.getOrCreateResidentByName(targetName)
        } else {
            null
        }

        val allow = when (args[4].lowercase()) {
            "allow", "true" -> true
            "deny", "false" -> false
            else -> {
                Message.error(player, "Invalid mode. Use allow or deny")
                return
            }
        }

        val modeText = if (allow) "allowed" else "denied"
        if (typeRaw == "all") {
            for (kind in enumValues<PlotPermissionType>()) {
                if (group != null) {
                    PlotManager.setPermission(plot, kind, group, allow)
                } else {
                    PlotManager.setResidentPermission(plot, kind, targetResident!!, allow)
                }
            }
            val target = group?.name?.lowercase() ?: targetResident!!.name
            Message.print(player, "${ChatColor.GREEN}[Plot] $target is now $modeText for all permissions on '${plot.name}'")
        } else {
            if (group != null) {
                PlotManager.setPermission(plot, type!!, group, allow)
                Message.print(player, "${ChatColor.GREEN}[Plot] ${group.name.lowercase()} is now $modeText for ${type.name.lowercase()} on '${plot.name}'")
            } else {
                PlotManager.setResidentPermission(plot, type!!, targetResident!!, allow)
                Message.print(player, "${ChatColor.GREEN}[Plot] ${targetResident.name} is now $modeText for ${type.name.lowercase()} on '${plot.name}'")
            }
        }
    }

    private fun list(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /plot list player <name> OR /plot list town")
            return
        }

        when (args[1].lowercase()) {
            "player" -> {
                if (args.size < 3) {
                    Message.error(sender, "Usage: /plot list player <name>")
                    return
                }
                val plots = PlotManager.listPlayerPlots(args[2])
                Message.print(sender, "${ChatColor.AQUA}[Plot] ${plots.size} plots for ${args[2]}:")
                for (plot in plots) {
                    Message.print(sender, "- ${plot.name} (${plot.townName}) @ ${plot.centerString()}")
                }
            }
            "town" -> {
                val player = sender as? Player ?: run {
                    Message.error(sender, "Only players can use this")
                    return
                }
                val town = Nodes.getResident(player)?.town ?: run {
                    Message.error(player, "You must be in a town")
                    return
                }
                val plots = PlotManager.listTownPlots(town.name)
                Message.print(player, "${ChatColor.AQUA}[Plot] ${plots.size} plots in town ${town.name}:")
                for (plot in plots) {
                    Message.print(player, "- ${plot.name} (owner: ${plot.ownerName ?: "none"}) @ ${plot.centerString()}")
                }
            }
            "all" -> {
                val plots = PlotManager.allPlots()
                Message.print(sender, "${ChatColor.AQUA}[Plot] ${plots.size} total plots:")
                for (plot in plots) {
                    Message.print(sender, "- ${plot.name} (${plot.townName}, owner: ${plot.ownerName ?: "none"}) @ ${plot.centerString()}")
                }
            }
            else -> Message.error(sender, "Usage: /plot list player <name> OR /plot list town")
        }
    }

    private fun near(sender: CommandSender) {
        val player = sender as? Player ?: run {
            Message.error(sender, "Only players can use this")
            return
        }

        val plot = PlotManager.getNearbyPlot(player.location, 32)
        if (plot == null) {
            Message.print(player, "${ChatColor.YELLOW}[Plot] No plot near you")
            return
        }

        Message.print(player, "${ChatColor.AQUA}[Plot] Nearby: ${plot.name} (${plot.townName}) owner=${plot.ownerName ?: "none"}")
    }

    private fun info(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /plot info <name>")
            return
        }

        val plot = PlotManager.getPlot(args[1]) ?: run {
            Message.error(sender, "Plot not found")
            return
        }

        val s = plot.selection
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}[Plot] ${plot.name}")
        Message.print(sender, "- Town: ${plot.townName}")
        Message.print(sender, "- Owner: ${plot.ownerName ?: "none"}")
        Message.print(sender, "- World: ${s.world}")
        Message.print(sender, "- Area: (${s.minX}, ${s.minY}, ${s.minZ}) -> (${s.maxX}, ${s.maxY}, ${s.maxZ})")
        for (type in enumValues<PlotPermissionType>()) {
            val groups = plot.permissions[type]?.joinToString(", ") { it.name.lowercase() } ?: "none"
            val residents = plot.residentPermissionNames(type).joinToString(", ").ifBlank { "none" }
            Message.print(sender, "- ${type.name.lowercase()}: groups=$groups; players=$residents")
        }
    }

    private fun show(sender: CommandSender) {
        val player = sender as? Player ?: run {
            Message.error(sender, "Only players can use this")
            return
        }
        PlotManager.showAllPlotsTo(player, 5)
        Message.print(player, "${ChatColor.AQUA}[Plot] Showing all plot borders for 5 seconds")
    }

    private fun canManageTown(resident: Resident, town: Town): Boolean = resident == town.leader || town.officers.contains(resident)
}
