/**
 * Admin commands to manage world
 * - modify towns, nations
 * - war enable/disable
 *
 *    /nodesadmin command ...
 *    /nda command
 */

package phonon.nodes.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minecraft.commands.arguments.EntityArgument.player
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.Nodes.addPortToGroup
import phonon.nodes.Nodes.removePortFromGroup
import phonon.nodes.chat.ChatMode
import phonon.nodes.constants.ErrorTerritoryHasClaim
import phonon.nodes.constants.ErrorTerritoryNotConnected
import phonon.nodes.constants.ErrorTooManyClaims
import phonon.nodes.market.MarketManager
import phonon.nodes.objects.Coord
import phonon.nodes.objects.OutpostApplication
import phonon.nodes.objects.PortApplication
import phonon.nodes.objects.PortTier
import phonon.nodes.objects.Resident
import phonon.nodes.objects.ResourceAttributeAttackerTimeMultiplier
import phonon.nodes.objects.ResourceNode
import phonon.nodes.objects.SocialSpy
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.utils.leaderlaunch.LeaderLaunchManager
import phonon.nodes.utils.sanitizeString
import phonon.nodes.utils.string.filterByStart
import phonon.nodes.utils.string.filterNation
import phonon.nodes.utils.string.filterNationTown
import phonon.nodes.utils.string.filterPlayer
import phonon.nodes.utils.string.filterPort
import phonon.nodes.utils.string.filterPortGroup
import phonon.nodes.utils.string.filterResident
import phonon.nodes.utils.string.filterTown
import phonon.nodes.utils.string.filterTownOrNation
import phonon.nodes.utils.string.filterTownResident
import phonon.nodes.utils.stringInputIsValid
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Treaty
import phonon.nodes.war.TreatyTermItems
import phonon.nodes.war.TreatyTermOccupation
import phonon.nodes.war.WarAccessEntry
import phonon.nodes.war.WarAccessListManager
import phonon.nodes.war.WarAccessUpdateResult
import phonon.nodes.warzone.WarzoneGui
import phonon.nodes.warzone.WarzoneManager
import java.nio.file.Files
import kotlin.getOrElse
import kotlin.math.roundToInt
import phonon.nodes.MiningOutminerManager as ActiveMiningOutminerManager

// list of all subcommands, used for onTabComplete
private val SUBCOMMANDS: List<String> = listOf(
    "help",
    "integrations",
    "reload",
    "war",
    "wardeclaration",
    "peaceall",
    "leaderlaunch",
    "port",
    "outpost",
    "portgroup",
    "resident",
    "town",
    "disable",
    "territory",
    "resource",
    "warzone",
    "nation",
    "enemy",
    "enemy",
    "ban",
    "unban",
    "checkban",
    "peace",
    "ally",
    "allyremove",
    "truce",
    "truceremove",
    "treaty",
    "socialspy",
    "save",
    "rallycap",
    "load",
    "runincome",
    "clearincome",
    "holograms",
    "playersonline",
    "miningoutpost",
    "miningoutminer",
    "attacktimedebug",
    "debug",
)

private val MINING_OUTMINER_SUBCOMMANDS: List<String> = listOf(
    "place",
    "remove",
    "removeindex",
    "list",
    "resources",
    "setresource",
    "tp",
    "regenholos",
)

private val RELOAD_SUBCOMMANDS: List<String> = listOf(
    "config",
    "managers",
    "resources",
    "territory",
    "tab",
)

private val DISABLE_SUBCOMMANDS: List<String> = listOf(
    "market",
    "miningtower",
    "miningoutminer",
)

private val HOLOGRAM_SUBCOMMANDS: List<String> = listOf(
    "stats",
    "refresh",
    "respawn",
    "cleanup",
)

// war subcommands
private val WAR_SUBCOMMANDS: List<String> = listOf(
    "enable",
    "skirmish",
    "disable",
    "whitelist",
    "blacklist",
)

private val LEADERLAUNCH_SUBCOMMANDS: List<String> = listOf(
    "add",
    "remove",
    "on",
    "off",
    "status",
)

// port subcommands
private val PORT_SUBCOMMANDS: List<String> = listOf(
    "list",
    "create",
    "delete",
    "addgroup",
    "removegroup",
    "accept",
    "deny",
    "tier",
    "tpn",
)

private val OUTPOST_SUBCOMMANDS: List<String> = listOf(
    "list",
    "accept",
    "deny",
    "tier",
    "tpn",
)

// portgroup subcommands
private val PORTGROUP_SUBCOMMANDS: List<String> = listOf(
    "create",
    "delete",
)

// resident/player subcommands
private val RESIDENT_SUBCOMMANDS: List<String> = listOf(
    "towncooldown",
)

// town subcommands
private val TOWN_SUBCOMMANDS: List<String> = listOf(
    "create",
    "delete",
    "addplayer",
    "addallplayer",
    "removeplayer",
    "addterritory",
    "removeterritory",
    "captureterritory",
    "releaseterritory",
    "addofficer",
    "removeofficer",
    "addgeneral",
    "removegeneral",
    "leader",
    "removeleader",
    "claimsbonus",
    "claimsannex",
    "claimspenalty",
    "open",
    "income",
    "incomeadd",
    "incomeremove",
    "setspawn",
    "claim",
    "spawn",
    "sethome",
    "sethomecooldown",
    "addoutpost",
    "removeoutpost",
    "addoutpostcredits",
    "color",
    "rename",
    "defaulttownspawns",
    "fixspawns",
)

// territory subcommands
private val TERRITORY_SUBCOMMANDS: List<String> = listOf(
    "tp",
    "addresource",
    "removeresource",
    "addtype",
    "removetype",
    "addcore",
    "removecore",
)

// nation subcommands
private val NATION_SUBCOMMANDS: List<String> = listOf(
    "create",
    "delete",
    "addtown",
    "removetown",
    "capital",
    "color",
    "rename",
)

// debug subcommands
private val DEBUG_SUBCOMMANDS: List<String> = listOf(
    "resource",
    "chunk",
    "territory",
    "resident",
    "town",
    "nation",
)

public class NodesAdminCommand :
    CommandExecutor,
    TabCompleter {
    private val pendingPortTierSelection: HashSet<Int> = hashSetOf()
    private val pendingOutpostTierSelection: HashSet<Int> = hashSetOf()

    private fun tr(sender: CommandSender, text: String): String = Message.translated(sender, text)

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        // no args, print plugin info
        if (args.size == 0) {
            Message.print(sender, "${ChatColor.BOLD}Nodes ${Nodes.version}")
            printHelp(sender)
            return true
        }

        val isAdmin = sender.hasPermission("nodes.admin")
        val isMod = sender.hasPermission("nodes.mod")
        val isHelper = sender.hasPermission("nodes.helper")

        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "integrations" -> printIntegrations(sender)
            "reload" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                reload(sender, args)
            }
            "war" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                war(sender, args)
            }

            "disable" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageDisable(sender, args)
            }

            "wardeclaration", "wardeclartion" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                warDeclaration(sender, args)
            }
            "peaceall" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                peaceAll(sender)
            }
            "leaderlaunch" -> manageLeaderLaunch(sender, args, isAdmin, isMod)
            "resident" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageResident(sender, args)
            }
            "town" -> manageTown(sender, args, isAdmin, isMod)
            "territory" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageTerritory(sender, args)
            }
            "resource", "ressource" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageResourceShortcut(sender, args)
            }
            "warzone" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageWarzone(sender, args)
            }
            "port" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                managePort(sender, args)
            }
            "outpost" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageOutpost(sender, args)
            }
            "portgroup" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                managePortGroup(sender, args)
            }
            "nation" -> manageNation(sender, args, isAdmin, isMod, isHelper)
            "enemy" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                setEnemy(sender, args)
            }
            "peace" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                setPeace(sender, args)
            }
            "ally" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                setAlly(sender, args)
            }
            "ban" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                banTownOrNation(sender, args)
            }
            "unban" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                unbanPlayerOrTown(sender, args)
            }
            "checkban" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                checkBanTown(sender, args)
            }
            "allyremove" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                removeAlly(sender, args)
            }
            "rallycap" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                rallyCapToggle(sender, args)
            }
            "truce" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                setTruce(sender, args)
            }
            "truceremove" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                removeTruce(sender, args)
            }
            "treaty" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageTreaty(sender, args)
            }
            "socialspy" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageSocialSpy(sender, args)
            }
            "save" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                saveWorld(sender, args)
            }
            "load" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                loadWorld(sender)
            }
            "runincome" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                Nodes.runIncome()
            }
            "clearincome" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                clearIncome(sender, args)
            }
            "holograms" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageHolograms(sender, args)
            }
            "playersonline" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                Nodes.refreshPlayersOnline()
            }
            "miningoutpost",
            "miningoutminer",
            -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                manageMiningOutminer(sender, args)
            }
            "attacktimedebug" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                attackTimeDebug(sender, args)
            }
            "debug" -> {
                if (!requireAdmin(sender, isAdmin)) {
                    return true
                }
                debugger(sender, args)
            }
            else -> {
                Message.error(sender, "Invalid command, use \"/nodesadmin help\"")
            }
        }

        return true
    }

    private fun printIntegrations(sender: CommandSender) {
        Nodes.refreshIntegrationHooks()
        Message.print(sender, "${ChatColor.BOLD}Nodes integrations")
        for (status in Nodes.getIntegrationStatuses()) {
            val presentColor = if (status.present) ChatColor.GREEN else ChatColor.RED
            val presentText = if (status.present) "found" else "missing"
            val enabledText = if (!status.present) {
                ""
            } else if (status.enabled) {
                "enabled"
            } else {
                "disabled"
            }
            val versionText = if (status.present) (status.version ?: "unknown") else ""

            val suffix = listOfNotNull(
                enabledText.takeIf { it.isNotBlank() },
                versionText.takeIf { it.isNotBlank() },
            ).joinToString(" | ")

            if (suffix.isBlank()) {
                Message.raw(sender, "§7- ${status.name}: $presentColor$presentText")
            } else {
                Message.raw(sender, "§7- ${status.name}: $presentColor$presentText §7($suffix)")
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        // match subcommand
        if (args.size == 1) {
            return filterByStart(SUBCOMMANDS, args[0])
        }
        // match each subcommand format
        else if (args.size > 1) {
            // handle specific subcommands
            when (args[0].lowercase()) {
                "reload" -> {
                    if (args.size == 2) {
                        return filterByStart(RELOAD_SUBCOMMANDS, args[1])
                    }
                }

                // /nodesadmin war enable/disable
                "war" -> {
                    if (args.size == 2) {
                        return filterByStart(WAR_SUBCOMMANDS, args[1])
                    } else if (args.size >= 3) {
                        when (args[1].lowercase()) {
                            "enable",
                            "skirmish",
                            -> {
                                val suggestions = listOf("1h", "2h", "3h", "30m", "0m")
                                return filterByStart(suggestions, args[args.size - 1])
                            }
                        }
                    }
                }
                "wardeclaration" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("on", "off"), args[1])
                    }
                }

                "disable" -> {
                    if (args.size == 2) {
                        return filterByStart(DISABLE_SUBCOMMANDS, args[1])
                    }
                    if (args.size == 3) {
                        return filterByStart(listOf("on", "off"), args[2])
                    }
                }

                "leaderlaunch" -> {
                    if (args.size == 2) {
                        return filterByStart(LEADERLAUNCH_SUBCOMMANDS, args[1])
                    }
                    when (args[1].lowercase()) {
                        "add",
                        "remove",
                        -> {
                            if (args.size >= 3) {
                                return filterPlayer(args[args.size - 1])
                            }
                        }
                    }
                }
                "socialspy" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("local", "town", "nation", "ally", "all"), args[1])
                    }
                }
                "holograms" -> {
                    if (args.size == 2) {
                        return filterByStart(HOLOGRAM_SUBCOMMANDS, args[1])
                    }
                }

                // /nodesadmin port [subcommand]
                "port" -> {
                    if (args.size == 2) {
                        return filterByStart(PORT_SUBCOMMANDS, args[1])
                    }
                    // handle subcommand
                    else if (args.size == 3) {
                        when (args[1].lowercase()) {
                            // /nodesadmin port [subcommand] [port] ...
                            "delete",
                            "addgroup",
                            "removegroup",
                            -> {
                                return filterPort(args[2])
                            }
                            "accept",
                            "deny",
                            "tpn",
                            -> {
                                return filterByStart(Nodes.portApplications.keys.map { it.toString() }, args[2])
                            }
                            "tier" -> {
                                return filterByStart(
                                    Nodes.portApplications.keys.map { it.toString() } + listOf("set"),
                                    args[2],
                                )
                            }
                        }
                    }

                    // return x,z for /nodesadmin port create [town]
                    else if (args[1].lowercase() == "create") {
                        when (args.size) {
                            4 -> {
                                return listOf(Bukkit.getPlayer(sender.name)?.x?.roundToInt().toString())
                            }
                            5 -> {
                                return listOf(Bukkit.getPlayer(sender.name)?.z?.roundToInt().toString())
                            }
                        }
                    }
                    // return groups for /nodes port addgroup/removegroup
                    else if (args.size == 4) {
                        when (args[1].lowercase()) {
                            "addgroup",
                            "removegroup",
                            -> {
                                return filterPortGroup(args[3])
                            }
                            "tier" -> {
                                if (args[2].equals("set", ignoreCase = true)) {
                                    return filterPort(args[3])
                                }
                                return filterByStart(listOf("tier1", "tier2", "tier3"), args[3])
                            }
                        }
                    } else if (args.size == 5 && args[1].lowercase() == "tier" && args[2].equals("set", ignoreCase = true)) {
                        return filterByStart(listOf("tier1", "tier2", "tier3"), args[4])
                    }
                }

                // /nodesadmin outpost [subcommand]
                "outpost" -> {
                    if (args.size == 2) {
                        return filterByStart(OUTPOST_SUBCOMMANDS, args[1])
                    } else if (args.size == 3) {
                        when (args[1].lowercase()) {
                            "accept",
                            "deny",
                            "tier",
                            "tpn",
                            -> return filterByStart(Nodes.outpostApplications.keys.map { it.toString() }, args[2])
                        }
                    } else if (args.size == 4 && args[1].lowercase() == "tier") {
                        return filterByStart(listOf("tier1", "tier2", "tier3"), args[3])
                    }
                }

                "rallycap" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("enable", "disable", "size"), args[1])
                    }
                }

                "miningoutpost",
                "miningoutminer",
                -> {
                    if (args.size == 2) {
                        return filterByStart(MINING_OUTMINER_SUBCOMMANDS, args[1])
                    } else if (args.size == 3 && args[1].equals("setresource", ignoreCase = true)) {
                        return Material.entries.map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
                    } else if (args.size == 4 && args[1].equals("setresource", ignoreCase = true)) {
                        return filterByStart(listOf("24", "32", "64", "0"), args[3])
                    }
                }

                "attacktimedebug" -> {
                    // /nodesadmin attacktimedebug [chunkX] [chunkZ]
                    if (args.size == 2 || args.size == 3) {
                        return listOf()
                    }
                }

                // /nodesadmin portgroup [subcommand
                "portgroup" -> {
                    if (args.size == 2) {
                        return filterByStart(PORTGROUP_SUBCOMMANDS, args[1])
                    }
                    // handle subcommands
                    when (args[1].lowercase()) {
                        "delete" -> {
                            return filterPortGroup(args[2])
                        }
                    }
                }

                // /nodesadmin resident [subcommand]
                "resident" -> {
                    if (args.size == 2) {
                        return filterByStart(RESIDENT_SUBCOMMANDS, args[1])
                    } else if (args.size > 2) {
                        when (args[1].lowercase()) {
                            // /nodesadmin resident [subcommand] [player]
                            "towncooldown" -> {
                                if (args.size == 3) {
                                    return filterResident(args[2])
                                }
                            }
                        }
                    }
                }

                // /nodesadmin town [subcommand]
                "town" -> {
                    if (args.size == 2) {
                        return filterByStart(TOWN_SUBCOMMANDS, args[1])
                    }
                    // handle subcommand
                    else if (args.size > 2) {
                        when (args[1].lowercase()) {
                            // /nodesadmin town [subcommand] [town] ...
                            "delete",
                            "claimsbonus",
                            "claimsannex",
                            "claimspenalty",
                            "open",
                            "income",
                            "incomeadd",
                            "defaulttownspawns",
                            "setspawn",
                            "spawn",
                            "sethomecooldown",
                            "removeleader",
                            "addoutpostcredits",
                            "color",
                            "rename",
                            -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                }
                            }

                            // /nodesadmin town subcommand [town] [name1] [name2] ...
                            "addplayer",
                            "addallplayer",
                            "removeplayer",
                            -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else {
                                    return filterPlayer(args[args.size - 1])
                                }
                            }

                            // /nodesadmin town subcommand [town] [resident]
                            "leader" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size == 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town !== null) {
                                        return filterTownResident(town, args[3])
                                    }
                                }
                            }

                            // /nodesadmin town subcommand [town] [resident1] [resident2] ...
                            "addofficer",
                            "removeofficer",
                            "addgeneral",
                            "removegeneral",
                            -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size >= 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town !== null) {
                                        return filterTownResident(town, args[args.size - 1])
                                    }
                                }
                            }

                            // /nodesadmin town [subcommand] [town] [id1] [id2] ...
                            "addterritory" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size >= 4) {
                                    return completeTerritoryIds(
                                        args[args.size - 1],
                                        Nodes.territories.values.filter { it.town == null },
                                    )
                                }
                            }

                            "removeterritory" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size >= 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town != null) {
                                        return completeTerritoryIds(
                                            args[args.size - 1],
                                            town.territories
                                                .asSequence()
                                                .filter { it != town.home }
                                                .mapNotNull { Nodes.territories[it] }
                                                .toList(),
                                        )
                                    }
                                }
                            }

                            "captureterritory" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size >= 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town != null) {
                                        return completeTerritoryIds(
                                            args[args.size - 1],
                                            Nodes.territories.values.filter { it.town !== town },
                                        )
                                    }
                                }
                            }

                            // /nodesadmin town releaseterritory [id1] [id2] ...
                            "releaseterritory" -> {
                                if (args.size >= 3) {
                                    return completeTerritoryIds(
                                        args[args.size - 1],
                                        Nodes.territories.values.filter { it.occupier != null },
                                    )
                                }
                            }

                            // /nodesadmin town sethome [town] [id]
                            "sethome" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size == 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town != null) {
                                        return completeTerritoryIds(
                                            args[3],
                                            town.territories.asSequence().mapNotNull { Nodes.territories[it] }.toList(),
                                        )
                                    }
                                }
                            }

                            // /nodesadmin town incomeremove [town] [material]
                            "incomeremove" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size == 4) {
                                    return completeMaterialNames(args[3])
                                }
                            }

                            // outpost
                            "addoutpost" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size == 5) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town != null) {
                                        return completeTerritoryIds(
                                            args[4],
                                            town.territories.asSequence().mapNotNull { Nodes.territories[it] }.toList(),
                                        )
                                    }
                                }
                            }

                            // /nodesadmin town subcommand [town] [outpost] ...
                            "removeoutpost" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size >= 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town !== null) {
                                        return filterByStart(town.outposts.keys.toList(), args[3])
                                    }
                                }
                            }
                        }
                    }
                }

                // /nodesadmin territory [subcommand]
                "territory" -> {
                    if (args.size == 2) {
                        return filterByStart(TERRITORY_SUBCOMMANDS, args[1])
                    } else if (args.size >= 3) {
                        when (args[1].lowercase()) {
                            "addresource",
                            "removeresource",
                            "addtype",
                            "removetype",
                            "addcore",
                            "removecore",
                            -> {
                                if (args.size == 3) {
                                    val query = args[2]
                                    val showTerritories = query.length >= 2 && query.firstOrNull()?.isDigit() == true
                                    return if (args[1].lowercase() == "addcore" || args[1].lowercase() == "removecore") {
                                        if (showTerritories) {
                                            filterByStart(Nodes.territories.keys.map { it.toInt().toString() }, query)
                                        } else {
                                            filterTown(query)
                                        }
                                    } else {
                                        if (showTerritories) {
                                            filterByStart(Nodes.territories.keys.map { it.toInt().toString() }, query)
                                        } else {
                                            listOf()
                                        }
                                    }
                                } else if (args.size >= 4) {
                                    return when (args[1].lowercase()) {
                                        "addresource",
                                        "removeresource",
                                        -> filterByStart(Nodes.resourceNodes.keys.toList(), args[args.size - 1])
                                        "addtype",
                                        "removetype",
                                        -> filterByStart(Config.nodeTypes.keys.toList(), args[args.size - 1])
                                        else -> listOf()
                                    }
                                }
                            }
                        }
                    }
                }
                "resource", "ressource" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("add", "remove"), args[1])
                    } else if (args.size == 3) {
                        return filterByStart(Nodes.territories.keys.map { it.toString() }, args[2])
                    } else if (args.size >= 4) {
                        return filterByStart(Nodes.resourceNodes.keys.toList(), args[args.size - 1])
                    }
                }
                "warzone" -> {
                    if (args.size >= 2) {
                        val current = args.copyOfRange(1, args.size).joinToString(" ").trim().lowercase()
                        return WarzoneManager.getWarzones()
                            .map { it.name }
                            .filter { it.lowercase().startsWith(current) }
                    }
                }

                "ban" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("town", "nation"), args[1])
                    } else if (args.size == 3) {
                        when (args[1].lowercase()) {
                            "town" -> return filterTown(args[2])
                            "nation" -> return filterNation(args[2])
                        }
                    }
                }

                "unban" -> {
                    if (args.size == 2) {
                        val suggestions = ArrayList<String>()
                        suggestions.add("player")
                        suggestions.add("town")
                        suggestions.addAll(Nodes.towns.keys)
                        suggestions.addAll(Bukkit.getOfflinePlayers().map { it.name }.filterNotNull())
                        return filterByStart(suggestions.distinct(), args[1])
                    } else if (args.size == 3) {
                        when (args[1].lowercase()) {
                            "town" -> return filterTown(args[2])
                            "player" -> return filterResident(args[2])
                        }
                    }
                }

                "checkban" -> {
                    if (args.size == 2) {
                        val suggestions = ArrayList<String>()
                        suggestions.add("town")
                        suggestions.addAll(Nodes.towns.keys)
                        return filterByStart(suggestions, args[1])
                    } else if (args.size == 3 && args[1].lowercase() == "town") {
                        return filterTown(args[2])
                    }
                }

                // /nodesadmin town [subcommand]
                "nation" -> {
                    if (args.size == 2) {
                        return filterByStart(NATION_SUBCOMMANDS, args[1])
                    }
                    // handle subcommand
                    else if (args.size > 2) {
                        when (args[1].lowercase()) {
                            // /nodesadmin nation [subcommand] [nation] ...
                            "delete" -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                }
                            }
                            "color",
                            "rename",
                            -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                }
                            }

                            // /nodesadmin nation subcommand [nation] [town1] [town2] ...
                            "addtown",
                            "removetown",
                            -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                } else {
                                    return filterTown(args[args.size - 1])
                                }
                            }

                            // /nodesadmin nation subcommand [nation] [town1]
                            "capital" -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                } else if (args.size == 4) {
                                    val nation = Nodes.getNationFromName(args[2])
                                    if (nation !== null) {
                                        return filterNationTown(nation, args[3])
                                    }
                                }
                            }
                        }
                    }
                }

                // /nodesadmin subcommand [town/nation] [town/nation]
                "enemy",
                "peace",
                "ally",
                "allyremove",
                "truce",
                "truceremove",
                -> {
                    if (args.size == 2) {
                        return filterTownOrNation(args[1])
                    } else if (args.size == 3) {
                        return filterTownOrNation(args[2])
                    }
                }

                "treaty" -> {
                    if (args.size == 2) {
                        return filterTownOrNation(args[1])
                    } else if (args.size == 3) {
                        return filterTownOrNation(args[2])
                    }
                }

                // /nodesadmin debug [subcommand]
                "debug" -> {
                    if (args.size == 2) {
                        return filterByStart(DEBUG_SUBCOMMANDS, args[1])
                    }
                    // handle subcommand
                    else if (args.size > 2) {
                        when (args[1].lowercase()) {
                            "resident" -> {
                                if (args.size == 3) {
                                    return filterResident(args[2])
                                }
                            }

                            "town" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                }
                            }

                            "nation" -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                }
                            }
                        }
                    }
                }
            }
        }

        return listOf()
    }

    private fun completeTerritoryIds(query: String, territories: Collection<Territory> = Nodes.territories.values): List<String> = filterByStart(territories.map { it.id.toInt().toString() }.distinct(), query)

    private fun completeMaterialNames(query: String): List<String> = filterByStart(Material.values().map { it.name }, query)

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "/nodesadmin disable${ChatColor.WHITE}: Enable/disable market, miningtower, or miningoutminer")
        Message.print(sender, "[Nodes] Admin commands:")
        Message.print(sender, "/nodesadmin reload${ChatColor.WHITE}: Reloads modules of plugin")
        Message.print(sender, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
        Message.print(sender, "/nodesadmin wardeclaration on/off${ChatColor.WHITE}: Enable or disable /war declarations")
        Message.print(sender, "/nodesadmin peaceall${ChatColor.WHITE}: Set peace between all towns")
        Message.print(sender, "/nodesadmin leaderlaunch${ChatColor.WHITE}: Manage global Leader Launch access")
        Message.print(sender, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town help\")")
        Message.print(sender, "/nodesadmin territory${ChatColor.WHITE}: Manage territory resources/types (see \"/nodesadmin territory help\")")
        Message.print(sender, "/nodesadmin ressource add/remove [nodeId] [resource]${ChatColor.WHITE}: Shortcut for territory resources")
        Message.print(sender, "/nodesadmin warzone${ChatColor.WHITE}: Manage warzones")
        Message.print(sender, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation help\")")
        Message.print(sender, "/nodesadmin portgroup${ChatColor.WHITE}: Manage port groups (see \"/nodesadmin portgroup help\")")
        Message.print(sender, "/nodesadmin port${ChatColor.WHITE}: Manage ports (see \"/nodesadmin port help\")")
        Message.print(sender, "/nodesadmin outpost${ChatColor.WHITE}: Manage outpost requests (see \"/nodesadmin outpost list\")")
        Message.print(sender, "/nodesadmin miningoutpost${ChatColor.WHITE}: Place/remove mining outposts")
        Message.print(sender, "/nodesadmin attacktimedebug${ChatColor.WHITE}: Show computed attack time breakdown for a chunk")
        Message.print(sender, "/nodesadmin enemy${ChatColor.WHITE}: Make two towns/nations enemies")
        Message.print(sender, "/nodesadmin peace${ChatColor.WHITE}: Sets peace between two towns/nations")
        Message.print(sender, "/nodesadmin ally${ChatColor.WHITE}: Sets alliance between two towns/nations")
        Message.print(sender, "/nodesadmin allyremove${ChatColor.WHITE}: Removes alliance between two towns/nations")
        Message.print(sender, "/nodesadmin truce${ChatColor.WHITE}: Sets truce between two towns/nations")
        Message.print(sender, "/nodesadmin truceremove${ChatColor.WHITE}: Removes truce between two towns/nations")
        Message.print(sender, "/nodesadmin unban${ChatColor.WHITE}: Unban a player or all banned residents in a town")
        Message.print(sender, "/nodesadmin checkban${ChatColor.WHITE}: Check banned residents in a town")
        Message.print(sender, "/nodesadmin save${ChatColor.WHITE}: Force save world")
        Message.print(sender, "/nodesadmin load${ChatColor.WHITE}: Force load world")
        Message.print(sender, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
        Message.print(sender, "/nodesadmin clearincome${ChatColor.WHITE}: Clears stored income for all towns")
        Message.print(sender, "/nodesadmin holograms stats${ChatColor.WHITE}: Show player nametag hologram state")
        Message.print(sender, "/nodesadmin holograms refresh${ChatColor.WHITE}: Rebuild tracked player nametag text/visibility")
        Message.print(sender, "/nodesadmin holograms respawn${ChatColor.WHITE}: Delete and rebuild all player nametag holograms")
        Message.print(sender, "/nodesadmin holograms cleanup${ChatColor.WHITE}: Delete bugged/orphaned player nametag holograms")
        Message.print(sender, "/nodesadmin debug${ChatColor.WHITE}: World object debugger")
        return
    }

    private fun manageHolograms(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /nodesadmin holograms <stats|refresh|respawn|cleanup>")
            return
        }

        when (args[1].lowercase()) {
            "stats" -> {
                Message.print(sender, "${ChatColor.BOLD}Nametag hologram stats")
                Message.print(sender, "Online players: ")
                Message.print(sender, "Tracked owners: ")
                Message.print(sender, "Tracked mappings:")
                Message.print(sender, "Valid tracked displays:")
                Message.print(sender, "World tagged displays: ")
                Message.print(sender, "Estimated orphan displays: ")
                Message.print(sender, "Pipeline locked:")
            }
            "refresh" -> {
                Message.print(sender, "Queued player nametag hologram refresh")
            }
            "respawn" -> {
                Message.print(sender, "Deleted and rebuilt all player nametag holograms")
            }
            "cleanup" -> {
                val attackRemoved = FlagWar.cleanupAttackHolograms()
            }
            else -> Message.error(sender, "Usage: /nodesadmin holograms <stats|refresh|respawn|cleanup>")
        }
    }

    private fun requireAdmin(sender: CommandSender, isAdmin: Boolean): Boolean {
        if (!isAdmin) {
            Message.error(sender, "You do not have permission to run this command")
        }
        return isAdmin
    }

    private fun manageSocialSpy(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player
        if (player == null) {
            Message.error(sender, "Only players can use this command")
            return
        }

        if (args.size == 1) {
            val enabled = SocialSpy.toggleAll(player)
            if (enabled) {
                Message.print(sender, "SocialSpy now watching all chat channels")
            } else {
                Message.print(sender, "SocialSpy disabled for all chat channels")
            }
            return
        }

        val option = args[1].lowercase()
        if (option == "all") {
            val enabled = SocialSpy.toggleAll(player)
            if (enabled) {
                Message.print(sender, "SocialSpy now watching all chat channels")
            } else {
                Message.print(sender, "SocialSpy disabled for all chat channels")
            }
            return
        }

        val mode = when (option) {
            "local" -> ChatMode.LOCAL
            "town" -> ChatMode.TOWN
            "nation" -> ChatMode.NATION
            "ally" -> ChatMode.ALLY
            else -> null
        }

        if (mode != null) {
            val enabled = SocialSpy.toggleMode(player, mode)
            val name = mode.name.lowercase()
            if (enabled) {
                Message.print(sender, "SocialSpy enabled for $name chat")
            } else {
                Message.print(sender, "SocialSpy disabled for $name chat")
            }
            return
        }

        Message.error(sender, "Unknown socialspy option \"${option}\"")
        Message.print(sender, "/nodesadmin socialspy [local|town|nation|ally]")
        Message.print(sender, "Run without options to toggle all chat channels")
    }

    private fun manageLeaderLaunch(sender: CommandSender, args: Array<String>, isAdmin: Boolean, isMod: Boolean) {
        if (args.size < 2) {
            printLeaderLaunchHelp(sender)
            return
        }

        val sub = args[1].lowercase()
        if (!isAdmin) {
            if (!isMod) {
                Message.error(sender, "You do not have permission to manage Leader Launch")
                return
            }

            when (sub) {
                "add" -> leaderLaunchAdd(sender, args)
                "remove" -> leaderLaunchRemove(sender, args)
                else -> {
                    Message.error(sender, "You do not have permission to run /nodesadmin leaderlaunch $sub")
                    printLeaderLaunchHelp(sender)
                }
            }

            return
        }

        when (sub) {
            "on" -> {
                if (LeaderLaunchManager.isEnabled()) {
                    Message.print(sender, "Leader Launch is already enabled")
                    return
                }
                LeaderLaunchManager.setEnabled(true)
                Message.print(sender, "Leader Launch enabled")
            }

            "off" -> {
                if (!LeaderLaunchManager.isEnabled()) {
                    Message.print(sender, "Leader Launch is already disabled")
                    return
                }
                LeaderLaunchManager.setEnabled(false)
                Message.print(sender, "Leader Launch disabled")
            }

            "add" -> leaderLaunchAdd(sender, args)

            "remove" -> leaderLaunchRemove(sender, args)

            "status" -> printLeaderLaunchSummary(sender)

            else -> {
                Message.error(sender, "Unknown leaderlaunch command \"${args[1]}\"")
                printLeaderLaunchHelp(sender)
            }
        }
    }

    private fun leaderLaunchAdd(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin leaderlaunch add <player...>")
            return
        }

        for (name in args.copyOfRange(2, args.size)) {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) {
                continue
            }

            val offline = Bukkit.getOfflinePlayer(trimmedName)
            if (LeaderLaunchManager.addPlayer(offline.uniqueId, trimmedName)) {
                Message.print(sender, "Added $trimmedName to Leader Launch")
            } else {
                Message.print(sender, "$trimmedName is already on the Leader Launch list")
            }
        }
    }

    private fun leaderLaunchRemove(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin leaderlaunch remove <player...>")
            return
        }

        for (name in args.copyOfRange(2, args.size)) {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) {
                continue
            }

            val offline = Bukkit.getOfflinePlayer(trimmedName)
            if (LeaderLaunchManager.removePlayer(offline.uniqueId)) {
                Message.print(sender, "Removed $trimmedName from Leader Launch")
            } else {
                Message.error(sender, "$trimmedName is not on the Leader Launch list")
            }
        }
    }

    private fun printLeaderLaunchHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Leader Launch control:")
        Message.print(
            sender,
            "/nodesadmin leaderlaunch on${ChatColor.WHITE}: Enable the join lock",
        )
        Message.print(
            sender,
            "/nodesadmin leaderlaunch off${ChatColor.WHITE}: Disable the join lock",
        )
        Message.print(
            sender,
            "/nodesadmin leaderlaunch add <player...>${ChatColor.WHITE}: Add players to the allowlist",
        )
        Message.print(
            sender,
            "/nodesadmin leaderlaunch remove <player...>${ChatColor.WHITE}: Remove players from the allowlist",
        )
        Message.print(
            sender,
            "/nodesadmin leaderlaunch status${ChatColor.WHITE}: Show state and registered players",
        )
    }

    private fun printLeaderLaunchSummary(sender: CommandSender) {
        val modeText = if (LeaderLaunchManager.isEnabled()) "ON" else "OFF"
        val registeredPlayers = LeaderLaunchManager.listRegisteredNames()
        Message.print(sender, "Leader Launch${ChatColor.WHITE}: $modeText")
        Message.print(sender, "Registered${ChatColor.WHITE}: ${registeredPlayers.size}")
        if (registeredPlayers.isNotEmpty()) {
            Message.print(sender, registeredPlayers.joinToString(", "))
        }
    }

    private fun manageMiningOutminer(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.print(sender, "/nodesadmin miningoutpost place [name...]${ChatColor.WHITE}: Place one at the target block")
            Message.print(sender, "/nodesadmin miningoutpost remove${ChatColor.WHITE}: Remove the targeted one")
            Message.print(sender, "/nodesadmin miningoutpost removeindex <index>${ChatColor.WHITE}: Remove one from the list")
            Message.print(sender, "/nodesadmin miningoutpost list${ChatColor.WHITE}: Show current amount")
            Message.print(sender, "/nodesadmin miningoutpost resources${ChatColor.WHITE}: Show resources of targeted mining outpost")
            Message.print(sender, "/nodesadmin miningoutpost setresource <material> <amount>${ChatColor.WHITE}: Change one resource on targeted mining outpost")
            Message.print(sender, "/nodesadmin miningoutpost tp <index>${ChatColor.WHITE}: Teleport to one from the list")
            Message.print(sender, "/nodesadmin miningoutpost regenholos${ChatColor.WHITE}: Rebuild all mining outpost holograms")
            return
        }

        when (args[1].lowercase()) {
            "list" -> {
                val outminers = ActiveMiningOutminerManager.listOutminers()
                Message.print(sender, "Mining Outposts: ${outminers.size}")
                if (outminers.isEmpty()) {
                    return
                }

                for (summary in outminers) {
                    val statusText = if (summary.ready) {
                        "READY"
                    } else {
                        "${(summary.cooldownRemainingMs / 3600000L)}h ${((summary.cooldownRemainingMs % 3600000L) / 60000L)}m"
                    }
                    val line = Component.text()
                        .append(Component.text("#${summary.index} ").color(NamedTextColor.GOLD))
                        .append(Component.text(summary.name).color(NamedTextColor.AQUA))
                        .append(Component.text(" [${summary.world} ${summary.x}, ${summary.y}, ${summary.z}] ").color(NamedTextColor.WHITE))
                        .append(Component.text("${summary.resourceKinds} types / ${summary.totalItems} items ").color(NamedTextColor.GRAY))
                        .append(
                            Component.text("[$statusText] ")
                                .color(if (summary.ready) NamedTextColor.GREEN else NamedTextColor.YELLOW),
                        )
                        .append(
                            Component.text("[TP]")
                                .color(NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand("/nodesadmin miningoutpost tp ${summary.index}")),
                        )
                        .append(Component.text(" ").color(NamedTextColor.WHITE))
                        .append(
                            Component.text("[REMOVE]")
                                .color(NamedTextColor.RED)
                                .clickEvent(ClickEvent.runCommand("/nodesadmin miningoutpost removeindex ${summary.index}")),
                        )
                        .build()
                    Message.component(sender, line)
                }
            }
            "place" -> {
                val player = sender as? Player
                if (player == null) {
                    Message.error(sender, "Only players can place mining outposts")
                    return
                }

                val target = player.getTargetBlockExact(10)
                val placeBlock = if (target == null || target.type.isAir) player.location.block else target.getRelative(org.bukkit.block.BlockFace.UP)
                val name = if (args.size > 2) args.copyOfRange(2, args.size).joinToString(" ").trim() else ""
                val error = ActiveMiningOutminerManager.placeAt(placeBlock, name)
                if (error != null) {
                    Message.error(player, error)
                    return
                }

                val placedName = name.ifEmpty { "Mining Outpost ${placeBlock.x},${placeBlock.y},${placeBlock.z}" }
                Message.print(player, "Placed Mining Outpost \"$placedName\" at ${ActiveMiningOutminerManager.placementSummary(placeBlock)}")
            }

            "remove" -> {
                val player = sender as? Player
                if (player == null) {
                    Message.error(sender, "Only players can remove mining outposts")
                    return
                }

                val target = player.getTargetBlockExact(10) ?: player.location.block
                if (!ActiveMiningOutminerManager.removeAt(target)) {
                    Message.error(player, "No Mining Outpost found on the targeted block")
                    return
                }

                Message.print(player, "Removed Mining Outpost")
            }

            "removeindex" -> {
                if (args.size < 3) {
                    Message.error(sender, "Usage: /nodesadmin miningoutpost removeindex <index>")
                    return
                }

                val index = args[2].toIntOrNull()
                if (index == null || index <= 0) {
                    Message.error(sender, "Index must be a positive number")
                    return
                }

                val removed = ActiveMiningOutminerManager.removeAtIndex(index)
                if (removed == null) {
                    Message.error(sender, "No Mining Outpost found at list index $index")
                    return
                }

                Message.print(sender, "Removed Mining Outpost \"${removed.name}\" at ${removed.world} (${removed.x}, ${removed.y}, ${removed.z})")
            }

            "resources" -> {
                val player = sender as? Player
                if (player == null) {
                    Message.error(sender, "Only players can inspect mining outposts")
                    return
                }

                val target = player.getTargetBlockExact(10) ?: player.location.block
                val rewards = ActiveMiningOutminerManager.getRewardsAt(target)
                if (rewards == null) {
                    Message.error(player, "No Mining Outpost found on the targeted block")
                    return
                }

                Message.print(player, "Mining Outpost resources:")
                rewards.entries.sortedBy { it.key.name }.forEach { (material, amount) ->
                    Message.print(player, "- ${material.name}: $amount")
                }
            }

            "setresource" -> {
                val player = sender as? Player
                if (player == null) {
                    Message.error(sender, "Only players can edit mining outposts")
                    return
                }
                if (args.size < 4) {
                    Message.error(player, "Usage: /nodesadmin miningoutpost setresource <material> <amount>")
                    return
                }

                val material = Material.matchMaterial(args[2])
                if (material == null || material == Material.AIR) {
                    Message.error(player, "Invalid material")
                    return
                }

                val amount = args[3].toIntOrNull()
                if (amount == null) {
                    Message.error(player, "Amount must be a number")
                    return
                }

                val target = player.getTargetBlockExact(10) ?: player.location.block
                val error = ActiveMiningOutminerManager.setResourceAt(target, material, amount)
                if (error != null) {
                    Message.error(player, error)
                    return
                }

                if (amount <= 0) {
                    Message.print(player, "Removed ${material.name} from targeted Mining Outpost")
                } else {
                    Message.print(player, "Set ${material.name} to $amount on targeted Mining Outpost")
                }
            }

            "tp" -> {
                val player = sender as? Player
                if (player == null) {
                    Message.error(sender, "Only players can teleport to mining outposts")
                    return
                }
                if (args.size < 3) {
                    Message.error(player, "Usage: /nodesadmin miningoutpost tp <index>")
                    return
                }

                val index = args[2].toIntOrNull()
                if (index == null || index <= 0) {
                    Message.error(player, "Index must be a positive number")
                    return
                }

                val summary = ActiveMiningOutminerManager.getSummaryAtIndex(index)
                if (summary == null) {
                    Message.error(player, "No Mining Outpost found at list index $index")
                    return
                }

                val world = Bukkit.getWorld(summary.world)
                if (world == null) {
                    Message.error(player, "World \"${summary.world}\" is not loaded")
                    return
                }

                Message.print(player, "Teleported to Mining Outpost \"${summary.name}\"")
            }

            "regenholos" -> {
                val count = ActiveMiningOutminerManager.regenerateHolograms()
                Message.print(sender, "Regenerated mining outpost holograms for $count mining outposts")
            }

            else -> Message.error(sender, "Usage: /nodesadmin miningoutpost <place|remove|removeindex|list|resources|setresource|tp|regenholos>")
        }
    }

    /**
     * @command /nodesadmin reload [config|managers|territory]
     * Reload components of Nodes engine
     */
    private fun reload(sender: CommandSender, args: Array<String>) {
        // print general war state
        if (args.size < 2) {
            Message.print(sender, "Reload possibilities: \"/nodesadmin reload [config|managers|resources|territory|tab]\"")
            return
        }

        val subcommand = args[1].lowercase()
        if (subcommand == "config") {
            Nodes.reloadConfig()
            Message.print(sender, "[Nodes] reloaded configs")
        } else if (subcommand == "managers") {
            Nodes.reloadManagers()
            Message.print(sender, "[Nodes] reloaded manager tasks")
        } else if (subcommand == "resources") {
            val success = Nodes.reloadWorldJson(
                reloadResources = true,
                reloadTerritories = false,
            )
            if (success) {
                Message.print(sender, "[Nodes] reloaded resources and territories")
            } else {
                Message.error(sender, "[Nodes] failed to reload resources and territories")
            }
        } else if (subcommand == "territory") {
            // parse territory ids
            if (args.size < 3) {
                Message.print(sender, "Usage: \"/nodesadmin reload territory *\"${ChatColor.WHITE}: reloads ALL territories")
                Message.print(sender, "Usage: \"/nodesadmin reload territory id1 id2 id3 ...\"${ChatColor.WHITE}: reloads specific ids")
                return
            }

            val terrIds: List<TerritoryId>? = if (args[2] == "*") { // reload ALL territories (don't specify ids)
                null
            } else { // load specific ids: parse from chat input
                val ids = HashSet<TerritoryId>()

                // validate id exists in territories (don't allow reloading NEW territories,
                // since this can cause issues and instability if ids/neighbors are changing)
                for (i in 2 until args.size) {
                    try {
                        val id = TerritoryId(args[i].toInt())
                        val terr = Nodes.getTerritoryFromId(id)
                        if (terr == null) {
                            Message.error(sender, "Territory id \"${id}\" does not exist. This can only reload existing territories.")
                            return
                        }
                        ids.add(id)
                    } catch (err: Exception) {
                        Message.error(sender, "Invalid id: \"${args[i]}\"")
                        return
                    }
                }

                ids.toList()
            }

            val success = Nodes.reloadWorldJson(
                reloadResources = false,
                reloadTerritories = true,
                territoryIds = terrIds,
            )

            val terrIdsString = terrIds?.let { ids -> "territories $ids" } ?: "all territories"

            if (success) {
                Message.print(sender, "[Nodes] reloaded $terrIdsString")
            } else {
                Message.error(sender, "[Nodes] failed to reload $terrIdsString")
            }
        } else if (subcommand == "tab") {
            val plugin = Nodes.plugin as? org.bukkit.plugin.java.JavaPlugin
            if (plugin == null) {
                Message.error(sender, "[Nodes] plugin instance missing, cannot reload tab")
                return
            }
            Message.print(sender, "[Nodes] reloaded TAB integration")
        } else {
            Message.print(sender, "Reload possibilities: \"/nodesadmin reload [config|managers|resources|territory|tab]\"")
        }
    }

    private fun rallyCapToggle(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.print(sender, "${ChatColor.BOLD}Rally Cap Status:")
            Message.print(sender, "- Enabled: ${ChatColor.WHITE}${Config.rallyCapEnabled}")
            Message.print(sender, "- Max Players: ${ChatColor.WHITE}${Config.rallyCapSize}")
            Message.print(sender, "- Apply to: ${ChatColor.WHITE}${if (Config.rallyCapApplyToNations) "Nations" else "Towns"}")
            Message.print(sender, "")
            Message.print(sender, "Usage: /nodesadmin rallycap <enable|disable|size [number]>")
            return
        }

        when (args[1].lowercase()) {
            "enable" -> {
                Config.rallyCapEnabled = true
                Message.print(sender, "${ChatColor.GREEN}Rally cap enabled (max ${Config.rallyCapSize} players per ${if (Config.rallyCapApplyToNations) "nation" else "town"})")
                Message.broadcast("${ChatColor.BOLD}Rally cap has been enabled - maximum ${Config.rallyCapSize} players per ${if (Config.rallyCapApplyToNations) "nation" else "town"}")
            }

            "disable" -> {
                Config.rallyCapEnabled = false
                Message.print(sender, "${ChatColor.DARK_RED}Rally cap disabled")
                Message.broadcast("${ChatColor.BOLD}Rally cap has been disabled")
            }

            "size" -> {
                if (args.size < 3) {
                    Message.error(sender, "Usage: /nodesadmin rallycap size [number]")
                    Message.print(sender, "Current size: ${Config.rallyCapSize}")
                    return
                }

                val size = args[2].toIntOrNull()
                if (size == null || size <= 0) {
                    Message.error(sender, "Size must be a positive number")
                    return
                }

                Config.rallyCapSize = size
                Message.print(sender, "${ChatColor.GREEN}Rally cap size set to $size players")

                if (Config.rallyCapEnabled) {
                    Message.broadcast("${ChatColor.BOLD}Rally cap size changed to $size players per ${if (Config.rallyCapApplyToNations) "nation" else "town"}")

                    // Optionally: kick players over the new limit
                    Message.print(sender, "${ChatColor.YELLOW}Note: Currently online players over the new limit will not be kicked until they relog")
                }
            }

            else -> {
                Message.error(sender, "Usage: /nodesadmin rallycap <enable|disable|size [number]>")
            }
        }
    }

    private fun checkBanTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /nodesadmin checkban <town>")
            Message.error(sender, "Usage: /nodesadmin checkban town <town>")
            return
        }

        val townName = if (args[1].equals("town", ignoreCase = true)) {
            if (args.size < 3) {
                Message.error(sender, "Usage: /nodesadmin checkban town <town>")
                return
            }
            args[2]
        } else {
            args[1]
        }

        val town = Nodes.getTownFromName(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val bannedResidents = town.residents.filter { resident ->
            Bukkit.getOfflinePlayer(resident.uuid).isBanned
        }

        Message.print(sender, "${ChatColor.BOLD}Banned residents in ${town.name}: ${bannedResidents.size}")
        if (bannedResidents.isEmpty()) {
            Message.print(sender, "${ChatColor.GRAY}None")
            return
        }

        for (resident in bannedResidents) {
            Message.print(sender, "- ${resident.name}")
        }
    }

    private fun banTownOrNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ban <town|nation> <name>")
            return
        }

        val type = args[1].lowercase()
        val name = args[2]

        when (type) {
            "town" -> {
                val town = Nodes.getTownFromName(name)
                if (town == null) {
                    Message.error(sender, "Town \"$name\" does not exist")
                    return
                }

                // Ban leader
                town.leader?.let { leader ->
                    val player = leader.player()
                    player?.let { p ->
                        val offline = Bukkit.getOfflinePlayer(p.uniqueId)
                        offline.banPlayer("Banned by administrator", null, null, false)

                        p.kick(Component.text("Banned by administrator").color(NamedTextColor.RED))
                        Message.print(sender, "Banned and kicked leader: ${leader.name}")
                    }
                }

                // Ban all officers
                val officers = ArrayList(town.officers) // copy to avoid CME
                for (officer in officers) {
                    val player = officer.player()
                    player?.let { p ->
                        val offline = Bukkit.getOfflinePlayer(p.uniqueId)
                        offline.banPlayer("Banned by administrator", null, null, false)

                        p.kick(Component.text("Banned by administrator").color(NamedTextColor.RED))
                        Message.print(sender, "Banned and kicked officer: ${officer.name}")
                    }
                }

                // Kick all other residents (no ban)
                val residents = ArrayList(town.residents)
                for (resident in residents) {
                    if (resident != town.leader && !town.officers.contains(resident)) {
                        val player = resident.player()
                        player?.let { p ->
                            p.kick(
                                Component.text("Your town has been disbanded by an administrator")
                                    .color(NamedTextColor.RED),
                            )
                            Message.print(sender, "Kicked resident: ${resident.name}")
                        }
                    }
                }

                // Delete town
                Nodes.destroyTown(town)
                Message.print(sender, "${ChatColor.DARK_RED}Banned town \"$name\" - all officers banned, all members kicked")
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Town \"$name\" has been banned and disbanded")
            }

            "nation" -> {
                val nation = Nodes.getNationFromName(name)
                if (nation == null) {
                    Message.error(sender, "Nation \"$name\" does not exist")
                    return
                }

                val townsToProcess = ArrayList(nation.towns)

                for (town in townsToProcess) {
                    // Ban leader
                    town.leader?.let { leader ->
                        val player = leader.player()
                        player?.let { p ->
                            val offline = Bukkit.getOfflinePlayer(p.uniqueId)
                            offline.banPlayer("Banned by administrator", null, null)
                            p.kick(Component.text("Banned by administrator").color(NamedTextColor.RED))
                            Message.print(sender, "Banned and kicked ${town.name} leader: ${leader.name}")
                        }
                    }

                    // Ban officers
                    val officers = ArrayList(town.officers)
                    for (officer in officers) {
                        val player = officer.player()
                        player?.let { p ->
                            val offline = Bukkit.getOfflinePlayer(p.uniqueId)
                            offline.banPlayer("Banned by administrator", null, null)
                            p.kick(Component.text("Banned by administrator").color(NamedTextColor.RED))
                            Message.print(sender, "Banned and kicked ${town.name} officer: ${officer.name}")
                        }
                    }

                    // Kick other residents
                    val residents = ArrayList(town.residents)
                    for (resident in residents) {
                        if (resident != town.leader && !town.officers.contains(resident)) {
                            val player = resident.player()
                            player?.let { p ->
                                p.kick(
                                    Component.text("Your nation has been disbanded by an administrator")
                                        .color(NamedTextColor.RED),
                                )
                                Message.print(sender, "Kicked ${town.name} resident: ${resident.name}")
                            }
                        }
                    }
                }

                // Delete nation (should cascade to towns if your impl does)
                Nodes.destroyNation(nation)

                Message.print(sender, "${ChatColor.DARK_RED}Banned nation \"$name\" - all officers from all towns banned, all members kicked")
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nation \"$name\" has been banned and disbanded")
            }

            else -> {
                Message.error(sender, "Usage: /nodesadmin ban <town|nation> <name>")
            }
        }
    }

    private fun unbanPlayerOrTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /nodesadmin unban <player|town>")
            Message.error(sender, "Usage: /nodesadmin unban player <name>")
            Message.error(sender, "Usage: /nodesadmin unban town <town>")
            return
        }

        val mode = args[1].lowercase()
        if (mode == "town") {
            if (args.size < 3) {
                Message.error(sender, "Usage: /nodesadmin unban town <town>")
                return
            }
            val town = Nodes.getTownFromName(args[2])
            if (town == null) {
                Message.error(sender, "Town \"${args[2]}\" does not exist")
                return
            }

            var unbanned = 0
            for (resident in town.residents) {
                val offline = Bukkit.getOfflinePlayer(resident.uuid)
                if (offline.isBanned) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon ${resident.name}")
                    unbanned += 1
                }
            }

            Message.print(sender, "${ChatColor.GREEN}Unbanned $unbanned banned resident(s) from town ${town.name}")
            return
        }

        val playerName = if (mode == "player") {
            if (args.size < 3) {
                Message.error(sender, "Usage: /nodesadmin unban player <name>")
                return
            }
            args[2]
        } else {
            args[1]
        }

        val town = Nodes.getTownFromName(playerName)
        if (mode != "player" && town != null) {
            var unbanned = 0
            for (resident in town.residents) {
                val offline = Bukkit.getOfflinePlayer(resident.uuid)
                if (offline.isBanned) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon ${resident.name}")
                    unbanned += 1
                }
            }
            Message.print(sender, "${ChatColor.GREEN}Unbanned $unbanned banned resident(s) from town ${town.name}")
            return
        }

        val offline = Bukkit.getOfflinePlayer(playerName)
        if (!offline.isBanned) {
            Message.error(sender, "Player \"$playerName\" is not banned")
            return
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon $playerName")
        Message.print(sender, "${ChatColor.GREEN}Unbanned player \"$playerName\"")
    }

    /**
     * @command /nodesadmin war [enable|disable]
     * Enables/disables war
     */
    private fun war(sender: CommandSender, args: Array<String>) {
        // print general war state
        if (args.size < 2) {
            Nodes.war.printInfo(sender, true)
            Message.print(sender, "Toggle state: \"/nodesadmin war [enable|skirmish|disable] [time]\"")
        }
        // war subcommands
        else {
            val function = args[1].lowercase()
            // full war: allow annex, can attack any territory
            when (function) {
                "enable" -> {
                    val durationMillis = parseDurationMillis(args, 2).getOrElse { err ->
                        Message.error(sender, err.message ?: "Invalid war time")
                        Message.error(sender, "Usage: /nodesadmin war enable [3h 0m]")
                        return
                    }
                    Nodes.enableWar(true, false, true, durationMillis)
                    if (durationMillis > 0L) {
                        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled for ${formatDuration(durationMillis)}")
                    } else {
                        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")
                    }

                    // play MENACING wither spawn sound
                    for (p in Bukkit.getOnlinePlayers()) {
                        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.0f)
                    }
                }

                // cannot annex, cannot attack home territory, can only hit borders
                "skirmish" -> {
                    val durationMillis = parseDurationMillis(args, 2).getOrElse { err ->
                        Message.error(sender, err.message ?: "Invalid skirmish time")
                        Message.error(sender, "Usage: /nodesadmin war skirmish [3h 0m]")
                        return
                    }
                    Nodes.enableWar(false, true, Config.allowDestructionDuringSkirmish, durationMillis)
                    if (durationMillis > 0L) {
                        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishes enabled for ${formatDuration(durationMillis)}")
                    } else {
                        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishes enabled")
                    }

                    // play MENACING wither spawn sound
                    for (p in Bukkit.getOnlinePlayers()) {
                        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.0f)
                    }
                }

                "disable" -> {
                    if (Nodes.war.enabled == true) {
                        Nodes.disableWar()
                        Message.broadcast("${ChatColor.BOLD}Nodes war disabled")
                    } else {
                        Message.error(sender, "Nodes war already disabled")
                    }
                }

                "whitelist" -> manageWarAccessList(sender, args, WarAccessListType.WHITELIST)
                "blacklist" -> manageWarAccessList(sender, args, WarAccessListType.BLACKLIST)
                "annex" -> manageWarAccessList(sender, args, WarAccessListType.ANNEX)

                else -> {
                    Message.print(sender, "Nodes war usage: \"/nodesadmin war [enable|skirmish|disable|whitelist|blacklist|annex]\"")
                }
            }
        }
    }

    private fun warDeclaration(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            val state = if (Nodes.warDeclarationsEnabled) "on" else "off"
            Message.print(sender, "War declaration is currently $state")
            Message.print(sender, "Usage: /nodesadmin wardeclaration [on|off]")
            return
        }

        when (args[1].lowercase()) {
            "on", "enable", "enabled", "true" -> {
                Nodes.warDeclarationsEnabled = true
                Message.print(sender, "${ChatColor.GREEN}War declarations enabled")
            }
            "off", "disable", "disabled", "false" -> {
                Nodes.warDeclarationsEnabled = false
                Message.print(sender, "${ChatColor.YELLOW}War declarations disabled")
            }
            else -> {
                Message.error(sender, "Usage: /nodesadmin wardeclaration [on|off]")
            }
        }
    }

    private fun peaceAll(sender: CommandSender) {
        val towns = Nodes.towns.values.toList()
        var resolved = 0
        for (i in 0 until towns.size) {
            for (j in i + 1 until towns.size) {
                val townA = towns[i]
                val townB = towns[j]
                if (townA.enemies.contains(townB) || townB.enemies.contains(townA)) {
                    Nodes.removeEnemy(townA, townB)
                    resolved += 1
                }
            }
        }

        Message.print(sender, "${ChatColor.GREEN}Set peace for all towns. Removed $resolved enemy relation(s).")
        SocialSpy.notifyDiplomacy("${sender.name} executed peaceall ($resolved relation(s) removed)")
    }

    private fun manageWarAccessList(sender: CommandSender, args: Array<String>, type: WarAccessListType) {
        when (args.getOrNull(2)?.lowercase() ?: "list") {
            "list" -> printWarAccessEntries(sender, type)
            "add" -> addWarAccessEntry(sender, type, args)
            "remove", "delete", "del" -> removeWarAccessEntry(sender, type, args)
            else -> printWarAccessUsage(sender, type)
        }
    }

    private fun printWarAccessEntries(sender: CommandSender, type: WarAccessListType) {
        val entries = entriesForType(type)
        if (entries.isEmpty()) {
            Message.print(sender, "${ChatColor.GRAY}${type.displayName} is empty")
            return
        }
        Message.print(sender, "[War] ${type.displayName}:")
        for (entry in entries) {
            Message.print(sender, "${ChatColor.GRAY}- ${entry.name} (${entry.uuid})")
        }
    }

    private fun addWarAccessEntry(sender: CommandSender, type: WarAccessListType, args: Array<String>) {
        val target = args.getOrNull(3)
        if (target.isNullOrBlank()) {
            Message.error(sender, "Specify a town name or UUID to add.")
            printWarAccessUsage(sender, type)
            return
        }
        handleWarAccessUpdateResult(sender, type, addEntryToType(type, target), target)
    }

    private fun removeWarAccessEntry(sender: CommandSender, type: WarAccessListType, args: Array<String>) {
        val target = args.getOrNull(3)
        if (target.isNullOrBlank()) {
            Message.error(sender, "Specify a town name or UUID to remove.")
            printWarAccessUsage(sender, type)
            return
        }
        handleWarAccessUpdateResult(sender, type, removeEntryFromType(type, target), target)
    }

    private fun printWarAccessUsage(sender: CommandSender, type: WarAccessListType) {
        Message.print(sender, "Usage: /nodesadmin war ${type.commandName} [list|add <town>|remove <town>]")
    }

    private fun handleWarAccessUpdateResult(sender: CommandSender, type: WarAccessListType, result: WarAccessUpdateResult, target: String) {
        val label = type.displayName
        when (result) {
            WarAccessUpdateResult.ADDED -> Message.print(sender, "$label entry added: $target")
            WarAccessUpdateResult.REMOVED -> Message.print(sender, "$label entry removed: $target")
            WarAccessUpdateResult.ALREADY_PRESENT -> Message.error(sender, "$target is already on the $label")
            WarAccessUpdateResult.NOT_FOUND -> Message.error(sender, "Town not found: $target")
        }
    }

    private fun entriesForType(type: WarAccessListType): Collection<WarAccessEntry> = when (type) {
        WarAccessListType.WHITELIST -> WarAccessListManager.listWhitelist()
        WarAccessListType.BLACKLIST -> WarAccessListManager.listBlacklist()
        WarAccessListType.ANNEX -> WarAccessListManager.listAnnexBlacklist()
    }

    private fun addEntryToType(type: WarAccessListType, target: String): WarAccessUpdateResult = when (type) {
        WarAccessListType.WHITELIST -> WarAccessListManager.addToWhitelist(target)
        WarAccessListType.BLACKLIST -> WarAccessListManager.addToBlacklist(target)
        WarAccessListType.ANNEX -> WarAccessListManager.addToAnnexBlacklist(target)
    }

    private fun removeEntryFromType(type: WarAccessListType, target: String): WarAccessUpdateResult = when (type) {
        WarAccessListType.WHITELIST -> WarAccessListManager.removeFromWhitelist(target)
        WarAccessListType.BLACKLIST -> WarAccessListManager.removeFromBlacklist(target)
        WarAccessListType.ANNEX -> WarAccessListManager.removeFromAnnexBlacklist(target)
    }

    private enum class WarAccessListType(val commandName: String, val displayName: String) {
        WHITELIST("whitelist", "War whitelist"),
        BLACKLIST("blacklist", "War blacklist"),
        ANNEX("annex", "Annex blacklist"),
    }

    private fun parseDurationMillis(args: Array<String>, startIndex: Int): Result<Long> {
        if (args.size <= startIndex) {
            return Result.success(0L)
        }

        var totalMinutes = 0L
        var parsedAny = false

        for (i in startIndex until args.size) {
            val token = args[i].trim().lowercase()
            if (token.isEmpty()) {
                continue
            }

            when {
                token.endsWith("h") -> {
                    val hours = token.removeSuffix("h").toLongOrNull()
                        ?: return Result.failure(Exception("Invalid hours token: $token"))
                    if (hours < 0) {
                        return Result.failure(Exception("War duration cannot be negative"))
                    }
                    totalMinutes += hours * 60L
                    parsedAny = true
                }
                token.endsWith("m") -> {
                    val minutes = token.removeSuffix("m").toLongOrNull()
                        ?: return Result.failure(Exception("Invalid minutes token: $token"))
                    if (minutes < 0) {
                        return Result.failure(Exception("War duration cannot be negative"))
                    }
                    totalMinutes += minutes
                    parsedAny = true
                }
                token.toLongOrNull() != null -> {
                    val minutes = token.toLong()
                    if (minutes < 0) {
                        return Result.failure(Exception("War duration cannot be negative"))
                    }
                    totalMinutes += minutes
                    parsedAny = true
                }
                else -> {
                    return Result.failure(Exception("Invalid duration token: $token"))
                }
            }
        }

        if (!parsedAny) {
            return Result.failure(Exception("Invalid duration. Example: 3h 0m"))
        }

        return Result.success(totalMinutes * 60L * 1000L)
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        return "${hours}h ${minutes}m"
    }

    // =============================================================
    // port management commands
    // - create/delete ports
    // - edit port properties
    // =============================================================

    // route command to further subcommands
    private fun managePort(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printPortHelp(sender)
        } else {
            when (args[1].lowercase()) {
                "list" -> listPortApplications(sender)
                "create" -> createPort(sender, args)
                "delete" -> deletePort(sender, args)
                "addgroup" -> addPortGroup(sender, args)
                "removegroup" -> removePortGroup(sender, args)
                "accept" -> acceptPortApplication(sender, args)
                "deny" -> denyPortApplication(sender, args)
                "tier" -> setPortTierFromApplication(sender, args)
                "tpn" -> teleportToPortApplication(sender, args)
                else -> printPortHelp(sender)
            }
        }
    }

    private fun printPortHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port list${ChatColor.WHITE}: List open player port applications")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port tpn [id]${ChatColor.WHITE}: Teleport to an application")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port accept [id]${ChatColor.WHITE}: Start tier selection for an application")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port deny [id]${ChatColor.WHITE}: Deny an application")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port tier [id] [tier1|tier2|tier3]${ChatColor.WHITE}: Finalize accepted application")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port tier set [port] [tier1|tier2|tier3]${ChatColor.WHITE}: Set existing port tier")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port create [name] [x] [z] [groups] [public]${ChatColor.WHITE}: Create a new port")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port delete [name]${ChatColor.WHITE}: Delete a port")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port addgroup [port] [group]${ChatColor.WHITE}: Add a port to a group")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port removegroup [port] [group]${ChatColor.WHITE}: Remove a port from a group")
    }

    private fun listPortApplications(sender: CommandSender) {
        val applications = Nodes.portApplications.values
        if (applications.isEmpty()) {
            Message.print(sender, "No open port applications")
            return
        }

        Message.print(sender, "${ChatColor.BOLD}Open port applications:")
        for (app in applications) {
            val loc = app.applyLocation
            Message.print(
                sender,
                "#${app.id} ${app.requesterName} -> ${app.requestedPortName} @ (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})",
            )

            if (sender is Player) {
                val actionRow = Component.text()
                    .append(
                        Component.text("[${tr(sender, "TPN")}] ")
                            .color(NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/nda port tpn ${app.id}")),
                    )
                    .append(
                        Component.text("[${tr(sender, "ACCEPT")}] ")
                            .color(NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/nda port accept ${app.id}")),
                    )
                    .append(
                        Component.text("[${tr(sender, "DENY")}]")
                            .color(NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand("/nda port deny ${app.id}")),
                    )
                    .build()
                Message.component(sender, actionRow)
            }
        }
    }

    private fun teleportToPortApplication(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player
        if (player == null) {
            Message.error(sender, "Only players can use this command")
            return
        }
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin port tpn [id]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid id")
            return
        }

        val app = Nodes.getPortApplication(id)
        if (app == null) {
            Message.error(sender, "Port application #$id not found")
            return
        }

        Message.print(sender, "Teleported to port application #$id")
    }

    private fun acceptPortApplication(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin port accept [id]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid id")
            return
        }

        val app = Nodes.getPortApplication(id)
        if (app == null) {
            Message.error(sender, "Port application #$id not found")
            return
        }

        pendingPortTierSelection.add(id)
        printTierSelection(sender, app)
    }

    private fun denyPortApplication(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin port deny [id]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid id")
            return
        }

        val app = Nodes.clearPortApplication(id)
        if (app == null) {
            Message.error(sender, "Port application #$id not found")
            return
        }

        pendingPortTierSelection.remove(id)
        Message.print(sender, "${ChatColor.DARK_RED}Denied port application #$id from ${app.requesterName}")

        Bukkit.getPlayer(app.requesterUuid)?.let { player ->
            Message.error(player, "Your port request #$id (${app.requestedPortName}) was denied")
        }
    }

    private fun setPortTierFromApplication(sender: CommandSender, args: Array<String>) {
        if (args.size >= 3 && args[2].equals("set", ignoreCase = true)) {
            setExistingPortTier(sender, args)
            return
        }

        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin port tier [id] [tier1|tier2|tier3]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid id")
            return
        }

        if (!pendingPortTierSelection.contains(id)) {
            Message.error(sender, "Application #$id is not waiting for tier selection. Use /nda port accept $id first.")
            return
        }

        val app = Nodes.getPortApplication(id)
        if (app == null) {
            pendingPortTierSelection.remove(id)
            Message.error(sender, "Port application #$id not found")
            return
        }

        val tier = parsePortTier(args[3])
        if (tier == null) {
            Message.error(sender, "Invalid tier. Use tier1, tier2 or tier3")
            return
        }

        createPortFromApplicationAndTier(sender, app, tier)
        Nodes.clearPortApplication(id)
        pendingPortTierSelection.remove(id)
    }

    private fun setExistingPortTier(sender: CommandSender, args: Array<String>) {
        if (args.size < 5) {
            Message.error(sender, "Usage: /nodesadmin port tier set [port] [tier1|tier2|tier3]")
            return
        }

        val portName = args[3].lowercase()
        val port = Nodes.ports[portName]
        if (port == null) {
            Message.error(sender, "Port \"$portName\" does not exist")
            return
        }

        val tier = parsePortTier(args[4])
        if (tier == null) {
            Message.error(sender, "Invalid tier. Use tier1, tier2 or tier3")
            return
        }

        if (port.tier == tier) {
            Message.print(sender, "Port \"${port.name}\" is already ${tier.name.lowercase()}")
            return
        }

        Nodes.setPortTier(port, tier)
        Message.print(sender, "${ChatColor.GREEN}Set port \"${port.name}\" tier to ${tier.name.lowercase()}")
        SocialSpy.notifyDiplomacy("${sender.name} set port ${port.name} tier to ${tier.name.lowercase()}")
    }

    private fun printTierSelection(sender: CommandSender, app: PortApplication) {
        Message.print(sender, "Choose tier for application #${app.id} (${app.requestedPortName}):")

        if (sender is Player) {
            val row = Component.text()
                .append(
                    Component.text("[${tr(sender, "TIER1")}]")
                        .color(NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/nda port tier ${app.id} tier1")),
                )
                .append(Component.text(" "))
                .append(
                    Component.text("[${tr(sender, "TIER2")}]")
                        .color(NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/nda port tier ${app.id} tier2")),
                )
                .append(Component.text(" "))
                .append(
                    Component.text("[${tr(sender, "TIER3")}]")
                        .color(NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/nda port tier ${app.id} tier3")),
                )
                .build()
            Message.component(sender, row)
        } else {
            Message.print(sender, "/nda port tier ${app.id} tier1")
            Message.print(sender, "/nda port tier ${app.id} tier2")
            Message.print(sender, "/nda port tier ${app.id} tier3")
        }
    }

    private fun createPortFromApplicationAndTier(
        sender: CommandSender,
        app: PortApplication,
        tier: PortTier,
    ) {
        val location = app.applyLocation
        val x = location.blockX
        val z = location.blockZ

        Nodes.createPort(
            app.requestedPortName.lowercase(),
            x,
            z,
            hashSetOf(),
            false,
            tier,
        ).getOrElse { err ->
            Message.error(sender, "Failed to create port from application #${app.id}: ${err.message}")
            return
        }

        buildAcceptedPortTower(location)

        Message.print(
            sender,
            "${ChatColor.GREEN}Accepted port application #${app.id}: created \"${app.requestedPortName}\" at ($x, $z) with ${tier.name.lowercase()}",
        )

        Bukkit.getPlayer(app.requesterUuid)?.let { player ->
            Message.print(player, "${ChatColor.GREEN}Your port request #${app.id} was accepted as ${tier.name.lowercase()}")
        }
    }

    private fun buildAcceptedPortTower(baseLocation: Location) {
        val world = baseLocation.world ?: return
        val x = baseLocation.blockX
        val y = baseLocation.blockY
        val z = baseLocation.blockZ

        for (towerY in (y - 10)..(y + 5)) {
            world.getBlockAt(x, towerY, z).type = Material.BEDROCK
        }
        world.getBlockAt(x, y + 6, z).type = Material.BEACON
    }

    private fun parsePortTier(raw: String): PortTier? = when (raw.lowercase()) {
        "tier1", "1", "t1" -> PortTier.TIER1
        "tier2", "2", "t2" -> PortTier.TIER2
        "tier3", "3", "t3" -> PortTier.TIER3
        else -> null
    }

    private fun manageOutpost(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printOutpostHelp(sender)
            return
        }

        when (args[1].lowercase()) {
            "list" -> listOutpostApplications(sender)
            "tpn" -> teleportToOutpostApplication(sender, args)
            "accept" -> acceptOutpostApplication(sender, args)
            "deny" -> denyOutpostApplication(sender, args)
            "tier" -> setOutpostTierFromApplication(sender, args)
            else -> printOutpostHelp(sender)
        }
    }

    private fun printOutpostHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin outpost list${ChatColor.WHITE}: List open player outpost applications")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin outpost tpn [id]${ChatColor.WHITE}: Teleport to an application")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin outpost accept [id]${ChatColor.WHITE}: Start tier selection for an application")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin outpost deny [id]${ChatColor.WHITE}: Deny an application")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin outpost tier [id] [tier1|tier2|tier3]${ChatColor.WHITE}: Finalize accepted application")
    }

    private fun listOutpostApplications(sender: CommandSender) {
        val applications = Nodes.outpostApplications.values
        if (applications.isEmpty()) {
            Message.print(sender, "No open outpost applications")
            return
        }

        Message.print(sender, "${ChatColor.BOLD}Open outpost applications:")
        for (app in applications) {
            val loc = app.applyLocation
            Message.print(
                sender,
                "#${app.id} ${app.requesterName} (${app.townName}) -> ${app.requestedOutpostName} @ (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})",
            )

            if (sender is Player) {
                val actionRow = Component.text()
                    .append(
                        Component.text("[${tr(sender, "TPN")}] ")
                            .color(NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/nda outpost tpn ${app.id}")),
                    )
                    .append(
                        Component.text("[${tr(sender, "ACCEPT")}] ")
                            .color(NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/nda outpost accept ${app.id}")),
                    )
                    .append(
                        Component.text("[${tr(sender, "DENY")}]")
                            .color(NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand("/nda outpost deny ${app.id}")),
                    )
                    .build()
                Message.component(sender, actionRow)
            }
        }
    }

    private fun teleportToOutpostApplication(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player
        if (player == null) {
            Message.error(sender, "Only players can use this command")
            return
        }
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin outpost tpn [id]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid id")
            return
        }

        val app = Nodes.getOutpostApplication(id)
        if (app == null) {
            Message.error(sender, "Outpost application #$id not found")
            return
        }

        Message.print(sender, "${ChatColor.GREEN}Teleported to outpost application #$id")
    }

    private fun acceptOutpostApplication(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin outpost accept [id]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid id")
            return
        }

        val app = Nodes.getOutpostApplication(id)
        if (app == null) {
            Message.error(sender, "Outpost application #$id not found")
            return
        }

        pendingOutpostTierSelection.add(id)
        printOutpostTierSelection(sender, app)
    }

    private fun denyOutpostApplication(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin outpost deny [id]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid id")
            return
        }

        val app = Nodes.clearOutpostApplication(id)
        if (app == null) {
            Message.error(sender, "Outpost application #$id not found")
            return
        }

        pendingOutpostTierSelection.remove(id)
        Message.print(sender, "${ChatColor.YELLOW}Denied outpost application #$id")
        Bukkit.getPlayer(app.requesterUuid)?.let { player ->
            Message.print(player, "${ChatColor.RED}Your outpost request #$id was denied")
        }
    }

    private fun setOutpostTierFromApplication(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin outpost tier [id] [tier1|tier2|tier3]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid id")
            return
        }

        if (!pendingOutpostTierSelection.contains(id)) {
            Message.error(sender, "Application #$id is not waiting for tier selection. Use /nda outpost accept $id first.")
            return
        }

        val app = Nodes.getOutpostApplication(id)
        if (app == null) {
            pendingOutpostTierSelection.remove(id)
            Message.error(sender, "Outpost application #$id not found")
            return
        }

        val tier = parsePortTier(args[3])
        if (tier == null) {
            Message.error(sender, "Invalid tier. Use tier1, tier2 or tier3")
            return
        }

        createOutpostFromApplicationAndTier(sender, app, tier)
        Nodes.clearOutpostApplication(id)
        pendingOutpostTierSelection.remove(id)
    }

    private fun printOutpostTierSelection(sender: CommandSender, app: OutpostApplication) {
        Message.print(sender, "Choose tier for outpost application #${app.id} (${app.requestedOutpostName}):")

        if (sender is Player) {
            val row = Component.text()
                .append(
                    Component.text("[${tr(sender, "TIER1")}]")
                        .color(NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/nda outpost tier ${app.id} tier1")),
                )
                .append(Component.text(" "))
                .append(
                    Component.text("[${tr(sender, "TIER2")}]")
                        .color(NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/nda outpost tier ${app.id} tier2")),
                )
                .append(Component.text(" "))
                .append(
                    Component.text("[${tr(sender, "TIER3")}]")
                        .color(NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/nda outpost tier ${app.id} tier3")),
                )
                .build()
            Message.component(sender, row)
        } else {
            Message.print(sender, "/nda outpost tier ${app.id} tier1")
            Message.print(sender, "/nda outpost tier ${app.id} tier2")
            Message.print(sender, "/nda outpost tier ${app.id} tier3")
        }
    }

    private fun createOutpostFromApplicationAndTier(
        sender: CommandSender,
        app: OutpostApplication,
        tier: PortTier,
    ) {
        val town = Nodes.getTownFromName(app.townName)
        if (town == null) {
            Message.error(sender, "Town \"${app.townName}\" no longer exists")
            return
        }

        val territory = Nodes.getTerritoryFromId(app.territoryId)
        if (territory == null) {
            Message.error(sender, "Territory ${app.territoryId} no longer exists")
            return
        }

        if (territory.town != town) {
            Message.error(sender, "Territory ${app.territoryId} is no longer owned by ${town.name}")
            return
        }

        val created = Nodes.createTownOutpost(
            town = town,
            name = app.requestedOutpostName,
            territory = territory,
            tier = tier,
        )
        if (!created) {
            Message.error(sender, "Failed to create outpost from application #${app.id}")
            return
        }

        Message.print(
            sender,
            "${ChatColor.GREEN}Accepted outpost application #${app.id}: created \"${app.requestedOutpostName}\" for ${town.name} with ${tier.name.lowercase()}",
        )

        Bukkit.getPlayer(app.requesterUuid)?.let { player ->
            Message.print(player, "${ChatColor.GREEN}Your outpost request #${app.id} was accepted as ${tier.name.lowercase()}")
        }
    }

    /**
     * @command /nodesadmin port create [name] [x] [z]
     * Creates a port from name and a set of coordinates.
     */
    private fun createPort(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin port create [name] [x] [z]")
            Message.error(sender, "Example: /nodesadmin port create panama 100 200")
            return
        }

        val portName = args[2].lowercase()

        // parse coordinates
        val x = args[3].toIntOrNull()
        val z = args[4].toIntOrNull()
        if (x === null || z === null) {
            Message.error(sender, "Invalid coordinates: x and z must be integers")
            return
        }

        // create port with default values
        Nodes.createPort(
            portName,
            x,
            z,
            hashSetOf(),
            false,
        ).getOrElse({ err ->
            Message.error(sender, "Failed to create port: ${err.message}")
            return
        })

        Message.print(sender, "${ChatColor.GREEN}Created port \"${portName}\" at ($x, $z)")
    }

    /**
     * @command /nodesadmin port delete [name]
     * Deletes a port.
     */
    private fun deletePort(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin port delete [name]")
            Message.error(sender, "Example: /nodesadmin port delete panama")
            return
        }

        val portName = args[2].lowercase()
        val port = Nodes.getPortFromName(portName)

        if (port === null) {
            Message.error(sender, "Port \"${portName}\" does not exist")
            return
        }

        // delete the port
        Nodes.destroyPort(port)

        Message.print(sender, "Port \"${portName}\" has been deleted")
    }

    /**
     * @command /nodesadmin port addgroup [port] [group]
     * Adds a port to a group.
     */
    private fun addPortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin port addgroup [port] [group]")
            Message.error(sender, "Example: /nodesadmin port addgroup panama atlantic")
            return
        }

        val portName = args[2].lowercase()
        val port = Nodes.getPortFromName(portName)

        val groupName = args[3].lowercase()
        val group = Nodes.getPortGroupFromName(groupName)

        // check port exists
        if (port === null) {
            Message.error(sender, "Port \"${portName}\" does not exist")
            return
        }

        // check group exists
        if (group === null) {
            Message.error(sender, "Group \"${groupName}\" does not exist")
            return
        }

        // check port isnt already in group
        if (port.groups.contains(group)) {
            Message.error(sender, "Port is already part of ths group")
            return
        }

        addPortToGroup(port, group)

        Message.print(sender, "Port \"${portName}\" has been added to $groupName group")
    }

    /**
     * @command /nodesadmin port removegroup [port] [group]
     * Removes a port from a group
     */
    private fun removePortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin port removegroup [port] [group]")
            Message.error(sender, "Example: /nodesadmin port removegroup panama atlantic")
            return
        }

        val portName = args[2].lowercase()
        val port = Nodes.getPortFromName(portName)

        val groupName = args[3].lowercase()
        val group = Nodes.getPortGroupFromName(groupName)

        // check port exists
        if (port === null) {
            Message.error(sender, "Port \"${portName}\" does not exist")
            return
        }

        // check group exists
        if (group === null) {
            Message.error(sender, "Group \"${groupName}\" does not exist")
            return
        }

        // check port is in group
        if (!port.groups.contains(group)) {
            Message.error(sender, "Port \"$portName\" is not part of \"$groupName\" group")
            return
        }

        removePortFromGroup(port, group)

        Message.print(sender, "Port \"${portName}\" has been removed from $groupName group")
    }

    // =============================================================
    // port group management commands
    // - create/delete port groups
    // =============================================================

    // route command to further subcommands
    private fun managePortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printPortGroupHelp(sender)
        } else {
            when (args[1].lowercase()) {
                "create" -> createPortGroup(sender, args)
                "delete" -> deletePortGroup(sender, args)
                else -> printPortGroupHelp(sender)
            }
        }
    }

    private fun printPortGroupHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin portgroup create [name]${ChatColor.WHITE}: Create a new port group")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin portgroup delete [name]${ChatColor.WHITE}: Delete a port group")
    }

    /**
     * @command /nodesadmin portgroup create [name]
     * Creates a port group.
     */
    private fun createPortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin portgroup create [name]")
            Message.error(sender, "Example: /nodesadmin portgroup create atlantic")
            return
        }

        val portGroupName = args[2].lowercase()

        // create port group
        Nodes.createPortGroup(
            portGroupName,
        ).getOrElse({ err ->
            Message.error(sender, "Failed to create group: ${err.message}")
            return
        })

        Message.print(sender, "${ChatColor.GREEN}Created group \"${portGroupName}\"")
    }

    /**
     * @command /nodesadmin portgroup delete [name]
     * Deletes a port group.
     */
    private fun deletePortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin portgroup delete [name]")
            Message.error(sender, "Example: /nodesadmin portgroup delete atlantic")
            return
        }

        val portGroupName = args[2].lowercase()
        val portGroup = Nodes.getPortGroupFromName(portGroupName)

        if (portGroup === null) {
            Message.error(sender, "Port group \"${portGroupName}\" does not exist")
            return
        }

        // delete the port group
        Nodes.destroyPortGroup(portGroup)

        Message.print(sender, "${ChatColor.GREEN}Deleted group \"${portGroupName}\"")
    }

    // =============================================================
    // resident management commands
    // - change town create cooldown
    // =============================================================

    // route command to further subcommands
    private fun manageResident(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printTownHelp(sender)
        } else {
            // route subcommand function
            when (args[1].lowercase()) {
                "help" -> printResidentHelp(sender)
                "towncooldown" -> setResidentTownCooldown(sender, args)
                else -> {
                    printResidentHelp(sender)
                }
            }
        }
    }

    private fun printResidentHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin town management:")
        Message.print(sender, "/nodesadmin resident towncooldown${ChatColor.WHITE}: Change resident town cooldown")
        Message.print(sender, "Run a command with no args to see usage.")
    }

    private fun setResidentTownCooldown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin resident towncooldown [player] [number]")
            return
        }

        val residentName = args[2]
        val resident = Nodes.getResidentFromName(residentName)
        if (resident === null) {
            Message.error(sender, "Resident \"${residentName}\" does not exist")
            return
        }

        val cooldown: Long = Math.max(0, args[3].toLong())

        Nodes.setResidentTownCreateCooldown(resident, cooldown)
    }

    // =============================================================
    // town management commands
    // - create/delete towns
    // - toggle town properties, players
    // - modify town territories, claims bonus, ...
    // =============================================================

    // route command to further subcommands
    private fun manageTown(sender: CommandSender, args: Array<String>, isAdmin: Boolean, isMod: Boolean) {
        if (args.size < 2) {
            printTownHelp(sender)
            return
        }

        val sub = args[1].lowercase()
        if (!isAdmin) {
            if (isMod && handleModTownSubcommand(sender, args, sub)) {
                return
            }
            Message.error(sender, "You do not have permission to use /nodesadmin town $sub")
            return
        }

        when (sub) {
            "create" -> createTown(sender, args)
            "delete" -> deleteTown(sender, args)
            "addplayer" -> addPlayerToTown(sender, args)
            "addallplayer" -> addAllPlayersToTown(sender, args)
            "removeplayer" -> removePlayerFromTown(sender, args)
            "addterritory" -> addTerritoryToTown(sender, args)
            "removeterritory" -> removeTerritoryFromTown(sender, args)
            "captureterritory" -> captureTerritory(sender, args)
            "releaseterritory" -> releaseTerritory(sender, args)
            "claimsbonus" -> setClaimsBonus(sender, args)
            "claimsannex" -> setClaimsAnnexed(sender, args)
            "claimspenalty" -> setClaimsPenalty(sender, args)
            "setspawn" -> townSetSpawn(sender, args)
            "spawn" -> townSpawn(sender, args)
            "addofficer" -> addTownOfficer(sender, args)
            "removeofficer" -> removeTownOfficer(sender, args)
            "addgeneral" -> addTownGeneral(sender, args)
            "removegeneral" -> removeTownGeneral(sender, args)
            "leader" -> setTownLeader(sender, args)
            "removeleader" -> removeTownLeader(sender, args)
            "open" -> setTownOpen(sender, args)
            "income" -> townIncome(sender, args)
            "incomeadd" -> townIncomeAdd(sender, args)
            "incomeremove" -> townIncomeRemove(sender, args)
            "sethome" -> setTownHome(sender, args)
            "sethomecooldown" -> setTownMoveHomeCooldown(sender, args)
            "addoutpost" -> addOutpostToTown(sender, args)
            "removeoutpost" -> removeOutpostFromTown(sender, args)
            "addoutpostcredits" -> addOutpostCreditsToTown(sender, args)
            "color" -> setTownColor(sender, args)
            "rename" -> renameTown(sender, args)
            "defaulttownspawns" -> defaultTownSpawns(sender, args)
            "fixspawns" -> fixTownSpawns(sender, args)
            else -> {
                printTownHelp(sender)
            }
        }
    }

    private fun handleModTownSubcommand(sender: CommandSender, args: Array<String>, sub: String): Boolean = when (sub) {
        "help" -> {
            printTownHelp(sender)
            true
        }
        "addplayer" -> {
            addPlayerToTown(sender, args)
            true
        }
        "addallplayer" -> {
            addAllPlayersToTown(sender, args)
            true
        }
        "addterritory" -> {
            addTerritoryToTown(sender, args)
            true
        }
        "removeterritory" -> {
            removeTerritoryFromTown(sender, args)
            true
        }
        "addofficer" -> {
            addTownOfficer(sender, args)
            true
        }
        "removeofficer" -> {
            removeTownOfficer(sender, args)
            true
        }
        "addgeneral" -> {
            addTownGeneral(sender, args)
            true
        }
        "removegeneral" -> {
            removeTownGeneral(sender, args)
            true
        }
        "leader" -> {
            setTownLeader(sender, args)
            true
        }
        "removeleader" -> {
            removeTownLeader(sender, args)
            true
        }
        else -> false
    }

    private fun printTownHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin town management:")
        Message.print(sender, "/nodesadmin town create${ChatColor.WHITE}: Create a new town")
        Message.print(sender, "/nodesadmin town delete${ChatColor.WHITE}: Delete existing town")
        Message.print(sender, "/nodesadmin town addplayer${ChatColor.WHITE}: Add players to town")
        Message.print(sender, "/nodesadmin town addallplayer${ChatColor.WHITE}: Add all residents without a town")
        Message.print(sender, "/nodesadmin town removeplayer${ChatColor.WHITE}: Remove players from town")
        Message.print(sender, "/nodesadmin town addterritory${ChatColor.WHITE}: Add territories to town")
        Message.print(sender, "/nodesadmin town removeterritory${ChatColor.WHITE}: Remove territories from town")
        Message.print(sender, "/nodesadmin town captureterritory${ChatColor.WHITE}: Add captured territories to town")
        Message.print(sender, "/nodesadmin town releaseterritory${ChatColor.WHITE}: Release captured territories")
        Message.print(sender, "/nodesadmin town claimsbonus${ChatColor.WHITE}: Set town bonus claims")
        Message.print(sender, "/nodesadmin town claimsannex${ChatColor.WHITE}: Set town annexed claims penalty")
        Message.print(sender, "/nodesadmin town setspawn${ChatColor.WHITE}: Set town's spawn to location")
        Message.print(sender, "/nodesadmin town spawn${ChatColor.WHITE}: Go to town's spawn")
        Message.print(sender, "/nodesadmin town addofficer${ChatColor.WHITE}: Add officer to town")
        Message.print(sender, "/nodesadmin town removeofficer${ChatColor.WHITE}: Remove officer from town")
        Message.print(sender, "/nodesadmin town addgeneral${ChatColor.WHITE}: Promote general for the town")
        Message.print(sender, "/nodesadmin town removegeneral${ChatColor.WHITE}: Demote a general")
        Message.print(sender, "/nodesadmin town leader${ChatColor.WHITE}: Set town leader to player")
        Message.print(sender, "/nodesadmin town open${ChatColor.WHITE}: Toggle town is open to join")
        Message.print(sender, "/nodesadmin town income${ChatColor.WHITE}: View a town's income inventory")
        Message.print(sender, "/nodesadmin town addoutpost${ChatColor.WHITE}: Add an outpost to a town")
        Message.print(sender, "/nodesadmin town removeoutpost${ChatColor.WHITE}: Remove an outpost from a town")
        Message.print(sender, "/nodesadmin town addoutpostcredits${ChatColor.WHITE}: Add outpost credits to a town")
        Message.print(sender, "/nodesadmin town color${ChatColor.WHITE}: Set town color on map")
        Message.print(sender, "/nodesadmin town rename${ChatColor.WHITE}: Rename a town")
        Message.print(sender, "/nodesadmin town fixspawns${ChatColor.WHITE}: Set all town spawns to home core chunk at Y=70")
        Message.print(sender, "Run a command with no args to see usage.")
    }

    private fun printTerritoryHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Territory subcommands:")
        Message.print(sender, "/nodesadmin territory tp${ChatColor.WHITE}: Teleport to a territory by id")
        Message.print(sender, "/nodesadmin territory addresource${ChatColor.WHITE}: Add resource nodes to territory")
        Message.print(sender, "/nodesadmin territory removeresource${ChatColor.WHITE}: Remove resource nodes from territory")
        Message.print(sender, "/nodesadmin territory addtype${ChatColor.WHITE}: Add node types to territory")
        Message.print(sender, "/nodesadmin territory removetype${ChatColor.WHITE}: Remove node types from territory")
        Message.print(sender, "/nodesadmin territory addcore${ChatColor.WHITE}: Add core node type to territory")
        Message.print(sender, "/nodesadmin territory removecore${ChatColor.WHITE}: Remove core node type from territory")
        Message.print(sender, "Run a command with no args to see usage.")
    }

    private fun manageResourceShortcut(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin ressource [add|remove] [nodeId] [resource1] [resource2] ...")
            return
        }

        val action = args[1].lowercase()
        val mappedSubcommand = when (action) {
            "add" -> "addresource"
            "remove" -> "removeresource"
            else -> {
                Message.error(sender, "Usage: /nodesadmin ressource [add|remove] [nodeId] [resource1] [resource2] ...")
                return
            }
        }

        val delegatedArgs = arrayOf("territory", mappedSubcommand) + args.copyOfRange(2, args.size)
        manageTerritory(sender, delegatedArgs)
    }

    private fun manageWarzone(sender: CommandSender, args: Array<String>) {
        if (sender !is Player) {
            Message.error(sender, "Only players can open the warzone manager")
            return
        }

        if (args.size < 2) {
            WarzoneGui.openMain(sender)
            return
        }

        val name = args.copyOfRange(1, args.size).joinToString(" ").trim()
        if (name.equals("help", ignoreCase = true)) {
            Message.print(sender, "/nodesadmin warzone${ChatColor.WHITE}: Open warzone manager")
            Message.print(sender, "/nodesadmin warzone [name]${ChatColor.WHITE}: Open a specific warzone")
            return
        }

        val warzone = WarzoneManager.getWarzone(name)
        if (warzone == null) {
            Message.error(sender, "Unknown warzone \"$name\".")
            return
        }

        WarzoneGui.openDetail(sender, warzone)
    }

    /**
     * @command /nodesadmin territory [subcommand]
     * Modify world.json directly (and dynmap copy).
     */
    private fun manageTerritory(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printTerritoryHelp(sender)
            return
        }

        val sub = args[1].lowercase()
        if (sub == "help") {
            printTerritoryHelp(sender)
            return
        }

        if (sub == "tp") {
            teleportToTerritory(sender, args)
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin territory $sub [id] [name1] [name2] ...")
            return
        }

        val parsedId = args[2].toIntOrNull()
        val id = if (parsedId == null && (sub == "addcore" || sub == "removecore")) {
            val town = Nodes.getTownFromName(args[2])
            if (town != null) {
                town.home.toInt()
            } else {
                null
            }
        } else {
            parsedId
        }
        if (id == null) {
            if (sub == "addcore" || sub == "removecore") {
                Message.error(sender, "Invalid territory id or town name: ${args[2]}")
            } else {
                Message.error(sender, "Invalid territory id: ${args[2]}")
            }
            return
        }

        when (sub) {
            "addresource",
            "removeresource",
            -> {
                if (args.size < 4) {
                    Message.error(sender, "Usage: /nodesadmin territory $sub [id] [resource1] [resource2] ...")
                    return
                }
                val resources = args.copyOfRange(3, args.size).toList()
                val missing = resources.filter { !Nodes.resourceNodes.containsKey(it) }
                if (missing.isNotEmpty()) {
                    Message.error(sender, "Unknown resource node(s): ${missing.joinToString(", ")}")
                    return
                }
                val add = sub == "addresource"
                if (updateTerritoryWorldJson(sender, id, { terr ->
                        updateStringArray(terr, "nodes", resources, add)
                    })
                ) {
                    Message.print(sender, "Updated territory $id resources in world.json")
                }
            }
            "addtype",
            "removetype",
            -> {
                if (args.size < 4) {
                    Message.error(sender, "Usage: /nodesadmin territory $sub [id] [type1] [type2] ...")
                    return
                }
                val types = args.copyOfRange(3, args.size).toList()
                val missing = types.filter { !Config.nodeTypes.containsKey(it) }
                if (missing.isNotEmpty()) {
                    Message.error(sender, "Unknown node type(s): ${missing.joinToString(", ")}")
                    return
                }
                val add = sub == "addtype"
                if (updateTerritoryWorldJson(sender, id, { terr ->
                        updateStringArray(terr, "node_types", types, add)
                        terr.remove("node_type")
                    })
                ) {
                    Message.print(sender, "Updated territory $id node types in world.json")
                }
            }
            "addcore",
            "removecore",
            -> {
                val add = sub == "addcore"
                val coreType = "core"
                if (!Config.nodeTypes.containsKey(coreType)) {
                    Message.error(sender, "Node type \"core\" is not defined in ${Config.nodeTypeFile}")
                    return
                }
                if (updateTerritoryWorldJson(sender, id, { terr ->
                        updateStringArray(terr, "node_types", listOf(coreType), add)
                        terr.remove("node_type")
                    })
                ) {
                    Message.print(sender, "Updated territory $id core type in world.json")
                }
            }
            else -> {
                Message.error(sender, "Unknown territory subcommand: $sub")
            }
        }
    }

    private fun teleportToTerritory(sender: CommandSender, args: Array<String>) {
        if (sender !is Player) {
            Message.error(sender, "Only players can teleport to a territory")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin territory tp [id]")
            return
        }

        val id = args[2].toIntOrNull()
        if (id == null) {
            Message.error(sender, "Invalid territory id: ${args[2]}")
            return
        }

        val territory = Nodes.getTerritoryFromId(TerritoryId(id))
        if (territory == null) {
            Message.error(sender, "Territory id $id does not exist")
            return
        }

        val location = Nodes.getDefaultSpawnLocation(territory)
        Message.print(sender, "Teleported to territory ${territory.id} (${territory.name})")
    }

    private fun updateTerritoryWorldJson(
        sender: CommandSender,
        territoryId: Int,
        update: (JsonObject) -> Unit,
    ): Boolean {
        if (!Files.exists(Config.pathWorld)) {
            Message.error(sender, "world.json not found at ${Config.pathWorld}")
            return false
        }

        val gson = GsonBuilder().disableHtmlEscaping().create()
        val root = JsonParser.parseReader(Files.newBufferedReader(Config.pathWorld)).asJsonObject
        val territories = root.getAsJsonObject("territories")
        if (territories == null) {
            Message.error(sender, "world.json missing territories section")
            return false
        }

        val terr = territories.getAsJsonObject(territoryId.toString())
        if (terr == null) {
            Message.error(sender, "Territory id $territoryId does not exist in world.json")
            return false
        }

        update(terr)

        Files.writeString(Config.pathWorld, gson.toJson(root))

        // write dynmap copy with node type definitions injected for display
        if (Files.exists(Nodes.DYNMAP_PATH_WORLD)) {
            val dynmapRoot = JsonParser.parseString(gson.toJson(root)).asJsonObject
            injectNodeTypesIntoWorldJson(dynmapRoot)
            Files.writeString(Nodes.DYNMAP_PATH_WORLD, gson.toJson(dynmapRoot))
        }

        return true
    }

    private fun updateStringArray(
        terr: JsonObject,
        key: String,
        values: List<String>,
        add: Boolean,
    ) {
        val array = terr.getAsJsonArray(key) ?: JsonArray()
        val current = array.mapNotNull { it.asString }.toMutableList()
        if (add) {
            values.forEach { if (!current.contains(it)) current.add(it) }
        } else {
            current.removeAll(values.toSet())
        }
        val newArray = JsonArray()
        current.forEach { newArray.add(it) }
        terr.add(key, newArray)
    }

    private fun injectNodeTypesIntoWorldJson(root: JsonObject) {
        val nodes = root.getAsJsonObject("nodes") ?: JsonObject().also { root.add("nodes", it) }
        for ((name, node) in Config.nodeTypes) {
            if (nodes.has(name)) {
                continue
            }
            val nodeJson = JsonObject()
            node.icon?.let { nodeJson.addProperty("icon", it) }
            nodeJson.addProperty("priority", node.priority)
            val cost = JsonObject()
            cost.addProperty("constant", 0)
            cost.addProperty("scale", 1.0)
            nodeJson.add("cost", cost)
            nodes.add(name, nodeJson)
        }
    }

    /**
     * @command /nodesadmin town create [name] [id1] [id2] ...
     * Creates a town from name and list of territory ids.
     * The first id is required and becomes the home territory.
     * This town is created without any residents or leader.
     */
    private fun createTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town create [name] [id1] [id2] ...")
            Message.error(sender, "Each id is a territory id (first id required)")
            return
        }

        // get town name
        if (!stringInputIsValid(args[2])) {
            Message.error(sender, "Invalid town name")
            return
        }
        val name = sanitizeString(args[2])

        // map ids to territories
        val territories: MutableList<Territory> = mutableListOf()
        for (i in 3 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null || terr.town != null) {
                Message.error(sender, "Invalid territory id=$id: either does not exist or already has town")
                return
            } else {
                territories.add(terr)
            }
        }

        // first territory is new town home
        val town = Nodes.createTown(name, territories[0], null).getOrElse({ err ->
            Message.error(sender, "Failed to create town: ${err.message}")
            return
        })

        // add the other territories
        for (i in 1 until territories.size) {
            Nodes.addTerritoryToTown(town, territories[i])
        }

        Message.print(sender, "Created town \"${name}\" with ${territories.size} territories")
    }

    /**
     * @command /nodesadmin town delete [name]
     * Deletes town with given name.
     */
    private fun deleteTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: \"/nodesadmin town delete [name]")
            return
        }

        val name = args[2]
        val town = Nodes.towns.get(name)
        if (town != null) {
            Nodes.destroyTown(town)
            Message.print(sender, "Town \"${name}\" has been deleted")
        } else {
            Message.error(sender, "Town \"${name}\" does not exist")
        }
    }

    private fun claimTerritory(player: Player?) {
        if (player == null) {
            return
        }

        // get town from player
        val resident = Nodes.getResident(player)
        val town = resident?.town
        if (town == null) {
            Message.error(player, "Cannot claim without being in a town")
            return
        }

        if (resident !== town.leader && !town.officers.contains(resident)) {
            Message.error(player, "You are not a town leader or officer")
            return
        }

        // get territory from chunk and run claim process
        val loc = player.getLocation()
        val territory = Nodes.getTerritoryFromBlock(loc.x.toInt(), loc.z.toInt())
        if (territory == null) {
            Message.error(player, "This chunk has no territory")
            return
        }

        val result = Nodes.claimTerritory(town, territory)
        if (result.isSuccess) {
            Message.print(player, "Territory(id=${territory.id}) claimed")
        } else {
            when (result.exceptionOrNull()) {
                ErrorTooManyClaims -> Message.error(player, "Not enough claim power")
                ErrorTerritoryNotConnected -> Message.error(player, "Territory must neighbor existing claims")
                ErrorTerritoryHasClaim -> Message.error(player, "Territory is already claimed by a town")
            }
        }
    }

    /**
     * @command /nodesadmin town addplayer [name] [player1] [player2] ...
     * Add list of player names [player] to town from [name].
     */
    private fun addPlayerToTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town addplayer [name] [player1] [player2] ...")
            Message.error(sender, "First player name is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get residents from player names (auto-create for names that never joined)
        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val inputName = args[i]
            val res = Nodes.getOrCreateResidentByName(inputName)
            if (!res.name.equals(inputName, ignoreCase = true)) {
                Message.print(sender, "Resolved \"$inputName\" as \"${res.name}\"")
            }
            residents.add(res)
        }

        // add residents to town
        for (r in residents) {
            val oldTown = r.town
            if (oldTown != null && oldTown !== town) {
                Nodes.removeResidentFromTown(oldTown, r)
            }
            Nodes.addResidentToTown(town, r)
            Message.print(sender, "Added \"${r.name}\" to town \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town addallplayer [name]
     * Add every resident without a town to [name].
     */
    private fun addAllPlayersToTown(sender: CommandSender, args: Array<String>) {
        if (args.size != 3) {
            Message.error(sender, "Usage: /nodesadmin town addallplayer [name]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns[townName]
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val residentsWithoutTown = Nodes.residents.values
            .filter { it.town == null }
            .sortedBy { it.name.lowercase() }

        if (residentsWithoutTown.isEmpty()) {
            Message.print(sender, "No residents without a town were found")
            return
        }

        var added = 0
        for (resident in residentsWithoutTown) {
            Nodes.addResidentToTown(town, resident)
            added += 1
        }

        Message.print(sender, "Added $added residents without a town to \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town removeplayer [name] [player1] [player2] ...
     * Remove list of player names [player] from town from [name].
     */
    private fun removePlayerFromTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removeplayer [name] [player1] [player2] ...")
            Message.error(sender, "First player name is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get residents from player names, error out if any do not exist
        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res == null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            residents.add(res)
        }

        // add residents to town
        for (r in residents) {
            Nodes.removeResidentFromTown(town, r)
            Message.print(sender, "Removed \"${r.name}\" from town \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town addterritory [name] [id1] [id2] ...
     * Add list of territory from their ids to town from [name].
     * This ignores chunk claiming limits for a town.
     */
    private fun addTerritoryToTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town addterritory [name] [id1] [id2] ...")
            Message.error(sender, "First territory id is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // map ids to territories
        val territories: MutableList<Territory> = mutableListOf()
        for (i in 3 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null || terr.town != null) {
                Message.error(sender, "Invalid territory id=$id: either does not exist or already has town")
                return
            } else {
                territories.add(terr)
            }
        }

        // add territories
        for (terr in territories) {
            Nodes.addTerritoryToTown(town, terr)
        }

        Message.print(sender, "Added ${territories.size} territories to town \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town removeterritory [name] [id1] [id2] ...
     * Remove list of territory from their ids to town from [name].
     */
    private fun removeTerritoryFromTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removeterritory [name] [id1] [id2] ...")
            Message.error(sender, "First territory id is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // map ids to territories
        val territories: MutableList<Territory> = mutableListOf()
        for (i in 3 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null || terr?.town != town) {
                Message.error(sender, "Invalid territory id=$id: does not belong to town")
                return
            } else if (town.home == terr.id) {
                Message.error(sender, "Cannot remove town's home territory id=$id")
                return
            } else {
                territories.add(terr)
            }
        }

        // remove territories
        for (terr in territories) {
            Nodes.unclaimTerritory(town, terr)
        }

        Message.print(sender, "Removed ${territories.size} territories to town \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town captureterritory [town] [id1] [id2] ...
     * Makes town capture list of territory from their ids
     */
    private fun captureTerritory(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town captureterritory [name] [id1] [id2] ...")
            Message.error(sender, "First territory id is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // map ids to territories
        val territories: MutableList<Territory> = mutableListOf()
        for (i in 3 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null || terr.town == town) {
                Message.error(sender, "Invalid territory id=$id: either does not exist or belongs to town")
                return
            } else {
                territories.add(terr)
            }
        }

        // add territories
        for (terr in territories) {
            Nodes.captureTerritory(town, terr)
        }

        Message.print(sender, "Captured ${territories.size} territories for town \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town releaseterritory [id1] [id2] ...
     * Releases list of territory ids from their current occupier
     */
    private fun releaseTerritory(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town releaseterritory [id1] [id2] ...")
            Message.error(sender, "First territory id is required")
            return
        }

        // map ids to territories
        val territories: MutableList<Territory> = mutableListOf()
        for (i in 2 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null) {
                Message.error(sender, "Invalid territory id=$id: does not exist")
                return
            } else {
                territories.add(terr)
            }
        }

        // add territories
        for (terr in territories) {
            Nodes.releaseTerritory(terr)
        }

        Message.print(sender, "Released ${territories.size} territories under occupation")
    }

    /**
     * @command /nodesadmin town claimsbonus [town] [#]
     * Set town bonus claims.
     */
    private fun setClaimsBonus(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town claimsbonus [town] [#]")
            return
        }

        // get town
        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // print bonus claims for town
        if (args.size == 3) {
            Message.print(sender, "Town \"${townName}\" bonus claims: ${town.claimsBonus}")
        }
        // set town bonus claims
        else if (args.size > 3) {
            val bonus = args[3].toInt()

            // set claims
            Nodes.setClaimsBonus(town, bonus)

            Message.print(sender, "Town \"${townName}\" bonus claims set to $bonus")
        }
    }

    /**
     * @command /nodesadmin town claimspenalty [town] [#]
     * Set town penalty claims.
     */
    private fun setClaimsPenalty(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town claimspenalty [town] [#]")
            return
        }

        // get town
        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // print bonus claims for town
        if (args.size == 3) {
            Message.print(sender, "Town \"${townName}\" claims penalty: ${town.claimsPenalty}")
        }
        // set town bonus claims
        else if (args.size > 3) {
            val num = args[3].toInt()

            // set claims
            Nodes.setClaimsPenalty(town, num)

            Message.print(sender, "Town \"${townName}\" claims penalty set to $num")
        }
    }

    /**
     * @command /nodesadmin town claimsannex [town] [#]
     * Set town bonus claims.
     */
    private fun setClaimsAnnexed(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town claimsannex [town] [#]")
            return
        }

        // get town
        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // print bonus claims for town
        if (args.size == 3) {
            Message.print(sender, "Town \"${townName}\" annexed claims penalty: ${town.claimsAnnexed}")
        }
        // set town bonus claims
        else if (args.size > 3) {
            val value = args[3].toInt()

            // set claims
            Nodes.setClaimsAnnexed(town, value)

            Message.print(sender, "Town \"${townName}\" annexed claims penalty set to $value")
        }
    }

    /**
     * @command /nodesadmin town addofficer [town] [player]
     * Add officer to town
     */
    private fun addTownOfficer(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town addofficer [town] [player]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get residents from player names, error out if any do not exist
        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res === null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            if (res.town !== town) {
                Message.error(sender, "Player \"${args[i]}\" does not belong to ${town.name}")
                return
            }
            residents.add(res)
        }

        // make residents officers
        for (r in residents) {
            Nodes.townAddOfficer(town, r)
            Message.print(sender, "Made \"${r.name}\" officer of \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town removeofficer [town] [player]
     * Remove officer from town
     */
    private fun removeTownOfficer(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removeofficer [town] [player]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get residents from player names, error out if any do not exist
        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res === null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            if (res.town !== town) {
                Message.error(sender, "Player \"${args[i]}\" does not belong to ${town.name}")
                return
            }
            residents.add(res)
        }

        // make residents officers
        for (r in residents) {
            Nodes.townRemoveOfficer(town, r)
            Message.print(sender, "Removed \"${r.name}\" as officer of \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town addgeneral [town] [player]
     * Promote a town resident to general
     */
    private fun addTownGeneral(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town addgeneral [town] [player]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res === null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            if (res.town !== town) {
                Message.error(sender, "Player \"${args[i]}\" does not belong to ${town.name}")
                return
            }
            residents.add(res)
        }

        for (r in residents) {
            Nodes.townAddGeneral(town, r)
            Message.print(sender, "Promoted \"${r.name}\" to general of \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town removegeneral [town] [player]
     * Demote a town general to officer
     */
    private fun removeTownGeneral(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removegeneral [town] [player]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res === null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            if (res.town !== town) {
                Message.error(sender, "Player \"${args[i]}\" does not belong to ${town.name}")
                return
            }
            residents.add(res)
        }

        for (r in residents) {
            Nodes.townRemoveGeneral(town, r)
            Message.print(sender, "Demoted \"${r.name}\" from general of \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town leader [town] [player]
     * Set town resident as new town leader
     */
    private fun setTownLeader(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town leader [town] [player]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town === null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val playerName = args[3]
        val resident = Nodes.getResidentFromName(playerName)
        if (resident === null) {
            Message.error(sender, "Player \"${playerName}\" does not exist")
            return
        }
        if (resident.town !== town) {
            Message.error(sender, "Player \"${playerName}\" is not a member of \"${town.name}\"")
            return
        }

        Nodes.townSetLeader(town, resident)
        Message.print(sender, "Player \"${playerName}\" is now leader of \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town removeleader [town]
     * Remove town's leader (makes town leaderless)
     */
    private fun removeTownLeader(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town removeleader [town]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town === null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        Nodes.townSetLeader(town, null)
        Message.print(sender, "Removed leader of \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town open [town]
     * Toggle whether town is open to join
     */
    private fun setTownOpen(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town open [town]")
            return
        }

        // get town
        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // set town open state
        Nodes.setTownOpen(town, !town.isOpen)

        Message.print(sender, "Town \"${townName}\" isOpen set to ${town.isOpen}")
    }

    /**
     * @command /nodesadmin town income [town]
     * View a town's income inventory gui (must run ingame).
     */
    private fun townIncome(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player == null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town income [town]")
            return
        }

        // get town
        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // open town inventory
        player.openInventory(Nodes.getTownIncomeInventory(town))
    }

    /**
     * @command /nodesadmin town incomeadd [town]
     * Adds item player is holding to input town income inventories.
     */
    private fun townIncomeAdd(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player === null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town incomeadd [town]")
            return
        }

        // get towns
        val townName = args[2]
        val towns = Nodes.matchTowns(townName)
        if (towns.size == 0) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get item in player hand
        val item = player.inventory.itemInMainHand
        if (item === null || item.type == Material.AIR) {
            Message.error(sender, "You must be holding an item")
            return
        }

        for (town in towns) {
            Nodes.addToIncome(town, item.type, item.amount)
            Message.print(sender, "Added item to \"${town.name}\" income inventory")
        }
    }

    /**
     * @command /nodesadmin town incomeremove [town] [material]
     * Removes items in town income inventories. If material is not specified,
     * removes all items.
     */
    private fun townIncomeRemove(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player === null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town incomeremove [town] [material]")
            return
        }

        // get towns
        val townName = args[2]
        val towns = Nodes.matchTowns(townName)
        if (towns.size == 0) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get material if specified, otherwise treat as null and remove all
        val material = if (args.size >= 4) Material.matchMaterial(args[3]) else null

        // TODO: perhaps abstract this into a Nodes api func
        for (town in towns) {
            Nodes.townIncomeRemove(town, material)
            if (material !== null) {
                Message.print(sender, "Removed $material from \"${town.name}\" income inventory")
            } else {
                Message.print(sender, "Removed all items from \"${town.name}\" income inventory")
            }
        }
    }

    /**
     * @command /nodesadmin town setspawn [town]
     * Set town spawn to player location (must run ingame).
     */
    private fun townSetSpawn(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player == null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town setspawn [town]")
            return
        }

        // get town
        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val result = Nodes.setTownSpawn(town, player.location)

        if (result == true) {
            Message.print(player, "Town \"${townName}\" spawn set to current location")
        } else {
            Message.error(player, "Spawn location must be within town's home territory")
        }
    }

    /**
     * @command /nodesadmin town spawn [town]
     * Spawn at a town's spawn
     */
    private fun townSpawn(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player == null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town spawn [town]")
            return
        }

        // get town
        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town === null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val spawnpoint = town.spawnpoint
        if (spawnpoint === null) {
            Message.error(sender, "Town \"${townName}\" has no spawn set")
            return
        }

        player.teleport(spawnpoint)
        Message.print(sender, "Teleported to \"${townName}\" spawn")
    }

    /**
     * @command /nodesadmin town sethome [town] [id]
     * Set a town's home territory
     */
    private fun setTownHome(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town sethome [name] [id]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get new home territory
        val id = TerritoryId(args[3].toInt())
        val terr = Nodes.territories[id]
        if (terr == null) {
            Message.error(sender, "Invalid territory id=$id: does not exist")
            return
        }

        // set town home territory
        if (town !== terr.town) {
            Message.error(sender, "Invalid territory id=$id: does not belong to town")
            return
        }

        if (town.home == terr.id) {
            Message.error(sender, "Invalid territory id=$id: already is home territory")
            return
        }

        Nodes.setTownHomeTerritory(town, terr)
        Message.print(sender, "Moved \"${townName}\" home territory to id = ${terr.id}")
    }

    /**
     * @command /nodesadmin town setHomeCooldown [town] [int]
     * Set a town's move home cooldown
     */
    private fun setTownMoveHomeCooldown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town sethome [name] [id]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get new home territory
        val cooldown: Long = Math.max(0L, args[3].toLong())

        Nodes.setTownHomeMoveCooldown(town, cooldown)
        Message.print(sender, "Set town \"${townName}\" move cooldown to $cooldown ms")
    }

    /**
     * @command /nodesadmin town addoutpost [town] [name] [id]
     * Add an outpost with name to town at territory id
     */
    private fun addOutpostToTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 5) {
            Message.error(sender, "Usage: /nodesadmin town addoutpost [town] [name] [id]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get outpost name
        val name = args[3]
        if (town.outposts.contains(name)) {
            Message.error(sender, "Town already has outpost named: \"${name}\"")
            return
        }

        // get outpost territory
        val id = TerritoryId(args[4].toInt())
        val terr = Nodes.territories.get(id)
        if (terr == null) {
            Message.error(sender, "Invalid territory id=$id: does not exist")
            return
        }

        // set town home territory
        if (town !== terr.town) {
            Message.error(sender, "Invalid territory id=$id: does not belong to town")
            return
        }

        val result = Nodes.createTownOutpost(town, name, terr)
        if (result == true) {
            Message.print(sender, "Created outpost \"${name}\" for \"${townName}\" in territory id = ${terr.id}")
        } else {
            Message.error(sender, "Failed to create outpost \"${name}\" for \"${townName}\"")
        }
    }

    /**
     * @command /nodesadmin town removeoutpost [town] [name]
     * Remove outpost from town with given name
     */
    private fun removeOutpostFromTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removeoutpost [town] [name]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        // get outpost name
        val name = args[3]
        if (!town.outposts.contains(name)) {
            Message.error(sender, "Town does not have outpost named: \"${name}\"")
            return
        }

        val result = Nodes.destroyTownOutpost(town, name)
        if (result == true) {
            Message.print(sender, "Removed outpost \"${name}\" from \"${townName}\"")
        } else {
            Message.error(sender, "Failed to remove outpost \"${name}\" from \"${townName}\"")
        }
    }

    private fun addOutpostCreditsToTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town addoutpostcredits [town] [amount]")
            return
        }

        val townName = args[2]
        val town = Nodes.getTownFromName(townName)
        if (town == null) {
            Message.error(sender, "Town \"$townName\" does not exist")
            return
        }

        val amount = args[3].toIntOrNull()
        if (amount == null) {
            Message.error(sender, "Invalid amount")
            return
        }

        town.outpostCredits = (town.outpostCredits + amount).coerceAtLeast(0)
        town.needsUpdate()
        Nodes.needsSave = true

        Message.print(sender, "${ChatColor.GREEN}${town.name} outpost credits are now ${town.outpostCredits}")
    }

    // TODO: town perm toggles

    /**
     * @command /nodesadmin town defaulttownspawns [town]
     * Reset town spawns to default position (highest block before air)
     */
    private fun defaultTownSpawns(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town defaulttownspawns [town]")
            return
        }

        // get towns
        val townName = args[2]
        val towns = Nodes.matchTowns(townName)
        if (towns.size == 0) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        for (town in towns) {
            val terrHome = Nodes.territories.get(town.home)
            if (terrHome !== null) {
                val spawnpoint = Nodes.getDefaultSpawnLocation(terrHome)
                town.spawnpoint = spawnpoint
                town.needsUpdate()
                Message.print(sender, "Set town \"${town.name}\" spawnpoint to $spawnpoint")
            } else {
                Message.error(sender, "Town \"${town.name}\" home territory ${town.home} does not exist")
            }
        }

        // TODO: move this out
        Nodes.needsSave = true
    }

    private fun fixTownSpawns(sender: CommandSender, args: Array<String>) {
        if (args.size != 2) {
            Message.error(sender, "Usage: /nodesadmin town fixspawns")
            return
        }

        val world = Bukkit.getWorlds().firstOrNull()
        if (world == null) {
            Message.error(sender, "No world loaded")
            return
        }

        var updated = 0
        var skipped = 0

        for (town in Nodes.towns.values) {
            val home = Nodes.territories[town.home]
            if (home == null) {
                skipped += 1
                Message.error(sender, "Skipped ${town.name}: home territory ${town.home} does not exist")
                continue
            }

            val core = home.core
            town.spawnpoint = Location(
                world,
                core.x * 16.0 + 8.5,
                70.0,
                core.z * 16.0 + 8.5,
                0.0f,
                0.0f,
            )
            town.needsUpdate()
            updated += 1
        }

        if (updated > 0) {
            Nodes.needsSave = true
            Nodes.saveWorld(checkIfNeedsSave = false, async = false)
        }

        Message.print(sender, "Set $updated town spawnpoint(s) to home core chunk at Y=70")
        if (skipped > 0) {
            Message.error(sender, "Skipped $skipped town(s) because home territory was missing")
        }
    }

    private fun setTownColor(sender: CommandSender, args: Array<String>) {
        if (args.size < 6) {
            Message.error(sender, "Usage: /nodesadmin town color [town] [r] [g] [b]")
            return
        }

        val townName = args[2]
        val town = Nodes.getTownFromName(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        try {
            val r = args[3].toInt().coerceIn(0, 255)
            val g = args[4].toInt().coerceIn(0, 255)
            val b = args[5].toInt().coerceIn(0, 255)

            Nodes.setTownColor(town, r, g, b)
            Message.print(sender, "${town.name} color set to ${ChatColor.WHITE}$r $g $b")
        } catch (_: NumberFormatException) {
            Message.error(sender, "Invalid color; values must be integers 0-255")
        }
    }

    private fun renameTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town rename [town] [new_name]")
            return
        }

        val townName = args[2]
        val town = Nodes.getTownFromName(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val newName = args[3]
        if (!stringInputIsValid(newName)) {
            Message.error(sender, "Invalid town name")
            return
        }

        if (town.name.equals(newName, ignoreCase = true)) {
            Message.error(sender, "Town is already named ${town.name}")
            return
        }

        if (!Nodes.renameTown(town, newName)) {
            Message.error(sender, "A town named \"${newName}\" already exists")
            return
        }

        Message.print(sender, "Town renamed to ${town.name}")
    }

    /**
     * @command /nodesadmin disable <market|miningtower|miningoutminer> [on|off]
     *
     * Enables or disables a system at runtime without restarting the server.
     * Running the command without [on|off] prints the current state.
     *
     * Usage:
     *   /nodesadmin disable market           – show current state
     *   /nodesadmin disable market off       – disable the market system
     *   /nodesadmin disable market on        – re-enable the market system
     *   /nodesadmin disable miningtower off
     *   /nodesadmin disable miningoutminer off
     */
    private fun manageDisable(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.print(sender, "${ChatColor.BOLD}[Nodes] System toggle:")
            Message.print(sender, "/nodesadmin disable <market|miningtower|miningoutminer> [on|off]")
            Message.print(
                sender,
                "Market:       ${if (MarketManager.enabled) "${ChatColor.GREEN}ON" else "${ChatColor.RED}OFF"}",
            )
            Message.print(
                sender,
                "MiningTower:  ${if (MiningTowerManager.enabled) "${ChatColor.GREEN}ON" else "${ChatColor.RED}OFF"}",
            )
            Message.print(
                sender,
                "MiningOutminer: ${if (ActiveMiningOutminerManager.enabled) "${ChatColor.GREEN}ON" else "${ChatColor.RED}OFF"}",
            )
            return
        }

        val system = args[1].lowercase()

        // If no on/off provided, just print current state for that system
        if (args.size < 3) {
            when (system) {
                "market" -> {
                    val state = if (MarketManager.enabled) "${ChatColor.GREEN}ON" else "${ChatColor.RED}OFF"
                    Message.print(sender, "Market is currently $state")
                    Message.print(sender, "Usage: /nodesadmin disable market [on|off]")
                }
                "miningtower" -> {
                    val state = if (MiningTowerManager.enabled) "${ChatColor.GREEN}ON" else "${ChatColor.RED}OFF"
                    Message.print(sender, "MiningTower is currently $state")
                    Message.print(sender, "Usage: /nodesadmin disable miningtower [on|off]")
                }
                "miningoutminer" -> {
                    val state = if (ActiveMiningOutminerManager.enabled) "${ChatColor.GREEN}ON" else "${ChatColor.RED}OFF"
                    Message.print(sender, "MiningOutminer is currently $state")
                    Message.print(sender, "Usage: /nodesadmin disable miningoutminer [on|off]")
                }
                else -> {
                    Message.error(sender, "Unknown system \"$system\". Choose: market, miningtower, miningoutminer")
                }
            }
            return
        }

        // Parse on/off
        val enable: Boolean = when (args[2].lowercase()) {
            "on", "enable", "true" -> true
            "off", "disable", "false" -> false
            else -> {
                Message.error(sender, "Invalid toggle \"${args[2]}\". Use: on / off")
                return
            }
        }

        when (system) {
            "market" -> {
                MarketManager.enabled = enable
                if (enable) {
                    Message.print(sender, "${ChatColor.GREEN}Market system enabled")
                    Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}The market is now open.")
                } else {
                    Message.print(sender, "${ChatColor.RED}Market system disabled")
                    Message.broadcast("${ChatColor.RED}${ChatColor.BOLD}The market has been closed by an administrator.")
                }
            }

            "miningtower" -> {
                MiningTowerManager.enabled = enable
                if (enable) {
                    Message.print(sender, "${ChatColor.GREEN}MiningTower system enabled")
                    Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}Mining towers are now active.")
                } else {
                    Message.print(sender, "${ChatColor.RED}MiningTower system disabled")
                    Message.broadcast("${ChatColor.RED}${ChatColor.BOLD}Mining towers have been disabled by an administrator.")
                }
            }

            "miningoutminer" -> {
                ActiveMiningOutminerManager.enabled = enable
                if (enable) {
                    Message.print(sender, "${ChatColor.GREEN}MiningOutminer system enabled")
                    Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}Mining outminers are now active.")
                } else {
                    Message.print(sender, "${ChatColor.RED}MiningOutminer system disabled")
                    Message.broadcast("${ChatColor.RED}${ChatColor.BOLD}Mining outminers have been disabled by an administrator.")
                }
            }

            else -> {
                Message.error(sender, "Unknown system \"$system\". Choose: market, miningtower, miningoutminer")
            }
        }
    }

    // =============================================================
    // nation management commands:
    // - create/delete nations
    // - toggle nation properties, alliances, towns, ...
    // =============================================================

    // route command to further subcommands
    private fun manageNation(sender: CommandSender, args: Array<String>, isAdmin: Boolean, isMod: Boolean, isHelper: Boolean) {
        if (args.size < 2) {
            printNationHelp(sender)
            return
        }

        val sub = args[1].lowercase()
        if (!isAdmin) {
            if ((isMod || isHelper) && sub == "addtown") {
                addTownToNation(sender, args)
            } else {
                Message.error(sender, "You do not have permission to use /nodesadmin nation $sub")
            }
            return
        }

        when (sub) {
            "create" -> createNation(sender, args)
            "delete" -> deleteNation(sender, args)
            "addtown" -> addTownToNation(sender, args)
            "removetown" -> removeTownFromNation(sender, args)
            "capital" -> setNationCapital(sender, args)
            "color" -> setNationColor(sender, args)
            "rename" -> renameNation(sender, args)
            else -> {
                printNationHelp(sender)
            }
        }
    }

    private fun printNationHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin nation management:")
        Message.print(sender, "/nodesadmin nation create${ChatColor.WHITE}: Create a new nation")
        Message.print(sender, "/nodesadmin nation delete${ChatColor.WHITE}: Delete existing nation")
        Message.print(sender, "/nodesadmin nation addtown${ChatColor.WHITE}: Add towns to nation")
        Message.print(sender, "/nodesadmin nation removetown${ChatColor.WHITE}: Remove towns from nation")
        Message.print(sender, "/nodesadmin nation capital${ChatColor.WHITE}: Set nation's capital town")
        Message.print(sender, "/nodesadmin nation color${ChatColor.WHITE}: Set nation color on map")
        Message.print(sender, "/nodesadmin nation rename${ChatColor.WHITE}: Rename a nation")
        Message.print(sender, "Run a command with no args to see usage.")
    }

    /**
     * @command /nodesadmin nation create [name] [town1] [town2] ...
     * Creates a new nation with [name] with list of towns.
     * [town1] required (cannot have empty nations).
     */
    private fun createNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation create [name] [town1] [town2] ...")
            Message.error(sender, "List is town names")
            return
        }

        // get nation name
        if (!stringInputIsValid(args[2])) {
            Message.error(sender, "Invalid nation name")
            return
        }
        val nationName = sanitizeString(args[2])

        // get towns
        val towns: MutableList<Town> = mutableListOf()
        for (i in 3 until args.size) {
            val townName = args[i]
            val town = Nodes.towns.get(townName)
            if (town == null || town.nation != null) {
                Message.error(sender, "Invalid town \"${townName}\": either does not exist or already has nation")
                return
            } else {
                towns.add(town)
            }
        }

        // create new nation from town
        val nation = Nodes.createNation(nationName, towns[0], towns[0].leader).getOrElse({ err ->
            Message.error(sender, "Failed to create nation: ${err.message}")
            return
        })

        // add other towns
        for (i in 1 until towns.size) {
            Nodes.addTownToNation(nation, towns[i])
        }

        Message.print(sender, "Created nation \"${nationName}\" with ${towns.size} towns")
    }

    /**
     * @command /nodesadmin nation delete [name]
     * Deletes nation [name].
     */
    private fun deleteNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin nation delete [name]")
            return
        }

        val name = args[2]
        val nation = Nodes.nations.get(name)
        if (nation != null) {
            Nodes.destroyNation(nation)
            Message.print(sender, "Nation \"${name}\" has been deleted")
        } else {
            Message.error(sender, "Nation \"${name}\" does not exist")
        }
    }

    /**
     * @command /nodesadmin nation addtown [name] [town1] [town2] ...
     * Add list of towns to nation [name].
     */
    private fun addTownToNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation addtown [name] [town1] [town2] ...")
            Message.error(sender, "First town name is required")
            return
        }

        val nationName = args[2]
        val nation = Nodes.nations.get(nationName)
        if (nation == null) {
            Message.error(sender, "Nation \"${nationName}\" does not exist")
            return
        }

        // get residents from player names, error out if any do not exist
        val towns: MutableList<Town> = mutableListOf()
        for (i in 3 until args.size) {
            val town = Nodes.towns.get(args[i])
            if (town == null || town.nation != null) {
                Message.error(sender, "Invalid town \"${args[i]}\": does not exist or has a nation already")
                return
            }
            towns.add(town)
        }

        // add towns
        for (town in towns) {
            Nodes.addTownToNation(nation, town)
            Message.print(sender, "Added town \"${town.name}\" to nation \"${nation.name}\"")
        }
    }

    /**
     * @command /nodesadmin nation removetown [name] [town1] [town2] ...
     * Remove list of towns from nation [name].
     */
    private fun removeTownFromNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation removetown [name] [town1] [town2] ...")
            Message.error(sender, "First town name is required")
            return
        }

        val nationName = args[2]
        val nation = Nodes.nations.get(nationName)
        if (nation == null) {
            Message.error(sender, "Nation \"${nationName}\" does not exist")
            return
        }

        // get towns, error out if any do not exist or do not belong to nation
        val towns: MutableList<Town> = mutableListOf()
        for (i in 3 until args.size) {
            val town = Nodes.towns.get(args[i])
            if (town == null || town.nation != nation) {
                Message.error(sender, "Invalid town \"${args[i]}\": does not belong to nation")
                return
            }
            towns.add(town)
        }

        // remove towns
        for (town in towns) {
            Nodes.removeTownFromNation(nation, town)
            Message.print(sender, "Removed town \"${town.name}\" from nation \"${nation.name}\"")
        }
    }

    /**
     * @command /nodesadmin nation capital [nation] [town]
     * Make input town the new capital of nation. Town must already
     * be part of the nation.
     */
    private fun setNationCapital(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation capital [nation] [town]")
            return
        }

        val nationName = args[2]
        val nation = Nodes.nations.get(nationName)
        if (nation === null) {
            Message.error(sender, "Nation \"${nationName}\" does not exist")
            return
        }

        val townName = args[3]
        val town = Nodes.getTownFromName(townName)
        if (town === null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }
        if (town.nation !== nation) {
            Message.error(sender, "Town does not belong to this nation")
            return
        }
        if (town === nation.capital) {
            Message.error(sender, "Town is already the nation capital")
            return
        }

        Nodes.setNationCapital(nation, town)

        // broadcast message
        Message.print(sender, "${town.name} is now the capital of ${nation.name}")
    }

    private fun setNationColor(sender: CommandSender, args: Array<String>) {
        if (args.size < 6) {
            Message.error(sender, "Usage: /nodesadmin nation color [nation] [r] [g] [b]")
            return
        }

        val nationName = args[2]
        val nation = Nodes.nations[nationName]
        if (nation == null) {
            Message.error(sender, "Nation \"${nationName}\" does not exist")
            return
        }

        try {
            val r = args[3].toInt().coerceIn(0, 255)
            val g = args[4].toInt().coerceIn(0, 255)
            val b = args[5].toInt().coerceIn(0, 255)

            Nodes.setNationColor(nation, r, g, b)
            Message.print(sender, "${nation.name} color set to ${ChatColor.WHITE}$r $g $b")
        } catch (_: NumberFormatException) {
            Message.error(sender, "Invalid color; values must be integers 0-255")
        }
    }

    private fun renameNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation rename [nation] [new_name]")
            return
        }

        val nationName = args[2]
        val nation = Nodes.nations[nationName]
        if (nation == null) {
            Message.error(sender, "Nation \"${nationName}\" does not exist")
            return
        }

        val newName = args[3]
        if (!stringInputIsValid(newName)) {
            Message.error(sender, "Invalid nation name")
            return
        }

        if (nation.name.equals(newName, ignoreCase = true)) {
            Message.error(sender, "Nation is already named ${nation.name}")
            return
        }

        if (!Nodes.renameNation(nation, newName)) {
            Message.error(sender, "A nation named \"${newName}\" already exists")
            return
        }

        Message.print(sender, "Nation renamed to ${nation.name}")
    }

    // =============================================================
    // Diplomacy commands
    // force add/remove war, peace, ally
    // =============================================================

    /**
     * @command /nodesadmin enemy [name1] [name2]
     * Sets enemy status between [name1] and [name2]
     * (either town or nation names)
     */
    private fun setEnemy(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin enemy [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        // try getting nations first
        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        // if either null, get towns, else use nation capital
        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.addEnemy(town1, town2)

        Message.print(sender, "Set war between $name1 and $name2")
        SocialSpy.notifyDiplomacy("${sender.name} set war between $name1 and $name2")
    }

    /**
     * @command /nodesadmin peace [name1] [name2]
     * Removes enemy status between [name1] and [name2]
     * (either town or nation names)
     */
    private fun setPeace(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin peace [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        // try getting nations first
        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        // if either null, get towns, else use nation capital
        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.removeEnemy(town1, town2)

        Message.print(sender, "Set peace between $name1 and $name2")
        SocialSpy.notifyDiplomacy("${sender.name} set peace between $name1 and $name2")
    }

    /**
     * @command /nodesadmin ally [name1] [name2]
     * Makes [name1] and [name2] allies (either town or nation names).
     */
    private fun setAlly(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ally [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        // try getting nations first
        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        // if either null, get towns, else use nation capital
        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.addAlly(town1, town2)

        Message.print(sender, "Set alliance between $name1 and $name2")
        SocialSpy.notifyDiplomacy("${sender.name} set alliance between $name1 and $name2")
    }

    /**
     * @command /nodesadmin allyremove [name1] [name2]
     * Removes alliance between [name1] and [name2] (either town or nation names).
     */
    private fun removeAlly(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ally [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        // try getting nations first
        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        // if either null, get towns, else use nation capital
        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.removeAlly(town1, town2)

        Message.print(sender, "Removed any alliance between $name1 and $name2")
        SocialSpy.notifyDiplomacy("${sender.name} removed alliance between $name1 and $name2")
    }

    /**
     * @command /nodesadmin truce [name1] [name2]
     * Sets a truce between [name1] and [name2] (either town or nation names).
     */
    private fun setTruce(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ally [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        // try getting nations first
        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        // if either null, get towns, else use nation capital
        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.addTruce(town1, town2)

        Message.print(sender, "Set truce between $name1 and $name2")
        SocialSpy.notifyDiplomacy("${sender.name} set truce between $name1 and $name2")
    }

    /**
     * @command /nodesadmin truceremove [name1] [name2]
     * Removes any truce between [name1] and [name2] (either town or nation names).
     */
    private fun removeTruce(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ally [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        // try getting nations first
        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        // if either null, get towns, else use nation capital
        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.removeTruce(town1, town2)

        Message.print(sender, "Removed any truce between $name1 and $name2")
        SocialSpy.notifyDiplomacy("${sender.name} removed truce between $name1 and $name2")
    }

    // =============================================================
    // treaty manipulation commands
    // - add/remove terms from active peace treaties (mostly for debug)
    // - main form:
    //   /nda treaty [name1] [name2] [add/remove] [term] [side: 0 or 1] [args...]
    //
    //   for term side, 0 == name1 and 1 == name2
    // =============================================================
    private fun manageTreaty(sender: CommandSender, args: Array<String>) {
        if (args.size < 6) {
            Message.error(sender, "Usage: /nodesadmin treaty [name1] [name2] [add/remove] [term] [side] [args...]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        // try getting nations first
        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        // if either null, get towns, else use nation capital
        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        // get treaty
        val treaty = Treaty.get(town1, town2)
        if (treaty === null) {
            Message.error(sender, "No treaty exists between ${town1.name} and ${town2.name}")
            return
        }

        // get town side the term belongs to
        var provider: Town? = null
        var receiver: Town? = null
        if (args[5] == "0") {
            provider = town1
            receiver = town2
        } else if (args[5] == "1") {
            provider = town2
            receiver = town1
        } else {
            Message.error(sender, "Term side arg[5] must be \"0\" or \"1\"")
            return
        }
        provider = provider as Town
        receiver = receiver as Town

        // get add/remove term
        if (args[3] == "add") {
            // match term type
            when (args[4]) {
                "occupy" -> {
                    if (args.size < 7) {
                        Message.error(sender, "Usage: /nodesadmin treaty [name1] [name2] [add/remove] occupy [side] [id]")
                        return
                    }

                    // get territory
                    val territory = Nodes.getTerritoryFromId(TerritoryId(args[6].toInt()))
                    if (territory === null) {
                        Message.error(sender, "Invalid territory id")
                        return
                    }

                    treaty.add(TreatyTermOccupation(provider, receiver, territory.id))

                    Message.print(sender, "Added occupation term to treaty between ${town1.name} and ${town2.name}")
                }
                "item" -> {
                    if (args.size < 8) {
                        Message.error(sender, "Usage: /nodesadmin treaty [name1] [name2] [add/remove] item [side] [type] [count]")
                        return
                    }

                    val itemType = Material.matchMaterial(args[6])
                    if (itemType == null) {
                        return
                    }
                    val itemCount = args[7].toInt()

                    treaty.add(TreatyTermItems(provider, receiver, ItemStack(itemType, itemCount), null))

                    Message.print(sender, "Added item term to treaty between ${town1.name} and ${town2.name}")
                }
            }
        } else if (args[3] == "remove") {
            // TODO
        } else {
            Message.error(sender, "arg[3] must be \"add\" or \"remove\"")
        }
    }

    // =============================================================
    // World save/load commands:
    // - Force world save/load
    // TODO: - better print out
    //       - add backupWorld command to force backup
    //       - allow loading from backups folder?
    // =============================================================

    // force save the world (using async save)
    private fun saveWorld(sender: CommandSender, args: Array<String>) {
        if (args.size > 1 && args[1] == "sync") {
            Message.print(sender, "[Nodes] Saving world (sync)")
            Nodes.saveWorld(checkIfNeedsSave = false, async = false)
        } else {
            Message.print(sender, "[Nodes] Saving world (async)")
            Nodes.saveWorld(checkIfNeedsSave = false, async = true)
        }
    }

    private fun clearIncome(sender: CommandSender, args: Array<String>) {
        if (args.size != 1) {
            Message.error(sender, "Usage: /nodesadmin clearincome")
            return
        }

        var touchedTowns = 0
        for (town in Nodes.towns.values) {
            val movedFromGui = town.income.pushToStorage(force = true)
            val hadStoredIncome = town.income.storage.isNotEmpty() || town.income.storageSpawnEgg.isNotEmpty()
            if (movedFromGui || hadStoredIncome) {
                touchedTowns += 1
            }

            town.income.storage.clear()
            town.income.storageSpawnEgg.clear()
            town.income._inventory.clear()
            town.needsUpdate()
        }

        if (Nodes.towns.isNotEmpty()) {
            Nodes.needsSave = true
            Nodes.saveWorld(checkIfNeedsSave = false, async = false)
        }

        Message.print(sender, "Cleared income for ${Nodes.towns.size} town(s) ($touchedTowns had stored income)")
    }

    // force reload the world
    // TODO: add optional path name for world data to load?
    private fun loadWorld(sender: CommandSender) {
        Message.print(sender, "[Nodes] Loading world")
        Nodes.loadWorld()
    }

    private fun attackTimeDebug(sender: CommandSender, args: Array<String>) {
        val chunk = when {
            args.size >= 3 -> {
                val chunkX = args[1].toIntOrNull()
                val chunkZ = args[2].toIntOrNull()
                if (chunkX == null || chunkZ == null) {
                    Message.error(sender, "Usage: /nodesadmin attacktimedebug [chunkX chunkZ]")
                    return
                }
                Nodes.getTerritoryChunkFromCoord(Coord(chunkX, chunkZ))
            }
            sender is Player -> {
                val loc = sender.location
                Nodes.getTerritoryChunkFromBlock(loc.blockX, loc.blockZ)
            }
            else -> {
                Message.error(sender, "Provide chunk coordinates when running from console.")
                return
            }
        }

        if (chunk == null) {
            Message.error(sender, "Chunk not part of any territory.")
            return
        }

        val territory = chunk.territory
        val owningTown = territory.town
        val chunkIsCore = chunk.coord == territory.core
        val baseTime = Config.chunkAttackTime.toDouble()
        var runningTime = baseTime

        fun formatTicks(value: Double): String = String.format("%.2f", value)

        Message.print(
            sender,
            "${ChatColor.BOLD}[Attack Time Debug] Chunk (${chunk.coord.x}, ${chunk.coord.z}) in territory ${territory.id}",
        )
        Message.print(
            sender,
            "- Territory town: ${owningTown?.name ?: ChatColor.GRAY.toString() + "None"} (${if (owningTown != null && chunkIsCore) "Core" else "Non-core"})",
        )
        Message.print(sender, "- Borders wilderness: ${territory.bordersWilderness}")
        val resourceNodes = territory.resourceNodes
            .mapNotNull { name -> Nodes.resourceNodes[name] }
        if (resourceNodes.isEmpty()) {
            Message.print(sender, "- Resource nodes: None")
        } else {
            Message.print(sender, "- Resource nodes:")
            resourceNodes.forEach { node ->
                Message.print(sender, "   - ${node.name}: ${describeNodeMultiplier(node)}")
            }
        }

        val nodeTypeNodes = territory.nodeTypes
            .mapNotNull { type -> Config.nodeTypes[type] }
        if (nodeTypeNodes.isEmpty()) {
            Message.print(sender, "- Node types: None")
        } else {
            Message.print(sender, "- Node types:")
            nodeTypeNodes.forEach { node ->
                Message.print(sender, "   - ${node.name}: ${describeNodeMultiplier(node)}")
            }
        }

        Message.print(sender, "- Base chunk attack time: ${baseTime.toLong()} ticks")

        if (territory.bordersWilderness) {
            runningTime *= Config.chunkAttackFromWastelandMultiplier
            Message.print(
                sender,
                "- Wasteland multiplier x${Config.chunkAttackFromWastelandMultiplier} => ${formatTicks(runningTime)}",
            )
        } else {
            Message.print(
                sender,
                "- Wasteland multiplier not applied (Chunk not bordering wilderness)",
            )
        }

        if (owningTown != null && territory.id == owningTown.home) {
            runningTime *= Config.chunkAttackHomeMultiplier
            Message.print(
                sender,
                "- Home territory multiplier x${Config.chunkAttackHomeMultiplier} => ${formatTicks(runningTime)}",
            )
        } else if (owningTown != null) {
            Message.print(
                sender,
                "- Home territory multiplier not applied (not home core)",
            )
        }

        if (owningTown != null) {
            Message.print(
                sender,
                "- Territory attacker multiplier: x${formatTicks(territory.attackerTimeMultiplier)}",
            )
            runningTime *= territory.attackerTimeMultiplier
        } else {
            Message.print(
                sender,
                "- Territory attacker multiplier skipped (territory unclaimed)",
            )
        }

        val defenderString =
            "${ChatColor.GRAY}Use ${territory.defenderTimeMultiplier} if attacker is owner/ally instead of enemy"
        Message.print(sender, "- Territory defender multiplier info: $defenderString")

        if (runningTime <= 0.0) {
            Message.print(sender, "- Computed attack time <= 0; will clamp to 1 tick.")
        }

        val finalTicks = runningTime.toLong().coerceAtLeast(1L)
        val finalSeconds = String.format("%.2f", finalTicks.toDouble() / 20.0)
        Message.print(sender, "- Final attack time: $finalTicks ticks ($finalSeconds s)")
    }

    private fun describeNodeMultiplier(node: ResourceNode): String {
        val multipliers = node.attributesSorted
            .filterIsInstance<ResourceAttributeAttackerTimeMultiplier>()
            .map { it.multiplier }

        return if (multipliers.isEmpty()) {
            "attacker multiplier not set (defaults to 1.0)"
        } else {
            multipliers.joinToString(", ") { "x${String.format("%.2f", it)}" }
        }
    }

    // =============================================================
    // Debug commands:
    // Commands to print fields from resident, town, nation objects
    // using reflection. Only intended for developer use.
    // =============================================================

    /**
     * @command /nodesadmin debug
     * Console command to debug runtime world object state.
     * General usage is /nodesadmin debug [type] [name/id] [field]
     *
     * @subcommand /nodesadmin debug resource [name] [field]
     *
     * @subcommand /nodesadmin debug chunk [x,z] [field]
     * To input coord, format is "x,z" with no spaces
     *
     * @subcommand /nodesadmin debug territory [id] [field]
     *
     * @subcommand /nodesadmin debug resident [name] [field]
     *
     * @subcommand /nodesadmin debug town [name] [field]
     *
     * @subcommand /nodesadmin debug nation [name] [field]
     */
    private fun debugger(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            printDebuggerHelp(sender)
            return
        }

        // get object instance
        val instance: Any? = when (args[1].lowercase()) {
            "resource" -> Nodes.resourceNodes.get(args[2])
            "chunk" -> Nodes.territoryChunks.get(Coord.fromString(args[2]))
            "territory" -> Nodes.territories.get(TerritoryId(args[2].toInt()))
            "resident" -> Nodes.getResidentFromName(args[2])
            "town" -> Nodes.towns.get(args[2])
            "nation" -> Nodes.nations.get(args[2])
            else -> null
        }

        if (instance == null) {
            Message.error(sender, "Invalid object: ${args[2]}")
            return
        }

        val classType = instance.javaClass
        val fieldName = args[3]
        try {
            val classField = classType.getDeclaredField(fieldName)
            classField.setAccessible(true)
            println(classField.get(instance))
        } catch (e: NoSuchFieldException) {
            Message.error(sender, "No such field: $fieldName")
        }
    }

    private fun printDebuggerHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Runtime debugger:")
        Message.print(sender, "/nodesadmin debug resource${ChatColor.WHITE}: Get resource node data")
        Message.print(sender, "/nodesadmin debug chunk${ChatColor.WHITE}: Get chunk data")
        Message.print(sender, "/nodesadmin debug territory${ChatColor.WHITE}: Get territory data")
        Message.print(sender, "/nodesadmin debug resident${ChatColor.WHITE}: Get resident data")
        Message.print(sender, "/nodesadmin debug town${ChatColor.WHITE}: Get town data")
        Message.print(sender, "/nodesadmin debug nation${ChatColor.WHITE}: Get nation data")
        Message.print(sender, "Used to print debug info into system console from object data fields.")
        Message.print(sender, "General usage is \"/nodesadmin debug [type] [name/id] [field]\"")
    }

    // =============================================================
    /* test function for ore sampler, uncomment if needed
    private fun testOreSampler() {
        val ore1 = OreDeposit(Material.DIAMOND, 0.1, 1, 1)
        val ore2 = OreDeposit(Material.EMERALD, 0.1, 1, 1)
        val ore3 = OreDeposit(Material.GOLD_ORE, 0.3, 1, 1)
        val ore4 = OreDeposit(Material.IRON_ORE, 0.3, 1, 1)
        println(ore1)
        println(ore2)
        println(ore3)
        println(ore4)

        // create ore sampler from ores
        val arrOre: ArrayList<OreDeposit> = arrayListOf(ore1, ore2, ore3, ore4)
        val oreSampler = OreSampler(arrOre)

        // try sampling
        val COUNT = 10000

        println("Running sampleAll: ${COUNT} counts")
        val allDrops: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)
        val timeSampleAll = measureTimeMillis {
            for ( i in 0 until COUNT ) {
                val drops = oreSampler.sampleAll(128)
                drops.forEach { itemStack ->
                    val mat = itemStack.getType()
                    val amount = itemStack.getAmount()
                    allDrops.get(mat)?.let { currentAmount ->
                        allDrops.put(mat, currentAmount + amount)
                    } ?: run {
                        allDrops.put(mat, amount)
                    }
                }
            }
        }
        println(allDrops)
        println("Time sampleAll: ${timeSampleAll}ms")

        println("================================")
        println("Test at different heights")
        val ore_y_1 = OreDeposit(Material.DIAMOND, 0.6, 1, 1, 0, 255)
        val ore_y_2 = OreDeposit(Material.EMERALD, 0.6, 1, 1, 50, 100)
        val ore_y_3 = OreDeposit(Material.GOLD_ORE, 0.6, 1, 1, 51, 51)
        val ore_y_4 = OreDeposit(Material.IRON_ORE, 0.6, 1, 1, 70, 255)
        println(ore_y_1)
        println(ore_y_2)
        println(ore_y_3)
        println(ore_y_4)

        // create ore sampler from ores
        val arrOre2: ArrayList<OreDeposit> = arrayListOf(ore_y_1, ore_y_2, ore_y_3, ore_y_4)
        val oreSamplerHeight = OreSampler(arrOre2)

        // test samples at different y levels
        val yToTest = arrayOf(25, 50, 51, 69, 80, 150)
        yToTest.forEach { y ->
            println("SAMPLING AT y=${y}")
            val currentDrops: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)
            val timeSample = measureTimeMillis {
                for ( i in 0 until COUNT ) {
                    val drops = oreSamplerHeight.sample(y)
                    drops.forEach { itemStack ->
                        val mat = itemStack.getType()
                        val amount = itemStack.getAmount()
                        currentDrops.get(mat)?.let { currentAmount ->
                            currentDrops.put(mat, currentAmount + amount)
                        } ?: run {
                            currentDrops.put(mat, amount)
                        }
                    }
                }
            }
            println(currentDrops)
            println("Time sampleAll: ${timeSample}ms")
        }
    }
     */
}
