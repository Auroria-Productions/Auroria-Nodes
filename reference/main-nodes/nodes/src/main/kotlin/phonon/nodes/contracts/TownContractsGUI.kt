package phonon.nodes.contracts

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes

private val CONTRACT_SLOTS = intArrayOf(10, 11, 12, 13, 14, 15)
private const val INFO_SLOT = 22
private const val INVENTORY_SIZE = 27

object TownContractsGUI : Listener {

    private val openInventories: HashMap<Inventory, String> = hashMapOf()

    // ── Open ──────────────────────────────────────────────────────

    fun open(player: Player) {
        if (!ContractConfig.enabled) {
            Message.error(player, ContractConfig.msgSystemDisabled)
            return
        }

        val resident = Nodes.getResident(player)
        val town = resident?.town

        if (town == null) {
            Message.error(player, ContractConfig.msgNotInTown)
            return
        }

        val state = TownContractManager.getOrCreateState(town)
        val inv = buildInventory(state)
        openInventories[inv] = town.name
        player.openInventory(inv)
    }

    // ── Events ────────────────────────────────────────────────────

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val inv = event.inventory
        val townName = openInventories[inv] ?: return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val slot = event.rawSlot
        if (slot !in CONTRACT_SLOTS) return

        val contractIndex = CONTRACT_SLOTS.indexOf(slot)
        val town = Nodes.getTownFromName(townName) ?: return
        val state = TownContractManager.getOrCreateState(town)
        val contract = state.contracts.getOrNull(contractIndex) ?: return

        handleContractClick(player, town, contract, inv, state)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        openInventories.remove(event.inventory)
    }

    // ── Click logic ───────────────────────────────────────────────

    private fun handleContractClick(
        player: Player,
        town: phonon.nodes.objects.Town,
        contract: Contract,
        inv: Inventory,
        state: TownContractState,
    ) {
        if (state.completed.contains(contract.id)) {
            player.sendMessage("${ChatColor.YELLOW}${ContractConfig.msgAlreadyDone}")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f)
            return
        }

        val result = TownContractManager.complete(town, contract, player.inventory.contents)

        when (result) {
            is ContractResult.Success -> {
                // Consume required items
                for (required in contract.required) {
                    var toRemove = required.amount
                    val slots = (player.inventory.contents.indices).toMutableList()
                        .also { it.add(-1) } // -1 = offhand slot handled separately

                    // Remove from main inventory first
                    for ((index, stack) in player.inventory.contents.withIndex()) {
                        if (toRemove <= 0) break
                        if (stack == null || stack.type != required.type) continue
                        if (stack.amount <= toRemove) {
                            toRemove -= stack.amount
                            player.inventory.setItem(index, null)
                        } else {
                            stack.amount -= toRemove
                            toRemove = 0
                        }
                    }

                    if (toRemove > 0) {
                        val offhand = player.inventory.itemInOffHand
                        if (offhand.type == required.type) {
                            if (offhand.amount <= toRemove) {
                                player.inventory.setItemInOffHand(null)
                            } else {
                                offhand.amount -= toRemove
                            }
                        }
                    }
                }

                val rewardSummary = result.rewards.joinToString(", ") {
                    "${it.amount}x ${formatMaterial(it.type)}"
                }

                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)

                Message.print(
                    player,
                    "${ChatColor.GREEN}${ChatColor.BOLD}" +
                        ContractConfig.msgSuccess.replace("{contract}", contract.displayName),
                )
                Message.print(
                    player,
                    "${ChatColor.GOLD}" +
                        ContractConfig.msgRewardAdded.replace("{rewards}", rewardSummary),
                )

                // Optional server-wide broadcast
                if (ContractConfig.broadcastOnComplete) {
                    val broadcast = ContractConfig.broadcastFormat
                        .replace("{player}", player.name)
                        .replace("{town}", town.name)
                        .replace("{contract}", contract.displayName)
                    Bukkit.broadcastMessage("${ChatColor.GOLD}$broadcast")
                }

                refreshInventory(inv, state)
            }

            is ContractResult.MissingItems -> {
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f)
                Message.error(
                    player,
                    ContractConfig.msgMissingItems
                        .replace("{amount}", result.missing.amount.toString())
                        .replace("{material}", formatMaterial(result.missing.type)),
                )
            }

            is ContractResult.AlreadyCompleted -> {
                player.sendMessage("${ChatColor.YELLOW}${ContractConfig.msgAlreadyDone}")
            }
        }
    }

    // ── GUI building ──────────────────────────────────────────────

    private fun buildInventory(state: TownContractState): Inventory {
        val inv = Bukkit.createInventory(null, INVENTORY_SIZE, ContractConfig.guiTitle)

        val border = makeNamedItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        for (i in 0 until INVENTORY_SIZE) {
            if (i !in CONTRACT_SLOTS && i != INFO_SLOT) {
                inv.setItem(i, border)
            }
        }

        state.contracts.forEachIndexed { idx, contract ->
            val slot = CONTRACT_SLOTS.getOrNull(idx) ?: return@forEachIndexed
            inv.setItem(slot, buildContractItem(contract, state.completed.contains(contract.id)))
        }

        inv.setItem(INFO_SLOT, buildInfoItem(state))
        return inv
    }

    private fun refreshInventory(inv: Inventory, state: TownContractState) {
        state.contracts.forEachIndexed { idx, contract ->
            val slot = CONTRACT_SLOTS.getOrNull(idx) ?: return@forEachIndexed
            inv.setItem(slot, buildContractItem(contract, state.completed.contains(contract.id)))
        }
        inv.setItem(INFO_SLOT, buildInfoItem(state))
    }

    private fun buildContractItem(contract: Contract, completed: Boolean): ItemStack {
        val material = if (completed) Material.LIME_DYE else contract.icon
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item

        val titleColor = when {
            completed -> ChatColor.GREEN
            contract.tier == 3 -> ChatColor.LIGHT_PURPLE
            contract.tier == 2 -> ChatColor.YELLOW
            else -> ChatColor.WHITE
        }

        meta.setDisplayName("$titleColor${ChatColor.BOLD}${contract.displayName}")

        val lore = mutableListOf<String>()
        lore.add("${ChatColor.GRAY}${contract.description}")
        lore.add("")

        if (completed) {
            lore.add("${ChatColor.GREEN}✔ Completed this cycle")
        } else {
            lore.add("${ChatColor.RED}§lRequires:")
            for (req in contract.required) {
                lore.add("  ${ChatColor.WHITE}${req.amount}x ${formatMaterial(req.type)}")
            }
            lore.add("")
            lore.add("${ChatColor.GOLD}§lRewards (→ town income):")
            for (reward in contract.rewards) {
                // Show scaled amount if multiplier ≠ 1.0 so players see what they actually get
                val displayAmount = if (ContractConfig.rewardMultiplier == 1.0) {
                    reward.amount
                } else {
                    (reward.amount * ContractConfig.rewardMultiplier).toInt().coerceAtLeast(1)
                }
                lore.add("  ${ChatColor.YELLOW}${displayAmount}x ${formatMaterial(reward.type)}")
            }
            lore.add("")
            lore.add("${ChatColor.AQUA}Click to complete")
        }

        lore.add("")
        lore.add(
            when (contract.tier) {
                1 -> "${ChatColor.GRAY}★☆☆ Common"
                2 -> "${ChatColor.YELLOW}★★☆ Uncommon"
                3 -> "${ChatColor.LIGHT_PURPLE}★★★ Rare"
                else -> ""
            },
        )

        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun buildInfoItem(state: TownContractState): ItemStack {
        val item = ItemStack(Material.CLOCK)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName("${ChatColor.AQUA}${ChatColor.BOLD}Contract Cycle")

        val ms = state.msUntilRefresh()
        val hours = ms / 3_600_000L
        val minutes = (ms % 3_600_000L) / 60_000L
        val refreshText = if (ms <= 0L) {
            "${ChatColor.GREEN}Refreshing soon…"
        } else {
            "${ChatColor.YELLOW}${hours}h ${minutes}m"
        }

        val completed = state.completed.size
        val total = state.contracts.size
        val cycleLen = ContractConfig.cycleDurationMinutes

        meta.lore = listOf(
            "${ChatColor.GRAY}Completed: ${ChatColor.WHITE}$completed / $total",
            "${ChatColor.GRAY}Next refresh: $refreshText",
            "${ChatColor.GRAY}Cycle length: ${ChatColor.WHITE}${cycleLen}m",
            "",
            "${ChatColor.GRAY}Rewards go directly to",
            "${ChatColor.GRAY}your town's §6/t income§7.",
        )
        item.itemMeta = meta
        return item
    }

    // ── Utility ───────────────────────────────────────────────────

    private fun makeNamedItem(material: Material, name: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(name)
        item.itemMeta = meta
        return item
    }

    private fun formatMaterial(material: Material): String = material.name.lowercase().replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
}
