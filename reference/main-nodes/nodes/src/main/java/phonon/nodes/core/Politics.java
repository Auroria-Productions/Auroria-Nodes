package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import java.util.*;

final class Politics {
    final CoreFeatures core;final SubjectGraph graph=new SubjectGraph();
    record Offer(UUID lord,UUID subject,SubjectGraph.Kind kind,double resource,double economy,long expiry){}
    final Map<String,Offer> offers=new HashMap<>();
    Politics(CoreFeatures core){this.core=core;var root=core.data.yaml.getConfigurationSection("subjects");if(root!=null)for(String key:root.getKeys(false)){
        var s=root.getConfigurationSection(key);if(s==null)continue;
        graph.add(new SubjectGraph.Link(UUID.fromString(key),UUID.fromString(s.getString("overlord")),SubjectGraph.Kind.valueOf(s.getString("kind")),s.getDouble("resources"),s.getDouble("economy"),s.getLong("expires")));
    }}
    void save(){core.data.yaml.set("subjects",null);for(var l:graph.links.values()){
        String p="subjects."+l.subject();core.data.yaml.set(p+".overlord",l.overlord().toString());core.data.yaml.set(p+".kind",l.kind().name());core.data.yaml.set(p+".resources",l.resources());core.data.yaml.set(p+".economy",l.economy());core.data.yaml.set(p+".expires",l.expires());
    }core.data.save();}
    boolean active(SubjectGraph.Link l){return l!=null&&core.bool(l.kind()==SubjectGraph.Kind.TRIBUTARY?"enableTributaries":"enableVassals",true);}
    Object own(Player player){Object nation=core.api.nation(player.getUniqueId());if(!core.api.nationLeader(player,nation))throw new IllegalArgumentException("Only a nation's capital leader/officers may change foreign agreements.");return nation;}
    Object target(String name){Object target=core.api.named(name,true);if(target==null)throw new IllegalArgumentException("Unknown nation: "+name);return target;}
    boolean command(Player p,String kind,String[] args){
        if(kind.equals("subjects")){Object nation=core.api.nation(p.getUniqueId());if(nation==null)throw new IllegalArgumentException("Join a nation first.");info(p,nation);return true;}
        Object own=own(p);UUID self=Native.id(own);
        SubjectGraph.Kind type=kind.equals("tributary")?SubjectGraph.Kind.TRIBUTARY:SubjectGraph.Kind.VASSAL;
        if(!core.bool(type==SubjectGraph.Kind.VASSAL?"enableVassals":"enableTributaries",true))throw new IllegalArgumentException("This relationship type is disabled.");
        if(args.length<2){core.say(p,"/nation "+kind+" offer|accept|decline|release|break <nation> [resourcePercent] [economyPercent]");return true;}
        String action=args[1].toLowerCase(Locale.ROOT);
        if(args.length<3)throw new IllegalArgumentException("Specify the other nation.");
        Object other=target(args[2]);UUID otherId=Native.id(other);
        if(self.equals(otherId))throw new IllegalArgumentException("A nation cannot be its own subject.");
        if(action.equals("offer")){
            double resource=args.length>3?percent(args[3]):0,economy=args.length>4?percent(args[4]):0;
            if(resource>core.number(type==SubjectGraph.Kind.TRIBUTARY?"maxResourceTributaryTaxRate":"maxResourceTaxRate",40)||economy>core.number(type==SubjectGraph.Kind.TRIBUTARY?"maxEcoTributaryTaxRate":"maxEcoTaxRate",30))throw new IllegalArgumentException("Offered tax exceeds configured cap.");
            if(graph.links.containsKey(otherId))throw new IllegalArgumentException("That nation already has an overlord.");
            var test=new SubjectGraph();test.links.putAll(graph.links);test.add(new SubjectGraph.Link(otherId,self,type,resource,economy,0));
            offers.put(self+":"+otherId,new Offer(self,otherId,type,resource,economy,System.currentTimeMillis()+600000));
            core.announce("%nation% offered "+type.name().toLowerCase()+" terms to %defender%",Map.of("nation",Native.name(own),"defender",Native.name(other)));
            core.say(p,"Offer expires in 10 minutes. The other nation must /nation "+kind+" accept "+Native.name(own));return true;
        }
        if(action.equals("accept")||action.equals("decline")){
            String key=otherId+":"+self;Offer offer=offers.get(key);
            if(offer==null||offer.kind!=type||offer.expiry<System.currentTimeMillis())throw new IllegalArgumentException("No matching unexpired offer.");
            if(action.equals("decline")){offers.remove(key);core.say(p,"Offer declined.");return true;}
            if(core.api.enemies(core.api.capital(own),core.api.capital(other)))throw new IllegalArgumentException("End the war before accepting a subject agreement.");
            graph.add(new SubjectGraph.Link(self,otherId,type,offer.resource,offer.economy,0));save();offers.remove(key);
            core.say(p,"Your nation is now a "+type.name().toLowerCase()+" of "+Native.name(other));info(p,own);return true;
        }
        if(action.equals("release")){
            var link=graph.links.get(otherId);if(link==null||!link.overlord().equals(self)||link.kind()!=type)throw new IllegalArgumentException("That nation is not your subject of this type.");
            graph.links.remove(otherId);save();core.say(p,"Subject released.");return true;
        }
        if(action.equals("break")){
            var link=graph.links.get(self);if(link==null||!link.overlord().equals(otherId)||link.kind()!=type)throw new IllegalArgumentException("That nation is not your overlord.");
            if(type==SubjectGraph.Kind.VASSAL)core.api.enemy(core.api.capital(own),core.api.capital(other),true);
            graph.links.remove(self);save();core.say(p,type==SubjectGraph.Kind.VASSAL?"Independence war declared against your former overlord.":"Tributary agreement ended.");return true;
        }
        throw new IllegalArgumentException("Unknown subject action.");
    }
    static double percent(String s){try{double x=Double.parseDouble(s);if(!Double.isFinite(x)||x<0||x>100)throw new NumberFormatException();return x;}catch(NumberFormatException e){throw new IllegalArgumentException("Tax must be a finite percentage between 0 and 100.");}}
    void info(CommandSender sender,Object nation){
        UUID id=Native.id(nation);var own=graph.links.get(id);core.say(sender,Native.name(nation)+" foreign status: "+(own==null?"independent":own.kind()+" under "+Native.name(core.api.byId(own.overlord(),true))));
        for(var l:graph.children(id))core.say(sender,l.kind()+": "+Native.name(core.api.byId(l.subject(),true))+"; resources="+l.resources()+"%, economy="+l.economy()+"%");
    }
    boolean autonomous(UUID nation){var link=graph.links.get(nation);return !active(link)||link.kind()!=SubjectGraph.Kind.VASSAL;}
    void tick(){
        int before=graph.links.size();graph.expire(System.currentTimeMillis());
        graph.links.values().removeIf(l->core.api.byId(l.subject(),true)==null||core.api.byId(l.overlord(),true)==null);
        if(before!=graph.links.size())save();
        offers.values().removeIf(o->o.expiry<System.currentTimeMillis());
        for(var link:List.copyOf(graph.links.values()))if(active(link)&&link.kind()==SubjectGraph.Kind.VASSAL){
            Object lord=core.api.byId(link.overlord(),true),vassal=core.api.byId(link.subject(),true);if(lord==null||vassal==null)continue;
            Object root=core.api.capital(lord),subject=core.api.capital(vassal);
            for(Object enemy:Native.collection(lord,"getEnemies")){
                Object enemyCapital=core.api.capital(enemy);if(!core.api.enemies(subject,enemyCapital))try{core.api.enemy(subject,enemyCapital,true);}catch(Exception ignored){}
            }
        }
    }
    private int taxTo(Object payer,Object receiver,Material material,int amount,double rate,String stage){
        if(receiver==null||receiver==payer||rate<=0||amount<=0)return amount;
        String key="resourceCarry."+Native.id(payer)+"."+Native.id(receiver)+"."+material.name()+"."+stage;
        double precise=amount*rate+core.data.yaml.getDouble(key);int owed=(int)Math.min(amount,Math.floor(precise));
        int room=Integer.MAX_VALUE-core.api.storage(receiver).getOrDefault(material,0);int paid=Math.max(0,Math.min(owed,room));
        if(paid>0){try{CoreHooks.transferring.set(true);core.api.put(receiver,material,paid);}finally{CoreHooks.transferring.set(false);}}
        core.data.yaml.set(key,precise-Math.floor(precise));return amount-paid;
    }
    int income(Object inventory,Material material,int amount){
        if(amount<=0||!core.bool("enableTaxation",true)||!core.bool("enableResourceTaxation",false)||CoreHooks.transferring.get())return amount;
        Object town=null;for(Object t:core.api.towns().values())if(core.api.income(t)==inventory){town=t;break;}if(town==null)return amount;
        int kept=amount;Object nation=Native.call(town,"getNation");
        if(nation!=null){
            Object capital=core.api.capital(nation);double rate=core.data.yaml.getDouble("nationTax."+Native.id(nation)+".resources");
            kept=taxTo(town,capital,material,kept,Math.min(rate,core.number("maxResourceTaxRate",40))/100,"nation");
            var link=graph.links.get(Native.id(nation));
            if(active(link)){
                Object overlord=core.api.byId(link.overlord(),true);if(overlord!=null){Object receiver=core.api.capital(overlord);Location a=Native.spawn(town),b=Native.spawn(receiver);
                    if(a.getWorld().equals(b.getWorld())){double cap=core.number(link.kind()==SubjectGraph.Kind.TRIBUTARY?"maxResourceTributaryTaxRate":link.kind()==SubjectGraph.Kind.CONQUERED?"maxResourceConqueredTaxRate":"maxResourceTaxRate",40);
                        double rate2=SubjectGraph.effectiveRate(link.resources(),cap,Math.hypot(a.getX()-b.getX(),a.getZ()-b.getZ())/16,core.number("taxDistancePenaltyMinimum",96),core.number("taxDistancePenaltyRamp",.01090476));
                        kept=taxTo(town,receiver,material,kept,rate2,"subject");
                    }
                }
            }
        }
        String conquered="conquered."+Native.id(town);String winner=core.data.yaml.getString(conquered+".winner");
        if(winner!=null&&core.data.yaml.getLong(conquered+".until")>System.currentTimeMillis())kept=taxTo(town,core.api.byId(UUID.fromString(winner),false),material,kept,core.number("maxResourceConqueredTaxRate",60)/100,"conquered");
        return kept;
    }
}
