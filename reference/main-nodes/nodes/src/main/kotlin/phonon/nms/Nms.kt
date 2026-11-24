package phonon.nodes.nms

import com.github.retrooper.packetevents.PacketEvents
import io.netty.channel.Channel
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.Team
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.util.CraftMagicNumbers
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import net.minecraft.core.BlockPos as NMSBlockPos
import net.minecraft.network.protocol.Packet as NMSPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket as NMSPacketLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket as NMSPacketSetEntityData
import net.minecraft.network.syncher.EntityDataSerializers as NMSEntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData as NMSSynchedEntityData
import net.minecraft.server.level.ServerPlayer as NMSPlayer
import net.minecraft.world.level.block.state.BlockState as NMSBlockState
import net.minecraft.world.level.chunk.LevelChunk as NMSChunk

// re-exported type aliases
internal typealias NMSBlockPos = NMSBlockPos
internal typealias NMSBlockState = NMSBlockState
internal typealias NMSChunk = NMSChunk
internal typealias NMSPlayer = NMSPlayer
internal typealias NMSPacketLevelChunkWithLightPacket = NMSPacketLevelChunkWithLightPacket
internal typealias NMSPacketSetEntityData = NMSPacketSetEntityData
internal typealias CraftWorld = CraftWorld
internal typealias CraftMagicNumbers = CraftMagicNumbers

// ---------------------------------------------------------------------------
// Packet sender
//
// Previously: a two-MethodHandle chain through CraftPlayer → NMSPlayer.connection
// → ServerGamePacketListenerImpl.send() to avoid Paper's remapping overhead on
// the CraftPlayer cast and the reflection cache in PaperReflectionHolder.
//
// Now: PacketEvents already injected itself into each player's Netty pipeline at
// login. getChannel() returns that same channel directly — no reflection, no
// MethodHandles, no CraftPlayer cast at all. writeAndFlush() enters the pipeline
// at the Netty level, below Paper's packet-send machinery, so the remapping
// issue that motivated the original MethodHandle approach no longer applies.
//
// Tradeoffs vs the old approach:
//   - Simpler: ~70 lines of MethodHandle setup replaced by 2 lines.
//   - Bypasses PacketEvents' own outbound PacketSendEvent (same as the NMS path).
//   - writeAndFlush() failures are silent; add a ChannelFutureListener if you
//     need diagnostics (see sendPacket() below).
//   - Hard dependency: PacketEvents must be initialised before the first call.
//     Declare it as a dependency in plugin.yml to guarantee load order.
// ---------------------------------------------------------------------------

private val sendFailureLoggedFor: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap(16)

internal fun Player.sendPacket(packet: NMSPacket<*>) {
    val ch = PacketEvents.getAPI().getPlayerManager().getChannel(this) as? Channel
    if (ch == null || !ch.isActive) {
        // Log once per player so we don't spam on legitimate disconnect races
        if (sendFailureLoggedFor.putIfAbsent(this.uniqueId, true) == null) {
            org.bukkit.Bukkit.getLogger().warning(
                "[nodes] sendPacket: no active channel for ${this.name} — packet dropped",
            )
        }
        return
    }
    ch.writeAndFlush(packet)
}

internal fun clearSendFailureLog(uuid: UUID) = sendFailureLoggedFor.remove(uuid)

internal fun evictTeamFromPool(teamName: String) {
    val team = teamPool.remove(teamName) ?: return
    try {
        packetScoreboard.removePlayerTeam(team)
    } catch (_: Exception) {}
}

// ---------------------------------------------------------------------------
// PlayerTeam field setters — MethodHandle via privateLookupIn
//
// Field.set() goes through JNI and Paper's remapping layer on every call.
// unreflectSetter() is resolved once; subsequent invoke() calls are inlined
// by JIT and benchmark at ~2-4 ns vs ~17-50 ns for Field.set on Paper.
// ---------------------------------------------------------------------------

private object TeamFieldSetters {
    private val lookup = MethodHandles.privateLookupIn(PlayerTeam::class.java, MethodHandles.lookup())
    val setPrefix: MethodHandle = lookup.unreflectSetter(PlayerTeam::class.java.getDeclaredField("playerPrefix").also { it.isAccessible = true })
    val setSuffix: MethodHandle = lookup.unreflectSetter(PlayerTeam::class.java.getDeclaredField("playerSuffix").also { it.isAccessible = true })
    val setNameTagVis: MethodHandle = lookup.unreflectSetter(PlayerTeam::class.java.getDeclaredField("nameTagVisibility").also { it.isAccessible = true })
    val setCollisionRule: MethodHandle = lookup.unreflectSetter(PlayerTeam::class.java.getDeclaredField("collisionRule").also { it.isAccessible = true })
    val setColor: MethodHandle = lookup.unreflectSetter(PlayerTeam::class.java.getDeclaredField("color").also { it.isAccessible = true })
}

// ---------------------------------------------------------------------------
// PlayerTeam pool — one reusable team per name, no allocation per send
//
// R4: sendTeamRemove() calls packetScoreboard.removePlayerTeam() to clean
// the scoreboard's internal nameMap. Without this every evicted team leaks.
// ---------------------------------------------------------------------------

private val packetScoreboard = Scoreboard()
private val teamPool: ConcurrentHashMap<String, PlayerTeam> = ConcurrentHashMap(64)

// Static defaults applied once at construction — suffix/visibility/collision never change.
internal fun getOrCreateTeam(name: String): PlayerTeam = teamPool.getOrPut(name) {
    PlayerTeam(packetScoreboard, name).also { team ->
        TeamFieldSetters.setSuffix.invoke(team, Component.empty())
        TeamFieldSetters.setNameTagVis.invoke(team, Team.Visibility.ALWAYS)
        TeamFieldSetters.setCollisionRule.invoke(team, Team.CollisionRule.ALWAYS)
    }
}

// Apply only prefix + color — called on every send
internal fun applyTeamPrefix(team: PlayerTeam, prefixComponent: Component, color: ChatFormatting) {
    TeamFieldSetters.setPrefix.invoke(team, prefixComponent)
    TeamFieldSetters.setColor.invoke(team, color)
}

// ---------------------------------------------------------------------------
// Component cache — zero allocation in the hot path
// ---------------------------------------------------------------------------

private val componentCache: ConcurrentHashMap<String, Component> = ConcurrentHashMap(64)

public fun buildNametagComponent(prefix: String, color: ChatFormatting): Component = componentCache.getOrPut("$prefix\u0000${color.name}") {
    Component.literal(prefix).withStyle(color)
}

// ---------------------------------------------------------------------------
// Entity ID counter — atomic, collision-free, no SecureRandom
// ---------------------------------------------------------------------------

private val fakeEntityIdCounter = AtomicInteger(0x00F00000)
private fun nextFakeEntityId(): Int = fakeEntityIdCounter.getAndIncrement()
private fun fakeEntityUUID(id: Int): UUID = UUID(-410390516044136448L, id.toLong())
private val DUMMY_SCOREBOARD = Scoreboard()

// ----------------------------------------------------------
// -----------------
// Public NMS API
// ---------------------------------------------------------------------------

public fun ArmorStand.createArmorStandNamePacket(name: String): NMSPacketSetEntityData {
    val nameComponent = Optional.of(Component.literal(name) as Component)
    val dataValues = ArrayList<NMSSynchedEntityData.DataValue<*>>()
    dataValues.add(NMSSynchedEntityData.DataValue(2, NMSEntityDataSerializers.OPTIONAL_COMPONENT, nameComponent))
    dataValues.add(NMSSynchedEntityData.DataValue(3, NMSEntityDataSerializers.BOOLEAN, true))
    return ClientboundSetEntityDataPacket(this.entityId, dataValues)
}

// R1: accepts pre-built Component — zero allocation per send
// R2: never touches team.players — members sent via separate addPlayers packet
public fun Player.sendTeamCreate(
    teamName: String,
    prefixComponent: Component,
    color: ChatFormatting = ChatFormatting.RESET,
) {
    val team = getOrCreateTeam(teamName)
    applyTeamPrefix(team, prefixComponent, color)
    this.sendPacket(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true))
}

// Convenience overload for non-hot call-sites
public fun Player.sendTeamCreate(
    teamName: String,
    prefix: String,
    color: ChatFormatting = ChatFormatting.RESET,
) = sendTeamCreate(teamName, buildNametagComponent(prefix, color), color)

// Mode 2 (MODIFY) — does not touch client member list
public fun Player.sendTeamUpdatePrefix(
    teamName: String,
    prefixComponent: Component,
    color: ChatFormatting = ChatFormatting.RESET,
) {
    val team = getOrCreateTeam(teamName)
    applyTeamPrefix(team, prefixComponent, color)
    this.sendPacket(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false))
}

public fun Player.sendTeamUpdatePrefix(
    teamName: String,
    prefix: String,
    color: ChatFormatting = ChatFormatting.RESET,
) = sendTeamUpdatePrefix(teamName, buildNametagComponent(prefix, color), color)

// R4: removePlayerTeam cleans scoreboard's internal nameMap — prevents leak.
public fun Player.sendTeamRemove(teamName: String) {
    val team = teamPool[teamName] ?: PlayerTeam(packetScoreboard, teamName)
    this.sendPacket(ClientboundSetPlayerTeamPacket.createRemovePacket(team))
    try {
        packetScoreboard.removePlayerTeam(team)
    } catch (_: Exception) {}
}

public fun Player.sendTeamAddPlayers(teamName: String, members: Collection<String>) {
    val team = getOrCreateTeam(teamName)
    this.sendPacket(
        ClientboundSetPlayerTeamPacket.createMultiplePlayerPacket(
            team,
            members,
            ClientboundSetPlayerTeamPacket.Action.ADD,
        ),
    )
}

public fun Player.sendTeamRemovePlayers(teamName: String, members: Collection<String>) {
    val team = getOrCreateTeam(teamName)
    this.sendPacket(
        ClientboundSetPlayerTeamPacket.createMultiplePlayerPacket(
            team,
            members,
            ClientboundSetPlayerTeamPacket.Action.REMOVE,
        ),
    )
}

// ---------------------------------------------------------------------------
// Objective / scoreboard helpers
// ---------------------------------------------------------------------------

public fun Player.sendObjectiveCreate(objectiveName: String, displayName: String) = this.sendPacket(
    ClientboundSetObjectivePacket(
        Objective(
            DUMMY_SCOREBOARD,
            objectiveName,
            ObjectiveCriteria.DUMMY,
            Component.literal(displayName),
            ObjectiveCriteria.RenderType.INTEGER,
            false,
            BlankFormat.INSTANCE,
        ),
        0,
    ),
)

public fun Player.sendObjectiveRemove(objectiveName: String) = this.sendPacket(
    ClientboundSetObjectivePacket(
        Objective(
            DUMMY_SCOREBOARD,
            objectiveName,
            ObjectiveCriteria.DUMMY,
            Component.literal(""),
            ObjectiveCriteria.RenderType.INTEGER,
            false,
            BlankFormat.INSTANCE,
        ),
        1,
    ),
)

public fun Player.sendObjectiveDisplay(objectiveName: String, slot: DisplaySlot) {
    val nmsSlot = when (slot) {
        DisplaySlot.SIDEBAR -> net.minecraft.world.scores.DisplaySlot.SIDEBAR
        DisplaySlot.PLAYER_LIST -> net.minecraft.world.scores.DisplaySlot.LIST
        DisplaySlot.BELOW_NAME -> net.minecraft.world.scores.DisplaySlot.BELOW_NAME
        else -> net.minecraft.world.scores.DisplaySlot.SIDEBAR
    }
    val objective = if (objectiveName.isEmpty()) {
        null
    } else {
        Objective(
            DUMMY_SCOREBOARD,
            objectiveName,
            ObjectiveCriteria.DUMMY,
            Component.literal(""),
            ObjectiveCriteria.RenderType.INTEGER,
            false,
            BlankFormat.INSTANCE,
        )
    }
    this.sendPacket(ClientboundSetDisplayObjectivePacket(nmsSlot, objective))
}

public fun Player.sendScore(objectiveName: String, entryName: String, score: Int) = this.sendPacket(ClientboundSetScorePacket(entryName, objectiveName, score, Optional.empty(), Optional.empty()))
