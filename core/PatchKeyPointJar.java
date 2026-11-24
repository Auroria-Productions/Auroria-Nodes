import org.objectweb.asm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** A non-destructive overlay on the already-patched, working dev.5.8 core. */
public final class PatchKeyPointJar {
    private static final String PLUGIN="phonon/nodes/NodesPlugin";
    private static final String ENTRY="phonon/nodes/keywar/KeyPointWars";
    private static byte[] patchPlugin(byte[] bytes) {
        var reader=new ClassReader(bytes);
        var writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex) {
                MethodVisitor delegate=super.visitMethod(access,name,desc,sig,ex);
                if(name.equals("onEnable")&&desc.equals("()V"))return new MethodVisitor(Opcodes.ASM9,delegate) {
                    @Override public void visitInsn(int opcode) {
                        if(opcode==Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,ENTRY,"start","(Lorg/bukkit/plugin/java/JavaPlugin;)V",false);
                        }
                        super.visitInsn(opcode);
                    }
                };
                if(name.equals("onDisable")&&desc.equals("()V"))return new MethodVisitor(Opcodes.ASM9,delegate) {
                    @Override public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,ENTRY,"stop","()V",false);
                    }
                };
                return delegate;
            }
        },0);
        return writer.toByteArray();
    }
    private static String pluginYaml(String content) {
        if(content.contains("  keywar:\n"))throw new IllegalStateException("Already patched: keywar command exists");
        String command="commands:\n  keywar:\n    description: Staff-supervised one-life key-point wars\n    aliases: [kpwar, keypointwar]\n    permission: nodes.command.keywar\n    usage: /keywar help\n";
        String perms="permissions:\n  nodes.command.keywar:\n    description: Use /keywar\n    default: true\n  nodes.keywar.staff:\n    description: Manage battle setup, roster, start and outcomes\n    default: op\n";
        content=content.replaceFirst("(?m)^commands:\\s*\\n",java.util.regex.Matcher.quoteReplacement(command));
        content=content.replaceFirst("(?m)^permissions:\\s*\\n",java.util.regex.Matcher.quoteReplacement(perms));
        if(!content.contains("  keywar:\n")||!content.contains("  nodes.keywar.staff:"))throw new IllegalStateException("Missing plugin.yml sections");
        return content.replace("version: 1.21-26.3-core-dev.5.8","version: 1.21-26.3-core-dev.5.11-refinery1182-keywarcfg");
    }
    private static String config(String existing) {
        if(existing.matches("(?s).*\\nwar:\\s*\\n.*"))throw new IllegalStateException("Existing 'war' section needs merge, not duplicate");
        return existing + "\n# Tertiary staff-supervised war mode; independent of classic/NodesWar.\n" +
            "# Enabled by default for new installations; existing server choices are preserved.\n" +
            "war:\n"+
            "  enabled: true\n"+
            "  keyPointWars:\n"+
            "    enabled: true\n"+
            "    stateFile: data/war/key-point-battles.json\n"+
            "    defaultRadius: 96\n"+
            "    maxRadius: 256\n"+
            "    maxPointsPerWar: 12\n"+
            "    warSicknessSeconds: 600\n"+
            "    abandonOnExit: true\n";
    }
    private static void put(JarOutputStream dest,String path,byte[] data) throws Exception {
        JarEntry entry=new JarEntry(path);entry.setTime(0);dest.putNextEntry(entry);dest.write(data);dest.closeEntry();
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Usage: PatchKeyPointJar base-dev5.8.jar compiled-class-dir out.jar refinery-config.yml");
        Path base=Path.of(args[0]),folder=Path.of(args[1]),output=Path.of(args[2]);
        if(base.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()))throw new IllegalArgumentException("Do not overwrite your source JAR");
        Files.createDirectories(output.toAbsolutePath().getParent());
        int count=0;
        try(JarFile src=new JarFile(base.toFile());JarOutputStream dst=new JarOutputStream(Files.newOutputStream(output))) {
            for(var it=src.entries();it.hasMoreElements();) {
                JarEntry entry=it.nextElement();if(entry.isDirectory())continue;
                String path=entry.getName();
                if(path.startsWith("META-INF/") && (path.endsWith(".SF")||path.endsWith(".RSA")||path.endsWith(".DSA")))continue;
                if(path.equals("phonon/nodes/imported/ForkFeatures.class"))continue; // modern 1.18.2 subsystem replaces the legacy refinery registration
                byte[] data;
                try(var in=src.getInputStream(entry)){data=in.readAllBytes();}
                if(path.equals("phonon/nodes/NodesPlugin.class")) {data=patchPlugin(data);count++;}
                if(path.equals("plugin.yml")) {data=pluginYaml(new String(data,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);count++;}
                if(path.equals("config.yml")) {data=config(new String(data,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);count++;}
                put(dst,path,data);
            }
            if(count!=3)throw new IllegalStateException("Missing original plugin bootstrap or metadata");
            try(var stream=Files.walk(folder)) {
                for(Path path:stream.filter(Files::isRegularFile).sorted().toList()) {
                    String rel=folder.relativize(path).toString().replace('\\','/');
                    if(src.getJarEntry(rel)!=null && !rel.equals("phonon/nodes/imported/ForkFeatures.class"))throw new IllegalStateException("Class already present in base: "+rel);
                    put(dst,rel,Files.readAllBytes(path));
                }
            }
        }
        // Insert the canonical 1.18.2-style recipe/type configuration as a separate safe resource.
        try(var fs = java.nio.file.FileSystems.newFileSystem(output, (ClassLoader)null)) {
            java.nio.file.Files.write(fs.getPath("refinery-1182.yml"), Files.readAllBytes(Path.of(args[3])));
        }
        System.out.println("Merged dev.5.8 + Key-point Wars + 1.18.2 refinery + MapEditApi shim to "+output);
    }
}
