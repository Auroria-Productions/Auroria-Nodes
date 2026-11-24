package phonon.nodes.utils.leaderlaunch

import org.bukkit.configuration.file.YamlConfiguration
import phonon.nodes.Config
import java.io.File
import java.io.IOException
import java.util.UUID

public object LeaderLaunchManager {
    public data class LeaderLaunchStatus(
        val enabled: Boolean,
        val registeredPlayers: List<String>,
    )

    private val lock = Any()

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var registeredByUuid: Map<UUID, String> = linkedMapOf()

    @Volatile
    private var allowedPlayers: Set<UUID> = emptySet()

    private const val FILE_NAME = "leaderlaunch.yml"
    private const val BLOCKED_MESSAGE =
        "Leader Launch is currently active.\nYou cannot join right now.\n\nNeed access? Buy a Leader Launch pass or a rank in the store.\nDiscord: https://discord.gg/zWZrAGbcQX"

    public fun load() {
        val file = getDataFile()
        if (!file.exists()) {
            synchronized(lock) {
                enabled = false
                registeredByUuid = linkedMapOf()
                allowedPlayers = emptySet()
            }
            save()
            return
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val loadedPlayers = linkedMapOf<UUID, String>()

        for (key in config.getConfigurationSection("players")?.getKeys(false).orEmpty()) {
            val uuid = key.toUUIDOrNull() ?: continue
            val name = config.getString("players.$key", "")?.trim().orEmpty()
            loadedPlayers[uuid] = if (name.isEmpty()) key else name
        }

        synchronized(lock) {
            enabled = config.getBoolean("enabled", false)
            registeredByUuid = loadedPlayers
            allowedPlayers = loadedPlayers.keys.toHashSet()
        }
    }

    public fun shutdown() {
        save()
    }

    public fun save() {
        val snapshotEnabled = enabled
        val snapshotPlayers = registeredByUuid
        val file = getDataFile()
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val config = YamlConfiguration()
        config.set("enabled", snapshotEnabled)
        for ((uuid, name) in snapshotPlayers) {
            config.set("players.$uuid", name)
        }

        try {
            config.save(file)
        } catch (_: IOException) {
        }
    }

    public fun setEnabled(value: Boolean) {
        var changed = false
        synchronized(lock) {
            if (enabled != value) {
                enabled = value
                changed = true
            }
        }
        if (changed) {
            save()
        }
    }

    public fun isEnabled(): Boolean = enabled

    public fun canJoin(playerUuid: UUID): Boolean = !enabled || allowedPlayers.contains(playerUuid)

    public fun addPlayer(playerUuid: UUID, playerName: String): Boolean {
        val normalizedName = playerName.trim().ifEmpty { playerUuid.toString() }
        var changed = false
        synchronized(lock) {
            val updated = LinkedHashMap(registeredByUuid)
            val previous = updated.put(playerUuid, normalizedName)
            if (previous != normalizedName) {
                registeredByUuid = updated
                allowedPlayers = updated.keys.toHashSet()
                changed = true
            }
        }
        if (changed) {
            save()
        }
        return changed
    }

    public fun removePlayer(playerUuid: UUID): Boolean {
        var changed = false
        synchronized(lock) {
            if (registeredByUuid.containsKey(playerUuid)) {
                val updated = LinkedHashMap(registeredByUuid)
                updated.remove(playerUuid)
                registeredByUuid = updated
                allowedPlayers = updated.keys.toHashSet()
                changed = true
            }
        }
        if (changed) {
            save()
        }
        return changed
    }

    public fun getStatus(): LeaderLaunchStatus {
        val players = listRegisteredNames()
        return LeaderLaunchStatus(enabled = enabled, registeredPlayers = players)
    }

    public fun listRegisteredNames(): List<String> = registeredByUuid.values.sortedBy { it.lowercase() }

    public fun getBlockedMessage(): String = BLOCKED_MESSAGE

    private fun getDataFile(): File = File(Config.pathPlugin, FILE_NAME)

    private fun String.toUUIDOrNull(): UUID? = try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }
}
