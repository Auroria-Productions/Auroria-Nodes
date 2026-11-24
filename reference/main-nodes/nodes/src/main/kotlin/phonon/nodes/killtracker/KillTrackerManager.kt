package phonon.nodes.killtracker

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Nodes
import java.io.File
import java.util.UUID

public data class PlayerKillStats(
    val uuid: UUID,
    var kills: Int = 0,
    var deaths: Int = 0,
) {
    val kdr: Double
        get() = if (deaths == 0) kills.toDouble() else kills.toDouble() / deaths.toDouble()
}

public object KillTrackerManager {
    private val stats: MutableMap<UUID, PlayerKillStats> = mutableMapOf()
    private var dirty: Boolean = false
    private var saveTask: BukkitTask? = null
    private val dataFile: File
        get() = File(Config.pathKillTracker.toString())

    public fun load() {
        migrateLegacyFile()
        if (!dataFile.exists()) {
            Nodes.logger?.info("No killtracker data found, starting fresh")
            return
        }

        try {
            val config = YamlConfiguration.loadConfiguration(dataFile)
            val playersSection = config.getConfigurationSection("players") ?: return

            for (key in playersSection.getKeys(false)) {
                val uuid = UUID.fromString(key)
                val kills = playersSection.getInt("$key.kills", 0)
                val deaths = playersSection.getInt("$key.deaths", 0)
                stats[uuid] = PlayerKillStats(uuid, kills, deaths)
            }

            Nodes.logger?.info("Loaded kill tracker data for ${stats.size} players")
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to load kill tracker data: ${e.message}")
        }
    }

    public fun save() {
        try {
            if (!dataFile.parentFile.exists()) {
                dataFile.parentFile.mkdirs()
            }

            val config = YamlConfiguration()
            for ((uuid, playerStats) in stats) {
                config.set("players.$uuid.kills", playerStats.kills)
                config.set("players.$uuid.deaths", playerStats.deaths)
            }
            config.save(dataFile)
            dirty = false
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to save kill tracker data: ${e.message}")
        }
    }

    public fun initializeAutoSave() {
        saveTask?.cancel()
        val plugin = Nodes.plugin ?: return
        // Batch writes: save at most every 30s and only when dirty.
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            Runnable {
                if (!dirty) return@Runnable
                save()
            },
            20L * 30L,
            20L * 30L,
        )
    }

    public fun shutdown() {
        saveTask?.cancel()
        saveTask = null
        if (dirty) {
            save()
        }
    }

    public fun getStats(uuid: UUID): PlayerKillStats = stats.getOrPut(uuid) { PlayerKillStats(uuid) }

    public fun addKill(uuid: UUID) {
        getStats(uuid).kills++
        dirty = true
    }

    public fun addDeath(uuid: UUID) {
        getStats(uuid).deaths++
        dirty = true
    }

    public fun getRank(uuid: UUID): Int? {
        val ranking = stats.values
            .filter { it.kills > 0 || it.deaths > 0 }
            .sortedByDescending { it.kdr }
        val index = ranking.indexOfFirst { it.uuid == uuid }
        return if (index == -1) null else index + 1
    }

    public fun getTopPlayers(limit: Int = 10): List<Pair<PlayerKillStats, String>> = stats.values
        .filter { it.kills > 0 || it.deaths > 0 }
        .sortedByDescending { it.kdr }
        .take(limit)
        .map { s ->
            val playerName = Bukkit.getOfflinePlayer(s.uuid).name ?: "Unknown"
            Pair(s, playerName)
        }

    public fun getStatsByName(name: String): Pair<PlayerKillStats, String>? {
        val player = Bukkit.getOfflinePlayer(name)
        if (!player.hasPlayedBefore() && !player.isOnline) {
            return null
        }

        val playerStats = getStats(player.uniqueId)
        return Pair(playerStats, player.name ?: name)
    }

    private fun migrateLegacyFile() {
        if (dataFile.exists()) {
            return
        }

        val legacy = File(Config.pathPlugin, "killtracker.yml")
        if (!legacy.exists()) {
            return
        }

        try {
            dataFile.parentFile?.mkdirs()
            legacy.copyTo(dataFile, overwrite = true)
            legacy.delete()
            Nodes.logger?.info("[KillTracker] Migrated legacy killtracker.yml to ${dataFile.path}")
        } catch (e: Exception) {
            Nodes.logger?.warning("[KillTracker] Failed to migrate legacy killtracker.yml: ${e.message}")
        }
    }
}
