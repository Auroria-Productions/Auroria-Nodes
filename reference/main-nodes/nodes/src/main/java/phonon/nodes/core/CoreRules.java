package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.*;
import java.util.*;

final class CoreRules implements Listener {
    private int seconds;
    final CoreFeatures c;final Map<UUID,Object> beforeTown=new HashMap<>();final Map<UUID,UUID> beforeNation=new HashMap<>();final Map<UUID,Integer> beforeClaims=new HashMap<>();
    final Map<UUID,String> beforeName=new HashMap<>();final Map<UUID,Long> fees=new HashMap<>();final Map<UUID,Object> toggles=new HashMap<>();
    record DisplayFlag(Object attack,BlockDisplay display,Interaction interaction){}
    final Map<UUID,DisplayFlag> displayFlags=new HashMap<>();
    boolean flagged(Location location){try{Object war=Native.call(c.api.nodes,"getWar$nodes");Class<?> coord=Class.forName("phonon.nodes.objects.Coord",true,c.api.loader);Object key=coord.getConstructor(int.class,int.class).newInstance(location.getBlockX()>>4,location.getBlockZ()>>4);return ((Map<?,?>)Native.call(war,"getChunkToAttacker$nodes")).containsKey(key);}catch(Exception e){return false;}}
    void displayTick(){
        if(!c.bool("displayEntityBeacon",false)){displayFlags.values().forEach(f->{f.display.remove();f.interaction.remove();});displayFlags.clear();return;}
        Object war=Native.call(c.api.nodes,"getWar$nodes");Collection<?> active=((Map<?,?>)Native.call(war,"getChunkToAttacker$nodes")).values();
        displayFlags.entrySet().removeIf(e->{if(active.contains(e.getValue().attack))return false;e.getValue().display.remove();e.getValue().interaction.remove();return true;});
        for(Object attack:active){boolean exists=displayFlags.values().stream().anyMatch(f->f.attack==attack);if(exists)continue;Block flag=(Block)Native.call(attack,"getFlagBlock");Location pos=flag.getLocation();
            BlockDisplay display=flag.getWorld().spawn(pos,BlockDisplay.class);display.setBlock(flag.getBlockData());display.setPersistent(false);display.setInvulnerable(true);Interaction interaction=flag.getWorld().spawn(pos.clone().add(.5,0,.5),Interaction.class);interaction.setInteractionWidth(1.2f);interaction.setInteractionHeight(1.6f);interaction.setPersistent(false);interaction.setResponsive(true);displayFlags.put(interaction.getUniqueId(),new DisplayFlag(attack,display,interaction));
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void defendFlag(PlayerInteractEntityEvent e){var flag=displayFlags.get(e.getRightClicked().getUniqueId());if(flag==null)return;e.setCancelled(true);Object defender=c.api.town(e.getPlayer().getUniqueId()),attacker=Native.call(flag.attack,"getTown");if(c.api.enemies(defender,attacker)){Native.call(flag.attack,"cancel");c.say(e.getPlayer(),"Enemy display flag defended.");}}
    @EventHandler(priority=EventPriority.LOWEST)public void unmineableFlag(BlockBreakEvent e){if(!c.bool("displayEntityBeacon",false))return;for(var flag:displayFlags.values())if(e.getBlock().equals(Native.call(flag.attack,"getFlagBlock"))||e.getBlock().equals(Native.call(flag.attack,"getFlagBase"))||e.getBlock().equals(Native.call(flag.attack,"getFlagTorch"))){e.setCancelled(true);c.say(e.getPlayer(),"Right-click the display flag to defend it.");return;}}
    void close(){displayFlags.values().forEach(f->{f.display.remove();f.interaction.remove();});displayFlags.clear();}
    CoreRules(CoreFeatures c){this.c=c;
        try{@SuppressWarnings("unchecked")Class<? extends Event> type=(Class<? extends Event>)Class.forName("phonon.nodes.event.WarAttackStartEvent",true,c.api.loader);Bukkit.getPluginManager().registerEvent(type,this,EventPriority.HIGHEST,(listener,event)->attack(event),c.plugin,true);}catch(Exception e){throw new IllegalStateException("Native war event missing",e);}
    }
    boolean world(Location location){return location.getWorld().getName().equals(c.config.getString("selectedWorld","world"));}
    boolean globalWar(){return Boolean.TRUE.equals(Native.call(Native.call(c.api.nodes,"getWar$nodes"),"getEnabled$nodes"));}
    boolean inside(Location l){return l.getX()>=c.number("bottomLeftX",-30000000)&&l.getX()<=c.number("topRightX",30000000)&&l.getZ()>=c.number("bottomLeftZ",-30000000)&&l.getZ()<=c.number("topRightZ",30000000);}
    boolean region(Player p,Location location){if(!c.bool("enableRegionLimits",false))return true;Object n=c.api.nation(p.getUniqueId());if(n==null)return true;var bounds=c.data.yaml.getIntegerList("regions."+Native.id(n));return bounds.size()!=4||location.getX()>=bounds.get(0)&&location.getZ()>=bounds.get(1)&&location.getX()<=bounds.get(2)&&location.getZ()<=bounds.get(3);}
    void diplomaticDistance(Object own,Object other,String toggle){
        if(!c.bool(toggle,false))return;
        Location a=Native.spawn(own),b=Native.spawn(other);if(!a.getWorld().equals(b.getWorld())||closestDistance(own,other)>c.number("maxDiplomaticDistance",96))throw new IllegalArgumentException("Diplomatic target exceeds configured distance.");
    }
    double closestDistance(Object a,Object b){
        List<Object> aa=new ArrayList<>(),bb=new ArrayList<>();Object an=Native.call(a,"getNation"),bn=Native.call(b,"getNation");aa.addAll(an==null?List.of(a):Native.collection(an,"getTowns"));bb.addAll(bn==null?List.of(b):Native.collection(bn,"getTowns"));double best=Double.POSITIVE_INFINITY;
        for(Object x:aa)for(Object y:bb){Location p=Native.spawn(x),q=Native.spawn(y);if(p.getWorld().equals(q.getWorld()))best=Math.min(best,Math.hypot(p.getX()-q.getX(),p.getZ()-q.getZ())/16);}return best;
    }
    boolean command(org.bukkit.command.CommandSender sender,String root,String[] args){
        if(!(sender instanceof Player p)||args.length==0)return true;String sub=args[0].toLowerCase(Locale.ROOT);Object town=c.api.town(p.getUniqueId()),nation=c.api.nation(p.getUniqueId());
        beforeTown.put(p.getUniqueId(),town);beforeNation.put(p.getUniqueId(),Native.id(nation));beforeName.put(p.getUniqueId(),town==null?"":Native.name(town));beforeClaims.put(p.getUniqueId(),town==null?0:Native.collection(town,"getTerritories").size());fees.remove(p.getUniqueId());
        if(root.equals("town")&&Set.of("create","claim","sphere").contains(sub)){
            recalculateClaims();
        displayTick();
            if(!world(p.getLocation())||!inside(p.getLocation())||!region(p,p.getLocation()))throw new IllegalArgumentException("Town action is outside the configured world/map/nation region.");
            if(globalWar()&&!c.bool(sub.equals("claim")?"canClaimDuringWar":sub.equals("sphere")?"canSphereDuringWar":"canCreateTownDuringWar",true))throw new IllegalArgumentException("This town action is disabled during war.");
            if(sub.equals("create")&&c.bool("minimumDistanceForNewTown",false))for(Object other:c.api.towns().values()){Location home=Native.spawn(other);if(home.getWorld().equals(p.getWorld())&&home.distance(p.getLocation())/16<c.number("minimumDistanceForNewTownVal",32))throw new IllegalArgumentException("Too close to an existing town.");}
            if(town!=null&&Set.of("claim","sphere").contains(sub)){
                if(c.number("maxClaimsPerTown",0)>0&&Native.collection(town,"getTerritories").size()>=c.number("maxClaimsPerTown",0))throw new IllegalArgumentException("Town territory limit reached.");
                Object target=c.api.territory(p.getLocation());if(target==null)throw new IllegalArgumentException("No territory here.");
                Object owner=Native.call(target,"getTown");if(c.bool("disableClaimingCapital",false)&&owner!=null&&Objects.equals(Native.call(target,"getId"),Native.call(owner,"getHome")))throw new IllegalArgumentException("Capital territories cannot be claimed.");
            }
            double price=sub.equals("create")?c.number("townCreationCost",0):c.number("territoryClaimCost",0)*((Number)Native.call(c.api.territory(p.getLocation()),"getCost")).doubleValue();
            if(c.bool("enableEconomy",false)&&price>0){long fee=(long)Math.ceil(price*100);Object provider=c.economy.provider();if(!(Boolean)Native.call(provider,"has",(OfflinePlayer)p,fee/100.0))throw new IllegalArgumentException("Insufficient funds for this action.");fees.put(p.getUniqueId(),fee);}
        }
        if(root.equals("town")&&sub.equals("rename")&&town!=null&&System.currentTimeMillis()-c.data.yaml.getLong("rename."+Native.id(town))<c.duration("townRenameCooldown",10000))throw new IllegalArgumentException("Town rename cooldown is active.");
        if(root.equals("nation")&&Set.of("capital","setcapital").contains(sub)){
            if(globalWar()&&!c.bool("canSetCapitalDuringWar",true)||nation!=null&&!Native.collection(nation,"getEnemies").isEmpty()&&!c.bool("canSetCapitalIfEnemies",true))throw new IllegalArgumentException("Capital changes are currently blocked.");
        }
        if(Set.of("war","ally","unally").contains(root)&&nation!=null&&!c.politics.autonomous(Native.id(nation)))throw new IllegalArgumentException("Vassals cannot independently declare war.");
        if(Set.of("war","ally").contains(root)){
            Object target;try{target=c.war.target(args[0]);}catch(IllegalArgumentException e){return true;}
            if(town!=null)diplomaticDistance(town,target,root.equals("war")?"maxDistanceWarDeclaration":"maxDistanceAlliance");
        }
        if(root.equals("peace")&&!c.bool("peaceNonWarTargets",false)&&!c.bool("oldWarSystem",true)&&town!=null){Object target;try{target=c.war.target(args[0]);}catch(Exception ignored){return true;}if(!c.api.enemies(town,target))throw new IllegalArgumentException("Peace targets must be opponents in an existing war.");}
        if(root.equals("town")&&sub.equals("pvp")){
            if(!c.bool("togglePVPMechanic",true)||!c.bool("canToggleTownPVP",true))throw new IllegalArgumentException("Town PvP toggling is disabled.");if(town==null||!c.api.leadership(p,town))throw new IllegalArgumentException("Town leadership required.");
            String key="pvp."+Native.id(town);long last=c.data.yaml.getLong(key+".changed");if(System.currentTimeMillis()-last<c.duration("pvpToggleCooldown",259200000))throw new IllegalArgumentException("PvP toggle cooldown is active.");c.data.yaml.set(key+".enabled",!c.data.yaml.getBoolean(key+".enabled",true));c.data.yaml.set(key+".changed",System.currentTimeMillis());c.data.save();c.say(p,"Town PvP is now "+c.data.yaml.getBoolean(key+".enabled"));return false;
        }
        if(root.equals("town")&&sub.equals("sphere")){
            if(town==null||!c.api.leadership(p,town))throw new IllegalArgumentException("Town leadership required.");
            Object territory=c.api.territory(p.getLocation());Object result=Native.call(c.api.nodes,"claimTerritory",town,territory);if(Native.call(territory,"getTown")!=town)throw new IllegalArgumentException("Native claim rules rejected the sphere.");
            c.data.yaml.set("spheres."+Native.call(territory,"getId"),Native.id(town).toString());c.api.save();c.data.save();c.say(p,"Territory claimed as a sphere with its configured claim cost/capture timer.");return false;
        }
        return true;
    }
    void afterCommand(org.bukkit.command.CommandSender sender,String root,String[] args){
        if(!(sender instanceof Player p))return;Object before=beforeTown.remove(p.getUniqueId()),after=c.api.town(p.getUniqueId());UUID nation=Native.id(c.api.nation(p.getUniqueId())),prior=beforeNation.remove(p.getUniqueId());
        if(before!=null&&after==null&&prior!=null){String path="nationLeave."+p.getUniqueId();c.data.yaml.set(path+".nation",prior.toString());c.data.yaml.set(path+".time",System.currentTimeMillis());}
        if(after!=null&&!beforeName.getOrDefault(p.getUniqueId(),"").isEmpty()&&!Native.name(after).equals(beforeName.get(p.getUniqueId())))c.data.yaml.set("rename."+Native.id(after),System.currentTimeMillis());
        Long price=fees.remove(p.getUniqueId());if(price!=null&&(before==null&&after!=null||after!=null&&Native.collection(after,"getTerritories").size()>beforeClaims.getOrDefault(p.getUniqueId(),0)))c.economy.transfer(p,"fee",price,true,null);
        c.data.save();
    }
    boolean canJoin(Object resident,Object town){
        UUID player=Native.id(resident);Object nation=Native.call(town,"getNation");if(nation==null)return true;
        String path="nationLeave."+player;String previous=c.data.yaml.getString(path+".nation");if(previous==null||previous.equals(Native.id(nation).toString()))return true;
        boolean ok=System.currentTimeMillis()-c.data.yaml.getLong(path+".time")>=c.duration("nationJoinCooldown",172800000);if(!ok){Player online=Bukkit.getPlayer(player);if(online!=null)c.say(online,"Cross-nation rejoin cooldown is active.");}return ok;
    }
    void attack(Event event){
        try{
            Object town=Native.call(event,"getAttackingTown"),territory=Native.call(event,"getTerritory"),owner=Native.call(territory,"getTown");Player p=Bukkit.getPlayer((UUID)Native.call(event,"getAttacker"));Block block=(Block)Native.call(event,"getBlock");String reason=null;
            if(p==null||p.getStatistic(Statistic.PLAY_ONE_MINUTE)/72000.0<c.number("hoursRequiredToJoinWars",0))reason="More playtime is required to flag.";
            else if(c.war.warming(town,owner))reason="The war is still warming up.";
            else if(owner==null&&!c.bool("allowUnownedAttack",true))reason="Wilderness flagging is disabled.";
            else if(!region(p,block.getLocation()))reason="Attack is outside your nation's region.";
            else if(c.bool("staticFlagblock",false)&&!block.getType().name().equalsIgnoreCase(c.config.getString("staticFlagblockMaterial","deepslate")))reason="Use the configured static flag block material.";
            if(reason!=null){((Cancellable)event).setCancelled(true);if(p!=null)c.say(p,reason);}else c.integrations.warEvent("attack",event);
        }catch(Exception ex){((Cancellable)event).setCancelled(true);c.plugin.getLogger().log(java.util.logging.Level.WARNING,"Core attack policy failed closed",ex);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void damage(EntityDamageByEntityEvent event){
        Player attacker=event.getDamager() instanceof Player p?p:event.getDamager() instanceof Projectile projectile&&projectile.getShooter() instanceof Player p?p:null;if(attacker==null||!world(event.getEntity().getLocation()))return;
        if(event.getEntity() instanceof Player victim){
            Object a=c.api.town(attacker.getUniqueId()),b=c.api.town(victim.getUniqueId());
            if(c.bool("togglePVPMechanic",true)&&b!=null&&!c.data.yaml.getBoolean("pvp."+Native.id(b)+".enabled",true))event.setCancelled(true);
            if(c.bool("noFriendlyFire",false)&&a!=null&&b!=null&&(a==b||Objects.equals(Native.call(a,"getNation"),Native.call(b,"getNation"))&&Native.call(a,"getNation")!=null||Native.collection(a,"getAllies").contains(b)))event.setCancelled(true);
            if(c.bool("limitPVPByRegion",false)&&(!region(attacker,victim.getLocation())||!region(victim,attacker.getLocation())))event.setCancelled(true);
            if(c.bool("warSickness",false)&&globalWar()&&!c.api.enemies(a,b))event.setCancelled(true);
        }else{
            Object owner=c.api.owner(event.getEntity().getLocation());if(owner!=null){String type=event.getEntity().getType().name();boolean protectedMob=c.bool("mobProtection",false)&&c.config.getStringList("protectedMobs").contains(type)||!c.data.yaml.getBoolean("pvp."+Native.id(owner)+".enabled",true)&&c.config.getStringList("protectedMobsIfPVPOff").contains(type);if(protectedMob&&owner!=c.api.town(attacker.getUniqueId()))event.setCancelled(true);}
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void entityExplosion(EntityExplodeEvent e){if(!c.bool("explodeChests",true))e.blockList().removeIf(b->b.getState() instanceof Container);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void blockExplosion(BlockExplodeEvent e){if(!c.bool("explodeChests",true))e.blockList().removeIf(b->b.getState() instanceof Container);}
    @EventHandler public void join(PlayerJoinEvent e){
        Player p=e.getPlayer();Bukkit.getScheduler().runTask(c.plugin,()->{
            c.integrations.waypoint(p);
            if(c.bool("warPlayerWhiteList",false)&&globalWar()&&!p.hasPermission("nodes.admin")){Object town=c.api.town(p.getUniqueId());boolean eligible=town!=null&&!Native.collection(town,"getEnemies").isEmpty()&&p.getStatistic(Statistic.PLAY_ONE_MINUTE)/72000.0>=c.number("hoursRequiredToJoinWars",0);if(!eligible)Bukkit.dispatchCommand(Bukkit.getConsoleSender(),c.config.getString("whitelistDenyCommand","kick %player%").replace("%player%",p.getName()));}
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void customOre(BlockBreakEvent event){
        if(!c.bool("hiddenOreMechanic",true)||!world(event.getBlock().getLocation())||event.getPlayer().getGameMode()!=GameMode.SURVIVAL||!CoreHooks.hiddenOre(event.getPlayer(),event.getBlock()))return;
        Object territory=c.api.territory(event.getBlock().getLocation());if(territory==null&&!c.bool("allowOreInWilderness",false))return;
        var ores=c.config.getConfigurationSection("globalResources.ore");if(ores==null)return;for(String name:ores.getKeys(false)){var spec=ores.getConfigurationSection(name);if(spec==null||!spec.contains("customname")&&!spec.contains("lore"))continue;
            if(Math.random()>=spec.getDouble("chance"))continue;Material mat=Material.matchMaterial(name);int min=spec.getInt("min",1),max=spec.getInt("max",min);if(mat==null||min<=0||max<min||max>2304)continue;
            ItemStack item=new ItemStack(mat,java.util.concurrent.ThreadLocalRandom.current().nextInt(min,max+1));var meta=item.getItemMeta();if(spec.contains("customname"))meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',spec.getString("customname")));meta.setLore(spec.getStringList("lore"));item.setItemMeta(meta);event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(),item);
        }
    }
    void recalculateClaims(){
        if(!c.bool("enableCoreMechanics",false)&&!c.bool("enableClaimedMechanics",false)&&!c.bool("enableSphereMechanics",false))return;
        for(Object town:c.api.towns().values()){
            long total=0;Object home=Native.call(town,"getHome");
            for(Object boxed:Native.collection(town,"getTerritories")){
                Object id=CoreWar.unbox(boxed),territory=Native.call(c.api.nodes,"getTerritoryFromId",id);if(territory==null)continue;
                double multiplier=Objects.equals(id,home)?(c.bool("enableCoreMechanics",false)?c.number("coreNodeCostIncrease",1):1):c.data.yaml.contains("spheres."+id)?(c.bool("enableSphereMechanics",false)?c.number("sphereNodeCostIncrease",1):1):(c.bool("enableClaimedMechanics",false)?c.number("claimedNodeCostIncrease",1):1);
                if(multiplier<1)throw new IllegalArgumentException("Claim cost multipliers must be at least 1.");total+=Math.ceil(((Number)Native.call(territory,"getCost")).doubleValue()*multiplier);
            }
            Native.call(town,"setClaimsUsed",(int)Math.min(Integer.MAX_VALUE,total));
        }
    }
    void tick(){
        seconds++;if(seconds%60==0){long cutoff=System.currentTimeMillis()-(long)(c.number("maxDaysForInactivity",30)*86400000);for(org.bukkit.OfflinePlayer player:Bukkit.getOfflinePlayers())c.data.yaml.set("inactive."+player.getUniqueId(),!player.isOnline()&&player.getLastPlayed()>0&&player.getLastPlayed()<cutoff);}
        recalculateClaims();
        displayTick();
        if(c.bool("warSickness",false)&&globalWar())for(Player p:Bukkit.getOnlinePlayers()){Object town=c.api.town(p.getUniqueId()),owner=c.api.owner(p.getLocation());if(owner!=null&&owner!=town&&!c.api.enemies(town,owner)){p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,60,0));p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,0));}}
    }
}
