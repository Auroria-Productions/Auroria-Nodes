package phonon.nodes.listeners

import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory
import phonon.nodes.utils.entity.spawnEggFromEntity
import java.util.concurrent.ThreadLocalRandom

public class NodesEntityBreedListener : Listener {

    // Cancelling breed event causes entities to keep breeding until timeout
    // Cannot stop breeding, instead delete spawned child with % chance
    // from territory resource nodes
    @EventHandler
    public fun onEntitySpawn(event: CreatureSpawnEvent) {
        val spawnReason = event.getSpawnReason()
        if (spawnReason != SpawnReason.BREEDING && spawnReason != SpawnReason.DISPENSE_EGG && spawnReason != SpawnReason.EGG) {
            return
        }

        val location = event.location
        val block = location.block
        val blockY = location.blockY

        // checks that breed event meets sky light level requirements
        if (block.lightFromSky < Config.breedingMinSkyLight) {
            event.setCancelled(true)
            return
        }

        // check breed event within height range allowed
        if (blockY < Config.breedingMinYHeight || blockY > Config.breedingMaxYHeight) {
            event.setCancelled(true)
            return
        }

        val entity: Entity = event.getEntity() // father and mother are the same entity type
        val territory: Territory? = Nodes.getTerritoryFromChunk(entity.location.chunk)

        // Breeding is allowed everywhere by default.
        // Only apply resource node rate limiting and tax if the territory has an animal node.
        if (territory !== null && territory.animalsCanBreed) {
            val snapshot = Nodes.getTerritoryResourceSnapshot(territory)
            val entityType = entity.type
            val breedRate = snapshot.animals.get(entityType)

            if (breedRate !== null) {
                val randNum: Double = ThreadLocalRandom.current().nextDouble()
                if (randNum <= breedRate) {
                    // do tax event check
                    val territoryOccupier = territory.occupier
                    if (territoryOccupier != null && Math.random() <= Config.taxAnimalRate) {
                        // convert bred animal to spawn egg given to occupier
                        val spawnEgg = spawnEggFromEntity(entityType)
                        if (spawnEgg !== null) {
                            Nodes.addToIncome(territoryOccupier, spawnEgg, 1)
                        }
                        event.entity.remove()
                    }
                    // handle town over max claims penalty
                    else if (Config.claimsPowerEnabled && Config.overClaimsPenalty && territory.town?.isOverClaimsMax == true) {
                        if (Math.random() < Config.overClaimsMaxPenalty) {
                            event.entity.remove()
                        }
                    }
                } else {
                    // Failed the node rate roll — cancel the spawn
                    event.entity.remove()
                }
            }
            // Entity type not in this territory's animal node — allow freely
        }
        // No territory or unowned land — allow freely
    }
}
