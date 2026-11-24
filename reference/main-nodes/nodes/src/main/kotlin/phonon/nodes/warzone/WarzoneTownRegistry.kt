package phonon.nodes.warzone

import phonon.nodes.Nodes
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import java.util.LinkedHashSet
import java.util.UUID
import kotlin.math.abs

internal object WarzoneTownRegistry {

    private val warzoneTowns: MutableMap<String, Town> = linkedMapOf()

    public fun syncAll(warzones: Collection<Warzone>) {
        val seen = mutableSetOf<String>()
        for (warzone in warzones) {
            val town = sync(warzone)
            if (town != null) {
                seen.add(warzone.name)
            }
        }
        val toRemove = warzoneTowns.keys.filter { it !in seen }
        for (name in toRemove) {
            val town = warzoneTowns.remove(name)
            if (town != null) {
                removeTown(town)
            }
        }
    }

    public fun sync(warzone: Warzone): Town? {
        val nodes = warzone.nodes
        if (nodes.isEmpty()) {
            remove(warzone)
            return null
        }

        val homeId = nodes.first()
        val homeTerritoryId = TerritoryId(homeId)
        val homeTerritory = Nodes.getTerritoryFromId(homeTerritoryId)
        if (homeTerritory == null) {
            remove(warzone)
            return null
        }

        val townName = buildTownName(warzone.name)
        val town = Nodes.towns[townName] ?: createTown(townName, homeTerritory)
        warzoneTowns[warzone.name] = town

        if (town.home != homeTerritoryId) {
            town.home = homeTerritoryId
            town.spawnpoint = Nodes.getDefaultSpawnLocation(homeTerritory)
        }

        val desiredTerritories = LinkedHashSet<TerritoryId>()
        for (nodeId in nodes) {
            desiredTerritories.add(TerritoryId(nodeId))
        }
        desiredTerritories.add(homeTerritoryId)

        val currentTerritories = town.territories.toList()
        for (territoryId in currentTerritories) {
            if (!desiredTerritories.contains(territoryId)) {
                Nodes.getTerritoryFromId(territoryId)?.let { territory ->
                    if (territory.town === town) {
                        territory.town = null
                    }
                }
                town.territories.remove(territoryId)
            }
        }

        var claimsUsed = 0
        for (territoryId in desiredTerritories) {
            val territory = Nodes.getTerritoryFromId(territoryId) ?: continue
            if (territory.town !== town) {
                territory.town?.territories?.remove(territoryId)
            }
            territory.town = town
            town.territories.add(territoryId)
            claimsUsed += territory.cost
        }

        town.claimsUsed = claimsUsed
        town.claimsMax = Nodes.calculateMaxClaims(town)
        town.needsUpdate()
        Nodes.needsSave = true

        return town
    }

    public fun remove(warzone: Warzone) {
        val town = warzoneTowns.remove(warzone.name)
        if (town != null) {
            removeTown(town)
        }
    }

    private fun removeTown(town: Town) {
        for (territoryId in town.territories) {
            Nodes.getTerritoryFromId(territoryId)?.let { territory ->
                if (territory.town === town) {
                    territory.town = null
                }
            }
        }
        Nodes.towns.remove(town.name)
        Nodes.needsSave = true
    }

    private fun createTown(name: String, homeTerritory: Territory): Town {
        val uuid = UUID.nameUUIDFromBytes("warzone-town:$name".toByteArray())
        val spawn = Nodes.getDefaultSpawnLocation(homeTerritory)
        val town = Town(uuid, name, homeTerritory.id, null, spawn)
        town.isOpen = false
        Nodes.towns[name] = town
        return town
    }

    internal fun buildTownName(name: String): String {
        val trimmed = name.trim().lowercase()
        val normalized = trimmed.replace("[^a-z0-9]".toRegex(), "_").trim('_')
        val base = if (normalized.isNotEmpty()) normalized else "unnamed_${abs(trimmed.hashCode())}"
        return "wz_$base"
    }

    internal fun isWarzoneTown(town: Town?): Boolean {
        if (town === null) {
            return false
        }
        return warzoneTowns.values.any { it.uuid == town.uuid }
    }
}
