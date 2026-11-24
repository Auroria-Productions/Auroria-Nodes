package phonon.nodes.warzone

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

public class WarzoneChatListener : Listener {

    @EventHandler
    public fun onPlayerChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        if (WarzoneManager.handlePendingChat(player, event.message)) {
            event.isCancelled = true
        }
    }
}
