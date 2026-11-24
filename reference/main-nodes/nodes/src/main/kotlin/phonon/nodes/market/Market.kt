package phonon.nodes.market

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.*

/**
 * Represents a single market listing.
 *
 * [itemStack] is a SINGLE unit of what's being sold.
 * [quantity]  is how many of that unit are available.
 * So a listing of "5x diamond for 10 gold, qty 3" means the buyer
 * pays 10 gold and receives 5 diamonds, and this can happen 3 times.
 */
data class MarketListing(
    val id: UUID = UUID.randomUUID(),
    val sellerUuid: UUID,
    val sellerName: String,
    val itemStack: ItemStack, // one unit (already cloned on creation)
    val price: Double, // cost per unit in [currency]
    val currency: Material,
    val quantity: Int, // how many units remain
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id.toString(),
        "sellerUuid" to sellerUuid.toString(),
        "sellerName" to sellerName,
        "itemStack" to itemStack.serialize(),
        "price" to price,
        "currency" to currency.name,
        "quantity" to quantity,
        "timestamp" to timestamp,
    )

    companion object {
        fun fromMap(map: Map<String, Any>): MarketListing? = runCatching {
            val itemStack = when {
                map["itemStack"] is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    ItemStack.deserialize(map["itemStack"] as Map<String, Any>)
                }
                // Legacy format support
                map.containsKey("itemType") -> {
                    val type = Material.valueOf(map["itemType"] as String)
                    val amount = (map["itemAmount"] as Number).toInt()
                    ItemStack(type, amount)
                }
                else -> return null
            }

            if (itemStack.type == Material.AIR) return null

            MarketListing(
                id = UUID.fromString(map["id"] as String),
                sellerUuid = UUID.fromString(map["sellerUuid"] as String),
                sellerName = map["sellerName"] as String,
                itemStack = itemStack,
                price = (map["price"] as Number).toDouble(),
                currency = Material.valueOf(map["currency"] as String),
                quantity = (map["quantity"] as Number).toInt(),
                timestamp = (map["timestamp"] as Number).toLong(),
            )
        }.getOrElse { e ->
            println("[Market] Failed to deserialize listing: ${e.message}")
            null
        }
    }
}

/**
 * A sanction placed by [sanctionedBy]'s town on [targetTownName].
 * Either blocks trading entirely or applies a price multiplier.
 */
data class TownSanction(
    val targetTownName: String,
    val isBlocked: Boolean = false,
    val priceMultiplier: Double = 1.0, // only relevant when !isBlocked
    val sanctionedBy: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toMap(): Map<String, Any> = mapOf(
        "targetTownName" to targetTownName,
        "isBlocked" to isBlocked,
        "priceMultiplier" to priceMultiplier,
        "sanctionedBy" to sanctionedBy,
        "timestamp" to timestamp,
    )

    companion object {
        fun fromMap(map: Map<String, Any>): TownSanction? = runCatching {
            TownSanction(
                targetTownName = map["targetTownName"] as String,
                isBlocked = map["isBlocked"] as Boolean,
                priceMultiplier = (map["priceMultiplier"] as Number).toDouble(),
                sanctionedBy = map["sanctionedBy"] as String,
                timestamp = (map["timestamp"] as Number).toLong(),
            )
        }.getOrNull()
    }
}

/**
 * Tax settings for a town's market.
 * [taxRate] is a fraction (0.0â€“1.0). Tax is added on top of the base price
 * and flows into the town's income pool.
 */
data class TownTax(
    val townName: String,
    val taxRate: Double = 0.0,
    val setBy: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toMap(): Map<String, Any> = mapOf(
        "townName" to townName,
        "taxRate" to taxRate,
        "setBy" to setBy,
        "timestamp" to timestamp,
    )

    companion object {
        fun fromMap(map: Map<String, Any>): TownTax? = runCatching {
            TownTax(
                townName = map["townName"] as String,
                taxRate = (map["taxRate"] as Number).toDouble(),
                setBy = map["setBy"] as String,
                timestamp = (map["timestamp"] as Number).toLong(),
            )
        }.getOrNull()
    }
}

/** Result of a purchase attempt. */
enum class PurchaseResult {
    SUCCESS,
    LISTING_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    INVENTORY_FULL,
    SANCTIONED,
    ALREADY_IN_PROGRESS, // concurrent purchase guard
}
