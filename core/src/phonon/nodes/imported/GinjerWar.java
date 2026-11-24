package phonon.nodes.imported;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.event.*;
import phonon.nodes.*;
import phonon.nodes.event.WarAttackStartEvent;
import phonon.nodes.event.WarAttackFinishEvent;
import phonon.nodes.objects.*;
import phonon.nodes.supply.SupplyLines;
import phonon.nodes.war.FlagWar;
import phonon.nodes.war.Raid;

public final class GinjerWar implements Listener {
    private static final ThreadLocal<Map<Town, Set<?>>> incomeRoutes = new ThreadLocal<>();

    public static boolean flagsAllowed(FlagWar war, TerritoryChunk chunk) {
        return war.getEnabled$nodes() || chunk != null && Raid.INSTANCE.isLiveTerritory(chunk.getTerritory());
    }

    public static boolean enemy(FlagWar war, TerritoryChunk chunk, Territory territory, Town town) {
        var state = Raid.INSTANCE.getLive(territory);
        if (state == null) return war.chunkIsEnemy$nodes(chunk, territory, town);
        return Raid.INSTANCE.canRaidAttack(town, territory) || Raid.INSTANCE.canRaidRetake(town, chunk);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void attackStarted(WarAttackStartEvent event) {
        var attackMap = Nodes.INSTANCE.getWar().getChunkToAttacker$nodes();
        var config = ForkFeatures.config();
        int global = config.getInt("maxConcurrentAttacks");
        int town = config.getInt("maxAttacksPerTown");
        String denial = null;
        if (global > 0 && attackMap.size() >= global) denial = "The server's simultaneous flag limit has been reached.";
        else if (town > 0 && attackMap.values().stream().filter(a -> a.getTown() == event.getAttackingTown()).count() >= town) {
            denial = "Your town's simultaneous flag limit has been reached.";
        }
        if (denial == null) return;
        event.setCancelled(true);
        var player = org.bukkit.Bukkit.getPlayer(event.getAttacker());
        if (player != null) Message.INSTANCE.error(player, denial);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void attackFinished(WarAttackFinishEvent event) {
        var territory = event.getTerritory();
        var state = Raid.INSTANCE.getLive(territory);
        if (state == null) return;
        var block = event.getBlock();
        var chunk = Nodes.INSTANCE.getTerritoryChunkFromBlock(block.getX(), block.getZ());
        if (chunk == null || chunk.getTerritory() != territory) return;
        if (event.getAttackingTown() == state.getAttacker() && chunk.getCoord().equals(territory.getCore())) {
            Raid.INSTANCE.onCoreCaptured(state.getAttacker(), territory);
            event.setCancelled(true);
        } else if (Raid.INSTANCE.canRaidRetake(event.getAttackingTown(), chunk)) {
            chunk.setOccupier(null);
            Nodes.INSTANCE.getWar().getOccupiedChunks$nodes().remove(chunk.getCoord());
            Nodes.INSTANCE.renderMinimaps();
            event.setCancelled(true);
        }
    }

    public static long captureTicks(long ticks, TerritoryChunk chunk, Town attacker) {
        var territory = chunk.getTerritory();
        var owner = territory.getTown();
        if (owner == null || territory.getOccupier() != null || SupplyLines.INSTANCE.isFriendly(owner, attacker)) return ticks;
        if (!cutOff(owner, territory)) return ticks;
        return Math.max(1, (long) (ticks * SupplyLines.INSTANCE.captureTimeMultiplier()));
    }

    public static boolean skipInstantAnnex(boolean warzoneTown) {
        return warzoneTown || !ForkFeatures.config().getBoolean("instantAnnexOnCapture", true);
    }

    public static void beginIncome() { incomeRoutes.set(new IdentityHashMap<>()); }
    public static void endIncome() { incomeRoutes.remove(); }

    public static EnumMap<Material, Double> income(EnumMap<Material, Double> rates, Territory territory, Town owner) {
        if (rates.isEmpty() || !ForkCompatibility.getSupplyLinesEnabled(null)) return rates;
        var cache = incomeRoutes.get();
        var supplied = cache == null ? SupplyLines.INSTANCE.connected(owner)
            : cache.computeIfAbsent(owner, town -> SupplyLines.INSTANCE.connected(town));
        if (supplied.contains(boxId(territory))) return rates;
        var scaled = new EnumMap<Material, Double>(Material.class);
        double multiplier = SupplyLines.INSTANCE.incomeMultiplier();
        rates.forEach((material, rate) -> scaled.put(material, rate * multiplier));
        return scaled;
    }

    // These bridges are supplied by the build for Kotlin inline territory IDs.
    public static native TerritoryId boxId(Territory territory);
    public static native boolean cutOff(Town town, Territory territory);
}
