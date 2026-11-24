package phonon.nodes.listeners

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.chat.Chat
import phonon.nodes.nodesaddons.NodesMineBuff
import phonon.nodes.objects.Nametag
import phonon.nodes.war.FlagWar
import phonon.nodes.warzone.WarzoneBossBarManager

class NodesPlayerJoinQuitListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        val plugin = Nodes.plugin ?: return

        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                if (Nodes.war.enabled) {
                    FlagWar.sendWarProgressBarToPlayer(player)
                    FlagWar.sendTimedWarBossBarToPlayer(player)
                }
            },
            5L,
        )

        // Create resident if it doesn't exist yet
        Nodes.createResident(player)

        val resident = Nodes.getResident(player) ?: return

        // Rally cap check (only if enabled)
        if (Config.rallyCapEnabled) {
            val town = resident.town

            if (town != null) {
                val entityName: String
                val onlineCount: Int

                if (Config.rallyCapApplyToNations && town.nation != null) {
                    val nation = town.nation!!
                    entityName = nation.name
                    onlineCount = nation.playersOnline.size
                } else {
                    entityName = town.name
                    onlineCount = town.playersOnline.size
                }

                if (onlineCount >= Config.rallyCapSize) {
                    player.kick(
                        Component.text()
                            .color(NamedTextColor.RED)
                            .content("Rally cap reached! Maximum ${Config.rallyCapSize} players online for $entityName")
                            .build(),
                    )
                    return
                }
            }
        }

        // Mark player as online
        Nodes.setResidentOnline(resident, player)
        NodesMineBuff.sendJoinStatus(player)

        // War-related features
        if (Nodes.war.enabled) {
            Nodes.war.sendWarProgressBarToPlayer(player)
        }

        // Nametag lifecycle — must run AFTER setResidentOnline so the
        // player's town is reachable via Nodes.getTownFromPlayer().
        //
        // Nametag.onPlayerJoin() does three things:
        //   1. adds the player to the team-members snapshot
        //   2. marks the joiner dirty so their own view is (re)built
        //   3. sends team-add packets to existing viewers who already
        //      had the joiner's team cached (so their client's team
        //      colour applies to the joiner immediately)
        Nametag.onPlayerJoin(player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val resident = Nodes.getResident(player) ?: return

        WarzoneBossBarManager.removePlayer(player)
        resident.destroyMinimap()

        // IMPORTANT: clean up the nametag state BEFORE setResidentOffline
        // clears the resident's town pointer — snapshotRemovePlayer needs
        // to know which team the player was on.
        Nametag.onPlayerQuit(player)

        Nodes.setResidentOffline(resident, player)

        // Re-enable global chat if player was muting it
        Chat.enableGlobalChat(player)

        // Cancel any ongoing chunk attacks by this player
        if (Nodes.war.enabled) {
            val attacks = Nodes.war.attackers[player.uniqueId]?.toList() ?: listOf()
            attacks.forEach { attack ->
                attack.cancel()
            }
            Nodes.war.attackers.remove(player.uniqueId)
        }
    }
}
