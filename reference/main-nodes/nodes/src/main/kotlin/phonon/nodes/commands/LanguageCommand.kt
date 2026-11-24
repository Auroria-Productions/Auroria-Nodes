package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.language.PlayerLanguage
import phonon.nodes.utils.string.filterByStart

private val LANGUAGE_CODES: List<String> = listOf("en", "de", "el", "tr")

public class LanguageCommand :
    CommandExecutor,
    TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        val player = sender as? Player
        if (player == null) {
            Message.error(sender, "Only players can use this command")
            return true
        }

        if (args.isEmpty()) {
            Message.print(player, "Usage: /language en|de|el|tr")
            Message.print(player, "en = English, de = Deutsch, el = Spanish, tr = Turkish")
            return true
        }

        val lang = PlayerLanguage.fromCode(args[0])
        if (lang == null) {
            Message.error(player, "Invalid language. Use: en, de, el, tr")
            return true
        }

        val resident = Nodes.getResident(player)
        if (resident == null) {
            Message.error(player, "Resident not found")
            return true
        }

        resident.language = lang
        resident.needsUpdate()
        Nodes.needsSave = true

        Message.print(player, "${ChatColor.GREEN}Language updated to ${lang.code}")
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = if (args.size == 1) filterByStart(LANGUAGE_CODES, args[0]) else listOf()
}
