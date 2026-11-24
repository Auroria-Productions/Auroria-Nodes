package phonon.nodes.market

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.NodesExtensions
import phonon.nodes.overview.MarketOverviewService
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages all market operations: listings, sanctions, taxes, GDP.
 *
 * THREAD SAFETY
 * =============
 * All mutation goes through [purchaseListing] which holds a per-listing lock
 * via [purchaseLocks] to prevent concurrent-purchase duplication.
 * Everything else is backed by ConcurrentHashMap for safe iteration.
 */
object MarketManager {
    private val plugin get() = NodesExtensions.instance

    // townName -> listings (MutableList access always on main thread or under listing lock)
    private val marketListings = ConcurrentHashMap<String, MutableList<MarketListing>>()

    // townName -> (targetTownName -> sanction)
    private val townSanctions = ConcurrentHashMap<String, MutableMap<String, TownSanction>>()

    // townName -> accumulated trade value (for GDP leaderboard)
    private val townGDP = ConcurrentHashMap<String, Double>()

    // townName -> tax
    private val townTaxes = ConcurrentHashMap<String, TownTax>()

    // Listing IDs currently being purchased (prevents duplicate concurrent purchases)
    private val purchaseLocks = ConcurrentHashMap.newKeySet<UUID>()

    // Offline seller payment mailbox: sellerUuid -> list of pending ItemStacks
    private val mailbox = ConcurrentHashMap<UUID, MutableList<ItemStack>>()

    private val dataFile get() = Config.pathMarketData.toFile()

    var enabled: Boolean = true

    // -------------------------------------------------------------------------
    // LOAD / SAVE
    // -------------------------------------------------------------------------

    fun load() {
        // Reset in-memory state before loading from disk (important for reloads).
        marketListings.clear()
        townSanctions.clear()
        townGDP.clear()
        townTaxes.clear()
        mailbox.clear()

        migrateLegacyDataFile()

        if (!dataFile.exists()) {
            plugin.logger.info("[Market] No data file found, starting fresh.")
            return
        }

        val config = YamlConfiguration.loadConfiguration(dataFile)

        // Listings
        config.getConfigurationSection("listings")?.getKeys(false)?.forEach { townName ->
            val listings = mutableListOf<MarketListing>()
            config.getConfigurationSection("listings")!!.getMapList(townName).forEach { raw ->
                @Suppress("UNCHECKED_CAST")
                MarketListing.fromMap(raw as Map<String, Any>)?.let { listings += it }
            }
            if (listings.isNotEmpty()) marketListings[townName] = listings
        }

        // GDP
        config.getMapList("town-gdp").forEach { raw ->
            @Suppress("UNCHECKED_CAST")
            val m = raw as Map<String, Any>
            val town = m["town"] as? String ?: return@forEach
            townGDP[town] = (m["gdp"] as? Number)?.toDouble() ?: 0.0
        }

        // Sanctions
        config.getConfigurationSection("sanctions")?.getKeys(false)?.forEach { townName ->
            val sanctions = mutableMapOf<String, TownSanction>()
            config.getConfigurationSection("sanctions")!!.getMapList(townName).forEach { raw ->
                @Suppress("UNCHECKED_CAST")
                TownSanction.fromMap(raw as Map<String, Any>)?.let { sanctions[it.targetTownName] = it }
            }
            if (sanctions.isNotEmpty()) townSanctions[townName] = sanctions
        }

        // Taxes
        config.getMapList("town-taxes").forEach { raw ->
            @Suppress("UNCHECKED_CAST")
            TownTax.fromMap(raw as Map<String, Any>)?.let { townTaxes[it.townName] = it }
        }

        // Mailbox
        config.getConfigurationSection("mailbox")?.getKeys(false)?.forEach { uuidStr ->
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: return@forEach
            val items = mutableListOf<ItemStack>()
            config.getConfigurationSection("mailbox")!!.getMapList(uuidStr).forEach { raw ->
                @Suppress("UNCHECKED_CAST")
                runCatching { ItemStack.deserialize(raw as Map<String, Any>) }.getOrNull()?.let { items += it }
            }
            if (items.isNotEmpty()) mailbox[uuid] = items
        }

        cleanExpiredListings()

        val total = marketListings.values.sumOf { it.size }
        plugin.logger.info("[Market] Loaded $total listings, ${townSanctions.size} sanctioned towns, ${townTaxes.size} town taxes.")
    }

    fun save() {
        dataFile.parentFile?.mkdirs()
        val config = YamlConfiguration()

        val listSection = config.createSection("listings")
        marketListings.forEach { (town, list) ->
            listSection.set(town, list.map { it.toMap() })
        }

        config.set("town-gdp", townGDP.map { mapOf("town" to it.key, "gdp" to it.value) })

        val sanctionSection = config.createSection("sanctions")
        townSanctions.forEach { (town, map) ->
            sanctionSection.set(town, map.values.map { it.toMap() })
        }

        config.set("town-taxes", townTaxes.values.map { it.toMap() })

        val mailboxSection = config.createSection("mailbox")
        mailbox.forEach { (uuid, items) ->
            mailboxSection.set(uuid.toString(), items.map { it.serialize() })
        }

        config.save(dataFile)
        plugin.logger.info("[Market] Data saved.")
    }

    private fun migrateLegacyDataFile() {
        if (dataFile.exists()) {
            return
        }

        val legacyFile = File(plugin.dataFolder, "market_data.yml")
        if (!legacyFile.exists()) {
            return
        }

        dataFile.parentFile?.mkdirs()
        runCatching {
            legacyFile.copyTo(dataFile, overwrite = true)
            legacyFile.delete()
            plugin.logger.info("[Market] Migrated legacy market_data.yml to market/market_data.yml")
        }.onFailure { err ->
            plugin.logger.warning("[Market] Failed to migrate legacy market_data.yml: ${err.message}")
        }
    }

    // -------------------------------------------------------------------------
    // LISTINGS
    // -------------------------------------------------------------------------

    /** Returns a snapshot of all listings for [townName]. */
    fun getListings(townName: String): List<MarketListing> = marketListings[townName]?.toList() ?: emptyList()

    /** Returns a snapshot of all listings grouped by town name. */
    fun getAllListingsSnapshot(): Map<String, List<MarketListing>> = marketListings.entries.associate { (town, listings) -> town to listings.toList() }

    /** Returns all listings owned by [playerUuid] in [townName]. */
    fun getPlayerListings(townName: String, playerUuid: UUID): List<MarketListing> = marketListings[townName]?.filter { it.sellerUuid == playerUuid } ?: emptyList()

    /**
     * Add a listing to [townName]'s market.
     * The [itemStack] must already be cloned by the caller.
     * Returns false if the player has hit the listing cap.
     */
    fun addListing(
        townName: String,
        seller: Player,
        itemStack: ItemStack, // caller must clone!
        price: Double,
        currency: Material,
        quantity: Int,
    ): Boolean {
        if (getPlayerListings(townName, seller.uniqueId).size >= MarketConfig.maxListingsPerPlayer) {
            return false
        }
        val listing = MarketListing(
            sellerUuid = seller.uniqueId,
            sellerName = seller.name ?: seller.uniqueId.toString(),
            itemStack = itemStack,
            price = price,
            currency = currency,
            quantity = quantity,
        )
        marketListings.computeIfAbsent(townName) { mutableListOf() } += listing
        return true
    }

    /**
     * Remove a listing and return it (or null if not found).
     * Used for the seller cancelling their own listing.
     */
    fun removeListing(townName: String, listingId: UUID): MarketListing? {
        val listings = marketListings[townName] ?: return null
        val listing = listings.find { it.id == listingId } ?: return null
        listings -= listing
        return listing
    }

    // -------------------------------------------------------------------------
    // PURCHASE  (the critical path — must be atomic and dupe-proof)
    // -------------------------------------------------------------------------

    /**
     * Attempt to purchase one unit of [listingId] in [townName] for [buyer].
     *
     * Steps (all validated before any mutation):
     *  1. Listing must still exist.
     *  2. Buyer's town must not be blocked.
     *  3. Buyer must have enough currency (base × sanction × (1 + tax)).
     *  4. Buyer must have room for the item.
     *  5. Remove currency from buyer atomically.
     *  6. Give item to buyer.
     *  7. Decrement listing quantity (or remove if last).
     *  8. Pay seller (online-only, offline payment is out-of-scope without mailbox).
     *  9. Route tax to town income.
     * 10. Record GDP.
     */
    fun purchaseListing(buyer: Player, townName: String, listingId: UUID): PurchaseResult {
        // ── 0. Concurrent-purchase guard ────────────────────────────────────
        if (!purchaseLocks.add(listingId)) {
            return PurchaseResult.ALREADY_IN_PROGRESS
        }

        try {
            return doPurchase(buyer, townName, listingId)
        } finally {
            purchaseLocks.remove(listingId)
        }
    }

    private fun doPurchase(buyer: Player, townName: String, listingId: UUID): PurchaseResult {
        // ── 1. Find listing ─────────────────────────────────────────────────
        val listings = marketListings[townName] ?: return PurchaseResult.LISTING_NOT_FOUND
        val listing = listings.find { it.id == listingId } ?: return PurchaseResult.LISTING_NOT_FOUND

        // ── 2. Sanction check ───────────────────────────────────────────────
        val buyerTown = Nodes.getTownFromPlayer(buyer)
        val marketTown = Nodes.getTownFromName(townName)

        if (buyerTown != null && isTownBlocked(townName, buyerTown.name)) {
            return PurchaseResult.SANCTIONED
        }

        // ── 3. Price calculation ─────────────────────────────────────────────
        //  basePrice   = listing.price
        //  afterSanction = basePrice * sanctionMultiplier   (≥ 1.0 or < 1.0)
        //  tax         = afterSanction * taxRate
        //  finalPrice  = afterSanction + tax
        //  sellerGets  = afterSanction   (tax is skimmed off the top)

        var afterSanction = listing.price

        buyerTown?.let { bTown ->
            val sanction = townSanctions[townName]?.get(bTown.name)
            if (sanction != null && !sanction.isBlocked) {
                afterSanction *= sanction.priceMultiplier
            }
        }

        val taxRate = townTaxes[townName]?.taxRate ?: 0.0
        val taxAmount = afterSanction * taxRate
        val finalPrice = afterSanction + taxAmount // what buyer pays
        val sellerGets = afterSanction // seller receives (tax already excluded)

        val currencyNeeded = finalPrice.toInt().coerceAtLeast(1)

        // ── 4. Buyer funds check ─────────────────────────────────────────────
        val buyerCurrencyCount = buyer.inventory.contents
            .filterNotNull()
            .filter { it.type == listing.currency }
            .sumOf { it.amount }

        if (buyerCurrencyCount < currencyNeeded) {
            return PurchaseResult.INSUFFICIENT_FUNDS
        }

        // ── 5. Buyer inventory space check ──────────────────────────────────
        val itemToGive = listing.itemStack.clone()
        if (!hasRoomFor(buyer, itemToGive)) {
            return PurchaseResult.INVENTORY_FULL
        }

        // ── 6. Remove currency from buyer ────────────────────────────────────
        //  Build an ItemStack of the exact currency amount and use removeItem,
        //  which returns what it couldn't remove.  If anything remains, abort.
        val currencyStack = ItemStack(listing.currency, currencyNeeded)
        val notRemoved = buyer.inventory.removeItem(currencyStack)
        if (notRemoved.isNotEmpty()) {
            // Shouldn't happen after the check above, but be safe
            return PurchaseResult.INSUFFICIENT_FUNDS
        }

        // ── 7. Give item to buyer ────────────────────────────────────────────
        val leftover = buyer.inventory.addItem(itemToGive)
        if (leftover.isNotEmpty()) {
            // Inventory became full between check and give — refund currency and abort
            buyer.inventory.addItem(currencyStack)
            return PurchaseResult.INVENTORY_FULL
        }

        // ── 8. Decrement / remove listing ────────────────────────────────────
        if (listing.quantity <= 1) {
            listings -= listing
        } else {
            val idx = listings.indexOf(listing)
            if (idx != -1) listings[idx] = listing.copy(quantity = listing.quantity - 1)
        }

        // ── 9. Pay seller ────────────────────────────────────────────────────────
        val sellerPaymentAmount = sellerGets.toInt().coerceAtLeast(0)
        if (sellerPaymentAmount > 0) {
            val payment = ItemStack(listing.currency, sellerPaymentAmount)
            val seller = plugin.server.getPlayer(listing.sellerUuid)
            if (seller != null && seller.isOnline) {
                // Online — give directly, drop overflow at feet
                val unpaid = seller.inventory.addItem(payment)
                if (unpaid.isNotEmpty()) {
                    unpaid.values.forEach { seller.world.dropItemNaturally(seller.location, it) }
                }
            } else {
                // Offline — queue into mailbox for later redemption
                mailbox.computeIfAbsent(listing.sellerUuid) { mutableListOf() } += payment
            }
        }

        // ── 10. Tax to town income ───────────────────────────────────────────
        val taxItems = taxAmount.toInt()
        if (taxItems > 0 && marketTown != null) {
            runCatching {
                marketTown.income.add(listing.currency, taxItems)
            }.onFailure { e ->
                plugin.logger.warning("[Market] Failed to add tax to town income: ${e.message}")
            }
        }

        // ── 11. GDP accounting ───────────────────────────────────────────────
        // GDP = total value of goods sold at base price (economic output, not buyer cost)
        townGDP.merge(townName, afterSanction) { old, new -> old + new }

        // Feed market web analytics history.
        MarketOverviewService.recordSale(
            timestamp = System.currentTimeMillis(),
            townName = townName,
            itemType = listing.itemStack.type,
            currency = listing.currency,
            itemAmount = listing.itemStack.amount,
            listingUnitsSold = 1,
            sellerRevenue = sellerGets,
            buyerPaid = finalPrice,
        )

        return PurchaseResult.SUCCESS
    }

    // -------------------------------------------------------------------------
    // MAILBOX
    // -------------------------------------------------------------------------

    /** How many pending payment stacks [playerUuid] has waiting. */
    fun getMailboxSize(playerUuid: UUID): Int = mailbox[playerUuid]?.size ?: 0

    /**
     * Give all queued payments to [player] and clear their mailbox.
     * Returns the number of stacks redeemed (0 if empty).
     * Any items that don't fit are dropped at their feet.
     */
    fun redeemMailbox(player: Player): Int {
        val pending = mailbox.remove(player.uniqueId) ?: return 0
        var redeemed = 0
        for (item in pending) {
            val leftover = player.inventory.addItem(item.clone())
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
                phonon.nodes.Message.raw(player, "§eInventory full — some payments dropped at your feet.")
            }
            redeemed++
        }
        return redeemed
    }

    // -------------------------------------------------------------------------
    // SANCTIONS
    // -------------------------------------------------------------------------

    fun setSanction(
        byTownName: String,
        targetTownName: String,
        isBlocked: Boolean,
        priceMultiplier: Double,
        sanctionedBy: String,
    ) {
        val sanction = TownSanction(targetTownName, isBlocked, priceMultiplier, sanctionedBy)
        townSanctions.computeIfAbsent(byTownName) { mutableMapOf() }[targetTownName] = sanction
    }

    fun removeSanction(byTownName: String, targetTownName: String) {
        townSanctions[byTownName]?.remove(targetTownName)
    }

    fun isTownBlocked(marketTownName: String, buyerTownName: String): Boolean = townSanctions[marketTownName]?.get(buyerTownName)?.isBlocked ?: false

    fun getSanction(marketTownName: String, targetTownName: String): TownSanction? = townSanctions[marketTownName]?.get(targetTownName)

    fun getTownSanctions(townName: String): Map<String, TownSanction> = townSanctions[townName]?.toMap() ?: emptyMap()

    // -------------------------------------------------------------------------
    // TAXES
    // -------------------------------------------------------------------------

    fun setTownTax(townName: String, taxRate: Double, setBy: String) {
        townTaxes[townName] = TownTax(townName, taxRate, setBy)
    }

    fun removeTownTax(townName: String) {
        townTaxes.remove(townName)
    }

    fun getTownTax(townName: String): TownTax? = townTaxes[townName]

    // -------------------------------------------------------------------------
    // GDP
    // -------------------------------------------------------------------------

    fun getTownGDP(townName: String): Double = townGDP[townName] ?: 0.0

    fun getTopGDP(limit: Int = 10): List<Pair<String, Double>> = townGDP.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key to it.value }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    /**
     * True if [player]'s inventory can accept [item] without losing it.
     * Checks for an empty slot OR a partially-filled matching stack.
     */
    private fun hasRoomFor(player: Player, item: ItemStack): Boolean {
        val inv = player.inventory
        if (inv.firstEmpty() != -1) return true
        return inv.contents.filterNotNull().any { slot ->
            slot.isSimilar(item) && slot.amount + item.amount <= slot.maxStackSize
        }
    }

    /** Remove listings older than the configured duration. */
    private fun cleanExpiredListings() {
        val cutoff = System.currentTimeMillis() - MarketConfig.listingDurationDays * 86_400_000L
        var removed = 0
        marketListings.values.forEach { list ->
            val iter = list.iterator()
            while (iter.hasNext()) {
                if (iter.next().timestamp < cutoff) {
                    iter.remove()
                    removed++
                }
            }
        }
        if (removed > 0) plugin.logger.info("[Market] Removed $removed expired listings.")
    }
}
