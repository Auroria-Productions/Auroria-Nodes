package phonon.nodes.imported;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import phonon.nodes.mining.MiningBoost;
import phonon.nodes.nodesaddons.MineBuffEffect;
import org.bukkit.entity.EntityType;
import phonon.nodes.Config;
import phonon.nodes.objects.Town;

public final class ForkCompatibility {
    private ForkCompatibility() {}
    public static String getDailyEventTime(Config ignored) { return string("dailyEventTime"); }
    public static boolean getTogglePVPMechanic(Config ignored) { return flag("togglePVPMechanic"); }
    public static boolean getMobProtection(Config ignored) { return flag("mobProtection"); }
    public static boolean getWarSickness(Config ignored) { return flag("warSickness"); }
    public static long getTownRenameCooldown(Config ignored) { return duration("townRenameCooldown"); }
    public static long getPvpToggleCooldown(Config ignored) { return duration("pvpToggleCooldown"); }
    public static Set<EntityType> getProtectedMobs(Config ignored) { return mobs("protectedMobs"); }
    public static Set<EntityType> getProtectedMobsIfPVPOff(Config ignored) { return mobs("protectedMobsIfPVPOff"); }
    public static HashSet<String> getInvulnerabilityTowns(Config ignored) { return new HashSet<>(cfg().getStringList("invulnerabilityTowns")); }
    public static boolean getSupplyDropsEnabled(Config ignored) { return flag("supplyDrops.enabled"); }
    public static boolean getSupplyDropsDiscord(Config ignored) { return flag("supplyDrops.discord"); }
    public static long getSupplyDropsIntervalMs(Config ignored) { return minutes("supplyDrops.intervalMinutes"); }
    public static long getSupplyDropsJitterMs(Config ignored) { return minutes("supplyDrops.jitterMinutes"); }
    public static long getSupplyDropsWarningMs(Config ignored) { return cfg().getLong("supplyDrops.warningSeconds") * 1000; }
    public static long getSupplyDropsDespawnMs(Config ignored) { return minutes("supplyDrops.despawnMinutes"); }
    public static double getSupplyDropsFallHeight(Config ignored) { return cfg().getDouble("supplyDrops.fallHeight"); }
    public static long getSupplyDropsChannelMs(Config ignored) { return cfg().getLong("supplyDrops.claimSeconds") * 1000; }
    public static double getSupplyDropsChannelRadius(Config ignored) { return cfg().getDouble("supplyDrops.claimRadius"); }
    public static int getSupplyDropsItemsPerDrop(Config ignored) { return cfg().getInt("supplyDrops.itemsPerDrop"); }
    public static int getSupplyDropsMinPlayersOnline(Config ignored) { return cfg().getInt("supplyDrops.minPlayersOnline"); }
    public static boolean getDiscordRelayWar(Config ignored) { return flag("discordRelayWar"); }
    public static boolean getDiscordRelayFlagWar(Config ignored) { return flag("discordRelayFlagWar"); }
    public static boolean getDiscordRelayRaids(Config ignored) { return flag("discordRelayRaids"); }
    public static boolean getDiscordRelayWarzones(Config ignored) { return flag("discordRelayWarzones"); }
    public static String getDiscordWebhookWar(Config ignored) { return string("discordWebhookWar"); }
    public static String getDiscordWebhookFlagWar(Config ignored) { return string("discordWebhookFlagWar"); }
    public static String getDiscordWebhookRaids(Config ignored) { return string("discordWebhookRaids"); }
    public static String getDiscordWebhookWarzones(Config ignored) { return string("discordWebhookWarzones"); }

    public static double miningChance(MineBuffEffect townEffect, Player player) {
        return townEffect.getChanceMultiplier() * MiningBoost.INSTANCE.effective(player).getFirst();
    }

    public static double miningAmount(MineBuffEffect townEffect, Player player) {
        return townEffect.getAmountMultiplier() * MiningBoost.INSTANCE.effective(player).getSecond();
    }

    public static void cancelAttacksForSurrender(phonon.nodes.war.FlagWar war, Town town, phonon.nodes.objects.Nation nation) {
        var attacks = List.copyOf(war.getChunkToAttacker$nodes().values());
        for (var attack : attacks) {
            var chunk = phonon.nodes.Nodes.INSTANCE.getTerritoryChunkFromCoord(attack.getCoord());
            var defender = chunk == null ? null : chunk.getTerritory().getTown();
            var attacker = attack.getTown();
            if (attacker == town && defender != null && defender.getNation() == nation
                || defender == town && attacker != null && attacker.getNation() == nation) war.cancelAttack$nodes(attack);
        }
    }

    private static org.bukkit.configuration.file.FileConfiguration cfg() { return ForkFeatures.config(); }
    private static boolean flag(String path) { return cfg().getBoolean(path); }
    private static String string(String path) { return cfg().getString(path, ""); }
    private static long minutes(String path) { return cfg().getLong(path) * 60_000L; }
    private static long duration(String path) {
        String text = string(path).strip().toLowerCase(Locale.ROOT);
        var match = java.util.regex.Pattern.compile("(\\d+)([smhd]?)").matcher(text);
        if (!match.matches()) return 0;
        try {
            long value = Long.parseLong(match.group(1));
            return Math.multiplyExact(value, switch (match.group(2)) {
                case "s" -> 1_000L; case "m" -> 60_000L; case "h" -> 3_600_000L; case "d" -> 86_400_000L; default -> 1L;
            });
        } catch (ArithmeticException | NumberFormatException e) { return 0; }
    }
    private static Set<EntityType> mobs(String path) {
        var result = EnumSet.noneOf(EntityType.class);
        for (String name : cfg().getStringList(path)) {
            try { result.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) { }
        }
        return result;
    }
    public static boolean getRaidEnabled(Config ignored) { return flag("raid.enabled"); }
    public static long getRaidPrepMs(Config ignored) { return minutes("raid.prepMinutes"); }
    public static long getRaidLiveMs(Config ignored) { return minutes("raid.liveMinutes"); }
    public static long getRaidCooldownMs(Config ignored) { return minutes("raid.cooldownMinutes"); }
    public static long getRaidCancelDefenderWinWindowMs(Config ignored) { return minutes("raid.cancelDefenderWinWindowMinutes"); }
    public static int getRaidMinDefenderOnline(Config ignored) { return cfg().getInt("raid.minDefenderOnline"); }
    public static Map<Material,Integer> getRaidAttackerReward(Config ignored) { return reward("raid.attackerReward"); }
    public static Map<Material,Integer> getRaidDefenderReward(Config ignored) { return reward("raid.defenderReward"); }
    public static boolean isWarzoneTown(Town town) {
        return phonon.nodes.warzone.WarzoneTownRegistry.INSTANCE.isWarzoneTown$nodes(town);
    }
    private static Map<Material,Integer> reward(String path) {
        var amounts = new EnumMap<Material,Integer>(Material.class);
        var section = cfg().getConfigurationSection(path);
        if (section != null) for (String key : section.getKeys(false)) {
            var material = Material.matchMaterial(key);
            int amount = section.getInt(key);
            if (material != null && material.isItem() && amount > 0) amounts.put(material, amount);
        }
        return amounts;
    }

    public static boolean getSupplyLinesEnabled(Config ignored) { return flag("supplyLines.enabled"); }
    public static boolean getSupplyLinesWastelandRoutes(Config ignored) { return flag("supplyLines.wastelandRoutes"); }
    public static double getSupplyLinesChunkBlockFraction(Config ignored) { return cfg().getDouble("supplyLines.chunkBlockFraction"); }
    public static double getSupplyLinesCutCaptureTimeMultiplier(Config ignored) { return cfg().getDouble("supplyLines.cutCaptureTimeMultiplier", 1.0); }
    public static double getSupplyLinesCutIncomeMultiplier(Config ignored) { return cfg().getDouble("supplyLines.cutIncomeMultiplier", 1.0); }
}
