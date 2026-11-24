package phonon.nodes.keywar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import phonon.nodes.Nodes;
import phonon.nodes.objects.Nation;
import phonon.nodes.objects.Resident;
import phonon.nodes.objects.Town;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/** Configurable, staff-supervised key-point warfare. No Nodes territory ownership mutations. */
public final class KeyPointWars implements Listener, CommandExecutor, TabCompleter {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static KeyPointWars instance;
    private final JavaPlugin plugin;
    private final Path stateFile, viewerFile;
    private KeyWarModel.Data data;
    private final int sicknessSeconds;
    private final boolean onExitAbandon;
    private final int maxPoints;
    private final int maxRadius;
    private boolean stopping = false;

    private KeyPointWars(JavaPlugin plugin) {
        this.plugin=plugin;
        FileConfiguration cfg = plugin.getConfig();
        String prefix="war.keyPointWars.";
        sicknessSeconds=Math.clamp(cfg.getInt(prefix+"warSicknessSeconds",600),0,3600);
        onExitAbandon=cfg.getBoolean(prefix+"abandonOnExit",true);
        maxPoints=Math.clamp(cfg.getInt(prefix+"maxPointsPerWar",12),1,64);
        maxRadius=Math.clamp(cfg.getInt(prefix+"maxRadius",256),32,2048);
        // Both locations are fixed under the Nodes data folder. Never read path input from a user command.
        Path root=plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        stateFile=root.resolve("data/war/key-point-war-state.json");
        String viewerState=cfg.getString(prefix+"stateFile", "data/war/key-point-battles.json");
        Path resolved=root.resolve(viewerState).normalize();
        if(!resolved.startsWith(root))throw new IllegalArgumentException("Key-point Wars stateFile must be inside plugin data folder");
        viewerFile=resolved;
        load();
    }
    public static void start(JavaPlugin owner) {
        if(instance!=null) return;
        // Existing installations keep their values; only missing keys are inserted.
        // Do not rewrite the full YAML using Bukkit saveConfig(), which removes comments.
        try {
            Path configPath=owner.getDataFolder().toPath().resolve("config.yml");
            if(Files.notExists(configPath)) owner.saveDefaultConfig();
            if(KeyWarConfigMigration.ensure(configPath)) {
                owner.reloadConfig();
                owner.getLogger().info("Added missing Key-point Wars settings to config.yml; previous file backed up as config.yml.before-keywar");
            }
        } catch (IOException | IllegalStateException ex) {
            owner.getLogger().log(Level.SEVERE, "Unable to add missing Key-point Wars settings to config.yml; check configuration permissions and contents", ex);
            return;
        }
        boolean enabled=owner.getConfig().isSet("war.systems.keyPointWars.enabled")
            ? owner.getConfig().getBoolean("war.systems.keyPointWars.enabled")
            : owner.getConfig().getBoolean("war.keyPointWars.enabled",false);
        if(!owner.getConfig().getBoolean("war.enabled",true))enabled=false;
        if(!enabled) {
            owner.getLogger().info("Key-point Wars disabled (war.keyPointWars.enabled=false).");
            return;
        }
        KeyPointWars manager=new KeyPointWars(owner);
        var command=owner.getCommand("keywar");
        if(command==null) {
            owner.getLogger().severe("Missing keywar entry in plugin.yml; Key-point Wars not enabled");
            return;
        }
        command.setExecutor(manager);
        command.setTabCompleter(manager);
        Bukkit.getPluginManager().registerEvents(manager,owner);
        instance=manager;
        manager.publish();
        owner.getLogger().info("Key-point Wars enabled (staff-led, one-life; no automatic territory capture).");
    }
    public static void stop() {
        if(instance==null) return;
        instance.stopping=true;
        instance.save();
        instance=null;
    }
    private void load() {
        data=new KeyWarModel.Data();
        if(!Files.exists(stateFile)) return;
        try {
            if(Files.size(stateFile)>8_000_000) throw new IOException("state exceeds 8 MB limit");
            var saved=JSON.fromJson(Files.readString(stateFile,StandardCharsets.UTF_8),KeyWarModel.Data.class);
            if(saved==null || saved.schema!=2 || saved.wars==null) throw new IOException("unsupported battle state schema");
            for(var war:saved.wars.values()) {
                if(war==null||war.points==null) throw new IOException("corrupt war record");
                for(var point:war.points.values()) if(point==null||point.members==null||point.ceasefireVotes==null)
                    throw new IOException("corrupt point record");
            }
            data=saved;
        } catch(Exception e) {
            plugin.getLogger().log(Level.SEVERE,"Unable to read Key-point Wars state; NOT overwriting it. Key-point Wars disabled until resolved",e);
            throw new IllegalStateException("Could not load key-point battle state",e);
        }
    }
    /** Atomic saves guarantee death lockouts survive restarts without half-written JSON. */
    private static void atomic(Path target,String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),".keywar-",".tmp");
        try {
            Files.writeString(temp,text,StandardCharsets.UTF_8);
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private void save() {
        try { atomic(stateFile,JSON.toJson(data)); publish(); }
        catch(IOException e) { plugin.getLogger().log(Level.SEVERE,"Key-point Wars state save failed; stop PvP and check disk",e); }
    }
    private void publish() {
        var records=new ArrayList<java.util.Map<String,Object>>();
        for(var war:data.wars.values()) for(var point:war.points.values()) {
            var item=new LinkedHashMap<String,Object>();
            item.put("id",war.id+":"+point.id);
            item.put("name",point.name);
            item.put("type",point.type);
            item.put("attacker",war.attacker);
            item.put("defender",war.defender);
            item.put("status",point.status);
            item.put("winner",point.winner);
            item.put("x",point.x); item.put("z",point.z);
            item.put("startsAt",point.startsAt); item.put("endsAt",point.endsAt);
            item.put("eliminatedCount",point.eliminated());
            item.put("attackerAlive",point.alive(KeyWarModel.ATTACKERS));
            item.put("defenderAlive",point.alive(KeyWarModel.DEFENDERS));
            item.put("keyPointName",point.name);
            records.add(item);
        }
        try {atomic(viewerFile,JSON.toJson(Collections.singletonMap("battles",records)));}
        catch(IOException e) {plugin.getLogger().log(Level.WARNING,"Could not publish key-point map JSON",e);}
    }
    private static String slug(String str) {
        if(!str.matches("[a-zA-Z0-9_-]{1,40}")) throw new IllegalArgumentException("ID must be 1-40 letters, numbers, _ or -");
        return str.toLowerCase(Locale.ROOT);
    }
    private static String name(String input) {
        String safe=input.replaceAll("[\\p{Cntrl}\\u00a7]","").trim();
        if(safe.isEmpty() || safe.length()>48) throw new IllegalArgumentException("Name must be 1-48 characters");
        return safe;
    }
    private Nation findNation(String value) {
        for(var entry:Nodes.INSTANCE.getNations$nodes().values()) if(entry.getName().equalsIgnoreCase(value)) return entry;
        throw new IllegalArgumentException("Unknown nation: " + value);
    }
    private static Resident resident(Player player) {return Nodes.INSTANCE.getResidents$nodes().get(player.getUniqueId());}
    private static String side(Player player,KeyWarModel.War war) {
        var r=resident(player);
        Nation n=r==null?null:r.getNation();
        if(n==null) return "";
        return n.getName().equalsIgnoreCase(war.attacker)?KeyWarModel.ATTACKERS:
            n.getName().equalsIgnoreCase(war.defender)?KeyWarModel.DEFENDERS:"";
    }
    private static boolean staff(CommandSender sender) {return sender.hasPermission("nodes.keywar.staff");}
    private boolean nationLeader(Player player,KeyWarModel.War war) {
        if(staff(player)) return true;
        if(side(player,war).isEmpty()) return false;
        Resident r=resident(player); if(r==null) return false;
        Town t=r.getTown();
        if(t==null) return false;
        return t.getLeader()==r || t.getOfficers().contains(r) || t.getGenerals().contains(r);
    }
    private void requireStaff(CommandSender sender) {
        if(!staff(sender)) throw new IllegalArgumentException("Staff permission required: nodes.keywar.staff");
    }
    private KeyWarModel.War war(String id) {
        var w=data.wars.get(slug(id));
        if(w==null) throw new IllegalArgumentException("Unknown war ID: "+id);
        return w;
    }
    private KeyWarModel.Point point(KeyWarModel.War war,String id) {
        var p=war.points.get(slug(id));
        if(p==null) throw new IllegalArgumentException("Unknown key-point: "+id);
        return p;
    }
    private static void tell(CommandSender s,String msg) {s.sendMessage(net.kyori.adventure.text.Component.text("[KeyWar] "+msg));}
    private static void broadcast(String msg) {Bukkit.getOnlinePlayers().forEach(p -> tell(p,msg)); Bukkit.getLogger().info("[KeyWar] "+msg);}
    private String status(KeyWarModel.War w,KeyWarModel.Point p) {
        return w.id+"/"+p.id+" "+p.name+" ("+p.status+") " + w.attacker+" vs "+w.defender+
            " | alive: "+p.alive(KeyWarModel.ATTACKERS)+" attackers / "+p.alive(KeyWarModel.DEFENDERS)+" defenders"+
            (p.winner.isEmpty()?"":" | winner: "+p.winner);
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if(args.length==0 || args[0].equalsIgnoreCase("help")) {
            tell(sender,"create <id> <attackerNation> <defenderNation> (staff)");
            tell(sender,"point <war> <name> [radius] (nation leader, at your position)");
            tell(sender,"enlist <war> <point> | leave <war> <point> | status [war]");
            tell(sender,"roster <war> <point> <add|remove> <player> [attackers|defenders] (staff)");
            tell(sender,"start <war> <point> | resolve <war> <point> <attackers|defenders|draw> (staff)");
            tell(sender,"ceasefire <war> <point> (both nations must agree)");
            tell(sender,"terms <war> <point> <agreement text...> (staff; never transfers land)");
            return true;
        }
        try {
            String action=args[0].toLowerCase(Locale.ROOT);
            switch(action) {
                case "create" -> {
                    requireStaff(sender);need(args,4,"create <id> <attackerNation> <defenderNation>");
                    String id=slug(args[1]);
                    if(data.wars.containsKey(id)) throw new IllegalArgumentException("War already exists");
                    Nation attack=findNation(args[2]),defend=findNation(args[3]);
                    if(attack==defend) throw new IllegalArgumentException("Choose two different nations");
                    data.wars.put(id,new KeyWarModel.War(id,attack.getName(),defend.getName()));
                    save();tell(sender,"Created "+id+". Nation leaders may now propose key-points.");
                }
                case "point" -> {
                    need(args,3,"point <war> <name> [radius]");
                    if(!(sender instanceof Player player)) throw new IllegalArgumentException("Visit the stronghold in-game to propose its location");
                    var w=war(args[1]);
                    if(!nationLeader(player,w)) throw new IllegalArgumentException("Only a leader/officer of a participating nation can propose points");
                    if(w.points.size()>=maxPoints) throw new IllegalArgumentException("Maximum key-points reached for this war");
                    int radius=args.length>3?Integer.parseInt(args[3]):plugin.getConfig().getInt("war.keyPointWars.defaultRadius",96);
                    if(radius<16||radius>maxRadius) throw new IllegalArgumentException("Radius must be 16 to "+maxRadius);
                    var pos=player.getLocation();
                    String id="p"+(++w.sequence);
                    var point=new KeyWarModel.Point(id,name(args[2]),side(player,w),pos.getWorld().getName(),pos.getX(),pos.getY(),pos.getZ(),radius);
                    w.points.put(id,point);save();
                    broadcast("Point "+w.id+"/"+id+" proposed by "+player.getName()+": "+point.name+" ("+radius+" blocks).");
                }
                case "enlist", "join" -> {
                    need(args,3,"enlist <war> <point>");
                    if(!(sender instanceof Player player)) throw new IllegalArgumentException("Players must enrol themselves");
                    var w=war(args[1]);var p=point(w,args[2]);
                    String side=side(player,w);
                    if(side.isBlank()) throw new IllegalArgumentException("You must belong to one of the two nations");
                    if(enrolledInAnotherLivePoint(player.getUniqueId(),p)) throw new IllegalArgumentException("You are already fighting in another key-point");
                    p.enrol(player.getUniqueId(),player.getName(),side);save();
                    tell(sender,"Enlisted for "+w.id+"/"+p.id+" as "+side+". Only one life once staff starts it.");
                }
                case "leave" -> {
                    need(args,3,"leave <war> <point>");
                    if(!(sender instanceof Player player)) throw new IllegalArgumentException("Player command");
                    var w=war(args[1]);var p=point(w,args[2]);
                    if(KeyWarModel.SETUP.equals(p.status)) {p.remove(player.getUniqueId());save(); tell(sender,"Removed from setup roster.");}
                    else if(eliminate(w,p,player.getUniqueId(),KeyWarModel.ABANDONED)) tell(sender,"You abandoned your one-life participation.");
                    else throw new IllegalArgumentException("You are not an active fighter in that point");
                }
                case "roster" -> {
                    requireStaff(sender);need(args,5,"roster <war> <point> <add|remove> <player> [side]");
                    var w=war(args[1]); var p=point(w,args[2]);
                    String verb=args[3];var offline=resolvePlayer(args[4]);
                    if(verb.equalsIgnoreCase("remove")) {p.remove(offline.getUniqueId());tell(sender,"Removed player from setup roster");}
                    else if(verb.equalsIgnoreCase("add")) {
                        String side;
                        if(args.length>5)side=args[5].toLowerCase(Locale.ROOT);
                        else {
                            Player online=Bukkit.getPlayerExact(args[4]);
                            side=online==null?"":side(online,w);
                        }
                        if(!side.equals(KeyWarModel.ATTACKERS)&&!side.equals(KeyWarModel.DEFENDERS))
                            throw new IllegalArgumentException("Specify side: attackers or defenders");
                        p.enrol(offline.getUniqueId(),offline.getName()==null?args[4]:offline.getName(),side);
                        tell(sender,"Added "+args[4]+" to the "+side+" roster.");
                    } else throw new IllegalArgumentException("Use roster add/remove");
                    save();
                }
                case "start" -> {
                    requireStaff(sender);need(args,3,"start <war> <point>");
                    var w=war(args[1]);var p=point(w,args[2]);
                    if(!KeyWarModel.SETUP.equals(p.status)) throw new IllegalArgumentException("Point is not pending");
                    for(var other:data.wars.values()) for(var otherPoint:other.points.values()) {
                        if(otherPoint==p || !KeyWarModel.LIVE.equals(otherPoint.status))continue;
                        if(otherPoint.world.equals(p.world) && Math.hypot(otherPoint.x-p.x,otherPoint.z-p.z)<otherPoint.radius+p.radius)
                            throw new IllegalArgumentException("Key-point radius overlaps a live battle: "+other.id+"/"+otherPoint.id);
                        for(String id:p.members.keySet()) if(otherPoint.members.containsKey(id) && otherPoint.members.get(id).alive())
                            throw new IllegalArgumentException("A fighter is already in another live point: "+id);
                    }
                    for(var member:p.members.values()) {
                        Player player=Bukkit.getPlayer(UUID.fromString(member.uuid));
                        if(player==null || !p.inArea(player.getWorld().getName(),player.getLocation().getX(),player.getLocation().getZ()))
                            throw new IllegalArgumentException("All fighters must be online inside the point before start: "+member.name);
                        var r=resident(player);
                        if(r==null || r.getNation()==null || !r.getNation().getName().equalsIgnoreCase(member.side.equals(KeyWarModel.ATTACKERS)?w.attacker:w.defender))
                            throw new IllegalArgumentException("Roster nation mismatch: "+member.name);
                    }
                    p.start(System.currentTimeMillis());save();
                    broadcast("Key-point battle LIVE: "+status(w,p)+". One life: death/abandonment removes you.");
                }
                case "resolve", "finish" -> {
                    requireStaff(sender);need(args,4,"resolve <war> <point> <attackers|defenders|draw>");
                    var w=war(args[1]);var p=point(w,args[2]);
                    p.finish(args[3].toLowerCase(Locale.ROOT),System.currentTimeMillis());save();
                    broadcast("Staff resolved "+status(w,p)+". Peace terms are handled manually; no land transfers.");
                }
                case "ceasefire" -> {
                    need(args,3,"ceasefire <war> <point>");
                    if(!(sender instanceof Player player)) throw new IllegalArgumentException("Ceasefire needs both nations' leaders");
                    var w=war(args[1]);var p=point(w,args[2]);
                    if(!nationLeader(player,w)) throw new IllegalArgumentException("Only a participating nation leader/officer may request ceasefire");
                    String nation=side(player,w).equals(KeyWarModel.ATTACKERS)?w.attacker:w.defender;
                    boolean done=p.vote(nation,w.attacker,w.defender,System.currentTimeMillis());save();
                    if(done)broadcast("Both nations agreed to ceasefire at "+w.id+"/"+p.id+". Staff must negotiate peace terms.");
                    else broadcast(nation+" requests ceasefire at "+w.id+"/"+p.id+"; awaiting other nation.");
                }
                case "terms" -> {
                    requireStaff(sender);need(args,4,"terms <war> <point> <peace terms...>");
                    var w=war(args[1]);var p=point(w,args[2]);
                    if(KeyWarModel.LIVE.equals(p.status) || KeyWarModel.SETUP.equals(p.status))
                        throw new IllegalArgumentException("Resolve battle before writing peace terms");
                    String agreement=String.join(" ", Arrays.copyOfRange(args,3,args.length)).replaceAll("[\\p{Cntrl}\\u00a7]", "").trim();
                    if(agreement.length()>1000 || agreement.isEmpty())throw new IllegalArgumentException("Peace agreement must be 1-1000 characters");
                    p.peaceTerms=agreement;
                    save();tell(sender,"Peace agreement recorded (manual only; no automatic puppet or land changes).");
                }
                case "status", "list" -> {
                    if(args.length>1) {
                        var w=war(args[1]);
                        tell(sender,w.id+": "+w.attacker+" vs "+w.defender);
                        for(var p:w.points.values())tell(sender,status(w,p));
                    } else {
                        if(data.wars.isEmpty())tell(sender,"No key-point wars created.");
                        for(var w:data.wars.values())tell(sender,w.id+" ("+w.attacker+" vs "+w.defender+"): "+w.points.size()+" points");
                    }
                }
                default -> tell(sender,"Unknown command. Use /keywar help.");
            }
        } catch(IllegalArgumentException|IllegalStateException ex) {tell(sender,ex.getMessage());}
        catch(Exception ex) {plugin.getLogger().log(Level.SEVERE,"Key-point war command error",ex);tell(sender,"Internal error; see console.");}
        return true;
    }
    private static void need(String[] args,int count,String usage) {
        if(args.length<count)throw new IllegalArgumentException("Usage: /keywar "+usage);
    }
    private boolean enrolledInAnotherLivePoint(UUID uuid,KeyWarModel.Point except) {
        for(var war:data.wars.values())for(var p:war.points.values()) {
            if(p==except||!KeyWarModel.LIVE.equals(p.status))continue;
            var member=p.members.get(uuid.toString());
            if(member!=null && member.alive())return true;
        }
        return false;
    }
    private boolean eliminate(KeyWarModel.War war,KeyWarModel.Point point,UUID uuid,String reason) {
        boolean changed=point.eliminate(uuid,reason,System.currentTimeMillis(),sicknessSeconds);
        if(changed) {
            save();
            broadcast("Fighter "+(point.members.get(uuid.toString()).name)+" "+
                (reason.equals(KeyWarModel.DEAD)?"died":"abandoned")+" at "+war.id+"/"+point.id+
                "; "+point.alive(KeyWarModel.ATTACKERS)+" attackers and "+point.alive(KeyWarModel.DEFENDERS)+" defenders remain.");
            if(KeyWarModel.FINISHED.equals(point.status))
                broadcast("Key-point "+war.id+"/"+point.id+" ended. Winner: "+point.winner+". Staff will handle peace talks.");
        }
        return changed;
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent event) {
        Player player=event.getEntity();Location loc=player.getLocation();
        for(var war:data.wars.values())for(var point:war.points.values()) {
            if(!KeyWarModel.LIVE.equals(point.status) || !point.inArea(loc.getWorld().getName(),loc.getX(),loc.getZ()))continue;
            if(eliminate(war,point,player.getUniqueId(),KeyWarModel.DEAD)) return;
        }
    }
    private void sickness(Player p) {
        long until=0;
        for(var war:data.wars.values())for(var point:war.points.values()) {
            var member=point.members.get(p.getUniqueId().toString());
            if(member!=null)until=Math.max(until,member.sickUntil);
        }
        int ticks=(int)Math.clamp((until-System.currentTimeMillis())/50,0,72000);
        if(ticks<1)return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,ticks,0,false,false,true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,ticks,0,false,false,true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,ticks,0,false,false,true));
        tell(p,"You have war sickness. Your one life in that key-point battle is spent.");
    }
    private boolean removedFromLive(Player p,Location where) {
        for(var war:data.wars.values())for(var point:war.points.values()) {
            if(!KeyWarModel.LIVE.equals(point.status) || !point.inArea(where.getWorld().getName(),where.getX(),where.getZ()))continue;
            var member=point.members.get(p.getUniqueId().toString());
            if(member!=null && !member.alive())return true;
        }
        return false;
    }
    @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent event) {
        Player p=event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(!p.isOnline())return;
            sickness(p);
            if(removedFromLive(p,p.getLocation())) p.teleport(p.getWorld().getSpawnLocation());
        },1L);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        Player p=event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(!p.isOnline())return;
            sickness(p);
            if(removedFromLive(p,p.getLocation()))p.teleport(p.getWorld().getSpawnLocation());
        },2L);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        if(stopping || Bukkit.isStopping())return;
        UUID uuid=event.getPlayer().getUniqueId();
        for(var w:data.wars.values())for(var p:w.points.values()) {
            if(KeyWarModel.LIVE.equals(p.status)) eliminate(w,p,uuid,KeyWarModel.ABANDONED);
        }
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void move(PlayerMoveEvent event) {
        if(event.getTo()==null || (event.getFrom().getBlockX()==event.getTo().getBlockX()
            && event.getFrom().getBlockZ()==event.getTo().getBlockZ() && event.getFrom().getWorld()==event.getTo().getWorld()))return;
        UUID id=event.getPlayer().getUniqueId();
        Location from=event.getFrom(),to=event.getTo();
        // Fighters who already died/abandoned are not allowed back into their live arena.
        for(var w:data.wars.values())for(var p:w.points.values()) {
            if(!KeyWarModel.LIVE.equals(p.status))continue;
            var member=p.members.get(id.toString());
            if(member==null)continue;
            boolean before=p.inArea(from.getWorld().getName(),from.getX(),from.getZ());
            boolean after=p.inArea(to.getWorld().getName(),to.getX(),to.getZ());
            if(!member.alive()) {
                if(after && !before) {event.setTo(from);tell(event.getPlayer(),"You were eliminated and cannot re-enter this key-point battle.");return;}
                continue;
            }
            if(before&&!after&&onExitAbandon) {
                eliminate(w,p,id,KeyWarModel.ABANDONED);
                tell(event.getPlayer(),"Leaving the battlefield counts as abandonment. You cannot rejoin.");
            }
        }
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent event) {
        if(!(event.getEntity() instanceof Player victim))return;
        if(removedFromLive(victim,victim.getLocation()))event.setCancelled(true);
        if(event.getDamager() instanceof Player attacker && removedFromLive(attacker,attacker.getLocation()))event.setCancelled(true);
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] args) {
        if(args.length==1) return words(args[0],List.of("help","create","point","enlist","leave","roster","start","resolve","ceasefire","terms","status"));
        if(args.length==2) return words(args[1],data.wars.keySet());
        if(args.length==3 && !args[0].equalsIgnoreCase("point") && !args[0].equalsIgnoreCase("create")) {
            try{return words(args[2],war(args[1]).points.keySet());}catch(Exception ignored){}
        }
        if(args.length==4 && args[0].equalsIgnoreCase("resolve"))return words(args[3],List.of("attackers","defenders","draw"));
        return Collections.emptyList();
    }
    private static org.bukkit.OfflinePlayer resolvePlayer(String input) {
        Player online=Bukkit.getPlayerExact(input);
        if(online!=null)return online;
        try {return Bukkit.getOfflinePlayer(UUID.fromString(input));}
        catch(IllegalArgumentException ignored) { /* name rather than UUID */ }
        for(var known:Bukkit.getOfflinePlayers()) if(known.getName()!=null && known.getName().equalsIgnoreCase(input))return known;
        throw new IllegalArgumentException("Unknown cached player. Have them join once, or specify their UUID.");
    }
    private static List<String> words(String prefix,Collection<String> choices) {
        String low=prefix.toLowerCase(Locale.ROOT);
        return choices.stream().filter(c->c.toLowerCase(Locale.ROOT).startsWith(low)).sorted().toList();
    }
}
