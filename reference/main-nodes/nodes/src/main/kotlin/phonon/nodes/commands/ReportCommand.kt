package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.utils.DiscordWebhook
import java.util.UUID

class ReportCommand : CommandExecutor {
    private val cooldowns: HashMap<UUID, Long> = hashMapOf()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Message.error(sender, "This command is only for players.")
            return true
        }

        if (args.isEmpty()) {
            Message.error(sender, "Usage: /report <message>")
            return true
        }

        // Cooldown check
        val now = System.currentTimeMillis()
        val lastUsed = cooldowns[sender.uniqueId]
        if (lastUsed != null) {
            val remaining = Config.reportCooldown - (now - lastUsed)
            if (remaining > 0) {
                val seconds = remaining / 1000
                Message.error(sender, "Please wait ${seconds}s before reporting again.")
                return true
            }
        }

        val message = args.joinToString(" ")
        val resident = Nodes.getResident(sender)

        // Gathers info
        val locationStr = "${sender.world.name}, ${sender.location.blockX}, ${sender.location.blockY}, ${sender.location.blockZ}"

        var townNationStr = "None"
        var webhookUrl = Config.discordWebhooks["default"]

        if (resident != null) {
            val town = resident.town
            if (town != null) {
                townNationStr = town.name
                val nation = town.nation
                if (nation != null) {
                    townNationStr += " / ${nation.name}"

                    // Check if there is a specific webhook for this nation
                    val nationWebhook = Config.discordWebhooks[nation.name]
                    if (nationWebhook != null) {
                        webhookUrl = nationWebhook
                    }
                }
            }
        }

        if (webhookUrl == null) {
            Message.error(sender, "Report system is not configured. Please contact an admin.")
            Nodes.logger?.warning("Report attempted but 'default' webhook is missing in config.")
            return true
        }

        // Send payload
        DiscordWebhook.sendReport(
            url = webhookUrl,
            reporter = sender.name,
            content = message,
            location = locationStr,
            townNation = townNationStr,
        )

        cooldowns[sender.uniqueId] = now
        Message.print(sender, "${ChatColor.GREEN}Report sent to your regions staff. Thank you!")
        return true
    }
}
