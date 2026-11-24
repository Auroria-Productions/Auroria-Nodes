package phonon.nodes.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import phonon.nodes.killtracker.KillTrackerManager

public class KillTrackerListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        val killer = victim.killer

        KillTrackerManager.addDeath(victim.uniqueId)

        if (killer != null && killer != victim) {
            KillTrackerManager.addKill(killer.uniqueId)
        }
    }
}
