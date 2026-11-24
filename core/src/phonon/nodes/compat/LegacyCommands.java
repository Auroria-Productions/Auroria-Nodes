package phonon.nodes.compat;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Collection;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

final class LegacyCommands {
    private LegacyCommands() {}

    static void register(JavaPlugin plugin, String namespace) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (String name : plugin.getDescription().getCommands().keySet()) {
                var command = plugin.getCommand(name);
                if (command == null) continue;
                var aliases = new ArrayList<String>();
                for (var alias : command.getAliases()) aliases.add(namespace + ":" + alias);
                event.registrar().register(namespace + ":" + name, command.getDescription(), aliases, new BasicCommand() {
                    @Override public boolean canUse(CommandSender sender) {
                        return command.testPermissionSilent(sender);
                    }
                    @Override public void execute(CommandSourceStack source, String[] args) {
                        command.execute(source.getSender(), name, args);
                    }
                    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
                        return command.tabComplete(source.getSender(), name, args);
                    }
                });
            }
        });
    }
}
