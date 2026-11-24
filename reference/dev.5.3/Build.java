import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

public final class Build {
    private static final Map<String, String> TYPES = Map.of(
        "net/minecraft/ChatFormatting", "org/bukkit/ChatColor",
        "net/minecraft/network/chat/Component", "phonon/nodes/compat/TagText",
        "net/minecraft/network/chat/MutableComponent", "phonon/nodes/compat/TagText",
        "io/github/retrooper/packetevents/adventure/serializer/legacy/LegacyComponentSerializer",
        "net/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer"
    );

    private static byte[] patch(String entry, byte[] code) {
        var writer = new ClassWriter(0);
        var redirect = new ClassRemapper(writer, new SimpleRemapper(TYPES)) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (entry.equals("phonon/nodes/tab/TabListKt.class")
                    && descriptor.equals("Lkotlin/Metadata;")) return null;
                return super.visitAnnotation(descriptor, visible);
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                if (entry.equals("phonon/nodes/tab/TabListKt.class")
                    && name.startsWith("com/github/retrooper/packetevents/")) return;
                super.visitInnerClass(name, outerName, innerName, access);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (entry.equals("phonon/nodes/tab/TabListKt.class")) {
                    if (name.equals("sendTeamPacket")) return null;
                    if (Set.of("sendTeamCreate", "sendTeamRemove", "sendHeaderFooter").contains(name)) {
                        var method = super.visitMethod(access, name, descriptor, signature, exceptions);
                        method.visitCode();
                        int index = 0;
                        for (var arg : Type.getArgumentTypes(descriptor)) {
                            method.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), index);
                            index += arg.getSize();
                        }
                        method.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/compat/PaperBoards", name, descriptor, false);
                        method.visitInsn(Opcodes.RETURN);
                        method.visitMaxs(index, index);
                        method.visitEnd();
                        return null;
                    }
                }
                var downstream = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, downstream) {
                    private boolean exportResolverPatched;
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN && entry.equals("phonon/nodes/NodesPlugin.class")
                            && name.equals("onDisable")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/compat/PaperBoards",
                                "releaseAll", "()V", false);
                        }
                        super.visitInsn(opcode);
                    }
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        if (opcode == Opcodes.GETSTATIC && owner.equals("java/lang/System")
                            && (field.equals("out") || field.equals("err"))
                            && desc.equals("Ljava/io/PrintStream;")) {
                            super.visitFieldInsn(opcode, "phonon/nodes/compat/NodesLog",
                                field.equals("out") ? "OUT" : "ERR", desc);
                        } else if (opcode == Opcodes.GETSTATIC && owner.equals("org/bukkit/Material")
                            && field.equals("PALE_OAK_FENCE")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/compat/Platform",
                                "paleOakFence", "()Lorg/bukkit/Material;", false);
                        } else {
                            super.visitFieldInsn(opcode, owner, field, desc);
                        }
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(entry.equals("phonon/nodes/core/DynmapInstaller.class")
                            && name.equals("command") ? maxStack + 1 : maxStack, maxLocals);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc,
                                                boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && method.equals("printStackTrace")
                            && desc.equals("()V") && Set.of("java/lang/Throwable", "java/lang/Exception",
                                "java/io/IOException").contains(owner)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/compat/NodesLog",
                                "exception", "(Ljava/lang/Throwable;)V", false);
                            return;
                        }
                        if (entry.equals("phonon/nodes/core/DynmapInstaller.class") && name.equals("command")
                            && !exportResolverPatched && owner.equals("java/nio/file/Path") && method.equals("resolve")
                            && desc.equals("(Ljava/lang/String;)Ljava/nio/file/Path;")) {
                            exportResolverPatched = true;
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, "phonon/nodes/core/DynmapInstaller", "plugin",
                                "Lorg/bukkit/plugin/java/JavaPlugin;");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/core/DynmapExportFiles",
                                "resolve", "(Ljava/nio/file/Path;Ljava/lang/String;Lorg/bukkit/plugin/java/JavaPlugin;)Ljava/nio/file/Path;", false);
                            return;
                        }
                        if (owner.equals("net/minecraft/network/chat/MutableComponent")) {
                            opcode = Opcodes.INVOKEINTERFACE;
                            isInterface = true;
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                    }
                };
            }
        };
        new ClassReader(code).accept(redirect, 0);
        return writer.toByteArray();
    }

    private static byte[] linkDynmapData(byte[] code) {
        var writer = new ClassWriter(0);
        new ClassReader(code).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                             String[] exceptions) {
                var method = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("injectNodeTypes") && !name.equals("warzones")) return method;
                method.visitCode();
                if (name.equals("injectNodeTypes")) method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/tasks/SaveDynmapStateKt",
                    name.equals("injectNodeTypes") ? "access$injectNodeTypesIntoWorldJson" : "access$buildWarzonesJson",
                    desc, false);
                method.visitInsn(name.equals("injectNodeTypes") ? Opcodes.RETURN : Opcodes.ARETURN);
                method.visitMaxs(1, name.equals("injectNodeTypes") ? 1 : 0);
                method.visitEnd();
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        var output = Path.of(args[2]);
        var classes = Path.of(args[1]);
        try (var original = new JarFile(args[0]);
             var out = new JarOutputStream(Files.newOutputStream(output))) {
            for (var entry : Collections.list(original.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || Files.isRegularFile(classes.resolve(name))
                    || name.startsWith("phonon/nodes/nms/NmsKt$")
                    || name.startsWith("phonon/nodes/nms/TeamFieldSetters")
                    || name.startsWith("phonon/nodes/nms/ThrottledPacketQueue")) {
                    continue;
                }
                byte[] bytes;
                try (var input = original.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                if (name.startsWith("phonon/") && name.endsWith(".class")) {
                    bytes = patch(name, bytes);
                } else if (name.equals("config.yml")) {
                    bytes = Files.readAllBytes(Path.of(args[4]));
                } else if (name.equals("plugin.yml")) {
                    var text = new String(bytes, StandardCharsets.UTF_8);
                    bytes = text.replace("api-version: 1.21.11", "api-version: '1.21'")
                        .replace("version: 1.21.11-core-dev.3", "version: 1.21-26.3-core-dev.5.3")
                        .replaceAll("(?m)^-[ \t]*packetevents\\r?\\n", "")
                        .replace("  nation:\n", "  vassal:\n    description: Vassal independence command\n    permission: nodes.command.nation\n    usage: /vassal revolt\n  nation:\n")
                        .getBytes(StandardCharsets.UTF_8);
                }
                put(out, name, bytes);
            }
            try (var paths = Files.walk(classes)) {
                for (var p : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = classes.relativize(p).toString().replace('\\', '/');
                    byte[] bytes = Files.readAllBytes(p);
                    if (name.equals("phonon/nodes/tasks/DynmapData.class")) bytes = linkDynmapData(bytes);
                    put(out, name, bytes);
                }
            }
            try (var kotlin = new JarFile(args[3])) {
                for (var entry : Collections.list(kotlin.entries())) {
                    var name = entry.getName();
                    if (entry.isDirectory() || !(name.startsWith("kotlin/") ||
                        name.equals("META-INF/kotlin-stdlib.kotlin_module") ||
                        name.startsWith("META-INF/LICENSE") || name.startsWith("META-INF/NOTICE"))) continue;
                    try (var input = kotlin.getInputStream(entry)) {
                        if (name.startsWith("META-INF/LICENSE") || name.startsWith("META-INF/NOTICE")) {
                            name = "META-INF/nodes-kotlin-" + name.substring("META-INF/".length());
                        }
                        put(out, name, input.readAllBytes());
                    }
                }
            }
        }
    }

    private static void put(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        var entry = new JarEntry(name);
        entry.setTime(0);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }
}
