package phonon.nodes.miningtower

import MiningTowerManager.enabled
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.NodesExtensions
import kotlin.random.Random

/**
 * Listens for block breaks and applies mining rate buffs based on mining tower tier.
 *
 * Key fixes vs original:
 *  - Mining buff message uses \u00a7 escape (was corrupted as Â§).
 *  - validMiningBlocks can be overridden via config (mining-tower.valid-mining-blocks).
 */
class MiningRateListener : Listener {

    // Default set of blocks that can trigger the ore buff.
    // Overridden at registration time if config supplies a list.
    private var validMiningBlocks: Set<Material> = setOf(
        Material.STONE,
        Material.COBBLESTONE,
        Material.DEEPSLATE,
        Material.COBBLED_DEEPSLATE,
        Material.ANDESITE,
        Material.DIORITE,
        Material.GRANITE,
        Material.TUFF,
        Material.NETHERRACK,
        Material.END_STONE,
    )

    /**
     * Call this after MiningTowerConfig.load() to optionally override the block set
     * from config key "mining-tower.valid-mining-blocks" (list of material names).
     */
    fun loadValidBlocks(config: org.bukkit.configuration.file.FileConfiguration) {
        val list = config.getStringList("mining-tower.valid-mining-blocks")
        if (list.isNotEmpty()) {
            val parsed = list.mapNotNull { name ->
                runCatching { Material.valueOf(name.uppercase()) }.getOrNull().also { mat ->
                    if (mat == null) {
                        NodesExtensions.instance.logger.warning("[MiningTower] Unknown material in valid-mining-blocks: $name")
                    }
                }
            }.toSet()
            if (parsed.isNotEmpty()) {
                validMiningBlocks = parsed
                NodesExtensions.instance.logger.info("[MiningTower] Loaded ${parsed.size} valid mining block(s) from config")
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!enabled) return

        val player = event.player
        val block = event.block

        if (!validMiningBlocks.contains(block.type)) return

        val logger = NodesExtensions.instance.logger
        val debug = MiningTowerConfig.debugEnabled

        if (debug) logger.info("[MiningTower] ${player.name} mined ${block.type} at ${block.x}, ${block.y}, ${block.z}")

        val town = Nodes.getTownFromPlayer(player)
        if (town == null) {
            if (debug) logger.info("[MiningTower] ${player.name} has no town")
            return
        }

        val miningTower = MiningTowerManager.getApprovedTower(town.name)
        if (miningTower == null) {
            if (debug) logger.info("[MiningTower] Town ${town.name} has no approved mining tower")
            return
        }

        val tier = miningTower.ratedTier
        if (tier == null) {
            if (debug) logger.info("[MiningTower] Mining tower has no tier rating")
            return
        }

        val tierConfig = MiningTowerConfig.tiers[tier]
        if (tierConfig == null) {
            if (debug) logger.info("[MiningTower] Tier config not found for: $tier")
            return
        }

        val miningRateMultiplier = tierConfig.miningRateMultiplier
        if (miningRateMultiplier <= 1.0) {
            if (debug) logger.info("[MiningTower] Multiplier is 1.0 or less, no buff")
            return
        }

        val territory = Nodes.getTerritoryFromBlock(block.x, block.z)
        if (territory == null) {
            if (debug) logger.info("[MiningTower] Block is not in any territory")
            return
        }

        if (territory.town != town) {
            if (debug) logger.info("[MiningTower] Territory belongs to ${territory.town?.name ?: "wilderness"}, not ${town.name}")
            return
        }

        val towerTerritoryIntId = miningTower.territoryId?.toInt()
        if (towerTerritoryIntId == null) {
            if (debug) logger.info("[MiningTower] Mining tower has no territory ID")
            return
        }

        // Compare by integer value (fixes object-reference equality bug)
        if (territory.id.toInt() != towerTerritoryIntId) {
            if (debug) logger.info("[MiningTower] Not in tower territory. Current: ${territory.id.toInt()}, Tower: $towerTerritoryIntId")
            return
        }

        if (debug) logger.info("[MiningTower] \u2714 All checks passed! Applying mining buff x$miningRateMultiplier")
        applyMiningBuff(player, block, territory, miningRateMultiplier, tierConfig)
    }

    private fun applyMiningBuff(
        player: Player,
        block: Block,
        territory: phonon.nodes.objects.Territory,
        multiplier: Double,
        tierConfig: TierConfig,
    ) {
        val logger = NodesExtensions.instance.logger
        val debug = MiningTowerConfig.debugEnabled

        val snapshot = Nodes.getTerritoryResourceSnapshot(territory)

        if (!snapshot.oreSampler.containsOre) {
            if (debug) logger.info("[MiningTower] Territory has no ore deposits configured")
            return
        }

        val bonusMultiplier = multiplier - 1.0
        if (bonusMultiplier <= 0.0) return

        val guaranteedBonuses = bonusMultiplier.toInt()
        val fractionalChance = bonusMultiplier - guaranteedBonuses
        var bonusDrops = guaranteedBonuses

        if (fractionalChance > 0.0 && Random.nextDouble() < fractionalChance) {
            bonusDrops++
        }

        if (debug) logger.info("[MiningTower] Bonus drops to give: $bonusDrops")
        if (bonusDrops <= 0) return

        val allBonusItems = mutableListOf<ItemStack>()
        repeat(bonusDrops) {
            allBonusItems.addAll(snapshot.oreSampler.sample(block.y))
        }

        if (debug) logger.info("[MiningTower] Generated ${allBonusItems.size} bonus item stacks")

        if (allBonusItems.isNotEmpty()) {
            val location = block.location.add(0.5, 0.5, 0.5)
            for (item in allBonusItems) {
                if (debug) logger.info("[MiningTower] Dropping: ${item.amount}x ${item.type}")
                block.world.dropItemNaturally(location, item)
            }

            if (MiningTowerConfig.showMiningBuffMessage) {
                Message.announcement(player, "\u00a76\u26cf \u00a7eMining Tower Bonus! \u00a76\u26cf")
            }

            if (debug) logger.info("[MiningTower] \u2714 Successfully gave mining bonus to ${player.name}")
        } else {
            if (debug) logger.info("[MiningTower] Ore sampler returned no drops (unlucky roll)")
        }
    }
}
