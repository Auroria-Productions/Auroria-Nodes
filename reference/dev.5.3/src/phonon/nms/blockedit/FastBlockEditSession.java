package phonon.nms.blockedit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

public final class FastBlockEditSession {
    private record Position(int x, int y, int z) {}

    private final World world;
    private final Map<Position, Material> pending = new LinkedHashMap<>();

    public FastBlockEditSession(World world) {
        this.world = world;
    }

    public World getBukkitWorld() {
        return world;
    }

    public void setBlock(int x, int y, int z, Material material) {
        if (!material.isBlock()) {
            throw new IllegalArgumentException(material + " is not a block");
        }
        if (y >= world.getMinHeight() && y < world.getMaxHeight()) {
            pending.put(new Position(x, y, z), material);
        }
    }

    public Material getBlock(int x, int y, int z) {
        var material = pending.get(new Position(x, y, z));
        return material != null ? material : world.getBlockAt(x, y, z).getType();
    }

    public void execute(boolean updateLighting) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Block updates must run on the Paper server thread");
        }
        // Paper handles lighting and client updates for Bukkit block mutations.
        var changes = pending.entrySet().iterator();
        while (changes.hasNext()) {
            var change = changes.next();
            var pos = change.getKey();
            world.getBlockAt(pos.x(), pos.y(), pos.z()).setType(change.getValue(), false);
            changes.remove();
        }
    }
}
