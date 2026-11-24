package phonon.nodes.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Migrates Ginjer JSON files to feature-scoped, authoritative live storage. */
public final class JsonStorageLayout {
    private JsonStorageLayout() {}

    // Keep this in step with the literal replacement table in PatchJar.java.
    public static final Map<String, String> FILES;
    static {
        var paths = new LinkedHashMap<String, String>();
        paths.put("calendar.json", "calendar/calendar.json");
        paths.put("pois.json", "exploration/pois.json");
        paths.put("discoveries.json", "exploration/discoveries.json");
        paths.put("skills.json", "skills/skills.json");
        paths.put("duels.json", "duels/duels.json");
        paths.put("bounties.json", "bounties/bounties.json");
        paths.put("economy.json", "economy/economy.json");
        paths.put("refineries.json", "refineries/refineries.json");
        paths.put("player-stats.json", "statistics/player-stats.json");
        paths.put("warSession.json", "statistics/warSession.json");
        paths.put("supplydrops.json", "supply/supplydrops.json");
        paths.put("mining-boosts.json", "mining/mining-boosts.json");
        paths.put("mining-towers.json", "mining/mining-towers.json");
        paths.put("surrender.json", "diplomacy/surrender.json");
        paths.put("townflags.json", "towns/townflags.json");
        paths.put("portpolicies.json", "ports/portpolicies.json");
        FILES = Collections.unmodifiableMap(paths);
    }

    public record Status(int activeFiles, int legacyFiles, int folders) {}

    public static int prepare(JavaPlugin plugin) throws IOException {
        return prepare(plugin.getDataFolder().toPath(), plugin.getLogger());
    }

    /** Overload for offline migration checks; never needs a live Bukkit server. */
    public static int prepare(Path root, java.util.logging.Logger logger) throws IOException {
        Path data = root.resolve("data");
        Files.createDirectories(data);
        int migrated = 0;
        Set<String> folders = new LinkedHashSet<>();
        for (var entry : FILES.entrySet()) {
            String relative = entry.getValue();
            Path source = root.resolve(entry.getKey());
            Path destination = data.resolve(relative);
            Files.createDirectories(destination.getParent());
            folders.add(relative.substring(0, relative.indexOf('/')));
            if (Files.isRegularFile(source) && !Files.exists(destination)) {
                Path tmp = Files.createTempFile(destination.getParent(), ".nodes-migration-", ".tmp");
                try {
                    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                    atomicReplace(tmp, destination);
                    migrated++;
                    logger.info("Imported legacy JSON " + entry.getKey() + " into data/" + relative + "; original retained.");
                } finally { Files.deleteIfExists(tmp); }
            } else if (Files.isRegularFile(source) && Files.isRegularFile(destination)
                    && Files.size(source) != Files.size(destination)) {
                // Do not override authoritative new data or remove the old save.
                logger.fine("Both legacy and new JSON exist for " + entry.getKey() + "; keeping current data/ file.");
            }
        }
        // These schemas are the exact defaults in the imported Ginjer data classes.
        Map<String, String> empty = Map.of(
            "calendar/calendar.json", "{\n  \"nextId\": 1,\n  \"events\": [],\n  \"announcements\": [],\n  \"fired\": {}\n}\n",
            "exploration/pois.json", "{\n  \"nextId\": 1,\n  \"pois\": []\n}\n",
            "exploration/discoveries.json", "{\n  \"players\": {}\n}\n",
            "skills/skills.json", "{\n  \"players\": {}\n}\n",
            "duels/duels.json", "{\n  \"arenas\": {},\n  \"kits\": {},\n  \"stats\": {},\n  \"pending\": {},\n  \"escrow\": {}\n}\n");
        for (var entry : empty.entrySet()) {
            Path target = data.resolve(entry.getKey());
            if (!Files.exists(target)) Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        Path manifest = data.resolve("storage-layout.json");
        if (!Files.exists(manifest)) {
            StringJoiner map = new StringJoiner(",\n", "{\n  \"schemaVersion\": 1,\n  \"files\": {\n", "\n  }\n}\n");
            FILES.forEach((key, value) -> map.add("    \"" + key + "\": \"data/" + value + "\""));
            Files.writeString(manifest, map.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        if (migrated > 0) logger.info("Migrated " + migrated + " Ginjer JSON saves into feature folders; legacy files retained.");
        return migrated;
    }

    private static void atomicReplace(Path tmp, Path target) throws IOException {
        try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target); }
    }

    public static Status status(JavaPlugin plugin) {
        Path root = plugin.getDataFolder().toPath();
        Path data = root.resolve("data");
        int active = 0, legacy = 0;
        Set<String> folders = new HashSet<>();
        for (var entry : FILES.entrySet()) {
            if (Files.isRegularFile(data.resolve(entry.getValue()))) active++;
            if (Files.isRegularFile(root.resolve(entry.getKey()))) legacy++;
            folders.add(entry.getValue().split("/", 2)[0]);
        }
        return new Status(active, legacy, folders.size());
    }
}
