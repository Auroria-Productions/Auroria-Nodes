package phonon.nodes.objects

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.nms.clearSendFailureLog
import phonon.nodes.nms.sendTeamAddPlayers
import phonon.nodes.nms.sendTeamCreate
import phonon.nodes.nms.sendTeamRemove
import phonon.nodes.nms.sendTeamUpdatePrefix
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Relation enum
// ---------------------------------------------------------------------------

enum class NametagRelation { TOWN, NATION, ALLY, ENEMY, NEUTRAL }

private fun townRelationForViewer(town: Town, viewer: Player): NametagRelation {
    val otherTown = Nodes.getResident(viewer)?.town ?: return NametagRelation.NEUTRAL
    return when {
        town === otherTown -> NametagRelation.TOWN
        town.nation != null && town.nation === otherTown.nation -> NametagRelation.NATION
        town.allies.contains(otherTown) || otherTown.allies.contains(town) -> NametagRelation.ALLY
        town.enemies.contains(otherTown) -> NametagRelation.ENEMY
        else -> NametagRelation.NEUTRAL
    }
}

// ---------------------------------------------------------------------------
// Colour override system
//
// Each Town ships with pre-built Components (nametagTownComponent,
// nametagNationComponent, ...) whose colours are baked in by the Town class.
// The default build uses WHITE for NATION, which is indistinguishable from
// the default NEUTRAL colour — two players in different relations end up
// looking identical on-screen.
//
// Rather than edit Town.kt (not owned by this file), we override colours
// here: for each relation, if Config provides a non-empty override, the
// base component's plain text is re-wrapped with the override colour.
//
// Empty override → use the Component as provided by the Town (original
// behaviour). Unknown colour name → treated as empty (ignored silently).
//
// The rebuilt components are cached per (text + colour) pair so repeated
// calls don't allocate.
// ---------------------------------------------------------------------------

private val overrideCache: ConcurrentHashMap<String, Component> = ConcurrentHashMap(128)

// Soft cap on override cache size. Every (townName × colourName) pair creates
// an entry. On rename, the old entry is orphaned (still keyed by old name).
// In steady state this is bounded by ~towns × relations; we defensively clear
// the whole cache above this threshold to prevent accumulation across many
// rename/recolor events over a long server uptime.
private const val OVERRIDE_CACHE_SOFT_CAP = 4096

private fun parseChatColor(name: String): ChatFormatting? {
    if (name.isEmpty()) return null
    return try {
        ChatFormatting.valueOf(name.trim().uppercase())
    } catch (_: Exception) {
        null
    }
}

private fun overrideFor(rel: NametagRelation): ChatFormatting? = parseChatColor(
    when (rel) {
        NametagRelation.TOWN -> Config.nametagTownColor
        NametagRelation.NATION -> Config.nametagNationColor
        NametagRelation.ALLY -> Config.nametagAllyColor
        NametagRelation.ENEMY -> Config.nametagEnemyColor
        NametagRelation.NEUTRAL -> Config.nametagNeutralColor
    },
)

private fun Town.prefixComponentForRelation(rel: NametagRelation): Component {
    val base: Component = when (rel) {
        NametagRelation.TOWN -> nametagTownComponent
        NametagRelation.NATION -> nametagNationComponent
        NametagRelation.ALLY -> nametagAllyComponent
        NametagRelation.ENEMY -> nametagEnemyComponent
        NametagRelation.NEUTRAL -> nametagNeutralComponent
    }
    val override = overrideFor(rel) ?: return base
    val plainText = base.string // Component.getString() — concatenated style-free text
    val cacheKey = "$plainText\u0000${override.name}"
    if (overrideCache.size >= OVERRIDE_CACHE_SOFT_CAP) overrideCache.clear()
    return overrideCache.getOrPut(cacheKey) {
        Component.literal(plainText).withStyle(override)
    }
}

/** Call whenever a town's prefix text might have changed (rename, etc). */
internal fun clearOverrideCache() = overrideCache.clear()

// ---------------------------------------------------------------------------
// Nametag singleton
//
// Replaces the two-tier priority/burst NametagPipeline with a single
// dirty-viewer set plus a per-tick drain. The drain task is managed by
// Nametag.start(plugin) — AND is auto-started on first dirty mark, so
// forgetting to wire it into onEnable doesn't silently break updates.
//
// Semantics the call-sites rely on:
//
//   markPriorityAndFlush(player)  — immediate, synchronous refresh of that
//                                   player's view. Used when a player
//                                   transitions town membership (join/leave)
//                                   and needs instant visual feedback.
//
//   markBurstAndFlush(players)    — queue for the next tick drain. Up to
//                                   ~1s latency acceptable.
//
//   refresh(player) / refreshAll() — queue only (no synchronous work).
//
// Town destruction uses broadcastTeamRemove() to guarantee the client drops
// the dead team — without it, the team sticks forever in the scoreboard.
// ---------------------------------------------------------------------------

public object Nametag {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private val dirtyViewers: MutableSet<UUID> = ConcurrentHashMap.newKeySet(128)

    private val pendingRemoves: ConcurrentHashMap<UUID, MutableSet<String>> =
        ConcurrentHashMap(128)

    private val relationCache: ConcurrentHashMap<UUID, HashMap<String, NametagRelation>> =
        ConcurrentHashMap(128)

    @Volatile private var teamMembersSnapshot: Map<String, List<String>> = emptyMap()

    private val lastNearbyTeams: ConcurrentHashMap<UUID, Set<String>> = ConcurrentHashMap(128)

    private val started = AtomicBoolean(false)

    // -----------------------------------------------------------------------
    // Snapshot management
    // -----------------------------------------------------------------------

    public fun rebuildTeamSnapshot() {
        val map = HashMap<String, MutableList<String>>(Nodes.towns.size * 2)
        for (player in Bukkit.getOnlinePlayers()) {
            val town = Nodes.getTownFromPlayer(player) ?: continue
            map.getOrPut("t${town.townNametagId}") { mutableListOf() }.add(player.name)
        }
        teamMembersSnapshot = map.mapValues { it.value.toList() }
    }

    public fun snapshotAddPlayer(player: Player) {
        val town = Nodes.getTownFromPlayer(player) ?: return
        val teamId = "t${town.townNametagId}"
        val current = teamMembersSnapshot.toMutableMap()
        current[teamId] = (current[teamId] ?: emptyList()) + player.name
        teamMembersSnapshot = current
    }

    private fun snapshotRemovePlayer(player: Player) {
        val town = Nodes.getTownFromPlayer(player) ?: return
        val teamId = "t${town.townNametagId}"
        val current = teamMembersSnapshot.toMutableMap()
        val members = current[teamId] ?: return
        val updated = members - player.name
        if (updated.isEmpty()) current.remove(teamId) else current[teamId] = updated
        teamMembersSnapshot = current
    }

    // -----------------------------------------------------------------------
    // Cache invalidation
    // -----------------------------------------------------------------------

    public fun invalidateViewerCache(player: Player) {
        relationCache.remove(player.uniqueId)
        lastNearbyTeams.remove(player.uniqueId)
    }

    public fun invalidateTeamInAllCaches(teamId: String) {
        for (cache in relationCache.values) cache.remove(teamId)
    }

    public fun invalidateTeamsInAllCaches(teamIds: Collection<String>) {
        if (teamIds.isEmpty()) return
        for (cache in relationCache.values) {
            for (teamId in teamIds) cache.remove(teamId)
        }
    }

    public fun invalidateAllCaches() {
        relationCache.clear()
        lastNearbyTeams.clear()
    }

    // -----------------------------------------------------------------------
    // Broadcast team remove — call on town destroy
    // -----------------------------------------------------------------------

    public fun broadcastTeamRemove(teamId: String) {
        for (player in Bukkit.getOnlinePlayers()) {
            pendingRemoves.getOrPut(player.uniqueId) { HashSet(4) }.add(teamId)
            relationCache[player.uniqueId]?.remove(teamId)
            dirtyViewers.add(player.uniqueId)
        }
        ensureStarted()
    }

    public fun broadcastTeamRemove(teamIds: Collection<String>) {
        if (teamIds.isEmpty()) return
        for (teamId in teamIds) broadcastTeamRemove(teamId)
    }

    // -----------------------------------------------------------------------
    // Dirty API
    // -----------------------------------------------------------------------

    public fun refresh(player: Player) {
        dirtyViewers.add(player.uniqueId)
        ensureStarted()
    }

    public fun refresh(players: Iterable<Player>) {
        for (p in players) dirtyViewers.add(p.uniqueId)
        ensureStarted()
    }

    public fun refreshAll() {
        for (p in Bukkit.getOnlinePlayers()) dirtyViewers.add(p.uniqueId)
        ensureStarted()
    }

    /**
     * Immediate synchronous refresh — runs updateTextForPlayer NOW on the
     * calling thread. Use when the player just performed a town transition
     * and needs to see the colour change on their own client within the
     * same frame (e.g. /town join, /town leave).
     */
    public fun refreshNow(player: Player) {
        // Remove from dirty queue since we're about to process inline.
        // Keep pendingRemoves as-is: updateTextForPlayer consumes them.
        dirtyViewers.remove(player.uniqueId)
        try {
            updateTextForPlayer(player)
        } catch (_: Exception) {}
    }

    public val queuedViewers: Int get() = dirtyViewers.size

    // -----------------------------------------------------------------------
    // Proximity filter
    // -----------------------------------------------------------------------

    private fun buildNearbyTeamIds(viewer: Player): Set<String> {
        val radiusSq = Config.nametagProximityRadiusSquared
        val result = HashSet<String>(32)
        for (other in Bukkit.getOnlinePlayers()) {
            if (other.uniqueId == viewer.uniqueId) continue
            if (other.world != viewer.world) continue
            if (viewer.location.distanceSquared(other.location) > radiusSq) continue
            val town = Nodes.getTownFromPlayer(other) ?: continue
            result.add("t${town.townNametagId}")
        }
        return result
    }

    public fun tickProximityChanges() {
        if (!Config.nametagProximityEnabled) return
        for (player in Bukkit.getOnlinePlayers()) {
            val current = buildNearbyTeamIds(player)
            val previous = lastNearbyTeams[player.uniqueId]
            if (previous == null || current != previous) {
                lastNearbyTeams[player.uniqueId] = current
                dirtyViewers.add(player.uniqueId)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Core worker
    // -----------------------------------------------------------------------

    private fun updateTextForPlayer(player: Player) {
        if (!phonon.nodes.core.CoreHooks.nametags()) return
        val viewerUuid = player.uniqueId
        val cache = relationCache.getOrPut(viewerUuid) { HashMap(Nodes.towns.size * 2) }

        // 1. Process pending removes first (town-destroy broadcasts).
        val removes = pendingRemoves.remove(viewerUuid)
        if (removes != null) {
            for (teamId in removes) {
                try {
                    player.sendTeamRemove(teamId)
                } catch (_: Exception) {}
                cache.remove(teamId)
            }
        }

        // 2. Build proximity filter if enabled.
        val nearbyTeamIds: Set<String>? = if (Config.nametagProximityEnabled) {
            buildNearbyTeamIds(player).also { lastNearbyTeams[viewerUuid] = it }
        } else {
            null
        }

        // 3. Classify every town: create / update / no-op.
        val teamsNeedingCreate = mutableListOf<Pair<String, Town>>()
        val teamsNeedingPrefix = mutableListOf<Pair<String, Town>>()
        val newRelations = HashMap<String, NametagRelation>(Nodes.towns.size * 2)

        for (town in Nodes.towns.values) {
            val teamId = "t${town.townNametagId}"
            if (nearbyTeamIds != null && !nearbyTeamIds.contains(teamId)) continue
            val newRel = townRelationForViewer(town, player)
            newRelations[teamId] = newRel
            when (cache[teamId]) {
                null -> teamsNeedingCreate.add(teamId to town)
                newRel -> { /* unchanged */ }
                else -> teamsNeedingPrefix.add(teamId to town)
            }
        }

        // 4. Remove stale teams (dissolved / proximity drop-outs).
        val teamsNeedingRemove = ArrayList<String>(4)
        for (oldTeamId in cache.keys) {
            if (!newRelations.containsKey(oldTeamId)) teamsNeedingRemove.add(oldTeamId)
        }
        for (teamId in teamsNeedingRemove) {
            try {
                player.sendTeamRemove(teamId)
            } catch (_: Exception) {}
            cache.remove(teamId)
        }

        // 5. Create (with leading REMOVE to reset stale client-side member lists).
        //    First-ever create: REMOVE is a no-op on the client.
        //    Re-create after invalidateTeamInAllCaches: REMOVE clears the
        //    stale member list so someone who left a town doesn't stick in
        //    the team on the client.
        for ((teamId, town) in teamsNeedingCreate) {
            val rel = newRelations[teamId] ?: continue
            try {
                player.sendTeamRemove(teamId)
                player.sendTeamCreate(teamId, town.prefixComponentForRelation(rel))
                cache[teamId] = rel
            } catch (_: Exception) {}
        }

        // 6. Prefix updates (relation changed, team still on client).
        for ((teamId, town) in teamsNeedingPrefix) {
            val rel = newRelations[teamId] ?: continue
            try {
                player.sendTeamUpdatePrefix(teamId, town.prefixComponentForRelation(rel))
                cache[teamId] = rel
            } catch (_: Exception) {
                try {
                    player.sendTeamRemove(teamId)
                    player.sendTeamCreate(teamId, town.prefixComponentForRelation(rel))
                    cache[teamId] = rel
                } catch (_: Exception) {}
            }
        }

        // 7. Push member lists for freshly-created teams.
        if (teamsNeedingCreate.isNotEmpty()) {
            val snapshot = teamMembersSnapshot
            for ((teamId, _) in teamsNeedingCreate) {
                val members = snapshot[teamId] ?: continue
                if (members.isNotEmpty()) {
                    try {
                        player.sendTeamAddPlayers(teamId, members)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tick — drain up to Config.nametagUpdatesPerTick dirty viewers
    // -----------------------------------------------------------------------

    public fun tick() {
        val max = Config.nametagUpdatesPerTick.coerceAtLeast(1)
        var processed = 0
        val iter = dirtyViewers.iterator()
        while (iter.hasNext() && processed < max) {
            val uuid = iter.next()
            iter.remove()
            processed++
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (!player.isOnline) continue
            try {
                updateTextForPlayer(player)
            } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public fun onPlayerJoin(joining: Player) {
        snapshotAddPlayer(joining)
        dirtyViewers.add(joining.uniqueId)
        ensureStarted()

        val town = Nodes.getTownFromPlayer(joining) ?: return
        val teamId = "t${town.townNametagId}"
        val list = listOf(joining.name)
        val proximityEnabled = Config.nametagProximityEnabled
        val radiusSq = Config.nametagProximityRadiusSquared

        for (existing in Bukkit.getOnlinePlayers()) {
            if (existing.uniqueId == joining.uniqueId) continue
            val cache = relationCache[existing.uniqueId] ?: continue
            if (!cache.containsKey(teamId)) continue
            if (proximityEnabled) {
                if (existing.world != joining.world) continue
                if (existing.location.distanceSquared(joining.location) > radiusSq) continue
            }
            try {
                existing.sendTeamAddPlayers(teamId, list)
            } catch (_: Exception) {}
        }
    }

    public fun onPlayerQuit(quitting: Player) {
        relationCache.remove(quitting.uniqueId)
        dirtyViewers.remove(quitting.uniqueId)
        lastNearbyTeams.remove(quitting.uniqueId)
        pendingRemoves.remove(quitting.uniqueId)
        snapshotRemovePlayer(quitting)
        clearSendFailureLog(quitting.uniqueId)
    }

    // -----------------------------------------------------------------------
    // Scheduler wiring
    // -----------------------------------------------------------------------

    public fun start(plugin: Plugin) {
        if (!started.compareAndSet(false, true)) return
        startTasks(plugin)
    }

    /**
     * Auto-start fallback. If start(plugin) was never called explicitly,
     * we pick up the current Nodes.plugin reference the first time something
     * marks a viewer dirty, so updates never silently queue up with nothing
     * draining them.
     */
    private fun ensureStarted() {
        if (started.get()) return
        val plugin = Nodes.plugin ?: return // plugin not initialised yet — defer
        if (started.compareAndSet(false, true)) startTasks(plugin)
    }

    private fun startTasks(plugin: Plugin) {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { _ -> tick() },
            1L,
            1L,
        )
        val proxPeriod = Config.nametagProximityRefreshTicks.coerceAtLeast(20L)
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { _ -> tickProximityChanges() },
            proxPeriod,
            proxPeriod,
        )
    }

    public fun stop() {
        dirtyViewers.clear()
        pendingRemoves.clear()
        relationCache.clear()
        lastNearbyTeams.clear()
        teamMembersSnapshot = emptyMap()
        clearOverrideCache()
        started.set(false)
    }

    // -----------------------------------------------------------------------
    // Back-compat shims
    //
    // Priority = immediate synchronous update (for the affected player).
    // Burst    = queued for the per-tick drain (for third-party viewers).
    // flushXxx()  = no-ops (drain runs every tick + priority runs inline).
    // -----------------------------------------------------------------------

    public fun markPriority(player: Player) = refreshNow(player)
    public fun markPriority(players: Iterable<Player>) {
        for (p in players) refreshNow(p)
    }
    public fun markPriorityAll() {
        for (p in Bukkit.getOnlinePlayers()) refreshNow(p)
    }

    public fun markBurst(player: Player) = refresh(player)
    public fun markBurst(players: Iterable<Player>) = refresh(players)
    public fun markBurstAll() = refreshAll()

    public fun flushPriority() {}
    public fun flushBurst() {}

    public fun markPriorityAndFlush(player: Player) = refreshNow(player)
    public fun markPriorityAndFlush(players: Iterable<Player>) {
        for (p in players) refreshNow(p)
    }
    public fun markBurstAndFlush(player: Player) = refresh(player)
    public fun markBurstAndFlush(players: Iterable<Player>) = refresh(players)

    public fun pipelinedUpdateAllText() = refreshAll()
    public fun pipelinedUpdateAllTextFull() = refreshAll()
}
