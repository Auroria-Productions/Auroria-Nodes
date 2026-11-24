package phonon.nodes.nms;

import java.util.Collection;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import phonon.nodes.compat.PaperBoards;
import phonon.nodes.compat.TagText;

public final class NmsKt {
    private NmsKt() {}

    public static Boolean clearSendFailureLog(UUID player) {
        PaperBoards.release(player);
        return null;
    }

    public static void evictTeamFromPool(String name) {}

    public static TagText buildNametagComponent(String prefix, ChatColor color) {
        return TagText.literal(prefix).withStyle(color);
    }

    public static void sendTeamCreate(Player p, String n, TagText t, ChatColor c) {
        PaperBoards.edit(p, board -> {
            var team = PaperBoards.team(board, n);
            team.prefix(t.adventure());
            team.setColor(c);
        });
    }

    public static void sendTeamCreate(Player p, String n, String t, ChatColor c) {
        sendTeamCreate(p, n, buildNametagComponent(t, c), c);
    }

    public static void sendTeamCreate$default(Player p, String n, TagText t, ChatColor c,
                                               int mask, Object unused) {
        sendTeamCreate(p, n, t, (mask & 4) != 0 ? ChatColor.RESET : c);
    }

    public static void sendTeamCreate$default(Player p, String n, String t, ChatColor c,
                                               int mask, Object unused) {
        sendTeamCreate(p, n, t, (mask & 4) != 0 ? ChatColor.RESET : c);
    }

    public static void sendTeamUpdatePrefix(Player p, String n, TagText t, ChatColor c) {
        sendTeamCreate(p, n, t, c);
    }

    public static void sendTeamUpdatePrefix(Player p, String n, String t, ChatColor c) {
        sendTeamCreate(p, n, t, c);
    }

    public static void sendTeamUpdatePrefix$default(Player p, String n, TagText t, ChatColor c,
                                                     int mask, Object unused) {
        sendTeamCreate$default(p, n, t, c, mask, unused);
    }

    public static void sendTeamUpdatePrefix$default(Player p, String n, String t, ChatColor c,
                                                     int mask, Object unused) {
        sendTeamCreate$default(p, n, t, c, mask, unused);
    }

    public static void sendTeamRemove(Player p, String n) {
        PaperBoards.sendTeamRemove(p, n);
    }

    public static void sendTeamAddPlayers(Player p, String n, Collection<String> members) {
        PaperBoards.edit(p, board -> members.forEach(PaperBoards.team(board, n)::addEntry));
    }

    public static void sendTeamRemovePlayers(Player p, String n, Collection<String> members) {
        PaperBoards.edit(p, board -> {
            var team = board.getTeam(n);
            if (team != null) members.forEach(team::removeEntry);
        });
    }

    public static void sendObjectiveCreate(Player p, String n, String title) {
        PaperBoards.edit(p, board -> {
            var objective = board.getObjective(n);
            if (objective == null) objective = board.registerNewObjective(n, Criteria.DUMMY, Component.text(title));
            else objective.displayName(Component.text(title));
            objective.numberFormat(NumberFormat.blank());
        });
    }

    public static void sendObjectiveRemove(Player p, String n) {
        PaperBoards.edit(p, board -> {
            var objective = board.getObjective(n);
            if (objective != null) objective.unregister();
        });
    }

    public static void sendObjectiveDisplay(Player p, String n, DisplaySlot slot) {
        PaperBoards.edit(p, board -> {
            if (n.isEmpty()) board.clearSlot(slot);
            else {
                var objective = board.getObjective(n);
                if (objective != null) objective.setDisplaySlot(slot);
            }
        });
    }

    public static void sendScore(Player p, String n, String entry, int score) {
        PaperBoards.edit(p, board -> {
            var objective = board.getObjective(n);
            if (objective != null) objective.getScore(entry).setScore(score);
        });
    }
}
