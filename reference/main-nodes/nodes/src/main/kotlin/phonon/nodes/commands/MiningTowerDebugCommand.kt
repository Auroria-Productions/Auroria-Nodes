package phonon.nodes.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import phonon.nodes.Nodes
import phonon.nodes.miningtower.MiningTowerConfig

/**
 * Debug command to test mining tower system
 */
class MiningTowerDebugCommand : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (sender !is Player) {
            phonon.nodes.Message.raw(sender, "§cOnly players can use this command!")
            return true
        }

        phonon.nodes.Message.raw(sender, "§6=== Mining Tower Debug ===")

        // Check player's town
        val town = Nodes.getTownFromPlayer(sender)
        if (town == null) {
            phonon.nodes.Message.raw(sender, "§c✗ You are not in a town")
            return true
        }

        phonon.nodes.Message.raw(sender, "§a✓ Your town: ${town.name}")

        // Check for mining tower
        val miningTower = MiningTowerManager.getApprovedTower(town.name)
        if (miningTower == null) {
            phonon.nodes.Message.raw(sender, "§c✗ Your town has no approved mining tower")
            phonon.nodes.Message.raw(sender, "§7  Use /miningtower submit to submit one")
            return true
        }

        phonon.nodes.Message.raw(sender, "§a✓ Mining tower found!")
        phonon.nodes.Message.raw(sender, "§7  Submitted by: ${miningTower.submitterName}")

        // Check tier
        val tier = miningTower.ratedTier
        if (tier == null) {
            phonon.nodes.Message.raw(sender, "§c✗ Mining tower has no tier rating")
            return true
        }

        phonon.nodes.Message.raw(sender, "§a✓ Tier: $tier")

        // Check tier config
        val tierConfig = MiningTowerConfig.tiers[tier]
        if (tierConfig == null) {
            phonon.nodes.Message.raw(sender, "§c✗ Tier configuration not found for: $tier")
            phonon.nodes.Message.raw(sender, "§7  This tier might not be in config.yml")
            return true
        }

        phonon.nodes.Message.raw(sender, "§a✓ Tier config loaded")
        phonon.nodes.Message.raw(sender, "§7  Display: ${tierConfig.displayName}")
        phonon.nodes.Message.raw(sender, "§7  Income bonus: ${(tierConfig.incomeBonus * 100).toInt()}%")
        phonon.nodes.Message.raw(sender, "§7  Mining rate: ${tierConfig.miningRateMultiplier}x")

        if (tierConfig.miningRateMultiplier <= 1.0) {
            phonon.nodes.Message.raw(sender, "§e⚠ Mining rate multiplier is 1.0 or less - no buff will apply!")
        } else {
            phonon.nodes.Message.raw(sender, "§a✓ Mining rate buff should be active!")
        }

        // Check current territory
        val territory = Nodes.getTerritoryFromBlock(sender.location.blockX, sender.location.blockZ)
        if (territory == null) {
            phonon.nodes.Message.raw(sender, "§e⚠ You are not in any territory")
            return true
        }

        phonon.nodes.Message.raw(sender, "§a✓ Current territory: ${territory.name}")
        phonon.nodes.Message.raw(sender, "§7  Territory ID: ${territory.id.toInt()}")
        phonon.nodes.Message.raw(sender, "§7  Territory town: ${territory.town?.name ?: "none"}")

        if (territory.town == town) {
            phonon.nodes.Message.raw(sender, "§a✓ This territory belongs to your town")

            // Check if tower is in THIS territory
            val towerTerritoryId = miningTower.territoryId
            if (towerTerritoryId == null) {
                phonon.nodes.Message.raw(sender, "§e⚠ Mining tower has no territory ID (old submission)")
                phonon.nodes.Message.raw(sender, "§7  Submit a new tower to fix this")
            } else {
                phonon.nodes.Message.raw(sender, "§7  Tower territory ID: ${towerTerritoryId.toInt()}")

                if (territory.id == towerTerritoryId) {
                    phonon.nodes.Message.raw(sender, "§a✓ Mining tower is in THIS territory!")
                    phonon.nodes.Message.raw(sender, "§a✓ Mining buff WILL work here!")
                } else {
                    phonon.nodes.Message.raw(sender, "§c✗ Mining tower is in a DIFFERENT territory")
                    phonon.nodes.Message.raw(sender, "§7  Buff only works in the territory where tower is located")

                    // Try to find the tower's territory name
                    val towerTerritory = Nodes.getTerritoryFromId(towerTerritoryId)
                    if (towerTerritory != null) {
                        phonon.nodes.Message.raw(sender, "§7  Tower is in: §e${towerTerritory.name}")
                    }
                }
            }
        } else {
            phonon.nodes.Message.raw(sender, "§c✗ This territory belongs to: ${territory.town?.name ?: "wilderness"}")
            phonon.nodes.Message.raw(sender, "§7  Mining buff only works in your own town's territory")
        }

        phonon.nodes.Message.raw(sender, "§6===========================")
        phonon.nodes.Message.raw(sender, "§eTry mining stone in your territory and watch console for debug logs")

        return true
    }
}
