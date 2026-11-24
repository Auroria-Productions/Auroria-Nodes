package phonon.nodes.compat;

import java.io.IOException;
import java.nio.file.*;

import org.bukkit.plugin.java.JavaPlugin;

public final class PluginIdentity {
    private PluginIdentity() {}

    public static void prepareCore(JavaPlugin plugin) {
        prepareData(plugin, "nodes");
        LegacyCommands.register(plugin, "nodes");
    }

    public static void prepareData(JavaPlugin plugin, String oldName) {
        Path target = plugin.getDataFolder().toPath();
        Path original = target.resolveSibling(oldName);
        if (!Files.isDirectory(original) || Files.exists(target)) return;
        try {
            Files.move(original, target);
            plugin.getLogger().info("Moved " + oldName + " data to " + target.getFileName());
        } catch (IOException e) {
            throw new IllegalStateException("Could not move " + original + " to " + target, e);
        }
    }

}
