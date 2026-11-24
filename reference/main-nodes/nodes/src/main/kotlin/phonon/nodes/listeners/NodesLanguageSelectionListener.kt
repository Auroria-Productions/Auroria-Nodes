package phonon.nodes.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import phonon.nodes.Nodes
import phonon.nodes.language.PlayerLanguage

public class NodesLanguageSelectionListener : Listener {

    private fun ensureDefaultLanguage(player: org.bukkit.entity.Player) {
        val resident = Nodes.getResident(player) ?: return
        if (resident.language == null) {
            resident.language = PlayerLanguage.EN
            resident.needsUpdate()
            Nodes.needsSave = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onJoin(event: PlayerJoinEvent) {
        ensureDefaultLanguage(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onInteract(event: PlayerInteractEvent) {
        ensureDefaultLanguage(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onBreak(event: BlockBreakEvent) {
        ensureDefaultLanguage(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onPlace(event: BlockPlaceEvent) {
        ensureDefaultLanguage(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onCommand(event: PlayerCommandPreprocessEvent) {
        ensureDefaultLanguage(event.player)
    }
}
