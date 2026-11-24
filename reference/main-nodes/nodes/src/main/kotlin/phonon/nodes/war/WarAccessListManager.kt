package phonon.nodes.war

import org.bukkit.configuration.file.YamlConfiguration
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Town
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID

public data class WarAccessEntry(
    val uuid: UUID,
    var name: String,
)

public enum class WarAccessUpdateResult {
    ADDED,
    REMOVED,
    ALREADY_PRESENT,
    NOT_FOUND,
}

public object WarAccessListManager {
    private val whitelist: LinkedHashMap<UUID, WarAccessEntry> = linkedMapOf()
    private val blacklist: LinkedHashMap<UUID, WarAccessEntry> = linkedMapOf()
    private val annexBlacklist: LinkedHashMap<UUID, WarAccessEntry> = linkedMapOf()

    private val dataFile: File
        get() {
            val file = Config.pathWarAccessLists.toFile()
            file.parentFile?.mkdirs()
            return file
        }

    public fun initialize() {
        load()
    }

    public fun shutdown() {
        save()
    }

    public fun refreshTownNames() {
        refreshNamesForMap(whitelist)
        refreshNamesForMap(blacklist)
        refreshNamesForMap(annexBlacklist)
    }

    public fun hasWhitelist(): Boolean = whitelist.isNotEmpty()
    public fun hasBlacklist(): Boolean = blacklist.isNotEmpty()
    public fun hasAnnexBlacklist(): Boolean = annexBlacklist.isNotEmpty()

    public fun isTownWhitelisted(town: Town?): Boolean = town?.uuid?.let { whitelist.containsKey(it) } ?: false
    public fun isTownBlacklisted(town: Town?): Boolean = town?.uuid?.let { blacklist.containsKey(it) } ?: false
    public fun isTownAnnexBlacklisted(town: Town?): Boolean = town?.uuid?.let { annexBlacklist.containsKey(it) } ?: false

    public fun listWhitelist(): Collection<WarAccessEntry> = whitelist.values
    public fun listBlacklist(): Collection<WarAccessEntry> = blacklist.values
    public fun listAnnexBlacklist(): Collection<WarAccessEntry> = annexBlacklist.values

    public fun addToWhitelist(name: String): WarAccessUpdateResult = add(name, whitelist)
    public fun addToBlacklist(name: String): WarAccessUpdateResult = add(name, blacklist)
    public fun addToAnnexBlacklist(name: String): WarAccessUpdateResult = add(name, annexBlacklist)
    public fun removeFromWhitelist(name: String): WarAccessUpdateResult = remove(name, whitelist)
    public fun removeFromBlacklist(name: String): WarAccessUpdateResult = remove(name, blacklist)
    public fun removeFromAnnexBlacklist(name: String): WarAccessUpdateResult = remove(name, annexBlacklist)

    private fun add(name: String, map: LinkedHashMap<UUID, WarAccessEntry>): WarAccessUpdateResult {
        val town = lookupTown(name) ?: return WarAccessUpdateResult.NOT_FOUND
        if (map.containsKey(town.uuid)) {
            return WarAccessUpdateResult.ALREADY_PRESENT
        }
        map[town.uuid] = WarAccessEntry(town.uuid, town.name)
        save()
        return WarAccessUpdateResult.ADDED
    }

    private fun remove(name: String, map: LinkedHashMap<UUID, WarAccessEntry>): WarAccessUpdateResult {
        val town = lookupTown(name)
        val entryKey = town?.uuid ?: parseUuid(name)
        if (entryKey == null) {
            return WarAccessUpdateResult.NOT_FOUND
        }
        if (map.remove(entryKey) != null) {
            save()
            return WarAccessUpdateResult.REMOVED
        }
        return WarAccessUpdateResult.NOT_FOUND
    }

    private fun lookupTown(input: String): Town? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        Nodes.towns[trimmed]?.let { return it }
        val match = Nodes.towns.values.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        if (match != null) {
            return match
        }
        val uuid = parseUuid(trimmed)
        if (uuid != null) {
            return Nodes.towns.values.firstOrNull { it.uuid == uuid }
        }
        return null
    }

    private fun parseUuid(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (_: Exception) {
        null
    }

    private fun refreshNamesForMap(map: LinkedHashMap<UUID, WarAccessEntry>) {
        val towns = Nodes.towns
        for ((uuid, entry) in map) {
            val town = towns.values.firstOrNull { it.uuid == uuid }
            if (town != null) {
                entry.name = town.name
            }
        }
    }

    private fun load() {
        whitelist.clear()
        blacklist.clear()
        annexBlacklist.clear()

        val file = dataFile
        if (!file.exists()) {
            return
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        loadList(yaml, "whitelist", whitelist)
        loadList(yaml, "blacklist", blacklist)
        loadList(yaml, "annexBlacklist", annexBlacklist)
    }

    private fun loadList(yaml: YamlConfiguration, key: String, map: LinkedHashMap<UUID, WarAccessEntry>) {
        val entries = yaml.getMapList(key) ?: return
        for (raw in entries) {
            val uuid = (raw["uuid"] as? String)?.let { parseUuid(it) } ?: continue
            val name = raw["name"] as? String ?: "<unknown>"
            map[uuid] = WarAccessEntry(uuid, name)
        }
    }

    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("whitelist", whitelist.values.map { entry -> mapOf("uuid" to entry.uuid.toString(), "name" to entry.name) })
        yaml.set("blacklist", blacklist.values.map { entry -> mapOf("uuid" to entry.uuid.toString(), "name" to entry.name) })
        yaml.set("annexBlacklist", annexBlacklist.values.map { entry -> mapOf("uuid" to entry.uuid.toString(), "name" to entry.name) })
        try {
            yaml.save(dataFile)
        } catch (err: Exception) {
            Nodes.logger?.warning("Failed to save war access list: ${err.message}")
        }
    }
}

public class WarAccessListSnapshot(private val supplier: () -> Collection<WarAccessEntry>) {
    public val list: Collection<WarAccessEntry>
        get() = supplier()

    public val size: Int
        get() = list.size
}

public val FlagWar.whitelist: WarAccessListSnapshot
    get() = WarAccessListSnapshot { WarAccessListManager.listWhitelist() }

public val FlagWar.blacklist: WarAccessListSnapshot
    get() = WarAccessListSnapshot { WarAccessListManager.listBlacklist() }

public val FlagWar.annexBlacklist: WarAccessListSnapshot
    get() = WarAccessListSnapshot { WarAccessListManager.listAnnexBlacklist() }
