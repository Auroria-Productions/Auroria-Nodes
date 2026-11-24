package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import net.md_5.bungee.api.chat.*;
import java.util.*;

final class Presentation implements Listener,AutoCloseable {
    final CoreFeatures c;final Map<UUID,TextDisplay> labels=new HashMap<>();int ticks;
    Presentation(CoreFeatures c){this.c=c;Bukkit.getScheduler().runTaskTimer(c.plugin,this::fastTick,2,Math.max(2,(long)c.number("nametagUpdateInterval",2)));}
    boolean customChat(){return c.config.contains("chatPrefixMode")||c.config.contains("chatPrefixSuffixShow")||c.config.contains("localChatDistance")||c.config.contains("greenText");}
    String placeholders(Player p,String raw){
        Object r=c.api.resident(p.getUniqueId()),town=c.api.town(p.getUniqueId()),nation=c.api.nation(p.getUniqueId());
        String out=raw.replace("%player%",p.getName()).replace("%online%",Integer.toString(Bukkit.getOnlinePlayers().size())).replace("%ping%",Integer.toString(p.getPing())).replace("%tps%",tps())
            .replace("%int_nodes_town%",Native.name(town)).replace("%int_nodes_nation%",Native.name(nation)).replace("%int_nodes_prefix%",r==null?"":String.valueOf(Native.call(r,"getPrefix"))).replace("%int_nodes_suffix%",r==null?"":String.valueOf(Native.call(r,"getSuffix")));
        out=out.replace("{town}",Native.name(town)).replace("{nation}",Native.name(nation)).replace("{player}",p.getName()).replace("{online}",Integer.toString(Bukkit.getOnlinePlayers().size()));
        return c.integrations.placeholders(p,out);
    }
    String tps(){try{Object values=Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());return String.format(java.util.Locale.ROOT,"%.1f",((double[])values)[0]);}catch(Exception e){return "N/A";}}
    static String legacy(String input){
        try{Class<?> mini=Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");Object mm=mini.getMethod("miniMessage").invoke(null),component=Native.call(mm,"deserialize",input);Class<?> serial=Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");return String.valueOf(Native.call(serial.getMethod("legacySection").invoke(null),"serialize",component));}
        catch(Exception ignored){return ChatColor.translateAlternateColorCodes('&',input.replaceAll("</?[^>]+>",""));}
    }
    TextComponent component(String text,String hover,Player p){TextComponent out=new TextComponent(TextComponent.fromLegacyText(legacy(placeholders(p,text))));if(!hover.isEmpty())out.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,TextComponent.fromLegacyText(legacy(placeholders(p,hover)).replace("\\n","\n"))));return out;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void chat(AsyncPlayerChatEvent event){
        if(!customChat())return;event.setCancelled(true);String message=event.getMessage();Player sender=event.getPlayer();Set<UUID> recipients=new HashSet<>();for(Player p:event.getRecipients())recipients.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(c.plugin,()->{
            Object resident=c.api.resident(sender.getUniqueId());if(resident==null)return;Object town=c.api.town(sender.getUniqueId()),nation=c.api.nation(sender.getUniqueId());String mode=String.valueOf(Native.call(resident,"getChatMode"));
            TextComponent line=new TextComponent();String prepend=c.config.getString("chatPrefix","");if(!prepend.isEmpty())line.addExtra(component(prepend,c.config.getString("chatJSONFormattingHoverPrepend",""),sender));
            int prefixMode=c.config.getInt("chatPrefixMode",0);String tag=prefixMode==2?"":prefixMode==1?Native.name(town):nation==null?Native.name(town):Native.name(nation)+":"+Native.name(town);
            if(town!=null&&!tag.isEmpty())line.addExtra(component("["+tag+"] ",c.config.getString("chatJSONFormattingHoverTown",""),sender));
            String prefix=c.bool("chatPrefixSuffixShow",true)?String.valueOf(Native.call(resident,"getPrefix")):"",suffix=c.bool("chatPrefixSuffixShow",true)?String.valueOf(Native.call(resident,"getSuffix")):"";
            line.addExtra(component(prefix+sender.getName()+suffix,c.config.getString("chatJSONFormattingHoverPlayer",""),sender));line.addExtra(new TextComponent(": "+(c.bool("greenText",true)&&message.startsWith(">")?ChatColor.GREEN:ChatColor.WHITE)+message));
            for(UUID id:recipients){Player viewer=Bukkit.getPlayer(id);if(viewer==null)continue;Object vt=c.api.town(id),vn=c.api.nation(id);boolean visible=switch(mode){case "TOWN"->town!=null&&town==vt;case "NATION"->nation!=null&&nation==vn;case "ALLY"->town!=null&&(vt==town||Native.collection(town,"getAllies").contains(vt));case "LOCAL"->viewer.getWorld().equals(sender.getWorld())&&viewer.getLocation().distanceSquared(sender.getLocation())<=Math.pow(c.number("localChatDistance",100),2);default->true;};if(visible)viewer.spigot().sendMessage(line);}
            if(mode.equals("GLOBAL"))c.integrations.globalChat(sender,message);
        });
    }
    void fastTick(){
        if(!c.bool("useUnlimitedNametags",false)){if(!labels.isEmpty()){labels.values().forEach(Entity::remove);labels.clear();}return;}
        Set<UUID> online=new HashSet<>();for(Player p:Bukkit.getOnlinePlayers()){
            online.add(p.getUniqueId());if(p.isInvisible()||p.isSneaking()||p.getGameMode()==GameMode.SPECTATOR){TextDisplay old=labels.remove(p.getUniqueId());if(old!=null)old.remove();continue;}
            Object town=c.api.town(p.getUniqueId()),nation=c.api.nation(p.getUniqueId());String text=nation==null?Native.name(town):c.bool("tabList.showTownBeforeNation",true)?Native.name(town)+" | "+Native.name(nation):Native.name(nation)+" | "+Native.name(town);
            TextDisplay label=labels.get(p.getUniqueId());if(label==null||!label.isValid()){label=p.getWorld().spawn(p.getLocation().add(0,2.4,0),TextDisplay.class);label.setPersistent(false);label.setInvulnerable(true);label.setBillboard(Display.Billboard.CENTER);label.setSeeThrough(true);label.setDefaultBackground(false);labels.put(p.getUniqueId(),label);}
            label.setText(legacy("&7"+text));label.teleport(p.getLocation().add(0,2.4,0));
            double max=c.number("nametagProximityDistance",100);for(Player viewer:Bukkit.getOnlinePlayers())if(viewer.equals(p)||!viewer.canSee(p)||!viewer.getWorld().equals(p.getWorld())||viewer.getLocation().distanceSquared(p.getLocation())>max*max)viewer.hideEntity(c.plugin,label);else viewer.showEntity(c.plugin,label);
        }
        labels.entrySet().removeIf(e->{if(online.contains(e.getKey()))return false;e.getValue().remove();return true;});
    }
    void tick(){
        ticks+=20;int header=Math.max(1,c.config.getInt("tabList.updateIntervals.headerFooter",20));
        if(ticks%header<20&&c.bool("tabList.enabled",true))for(Player p:Bukkit.getOnlinePlayers()){
            String image=c.config.getString("tabList.headerImageChar","");String h=String.join("\n",c.config.getStringList("tabList.header")),f=String.join("\n",c.config.getStringList("tabList.footer"));
            p.setPlayerListHeaderFooter(legacy(placeholders(p,image.isEmpty()?h:image+"\n"+h)),legacy(placeholders(p,f)));
        }
        int minimap=Math.max(1,c.config.getInt("minimapUpdateFreqTicks",20));if(ticks%minimap<20)for(Player p:Bukkit.getOnlinePlayers()){
            Object resident=c.api.resident(p.getUniqueId());if(resident==null)continue;Object map=Native.call(resident,"getMinimap");if(map==null)continue;
            try{Class<?> coord=Class.forName("phonon.nodes.objects.Coord",true,c.api.loader);Object current=coord.getConstructor(int.class,int.class).newInstance(p.getLocation().getBlockX()>>4,p.getLocation().getBlockZ()>>4);Native.call(resident,"updateMinimap",current);sidebar(map);}catch(Exception e){c.plugin.getLogger().fine("Minimap update skipped: "+e.getMessage());}
        }
    }
    void sidebar(Object map){try{
        var pf=map.getClass().getDeclaredField("player");pf.setAccessible(true);Player player=(Player)pf.get(map);var sf=map.getClass().getDeclaredField("size");sf.setAccessible(true);int size=sf.getInt(map);
        if(c.bool("enableSidebarTextOnlyOnMaxSize",true)&&size<c.config.getInt("minimap.maxSize",6))return;
        var api=Class.forName("phonon.nms.NmsKt",true,c.api.loader).getMethod("sendScore",Player.class,String.class,String.class,int.class);
        String top=c.config.getString("minimapSidebarTopText",""),bottom=c.config.getString("minimapSidebarBottomText","");if(!top.isEmpty())api.invoke(null,player,"nodes_minimap",legacy(placeholders(player,top)),size+2);if(!bottom.isEmpty())api.invoke(null,player,"nodes_minimap",legacy(placeholders(player,bottom)),-size-1);
    }catch(Exception e){c.plugin.getLogger().fine("Sidebar text could not be rendered: "+e.getMessage());}}
    @Override public void close(){labels.values().forEach(Entity::remove);labels.clear();}
}
