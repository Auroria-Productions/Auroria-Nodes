package phonon.nodes.tab

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LuckPermsHook — safe optional integration with LuckPerms.
 *
 * Provides prefix, suffix, and weight lookups from LP's cached data.
 * All methods return safe fallback values if LP is not installed.
 */

// Compiled once — reused on every prefix/suffix lookup.
private val REGEX_MM_HEX = Regex("<#([0-9a-fA-F]{6})>")
private val REGEX_MM_COLOR_HEX = Regex("<color:#([0-9a-fA-F]{6})>")
private val REGEX_MM_TAGS = Regex("</?[a-zA-Z_:#0-9]+>")

object LuckPermsHook {

    private var api: LuckPerms? = null
    private val weightCache: ConcurrentHashMap<UUID, Int> = ConcurrentHashMap()

    val isHooked: Boolean get() = api != null

    fun init() {
        if (!TabListConfig.luckPermsEnabled) return

        val lpPlugin = Bukkit.getPluginManager().getPlugin("LuckPerms")
        if (lpPlugin == null || !lpPlugin.isEnabled) return

        try {
            api = LuckPermsProvider.get()
        } catch (e: IllegalStateException) {
            Bukkit.getLogger().warning("[Nodes] LuckPerms API unavailable: ${e.message}")
        }
    }

    fun getPrefix(player: Player): String {
        if (!TabListConfig.luckPermsEnabled) return ""
        val lp = api ?: return ""
        return try {
            val raw = lp.getPlayerAdapter(Player::class.java).getMetaData(player).prefix ?: return ""
            convertMiniMessageHex(raw)
        } catch (e: Exception) {
            ""
        }
    }
    fun invalidateWeight(uuid: UUID) {
        weightCache.remove(uuid)
    }

    fun getWeight(player: Player): Int = weightCache.getOrPut(player.uniqueId) { computeWeight(player) }

    private fun computeWeight(player: Player): Int {
        val lp = api ?: return 0
        return try {
            val user = lp.userManager.getUser(player.uniqueId) ?: return 0
            user.getInheritedGroups(user.queryOptions)
                .filter { it.weight.isPresent }
                .maxOfOrNull { it.weight.asInt } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun getSuffix(player: Player): String {
        if (!TabListConfig.luckPermsEnabled) return ""
        val lp = api ?: return ""
        return try {
            val raw = lp.getPlayerAdapter(Player::class.java).getMetaData(player).suffix ?: return ""
            convertMiniMessageHex(raw)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Converts MiniMessage hex tags like <#FF5500> or <color:#FF5500>
     * into legacy &#RRGGBB format that translateLegacy() can then process.
     */
    private fun convertMiniMessageHex(input: String): String {
        var result = input
        result = REGEX_MM_HEX.replace(result, "&#$1")
        result = REGEX_MM_COLOR_HEX.replace(result, "&#$1")
        result = REGEX_MM_TAGS.replace(result, "")
        return result
    }
}
