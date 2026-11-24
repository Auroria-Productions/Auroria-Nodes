package phonon.nodes.poi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;

/** Candidate filter for the existing discovery loop, which still checks time, 3D distance and rewards. */
public final class PoiIndex {
    private static final int CELL_SIZE = 64;
    private static final int MAX_BUCKETS_PER_POINT = 64;
    private static PoiIndex current;

    private final List<Poi> points;
    private final Map<String, WorldIndex> worlds = new HashMap<>();

    private PoiIndex(List<Poi> points) {
        this.points = points;
        for (Poi poi : points) {
            var index = worlds.computeIfAbsent(poi.getWorld(), ignored -> new WorldIndex());
            double radius = Math.abs(poi.getRadius());
            int left = cell(poi.getX() - radius);
            int right = cell(poi.getX() + radius);
            int top = cell(poi.getZ() - radius);
            int bottom = cell(poi.getZ() + radius);
            long columns = (long) right - left + 1;
            long rows = (long) bottom - top + 1;
            if (columns > MAX_BUCKETS_PER_POINT || rows > MAX_BUCKETS_PER_POINT
                || columns * rows > MAX_BUCKETS_PER_POINT) {
                index.wide.add(poi);
                continue;
            }
            for (long x = left; x <= right; x++) {
                for (long z = top; z <= bottom; z++) {
                    index.cells.computeIfAbsent(pack((int)x, (int)z), ignored -> new ArrayList<>()).add(poi);
                }
            }
        }
    }

    public static void invalidate() { current = null; }

    public static List<Poi> points(PoiData data) {
        if (current == null) current = new PoiIndex(data.pois());
        return current.points;
    }

    public static List<Poi> candidates(List<Poi> points, Location location) {
        if (current == null || current.points != points) current = new PoiIndex(points);
        var world = location.getWorld();
        var index = current.worlds.get(world == null ? null : world.getName());
        if (index == null) return List.of();
        var local = index.cells.get(pack(cell(location.getX()), cell(location.getZ())));
        if (local == null) return index.wide;
        if (index.wide.isEmpty()) return local;
        var candidates = new ArrayList<>(local);
        candidates.addAll(index.wide);
        candidates.sort(Comparator.comparingInt(Poi::getId));
        return candidates;
    }

    private static int cell(double coordinate) { return (int)Math.floor(coordinate / CELL_SIZE); }

    private static long pack(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }

    private static final class WorldIndex {
        final Map<Long, List<Poi>> cells = new HashMap<>();
        final List<Poi> wide = new ArrayList<>();
    }
}
