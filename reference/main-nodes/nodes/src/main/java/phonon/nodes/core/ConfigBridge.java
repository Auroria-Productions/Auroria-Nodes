package phonon.nodes.core;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

final class ConfigBridge {
    static volatile FileConfiguration latest;
    static void prepare(FileConfiguration c){
        latest=c;
        for(String key:List.copyOf(c.getKeys(true)))if(c.get(key) instanceof String value&&value.matches("(?i)[0-9].*[dhms]")){
            if(key.endsWith("Period")||key.endsWith("Cooldown")||key.endsWith("Expiry")||key.endsWith("Time")||key.equals("backupPeriod")){
                long time=TimeValue.millis(value);c.set(key,key.equals("townSpawnTime")?time/1000:time);
            }
        }
        alias(c,"mainPeriodicTick","townCooldownUpdateTick");alias(c,"nametagPipelineTicks","nametagUpdateInterval");
        alias(c,"nametagProximityRadius","nametagProximityDistance");alias(c,"nametagProximityRefreshTicks","nametagUpdatePeriod");
        alias(c,"punishBlockGlitchingAttempts","antiBlockGlitchAttempts");
        alias(c,"tabMaxEntriesPerColumn","tabList.maxPlayersPerSection");alias(c,"tabEnabled","tabList.enabled");
        alias(c,"tablist.enabled","tabList.enabled");alias(c,"tablist.header.lines","tabList.header");alias(c,"tablist.footer.lines","tabList.footer");
        for(String group:List.of("staff","town","allies","enemies"))alias(c,"tablist.groups."+switch(group){case "allies"->"ally";case "enemies"->"enemy";default->group;}+".header","tabList.columnHeaders."+group);
        if(c.contains("nametagProximityDistance"))c.set("nametagProximityEnabled",true);
        if(c.contains("staticFlagblockMaterial")&&c.getBoolean("staticFlagblock",false))c.set("flagTopBlockMaterial",c.getString("staticFlagblockMaterial").toUpperCase(Locale.ROOT));
        if(c.getBoolean("noFriendlyFire",false)){c.set("allowNationFriendlyFire",false);c.set("allowAllyFriendlyFire",false);}
        // Legacy lower-case 'gold' means GOLD_INGOT in teleport material sections.
        for(String key:List.of("outpostTeleportCost","nationTownTeleportCost")){
            var section=c.getConfigurationSection(key);if(section!=null&&section.contains("gold")){section.set("gold_ingot",section.get("gold"));section.set("gold",null);}
        }
    }
    private static void alias(FileConfiguration c,String nativeKey,String oldKey){if(c.contains(oldKey))c.set(nativeKey,c.get(oldKey));}
}
