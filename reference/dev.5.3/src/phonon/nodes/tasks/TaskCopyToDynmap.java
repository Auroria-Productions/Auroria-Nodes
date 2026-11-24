package phonon.nodes.tasks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;

public final class TaskCopyToDynmap implements Runnable {
    private final Path source;
    private final Path directory;
    private final Path target;

    public TaskCopyToDynmap(Path source, Path directory, Path target) {
        this.source = source;
        this.directory = directory;
        this.target = target;
    }

    public Path getPathTownsJson() { return source; }
    public Path getPathDynmapDir() { return directory; }
    public Path getPathDynmapJson() { return target; }

    @Override
    public void run() {
        var output = target.toAbsolutePath().normalize();
        var file = output.getFileName().toString();
        boolean warzones = file.equals("warzones.json");
        if (!warzones && (!Files.isRegularFile(source)
            || source.toAbsolutePath().normalize().equals(output))) {
            return;
        }

        try {
            Files.createDirectories(directory);
            var parent = Files.createDirectories(output.getParent());
            var temp = Files.createTempFile(parent, "nodes-export-", ".tmp");
            try {
                if (file.equalsIgnoreCase("world.json")) {
                    try (var reader = Files.newBufferedReader(source)) {
                        var world = JsonParser.parseReader(reader).getAsJsonObject();
                        DynmapData.injectNodeTypes(world);
                        Files.writeString(temp, new GsonBuilder().disableHtmlEscaping().create().toJson(world));
                    }
                } else if (warzones) {
                    Files.writeString(temp, new GsonBuilder().disableHtmlEscaping().create()
                        .toJson(DynmapData.warzones()));
                } else {
                    Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (NoSuchFileException e) {
            // The first save can overlap the export. Preserve the previous export until the next tick.
            if (Files.isRegularFile(source) || warzones) warn(e);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            warn(e);
        }
    }

    private void warn(Exception e) {
        Bukkit.getLogger().log(Level.WARNING, "[nodes] Could not export " + target + " to Dynmap", e);
    }
}
