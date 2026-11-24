package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import phonon.nodes.NodesExtensions
import phonon.nodes.towncontrol.TownControlConfig

/**
 * /netowncontrol command
 *
 * Subcommands:
 *   status                    - show current toggle states
 *   leave  [enable|disable]   - toggle /town leave
 *   spawn  [enable|disable]   - toggle /town spawn
 *
 * Permission: nodesextensions.admin  (or op)
 */
class TownControlCommand :
    CommandExecutor,
    TabCompleter {

    private val subcommands = listOf("status", "leave", "spawn", "capital", "disband")
    private val flags = listOf("enable", "disable")

    override fun onCommand(
        sender: CommandSender,
        cmd: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        if (!sender.hasPermission("nodesextensions.admin") && !sender.isOp) {
            phonon.nodes.Message.raw(sender, "${ChatColor.RED}You do not have permission to use this command.")
            return true
        }

        if (args.isEmpty() || args[0].equals("status", ignoreCase = true)) {
            printStatus(sender)
            return true
        }

        when (args[0].lowercase()) {
            "leave" -> handleToggle(sender, args, "leave")
            "spawn" -> handleToggle(sender, args, "spawn")
            "capital" -> handleToggle(sender, args, "capital")
            "disband" -> handleToggle(sender, args, "disband")
            else -> {
                phonon.nodes.Message.raw(sender, "${ChatColor.RED}Unknown subcommand. Usage: /netowncontrol <status|leave|spawn> [enable|disable]")
            }
        }

        return true
    }

    private fun handleToggle(sender: CommandSender, args: Array<String>, target: String) {
        if (args.size < 2) {
            // No flag given → just print current state
            printStatus(sender)
            phonon.nodes.Message.raw(sender, "${ChatColor.YELLOW}Usage: /netowncontrol $target <enable|disable>")
            return
        }

        val enable: Boolean = when (args[1].lowercase()) {
            "enable", "on", "true" -> true
            "disable", "off", "false" -> false
            else -> {
                phonon.nodes.Message.raw(sender, "${ChatColor.RED}Invalid flag \"${args[1]}\". Use enable or disable.")
                return
            }
        }

        val plugin = NodesExtensions.instance
        val config = plugin.config

        when (target) {
            "leave" -> {
                TownControlConfig.setLeaveEnabled(enable, config)
                plugin.saveConfig()
                val state = if (enable) "${ChatColor.GREEN}enabled" else "${ChatColor.RED}disabled"
                phonon.nodes.Message.raw(sender, "${ChatColor.GOLD}[NE] ${ChatColor.WHITE}/town leave is now $state${ChatColor.WHITE}.")
                plugin.logger.info("/town leave set to $enable by ${sender.name}")
            }
            "spawn" -> {
                TownControlConfig.setSpawnEnabled(enable, config)
                plugin.saveConfig()
                val state = if (enable) "${ChatColor.GREEN}enabled" else "${ChatColor.RED}disabled"
                phonon.nodes.Message.raw(sender, "${ChatColor.GOLD}[NE] ${ChatColor.WHITE}/town spawn is now $state${ChatColor.WHITE}.")
                plugin.logger.info("/town spawn set to $enable by ${sender.name}")
            }
            "capital" -> {
                TownControlConfig.setCapitalEnabled(enable, config)
                plugin.saveConfig()
                val state = if (enable) "${ChatColor.GREEN}enabled" else "${ChatColor.RED}disabled"
                phonon.nodes.Message.raw(sender, "${ChatColor.GOLD}[NE] ${ChatColor.WHITE}/town spawn is now $state${ChatColor.WHITE}.")
                plugin.logger.info("/town capital set to $enable by ${sender.name}")
            }
            "disband" -> {
                TownControlConfig.setDisbandEnabled(enable, config)
                plugin.saveConfig()
                val state = if (enable) "${ChatColor.GREEN}enabled" else "${ChatColor.RED}disabled"
                phonon.nodes.Message.raw(sender, "${ChatColor.GOLD}[NE] ${ChatColor.WHITE}/town spawn is now $state${ChatColor.WHITE}.")
                plugin.logger.info("/town disband set to $enable by ${sender.name}")
            }
        }
    }

    private fun printStatus(sender: CommandSender) {
        val leaveState = if (TownControlConfig.leaveEnabled) "${ChatColor.GREEN}enabled" else "${ChatColor.RED}disabled"
        val spawnState = if (TownControlConfig.spawnEnabled) "${ChatColor.GREEN}enabled" else "${ChatColor.RED}disabled"

        phonon.nodes.Message.raw(sender, "${ChatColor.GOLD}${ChatColor.BOLD}[NodesExtensions] Town Command Status")
        phonon.nodes.Message.raw(sender, "  ${ChatColor.WHITE}/town leave ${ChatColor.GRAY}» $leaveState")
        phonon.nodes.Message.raw(sender, "  ${ChatColor.WHITE}/town spawn ${ChatColor.GRAY}» $spawnState")
        phonon.nodes.Message.raw(sender, "${ChatColor.GRAY}Use /netowncontrol <leave|spawn> <enable|disable> to change.")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> = when (args.size) {
        1 -> subcommands.filter { it.startsWith(args[0], ignoreCase = true) }
        2 -> if (args[0].lowercase() in listOf("leave", "spawn", "disband", "capital")) {
            flags.filter { it.startsWith(args[1], ignoreCase = true) }
        } else {
            listOf()
        }
        else -> listOf()
    }
}
