import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.objectweb.asm.*;

public final class BuildMilzzy {
    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[1]);
        try (var base = new JarFile(args[0]); var imported = new JarFile(args[3]); var ginjer = new JarFile(args[4]); var out = new JarOutputStream(Files.newOutputStream(Path.of(args[2])))) {
            var sourceConfig = yamlMap(ginjer.getInputStream(ginjer.getJarEntry("ginjer-nodes-master/nodes/src/main/resources/config.yml")));
            byte[] bundledDefaults = featureDefaults(sourceConfig);
            for (var it = base.entries(); it.hasMoreElements();) {
                var entry = it.nextElement();
                if (entry.isDirectory() || Files.isRegularFile(classes.resolve(entry.getName()))) continue;
                var name = entry.getName();
                byte[] data;
                try (var stream = base.getInputStream(entry)) { data = stream.readAllBytes(); }
                if (name.equals("plugin.yml")) {
                    data = pluginDescription(data, ginjer);
                } else if (name.equals("config.yml")) {
                    data = Files.readAllBytes(Path.of("config.yml"));
                } else if (name.startsWith("phonon/nodes/") && name.endsWith(".class")) {
                    data = PerformancePatch.patch(GinjerPatch.patch(patch(data)));
                }
                put(out, name, data);
            }
            put(out, "imported-nodes-defaults.yml", bundledDefaults);
            for (var entry : imported.stream().filter(e -> !e.isDirectory() && feature(e.getName())).toList()) {
                String name = entry.getName();
                if (base.getJarEntry(name) != null) continue;
                try (var in = imported.getInputStream(entry)) {
                    put(out, name, PerformancePatch.patch(adaptFeature(in.readAllBytes())));
                }
            }
            try (var paths = Files.walk(classes)) {
                for (var p : paths.filter(Files::isRegularFile).sorted().toList()) {
                    put(out, classes.relativize(p).toString().replace('\\', '/'), GinjerPatch.patch(Files.readAllBytes(p)));
                }
            }
        }
    }
    private static final Set<String> COMMANDS = Set.of("calendar", "duel", "skills", "map", "refinery", "stats", "leaderboard",
        "plot", "killshop", "bounty", "economy", "economyadmin", "supplydrop", "surrender", "rpchat", "captimes", "raid");
    private static final Set<String> OWNER_COMMANDS = Set.of("CalendarCommand", "DuelCommand", "SkillsCommand", "MapCommand", "RefineryCommand",
        "StatsCommand", "KillShopCommand", "BountyCommand", "EconomyCommand", "EconomyAdminCommand", "SupplyDropCommand", "MiningTowerCommand",
        "SurrenderCommand", "RpChatCommand", "RaidCommand");
    private static final Set<String> SETTINGS = Set.of("calendar", "duel", "poi", "skills", "bounties", "economy", "supplyDrops",
        "togglePVPMechanic", "canToggleTownPVP", "pvpToggleCooldown", "townRenameCooldown", "mobProtection", "warSickness", "invulnerabilityTowns",
        "protectedMobs", "protectedMobsIfPVPOff", "discordWebhookWar", "discordWebhookFlagWar", "discordWebhookRaids", "discordWebhookWarzones",
        "discordRelayWar", "discordRelayFlagWar", "discordRelayRaids", "discordRelayWarzones", "dailyEventTime", "raid", "supplyLines", "maxConcurrentAttacks", "maxAttacksPerTown", "portNotInShipMultiplier",
        "instantAnnexOnCapture", "allyProximity", "allyProximityDistance", "warProximity", "warProximityDistance");
    private static final String COMPAT = "phonon/nodes/imported/ForkCompatibility";

    private static boolean feature(String path) {
        if (!path.startsWith("phonon/nodes/") || !path.endsWith(".class")) return false;
        String name = path.substring("phonon/nodes/".length(), path.length() - 6);
        String outer = name.split("\\$", 2)[0];
        int divider = name.indexOf('/');
        String pkg = divider < 0 ? "" : name.substring(0, divider);
        return Set.of("calendar", "duel", "poi", "skills", "bounty", "economy", "killshop", "refinery", "stats", "rules", "surrender", "supply", "mining").contains(pkg)
            || pkg.equals("commands") && OWNER_COMMANDS.contains(outer.substring(divider + 1).replaceFirst("Kt$", ""))
            || Set.of("WorldMap", "WorldMapKt", "ports/PortPolicies", "ports/PortPolicy", "war/Raid", "war/RaidPhase", "war/RaidState", "utils/AdminPerms", "util/DiscordRelay", "war/WarTimer", "listeners/MiningTowerListener",
                "listeners/RefineryListener", "listeners/StatsListener", "listeners/BountyListener").contains(outer);
    }

    private static byte[] pluginDescription(byte[] data, JarFile ginjer) throws Exception {
        var result = yamlMap(new java.io.ByteArrayInputStream(data));
        var other = yamlMap(ginjer.getInputStream(ginjer.getJarEntry("ginjer-nodes-master/nodes/src/main/resources/plugin.yml")));
        result.put("name", "milzzynodes");
        result.put("provides", List.of("nodes"));
        result.put("version", "1.21-26.3-core-dev.5.7");
        Map<String, Object> existing = section(result, "commands");
        Map<String, Object> incoming = section(other, "commands");
        for (String name : incoming.keySet()) if (COMMANDS.contains(name)) existing.putIfAbsent(name, incoming.get(name));
        var resourceTower = new LinkedHashMap<>(section(incoming, "miningtower"));
        resourceTower.put("usage", "/resourcetower help");
        var nativeTower = section(existing, "miningtower");
        if (!(nativeTower.get("aliases") instanceof List<?> aliases) || !aliases.contains("mt")) {
            resourceTower.put("aliases", List.of("mt"));
        } else {
            resourceTower.remove("aliases");
        }
        existing.put("resourcetower", resourceTower);
        var dependencies = new LinkedHashSet<Object>((List<?>) result.getOrDefault("softdepend", List.of()));
        dependencies.add("Votifier");
        result.put("softdepend", new ArrayList<>(dependencies));
        Map<String, Object> permissions = section(result, "permissions");
        section(other, "permissions").forEach((name, value) -> {
            if (!name.equals("nodes.admin.shards")) permissions.putIfAbsent(name, value);
        });
        var vassal = section(existing, "vassal");
        if (!vassal.containsKey("aliases")) vassal.put("aliases", List.of("vassals"));
        return dump(result);
    }

    private static byte[] featureDefaults(Map<String, Object> config) {
        config.keySet().retainAll(SETTINGS);
        section(config, "raid").put("enabled", false);
        section(config, "supplyLines").put("enabled", false);
        config.put("portNotInShipMultiplier", 1.0);
        config.put("portTownPolicies", false);
        return dump(config);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlMap(java.io.InputStream stream) throws Exception {
        try (stream) { return (Map<String, Object>) new Yaml().load(stream); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> map, String key) {
        return (Map<String, Object>) map.get(key);
    }
    private static byte[] dump(Map<String, Object> data) {
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(data).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] adaptFeature(byte[] data) {
        var reader = new ClassReader(data);
        var writer = new ClassWriter(reader, 0);
        boolean raid = reader.getClassName().equals("phonon/nodes/war/Raid");
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, exceptions)) {
                    @Override public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(value instanceof String text ? path(text) : value);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        if (opcode == Opcodes.GETSTATIC && (owner.equals("phonon/nodes/vassal/Vassals")
                            || raid && owner.equals("phonon/nodes/war/Warzone")) && name.equals("INSTANCE")) return;
                        super.visitFieldInsn(opcode, owner, name, desc);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (raid && owner.equals("phonon/nodes/war/Warzone") && name.equals("isWarzoneTown")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT, name, desc, false);
                            return;
                        }
                        if (owner.equals("phonon/nodes/vassal/Vassals") && Set.of("isBound", "isVassal").contains(name)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/core/ForkDiplomacy", name, desc, false);
                            return;
                        }
                        if (owner.equals("phonon/nodes/Config") && configBridge(name)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT, name, "(Lphonon/nodes/Config;)" + desc.substring(2), false);
                            return;
                        }
                        if (owner.equals("phonon/nodes/Nodes") && name.equals("enableWar") && desc.equals("(ZZZ)V")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/imported/ForkFeatures", "startWar", "(Lphonon/nodes/Nodes;ZZZ)V", false);
                            return;
                        }
                        if (owner.equals("phonon/nodes/war/FlagWar") && name.equals("cancelAttacksForSurrender$nodes")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT, "cancelAttacksForSurrender",
                                "(Lphonon/nodes/war/FlagWar;" + desc.substring(1), false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static boolean configBridge(String name) {
        return name.startsWith("getRaid") || name.startsWith("getSupplyLines") || name.startsWith("getSupplyDrops") || name.startsWith("getDiscordRelay")
            || name.startsWith("getDiscordWebhook")
            || Set.of("getDailyEventTime", "getTogglePVPMechanic", "getMobProtection", "getWarSickness", "getTownRenameCooldown",
                "getPvpToggleCooldown", "getProtectedMobs", "getProtectedMobsIfPVPOff", "getInvulnerabilityTowns").contains(name);
    }

    private static byte[] patch(byte[] data) {
        var reader = new ClassReader(data);
        var writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (reader.getClassName().equals("phonon/nodes/killtracker/KillTrackerManager") && name.equals("dirty")) access |= Opcodes.ACC_VOLATILE;
                return super.visitField(access, name, desc, sig, value);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                String owner = reader.getClassName();
                boolean tracker = owner.equals("phonon/nodes/killtracker/KillTrackerManager");
                if (tracker && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) == Opcodes.ACC_PUBLIC) access |= Opcodes.ACC_SYNCHRONIZED;
                var method = super.visitMethod(access, name, desc, signature, exceptions);
                boolean rally = owner.equals("phonon/nodes/listeners/NodesPlayerJoinQuitListener") && name.equals("onPlayerJoin");
                boolean mining = owner.equals("phonon/nodes/listeners/NodesWorldListenerKt") && name.equals("handleHiddenOre");
                boolean shutdown = owner.equals("phonon/nodes/NodesPlugin") && name.equals("onDisable");
                boolean startup = reader.getClassName().equals("phonon/nodes/NodesPlugin") && name.equals("onEnable");
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (shutdown) super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/admin/AdminTools", "shutdown", "()V", false);
                        if (startup) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/compat/PluginIdentity", "prepareCore",
                                "(Lorg/bukkit/plugin/java/JavaPlugin;)V", false);
                        }
                    }
                    @Override public void visitInsn(int opcode) {
                        if (startup && opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/admin/AdminTools", "install",
                                "(Lorg/bukkit/plugin/java/JavaPlugin;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                    @Override public void visitMethodInsn(int opcode, String target, String method, String desc, boolean itf) {
                        if (mining && target.equals("phonon/nodes/nodesaddons/MineBuffEffect")
                            && Set.of("getChanceMultiplier", "getAmountMultiplier").contains(method)) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT,
                                method.equals("getChanceMultiplier") ? "miningChance" : "miningAmount",
                                "(Lphonon/nodes/nodesaddons/MineBuffEffect;Lorg/bukkit/entity/Player;)D", false);
                            return;
                        }
                        if (rally && target.equals("phonon/nodes/Config")
                            && java.util.Set.of("getRallyCapEnabled", "getRallyCapSize", "getRallyCapApplyToNations").contains(method)) {
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/admin/AdminTools", method,
                                "(Lphonon/nodes/Config;Lorg/bukkit/entity/Player;)" + desc.substring(2), false);
                            return;
                        }
                        super.visitMethodInsn(opcode, target, method, desc, itf);
                    }
                    @Override public void visitLdcInsn(Object v) {
                        super.visitLdcInsn(v instanceof String text ? path(text) : v);
                    }
                    @Override public void visitInvokeDynamicInsn(String key, String desc, Handle handle, Object... values) {
                        for (int i = 0; i < values.length; i++) {
                            if (values[i] instanceof String text) values[i] = path(text);
                        }
                        super.visitInvokeDynamicInsn(key, desc, handle, values);
                    }
                    @Override public void visitMaxs(int stack, int locals) { super.visitMaxs(rally || mining ? stack + 1 : startup ? Math.max(1, stack) : stack, locals); }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static String path(String value) {
        return value.replaceAll("plugins/nodes(?=/|$)", "plugins/milzzynodes");
    }

    private static void put(JarOutputStream out, String name, byte[] data) throws Exception {
        var entry = new JarEntry(name);
        entry.setTime(0);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }
}
