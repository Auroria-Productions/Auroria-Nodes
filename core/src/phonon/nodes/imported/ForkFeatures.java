package phonon.nodes.imported;

import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import phonon.nodes.Nodes;
import phonon.nodes.bounty.Bounties;
import phonon.nodes.calendar.Calendar;
import phonon.nodes.commands.*;
import phonon.nodes.duel.Duels;
import phonon.nodes.economy.*;
import phonon.nodes.killshop.*;
import phonon.nodes.listeners.*;
import phonon.nodes.poi.*;
import phonon.nodes.refinery.legacy1182.PhononRefinery;
import phonon.nodes.rules.*;
import phonon.nodes.skills.Skills;
import phonon.nodes.stats.*;
import phonon.nodes.supply.*;
import phonon.nodes.surrender.Surrender;
import phonon.nodes.war.WarTimer;
import phonon.nodes.war.Raid;
import phonon.nodes.ports.PortPolicies;
import phonon.nodes.mining.*;

public final class ForkFeatures implements Listener {
    private static JavaPlugin plugin;
    private static YamlConfiguration defaults;
    private ForkFeatures() {}

    public static void start(JavaPlugin owner) {
        plugin = owner;
        try (var in = owner.getResource("imported-nodes-defaults.yml")) {
            defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        mergeDefaults();
        var config = owner.getConfig();
        Calendar.INSTANCE.loadConfig(config);
        Pois.INSTANCE.loadConfig(config);
        Skills.INSTANCE.loadConfig(config);
        Duels.INSTANCE.loadConfig(config);
        Bounties.INSTANCE.loadConfig(config);
        Economy.INSTANCE.loadConfig(config);
        TownFlags.INSTANCE.load();
        PortPolicies.INSTANCE.load();
        Raid.INSTANCE.load();
        TownRules.INSTANCE.start(owner);
        Calendar.INSTANCE.start(owner);
        Pois.INSTANCE.start(owner);
        Skills.INSTANCE.start(owner);
        Duels.INSTANCE.start(owner);
        MiningBoost.INSTANCE.load();
        MiningTowers.INSTANCE.load();
        Surrender.INSTANCE.load();
        PlayerStats.INSTANCE.load();
        WarStats.INSTANCE.load();
        Bounties.INSTANCE.load();
        // Phonon 1.18.2 territory-resource refinery replaces the legacy dropper system.
        KillShop.INSTANCE.load();
        SupplyDrops.INSTANCE.load();
        Economy.INSTANCE.load();
        VoteHook.INSTANCE.register(owner);
        for (var listener : List.<Listener>of(new ForkFeatures(), new PoiMenuListener(),
            new KillShopGuiListener(), new SupplyDropListener(),
            new EconomyMenuListener(), new StatsListener(), new BountyListener(), new TownRulesListener(), new MiningTowerListener(), new GinjerWar())) {
            Bukkit.getPluginManager().registerEvents(listener, owner);
        }
        register("calendar", new CalendarCommand());
        register("duel", new DuelCommand());
        register("skills", new SkillsCommand());
        register("map", new MapCommand());
        PhononRefinery.start(owner);
        register("stats", new StatsCommand());
        register("leaderboard", new StatsCommand());
        register("plot", new PlotCommand());
        register("killshop", new KillShopCommand());
        register("bounty", new BountyCommand());
        register("economy", new EconomyCommand());
        register("economyadmin", new EconomyAdminCommand());
        register("supplydrop", new SupplyDropCommand());
        register("resourcetower", new phonon.nodes.commands.MiningTowerCommand());
        register("surrender", new SurrenderCommand());
        register("raid", new RaidCommand());
        GinjerCommands.install(owner, "town");
        GinjerCommands.install(owner, "port");
        GinjerCommands.install(owner, "ally");
        GinjerCommands.install(owner, "war");
        register("rpchat", new RpChatCommand());
        var capture = owner.getCommand("captime");
        if (capture != null) {
            register("captimes", capture.getExecutor());
            var command = owner.getCommand("captimes");
            if (command != null) command.setTabCompleter(capture.getTabCompleter());
        }
        WarTimer.INSTANCE.restore();
        Bukkit.getScheduler().runTaskTimer(owner, Economy.INSTANCE::tick, 200, 200);
        owner.getLogger().info("Loaded the imported Nodes features and player commands.");
    }

    private static void mergeDefaults() {
        var config = plugin.getConfig();
        boolean changed = false;
        for (String path : defaults.getKeys(true)) {
            if (config.isSet(path)) continue;
            changed = true;
            if (defaults.isConfigurationSection(path)) config.createSection(path);
            else config.set(path, defaults.get(path));
        }
        if (changed) plugin.saveConfig();
    }

    private static void register(String name, CommandExecutor executor) {
        var command = plugin.getCommand(name);
        if (command == null) return;
        command.setExecutor(executor);
        if (executor instanceof TabCompleter completer) command.setTabCompleter(completer);
    }

    public static void stop() {
        if (plugin == null) return;
        for (Runnable stop : List.<Runnable>of(Skills.INSTANCE::shutdown, Duels.INSTANCE::shutdown,
            Raid.INSTANCE::shutdown, MiningBoost.INSTANCE::shutdown, MiningTowers.INSTANCE::shutdown, PlayerStats.INSTANCE::shutdown, WarStats.INSTANCE::shutdown,
            PhononRefinery::stop, KillShop.INSTANCE::shutdown, SupplyDrops.INSTANCE::shutdown,
            Bounties.INSTANCE::save, Surrender.INSTANCE::save)) {
            try { stop.run(); } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not save imported Nodes feature", e);
            }
        }
        plugin = null;
    }

    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) {
        MiningBoost.INSTANCE.onQuit(event.getPlayer());
        WarTimer.INSTANCE.onPlayerQuit(event.getPlayer());
    }

    @EventHandler public void joined(PlayerJoinEvent event) {
        MiningBoost.INSTANCE.onJoin(event.getPlayer());
        WarTimer.INSTANCE.onPlayerJoin(event.getPlayer());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) Bounties.INSTANCE.deliverPending(event.getPlayer());
        }, 20);
    }

    static org.bukkit.configuration.file.FileConfiguration config() { return plugin.getConfig(); }

    public static void startWar(Nodes core, boolean annex, boolean borders, boolean destruction) {
        core.enableWar(annex, borders, destruction, 0L, "normal");
    }
}
