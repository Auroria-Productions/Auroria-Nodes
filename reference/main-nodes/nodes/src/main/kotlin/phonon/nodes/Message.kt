package phonon.nodes

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import phonon.nodes.language.Language
import phonon.nodes.objects.Town
import phonon.nodes.war.AttackMessageMode

/**
 * Helper functions for printing messages for players.
 */
public object Message {

    public val PREFIX = "[Nodes]"
    public val COL_MSG = ChatColor.AQUA
    public val COL_ERROR = ChatColor.RED

    public fun translated(sender: CommandSender?, s: String): String {
        if (sender is Player) {
            val lang = Nodes.getResident(sender)?.language
            return Language.translateRaw(s, lang)
        }
        return s
    }

    /**
     * Print generic plugin message to command sender's chat (either console
     * or player).
     */
    public fun print(sender: CommandSender?, s: String) {
        if (sender === null) {
            System.out.println("$PREFIX Message called with null sender: $s")
            return
        }

        val text = translated(sender, s)
        // Use LegacyComponentSerializer so §-prefixed ChatColor codes in `text`
        // (e.g. ${ChatColor.GREEN}) are parsed as actual color styling instead of
        // being treated as literal characters by Component.text().
        val msg = LegacyComponentSerializer.legacySection().deserialize("${COL_MSG}$text")
        sender.sendMessage(msg)
    }

    /**
     * Print error message to a command sender's chat (either console or player).
     */
    public fun error(sender: CommandSender?, s: String) {
        if (sender === null) {
            System.out.println("$PREFIX Message called with null sender: $s")
            return
        }

        val text = translated(sender, s)
        val msg = LegacyComponentSerializer.legacySection().deserialize("${COL_ERROR}$text")
        sender.sendMessage(msg)
    }

    /**
     * Send a raw chat line without Nodes prefix/color wrapper, but still run it
     * through per-player language translation.
     */
    public fun raw(sender: CommandSender?, s: String) {
        if (sender === null) {
            System.out.println("$PREFIX Message called with null sender: $s")
            return
        }

        val text = translated(sender, s)
        sender.sendMessage(text)
    }

    /**
     * Send an adventure component while translating all text segments for the
     * receiving player's selected language.
     */
    public fun component(sender: CommandSender?, component: Component) {
        if (sender === null) {
            System.out.println("$PREFIX Message called with null sender component")
            return
        }

        if (sender is Player) {
            sender.sendMessage(translateComponent(component, sender))
        } else {
            sender.sendMessage(component)
        }
    }

    /**
     * Wrapper around Bukkit.broadcast to send plugin formatted messages
     * to all players.
     */
    public fun broadcast(s: String) {
        for (player in Bukkit.getOnlinePlayers()) {
            val lang = Nodes.getResident(player)?.language
            val text = Language.translateRaw(s, lang)
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("${COL_MSG}$text"))
        }
        Bukkit.getConsoleSender().sendMessage(LegacyComponentSerializer.legacySection().deserialize("${COL_MSG}$s"))
    }

    public fun broadcastTownAttack(s: String, vararg relatedTowns: Town?) {
        broadcastWarNotice(s, relatedTowns = relatedTowns.toList())
    }

    public fun broadcastWarNotice(
        s: String,
        relatedTowns: Collection<Town?> = emptyList(),
        isWarzone: Boolean = false,
        territoryCapture: Boolean = false,
    ) {
        val related = relatedTowns.filterNotNull().toSet()
        for (player in Bukkit.getOnlinePlayers()) {
            if (!shouldReceiveWarNotice(player, related, isWarzone, territoryCapture)) {
                continue
            }
            val lang = Nodes.getResident(player)?.language
            val text = Language.translateRaw(s, lang)
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("${COL_MSG}$text"))
        }
        Bukkit.getConsoleSender().sendMessage(LegacyComponentSerializer.legacySection().deserialize("${COL_MSG}$s"))
    }

    /**
     * Wrapper around paper sendActionBar to message to player's action bar
     * above hotbar.
     */
    public fun announcement(player: Player, s: String) {
        val lang = Nodes.getResident(player)?.language
        val text = Language.translateRaw(s, lang)
        player.sendActionBar(Component.text(text))
    }

    /**
     * Send a title/subtitle pair translated for the player.
     */
    public fun title(player: Player, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int) {
        val lang = Nodes.getResident(player)?.language
        val translatedTitle = Language.translateRaw(title, lang)
        val translatedSubtitle = Language.translateRaw(subtitle, lang)
        player.sendTitle(translatedTitle, translatedSubtitle, fadeIn, stay, fadeOut)
    }

    public fun titleTownAttack(title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int, relatedTowns: Collection<Town?>) {
        titleWarNotice(title, subtitle, fadeIn, stay, fadeOut, relatedTowns = relatedTowns)
    }

    public fun titleWarNotice(
        title: String,
        subtitle: String,
        fadeIn: Int,
        stay: Int,
        fadeOut: Int,
        relatedTowns: Collection<Town?> = emptyList(),
        isWarzone: Boolean = false,
        territoryCapture: Boolean = false,
    ) {
        val related = relatedTowns.filterNotNull().toSet()
        for (player in Bukkit.getOnlinePlayers()) {
            if (!shouldReceiveWarNotice(player, related, isWarzone, territoryCapture)) {
                continue
            }
            Message.title(player, title, subtitle, fadeIn, stay, fadeOut)
        }
    }

    public fun territoryCapture(
        title: String,
        subtitle: String,
        message: String,
        fadeIn: Int,
        stay: Int,
        fadeOut: Int,
        relatedTowns: Collection<Town?>,
        isWarzone: Boolean = false,
    ) {
        val related = relatedTowns.filterNotNull().toSet()
        for (player in Bukkit.getOnlinePlayers()) {
            val resident = Nodes.getResident(player) ?: continue
            if (!shouldReceiveWarNotice(player, related, isWarzone = isWarzone, territoryCapture = true)) {
                continue
            }

            when (resident.territoryCaptureNotificationMode) {
                TerritoryCaptureNotificationMode.TITLE -> Message.title(player, title, subtitle, fadeIn, stay, fadeOut)
                TerritoryCaptureNotificationMode.CHAT -> {
                    Message.raw(player, "")
                    Message.raw(player, message)
                    Message.raw(player, "")
                }
            }
        }
    }

    private fun shouldReceiveWarNotice(player: Player, relatedTowns: Set<Town>, isWarzone: Boolean, territoryCapture: Boolean): Boolean {
        val resident = Nodes.getResident(player) ?: return true
        return when (resident.attackMessageMode) {
            AttackMessageMode.ALL -> true
            AttackMessageMode.TOWN -> matchesTown(resident.town, relatedTowns)
            AttackMessageMode.NATION -> matchesNation(resident.town, relatedTowns)
            AttackMessageMode.ALLY -> matchesAlly(resident.town, relatedTowns)
            AttackMessageMode.WARZONE_ONLY -> isWarzone
            AttackMessageMode.TERRITORY_CAPTURES -> territoryCapture
        }
    }

    private fun matchesTown(residentTown: Town?, relatedTowns: Set<Town>): Boolean {
        if (residentTown == null || relatedTowns.isEmpty()) {
            return false
        }
        return relatedTowns.any { related -> related === residentTown }
    }

    private fun matchesNation(residentTown: Town?, relatedTowns: Set<Town>): Boolean {
        if (residentTown == null || relatedTowns.isEmpty()) {
            return false
        }

        val residentNation = residentTown.nation
        return relatedTowns.any { related ->
            related === residentTown ||
                (residentNation != null && related.nation === residentNation)
        }
    }

    private fun matchesAlly(residentTown: Town?, relatedTowns: Set<Town>): Boolean {
        if (residentTown == null || relatedTowns.isEmpty()) {
            return false
        }

        val residentNation = residentTown.nation
        return relatedTowns.any { related ->
            related === residentTown ||
                residentTown.allies.contains(related) ||
                (residentNation != null && related.nation === residentNation)
        }
    }

    private fun translateComponent(component: Component, player: Player): Component {
        val lang = Nodes.getResident(player)?.language

        val translatedSelf = if (component is TextComponent) {
            val content = component.content()
            if (content.isEmpty()) {
                component
            } else {
                component.content(Language.translateRaw(content, lang))
            }
        } else {
            component
        }

        val children = translatedSelf.children()
        if (children.isEmpty()) {
            return translatedSelf
        }

        return translatedSelf.children(
            children.map { child ->
                translateComponent(child, player)
            },
        )
    }
}
