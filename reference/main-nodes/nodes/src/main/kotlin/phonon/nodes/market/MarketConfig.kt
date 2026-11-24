package phonon.nodes.market

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration

/**
 * Static configuration for the market system.
 * Values are loaded from config.yml under the "market" key.
 */
object MarketConfig {
    var allowedCurrencies: List<Material> = listOf(
        Material.DIAMOND,
        Material.GOLD_INGOT,
        Material.IRON_INGOT,
        Material.EMERALD,
        Material.NETHERITE_INGOT,
        Material.COPPER_INGOT,
    )

    var maxListingsPerPlayer: Int = 20
    var listingDurationDays: Int = 7
    var defaultTaxRate: Double = 0.05
    var sanctionPriceMultiplierMax: Double = 5.0
    var sanctionPriceMultiplierMin: Double = 0.1

    fun load(config: FileConfiguration) {
        val currencyStrings = config.getStringList("market.allowed-currencies")
        if (currencyStrings.isNotEmpty()) {
            allowedCurrencies = currencyStrings.mapNotNull {
                runCatching { Material.valueOf(it.uppercase()) }.getOrNull()
            }
        }
        maxListingsPerPlayer = config.getInt("market.max-listings-per-player", 20)
        listingDurationDays = config.getInt("market.listing-duration-days", 7)
        defaultTaxRate = config.getDouble("market.default-tax-rate", 0.05)
        sanctionPriceMultiplierMax = config.getDouble("market.sanction-price-multiplier-max", 5.0)
        sanctionPriceMultiplierMin = config.getDouble("market.sanction-price-multiplier-min", 0.1)
    }

    fun setDefaults(config: FileConfiguration) {
        config.addDefault(
            "market.allowed-currencies",
            listOf(
                "DIAMOND",
                "GOLD_INGOT",
                "IRON_INGOT",
                "EMERALD",
                "NETHERITE_INGOT",
                "COPPER_INGOT",
            ),
        )
        config.addDefault("market.max-listings-per-player", 20)
        config.addDefault("market.listing-duration-days", 7)
        config.addDefault("market.default-tax-rate", 0.05)
        config.addDefault("market.sanction-price-multiplier-max", 5.0)
        config.addDefault("market.sanction-price-multiplier-min", 0.1)
    }
}
