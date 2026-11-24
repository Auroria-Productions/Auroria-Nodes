package phonon.nodes.warzone

import org.bukkit.ChatColor
import net.md_5.bungee.api.ChatColor as BungeeChatColor

public object WarzoneText {
    public const val PRIMARY_HEX: String = "#00ecff"
    public val PRIMARY: String = BungeeChatColor.of(PRIMARY_HEX).toString()
    public val RESET: String = ChatColor.RESET.toString()

    public fun prefix(): String = "$PRIMARY[Warzone]$RESET"
}
