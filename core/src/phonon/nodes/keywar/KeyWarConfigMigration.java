package phonon.nodes.keywar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Add missing KeyWar configuration keys without reserializing the entire server YAML. */
public final class KeyWarConfigMigration {
    private KeyWarConfigMigration() {}
    private static final LinkedHashMap<String,String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("enabled", "true");
        DEFAULTS.put("stateFile", "data/war/key-point-battles.json");
        DEFAULTS.put("defaultRadius", "96");
        DEFAULTS.put("maxRadius", "256");
        DEFAULTS.put("maxPointsPerWar", "12");
        DEFAULTS.put("warSicknessSeconds", "600");
        DEFAULTS.put("abandonOnExit", "true");
    }
    private static int spaces(String line) {
        int n=0; while(n<line.length() && line.charAt(n)==' ') n++; return n;
    }
    private static boolean mappingKey(String line,String key,int indent) {
        if(spaces(line)!=indent || line.isBlank() || line.stripLeading().startsWith("#"))return false;
        String stripped=line.substring(indent);
        return stripped.matches(java.util.regex.Pattern.quote(key)+"\\s*:.*");
    }
    private static boolean plainMapping(String line,String key,int indent) {
        String stripped=line.substring(indent).replaceFirst("^"+java.util.regex.Pattern.quote(key)+"\\s*:\\s*", "").trim();
        return stripped.isEmpty() || stripped.startsWith("#");
    }
    private static int endOfMapping(List<String> lines,int start,int indent) {
        for(int i=start+1;i<lines.size();i++) {
            String l=lines.get(i);
            if(l.isBlank()||l.stripLeading().startsWith("#"))continue;
            if(spaces(l)<=indent) return i;
        }
        return lines.size();
    }
    private static String newBlock() {
        StringBuilder s=new StringBuilder();
        s.append("# Tertiary war mode: one life per battle; no automatic territory capture.\n");
        s.append("war:\n  enabled: true\n  keyPointWars:\n");
        for(var entry:DEFAULTS.entrySet())s.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        return s.toString();
    }
    /** Returns true only if the existing config file was updated. Never changes explicitly present values. */
    public static boolean ensure(Path configPath) throws IOException {
        if(!Files.isRegularFile(configPath)) return false;
        String input=Files.readString(configPath,StandardCharsets.UTF_8);
        String newline=input.contains("\r\n")?"\r\n":"\n";
        List<String> lines=new ArrayList<>(Arrays.asList(input.split("\\r?\\n",-1)));
        int war=-1;
        for(int i=0;i<lines.size();i++)if(mappingKey(lines.get(i),"war",0)) {
            if(war>=0) throw new IllegalStateException("Duplicate top-level war sections in config.yml");
            war=i;
        }
        if(war<0) {
            String updated=input+(input.isEmpty()||input.endsWith("\n")?"":"\n")+newBlock().replace("\n",newline);
            atomicWithBackup(configPath,updated);
            return true;
        }
        if(!plainMapping(lines.get(war),"war",0))throw new IllegalStateException("war section is not a YAML block mapping");
        int warEnd=endOfMapping(lines,war,0);
        int keyPoint=-1;
        boolean hasWarEnabled=false;
        for(int i=war+1;i<warEnd;i++) {
            if(mappingKey(lines.get(i),"enabled",2))hasWarEnabled=true;
            if(mappingKey(lines.get(i),"keyPointWars",2)){
                if(keyPoint>=0)throw new IllegalStateException("Duplicate war.keyPointWars sections");
                keyPoint=i;
            }
        }
        if(keyPoint<0) {
            List<String> additions=new ArrayList<>();
            if(!hasWarEnabled)additions.add("  enabled: true");
            additions.add("  # Tertiary war mode: one life per battle; no automatic territory capture.");
            additions.add("  keyPointWars:");
            for(var entry:DEFAULTS.entrySet())additions.add("    "+entry.getKey()+": "+entry.getValue());
            lines.addAll(warEnd,additions);
        } else {
            if(!plainMapping(lines.get(keyPoint),"keyPointWars",2))throw new IllegalStateException("war.keyPointWars is not a YAML block mapping");
            int kpEnd=endOfMapping(lines,keyPoint,2);
            List<String> additions=new ArrayList<>();
            for(Map.Entry<String,String> entry:DEFAULTS.entrySet()) {
                boolean found=false;
                for(int i=keyPoint+1;i<kpEnd;i++)if(mappingKey(lines.get(i),entry.getKey(),4)){
                    found=true;break;
                }
                if(!found)additions.add("    "+entry.getKey()+": "+entry.getValue());
            }
            if(!hasWarEnabled) {
                // Insert immediately after war: so the key remains at the correct depth.
                lines.add(war+1,"  enabled: true");
                kpEnd++;
            }
            if(additions.isEmpty() && hasWarEnabled)return false;
            lines.addAll(kpEnd,additions);
        }
        String updated=String.join(newline,lines);
        if(updated.equals(input))return false;
        atomicWithBackup(configPath,updated);
        return true;
    }
    private static void atomicWithBackup(Path configPath,String updated) throws IOException {
        Path backup=configPath.resolveSibling("config.yml.before-keywar");
        if(Files.notExists(backup)) Files.copy(configPath,backup);
        Path temp=Files.createTempFile(configPath.getParent(),".keywar-config-",".tmp");
        try {
            Files.writeString(temp,updated,StandardCharsets.UTF_8);
            try { Files.move(temp,configPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex) {Files.move(temp,configPath,StandardCopyOption.REPLACE_EXISTING);}
        } finally { Files.deleteIfExists(temp); }
    }
}
