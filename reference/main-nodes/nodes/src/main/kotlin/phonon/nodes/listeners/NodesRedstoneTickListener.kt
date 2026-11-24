package phonon.nodes.listeners

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import phonon.nodes.Config

class NodesRedstoneTickListener : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPhysics(event: BlockPhysicsEvent) {
        if (!Config.disableRedstoneTicking) {
            return
        }

        if (isRedstoneComponent(event.block.type)) {
            event.isCancelled = true
        }
    }

    private fun isRedstoneComponent(type: Material): Boolean {
        if (!type.isBlock) {
            return false
        }

        if (type == Material.REPEATER ||
            type == Material.COMPARATOR ||
            type == Material.DAYLIGHT_DETECTOR
        ) {
            return true
        }

        val upper = type.name
        return upper.contains("REDSTONE") || upper.contains("DAYLIGHT")
    }
}
