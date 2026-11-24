package phonon.nodes.core;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.plugin.java.JavaPlugin;

public final class DynmapExportFiles {
    private DynmapExportFiles() {}

    public static Path resolve(Path dataDirectory, String file, JavaPlugin plugin) {
        var dataFile = dataDirectory.resolve(file);
        if (Files.isRegularFile(dataFile)) return dataFile;

        var config = plugin.getConfig();
        var directory = Path.of(config.getString("dynmapdir", "plugins/dynmap/web/nodes"));
        var key = switch (file) {
            case "config.json" -> "dynmapnodesconfig";
            case "world.json" -> "dynmapworld";
            case "towns.json" -> "dynmaptowns";
            case "nameoverrides.json" -> "dynmapNameoverrides";
            default -> null;
        };
        if (key != null) {
            var configured = config.getString(key);
            if (configured != null && !configured.isBlank()) {
                var path = Path.of(configured);
                if (Files.isRegularFile(path)) return path;
            }
        }

        return directory.resolve(file);
    }
}
