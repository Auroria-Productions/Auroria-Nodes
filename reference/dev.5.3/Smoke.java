import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import org.bukkit.ChatColor;
import org.bukkit.plugin.PluginDescriptionFile;
import phonon.nodes.nms.NmsKt;
import phonon.nodes.tasks.TaskCopyToDynmap;

public final class Smoke {
    public static void main(String[] args) throws Exception {
        try (var jar = new JarFile(args[0]);
             var input = jar.getInputStream(jar.getJarEntry("plugin.yml"))) {
            var desc = new PluginDescriptionFile(input);
            if (!desc.getName().equals("nodes") || !desc.getAPIVersion().equals("1.21")
                || !desc.getDepend().contains("Vault") || desc.getDepend().contains("packetevents")
                || jar.getJarEntry("nodes-web.zip") == null) throw new AssertionError("Plugin metadata");
        }

        for (var name : List.of("phonon.nodes.NodesPlugin", "phonon.nodes.Config", "phonon.nodes.objects.Town",
            "phonon.nodes.objects.NametagKt", "phonon.nodes.tab.TabListConfig", "phonon.nodes.tab.TabListKt",
            "phonon.nodes.core.CoreHooks", "phonon.nodes.core.DynmapInstaller", "phonon.nms.blockedit.FastBlockEditSession")) {
            Class.forName(name, false, Smoke.class.getClassLoader()).getDeclaredMethods();
        }

        var tag = NmsKt.buildNametagComponent("Rome ", ChatColor.GOLD);
        if (!tag.getString().equals("Rome ") || !tag.adventure().toString().contains("gold")) {
            throw new AssertionError("Nametag color conversion");
        }

        var root = Files.createTempDirectory("nodes-dynmap-test");
        try {
            var source = root.resolve("towns.json");
            var directory = root.resolve("web/nodes");
            var dest = directory.resolve("towns.json");
            var copy = new TaskCopyToDynmap(source, directory, dest);

            copy.run();
            if (Files.exists(dest)) throw new AssertionError("First export published missing data");
            Files.writeString(source, "{\"towns\":{}}");
            copy.run();
            if (!Files.readString(dest).equals(Files.readString(source))) throw new AssertionError("Export mismatch");
            Files.delete(source);
            copy.run();
            if (!Files.readString(dest).equals("{\"towns\":{}}")) throw new AssertionError("Existing export lost");
            try (var entries = Files.list(directory)) {
                if (entries.count() != 1) throw new AssertionError("Temporary export left behind");
            }
        } finally {
            try (var entries = Files.walk(root)) {
                for (var path : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Plugin metadata, JVM class verification, nametag text and Dynmap save/copy passed.");
    }
}
