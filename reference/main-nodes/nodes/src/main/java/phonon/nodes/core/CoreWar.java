package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

final class CoreWar {
    final CoreFeatures c;
    CoreWar(CoreFeatures c){this.c=c;}
    String key(Object town){Object nation=Native.call(town,"getNation");return nation==null?"T-"+Native.id(town):"N-"+Native.id(nation);}
    Object capital(String key){UUID id=UUID.fromString(key.substring(2));Object o=c.api.byId(id,key.startsWith("N-"));return key.startsWith("N-")?c.api.capital(o):o;}
    Object target(String name){Object nation=c.api.named(name,true);Object town=nation==null?c.api.named(name,false):c.api.capital(nation);if(town==null)throw new IllegalArgumentException("Unknown town/nation.");return town;}
    Set<String> party(String path,String side){return new HashSet<>(c.data.yaml.getStringList(path+"."+side));}
    boolean command(Player p,String root,String[] args){
        if(root.equals("peace")&&args.length>0&&args[0].equalsIgnoreCase("surrender")){
            if(!c.bool("newStyleSurrenderConquer",false))throw new IllegalArgumentException("Conquest-style surrender is disabled.");
            Object town=c.api.town(p.getUniqueId());if(town==null||!c.api.leadership(p,town))throw new IllegalArgumentException("Town leadership required.");if(args.length<2)throw new IllegalArgumentException("/peace surrender <enemy> [conquer|plunder]");
            Object winner=target(args[1]);if(!c.api.enemies(town,winner))throw new IllegalArgumentException("Surrender requires an existing war.");
            boolean plunder=args.length>2&&args[2].equalsIgnoreCase("plunder");long until=System.currentTimeMillis()+c.duration(plunder?"plunderPeriod":"conquestPeriod",259200000);
            String path="conquered."+Native.id(town);c.data.yaml.set(path+".winner",Native.id(winner).toString());c.data.yaml.set(path+".until",until);c.data.yaml.set(path+".plunder",plunder);
            for(Object id:List.copyOf(Native.collection(town,"getCaptured"))){Object territory=Native.call(c.api.nodes,"getTerritoryFromId",unbox(id));if(territory!=null)Native.call(c.api.nodes,"releaseTerritory",territory);}
            if(c.bool("onlyKeepCapital",false)){for(Object id:List.copyOf(Native.collection(town,"getTerritories")))if(!Objects.equals(unbox(id),Native.call(town,"getHome"))){Object territory=Native.call(c.api.nodes,"getTerritoryFromId",unbox(id));Native.call(c.api.nodes,"unclaimTerritory",town,territory);}}
            if(plunder&&c.bool("enableEconomy",false)){long price=(long)Math.ceil(((Number)Native.call(town,"getClaimsUsed")).doubleValue()*c.number("plunderAmount",25)*100);String source=c.economy.account(town),dest=c.economy.account(winner);long paid=Math.min(price,c.economy.balance(source));c.economy.balance(source,c.economy.balance(source)-paid);c.economy.balance(dest,c.economy.balance(dest)+paid);}
            c.announce(c.config.getString("messageSurrender","%surrendertown% surrendered to %invader%"),Map.of("surrendertown",Native.name(town),"invader",Native.name(winner)));
            c.api.enemy(town,winner,false);c.api.save();c.data.save();String template=c.config.getString(plunder?"messageTownPlunder":"messageTownConquer","%surrendertown% surrendered to %invader%");c.announce(template,Map.of("surrendertown",Native.name(town),"invader",Native.name(winner)));return true;
        }
        if(!root.equals("war")||c.bool("oldWarSystem",true)||args.length==0)return false;
        Object town=c.api.town(p.getUniqueId()),nation=c.api.nation(p.getUniqueId());if(town==null||!c.api.leadership(p,town)||nation!=null&&!c.api.nationLeader(p,nation))throw new IllegalArgumentException("Capital leadership is required to manage wars.");
        if(nation!=null&&!c.politics.autonomous(Native.id(nation)))throw new IllegalArgumentException("Vassals follow their overlord's foreign policy; use /nation vassal break to seek independence.");
        if(args[0].equalsIgnoreCase("join")){
            if(args.length!=3||!Set.of("attackers","defenders").contains(args[2]))throw new IllegalArgumentException("/war join <war-id> attackers|defenders");
            String path="wars."+args[1];if(!c.data.yaml.contains(path)||c.data.yaml.getString(path+".state","").equals("ENDED"))throw new IllegalArgumentException("Unknown active war.");
            Set<String> side=party(path,args[2]);boolean allied=false;for(String member:side){Object capital=capital(member);if(capital!=null&&(Native.collection(capital,"getAllies").contains(town)||key(town).equals(member)))allied=true;}
            if(!allied)throw new IllegalArgumentException("You must be allied with a participant on that side.");Set<String> opposite=party(path,args[2].equals("attackers")?"defenders":"attackers");if(opposite.contains(key(town)))throw new IllegalArgumentException("You cannot join both sides.");
            side.add(key(town));c.data.yaml.set(path+"."+args[2],List.copyOf(side));c.data.save();activate(path);c.announce(c.config.getString("messageJoinWar","%nation% joined war against %defender%"),Map.of("nation",Native.name(nation==null?town:nation),"defender",String.join(", ",opposite)));return true;
        }
        Object enemy=target(args[0]);if(key(town).equals(key(enemy)))throw new IllegalArgumentException("Cannot declare war on your own nation.");
        if(c.api.enemies(town,enemy))throw new IllegalArgumentException("Already at war.");
        c.rules.diplomaticDistance(town,enemy,"maxDistanceWarDeclaration");
        if(Native.collection(town,"getAllies").contains(enemy))throw new IllegalArgumentException("End your alliance first.");
        String id=UUID.randomUUID().toString(),path="wars."+id;boolean plunder=args.length>1&&args[1].equalsIgnoreCase("plunder");long now=System.currentTimeMillis();
        Set<String> attackers=new HashSet<>(),defenders=new HashSet<>();attackers.add(key(town));defenders.add(key(enemy));
        if(nation!=null)for(UUID subject:c.politics.graph.vassalTree(Native.id(nation)))attackers.add("N-"+subject);
        Object defenderNation=Native.call(enemy,"getNation");if(defenderNation!=null)for(UUID subject:c.politics.graph.vassalTree(Native.id(defenderNation)))defenders.add("N-"+subject);
        c.data.yaml.set(path+".attackers",List.copyOf(attackers));c.data.yaml.set(path+".defenders",List.copyOf(defenders));c.data.yaml.set(path+".state","WARMUP");
        long starts=now+c.duration("warmupPeriod",86400000);c.data.yaml.set(path+".starts",starts);c.data.yaml.set(path+".ends",starts+c.duration(plunder?"plunderPeriod":"conquestPeriod",259200000));c.data.save();
        c.say(p,"War "+id+" scheduled. Allies may /war join "+id+" attackers|defenders. Warmup ends at "+java.time.Instant.ofEpochMilli(starts));return true;
    }
    static Object unbox(Object id){return id instanceof Number?id:Native.call(id,"unbox");}
    boolean warming(Object a,Object b){var wars=c.data.yaml.getConfigurationSection("wars");if(wars==null||a==null||b==null)return false;for(String id:wars.getKeys(false)){String p="wars."+id;if(!c.data.yaml.getString(p+".state","").equals("WARMUP"))continue;var left=party(p,"attackers");var right=party(p,"defenders");if(left.contains(key(a))&&right.contains(key(b))||left.contains(key(b))&&right.contains(key(a)))return true;}return false;}
    void activate(String path){
        if(System.currentTimeMillis()<c.data.yaml.getLong(path+".starts"))return;
        for(String a:party(path,"attackers"))for(String b:party(path,"defenders")){Object x=capital(a),y=capital(b);if(x!=null&&y!=null&&!c.api.enemies(x,y))c.api.enemy(x,y,true);}
        if(!c.data.yaml.getString(path+".state","").equals("ACTIVE"))c.announce(c.config.getString("messageWarDeclare","%attacker% declared war on %defender%"),Map.of("attacker",names(party(path,"attackers")),"defender",names(party(path,"defenders"))));
        c.data.yaml.set(path+".state","ACTIVE");c.data.save();
    }
    String names(Set<String> roots){return roots.stream().map(this::capital).filter(Objects::nonNull).map(Native::name).sorted().collect(java.util.stream.Collectors.joining(", "));}
    void tick(){
        var conquered=c.data.yaml.getConfigurationSection("conquered");if(conquered!=null)for(String id:List.copyOf(conquered.getKeys(false)))if(conquered.getLong(id+".until")<=System.currentTimeMillis())conquered.set(id,null);
        var wars=c.data.yaml.getConfigurationSection("wars");if(wars==null)return;long now=System.currentTimeMillis();
        for(String id:wars.getKeys(false)){String path="wars."+id,state=c.data.yaml.getString(path+".state","");if(state.equals("ENDED"))continue;
            if(now>=c.data.yaml.getLong(path+".ends")){for(String a:party(path,"attackers"))for(String b:party(path,"defenders")){Object x=capital(a),y=capital(b);if(x!=null&&y!=null&&c.api.enemies(x,y))c.api.enemy(x,y,false);}c.data.yaml.set(path+".state","ENDED");c.data.save();c.announce(c.config.getString("messageEnd","War between %nation% and %defender% ended"),Map.of("nation",names(party(path,"attackers")),"defender",names(party(path,"defenders"))));}
            else if(state.equals("WARMUP")&&now>=c.data.yaml.getLong(path+".starts"))activate(path);
        }
    }
}
