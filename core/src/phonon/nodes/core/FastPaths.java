package phonon.nodes.core;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import phonon.nodes.Nodes;
import phonon.nodes.objects.Coord;
import phonon.nodes.war.Attack;

public final class FastPaths {
    private FastPaths() {}

    public static boolean worldEvent(Event event) {
        var core = CoreFeatures.instance;
        if (core == null) return true;
        World world = switch (event) {
            case BlockEvent block -> block.getBlock().getWorld();
            case PlayerEvent player -> player.getPlayer().getWorld();
            case EntityEvent entity -> entity.getEntity().getWorld();
            default -> null;
        };
        return world == null || world.getName().equals(core.config.getString("selectedWorld", "world"));
    }

    public static boolean flagged(Location location) {
        return Nodes.INSTANCE.getWar().getChunkToAttacker$nodes()
            .containsKey(new Coord(location.getBlockX() >> 4, location.getBlockZ() >> 4));
    }

    public static void accelerate(Object attack) {
        var core = CoreFeatures.instance;
        if (core == null || !core.bool("playersInChunkMultiplier", false)) return;

        var active = (Attack) attack;
        var base = active.getFlagBase();
        int chunkX = base.getX() >> 4;
        int chunkZ = base.getZ() >> 4;
        int count = 0;
        var location = new Location(base.getWorld(), 0, 0, 0);
        for (var player : base.getWorld().getPlayers()) {
            var here = player.getLocation(location);
            if ((here.getBlockX() >> 4) == chunkX && (here.getBlockZ() >> 4) == chunkZ
                && Nodes.INSTANCE.getTownFromPlayer(player) == active.getTown()) {
                count++;
            }
        }
        if (count > 1) {
            active.setProgress(Math.min(active.getAttackTime(), active.getProgress() + (count - 1) * 20L));
        }
    }
}
