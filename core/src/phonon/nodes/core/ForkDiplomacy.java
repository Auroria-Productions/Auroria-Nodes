package phonon.nodes.core;

import phonon.nodes.objects.Town;

public final class ForkDiplomacy {
    private ForkDiplomacy() {}

    public static boolean isVassal(Town town) {
        var core = CoreFeatures.instance;
        return core != null && town.getNation() != null && !core.politics.autonomous(town.getNation().getUuid());
    }

    public static boolean isBound(Town a, Town b) {
        var core = CoreFeatures.instance;
        if (core == null || a.getNation() == null || b.getNation() == null) return false;
        var first = a.getNation().getUuid();
        var second = b.getNation().getUuid();
        return !first.equals(second) && (core.politics.graph.vassalTree(first).contains(second)
            || core.politics.graph.vassalTree(second).contains(first));
    }
}
