package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.TerritoryCaptureNotificationMode
import phonon.nodes.chat.ChatMode
import phonon.nodes.objects.Coord
import phonon.nodes.objects.MinimapOrientation
import phonon.nodes.objects.Resident
import phonon.nodes.utils.string.filterByStart
import phonon.nodes.war.AttackMessageMode
import kotlin.math.floor

internal object OptionsMenu {

    private val optionSubcommands = listOf("orientation", "chat", "attackmessages", "capturemessages", "help")

    public fun handle(sender: CommandSender?, args: Array<String>): Boolean {
        if (sender !is Player) {
            Message.error(sender, "Only players can use this command")
            return true
        }

        val resident = Nodes.getResident(sender)
        if (resident == null) {
            Message.error(sender, "Player data not loaded")
            return true
        }

        if (args.isEmpty()) {
            showMenu(sender, resident)
            return true
        }

        when (args[0].lowercase()) {
            "orientation" -> handleOrientation(sender, resident, args)
            "chat" -> handleChat(sender, resident, args)
            "attackmessages" -> handleAttackMessages(sender, resident, args)
            "capturemessages" -> handleCaptureMessages(sender, resident, args)
            "help" -> showMenu(sender, resident)
            else -> Message.error(sender, "Unknown options command; try \"/noptions\"")
        }

        return true
    }

    private fun showMenu(player: Player, resident: Resident) {
        Message.print(player, "${ChatColor.BOLD}[Nodes] Options menu")

        val orientationLabel = if (resident.minimapOrientation == MinimapOrientation.NORTH_UP) {
            "North is up"
        } else {
            "Facing direction is up"
        }
        Message.print(player, "- ${ChatColor.YELLOW}Minimap orientation${ChatColor.WHITE}: $orientationLabel (north|forward)")

        val chatLabel = when (resident.chatMode) {
            ChatMode.GLOBAL -> "Global"
            ChatMode.TOWN -> "Town"
            ChatMode.NATION -> "Nation"
            ChatMode.ALLY -> "Ally"
            ChatMode.LOCAL -> "Local (100 block radius)"
        }
        Message.print(player, "- ${ChatColor.YELLOW}Chat channel${ChatColor.WHITE}: $chatLabel")

        Message.print(
            player,
            "- ${ChatColor.YELLOW}Attack messages${ChatColor.WHITE}: ${resident.attackMessageMode.label} (all|town|nation|ally|warzone|captures)",
        )

        val captureMessagesLabel = when (resident.territoryCaptureNotificationMode) {
            TerritoryCaptureNotificationMode.TITLE -> "Title + subtitle"
            TerritoryCaptureNotificationMode.CHAT -> "Large chat message"
        }
        Message.print(player, "- ${ChatColor.YELLOW}Territory cap notice${ChatColor.WHITE}: $captureMessagesLabel (title|chat)")

        Message.print(
            player,
            "${ChatColor.GRAY}Use \"/noptions orientation north|forward\", \"/noptions chat <global|town|nation|ally|local>\", \"/noptions attackmessages <all|town|nation|ally|warzone|captures>\", or \"/noptions capturemessages <title|chat>\"",
        )
        Message.print(player, "${ChatColor.GRAY}You can also open this menu with \"/nodes options\"")
    }

    private fun handleOrientation(player: Player, resident: Resident, args: Array<String>) {
        if (args.size < 2) {
            Message.error(player, "Usage: /noptions orientation <north|forward>")
            return
        }

        val choice = parseOrientation(args[1])
        if (choice == null) {
            Message.error(player, "Invalid orientation; use \"north\" or \"forward\"")
            return
        }

        if (resident.minimapOrientation == choice) {
            Message.print(player, "Minimap orientation already ${choice.label}")
            return
        }

        resident.minimapOrientation = choice
        resident.needsUpdate()
        Nodes.needsSave = true
        refreshMinimap(player, resident)

        Message.print(player, "Minimap orientation set to ${choice.label}")
    }

    private fun handleChat(player: Player, resident: Resident, args: Array<String>) {
        if (args.size < 2) {
            Message.error(player, "Usage: /noptions chat <global|town|nation|ally|local>")
            return
        }

        val choice = parseChat(args[1])
        if (choice == null) {
            Message.error(player, "Invalid chat channel; valid options are global, town, nation, ally, local")
            return
        }

        if (resident.chatMode == choice) {
            Message.print(player, "Already talking in ${choice.name.lowercase()} chat")
            return
        }

        resident.chatMode = choice
        resident.needsUpdate()
        Nodes.needsSave = true

        val message = when (choice) {
            ChatMode.GLOBAL -> "${ChatColor.BOLD}Now talking in global chat"
            ChatMode.TOWN -> "${ChatColor.DARK_AQUA}${ChatColor.BOLD}Now talking in town chat"
            ChatMode.NATION -> "${ChatColor.GOLD}${ChatColor.BOLD}Now talking in nation chat"
            ChatMode.ALLY -> "${ChatColor.GREEN}${ChatColor.BOLD}Now talking in ally chat"
            ChatMode.LOCAL -> "${ChatColor.LIGHT_PURPLE}${ChatColor.BOLD}Now talking in local chat"
        }
        Message.print(player, message)
    }

    private fun handleAttackMessages(player: Player, resident: Resident, args: Array<String>) {
        if (args.size < 2) {
            Message.error(player, "Usage: /noptions attackmessages <all|town|nation|ally|warzone|captures>")
            return
        }

        val rawChoice = args[1].lowercase()
        val validChoices = setOf(
            "all",
            "global",
            "everyone",
            "town",
            "townonly",
            "town-only",
            "local",
            "nation",
            "n",
            "ally",
            "allies",
            "a",
            "warzone",
            "warzones",
            "warzoneonly",
            "warzone-only",
            "captures",
            "capture",
            "territory",
            "territories",
            "annex",
            "annexes",
        )
        if (rawChoice !in validChoices) {
            Message.error(player, "Invalid attack message mode; use all, town, nation, ally, warzone, or captures")
            return
        }
        val choice = AttackMessageMode.fromName(rawChoice)

        if (resident.attackMessageMode == choice) {
            Message.print(player, "Attack messages already set to ${choice.label.lowercase()}")
            return
        }

        resident.attackMessageMode = choice
        resident.needsUpdate()
        Nodes.needsSave = true

        Message.print(player, "Attack messages set to ${choice.label.lowercase()}")
    }

    private fun handleCaptureMessages(player: Player, resident: Resident, args: Array<String>) {
        if (args.size < 2) {
            Message.error(player, "Usage: /noptions capturemessages <title|chat>")
            return
        }

        val choice = TerritoryCaptureNotificationMode.fromName(args[1], fallback = resident.territoryCaptureNotificationMode)
        if (!isValidCaptureMessageMode(args[1])) {
            Message.error(player, "Invalid territory cap notice mode; use \"title\" or \"chat\"")
            return
        }

        if (resident.territoryCaptureNotificationMode == choice) {
            val current = when (choice) {
                TerritoryCaptureNotificationMode.TITLE -> "Territory cap notices already use titles"
                TerritoryCaptureNotificationMode.CHAT -> "Territory cap notices already use chat messages"
            }
            Message.print(player, current)
            return
        }

        resident.territoryCaptureNotificationMode = choice
        resident.needsUpdate()
        Nodes.needsSave = true

        val updated = when (choice) {
            TerritoryCaptureNotificationMode.TITLE -> "Territory cap notices now use titles"
            TerritoryCaptureNotificationMode.CHAT -> "Territory cap notices now use large chat messages"
        }
        Message.print(player, updated)
    }

    private fun parseOrientation(input: String): MinimapOrientation? = when (input.lowercase()) {
        "north", "northup", "north-up" -> MinimapOrientation.NORTH_UP
        "forward", "heading", "forward-up", "facing" -> MinimapOrientation.FORWARD_UP
        else -> null
    }

    private fun parseChat(input: String): ChatMode? = when (input.lowercase()) {
        "global", "g" -> ChatMode.GLOBAL
        "town", "t" -> ChatMode.TOWN
        "nation", "n" -> ChatMode.NATION
        "ally", "a" -> ChatMode.ALLY
        "local", "l" -> ChatMode.LOCAL
        else -> null
    }

    private fun isValidCaptureMessageMode(input: String): Boolean = when (input.lowercase()) {
        "title", "titles", "chat", "message", "messages" -> true
        else -> false
    }

    private fun refreshMinimap(player: Player, resident: Resident) {
        val loc = player.location
        val coordX = floor(loc.x).toInt()
        val coordZ = floor(loc.z).toInt()
        resident.updateMinimap(Coord.fromBlockCoords(coordX, coordZ))
    }

    public fun tabComplete(args: Array<String>): List<String> {
        if (args.isEmpty()) {
            return optionSubcommands
        }

        return when (args.size) {
            1 -> filterByStart(optionSubcommands, args[0])
            2 -> when (args[0].lowercase()) {
                "orientation" -> filterByStart(listOf("north", "forward"), args[1])
                "chat" -> filterByStart(listOf("global", "town", "nation", "ally", "local"), args[1])
                "attackmessages" -> filterByStart(listOf("all", "town", "nation", "ally", "warzone", "captures"), args[1])
                "capturemessages" -> filterByStart(listOf("title", "chat"), args[1])
                else -> listOf()
            }
            else -> listOf()
        }
    }
}
