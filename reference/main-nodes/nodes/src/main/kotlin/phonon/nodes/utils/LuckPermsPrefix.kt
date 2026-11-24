package phonon.nodes.utils

import me.clip.placeholderapi.PlaceholderAPI
import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object LuckPermsPrefix {

    private val cache: MutableMap<UUID, Pair<Long, String>> = ConcurrentHashMap()

    fun resolve(player: Player, placeholder: String, cacheMs: Long): String {
        val now = System.currentTimeMillis()
        val cached = cache[player.uniqueId]
        if (cached != null && now < cached.first) {
            return cached.second
        }

        val fromPapi = resolveFromPlaceholderApi(player, placeholder)
        if (fromPapi.isNotBlank()) {
            val colored = colorize(fromPapi)
            cache[player.uniqueId] = Pair(now + cacheMs.coerceAtLeast(1000L), colored)
            return colored
        }

        val fromLuckPerms = resolveFromLuckPerms(player)
        val colored = colorize(fromLuckPerms)
        cache[player.uniqueId] = Pair(now + cacheMs.coerceAtLeast(1000L), colored)
        return colored
    }

    fun cleanupOnline(onlineIds: Set<UUID>) {
        cache.keys.removeIf { !onlineIds.contains(it) }
    }

    private fun resolveFromPlaceholderApi(player: Player, placeholder: String): String {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return ""
        }
        val value = PlaceholderAPI.setPlaceholders(player, placeholder).trim()
        if (value.isBlank()) return ""
        if (value == placeholder) return ""
        if (value.startsWith("%") && value.endsWith("%")) return ""
        return value
    }

    private fun resolveFromLuckPerms(player: Player): String {
        return try {
            val providerClass = Class.forName("net.luckperms.api.LuckPermsProvider")
            val getMethod = providerClass.getMethod("get")
            val luckPerms = getMethod.invoke(null) ?: return ""
            val userManager = luckPerms.javaClass.getMethod("getUserManager").invoke(luckPerms) ?: return ""
            val user = userManager.javaClass.getMethod("getUser", UUID::class.java).invoke(userManager, player.uniqueId) ?: return ""
            val cachedData = user.javaClass.getMethod("getCachedData").invoke(user) ?: return ""
            val metaData = cachedData.javaClass.getMethod("getMetaData").invoke(cachedData) ?: return ""
            (metaData.javaClass.getMethod("getPrefix").invoke(metaData) as? String)?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun colorize(input: String): String {
        if (input.isBlank()) return ""
        var out = input

        // Supports styles like {#55c6ff} or <#55c6ff> as seen in many LP setups.
        val braceHex = Regex("\\{#([A-Fa-f0-9]{6})}")
        out = braceHex.replace(out) { m -> ChatColor.of("#${m.groupValues[1]}").toString() }
        val angleHex = Regex("<#([A-Fa-f0-9]{6})>")
        out = angleHex.replace(out) { m -> ChatColor.of("#${m.groupValues[1]}").toString() }

        // Supports &#55c6ff.
        val ampHex = Regex("&\\#([A-Fa-f0-9]{6})")
        out = ampHex.replace(out) { m -> ChatColor.of("#${m.groupValues[1]}").toString() }

        // Supports legacy &a &l &r.
        out = org.bukkit.ChatColor.translateAlternateColorCodes('&', out)
        return out
    }
}
