package phonon.nodes.miningtower

import MiningTowerManager.enabled
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import java.text.SimpleDateFormat
import java.util.Date

/**
 * GUI for staff to view pending mining tower submissions.
 *
 * Key fixes vs original:
 *  - InventoryCloseEvent cleans up openGuis to prevent memory leaks.
 *  - Fixed encoding corruption (Â§ → \u00a7) in all hardcoded strings.
 */
object MiningTowerPendingGUI : Listener {

    private val openGuis = mutableMapOf<Player, List<MiningTowerSubmission>>()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm")

    fun open(player: Player, submissions: List<MiningTowerSubmission>) {
        val inv = Bukkit.createInventory(null, 54, MiningTowerConfig.pendingGuiTitle)

        submissions.take(45).forEachIndexed { index, submission ->
            inv.setItem(index, createSubmissionItem(submission))
        }

        val infoItem = createItem(
            Material.BOOK,
            "\u00a76\u00a7lStaff Instructions",
            listOf(
                "\u00a77Click a submission to open",
                "\u00a77the rating menu.",
                "\u00a77",
                "\u00a77You can:",
                "\u00a7e\u2022 Teleport to the location",
                "\u00a7e\u2022 Rate with a tier",
                "\u00a7e\u2022 Reject the submission",
            ),
        )
        inv.setItem(53, infoItem)

        openGuis[player] = submissions
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (!enabled) return

        val player = event.player as? Player ?: return
        if (event.view.title == MiningTowerConfig.pendingGuiTitle) {
            openGuis.remove(player)
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!enabled) return

        val player = event.whoClicked as? Player ?: return
        if (event.view.title != MiningTowerConfig.pendingGuiTitle) return
        if (!openGuis.containsKey(player)) return

        event.isCancelled = true

        val clicked = event.currentItem ?: return
        if (clicked.type == Material.AIR || clicked.type == Material.BOOK) return

        val submissions = openGuis[player] ?: return
        val slot = event.slot

        if (slot < submissions.size) {
            val submission = submissions[slot]
            player.closeInventory() // triggers InventoryCloseEvent → cleans map
            MiningTowerRatingGUI.open(player, submission)
        }
    }

    private fun createSubmissionItem(submission: MiningTowerSubmission): ItemStack {
        val item = ItemStack(Material.STONE_PICKAXE)
        val meta = item.itemMeta
        meta.setDisplayName("\u00a7e${submission.townName}")
        meta.lore = listOf(
            "\u00a77Submitted by: \u00a7f${submission.submitterName}",
            "\u00a77Date: \u00a7f${dateFormat.format(Date(submission.submittedAt))}",
            "\u00a77Location: \u00a7f${submission.location.blockX}, ${submission.location.blockY}, ${submission.location.blockZ}",
            "\u00a77World: \u00a7f${submission.location.world?.name ?: "unknown"}",
            "\u00a77",
            "\u00a7eClick to rate this submission",
        )
        item.itemMeta = meta
        return item
    }

    private fun createItem(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(name)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }
}
