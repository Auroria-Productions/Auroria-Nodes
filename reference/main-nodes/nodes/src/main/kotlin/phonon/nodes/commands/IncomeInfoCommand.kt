package phonon.nodes.commands

import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Nodes
import phonon.nodes.objects.Town

/**
 * Command to display town income information
 * Usage: /t income info
 */
class IncomeInfoCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        // /neincome info
        if (args.isNotEmpty() && args[0].lowercase() != "info") {
            phonon.nodes.Message.raw(sender, "§cUsage: /neincome info")
            return true
        }

        if (sender !is Player) {
            phonon.nodes.Message.raw(sender, "§cThis command can only be used by players!")
            return true
        }

        // Get player's town using public API
        val town = Nodes.getTownFromPlayer(sender)
        if (town == null) {
            phonon.nodes.Message.raw(sender, "§cYou are not part of any town!")
            return true
        }

        try {
            displayIncomeInfo(sender, town)
        } catch (e: Exception) {
            phonon.nodes.Message.raw(sender, "§cError retrieving income information: ${e.message}")
            e.printStackTrace()
        }

        return true
    }

    private fun displayIncomeInfo(player: Player, town: Town) {
        // Calculate total hourly income from all territories
        val hourlyIncome = mutableMapOf<Material, Double>()
        var territoryCount = 0

        // Iterate through all territories using public API
        for (territoryEntry in Nodes.iterTerritories()) {
            val territory = territoryEntry.value

            // Check if territory belongs to this town
            // Use the public town property on Territory
            if (territory.town == town) {
                territoryCount++

                // Get income from territory (this may follow the resource-only mode)
                val territoryIncome = Nodes.getTerritoryResourceSnapshot(territory).income
                for ((material, amount) in territoryIncome) {
                    hourlyIncome[material] = (hourlyIncome[material] ?: 0.0) + amount
                }
            }
        }

        // Get current stored income from town
        // Town.income is a TownIncome object with storage properties
        val storedIncome = town.income.storage // EnumMap<Material, Int>
        val storedSpawnEggs = town.income.storageSpawnEgg // EnumMap<EntityType, Int>

        // Display information
        phonon.nodes.Message.raw(player, "§6§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        phonon.nodes.Message.raw(player, "§e§lIncome Info for §f${town.name}")
        phonon.nodes.Message.raw(player, "")

        // Display hourly production
        phonon.nodes.Message.raw(player, "§a§lHourly Production:")
        if (hourlyIncome.isEmpty()) {
            phonon.nodes.Message.raw(player, "  §7No income from territories")
        } else {
            val sortedIncome = hourlyIncome.entries.sortedByDescending { it.value }
            for ((material, amount) in sortedIncome) {
                val materialName = formatMaterialName(material)
                phonon.nodes.Message.raw(player, "  §f$materialName: §a${String.format("%.2f", amount)}/hour")
            }
        }

        phonon.nodes.Message.raw(player, "")

        // Display currently stored income
        phonon.nodes.Message.raw(player, "§b§lCurrently Stored:")
        if (storedIncome.isEmpty() && storedSpawnEggs.isEmpty()) {
            phonon.nodes.Message.raw(player, "  §7No items in storage")
        } else {
            // Regular materials
            if (storedIncome.isNotEmpty()) {
                val sortedStored = storedIncome.entries.sortedByDescending { it.value }
                for ((material, amount) in sortedStored) {
                    val materialName = formatMaterialName(material)
                    phonon.nodes.Message.raw(player, "  §f$materialName: §b$amount")
                }
            }

            // Spawn eggs (if any)
            if (storedSpawnEggs.isNotEmpty()) {
                for ((entityType, amount) in storedSpawnEggs.entries) {
                    phonon.nodes.Message.raw(player, "  §f${formatEntityName(entityType)}: §b$amount")
                }
            }
        }

        phonon.nodes.Message.raw(player, "")
        phonon.nodes.Message.raw(player, "§eTerritories: §f$territoryCount")
        phonon.nodes.Message.raw(player, "§eUse §f/t income §eto collect your stored income")
        phonon.nodes.Message.raw(player, "§6§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Format material name to be more readable
     */
    private fun formatMaterialName(material: Material): String = material.name
        .lowercase()
        .split('_')
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }

    /**
     * Format entity type name to be more readable
     */
    private fun formatEntityName(entityType: org.bukkit.entity.EntityType): String = entityType.name
        .lowercase()
        .split('_')
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        } + " Spawn Egg"

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = when (args.size) {
        1 -> listOf("info").filter { it.startsWith(args[0].lowercase()) }
        else -> emptyList()
    }
}
