package phonon.nodes.core;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Native Nodes fortifications; state is stored with the existing integrated core state. */
final class Fortifications {
    record Level(int number, double multiplier, Map<Material, Integer> cost, Map<Material, Integer> upkeep) {}

    private final CoreFeatures core;
    private final NavigableMap<Integer,Level> levels = new TreeMap<>();
    private final Set<Material> allowed = new HashSet<>();
    private long previousCycle;

    Fortifications(CoreFeatures core) {
        this.core = core;
        ConfigurationSection section=core.config.getConfigurationSection("fortify.levels");
        if(section!=null) {
            for(String key:section.getKeys(false)) {
                final int number;
                try{number=Integer.parseInt(key);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid fortify level: "+key,e);}
                if(number<1)throw new IllegalArgumentException("Fortify levels must be positive.");
                ConfigurationSection level=section.getConfigurationSection(key);
                if(level==null)throw new IllegalArgumentException("Missing fortify level "+key);
                double multiplier=level.getDouble("captureTimeMultiplier",1.0);
                if(!Double.isFinite(multiplier)||multiplier<1||multiplier>1000)throw new IllegalArgumentException("Invalid captureTimeMultiplier for fortify level "+key);
                var cost=materials(level.getConfigurationSection("cost"));
                var upkeep=materials(level.getConfigurationSection("upkeep"));
                levels.put(number,new Level(number,multiplier,cost,upkeep));
                allowed.addAll(cost.keySet());allowed.addAll(upkeep.keySet());
            }
        }
        long now=System.currentTimeMillis();
        previousCycle=core.data.yaml.getLong("fortificationLastIncomeCycle",now);
        if(previousCycle>now)previousCycle=now;
    }

    private Map<Material,Integer> materials(ConfigurationSection s) {
        if(s==null)return Map.of();
        Map<Material,Integer> result=new LinkedHashMap<>();
        for(String key:s.getKeys(false)) {
            Material material=Material.matchMaterial(key);
            if(material==null||!material.isItem()||material==Material.AIR)
                throw new IllegalArgumentException("Unknown item in fortify levels: "+key);
            int amount=s.getInt(key,-1);
            if(amount<0)throw new IllegalArgumentException("Negative/invalid fortify item quantity for "+key);
            if(amount>0)result.put(material,amount);
        }
        return Map.copyOf(result);
    }

    private String territoryPath(Object territory) {
        return "fortifications."+Native.call(territory,"getId");
    }
    private String fundPath(Object town) {
        return "fortificationTreasuries."+Native.id(town);
    }
    private int level(Object territory) {
        String path=territoryPath(territory);
        int value=core.data.yaml.getInt(path+".level",0);
        if(value<=0)return 0;
        Object currentOwner=Native.call(territory,"getTown");
        String recorded=core.data.yaml.getString(path+".town","");
        // An occupied but still-owned town keeps its defences; annexation changes ownership.
        if(currentOwner==null||!Native.id(currentOwner).toString().equals(recorded)||!levels.containsKey(value)) {
            core.data.yaml.set(path,null);
            return 0;
        }
        return value;
    }
    private int count(Object town,Material item) {
        return core.data.yaml.getInt(fundPath(town)+"."+item.name(),0);
    }
    private void change(Object town,Material item,int delta) {
        String path=fundPath(town)+"."+item.name();
        long amount=(long)count(town,item)+delta;
        if(amount<0||amount>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid treasury balance for "+item);
        core.data.yaml.set(path,amount==0?null:(int)amount);
    }
    private boolean canPay(Object town,Map<Material,Integer> items) {
        for(var e:items.entrySet())if(count(town,e.getKey())<e.getValue())return false;
        return true;
    }
    private void pay(Object town,Map<Material,Integer> items) {
        if(!canPay(town,items))throw new IllegalArgumentException("Insufficient fortification treasury materials: "+items);
        for(var e:items.entrySet())change(town,e.getKey(),-e.getValue());
    }
    private void showTreasury(Player p,Object town) {
        core.say(p,"Town fortification treasury:");
        for(Material material:new TreeSet<>(allowed)) {
            int amount=count(town,material);
            if(amount>0)core.say(p,material.name()+": "+amount);
        }
        core.say(p,"Allowed materials: "+allowed);
    }
    boolean command(Player p,String[] args) {
        if(!core.bool("fortify.enabled",true))throw new IllegalArgumentException("Territory fortification is disabled.");
        Object town=core.api.town(p.getUniqueId());
        if(town==null)throw new IllegalArgumentException("Join a town first.");
        String sub=args.length>=2?args[1].toLowerCase(Locale.ROOT):"upgrade";
        if(sub.equals("treasury")){showTreasury(p,town);return true;}
        if(sub.equals("deposit")) {
            if(args.length>3)throw new IllegalArgumentException("/t fortify deposit [amount]");
            ItemStack item=p.getInventory().getItemInMainHand();
            if(item.getType()==Material.AIR||!allowed.contains(item.getType()))throw new IllegalArgumentException("Hold one of the configured fortification materials.");
            if(item.hasItemMeta())throw new IllegalArgumentException("Custom/enchanted items cannot be stored in the material-only treasury.");
            int amount=args.length==3?positive(args[2]):item.getAmount();
            if(amount>item.getAmount())throw new IllegalArgumentException("You're not holding enough of that item.");
            int existing=count(town,item.getType());
            if((long)existing+amount>Integer.MAX_VALUE)throw new IllegalArgumentException("Treasury limit reached.");
            Material material=item.getType();
            if(amount==item.getAmount())p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            else item.setAmount(item.getAmount()-amount);
            change(town,material,amount);core.data.save();
            core.say(p,"Deposited "+amount+" "+material.name()+" into town's fortification treasury.");return true;
        }
        if(sub.equals("withdraw")) {
            if(!core.api.leadership(p,town))throw new IllegalArgumentException("Only town leadership may withdraw fortification items.");
            if(args.length!=4)throw new IllegalArgumentException("/t fortify withdraw <material> <amount>");
            Material material=Material.matchMaterial(args[2]);
            if(material==null||!allowed.contains(material))throw new IllegalArgumentException("Material must be a configured fortification item.");
            int amount=positive(args[3]);
            if(count(town,material)<amount)throw new IllegalArgumentException("Not enough items in the fortification treasury.");
            int left=amount;
            // Inventory inserts or drops overflow at the player's feet, avoiding item loss.
            while(left>0){int take=Math.min(left,material.getMaxStackSize());ItemStack item=new ItemStack(material,take);
                for(ItemStack leftover:p.getInventory().addItem(item).values())p.getWorld().dropItemNaturally(p.getLocation(),leftover);
                left-=take;
            }
            change(town,material,-amount);core.data.save();
            core.say(p,"Withdrawn "+amount+" "+material.name()+" from the treasury.");return true;
        }
        if(!Set.of("upgrade","info","status").contains(sub))throw new IllegalArgumentException("/t fortify [upgrade|info|treasury|deposit|withdraw]");
        Object territory=core.api.territory(p.getLocation());
        if(territory==null||!Objects.equals(Native.call(territory,"getTown"),town))
            throw new IllegalArgumentException("Stand within territory owned by your town.");
        int current=level(territory);
        if(sub.equals("info")||sub.equals("status")) {
            core.say(p,"Territory "+Native.call(territory,"getId")+" fortification level: "+current);
            if(levels.containsKey(current))core.say(p,"Enemy capture-time multiplier: "+levels.get(current).multiplier());
            Integer next=levels.higherKey(current);
            if(next!=null)core.say(p,"Next upgrade level "+next+" requires "+levels.get(next).cost());
            return true;
        }
        if(!core.api.leadership(p,town))throw new IllegalArgumentException("Only town leadership may upgrade fortifications.");
        Integer next=levels.higherKey(current);
        if(next==null)throw new IllegalArgumentException("This territory is already fortified to the highest configured level.");
        Level upgrade=levels.get(next);
        pay(town,upgrade.cost());
        String path=territoryPath(territory);
        core.data.yaml.set(path+".town",Native.id(town).toString());
        core.data.yaml.set(path+".level",next);
        core.data.save();
        core.say(p,"Fortified territory "+Native.call(territory,"getId")+" to level "+next+"; enemy capture time x"+upgrade.multiplier());
        return true;
    }
    private int positive(String s) {
        try{int x=Integer.parseInt(s);if(x<1)throw new NumberFormatException();return x;}
        catch(NumberFormatException e){throw new IllegalArgumentException("Amount must be a positive whole number.");}
    }

    long captureTime(Object territory,Object attackerTown,long base) {
        if(!core.bool("fortify.enabled",true)||territory==null)return base;
        Object defender=Native.call(territory,"getTown");
        if(defender==null||Objects.equals(defender,attackerTown))return base;
        int value=level(territory);
        if(value==0)return base;
        double scaled=base*levels.get(value).multiplier();
        return scaled>=Long.MAX_VALUE?Long.MAX_VALUE:Math.max(20L,(long)Math.ceil(scaled));
    }

    /** Approximate income period; does not bill multiple past cycles on restart/downtime. */
    void tick() {
        if(!core.bool("fortify.enabled",true)||!core.bool("incomeEnabled",true))return;
        long interval=(long)core.number("incomePeriod",3600000);
        if(interval<1000)throw new IllegalArgumentException("incomePeriod must be at least 1000ms for fortifications.");
        long now=System.currentTimeMillis();
        if(now-previousCycle<interval)return;
        previousCycle=now;
        core.data.yaml.set("fortificationLastIncomeCycle",now);
        ConfigurationSection state=core.data.yaml.getConfigurationSection("fortifications");
        if(state==null){core.data.save();return;}
        for(String id:new ArrayList<>(state.getKeys(false))) {
            String path="fortifications."+id;
            String uuid=core.data.yaml.getString(path+".town","");
            int value=core.data.yaml.getInt(path+".level",0);
            try {
                Level details=levels.get(value);
                if(details==null){core.data.yaml.set(path,null);continue;}
                Object town=core.api.byId(UUID.fromString(uuid),false);
                if(town==null){core.data.yaml.set(path,null);continue;}
                // Stored owner must still possess the territory; lookup by its numeric ID.
                Object territory=Native.call(core.api.nodes,"getTerritoryFromId",Integer.parseInt(id));
                if(territory==null||!Objects.equals(Native.call(territory,"getTown"),town)) {
                    core.data.yaml.set(path,null);continue;
                }
                if(!canPay(town,details.upkeep())) {
                    Integer lower=levels.lowerKey(value);
                    if(lower==null)core.data.yaml.set(path,null);
                    else core.data.yaml.set(path+".level",lower);
                    core.plugin.getLogger().info("Territory "+id+" dropped fortification from level "+value+" due to unpaid upkeep.");
                } else pay(town,details.upkeep());
            } catch(Exception e) {
                core.plugin.getLogger().warning("Could not process fortification for territory "+id+": "+e.getMessage());
            }
        }
        core.data.save();
    }
}
