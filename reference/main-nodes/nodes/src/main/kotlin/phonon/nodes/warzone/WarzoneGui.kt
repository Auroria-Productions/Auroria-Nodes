package phonon.nodes.warzone

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.gui.Gui
import phonon.nodes.gui.GuiButton
import phonon.nodes.gui.GuiColor
import phonon.nodes.gui.GuiElement
import phonon.nodes.gui.GuiFillColor
import phonon.nodes.gui.GuiLabel
import phonon.nodes.gui.GuiWindow

private const val GUI_SIZE = 54

public object WarzoneGui {

    public fun openMain(player: Player) {
        if (!player.hasPermission("nodes.admin")) {
            Message.error(player, "You do not have permission to manage warzones.")
            return
        }
        Gui.render(WarzoneMainView(player), player, GUI_SIZE, "Warzone Manager")
    }

    public fun openDetail(player: Player, warzone: Warzone?) {
        if (!player.hasPermission("nodes.admin")) {
            Message.error(player, "You do not have permission to manage warzones.")
            return
        }
        if (warzone == null) {
            Message.error(player, "That warzone no longer exists.")
            openMain(player)
            return
        }
        Gui.render(WarzoneDetailView(player, warzone), player, GUI_SIZE, "Warzone: ${warzone.name}")
    }

    private fun createIcon(material: Material, title: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName(title)
        meta?.setLore(lore)
        item.itemMeta = meta
        return item
    }

    private class WarzoneMainView(private val player: Player) : GuiElement {
        private val positions = listOf(
            1 to 1, 4 to 1, 7 to 1,
            1 to 2, 4 to 2, 7 to 2,
            1 to 3, 4 to 3, 7 to 3,
            1 to 4, 4 to 4, 7 to 4,
        )

        override fun render(screen: GuiWindow) {
            fillBackground(screen, GuiColor.GRAY)
            GuiButton(
                4,
                0,
                createIcon(Material.WRITABLE_BOOK, "Create warzone", listOf("Start a new warzone definition")),
                null,
                listOf("Click to name a new warzone"),
            ) { _ ->
                WarzoneManager.promptChatInput(player, "", WarzoneChatInputType.CREATE_NAME)
                player.closeInventory()
            }.render(screen)

            val warzones = WarzoneManager.getWarzones()
            if (warzones.isEmpty()) {
                GuiLabel(
                    3,
                    2,
                    createIcon(Material.GLASS, "No warzones", listOf("Use the create button above")),
                    "No warzones defined",
                    listOf("Click \"Create warzone\" to begin"),
                ).render(screen)
                return
            }

            for ((index, warzone) in warzones.withIndex()) {
                if (index >= positions.size) {
                    GuiLabel(
                        2,
                        5,
                        createIcon(Material.SPECTRAL_ARROW, "More warzones", listOf("Use /warzone <name> to view additional warzones")),
                        "Limiting display",
                        listOf("Showing the first ${positions.size} entries"),
                    ).render(screen)
                    break
                }
                val (x, y) = positions[index]
                val now = System.currentTimeMillis()
                val status = if (warzone.isActive(now)) "Active" else "Inactive"
                val nextStart = warzone.nextStartTime.takeIf { it > 0L }?.let {
                    WarzoneInfoPrinter.formatNextStart(warzone, now)
                } ?: "Not scheduled"
                val lore = listOf(
                    "Status: $status",
                    "Nodes: ${warzone.nodes.size}",
                    "Reward items: ${warzone.config.rewardItems.size}",
                    "Next start: $nextStart",
                )
                GuiButton(
                    x,
                    y,
                    createIcon(Material.COMPASS, warzone.name, lore),
                    null,
                    listOf("Open the ${warzone.name} management view"),
                ) { _ ->
                    openDetail(player, warzone)
                }.render(screen)
            }
        }
    }

    private class WarzoneDetailView(private val player: Player, private val warzone: Warzone) : GuiElement {

        override fun render(screen: GuiWindow) {
            fillBackground(screen, GuiColor.LIGHT_GRAY)
            val now = System.currentTimeMillis()
            val infoLore = warzoneInfoLore(now, warzone)
            GuiLabel(
                3,
                0,
                createIcon(Material.PAPER, warzone.name, infoLore),
                "Warzone info",
                infoLore,
            ).render(screen)

            GuiButton(
                7,
                0,
                createIcon(Material.ARROW, "Back", listOf("Return to warzone list")),
                null,
                listOf("Go back"),
            ) { _ ->
                openMain(player)
            }.render(screen)

            listOf(
                Action(
                    1,
                    1,
                    Material.LIME_DYE,
                    "Force start",
                    lore = listOf("Start immediately with configured duration"),
                    tooltip = listOf("Force start now"),
                ) {
                    if (WarzoneManager.forceStart(warzone.name, null)) {
                        Message.print(player, "Force-started ${warzone.name}.")
                    } else {
                        Message.error(player, "Cannot force-start (already active or no duration set).")
                    }
                    reopen()
                },
                Action(
                    3,
                    1,
                    Material.SAND,
                    "Clear duration",
                    lore = listOf("Clear default duration"),
                    tooltip = listOf("Set default duration to 0"),
                ) {
                    if (WarzoneManager.clearDefaultDuration(warzone.name)) {
                        Message.print(player, "Cleared default duration for ${warzone.name}.")
                    } else {
                        Message.error(player, "Failed to clear default duration.")
                    }
                    reopen()
                },
                Action(
                    5,
                    1,
                    Material.REDSTONE_TORCH,
                    "Remove nodes",
                    lore = listOf("Remove listed node IDs via chat"),
                    tooltip = listOf("Remove nodes via chat input"),
                ) {
                    WarzoneManager.promptChatInput(player, warzone.name, WarzoneChatInputType.NODE_REMOVE_LIST)
                },
                Action(
                    7,
                    1,
                    Material.BOOK,
                    "Replace nodes",
                    lore = listOf("Set full node list via chat"),
                    tooltip = listOf("Replace nodes via chat input"),
                ) {
                    WarzoneManager.promptChatInput(player, warzone.name, WarzoneChatInputType.NODE_REPLACE_LIST)
                },
                Action(
                    1,
                    2,
                    Material.CHEST,
                    "Edit reward",
                    lore = listOf("Configure reward inventory"),
                    tooltip = listOf("Configure reward inventory"),
                ) {
                    WarzoneRewardListener.openEditor(player, warzone)
                },
                Action(
                    3,
                    2,
                    Material.NETHER_STAR,
                    "Win mode",
                    lore = listOf("Current: ${formatWinMode(warzone.config.winMode)}", "Click to cycle win behavior"),
                    tooltip = listOf("Cycle win behavior"),
                ) {
                    val next = when (warzone.config.winMode) {
                        WarzoneWinMode.REWARD -> WarzoneWinMode.REWARD_AND_NODES
                        WarzoneWinMode.REWARD_AND_NODES -> WarzoneWinMode.NODES
                        WarzoneWinMode.NODES -> WarzoneWinMode.REWARD
                    }
                    WarzoneManager.setWinMode(warzone.name, next)
                    Message.print(player, "Win mode is now ${formatWinMode(next)}.")
                    reopen()
                },
                Action(
                    4,
                    2,
                    Material.OAK_SIGN,
                    "Edit nodes",
                    lore = listOf("Add nodes via chat"),
                    tooltip = listOf("Add nodes via chat"),
                ) {
                    WarzoneManager.promptChatInput(player, warzone.name, WarzoneChatInputType.NODE_LIST)
                },
                Action(
                    7,
                    2,
                    Material.CLOCK,
                    "Set start",
                    lore = listOf("Schedule start time via chat"),
                    tooltip = listOf("Schedule start time via chat"),
                ) {
                    WarzoneManager.promptChatInput(player, warzone.name, WarzoneChatInputType.START_TIME)
                },
                Action(
                    1,
                    3,
                    Material.CLOCK,
                    "Extend +30m",
                    lore = listOf("Add 30 minutes"),
                    tooltip = listOf("Add 30 minutes"),
                ) {
                    val wasActive = warzone.isActive(System.currentTimeMillis())
                    if (WarzoneManager.extendActive(warzone.name, 30 * 60 * 1000L)) {
                        if (wasActive) {
                            Message.print(player, "Extended ${warzone.name} by 30 minutes.")
                        } else {
                            Message.print(player, "Default duration increased by 30 minutes.")
                        }
                    }
                    reopen()
                },
                Action(
                    4,
                    3,
                    Material.CLOCK,
                    "Extend +1h",
                    lore = listOf("Add 60 minutes"),
                    tooltip = listOf("Add 60 minutes"),
                ) {
                    val wasActive = warzone.isActive(System.currentTimeMillis())
                    if (WarzoneManager.extendActive(warzone.name, 60 * 60 * 1000L)) {
                        if (wasActive) {
                            Message.print(player, "Extended ${warzone.name} by 1 hour.")
                        } else {
                            Message.print(player, "Default duration increased by 1 hour.")
                        }
                    }
                    reopen()
                },
                Action(
                    7,
                    3,
                    Material.BARRIER,
                    "End early",
                    lore = listOf("Force-wrap current warzone"),
                    tooltip = listOf("Force-wrap current warzone"),
                ) {
                    if (WarzoneManager.endWarzoneEarly(warzone.name)) {
                        Message.print(player, "${warzone.name} ended early.")
                    } else {
                        Message.error(player, "Warzone is not active.")
                    }
                    reopen()
                },
                Action(
                    1,
                    4,
                    Material.PAPER,
                    "View leaderboard",
                    lore = listOf("Show leaderboard via chat"),
                    tooltip = listOf("Show leaderboard via chat"),
                ) {
                    WarzoneInfoPrinter.printSummary(player, warzone)
                },
                Action(
                    4,
                    4,
                    Material.SANDSTONE,
                    "Default +30m",
                    lore = listOf("Increase base duration by 30 minutes"),
                    tooltip = listOf("Increase base duration by 30 minutes"),
                ) {
                    val newMillis = warzone.config.durationMillis + 30 * 60 * 1000L
                    WarzoneManager.setDuration(warzone.name, newMillis)
                    WarzoneManager.getWarzone(warzone.name)?.let {
                        Message.print(player, "Default duration is now ${WarzoneInfoPrinter.formatDuration(it.config.durationMillis)}.")
                    }
                    reopen()
                },
                Action(
                    7,
                    4,
                    Material.SANDSTONE,
                    "Default +1h",
                    lore = listOf("Increase base duration by 1 hour"),
                    tooltip = listOf("Increase base duration by 1 hour"),
                ) {
                    val newMillis = warzone.config.durationMillis + 60 * 60 * 1000L
                    WarzoneManager.setDuration(warzone.name, newMillis)
                    WarzoneManager.getWarzone(warzone.name)?.let {
                        Message.print(player, "Default duration is now ${WarzoneInfoPrinter.formatDuration(it.config.durationMillis)}.")
                    }
                    reopen()
                },
                Action(
                    0,
                    5,
                    Material.BLUE_BANNER,
                    "Edit whitelist",
                    lore = listOf("Type nations (comma separated)"),
                    tooltip = listOf("Type nations (comma separated)"),
                ) {
                    WarzoneManager.promptChatInput(player, warzone.name, WarzoneChatInputType.WHITELIST)
                },
                Action(
                    3,
                    5,
                    Material.GRAY_BANNER,
                    "Disable whitelist",
                    lore = listOf("Clear whitelist access"),
                    tooltip = listOf("Clear whitelist access"),
                ) {
                    WarzoneManager.setWhitelist(warzone.name, false, emptyList())
                    Message.print(player, "Whitelist disabled for ${warzone.name}.")
                    reopen()
                },
                Action(
                    5,
                    5,
                    Material.RED_BANNER,
                    "Edit blacklist",
                    lore = listOf("Type nations (comma separated)"),
                    tooltip = listOf("Type nations (comma separated)"),
                ) {
                    WarzoneManager.promptChatInput(player, warzone.name, WarzoneChatInputType.BLACKLIST)
                },
                Action(
                    8,
                    5,
                    Material.BLACK_BANNER,
                    "Disable blacklist",
                    lore = listOf("Allow all nations"),
                    tooltip = listOf("Allow all nations"),
                ) {
                    WarzoneManager.setBlacklist(warzone.name, false, emptyList())
                    Message.print(player, "Blacklist disabled for ${warzone.name}.")
                    reopen()
                },
            ).forEach { action ->
                GuiButton(
                    action.x,
                    action.y,
                    createIcon(
                        action.material,
                        action.title,
                        action.lore,
                    ),
                    null,
                    action.tooltip,
                ) { _ ->
                    action.runnable()
                }.render(screen)
            }

            if (!warzone.isActive(now)) {
                GuiButton(
                    7,
                    5,
                    createIcon(Material.BARRIER, "Delete warzone", listOf("Click to remove this warzone")),
                    null,
                    listOf("Remove ${warzone.name}"),
                ) { _ ->
                    if (WarzoneManager.deleteWarzone(warzone.name)) {
                        Message.print(player, "${warzone.name} deleted.")
                        openMain(player)
                    } else {
                        Message.error(player, "Cannot delete active warzone.")
                        reopen()
                    }
                }.render(screen)
            } else {
                GuiLabel(
                    7,
                    5,
                    createIcon(Material.BARRIER, "Delete warzone", listOf("Warzone must be inactive to delete")),
                    "Deleting disabled",
                    listOf("End the warzone before deleting"),
                ).render(screen)
            }
        }

        private fun reopen() {
            openDetail(player, WarzoneManager.getWarzone(warzone.name))
        }

        private fun warzoneInfoLore(now: Long, warzone: Warzone): List<String> {
            val status = if (warzone.isActive(now)) {
                "Active (${WarzoneInfoPrinter.formatDuration(warzone.remainingMillis(now))} remaining)"
            } else {
                "Inactive"
            }
            val nextStart = if (warzone.nextStartTime > 0L) {
                WarzoneInfoPrinter.formatNextStart(warzone, now)
            } else {
                "Not scheduled"
            }
            return listOf(
                status,
                "Nodes: ${warzone.nodes.size}",
                "Win mode: ${formatWinMode(warzone.config.winMode)}",
                "Duration: ${WarzoneInfoPrinter.formatDuration(warzone.durationPreview())}",
                "Next start: $nextStart",
                (warzone.lastWinnerName?.let { "Last winner: $it" } ?: "No winner yet"),
            )
        }

        private fun formatWinMode(mode: WarzoneWinMode): String = when (mode) {
            WarzoneWinMode.REWARD -> "Reward"
            WarzoneWinMode.REWARD_AND_NODES -> "Reward + Nodes"
            WarzoneWinMode.NODES -> "Nodes"
        }

        private fun fillBackground(screen: GuiWindow, color: GuiColor) {
            for (y in 0 until screen.rows) {
                for (x in 0 until screen.cols) {
                    GuiFillColor(x, y, color).render(screen)
                }
            }
        }

        private data class Action(
            val x: Int,
            val y: Int,
            val material: Material,
            val title: String,
            val lore: List<String>,
            val tooltip: List<String> = lore,
            val runnable: () -> Unit,
        )
    }

    private fun fillBackground(screen: GuiWindow, color: GuiColor) {
        for (y in 0 until screen.rows) {
            for (x in 0 until screen.cols) {
                GuiFillColor(x, y, color).render(screen)
            }
        }
    }
}
