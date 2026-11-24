package phonon.nodes.imported;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kotlin.Unit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import phonon.nodes.Message;
import phonon.nodes.Nodes;
import phonon.nodes.commands.PortCommandKt;
import phonon.nodes.constants.DiplomaticRelationship;
import phonon.nodes.economy.Economy;
import phonon.nodes.economy.EconomyExtras;
import phonon.nodes.objects.Port;
import phonon.nodes.objects.Town;
import phonon.nodes.ports.PortPolicies;
import phonon.nodes.ports.PortPolicy;
import phonon.nodes.tasks.PortWarpTask;

public final class GinjerPorts {
    private GinjerPorts() {}

    private static boolean enabled() { return ForkFeatures.config().getBoolean("portTownPolicies"); }

    public static void policy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("Run this command in game.");
        if (!enabled()) throw new IllegalArgumentException("Town port policies are disabled (portTownPolicies).");
        var resident = Nodes.INSTANCE.getResident(player);
        var town = resident == null ? null : resident.getTown();
        if (town == null || resident != town.getLeader() && !town.getOfficers().contains(resident)) {
            throw new IllegalArgumentException("Only the town leader or officers can set port policies.");
        }
        if (args.length != 3 || !Set.of("ally", "neutral", "enemy").contains(args[1].toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("/port allow <ally|neutral|enemy> <true|false> or /port fee <ally|neutral|enemy> <amount>");
        }
        String group = args[1].toLowerCase(Locale.ROOT);
        if (args[0].equalsIgnoreCase("allow")) {
            if (!Set.of("true", "false").contains(args[2].toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Use true or false.");
            boolean allowed = Boolean.parseBoolean(args[2]);
            PortPolicies.INSTANCE.edit(town, policy -> {
                switch (group) {
                    case "ally" -> policy.setAllowAlly(allowed);
                    case "neutral" -> policy.setAllowNeutral(allowed);
                    case "enemy" -> policy.setAllowEnemy(allowed);
                }
                return Unit.INSTANCE;
            });
            Message.INSTANCE.print(sender, "Port access for " + group + " set to " + allowed + ".");
            return;
        }
        long cents;
        try { cents = new BigDecimal(args[2]).movePointRight(2).longValueExact(); }
        catch (NumberFormatException | ArithmeticException ex) { throw new IllegalArgumentException("Enter a fee with up to two decimal places."); }
        if (cents < 0 || cents > Economy.INSTANCE.getPortMaxFee$nodes()) throw new IllegalArgumentException("Enter a fee within the configured limit.");
        PortPolicies.INSTANCE.edit(town, policy -> {
            switch (group) {
                case "ally" -> policy.setCostAlly(cents);
                case "neutral" -> policy.setCostNeutral(cents);
                case "enemy" -> policy.setCostEnemy(cents);
            }
            return Unit.INSTANCE;
        });
        Message.INSTANCE.print(sender, "Port fee for " + group + " set to " + BigDecimal.valueOf(cents, 2) + ".");
    }

    public static boolean isPublic(Port port, Player player) {
        if (!enabled()) return port.isPublic();
        var owner = Nodes.INSTANCE.getPortOwner(port);
        return owner == null || allowed(PortPolicies.INSTANCE.of(owner), Nodes.INSTANCE.getRelationshipOfPlayerToTown(player, owner));
    }

    public static DiplomaticRelationship relationship(Nodes nodes, Player player, Town owner) {
        return enabled() ? DiplomaticRelationship.NEUTRAL : nodes.getRelationshipOfPlayerToTown(player, owner);
    }

    private static boolean allowed(PortPolicy policy, DiplomaticRelationship relationship) {
        return switch (relationship) {
            case TOWN, NATION -> true;
            case ALLY -> policy.getAllowAlly();
            case NEUTRAL -> policy.getAllowNeutral();
            case ENEMY -> policy.getAllowEnemy();
        };
    }

    public static double warpTime(double base, Player player) {
        double multiplier = ForkFeatures.config().getDouble("portNotInShipMultiplier", 1.0);
        return player.getVehicle() == null && Double.isFinite(multiplier) && multiplier >= 1.0 && multiplier <= 100.0
            ? base * multiplier : base;
    }

    public static void arrive(Port port, List<Player> players, List<Entity> vehicles, PortWarpTask task) {
        var player = task.getPlayer();
        if (enabled()) {
            var owner = Nodes.INSTANCE.getPortOwner(port);
            if (owner != null) {
                var policy = PortPolicies.INSTANCE.of(owner);
                var relation = Nodes.INSTANCE.getRelationshipOfPlayerToTown(player, owner);
                if (!allowed(policy, relation)) {
                    Message.INSTANCE.error(player, "The destination town no longer allows this warp.");
                    return;
                }
                long fee = switch (relation) {
                    case ALLY -> policy.getCostAlly();
                    case NEUTRAL -> policy.getCostNeutral();
                    case ENEMY -> policy.getCostEnemy();
                    default -> 0;
                };
                if (fee > 0) {
                    try {
                        if (!Economy.INSTANCE.getEnabled() || !EconomyExtras.INSTANCE.canPay(player, fee)) {
                            Message.INSTANCE.error(player, "You cannot pay this port's warp fee.");
                            return;
                        }
                        EconomyExtras.INSTANCE.payPortFee(player, owner, fee, port.getName());
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        Message.INSTANCE.error(player, "Could not collect this port's warp fee.");
                        return;
                    }
                }
            }
        }
        PortCommandKt.warpToPort(port, players, vehicles);
        Message.INSTANCE.announcement(player, "Warped to " + port.getName());
    }
}
