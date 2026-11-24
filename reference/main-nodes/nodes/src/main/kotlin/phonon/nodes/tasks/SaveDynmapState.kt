/**
 * Tasks for copying nodes world save state files to the dynmap plugin
 * folder.
 */

package phonon.nodes.tasks

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.configuration.file.YamlConfiguration
import phonon.nodes.Config
import phonon.nodes.utils.saveStringToFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Task for copying world.json or towns.json to dynmap folder.
 */
public class TaskCopyToDynmap(
    val pathTownsJson: Path,
    val pathDynmapDir: Path,
    val pathDynmapJson: Path,
) : Runnable {
    override fun run() {
        Files.createDirectories(pathDynmapDir)
        if (pathDynmapJson.fileName.toString().equals("world.json", ignoreCase = true)) {
            val gson = GsonBuilder().disableHtmlEscaping().create()
            val root = JsonParser.parseReader(Files.newBufferedReader(pathTownsJson)).asJsonObject
            injectNodeTypesIntoWorldJson(root)
            Files.writeString(pathDynmapJson, gson.toJson(root))
        } else if (pathDynmapJson.fileName.toString().equals("warzones.json", ignoreCase = true)) {
            val gson = GsonBuilder().disableHtmlEscaping().create()
            Files.writeString(pathDynmapJson, gson.toJson(buildWarzonesJson()))
        } else {
            Files.copy(pathTownsJson, pathDynmapJson, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun injectNodeTypesIntoWorldJson(root: JsonObject) {
    val nodes = root.getAsJsonObject("nodes") ?: JsonObject().also { root.add("nodes", it) }
    for ((name, node) in Config.nodeTypes) {
        if (nodes.has(name)) {
            continue
        }
        val nodeJson = JsonObject()
        node.icon?.let { nodeJson.addProperty("icon", it) }
        nodeJson.addProperty("priority", node.priority)
        val cost = JsonObject()
        cost.addProperty("constant", 0)
        cost.addProperty("scale", 1.0)
        nodeJson.add("cost", cost)
        nodes.add(name, nodeJson)
    }
}

private fun buildWarzonesJson(): JsonObject {
    val root = JsonObject()
    val meta = JsonObject()
    meta.addProperty("type", "warzones")
    root.add("meta", meta)

    val yaml = YamlConfiguration.loadConfiguration(pathSafeFile(Config.pathWarzones))
    val gson = GsonBuilder().disableHtmlEscaping().create()
    val serialized = mutableListOf<Map<String, Any>>()

    for (entry in yaml.getMapList("warzones")) {
        val name = (entry["name"] as? String)?.trim().orEmpty()
        if (name.isBlank()) {
            continue
        }
        val nodes = when (val rawNodes = entry["nodes"]) {
            is List<*> -> rawNodes.mapNotNull { it?.toString()?.toIntOrNull() }
            else -> emptyList()
        }
        serialized.add(
            linkedMapOf(
                "name" to name,
                "nodes" to nodes,
            ),
        )
    }

    root.add("warzones", gson.toJsonTree(serialized))
    return root
}

private fun pathSafeFile(path: Path): java.io.File = path.toFile()

/**
 * Copy global claims config into dynmap folder as "config.json":
 * {
 *   "meta":{"type":"config"},
 *   "territoryCost":{
 *       "constant": 10,
 *       "scale": 0.25
 *   }
 * }
 */
public class TaskSaveDynmapClaimsConfig(
    val territoryCostBase: Int,
    val territoryCostScale: Double,
    val pathDynmapConfig: Path,
) : Runnable {

    override fun run() {
        val configJson = (
            "{\"meta\":{\"type\":\"config\"}," +
                "\"territoryCost\":{\"constant\":$territoryCostBase,\"scale\":$territoryCostScale}}"
            )

        saveStringToFile(configJson, pathDynmapConfig)
    }
}
