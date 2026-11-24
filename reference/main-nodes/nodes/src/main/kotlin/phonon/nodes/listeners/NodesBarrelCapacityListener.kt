package phonon.nodes.listeners

import org.bukkit.block.Barrel
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent

/**
 * Doubles barrel storage capacity by allowing double stack size.
 * Lightweight: runs only when a barrel inventory is opened.
 */
class NodesBarrelCapacityListener : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (event.inventory.holder !is Barrel) return
        if (event.inventory.maxStackSize < 128) {
            event.inventory.maxStackSize = 128
        }
    }
}
