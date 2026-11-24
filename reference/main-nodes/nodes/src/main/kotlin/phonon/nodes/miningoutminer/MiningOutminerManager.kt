package phonon.nodes.miningoutminer

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.Listener
import phonon.nodes.Config
import phonon.nodes.NodesExtensions

object MiningOutminerManager : Listener {

    var enabled: Boolean = true

    private const val SPAWN_BLOCK_RADIUS = 50
    private const val SPAWN_BLOCK_RADIUS_SQ = SPAWN_BLOCK_RADIUS * SPAWN_BLOCK_RADIUS

    private val plugin get() = NodesExtensions.instance
    private val dataFile get() = Config.pathMiningOutminerData.toFile()
    private val outminers = linkedMapOf<LocationKey, MiningOutminer>()

    fun load() {
        outminers.clear()
        if (!dataFile.exists()) {
            plugin.logger.info("[MiningOutminer] No data file found, starting fresh.")
            return
        }

        val config = YamlConfiguration.loadConfiguration(dataFile)
        config.getMapList("outminers").forEach { raw ->
            @Suppress("UNCHECKED_CAST")
            val entry = raw as? Map<String, Any> ?: return@forEach
            val world = entry["world"] as? String ?: return@forEach
            val x = (entry["x"] as? Number)?.toInt() ?: return@forEach
            val y = (entry["y"] as? Number)?.toInt() ?: return@forEach
            val z = (entry["z"] as? Number)?.toInt() ?: return@forEach

            val resources = linkedMapOf<Material, Int>()

            @Suppress("UNCHECKED_CAST")
            val rawResources = entry["resources"] as? Map<String, Any>
            rawResources?.forEach { (materialName, amountValue) ->
                val material = Material.matchMaterial(materialName)
                val amount = (amountValue as? Number)?.toInt() ?: return@forEach
                if (material != null && material != Material.AIR && amount > 0) {
                    resources[material] = amount
                }
            }

            val key = LocationKey(world, x, y, z)
            outminers[key] = MiningOutminer(key, resources)
        }

        plugin.logger.info("[MiningOutminer] Loaded ${outminers.size} mining outposts.")
    }

    fun save() {
        dataFile.parentFile?.mkdirs()
        val config = YamlConfiguration()
        config.set(
            "outminers",
            outminers.values.map { miner ->
                mapOf(
                    "world" to miner.key.world,
                    "x" to miner.key.x,
                    "y" to miner.key.y,
                    "z" to miner.key.z,
                    "resources" to miner.resources.entries.associate { (material, amount) -> material.name to amount },
                )
            },
        )
        config.save(dataFile)
        plugin.logger.info("[MiningOutminer] Saved ${outminers.size} mining outposts.")
    }

    fun getCount(): Int = outminers.size

    fun placeAt(block: Block): String? {
        val key = LocationKey.from(block) ?: return "Block location is invalid"
        if (outminers.containsKey(key)) {
            return "A Mining Outpost already exists at ${placementSummary(block)}"
        }

        outminers[key] = MiningOutminer(key)
        return null
    }

    fun placementSummary(block: Block): String {
        val world = block.world?.name ?: "<unknown>"
        return "$world (${block.x}, ${block.y}, ${block.z})"
    }

    fun removeAt(block: Block): Boolean = LocationKey.from(block)?.let { outminers.remove(it) } != null

    fun getRewardsAt(block: Block): Map<Material, Int>? = getOutminer(block)?.resources?.toMap()

    fun setResourceAt(block: Block, material: Material, amount: Int): String? {
        val miner = getOutminer(block) ?: return "No Mining Outpost found on the targeted block"
        if (amount <= 0) {
            miner.resources.remove(material)
            return null
        }

        miner.resources[material] = amount
        return null
    }

    fun isTownSpawnBlocked(location: Location): Boolean {
        val source = LocationKey.from(location) ?: return false
        return outminers.keys.any { it.world == source.world && it.squaredDistance(source) <= SPAWN_BLOCK_RADIUS_SQ }
    }

    private fun getOutminer(block: Block): MiningOutminer? = LocationKey.from(block)?.let { outminers[it] }
}

private data class MiningOutminer(
    val key: LocationKey,
    val resources: MutableMap<Material, Int> = linkedMapOf(),
)

private data class LocationKey(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun squaredDistance(other: LocationKey): Int {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        fun from(block: Block): LocationKey? {
            val worldName = block.world?.name ?: return null
            return LocationKey(worldName, block.x, block.y, block.z)
        }

        fun from(location: Location): LocationKey? {
            val worldName = location.world?.name ?: return null
            return LocationKey(worldName, location.blockX, location.blockY, location.blockZ)
        }
    }
}
