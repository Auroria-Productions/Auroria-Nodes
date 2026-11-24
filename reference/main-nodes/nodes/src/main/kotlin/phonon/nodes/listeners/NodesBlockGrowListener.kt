package phonon.nodes.listeners

import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockGrowEvent
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory

public class NodesBlockGrowListener : Listener {

    @EventHandler
    public fun onBlockGrow(event: BlockGrowEvent) {
        if (!phonon.nodes.core.CoreHooks.crops()) return
        val block = event.block
        val blockY = block.location.blockY

        // checks that crop meets sky light level requirements
        if (block.lightFromSky < Config.cropsMinSkyLight) {
            event.setCancelled(true)
            return
        }

        // check block within height range allowed for farming
        if (blockY < Config.cropsMinYHeight || blockY > Config.cropsMaxYHeight) {
            event.setCancelled(true)
            return
        }

        val chunk: Chunk = event.block.chunk
        val territory: Territory? = Nodes.getTerritoryFromChunk(chunk)

        // Crops grow everywhere by default.
        // Only apply resource node rate limiting if the territory has a crop node.
        if (territory !== null && territory.cropsCanGrow) {
            val snapshot = Nodes.getTerritoryResourceSnapshot(territory)
            val blockState = event.getNewState()
            var plant: Material = blockState.type

            if (!snapshot.crops.containsKey(plant)) {
                val altName = Config.cropAlternativeNames.get(plant)
                if (altName === null || !snapshot.crops.containsKey(altName)) {
                    // Crop not in this territory's node — allow growth freely
                    return
                }
                plant = altName
            }

            // Roll against the node's grow rate
            val randNum = Math.random()
            val growRate = snapshot.crops.get(plant) ?: 0.0
            if (randNum <= growRate) {
                return
            }

            // Failed the roll — cancel this tick (vanilla will retry next random tick)
            event.setCancelled(true)
        }
        // No territory, unowned land, or territory without a crop node — grow freely
    }
}
