package phonon.nodes.objects

import org.bukkit.block.Block
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.utils.EnumArrayMap
import phonon.nodes.utils.string.stringArrayFromSet
import java.util.EnumSet

internal fun permissionsToJsonString(permissions: EnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>>): String {
    val str = StringBuilder()

    str.append("{")

    var index: Int = 0
    for (type in enumValues<TownPermissions>()) {
        val groups = permissions[type]
        str.append("\"${type}\":")
        str.append(stringArrayFromSet<PermissionsGroup>(groups, { g -> "${g.ordinal}" }))
        if (index < permissions.size - 1) {
            str.append(",")
        }
        index += 1
    }

    str.append("}")
    return str.toString()
}

internal fun blocksToJsonString(blocks: Collection<Block>): String {
    val str = StringBuilder()
    str.append("[")

    var index: Int = 0
    for (block in blocks) {
        str.append("[${block.x},${block.y},${block.z}]")
        if (index < blocks.size - 1) {
            str.append(",")
        }
        index += 1
    }

    str.append("]")
    return str.toString()
}

internal fun townOutpostsToJsonString(outposts: Map<String, TownOutpost>): String {
    val str = StringBuilder()
    str.append("{")

    var index: Int = 0
    for ((name, outpost) in outposts) {
        str.append("\"${name}\":${outpost.toJsonString()}")
        if (index < outposts.size - 1) {
            str.append(",")
        }
        index += 1
    }

    str.append("}")
    return str.toString()
}
