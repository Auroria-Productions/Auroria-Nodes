/**
 * Fast set blocks
 * Because default world.setBlock or block.setType is very slow (lighting + packets)
 *
 * See: https://www.spigotmc.org/threads/how-to-set-blocks-incredibly-fast.476097/
 *
 * Packet dispatch has been migrated from the custom MethodHandle-based PacketSender
 * (Nms.kt) to PacketEvents' channel abstraction. Block mutation, lighting, and chunk
 * packet construction are still NMS — PacketEvents has no substitute for those.
 *
 * PacketEvents replaces:
 *   - `p.sendPacket(packetChunk)` → `PacketEvents channel.writeAndFlush(packetChunk)`
 *
 * Still NMS (no PacketEvents equivalent):
 *   - CraftWorld.getHandle() / world.getChunkSource()
 *   - LevelChunk.setBlockState()
 *   - ServerLightingProvider.checkBlock()
 *   - ClientboundLevelChunkWithLightPacket construction
 */
package phonon.nms.blockedit

import com.github.retrooper.packetevents.PacketEvents
import io.netty.channel.Channel
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import phonon.nodes.nms.CraftMagicNumbers
import phonon.nodes.nms.CraftWorld
import phonon.nodes.nms.NMSBlockPos
import phonon.nodes.nms.NMSBlockState
import phonon.nodes.nms.NMSChunk
import phonon.nodes.nms.NMSPacketLevelChunkWithLightPacket
import java.util.HashMap
import java.util.HashSet

public class FastBlockEditSession(
    val bukkitWorld: org.bukkit.World,
) {
    // nms world — still required; no PacketEvents equivalent
    private val world = (bukkitWorld as CraftWorld).getHandle()

    // blocks that were modified in this session
    private val modified: HashMap<NMSBlockPos, NMSBlockState> = hashMapOf()

    /**
     * Mark a block as updated to a new material. Lazy — world is not touched
     * until `execute` is called.
     */
    public fun setBlock(x: Int, y: Int, z: Int, material: Material) {
        modified.put(NMSBlockPos(x, y, z), CraftMagicNumbers.getBlock(material).defaultBlockState())
    }

    /**
     * Get material of block at (x, y, z), consulting pending session changes first.
     */
    public fun getBlock(x: Int, y: Int, z: Int): Material {
        val bData = modified.get(NMSBlockPos(x, y, z))
        if (bData != null) {
            return CraftMagicNumbers.getMaterial(bData).getItemType()
        }
        return Location(bukkitWorld, x.toDouble(), y.toDouble(), z.toDouble()).getBlock().getType()
    }

    /**
     * Apply all queued block changes and push chunk updates to players in
     * view distance via PacketEvents' Netty channel.
     *
     * Chunk source api:
     * https://nms.screamingsandals.org/1.18.2/net/minecraft/server/level/ServerChunkCache.html
     */
    public fun execute(updateLighting: Boolean) {
        // 1. Mutate block state in memory — still NMS, no PE equivalent
        val chunks: HashSet<NMSChunk> = hashSetOf()
        for ((bPos, bState) in modified) {
            val chunk = world.getChunkSource().getChunk(bPos.getX() shr 4, bPos.getZ() shr 4, true)
            if (chunk !== null) {
                chunks.add(chunk)
                chunk.setBlockState(bPos, bState, 0)
            }
        }

        // 2. Schedule lighting recalculation — still NMS, no PE equivalent
        val lightingEngine = world.getChunkSource().getLightEngine()
        if (updateLighting) {
            for (pos in modified.keys) {
                lightingEngine.checkBlock(pos)
            }
        }

        // 3. Build chunk packets and dispatch via PacketEvents channel.
        //
        // PacketEvents.getAPI().getPlayerManager().getChannel() returns the
        // underlying Object (Netty Channel) that PacketEvents injected into on
        // player login. Casting to io.netty.channel.Channel is safe as long as
        // the server is running Netty (all Paper/Spigot versions do).
        //
        // writeAndFlush() serialises packetChunk through the existing Netty
        // pipeline — encoder, compressor, cipher — exactly as a normal send
        // would. The NMS `connection.send()` path does the same thing, so
        // behaviour is identical from the client's perspective.
        for (chunk in chunks) {
            val packetChunk = NMSPacketLevelChunkWithLightPacket(
                chunk,
                chunk.getLevel().getLightEngine(),
                null,
                null,
                true,
            )

            val dist = Bukkit.getViewDistance() + 1

            for (p in Bukkit.getOnlinePlayers()) {
                val playerChunk = p.location.chunk
                if (chunk.getPos().x < playerChunk.x - dist ||
                    chunk.getPos().x > playerChunk.x + dist ||
                    chunk.getPos().z < playerChunk.z - dist ||
                    chunk.getPos().z > playerChunk.z + dist
                ) {
                    continue
                }

                // PacketEvents manages a Netty Channel per player from login.
                // Cast to Channel is safe: Paper always uses Netty under the hood.
                val channel = PacketEvents.getAPI().getPlayerManager().getChannel(p) as? Channel
                if (channel == null || !channel.isActive) continue
                channel.writeAndFlush(packetChunk)
            }
        }
    }
}
