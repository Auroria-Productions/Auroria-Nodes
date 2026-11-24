package phonon.nodes.imported;

import java.util.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import phonon.nodes.Message;
import phonon.nodes.Nodes;
import phonon.nodes.admin.AdminTools;
import phonon.nodes.rules.TownFlags;
import phonon.nodes.supply.SupplyLines;

final class GinjerCommands implements TabExecutor {
    private final String root;
    private final CommandExecutor original;
    private final TabCompleter completer;

    static void install(JavaPlugin plugin, String root) {
        var command = plugin.getCommand(root);
        if (command == null) return;
        var wrapper = new GinjerCommands(root, command.getExecutor(), command.getTabCompleter());
        command.setExecutor(wrapper);
        command.setTabCompleter(wrapper);
    }

    private GinjerCommands(String root, CommandExecutor original, TabCompleter completer) {
        this.root = root;
        this.original = original;
        this.completer = completer;
    }

    private List<String> actions() {
        return switch (root) {
            case "port" -> List.of("request", "allow", "fee");
            case "town" -> List.of("pvp", "supply");
            default -> List.of();
        };
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return original.onCommand(sender, command, label, args);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help") && (root.equals("port") || root.equals("town"))) {
            boolean result = original.onCommand(sender, command, label, args);
            if (root.equals("town")) {
                Message.INSTANCE.print(sender, "/town pvp [on|off]");
                Message.INSTANCE.print(sender, "/town supply");
            } else {
                Message.INSTANCE.print(sender, "/port request <name>");
                Message.INSTANCE.print(sender, "/port allow <ally|neutral|enemy> <true|false>");
                Message.INSTANCE.print(sender, "/port fee <ally|neutral|enemy> <amount>");
            }
            return result;
        }
        try {
            if (root.equals("port")) {
                if (action.equals("allow") || action.equals("fee")) {
                    GinjerPorts.policy(sender, args);
                    return true;
                }
                if (action.equals("request")) {
                    args = args.clone();
                    args[0] = "apply";
                }
            } else if (root.equals("town") && sender instanceof Player player) {
                if (action.equals("supply")) {
                    showSupply(player);
                    return true;
                }
                if (action.equals("pvp")) {
                    setPvp(player, args);
                    return true;
                }
                if (action.equals("rename")) {
                    var town = Nodes.INSTANCE.getTownFromPlayer(player);
                    if (town == null) return original.onCommand(sender, command, label, args);
                    var previous = town.getName();
                    long left = TownFlags.INSTANCE.renameCooldownLeft(town);
                    if (left > 0 && args.length > 1) {
                        Message.INSTANCE.error(player, "Your town can be renamed again in " + TownFlags.INSTANCE.durationText(left) + ".");
                        return true;
                    }
                    boolean result = original.onCommand(sender, command, label, args);
                    if (!town.getName().equals(previous)) TownFlags.INSTANCE.markRenamed(town);
                    return result;
                }
            } else if ((root.equals("ally") || root.equals("war")) && sender instanceof Player player) {
                if (!(root.equals("war") && action.equals("join"))) checkProximity(player, args[0]);
            }
        } catch (IllegalArgumentException | java.io.IOException e) {
            Message.INSTANCE.error(sender, e.getMessage());
            return true;
        }
        return original.onCommand(sender, command, label, args);
    }

    private void checkProximity(Player player, String target) {
        var config = ForkFeatures.config();
        if (!config.getBoolean(root + "Proximity")) return;
        double limit = config.getDouble(root + "ProximityDistance");
        if (!(limit > 0 && Double.isFinite(limit))) return;
        var nodes = Nodes.INSTANCE;
        var source = nodes.getTownFromPlayer(player);
        var nation = nodes.getNationFromName(target);
        var other = nation == null ? nodes.getTownFromName(target) : nation.getCapital();
        if (source == null || other == null) return;
        for (var a : townsOf(source)) {
            var first = a.getSpawnpoint();
            if (first == null) continue;
            for (var b : townsOf(other)) {
                var second = b.getSpawnpoint();
                if (second != null && first.getWorld() != null && first.getWorld().equals(second.getWorld())) {
                    double x = first.getX() - second.getX(), z = first.getZ() - second.getZ();
                    if (Math.hypot(x, z) <= limit) return;
                }
            }
        }
        throw new IllegalArgumentException("The nearest towns must be at most " + limit + " blocks apart.");
    }

    private static Collection<phonon.nodes.objects.Town> townsOf(phonon.nodes.objects.Town town) {
        return town.getNation() == null ? List.of(town) : town.getNation().getTowns();
    }

    private static void showSupply(Player player) {
        var town = Nodes.INSTANCE.getTownFromPlayer(player);
        if (town == null) throw new IllegalArgumentException("You do not belong to a town.");
        if (!ForkCompatibility.getSupplyLinesEnabled(null)) throw new IllegalArgumentException("Supply lines are disabled.");
        var isolated = SupplyLines.INSTANCE.cutOff(town);
        if (isolated.isEmpty()) Message.INSTANCE.print(player, "All territories are supplied from your town's home.");
        else {
            Message.INSTANCE.print(player, "Cut-off territories " + isolated + ". Income ×"
                + SupplyLines.INSTANCE.formatMultiplier(SupplyLines.INSTANCE.incomeMultiplier())
                + ", enemy capture time ×" + SupplyLines.INSTANCE.formatMultiplier(SupplyLines.INSTANCE.captureTimeMultiplier()) + ".");
        }
    }

    private static void setPvp(Player player, String[] args) throws java.io.IOException {
        var config = ForkFeatures.config();
        if (!config.getBoolean("togglePVPMechanic")) throw new IllegalArgumentException("Town PvP switching is disabled.");
        var resident = Nodes.INSTANCE.getResident(player);
        var town = resident == null ? null : resident.getTown();
        if (town == null) throw new IllegalArgumentException("You do not belong to a town.");
        var flags = TownFlags.INSTANCE;
        if (args.length == 1) {
            Message.INSTANCE.print(player, "PvP in " + town.getName() + " is " + (flags.pvpEnabled(town) ? "on" : "off") + ".");
            return;
        }
        if (!config.getBoolean("canToggleTownPVP") || resident != town.getLeader() && !town.getOfficers().contains(resident)) {
            throw new IllegalArgumentException("You cannot change your town's PvP setting.");
        }
        String setting = args[1].toLowerCase(Locale.ROOT);
        if (args.length != 2 || !Set.of("on", "off").contains(setting)) throw new IllegalArgumentException("/town pvp [on|off]");
        boolean value = setting.equals("on");
        if (flags.pvpEnabled(town) == value) return;
        long left = flags.pvpCooldownLeft(town);
        if (left > 0) throw new IllegalArgumentException("PvP can be changed again in " + flags.durationText(left) + ".");
        AdminTools.setTownPvp(town, value);
        for (Player member : town.getPlayersOnline()) Message.INSTANCE.print(member, "PvP in " + town.getName() + " is now " + setting + ".");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        var existing = completer == null ? null : completer.onTabComplete(sender, command, alias, args);
        List<String> extra = args.length == 1 ? actions() : List.of();
        if (args.length == 2 && root.equals("town") && args[0].equalsIgnoreCase("pvp")) extra = List.of("on", "off");
        if (args.length == 2 && root.equals("port") && Set.of("allow", "fee").contains(args[0].toLowerCase(Locale.ROOT))) extra = List.of("ally", "neutral", "enemy");
        if (args.length == 3 && root.equals("port") && args[0].equalsIgnoreCase("allow")) extra = List.of("true", "false");
        if (extra.isEmpty()) return existing;
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        var result = new LinkedHashSet<>(existing == null ? List.<String>of() : existing);
        extra.stream().filter(value -> value.startsWith(prefix)).forEach(result::add);
        return List.copyOf(result);
    }
}
