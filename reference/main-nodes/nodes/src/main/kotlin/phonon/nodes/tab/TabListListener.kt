package phonon.nodes.tab

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import phonon.nodes.NodesPlugin

/**
 * Listens for player join/quit events and triggers TAB-list refreshes.
 *
 * Join behaviour is fully configurable via TabListConfig:
 *   refresh-on-join          → update the joining player's own team view + header/footer
 *   refresh-others-on-join   → push a fresh view to every other online player
 *   join-refresh-delay-ticks → how long to wait before sending packets
 */
class TabListListener : Listener {

    class TabListListener : Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        fun onJoin(event: PlayerJoinEvent) {
            if (!TabListConfig.enabled) return

            val joiner = event.player
            val plugin = NodesPlugin.plugin
            val delay = TabListConfig.joinRefreshDelayTicks.coerceAtLeast(1L)

            // invalidate cached weight in case groups changed while offline
            LuckPermsHook.invalidateWeight(joiner.uniqueId)

            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
                if (TabListConfig.refreshOnJoin && joiner.isOnline) {
                    Bukkit.getAsyncScheduler().runNow(plugin) { _ -> TabList.updateForPlayer(joiner) }
                    TabList.updateHeaderFooter(joiner)
                }
                if (TabListConfig.refreshOthersOnJoin) {
                    for (other in Bukkit.getOnlinePlayers()) {
                        if (other != joiner && other.isOnline) {
                            Bukkit.getAsyncScheduler().runNow(plugin) { _ -> TabList.updateForPlayer(other) }
                        }
                    }
                }
            }, delay)
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onQuit(event: PlayerQuitEvent) {
            if (!TabListConfig.enabled) return

            val leaver = event.player
            TabList.removePlayer(leaver.uniqueId)
            LuckPermsHook.invalidateWeight(leaver.uniqueId)

            Bukkit.getGlobalRegionScheduler().runDelayed(NodesPlugin.plugin, { _ ->
                for (other in Bukkit.getOnlinePlayers()) {
                    if (other.isOnline) {
                        Bukkit.getAsyncScheduler()
                            .runNow(NodesPlugin.plugin) { _ -> TabList.updateForPlayer(other) }
                    }
                }
            }, 1L)
        }
    }
}
