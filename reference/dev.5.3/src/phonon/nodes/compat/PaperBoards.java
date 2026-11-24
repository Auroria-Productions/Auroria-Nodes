package phonon.nodes.compat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class PaperBoards {
    private record View(Scoreboard previous, Scoreboard nodes) {}

    private static final Map<UUID, View> VIEWS = new HashMap<>();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private PaperBoards() {}

    public static void edit(Player player, Consumer<Scoreboard> action) {
        if (!Bukkit.isPrimaryThread()) {
            var plugin = Bukkit.getPluginManager().getPlugin("nodes");
            if (plugin != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> edit(player, action));
            }
            return;
        }
        if (!player.isOnline()) return;
        var manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        var current = player.getScoreboard();
        var view = VIEWS.get(player.getUniqueId());
        if (view == null) {
            // Leave boards already assigned by another plugin under that plugin's control.
            if (current != manager.getMainScoreboard()) return;
            view = new View(current, manager.getNewScoreboard());
            VIEWS.put(player.getUniqueId(), view);
            player.setScoreboard(view.nodes());
        }
        if (player.getScoreboard() == view.nodes()) action.accept(view.nodes());
    }

    public static Team team(Scoreboard board, String name) {
        var team = board.getTeam(name);
        return team == null ? board.registerNewTeam(name) : team;
    }

    public static void sendTeamCreate(Player p, String name, String prefix, String suffix,
                                      String member, NamedTextColor color) {
        edit(p, board -> {
            var team = team(board, name);
            team.prefix(LEGACY.deserialize(prefix));
            team.suffix(LEGACY.deserialize(suffix));
            team.color(color);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            team.addEntry(member);
        });
    }

    public static void sendTeamRemove(Player p, String name) {
        edit(p, board -> {
            var team = board.getTeam(name);
            if (team != null) team.unregister();
        });
    }

    public static void sendHeaderFooter(Player p, Component header, Component footer) {
        p.sendPlayerListHeaderAndFooter(header, footer);
    }

    public static void release(UUID id) {
        var view = VIEWS.remove(id);
        var player = Bukkit.getPlayer(id);
        if (view != null && player != null && player.getScoreboard() == view.nodes()) {
            player.setScoreboard(view.previous());
        }
    }

    public static void releaseAll() {
        for (var id : java.util.List.copyOf(VIEWS.keySet())) release(id);
    }
}
