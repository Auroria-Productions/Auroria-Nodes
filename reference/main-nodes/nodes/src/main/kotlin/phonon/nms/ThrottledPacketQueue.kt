package phonon.nodes.nms

import com.github.retrooper.packetevents.PacketEvents
import io.netty.channel.Channel
import org.bukkit.entity.Player
import net.minecraft.network.protocol.Packet as NMSPacket

// ---------------------------------------------------------------------------
// Channel accessor — via PacketEvents
//
// PacketEvents injects into each player's Netty pipeline at login and exposes
// the same Channel directly, removing the need for the old ChannelAccessor
// MethodHandle chain (NMSPlayer.connection → Connection.channel).
//
// Must only be called on the main server thread — getChannel() is not
// documented as thread-safe for Spigot players.
// ---------------------------------------------------------------------------

private fun playerChannel(player: Player): Channel? {
    val ch = PacketEvents.getAPI().getPlayerManager().getChannel(player) as? Channel
    return if (ch != null && ch.isActive) ch else null
}

// ---------------------------------------------------------------------------
// ThrottledPacketQueue
//
// Generic utility: enqueue (Player, Packet) pairs and drain at most
// [drainPerTick] jobs per call to drain(). Call drain() once per tick from
// your plugin scheduler.
//
// Grouping: all packets for the same player within one drain window are
// batched into a single Netty eventLoop().execute { write × N; flush × 1 }
// task. The main thread only allocates lambdas; serialisation, compression
// and encryption run on the I/O thread.
//
// Previously the codebase also shipped high-level helpers on top of this
// queue (enqueueTeamPrefixUpdate, spreadTeamPrefixUpdate, RoundRobinDispatcher,
// TeamUpdateDiffCache). They were built for the old two-tier nametag
// pipeline which has been replaced by a single-stage dirty queue inside
// Nametag itself (see Nametag.kt). None of those helpers had a caller in
// the codebase and they are removed to reduce surface area. Restore them
// if you write code that needs generic packet-level throttling; for
// nametags specifically, use Nametag.refresh() / refreshAll().
//
// Thread safety: enqueue() and drain() must both run on the main server
// thread. The netty execute {} lambda runs on the I/O thread but only
// touches immutable packet objects and the channel itself.
// ---------------------------------------------------------------------------

class ThrottledPacketQueue(val drainPerTick: Int = 600) {

    private data class PacketJob(val player: Player, val packet: NMSPacket<*>)

    // 2048 initial capacity avoids ArrayDeque resize on worst-case bulk events.
    private val queue: ArrayDeque<PacketJob> = ArrayDeque(2048)

    // Reused across drain() calls; cleared at the start of each drain.
    private val drainBuffer = LinkedHashMap<Player, MutableList<NMSPacket<*>>>(64)

    /** Number of jobs currently waiting in the queue. */
    val size: Int get() = queue.size

    /** Enqueue a packet to be sent to [player]. Cheap: one allocation + deque append. */
    fun enqueue(player: Player, packet: NMSPacket<*>) {
        queue.addLast(PacketJob(player, packet))
    }

    /**
     * Process up to [drainPerTick] queued jobs this tick.
     *
     * Groups packets by player, then submits one Netty event-loop task per
     * player: write all their packets then flush once.
     *
     * Returns the number of jobs dequeued (not necessarily sent — offline
     * players are dequeued but discarded).
     */
    fun drain(): Int {
        var count = 0
        drainBuffer.clear()

        while (count < drainPerTick) {
            val job = queue.removeFirstOrNull() ?: break
            count++
            if (!job.player.isOnline) continue
            drainBuffer.getOrPut(job.player) { mutableListOf() }.add(job.packet)
        }

        for ((player, packets) in drainBuffer) {
            val ch = playerChannel(player) ?: continue // channel gone — discard silently
            ch.eventLoop().execute {
                for (p in packets) ch.write(p)
                ch.flush()
            }
        }

        return count
    }

    /** Drop all pending jobs — call on plugin disable or world reset. */
    fun clear() {
        queue.clear()
        drainBuffer.clear()
    }
}
