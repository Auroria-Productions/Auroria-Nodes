package phonon.nodes.tab

import net.minecraft.ChatFormatting
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

object TabListConfig {

    var enabled = true

    var pipelineTicks = 10
    var updateIntervalTicks = 100L

    var refreshOnJoin = true
    var refreshOthersOnJoin = true
    var joinRefreshDelayTicks = 2L

    var showTownTag = true
    var tagOpen = "["
    var tagClose = "] "

    var luckPermsEnabled = true
    var luckPermsSuffixEnabled = false
    var luckPermsSeparator = ""

    var teamPrefix = "tp" // keep short (2 chars ideal)

    // ── Header / Footer ─────────────────────────────────────────────────────

    var headerEnabled = true

    /**
     * Lines joined with \n before sending.
     * Supports legacy colour codes (&a, &#RRGGBB).
     * Placeholders resolved at send-time:
     *   {player}  – viewer's name
     *   {town}    – viewer's town, or the townless tag
     *   {nation}  – viewer's nation, or ""
     *   {online}  – current online player count
     */
    var headerLines: List<String> = listOf(
        "&6&lNODES",
        "&7{town}",
    )

    var footerEnabled = true
    var footerLines: List<String> = listOf(
        "&7Online: &f{online}",
    )

    // ── Column / Section Headers ────────────────────────────────────────────

    var staffEnabled = true
    var staffMinWeight = 50
    var staffHeader = "&6&lStaff "

    var townEnabled = true
    var townHeader = "&a&lTown / Nation "

    var allyEnabled = true
    var allyHeader = "&e&lAllies "

    var enemyEnabled = true
    var enemyHeader = "&c&lEnemies "

    var neutralEnabled = true
    var neutralHeader = "&7&lEveryone else "

    // ── Town tag relation styles ────────────────────────────────────────────

    data class RelationStyle(val tagColor: ChatFormatting, val bold: Boolean)
    data class TownlessStyle(val tagColor: ChatFormatting, val tagText: String, val bold: Boolean)

    var self = RelationStyle(ChatFormatting.GREEN, false)
    var nation = RelationStyle(ChatFormatting.AQUA, false)
    var ally = RelationStyle(ChatFormatting.YELLOW, false)
    var enemy = RelationStyle(ChatFormatting.RED, false)
    var neutral = RelationStyle(ChatFormatting.WHITE, false)
    var townless = TownlessStyle(ChatFormatting.GRAY, "Wild", false)

    // ── Townless display text (used in header {town} placeholder) ───────────
    var townlessDisplayText = "&7[Wild]"

    fun load(section: ConfigurationSection?) {
        section ?: return

        enabled = section.getBoolean("enabled", enabled)
        pipelineTicks = section.getInt("pipeline-ticks", pipelineTicks)
        updateIntervalTicks = section.getLong("update-interval-ticks", updateIntervalTicks)
        refreshOnJoin = section.getBoolean("refresh-on-join", refreshOnJoin)
        refreshOthersOnJoin = section.getBoolean("refresh-others-on-join", refreshOthersOnJoin)
        joinRefreshDelayTicks = section.getLong("join-refresh-delay-ticks", joinRefreshDelayTicks)

        teamPrefix = section.getString("team-prefix", teamPrefix)!!

        showTownTag = section.getBoolean("show-town-tag", showTownTag)
        tagOpen = section.getString("tag-open", tagOpen) ?: tagOpen
        tagClose = section.getString("tag-close", tagClose) ?: tagClose

        luckPermsEnabled = section.getBoolean("luckperms.enabled", luckPermsEnabled)
        luckPermsSuffixEnabled = section.getBoolean("luckperms.show-suffix", luckPermsSuffixEnabled)
        luckPermsSeparator = section.getString("luckperms.separator", luckPermsSeparator) ?: ""

        // header / footer
        headerEnabled = section.getBoolean("header.enabled", headerEnabled)
        section.getStringList("header.lines").takeIf { it.isNotEmpty() }?.let { headerLines = it }

        footerEnabled = section.getBoolean("footer.enabled", footerEnabled)
        section.getStringList("footer.lines").takeIf { it.isNotEmpty() }?.let { footerLines = it }

        townlessDisplayText = section.getString("header.townless-text", townlessDisplayText) ?: townlessDisplayText

        staffEnabled = section.getBoolean("groups.staff.enabled", staffEnabled)
        staffMinWeight = section.getInt("groups.staff.min-weight", staffMinWeight)
        staffHeader = section.getString("groups.staff.header", staffHeader) ?: staffHeader

        townEnabled = section.getBoolean("groups.town.enabled", townEnabled)
        townHeader = section.getString("groups.town.header", townHeader) ?: townHeader

        allyEnabled = section.getBoolean("groups.ally.enabled", allyEnabled)
        allyHeader = section.getString("groups.ally.header", allyHeader) ?: allyHeader

        enemyEnabled = section.getBoolean("groups.enemy.enabled", enemyEnabled)
        enemyHeader = section.getString("groups.enemy.header", enemyHeader) ?: enemyHeader

        neutralEnabled = section.getBoolean("groups.neutral.enabled", neutralEnabled)
        neutralHeader = section.getString("groups.neutral.header", neutralHeader) ?: neutralHeader

        townless = loadTownlessStyle(section.getConfigurationSection("townless"), townless)
        self = loadRelationStyle(section.getConfigurationSection("relations.self"), self)
        nation = loadRelationStyle(section.getConfigurationSection("relations.nation"), nation)
        ally = loadRelationStyle(section.getConfigurationSection("relations.ally"), ally)
        enemy = loadRelationStyle(section.getConfigurationSection("relations.enemy"), enemy)
        neutral = loadRelationStyle(section.getConfigurationSection("relations.neutral"), neutral)
    }

    /**
     * Registers defaults on the ROOT FileConfiguration using full "tablist.*"
     * key paths. This is the same pattern ContractConfig uses — addDefault()
     * only propagates to disk when called on the root config, not on a child
     * ConfigurationSection, so never pass a sub-section here.
     *
     * In NodesPlugin call as:  TabListConfig.setDefaults(this.config)
     */
    fun setDefaults(config: FileConfiguration) {
        val p = "tablist"
        config.addDefault("$p.enabled", true)
        config.addDefault("$p.pipeline-ticks", 10)
        config.addDefault("$p.update-interval-ticks", 100L)
        config.addDefault("$p.refresh-on-join", true)
        config.addDefault("$p.refresh-others-on-join", true)
        config.addDefault("$p.join-refresh-delay-ticks", 2L)
        config.addDefault("$p.team-prefix", "tp")
        config.addDefault("$p.show-town-tag", true)
        config.addDefault("$p.tag-open", "[")
        config.addDefault("$p.tag-close", "] ")
        config.addDefault("$p.luckperms.enabled", true)
        config.addDefault("$p.luckperms.show-suffix", false)
        config.addDefault("$p.luckperms.separator", "")

        // header / footer
        config.addDefault("$p.header.enabled", true)
        config.addDefault("$p.header.lines", listOf("&6&lNODES", "&7{town}"))
        config.addDefault("$p.header.townless-text", "&7[Wild]")
        config.addDefault("$p.footer.enabled", true)
        config.addDefault("$p.footer.lines", listOf("&7Online: &f{online}"))

        config.addDefault("$p.groups.staff.enabled", true)
        config.addDefault("$p.groups.staff.min-weight", 50)
        config.addDefault("$p.groups.staff.header", "&6&lStaff ")
        config.addDefault("$p.groups.town.enabled", true)
        config.addDefault("$p.groups.town.header", "&a&lTown / Nation ")
        config.addDefault("$p.groups.ally.enabled", true)
        config.addDefault("$p.groups.ally.header", "&e&lAllies ")
        config.addDefault("$p.groups.enemy.enabled", true)
        config.addDefault("$p.groups.enemy.header", "&c&lEnemies ")
        config.addDefault("$p.groups.neutral.enabled", true)
        config.addDefault("$p.groups.neutral.header", "&7&lEveryone else ")

        config.addDefault("$p.townless.tag-color", "GRAY")
        config.addDefault("$p.townless.tag-text", "Wild")
        config.addDefault("$p.townless.bold", false)

        config.addDefault("$p.relations.self.tag-color", "GREEN")
        config.addDefault("$p.relations.self.bold", false)
        config.addDefault("$p.relations.nation.tag-color", "AQUA")
        config.addDefault("$p.relations.nation.bold", false)
        config.addDefault("$p.relations.ally.tag-color", "YELLOW")
        config.addDefault("$p.relations.ally.bold", false)
        config.addDefault("$p.relations.enemy.tag-color", "RED")
        config.addDefault("$p.relations.enemy.bold", false)
        config.addDefault("$p.relations.neutral.tag-color", "WHITE")
        config.addDefault("$p.relations.neutral.bold", false)
    }

    private fun loadRelationStyle(sec: ConfigurationSection?, fallback: RelationStyle): RelationStyle {
        sec ?: return fallback
        return RelationStyle(
            tagColor = parseChatFormatting(sec.getString("tag-color"), fallback.tagColor),
            bold = sec.getBoolean("bold", fallback.bold),
        )
    }

    private fun loadTownlessStyle(sec: ConfigurationSection?, fallback: TownlessStyle): TownlessStyle {
        sec ?: return fallback
        return TownlessStyle(
            tagColor = parseChatFormatting(sec.getString("tag-color"), fallback.tagColor),
            tagText = sec.getString("tag-text", fallback.tagText) ?: fallback.tagText,
            bold = sec.getBoolean("bold", fallback.bold),
        )
    }

    private fun parseChatFormatting(name: String?, fallback: ChatFormatting): ChatFormatting {
        name ?: return fallback
        return try {
            ChatFormatting.valueOf(name.uppercase())
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }
}
