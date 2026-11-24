package phonon.nodes.towncontrol

import org.bukkit.configuration.file.FileConfiguration

/**
 * Manages overrides for Nodes' built-in town command toggles.
 * Writes directly to phonon.nodes.Config at runtime so TownCommand
 * picks up the changes without any bytecode patching.
 */
object TownControlConfig {

    var leaveEnabled: Boolean = true
        private set

    var spawnEnabled: Boolean = true
        private set

    var capitalEnabled: Boolean = true
        private set

    var disbandEnabled: Boolean = true
        private set

    private const val KEY_LEAVE = "town-control.leave-enabled"
    private const val KEY_SPAWN = "town-control.spawn-enabled"
    private const val KEY_CAPITAL = "town-control.capital-enabled"
    private const val KEY_DISBAND = "town-control.disband-enabled"

    fun setDefaults(config: FileConfiguration) {
        config.addDefault(KEY_LEAVE, true)
        config.addDefault(KEY_SPAWN, true)
        config.addDefault(KEY_CAPITAL, false)
        config.addDefault(KEY_DISBAND, false)
    }

    fun load(config: FileConfiguration) {
        leaveEnabled = config.getBoolean(KEY_LEAVE, true)
        spawnEnabled = config.getBoolean(KEY_SPAWN, true)
        capitalEnabled = config.getBoolean(KEY_CAPITAL, true)
        capitalEnabled = config.getBoolean(KEY_DISBAND, true)
    }

    fun setLeaveEnabled(enabled: Boolean, config: FileConfiguration) {
        leaveEnabled = enabled
        config.set(KEY_LEAVE, enabled)
    }

    fun setSpawnEnabled(enabled: Boolean, config: FileConfiguration) {
        spawnEnabled = enabled
        config.set(KEY_SPAWN, enabled)
    }
    fun setCapitalEnabled(enabled: Boolean, config: FileConfiguration) {
        capitalEnabled = enabled
        config.set(KEY_CAPITAL, enabled)
    }
    fun setDisbandEnabled(enabled: Boolean, config: FileConfiguration) {
        disbandEnabled = enabled
        config.set(KEY_DISBAND, enabled)
    }
}
