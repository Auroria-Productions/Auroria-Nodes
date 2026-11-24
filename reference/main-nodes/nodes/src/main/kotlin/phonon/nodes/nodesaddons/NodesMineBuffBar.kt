package phonon.nodes.nodesaddons

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import java.util.UUID

private enum class MineBuffBarType {
    GLOBAL_RATE,
    GLOBAL_AMOUNT,
    TOWN_RATE,
    TOWN_AMOUNT,
}

object NodesMineBuffBar {
    private val bossBars: MutableMap<UUID, MutableMap<MineBuffBarType, BossBar>> = linkedMapOf()

    fun showGlobal(player: Player, mode: MineBuffMode, multiplier: Double, remainingMillis: Long, totalMillis: Long) {
        showFor(
            player = player,
            type = if (mode == MineBuffMode.RATE) MineBuffBarType.GLOBAL_RATE else MineBuffBarType.GLOBAL_AMOUNT,
            scopeLabel = "Global Minebuff",
            mode = mode,
            multiplier = multiplier,
            remainingMillis = remainingMillis,
            totalMillis = totalMillis,
            color = BarColor.YELLOW,
        )
    }

    fun showTown(player: Player, townName: String, mode: MineBuffMode, multiplier: Double, remainingMillis: Long, totalMillis: Long) {
        showFor(
            player = player,
            type = if (mode == MineBuffMode.RATE) MineBuffBarType.TOWN_RATE else MineBuffBarType.TOWN_AMOUNT,
            scopeLabel = "$townName Minebuff",
            mode = mode,
            multiplier = multiplier,
            remainingMillis = remainingMillis,
            totalMillis = totalMillis,
            color = BarColor.BLUE,
        )
    }

    fun hideGlobal(player: Player, mode: MineBuffMode) {
        hide(player, if (mode == MineBuffMode.RATE) MineBuffBarType.GLOBAL_RATE else MineBuffBarType.GLOBAL_AMOUNT)
    }

    fun hideTown(player: Player, mode: MineBuffMode) {
        hide(player, if (mode == MineBuffMode.RATE) MineBuffBarType.TOWN_RATE else MineBuffBarType.TOWN_AMOUNT)
    }

    fun stop() {
        bossBars.values.forEach { perPlayer ->
            perPlayer.values.forEach { it.removeAll() }
            perPlayer.clear()
        }
        bossBars.clear()
    }

    private fun showFor(
        player: Player,
        type: MineBuffBarType,
        scopeLabel: String,
        mode: MineBuffMode,
        multiplier: Double,
        remainingMillis: Long,
        totalMillis: Long,
        color: BarColor,
    ) {
        val perPlayer = bossBars.getOrPut(player.uniqueId) { linkedMapOf() }
        val bar = perPlayer.getOrPut(type) {
            Bukkit.createBossBar("", color, BarStyle.SOLID).also { it.addPlayer(player) }
        }

        val rem = remainingMillis.coerceAtLeast(0L)
        val progress = (rem.toDouble() / totalMillis.coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)

        bar.color = color
        bar.style = BarStyle.SOLID
        bar.progress = progress
        bar.setTitle(
            "${if (type == MineBuffBarType.TOWN_RATE || type == MineBuffBarType.TOWN_AMOUNT) ChatColor.AQUA else ChatColor.GOLD}$scopeLabel " +
                "${ChatColor.WHITE}| ${modeLabel(mode)} x${String.format("%.2f", multiplier)} " +
                "${ChatColor.AQUA}${formatTime(rem)}",
        )
    }

    private fun modeLabel(mode: MineBuffMode): String = when (mode) {
        MineBuffMode.RATE -> "rates"
        MineBuffMode.AMOUNT -> "amount"
    }

    private fun hide(player: Player, type: MineBuffBarType) {
        val perPlayer = bossBars[player.uniqueId] ?: return
        val bar = perPlayer.remove(type) ?: return
        bar.removePlayer(player)
        bar.removeAll()
        if (perPlayer.isEmpty()) {
            bossBars.remove(player.uniqueId)
        }
    }

    private fun formatTime(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return "${hours}h ${minutes}m ${seconds}s"
    }
}
