package phonon.nodes.listeners

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.Nodes.getTownFromPlayer
import phonon.nodes.miningtower.MiningTowerConfig
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Territory
import phonon.nodes.objects.Town
import phonon.nodes.warzone.WarzoneBossBarManager
import phonon.nodes.warzone.WarzoneManager
import java.util.UUID

public class NodesPlayerMoveListener : Listener {

    // -------------------------------------------------------------------------
    // Mining tower territory tracking
    // Keyed by UUID (not Player) to avoid stale references after logout.
    // -------------------------------------------------------------------------

    /** Last territory ID (raw Int) seen for each online player. */
    private val playerLastTerritoryId = mutableMapOf<UUID, Int?>()

    /** Active mining-tower bossbars, one per player at most. */
    private val activeMiningBossbars = mutableMapOf<UUID, BossBar>()

    // =========================================================================
    // Player join / quit — seed and clean up territory state
    // =========================================================================

    /**
     * Seed the initial territory on join so the first chunk-crossing comparison
     * is accurate and doesn't fire a spurious notification.
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val territory = Nodes.getTerritoryFromBlock(player.location.blockX, player.location.blockZ)
        playerLastTerritoryId[player.uniqueId] = territory?.id?.toInt()
    }

    /** Remove all per-player state and cancel any live bossbar on logout. */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        playerLastTerritoryId.remove(player.uniqueId)
        removeMiningBossbar(player)
    }

    // =========================================================================
    // Movement events
    // =========================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPlayerMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to ?: return
        if (!didChangeBlock(from.blockX, from.blockY, from.blockZ, to.blockX, to.blockY, to.blockZ)) {
            return
        }

        val player = event.player
        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        if (resident.teleportThread != null) {
            cancelPendingTeleport(player, resident)
        }

        if (didChangeChunk(from.blockX, from.blockZ, to.blockX, to.blockZ)) {
            onPlayerMoveChunk(
                player,
                resident,
                Coord.fromBlockCoords(from.blockX, from.blockZ),
                Coord.fromBlockCoords(to.blockX, to.blockZ),
            )
        }
    }

    // handle player teleport (e.g. /t spawn)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val from = event.from
        val to = event.to ?: return
        if (!didChangeBlock(from.blockX, from.blockY, from.blockZ, to.blockX, to.blockY, to.blockZ)) {
            return
        }

        val resident = Nodes.getResident(event.player)
        if (resident == null) {
            return
        }

        if (didChangeChunk(from.blockX, from.blockZ, to.blockX, to.blockZ)) {
            onPlayerMoveChunk(
                event.player,
                resident,
                Coord.fromBlockCoords(from.blockX, from.blockZ),
                Coord.fromBlockCoords(to.blockX, to.blockZ),
            )
        }
    }

    // handle player changing to new chunk
    public fun onPlayerMoveChunk(player: Player, resident: Resident, fromCoord: Coord, toCoord: Coord) {
        val fromTerritory = Nodes.getTerritoryFromCoord(fromCoord)
        val toTerritory = Nodes.getTerritoryFromCoord(toCoord)

        if (fromTerritory != null && toTerritory != null) {
            val toTown = toTerritory.town
            val fromTown = fromTerritory.town
            if (toTerritory.name != fromTerritory.name) {
                if (toTown != null) {
                    printTownMessage(player, resident, toTown, toTerritory)
                } else {
                    Message.announcement(player, "${ChatColor.GRAY}${toTerritory.name}")
                }
            } else if (fromTown !== null && toTown !== null) {
                if (toTown !== fromTown || fromTerritory.occupier !== toTerritory.occupier) {
                    printTownMessage(player, resident, toTown, toTerritory)
                }
            } else if (fromTown !== null && toTown === null) {
                if (toTerritory.name != fromTerritory.name) {
                    Message.announcement(player, "${ChatColor.GRAY}${toTerritory.name}")
                } else {
                    Message.announcement(player, "${ChatColor.GRAY}Wilderness")
                }
            } else if (toTown !== null) {
                printTownMessage(player, resident, toTown, toTerritory)
            }
        }

        val activeWarzone = toTerritory?.let { WarzoneManager.findActiveWarzoneByNode(it.id.toInt()) }
        WarzoneBossBarManager.updatePlayerWarzone(player, activeWarzone)

        // update minimap
        resident.updateMinimap(toCoord)

        // -----------------------------------------------------------------
        // Mining tower territory notification
        // toTerritory is already resolved above — no second lookup needed.
        // -----------------------------------------------------------------
        checkMiningTowerTerritory(player, toTerritory)

        // check if flight needs to be disabled (e.g. player moved to different town or wilderness)
        // ignore admins in creative and spectator
        val gameMode = player.gameMode
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR) return

        val playerTown = getTownFromPlayer(player)

        // if player enters an active warzone or leaves their own town while flying, disable flight
        if (player.allowFlight && activeWarzone != null) {
            player.allowFlight = false
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, false))
            Message.print(player, "Cannot fly in an active warzone, disabling flight")
        } else if (player.allowFlight && toTerritory?.town != playerTown) {
            player.allowFlight = false
            // give player slow falling to avoid fall damage
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, false))
            Message.print(player, "You are no longer in your town, disabling flight")
        }
    }

    // =========================================================================
    // Mining tower territory helpers
    // =========================================================================

    /**
     * Called once per chunk-crossing with the already-resolved [toTerritory].
     *
     * Compares the new territory ID against the last known one for this player.
     * Only does work when the territory has actually changed, and only when the
     * new territory belongs to a town that has an approved mining tower in that
     * exact territory.
     */
    private fun checkMiningTowerTerritory(player: Player, toTerritory: phonon.nodes.objects.Territory?) {
        val newId: Int? = toTerritory?.id?.toInt()
        val lastId = playerLastTerritoryId[player.uniqueId]

        // Skip if territory hasn't changed (handles null == null too)
        if (newId == lastId) return
        playerLastTerritoryId[player.uniqueId] = newId

        if (toTerritory == null) {
            // Entered wilderness — dismiss any active tower bossbar
            removeMiningBossbar(player)
            return
        }

        val town = toTerritory.town
        if (town == null) {
            removeMiningBossbar(player)
            return
        }

        val miningTower = MiningTowerManager.getApprovedTower(town.name)
        if (miningTower == null) {
            removeMiningBossbar(player)
            return
        }

        // Ensure the player is actually in the tower's territory, not just the same town
        val towerTerritoryIntId = miningTower.territoryId?.toInt()
        if (towerTerritoryIntId == null || toTerritory.id.toInt() != towerTerritoryIntId) {
            removeMiningBossbar(player)
            return
        }

        val tier = miningTower.ratedTier
        val tierConfig = if (tier != null) MiningTowerConfig.tiers[tier] else null
        if (tierConfig == null) {
            removeMiningBossbar(player)
            return
        }

        val variables = mapOf(
            "{town}" to town.name,
            "{tier}" to tierConfig.displayName,
            "{multiplier}" to tierConfig.miningRateMultiplier.toString(),
        )

        if (MiningTowerConfig.showTerritoryEntryMessage) {
            val title = replaceVariables(MiningTowerConfig.territoryEntryMessage, variables)
            val subtitle = replaceVariables(MiningTowerConfig.territoryEntrySubtitle, variables)
            Message.title(player, title, subtitle, 10, 40, 10)
        }

        if (MiningTowerConfig.showTerritoryEntryBossbar) {
            showMiningBossbar(player, variables)
        }
    }

    private fun showMiningBossbar(player: Player, variables: Map<String, String>) {
        removeMiningBossbar(player) // cancel any existing bossbar first

        val text = replaceVariables(MiningTowerConfig.territoryEntryBossbarText, variables)

        val color = try {
            BarColor.valueOf(MiningTowerConfig.territoryEntryBossbarColor.uppercase())
        } catch (_: IllegalArgumentException) {
            BarColor.YELLOW
        }

        val style = try {
            BarStyle.valueOf(MiningTowerConfig.territoryEntryBossbarStyle.uppercase())
        } catch (_: IllegalArgumentException) {
            BarStyle.SOLID
        }

        val bossbar = Bukkit.createBossBar(text, color, style)
        bossbar.addPlayer(player)
        bossbar.progress = 1.0
        activeMiningBossbars[player.uniqueId] = bossbar

        val durationTicks = MiningTowerConfig.territoryEntryBossbarDuration.toLong() * 20L
        Bukkit.getScheduler().runTaskLater(
            phonon.nodes.NodesExtensions.instance,
            Runnable {
                if (player.isOnline) removeMiningBossbar(player)
            },
            durationTicks,
        )
    }

    private fun removeMiningBossbar(player: Player) {
        val bossbar = activeMiningBossbars.remove(player.uniqueId) ?: return
        bossbar.removePlayer(player)
        bossbar.removeAll()
    }

    private fun replaceVariables(text: String, variables: Map<String, String>): String {
        var result = text
        for ((key, value) in variables) result = result.replace(key, value)
        return result
    }
}

private fun didChangeBlock(fromX: Int, fromY: Int, fromZ: Int, toX: Int, toY: Int, toZ: Int): Boolean = fromX != toX || fromY != toY || fromZ != toZ

private fun didChangeChunk(fromX: Int, fromZ: Int, toX: Int, toZ: Int): Boolean = (fromX shr 4) != (toX shr 4) || (fromZ shr 4) != (toZ shr 4)

private fun cancelPendingTeleport(player: Player, resident: Resident) {
    resident.teleportThread?.cancel()
    resident.teleportThread = null
    Message.error(player, "You moved, teleport cancelled")

    if (resident.isTeleportingToOutpost) {
        Message.error(player, "Refunding outpost teleport items")
        refundTeleportItems(player, Config.outpostTeleportCost)
        resident.isTeleportingToOutpost = false
    }

    if (resident.isTeleportingToNationTown) {
        Message.error(player, "Refunding teleport items")
        refundTeleportItems(player, Config.nationTownTeleportCost)
        resident.isTeleportingToNationTown = false
    }
}

private fun refundTeleportItems(player: Player, costs: Map<org.bukkit.Material, Int>) {
    val world = player.world
    val location = player.location
    val inventory = player.inventory
    for ((material, amount) in costs) {
        val items = ItemStack(material, amount)
        val leftover = inventory.addItem(items)
        for (remaining in leftover.values) {
            world.dropItem(location, remaining)
        }
    }
}

/**
 * Inputs:
 * player: player who will see message
 * resident: resident from player
 * toTown: territory town the player has entered
 * toTerritory: territory player has entered
 */
private fun printTownMessage(player: Player, resident: Resident, toTown: Town, toTerritory: Territory) {
    val residentTown = resident.town
    val territoryOccupier = toTerritory.occupier

    // territory name token
    val territoryName = if (toTerritory.name != "") {
        "${toTerritory.name} (${toTown.name})"
    } else {
        "${toTown.name}"
    }

    // territory name color and territory occupation/captured modifier
    var territoryNameColor = ""
    var ownerStatus = ""

    if (residentTown !== null) {
        if (toTown === residentTown) {
            territoryNameColor = "${ChatColor.DARK_GREEN}"
        } else if (residentTown.enemies.contains(toTown)) {
            territoryNameColor = "${ChatColor.DARK_RED}"
        } else {
            territoryNameColor = "${ChatColor.DARK_AQUA}"
        }

        // set occupation status
        if (territoryOccupier !== null) {
            if (territoryOccupier === residentTown) {
                ownerStatus = " ${ChatColor.DARK_GREEN}(Captured)"
            } else if (toTown === residentTown) {
                ownerStatus = " ${ChatColor.DARK_RED}(Occupied)"
            } else {
                ownerStatus = " ${ChatColor.DARK_AQUA}(Occupied)"
            }
        }
    } else {
        territoryNameColor = "${ChatColor.DARK_AQUA}"
        if (territoryOccupier !== null) {
            ownerStatus = " (Occupied)"
        }
    }

    Message.announcement(player, "${territoryNameColor}${territoryName}$ownerStatus")
}
