/**
 * /nation (/n) command
 */

package phonon.nodes.commands

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.ErrorNationExists
import phonon.nodes.constants.ErrorPlayerHasNation
import phonon.nodes.constants.ErrorTownHasNation
import phonon.nodes.objects.Nation
import phonon.nodes.utils.string.filterByStart
import phonon.nodes.utils.string.filterNation
import phonon.nodes.utils.string.filterNationTown
import phonon.nodes.utils.string.filterTown
import phonon.nodes.utils.stringInputIsValid
import java.util.concurrent.TimeUnit

// list of all subcommands, used for onTabComplete
private val subcommands: List<String> = listOf(
    "help",
    "create",
    "new",
    "delete",
    "disband",
    "leave",
    "capital",
    "invite",
    "accept",
    "deny",
    "reject",
    "list",
    "color",
    "rename",
    "online",
    "onlinetop",
    "info",
    "spawn",
    "announce",
    "annonce",
)

public class NationCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        val player = if (sender is Player) sender else null

        // no args, print current nation info
        if (args.size == 0) {
            if (player != null) {
                val resident = Nodes.getResident(player)
                if (resident != null && resident.nation != null) {
                    resident.nation!!.printInfo(player)
                }
                Message.print(player, "Use \"/nation help\" to view commands")
            }
            return true
        }

        // parse subcommand
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "create", "new" -> createNation(player, args)
            "delete",
            "disband",
            -> deleteNation(player)
            "leave" -> leaveNation(player)
            "capital" -> setCapital(player, args)
            "invite" -> inviteToNation(player, args)
            "accept" -> accept(player)
            "deny", "reject" -> deny(player)
            "list" -> listNations(player)
            "color" -> setColor(player, args)
            "rename" -> renameNation(player, args)
            "online" -> getOnline(player, args)
            "onlinetop" -> getOnlineTop(player)
            "info" -> getInfo(player, args)
            "spawn" -> goToNationTownSpawn(player, args)
            "announce",
            "annonce",
            -> announceNation(player, args)
            else -> {
                Message.error(player, "Invalid command, use /nation help")
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        val player: Player = if (sender is Player) {
            sender
        } else {
            return listOf()
        }

        if (args.size == 1) {
            return filterByStart(subcommands, args[0])
        } else if (args.size > 1) {
            when (args[0].lowercase()) {
                "invite" -> {
                    if (args.size == 2) {
                        return filterTown(args[1])
                    }
                }
                "list", "online", "info" -> {
                    if (args.size == 2) {
                        return filterNation(args[1])
                    }
                }
                "capital", "spawn" -> {
                    if (args.size == 2) {
                        val nation = Nodes.getResident(player)?.nation
                        if (nation !== null) {
                            return filterNationTown(nation, args[1])
                        }
                    }
                }
            }
        }

        return listOf()
    }

    // =========================================================================

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Nation commands:")
        Message.print(sender, "/nation create [name]${ChatColor.WHITE}: Create a nation (town leader only)")
        Message.print(sender, "/nation delete${ChatColor.WHITE}: Delete your nation")
        Message.print(sender, "/nation leave${ChatColor.WHITE}: Leave your nation")
        Message.print(sender, "/nation invite [town]${ChatColor.WHITE}: Invite a town to your nation")
        Message.print(sender, "/nation accept${ChatColor.WHITE}: Accept a nation invite")
        Message.print(sender, "/nation deny${ChatColor.WHITE}: Deny a nation invite")
        Message.print(sender, "/nation capital [town]${ChatColor.WHITE}: Set the nation capital")
        Message.print(sender, "/nation list${ChatColor.WHITE}: List all nations")
        Message.print(sender, "/nation info [nation]${ChatColor.WHITE}: View nation details")
        Message.print(sender, "/nation online [nation]${ChatColor.WHITE}: View online players")
        Message.print(sender, "/nation color [r] [g] [b]${ChatColor.WHITE}: Set nation color on map")
        Message.print(sender, "/nation rename [name]${ChatColor.WHITE}: Rename your nation")
        Message.print(sender, "/nation spawn [town]${ChatColor.WHITE}: Teleport to a town in your nation")
        Message.print(sender, "/nation announce <text>${ChatColor.WHITE}: Broadcast title to nation members")
    }

    // =========================================================================
    // /nation create [name]
    // =========================================================================

    private fun createNation(player: Player?, args: Array<String>) {
        if (player == null) return

        if (args.size < 2) {
            Message.print(player, "Usage: ${ChatColor.WHITE}/nation create [name]")
            return
        }

        val resident = Nodes.getResident(player) ?: return

        val town = resident.town
        if (town == null) {
            Message.error(player, "You must be a town leader to create a nation")
            return
        }

        if (resident !== town.leader) {
            Message.error(player, "Only the town leader can create a nation")
            return
        }

        if (town.nation != null) {
            Message.error(player, "Your town already belongs to a nation")
            return
        }

        if (Nodes.war.enabled == true) {
            Message.error(player, "Cannot create a nation during war")
            return
        }

        val name = args[1]
        if (!stringInputIsValid(name)) {
            Message.error(player, "Invalid nation name")
            return
        }

        if (Nodes.nations.containsKey(name)) {
            Message.error(player, "A nation named \"$name\" already exists")
            return
        }

        val result = Nodes.createNation(name, town, resident)
        result.fold(
            onSuccess = {
                Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}${player.name} has founded the nation \"${name}\"!")
            },
            onFailure = { err ->
                when (err) {
                    ErrorNationExists -> Message.error(player, "A nation named \"$name\" already exists")
                    ErrorTownHasNation -> Message.error(player, "Your town already belongs to a nation")
                    ErrorPlayerHasNation -> Message.error(player, "You are already part of a nation")
                    else -> Message.error(player, err.message ?: "Failed to create nation")
                }
            },
        )
    }

    // =========================================================================
    // /nation announce
    // =========================================================================

    private fun announceNation(player: Player?, args: Array<String>) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return
        val town = resident.town ?: run {
            Message.error(player, "You are not in a town")
            return
        }
        val nation = resident.nation ?: run {
            Message.error(player, "You are not in a nation")
            return
        }

        if (town !== nation.capital) {
            Message.error(player, "Only the capital town can send nation announcements")
            return
        }
        if (resident !== town.leader && !town.generals.contains(resident)) {
            Message.error(player, "Only capital leader or generals can announce")
            return
        }

        if (args.size < 2) {
            Message.error(player, "Usage: /n announce <text>")
            return
        }

        val text = args.copyOfRange(1, args.size).joinToString(" ").trim()
        if (text.isEmpty()) {
            Message.error(player, "Announcement text cannot be empty")
            return
        }

        for (member in nation.playersOnline) {
            Message.title(member, "${ChatColor.DARK_GREEN}${nation.name}", "${ChatColor.GREEN}$text", 5, 60, 10)
        }
    }

    // =========================================================================
    // /nation delete
    // =========================================================================

    private fun deleteNation(player: Player?) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        val nation = resident.nation
        if (nation == null) {
            Message.error(player, "You do not belong to a nation")
            return
        }

        val leader = nation.capital.leader
        if (resident !== leader) {
            Message.error(player, "You are not the nation leader")
            return
        }

        if (Nodes.war.enabled == true) {
            Message.error(player, "Cannot delete your nation during war")
            return
        }

        Nodes.destroyNation(nation)
        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nation ${nation.name} has been destroyed")
    }

    // =========================================================================
    // /nation leave
    // =========================================================================

    private fun leaveNation(player: Player?) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        val town = resident.town
        if (town == null) {
            Message.error(player, "You do not belong to a town")
            return
        }

        val nation = town.nation
        if (nation == null) {
            Message.error(player, "You do not belong to a nation")
            return
        }

        if (town === nation.capital) {
            Message.error(player, "The nation's capital cannot leave (use /n delete)")
            return
        }

        val leader = town.leader
        if (resident !== leader) {
            Message.error(player, "You are not the town leader")
            return
        }

        if (Nodes.war.enabled == true && Config.canLeaveNationDuringWar == false) {
            Message.error(player, "Cannot leave your nation during war")
            return
        }

        Nodes.removeTownFromNation(nation, town)

        for (r in town.residents) {
            val p = r.player()
            if (p != null) {
                Message.print(p, "${ChatColor.BOLD}${ChatColor.DARK_RED}Your town has left nation ${ChatColor.WHITE}${nation.name}")
            }
        }
    }

    // =========================================================================
    // /nation capital [town]
    // =========================================================================

    private fun setCapital(player: Player?, args: Array<String>) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        val nation = resident.nation
        if (nation == null) {
            Message.error(player, "You do not belong to a nation")
            return
        }

        val leader = nation.capital.leader
        if (resident !== leader) {
            Message.error(player, "Only nation leaders can change the capital town")
            return
        }

        if (args.size < 2) {
            Message.print(player, "Usage: ${ChatColor.WHITE}/nation capital [town]")
            return
        }

        val newCapital = Nodes.getTownFromName(args[1])
        if (newCapital === null) {
            Message.error(player, "That town does not exist")
            return
        }
        if (newCapital.nation !== nation) {
            Message.error(player, "That town does not belong to this nation")
            return
        }
        if (newCapital === nation.capital) {
            Message.error(player, "This town is already the nation capital")
            return
        }

        Nodes.setNationCapital(nation, newCapital)
        Message.broadcast("${ChatColor.BOLD}${newCapital.name} is now the capital of ${nation.name}")
    }

    // =========================================================================
    // /nation invite [town]
    // =========================================================================

    private fun inviteToNation(player: Player?, args: Array<String>) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        val nation = resident.nation
        if (nation == null) {
            Message.error(player, "You do not belong to a nation")
            return
        }

        val leader = nation.capital.leader
        if (resident !== leader) {
            Message.error(player, "Only nation leaders can invite new towns")
            return
        }

        if (args.size < 2) {
            Message.print(player, "Usage: ${ChatColor.WHITE}/nation invite [town]")
            return
        }

        val inviteeTown = Nodes.getTownFromName(args[1])
        if (inviteeTown == null) {
            Message.error(player, "That town does not exist")
            return
        }
        if (inviteeTown.nation != null) {
            Message.error(player, "That town already belongs to another nation")
            return
        }

        val inviteeResident = inviteeTown.leader
        if (inviteeResident == null) {
            Message.error(player, "That town has no leader (?)")
            return
        }
        val invitee: Player? = Bukkit.getPlayer(inviteeResident.name)
        if (invitee == null) {
            Message.error(player, "That town's leader is not online")
            return
        }

        if (inviteeResident.invitingNation != null) {
            Message.error(player, "That town already has a pending nation invite")
            return
        }

        Message.print(player, "${inviteeTown.name} has been invited to your nation.")
        Message.print(
            invitee,
            "Your town has been invited to join the nation of ${nation.name} by ${player.name}. " +
                "\nType \"/n accept\" to agree or \"/n reject\" to refuse the offer.",
        )
        inviteeResident.invitingNation = nation
        inviteeResident.invitingTown = inviteeTown
        inviteeResident.invitingPlayer = player
        inviteeResident.inviteThread = Bukkit.getAsyncScheduler().runDelayed(
            Nodes.plugin!!,
            { _ ->
                player.scheduler.run(
                    Nodes.plugin!!,
                    { _ ->
                        if (inviteeResident.invitingPlayer == player) {
                            Message.print(player, "${invitee.name} didn't respond to your nation invitation!")
                            inviteeResident.invitingNation = null
                            inviteeResident.invitingTown = null
                            inviteeResident.invitingPlayer = null
                            inviteeResident.inviteThread = null
                        }
                    },
                    null,
                )
            },
            60000,
            TimeUnit.MILLISECONDS,
        )
    }

    // =========================================================================
    // /nation accept
    // =========================================================================

    private fun accept(player: Player?) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        if (resident.town != resident.invitingTown) {
            Message.error(player, "Invite invalid")
            return
        }

        if (resident.invitingNation == null) {
            Message.error(player, "You have not been invited to any nation")
            return
        }

        Message.print(player, "${resident.town?.name} is now a jurisdiction of ${resident.invitingNation?.name}!")
        Message.print(resident.invitingPlayer, "${resident.town?.name} has accepted your authority!")

        Nodes.addTownToNation(resident.invitingNation!!, resident.town!!)
        resident.invitingNation = null
        resident.invitingTown = null
        resident.invitingPlayer = null
        resident.inviteThread = null
    }

    // =========================================================================
    // /nation deny | reject
    // =========================================================================

    private fun deny(player: Player?) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        if (resident.invitingNation == null) {
            Message.error(player, "You have not been invited to any nation")
            return
        }

        Message.print(player, "You have rejected the invitation to ${resident.invitingNation?.name}!")
        Message.print(resident.invitingPlayer, "${resident.town?.name} has rejected your authority!")

        resident.invitingNation = null
        resident.invitingTown = null
        resident.invitingPlayer = null
        resident.inviteThread = null
    }

    // =========================================================================
    // /nation list
    // =========================================================================

    private fun listNations(player: Player?) {
        Message.print(player, "${ChatColor.BOLD}Nation - Population - Towns")
        val nationsList = ArrayList(Nodes.nations.values)
        nationsList.sortByDescending { it.residents.size }
        for (n in nationsList) {
            val townsList = ArrayList(n.towns)
            townsList.sortByDescending { it.residents.size }
            var towns = ""
            for ((i, t) in townsList.withIndex()) {
                towns += t.name
                towns += " (${t.residents.size})"
                if (i < n.towns.size - 1) {
                    towns += ", "
                }
            }
            Message.print(player, "${n.name} ${ChatColor.WHITE}- ${n.residents.size} - $towns")
        }
    }

    // =========================================================================
    // /nation color [r] [g] [b]
    // =========================================================================

    private fun setColor(player: Player?, args: Array<String>) {
        if (player == null) return

        if (args.size < 4) {
            Message.print(player, "Usage: ${ChatColor.WHITE}/nation color [r] [g] [b]")
            return
        }

        val resident = Nodes.getResident(player) ?: return

        val nation = resident.nation ?: return

        val leader = nation.capital.leader
        if (resident !== leader) {
            Message.error(player, "Only nation leaders can do this")
            return
        }

        try {
            val r = args[1].toInt().coerceIn(0, 255)
            val g = args[2].toInt().coerceIn(0, 255)
            val b = args[3].toInt().coerceIn(0, 255)
            Nodes.setNationColor(nation, r, g, b)
            Message.print(player, "Nation color set: ${ChatColor.WHITE}$r $g $b")
        } catch (e: NumberFormatException) {
            Message.error(player, "Invalid color (must be [r] [g] [b] in range 0-255)")
        }
    }

    // =========================================================================
    // /nation rename [name]
    // =========================================================================

    private fun renameNation(player: Player?, args: Array<String>) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        if (args.size == 1) {
            Message.print(player, "Usage: /n rename [new_name]")
            return
        }

        val nation = resident.nation
        if (nation == null) {
            Message.error(player, "You do not belong to a nation")
            return
        }

        if (resident != nation.capital.leader) {
            Message.error(player, "Only nation leaders can do this")
            return
        }

        val name = args[1]
        if (!stringInputIsValid(name)) {
            Message.error(player, "Invalid nation name")
            return
        }

        if (nation.name.lowercase() == name.lowercase()) {
            Message.error(player, "Your nation is already named ${nation.name}")
            return
        }

        if (Nodes.nations.containsKey(name)) {
            Message.error(player, "There is already a nation with this name")
            return
        }

        Nodes.renameNation(nation, name)
        Message.print(player, "Nation renamed to ${nation.name}!")
    }

    // =========================================================================
    // /nation online [nation]
    // =========================================================================

    private fun getOnline(player: Player?, args: Array<String>) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        val nation: Nation? = when {
            args.size == 1 -> {
                resident.nation ?: run {
                    Message.error(player, "You do not belong to a nation")
                    return
                }
            }
            args.size == 2 -> {
                Nodes.getNationFromName(args[1]) ?: run {
                    Message.error(player, "That nation does not exist")
                    return
                }
            }
            else -> {
                Message.error(player, "Usage: /nation online [nation]")
                return
            }
        }

        if (nation == null) return

        val numPlayersOnline = nation.playersOnline.size
        val playersOnline = nation.playersOnline.joinToString(", ") { it.name }
        Message.print(player, "Players online in nation ${nation.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
    }

    private fun getOnlineTop(player: Player?) {
        if (player == null) return

        val ranking = Nodes.nations.values
            .sortedWith(
                compareByDescending<Nation> { it.playersOnline.size }
                    .thenBy { it.name.lowercase() },
            )
            .take(10)

        if (ranking.isEmpty()) {
            Message.print(player, "No nations found")
            return
        }

        Message.print(player, "${ChatColor.GOLD}${ChatColor.BOLD}Nation Online Top")
        ranking.forEachIndexed { index, nation ->
            Message.print(
                player,
                "${ChatColor.GRAY}${index + 1}. ${ChatColor.WHITE}${nation.name} " +
                    "${ChatColor.GREEN}[${nation.playersOnline.size}]",
            )
        }
    }

    // =========================================================================
    // /nation info [nation]
    // =========================================================================

    private fun getInfo(player: Player?, args: Array<String>) {
        if (player == null) return

        val resident = Nodes.getResident(player) ?: return

        val nation: Nation? = when {
            args.size == 1 -> {
                resident.nation ?: run {
                    Message.error(player, "You do not belong to a nation")
                    return
                }
            }
            args.size == 2 -> {
                Nodes.getNationFromName(args[1]) ?: run {
                    Message.error(player, "That nation does not exist")
                    return
                }
            }
            else -> {
                Message.error(player, "Usage: /nation info [nation]")
                return
            }
        }

        nation?.printInfo(player)
    }

    // =========================================================================
    // /nation spawn [town]
    // =========================================================================

    private fun goToNationTownSpawn(player: Player?, args: Array<String>) {
        if (player == null) return

        if (!Config.allowNationTownSpawn) {
            Message.error(player, "Server has disabled teleporting to other towns in your nation")
            return
        }

        if (args.size < 2) {
            Message.error(player, "Usage: /nation spawn [town]")
            return
        }

        val resident = Nodes.getResident(player) ?: return

        val town = resident.town
        if (town === null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        val nation = town.nation
        if (nation === null) {
            Message.error(player, "You are not a member of a nation")
            return
        }

        val destinationName = args[1]
        val destinationTown = Nodes.getTownFromName(destinationName)
        if (destinationTown === null) {
            Message.error(player, "Destination town does not exist: $destinationName")
            return
        }

        if (destinationTown === town) {
            Message.error(player, "Destination town is your town, use /town spawn")
            return
        }

        if (nation !== destinationTown.nation) {
            Message.error(player, "Destination town is not in the same nation: $destinationName")
            return
        }

        if (resident.teleportThread !== null) {
            Message.error(player, "You are already trying to teleport")
            return
        }

        // pay item cost to teleport
        if (Config.nationTownTeleportCost.size > 0) {
            val inventory = player.getInventory()
            for ((material, amount) in Config.nationTownTeleportCost) {
                val items = ItemStack(material)
                if (!inventory.containsAtLeast(items, amount)) {
                    Message.error(player, "You do not have required payment to teleport: ${Config.nationTownTeleportCostString}")
                    return
                }
            }
            for ((material, amount) in Config.nationTownTeleportCost) {
                inventory.removeItem(ItemStack(material, amount))
            }
        }

        val destination = destinationTown.spawnpoint
        val teleportTimerTicks: Long = Math.max(0, Config.townSpawnTime * 20).toLong()

        resident.isTeleportingToNationTown = true
        resident.teleportThread = Bukkit.getAsyncScheduler().runDelayed(
            Nodes.plugin!!,
            { _ ->
                player.scheduler.run(
                    Nodes.plugin!!,
                    { _ ->
                        player.teleportAsync(destination).whenComplete { success, throwable ->
                            player.scheduler.run(
                                Nodes.plugin!!,
                                { _ ->
                                    resident.teleportThread = null
                                    resident.isTeleportingToNationTown = false
                                    if (throwable != null || success != true) {
                                        Message.error(player, "Teleport failed")
                                    }
                                },
                                null,
                            )
                        }
                    },
                    null,
                )
            },
            teleportTimerTicks * 50,
            TimeUnit.MILLISECONDS,
        )

        if (teleportTimerTicks > 0) {
            Message.print(player, "Teleporting to $destinationName in ${Config.townSpawnTime} seconds. Don't move...")
        }
    }
}
