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

/**
 * GUI for staff to rate a specific mining tower submission.
 *
 * Key fixes vs original:
 *  - Tiers are now looked up by slot (slotToTier map) instead of display name,
 *    which was fragile and would silently fail on colour-code mismatches.
 *  - InventoryCloseEvent cleans up openGuis to prevent memory leaks.
 *  - Icon material reads optional TierConfig.iconMaterial before falling back
 *    to the hardcoded tier1–tier5 ladder, so config-defined tier IDs work.
 */
object MiningTowerRatingGUI : Listener {

    private val openGuis = mutableMapOf<Player, MiningTowerSubmission>()

    // Per-player slot → TierConfig map built when the GUI is opened
    private val slotToTier = mutableMapOf<Player, Map<Int, TierConfig>>()

    private val tierSlots = listOf(19, 20, 21, 22, 23, 24, 25, 28, 29) // up to 9 tiers

    fun open(player: Player, submission: MiningTowerSubmission) {
        val inv = Bukkit.createInventory(null, 54, MiningTowerConfig.ratingGuiTitle)

        val infoItem = createItem(
            Material.PAPER,
            "\u00a76\u00a7lSubmission Details",
            listOf(
                "\u00a77Town: \u00a7f${submission.townName}",
                "\u00a77Submitted by: \u00a7f${submission.submitterName}",
                "\u00a77",
                "\u00a77Location:",
                "\u00a7f  ${submission.location.blockX}, ${submission.location.blockY}, ${submission.location.blockZ}",
                "\u00a7f  ${submission.location.world?.name ?: "unknown"}",
                "\u00a77",
                "\u00a7eUse the buttons below to rate!",
            ),
        )
        inv.setItem(4, infoItem)

        val teleportItem = createItem(
            Material.ENDER_PEARL,
            "\u00a7b\u00a7lTELEPORT",
            listOf(
                "\u00a77Click to teleport to the",
                "\u00a77mining tower location.",
            ),
        )
        inv.setItem(13, teleportItem)

        // Build slot→tier mapping for this player
        val tierMap = mutableMapOf<Int, TierConfig>()
        val tiers = MiningTowerConfig.getOrderedTiers()
        tiers.forEachIndexed { index, tier ->
            if (index < tierSlots.size) {
                val slot = tierSlots[index]
                tierMap[slot] = tier
                inv.setItem(slot, createTierItem(tier))
            }
        }
        slotToTier[player] = tierMap

        val rejectItem = createItem(
            Material.BARRIER,
            "\u00a7c\u00a7lREJECT SUBMISSION",
            listOf(
                "\u00a77Click to reject this submission.",
                "\u00a77The submitter will need to wait",
                "\u00a77before submitting again.",
            ),
        )
        inv.setItem(49, rejectItem)

        val backItem = createItem(
            Material.ARROW,
            "\u00a77\u00a7lBACK",
            listOf("\u00a77Return to pending submissions"),
        )
        inv.setItem(45, backItem)

        openGuis[player] = submission
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (!enabled) return

        val player = event.player as? Player ?: return
        if (event.view.title == MiningTowerConfig.ratingGuiTitle) {
            openGuis.remove(player)
            slotToTier.remove(player)
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!enabled) return

        val player = event.whoClicked as? Player ?: return
        if (event.view.title != MiningTowerConfig.ratingGuiTitle) return
        if (!openGuis.containsKey(player)) return

        event.isCancelled = true

        val clicked = event.currentItem ?: return
        if (clicked.type == Material.AIR) return

        val submission = openGuis[player] ?: return
        val slot = event.slot

        when (clicked.type) {
            Material.ENDER_PEARL -> {
                player.closeInventory()
                phonon.nodes.Message.raw(player, MiningTowerConfig.teleportSuccessMsg)

                // Re-open GUI after teleport delay
                Bukkit.getScheduler().runTaskLater(
                    phonon.nodes.NodesExtensions.instance,
                    Runnable {
                        if (player.isOnline) open(player, submission)
                    },
                    10L,
                )
            }

            Material.BARRIER -> {
                player.closeInventory() // triggers InventoryCloseEvent → cleans maps
                MiningTowerManager.rejectSubmission(submission.id, player.uniqueId)
                phonon.nodes.Message.raw(player, MiningTowerConfig.rejectedSuccessMsg)
                notifySubmitter(submission, null, rejected = true)
            }

            Material.ARROW -> {
                player.closeInventory()
                val pending = MiningTowerManager.getPendingSubmissions()
                if (pending.isNotEmpty()) {
                    MiningTowerPendingGUI.open(player, pending)
                } else {
                    phonon.nodes.Message.raw(player, MiningTowerConfig.noPendingMsg)
                }
            }

            else -> {
                // Look up tier by slot — no fragile display-name matching
                val tier = slotToTier[player]?.get(slot) ?: return

                player.closeInventory()
                MiningTowerManager.approveSubmission(submission.id, tier.id, player.uniqueId)
                phonon.nodes.Message.raw(
                    player,
                    MiningTowerConfig.ratedSuccessMsg.replace("{tier}", tier.displayName),
                )
                notifySubmitter(submission, tier, rejected = false)
            }
        }
    }

    private fun createTierItem(tier: TierConfig): ItemStack {
        // Prefer per-tier icon from config, then fall back to positional defaults
        val material = tier.iconMaterial?.let {
            runCatching { Material.valueOf(it.uppercase()) }.getOrNull()
        } ?: when (tier.id) {
            "tier1" -> Material.WOODEN_PICKAXE
            "tier2" -> Material.STONE_PICKAXE
            "tier3" -> Material.IRON_PICKAXE
            "tier4" -> Material.GOLDEN_PICKAXE
            "tier5" -> Material.DIAMOND_PICKAXE
            else -> Material.NETHERITE_PICKAXE
        }

        val lore = tier.description.toMutableList()
        lore.add("\u00a77")
        lore.add("\u00a7aIncome Bonus: \u00a7f+${(tier.incomeBonus * 100).toInt()}%")
        lore.add("\u00a76Mining Rate: \u00a7f${tier.miningRateMultiplier}x")
        lore.add("\u00a77")
        lore.add("\u00a7eClick to rate as ${tier.displayName}")

        return createItem(material, tier.displayName, lore)
    }

    private fun notifySubmitter(submission: MiningTowerSubmission, tier: TierConfig?, rejected: Boolean) {
        val submitter = Bukkit.getPlayer(submission.submitterUuid) ?: return
        if (rejected) {
            phonon.nodes.Message.raw(submitter, "\u00a7c\u00a7l[Mining Tower] \u00a7cYour mining tower submission was rejected!")
            phonon.nodes.Message.raw(submitter, "\u00a77You can submit a new one after the cooldown period.")
        } else {
            phonon.nodes.Message.raw(submitter, "\u00a7a\u00a7l[Mining Tower] \u00a7aYour mining tower was approved!")
            phonon.nodes.Message.raw(submitter, "\u00a77Rated as: ${tier?.displayName}")
            phonon.nodes.Message.raw(submitter, "\u00a77Income bonus: \u00a7a+${tier?.let { (it.incomeBonus * 100).toInt() }}%")
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
