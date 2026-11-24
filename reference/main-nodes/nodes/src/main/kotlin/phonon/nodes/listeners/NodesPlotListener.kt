package phonon.nodes.listeners

import org.bukkit.ChatColor
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerInteractEvent
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.PROTECTED_BLOCKS
import phonon.nodes.plot.PlotManager
import phonon.nodes.plot.PlotPermissionType

public class NodesPlotListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSelect(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }

        val player = event.player
        if (!PlotManager.isSelectionMode(player.uniqueId)) {
            return
        }

        val clicked = event.clickedBlock ?: return

        if (event.action == Action.LEFT_CLICK_BLOCK) {
            PlotManager.setSelectionPos1(player.uniqueId, clicked.location)
            Message.print(player, "${ChatColor.AQUA}[Plot] Position 1 set to ${clicked.x}, ${clicked.y}, ${clicked.z}")
        } else if (event.action == Action.RIGHT_CLICK_BLOCK) {
            PlotManager.setSelectionPos2(player.uniqueId, clicked.location)
            Message.print(player, "${ChatColor.AQUA}[Plot] Position 2 set to ${clicked.x}, ${clicked.y}, ${clicked.z}")
        }

        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (player.isOp) return

        val resident = Nodes.getResident(player) ?: return
        val block = event.block
        if (!PlotManager.hasPermission(resident, block, PlotPermissionType.BUILD)) {
            event.isCancelled = true
            Message.error(player, "[Plot] You cannot build in this plot")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (player.isOp) return

        val resident = Nodes.getResident(player) ?: return
        val block = event.block
        if (!PlotManager.hasPermission(resident, block, PlotPermissionType.DESTROY)) {
            event.isCancelled = true
            Message.error(player, "[Plot] You cannot destroy in this plot")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucket(event: PlayerBucketEmptyEvent) {
        val player = event.player
        if (player.isOp) return

        val resident = Nodes.getResident(player) ?: return
        val block = event.blockClicked.getRelative(event.blockFace)
        if (!PlotManager.hasPermission(resident, block, PlotPermissionType.ITEMS)) {
            event.isCancelled = true
            Message.error(player, "[Plot] You cannot use items in this plot")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val player = event.player
        if (player.isOp) return

        val resident = Nodes.getResident(player) ?: return

        val type = if (PROTECTED_BLOCKS.contains(block.type)) {
            PlotPermissionType.CONTAINERS
        } else {
            PlotPermissionType.INTERACT
        }

        if (!PlotManager.hasPermission(resident, block, type)) {
            event.isCancelled = true
            event.setUseInteractedBlock(Event.Result.DENY)
            Message.error(player, "[Plot] You cannot interact in this plot")
        }
    }
}
