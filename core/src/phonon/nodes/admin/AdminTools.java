package phonon.nodes.admin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.command.ConsoleCommandSender;
import phonon.nodes.objects.SocialSpy;
import phonon.nodes.chat.ChatMode;
import phonon.nodes.storage.JsonStorageLayout;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import phonon.nodes.Config;
import phonon.nodes.Nodes;
import phonon.nodes.imported.ForkFeatures;
import phonon.nodes.calendar.CalendarAdmin;
import phonon.nodes.poi.PoiAdmin;
import phonon.nodes.skills.SkillsAdmin;
import phonon.nodes.duel.DuelAdmin;
import phonon.nodes.rules.TownFlags;
import phonon.nodes.mining.MiningBoost;
import phonon.nodes.utils.AdminPerms;
import phonon.nodes.stats.PlayerStats;
import phonon.nodes.killtracker.KillTrackerManager;
import phonon.nodes.objects.Resident;
import phonon.nodes.plot.PlotManager;

public final class AdminTools implements TabExecutor, Listener {
    private static final Map<String, String> HELP = Map.ofEntries(
        Map.entry("pvp", "pvp <town> <on|off>"),
        Map.entry("inactive", "inactive [days]"),
        Map.entry("socialspy", "socialspy [local|town|nation|ally]"),
        Map.entry("stats", "stats reset <player|all confirm>"),
        Map.entry("announce", "announce <message>"),
        Map.entry("calendar", "calendar <list|add|edit|remove|announce>"),
        Map.entry("poi", "poi <add|list|show|move|tp|remove|reward>"),
        Map.entry("skills", "skills <show|set|addxp|reset>"),
        Map.entry("duel", "duel <arena|kit|end|resetstats>"),
        Map.entry("plot", "plot list|delete <name>"),
        Map.entry("miningboost", "miningboost <global|player|clear>"),
        Map.entry("rallycap", "rallycap <nation> <n|-1>"),
        Map.entry("storage", "storage <status|migrate>"),
        Map.entry("consolecheck", "consolecheck"));
    private static final Set<String> IMPORTED_ADMIN = Set.of("calendar", "poi", "skills", "duel");
    private static AdminTools active;
    private final JavaPlugin plugin;
    private final CommandExecutor original;
    private final TabCompleter originalCompleter;
    private final Store store;
    private static final Nodes NODES = Nodes.INSTANCE;

    private AdminTools(JavaPlugin plugin, CommandExecutor original, TabCompleter completer) throws IOException {
        this.plugin = plugin;
        this.original = original;
        this.store = new Store(plugin);
        this.originalCompleter = completer;
    }

    public static void install(JavaPlugin plugin) {
        var command = plugin.getCommand("nodesadmin");
        if (command == null || command.getExecutor() == plugin || active != null) return;
        try {
            JsonStorageLayout.prepare(plugin);
            ForkFeatures.start(plugin);
            active = new AdminTools(plugin, command.getExecutor(), command.getTabCompleter());
            command.setExecutor(active);
            command.setTabCompleter(active);
            Bukkit.getPluginManager().registerEvents(active, plugin);

        } catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not initialise admin command storage", e);
        }
    }

    public static void shutdown() {
        ForkFeatures.stop();
        active = null;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String rallyTarget = args.length > 1 ? args[1] : "";
        boolean nativeRally = action.equals("rallycap") && (args.length < 2
            || (args.length <= 3 && Set.of("enable", "disable", "size").contains(args[1].toLowerCase(Locale.ROOT))
                && !(args.length == 3 && NODES.getNations$nodes().values().stream().anyMatch(n -> n.getName().equalsIgnoreCase(rallyTarget)))));
        if (action.equals("port") && args.length > 1) {
            String portAction = args[1].toLowerCase(Locale.ROOT);
            if (portAction.equals("list")) {
                if (!allowed(sender, "port", "list")) { tell(sender, "You do not have permission to list ports."); return true; }
                var ports = NODES.getPorts$nodes().values();
                tell(sender, ports.size() + " registered ports.");
                ports.stream().sorted(Comparator.comparing(phonon.nodes.objects.Port::getName)).forEach(port -> {
                    var town = NODES.getPortOwner(port);
                    tell(sender, port.getName() + " (" + port.getLocX() + ", " + port.getLocZ() + ")"
                        + (town == null ? " — " + "unowned" : " — " + town.getName()));
                });
                return true;
            }
            if (portAction.equals("requests")) {
                if (!allowed(sender, "port", "requests")) { tell(sender, "You do not have permission to manage port requests."); return true; }
                if (args.length == 2) {
                    var requests = NODES.getPortApplications$nodes().values();
                    tell(sender, requests.size() + " pending port requests.");
                    requests.stream().sorted(Comparator.comparingInt(phonon.nodes.objects.PortApplication::getId)).forEach(request ->
                        tell(sender, "#" + request.getId() + " " + request.getRequestedPortName() + " (" + request.getRequesterName() + ")"));
                    return true;
                }
                if (args.length == 4) {
                    String verb = switch (args[3].toLowerCase(Locale.ROOT)) {
                        case "approve" -> "accept";
                        case "deny" -> "deny";
                        default -> null;
                    };
                    if (verb == null) { tell(sender, "/nodesadmin port requests [id approve|deny]"); return true; }
                    if (sender instanceof ConsoleCommandSender && verb.equals("accept")) {
                        tell(sender, "Approving a port opens a tier-selection inventory, so this action must be completed in-game. Use /nodesadmin port requests to view IDs.");
                        return true;
                    }
                    args = new String[]{"port", verb, args[2]};
                } else { tell(sender, "/nodesadmin port requests [id approve|deny]"); return true; }
            } else if (portAction.equals("group") && args.length == 4 && allowed(sender, "port", "group")) {
                String verb = switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "add" -> "create";
                    case "remove" -> "delete";
                    default -> null;
                };
                if (verb == null) { tell(sender, "/nodesadmin port group <add|remove> <name>"); return true; }
                args = new String[]{"portgroup", verb, args[3]};
            }
        }
        if (action.equals("socialspy") && sender instanceof ConsoleCommandSender) {
            consoleSocialSpy(sender, args);
            return true;
        }
        if (!HELP.containsKey(action) || action.equals("socialspy") || nativeRally) {
            boolean handled;
            try {
                handled = original.onCommand(sender, command, label, args);
            } catch (ClassCastException e) {
                // A few legacy admin handlers blindly cast a CommandSender to Player.
                // Keep their player-only behavior without crashing the console.
                if (!(sender instanceof ConsoleCommandSender)) throw e;
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Legacy command unexpectedly required an in-game player: " + action, e);
                tell(sender, "This legacy action needs a player location or inventory. Run it in-game; other admin commands work from console.");
                return true;
            }

            if (action.equals("help")) {
                HELP.keySet().stream().filter(key -> canSee(sender, key)).sorted().forEach(key -> tell(sender, "/nodesadmin " + HELP.get(key)));
            }
            return handled;
        }
        if (!IMPORTED_ADMIN.contains(action) && !allowed(sender, action, args.length < 2 ? null : args[1])) {
            tell(sender, "You do not have permission for /nodesadmin " + action + ".");
            return true;
        }
        try {
            switch (action) {
                case "pvp" -> pvp(sender, args);
                case "inactive" -> inactive(sender, args);
                case "stats" -> resetStats(sender, args);
                case "announce" -> announce("Announcement", words(args, 1));
                case "calendar" -> CalendarAdmin.INSTANCE.handle(sender, args);
                case "poi" -> PoiAdmin.INSTANCE.handle(sender, args);
                case "skills" -> SkillsAdmin.INSTANCE.handle(sender, args);
                case "duel" -> DuelAdmin.INSTANCE.handle(sender, args);
                case "miningboost" -> miningBoost(sender, args);
                case "plot" -> plot(sender, args);
                case "rallycap" -> rally(sender, args);
                case "storage" -> storage(sender, args);
                case "consolecheck" -> consoleCheck(sender, args);
                default -> throw new IllegalStateException(action);
            }
        } catch (IllegalArgumentException e) {
            tell(sender, e.getMessage());
        } catch (IOException | ReflectiveOperationException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Admin command " + action + " failed", e);
            tell(sender, "The command could not save its changes. Check the server log.");
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Admin command " + action + " failed unexpectedly", e);
            tell(sender, "The command failed unexpectedly. Check the server log for the complete error.");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        List<String> previous = originalCompleter == null ? null : originalCompleter.onTabComplete(sender, cmd, label, a);
        if (a.length == 0) return previous;
        if (a.length > 1 && !canSee(sender, a[0].toLowerCase(Locale.ROOT))) return previous;
        if (a.length >= 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "calendar": return CalendarAdmin.INSTANCE.complete(a);
                case "poi": return PoiAdmin.INSTANCE.complete(a);
                case "skills": return SkillsAdmin.INSTANCE.complete(a);
                case "duel": return DuelAdmin.INSTANCE.complete(a);
                default: break;
            }
        }
        var options = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (previous != null) options.addAll(previous);
        if (a.length == 2 && a[0].equalsIgnoreCase("port") && canSee(sender, "port"))
            options.addAll(List.of("requests", "group"));
        if (a.length == 1) HELP.keySet().stream().filter(key -> canSee(sender, key)).forEach(options::add);
        if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "pvp" -> options.addAll(NODES.getTowns$nodes().keySet());
                case "rallycap" -> options.addAll(NODES.getNations$nodes().keySet());
                case "stats" -> options.add("reset");
                case "miningboost" -> options.addAll(List.of("global", "player", "clear"));
                case "plot" -> options.addAll(List.of("list", "delete"));
                case "storage" -> options.addAll(List.of("status", "migrate"));
                default -> { }
            }
        }
        if (a.length == 3) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "pvp" -> options.addAll(List.of("on", "off"));
                case "rallycap" -> options.add("-1");
                case "stats" -> NODES.getResidents$nodes().values().stream().map(Resident::getName)
                    .filter(Objects::nonNull).forEach(options::add);
                case "plot" -> {
                    if (a[1].equalsIgnoreCase("delete")) PlotManager.INSTANCE.allPlots().forEach(p -> options.add(p.getName()));
                }
                default -> { }
            }
            if (a[0].equalsIgnoreCase("stats")) options.add("all");
        }
        if (a.length == 4 && a[0].equalsIgnoreCase("stats") && a[2].equalsIgnoreCase("all")) options.add("confirm");
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }


    private static boolean allowed(CommandSender who, String action, String subaction) {
        // Bukkit console is the trusted server administrator, not a player with rank grants.
        return who instanceof ConsoleCommandSender || AdminPerms.INSTANCE.has(who, action, subaction);
    }

    private static boolean canSee(CommandSender who, String action) {
        return who instanceof ConsoleCommandSender || AdminPerms.INSTANCE.canSee(who, action);
    }

    private static void consoleSocialSpy(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            tell(sender, "Console use: nodesadmin socialspy <online-player> [local|town|nation|ally|global]. Console cannot receive player chat as a spy.");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            tell(sender, "Player not online: " + args[1]);
            return;
        }
        boolean enabled;
        if (args.length == 2) {
            enabled = SocialSpy.INSTANCE.toggleAll(target);
        } else {
            ChatMode channel;
            try { channel = ChatMode.valueOf(args[2].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) {
                tell(sender, "Chat channels: local, town, nation, ally, global.");
                return;
            }
            enabled = SocialSpy.INSTANCE.toggleMode(target, channel);
        }
        tell(sender, "Chat spy " + (enabled ? "enabled" : "disabled") + " for " + target.getName() + ".");
    }

    private void storage(CommandSender sender, String[] args) throws IOException {
        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            var status = JsonStorageLayout.status(plugin);
            tell(sender, "Ginjer JSON: " + status.activeFiles() + " active files in " + status.folders()
                + " feature folders, " + status.legacyFiles() + " legacy flat files retained as backups.");
            tell(sender, "Path: " + plugin.getDataFolder().toPath().resolve("data").toAbsolutePath());
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("migrate")) {
            int copied = JsonStorageLayout.prepare(plugin);
            tell(sender, "JSON migration complete: " + copied + " previous saves copied. Existing JSON was never overwritten.");
            return;
        }
        throw new IllegalArgumentException("/nodesadmin " + HELP.get("storage"));
    }

    private void consoleCheck(CommandSender sender, String[] args) {
        require(args.length == 1, HELP.get("consolecheck"));
        tell(sender, "MilzzyNodes console commands are registered. /nodesadmin and /nda share the same executor.");
        tell(sender, "Try: nodesadmin inactive 30; nodesadmin calendar list; nodesadmin storage status.");
        tell(sender, "Location/inventory actions remain player-only; use an in-game admin for those.");
    }

    private void pvp(CommandSender sender, String[] args) throws IOException {
        require(args.length == 3 && Set.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT)), HELP.get("pvp"));
        var town = NODES.getTowns$nodes().values().stream().filter(t -> t.getName().equalsIgnoreCase(args[1]))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Town not found."));
        boolean allowed = args[2].equalsIgnoreCase("on");
        setTownPvp(town, allowed);
        tell(sender, "PvP " + (allowed ? "enabled" : "disabled") + " in " + town.getName() + " territory.");
    }

    public static void setTownPvp(phonon.nodes.objects.Town town, boolean value) throws IOException {
        active.store.set("town-pvp." + town.getUuid(), value);
        TownFlags.INSTANCE.setPvp(town, value);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getDamageSource().getCausingEntity() == null
            || !(event.getDamageSource().getCausingEntity() instanceof Player attacker) || victim == attacker) return;
        Location where = victim.getLocation();
        var territory = NODES.getTerritoryFromBlock(where.getBlockX(), where.getBlockZ());
        var town = territory == null ? null : territory.getTown();
        if (town != null && !store.data().getBoolean("town-pvp." + town.getUuid(), true)) event.setCancelled(true);
    }

    private static void inactive(CommandSender sender, String[] args) {
        require(args.length <= 2, HELP.get("inactive"));
        int days = args.length == 2 ? number(args[1], 1, 36500, "days") : 30;
        Instant now = Instant.now();
        long before = now.minus(Duration.ofDays(days)).toEpochMilli();
        var entries = new ArrayList<Map.Entry<String, Long>>();
        for (var r : NODES.getResidents$nodes().values()) {
            var player = Bukkit.getOfflinePlayer(r.getUuid());
            long login = player.getLastLogin();
            if (login <= 0) login = player.getLastPlayed();
            if (!player.isOnline() && login > 0 && login <= before) {
                entries.add(Map.entry(Objects.toString(r.getName(), r.getUuid().toString()), login));
            }
        }
        entries.sort(Map.Entry.comparingByValue());
        tell(sender, entries.size() + " residents inactive for at least " + days + " days.");
        for (var e : entries) tell(sender, e.getKey() + " · " + Duration.between(Instant.ofEpochMilli(e.getValue()), now).toDays() + " days");
    }

    private static void resetStats(CommandSender sender, String[] args) throws ReflectiveOperationException {
        require(args.length >= 3 && args[1].equalsIgnoreCase("reset"), HELP.get("stats"));
        boolean everyone = args[2].equalsIgnoreCase("all");
        require(everyone ? args.length == 4 && args[3].equalsIgnoreCase("confirm") : args.length == 3, HELP.get("stats"));
        UUID id = everyone ? null : resident(args[2]).getUuid();
        var tracker = KillTrackerManager.INSTANCE;
        Field field = KillTrackerManager.class.getDeclaredField("stats");
        field.setAccessible(true);
        int changed;
        synchronized (tracker) {
            var stats = (Map<?, ?>) field.get(null);
            if (everyone) {
                changed = stats.size();
                stats.clear();
            } else changed = stats.remove(id) == null ? 0 : 1;
            tracker.save();
        }
        if (everyone) changed = Math.max(changed, PlayerStats.INSTANCE.resetAll());
        else PlayerStats.INSTANCE.reset(id);
        PlayerStats.INSTANCE.save();
        tell(sender, "Reset K/D records: " + changed + ".");
    }

    private static void plot(CommandSender sender, String[] a) {
        if (a.length < 2) { tell(sender, "/nodesadmin " + HELP.get("plot")); return; }
        var manager = PlotManager.INSTANCE;
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                require(a.length == 2, "plot list");
                var plots = manager.allPlots();
                tell(sender, plots.size() + " plots.");
                plots.stream().sorted(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)))
                    .forEach(p -> tell(sender, p.getName() + " · " + p.getTownName() + " · " + p.centerString()));
            }
            case "delete" -> {
                require(a.length >= 3, "plot delete <name>");
                require(manager.deletePlot(words(a, 2)), "Plot not found.");
                manager.save();
                tell(sender, "Deleted plot " + words(a, 2) + ".");
            }
            default -> throw new IllegalArgumentException(HELP.get("plot"));
        }
    }

    private void rally(CommandSender sender, String[] a) throws IOException {
        require(a.length == 3, HELP.get("rallycap"));
        var nation = NODES.getNations$nodes().values().stream().filter(n -> n.getName().equalsIgnoreCase(a[1]))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Nation not found."));
        int limit = number(a[2], -1, Integer.MAX_VALUE, "rally cap");
        require(limit != 0, "Use a positive cap or -1 for unlimited.");
        store.set("rally-caps." + nation.getUuid(), limit);
        tell(sender, nation.getName() + " rally cap: " + (limit == -1 ? "unlimited" : limit) + ".");
    }

    private static void miningBoost(CommandSender sender, String[] args) {
        if (args.length == 1) {
            tell(sender, "/nodesadmin miningboost global <duration> [tier]");
            tell(sender, "/nodesadmin miningboost player <name> <duration> [tier]");
            tell(sender, "/nodesadmin miningboost clear global|player [name]");
            return;
        }
        var boost = MiningBoost.INSTANCE;
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("clear")) {
            if (args.length == 3 && args[2].equalsIgnoreCase("global")) boost.clearGlobal();
            else if (args.length == 4 && args[2].equalsIgnoreCase("player")) boost.clearPlayer(resident(args[3]).getUuid());
            else throw new IllegalArgumentException("/nodesadmin miningboost clear global|player [name]");
            tell(sender, "Mining boost cleared.");
            return;
        }
        require(Set.of("global", "player").contains(action), HELP.get("miningboost"));
        int timeIndex = action.equals("global") ? 2 : 3;
        require(args.length == timeIndex + 1 || args.length == timeIndex + 2, HELP.get("miningboost"));
        Long duration = boost.parseDurationMs(args[timeIndex]);
        require(duration != null && duration > 0 && duration < Long.MAX_VALUE - System.currentTimeMillis(), "Invalid duration. Use a value such as 30m.");
        int tier = args.length == timeIndex + 2 ? number(args[timeIndex + 1], 1, MiningBoost.MAX_TIER, "tier") : 1;
        if (action.equals("global")) boost.setGlobal(tier, duration, sender.getName());
        else {
            var recipient = resident(args[2]);
            boost.setPlayer(recipient.getUuid(), recipient.getName(), tier, duration, sender.getName());
        }
        tell(sender, "Mining boost set to tier " + tier + ".");
    }

    private static Integer cap(Player player) {
        if (active == null) return null;
        var resident = NODES.getResident(player);
        var nation = resident == null || resident.getTown() == null ? null : resident.getTown().getNation();
        if (nation == null) return null;
        var cfg = active.store.data();
        String path = "rally-caps." + nation.getUuid();
        return cfg.isInt(path) ? cfg.getInt(path) : null;
    }

    public static boolean getRallyCapEnabled(Config config, Player player) {
        Integer cap = cap(player);
        return cap == null ? config.getRallyCapEnabled() : cap > 0;
    }
    public static boolean getRallyCapApplyToNations(Config config, Player player) {
        return cap(player) != null || config.getRallyCapApplyToNations();
    }
    public static int getRallyCapSize(Config config, Player player) {
        Integer cap = cap(player);
        return cap == null ? config.getRallyCapSize() : cap;
    }

    static Resident resident(String name) {
        return NODES.getResidents$nodes().values().stream().filter(r -> name.equalsIgnoreCase(r.getName())
            || name.equalsIgnoreCase(r.getUuid().toString())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Nodes resident not found."));
    }
    static void announce(String prefix, String message) {
        String line = "[" + prefix + "] " + message;
        Bukkit.getOnlinePlayers().forEach(player -> tell(player, line));
        tell(Bukkit.getConsoleSender(), line);
    }
    static void tell(CommandSender sender, String message) { ((net.kyori.adventure.audience.Audience) sender).sendMessage(Component.text(message)); }
    static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
    static int number(String s, int min, int max, String what) {
        try { int n = Integer.parseInt(s); if (n >= min && n <= max) return n; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Invalid " + what + ".");
    }
    static String words(String[] a, int from) {
        require(a.length > from, "Enter a message or name.");
        String text = String.join(" ", Arrays.copyOfRange(a, from, a.length)).trim();
        require(!text.isEmpty() && text.length() <= 512, "Use between 1 and 512 characters.");
        return text;
    }
    static final class Store {
        private final Path file;
        private final YamlConfiguration data = new YamlConfiguration();
        Store(JavaPlugin plugin) throws IOException {
            this.file = plugin.getDataFolder().toPath().resolve("admin-tools.yml");
            if (!Files.exists(file)) return;
            try { data.load(file.toFile()); }
            catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("Invalid " + file, e); }
        }
        YamlConfiguration data() { return data; }
        void set(String path, Object value) throws IOException {
            Object before = data.get(path);
            data.set(path, value);
            try { write(); }
            catch (IOException e) {
                data.set(path, before);
                throw e;
            }
        }
        private void write() throws IOException {
            Files.createDirectories(file.getParent());
            var temp = Files.createTempFile(file.getParent(), ".admin-tools-", ".tmp");
            try {
                data.save(temp.toFile());
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
    }
}
