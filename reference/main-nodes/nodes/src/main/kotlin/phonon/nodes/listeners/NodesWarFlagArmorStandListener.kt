package phonon.nodes.listeners

import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import phonon.nodes.war.NODES_ARMORSTAND_KEY
import phonon.nodes.war.NODES_WAR_DISPLAY_KEY

public class NodesWarFlagArmorStandListener : Listener {

    /**
     * If a war flag display entity is loaded from a chunk, it is most
     * likely a stale entity. Normally these are cleaned up on shutdown,
     * but crashes can leave dangling entities behind.
     *
     * If we accidentally delete a valid display entity when a chunk becomes
     * loaded, that's okay because the war flag system will automatically respawn it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public fun onLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            if ((entity.type == EntityType.ARMOR_STAND || entity.type == EntityType.TEXT_DISPLAY) &&
                (
                    entity.persistentDataContainer.has(NODES_WAR_DISPLAY_KEY, PersistentDataType.INTEGER) ||
                        entity.persistentDataContainer.has(NODES_ARMORSTAND_KEY, PersistentDataType.INTEGER)
                    )
            ) {
                entity.remove()
            }
        }
    }
}
