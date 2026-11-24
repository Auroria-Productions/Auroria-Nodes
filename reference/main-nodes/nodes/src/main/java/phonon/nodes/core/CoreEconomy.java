package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import java.util.*;
import java.time.*;
import java.math.*;

final class CoreEconomy {
    final CoreFeatures c;private String lastDay="";
    CoreEconomy(CoreFeatures core){c=core;}
    static long cents(String input){try{BigDecimal n=new BigDecimal(input);long x=n.movePointRight(2).longValueExact();if(x<1||x>100_000_000_000L)throw new ArithmeticException();return x;}catch(Exception e){throw new IllegalArgumentException("Enter an amount from 0.01 to 1000000000.00 with at most 2 decimal places.");}}
    long balance(String account){return c.data.yaml.getLong("banks."+account);}
    void balance(String account,long value){if(value<0)throw new IllegalArgumentException("Insufficient treasury balance.");c.data.yaml.set("banks."+account,value);}
    String account(Object town){return "town-"+Native.id(town);}
    Object provider(){
        if(!c.bool("enableEconomy",false))throw new IllegalArgumentException("Economy mechanics are disabled.");
        var vault=Bukkit.getPluginManager().getPlugin("Vault");if(vault==null)throw new IllegalArgumentException("Vault is required for economy operations.");
        try{@SuppressWarnings({"unchecked","rawtypes"})var registration=Bukkit.getServicesManager().getRegistration((Class)Class.forName("net.milkbowl.vault.economy.Economy",true,vault.getClass().getClassLoader()));if(registration==null)throw new IllegalArgumentException("No Vault economy provider is available.");Object p=registration.getProvider();
            String name=String.valueOf(Native.call(p,"getName")),old=c.data.yaml.getString("economyProvider");if(old!=null&&!old.equals(name))throw new IllegalArgumentException("Economy provider changed: treasury migration is required.");c.data.yaml.set("economyProvider",name);return p;
        }catch(ClassNotFoundException e){throw new IllegalArgumentException("Vault API is unavailable.");}
    }
    boolean held(UUID actor){var root=c.data.yaml.getConfigurationSection("payments");if(root==null)return false;for(String id:root.getKeys(false))if(root.getString(id+".actor","").equals(actor.toString())&&root.getString(id+".state","").equals("PENDING"))return true;return false;}
    void transfer(Player actor,String account,long amount,boolean deposit,String bill){
        if(held(actor.getUniqueId()))throw new IllegalArgumentException("An earlier payment is unresolved; ask staff to inspect /nda core audit.");
        Object provider=provider();if(!deposit&&balance(account)<amount)throw new IllegalArgumentException("Insufficient treasury funds.");
        if(deposit&&!(Boolean)Native.call(provider,"has",(OfflinePlayer)actor,amount/100.0))throw new IllegalArgumentException("Insufficient personal funds.");
        String id=UUID.randomUUID().toString(),path="payments."+id;
        c.data.yaml.set(path+".actor",actor.getUniqueId().toString());c.data.yaml.set(path+".account",account);c.data.yaml.set(path+".amount",amount);c.data.yaml.set(path+".deposit",deposit);c.data.yaml.set(path+".bill",bill);c.data.yaml.set(path+".state","PENDING");
        if(!deposit)balance(account,balance(account)-amount);c.data.save();
        try{
            Object response=Native.call(provider,deposit?"withdrawPlayer":"depositPlayer",(OfflinePlayer)actor,amount/100.0);
            boolean ok=(Boolean)Native.call(response,"transactionSuccess");resolve(id,ok);
            if(!ok)throw new IllegalArgumentException("Economy provider rejected the transfer.");
            c.integrations.log("treasury",actor.getUniqueId(),(deposit?"deposit ":"withdraw ")+amount+" "+account);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("Payment "+id+" held for staff review; never automatically replayed",e);}
    }
    void resolve(String id,boolean applied){
        String path="payments."+id;if(!c.data.yaml.getString(path+".state","").equals("PENDING"))throw new IllegalArgumentException("No unresolved payment with that ID.");
        String account=c.data.yaml.getString(path+".account");long amount=c.data.yaml.getLong(path+".amount");boolean deposit=c.data.yaml.getBoolean(path+".deposit");
        if(deposit&&applied&&!account.equals("fee"))credit(account,amount);
        if(!deposit&&!applied)balance(account,balance(account)+amount);
        String bill=c.data.yaml.getString(path+".bill");if(bill!=null&&applied)c.data.yaml.set("bills."+bill+".state","PAID");
        c.data.yaml.set(path+".state",applied?"DONE":"FAILED");c.data.save();
    }
    private void credit(String account,long amount){
        balance(account,Math.addExact(balance(account),amount));
        if(!c.bool("enableTaxation",true)||!c.bool("enableEcoTaxation",false)||!account.startsWith("town-"))return;
        Object town=c.api.byId(UUID.fromString(account.substring(5)),false);if(town==null)return;Object nation=Native.call(town,"getNation");if(nation==null)return;
        UUID nid=Native.id(nation);double rate=Math.min(c.data.yaml.getDouble("nationTax."+nid+".economy"),c.number("maxEcoTaxRate",30))/100;
        long tax=(long)Math.floor(amount*rate);if(tax>0){balance(account,balance(account)-tax);balance("nation-"+nid,Math.addExact(balance("nation-"+nid),tax));}
        String occupied="conquered."+Native.id(town),winner=c.data.yaml.getString(occupied+".winner");
        if(winner!=null&&c.data.yaml.getLong(occupied+".until")>System.currentTimeMillis()){
            long conqueredTax=(long)Math.floor((amount-tax)*c.number("maxEcoConqueredTaxRate",50)/100);conqueredTax=Math.min(conqueredTax,balance(account));
            String receiver="town-"+winner;balance(account,balance(account)-conqueredTax);balance(receiver,Math.addExact(balance(receiver),conqueredTax));return;
        }
        var link=c.politics.graph.links.get(nid);if(c.politics.active(link)){
            double cap=c.number(link.kind()==SubjectGraph.Kind.TRIBUTARY?"maxEcoTributaryTaxRate":link.kind()==SubjectGraph.Kind.CONQUERED?"maxEcoConqueredTaxRate":"maxEcoTaxRate",30);
            long tribute=(long)Math.floor((amount-tax)*Math.min(cap,link.economy())/100);if(tribute>0){balance(account,balance(account)-tribute);String receiver="nation-"+link.overlord();balance(receiver,Math.addExact(balance(receiver),tribute));}
        }
    }
    boolean home(Player p,Object town){Object territory=c.api.territory(p.getLocation());return territory!=null&&Objects.equals(Native.call(territory,"getId"),Native.call(town,"getHome"));}
    boolean command(Player p,String root,String[] args){
        if(args.length==0||!Set.of("town","nation").contains(root))return false;String sub=args[0].toLowerCase(Locale.ROOT);
        if(root.equals("town")&&sub.equals("plotbank")){
            if(args.length<2)throw new IllegalArgumentException("/town plotbank <plot> [deposit|withdraw <amount>]");
            Object plot=Native.call(c.api.singleton("phonon.nodes.plot.PlotManager"),"getPlot",args[1]);if(plot==null)throw new IllegalArgumentException("Unknown plot.");
            UUID owner=(UUID)Native.call(plot,"getOwnerUuid");Object town=c.api.named(String.valueOf(Native.call(plot,"getTownName")),false);
            if(!p.getUniqueId().equals(owner)&&(town==null||!c.api.leadership(p,town)))throw new IllegalArgumentException("Plot owner or town leadership required.");
            String account="plot-"+UUID.nameUUIDFromBytes(String.valueOf(Native.call(plot,"getId")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(args.length==2){c.say(p,"Plot treasury: "+BigDecimal.valueOf(balance(account),2));return true;}
            if(args.length!=4||!Set.of("deposit","withdraw").contains(args[2]))throw new IllegalArgumentException("/town plotbank <plot> deposit|withdraw <amount>");
            if(args[2].equals("withdraw")&&!p.getUniqueId().equals(owner))throw new IllegalArgumentException("Only the plot owner may withdraw its funds.");
            if(args[2].equals("deposit")&&c.bool("plotDepositOnlyInHome",true)&&!Boolean.TRUE.equals(Native.call(Native.call(plot,"getSelection"),"contains",p.getLocation())))throw new IllegalArgumentException("Stand inside the plot to deposit.");
            transfer(p,account,cents(args[3]),args[2].equals("deposit"),null);c.say(p,"Plot treasury transfer completed.");return true;
        }
        if(Set.of("bank","deposit","withdraw").contains(sub)){
            Object town=c.api.town(p.getUniqueId()),nation=c.api.nation(p.getUniqueId());if(town==null)throw new IllegalArgumentException("Join a town first.");Object origin=root.equals("nation")?(nation==null?null:c.api.capital(nation)):town;if(origin==null)throw new IllegalArgumentException("Join a nation first.");
            String account=root+"-"+Native.id(root.equals("nation")?nation:town);
            if(sub.equals("bank")){c.say(p,"Treasury balance: "+BigDecimal.valueOf(balance(account),2));return true;}
            if(args.length!=2)throw new IllegalArgumentException("Specify an amount.");long amount=cents(args[1]);
            if(sub.equals("withdraw")&&!(root.equals("nation")?c.api.nationLeader(p,nation):c.api.leadership(p,town)))throw new IllegalArgumentException("Only leadership may withdraw.");
            if(sub.equals("deposit")&&c.bool(root+"DepositOnlyInHome",true)&&!home(p,origin))throw new IllegalArgumentException("Deposit while standing in the treasury's home node.");
            transfer(p,account,amount,sub.equals("deposit"),null);c.say(p,"Treasury transfer completed.");return true;
        }
        if(root.equals("nation")&&sub.equals("tax")){
            Object nation=c.politics.own(p);if(args.length!=3||!Set.of("resources","economy").contains(args[1]))throw new IllegalArgumentException("/nation tax resources|economy <percent>");
            double percent=Politics.percent(args[2]);double cap=c.number(args[1].equals("resources")?"maxResourceTaxRate":"maxEcoTaxRate",30);if(percent>cap)throw new IllegalArgumentException("Tax exceeds configured cap.");
            c.data.yaml.set("nationTax."+Native.id(nation)+"."+args[1],percent);c.data.save();c.say(p,"Nation tax saved.");return true;
        }
        if(root.equals("town")&&sub.equals("levy")){
            Object town=c.api.town(p.getUniqueId());if(town==null||!c.api.leadership(p,town))throw new IllegalArgumentException("Town leadership required.");if(args.length!=2)throw new IllegalArgumentException("/town levy <amount per resident>");
            long amount=cents(args[1]);for(Object resident:Native.collection(town,"getResidents"))bill(Native.id(resident),account(town),amount,c.duration("townLevyBillingExpiry",259200000),"levy");c.data.save();c.say(p,"Levy invoices issued to residents; no automatic withdrawals.");return true;
        }
        if(root.equals("town")&&sub.equals("bills")){
            if(args.length==3&&args[1].equalsIgnoreCase("pay")){
                String id=args[2],path="bills."+id;if(!c.data.yaml.getString(path+".actor","").equals(p.getUniqueId().toString())||c.data.yaml.getString(path+".state","").equals("PAID"))throw new IllegalArgumentException("No payable invoice with that ID.");
                transfer(p,c.data.yaml.getString(path+".account"),c.data.yaml.getLong(path+".amount"),true,id);c.say(p,"Invoice paid.");return true;
            }
            var bills=c.data.yaml.getConfigurationSection("bills");if(bills!=null)for(String id:bills.getKeys(false))if(bills.getString(id+".actor","").equals(p.getUniqueId().toString()))c.say(p,id+" "+BigDecimal.valueOf(bills.getLong(id+".amount"),2)+" "+bills.getString(id+".state"));return true;
        }
        if(root.equals("town")&&sub.equals("plotupkeep")){
            Object town=c.api.town(p.getUniqueId());if(town==null||!c.api.leadership(p,town))throw new IllegalArgumentException("Town leadership required.");if(args.length!=3)throw new IllegalArgumentException("/town plotupkeep <plot> <amount>");
            Object manager=c.api.singleton("phonon.nodes.plot.PlotManager"),plot=Native.call(manager,"getPlot",args[1]);if(plot==null||!Native.call(plot,"getTownName").equals(Native.name(town)))throw new IllegalArgumentException("Plot must belong to your town.");
            c.data.yaml.set("plotRates."+args[1].toLowerCase(Locale.ROOT),cents(args[2]));c.data.save();c.say(p,"Recurring plot upkeep rate saved.");return true;
        }
        return false;
    }
    void bill(UUID actor,String account,long amount,long expiry,String reason){String path="bills."+UUID.randomUUID();c.data.yaml.set(path+".actor",actor.toString());c.data.yaml.set(path+".account",account);c.data.yaml.set(path+".amount",amount);c.data.yaml.set(path+".expires",System.currentTimeMillis()+expiry);c.data.yaml.set(path+".state","OPEN");c.data.yaml.set(path+".reason",reason);}
    void audit(CommandSender sender){var pending=c.data.yaml.getConfigurationSection("payments");if(pending!=null)for(String id:pending.getKeys(false))if(pending.getString(id+".state","").equals("PENDING"))c.say(sender,id+" "+pending.getString(id+".actor")+" "+pending.getLong(id+".amount")+" hundredths; deposit="+pending.getBoolean(id+".deposit"));}
    void tick(){
        if(!c.bool("enableEconomy",false))return;
        long now=System.currentTimeMillis();var bills=c.data.yaml.getConfigurationSection("bills");if(bills!=null)for(String id:bills.getKeys(false))if(bills.getString(id+".state","").equals("OPEN")&&now>bills.getLong(id+".expires"))bills.set(id+".state","OVERDUE");
        var rates=c.data.yaml.getConfigurationSection("plotRates");if(rates!=null){Object manager=c.api.singleton("phonon.nodes.plot.PlotManager");for(String name:rates.getKeys(false)){
            if(now-c.data.yaml.getLong("plotLastBill."+name)<c.duration("plotUpkeepBillingPeriod",1209600000))continue;
            Object plot=Native.call(manager,"getPlot",name);if(plot==null)continue;UUID owner=(UUID)Native.call(plot,"getOwnerUuid");Object town=c.api.named(String.valueOf(Native.call(plot,"getTownName")),false);if(owner==null||town==null)continue;
            bill(owner,account(town),rates.getLong(name),c.duration("plotUpkeepBillingExpiry",259200000),"plot "+name);c.data.yaml.set("plotLastBill."+name,now);
        }}
        LocalTime time=LocalTime.parse(c.config.getString("dailyEventTime","12:00"));LocalDateTime local=LocalDateTime.now();String date=local.toLocalDate().toString();if(local.toLocalTime().isBefore(time)||date.equals(c.data.yaml.getString("lastDaily","")))return;
        for(Object town:List.copyOf(c.api.towns().values())){
            long used=((Number)Native.call(town,"getClaimsUsed")).longValue();long cost=(long)Math.ceil(used*c.number("upkeepMultiplier",0)*100);if(cost<0)throw new IllegalArgumentException("Negative upkeep multiplier.");String account=account(town);if(balance(account)>=cost){balance(account,balance(account)-cost);c.data.yaml.set("overdueUpkeep."+Native.id(town),null);}else{
                String overdue="overdueUpkeep."+Native.id(town);long first=c.data.yaml.getLong(overdue,now);c.data.yaml.set(overdue,first);
                if(c.bool("coreSafety.deleteInsolventTowns",false)&&now-first>=Math.max(1,c.number("coreSafety.upkeepGraceDays",7))*86400000){String name=Native.name(town);Native.call(c.api.nodes,"destroyTown",town);c.integrations.log("upkeep-deletion",null,name);if(c.bool("relayTownUpkeepDeletions",true))c.integrations.discord("warChannelName","warMessagePrefix","Town "+name+" was deleted after unpaid upkeep and its configured grace period.");}
            }
        }
        for(Object nation:c.api.nations().values()){
            long used=0;for(Object town:Native.collection(nation,"getTowns"))used+=((Number)Native.call(town,"getClaimsUsed")).longValue();long cost=(long)Math.ceil(used*c.number("upkeepMultiplier",0)*c.number("nationUpkeepMultiplier",0)*100);String account="nation-"+Native.id(nation);if(cost>0&&balance(account)>=cost)balance(account,balance(account)-cost);
        }
        c.data.yaml.set("lastDaily",date);c.data.save();
    }
}
