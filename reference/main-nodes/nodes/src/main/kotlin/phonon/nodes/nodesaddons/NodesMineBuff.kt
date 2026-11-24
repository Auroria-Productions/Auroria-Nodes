package phonon.nodes.nodesaddons

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Town
import java.io.File

private data class ActiveMineBuff(
    val multiplier: Double,
    val startMillis: Long,
    val endMillis: Long,
)

enum class MineBuffMode {
    RATE,
    AMOUNT,
}

data class MineBuffEffect(
    val chanceMultiplier: Double,
    val amountMultiplier: Double,
)

object NodesMineBuff :
    CommandExecutor,
    TabCompleter {

    private val globalBuffs: LinkedHashMap<MineBuffMode, ActiveMineBuff> = linkedMapOf()
    private val townBuffs: LinkedHashMap<String, LinkedHashMap<MineBuffMode, ActiveMineBuff>> = linkedMapOf()
    private var tickTask: BukkitRunnable? = null
    private var nextExpiryAt: Long = Long.MAX_VALUE
    private var hadAnyBuffLastTick: Boolean = false
    private val dataFile: File
        get() = File(phonon.nodes.Config.pathPlugin, "minebuff.yml")

    fun initialize() {
        load()
        ensureTickTask()
        cleanupExpired()
    }

    fun shutdown() {
        save()
        tickTask?.cancel()
        tickTask = null
        globalBuffs.clear()
        townBuffs.clear()
        nextExpiryAt = Long.MAX_VALUE
        hadAnyBuffLastTick = false
        NodesMineBuffBar.stop()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!canUseCommand(sender)) {
            send(sender, "${ChatColor.RED}No permission.")
            return true
        }

        ensureTickTask()
        cleanupExpired()

        return when {
            label.equals("minebuff", ignoreCase = true) -> handleMineBuffCommand(sender, args)
            label.equals("minebufftown", ignoreCase = true) -> handleTownCommand(sender, args)
            else -> false
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> = when {
        label.equals("minebuff", ignoreCase = true) -> when (args.size) {
            1 -> listOf("clear", "town", "rates", "amount", "1.5", "2.0", "3.0", "5.0")
                .filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> {
                if (args[0].equals("town", ignoreCase = true)) {
                    Nodes.towns.keys.filter { it.startsWith(args[1], ignoreCase = true) }
                } else if (parseMode(args[0]) != null) {
                    listOf("1.5", "2.0", "3.0", "5.0").filter { it.startsWith(args[1], ignoreCase = true) }
                } else {
                    listOf("30m", "1h", "2h", "3h").filter { it.startsWith(args[1], ignoreCase = true) }
                }
            }
            3 -> {
                if (args[0].equals("town", ignoreCase = true)) {
                    listOf("clear", "rates", "amount", "1.5", "2.0", "3.0", "5.0")
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                } else {
                    listOf("30m", "1h", "2h", "3h").filter { it.startsWith(args[2], ignoreCase = true) }
                }
            }
            4 -> {
                if (args[0].equals("town", ignoreCase = true) && parseMode(args[2]) != null) {
                    listOf("1.5", "2.0", "3.0", "5.0").filter { it.startsWith(args[3], ignoreCase = true) }
                } else {
                    listOf("30m", "1h", "2h", "3h").filter { it.startsWith(args[3], ignoreCase = true) }
                }
            }
            else -> listOf("30m", "1h", "2h", "3h").filter { it.startsWith(args[args.size - 1], ignoreCase = true) }
        }

        label.equals("minebufftown", ignoreCase = true) -> when (args.size) {
            1 -> Nodes.towns.keys.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> listOf("clear", "rates", "amount", "1.5", "2.0", "3.0", "5.0").filter { it.startsWith(args[1], ignoreCase = true) }
            3 -> {
                if (parseMode(args[1]) != null) {
                    listOf("1.5", "2.0", "3.0", "5.0").filter { it.startsWith(args[2], ignoreCase = true) }
                } else {
                    listOf("30m", "1h", "2h", "3h").filter { it.startsWith(args[2], ignoreCase = true) }
                }
            }
            else -> listOf("30m", "1h", "2h", "3h").filter { it.startsWith(args[args.size - 1], ignoreCase = true) }
        }

        else -> emptyList()
    }

    fun sendJoinStatus(player: Player) {
        cleanupExpired()

        for (mode in MineBuffMode.entries) {
            val global = globalBuffs[mode] ?: continue
            send(
                player,
                "${ChatColor.GOLD}[Minebuff] ${ChatColor.WHITE}Global " +
                    "${ChatColor.YELLOW}${modeLabel(mode)} x${formatMultiplier(global.multiplier)} " +
                    "${ChatColor.GRAY}${formatDuration((global.endMillis - System.currentTimeMillis()).coerceAtLeast(0L))}",
            )
        }

        val town = Nodes.getTownFromPlayer(player)
        val townModeBuffs = town?.let { townBuffs[it.name.lowercase()] } ?: return
        for (mode in MineBuffMode.entries) {
            val townBuff = townModeBuffs[mode] ?: continue
            send(
                player,
                "${ChatColor.AQUA}[Minebuff] ${ChatColor.WHITE}${town.name} " +
                    "${ChatColor.YELLOW}${modeLabel(mode)} x${formatMultiplier(townBuff.multiplier)} " +
                    "${ChatColor.GRAY}${formatDuration((townBuff.endMillis - System.currentTimeMillis()).coerceAtLeast(0L))}",
            )
        }
    }

    fun hasActiveBoost(player: Player? = null): Boolean {
        val effect = getCurrentEffect(player)
        return effect.chanceMultiplier > 1.0 || effect.amountMultiplier > 1.0
    }

    fun getCurrentMultiplier(player: Player? = null): Double = getCurrentEffect(player).chanceMultiplier

    fun getCurrentMultiplierForTown(town: Town?): Double = getCurrentEffectForTown(town).chanceMultiplier

    fun getCurrentEffect(player: Player? = null): MineBuffEffect {
        val town = player?.let { Nodes.getTownFromPlayer(it) }
        return getCurrentEffectForTown(town)
    }

    fun getCurrentEffectForTown(town: Town?): MineBuffEffect {
        if (globalBuffs.isEmpty() && townBuffs.isEmpty()) {
            return MineBuffEffect(1.0, 1.0)
        }

        cleanupExpired()

        val townModeBuffs = town?.let { townBuffs[it.name.lowercase()] }
        val globalChance = globalBuffs[MineBuffMode.RATE]?.multiplier ?: 1.0
        val townChance = townModeBuffs?.get(MineBuffMode.RATE)?.multiplier ?: 1.0
        val globalAmount = globalBuffs[MineBuffMode.AMOUNT]?.multiplier ?: 1.0
        val townAmount = townModeBuffs?.get(MineBuffMode.AMOUNT)?.multiplier ?: 1.0

        return MineBuffEffect(
            chanceMultiplier = maxOf(globalChance, townChance),
            amountMultiplier = maxOf(globalAmount, townAmount),
        )
    }

    private fun handleMineBuffCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            send(sender, "${ChatColor.RED}Usage: /minebuff <multiplier> <time>")
            send(sender, "${ChatColor.RED}Usage: /minebuff <rates|amount> <multiplier> <time>")
            send(sender, "${ChatColor.RED}Usage: /minebuff clear")
            send(sender, "${ChatColor.RED}Usage: /minebuff town <town> <multiplier> <time>")
            send(sender, "${ChatColor.RED}Usage: /minebuff town <town> <rates|amount> <multiplier> <time>")
            send(sender, "${ChatColor.RED}Usage: /minebuff town <town> clear")
            return true
        }

        if (args[0].equals("clear", ignoreCase = true)) {
            globalBuffs.clear()
            refreshNextExpiry()
            save()
            refreshBossBars()
            send(sender, "${ChatColor.GREEN}Cleared global minebuff")
            return true
        }

        if (args[0].equals("town", ignoreCase = true)) {
            return handleTownSubcommand(sender, args)
        }

        return applyGlobalBuff(sender, args)
    }

    private fun handleTownSubcommand(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 3) {
            send(sender, "${ChatColor.RED}Usage: /minebuff town <town> <multiplier> <time>")
            send(sender, "${ChatColor.RED}Usage: /minebuff town <town> <rates|amount> <multiplier> <time>")
            send(sender, "${ChatColor.RED}Usage: /minebuff town <town> clear")
            return true
        }

        val town = Nodes.towns[args[1]]
        if (town == null) {
            send(sender, "${ChatColor.RED}Town \"${args[1]}\" does not exist")
            return true
        }

        if (args[2].equals("clear", ignoreCase = true)) {
            townBuffs.remove(town.name.lowercase())
            refreshNextExpiry()
            save()
            refreshBossBars()
            send(sender, "${ChatColor.GREEN}Cleared minebuff for ${town.name}")
            return true
        }

        return applyTownBuff(sender, town.name, args, 2)
    }

    private fun handleTownCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 2) {
            send(sender, "${ChatColor.RED}Usage: /minebufftown <town> <multiplier> <time>")
            send(sender, "${ChatColor.RED}Usage: /minebufftown <town> <rates|amount> <multiplier> <time>")
            send(sender, "${ChatColor.RED}Usage: /minebufftown <town> clear")
            return true
        }

        val town = Nodes.towns[args[0]]
        if (town == null) {
            send(sender, "${ChatColor.RED}Town \"${args[0]}\" does not exist")
            return true
        }

        if (args[1].equals("clear", ignoreCase = true)) {
            townBuffs.remove(town.name.lowercase())
            refreshNextExpiry()
            save()
            refreshBossBars()
            send(sender, "${ChatColor.GREEN}Cleared minebuff for ${town.name}")
            return true
        }

        return applyTownBuff(sender, town.name, args, 1)
    }

    private fun applyGlobalBuff(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 2) {
            send(sender, "${ChatColor.RED}Usage: /minebuff <multiplier> <time>")
            return true
        }

        val mode = parseMode(args[0])
        val multiplierIndex = if (mode != null) 1 else 0
        val resolvedMode = mode ?: MineBuffMode.RATE
        if (args.size <= multiplierIndex) {
            send(sender, "${ChatColor.RED}Missing multiplier")
            return true
        }

        val multiplier = parseMultiplier(args[multiplierIndex], sender) ?: return true
        val duration = parseDurationMillis(args, multiplierIndex + 1)
        if (duration == null || duration <= 0L) {
            send(sender, "${ChatColor.RED}Invalid time. Example: 3h 30m")
            return true
        }

        globalBuffs[resolvedMode] = mergeBuff(globalBuffs[resolvedMode], multiplier, duration)
        refreshNextExpiry()
        save()
        refreshBossBars()
        val active = globalBuffs[resolvedMode]!!
        send(
            sender,
            "${ChatColor.GREEN}Set global minebuff (${modeLabel(resolvedMode)}) to x${formatMultiplier(active.multiplier)} " +
                "for ${formatDuration((active.endMillis - System.currentTimeMillis()).coerceAtLeast(0L))}",
        )
        return true
    }

    private fun applyTownBuff(sender: CommandSender, townName: String, args: Array<String>, multiplierIndex: Int): Boolean {
        if (args.size <= multiplierIndex) {
            send(sender, "${ChatColor.RED}Missing multiplier")
            return true
        }

        val mode = parseMode(args[multiplierIndex])
        val resolvedMode = mode ?: MineBuffMode.RATE
        val resolvedMultiplierIndex = if (mode != null) multiplierIndex + 1 else multiplierIndex
        if (args.size <= resolvedMultiplierIndex) {
            send(sender, "${ChatColor.RED}Missing multiplier")
            return true
        }

        val multiplier = parseMultiplier(args[resolvedMultiplierIndex], sender) ?: return true
        val duration = parseDurationMillis(args, resolvedMultiplierIndex + 1)
        if (duration == null || duration <= 0L) {
            send(sender, "${ChatColor.RED}Invalid time. Example: 3h 30m")
            return true
        }

        val key = townName.lowercase()
        val townModeBuffs = townBuffs.getOrPut(key) { linkedMapOf() }
        townModeBuffs[resolvedMode] = mergeBuff(townModeBuffs[resolvedMode], multiplier, duration)
        refreshNextExpiry()
        save()
        refreshBossBars()
        val active = townModeBuffs[resolvedMode]!!
        send(
            sender,
            "${ChatColor.GREEN}Set minebuff for $townName (${modeLabel(resolvedMode)}) to x${formatMultiplier(active.multiplier)} " +
                "for ${formatDuration((active.endMillis - System.currentTimeMillis()).coerceAtLeast(0L))}",
        )
        return true
    }

    private fun mergeBuff(current: ActiveMineBuff?, requestedMultiplier: Double, addedDuration: Long): ActiveMineBuff {
        val now = System.currentTimeMillis()
        val remaining = current?.let { (it.endMillis - now).coerceAtLeast(0L) } ?: 0L
        val finalMultiplier = maxOf(current?.multiplier ?: 1.0, requestedMultiplier)
        val combinedDuration = remaining + addedDuration
        return ActiveMineBuff(
            multiplier = finalMultiplier,
            startMillis = now,
            endMillis = now + combinedDuration,
        )
    }

    private fun cleanupExpired(now: Long = System.currentTimeMillis()) {
        if (now < nextExpiryAt) {
            return
        }

        var changed = false

        if (globalBuffs.entries.removeIf { it.value.endMillis <= now }) {
            changed = true
        }

        val townIter = townBuffs.entries.iterator()
        while (townIter.hasNext()) {
            val townEntry = townIter.next()
            val modeBuffs = townEntry.value
            if (modeBuffs.entries.removeIf { it.value.endMillis <= now }) {
                changed = true
            }
            if (modeBuffs.isEmpty()) {
                townIter.remove()
                changed = true
            }
        }

        if (changed) {
            refreshNextExpiry()
            save()
        }
    }

    private fun ensureTickTask() {
        if (tickTask != null) {
            return
        }

        val plugin = Nodes.plugin ?: return
        tickTask = object : BukkitRunnable() {
            override fun run() {
                cleanupExpired()
                refreshBossBars()
            }
        }
        tickTask?.runTaskTimer(plugin, 20L, 20L)
    }

    private fun refreshBossBars() {
        val now = System.currentTimeMillis()
        cleanupExpired(now)
        if (globalBuffs.isEmpty() && townBuffs.isEmpty()) {
            if (hadAnyBuffLastTick) {
                NodesMineBuffBar.stop()
                hadAnyBuffLastTick = false
            }
            return
        }
        hadAnyBuffLastTick = true

        Bukkit.getOnlinePlayers().forEach { player ->
            for (mode in MineBuffMode.entries) {
                val global = globalBuffs[mode]
                if (global == null) {
                    NodesMineBuffBar.hideGlobal(player, mode)
                } else {
                    val totalMillis = (global.endMillis - global.startMillis).coerceAtLeast(1L)
                    val remainingMillis = (global.endMillis - now).coerceAtLeast(0L)
                    NodesMineBuffBar.showGlobal(player, mode, global.multiplier, remainingMillis, totalMillis)
                }
            }

            val town = Nodes.getTownFromPlayer(player)
            val townModeBuffs = town?.let { townBuffs[it.name.lowercase()] }
            for (mode in MineBuffMode.entries) {
                val townBuff = townModeBuffs?.get(mode)
                if (town == null || townBuff == null) {
                    NodesMineBuffBar.hideTown(player, mode)
                } else {
                    val totalMillis = (townBuff.endMillis - townBuff.startMillis).coerceAtLeast(1L)
                    val remainingMillis = (townBuff.endMillis - now).coerceAtLeast(0L)
                    NodesMineBuffBar.showTown(player, town.name, mode, townBuff.multiplier, remainingMillis, totalMillis)
                }
            }
        }
    }

    private fun parseDurationMillis(args: Array<String>, startIndex: Int): Long? {
        if (args.size <= startIndex) return null
        var totalMinutes = 0L
        var parsedAny = false

        for (i in startIndex until args.size) {
            val token = args[i].trim().lowercase()
            if (token.isBlank()) continue
            when {
                token.endsWith("h") -> {
                    val value = token.removeSuffix("h").toLongOrNull() ?: return null
                    if (value < 0) return null
                    totalMinutes += value * 60L
                    parsedAny = true
                }
                token.endsWith("m") -> {
                    val value = token.removeSuffix("m").toLongOrNull() ?: return null
                    if (value < 0) return null
                    totalMinutes += value
                    parsedAny = true
                }
                token.toLongOrNull() != null -> {
                    val value = token.toLong()
                    if (value < 0) return null
                    totalMinutes += value
                    parsedAny = true
                }
                else -> return null
            }
        }

        if (!parsedAny) return null
        return totalMinutes * 60L * 1000L
    }

    private fun parseMultiplier(value: String, sender: CommandSender): Double? {
        val multiplier = value.toDoubleOrNull()
        if (multiplier == null || multiplier < 1.0) {
            send(sender, "${ChatColor.RED}Multiplier must be a number >= 1.0")
            return null
        }
        return multiplier
    }

    private fun parseMode(raw: String): MineBuffMode? = when (raw.lowercase()) {
        "rates", "rate", "chance", "percent", "%" -> MineBuffMode.RATE
        "amount", "amt", "drops", "quantity" -> MineBuffMode.AMOUNT
        else -> null
    }

    private fun canUseCommand(sender: CommandSender): Boolean = sender.isOp || sender.hasPermission("nodes.admin")

    private fun send(sender: CommandSender, text: String) {
        Message.raw(sender, Message.translated(sender, text))
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return "${hours}h ${minutes}m ${seconds}s"
    }

    private fun formatMultiplier(multiplier: Double): String = String.format("%.2f", multiplier)

    private fun modeLabel(mode: MineBuffMode): String = when (mode) {
        MineBuffMode.RATE -> "rates"
        MineBuffMode.AMOUNT -> "amount"
    }

    private fun modeKey(mode: MineBuffMode): String = when (mode) {
        MineBuffMode.RATE -> "rates"
        MineBuffMode.AMOUNT -> "amount"
    }

    private fun refreshNextExpiry() {
        var next = Long.MAX_VALUE
        for (buff in globalBuffs.values) {
            if (buff.endMillis < next) {
                next = buff.endMillis
            }
        }
        for (modeBuffs in townBuffs.values) {
            for (buff in modeBuffs.values) {
                if (buff.endMillis < next) {
                    next = buff.endMillis
                }
            }
        }
        nextExpiryAt = next
    }

    private fun loadLegacyGlobal(yaml: YamlConfiguration, now: Long) {
        val globalMultiplier = yaml.getDouble("global.multiplier", 1.0)
        val globalStart = yaml.getLong("global.startMillis", 0L)
        val globalEnd = yaml.getLong("global.endMillis", 0L)
        val globalMode = parseMode(yaml.getString("global.mode", "rates") ?: "rates") ?: MineBuffMode.RATE
        if (globalMultiplier > 1.0 && globalEnd > now) {
            globalBuffs[globalMode] = ActiveMineBuff(globalMultiplier, globalStart, globalEnd)
        }
    }

    private fun loadLegacyTown(townKey: String, yaml: YamlConfiguration, now: Long) {
        val base = "towns.$townKey"
        val multiplier = yaml.getDouble("$base.multiplier", 1.0)
        val startMillis = yaml.getLong("$base.startMillis", 0L)
        val endMillis = yaml.getLong("$base.endMillis", 0L)
        val mode = parseMode(yaml.getString("$base.mode", "rates") ?: "rates") ?: MineBuffMode.RATE
        if (multiplier > 1.0 && endMillis > now) {
            val modeBuffs = townBuffs.getOrPut(townKey.lowercase()) { linkedMapOf() }
            modeBuffs[mode] = ActiveMineBuff(multiplier, startMillis, endMillis)
        }
    }

    private fun load() {
        if (!dataFile.exists()) {
            return
        }

        try {
            val yaml = YamlConfiguration.loadConfiguration(dataFile)
            val now = System.currentTimeMillis()

            globalBuffs.clear()
            townBuffs.clear()

            for (mode in MineBuffMode.entries) {
                val base = "global.${modeKey(mode)}"
                val multiplier = yaml.getDouble("$base.multiplier", 1.0)
                val startMillis = yaml.getLong("$base.startMillis", 0L)
                val endMillis = yaml.getLong("$base.endMillis", 0L)
                if (multiplier > 1.0 && endMillis > now) {
                    globalBuffs[mode] = ActiveMineBuff(multiplier, startMillis, endMillis)
                }
            }
            if (globalBuffs.isEmpty()) {
                loadLegacyGlobal(yaml, now)
            }

            val townsSection = yaml.getConfigurationSection("towns")
            if (townsSection != null) {
                for (townKey in townsSection.getKeys(false)) {
                    val modeBuffs: LinkedHashMap<MineBuffMode, ActiveMineBuff> = linkedMapOf()
                    for (mode in MineBuffMode.entries) {
                        val base = "towns.$townKey.${modeKey(mode)}"
                        val multiplier = yaml.getDouble("$base.multiplier", 1.0)
                        val startMillis = yaml.getLong("$base.startMillis", 0L)
                        val endMillis = yaml.getLong("$base.endMillis", 0L)
                        if (multiplier > 1.0 && endMillis > now) {
                            modeBuffs[mode] = ActiveMineBuff(multiplier, startMillis, endMillis)
                        }
                    }

                    if (modeBuffs.isEmpty()) {
                        loadLegacyTown(townKey, yaml, now)
                    } else {
                        townBuffs[townKey.lowercase()] = modeBuffs
                    }
                }
            }

            refreshNextExpiry()
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to load minebuff data: ${e.message}")
        }
    }

    private fun save() {
        try {
            if (!dataFile.parentFile.exists()) {
                dataFile.parentFile.mkdirs()
            }

            val yaml = YamlConfiguration()
            val now = System.currentTimeMillis()

            for ((mode, buff) in globalBuffs) {
                if (buff.multiplier <= 1.0 || buff.endMillis <= now) {
                    continue
                }
                val base = "global.${modeKey(mode)}"
                yaml.set("$base.multiplier", buff.multiplier)
                yaml.set("$base.startMillis", buff.startMillis)
                yaml.set("$base.endMillis", buff.endMillis)
            }

            for ((townKey, modeBuffs) in townBuffs) {
                for ((mode, buff) in modeBuffs) {
                    if (buff.multiplier <= 1.0 || buff.endMillis <= now) {
                        continue
                    }
                    val base = "towns.$townKey.${modeKey(mode)}"
                    yaml.set("$base.multiplier", buff.multiplier)
                    yaml.set("$base.startMillis", buff.startMillis)
                    yaml.set("$base.endMillis", buff.endMillis)
                }
            }

            yaml.save(dataFile)
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to save minebuff data: ${e.message}")
        }
    }
}
