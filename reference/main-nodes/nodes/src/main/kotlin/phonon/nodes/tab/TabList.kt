package phonon.nodes.tab

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo
import io.github.retrooper.packetevents.adventure.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minecraft.ChatFormatting
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import phonon.nodes.Nodes
import phonon.nodes.NodesPlugin
import phonon.nodes.constants.DiplomaticRelationship
import phonon.nodes.objects.Town
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private val REGEX_HEX = Regex("&#([0-9a-fA-F]{6})")
private val REGEX_LEGACY = Regex("&([0-9a-fk-orA-FK-OR])")

private val LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection()

private fun translateLegacy(text: String): String {
    var result = text
    result = REGEX_HEX.replace(result) { match ->
        val hex = match.groupValues[1]
        "§x§${hex[0]}§${hex[1]}§${hex[2]}§${hex[3]}§${hex[4]}§${hex[5]}"
    }
    result = REGEX_LEGACY.replace(result, "§$1")
    return result
}

private fun legacyComponent(text: String): Component = LEGACY_SERIALIZER.deserialize(translateLegacy(text))

private fun Player.sendTeamPacket(wrapper: WrapperPlayServerTeams) = PacketEvents.getAPI().playerManager.sendPacket(this, wrapper)

// ─────────────────────────────────────────────────────────────────────────────
// Color helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun ChatFormatting.toNamedTextColor(): NamedTextColor = when (this) {
    ChatFormatting.BLACK -> NamedTextColor.BLACK
    ChatFormatting.DARK_BLUE -> NamedTextColor.DARK_BLUE
    ChatFormatting.DARK_GREEN -> NamedTextColor.DARK_GREEN
    ChatFormatting.DARK_AQUA -> NamedTextColor.DARK_AQUA
    ChatFormatting.DARK_RED -> NamedTextColor.DARK_RED
    ChatFormatting.DARK_PURPLE -> NamedTextColor.DARK_PURPLE
    ChatFormatting.GOLD -> NamedTextColor.GOLD
    ChatFormatting.GRAY -> NamedTextColor.GRAY
    ChatFormatting.DARK_GRAY -> NamedTextColor.DARK_GRAY
    ChatFormatting.BLUE -> NamedTextColor.BLUE
    ChatFormatting.GREEN -> NamedTextColor.GREEN
    ChatFormatting.AQUA -> NamedTextColor.AQUA
    ChatFormatting.RED -> NamedTextColor.RED
    ChatFormatting.LIGHT_PURPLE -> NamedTextColor.LIGHT_PURPLE
    ChatFormatting.YELLOW -> NamedTextColor.YELLOW
    ChatFormatting.WHITE -> NamedTextColor.WHITE
    else -> NamedTextColor.WHITE
}

private fun ChatFormatting.toSectionString(): String = when (this) {
    ChatFormatting.BLACK -> "§0"
    ChatFormatting.DARK_BLUE -> "§1"
    ChatFormatting.DARK_GREEN -> "§2"
    ChatFormatting.DARK_AQUA -> "§3"
    ChatFormatting.DARK_RED -> "§4"
    ChatFormatting.DARK_PURPLE -> "§5"
    ChatFormatting.GOLD -> "§6"
    ChatFormatting.GRAY -> "§7"
    ChatFormatting.DARK_GRAY -> "§8"
    ChatFormatting.BLUE -> "§9"
    ChatFormatting.GREEN -> "§a"
    ChatFormatting.AQUA -> "§b"
    ChatFormatting.RED -> "§c"
    ChatFormatting.LIGHT_PURPLE -> "§d"
    ChatFormatting.YELLOW -> "§e"
    ChatFormatting.WHITE -> "§f"
    else -> ""
}

// ─────────────────────────────────────────────────────────────────────────────
// Sort buckets
// ─────────────────────────────────────────────────────────────────────────────

private const val BUCKET_STAFF = "10"
private const val BUCKET_TOWN = "20"
private const val BUCKET_ALLY = "30"
private const val BUCKET_ENEMY = "40"
private const val BUCKET_NEUTRAL = "50"
private const val BUCKET_TOWNLESS = "60"

private fun groupSortFor(viewer: Player, target: Player, weight: Int): String {
    val cfg = TabListConfig
    if (cfg.staffEnabled && weight >= cfg.staffMinWeight) return BUCKET_STAFF
    val town = Nodes.getTownFromPlayer(target) ?: return BUCKET_TOWNLESS
    return when (Nodes.getRelationshipOfPlayerToTown(viewer, town)) {
        DiplomaticRelationship.TOWN -> BUCKET_TOWN
        DiplomaticRelationship.NATION -> BUCKET_TOWN
        DiplomaticRelationship.ALLY -> BUCKET_ALLY
        DiplomaticRelationship.ENEMY -> BUCKET_ENEMY
        else -> BUCKET_NEUTRAL
    }
}

private fun playerTeamName(groupSort: String, target: Player, weight: Int = 0): String {
    val inverted = (9999 - weight.coerceIn(0, 9999)).toString().padStart(4, '0')
    val t = target.uniqueId.toString().replace("-", "").take(6)
    return "${TabListConfig.teamPrefix}$groupSort$inverted$t"
}

// ─────────────────────────────────────────────────────────────────────────────
// Team packet builders
// ─────────────────────────────────────────────────────────────────────────────

private fun Player.sendTeamCreate(
    teamName: String,
    prefix: String,
    suffix: String,
    playerName: String,
    color: NamedTextColor,
) {
    val info = ScoreBoardTeamInfo(
        legacyComponent(teamName),
        legacyComponent(prefix),
        legacyComponent(suffix),
        WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
        WrapperPlayServerTeams.CollisionRule.NEVER,
        color,
        WrapperPlayServerTeams.OptionData.NONE,
    )
    sendTeamPacket(
        WrapperPlayServerTeams(
            teamName,
            WrapperPlayServerTeams.TeamMode.CREATE,
            Optional.of(info),
            listOf(playerName),
        ),
    )
}

private fun Player.sendTeamRemove(teamName: String) {
    sendTeamPacket(
        WrapperPlayServerTeams(
            teamName,
            WrapperPlayServerTeams.TeamMode.REMOVE,
            Optional.empty(),
        ),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Relation style & prefix building
// ─────────────────────────────────────────────────────────────────────────────

private fun styleFor(viewer: Player, targetTown: Town) = when (Nodes.getRelationshipOfPlayerToTown(viewer, targetTown)) {
    DiplomaticRelationship.TOWN -> TabListConfig.self
    DiplomaticRelationship.NATION -> TabListConfig.nation
    DiplomaticRelationship.ALLY -> TabListConfig.ally
    DiplomaticRelationship.ENEMY -> TabListConfig.enemy
    else -> TabListConfig.neutral
}

/**
 * Builds the full name-tag prefix shown left of a player's name in TAB.
 *
 * Layout (each part omitted when empty):
 *   [LP prefix] [separator] [town tag]
 *
 * Returns the prefix string and the ChatFormatting colour used for the
 * scoreboard team (drives the player name colour in TAB).
 */
private fun buildPrefix(viewer: Player, target: Player): Pair<String, ChatFormatting> {
    val cfg = TabListConfig
    val reset = "§r"

    val lpPrefix = if (cfg.luckPermsEnabled) LuckPermsHook.getPrefix(target) else ""
    val lpSuffix = if (cfg.luckPermsEnabled && cfg.luckPermsSuffixEnabled) LuckPermsHook.getSuffix(target) else ""

    val town = Nodes.getTownFromPlayer(target)

    val (tag, color) = when {
        cfg.showTownTag && town != null -> {
            val style = styleFor(viewer, town)
            val c = style.tagColor
            val bold = if (style.bold) "§l" else ""
            val tag = "${c.toSectionString()}$bold${cfg.tagOpen}${town.name}${cfg.tagClose}$reset"
            tag to c
        }
        cfg.showTownTag && town == null -> {
            val style = cfg.townless
            val c = style.tagColor
            val bold = if (style.bold) "§l" else ""
            val tag = "${c.toSectionString()}$bold${cfg.tagOpen}${style.tagText}${cfg.tagClose}$reset"
            tag to c
        }
        else -> "" to ChatFormatting.WHITE
    }

    val sep = if (lpPrefix.isNotEmpty() && tag.isNotEmpty()) cfg.luckPermsSeparator else ""
    return "$lpPrefix$sep$tag" to color
}

// ─────────────────────────────────────────────────────────────────────────────
// Header / Footer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolves per-viewer placeholders in a list of lines and joins them with
 * newlines, then translates legacy colour codes ready for packet sending.
 *
 * Supported placeholders:
 *   {player}  – viewer's IGN
 *   {town}    – viewer's town name (coloured) or the configured townless text
 *   {nation}  – viewer's nation name, or empty string
 *   {online}  – current online player count
 *   {prefix}  – viewer's LP prefix (if LP enabled)
 */
private fun resolveLines(lines: List<String>, viewer: Player): Component {
    val cfg = TabListConfig
    val town = Nodes.getTownFromPlayer(viewer)
    val townText = if (town != null) {
        // viewer is always in their own town, so relation is always TOWN → use self style
        val style = cfg.self
        val c = style.tagColor.toSectionString()
        val bold = if (style.bold) "§l" else ""
        "$c$bold${town.name}§r"
    } else {
        translateLegacy(cfg.townlessDisplayText)
    }
    val nationText = town?.nation?.name ?: ""
    val lpPrefix = if (cfg.luckPermsEnabled) LuckPermsHook.getPrefix(viewer) else ""
    val online = Bukkit.getOnlinePlayers().size.toString()

    val resolved = lines.joinToString("\n") { line ->
        line
            .replace("{player}", viewer.name)
            .replace("{town}", townText)
            .replace("{nation}", nationText)
            .replace("{online}", online)
            .replace("{prefix}", lpPrefix)
    }
    return legacyComponent(resolved)
}

private fun Player.sendHeaderFooter(header: Component, footer: Component) {
    val packet = WrapperPlayServerPlayerListHeaderAndFooter(header, footer)
    PacketEvents.getAPI().playerManager.sendPacket(this, packet)
}

// ─────────────────────────────────────────────────────────────────────────────
// Core
// ─────────────────────────────────────────────────────────────────────────────

object TabList {

    // viewer UUID -> (target UUID -> team name currently registered for that viewer)
    private val activeTeams: ConcurrentHashMap<UUID, HashMap<UUID, String>> = ConcurrentHashMap()

    // ── Teams (name-tag colouring / sort) ─────────────────────────────────

    fun updateForPlayer(viewer: Player) {
        val online = Bukkit.getOnlinePlayers()
        val old = activeTeams.getOrPut(viewer.uniqueId) { HashMap() }
        val newMap = HashMap<UUID, String>(online.size)

        for (target in online) {
            val weight = if (TabListConfig.luckPermsEnabled) LuckPermsHook.getWeight(target) else 0
            val groupSort = groupSortFor(viewer, target, weight)
            val teamName = playerTeamName(groupSort, target, weight)
            val (prefix, colorRaw) = buildPrefix(viewer, target)

            val existing = old[target.uniqueId]
            if (existing != teamName) {
                // Only remove the old team if it existed and changed
                if (existing != null) viewer.sendTeamRemove(existing)
                viewer.sendTeamCreate(
                    teamName = teamName,
                    prefix = prefix,
                    suffix = "",
                    playerName = target.name,
                    color = colorRaw.toNamedTextColor(),
                )
            }
            newMap[target.uniqueId] = teamName
        }

        // Remove teams for players who went offline
        for ((uuid, team) in old) {
            if (uuid !in newMap) viewer.sendTeamRemove(team)
        }

        activeTeams[viewer.uniqueId] = newMap
        updateHeaderFooter(viewer)
    }

    // ── Header / Footer ───────────────────────────────────────────────────

    fun updateHeaderFooter(viewer: Player) {
        val cfg = TabListConfig

        val header = if (cfg.headerEnabled && cfg.headerLines.isNotEmpty()) {
            resolveLines(cfg.headerLines, viewer)
        } else {
            Component.empty()
        }

        val footer = if (cfg.footerEnabled && cfg.footerLines.isNotEmpty()) {
            resolveLines(cfg.footerLines, viewer)
        } else {
            Component.empty()
        }

        viewer.sendHeaderFooter(header, footer)
    }

    /**
     * Push a fresh header/footer to every online player.
     * Called on the periodic timer so {online} etc. stay current.
     */
    fun updateAllHeaderFooters() {
        for (p in Bukkit.getOnlinePlayers()) {
            if (p.isOnline) updateHeaderFooter(p)
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    fun removePlayer(uuid: UUID) {
        activeTeams.remove(uuid)
    }

    // ── Pipelined full refresh ─────────────────────────────────────────────

    fun pipelinedUpdateAll() {
        val online = Bukkit.getOnlinePlayers().toList()
        val perTick = max(1, online.size / TabListConfig.pipelineTicks)

        var i = 0
        var tick = 1L

        while (i < online.size) {
            val start = i
            val end = (i + perTick).coerceAtMost(online.size)

            Bukkit.getGlobalRegionScheduler().runDelayed(NodesPlugin.plugin, { _ ->
                for (j in start until end) {
                    val p = online[j]
                    if (p.isOnline) Bukkit.getAsyncScheduler().runNow(NodesPlugin.plugin) { _ -> updateForPlayer(p) }
                }
            }, tick)

            i += perTick
            tick++
        }
    }
}
