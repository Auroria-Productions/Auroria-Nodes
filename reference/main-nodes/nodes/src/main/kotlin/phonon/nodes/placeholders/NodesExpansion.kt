package phonon.nodes.placeholders

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.md_5.bungee.api.ChatColor
import org.bukkit.entity.Player
import phonon.nodes.Nodes
import phonon.nodes.utils.Color
import phonon.nodes.war.WarAccessListSnapshot
import phonon.nodes.war.annexBlacklist
import phonon.nodes.war.blacklist
import phonon.nodes.war.whitelist

/**
 * PlaceholderAPI expansion for Nodes plugin
 * Provides placeholders for town/nation info with RGB colors
 */
class NodesExpansion : PlaceholderExpansion() {

    override fun getIdentifier(): String = "nodes"
    override fun getAuthor(): String = "YourName"
    override fun getVersion(): String = "1.0.5"
    override fun persist(): Boolean = true
    override fun canRegister(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, identifier: String): String? {
        if (!phonon.nodes.core.CoreHooks.papi() || player == null) return null

        val resident = Nodes.getResident(player) ?: return null
        val town = resident.town

        val idLower = identifier.lowercase()

        return when {
            // Basic town info
            idLower == "town_name" -> town?.name ?: ""
            idLower == "town_leader" -> town?.leader?.name ?: "None"

            idLower == "town_population" -> town?.residents?.size?.toString() ?: "0"
            idLower == "town_claims" -> town?.territories?.size?.toString() ?: "0"

            // Nation info
            idLower == "nation_name" -> town?.nation?.name ?: "None"
            idLower == "nation_capital" -> town?.nation?.capital?.name ?: "None"

            // RGB hex color (matches tab list color)

            idLower == "town_color" -> {
                if (town == null) {
                    val grayColor = ChatColor.of("#808080")
                    "$grayColor"
                } else {
                    val nation = town.nation
                    if (nation != null) {
                        // Use nation capital's color
                        val capital = nation.capital
                        val hexColor = rgbToHexColor(capital.color)
                        "$hexColor"
                    } else {
                        // Use town's own color
                        val hexColor = rgbToHexColor(town.color)
                        "$hexColor"
                    }
                }
            }

            // Display name (what shows in tab/nametag)
            idLower == "display_name" -> {
                if (town == null) {
                    "Wilderness"
                } else {
                    town.nation?.name ?: town.name
                }
            }

            idLower == "nation" -> {
                resident.nation?.name ?: ""
            }
            idLower == "warzone_count" -> {
                Nodes.getWarzoneCount().toString()
            }
            idLower == "war_whitelist_size" -> {
                Nodes.war.whitelist.size.toString()
            }
            idLower == "war_blacklist_size" -> {
                Nodes.war.blacklist.size.toString()
            }
            idLower == "war_annex_size" -> {
                Nodes.war.annexBlacklist.size.toString()
            }
            idLower == "war_whitelist_entries" -> {
                formatWarAccessEntries(Nodes.war.whitelist)
            }
            idLower == "war_blacklist_entries" -> {
                formatWarAccessEntries(Nodes.war.blacklist)
            }
            idLower == "war_annex_entries" -> {
                formatWarAccessEntries(Nodes.war.annexBlacklist)
            }

            else -> null
        }
    }

    /**
     * Convert RGB color to hex ChatColor (matches TabIntegration logic)
     */
    private fun rgbToHexColor(color: Color): ChatColor {
        val r = color.r.coerceIn(0, 255)
        val g = color.g.coerceIn(0, 255)
        val b = color.b.coerceIn(0, 255)

        val hex = String.format("#%02x%02x%02x", r, g, b)
        return ChatColor.of(hex)
    }
}

private fun formatWarAccessEntries(snapshot: WarAccessListSnapshot): String {
    val entries = snapshot.list
    if (entries.isEmpty()) {
        return "none"
    }
    return entries.joinToString(", ") { it.name }
}
