package phonon.nodes.nametags

import org.bukkit.Bukkit
import phonon.nodes.nms.sendTeamRemovePlayers
import phonon.nodes.objects.Nametag
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Town

object NametagUtils {

    /**
     * Call this when a resident's town changes (join OR leave).
     *
     * [oldTown] must be the town the resident belonged to BEFORE the change,
     * captured by the caller before clearing resident.town.
     *
     * FIX Bug 2: when a player leaves a town the client still has their name
     * inside the old team, so the prefix text stays visible even though the
     * relation/colour is now wrong. We must explicitly send a
     * sendTeamRemovePlayers packet to every online client to evict the player
     * from the old team before the burst rebuild re-adds them to whichever
     * team (or no team) they now belong to.
     */
    fun onPlayerTownChange(resident: Resident, oldTown: Town? = null) {
        val player = resident.player() ?: return

        // Remove the player's name from the old team on every existing client.
        // This must happen BEFORE rebuildTeamSnapshot() and the burst pipeline,
        // otherwise the client temporarily shows both the old and new prefix.
        if (oldTown != null) {
            val oldTeamId = "t${oldTown.townNametagId}"
            val playerNameList = listOf(player.name)
            for (other in Bukkit.getOnlinePlayers()) {
                if (other.uniqueId == player.uniqueId) continue
                try {
                    other.sendTeamRemovePlayers(oldTeamId, playerNameList)
                } catch (_: Exception) {}
            }
        }

        // Snapshot must be rebuilt — player moved teams
        Nametag.rebuildTeamSnapshot()

        Nametag.markPriorityAndFlush(player)

        val others = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != player.uniqueId }
        Nametag.markBurstAndFlush(others)
    }

    fun onTownDataChange(town: Town) {
        // No nametag packets — prefix strings unaffected by name change
    }

    fun onTownColorChange(town: Town) {
        val nation = town.nation
        if (nation != null && nation.capital == town) {
        } else {
        }
        // Components cached on Town are now stale — Town must rebuild them before
        // we pipeline (Town.onColorChange() should call buildNametagComponent() for
        // each of its five relation strings and store the results)
        Nametag.markBurstAll()
        Nametag.flushBurst()
    }

    fun onNationMembershipChange(nation: Nation) {
        val nationPlayerUuids = HashSet<java.util.UUID>()
        nation.towns.forEach { town ->
            town.playersOnline.forEach { player ->
                Nametag.markPriority(player)
                nationPlayerUuids.add(player.uniqueId)
            }
        }
        // Nation membership changes team composition — rebuild snapshot
        Nametag.rebuildTeamSnapshot()
        Nametag.flushPriority()

        val others = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId !in nationPlayerUuids }
        Nametag.markBurstAndFlush(others)
    }

    fun onTownAllianceChange(town1: Town, town2: Town) {
        val affected = (town1.playersOnline + town2.playersOnline).toSet()
        val affectedUuids = affected.mapTo(HashSet()) { it.uniqueId }

        affected.forEach { player ->
            Nametag.markPriority(player)
        }
        Nametag.flushPriority()

        val others = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId !in affectedUuids }
        Nametag.markBurstAndFlush(others)
    }

    fun onNationCapitalChange(nation: Nation) {
        Nametag.markBurstAll()
        Nametag.flushBurst()
    }
}
