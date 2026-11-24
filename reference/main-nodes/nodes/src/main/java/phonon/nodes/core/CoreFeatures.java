package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** MainNodes-owned lifecycle; uses the original Plugin instance, commands, folders and permissions. */
public final class CoreFeatures implements AutoCloseable {
    static CoreFeatures instance;
    final JavaPlugin plugin;final Native api;final CoreData data;final FileConfiguration config;
    final Politics politics;final CoreRules rules;final CoreEconomy economy;final CoreWar war;final Presentation presentation;final Integrations integrations;
    final Map<String,CommandExecutor> originals=new HashMap<>();
    private int tick;
    public static void start(JavaPlugin plugin)throws Exception{if(instance!=null)instance.close();instance=new CoreFeatures(plugin);instance.enable();}
    private CoreFeatures(JavaPlugin plugin)throws Exception{
        this.plugin=plugin;api=new Native(plugin);config=ConfigBridge.latest!=null?ConfigBridge.latest:plugin.getConfig();
        data=new CoreData(plugin.getDataFolder().toPath().resolve("core/state.yml"));politics=new Politics(this);economy=new CoreEconomy(this);war=new CoreWar(this);rules=new CoreRules(this);presentation=new Presentation(this);integrations=new Integrations(this);
    }
    private void enable(){
        Native.call(api.singleton("phonon.nodes.tab.TabListConfig"),"load",config.getConfigurationSection("tablist"));
        for(String name:List.of("town","nation","nodesadmin","war","peace","ally","unally")){
            PluginCommand command=plugin.getCommand(name);if(command==null)continue;CommandExecutor original=command.getExecutor();originals.put(name,original);
            command.setExecutor((sender,cmd,label,args)->{
                try{
                    if(command(sender,name,args)){integrations.log("command",sender instanceof Player actor?actor.getUniqueId():null,name+" "+String.join(" ",args));return true;}
                    if(!rules.command(sender,name,args))return true;
                    boolean result=original.onCommand(sender,cmd,label,args);rules.afterCommand(sender,name,args);integrations.log("command",sender instanceof Player actor?actor.getUniqueId():null,name+" "+String.join(" ",args));return result;
                }catch(IllegalArgumentException e){say(sender,e.getMessage());return true;}catch(Exception e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Core command stopped",e);say(sender,"Operation stopped; check server log.");return true;}
            });
        }
        Bukkit.getPluginManager().registerEvents(rules,plugin);Bukkit.getPluginManager().registerEvents(presentation,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            try{tick++;war.tick();rules.tick();presentation.tick();if(tick%10==0){politics.tick();economy.tick();integrations.tick();data.save();}}
            catch(Exception e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Core periodic operation stopped",e);}
        },20,20);
        plugin.getLogger().info("Integrated core features loaded: subjects, tributaries, governance, treasury, billing, rules, presentation and integrations.");
    }
    boolean command(CommandSender sender,String root,String[] args)throws Exception{
        if(root.equals("nodesadmin")&&args.length>0&&args[0].equalsIgnoreCase("core")){
            if(!sender.hasPermission("nodes.admin"))throw new IllegalArgumentException("Staff permission required.");
            if(args.length==1){say(sender,"/nda core region <nation> <minX> <minZ> <maxX> <maxZ> | audit | info");return true;}
            if(args[1].equalsIgnoreCase("region")){
                if(args.length!=7)throw new IllegalArgumentException("Specify nation and four block coordinates.");Object n=politics.target(args[2]);
                int x1=Integer.parseInt(args[3]),z1=Integer.parseInt(args[4]),x2=Integer.parseInt(args[5]),z2=Integer.parseInt(args[6]);if(x1>x2||z1>z2)throw new IllegalArgumentException("Invalid region bounds.");
                String p="regions."+Native.id(n);data.yaml.set(p,List.of(x1,z1,x2,z2));data.save();say(sender,"Nation region saved.");return true;
            }
            if(args[1].equalsIgnoreCase("resolve")){if(args.length!=4||!Set.of("applied","not-applied").contains(args[3]))throw new IllegalArgumentException("/nda core resolve <id> applied|not-applied");economy.resolve(args[2],args[3].equals("applied"));say(sender,"Payment resolved.");return true;}
            if(args[1].equalsIgnoreCase("inactive")){var flags=data.yaml.getConfigurationSection("inactive");if(flags!=null)for(String id:flags.getKeys(false))if(flags.getBoolean(id))say(sender,id+" is flagged inactive");return true;}
            if(args[1].equalsIgnoreCase("relay")){if(bool("relayThirdParty",false))integrations.discord("thirdPartyPlaceholderName","thirdPartyPlaceholderMessagePrefix",String.join(" ",Arrays.copyOfRange(args,2,args.length)));say(sender,"Third-party relay submitted when enabled and connected.");return true;}
            economy.audit(sender);return true;
        }
        if(!(sender instanceof Player p))return false;
        if(root.equals("nation")&&args.length>0&&Set.of("vassal","tributary","subjects").contains(args[0].toLowerCase(Locale.ROOT)))return politics.command(p,args[0].toLowerCase(Locale.ROOT),args);
        if(root.equals("town")&&args.length>1&&args[0].equalsIgnoreCase("rp")){String message=String.join(" ",Arrays.copyOfRange(args,1,args.length));for(Player viewer:p.getWorld().getPlayers())if(viewer.getLocation().distanceSquared(p.getLocation())<=Math.pow(number("rpChatDistance",100),2))say(viewer,"[RP] "+p.getName()+": "+message);return true;}
        if(root.equals("town")&&args.length>0&&args[0].equalsIgnoreCase("waypoint")){integrations.waypoint(p);return true;}
        if(economy.command(p,root,args))return true;
        return war.command(p,root,args);
    }
    boolean bool(String key,boolean fallback){return config.getBoolean(key,fallback);}
    double number(String key,double fallback){double n=config.getDouble(key,fallback);if(!Double.isFinite(n))throw new IllegalArgumentException("Non-finite config number: "+key);return n;}
    long duration(String key,long fallback){Object raw=config.get(key);return raw==null?fallback:TimeValue.millis(raw);}
    void say(CommandSender sender,String text){sender.sendMessage(ChatColor.GOLD+"[Nodes] "+ChatColor.WHITE+text);}
    void announce(String template,Map<String,String> replacements){String text=template;for(var e:replacements.entrySet())text=text.replace("%"+e.getKey()+"%",e.getValue());Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',text));integrations.log("event",null,text);if(bool("relayWar",false))integrations.discord("warChannelName","warMessagePrefix",text);}
    @Override public void close(){
        if(instance!=this)return;try{presentation.close();rules.close();integrations.close();politics.save();data.save();}finally{instance=null;}
    }
}
