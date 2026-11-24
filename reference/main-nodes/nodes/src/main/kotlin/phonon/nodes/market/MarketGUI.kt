package phonon.nodes.market

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import phonon.nodes.Nodes
import phonon.nodes.market.MarketManager.enabled
import java.util.*

/**
 * Market GUI — browse only. My Listings is handled via /nemarket listings.
 *
 * SLOT LAYOUT (54-slot inventory)
 * ================================
 * Slots 0–44 : listings
 * Slot  45   : Close
 * Slot  46   : filler
 * Slot  47   : Previous Page
 * Slot  48   : filler
 * Slot  49   : Next Page
 * Slots 50–52: filler
 * Slot  53   : Add Listing (own town only)
 */
object MarketGUI : Listener {

    private data class GUISession(val townName: String, val page: Int)
    private val sessions = mutableMapOf<UUID, GUISession>()

    private const val ITEMS_PER_PAGE = 45
    private const val INV_SIZE = 54

    private const val SLOT_CLOSE = 45
    private const val SLOT_PREV = 47
    private const val SLOT_NEXT = 49
    private const val SLOT_ADD = 53

    // -------------------------------------------------------------------------
    // OPEN
    // -------------------------------------------------------------------------

    fun openBrowse(player: Player, townName: String, page: Int = 0) {
        val listings = MarketManager.getListings(townName)
        val maxPage = if (listings.isEmpty()) 0 else (listings.size - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, maxPage)

        val inv = Bukkit.createInventory(null, INV_SIZE, "§6§l$townName Market — Page ${safePage + 1}")
        val filler = makeFiller()
        val start = safePage * ITEMS_PER_PAGE
        val end = minOf(start + ITEMS_PER_PAGE, listings.size)
        val buyerTown = Nodes.getTownFromPlayer(player)

        for (i in start until end) {
            inv.setItem(i - start, buildListingDisplay(listings[i], buyerTown?.name, townName))
        }
        for (slot in (end - start) until ITEMS_PER_PAGE) {
            inv.setItem(slot, filler)
        }

        inv.setItem(SLOT_CLOSE, makeButton(Material.BARRIER, "§cClose"))
        inv.setItem(SLOT_PREV, if (safePage > 0) makeButton(Material.ARROW, "§ePrevious Page") else filler)
        inv.setItem(SLOT_NEXT, if (safePage < maxPage) makeButton(Material.ARROW, "§eNext Page") else filler)
        for (slot in listOf(46, 48, 50, 51, 52)) inv.setItem(slot, filler)

        val ownTown = buyerTown != null && buyerTown.name.equals(townName, ignoreCase = true)
        inv.setItem(
            SLOT_ADD,
            if (ownTown) {
                makeButton(
                    Material.EMERALD,
                    "§a§lAdd Listing",
                    "§7Hold item then:",
                    "§e/nemarket add <price> <currency> [qty]",
                )
            } else {
                filler
            },
        )

        sessions[player.uniqueId] = GUISession(townName, safePage)
        player.openInventory(inv)
    }

    // -------------------------------------------------------------------------
    // EVENTS
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!enabled) return

        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId] ?: return

        event.isCancelled = true

        when (event.action) {
            InventoryAction.HOTBAR_SWAP,
            InventoryAction.HOTBAR_MOVE_AND_READD,
            InventoryAction.COLLECT_TO_CURSOR,
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
            -> return
            else -> Unit
        }

        val clicked = event.clickedInventory ?: return
        if (clicked !== event.view.topInventory) return

        val slot = event.rawSlot
        if (slot < 0 || slot >= INV_SIZE) return

        handleBrowseClick(player, session, slot)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (!enabled) return

        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId in sessions) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.inventory === player.openInventory.topInventory) {
            sessions.remove(player.uniqueId)
        }
    }

    // -------------------------------------------------------------------------
    // CLICK HANDLER
    // -------------------------------------------------------------------------

    private fun handleBrowseClick(player: Player, session: GUISession, slot: Int) {
        val townName = session.townName
        val page = session.page

        when (slot) {
            SLOT_CLOSE -> player.closeInventory()
            SLOT_PREV -> if (page > 0) openBrowse(player, townName, page - 1)
            SLOT_NEXT -> openBrowse(player, townName, page + 1)
            SLOT_ADD -> {
                player.closeInventory()
                phonon.nodes.Message.raw(player, "§aHold the item you want to sell and type:")
                phonon.nodes.Message.raw(player, "§e/nemarket add <price> <currency> [quantity]")
            }
            in 0 until ITEMS_PER_PAGE -> {
                val listings = MarketManager.getListings(townName)
                val listingIndex = page * ITEMS_PER_PAGE + slot
                if (listingIndex !in listings.indices) return

                val listing = listings[listingIndex]

                if (listing.sellerUuid == player.uniqueId) {
                    phonon.nodes.Message.raw(player, "§cYou can't buy your own listing.")
                    phonon.nodes.Message.raw(player, "§7Use §e/nemarket listings§7 to cancel it.")
                    return
                }

                when (MarketManager.purchaseListing(player, townName, listing.id)) {
                    PurchaseResult.SUCCESS -> {
                        phonon.nodes.Message.raw(player, "§aBought §f${listing.itemStack.amount}× ${formatMaterial(listing.itemStack.type)}§a!")
                        openBrowse(player, townName, page)
                    }
                    PurchaseResult.LISTING_NOT_FOUND -> {
                        phonon.nodes.Message.raw(player, "§cThat listing no longer exists.")
                        openBrowse(player, townName, page)
                    }
                    PurchaseResult.INSUFFICIENT_FUNDS ->
                        phonon.nodes.Message.raw(player, "§cNot enough §f${formatMaterial(listing.currency)}§c!")
                    PurchaseResult.INVENTORY_FULL ->
                        phonon.nodes.Message.raw(player, "§cYour inventory is full!")
                    PurchaseResult.SANCTIONED ->
                        phonon.nodes.Message.raw(player, "§cYour town is blocked from trading here!")
                    PurchaseResult.ALREADY_IN_PROGRESS ->
                        phonon.nodes.Message.raw(player, "§cSomeone else is buying this — try again.")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // DISPLAY HELPERS
    // -------------------------------------------------------------------------

    private fun buildListingDisplay(
        listing: MarketListing,
        buyerTownName: String?,
        marketTownName: String,
    ): ItemStack {
        val display = listing.itemStack.clone()
        val meta = display.itemMeta
            ?: Bukkit.getItemFactory().getItemMeta(display.type)
            ?: return display

        var afterSanction = listing.price
        var sanctionNote: String? = null

        if (buyerTownName != null) {
            val sanction = MarketManager.getSanction(marketTownName, buyerTownName)
            if (sanction != null && !sanction.isBlocked && sanction.priceMultiplier != 1.0) {
                afterSanction *= sanction.priceMultiplier
                sanctionNote = "§c§lSanction: ${(sanction.priceMultiplier * 100).toInt()}% price"
            }
        }

        val taxRate = MarketManager.getTownTax(marketTownName)?.taxRate ?: 0.0
        val finalPrice = (afterSanction * (1.0 + taxRate)).toInt().coerceAtLeast(1)
        val basePrice = listing.price.toInt()

        meta.lore = buildList {
            add("§7Seller:     §f${listing.sellerName}")
            add("§7Base price: §f$basePrice× §e${formatMaterial(listing.currency)}")
            add("§7Quantity:   §f${listing.quantity}")
            add("")
            sanctionNote?.let { add(it) }
            if (taxRate > 0.0) add("§6Town tax: ${(taxRate * 100).toInt()}%")
            if (finalPrice != basePrice) {
                add("§7You pay:   §f$finalPrice× §e${formatMaterial(listing.currency)}")
            }
            add("")
            add("§aLeft-click to purchase!")
        }

        display.itemMeta = meta
        return display
    }

    private fun makeButton(material: Material, name: String, vararg loreLines: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta!!
        meta.setDisplayName(name)
        if (loreLines.isNotEmpty()) meta.lore = loreLines.toList()
        item.itemMeta = meta
        return item
    }

    private fun makeFiller(): ItemStack {
        val item = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta = item.itemMeta!!
        meta.setDisplayName(" ")
        item.itemMeta = meta
        return item
    }

    private fun formatMaterial(material: Material): String = material.name.lowercase().replace('_', ' ')
        .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    private fun timeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "$days day${if (days != 1L) "s" else ""} ago"
            hours > 0 -> "$hours hour${if (hours != 1L) "s" else ""} ago"
            minutes > 0 -> "$minutes minute${if (minutes != 1L) "s" else ""} ago"
            else -> "Just now"
        }
    }
}
