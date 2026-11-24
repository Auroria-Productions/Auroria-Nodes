package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Called directly by core classes. Packaging adds the same hooks to the supplied compiled base. */
public final class CoreHooks {
    static final ThreadLocal<Boolean> transferring=ThreadLocal.withInitial(()->false);
    private CoreHooks(){}
    public static void prepare(FileConfiguration config){ConfigBridge.prepare(config);}
    public static void enable(JavaPlugin plugin){
        try{if(!Boolean.TRUE.equals(Native.call(new Native(plugin).nodes,"getInitialized$nodes")))return;}catch(Exception ignored){}
        try{CoreFeatures.start(plugin);}catch(Exception e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Core feature initialization failed; disabling Nodes to avoid partially enforced rules",e);plugin.getServer().getPluginManager().disablePlugin(plugin);}
    }
    public static void disable(){if(CoreFeatures.instance!=null)CoreFeatures.instance.close();}
    public static int income(Object owner,Material type,int amount){CoreFeatures c=CoreFeatures.instance;return c==null?amount:c.politics.income(owner,type,amount);}
    public static boolean hiddenOre(Player p,Block b){CoreFeatures c=CoreFeatures.instance;return c==null||c.bool("hiddenOreMechanic",true)&&(c.bool("hiddenOreSilktouch",false)||!p.getInventory().getItemInMainHand().containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH));}
    public static boolean canJoin(Object resident,Object town){return CoreFeatures.instance==null||CoreFeatures.instance.rules.canJoin(resident,town);}
    public static void left(Object town,Object resident){CoreFeatures c=CoreFeatures.instance;if(c!=null){Object nation=Native.call(town,"getNation");if(nation!=null){String p="nationLeave."+Native.id(resident);c.data.yaml.set(p+".nation",Native.id(nation).toString());c.data.yaml.set(p+".time",System.currentTimeMillis());c.data.save();}}}
    public static void minimap(Object map){if(CoreFeatures.instance!=null)CoreFeatures.instance.presentation.sidebar(map);}
    public static long attackTime(Object chunk,Object town,long original){CoreFeatures c=CoreFeatures.instance;if(c==null)return original;Object territory=Native.call(chunk,"getTerritory");String id=String.valueOf(Native.call(territory,"getId"));return c.data.yaml.contains("spheres."+id)?Math.max(20,(long)c.number("chunkAttackTimeSphere",900)):original;}
    public static void accelerate(Object attack){CoreFeatures c=CoreFeatures.instance;if(c==null||!c.bool("playersInChunkMultiplier",false))return;Object town=Native.call(attack,"getTown");Block base=(Block)Native.call(attack,"getFlagBase");int count=0;for(Player p:base.getWorld().getPlayers())if(p.getLocation().getChunk().equals(base.getChunk())&&c.api.town(p.getUniqueId())==town)count++;if(count>1){long progress=((Number)Native.call(attack,"getProgress")).longValue(),time=((Number)Native.call(attack,"getAttackTime")).longValue();Native.call(attack,"setProgress",Math.min(time,progress+(count-1)*20L));}}
    public static boolean worldEvent(org.bukkit.event.Event event){CoreFeatures c=CoreFeatures.instance;if(c==null)return true;org.bukkit.Location location=event instanceof org.bukkit.event.block.BlockEvent e?e.getBlock().getLocation():event instanceof org.bukkit.event.player.PlayerEvent e?e.getPlayer().getLocation():event instanceof org.bukkit.event.entity.EntityEvent e?e.getEntity().getLocation():null;return location==null||c.rules.world(location);}
    public static boolean interaction(org.bukkit.event.Event event){if(!worldEvent(event))return false;CoreFeatures c=CoreFeatures.instance;if(c==null||!c.bool("allowAllInteractsInFlaggedChunks",false))return true;org.bukkit.Location location=event instanceof org.bukkit.event.block.BlockEvent e?e.getBlock().getLocation():event instanceof org.bukkit.event.player.PlayerInteractEvent e&&e.getClickedBlock()!=null?e.getClickedBlock().getLocation():event instanceof org.bukkit.event.player.PlayerEvent e?e.getPlayer().getLocation():null;return location==null||!c.rules.flagged(location);}
    public static boolean border(Object neighbor){CoreFeatures c=CoreFeatures.instance;if(c==null||c.bool("allowBorderSkirmishFromWilderness",true))return true;Object war=Native.call(c.api.nodes,"getWar$nodes");return !Boolean.TRUE.equals(Native.call(war,"getCanOnlyAttackBorders$nodes"))||neighbor!=null&&Native.call(Native.call(neighbor,"getTerritory"),"getTown")!=null;}
    public static boolean papi(){CoreFeatures c=CoreFeatures.instance;return c==null?(ConfigBridge.latest==null||ConfigBridge.latest.getBoolean("placeholderapiEnabled",true)):c.bool("placeholderapiEnabled",true);}
    public static boolean skull(org.bukkit.inventory.meta.SkullMeta meta,org.bukkit.OfflinePlayer player){CoreFeatures c=CoreFeatures.instance;int mode=c==null?0:c.config.getInt("skullsGUIToggle",0);return mode!=2&&(mode!=1||!c.rules.globalWar())&&meta.setOwningPlayer(player);}
    public static void skullProfile(org.bukkit.inventory.meta.SkullMeta meta,org.bukkit.profile.PlayerProfile profile){CoreFeatures c=CoreFeatures.instance;int mode=c==null?0:c.config.getInt("skullsGUIToggle",0);if(mode!=2&&(mode!=1||!c.rules.globalWar()))meta.setOwnerProfile(profile);}
    public static boolean respawn(){return CoreFeatures.instance==null||!CoreFeatures.instance.bool("disableFlagRespawner",false);}
    public static boolean crops(){return CoreFeatures.instance==null||CoreFeatures.instance.bool("cropgrowthMechanic",true);}
    public static boolean chat(){return CoreFeatures.instance==null||!CoreFeatures.instance.presentation.customChat();}
    public static void socialInfo(org.bukkit.command.CommandSender sender,Object nation){if(CoreFeatures.instance!=null)CoreFeatures.instance.politics.info(sender,nation);}
    public static boolean nametags(){CoreFeatures c=CoreFeatures.instance;return c==null||c.bool("usePacketNametags",true)&&!c.bool("useUnlimitedNametags",false);}
}
