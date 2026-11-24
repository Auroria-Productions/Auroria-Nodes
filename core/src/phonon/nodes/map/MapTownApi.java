package phonon.nodes.map;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import phonon.nodes.Nodes;
import phonon.nodes.objects.*;
import phonon.nodes.utils.Color;

/** Live town operations for authenticated map administrators. */
public final class MapTownApi {
    private static final Gson JSON = new Gson();
    private static final Nodes CORE = Nodes.INSTANCE;
    private static final Method TERRITORY = method(Nodes.class, "getTerritoryFromId-8M4gw-M", int.class);
    private static final Method HOME = method(Town.class, "getHome-L9_GXOM");
    private static final Method ID = method(TerritoryId.class, "unbox-impl");
    private static final Method CREATE_TOWN = method(Nodes.class, "createTown-0E7RQCE", String.class, Territory.class, Resident.class);
    private static final Method CREATE_NATION = method(Nodes.class, "createNation-0E7RQCE", String.class, Town.class, Resident.class);
    private static final Method ADD_LAND = method(Nodes.class, "addTerritoryToTown-gIAlu-s", Town.class, Territory.class);
    private static final Method REMOVE_LAND = method(Nodes.class, "unclaimTerritory-gIAlu-s", Town.class, Territory.class);
    private static final Method JOIN = method(Nodes.class, "addTownToNation-gIAlu-s", Nation.class, Town.class);
    private static final Method LEAVE = method(Nodes.class, "removeTownFromNation-gIAlu-s", Nation.class, Town.class);

    private MapTownApi() {}

    public static String townsJson() {
        return onServerThread(() -> JSON.toJson(snapshot()));
    }

    public static String act(String body, String actor) {
        return onServerThread(() -> {
            try {
                authorize(actor);
                var input = JsonParser.parseString(body).getAsJsonObject();
                var operation = text(input, "op");
                String message = perform(operation, input);
                if (CORE.getNeedsSave$nodes()) CORE.flushTownStateNowImmediate$nodes(true);
                plugin().getLogger().info("Map town editor " + actor + " completed " + operation);
                return JSON.toJson(Map.of("ok", true, "message", message, "data", snapshot()));
            } catch (Expected e) {
                return failure(e.getMessage());
            } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
                return failure("Invalid town editor request.");
            } catch (RuntimeException e) {
                plugin().getLogger().log(Level.WARNING, "Map town editor operation failed", e);
                return failure("The core could not complete the town operation. Refresh before retrying.");
            }
        });
    }

    private static String perform(String op, JsonObject in) {
        switch (op) {
            case "town.create" -> {
                checkLandChange();
                String name = name(in, "name");
                require(CORE.getTowns$nodes().keySet().stream().noneMatch(name::equalsIgnoreCase), "A town with that name already exists.");
                var land = territories(in);
                for (var territory : land) require(territory.getTown() == null && territory.getOccupier() == null,
                    "Select unclaimed and unoccupied territories.");
                Resident leader = text(in, "player").isBlank() ? null : resident(in);
                require(leader == null || leader.getTown() == null, "The selected leader already belongs to a town.");
                var town = (Town) result(CREATE_TOWN, name, land.getFirst(), leader);
                for (var territory : land.subList(1, land.size())) result(ADD_LAND, town, territory);
                return "Created " + town.getName() + ".";
            }
            case "town.removeTerritories" -> {
                checkLandChange();
                var land = territories(in);
                for (var territory : land) {
                    require(territory.getTown() != null && territory.getOccupier() == null, "Select claimed, unoccupied territories.");
                    require(territory != call(TERRITORY, CORE, call(HOME, territory.getTown())), "Set a different town home before removing its current home.");
                }
                for (var territory : land) result(REMOVE_LAND, territory.getTown(), territory);
                return "Removed territories from their towns.";
            }
            case "town.release" -> {
                checkLandChange();
                for (var territory : territories(in)) CORE.releaseTerritory(territory);
                return "Released the selected territories.";
            }
            case "nation.color", "nation.capital", "nation.rename", "nation.delete" -> {
                var nation = CORE.getNationFromName(text(in, "nation"));
                require(nation != null, "The nation no longer exists.");
                switch (op) {
                    case "nation.color" -> { int[] rgb = color(in); CORE.setNationColor(nation, rgb[0], rgb[1], rgb[2]); }
                    case "nation.capital" -> {
                        var town = town(in);
                        require(town.getNation() == nation, "Choose a town in this nation.");
                        CORE.setNationCapital(nation, town);
                    }
                    case "nation.rename" -> require(CORE.renameNation(nation, name(in, "name")), "That nation name is unavailable.");
                    case "nation.delete" -> { checkLandChange(); CORE.destroyNation(nation); }
                }
                return "Updated nation.";
            }
            default -> { }
        }
        var town = town(in);
        switch (op) {
            case "town.rename" -> require(CORE.renameTown(town, name(in, "name")), "That town name is unavailable.");
            case "town.color" -> { int[] rgb = color(in); CORE.setTownColor(town, rgb[0], rgb[1], rgb[2]); }
            case "town.open" -> {
                var open = in.get("open");
                require(open != null && open.isJsonPrimitive() && open.getAsJsonPrimitive().isBoolean(), "Choose whether the town is open.");
                CORE.setTownOpen$nodes(town, open.getAsBoolean());
            }
            case "town.home" -> {
                checkLandChange();
                var land = territories(in);
                require(land.size() == 1 && land.getFirst().getTown() == town && land.getFirst().getOccupier() == null,
                    "Choose one unoccupied territory belonging to this town.");
                CORE.setTownHomeTerritory(town, land.getFirst());
            }
            case "town.addTerritories", "town.capture" -> {
                checkLandChange();
                var land = territories(in);
                if (op.equals("town.capture")) {
                    for (var territory : land) require(territory.getTown() != town, "Choose land outside this town.");
                    for (var territory : land) CORE.captureTerritory(town, territory);
                } else {
                    for (var territory : land) require(territory.getTown() == null && territory.getOccupier() == null,
                        "Choose unclaimed and unoccupied territories.");
                    for (var territory : land) result(ADD_LAND, town, territory);
                }
            }
            case "town.setNation" -> {
                checkLandChange();
                String target = text(in, "nation");
                if (!target.isBlank()) target = name(in, "nation");
                var current = town.getNation();
                if (current != null && current.getName().equalsIgnoreCase(target)) return "Town already belongs to that nation.";
                var requestedName = target;
                var next = CORE.getNations$nodes().values().stream()
                    .filter(n -> n.getName().equalsIgnoreCase(requestedName)).findFirst().orElse(null);
                if (current != null) result(LEAVE, current, town);
                if (next != null) result(JOIN, next, town);
                else if (!target.isBlank()) result(CREATE_NATION, target, town, null);
            }
            case "town.addPlayer", "town.removePlayer", "town.rank" -> {
                var player = resident(in);
                if (op.equals("town.addPlayer")) {
                    if (player.getTown() != null && player.getTown() != town) {
                        require(player.getTown().getLeader() != player, "Set a new leader in the player's current town before moving them.");
                        CORE.removeResidentFromTown(player.getTown(), player);
                    }
                    if (player.getTown() != town) CORE.addResidentToTown(town, player);
                } else {
                    require(player.getTown() == town, "The player does not belong to this town.");
                    if (op.equals("town.removePlayer")) {
                        require(town.getLeader() != player, "Set a new leader before removing the current leader.");
                        CORE.removeResidentFromTown(town, player);
                    } else {
                        String rank = text(in, "rank");
                        require(Set.of("leader", "officer", "member").contains(rank), "Choose a valid town rank.");
                        if (rank.equals("leader")) {
                            CORE.townRemoveOfficer(town, player);
                            CORE.townSetLeader(town, player);
                        } else {
                            require(town.getLeader() != player, "Set a new leader before changing the current leader's rank.");
                            if (rank.equals("officer")) CORE.townAddOfficer(town, player);
                            else CORE.townRemoveOfficer(town, player);
                        }
                    }
                }
            }
            case "town.delete" -> { checkLandChange(); CORE.destroyTown(town); }
            default -> throw new Expected("Unknown town editor operation.");
        }
        return "Updated " + town.getName() + ".";
    }

    private static Map<String, Object> snapshot() {
        var towns = new ArrayList<Map<String, Object>>();
        var nations = new ArrayList<Map<String, Object>>();
        for (var town : CORE.getTowns$nodes().values()) {
            var item = new LinkedHashMap<String, Object>();
            var nation = town.getNation();
            item.put("name", town.getName());
            item.put("home", call(HOME, town));
            item.put("color", hex(town.getColor()));
            item.put("nation", nation == null ? null : nation.getName());
            item.put("capital", nation != null && nation.getCapital() == town);
            item.put("leader", town.getLeader() == null ? null : town.getLeader().getName());
            item.put("officers", players(town.getOfficers()));
            item.put("residents", players(town.getResidents()));
            item.put("territories", ids(town.getTerritories()));
            item.put("captured", ids(town.getCaptured()));
            item.put("claimsUsed", town.getClaimsUsed());
            item.put("claimsMax", town.getClaimsMax());
            item.put("open", town.isOpen());
            towns.add(item);
        }
        for (var nation : CORE.getNations$nodes().values()) {
            nations.add(Map.of("name", nation.getName(), "color", hex(nation.getColor()),
                "capital", nation.getCapital().getName(), "towns", nation.getTowns().stream().map(Town::getName).sorted().toList()));
        }
        return Map.of("towns", towns, "nations", nations, "players", players(CORE.getResidents$nodes().values()), "warBusy", warBusy());
    }

    private static Town town(JsonObject in) {
        var town = CORE.getTownFromName(text(in, "town"));
        require(town != null, "The town no longer exists.");
        return town;
    }

    private static Resident resident(JsonObject in) {
        var resident = CORE.getResidentFromName(text(in, "player"));
        require(resident != null, "Choose a player who has joined this server.");
        return resident;
    }

    private static List<Territory> territories(JsonObject in) {
        var value = in.get("territories");
        require(value != null && value.isJsonArray() && !value.getAsJsonArray().isEmpty()
            && value.getAsJsonArray().size() <= 4096, "Select between 1 and 4096 published territories.");
        var ids = new LinkedHashSet<Integer>();
        for (var id : value.getAsJsonArray()) {
            require(id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber(), "Invalid territory ID.");
            try { ids.add(id.getAsBigDecimal().intValueExact()); }
            catch (ArithmeticException e) { throw new Expected("Invalid territory ID."); }
        }
        var land = new ArrayList<Territory>();
        for (int id : ids) {
            var territory = (Territory) call(TERRITORY, CORE, id);
            require(territory != null, "Territory #" + id + " must be published before editing town ownership. Save a draft and run /nodesmap publish.");
            land.add(territory);
        }
        return land;
    }

    private static void authorize(String actor) {
        if ("console".equals(actor)) return;
        var player = actor.startsWith("player:") ? Bukkit.getPlayer(UUID.fromString(actor.substring(7))) : null;
        require(player != null && player.hasPermission("nodesmap.admin"), "Town editing needs an online administrator with nodesmap.admin or a console editor session.");
    }

    private static boolean warBusy() { return !CORE.getWar().getChunkToAttacker$nodes().isEmpty(); }
    private static void checkLandChange() { require(!warBusy(), "Wait for active flag attacks to finish before editing town ownership."); }

    private static String name(JsonObject in, String field) {
        String name = text(in, field);
        require(name.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,31}"), "Use up to 32 letters, numbers, underscores or hyphens for the name.");
        return name;
    }

    private static int[] color(JsonObject in) {
        String s = text(in, "color");
        require(s.matches("#[0-9a-fA-F]{6}"), "Choose a valid RGB color.");
        int rgb = Integer.parseInt(s.substring(1), 16);
        return new int[] { rgb >> 16, rgb >> 8 & 255, rgb & 255 };
    }

    private static String hex(Color color) { return "#%02x%02x%02x".formatted(color.getR(), color.getG(), color.getB()); }
    private static List<String> players(Collection<Resident> residents) {
        return residents.stream().map(Resident::getName).filter(Objects::nonNull).sorted().toList();
    }
    private static List<Integer> ids(Collection<TerritoryId> ids) {
        return ids.stream().map(id -> (Integer) call(ID, id)).sorted().toList();
    }

    private static String text(JsonObject in, String field) {
        var value = in.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString().trim() : "";
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try { return type.getMethod(name, params); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    private static Object call(Method method, Object receiver, Object... params) {
        try { return method.invoke(receiver, params); }
        catch (InvocationTargetException e) { throw new IllegalStateException("Core method failed: " + method.getName(), e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static Object result(Method method, Object... args) {
        var result = call(method, CORE, args);
        if (result instanceof kotlin.Result.Failure failure) {
            throw new Expected(Objects.toString(failure.exception.getMessage(), failure.exception.getClass().getSimpleName()));
        }
        return result;
    }

    private static JavaPlugin plugin() { return JavaPlugin.getProvidingPlugin(MapTownApi.class); }
    private static String onServerThread(Callable<String> work) {
        Future<String> task = null;
        try {
            if (Bukkit.isPrimaryThread()) return work.call();
            task = Bukkit.getScheduler().callSyncMethod(plugin(), work);
            return task.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("Request interrupted. Refresh before retrying.");
        } catch (TimeoutException e) {
            if (task != null) task.cancel(false);
            return failure("The server is busy. Refresh before retrying.");
        } catch (Exception e) {
            plugin().getLogger().log(Level.WARNING, "Map town request failed", e);
            return failure("Town data is temporarily unavailable.");
        }
    }

    private static String failure(String message) { return JSON.toJson(Map.of("ok", false, "message", message)); }
    private static void require(boolean ok, String message) { if (!ok) throw new Expected(message); }
    private static final class Expected extends RuntimeException { Expected(String message) { super(message); } }
}
