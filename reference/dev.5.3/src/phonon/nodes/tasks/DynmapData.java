package phonon.nodes.tasks;

import com.google.gson.JsonObject;

final class DynmapData {
    private DynmapData() {}
    static void injectNodeTypes(JsonObject world) {}
    static JsonObject warzones() { throw new AssertionError("Rebuild did not link Dynmap data access"); }
}
