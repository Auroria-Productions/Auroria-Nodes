package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.entity.Player;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class Integrations implements AutoCloseable {
    final CoreFeatures c;final ExecutorService worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2048),r->{Thread t=new Thread(r,"nodes-core-io");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardPolicy());
    Connection database;long lastCopy;final Set<String> warnings=ConcurrentHashMap.newKeySet();
    Integrations(CoreFeatures c){this.c=c;
        for(String type:List.of("WarAttackFinishEvent","WarAttackCancelEvent"))try{
            @SuppressWarnings("unchecked")Class<? extends org.bukkit.event.Event> event=(Class<? extends org.bukkit.event.Event>)Class.forName("phonon.nodes.event."+type,true,c.api.loader);
            Bukkit.getPluginManager().registerEvent(event,new org.bukkit.event.Listener(){},org.bukkit.event.EventPriority.MONITOR,(listener,e)->warEvent(type.contains("Finish")?"finish":"defend",e),c.plugin,true);
        }catch(Exception e){throw new IllegalStateException("Native war relay event is missing",e);}
    }
    void warn(String key,String message){if(warnings.add(key))c.plugin.getLogger().warning(message);}
    String placeholders(Player player,String text){
        if(!c.bool("placeholderapiEnabled",true)||Bukkit.getPluginManager().getPlugin("PlaceholderAPI")==null)return text;
        try{Class<?> api=Class.forName("me.clip.placeholderapi.PlaceholderAPI",true,c.api.loader);return String.valueOf(api.getMethod("setPlaceholders",Player.class,String.class).invoke(null,player,text));}catch(Exception e){warn("placeholder","PlaceholderAPI unavailable: "+e.getClass().getSimpleName());return text;}
    }
    void log(String action,UUID actor,String detail){
        if(!c.bool("enableSQL",false))return;worker.submit(()->{try{
            if(database==null||database.isClosed()){
                Class.forName("com.mysql.cj.jdbc.Driver");String host=c.config.getString("sql.ip","localhost"),name=c.config.getString("sql.database","nodes");int port=c.config.getInt("sql.port",3306);
                if(!host.matches("[A-Za-z0-9.:-]+")||!name.matches("[A-Za-z0-9_-]+")||port<1||port>65535)throw new SQLException("Invalid SQL host, port or database.");
                Properties p=new Properties();p.setProperty("user",c.config.getString("sql.username","root"));p.setProperty("password",c.config.getString("sql.password",""));p.setProperty("connectTimeout","3000");p.setProperty("socketTimeout","5000");
                database=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/"+name,p);try(var s=database.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS nodes_core_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,created BIGINT NOT NULL,action VARCHAR(64),actor VARCHAR(36),detail TEXT)");}
            }
            try(var s=database.prepareStatement("INSERT INTO nodes_core_log(created,action,actor,detail) VALUES(?,?,?,?)")){s.setLong(1,System.currentTimeMillis());s.setString(2,action);s.setString(3,actor==null?null:actor.toString());s.setString(4,detail);s.executeUpdate();}
        }catch(Exception e){warn("sql","SQL logging unavailable; gameplay continues. Check database access and credentials.");}});
    }
    void discord(String channelKey,String prefixKey,String text){
        if(!c.bool("activateDiscord",false))return;var plugin=Bukkit.getPluginManager().getPlugin("DiscordSRV");if(plugin==null){warn("discord","Discord relays are configured but DiscordSRV is not installed; relays are inactive.");return;}
        String channel=c.config.getString(channelKey,""),prefix=c.config.getString(prefixKey,"");worker.submit(()->{try{
            Class<?> main=Class.forName("github.scarsz.discordsrv.DiscordSRV",true,plugin.getClass().getClassLoader());Object discord=main.getMethod("getPlugin").invoke(null),guild=Native.call(discord,"getMainGuild");
            @SuppressWarnings("unchecked") List<Object> matches=(List<Object>)Native.call(guild,"getTextChannelsByName",channel,true);
            if(matches.size()!=1){warn("channel-"+channel,"Discord channel must resolve uniquely: "+channel);return;}
            Object action=Native.call(matches.getFirst(),"sendMessage",prefix+" "+ChatColor.stripColor(text));Native.call(action,"queue");
        }catch(Exception e){warn("discord-api","Discord relay API failed; no message was submitted.");}});
    }
    void globalChat(Player player,String message){if(c.bool("relayGlobalChat",false))discord("globalChatChannelName","globalChatMessagePrefix",player.getName()+": "+message);}
    void warEvent(String type,Object event){
        try{
            Player player=Bukkit.getPlayer((UUID)Native.call(event,"getAttacker"));var block=(org.bukkit.block.Block)Native.call(event,"getBlock");Object town=Native.call(event,"getAttackingTown"),territory=Native.call(event,"getTerritory"),owner=Native.call(territory,"getTown"),occupier=Native.call(territory,"getOccupier");
            boolean liberate=owner==town&&occupier!=null;String templateKey=type.equals("attack")?(liberate?"messageFlagWarAttackLiberate":"messageFlagWarAttack"):type.equals("defend")?"messageFlagWarDefend":liberate?"messageFlagWarSucceedLiberate":"messageFlagWarSucceedCapture";
            Map<String,String> fields=new HashMap<>();fields.put("player",type.equals("defend")?"unknown defender":player==null?"Unknown":player.getName());fields.put("x",""+block.getX());fields.put("y",""+block.getY());fields.put("z",""+block.getZ());fields.put("townattacked",Native.name(owner));fields.put("town",Native.name(owner));fields.put("attacker",Native.name(town));fields.put("occupier",Native.name(occupier));fields.put("id",String.valueOf(Native.call(territory,"getId")));
            String msg=format(c.config.getString(templateKey,""),fields);log("flag-"+type,player==null?null:player.getUniqueId(),msg);if(c.bool("relayFlagWar",false))discord("flagWarChannelName","flagWarMessagePrefix",msg);
            if(type.equals("finish")){
                Object coord=Native.call(territory,"getCore");if(((Number)Native.call(coord,"getX")).intValue()==block.getX()>>4&&((Number)Native.call(coord,"getZ")).intValue()==block.getZ()>>4){
                    String full=format(c.config.getString(liberate?"messageFlagWarLiberateTerritory":"messageFlagWarCaptureTerritory",""),fields);log("territory-finish",null,full);if(c.bool("relayFlagWar",false))discord("flagWarChannelName","flagWarMessagePrefix",full);
                }
            }
        }catch(Exception e){warn("war-event","A native war event could not be formatted; no fabricated player identity was used.");}
    }
    static String format(String template,Map<String,String> fields){String out=template;for(var e:fields.entrySet())out=out.replace("%"+e.getKey()+"%",e.getValue());return out;}
    void tick(){
        long now=System.currentTimeMillis();if(now-lastCopy<30000)return;lastCopy=now;
        if(!c.bool("dynmapCopyTowns",true))return;
        Path folder=c.plugin.getDataFolder().toPath();String[][] files={{"world.json","dynmapworld"},{"towns.json","dynmaptowns"},{"nameoverrides.json","dynmapNameoverrides"},{"config.json","dynmapnodesconfig"}};
        worker.submit(()->{for(String[] file:files){String path=c.config.getString(file[1]);if(path==null||path.isBlank())continue;String directory=c.config.getString("dynmapdir","plugins/dynmap/web/nodes");if(!directory.equals("plugins/dynmap/web/nodes")&&path.equals("plugins/dynmap/web/nodes/"+file[0]))path=Path.of(directory).resolve(file[0]).toString();Path source=folder.resolve(file[0]),dest=Path.of(path);try{if(!Files.isRegularFile(source)||source.toAbsolutePath().equals(dest.toAbsolutePath()))continue;Files.createDirectories(dest.toAbsolutePath().getParent());Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){warn("dynmap-"+file[0],"Configured Dynmap copy failed for "+file[0]);}}});
    }
    void waypoint(Player player){
        if(!c.bool("enableWaypoints",false))return;Object town=c.api.town(player.getUniqueId());if(town==null)return;Location home=Native.spawn(town);if(!player.getWorld().equals(home.getWorld()))return;player.setCompassTarget(home);
        c.say(player,"Home waypoint: "+Native.name(town)+" ("+home.getBlockX()+", "+home.getBlockY()+", "+home.getBlockZ()+"). Compass updated.");
        if(Bukkit.getPluginManager().getPlugin("JMWaypointManager")!=null)warn("jm-api","JMWaypointManager detected. Its version-specific wire API is not inferable from config; native compass fallback is active.");
    }
    @Override public void close(){worker.shutdown();try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))worker.shutdownNow();if(database!=null)database.close();}catch(Exception ignored){Thread.currentThread().interrupt();}}
}
