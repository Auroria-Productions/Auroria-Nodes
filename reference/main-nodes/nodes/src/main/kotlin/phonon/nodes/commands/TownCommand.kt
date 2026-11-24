/**
 * /town (/s) command
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
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.ErrorTerritoryHasClaim
import phonon.nodes.constants.ErrorTerritoryIsTownHome
import phonon.nodes.constants.ErrorTerritoryNotConnected
import phonon.nodes.constants.ErrorTerritoryNotInTown
import phonon.nodes.constants.ErrorTooManyClaims
import phonon.nodes.constants.NODES_SOUND_CHEST_PROTECT
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.contracts.TownContractsGUI
import phonon.nodes.nametags.NametagUtils
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Minimap
import phonon.nodes.objects.PortTier
import phonon.nodes.objects.Resident
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.objects.WorldMap
import phonon.nodes.utils.Color
import phonon.nodes.utils.string.filterByStart
import phonon.nodes.utils.string.filterResident
import phonon.nodes.utils.string.filterTown
import phonon.nodes.utils.string.filterTownResident
import phonon.nodes.utils.stringInputIsValid
import phonon.nodes.war.WarAccessListManager
import phonon.nodes.warzone.WarzoneManager
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import phonon.nodes.MiningOutminerManager as ActiveMiningOutminerManager

// list of all subcommands, used for onTabComplete
private val SUBCOMMANDS: List<String> = listOf(
    "help",
    "create",
    "new",
    "delete",
    "disband",
    "officer",
    "promote",
    "demote",
    "leader",
    "apply",
    "join",
    "invite",
    "accept",
    "deny",
    "reject",
    "leave",
    "contract",
    "contracts",
    "kick",
    "spawn",
    "setspawn",
    "list",
    "info",
    "online",
    "onlinetop",
    "color",
    "claim",
    "unclaim",
    "income",
    "prefix",
    "suffix",
    "rename",
    "map",
    "minimap",
    "permissions",
    "protect",
    "trust",
    "untrust",
    "capital",
    "annex",
    "outpost",
    "market",
    "pointshop",
    "fly",
    "plot",
    "general",
    "leaderlaunch",
    "announce",
    "point",
    "rally",
    "pointdisable",
)

private val OUTPOST_SUBCOMMANDS: List<String> = listOf(
    "apply",
    "list",
    "setspawn",
)

private val GENERAL_SUBCOMMANDS: List<String> = listOf(
    "promote",
    "demote",
    "list",
)

private val LEADERLAUNCH_SUBCOMMANDS: List<String> = listOf(
    "status",
)

// town permissions types
private val PERMISSIONS_TYPES: List<String> = listOf(
    "build",
    "destroy",
    "interact",
    "chests",
    "items",
    "income",
)

// town permissions types
private val PERMISSIONS_GROUPS: List<String> = listOf(
    "town",
    "nation",
    "ally",
    "outsider",
    "trusted",
)

// town permissions flag
private val PERMISSIONS_FLAGS: List<String> = listOf(
    "allow",
    "deny",
)

// ==================================================
// Constants for /t map
//
// symbols
val SHADE = "\u2592" // medium shade
val HOME = "\u2588" // full solid block
val CORE = "\u256B" // core chunk H
val CONQUERED0 = "\u2561" // captured chunk
val CONQUERED1 = "\u255F" // other chunk flag symbol
val ATTACK_FLAG = "\u2691" // attack flag symbol
val SPACER = "\u17f2" // spacer

// No left padding: even a few spaces can push the map line over the client wrap limit.
val MAP_STR_BEGIN = ""

// Hard cap to avoid client chat line wrapping (color codes are zero-width).
// Keep this conservative; players hate wrapped map lines.
private const val MAP_MAX_TOKENS_PER_LINE = 36
private const val MAP_MAX_RADIUS_X = (MAP_MAX_TOKENS_PER_LINE - 1) / 2

private val COLOR_CODE_REGEX = Regex("§.")

private fun padLegendCell(text: String, width: Int): String {
    val visible = text.replace(COLOR_CODE_REGEX, "")
    val pad = (width - visible.length).coerceAtLeast(0)
    return text + " ".repeat(pad)
}

// Legend lines printed after the map (bottom), not down the side.
private val MAP_LEGEND_LINES: List<String> = listOf(
    "",
    run {
        val w = 22
        val c1 = padLegendCell("${ChatColor.GRAY}${SHADE}${ChatColor.DARK_GRAY}$SHADE${ChatColor.WHITE} - Unclaimed", w)
        val c2 = padLegendCell("${ChatColor.GREEN}${SHADE}${ChatColor.DARK_GREEN}$SHADE${ChatColor.WHITE} - Town", w)
        val c3 = padLegendCell("${ChatColor.BLUE}${SHADE}${ChatColor.DARK_BLUE}$SHADE${ChatColor.WHITE} - Nation", w)
        "$c1| $c2| $c3"
    },
    run {
        val w = 22
        val c1 = padLegendCell("${ChatColor.YELLOW}${SHADE}${ChatColor.GOLD}$SHADE${ChatColor.WHITE} - Neutral", w)
        val c2 = padLegendCell("${ChatColor.AQUA}${SHADE}${ChatColor.DARK_AQUA}$SHADE${ChatColor.WHITE} - Ally", w)
        val c3 = padLegendCell("${ChatColor.RED}${SHADE}${ChatColor.DARK_RED}$SHADE${ChatColor.WHITE} - Enemy", w)
        "$c1| $c2| $c3"
    },
    "${ChatColor.WHITE}$HOME Home | $CORE Core | $CONQUERED0/$CONQUERED1 Occupied | $ATTACK_FLAG Under attack",
)
// ==================================================

public class TownCommand :
    CommandExecutor,
    TabCompleter {
    private val marketCommand = MarketCommand()
    private val plotCommand = PlotCommand()

    /**
     * Per-town claim rate-limit tracker.
     * Maps town UUID → (claimsThisWindow, windowStartMs)
     * Reset each time a new window opens (windowStartMs + period <= now).
     * Cleared on plugin reload/restart — intentional, windows are rolling.
     */
    private val claimWindowTracker: HashMap<java.util.UUID, Pair<Int, Long>> = HashMap()

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        val player = if (sender is Player) sender else null

        // no args, print current town info
        if (args.size == 0) {
            if (player != null) {
                // print player's town info
                val resident = Nodes.getResident(player)
                if (resident != null && resident.town != null) {
                    resident.town!!.printInfo(player)
                }
                Message.print(player, "Use \"/town help\" to view commands")
            }
            return true
        }

        // parse subcommand
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)

            "new", "create" -> createTown(player, args)
            "delete" -> deleteTown(player)
            "disband" -> deleteTown(player)
            "officer" -> setOfficer(player, args, null)
            "promote" -> setOfficer(player, args, true)
            "demote" -> setOfficer(player, args, false)
            "leader" -> setLeader(player, args)
            "transfer" -> transferTerritory(player, args)
            "apply" -> appToTown(player, args)
            "join" -> appToTown(player, args)
            "invite" -> invite(player, args)
            "accept" -> accept(player, args)
            "deny" -> deny(player, args)
            "reject" -> deny(player, args)
            "leave" -> leaveTown(player)
            "kick" -> kickFromTown(player, args)
            "spawn" -> goToSpawn(player, args)
            "setspawn" -> setSpawn(player)
            "list" -> listTowns(player)
            "info" -> getInfo(player, args)
            "online" -> getOnline(player, args)
            "onlinetop" -> getOnlineTop(player)
            "color" -> setColor(player, args)
            "unclaim" -> unclaimTerritory(player)
            "claim" -> claimTerritory(player)
            "income" -> getIncome(player)
            "prefix" -> prefix(player, args)
            "suffix" -> suffix(player, args)
            "rename" -> renameTown(player, args)
            "map" -> printMap(player, args)
            "minimap" -> minimap(player, args)
            "perms",
            "transfer",
            "permissions",
            -> setPermissions(player, args)
            "protect" -> protectChests(player, args)
            "trust" -> trustPlayer(player, args, true)
            "untrust" -> trustPlayer(player, args, false)
            "capital" -> setCapital(player, args)
            "annex" -> annexTerritory(player, args)
            "outpost" -> manageOutpost(sender, args)
            "market" -> routeMarketSubcommand(player, args)
            "pointshop" -> openPointShop(player)
            "fly" -> toggleFlight(player)
            "plot" -> routePlotSubcommand(player, cmd, commandLabel, args)
            "general" -> setGeneral(player, args)
            "leaderlaunch" -> handleLeaderLaunch(player, args)
            "announce" -> announceTown(player, args)
            "point", "rally" -> pointTown(player, args)
            "pointdisable" -> togglePointDisable(player)
            "contract", "contracts" -> {
                if (player == null) {
                    Message.error(sender, "Only players can open the contracts menu.")
                } else {
                    TownContractsGUI.open(player)
                }
            }
            else -> {
                Message.error(sender, "Invalid command, use /town help")
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

        // match subcommand
        if (args.size == 1) {
            return filterByStart(SUBCOMMANDS, args[0])
        }
        // match each subcommand format
        else if (args.size > 1) {
            // handle specific subcommands
            when (args[0].lowercase()) {
                // /town [subcommand] [resident]
                "officer",
                "promote",
                "demote",
                "leader",
                -> {
                    if (args.size == 2) {
                        val town = Nodes.getResident(player)?.town
                        if (town != null) {
                            return filterTownResident(town, args[1])
                        }
                    }
                }

                // /town [subcommand] [player]
                "invite" -> {
                    if (args.size == 2) {
                        return filterResident(args[1])
                    }
                }

                // /town [subcommand] [town]
                "apply",
                "join",
                "accept",
                "reject",
                "deny",
                -> {
                    if (args.size == 2) {
                        return filterTown(args[1])
                    }
                }

                // /town [subcommand] [resident]
                "kick",
                "trust",
                "untrust",
                "prefix",
                "suffix",
                -> {
                    if (args.size == 2) {
                        val town = Nodes.getResident(player)?.town
                        if (town != null) {
                            return filterTownResident(town, args[1])
                        }
                    }
                }

                // /town info name
                "info" -> {
                    if (args.size == 2) {
                        return filterTown(args[1])
                    }
                }

                // /town online name
                "online" -> {
                    if (args.size == 2) {
                        return filterTown(args[1])
                    }
                }

                // /town permissions [type] [group] [flag]
                "permissions" -> {
                    if (args.size == 2) {
                        return filterByStart(PERMISSIONS_TYPES, args[1])
                    } else if (args.size == 3) {
                        return filterByStart(PERMISSIONS_GROUPS, args[2])
                    } else if (args.size == 4) {
                        return filterByStart(PERMISSIONS_FLAGS, args[3])
                    }
                }

                // chest protection
                "protect" -> {
                    if (args.size == 2) {
                        return listOf("show")
                    }
                }

                "transfer" -> {
                    if (args.size == 2) {
                        // Player's current location territory id
                        return listOf(Nodes.getTerritoryFromPlayer(player)?.id?.toString() ?: "")
                    } else if (args.size == 3) {
                        // Filter allied towns
                        val resident = Nodes.getResident(player)
                        val town = resident?.town
                        if (town != null) {
                            return town.allies.map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
                        }
                    }
                }

                // spawn command
                "spawn" -> {
                    if (args.size == 2) {
                        val town = Nodes.getResident(player)?.town
                        if (town != null) {
                            return filterByStart(town.outposts.keys.toList(), args[1])
                        }
                    }
                }

                // outpost subcommand
                "outpost" -> {
                    if (args.size == 2) {
                        return filterByStart(OUTPOST_SUBCOMMANDS, args[1])
                    }
                }
                "leaderlaunch" -> {
                    if (args.size == 2) {
                        return filterByStart(LEADERLAUNCH_SUBCOMMANDS, args[1])
                    }
                }
                "general" -> {
                    if (args.size == 2) {
                        return filterByStart(GENERAL_SUBCOMMANDS, args[1])
                    } else if (args.size == 3 && (args[1].equals("promote", true) || args[1].equals("demote", true))) {
                        val town = Nodes.getResident(player)?.town
                        if (town != null) {
                            return filterTownResident(town, args[2])
                        }
                    }
                }

                // town market delegates to /nemarket
                "market" -> {
                    val shifted = if (args.size > 1) args.copyOfRange(1, args.size) else emptyArray()
                    return marketCommand.onTabComplete(sender, command, alias, shifted)
                }
                "plot" -> {
                    val shifted = if (args.size > 1) args.copyOfRange(1, args.size) else emptyArray()
                    return plotCommand.onTabComplete(sender, command, alias, shifted)
                }
            }
        }

        return listOf()
    }

    private fun printHelp(sender: CommandSender?) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Town commands:")
        Message.print(sender, "/town create${ChatColor.WHITE}: Create town with name at location")
        Message.print(sender, "/town delete${ChatColor.WHITE}: Delete your town")
        Message.print(sender, "/town promote${ChatColor.WHITE}: Give officer rank to resident")
        Message.print(sender, "/town demote${ChatColor.WHITE}: Remove officer rank from resident")
        Message.print(sender, "/town apply${ChatColor.WHITE}: Apply to join a town")
        Message.print(sender, "/town invite${ChatColor.WHITE}: Invite a player to your town")
        Message.print(sender, "/town leave${ChatColor.WHITE}: Leave your town")
        Message.print(sender, "/town kick${ChatColor.WHITE}: Kick player from your town")
        Message.print(sender, "/town spawn${ChatColor.WHITE}: Teleport to your town spawnpoint")
        Message.print(sender, "/town setspawn${ChatColor.WHITE}: Set a new town spawnpoint")
        Message.print(sender, "/town list${ChatColor.WHITE}: List all towns")
        Message.print(sender, "/town info${ChatColor.WHITE}: View town details")
        Message.print(sender, "/town online${ChatColor.WHITE}: View town's online players")
        Message.print(sender, "/town color${ChatColor.WHITE}: Set town color on map")
        Message.print(sender, "/town unclaim${ChatColor.WHITE}: Unclaim territory at current location")
        Message.print(sender, "/town prefix${ChatColor.WHITE}: Set player name prefix")
        Message.print(sender, "/town suffix${ChatColor.WHITE}: Set player name suffix")
        Message.print(sender, "/town rename${ChatColor.WHITE}: Rename town")
        Message.print(sender, "/town map${ChatColor.WHITE}: View world map")
        Message.print(sender, "/town minimap${ChatColor.WHITE}: Toggle sidebar world minimap")
        Message.print(sender, "/town permissions${ChatColor.WHITE}: Set town protection permissions")
        Message.print(sender, "/town protect${ChatColor.WHITE}: Protect town chests")
        Message.print(sender, "/town trust${ChatColor.WHITE}: Mark player as trusted")
        Message.print(sender, "/town untrust${ChatColor.WHITE}: Remove player from trusted")
        Message.print(sender, "/town capital${ChatColor.WHITE}: Set your town's home territory")
        Message.print(sender, "/town annex${ChatColor.WHITE}: Annex an occupied territory")
        Message.print(sender, "/town outpost${ChatColor.WHITE}: Town outpost commands")
        Message.print(sender, "/town market${ChatColor.WHITE}: Open and manage town market")
        Message.print(sender, "/town contracts${ChatColor.WHITE}: View and complete town contracts for income rewards")
        Message.print(sender, "/town pointshop${ChatColor.WHITE}: Open town points shop")
        Message.print(sender, "/town fly${ChatColor.WHITE}: Fly inside your town")
        Message.print(sender, "/town plot${ChatColor.WHITE}: Manage town plots")
        Message.print(sender, "/town general${ChatColor.WHITE}: Manage town generals")
        Message.print(sender, "/town leaderlaunch${ChatColor.WHITE}: Show staff-managed Leader Launch info")
        Message.print(sender, "/town announce${ChatColor.WHITE}: Broadcast title to town members")
        Message.print(sender, "/town point${ChatColor.WHITE}: Create rally beam for town members")
        Message.print(sender, "/town rally${ChatColor.WHITE}: Alias for /town point")
        Message.print(sender, "/town pointdisable${ChatColor.WHITE}: Toggle town point visibility for yourself")
        return
    }

    /**
     * @command /town create [name]  (alias: /town new [name])
     * Create a new town at the player's current unclaimed territory.
     *
     * Cost (when townCreateCostEnabled = true):
     *   total = townCreateCostFlatFee + territory.cost
     *   territory.cost = territoryCostBase + territoryCostScale * chunks.size
     */
    private fun createTown(player: Player?, args: Array<String>) {
        if (player == null) return

        if (!player.hasPermission("nodes.command.town.create")) {
            Message.error(player, "You do not have permission to create a town")
            return
        }

        if (args.size < 2) {
            Message.error(player, "Usage: /town create [name]")
            return
        }

        val name = args[1]
        if (!stringInputIsValid(name, 32)) {
            Message.error(player, "Invalid town name (max 32 characters, alphanumeric/underscore only)")
            return
        }

        val resident = Nodes.getResident(player) ?: run {
            Message.error(player, "Could not find your player data")
            return
        }

        if (resident.town != null) {
            Message.error(player, "You are already in a town")
            return
        }

        if (resident.townCreateCooldown > 0) {
            val minutesLeft = TimeUnit.MILLISECONDS.toMinutes(resident.townCreateCooldown)
            Message.error(player, "You must wait $minutesLeft more minute(s) before creating a town")
            return
        }

        val territory = Nodes.getTerritoryFromPlayer(player) ?: run {
            Message.error(player, "You are not standing in a valid territory")
            return
        }

        if (territory.town != null) {
            Message.error(player, "This territory is already claimed by another town")
            return
        }

        // ── Vault economy charge ─────────────────────────────────────────────
        var amountCharged = 0.0
        if (Config.townCreateCostEnabled) {
            val economy = Nodes.economy
            if (economy == null) {
                Message.error(player, "Economy service is unavailable; cannot process town creation payment")
                return
            }
            val totalCost = Config.townCreateCostFlatFee + territory.cost
            val balance = economy.getBalance(player)
            if (balance < totalCost) {
                Message.error(
                    player,
                    "You need ${economy.format(totalCost)} to create a town here " +
                        "(base fee ${economy.format(Config.townCreateCostFlatFee)} + " +
                        "territory cost ${territory.cost})",
                )
                return
            }
            val response = economy.withdrawPlayer(player, totalCost)
            if (!response.transactionSuccess()) {
                Message.error(player, "Payment failed: ${response.errorMessage}")
                return
            }
            amountCharged = totalCost
            Message.print(
                player,
                "${ChatColor.YELLOW}${economy.format(totalCost)} deducted " +
                    "(base ${economy.format(Config.townCreateCostFlatFee)} + territory cost ${territory.cost})",
            )
        }
        // ────────────────────────────────────────────────────────────────────

        val result = Nodes.createTown(name, territory, resident)
        result.fold(
            onSuccess = {
                Message.print(player, "${ChatColor.GREEN}Town \"${name}\" created successfully!")
                resident.townCreateCooldown = Config.townCreateCooldown
            },
            onFailure = { err ->
                if (amountCharged > 0.0) {
                    Nodes.economy?.depositPlayer(player, amountCharged)
                    Message.print(player, "${ChatColor.YELLOW}Payment of ${Nodes.economy?.format(amountCharged) ?: "$amountCharged"} refunded")
                }
                Message.error(player, err.message ?: "Failed to create town")
            },
        )
    }

    /**
     * @command /town claim
     * Claim the territory the player is currently standing in.
     * Only town leaders and officers may use this command.
     *
     * Money cost (when townClaimCostEnabled = true):
     *   cost = territory.cost (same value as claim power cost, varies by chunk count and resource nodes)
     *
     * Player ratio cap (when townClaimPlayerRatioEnabled = true):
     *   max territories = residents * townClaimPlayerRatioPerPlayer
     *
     * Rate limit (when townClaimCooldownEnabled = true):
     *   max townClaimCooldownMaxClaims claims per townClaimCooldownPeriod window.
     */
    private fun claimTerritory(player: Player?) {
        if (player == null) return

        if (!player.hasPermission("nodes.command.town.claim")) {
            Message.error(player, "You do not have permission to claim territory")
            return
        }

        val resident = Nodes.getResident(player)
        val town = resident?.town
        if (town == null) {
            Message.error(player, "You do not belong to a town")
            return
        }

        // Leaders and officers only
        if (resident !== town.leader && !town.officers.contains(resident)) {
            Message.error(player, "Only town leaders and officers can claim territory")
            return
        }

        val territory = Nodes.getTerritoryFromPlayer(player) ?: run {
            Message.error(player, "This chunk has no territory")
            return
        }

        // ── Claim rate-limit check ───────────────────────────────────────────
        if (Config.townClaimCooldownEnabled) {
            val now = System.currentTimeMillis()
            val window = claimWindowTracker[town.uuid]
            if (window != null) {
                val (claimsInWindow, windowStart) = window
                val windowExpired = now >= windowStart + Config.townClaimCooldownPeriod
                if (!windowExpired && claimsInWindow >= Config.townClaimCooldownMaxClaims) {
                    val msRemaining = (windowStart + Config.townClaimCooldownPeriod) - now
                    val hoursLeft = TimeUnit.MILLISECONDS.toHours(msRemaining)
                    val minutesLeft = TimeUnit.MILLISECONDS.toMinutes(msRemaining) % 60
                    Message.error(
                        player,
                        "Your town has reached its claim limit (${Config.townClaimCooldownMaxClaims} per ${TimeUnit.MILLISECONDS.toHours(Config.townClaimCooldownPeriod)}h). " +
                            "Window resets in ${hoursLeft}h ${minutesLeft}m",
                    )
                    return
                }
            }
        }
        // ────────────────────────────────────────────────────────────────────

        // ── Player ratio check ───────────────────────────────────────────────
        if (Config.townClaimPlayerRatioEnabled) {
            val maxTerritories = town.residents.size * Config.townClaimPlayerRatioPerPlayer
            if (town.territories.size >= maxTerritories) {
                Message.error(
                    player,
                    "Your town cannot claim more territories (${town.territories.size}/$maxTerritories). " +
                        "You need more residents — current: ${town.residents.size}, " +
                        "ratio: ${Config.townClaimPlayerRatioPerPlayer} territory/player",
                )
                return
            }
        }
        // ────────────────────────────────────────────────────────────────────

        // ── Money cost per claim (uses territory.cost so each node has a different price) ──
        var amountCharged = 0.0
        if (Config.townClaimCostEnabled) {
            val economy = Nodes.economy
            if (economy == null) {
                Message.error(player, "Economy service is unavailable; cannot process claim payment")
                return
            }
            val cost = territory.cost.toDouble()
            val balance = economy.getBalance(player)
            if (balance < cost) {
                Message.error(player, "You need ${economy.format(cost)} to claim this territory")
                return
            }
            val response = economy.withdrawPlayer(player, cost)
            if (!response.transactionSuccess()) {
                Message.error(player, "Payment failed: ${response.errorMessage}")
                return
            }
            amountCharged = cost
            Message.print(player, "${ChatColor.YELLOW}${economy.format(cost)} deducted for claiming territory")
        }
        // ────────────────────────────────────────────────────────────────────

        val result = Nodes.claimTerritory(town, territory)
        if (result.isSuccess) {
            // Record claim in the rate-limit window
            if (Config.townClaimCooldownEnabled) {
                val now = System.currentTimeMillis()
                val existing = claimWindowTracker[town.uuid]
                if (existing == null || now >= existing.second + Config.townClaimCooldownPeriod) {
                    claimWindowTracker[town.uuid] = Pair(1, now)
                } else {
                    claimWindowTracker[town.uuid] = Pair(existing.first + 1, existing.second)
                }
                val (claimsNow, _) = claimWindowTracker[town.uuid]!!
                val remaining = Config.townClaimCooldownMaxClaims - claimsNow
                Message.print(player, "${ChatColor.GREEN}Territory (id=${territory.id}) claimed! ($remaining claim(s) remaining this window)")
            } else {
                Message.print(player, "${ChatColor.GREEN}Territory (id=${territory.id}) claimed!")
            }
        } else {
            // refund if claim failed
            if (amountCharged > 0.0) {
                Nodes.economy?.depositPlayer(player, amountCharged)
                Message.print(player, "${ChatColor.YELLOW}${Nodes.economy?.format(amountCharged) ?: "$amountCharged"} refunded")
            }
            when (result.exceptionOrNull()) {
                ErrorTerritoryHasClaim -> Message.error(player, "This territory is already claimed")
                ErrorTerritoryNotConnected -> Message.error(player, "Territory must be connected to your town's existing territories")
                ErrorTooManyClaims -> Message.error(player, "Your town does not have enough claim power for this territory (cost=${territory.cost}, available=${town.claimsMax - town.claimsUsed})")
                else -> Message.error(player, result.exceptionOrNull()?.message ?: "Failed to claim territory")
            }
        }
    }

    private fun routePlotSubcommand(player: Player?, command: Command, alias: String, args: Array<String>) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player) ?: run {
            Message.error(player, "Could not resolve resident")
            return
        }
        val town = resident.town ?: run {
            Message.error(player, "You are not a member of a town")
            return
        }
        val shifted = if (args.size > 1) args.copyOfRange(1, args.size) else emptyArray()
        val sub = shifted.firstOrNull()?.lowercase()

        // allow regular town members to use only /t plot show
        if (sub == "show") {
            plotCommand.onCommand(player, command, alias, shifted)
            return
        }

        if (resident !== town.leader && !town.officers.contains(resident)) {
            Message.error(player, "Only town leader/officers can use plot commands")
            return
        }

        plotCommand.onCommand(player, command, alias, shifted)
    }

    private fun routeMarketSubcommand(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        val shifted = if (args.size > 1) args.copyOfRange(1, args.size) else emptyArray()
        val marketPluginCommand = player.server.getPluginCommand("nemarket")
        if (marketPluginCommand == null) {
            Message.error(player, "Market command is not available")
            return
        }

        marketCommand.onCommand(player, marketPluginCommand, "nemarket", shifted)
    }

    /**
     * @command /town create [name]
     * Create a new town with the specified name at location.
     */

    private fun transferTerritory(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        if (args.size < 3) {
            Message.error(player, "Usage: /town transfer [territoryId] [targetTown]")
            return
        }

        val resident = Nodes.getResident(player)
        val sourceTown = resident?.town
        if (sourceTown == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        // Check if player is leader or officer
        if (resident !== sourceTown.leader && !sourceTown.officers.contains(resident)) {
            Message.error(player, "Only leaders and officers can transfer territories")
            return
        }

        // Get territory
        val territoryId = try {
            TerritoryId(args[1].toInt())
        } catch (e: NumberFormatException) {
            Message.error(player, "Invalid territory ID")
            return
        }

        val territory = Nodes.getTerritoryFromId(territoryId)
        if (territory == null) {
            Message.error(player, "Territory with ID ${args[1]} does not exist")
            return
        }

        // Check if source town owns the territory
        if (territory.town !== sourceTown) {
            Message.error(player, "Your town does not own this territory")
            return
        }

        // Check if it's the home territory
        if (sourceTown.home == territoryId) {
            Message.error(player, "Cannot transfer your town's home territory")
            return
        }

        // Get target town
        val targetTownName = args[2]
        val targetTown = Nodes.getTownFromName(targetTownName)
        if (targetTown == null) {
            Message.error(player, "Town \"$targetTownName\" does not exist")
            return
        }

        // Check if towns are allies
        if (!sourceTown.allies.contains(targetTown)) {
            Message.error(player, "You can only transfer territories to allied towns")
            return
        }

        // Check if territory is connected to target town
        var isConnectedToTarget = false
        for (neighborId in territory.neighbors) {
            if (Nodes.getTerritoryFromId(neighborId)?.town === targetTown) {
                isConnectedToTarget = true
                break
            }
        }

        if (!isConnectedToTarget) {
            Message.error(player, "Territory must be connected to the target town's territories")
            return
        }

        // Transfer the territory
        // Remove from source town
        sourceTown.territories.remove(territoryId)
        if (sourceTown.annexed.contains(territoryId)) {
            sourceTown.annexed.remove(territoryId)
        } else {
            sourceTown.claimsUsed -= territory.cost
        }
        sourceTown.claimsMax = Nodes.calculateMaxClaims(sourceTown)

        // Add to target town
        targetTown.territories.add(territoryId)
        targetTown.claimsUsed += territory.cost
        targetTown.claimsMax = Nodes.calculateMaxClaims(targetTown)
        territory.town = targetTown

        // Mark dirty
        sourceTown.needsUpdate()
        targetTown.needsUpdate()
        Nodes.needsSave = true

        // Re-render minimaps
        Nodes.renderMinimaps()

        Message.print(player, "Transferred territory (id=$territoryId) to ${targetTown.name}")

        // Notify target town
        for (r in targetTown.residents) {
            val p = r.player()
            if (p !== null) {
                Message.print(p, "${sourceTown.name} has transferred territory (id=$territoryId) to your town")
            }
        }
    }

    /**
     * @command /town delete
     * Delete your town. Town leaders only.
     */
    private fun deleteTown(player: Player?) {
        if (player == null) {
            return
        }

        // check if player is town leader
        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        val town = resident.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        val leader = town.leader
        if (resident !== leader && !town.generals.contains(resident)) {
            Message.error(player, "Only town leader or generals can manage officers")
            return
        }

        val nation = town.nation
        if (nation !== null && town === nation.capital) {
            Message.error(player, "You cannot destroy your town as the nation capital, use /n delete first")
            return
        }

        // do not allow during war
        if (!Config.canDestroyTownDuringWar && Nodes.war.enabled == true) {
            Message.error(player, "Cannot delete your town during war")
            return
        }

        Nodes.destroyTown(town)

        // add player penalty for destroying town
        Nodes.setResidentTownCreateCooldown(resident, Config.townCreateCooldown)

        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}The town \"${town.name}\" has been destroyed...")
    }

    /**
     * @command /town promote [name]
     * Makes player a town officer.
     *
     * @command /town demote [name]
     * Removes player from town officers.
     */
    private fun setOfficer(player: Player?, args: Array<String>, toggle: Boolean?) {
        if (player == null || args.size < 2) {
            Message.error(player, "Usage: /t promote/demote [player]")
            return
        }

        // check if player is town leader
        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        val town = resident.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        val leader = town.leader
        if (resident !== leader && !town.generals.contains(resident)) {
            Message.error(player, "Only town leader or generals can manage officers")
            return
        }

        // get other resident
        val target = Nodes.getResidentFromName(args[1])
        if (target === null) {
            Message.error(player, "Player not found")
            return
        }
        if (target === resident) {
            Message.error(player, "You are already the town leader")
            return
        }

        val targetTown = target.town
        if (targetTown !== town) {
            Message.error(player, "Player is not in this town")
            return
        }

        val targetPlayer = target.player()

        // if target is general, officer management requires general demotion first
        if (town.generals.contains(target) && (toggle == false || toggle == null)) {
            Message.error(player, "Demote this player from general first")
            return
        }

        // if null, toggle officer
        if (toggle === null) {
            if (town.officers.contains(target)) {
                Nodes.townRemoveOfficer(town, target)
                Message.print(player, "Removed ${target.name} from town officers")

                if (targetPlayer !== null) {
                    Message.error(targetPlayer, "You are no longer a town officer")
                }
            } else {
                Nodes.townAddOfficer(town, target)
                Message.print(player, "Made ${target.name} a town officer")

                if (targetPlayer !== null) {
                    Message.print(targetPlayer, "You are now a town officer")
                }
            }
        }
        // add officer
        else if (toggle === true) {
            if (!town.officers.contains(target)) {
                Nodes.townAddOfficer(town, target)
                Message.print(player, "Made ${target.name} a town officer")

                if (targetPlayer !== null) {
                    Message.print(targetPlayer, "You are now a town officer")
                }
            }
        }
        // remove officer
        else if (toggle === false) {
            if (town.officers.contains(target)) {
                Nodes.townRemoveOfficer(town, target)
                Message.print(player, "Removed ${target.name} from town officers")

                if (targetPlayer !== null) {
                    Message.error(targetPlayer, "You are no longer a town officer")
                }
            }
        }
    }

    private fun setGeneral(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player) ?: return
        val town = resident.town ?: run {
            Message.error(player, "You are not a member of a town")
            return
        }

        if (resident !== town.leader) {
            Message.error(player, "Only town leaders can manage generals")
            return
        }

        if (args.size < 2) {
            Message.error(player, "Usage: /t general <promote|demote|list> [player]")
            return
        }

        when (args[1].lowercase()) {
            "list" -> {
                val names = if (town.generals.isEmpty()) "${ChatColor.GRAY}None" else town.generals.joinToString(", ") { it.name }
                Message.print(player, "Generals[${town.generals.size}]: ${ChatColor.WHITE}$names")
            }
            "promote" -> {
                if (args.size < 3) {
                    Message.error(player, "Usage: /t general promote <player>")
                    return
                }
                val target = Nodes.getResidentFromName(args[2]) ?: run {
                    Message.error(player, "Player not found")
                    return
                }
                if (target.town !== town) {
                    Message.error(player, "Player is not in this town")
                    return
                }
                if (target === town.leader) {
                    Message.error(player, "Leader is already above general")
                    return
                }
                Nodes.townAddGeneral(town, target)
                Message.print(player, "Promoted ${target.name} to General")
                target.player()?.let { Message.print(it, "${ChatColor.GOLD}You are now a General of ${town.name}") }
            }
            "demote" -> {
                if (args.size < 3) {
                    Message.error(player, "Usage: /t general demote <player>")
                    return
                }
                val target = Nodes.getResidentFromName(args[2]) ?: run {
                    Message.error(player, "Player not found")
                    return
                }
                if (target.town !== town) {
                    Message.error(player, "Player is not in this town")
                    return
                }
                if (!town.generals.contains(target)) {
                    Message.error(player, "Player is not a general")
                    return
                }
                Nodes.townRemoveGeneral(town, target)
                Message.print(player, "Demoted ${target.name} from General")
                target.player()?.let { Message.print(it, "${ChatColor.YELLOW}You are no longer a General of ${town.name}") }
            }
            else -> {
                Message.error(player, "Usage: /t general <promote|demote|list> [player]")
            }
        }
    }

    private fun handleLeaderLaunch(player: Player?, _args: Array<String>) {
        if (player == null) {
            return
        }

        printTownLeaderLaunchHelp(player)
    }

    private fun printTownLeaderLaunchHelp(sender: CommandSender?) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Leader Launch is managed by staff.")
        Message.print(sender, "Leader Launch access is global, not per town.")
        Message.print(sender, "/nodesadmin leaderlaunch on|off${ChatColor.WHITE}: Toggle global Leader Launch access")
        Message.print(sender, "/nodesadmin leaderlaunch add/remove <player...>${ChatColor.WHITE}: Manage the allowed player list")
        Message.print(sender, "/nodesadmin leaderlaunch status${ChatColor.WHITE}: Show the current global status")
    }

    private fun announceTown(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player) ?: return
        val town = resident.town ?: run {
            Message.error(player, "You are not in a town")
            return
        }

        if (resident !== town.leader && !town.generals.contains(resident)) {
            Message.error(player, "Only town leader or generals can announce")
            return
        }

        if (args.size < 2) {
            Message.error(player, "Usage: /t announce <text>")
            return
        }

        val text = args.copyOfRange(1, args.size).joinToString(" ").trim()
        if (text.isEmpty()) {
            Message.error(player, "Announcement text cannot be empty")
            return
        }

        for (member in town.playersOnline) {
            Message.title(member, "${ChatColor.GOLD}${ChatColor.BOLD}$text", "", 5, 70, 15)
        }
    }

    private fun pointTown(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player) ?: return
        val town = resident.town ?: run {
            Message.error(player, "You are not in a town")
            return
        }

        if (resident !== town.leader && !town.generals.contains(resident)) {
            Message.error(player, "Only town leader or generals can create rally points")
            return
        }

        if (args.size < 2) {
            Message.error(player, "Usage: /t point <n>")
            return
        }

        val name = args.copyOfRange(1, args.size).joinToString(" ").trim()
        if (name.isEmpty()) {
            Message.error(player, "Point name cannot be empty")
            return
        }

        Message.print(player, "${ChatColor.GREEN}Town point '$name' marker created")
        for (member in town.playersOnline) {
            if (member.uniqueId == player.uniqueId) {
                continue
            }
            Message.print(member, "${ChatColor.AQUA}[Town Point] ${ChatColor.WHITE}$name set at ${player.location.blockX}, ${player.location.blockY}, ${player.location.blockZ}")
        }
    }

    private fun togglePointDisable(player: Player?) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player) ?: return
        resident.isTownPointDisabled = !resident.isTownPointDisabled
        resident.needsUpdate()
        Nodes.needsSave = true

        if (resident.isTownPointDisabled) {
            Message.print(player, "${ChatColor.YELLOW}Town point markers disabled.")
        } else {
            Message.print(player, "${ChatColor.GREEN}Town point markers enabled.")
        }
    }

    /**
     * @command /town leader [name]
     * Give town leadership to another player in town.
     */
    private fun setLeader(player: Player?, args: Array<String>) {
        if (player == null || args.size < 2) {
            Message.error(player, "Usage: /t leader [player]")
            return
        }

        // check if player is town leader
        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        val town = resident.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        val leader = town.leader
        if (resident !== leader) {
            Message.error(player, "You are not the town leader")
            return
        }

        // get other resident
        val target = Nodes.getResidentFromName(args[1])
        if (target === null) {
            Message.error(player, "This player does not exist")
            return
        }
        if (target === resident) {
            Message.error(player, "You are already the town leader")
            return
        }

        val targetTown = target.town
        if (targetTown !== town) {
            Message.error(player, "Player is not in this town")
            return
        }

        Nodes.townSetLeader(town, target)
        Message.print(player, "You made ${target.name} the new leader of ${town.name}")

        val targetPlayer = target.player()
        if (targetPlayer !== null) {
            Message.print(targetPlayer, "You are now the leader of ${town.name}")
        }
    }

    /**
     * @command /town apply [town]
     * Ask to join a town.
     */
    private fun appToTown(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        if (args.size != 2) {
            Message.print(player, "Usage: /town apply [town]")
            return
        }

        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        if (resident.town != null) {
            Message.error(player, "You are already a member of a town")
            return
        }

        // Rejoin cooldown check
        if (Config.townRejoinCooldownEnabled && resident.townRejoinCooldown > 0) {
            val hours = TimeUnit.MILLISECONDS.toHours(resident.townRejoinCooldown)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(resident.townRejoinCooldown) % 60
            Message.error(player, "You must wait ${hours}h ${minutes}m before joining a town again")
            return
        }

        val town = Nodes.getTownFromName(args[1])
        if (town == null) {
            Message.error(player, "That town does not exist")
            return
        }

        if (town.isOpen == true) {
            Nodes.addResidentToTown(town, resident)
            Message.print(player, "You are now a resident of ${town.name}!")
            return
        }

        if (town.applications.containsKey(resident)) {
            Message.error(player, "You have already applied to ${town.name}")
            return
        }

        val approvers = buildList {
            town.leader?.player()?.let { add(it) }
            town.officers.forEach { officer -> officer.player()?.let { add(it) } }
        }

        if (approvers.isEmpty()) {
            Message.error(player, "There are no officers online from ${town.name} to receive your application")
            return
        }

        approvers.forEach { approver ->
            Message.print(approver, "${resident.name} has applied to join to your town. \nType \"/t accept\" to let them in or \"/t reject\" to refuse the offer.")
        }
        Message.print(player, "Your application has been sent")

        town.applications.put(
            resident,
            Bukkit.getAsyncScheduler().runDelayed(
                Nodes.plugin!!,
                { _ ->
                    player.scheduler.run(
                        Nodes.plugin!!,
                        { _ ->
                            if (resident.town == null) {
                                Message.print(player, "No one in ${town.name} responded to your application!")
                                town.applications.remove(resident)
                            }
                        },
                        null,
                    )
                },
                60000,
                TimeUnit.MILLISECONDS,
            ),
        )
    }

    /**
     * @command /town invite [player]
     * Invite a player to join your town. Town leader and officers only.
     */
    private fun invite(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        if (args.size != 2) {
            Message.print(player, "Usage: /town invite [player]")
            return
        }

        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }
        val town = resident.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        val invitee: Player? = Bukkit.getPlayer(args[1])
        if (invitee == null) {
            Message.error(player, "That player is not online")
            return
        } else if (invitee == player) {
            Message.error(player, "You're already in your town")
            return
        }

        val inviteeResident = Nodes.getResident(invitee)
        if (inviteeResident == null) {
            return
        }
        if (inviteeResident.invitingTown == town) {
            Message.error(player, "This player has already been invited to the town")
            return
        } else if (inviteeResident.invitingTown != null) {
            Message.error(player, "This player is considering another town invitation")
            return
        }
        val inviteeTown = inviteeResident.town
        if (inviteeTown != null) {
            Message.error(player, "This player is already a member of a town")
            return
        }

        if (town.leader === resident || town.officers.contains(resident)) {
            Message.print(player, "${invitee.name} has been invited to your town.")
            Message.print(invitee, "You have been invited to become a member of ${town.name}.\nType \"/t accept\" to join the town or \"/t reject\" to refuse the offer.")
            inviteeResident.invitingTown = town
            inviteeResident.invitingPlayer = player
            inviteeResident.inviteThread = Bukkit.getAsyncScheduler().runDelayed(
                Nodes.plugin!!,
                { _ ->
                    player.scheduler.run(
                        Nodes.plugin!!,
                        { _ ->
                            if (inviteeResident.invitingPlayer == player) {
                                Message.print(player, "${invitee.name} didn't respond to your town invitation!")
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
        } else {
            Message.error(player, "You are not allowed to invite new members")
        }
    }

    private fun accept(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        val town = resident.town
        if (town == null) {
            if (resident.invitingTown == null) {
                Message.error(player, "You have not been invited to any town or your invitation expired")
                return
            }

            // Rejoin cooldown check
            if (Config.townRejoinCooldownEnabled && resident.townRejoinCooldown > 0) {
                val hours = TimeUnit.MILLISECONDS.toHours(resident.townRejoinCooldown)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(resident.townRejoinCooldown) % 60
                Message.error(player, "You must wait ${hours}h ${minutes}m before joining a town again")
                return
            }

            Message.print(player, "You are now a member of ${resident.invitingTown?.name}! Type \"/t spawn\" to teleport to your new town.")
            Message.print(resident.invitingPlayer, "${resident.name} has accepted your invitation!")

            Nodes.addResidentToTown(resident.invitingTown!!, resident)
            resident.invitingTown = null
            resident.invitingPlayer = null
            resident.inviteThread = null
        } else {
            if (town.leader != resident && !town.officers.contains(resident)) {
                Message.error(player, "You aren't allowed to consider town applications")
                return
            }

            if (town.applications.isEmpty()) {
                Message.error(player, "There are no active applications")
                return
            }

            var applicant: Resident = resident
            if (town.applications.size == 1) {
                town.applications.forEach { k, v ->
                    applicant = k
                }
                if (args.size > 1 && args[1].lowercase() != applicant.name.lowercase()) {
                    Message.error(player, "That player has not applied or their application has expired")
                    return
                }
            } else {
                if (args.size == 1) {
                    val applicantsString = town.applications.map { application -> application.key.name }.joinToString(", ")
                    Message.print(player, "There are multiple town applications. Please use \"/town accept [player]\".\nCurrent applicants: $applicantsString")
                    return
                }

                val lookedUpApplicant = Nodes.getResidentFromName(args[1]) ?: run {
                    Message.error(player, "That player has not applied or their application has expired")
                    return
                }
                if (!town.applications.containsKey(lookedUpApplicant)) {
                    Message.error(player, "That player has not applied or their application has expired")
                    return
                }
                applicant = lookedUpApplicant
            }

            Message.print(player, "${applicant.name} has been accepted into your town!")
            val applicantPlayer = applicant.player()

            // Rejoin cooldown check — block the accept if the applicant is on cooldown
            if (Config.townRejoinCooldownEnabled && applicant.townRejoinCooldown > 0) {
                val hours = TimeUnit.MILLISECONDS.toHours(applicant.townRejoinCooldown)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(applicant.townRejoinCooldown) % 60
                Message.error(player, "${applicant.name} cannot join a town for another ${hours}h ${minutes}m (rejoin cooldown)")
                if (applicantPlayer != null) {
                    Message.error(applicantPlayer, "You cannot join a town for another ${hours}h ${minutes}m (rejoin cooldown)")
                }
                town.applications.remove(applicant)
                return
            }

            Nodes.addResidentToTown(town, applicant)
            town.applications.remove(applicant)
        }
    }

    private fun deny(player: Player?, args: Array<String>) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        val town = resident.town
        if (town == null) {
            if (resident.invitingTown == null) {
                Message.error(player, "You have not been invited to any town or your invitation expired")
                return
            }

            Message.print(player, "You have rejected the invitation to join ${resident.invitingTown?.name}")
            Message.print(resident.invitingPlayer, "${resident.name} has rejected your invitation!")
            resident.invitingTown = null
            resident.invitingPlayer = null
            resident.inviteThread = null
        } else {
            if (town.leader != resident && !town.officers.contains(resident)) {
                Message.error(player, "You aren't allowed to consider town applications")
                return
            }

            if (town.applications.isEmpty()) {
                Message.error(player, "There are no active applications")
                return
            }

            var applicant: Resident = resident
            if (town.applications.size == 1) {
                town.applications.forEach { k, v ->
                    applicant = k
                }
                if (args.size > 1 && args[1] != applicant.name) {
                    Message.error(player, "That player has not applied or their application has expired")
                    return
                }
            } else {
                if (args.size == 1) {
                    val applicantsString = town.applications.map { application -> application.key.name }.joinToString(", ")
                    Message.print(player, "There are multiple town applications. Please use \"/town accept [player]\".\nCurrent applicants: $applicantsString")
                    return
                }

                applicant = Nodes.getResidentFromName(args[1]) ?: run {
                    Message.error(player, "That player has not applied or their application has expired")
                    return
                }
                if (!town.applications.containsKey(applicant)) {
                    Message.error(player, "That player has not applied or their application has expired")
                    return
                }
            }

            Message.print(player, "${applicant.name} has been denied residence in your town!")
            val applicantPlayer = applicant.player()

            town.applications.remove(applicant)
        }
    }

    /**
     * @command /town leave
     * Abandon membership in your town.
     */
    private fun leaveTown(player: Player?) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        val town = resident.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        if (town.leader == resident) {
            Message.error(player, "You must transfer leadership before leaving the town")
            return
        }

        // do not allow during war?
        if (!Config.canLeaveTownDuringWar && Nodes.war.enabled == true) {
            Message.error(player, "Cannot leave your town during war")
            return
        }

        Message.print(player, "You have left ${town.name}")
        Nodes.removeResidentFromTown(town, resident)
    }

    /**
     * @command /town kick [player]
     * Remove another player from your town. Town leader and officers only.
     */
    private fun kickFromTown(player: Player?, args: Array<String>) {
        if (player === null || args.size < 2) {
            Message.error(player, "Usage: /t kick [player]")
            return
        }

        val resident = Nodes.getResident(player)
        if (resident === null) {
            return
        }

        val town = resident.town
        if (town === null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        // check if player is leader or officer
        val leader = town.leader
        if (resident !== leader && !town.officers.contains(resident)) {
            Message.error(player, "Only leaders and officers can kick players")
            return
        }

        // get other resident
        val target = Nodes.getResidentFromName(args[1])
        if (target === null) {
            Message.error(player, "Player not found")
            return
        }

        val targetTown = target.town
        if (targetTown !== town) {
            Message.error(player, "Player is not in this town")
            return
        }

        // cannot kick the leader
        if (target === leader) {
            Message.error(player, "You cannot kick the leader")
            return
        }

        // officers cannot be kicked by anyone other than the leader
        if (town.officers.contains(target) && resident !== leader) {
            Message.error(player, "You cannot kick the leader or other officers")
            return
        }

        Message.print(player, "You have kicked ${target.name} from the town")

        val targetPlayer = target.player()
        if (targetPlayer !== null) {
            Message.print(targetPlayer, "${ChatColor.DARK_RED}You have been kicked from ${town.name}")
        }

        Nodes.removeResidentFromTown(town, target)
    }

    /**
     * @command /town spawn
     * Teleport to your town's main spawnpoint.
     *
     * @subcommand /town spawn [outpost]
     * Teleport to an outpost's spawn point.
     */
    private fun goToSpawn(player: Player?, args: Array<String>) {
        if (player === null) {
            return
        }

        val resident = Nodes.getResident(player)
        if (resident === null) {
            return
        }

        val town = resident.town
        if (town === null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        // check if already trying to teleport
        if (resident.teleportThread !== null) {
            Message.error(player, "You are already trying to teleport")
            return
        }

        if (ActiveMiningOutminerManager.isTownSpawnBlocked(player.location)) {
            Message.error(player, "You cannot use /town spawn within 50 blocks of a Mining Outpost")
            return
        }

        // parse spawn destination
        var outpostWarpTicks: Double? = null
        val destination = if (args.size > 1) {
            val outpostName = args[1]
            val outpost = town.outposts.get(outpostName)
            if (outpost !== null) {
                if (town.outpostCredits < 1) {
                    Message.error(player, "Your town has no outpost credits left")
                    return
                }

                // pay item cost to teleport
                if (Config.outpostTeleportCost.size > 0) {
                    val inventory = player.getInventory()
                    for ((material, amount) in Config.outpostTeleportCost) {
                        val items = ItemStack(material)
                        if (!inventory.containsAtLeast(items, amount)) {
                            Message.error(player, "You do not have required payment to teleport to an outpost: ${Config.outpostTeleportCostString}")
                            return
                        }
                    }

                    // subtract cost
                    for ((material, amount) in Config.outpostTeleportCost) {
                        val items = ItemStack(material, amount)
                        inventory.removeItem(items)
                    }

                    Message.print(player, "Teleporting to an outpost, this will cost: ${Config.outpostTeleportCostString}")
                }

                town.outpostCredits -= 1
                town.needsUpdate()
                Nodes.needsSave = true

                outpostWarpTicks = getOutpostWarpTime(outpost.tier)
                resident.isTeleportingToOutpost = true
                outpost.spawn
            } else {
                if (town.outposts.size > 0) {
                    Message.error(player, "Town does not have outpost named: $outpostName")
                    Message.error(player, "Available outposts: ${town.outposts.keys}")
                } else {
                    Message.error(player, "Your town has no outposts")
                }

                return
            }
        }
        // go to main town spawn
        else {
            resident.isTeleportingToOutpost = false
            town.spawnpoint
        }

        // ticks before teleport timer runs
        var teleportTimerTicks = outpostWarpTicks ?: Math.max(0.0, Config.townSpawnTime * 20.0)

        // multiplier during war and if home occupied
        if (Nodes.war.enabled && Nodes.getTerritoryFromId(town.home)?.occupier !== null) {
            Message.error(player, "${ChatColor.BOLD}Your home is occupied, town spawn will take much longer...")
            teleportTimerTicks *= Config.occupiedHomeTeleportMultiplier
        }

        resident.teleportThread = Bukkit.getAsyncScheduler().runDelayed(
            Nodes.plugin!!,
            { task ->
                player.scheduler.run(
                    Nodes.plugin!!,
                    { _ ->
                        player.teleportAsync(destination).whenComplete { success, throwable ->
                            player.scheduler.run(
                                Nodes.plugin!!,
                                { _ ->
                                    resident.teleportThread = null
                                    resident.isTeleportingToOutpost = false
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
            (teleportTimerTicks * 50).toLong(),
            TimeUnit.MILLISECONDS,
        )

        if (teleportTimerTicks > 0) {
            val seconds = teleportTimerTicks.toInt() / 20

            if (resident.isTeleportingToOutpost) {
                Message.print(player, "Teleporting to an outpost in $seconds seconds. Don't move...")
            } else {
                Message.print(player, "Teleporting to town spawn in $seconds seconds. Don't move...")
            }
        }
    }

    private fun getOutpostWarpTime(tier: PortTier): Double = when (tier) {
        PortTier.TIER1 -> Config.outpostWarpTimeTier1
        PortTier.TIER2 -> Config.outpostWarpTimeTier2
        PortTier.TIER3 -> Config.outpostWarpTimeTier3
    }

    private fun openPointShop(player: Player?) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player)
        val town = resident?.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }
    }

    /**
     * @command /town setspawn
     * Change your town's spawnpoint to another location in the home territory. Town leader only.
     */
    private fun setSpawn(player: Player?) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        val town = resident.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        val leader = town.leader
        if (resident !== leader && !town.officers.contains(resident)) {
            Message.error(player, "You are not a town leader or officer")
            return
        }

        val result = Nodes.setTownSpawn(town, player.location)

        if (result == true) {
            Message.print(player, "Town spawn set to current location")
        } else {
            Message.error(player, "Spawn location must be within town's home territory")
        }
    }

    /**
     * @command /town list
     * View list of all established towns
     */
    private fun listTowns(player: Player?) {
        if (player == null) {
            return
        }

        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        Message.print(player, "${ChatColor.BOLD}Town - Population")
        Nodes.towns.values
            .sortedByDescending { it.residents.size }
            .forEach { town -> Message.print(player, "${town.name}${ChatColor.WHITE} - ${town.residents.size}") }
    }
}

/**
 * @command /town info
 * View your town's name, leader, officers, residents, and claims.
 * @subcommand /town info [town]
 * View details of another town
 */
private fun getInfo(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    var town: Town? = null
    if (args.size == 1) {
        if (resident.town == null) {
            Message.error(player, "You do not belong to a town")
            return
        }
        town = resident.town
    } else if (args.size == 2) {
        if (!Nodes.towns.containsKey(args[1])) {
            Message.error(player, "That town does not exist")
            return
        }
        town = Nodes.getTownFromName(args[1])
    } else {
        Message.error(player, "Usage: /town info [town]")
        return
    }

    town?.printInfo(player)
}

/**
 * @command /town online
 * View your town's online players
 * @subcommand /town online [town]
 * View another town's online players
 */
private fun getOnline(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    var town: Town? = null
    if (args.size == 1) {
        if (resident.town == null) {
            Message.error(player, "You do not belong to a town")
            return
        }
        town = resident.town
    } else if (args.size == 2) {
        if (!Nodes.towns.containsKey(args[1])) {
            Message.error(player, "That town does not exist")
            return
        }
        town = Nodes.getTownFromName(args[1])
    } else {
        Message.error(player, "Usage: /town online [town]")
        return
    }

    if (town == null) {
        return
    }

    val numPlayersOnline = town.playersOnline.size
    val playersOnline = town.playersOnline.map { p ->
        val r = Nodes.getResident(p)
        when {
            r === town.leader -> "${ChatColor.GOLD}[L]${ChatColor.WHITE}${p.name}"
            r != null && town.generals.contains(r) -> "${ChatColor.YELLOW}[G]${ChatColor.WHITE}${p.name}"
            r != null && town.officers.contains(r) -> "${ChatColor.AQUA}[O]${ChatColor.WHITE}${p.name}"
            else -> p.name
        }
    }.joinToString(", ")
    Message.print(player, "Players online in town ${town.name} [$numPlayersOnline]: ${ChatColor.WHITE}$playersOnline")
}

private fun getOnlineTop(player: Player?) {
    if (player == null) {
        return
    }

    val ranking = Nodes.towns.values
        .sortedWith(
            compareByDescending<Town> { it.playersOnline.size }
                .thenBy { it.name.lowercase() },
        )
        .take(10)

    if (ranking.isEmpty()) {
        Message.print(player, "No towns found")
        return
    }

    Message.print(player, "${ChatColor.GOLD}${ChatColor.BOLD}Town Online Top")
    ranking.forEachIndexed { index, town ->
        Message.print(
            player,
            "${ChatColor.GRAY}${index + 1}. ${ChatColor.WHITE}${town.name} " +
                "${ChatColor.GREEN}[${town.playersOnline.size}]",
        )
    }
}

/**
 * @command /town color [r] [g] [b]
 * Set town territory color for dynmap. Town leader only.
 */
private fun setColor(player: Player?, args: Array<String>) {
    if (player == null) return

    val resident = Nodes.getResident(player) ?: return
    val town = resident.town ?: run {
        Message.error(player, "You are not in a town")
        return
    }

    if (resident != town.leader && !town.officers.contains(resident)) {
        Message.error(player, "Only the town leader or officers can change the town color")
        return
    }

    if (args.size < 2) {
        Message.print(player, "Current color: rgb(${town.color.r}, ${town.color.g}, ${town.color.b})")
        Message.print(player, "Usage: /town color <red> <green> <blue>   or   /town color random")
        return
    }

    var newColor: Color? = null

    if (args[1].lowercase() == "random") {
        val random = ThreadLocalRandom.current()
        newColor = Color(random.nextInt(256), random.nextInt(256), random.nextInt(256))
    } else if (args.size >= 4) {
        try {
            val r = args[1].toInt().coerceIn(0, 255)
            val g = args[2].toInt().coerceIn(0, 255)
            val b = args[3].toInt().coerceIn(0, 255)
            newColor = Color(r, g, b)
        } catch (e: Exception) {
            Message.error(player, "Invalid RGB values (0-255)")
            return
        }
    }

    if (newColor == null) {
        Message.error(player, "Usage: /town color <r> <g> <b>   or   /town color random")
        return
    }

    town.color = newColor
    town.updateNametags()
    town.needsUpdate()

    // THIS IS THE IMPORTANT LINE - Updates tab instantly!
    NametagUtils.onTownColorChange(town)

    Message.print(player, "Town color updated to rgb(${newColor.r}, ${newColor.g}, ${newColor.b})")
}

// Extension function to convert our Color to Bukkit's ChatColor
fun Color.toChatColor(): ChatColor {
    val r = this.r
    val g = this.g
    val b = this.b

    return when {
        r >= 170 && g < 85 && b < 85 -> ChatColor.RED
        r < 85 && g >= 170 && b < 85 -> ChatColor.GREEN
        r < 85 && g < 85 && b >= 170 -> ChatColor.BLUE
        r >= 170 && g >= 170 && b < 100 -> ChatColor.YELLOW
        r >= 170 && g < 100 && b >= 170 -> ChatColor.LIGHT_PURPLE
        r < 100 && g >= 170 && b >= 170 -> ChatColor.AQUA
        r >= 200 && g >= 200 && b >= 200 -> ChatColor.WHITE
        r <= 60 && g <= 60 && b <= 60 -> ChatColor.BLACK
        r >= 128 && g >= 128 && b < 80 -> ChatColor.GOLD
        r >= 170 && g >= 85 && b >= 85 -> ChatColor.RED // reddish tones
        else -> ChatColor.GRAY
    }
}

/**
 * @command /town unclaim
 * Abandon your town's claim over a territory
 */
private fun unclaimTerritory(player: Player?) {
    if (player == null) {
        return
    }

    // get town from player
    val resident = Nodes.getResident(player)
    val town = resident?.town
    if (town == null) {
        Message.error(player, "You do not belong to a town")
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

    val result = Nodes.unclaimTerritory(town, territory)
    if (result.isSuccess) {
        Message.print(player, "Territory(id=${territory.id}) unclaimed, claim power used will regenerate over time")
    } else {
        when (result.exceptionOrNull()) {
            ErrorTerritoryNotInTown -> Message.error(player, "Territory not owned by town")
            ErrorTerritoryIsTownHome -> Message.error(player, "Cannot unclaim home territory")
        }
    }
}

/**
 * @command /town income
 * Collect income from territory bonuses. Town leader and officers only.
 */
// TODO: check if player is leader or officer to collect income
private fun getIncome(player: Player?) {
    if (player == null) {
        return
    }

    // get town from player
    val resident = Nodes.getResident(player)
    val town = resident?.town
    if (town == null) {
        Message.error(player, "You do not belong to a town")
        return
    }

    // check player permissions
    val hasPermissions = canAccessTownIncome(resident, town)

    // open town inventory
    if (hasPermissions) {
        player.openInventory(Nodes.getTownIncomeInventory(town))
    } else {
        Message.error(player, "You do not have permissions to view town income")
    }
}

private fun canAccessTownIncome(resident: Resident, town: Town): Boolean = if (resident === town.leader || town.officers.contains(resident)) {
    true
} else if (town.permissions[TownPermissions.INCOME].contains(PermissionsGroup.TOWN) && resident.town === town) {
    true
} else {
    town.permissions[TownPermissions.INCOME].contains(PermissionsGroup.TRUSTED) && resident.town === town && resident.trusted
}

/**
 * @command /town prefix [prefix]
 * Set personal chat prefix
 *
 * @subcommand /town prefix [player] [prefix]
 * Set a player's prefix (leader and officers only)
 */
private fun prefix(player: Player?, args: Array<String>) {
    if (player === null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident === null) {
        return
    }

    // reset prefix
    if (args.size == 1) {
        // print usage
        Message.error(player, "Usage: \"/town prefix [name]\" to set your prefix")
        Message.error(player, "Usage: \"/town prefix remove\" to remove your prefix")
        Message.error(player, "Usage: \"/town prefix [player] [name]\" to set a player's prefix")
        Message.error(player, "Usage: \"/town prefix [player] remove\" to remove a player's prefix")
    }
    // setting personal prefix
    else if (args.size == 2) {
        val prefix = args[1]
        if (prefix.lowercase() == "remove") {
            Nodes.setResidentPrefix(resident, "")
            Message.print(player, "Removed your prefix.")
        } else {
            Nodes.setResidentPrefix(resident, args[1])
            Message.print(player, "Your prefix set to: ${args[1]}")
        }
    }
    // setting a player's prefix in town
    else if (args.size > 2) {
        val town = resident.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        // check if player is leader or officer
        val leader = town.leader
        if (resident !== leader && !town.officers.contains(resident)) {
            Message.error(player, "Only leaders and officers can set other player's prefix/suffix")
            return
        }

        val targetPlayer = Bukkit.getPlayerExact(args[1]) // online players only, no network
        val target = if (targetPlayer != null) {
            Nodes.getResident(targetPlayer)
        } else {
            Nodes.residents.values.find {
                it.name.equals(args[1], ignoreCase = true)
            }
        } ?: run {
            Message.error(player, "Player not found")
            return
        }

        val targetTown = target.town
        if (targetTown !== town) {
            Message.error(player, "Player is not in this town")
            return
        }

        val prefix = args[2]
        if (prefix.lowercase() == "remove") {
            Nodes.setResidentPrefix(target, "")
            Message.print(player, "Removed ${target.name} prefix.")
        } else {
            Nodes.setResidentPrefix(target, args[2])
            Message.print(player, "${target.name} prefix set to: ${args[2]}")
        }
    }
}

/**
 * @command /town suffix [suffix]
 * Set personal chat suffix
 *
 * @subcommand /town suffix [player] [suffix]
 * Set a player's suffix (leader and officers only)
 */
private fun suffix(player: Player?, args: Array<String>) {
    if (player === null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident === null) {
        return
    }

    // reset prefix
    if (args.size == 1) {
        // print usage
        Message.error(player, "Usage: \"/town suffix [name]\" to set your suffix")
        Message.error(player, "Usage: \"/town suffix remove\" to remove your suffix")
        Message.error(player, "Usage: \"/town suffix [player] [name]\" to set a player's suffix")
        Message.error(player, "Usage: \"/town suffix [player] remove\" to remove a player's suffix")
    }
    // setting personal prefix
    else if (args.size == 2) {
        val prefix = args[1]
        if (prefix.lowercase() == "remove") {
            Nodes.setResidentSuffix(resident, "")
            Message.print(player, "Removed your suffix.")
        } else {
            Nodes.setResidentSuffix(resident, args[1])
            Message.print(player, "Your suffix set to: ${args[1]}")
        }
    }
    // setting a player's prefix in town
    else if (args.size > 2) {
        val town = resident.town
        if (town == null) {
            Message.error(player, "You are not a member of a town")
            return
        }

        // check if player is leader or officer
        val leader = town.leader
        if (resident !== leader && !town.officers.contains(resident)) {
            Message.error(player, "Only leaders and officers can set other player's prefix/suffix")
            return
        }

        // get other resident
        val target = Nodes.getResidentFromName(args[1])
        if (target === null) {
            Message.error(player, "Player not found")
            return
        }

        val targetTown = target.town
        if (targetTown !== town) {
            Message.error(player, "Player is not in this town")
            return
        }

        val prefix = args[2]
        if (prefix.lowercase() == "remove") {
            Nodes.setResidentSuffix(target, "")
            Message.print(player, "Removed ${target.name} suffix.")
        } else {
            Nodes.setResidentSuffix(target, args[2])
            Message.print(player, "${target.name} suffix set to: ${args[2]}")
        }
    }
}

/**
 * @command /town rename [new name]
 * Rename your town. Town leader only.
 */
private fun renameTown(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    if (args.size == 1) {
        Message.print(player, "Usage: /n rename [new_name]")
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You do not belong to a town")
        return
    }

    if (resident != town.leader) {
        Message.error(player, "Only town leaders can do this")
        return
    }

    val name = args[1]
    if (!stringInputIsValid(name)) {
        Message.error(player, "Invalid town name")
        return
    }

    if (town.name.lowercase() == args[1].lowercase()) {
        Message.error(player, "Your town is already named ${town.name}")
        return
    }

    if (Nodes.towns.containsKey(args[1])) {
        Message.error(player, "There is already a town with this name")
        return
    }

    Nodes.renameTown(town, name)
    Message.print(player, "Town renamed to ${town.name}!")
}

/**
 * @command /town map
 * Prints territory map into chat for player
 */
private fun printMap(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val loc = player.getLocation()
    val coordX = kotlin.math.floor(loc.x).toInt()
    val coordZ = kotlin.math.floor(loc.z).toInt()
    val coord = Coord.fromBlockCoords(coordX, coordZ)

    // /town map [radius]
    // Keep bounded so the client doesn't hard-wrap into 2 rows.
    // Default `10` is the sweet spot for most clients; cap at 15 to keep chat readable.
    val radiusY = args.getOrNull(1)?.toIntOrNull()?.coerceIn(6, 15) ?: 10
    val radiusX = (radiusY + 6).coerceAtMost(MAP_MAX_RADIUS_X)

    // Use raw messages for the map so we don't inject an extra leading color code
    // (Message.print prepends one), which can push some clients over the wrap limit.
    Message.raw(player, "\n${ChatColor.WHITE}--------------- Territory Map ---------------")
    for (y in radiusY downTo -radiusY) {
        val renderedLine = WorldMap.renderLine(resident, coord, coord.z - y, coord.x - radiusX, coord.x + radiusX)
        Message.raw(player, MAP_STR_BEGIN + renderedLine)
    }
    for (line in MAP_LEGEND_LINES) {
        Message.raw(player, MAP_STR_BEGIN + line)
    }
    Message.raw(player, "") // spacing
}

/**
 * @command /town minimap [size]
 * Turns on/off territory chunks minimap on sidebar.
 * Optionally specify size value within configured limits.
 */
private fun minimap(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val onlinePlayers = Bukkit.getOnlinePlayers().size
    val (minSize, maxSize) = Minimap.effectiveLimits(onlinePlayers)

    // if size input, create new minimap of that size
    // note: minimap creation internally handles removing old minimaps
    if (args.size >= 2) {
        val requestedSize = args[1].toIntOrNull()
        if (requestedSize == null) {
            Message.error(player, "Invalid minimap size: ${args[1]}, must be number in range $minSize-$maxSize. Using default")
            val size = Minimap.defaultSize(onlinePlayers)
            resident.createMinimap(player, size)
            Message.print(player, "Minimap enabled (size = $size)")
            return
        }

        val size = Minimap.clampSize(requestedSize, onlinePlayers)
        if (size != requestedSize) {
            Message.error(player, "Minimap size adjusted to $size (allowed range: $minSize-$maxSize)")
        }
        resident.createMinimap(player, size)
        Message.print(player, "Minimap enabled (size = $size)")
    } else { // toggle minimap
        if (resident.minimap != null) {
            resident.destroyMinimap()
            Message.print(player, "Minimap disabled")
        } else {
            val size = Minimap.defaultSize(onlinePlayers)
            resident.createMinimap(player, size)
            Message.print(player, "Minimap enabled (size = $size)")
        }
    }
}

/**
 * @command /town permissions [type] [group] [allow/deny]
 * Set permissions for interacting in town territory.
 * [type] can be: interact, build, destroy, chests, items
 * [group] can be: nation, ally, outsider.
 * Last entry is either "allow" or "deny"
 */
private fun setPermissions(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You do not belong to a town")
        return
    }

    if (args.size < 4) {
        // print current town permissions
        Message.print(player, "Town Permissions:")
        for (perm in enumValues<TownPermissions>()) {
            val groups = town.permissions[perm]
            Message.print(player, "- ${perm}${ChatColor.WHITE}: $groups")
        }

        // print usage for leader, officers
        if (resident === town.leader || town.officers.contains(resident)) {
            Message.error(player, "Usage: /town permissions [type] [group] [allow/deny]")
            Message.error(player, "[type]: all, build, destroy, interact, chests, items, income")
            Message.error(player, "[group]: town, nation, ally, outsider, trusted")
            Message.error(player, "[allow/deny]: either \"allow\" or \"deny\"")
        }

        return
    }

    if (resident !== town.leader && !town.officers.contains(resident)) {
        Message.error(player, "Only the town leader or officers can do this")
        return
    }

    // match permissions and group
    val permissions: List<TownPermissions> = when (args[1].lowercase()) {
        "all" -> enumValues<TownPermissions>().toList()
        "build" -> listOf(TownPermissions.BUILD)
        "destroy" -> listOf(TownPermissions.DESTROY)
        "interact" -> listOf(TownPermissions.INTERACT)
        "chests" -> listOf(TownPermissions.CHESTS)
        "items" -> listOf(TownPermissions.USE_ITEMS)
        "income" -> listOf(TownPermissions.INCOME)
        else -> {
            Message.error(player, "Invalid permissions type ${args[1]}. Valid options: all, build, destroy, interact, chests, items, income")
            return
        }
    }

    val group: PermissionsGroup = when (args[2].lowercase()) {
        "town" -> PermissionsGroup.TOWN
        "nation" -> PermissionsGroup.NATION
        "ally" -> PermissionsGroup.ALLY
        "outsider" -> PermissionsGroup.OUTSIDER
        "trusted" -> PermissionsGroup.TRUSTED
        else -> {
            Message.error(player, "Invalid permissions group ${args[2]}. Valid options: town, nation, ally, outsider, trusted")
            return
        }
    }

    // get flag state (allow/deny)
    val flag = when (args[3].lowercase()) {
        "allow",
        "true",
        -> {
            true
        }

        "deny",
        "false",
        -> {
            false
        }

        else -> {
            Message.error(player, "Invalid permissions flag ${args[3]}. Valid options: allow, deny")
            return
        }
    }

    for (permission in permissions) {
        Nodes.setTownPermissions(town, permission, group, flag)
    }

    Message.print(player, "Set permissions for ${town.name}: ${args[1].uppercase()} $group $flag")
}

/**
 * @command /town protect
 * Toggle protecting/unprotecting chests with mouse click.
 *
 * @subcommand /town protect show
 * Shows protected chests with particles
 */
private fun protectChests(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You are not a member of a town")
        return
    }

    if (args.size > 1) {
        if (args[1].lowercase() == "show") {
            Message.print(player, "Protected chests:")
            // print protected chests
            for (block in town.protectedBlocks) {
                Message.print(player, "${ChatColor.WHITE}${block.type}: x: ${block.x}, y: ${block.y}, z: ${block.z}")
            }

            Nodes.showProtectedChests(town, resident)
        } else {
            Message.error(player, "Usage: \"/t protect\" to toggle protecting chests")
            Message.error(player, "Usage: \"/t protect show\" to show protected chests")
        }
        return
    }

    // check if player is town leader or officer
    val leader = town.leader
    if (resident !== leader && !town.officers.contains(resident)) {
        Message.error(player, "Only leaders and officers can protect chests")
        return
    }

    if (resident.isProtectingChests) {
        Nodes.stopProtectingChests(resident)
        Message.print(player, "${ChatColor.DARK_AQUA}Stopped protecting chests.")
    } else {
        Nodes.startProtectingChests(resident)
        player.playSound(player.location, NODES_SOUND_CHEST_PROTECT, 1.0f, 1.0f)
        Message.print(player, "Click on a chest to protect or unprotect it. Use \"/t protect\" again to stop protecting, or click a non-chest block to stop.")
    }
}

/**
 * @command /town trust [name]
 * Mark player in town as trusted. Leader and officers only.
 *
 * @command /town untrust [name]
 * Mark player in town as untrusted. Leader and officers only.
 */
private fun trustPlayer(player: Player?, args: Array<String>, trust: Boolean) {
    if (player == null || args.size < 2) {
        Message.error(player, "Usage: /t trust/untrust [player]")
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You are not a member of a town")
        return
    }

    // check if player is leader or officer
    val leader = town.leader
    if (resident !== leader && !town.officers.contains(resident)) {
        Message.error(player, "Only leaders and officers can trust/untrust players")
        return
    }

    // get other resident
    val target = Nodes.getResidentFromName(args[1])
    if (target == null) {
        Message.error(player, "Player not found")
        return
    }

    val targetTown = target.town
    if (targetTown !== town) {
        Message.error(player, "Player is not in this town")
        return
    }

    // set player trust
    if (trust) {
        Nodes.setResidentTrust(target, true)
        Message.print(player, "${target.name} is now marked as trusted")
    } else {
        Nodes.setResidentTrust(target, false)
        Message.print(player, "${ChatColor.DARK_AQUA}${target.name} is marked as untrusted")
    }
}

/**
 * @command /town capital
 * Move town home territory to your current player location.
 * (This also changes town spawn location.)
 */
private fun setCapital(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You are not a member of a town")
        return
    }

    // check if player is leader or officer
    val leader = town.leader
    if (resident !== leader && !town.officers.contains(resident)) {
        Message.error(player, "Only leaders and officers can move the town's home capital territory")
        return
    }

    // check if territory belongs to town and isnt home already
    val territory = Nodes.getTerritoryFromPlayer(player)
    if (territory == null) {
        Message.error(player, "This region has no territory")
        return
    }
    if (town !== territory.town) {
        Message.error(player, "This is not your territory")
        return
    }
    if (town.home == territory.id) {
        Message.error(player, "This is already your home territory")
        return
    }
    if (town.moveHomeCooldown > 0) {
        val remainingTime = town.moveHomeCooldown
        val remainingTimeString = if (remainingTime > 0) {
            val hour: Long = remainingTime / 3600000L
            val min: Long = 1L + (remainingTime - hour * 3600000L) / 60000L
            "${hour}hr ${min}min"
        } else {
            "0hr 0min"
        }

        Message.error(player, "You cannot move the town's home territory for: $remainingTimeString ")
        return
    }

    // move home territory
    Nodes.setTownHomeTerritory(town, territory)
    Message.print(player, "You have moved the town's home territory to id = ${territory.id} (do not forget to update /t setspawn)")
}

/**
 * @command /town annex
 * Annex an occupied territory and add it to your town
 */
private fun annexTerritory(player: Player?, args: Array<String>) {
    if (player == null) {
        return
    }

    if (Config.annexDisabled) {
        Message.error(player, "Annexing disabled")
        return
    }

    if (!Nodes.war.enabled || !Nodes.war.canAnnexTerritories) {
        Message.error(player, "You can only annex territories during war")
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You are not a member of a town")
        return
    }

    // check if player is leader or officer
    val leader = town.leader
    if (resident !== leader && !town.officers.contains(resident)) {
        Message.error(player, "Only leaders and officers can annex territories")
        return
    }

    // check if territory belongs to town and isnt home already
    val territory = Nodes.getTerritoryFromPlayer(player)
    if (territory == null) {
        Message.error(player, "This region has no territory")
        return
    }

    val territoryTown = territory.town
    if (territoryTown === null) {
        Message.error(player, "There is no town here")
        return
    }
    // check blacklist
    if (WarAccessListManager.isTownBlacklisted(territoryTown)) {
        Message.error(player, "Cannot annex this town (blacklisted)")
        return
    }
    if (WarAccessListManager.isTownAnnexBlacklisted(territoryTown)) {
        Message.error(player, "Cannot annex this town (blacklisted)")
        return
    }

    // check whitelist
    if (WarAccessListManager.hasWhitelist()) {
        if (!WarAccessListManager.isTownWhitelisted(territoryTown)) {
            Message.error(player, "Cannot annex this town (not whitelisted)")
            return
        } else if (Config.onlyWhitelistCanAnnex && !WarAccessListManager.isTownWhitelisted(town)) {
            Message.error(player, "Cannot annex territories because your town is not white listed")
            return
        }
    }

    if (town === territoryTown) {
        Message.error(player, "This already your territory")
        return
    }
    if (territory.occupier !== town) {
        Message.error(player, "You have not occupied this territory")
        return
    }
    if (territoryTown.home == territory.id && territoryTown.territories.size > 1) {
        Message.error(player, "You must annex all of this town's other territories before you can annex its home territory")
        return
    }

    val result = Nodes.annexTerritory(town, territory)
    if (result == true) {
        Message.print(player, "Annexed territory (id = ${territory.id})")
    } else {
        Message.error(player, "Failed to annex territory")
    }
}

// =====================================
// town outpost management subcommands
//
// general usage: /town outpost [subcommand] [args]
// =====================================

/**
 * @command /town outpost
 * Commands to manage town outposts.
 */
private fun manageOutpost(sender: CommandSender, args: Array<String>) {
    val player = if (sender is Player) sender else null
    if (player == null) {
        printOutpostHelp(sender)
        return
    }

    if (args.size < 2) {
        printOutpostHelp(sender)
    } else {
        // route subcommand function
        when (args[1].lowercase()) {
            "apply" -> outpostApply(player, args)
            "list" -> outpostList(player, args)
            "setspawn" -> outpostSetSpawn(player, args)
            else -> {
                printOutpostHelp(sender)
            }
        }
    }
}

private fun printOutpostHelp(sender: CommandSender) {
    Message.print(sender, "${ChatColor.BOLD}[Nodes] Town outpost management:")
    Message.print(sender, "/town outpost apply [name]${ChatColor.WHITE}: Request a new outpost for current territory")
    Message.print(sender, "/town outpost list${ChatColor.WHITE}: List town's outposts")
    Message.print(sender, "/town outpost setspawn${ChatColor.WHITE}: Set town outpost spawn to your current location")
    Message.print(sender, "Run a command with no args to see usage.")
}

private fun outpostApply(player: Player, args: Array<String>) {
    if (args.size < 3) {
        Message.error(player, "Usage: /town outpost apply [name]")
        return
    }

    val requestedName = args[2].lowercase()
    if (!stringInputIsValid(requestedName, 32)) {
        Message.error(player, "Invalid outpost name")
        return
    }

    Nodes.createOutpostApplication(player, requestedName).getOrElse { err ->
        Message.error(player, err.message ?: "Could not create outpost request")
        return
    }.let { app ->
        Message.print(player, "${ChatColor.GREEN}Outpost request created: #${app.id} (${app.requestedOutpostName})")
        Message.print(player, "${ChatColor.GRAY}Waiting for staff review with /nda outpost list")
    }
}

/**
 * @subcommand /town outpost list
 * Print list of town's outposts.
 */
private fun outpostList(player: Player, args: Array<String>) {
    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You are not a member of a town")
        return
    }

    if (town.outposts.size > 0) {
        Message.print(player, "Town outposts (credits: ${town.outpostCredits}):")
        for ((name, outpost) in town.outposts) {
            val spawn = outpost.spawn
            Message.print(
                player,
                "- ${name}${ChatColor.WHITE}: Territory (id=${outpost.territory}, tier=${outpost.tier.name.lowercase()}, Spawn=(${spawn.x}, ${spawn.y}, ${spawn.z}))",
            )
        }
    } else {
        Message.error(player, "Town has no outposts")
    }
}

/**
 * @subcommand /town outpost setspawn
 * Set an outpost's spawn point. Player must be in the outpost territory.
 */
private fun outpostSetSpawn(player: Player, args: Array<String>) {
    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You are not a member of a town")
        return
    }

    // check if player is leader or officer
    val leader = town.leader
    if (resident !== leader && !town.officers.contains(resident)) {
        Message.error(player, "Only leaders and officers can move an outpost's spawn location")
        return
    }

    // check if territory belongs to town and isnt home already
    val territory = Nodes.getTerritoryFromPlayer(player)
    if (territory == null) {
        Message.error(player, "This region has no territory")
        return
    }
    if (town !== territory.town) {
        Message.error(player, "This is not your territory")
        return
    }

    // match outpost to territory
    for (outpost in town.outposts.values) {
        if (outpost.territory == territory.id) {
            val result = Nodes.setOutpostSpawn(town, outpost, player.location)
            if (result == true) {
                Message.print(player, "Set outpost \"${outpost.name}\" spawn to current location")
            } else {
                Message.error(player, "Failed to set outpost spawn in current location")
            }
            return
        }
    }

    // failed to match, return error
    Message.error(player, "Your town has no outpost in this location")
}

/**
 * @command /town fly
 * Enable flight, player must be in their town
 */
private fun toggleFlight(player: Player?) {
    if (player == null) {
        return
    }

    if (!player.hasPermission("nodes.command.town.fly")) {
        Message.error(player, "You do not have permission to fly")
        return
    }

    val resident = Nodes.getResident(player)
    if (resident == null) {
        return
    }

    val town = resident.town
    if (town == null) {
        Message.error(player, "You are not a member of a town")
        return
    }

    // do not allow during war?
    if (Nodes.war.enabled) {
        Message.error(player, "Cannot fly during war")
        return
    }

    if (player.allowFlight) {
        player.allowFlight = false
        // give player slow falling to avoid fall damage
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, false))
        Message.print(player, "Disabled flight")
        return
    }

    val currentTerritory = Nodes.getTerritoryFromChunk(player.chunk)
    if (currentTerritory?.town != town) {
        Message.error(player, "You must be in your town to enable flight")
        return
    }
    if (WarzoneManager.findActiveWarzoneByNode(currentTerritory.id.toInt()) != null) {
        Message.error(player, "Cannot fly in an active warzone")
        return
    }

    player.allowFlight = true
    Message.print(player, "Enabled flight")
}
