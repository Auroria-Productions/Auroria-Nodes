package phonon.nodes.commands

import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Nodes
import phonon.nodes.market.MarketConfig
import phonon.nodes.market.MarketGUI
import phonon.nodes.market.MarketManager
import phonon.nodes.objects.Coord

/**
 * /nemarket — player-facing market command.
 *
 * Subcommands:
 *   (none / browse)                          → open market GUI for current territory
 *   add <price> <currency> [qty]             → list held item (must be in own town)
 *   info                                     → show stats for own town market
 *   tax <set|remove|info> [rate]             → manage town tax (leader/officer only)
 *   sanction <town> <block|price> [mult]     → sanction another town (leader/officer only)
 *   unsanction <town>                        → remove sanctions (leader/officer only)
 *   sanctions                                → list your active sanctions
 *   gdp                                      → leaderboard
 *   help                                     → usage
 */
class MarketCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            phonon.nodes.Message.raw(sender, "§cOnly players can use this command.")
            return true
        }
        val player = sender

        // Default / browse: open market for the territory the player stands in
        if (args.isEmpty() || args[0].equals("browse", ignoreCase = true)) {
            val coord = coordOf(player)
            val territory = Nodes.getTerritoryFromCoord(coord)
            val town = territory?.town

            if (territory == null || town == null) {
                phonon.nodes.Message.raw(player, "§cYou must be inside a town's territory to open their market.")
                return true
            }

            MarketGUI.openBrowse(player, town.name)
            return true
        }

        when (args[0].lowercase()) {
            // -----------------------------------------------------------------
            "add", "sell" -> {
                if (args.size < 3) {
                    phonon.nodes.Message.raw(player, "§cUsage: /nemarket add <price> <currency> [quantity]")
                    return true
                }

                // Seller must be in their own town's territory
                val playerTown = Nodes.getTownFromPlayer(player) ?: run {
                    phonon.nodes.Message.raw(player, "§cYou must belong to a town to create listings.")
                    return true
                }

                val coord = coordOf(player)
                val locTown = Nodes.getTerritoryFromCoord(coord)?.town
                if (locTown == null || !locTown.name.equals(playerTown.name, ignoreCase = true)) {
                    phonon.nodes.Message.raw(player, "§cYou must be inside your own town's territory to add listings.")
                    phonon.nodes.Message.raw(player, "§7Currently in: ${locTown?.name ?: "wilderness"}")
                    return true
                }

                // Held item
                val heldItem = player.inventory.itemInMainHand
                if (heldItem.type == Material.AIR || heldItem.amount == 0) {
                    phonon.nodes.Message.raw(player, "§cHold the item you want to list in your main hand.")
                    return true
                }

                // Price
                val price = args[1].toDoubleOrNull()
                if (price == null || price <= 0) {
                    phonon.nodes.Message.raw(player, "§cPrice must be a positive number.")
                    return true
                }

                // Currency
                val currency = Material.getMaterial(args[2].uppercase())
                if (currency == null || currency !in MarketConfig.allowedCurrencies) {
                    phonon.nodes.Message.raw(player, "§cInvalid currency. Allowed: ${MarketConfig.allowedCurrencies.joinToString { it.name.lowercase() }}")
                    return true
                }

                // Quantity (how many units to list)
                val quantity = if (args.size > 3) args[3].toIntOrNull() ?: 1 else 1
                if (quantity <= 0) {
                    phonon.nodes.Message.raw(player, "§cQuantity must be a positive integer.")
                    return true
                }

                // One "unit" = the entire held stack.
                // Total items needed from inventory = heldItem.amount * quantity
                val unitSize = heldItem.amount
                val totalNeeded = unitSize * quantity

                // Check player has enough items
                val available = player.inventory.contents
                    .filterNotNull()
                    .filter { it.isSimilar(heldItem) }
                    .sumOf { it.amount }

                if (available < totalNeeded) {
                    phonon.nodes.Message.raw(player, "§cNot enough items. Need §f$totalNeeded§c, have §f$available§c.")
                    return true
                }

                // Clone item BEFORE removal (itemMeta etc. preserved)
                val listingItem = heldItem.clone()
                listingItem.amount = unitSize

                // Remove items using removeItem — returns anything it couldn't take
                val toRemove = ItemStack(heldItem.type, totalNeeded).also { it.itemMeta = heldItem.itemMeta?.clone() }
                val notRemoved = player.inventory.removeItem(toRemove)
                if (notRemoved.isNotEmpty()) {
                    // Partial removal shouldn't happen after the check, but handle it safely
                    notRemoved.values.forEach { player.inventory.addItem(it) }
                    phonon.nodes.Message.raw(player, "§cFailed to remove items from your inventory.")
                    return true
                }

                val success = MarketManager.addListing(
                    townName = playerTown.name,
                    seller = player,
                    itemStack = listingItem,
                    price = price,
                    currency = currency,
                    quantity = quantity,
                )

                if (success) {
                    phonon.nodes.Message.raw(player, "§aListing created!")
                    phonon.nodes.Message.raw(player, "§7Selling §f$unitSize× ${listingItem.type.name.lowercase()}§7 for §f${price.toInt()}× ${currency.name.lowercase()}§7 (×$quantity units)")
                } else {
                    // addListing rejected — return items. Drop overflow so nothing is lost silently.
                    val unfit = player.inventory.addItem(toRemove)
                    if (unfit.isNotEmpty()) {
                        unfit.values.forEach { player.world.dropItemNaturally(player.location, it) }
                        phonon.nodes.Message.raw(player, "§eInventory full — returned items dropped at your feet.")
                    }
                    phonon.nodes.Message.raw(player, "§cCouldn't create listing. You may have hit the cap of §f${MarketConfig.maxListingsPerPlayer}§c listings.")
                }
            }

            // -----------------------------------------------------------------
            "info" -> {
                val playerTown = Nodes.getTownFromPlayer(player) ?: run {
                    phonon.nodes.Message.raw(player, "§cYou are not in a town.")
                    return true
                }

                val listings = MarketManager.getListings(playerTown.name)
                val playerListings = MarketManager.getPlayerListings(playerTown.name, player.uniqueId)
                val tax = MarketManager.getTownTax(playerTown.name)

                phonon.nodes.Message.raw(player, "§6§l=== Market Info — ${playerTown.name} ===")
                phonon.nodes.Message.raw(player, "§7Total listings:   §f${listings.size}")
                phonon.nodes.Message.raw(player, "§7Your listings:    §f${playerListings.size}/${MarketConfig.maxListingsPerPlayer}")
                phonon.nodes.Message.raw(player, "§7Currencies:       §f${MarketConfig.allowedCurrencies.joinToString { it.name.lowercase() }}")
                if (tax != null && tax.taxRate > 0) {
                    phonon.nodes.Message.raw(player, "§7Market tax:       §f${(tax.taxRate * 100).toInt()}%")
                }

                val locTown = Nodes.getTerritoryFromCoord(coordOf(player))?.town
                phonon.nodes.Message.raw(player, "§7Current location: §f${locTown?.name ?: "wilderness"}")
            }

            // -----------------------------------------------------------------
            "tax" -> {
                if (args.size < 2) {
                    phonon.nodes.Message.raw(player, "§cUsage: /nemarket tax <set|remove|info> [rate]")
                    return true
                }

                val playerTown = Nodes.getTownFromPlayer(player) ?: run {
                    phonon.nodes.Message.raw(player, "§cYou are not in a town.")
                    return true
                }

                // Only leader/officers may edit tax
                val resident = Nodes.getResident(player)
                val isLeader = resident != null &&
                    (resident === playerTown.leader || playerTown.officers.contains(resident))

                when (args[1].lowercase()) {
                    "set" -> {
                        if (!isLeader) {
                            phonon.nodes.Message.raw(player, "§cOnly leaders and officers can set taxes.")
                            return true
                        }
                        if (args.size < 3) {
                            phonon.nodes.Message.raw(player, "§cUsage: /nemarket tax set <0.0–1.0>")
                            return true
                        }
                        val rate = args[2].toDoubleOrNull()
                        if (rate == null || rate !in 0.0..1.0) {
                            phonon.nodes.Message.raw(player, "§cTax rate must be between 0.0 and 1.0 (e.g. 0.10 = 10%).")
                            return true
                        }
                        MarketManager.setTownTax(playerTown.name, rate, player.name)
                        phonon.nodes.Message.raw(player, "§aMarket tax set to §f${(rate * 100).toInt()}%§a.")
                    }
                    "remove" -> {
                        if (!isLeader) {
                            phonon.nodes.Message.raw(player, "§cOnly leaders and officers can remove taxes.")
                            return true
                        }
                        MarketManager.removeTownTax(playerTown.name)
                        phonon.nodes.Message.raw(player, "§aMarket tax removed.")
                    }
                    "info" -> {
                        val tax = MarketManager.getTownTax(playerTown.name)
                        if (tax == null) {
                            phonon.nodes.Message.raw(player, "§7No tax set for §f${playerTown.name}§7.")
                        } else {
                            phonon.nodes.Message.raw(player, "§6Tax: §f${(tax.taxRate * 100).toInt()}% §7(set by ${tax.setBy})")
                        }
                    }
                    else -> phonon.nodes.Message.raw(player, "§cUnknown: use set, remove, or info.")
                }
            }

            // -----------------------------------------------------------------
            "gdp" -> {
                val top = MarketManager.getTopGDP(10)
                if (top.isEmpty()) {
                    phonon.nodes.Message.raw(player, "§7No market activity recorded yet.")
                    return true
                }
                phonon.nodes.Message.raw(player, "§6§l=== Top Towns by GDP ===")
                top.forEachIndexed { i, (town, gdp) ->
                    phonon.nodes.Message.raw(player, "§7${i + 1}. §e$town §7→ §f${gdp.toInt()}")
                }
            }

            // -----------------------------------------------------------------
            "sanction" -> {
                val (playerTown) = checkLeaderOrOfficer(player) ?: return true

                if (args.size < 3) {
                    phonon.nodes.Message.raw(player, "§cUsage: /nemarket sanction <town> <block|price> [multiplier]")
                    return true
                }

                val targetTown = Nodes.getTownFromName(args[1])
                if (targetTown == null) {
                    phonon.nodes.Message.raw(player, "§cTown not found: §f${args[1]}")
                    return true
                }
                if (targetTown.name.equals(playerTown.name, ignoreCase = true)) {
                    phonon.nodes.Message.raw(player, "§cYou cannot sanction your own town.")
                    return true
                }

                when (args[2].lowercase()) {
                    "block" -> {
                        MarketManager.setSanction(playerTown.name, targetTown.name, true, 1.0, player.name)
                        phonon.nodes.Message.raw(player, "§f${targetTown.name}§a is now blocked from your market.")
                    }
                    "price" -> {
                        if (args.size < 4) {
                            phonon.nodes.Message.raw(player, "§cUsage: /nemarket sanction <town> price <multiplier>")
                            return true
                        }
                        val mult = args[3].toDoubleOrNull()
                        if (mult == null ||
                            mult < MarketConfig.sanctionPriceMultiplierMin ||
                            mult > MarketConfig.sanctionPriceMultiplierMax
                        ) {
                            phonon.nodes.Message.raw(
                                player,
                                "§cMultiplier must be between " +
                                    "§f${MarketConfig.sanctionPriceMultiplierMin}§c and " +
                                    "§f${MarketConfig.sanctionPriceMultiplierMax}§c.",
                            )
                            return true
                        }
                        MarketManager.setSanction(playerTown.name, targetTown.name, false, mult, player.name)
                        phonon.nodes.Message.raw(player, "§f${targetTown.name}§a now pays §f$mult×§a prices in your market.")
                    }
                    else -> phonon.nodes.Message.raw(player, "§cUnknown sanction type. Use §eblock§c or §eprice§c.")
                }
            }

            // -----------------------------------------------------------------
            "unsanction" -> {
                val (playerTown) = checkLeaderOrOfficer(player) ?: return true

                if (args.size < 2) {
                    phonon.nodes.Message.raw(player, "§cUsage: /nemarket unsanction <town>")
                    return true
                }
                MarketManager.removeSanction(playerTown.name, args[1])
                phonon.nodes.Message.raw(player, "§aSanctions removed from §f${args[1]}§a.")
            }

            // -----------------------------------------------------------------
            "sanctions" -> {
                val playerTown = Nodes.getTownFromPlayer(player) ?: run {
                    phonon.nodes.Message.raw(player, "§cYou are not in a town.")
                    return true
                }
                val sanctions = MarketManager.getTownSanctions(playerTown.name)
                if (sanctions.isEmpty()) {
                    phonon.nodes.Message.raw(player, "§7No sanctions set for §f${playerTown.name}§7.")
                    return true
                }
                phonon.nodes.Message.raw(player, "§6§l=== Sanctions — ${playerTown.name} ===")
                sanctions.forEach { (town, s) ->
                    if (s.isBlocked) {
                        phonon.nodes.Message.raw(player, "§c$town: §4BLOCKED")
                    } else {
                        phonon.nodes.Message.raw(player, "§e$town: §f${s.priceMultiplier}× §7price")
                    }
                }
            }

            // -----------------------------------------------------------------
            "listings" -> {
                val playerTown = Nodes.getTownFromPlayer(player) ?: run {
                    phonon.nodes.Message.raw(player, "\u00a7cYou are not in a town.")
                    return true
                }
                val listings = MarketManager.getPlayerListings(playerTown.name, player.uniqueId)
                if (listings.isEmpty()) {
                    phonon.nodes.Message.raw(player, "\u00a77You have no active listings in \u00a7f${playerTown.name}\u00a77.")
                    return true
                }
                phonon.nodes.Message.raw(player, "\u00a76\u00a7l=== Your Listings in ${playerTown.name} ===")
                phonon.nodes.Message.raw(player, "\u00a77Total: \u00a7f${listings.size}\u00a77/\u00a7f${MarketConfig.maxListingsPerPlayer}")
                phonon.nodes.Message.raw(player, "")
                listings.forEachIndexed { index, listing ->
                    val item = listing.itemStack
                    phonon.nodes.Message.raw(
                        player,
                        "\u00a7e#${index + 1} \u00a7f${item.amount}\u00d7 ${item.type.name.lowercase()} " +
                            "\u00a77\u2014 \u00a7f${listing.price.toInt()}\u00d7 ${listing.currency.name.lowercase()} " +
                            "\u00a77(qty: \u00a7f${listing.quantity}\u00a77)",
                    )
                }
                phonon.nodes.Message.raw(player, "")
                phonon.nodes.Message.raw(player, "\u00a77To cancel: \u00a7e/nemarket cancel <#>")
            }

            // -----------------------------------------------------------------
            "cancel" -> {
                val playerTown = Nodes.getTownFromPlayer(player) ?: run {
                    phonon.nodes.Message.raw(player, "\u00a7cYou are not in a town.")
                    return true
                }
                if (args.size < 2) {
                    phonon.nodes.Message.raw(player, "\u00a7cUsage: /nemarket cancel <#>")
                    phonon.nodes.Message.raw(player, "\u00a77Use \u00a7e/nemarket listings\u00a77 to see your listing numbers.")
                    return true
                }
                val index = args[1].toIntOrNull()?.minus(1)
                if (index == null || index < 0) {
                    phonon.nodes.Message.raw(player, "\u00a7cPlease give a valid listing number.")
                    return true
                }
                val listings = MarketManager.getPlayerListings(playerTown.name, player.uniqueId)
                if (index >= listings.size) {
                    phonon.nodes.Message.raw(player, "\u00a7cNo listing #${index + 1}. You have ${listings.size} listing${if (listings.size != 1) "s" else ""}.")
                    return true
                }
                val listing = listings[index]
                val removed = MarketManager.removeListing(playerTown.name, listing.id) ?: run {
                    phonon.nodes.Message.raw(player, "\u00a7cListing not found — it may have already been sold or removed.")
                    return true
                }

                // Return all items, drop overflow
                var toReturn = removed.itemStack.amount * removed.quantity
                val maxStack = removed.itemStack.maxStackSize
                while (toReturn > 0) {
                    val batch = removed.itemStack.clone()
                    batch.amount = minOf(toReturn, maxStack)
                    val leftover = player.inventory.addItem(batch)
                    if (leftover.isNotEmpty()) {
                        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
                        phonon.nodes.Message.raw(player, "\u00a7eInventory full \u2014 some items dropped at your feet.")
                    }
                    toReturn -= batch.amount
                }

                phonon.nodes.Message.raw(player, "\u00a7aListing #${index + 1} cancelled \u2014 items returned!")
            }

            // -----------------------------------------------------------------
            "mailbox" -> {
                when (args.getOrNull(1)?.lowercase()) {
                    "redeem" -> {
                        val pending = MarketManager.getMailboxSize(player.uniqueId)
                        if (pending == 0) {
                            phonon.nodes.Message.raw(player, "§7Your mailbox is empty.")
                            return true
                        }
                        val redeemed = MarketManager.redeemMailbox(player)
                        phonon.nodes.Message.raw(player, "§aRedeemed §f$redeemed§a payment${if (redeemed != 1) "s" else ""} from your mailbox!")
                    }
                    null, "check" -> {
                        val pending = MarketManager.getMailboxSize(player.uniqueId)
                        if (pending == 0) {
                            phonon.nodes.Message.raw(player, "§7Your mailbox is empty.")
                        } else {
                            phonon.nodes.Message.raw(player, "§6You have §f$pending§6 pending payment${if (pending != 1) "s" else ""} in your mailbox.")
                            phonon.nodes.Message.raw(player, "§eUse §f/nemarket mailbox redeem§e to collect them.")
                        }
                    }
                    else -> phonon.nodes.Message.raw(player, "§cUsage: /nemarket mailbox [check|redeem]")
                }
            }

            // -----------------------------------------------------------------
            "help" -> showHelp(player)

            else -> phonon.nodes.Message.raw(player, "§cUnknown subcommand. Use §e/nemarket help§c.")
        }

        return true
    }

    /**
     * Checks that [player] belongs to a town and is a leader or officer.
     * Sends the appropriate error message and returns null on failure.
     */
    private fun checkLeaderOrOfficer(player: Player): Pair<phonon.nodes.objects.Town, Unit>? {
        val playerTown = Nodes.getTownFromPlayer(player) ?: run {
            phonon.nodes.Message.raw(player, "§cYou must be in a town to use this command.")
            return null
        }
        val resident = Nodes.getResident(player)
        val isAuthorised = resident != null &&
            (resident === playerTown.leader || playerTown.officers.contains(resident))
        if (!isAuthorised) {
            phonon.nodes.Message.raw(player, "§cOnly town leaders and officers can use this command.")
            return null
        }
        return playerTown to Unit
    }

    private fun showHelp(player: Player) {
        phonon.nodes.Message.raw(player, "§6§l=== Market Commands ===")
        phonon.nodes.Message.raw(player, "§e/nemarket §7— Browse the market in your current territory")
        phonon.nodes.Message.raw(player, "§e/nemarket add <price> <currency> [qty] §7— List held item")
        phonon.nodes.Message.raw(player, "§e/nemarket info §7— Your town's market stats")
        phonon.nodes.Message.raw(player, "§e/nemarket tax <set|remove|info> [rate] §7— Manage tax (leaders)")
        phonon.nodes.Message.raw(player, "§e/nemarket sanction <town> <block|price> [mult] §7— Sanction a town (leaders)")
        phonon.nodes.Message.raw(player, "§e/nemarket unsanction <town> §7— Remove sanction (leaders)")
        phonon.nodes.Message.raw(player, "§e/nemarket sanctions §7— List active sanctions")
        phonon.nodes.Message.raw(player, "§e/nemarket gdp §7— GDP leaderboard")
        phonon.nodes.Message.raw(player, "§e/nemarket listings §7— View your active listings")
        phonon.nodes.Message.raw(player, "§e/nemarket cancel <#> §7— Cancel a listing by number")
        phonon.nodes.Message.raw(player, "§e/nemarket mailbox [check|redeem] §7— Collect offline sale payments")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (sender !is Player) return emptyList()
        return when (args.size) {
            1 -> listOf("add", "sell", "info", "tax", "sanction", "unsanction", "sanctions", "gdp", "listings", "cancel", "mailbox", "help", "browse")
                .filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "add", "sell" -> listOf("<price>")
                "tax" -> listOf("set", "remove", "info").filter { it.startsWith(args[1], ignoreCase = true) }
                "mailbox" -> listOf("check", "redeem").filter { it.startsWith(args[1], ignoreCase = true) }
                "sanction", "unsanction" -> {
                    val mine = Nodes.getTownFromPlayer(sender)?.name
                    Nodes.matchTowns("*")
                        .filter { it.name != mine }
                        .map { it.name }
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "add", "sell" -> MarketConfig.allowedCurrencies.map { it.name.lowercase() }
                    .filter { it.startsWith(args[2], ignoreCase = true) }
                "tax" -> if (args[1].equals("set", ignoreCase = true)) listOf("<0.0–1.0>") else emptyList()
                "sanction" -> listOf("block", "price").filter { it.startsWith(args[2], ignoreCase = true) }
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "add", "sell" -> listOf("[quantity]")
                "sanction" -> if (args[2].equals("price", ignoreCase = true)) listOf("<multiplier>") else emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun coordOf(player: Player) = Coord(player.location.chunk.x, player.location.chunk.z)
}
