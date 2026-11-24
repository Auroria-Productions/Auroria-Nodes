package phonon.nodes.plot

import org.bukkit.Location
import org.bukkit.block.Block
import phonon.nodes.Nodes
import phonon.nodes.constants.DiplomaticRelationship
import phonon.nodes.objects.Resident
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID

public enum class PlotPermissionType {
    BUILD,
    DESTROY,
    INTERACT,
    CONTAINERS,
    ITEMS,
}

public enum class PlotPermissionGroup {
    OWNER,
    OFFICER,
    MEMBER,
    TOWN,
    TRUSTED,
    NATION,
    ALLY,
    OTHERTOWN,
    OUTSIDER,
}

public data class PlotSelection(
    val world: String,
    val x1: Int,
    val y1: Int,
    val z1: Int,
    val x2: Int,
    val y2: Int,
    val z2: Int,
) {
    val minX: Int = minOf(x1, x2)
    val maxX: Int = maxOf(x1, x2)
    val minY: Int = minOf(y1, y2)
    val maxY: Int = maxOf(y1, y2)
    val minZ: Int = minOf(z1, z2)
    val maxZ: Int = maxOf(z1, z2)

    fun contains(block: Block): Boolean = block.world.name == world &&
        block.x in minX..maxX &&
        block.y in minY..maxY &&
        block.z in minZ..maxZ

    fun contains(location: Location): Boolean = location.world?.name == world &&
        location.blockX in minX..maxX &&
        location.blockY in minY..maxY &&
        location.blockZ in minZ..maxZ

    fun intersects(other: PlotSelection): Boolean {
        if (world != other.world) return false
        return !(
            maxX < other.minX ||
                minX > other.maxX ||
                maxY < other.minY ||
                minY > other.maxY ||
                maxZ < other.minZ ||
                minZ > other.maxZ
            )
    }

    fun chunkRangeX(): IntRange = Math.floorDiv(minX, 16)..Math.floorDiv(maxX, 16)
    fun chunkRangeZ(): IntRange = Math.floorDiv(minZ, 16)..Math.floorDiv(maxZ, 16)
}

public data class Plot(
    val id: String,
    var name: String,
    val townName: String,
    var ownerUuid: UUID?,
    var ownerName: String?,
    val selection: PlotSelection,
    val permissions: EnumMap<PlotPermissionType, EnumSet<PlotPermissionGroup>>,
    val residentPermissions: EnumMap<PlotPermissionType, LinkedHashMap<UUID, String>>,
) {
    fun isAllowed(resident: Resident, type: PlotPermissionType): Boolean {
        val residents = residentPermissions[type]
        if (residents?.containsKey(resident.uuid) == true) {
            return true
        }

        val groups = permissions[type] ?: return false
        val residentTown = resident.town
        val plotTown = Nodes.getTownFromName(townName)

        if (ownerUuid != null && resident.uuid == ownerUuid) {
            return groups.contains(PlotPermissionGroup.OWNER)
        }

        if (residentTown != null && plotTown != null && residentTown === plotTown) {
            if (resident.trusted && groups.contains(PlotPermissionGroup.TRUSTED)) {
                return true
            }
            if (plotTown.leader === resident || plotTown.officers.contains(resident)) {
                return groups.contains(PlotPermissionGroup.OFFICER) || groups.contains(PlotPermissionGroup.MEMBER)
            }
            return groups.contains(PlotPermissionGroup.MEMBER) || groups.contains(PlotPermissionGroup.TOWN)
        }

        if (residentTown != null && plotTown != null) {
            if (residentTown.nation != null && residentTown.nation === plotTown.nation) {
                return groups.contains(PlotPermissionGroup.NATION)
            }

            val rel = Nodes.getRelationshipOfTownToTown(residentTown, plotTown)
            if (rel == DiplomaticRelationship.ALLY) {
                return groups.contains(PlotPermissionGroup.ALLY)
            }

            if (groups.contains(PlotPermissionGroup.OTHERTOWN)) {
                return true
            }
        }

        return groups.contains(PlotPermissionGroup.OUTSIDER) && residentTown == null
    }

    fun centerString(): String {
        val cx = (selection.minX + selection.maxX) / 2
        val cy = (selection.minY + selection.maxY) / 2
        val cz = (selection.minZ + selection.maxZ) / 2
        return "$cx, $cy, $cz"
    }

    fun residentPermissionNames(type: PlotPermissionType): List<String> = residentPermissions[type]?.values?.sortedBy { it.lowercase() } ?: emptyList()
}
