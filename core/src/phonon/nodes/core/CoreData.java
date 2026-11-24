package phonon.nodes.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

final class CoreData {
    final Path file;
    final YamlConfiguration yaml;
    private final StateYaml state;

    CoreData(Path file) throws Exception {
        this.file = file;
        state = new StateYaml();
        yaml = state;
        if (Files.exists(file)) {
            state.load(file.toFile());
            state.dirty = false;
        }
    }

    synchronized void save() {
        if (!state.dirty) return;

        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "core-", ".tmp");
            try {
                Files.writeString(temp, state.saveToString(), StandardCharsets.UTF_8);
                try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                if (Files.exists(file)) {
                    Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                state.dirty = false;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Nodes core state", e);
        }
    }

    static void setSection(ConfigurationSection section, String key, Object value) {
        if (section.getRoot() instanceof StateYaml root) {
            String parent = section.getCurrentPath();
            if (parent != null) {
                root.set(parent.isEmpty() ? key : parent + "." + key, value);
                return;
            }
        }
        section.set(key, value);
    }

    private static final class StateYaml extends YamlConfiguration {
        private boolean dirty = true;

        @Override
        public void set(String path, Object value) {
            if (Objects.equals(get(path), value)) return;
            super.set(path, value);
            dirty = true;
        }
    }
}
