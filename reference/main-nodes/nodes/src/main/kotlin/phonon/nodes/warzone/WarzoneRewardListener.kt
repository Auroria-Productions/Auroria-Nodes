package phonon.nodes.warzone

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import java.util.UUID

private data class RewardEditor(
    val playerId: UUID,
    val warzoneName: String,
    val inventory: Inventory,
)

public object WarzoneRewardListener : Listener {
    private val editors: MutableMap<UUID, RewardEditor> = hashMapOf()

    public fun openEditor(player: org.bukkit.entity.Player, warzone: Warzone) {
        val inventory = Bukkit.createInventory(null, 27, "Warzone Reward: ${warzone.name}")
        val items = warzone.config.rewardCopy()
        for ((idx, item) in items.withIndex()) {
            if (idx < inventory.size) {
                inventory.setItem(idx, item.clone())
            }
        }
        editors[player.uniqueId] = RewardEditor(player.uniqueId, warzone.name, inventory)
        player.openInventory(inventory)
    }

    @EventHandler
    public fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player
        val editor = editors[player.uniqueId] ?: return
        if (event.inventory !== editor.inventory) {
            return
        }

        val contents = editor.inventory.contents
        val rewardItems = contents.filterNotNull().map { it.clone() }
        WarzoneManager.setRewardItems(editor.warzoneName, rewardItems)
        editors.remove(player.uniqueId)
    }

    @EventHandler
    public fun onPlayerQuit(event: PlayerQuitEvent) {
        editors.remove(event.player.uniqueId)
    }
}
