/**
 * Allow shearing sheep based on territory interact permissions,
 * not sheep resource node ownership.
 */

package phonon.nodes.listeners

import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerShearEntityEvent
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.TownPermissions
import phonon.nodes.objects.Territory

public class NodesSheepShearListener : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public fun onEntityShear(event: PlayerShearEntityEvent) {
        val player = event.player

        // op bypass
        if (player.isOp()) {
            return
        }

        val entity: Entity = event.getEntity()
        val territory: Territory? = Nodes.getTerritoryFromChunk(entity.location.chunk)
        val town = territory?.town

        // allow shearing in wilderness or unclaimed territory
        if (territory === null || town === null) {
            return
        }

        val resident = Nodes.getResident(player) ?: run {
            Message.error(player, "You cannot shear sheep here!")
            event.isCancelled = true
            return
        }

        // check normal town interact permissions (covers own town, allies, etc.)
        if (hasTownPermissions(TownPermissions.INTERACT, town, resident)) {
            return
        }

        // check occupier permissions
        val occupier = territory.occupier
        if (occupier !== null && hasOccupierPermissions(TownPermissions.INTERACT, town, occupier, resident)) {
            return
        }

        Message.error(player, "You cannot shear sheep here!")
        event.isCancelled = true
    }
}
