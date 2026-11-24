package phonon.nodes.contracts

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * A single deliverable contract available to a town.
 *
 * @param id          Unique identifier (used as map key)
 * @param displayName Human-readable name shown in GUI
 * @param description Flavour line shown in item lore
 * @param icon        Material used as the GUI item icon
 * @param required    Items the player must have in their inventory to complete
 * @param rewards     Items added to the town's income on completion
 * @param tier        1 = easy, 2 = medium, 3 = hard – drives GUI border colour
 */
data class Contract(
    val id: String,
    val displayName: String,
    val description: String,
    val icon: Material,
    val required: List<ItemStack>,
    val rewards: List<ItemStack>,
    val tier: Int = 1,
)

/**
 * Every possible contract in the global pool.
 * A random subset of these is offered to each town each cycle.
 */
object ContractPool {

    val all: List<Contract> = listOf(

        // ── Tier 1 ────────────────────────────────────────────────
        Contract(
            id = "wheat_32",
            displayName = "Grain Delivery",
            description = "Deliver a bushel of wheat to the town stores.",
            icon = Material.WHEAT,
            required = listOf(ItemStack(Material.WHEAT, 32)),
            rewards = listOf(ItemStack(Material.DIAMOND, 3)),
            tier = 1,
        ),
        Contract(
            id = "carrot_32",
            displayName = "Root Harvest",
            description = "Supply the town kitchen with fresh carrots.",
            icon = Material.CARROT,
            required = listOf(ItemStack(Material.CARROT, 32)),
            rewards = listOf(ItemStack(Material.GOLD_INGOT, 3)),
            tier = 1,
        ),
        Contract(
            id = "potato_32",
            displayName = "Potato Surplus",
            description = "Bring a crate of potatoes to town storage.",
            icon = Material.POTATO,
            required = listOf(ItemStack(Material.POTATO, 32)),
            rewards = listOf(ItemStack(Material.IRON_INGOT, 3)),
            tier = 1,
        ),
        Contract(
            id = "log_32",
            displayName = "Timber Run",
            description = "Chop and deliver logs for the town workshop.",
            icon = Material.OAK_LOG,
            required = listOf(ItemStack(Material.OAK_LOG, 32)),
            rewards = listOf(ItemStack(Material.IRON_INGOT, 3)),
            tier = 1,
        ),
        Contract(
            id = "stone_64",
            displayName = "Mason's Order",
            description = "Bring cobblestone for ongoing construction.",
            icon = Material.COBBLESTONE,
            required = listOf(ItemStack(Material.COBBLESTONE, 64)),
            rewards = listOf(ItemStack(Material.IRON_INGOT, 3)),
            tier = 1,
        ),
        Contract(
            id = "wool_32",
            displayName = "Wool Collection",
            description = "The town needs wool for beds and banners.",
            icon = Material.WHITE_WOOL,
            required = listOf(ItemStack(Material.WHITE_WOOL, 32)),
            rewards = listOf(ItemStack(Material.GOLD_INGOT, 3)),
            tier = 1,
        ),

        // ── Tier 2 ────────────────────────────────────────────────
        Contract(
            id = "iron_ingot_32",
            displayName = "Iron Stockpile",
            description = "Smelt and deliver iron ingots to the armory.",
            icon = Material.IRON_INGOT,
            required = listOf(ItemStack(Material.IRON_INGOT, 32)),
            rewards = listOf(
                ItemStack(Material.DIAMOND, 3),
                ItemStack(Material.GOLD_INGOT, 3),
            ),
            tier = 2,
        ),
        Contract(
            id = "sugar_cane_64",
            displayName = "Sugar Trade",
            description = "A merchant wants bulk sugar cane for potions.",
            icon = Material.SUGAR_CANE,
            required = listOf(ItemStack(Material.SUGAR_CANE, 64)),
            rewards = listOf(
                ItemStack(Material.DIAMOND, 3),
                ItemStack(Material.IRON_INGOT, 3),
            ),
            tier = 2,
        ),
        Contract(
            id = "gunpowder_16",
            displayName = "Powder Requisition",
            description = "Dangerous work — gather gunpowder for the war chest.",
            icon = Material.GUNPOWDER,
            required = listOf(ItemStack(Material.GUNPOWDER, 16)),
            rewards = listOf(
                ItemStack(Material.DIAMOND, 3),
                ItemStack(Material.IRON_INGOT, 3),
            ),
            tier = 2,
        ),
        Contract(
            id = "leather_32",
            displayName = "Tannery Quota",
            description = "The leather worker needs hides for armour.",
            icon = Material.LEATHER,
            required = listOf(ItemStack(Material.LEATHER, 32)),
            rewards = listOf(
                ItemStack(Material.GOLD_INGOT, 3),
                ItemStack(Material.IRON_INGOT, 3),
            ),
            tier = 2,
        ),

        // ── Tier 3 ────────────────────────────────────────────────
        Contract(
            id = "gold_ingot_16",
            displayName = "Treasury Deposit",
            description = "Contribute gold ingots to bolster the town treasury.",
            icon = Material.GOLD_INGOT,
            required = listOf(ItemStack(Material.GOLD_INGOT, 16)),
            rewards = listOf(
                ItemStack(Material.DIAMOND, 3),
                ItemStack(Material.DIAMOND, 3),
            ),
            tier = 3,
        ),
        Contract(
            id = "ender_pearl_8",
            displayName = "Exotic Procurement",
            description = "Source ender pearls from the depths of the world.",
            icon = Material.ENDER_PEARL,
            required = listOf(ItemStack(Material.ENDER_PEARL, 8)),
            rewards = listOf(
                ItemStack(Material.DIAMOND, 3),
                ItemStack(Material.GOLD_INGOT, 3),
                ItemStack(Material.IRON_INGOT, 3),
            ),
            tier = 3,
        ),
        Contract(
            id = "blaze_rod_8",
            displayName = "Nether Expedition",
            description = "Brave the Nether and return with blaze rods.",
            icon = Material.BLAZE_ROD,
            required = listOf(ItemStack(Material.BLAZE_ROD, 8)),
            rewards = listOf(
                ItemStack(Material.DIAMOND, 3),
                ItemStack(Material.DIAMOND, 3),
                ItemStack(Material.GOLD_INGOT, 3),
            ),
            tier = 3,
        ),
    )
}
