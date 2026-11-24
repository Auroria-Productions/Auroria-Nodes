/**
 * Instance for attacking a chunk
 * - holds state data of attack
 * - functions as runnable thread for attack tick
 */

package phonon.nodes.war

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.boss.BossBar
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Town
import java.util.UUID

public class Attack(
    val attacker: UUID,
    val town: Town,
    val coord: Coord,
    val flagBase: Block,
    val flagBlock: Block,
    val flagTorch: Block,
    val progressBar: BossBar,
    val attackTime: Long,
    var progress: Long,
) : Runnable {
    // no build region
    val noBuildXMin: Int
    val noBuildXMax: Int
    val noBuildZMin: Int
    val noBuildZMax: Int
    val noBuildYMin: Int
    val noBuildYMax: Int = 255

    var thread: ScheduledTask

    // text display used to show town name and progress on flag
    val textDisplay = AttackTextDisplay(this, flagTorch.world, flagTorch.location.clone().add(0.5, 0.85, 0.5))

    // re-used json serialization StringBuilders
    val jsonStringBase: StringBuilder
    val jsonString: StringBuilder

    init {
        val flagX = flagBase.x
        val flagY = flagBase.y
        val flagZ = flagBase.z

        this.noBuildXMin = flagX - Config.flagNoBuildDistance
        this.noBuildXMax = flagX + Config.flagNoBuildDistance
        this.noBuildZMin = flagZ - Config.flagNoBuildDistance
        this.noBuildZMax = flagZ + Config.flagNoBuildDistance
        this.noBuildYMin = flagY + Config.flagNoBuildYOffset

        val progressNormalized = normalizedBossBarProgress(this.progress, this.attackTime)
        this.progressBar.setProgress(progressNormalized)

        this.jsonStringBase = generateFixedJsonBase(
            this.attacker,
            this.coord,
            this.flagBase,
        )

        try {
            this.textDisplay.sendPackets()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val jsonStringBufferSize = this.jsonStringBase.capacity() + 20
        this.jsonString = StringBuilder(jsonStringBufferSize)

        this.thread = Bukkit.getRegionScheduler().runAtFixedRate(
            Nodes.plugin!!,
            this.flagBase.world,
            this.flagBase.x shr 4,
            this.flagBase.z shr 4,
            { _ -> this.run() },
            FlagWar.ATTACK_TICK,
            FlagWar.ATTACK_TICK,
        )
    }

    public override fun run() {
        FlagWar.attackTick(this)
    }

    public fun cancel() {
        this.thread.cancel()
        FlagWar.cancelAttack(this)
    }

    public fun toJson(): StringBuilder {
        this.jsonString.setLength(0)
        this.jsonString.append(this.jsonStringBase)
        this.jsonString.append("\"p\":${this.progress}")
        this.jsonString.append("}")
        return this.jsonString
    }
}

private fun normalizedBossBarProgress(progress: Long, attackTime: Long): Double {
    if (attackTime <= 0L) return 0.0
    val normalized = progress.toDouble() / attackTime.toDouble()
    if (!normalized.isFinite()) return 0.0
    return normalized.coerceIn(0.0, 1.0)
}

private fun generateFixedJsonBase(
    attacker: UUID,
    coord: Coord,
    block: Block,
): StringBuilder {
    val s = StringBuilder()
    s.append("{")
    s.append("\"id\":\"$attacker\",")
    s.append("\"c\":[${coord.x},${coord.z}],")
    s.append("\"b\":[${block.x},${block.y},${block.z}],")
    return s
}

public class AttackTextDisplay(
    val attack: Attack,
    val world: World,
    val loc: Location,
) {
    var display = createTextDisplay(world, loc)

    public fun remove() {
        this.display.remove()
    }

    public fun isValid(): Boolean = this.display.isValid

    public fun respawn() {
        if (!phonon.nodes.core.CoreHooks.respawn()) return
        this.display.remove()
        this.display = createTextDisplay(this.world, this.loc)
        sendPackets()
    }

    public fun sendPackets() {
        if (!this.world.isChunkLoaded(this.loc.chunk)) {
            return
        }

        val remainingSeconds = (attack.attackTime - attack.progress).coerceAtLeast(0L) / 20L
        val minutes = remainingSeconds / 60L
        val seconds = remainingSeconds % 60L

        this.display.text(
            Component.text()
                .append(Component.text(attack.town.name).color(NamedTextColor.GOLD))
                .append(Component.newline())
                .append(Component.text("[$minutes:${seconds.toString().padStart(2, '0')}]").color(NamedTextColor.RED))
                .build(),
        )
    }
}

internal val NODES_WAR_DISPLAY_KEY = NamespacedKey("nodes", "war_display")
internal val NODES_ARMORSTAND_KEY = NamespacedKey("nodes", "armorstand")

private fun createTextDisplay(
    world: World,
    loc: Location,
): TextDisplay {
    val textDisplay = world.spawn(loc, TextDisplay::class.java)
    textDisplay.billboard = Display.Billboard.CENTER
    textDisplay.isDefaultBackground = false
    textDisplay.isSeeThrough = true
    textDisplay.isShadowed = true
    textDisplay.lineWidth = 240
    textDisplay.teleportDuration = 0
    textDisplay.interpolationDuration = 0
    textDisplay.isInvulnerable = true
    textDisplay.isGlowing = true
    textDisplay.persistentDataContainer.set(NODES_WAR_DISPLAY_KEY, PersistentDataType.INTEGER, 0)
    return textDisplay
}
