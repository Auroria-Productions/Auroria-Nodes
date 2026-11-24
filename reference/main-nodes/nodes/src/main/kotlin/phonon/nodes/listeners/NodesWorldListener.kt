/**
 * Main listener for Nodes world:
 * - town permissions and protections
 * - flag war events
 * - hidden ore
 * - ore, crop harvest taxation
 */

package phonon.nodes.listeners

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.Event.Result
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.MiningOutminerManager
import phonon.nodes.Nodes
import phonon.nodes.Nodes.getRelationshipOfPlayerToTown
import phonon.nodes.constants.DiplomaticRelationship
import phonon.nodes.constants.ErrorAlreadyCaptured
import phonon.nodes.constants.ErrorAlreadyUnderAttack
import phonon.nodes.constants.ErrorChunkNotEdge
import phonon.nodes.constants.ErrorFlagTooHigh
import phonon.nodes.constants.ErrorFlagTooLow
import phonon.nodes.constants.ErrorNoTerritory
import phonon.nodes.constants.ErrorNotBorderTerritory
import phonon.nodes.constants.ErrorNotEnemy
import phonon.nodes.constants.ErrorSkyBlocked
import phonon.nodes.constants.ErrorTooManyAttacks
import phonon.nodes.constants.ErrorTownBlacklisted
import phonon.nodes.constants.ErrorTownNotWhitelisted
import phonon.nodes.constants.INTERACTIVE_BLOCKS
import phonon.nodes.constants.PROTECTED_BLOCKS
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.nodesaddons.NodesMineBuff
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.Town
import phonon.nodes.war.Attack
import phonon.nodes.war.FlagWar
import phonon.nodes.war.WarAccessListManager
import phonon.nodes.warzone.Warzone
import phonon.nodes.warzone.WarzoneInfoPrinter
import phonon.nodes.warzone.WarzoneManager
import phonon.nodes.warzone.WarzoneText
import java.util.concurrent.ThreadLocalRandom

public class NodesWorldListener : Listener {
    private companion object {
        private val activeBlockGlitchPunishments: HashSet<java.util.UUID> = hashSetOf()
        private val blockGlitchAttempts: HashMap<java.util.UUID, Pair<Int, Long>> = hashMapOf()
    }

    private fun applyBlockGlitchEffect(player: Player, type: PotionEffectType, amplifier: Int, duration: Int) {
        if (amplifier < 0) {
            return
        }

        player.addPotionEffect(PotionEffect(type, duration, amplifier, false, false, false))
    }

    // Rapidly spins the player's camera for a fixed duration as part of the block-glitch punishment.
    // Runs independently of the potion effect duration since disorientation only needs to last
    // long enough to interrupt the glitch attempt, not the full poison/blindness window.
    private fun spinPlayerScreen(player: Player, durationTicks: Int) {
        val plugin = Nodes.plugin ?: return
        val random = ThreadLocalRandom.current()
        var ticksRemaining = durationTicks

        lateinit var task: org.bukkit.scheduler.BukkitTask
        task = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable {
                if (ticksRemaining <= 0 || !player.isOnline) {
                    task.cancel()
                    return@Runnable
                }

                val loc = player.location
                loc.yaw = (loc.yaw + 70f + random.nextFloat() * 50f) % 360f
                loc.pitch = (random.nextFloat() * 160f) - 80f
                player.teleport(loc)

                ticksRemaining--
            },
            0L,
            2L,
        )
    }

    private fun punishBlockGlitching(player: Player) {
        if (!Config.punishBlockGlitching) {
            return
        }

        val attemptsThreshold = Config.punishBlockGlitchingAttempts.coerceAtLeast(1)
        val windowTicks = Config.punishBlockGlitchingWindowTicks.coerceAtLeast(1)
        val windowMs = windowTicks * 50L
        val nowMs = System.currentTimeMillis()

        val uuid = player.uniqueId
        val prev = blockGlitchAttempts[uuid]
        val windowStart = prev?.second ?: nowMs
        val expired = (nowMs - windowStart) > windowMs
        val count = if (expired) 1 else (prev?.first ?: 0) + 1
        val newWindowStart = if (expired) nowMs else windowStart
        blockGlitchAttempts[uuid] = Pair(count, newWindowStart)

        if (count < attemptsThreshold) {
            return
        }

        val duration = Config.punishBlockGlitchingDurationTicks
        if (duration <= 0) {
            return
        }

        if (!activeBlockGlitchPunishments.add(uuid)) {
            return
        }

        applyBlockGlitchEffect(player, PotionEffectType.POISON, Config.punishBlockGlitchingPoisonAmplifier, duration)
        applyBlockGlitchEffect(player, PotionEffectType.BLINDNESS, Config.punishBlockGlitchingBlindnessAmplifier, duration)
        applyBlockGlitchEffect(player, PotionEffectType.DARKNESS, Config.punishBlockGlitchingDarknessAmplifier, duration)
        spinPlayerScreen(player, 60) // 3 seconds of camera spin
        Message.title(
            player,
            "${ChatColor.RED}STOP BLOCK GLITCHING",
            "Don't block glitch. It's against the rules.",
            5,
            duration,
            10,
        )

        val plugin = Nodes.plugin ?: return
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                blockGlitchAttempts.remove(uuid)
                activeBlockGlitchPunishments.remove(uuid)
            },
            duration.toLong(),
        )
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public fun onBlockBreak(event: BlockBreakEvent) {
        val player: Player = event.player
        val block: Block = event.block
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)

        // If this is the attack flag "top" block, cancel the attack directly (do not rely on no-build region math).
        // This prevents misconfiguration of y-bounds (flagNoBuildYOffset) or chunk-edge cases from making flags unbreakable.
        val directAttack = FlagWar.blockToAttacker[block]
        if (directAttack != null) {
            event.isCancelled = true

            val attackChunk = Nodes.getTerritoryChunkFromCoord(directAttack.coord)
            val inActiveWarzone = attackChunk?.territory?.id?.let { WarzoneManager.findActiveWarzoneForTerritoryId(it) } != null
            if (inActiveWarzone) {
                // Warzones are free-for-all: any town can break attack flags.
                val resident = Nodes.getResident(player)
                if (resident?.town == null) {
                    Message.error(player, "[War] You must be in a town to break warzone flags")
                    return
                }

                directAttack.cancel()
                Message.broadcastWarNotice(
                    "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) defeated by ${player.name}",
                    relatedTowns = listOf(
                        directAttack.town,
                        attackChunk?.territory?.town,
                        attackChunk?.territory?.occupier,
                        attackChunk?.occupier,
                    ),
                    isWarzone = true,
                )
                return
            }

            // handle breaking allies flags
            if (!Config.allowBreakingAlliesFlags) {
                // allow player to break their own flag
                if (player.uniqueId != directAttack.attacker) {
                    val relationship = getRelationshipOfPlayerToTown(player, directAttack.town)
                    if (relationship in setOf(
                            DiplomaticRelationship.NATION,
                            DiplomaticRelationship.ALLY,
                            DiplomaticRelationship.TOWN,
                        )
                    ) {
                        Message.error(player, "[War] Cannot break ally war flags")
                        return
                    }
                }
            }

            directAttack.cancel()
            Message.broadcastWarNotice(
                "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) defeated by ${player.name}",
                relatedTowns = listOf(
                    directAttack.town,
                    attackChunk?.territory?.town,
                    attackChunk?.territory?.occupier,
                    attackChunk?.occupier,
                ),
                isWarzone = false,
            )
            return
        }

        // Enforce no-break region around any active war flags (including across chunk borders).
        val blockingAttack = findAttackWithNoBuildRegion(block)
        if (blockingAttack != null) {
            // op bypass
            if (player.isOp()) {
                return
            }
            event.isCancelled = true
            Message.error(player, "[War] Cannot break blocks within ${Config.flagNoBuildDistance} blocks of war flags")
            return
        }

        // if chunk is being attacked, do flag checks
        if (territoryChunk?.attacker !== null) {
            var attack = FlagWar.chunkToAttacker.get(territoryChunk.coord)!!

            if (blockInWarFlagNoBuildRegion(block, attack)) {
                // handle war flag breaking
                if (attack.flagBlock == block) {
                    event.setCancelled(true)

                    val inActiveWarzone = WarzoneManager.findActiveWarzoneForTerritoryId(territoryChunk.territory.id) != null
                    if (inActiveWarzone) {
                        // Warzones are free-for-all: any town can break attack flags.
                        val resident = Nodes.getResident(player)
                        if (resident?.town == null) {
                            Message.error(player, "[War] You must be in a town to break warzone flags")
                            return
                        }

                        attack.cancel()
                        Message.broadcastWarNotice(
                            "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) defeated by ${player.name}",
                            relatedTowns = listOf(
                                attack.town,
                                territoryChunk.territory.town,
                                territoryChunk.territory.occupier,
                                territoryChunk.occupier,
                            ),
                            isWarzone = true,
                        )
                        return
                    }

                    // handle breaking allies flags
                    if (!Config.allowBreakingAlliesFlags) {
                        // allow player to break their own flag
                        if (player.uniqueId != attack.attacker) {
                            val relationship = getRelationshipOfPlayerToTown(player, attack.town)
                            if (relationship in setOf(
                                    DiplomaticRelationship.NATION,
                                    DiplomaticRelationship.ALLY,
                                    DiplomaticRelationship.TOWN,
                                )
                            ) {
                                Message.error(player, "[War] Cannot break ally war flags")
                                return
                            }
                        }
                    }
                    attack.cancel()
                    Message.broadcastWarNotice(
                        "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) defeated by ${player.name}",
                        relatedTowns = listOf(
                            attack.town,
                            territoryChunk.territory.town,
                            territoryChunk.territory.occupier,
                            territoryChunk.occupier,
                        ),
                        isWarzone = false,
                    )
                    return
                }
                // handle breaking within no build region

                // op bypass
                if (player.isOp()) {
                    return
                }
                event.setCancelled(true)
                Message.error(player, "[War] Cannot break blocks within ${Config.flagNoBuildDistance} blocks of war flags")
                return
            }
        }

        // Mining Outminer blocks are always breakable/collectable regardless of town permissions.
        if (block.type == Material.BLAST_FURNACE && MiningOutminerManager.isOutminerBlock(block)) {
            return
        }

        // block permissions

        // op bypass
        if (player.isOp) {
            return
        }

        val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
        val town: Town? = territory?.town
        val resident = Nodes.getResident(player)

        // interacting in areas with no territory or no town
        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }

            event.setCancelled(true)
            Message.error(player, "You cannot destroy here!")
            return
        }

        // interacting in a town
        if (resident !== null) {
            if (hasTownPermissions(TownPermissions.DESTROY, town, resident)) {
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.DESTROY, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }
        }

        event.setCancelled(true)
        Message.error(player, "You cannot destroy here!")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onBlockBreakSuccess(event: BlockBreakEvent) {
        if (event.isCancelled()) {
            return
        }

        val player = event.player
        val block = event.block
        val blockMaterial = block.getType()

        // handle hidden ore mining
        if (Config.oreBlocks.contains(blockMaterial)) {
            Nodes.getResident(player)?.let { resident ->
                resident.oreBlocksBroken += 1L
                resident.needsUpdate()
            }
            if (!Nodes.hiddenOreInvalidBlocks.contains(block)) {
                handleHiddenOre(player, block)

                // temporarily invalide block location
                Nodes.hiddenOreInvalidBlocks.add(block)
            }
        }
        // handle crop harvest
        else if (Config.cropTypes.contains(blockMaterial)) {
            handleCropHarvest(block)
        }
    }

    // In your main plugin listener (PlayerQuitEvent)
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId

        blockGlitchAttempts.remove(uuid)
        activeBlockGlitchPunishments.remove(uuid)

        // Clean up any beams this player might have seen
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public fun onBlockPlace(event: BlockPlaceEvent) {
        val block: Block = event.block
        val player: Player = event.player

        val now = System.currentTimeMillis()
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
        val isFlagMaterial = FlagWar.flagMaterials.contains(block.type)
        val warzone = if (territoryChunk !== null && isFlagMaterial) {
            WarzoneManager.findWarzoneByTerritoryId(territoryChunk.territory.id)
        } else {
            null
        }
        if (warzone !== null && handleWarzoneFlagPlacement(event, player, territoryChunk!!, warzone, now)) {
            return
        }

        // If any attack is active, enforce the no-build region regardless of whether war was toggled off mid-attack.
        val blockingAttack = findAttackWithNoBuildRegion(block)
        if (blockingAttack != null && !player.isOp()) {
            event.isCancelled = true
            Message.error(player, "[War] Cannot build within ${Config.flagNoBuildDistance} blocks of war flags")
            return
        }

        val treatAsWar = Nodes.war.enabled || (warzone?.isActive(now) == true)

        if (treatAsWar && territoryChunk !== null) {
            if (territoryChunk.attacker !== null) {
                if (player.isOp()) {
                    return
                }

                var attack = FlagWar.chunkToAttacker.get(territoryChunk.coord)
                if (attack !== null) {
                    if (blockInWarFlagNoBuildRegion(block, attack)) {
                        event.setCancelled(true)
                        Message.error(player, "[War] Cannot build within ${Config.flagNoBuildDistance} blocks of war flags")
                        return
                    }
                }
            } else if (isFlagMaterial) {
                val resident = Nodes.getResident(player)
                if (resident !== null) {
                    val town = resident.town
                    if (town !== null) {
                        val result = FlagWar.beginAttack(player.getUniqueId(), town, territoryChunk, block)
                        if (result.isSuccess) {
                            val townAttacked = territoryChunk.territory.town!!
                            val isWarzoneAttack = WarzoneManager.findActiveWarzoneForTerritoryId(territoryChunk.territory.id) != null
                            if (townAttacked === town) {
                                Message.broadcastWarNotice(
                                    "${ChatColor.DARK_RED}[War] ${event.player.name} is liberating ${townAttacked.name} at (${block.x}, ${block.y}, ${block.z})",
                                    relatedTowns = listOf(
                                        town,
                                        townAttacked,
                                        territoryChunk.territory.occupier,
                                        territoryChunk.occupier,
                                    ),
                                    isWarzone = isWarzoneAttack,
                                )
                            } else {
                                Message.broadcastWarNotice(
                                    "${ChatColor.DARK_RED}[War] ${event.player.name} is attacking ${townAttacked.name} at (${block.x}, ${block.y}, ${block.z})",
                                    relatedTowns = listOf(
                                        town,
                                        townAttacked,
                                        territoryChunk.territory.occupier,
                                        territoryChunk.occupier,
                                    ),
                                    isWarzone = isWarzoneAttack,
                                )
                            }
                            return
                        } else {
                            when (result.exceptionOrNull()) {
                                ErrorNoTerritory -> Message.error(player, "[War] There is no territory here")
                                ErrorAlreadyUnderAttack -> Message.error(player, "[War] Chunk already under attack")
                                ErrorAlreadyCaptured -> Message.error(player, "[War] Chunk already captured by town or allies")
                                ErrorTownBlacklisted -> Message.error(player, "[War] Cannot attack this town (blacklisted)")
                                ErrorTownNotWhitelisted -> Message.error(player, "[War] Cannot attack this town (not whitelisted)")
                                ErrorNotEnemy -> Message.error(player, "[War] Chunk does not belong to an enemy")
                                ErrorNotBorderTerritory -> Message.error(player, "[War] You can only attack border territories")
                                ErrorChunkNotEdge -> Message.error(player, "[War] Must attack from territory edge or from captured chunk")
                                ErrorFlagTooHigh -> Message.error(player, "[War] Flag placement too high, cannot create flag")
                                ErrorFlagTooLow -> Message.error(player, "[War] Flag placement too low, cannot create flag")
                                ErrorSkyBlocked -> Message.error(player, "[War] Flag must see the sky")
                                ErrorTooManyAttacks -> Message.error(player, "[War] You cannot attack any more chunks at the same time")
                                else -> {
                                    val errMessage = result.exceptionOrNull()?.message
                                    if (!errMessage.isNullOrBlank()) {
                                        Message.error(player, errMessage)
                                    } else {
                                        Message.error(player, "[War] Attack could not be started")
                                    }
                                }
                            }
                            event.setCancelled(true)
                            return
                        }
                    } else {
                        Message.error(player, "[War] Cannot claim unless you are part of a town")
                        event.setCancelled(true)
                        return
                    }
                } else {
                    event.setCancelled(true)
                    return
                }
            }
        }

        // op bypass
        if (player.isOp()) {
            return
        }

        val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
        val resident = Nodes.getResident(player)
        val town: Town? = territory?.town

        // interacting in areas with no territory or no town
        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }

            event.setCancelled(true)
            Message.error(player, "You cannot build here!")
            punishBlockGlitching(player)
            return
        }

        // interacting in a town
        if (resident !== null) {
            if (hasTownPermissions(TownPermissions.BUILD, town, resident)) {
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.BUILD, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }
        }

        event.setCancelled(true)
        Message.error(player, "You cannot build here!")
        punishBlockGlitching(player)
    }

    private fun handleWarzoneFlagPlacement(
        event: BlockPlaceEvent,
        player: Player,
        territoryChunk: TerritoryChunk,
        warzone: Warzone,
        now: Long,
    ): Boolean {
        if (warzone.isActive(now)) {
            return false
        }

        event.isCancelled = true
        val locationLabel = "node ${territoryChunk.territory.id}"
        val message = if (warzone.state.nextStartTime > now) {
            "${WarzoneText.prefix()} ${warzone.name} ($locationLabel) starts in ${WarzoneInfoPrinter.formatDuration(warzone.state.nextStartTime - now)}."
        } else if (warzone.state.nextStartTime > 0L) {
            "${WarzoneText.prefix()} ${warzone.name} ($locationLabel) is scheduled for ${WarzoneInfoPrinter.formatTimestamp(warzone.state.nextStartTime)}."
        } else {
            "${WarzoneText.prefix()} ${warzone.name} ($locationLabel) has no schedule yet; use /warzone."
        }
        Message.error(player, message)
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onBlockPlaceSuccess(event: BlockPlaceEvent) {
        if (event.isCancelled()) {
            return
        }

        val block = event.block
        val blockMaterial = block.type

        // invalide hidden ore blocks
        if (Config.oreBlocks.contains(blockMaterial)) {
            Nodes.hiddenOreInvalidBlocks.add(block)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public fun onPlayerBucketEmpty(event: PlayerBucketEmptyEvent) {
        val block = event.getBlockClicked().getRelative(event.getBlockFace())
        val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
        val player: Player = event.player
        val resident = Nodes.getResident(player)
        val town: Town? = territory?.town

        // op bypass
        if (player.isOp()) {
            return
        }

        // interacting in areas with no territory or no town
        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }

            event.setCancelled(true)
            Message.error(player, "You cannot use buckets here")
            return
        }

        // interacting in a town
        if (resident !== null) {
            if (hasTownPermissions(TownPermissions.BUILD, town, resident)) {
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.BUILD, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }
        }

        event.setCancelled(true)
        Message.error(player, "You cannot use buckets here")
    }

    // handle placing water/lava onto block (can be used to destroy harvest crops)
    @EventHandler(priority = EventPriority.MONITOR)
    public fun onPlayerBucketEmptySuccess(event: PlayerBucketEmptyEvent) {
        if (event.isCancelled()) {
            return
        }

        // gets block where water actually placed
        val block = event.getBlockClicked().getRelative(event.getBlockFace())
        val blockMaterial = block.type

        // handle crop harvest
        if (Config.cropTypes.contains(blockMaterial)) {
            handleCropHarvest(block)
        }
    }

    // handle fluids moving to block (for water auto-farms)
    // NOTE: there are edge cases that are not triggered:
    // - when water falling vertically this is not triggered
    // just ignoring that because very limited use ingame of vertical water harvesting
    @EventHandler(priority = EventPriority.MONITOR)
    public fun onBlockFromToSuccess(event: BlockFromToEvent) {
        if (event.isCancelled()) {
            return
        }

        val block = event.getToBlock()
        val blockMaterial = block.type

        // handle crop harvest
        if (Config.cropTypes.contains(blockMaterial)) {
            handleCropHarvest(block)
        }
    }

    // handle pistons breaking block (for auto-farms)
    // handle harvest tax for any blocks moved by piston event
    // this only hnadles objects directly moved by piston, not things like
    // dirt underneath a block
    @EventHandler(priority = EventPriority.MONITOR)
    public fun onPistonExtendSuccess(event: BlockPistonExtendEvent) {
        if (event.isCancelled()) {
            return
        }

        val blockMovedList = event.getBlocks()
        for (block in blockMovedList) {
            val blockMaterial = block.type

            // handle crop harvest
            if (Config.cropTypes.contains(blockMaterial)) {
                handleCropHarvest(block)
            }
        }
    }

    @EventHandler
    public fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.clickedBlock === null) {
            return
        }

        val player = event.player
        val action = event.action

        // op bypass
        if (player.isOp()) {
            return
        }

        val block = event.getClickedBlock()
        if (block === null) {
            return
        }

        val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
        val resident = Nodes.getResident(player)
        val town: Town? = territory?.town

        // interacting in areas with no territory or no town
        // DO NOT USE WILDERNESS PERMISSIONS
        if (territory === null) {
            return
        }
        if (town === null) {
            return
        }

        if (resident !== null) {
            // Allow placing war flags during war (or an active warzone) even if the player lacks INTERACT permission.
            // PlayerInteractEvent fires before BlockPlaceEvent; denying item use here prevents flag placement entirely.
            if (Config.flagMaterials.contains(event.getMaterial()) && action == Action.RIGHT_CLICK_BLOCK) {
                val now = System.currentTimeMillis()
                val treatAsWar = Nodes.war.enabled || WarzoneManager.findActiveWarzoneForTerritoryId(territory.id) != null
                if (treatAsWar) {
                    // Don't open/use interactive blocks while trying to place a flag; still allow the item use.
                    val clickedBlock = event.getClickedBlock()
                    if (clickedBlock !== null && INTERACTIVE_BLOCKS.contains(clickedBlock.getType())) {
                        event.setUseInteractedBlock(Result.DENY)
                    }
                    event.setUseItemInHand(Result.ALLOW)
                    return
                }
            }

            // special permissions for using chests, furnaces, etc...
            if (PROTECTED_BLOCKS.contains(block.type)) {
                // war permissions override
                if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                    return
                }

                // normal town permissions
                if (hasTownPermissions(TownPermissions.CHESTS, town, resident)) {
                    // check if chest protected
                    if (town.protectedBlocks.contains(block) && !hasTownProtectedChestPermissions(town, resident)) {
                        event.setCancelled(true)
                        Message.error(player, "This chest is for trusted residents only")
                    }

                    return
                }

                event.setCancelled(true)
                Message.error(player, "You cannot use chests here!")
                return
            }

            // general interact permissions
            if (hasTownPermissions(TownPermissions.INTERACT, town, resident)) {
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.INTERACT, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }
        }

        event.setUseInteractedBlock(Result.DENY)

        if (event.isBlockInHand()) {
            event.setUseItemInHand(Result.DENY)
        } else {
            event.setUseItemInHand(Result.DEFAULT)
        }

        if (action == Action.RIGHT_CLICK_BLOCK) {
            Message.error(player, "You cannot interact here!")
        }
    }

    // disable normal animal interactions (e.g. animal on pressure plate)
    @EventHandler
    public fun onEntityInteract(event: EntityInteractEvent) {
        val entity = event.getEntity()

        // check if animal has passenger that is player
        val passengers = entity.getPassengers()
        var player: Player? = null
        for (p in passengers) {
            if (p is Player) {
                player = p
                break
            }
        }

        // if player member of town in territory, allow access
        if (player !== null) {
            // op bypass
            if (player.isOp()) {
                return
            }

            val block: Block = event.block
            val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
            val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
            val resident = Nodes.getResident(player)
            val town: Town? = territory?.town

            // allow interacting in areas with no territory or no town
            // DO NOT USE WILDERNESS PERMISSIONS
            if (territory === null) {
                return
            }
            if (town === null) {
                return
            }

            if (resident !== null) {
                if (hasTownPermissions(TownPermissions.INTERACT, town, resident)) {
                    return
                }

                // territory occupier permissions
                val occupier: Town? = territory.occupier
                if (occupier !== null && hasOccupierPermissions(TownPermissions.INTERACT, town, occupier, resident)) {
                    return
                }

                // war permissions
                if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                    return
                }
            }
        }

        event.setCancelled(true)
    }

    /**
     * Handle entity explosion (i.e. tnt, creeper) and
     * destroying flag -> stop attack
     */
    @EventHandler(priority = EventPriority.LOW)
    public fun onEntityExplode(event: EntityExplodeEvent) {
        // check if explosion allowed
        if (Config.restrictExplosions) {
            if (!Nodes.war.enabled) {
                if (Config.onlyAllowExplosionsDuringWar) {
                    event.setCancelled(true)
                    return
                }
            }
            // war on, check town blacklist/whitelist
            else {
                val chunk = event.entity.location.chunk
                val town = Nodes.getTownAtChunkCoord(chunk.x, chunk.z)
                if (WarAccessListManager.isTownBlacklisted(town)) {
                    event.setCancelled(true)
                    return
                }
                if (WarAccessListManager.hasWhitelist()) {
                    if (town == null || !WarAccessListManager.isTownWhitelisted(town)) {
                        event.setCancelled(true)
                        return
                    }
                }
            }
        }

        // check if flag destroyed
        for (block in event.blockList()) {
            // check if chunk under attack
            val chunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
            if (chunk?.attacker !== null) {
                var attack = FlagWar.blockToAttacker.get(block)

                // check if block above was flag block (e.g. destroying fence)
                if (attack === null) {
                    attack = FlagWar.blockToAttacker.get(block.getRelative(0, 1, 0))
                }

                if (attack !== null) {
                    attack.cancel()
                    Message.broadcastWarNotice(
                        "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) stopped by an explosion",
                        relatedTowns = listOf(
                            attack.town,
                            chunk.territory.town,
                            chunk.territory.occupier,
                            chunk.occupier,
                        ),
                        isWarzone = WarzoneManager.findActiveWarzoneForTerritoryId(chunk.territory.id) != null,
                    )
                }
            }
        }
    }
}

/**
 * Handle block explosion (i.e. end crystals) and
 * destroying flag -> stop attack
 */
@EventHandler(priority = EventPriority.LOW)
public fun onBlockExplode(event: BlockExplodeEvent) {
    // check if explosion allowed
    if (Config.restrictExplosions) {
        if (!Nodes.war.enabled) {
            if (Config.onlyAllowExplosionsDuringWar) {
                event.setCancelled(true)
                return
            }
        }
        // war on, check town blacklist/whitelist
        else {
            val chunk = event.block.chunk
            val town = Nodes.getTownAtChunkCoord(chunk.x, chunk.z)
            if (WarAccessListManager.isTownBlacklisted(town)) {
                event.setCancelled(true)
                return
            }
            if (WarAccessListManager.hasWhitelist()) {
                if (town == null || !WarAccessListManager.isTownWhitelisted(town)) {
                    event.setCancelled(true)
                    return
                }
            }
        }
    }

    // check if flag destroyed
    for (block in event.blockList()) {
        // check if chunk under attack
        val chunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
        if (chunk?.attacker !== null) {
            var attack = FlagWar.blockToAttacker.get(block)

            // check if block above was flag block (e.g. destroying fence)
            if (attack === null) {
                attack = FlagWar.blockToAttacker.get(block.getRelative(0, 1, 0))
            }

            if (attack !== null) {
                attack.cancel()
                Message.broadcastWarNotice(
                    "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) stopped by an explosion",
                    relatedTowns = listOf(
                        attack.town,
                        chunk.territory.town,
                        chunk.territory.occupier,
                        chunk.occupier,
                    ),
                    isWarzone = WarzoneManager.findActiveWarzoneForTerritoryId(chunk.territory.id) != null,
                )
            }
        }
    }
}

/**
 * Permissions for unclaimed territories or empty areas (no territories)
 */
private fun hasWildernessPermissions(territory: Territory?): Boolean {
    if (territory !== null && Config.canInteractInUnclaimed) {
        return true
    } else if (Config.canInteractInEmpty) {
        return true
    }

    return false
}

/**
 * Default permissions check for town:
 * perms: town permissions type
 * town: town
 * player: player interacting in town
 */
fun hasTownPermissions(perms: TownPermissions, town: Town, player: Resident): Boolean {
    if (town.permissions[perms].contains(PermissionsGroup.TOWN) && player.town === town) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.TRUSTED) && player.town === town && player.trusted) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.NATION) && town.nation !== null && player.nation === town.nation) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.ALLY) && (town.allies.contains(player.town) == true)) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.OUTSIDER)) {
        return true
    }

    return false
}

/**
 * Permissions check for a town's territory occupied by another town:
 * perms: town permissions type
 * town: town that owns the territory
 * occupier: town that is occupier of the territory
 * player: player interacting in the territory
 */
fun hasOccupierPermissions(perms: TownPermissions, town: Town, occupier: Town, player: Resident): Boolean {
    if (Config.allowControlInOccupiedTownList.contains(town.uuid)) {
        return hasTownPermissions(perms, occupier, player)
    } else {
        return false
    }
}

/**
 * Permissions for town protected chests
 * Currently just returns if player.trusted flag if player in town
 */
private fun hasTownProtectedChestPermissions(town: Town, player: Resident): Boolean {
    if (player.town === town) {
        if (player === town.leader) {
            return true
        } else if (town.officers.contains(player)) {
            return true
        } else {
            return player.trusted
        }
    }

    return false
}

// bypass permissions and allow all interaction in
// captured chunks/territories during wartime
private fun hasWarPermissions(resident: Resident, territory: Territory, territoryChunk: TerritoryChunk): Boolean {
    val warzoneActive = WarzoneManager.findActiveWarzoneForTerritoryId(territory.id) != null
    if (!Nodes.war.enabled && !warzoneActive) {
        return false
    }

    if (Nodes.war.enabled || warzoneActive) {
        val residentTown = resident.town
        val territoryTown = territory.town

        if (residentTown !== null) {
            // extended permissions for allies
            if (Config.warPermissions) {
                val residentNation = residentTown.nation

                if (territory.occupier === residentTown ||
                    residentTown.allies.contains(territory.occupier) ||
                    territoryChunk.occupier === residentTown ||
                    territoryChunk.attacker === residentTown ||
                    residentTown.allies.contains(territoryTown) ||
                    residentTown.allies.contains(territoryChunk.occupier) ||
                    residentTown.allies.contains(territoryChunk.attacker)
                ) {
                    return true
                }

                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
            // only let town/nation by default
            else {
                if (territory.occupier === residentTown || territoryChunk.occupier === residentTown || territoryChunk.attacker === residentTown) {
                    return true
                }

                val residentNation = residentTown.nation
                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
        }
    }

    return false
}

// handle crop harvest and tax event
private fun handleCropHarvest(block: Block) {
    val blockX = block.location.blockX
    val blockZ = block.location.blockZ
    val territory = Nodes.getTerritoryFromBlock(blockX, blockZ)

    if (territory !== null) {
        val random = ThreadLocalRandom.current()

        // do tax event check
        val territoryOccupier = territory.occupier
        if (territoryOccupier !== null && random.nextDouble() <= Config.taxFarmRate) {
            val items = block.getDrops()
            for (itemStack in items) {
                Nodes.addToIncome(territoryOccupier, itemStack.type, itemStack.amount)
            }

            block.setType(Material.AIR)
        }
        // handle town over max claims penalty
        else if (Config.claimsPowerEnabled && Config.overClaimsPenalty && territory.town?.isOverClaimsMax == true) {
            if (random.nextDouble() < Config.overClaimsMaxPenalty) {
                block.setType(Material.AIR)
            }
        }
    }
}

// handle hidden ore generation during mining
private fun handleHiddenOre(player: Player, block: Block) {
    if (!phonon.nodes.core.CoreHooks.hiddenOre(player, block)) return
    // ignore hidden ore for silk touch tools
    val inMainHand: ItemStack? = player.inventory.itemInMainHand
    if (inMainHand?.hasItemMeta() == true && inMainHand.enchantments?.containsKey(Enchantment.SILK_TOUCH) == true) {
        return
    }

    val blockX = block.location.blockX
    val blockZ = block.location.blockZ
    val blockY = block.location.blockY
    val blockWorld = block.world

    val territory = Nodes.getTerritoryFromBlock(blockX, blockZ)
    val availableOres = territory?.ores?.ores ?: run {
        if (!Config.allowOreInWilderness) {
            return
        }

        Config.globalResources.ores.values
    }

    val random = ThreadLocalRandom.current()

    val territoryTown = territory?.town
    val territoryNation = territoryTown?.nation

    val playerTown = Nodes.getTownFromPlayer(player)
    val playerNation = playerTown?.nation

    // Hidden ore should always work in an existing unclaimed territory.
    // `/territory` already exposes ore values there, so treating it as
    // blocked wilderness makes the feature look broken.
    if (territory !== null &&
        territoryTown !== null &&
        territoryTown !== playerTown &&
        (!Config.allowOreInNationTowns || territoryNation === null || territoryNation !== playerNation) &&
        (!Config.allowOreInCaptured || territory.occupier !== playerTown)
    ) {
        return
    }

    val mineBuffEffect = NodesMineBuff.getCurrentEffectForTown(playerTown)
    val chanceMultiplier = mineBuffEffect.chanceMultiplier.coerceAtLeast(1.0)
    val amountMultiplier = mineBuffEffect.amountMultiplier.coerceAtLeast(1.0)
    val amountMultiplierFloor = kotlin.math.floor(amountMultiplier).toInt().coerceAtLeast(1)
    val amountMultiplierFraction = (amountMultiplier - amountMultiplierFloor).coerceAtLeast(0.0)
    val territoryOccupier = territory?.occupier
    val taxMine = territoryOccupier !== null && random.nextDouble() <= Config.taxMineRate

    if (!taxMine && Config.claimsPowerEnabled && Config.overClaimsPenalty && playerTown?.isOverClaimsMax == true) {
        if (random.nextDouble() < Config.overClaimsMaxPenalty) {
            return
        }
    }

    val dropLocation = block.location
    for (ore in availableOres) {
        if (blockY < ore.ymin || blockY > ore.ymax) {
            continue
        }

        val buffedDropChance = (ore.dropChance * chanceMultiplier).coerceAtMost(1.0)
        if (random.nextDouble() < buffedDropChance) {
            val amount = ore.minAmount + random.nextInt(ore.maxAmount - ore.minAmount + 1)
            var boostedAmount = amount * amountMultiplierFloor
            if (amountMultiplierFraction > 0.0) {
                val fractionalScaled = amount * amountMultiplierFraction
                var extraAmount = kotlin.math.floor(fractionalScaled).toInt()
                if (random.nextDouble() < (fractionalScaled - extraAmount)) {
                    extraAmount += 1
                }
                boostedAmount += extraAmount
            }

            if (taxMine) {
                Nodes.addToIncome(territoryOccupier!!, ore.material, boostedAmount)
            } else {
                if (boostedAmount > 0) { // ADD THIS GUARD
                    blockWorld.dropItem(dropLocation, ItemStack(ore.material, boostedAmount))
                }
            }
        }
    }
}

/**
 * Return if a block is within a war attack flag's no build region
 */
private fun blockInWarFlagNoBuildRegion(block: Block, attack: Attack): Boolean {
    val x = block.x
    val y = block.y
    val z = block.z

    if (x < attack.noBuildXMin || x > attack.noBuildXMax) {
        return false
    }
    if (y < attack.noBuildYMin || y > attack.noBuildYMax) {
        return false
    }
    if (z < attack.noBuildZMin || z > attack.noBuildZMax) {
        return false
    }

    return true
}

/**
 * Returns the first active [Attack] whose no-build region contains [block], or null if none.
 *
 * This is intentionally global (scans all active attacks) so chunk-edge cases can't bypass
 * [Config.flagNoBuildDistance].
 */
private fun findAttackWithNoBuildRegion(block: Block): Attack? {
    val x = block.x
    val y = block.y
    val z = block.z

    for (attack in FlagWar.chunkToAttacker.values) {
        if (x < attack.noBuildXMin || x > attack.noBuildXMax) continue
        if (y < attack.noBuildYMin || y > attack.noBuildYMax) continue
        if (z < attack.noBuildZMin || z > attack.noBuildZMax) continue
        return attack
    }

    return null
}