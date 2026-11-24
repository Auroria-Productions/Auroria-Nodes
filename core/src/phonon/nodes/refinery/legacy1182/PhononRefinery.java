package phonon.nodes.refinery.legacy1182;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import phonon.nodes.Nodes;
import phonon.nodes.event.NodesTerritoriesLoadedEvent;
import phonon.nodes.objects.*;

/**
 * Integrated port of the Phonon Nodes dev/refinery 1.18.2 model.
 * It replaces the Ginjer fixed-interval dropper refinery without changing the legacy save file.
 * All Bukkit/Nodes mutations are performed on the primary server thread.
 */
public final class PhononRefinery implements Listener, TabExecutor {
    private static PhononRefinery active;
    private final JavaPlugin plugin;
    private final Path dataFile;
    private final Path configFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String,Recipe> recipes = new LinkedHashMap<>();
    private final Map<String,Type> types = new LinkedHashMap<>();
    private final Map<String,Type> byResource = new LinkedHashMap<>();
    private final Map<Integer,Unit> units = new LinkedHashMap<>();
    private BukkitTask ticker, saver;
    private long lastTime;
    private int updateTicks=200,saveTicks=6000;
    private boolean loaded;
    private static final Method TERRITORY_ID;
    static {
        try { TERRITORY_ID = Territory.class.getMethod("getId-L9_GXOM"); }
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private static int territoryId(Territory territory){
        try{return (Integer) TERRITORY_ID.invoke(territory);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Nodes territory ID API unavailable",e);}
    }
    private record Recipe(String name, Map<Material,Integer> inputs, Map<Material,Integer> outputs, long millis) {}
    private record Type(String name, String title, String resource, int slots, List<Recipe> recipes, Set<Material> allowed) {}
    private static final class Unit implements InventoryHolder {
        final int id; final Type type; final Inventory inventory; final Map<String,Long> progress=new LinkedHashMap<>();
        Unit(int id,Type type){this.id=id;this.type=type;
            this.inventory=Bukkit.createInventory(this,type.slots,Component.text("Refinery: "+type.title));
            for(Recipe r:type.recipes) progress.put(r.name,0L);
        }
        @Override public Inventory getInventory(){return inventory;}
    }
    private PhononRefinery(JavaPlugin plugin){
        this.plugin=plugin;
        this.dataFile=plugin.getDataFolder().toPath().resolve("refinery-1182-state.json");
        this.configFile=plugin.getDataFolder().toPath().resolve("refinery-1182.yml");
    }
    public static void start(JavaPlugin plugin){
        if(active!=null) return;
        PhononRefinery service = new PhononRefinery(plugin);
        active=service;
        try {
            service.loadConfig();
            var command=plugin.getCommand("refinery");
            if(command!=null){command.setExecutor(service);command.setTabCompleter(service);}
            Bukkit.getPluginManager().registerEvents(service,plugin);
            service.schedule();
            // Nodes source may load territory data after the imported features initialize.
            Bukkit.getScheduler().runTaskLater(plugin,service::reloadWorld,1L);
            plugin.getLogger().info("Phonon 1.18.2 territory recipe refinery active; legacy dropper refinery disabled.");
        }catch(Throwable t){active=null;plugin.getLogger().log(Level.SEVERE,"Could not start Phonon refinery",t);}
    }
    public static void stop(){
        var s=active;active=null;if(s==null)return;
        if(s.ticker!=null)s.ticker.cancel();if(s.saver!=null)s.saver.cancel();
        if(s.loaded)s.save();HandlerList.unregisterAll(s);
    }
    private void schedule(){
        if(ticker!=null)ticker.cancel();if(saver!=null)saver.cancel();
        lastTime=System.currentTimeMillis();
        ticker=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,updateTicks,updateTicks);
        saver=Bukkit.getScheduler().runTaskTimer(plugin,this::save,saveTicks,saveTicks);
    }
    private void loadConfig(){
        try {
            Files.createDirectories(configFile.getParent());
            if(!Files.exists(configFile)){
                try(var in=plugin.getResource("refinery-1182.yml")){
                    if(in==null)throw new IllegalStateException("Missing bundled refinery-1182.yml");
                    Files.copy(in,configFile);
                }
            }
            YamlConfiguration y=YamlConfiguration.loadConfiguration(configFile.toFile());
            updateTicks=Math.max(20,y.getInt("updateTickPeriod",200));
            saveTicks=Math.max(100,y.getInt("saveTickPeriod",6000));
            Map<String,Recipe> nextRecipes=new LinkedHashMap<>();
            ConfigurationSection section=y.getConfigurationSection("recipe");
            if(section!=null)for(String name:section.getKeys(false)){
                ConfigurationSection s=section.getConfigurationSection(name);if(s==null)continue;
                Map<Material,Integer> input=materials(s.getConfigurationSection("inputs"));
                Map<Material,Integer> output=materials(s.getConfigurationSection("outputs"));
                long millis=Math.max(1,s.getLong("time",1800))*1000L;
                if(input.isEmpty()||output.isEmpty()){plugin.getLogger().warning("Skipping invalid refinery recipe "+name);continue;}
                nextRecipes.put(name,new Recipe(name,input,output,millis));
            }
            Map<String,Type> nextTypes=new LinkedHashMap<>(), nextResources=new LinkedHashMap<>();
            ConfigurationSection refs=y.getConfigurationSection("refinery");
            if(refs!=null)for(String name:refs.getKeys(false)){
                ConfigurationSection s=refs.getConfigurationSection(name);if(s==null)continue;
                int size=s.getInt("inventory",9);if(size<9||size>54||size%9!=0){plugin.getLogger().warning("Invalid refinery inventory size: "+name);continue;}
                List<Recipe> list=new ArrayList<>();Set<Material> valid=EnumSet.noneOf(Material.class);
                for(String recipe:s.getStringList("recipes")){
                    Recipe r=nextRecipes.get(recipe);if(r==null){plugin.getLogger().warning("Unknown refinery recipe "+recipe);continue;}
                    list.add(r);valid.addAll(r.inputs.keySet());
                }
                if(list.isEmpty())continue;
                String resource=s.getString("resource",name);
                Type t=new Type(name,s.getString("title",name),resource,size,List.copyOf(list),Set.copyOf(valid));
                nextTypes.put(name,t);nextResources.put(resource,t);
            }
            if(nextTypes.isEmpty())plugin.getLogger().warning("No refinery types configured; check refinery-1182.yml");
            recipes.clear();recipes.putAll(nextRecipes);types.clear();types.putAll(nextTypes);
            byResource.clear();byResource.putAll(nextResources);
        }catch(IOException e){throw new IllegalStateException("Could not load refinery-1182.yml",e);}
    }
    private Map<Material,Integer> materials(ConfigurationSection s){
        Map<Material,Integer> out=new LinkedHashMap<>();if(s==null)return out;
        for(String k:s.getKeys(false)){
            Material m=Material.matchMaterial(k);int amount=s.getInt(k,0);
            if(m==null||m.isAir()||!m.isItem()||amount<=0){plugin.getLogger().warning("Skipping invalid refinery material: "+k);continue;}
            out.put(m,amount);
        }return Map.copyOf(out);
    }
    @EventHandler public void onTerritoryLoad(NodesTerritoriesLoadedEvent e){
        // Avoid resetting running inventories during the same startup tick.
        Bukkit.getScheduler().runTask(plugin,this::reloadWorld);
    }
    private void reloadWorld(){
        if(active!=this)return;
        if(loaded)save();
        Map<Integer,Unit> next=new LinkedHashMap<>();
        for(Territory territory:Nodes.INSTANCE.getTerritories$nodes().values()){
            for(String resource:territory.getResourceNodes()){
                Type t=byResource.get(resource);if(t==null)continue;
                int id=territoryId(territory);next.put(id,new Unit(id,t));break; // one per territory
            }
        }
        units.clear();units.putAll(next);
        readState();loaded=true;
        plugin.getLogger().info("Loaded "+units.size()+" Phonon refinery territories and "+recipes.size()+" recipes.");
    }
    private void readState(){
        if(!Files.exists(dataFile))return;
        try{
            JsonObject root=JsonParser.parseString(Files.readString(dataFile)).getAsJsonObject();
            for(JsonElement obj:root.getAsJsonArray("refineries")){
                JsonObject entry=obj.getAsJsonObject();Unit u=units.get(entry.get("territoryId").getAsInt());if(u==null)continue;
                if(entry.has("recipeProgress"))for(JsonElement state:entry.getAsJsonArray("recipeProgress")){
                    JsonObject s=state.getAsJsonObject();String name=s.get("recipe").getAsString();
                    if(u.progress.containsKey(name))u.progress.put(name,Math.max(0,s.get("progress").getAsLong()));
                }
                if(entry.has("inventory")){
                    int legacySlot=0;
                    for(JsonElement state:entry.getAsJsonArray("inventory")){
                    JsonObject s=state.getAsJsonObject();
                    // Old Phonon 1.18.2 saves have sequential items without slots.
                    int slot=s.has("slot")?s.get("slot").getAsInt():legacySlot;
                    legacySlot++;
                    Material material=Material.matchMaterial(s.get("material").getAsString());int n=s.get("amount").getAsInt();
                    if(slot>=0&&slot<u.inventory.getSize()&&material!=null&&u.type.allowed.contains(material)&&n>0){
                        u.inventory.setItem(slot,new ItemStack(material,Math.min(n,material.getMaxStackSize())));
                    }
                    }
                }
            }
        }catch(Exception ex){plugin.getLogger().log(Level.WARNING,"Refinery state read failed; preserving original file",ex);}
    }
    private void save(){
        if(!loaded)return;
        JsonObject root=new JsonObject();root.addProperty("version",2);JsonArray all=new JsonArray();
        for(Unit u:units.values()){
            JsonObject entry=new JsonObject();entry.addProperty("territoryId",u.id);
            JsonArray progress=new JsonArray();u.progress.forEach((name,ms)->{
                JsonObject p=new JsonObject();p.addProperty("recipe",name);p.addProperty("progress",ms);progress.add(p);
            });entry.add("recipeProgress",progress);
            JsonArray items=new JsonArray();ItemStack[] contents=u.inventory.getStorageContents();
            for(int i=0;i<contents.length;i++){
                ItemStack stack=contents[i];if(stack==null||stack.getType().isAir())continue;
                JsonObject item=new JsonObject();item.addProperty("slot",i);item.addProperty("material",stack.getType().name());item.addProperty("amount",stack.getAmount());items.add(item);
            }
            entry.add("inventory",items);all.add(entry);
        }root.add("refineries",all);
        Path temporary=dataFile.resolveSibling(dataFile.getFileName()+".tmp");
        try{
            Files.createDirectories(dataFile.getParent());
            Files.writeString(temporary,gson.toJson(root),StandardCharsets.UTF_8);
            try{Files.move(temporary,dataFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,dataFile,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException e){plugin.getLogger().log(Level.SEVERE,"Refinery state save failed",e);}
    }
    private Map<Material,Integer> counts(Unit u){
        Map<Material,Integer> result=new EnumMap<>(Material.class);
        for(ItemStack it:u.inventory.getStorageContents())if(it!=null&&!it.getType().isAir())result.merge(it.getType(),it.getAmount(),Integer::sum);
        return result;
    }
    private static boolean has(Map<Material,Integer> amount,Map<Material,Integer> need){
        for(var entry:need.entrySet())if(amount.getOrDefault(entry.getKey(),0)<entry.getValue())return false;
        return true;
    }
    private static void consume(Unit u,Map<Material,Integer> needed){
        // Called only after a complete can-consume check on the main thread.
        for(var entry:needed.entrySet()){
            int remaining=entry.getValue();Material material=entry.getKey();
            for(int i=0;i<u.inventory.getSize()&&remaining>0;i++){
                ItemStack stack=u.inventory.getItem(i);if(stack==null||stack.getType()!=material)continue;
                int taking=Math.min(remaining,stack.getAmount());remaining-=taking;
                if(stack.getAmount()==taking)u.inventory.setItem(i,null);
                else{stack.setAmount(stack.getAmount()-taking);u.inventory.setItem(i,stack);}
            }
            if(remaining!=0)throw new IllegalStateException("Inventory changed during refinery tick");
        }
    }
    private void tick(){
        if(!loaded)return;
        long now=System.currentTimeMillis();long delta=Math.max(0,Math.min(now-lastTime,updateTicks*50L*2));lastTime=now;
        // Build a lookup once per update, not once per refinery (important on province-scale maps).
        Map<Integer,Territory> territories=new HashMap<>();
        for(Territory territory:Nodes.INSTANCE.getTerritories$nodes().values())territories.put(territoryId(territory),territory);
        for(Unit u:units.values()){
            Territory territory=territories.get(u.id);
            if(territory==null||territory.getTown()==null)continue;
            Town town=territory.getTown();
            try{
                Map<Material,Integer> available=counts(u);
                for(Recipe r:u.type.recipes){
                    if(!has(available,r.inputs)){u.progress.put(r.name,0L);continue;}
                    long progress=u.progress.getOrDefault(r.name,0L)+delta;
                    if(progress>=r.millis){
                        consume(u,r.inputs);
                        for(var out:r.outputs.entrySet())Nodes.INSTANCE.addToIncome(town,out.getKey(),out.getValue(),0);
                        available=counts(u);progress=0;
                    }
                    u.progress.put(r.name,progress);
                }
            }catch(Exception e){plugin.getLogger().log(Level.WARNING,"Refinery tick failed for territory "+u.id,e);}
        }
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Unit u))return;
        if(e.getClickedInventory()==null)return;
        if(e.getClickedInventory()==e.getView().getTopInventory()){
            switch(e.getAction()){
                case PLACE_ALL,PLACE_ONE,PLACE_SOME,SWAP_WITH_CURSOR -> {if(!valid(u,e.getCursor()))e.setCancelled(true);}
                case HOTBAR_SWAP,HOTBAR_MOVE_AND_READD -> {
                    int index=e.getHotbarButton();
                    if(index>=0&&index<9&&!valid(u,e.getWhoClicked().getInventory().getItem(index)))e.setCancelled(true);
                }
                default -> {}
            }
        }else if(e.getAction()==InventoryAction.MOVE_TO_OTHER_INVENTORY && !valid(u,e.getCurrentItem()))e.setCancelled(true);
        // Offhand swaps and collect-to-cursor cannot introduce invalid materials.
    }
    private boolean valid(Unit u,ItemStack item){return item==null||item.getType().isAir()||u.type.allowed.contains(item.getType());}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void drag(InventoryDragEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Unit u))return;
        if(valid(u,e.getOldCursor()))return;
        for(int slot:e.getRawSlots())if(slot<u.inventory.getSize()){e.setCancelled(true);break;}
    }
    private boolean admin(CommandSender sender){return sender.hasPermission("nodes.refinery.admin")||sender.hasPermission("refinery.admin")||sender.isOp();}
    private void reply(CommandSender sender,String message){sender.sendMessage(Component.text("[Refinery] "+message));}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length==0){
            if(!(sender instanceof Player p)){reply(sender,"Run in-game to open your territory refinery.");return true;}
            Territory territory=Nodes.INSTANCE.getTerritoryFromPlayer(p);
            if(territory==null){reply(sender,"You are not standing in a territory.");return true;}
            Unit u=units.get(territoryId(territory));if(u==null){reply(sender,"No refinery resource in this territory.");return true;}
            Town town=territory.getTown();
            if(town!=null){Resident r=Nodes.INSTANCE.getResident(p);if(r==null||r.getTown()!=town){reply(sender,"Only members of the owning town may use this refinery.");return true;}}
            p.openInventory(u.inventory);return true;
        }
        String sub=args[0].toLowerCase(Locale.ROOT);
        switch(sub){
            case "help" -> {reply(sender,"/refinery — open the refinery in your territory");reply(sender,"/refinery recipe <name> | /refinery type <name>");if(admin(sender))reply(sender,"/refinery reload <config|world> | /refinery save");}
            case "recipe" -> {
                if(args.length<2){reply(sender,"Recipes: "+String.join(", ",recipes.keySet()));return true;}
                Recipe r=recipes.get(args[1]);if(r==null){reply(sender,"Unknown recipe.");return true;}
                reply(sender,r.name+" | Input: "+r.inputs+" | Output: "+r.outputs+" | Time: "+r.millis/1000+"s");
            }
            case "type" -> {
                if(args.length<2){reply(sender,"Types: "+String.join(", ",types.keySet()));return true;}
                Type t=types.get(args[1]);if(t==null){reply(sender,"Unknown type.");return true;}
                reply(sender,t.title+" | Resource: "+t.resource+" | Slots: "+t.slots+" | Recipes: "+t.recipes.stream().map(Recipe::name).toList());
            }
            case "reload" -> {
                if(!admin(sender)){reply(sender,"No admin permission.");return true;}
                if(args.length<2){reply(sender,"Usage: /refinery reload config|world");return true;}
                if(args[1].equalsIgnoreCase("config")){save();loadConfig();reloadWorld();schedule();reply(sender,"Config and refinery territories reloaded.");}
                else if(args[1].equalsIgnoreCase("world")){reloadWorld();reply(sender,"Territory refineries reloaded.");}
                else reply(sender,"Usage: /refinery reload config|world");
            }
            case "save" -> {if(!admin(sender)){reply(sender,"No admin permission.");return true;}save();reply(sender,"Refinery state saved.");}
            default -> reply(sender,"Unknown command. Use /refinery help.");
        }return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1)return StreamMatch.of(List.of("help","recipe","type","reload","save"),args[0]);
        if(args.length==2)return switch(args[0].toLowerCase(Locale.ROOT)){
            case "recipe" -> StreamMatch.of(recipes.keySet(),args[1]);
            case "type" -> StreamMatch.of(types.keySet(),args[1]);
            case "reload" -> StreamMatch.of(List.of("config","world"),args[1]);
            default -> List.of();
        };return List.of();
    }
    private static class StreamMatch {
        static List<String> of(Collection<String> values,String prefix){return values.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().toList();}
    }
}
