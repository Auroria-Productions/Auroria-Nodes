package phonon.nodes.miningtower

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack

/**
 * GUI for submitting a mining tower.
 *
 * Key fixes vs original:
 *  - InventoryCloseEvent cleans up openGuis to prevent memory leaks.
 *  - Fixed encoding corruption (Â§ → \u00a7) in all hardcoded strings.
 */
object MiningTowerSubmissionGUI : Listener {

    private val openGuis = mutableMapOf<Player, String>() // player → townName

    fun open(player: Player, townName: String) {
        val inv = Bukkit.createInventory(null, 27, MiningTowerConfig.submissionGuiTitle)

        val infoItem = createItem(
            Material.PAPER,
            "\u00a76\u00a7lSubmission Information",
            listOf(
                "\u00a77",
                "\u00a77Your current location will be saved",
                "\u00a77as the mining tower coordinates.",
                "\u00a77",
                "\u00a77Staff will teleport here to rate",
                "\u00a77your mining tower.",
                "\u00a77",
                "\u00a7eClick the confirm button to submit!",
            ),
        )
        inv.setItem(4, infoItem)

        val loc = player.location
        val locationItem = createItem(
            Material.COMPASS,
            "\u00a7e\u00a7lYour Location",
            listOf(
                "\u00a77World: \u00a7f${loc.world?.name ?: "unknown"}",
                "\u00a77X: \u00a7f${loc.blockX}",
                "\u00a77Y: \u00a7f${loc.blockY}",
                "\u00a77Z: \u00a7f${loc.blockZ}",
                "\u00a77",
                "\u00a77This is where staff will teleport.",
            ),
        )
        inv.setItem(13, locationItem)

        // Show first 5 tier previews
        val tiers = MiningTowerConfig.getOrderedTiers()
        tiers.take(5).forEachIndexed { index, tier ->
            inv.setItem(19 + index, createItem(Material.DIAMOND_PICKAXE, tier.displayName, tier.description))
        }

        val confirmItem = createItem(
            Material.LIME_CONCRETE,
            "\u00a7a\u00a7lCONFIRM SUBMISSION",
            listOf(
                "\u00a77Click to submit your mining tower",
                "\u00a77for staff review.",
            ),
        )
        inv.setItem(11, confirmItem)

        val cancelItem = createItem(
            Material.RED_CONCRETE,
            "\u00a7c\u00a7lCANCEL",
            listOf("\u00a77Click to cancel submission."),
        )
        inv.setItem(15, cancelItem)

        openGuis[player] = townName
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (event.view.title == MiningTowerConfig.submissionGuiTitle) {
            openGuis.remove(player)
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title != MiningTowerConfig.submissionGuiTitle) return
        if (!openGuis.containsKey(player)) return

        event.isCancelled = true

        val clicked = event.currentItem ?: return
        val townName = openGuis[player] ?: return

        when (clicked.type) {
            Material.LIME_CONCRETE -> {
                player.closeInventory() // triggers InventoryCloseEvent → cleans map

                val submission = MiningTowerManager.submitTower(
                    townName = townName,
                    submitterUuid = player.uniqueId,
                    submitterName = player.name,
                    location = player.location,
                )

                phonon.nodes.Message.raw(player, MiningTowerConfig.submitSuccessMsg)
                phonon.nodes.Message.raw(player, "\u00a77Submission ID: \u00a7f${submission.id}")

                notifyStaff(player, submission)
            }

            Material.RED_CONCRETE -> {
                player.closeInventory()
                phonon.nodes.Message.raw(player, "\u00a77Submission cancelled.")
            }

            else -> { /* ignore */ }
        }
    }

    private fun notifyStaff(submitter: Player, submission: MiningTowerSubmission) {
        val message = "\u00a76[Mining Tower] \u00a7e${submitter.name} \u00a77submitted a mining tower for \u00a7e${submission.townName}"
        for (onlinePlayer in Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission(MiningTowerConfig.ratePermission)) {
                phonon.nodes.Message.raw(onlinePlayer, message)
                phonon.nodes.Message.raw(onlinePlayer, "\u00a77Use \u00a7f/miningtower pending \u00a77to review submissions")
            }
        }
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
