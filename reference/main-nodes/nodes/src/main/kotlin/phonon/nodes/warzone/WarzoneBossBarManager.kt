package phonon.nodes.warzone

import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

public object WarzoneBossBarManager {
    private val playerWarzones: MutableMap<UUID, Warzone> = hashMapOf()
    private val playerBars: MutableMap<UUID, BossBar> = hashMapOf()
    private var refreshTask: BukkitTask? = null

    public fun start(plugin: Plugin) {
        stop()
        refreshTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                refreshAll()
            },
            20L,
            20L,
        )
    }

    public fun shutdown() {
        stop()
        clearAll()
    }

    public fun updatePlayerWarzone(player: Player, warzone: Warzone?) {
        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        if (warzone == null || !warzone.isActive(now)) {
            removeBarForUuid(uuid)
            return
        }

        val current = playerWarzones[uuid]
        if (current === warzone) {
            refreshBar(uuid, warzone, now)
            return
        }

        removeBarForUuid(uuid)
        val bossBar = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID)
        bossBar.isVisible = true
        bossBar.addPlayer(player)
        playerWarzones[uuid] = warzone
        playerBars[uuid] = bossBar
        refreshBar(uuid, warzone, now)
    }

    public fun removePlayer(player: Player) {
        removeBarForUuid(player.uniqueId)
    }

    private fun stop() {
        refreshTask?.cancel()
        refreshTask = null
    }

    private fun clearAll() {
        val uuids = playerWarzones.keys.toList()
        for (uuid in uuids) {
            removeBarForUuid(uuid)
        }
    }

    private fun refreshAll() {
        val now = System.currentTimeMillis()
        val entries = playerWarzones.toList()
        for ((uuid, warzone) in entries) {
            if (!warzone.isActive(now)) {
                removeBarForUuid(uuid)
                continue
            }
            refreshBar(uuid, warzone, now)
        }
    }

    private fun refreshBar(uuid: UUID, warzone: Warzone, now: Long) {
        val bar = playerBars[uuid] ?: return
        if (!warzone.isActive(now)) {
            removeBarForUuid(uuid)
            return
        }
        val remaining = warzone.remainingMillis(now)
        val activeDuration = warzone.state.activeDurationMillis.takeIf { it > 0L } ?: warzone.config.durationMillis
        val safeDuration = activeDuration.coerceAtLeast(1L)
        val progress = (remaining.toDouble() / safeDuration).coerceIn(0.0, 1.0)
        bar.progress = progress
        bar.setTitle(buildTitle(warzone, remaining))
    }

    private fun buildTitle(warzone: Warzone, remaining: Long): String {
        val leaderName = warzone.state.lastLeader ?: "None"
        val leaderTime = warzone.state.controlTimes[leaderName] ?: 0L
        val holderTime = WarzoneInfoPrinter.formatDuration(leaderTime)
        val timeLeft = WarzoneInfoPrinter.formatDuration(remaining)
        val c = WarzoneText.PRIMARY
        val r = WarzoneText.RESET
        return "${c}holder: $leaderName $c($holderTime) " +
            "$c| warzone: ${warzone.name} $c| time left: $timeLeft$r"
    }

    private fun removeBarForUuid(uuid: UUID) {
        val bar = playerBars.remove(uuid) ?: return
        for (player in bar.players.toList()) {
            bar.removePlayer(player)
        }
        playerWarzones.remove(uuid)
    }
}
