package phonon.nodes.compat;

import org.bukkit.Material;

public final class Platform {
    private Platform() {}

    public static Material paleOakFence() {
        var fence = Material.getMaterial("PALE_OAK_FENCE");
        return fence == null ? Material.BIRCH_FENCE : fence;
    }

}
