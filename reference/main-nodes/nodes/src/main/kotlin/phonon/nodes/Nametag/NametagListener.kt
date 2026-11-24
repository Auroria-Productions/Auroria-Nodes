package phonon.nodes.nametags

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import phonon.nodes.objects.Nametag

class NametagListener(private val plugin: JavaPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->

            // Update snapshot so the joiner appears in addPlayers packets
            // sent to other players during their burst updates
            Nametag.snapshotAddPlayer(player)

            // FIX Bug 1: send addPlayers packet for the joiner to all existing
            // clients that already have the joiner's town team cached.
            // Without this call, existing players never receive a
            // sendTeamAddPlayers packet for the joiner, so their client never
            // associates the joiner's name with the team — the nametag stays
            // invisible until they rejoin and rebuild their full team state.
            Nametag.onPlayerJoin(player)

            // Joiner's own full build goes through the PRIORITY pipeline.
            Nametag.markPriorityAndFlush(player)

            // Existing clients are marked BURST — NOT sent an immediate broadcast.
            val others = Bukkit.getOnlinePlayers().filter { it.uniqueId != player.uniqueId }
            Nametag.markBurstAndFlush(others)
        }, 5L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        // Handles: cache eviction, queue removal, snapshot removal, failure log clear
        Nametag.onPlayerQuit(event.player)
    }
}
