package phonon.nodes.towncontrol

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent

class TownCommandBlocker : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val message = event.message.removePrefix("/")
        val args = message.split(" ")

        if (args.isEmpty()) return

        val base = args[0].lowercase()

        // Handle both /town and /t
        if (base != "town" && base != "t") return

        if (args.size < 2) return

        val sub = args[1].lowercase()

        when (sub) {
            "leave" -> {
                if (!TownControlConfig.leaveEnabled) {
                    event.isCancelled = true
                    phonon.nodes.Message.raw(event.player, "§c/town leave is currently disabled.")
                }
            }

            "capital" -> {
                if (!TownControlConfig.capitalEnabled) {
                    event.isCancelled = true
                    phonon.nodes.Message.raw(event.player, "§c/town capital is currently disabled.")
                }
            }

            "disband" -> {
                if (!TownControlConfig.leaveEnabled) {
                    event.isCancelled = true
                    phonon.nodes.Message.raw(event.player, "§c/town disband is currently disabled.")
                }
            }

            "spawn" -> {
                if (!TownControlConfig.spawnEnabled) {
                    event.isCancelled = true
                    phonon.nodes.Message.raw(event.player, "§c/town spawn is currently disabled.")
                }
            }
        }
    }
}
