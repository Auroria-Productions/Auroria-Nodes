import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/** Repackage a compiled MilzzyNodes dev.5.7 JAR without reapplying older ASM hooks. */
public final class PatchJar {
    private static final Map<String, String> RELOCATE = Map.ofEntries(
        Map.entry("calendar.json", "calendar/calendar.json"),
        Map.entry("pois.json", "exploration/pois.json"),
        Map.entry("discoveries.json", "exploration/discoveries.json"),
        Map.entry("skills.json", "skills/skills.json"),
        Map.entry("duels.json", "duels/duels.json"),
        Map.entry("bounties.json", "bounties/bounties.json"),
        Map.entry("economy.json", "economy/economy.json"),
        Map.entry("refineries.json", "refineries/refineries.json"),
        Map.entry("player-stats.json", "statistics/player-stats.json"),
        Map.entry("warSession.json", "statistics/warSession.json"),
        Map.entry("supplydrops.json", "supply/supplydrops.json"),
        Map.entry("mining-boosts.json", "mining/mining-boosts.json"),
        Map.entry("mining-towers.json", "mining/mining-towers.json"),
        Map.entry("surrender.json", "diplomacy/surrender.json"),
        Map.entry("townflags.json", "towns/townflags.json"),
        Map.entry("portpolicies.json", "ports/portpolicies.json"));

    private static int relocated;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: PatchJar <existing-jar> <classes-dir> <output-jar>");
        Path base = Path.of(args[0]), classes = Path.of(args[1]), output = Path.of(args[2]);
        if (base.toAbsolutePath().equals(output.toAbsolutePath())) throw new IllegalArgumentException("Do not patch JAR in place");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Set<String> names = new HashSet<>();
        try (var jar = new JarFile(base.toFile()); var stream = new JarOutputStream(Files.newOutputStream(output))) {
            for (var iter = jar.entries(); iter.hasMoreElements();) {
                var item = iter.nextElement();
                if (item.isDirectory()) continue;
                String name = item.getName();
                byte[] content;
                Path replace = classes.resolve(name);
                if (Files.isRegularFile(replace)) content = Files.readAllBytes(replace);
                else try (var in = jar.getInputStream(item)) { content = in.readAllBytes(); }
                if (name.endsWith(".class") && name.startsWith("phonon/nodes/")) content = patchClass(content);
                if (name.equals("plugin.yml")) {
                    String text = new String(content, StandardCharsets.UTF_8);
                    text = text.replace("version: 1.21-26.3-core-dev.5.7", "version: 1.21-26.3-core-dev.5.8");
                    content = text.getBytes(StandardCharsets.UTF_8);
                }
                add(stream, names, name, content);
            }
            try (var files = Files.walk(classes)) {
                for (var path : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = classes.relativize(path).toString().replace('\\','/');
                    if (names.contains(name)) continue;
                    byte[] data = Files.readAllBytes(path);
                    if (name.endsWith(".class") && name.startsWith("phonon/nodes/")) data = patchClass(data);
                    add(stream, names, name, data);
                }
            }
        }
        if (relocated < RELOCATE.size()) throw new IllegalStateException("Found only " + relocated + " JSON paths, expected at least " + RELOCATE.size());
        System.out.println("Patched " + relocated + " JSON path constants; new plugin JAR: " + output);
    }

    private static byte[] patchClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, ex)) {
                    @Override public void visitLdcInsn(Object value) {
                        if (value instanceof String old && old.startsWith("plugins/milzzynodes/") && !old.contains("/data/")) {
                            String file = old.substring("plugins/milzzynodes/".length());
                            String mapped = RELOCATE.get(file);
                            if (mapped != null) {
                                relocated++;
                                value = "plugins/milzzynodes/data/" + mapped;
                            }
                        }
                        super.visitLdcInsn(value);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void add(JarOutputStream out, Set<String> existing, String name, byte[] data) throws Exception {
        if (!existing.add(name)) throw new IllegalArgumentException("Duplicate JAR entry " + name);
        var entry = new JarEntry(name);
        entry.setTime(0);
        out.putNextEntry(entry); out.write(data); out.closeEntry();
    }
}
