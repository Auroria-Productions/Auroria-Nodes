package phonon.nodes.miningtower

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Nodes
import phonon.nodes.NodesExtensions

/**
 * Command handler for /miningtower
 *
 * Subcommands:
 *   submit                          — submit a mining tower (player)
 *   pending                         — view pending submissions (staff)
 *   delete <town>                   — delete a town's approved tower (staff)
 *   reload                          — reload config + data (staff)
 *   info                            — view own town's tower info (player)
 *   cooldown remove <town>          — clear submission cooldown for a town (staff)
 *   cooldown add <town> <hours>     — artificially set cooldown for a town (staff)
 *   cooldown check <town>           — show remaining cooldown for a town (staff)
 */
class MiningTowerCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            showHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "submit" -> handleSubmit(sender)
            "pending" -> handlePending(sender)
            "delete" -> handleDelete(sender, args)
            "reload" -> handleReload(sender)
            "info" -> handleInfo(sender)
            "cooldown" -> handleCooldown(sender, args)
            else -> phonon.nodes.Message.raw(sender, "\u00a7cUnknown subcommand. Use \u00a7f/miningtower \u00a7cfor help.")
        }

        return true
    }

    // -------------------------------------------------------------------------
    // SUBMIT
    // -------------------------------------------------------------------------

    private fun handleSubmit(sender: CommandSender) {
        if (sender !is Player) {
            phonon.nodes.Message.raw(sender, "\u00a7cOnly players can submit mining towers!")
            return
        }

        if (!sender.hasPermission(MiningTowerConfig.submitPermission)) {
            phonon.nodes.Message.raw(sender, MiningTowerConfig.noPermissionSubmitMsg)
            return
        }

        val town = Nodes.getTownFromPlayer(sender)
        if (town == null) {
            phonon.nodes.Message.raw(sender, MiningTowerConfig.noTownMsg)
            return
        }

        val resident = Nodes.getResident(sender)
        val isLeader = town.leader == resident
        val isOfficer = town.officers.contains(resident)
        if (!isLeader && !isOfficer) {
            phonon.nodes.Message.raw(sender, "\u00a7cOnly town leaders and officers can submit mining towers!")
            return
        }

        val territory = Nodes.getTerritoryFromBlock(sender.location.blockX, sender.location.blockZ)
        if (territory == null) {
            phonon.nodes.Message.raw(sender, "\u00a7cYou must be in a territory to submit a mining tower!")
            return
        }

        if (territory.town != town) {
            phonon.nodes.Message.raw(sender, "\u00a7cYou must be in your town's territory to submit a mining tower!")
            phonon.nodes.Message.raw(sender, "\u00a77This territory belongs to: \u00a7f${territory.town?.name ?: "wilderness"}")
            return
        }

        // Cooldown check (keyed by town name in updated MiningTowerManager)
        if (!MiningTowerManager.canSubmit(town.name)) {
            val remaining = MiningTowerManager.formatRemainingCooldown(town.name)
            phonon.nodes.Message.raw(sender, "\u00a7cYour town must wait \u00a7f$remaining \u00a7cbefore submitting another mining tower!")
            return
        }

        MiningTowerSubmissionGUI.open(sender, town.name)
    }

    // -------------------------------------------------------------------------
    // PENDING
    // -------------------------------------------------------------------------

    private fun handlePending(sender: CommandSender) {
        if (sender !is Player) {
            phonon.nodes.Message.raw(sender, "\u00a7cOnly players can use this command!")
            return
        }

        if (!sender.hasPermission(MiningTowerConfig.ratePermission)) {
            phonon.nodes.Message.raw(sender, MiningTowerConfig.noPermissionRateMsg)
            return
        }

        val pending = MiningTowerManager.getPendingSubmissions()
        if (pending.isEmpty()) {
            phonon.nodes.Message.raw(sender, MiningTowerConfig.noPendingMsg)
            return
        }

        MiningTowerPendingGUI.open(sender, pending)
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(MiningTowerConfig.ratePermission)) {
            phonon.nodes.Message.raw(sender, "\u00a7cYou don't have permission to delete mining towers!")
            return
        }

        if (args.size < 2) {
            phonon.nodes.Message.raw(sender, "\u00a7cUsage: /miningtower delete <town>")
            return
        }

        val townName = args.drop(1).joinToString(" ")
        val tower = MiningTowerManager.getApprovedTower(townName)
        if (tower == null) {
            phonon.nodes.Message.raw(sender, "\u00a7cNo approved mining tower found for town: \u00a7f$townName")
            return
        }

        val deleted = MiningTowerManager.deleteTower(tower.id)
        if (deleted) {
            phonon.nodes.Message.raw(sender, "\u00a7a\u00a7l[Mining Tower] \u00a7aDeleted mining tower for \u00a7f$townName")
            phonon.nodes.Message.raw(sender, "\u00a77Tower ID: \u00a7f${tower.id}")
            phonon.nodes.Message.raw(sender, "\u00a77Tier: \u00a7f${tower.ratedTier}")
            NodesExtensions.instance.logger.info("[MiningTower] ${sender.name} deleted tower for $townName (ID: ${tower.id})")
        } else {
            phonon.nodes.Message.raw(sender, "\u00a7cFailed to delete mining tower.")
        }
    }

    // -------------------------------------------------------------------------
    // RELOAD
    // -------------------------------------------------------------------------

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission(MiningTowerConfig.ratePermission)) {
            phonon.nodes.Message.raw(sender, "\u00a7cYou don't have permission to reload the configuration!")
            return
        }

        phonon.nodes.Message.raw(sender, "\u00a76\u00a7l[Mining Tower] \u00a77Reloading configuration...")

        try {
            NodesExtensions.instance.reloadConfig()
            MiningTowerConfig.load(NodesExtensions.instance.config)
            MiningTowerManager.load()

            phonon.nodes.Message.raw(sender, "\u00a7a\u00a7l[Mining Tower] \u00a7aConfiguration reloaded successfully!")
            phonon.nodes.Message.raw(sender, "\u00a77Loaded \u00a7f${MiningTowerConfig.tiers.size} \u00a77tiers")
            phonon.nodes.Message.raw(sender, "\u00a77Loaded \u00a7f${MiningTowerManager.getAllSubmissions().size} \u00a77submissions")
            NodesExtensions.instance.logger.info("[MiningTower] ${sender.name} reloaded the configuration")
        } catch (e: Exception) {
            phonon.nodes.Message.raw(sender, "\u00a7c\u00a7l[Mining Tower] \u00a7cFailed to reload configuration!")
            phonon.nodes.Message.raw(sender, "\u00a7cError: ${e.message}")
            e.printStackTrace()
        }
    }

    // -------------------------------------------------------------------------
    // INFO
    // -------------------------------------------------------------------------

    private fun handleInfo(sender: CommandSender) {
        if (sender !is Player) {
            phonon.nodes.Message.raw(sender, "\u00a7cOnly players can use this command!")
            return
        }

        val town = Nodes.getTownFromPlayer(sender)
        if (town == null) {
            phonon.nodes.Message.raw(sender, MiningTowerConfig.noTownMsg)
            return
        }

        val towers = MiningTowerManager.getAllSubmissions()
            .filter { it.townName == town.name && it.status == SubmissionStatus.APPROVED }

        if (towers.isEmpty()) {
            phonon.nodes.Message.raw(sender, "\u00a76\u00a7l[Mining Tower] \u00a77Your town has no approved mining towers.")
            phonon.nodes.Message.raw(sender, "\u00a77Use \u00a7f/miningtower submit \u00a77to submit one!")

            if (!MiningTowerManager.canSubmit(town.name)) {
                val remaining = MiningTowerManager.formatRemainingCooldown(town.name)
                phonon.nodes.Message.raw(sender, "\u00a77Cooldown: \u00a7c$remaining remaining")
            } else {
                phonon.nodes.Message.raw(sender, "\u00a7aYour town is ready to submit a mining tower!")
            }
            return
        }

        phonon.nodes.Message.raw(sender, "\u00a76\u00a7l[Mining Tower] \u00a77Approved towers for \u00a7f${town.name}")
        phonon.nodes.Message.raw(sender, "\u00a77")

        towers.forEachIndexed { index, tower ->
            val tierConfig = tower.ratedTier?.let { MiningTowerConfig.tiers[it] }
            val territoryId = tower.territoryId?.toInt() ?: 0

            phonon.nodes.Message.raw(sender, "\u00a7e${index + 1}. \u00a7fTerritory ID: $territoryId")
            phonon.nodes.Message.raw(sender, "   \u00a77Tier: \u00a7f${tierConfig?.displayName ?: tower.ratedTier}")
            phonon.nodes.Message.raw(sender, "   \u00a77Location: \u00a7f${tower.location.blockX}, ${tower.location.blockY}, ${tower.location.blockZ}")
            if (tierConfig != null) {
                phonon.nodes.Message.raw(sender, "   \u00a77Income Bonus: \u00a7a+${(tierConfig.incomeBonus * 100).toInt()}%")
                phonon.nodes.Message.raw(sender, "   \u00a77Mining Rate: \u00a76${tierConfig.miningRateMultiplier}x")
            }
            phonon.nodes.Message.raw(sender, "\u00a77")
        }
    }

    // -------------------------------------------------------------------------
    // COOLDOWN  (new)
    // -------------------------------------------------------------------------

    /**
     * /miningtower cooldown <remove|add|check> <town> [hours]
     *
     *   remove <town>         — clears the cooldown for <town> entirely.
     *   add    <town> <hours> — artificially sets a cooldown of <hours> hours.
     *   check  <town>         — shows the remaining cooldown for <town>.
     */
    private fun handleCooldown(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(MiningTowerConfig.ratePermission)) {
            phonon.nodes.Message.raw(sender, "\u00a7cYou don't have permission to manage cooldowns!")
            return
        }

        if (args.size < 3) {
            phonon.nodes.Message.raw(sender, "\u00a7cUsage: /miningtower cooldown <remove|add|check> <town> [hours]")
            return
        }

        val subCmd = args[1].lowercase()
        val townName = args.drop(2).let {
            // If sub-command is "add", the last token is <hours>, everything before is the town name
            if (subCmd == "add" && it.size >= 2) it.dropLast(1).joinToString(" ") else it.joinToString(" ")
        }

        when (subCmd) {
            "remove" -> {
                if (!MiningTowerManager.hasCooldown(townName)) {
                    phonon.nodes.Message.raw(sender, "\u00a77\u00a7f$townName \u00a77has no active cooldown.")
                    return
                }
                MiningTowerManager.clearCooldown(townName)
                phonon.nodes.Message.raw(sender, "\u00a7a\u00a7l[Mining Tower] \u00a7aCooldown cleared for \u00a7f$townName\u00a7a.")
                NodesExtensions.instance.logger.info("[MiningTower] ${sender.name} cleared cooldown for $townName")
            }

            "add" -> {
                if (args.size < 4) {
                    phonon.nodes.Message.raw(sender, "\u00a7cUsage: /miningtower cooldown add <town> <hours>")
                    return
                }
                val hoursStr = args.last()
                val hours = hoursStr.toDoubleOrNull()
                if (hours == null || hours <= 0) {
                    phonon.nodes.Message.raw(sender, "\u00a7cHours must be a positive number.")
                    return
                }
                MiningTowerManager.setCooldown(townName, hours)
                phonon.nodes.Message.raw(sender, "\u00a7a\u00a7l[Mining Tower] \u00a7aCooldown set to \u00a7f${hours}h \u00a7afor \u00a7f$townName\u00a7a.")
                NodesExtensions.instance.logger.info("[MiningTower] ${sender.name} set ${hours}h cooldown for $townName")
            }

            "check" -> {
                if (!MiningTowerManager.hasCooldown(townName)) {
                    phonon.nodes.Message.raw(sender, "\u00a77\u00a7f$townName \u00a77has no active cooldown — can submit now.")
                } else {
                    val remaining = MiningTowerManager.formatRemainingCooldown(townName)
                    phonon.nodes.Message.raw(sender, "\u00a76\u00a7l[Mining Tower] \u00a7f$townName \u00a77cooldown: \u00a7c$remaining remaining")
                }
            }

            else -> phonon.nodes.Message.raw(sender, "\u00a7cUnknown cooldown subcommand. Use remove, add, or check.")
        }
    }

    // -------------------------------------------------------------------------
    // HELP
    // -------------------------------------------------------------------------

    private fun showHelp(sender: CommandSender) {
        phonon.nodes.Message.raw(sender, "\u00a76\u00a7l[Mining Tower] \u00a77Commands:")
        phonon.nodes.Message.raw(sender, "\u00a7e/miningtower submit \u00a77- Submit a mining tower")
        phonon.nodes.Message.raw(sender, "\u00a7e/miningtower info \u00a77- View your town's tower info")
        if (sender.hasPermission(MiningTowerConfig.ratePermission)) {
            phonon.nodes.Message.raw(sender, "\u00a7e/miningtower pending \u00a77- View pending submissions \u00a78(staff)")
            phonon.nodes.Message.raw(sender, "\u00a7e/miningtower delete <town> \u00a77- Delete a town's tower \u00a78(staff)")
            phonon.nodes.Message.raw(sender, "\u00a7e/miningtower reload \u00a77- Reload configuration \u00a78(staff)")
            phonon.nodes.Message.raw(sender, "\u00a7e/miningtower cooldown remove <town> \u00a77- Clear cooldown \u00a78(staff)")
            phonon.nodes.Message.raw(sender, "\u00a7e/miningtower cooldown add <town> <hours> \u00a77- Set cooldown \u00a78(staff)")
            phonon.nodes.Message.raw(sender, "\u00a7e/miningtower cooldown check <town> \u00a77- Check cooldown \u00a78(staff)")
        }
    }

    // -------------------------------------------------------------------------
    // TAB COMPLETE
    // -------------------------------------------------------------------------

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val hasStaff = sender.hasPermission(MiningTowerConfig.ratePermission)

        return when (args.size) {
            1 -> buildList {
                add("submit")
                add("info")
                if (hasStaff) addAll(listOf("pending", "delete", "reload", "cooldown"))
            }.filter { it.startsWith(args[0], ignoreCase = true) }

            2 -> when (args[0].lowercase()) {
                "delete" -> if (hasStaff) {
                    MiningTowerManager.getAllApprovedTowns()
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                } else {
                    emptyList()
                }
                "cooldown" -> if (hasStaff) {
                    listOf("remove", "add", "check")
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                } else {
                    emptyList()
                }
                else -> emptyList()
            }

            3 -> when {
                args[0].equals("cooldown", ignoreCase = true) && hasStaff -> {
                    MiningTowerManager.getAllApprovedTowns()
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                }
                else -> emptyList()
            }

            4 -> when {
                args[0].equals("cooldown", ignoreCase = true) &&
                    args[1].equals("add", ignoreCase = true) &&
                    hasStaff ->
                    listOf("<hours>")
                else -> emptyList()
            }

            else -> emptyList()
        }
    }
}
