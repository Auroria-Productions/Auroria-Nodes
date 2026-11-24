package phonon.nodes.warzone

import org.bukkit.command.CommandSender
import phonon.nodes.Message
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

public object WarzoneInfoPrinter {
    private val SCHEDULE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault())

    public fun printSummary(sender: CommandSender, warzone: Warzone) {
        val now = System.currentTimeMillis()
        val status = if (warzone.isActive(now)) {
            "Active (${formatDuration(warzone.remainingMillis(now))} remaining)"
        } else {
            "Inactive"
        }
        Message.print(sender, "${WarzoneText.prefix()} ${warzone.name}: $status")
        val schedule = formatNextStart(warzone, now)
        Message.print(sender, "- Next start: $schedule")
        Message.print(sender, "- Duration: ${formatDuration(warzone.durationPreview())}")
        Message.print(sender, "- Win mode: ${formatWinMode(warzone.config.winMode)}")
        warzone.lastWinnerName?.let {
            Message.print(sender, "- Last winner: $it")
        }

        val leaderboard = warzone.leaderboardSnapshot()
        if (leaderboard.isEmpty()) {
            Message.print(sender, "- Leaderboard: no entries yet")
        } else {
            Message.print(sender, "- Leaderboard:")
            leaderboard.take(5).forEachIndexed { index, entry ->
                Message.print(sender, "   ${index + 1}. ${entry.first}: ${formatDuration(entry.second)}")
            }
        }
    }

    public fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "${hours}h ${minutes}m"
        } else if (minutes > 0L) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }

    public fun formatTimestamp(millis: Long): String = SCHEDULE_FORMAT.format(Instant.ofEpochMilli(millis))

    public fun formatNextStart(warzone: Warzone, now: Long = System.currentTimeMillis()): String {
        if (warzone.nextStartTime <= 0L) {
            return "No schedule"
        }

        val base = warzone.scheduleDescription ?: formatTimestamp(warzone.nextStartTime)
        val remaining = (warzone.nextStartTime - now).coerceAtLeast(0L)
        return "$base (in ${formatDuration(remaining)})"
    }

    private fun formatWinMode(mode: WarzoneWinMode): String = when (mode) {
        WarzoneWinMode.REWARD -> "Reward"
        WarzoneWinMode.REWARD_AND_NODES -> "Reward + Nodes"
        WarzoneWinMode.NODES -> "Nodes"
    }
}
