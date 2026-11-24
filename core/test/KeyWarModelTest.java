import phonon.nodes.keywar.KeyWarModel;
import java.util.UUID;

public final class KeyWarModelTest {
    static void yes(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var u1=UUID.randomUUID(); var u2=UUID.randomUUID(); var u3=UUID.randomUUID();
        var p=new KeyWarModel.Point("p1","Castle","defenders","world",100,65,100,80);
        p.enrol(u1,"A","attackers");p.enrol(u2,"D","defenders");p.enrol(u3,"D2","defenders");
        yes(p.inArea("world",100,100)&&!p.inArea("nether",100,100),"dimension");
        p.start(1_000);yes(p.status.equals("LIVE"),"start");
        yes(p.eliminate(u2,"DEAD",2_000,300),"first death");
        yes(!p.eliminate(u2,"DEAD",2_001,300),"one life");
        yes(p.members.get(u2.toString()).sickUntil==302_000,"sickness expiration");
        yes(p.eliminate(u3,"ABANDONED",3_000,300),"abandonment");
        yes(p.status.equals("FINISHED") && p.winner.equals("attackers"),"defender elimination ends battle");
        yes(!p.eliminate(u1,"DEAD",4_000,300),"no play after finish");
        var p2=new KeyWarModel.Point("p2","Bridge","attackers","world",0,65,0,80);
        p2.enrol(u1,"A","attackers");p2.enrol(u2,"D","defenders");p2.start(1);
        yes(!p2.vote("North","North","South",2),"one vote not enough");
        yes(p2.vote("South","North","South",3),"two votes");
        yes(p2.status.equals("CEASEFIRE") && p2.endsAt==3,"ceasefire");
        var p3=new KeyWarModel.Point("p3","Harbour","defenders","world",0,65,0,60);
        p3.enrol(u1,"A","attackers");p3.enrol(u2,"D","defenders");p3.start(1);
        p3.eliminate(u1,"DEAD",2,0);
        yes("defenders".equals(p3.winner),"defender victory");
        try{p3.enrol(u3,"Late","attackers");throw new AssertionError("late join allowed");}
        catch(IllegalStateException expected){}
        var data=new KeyWarModel.Data();
        var war=new KeyWarModel.War("siege", "North", "South");
        war.points.put(p.id,p);data.wars.put(war.id,war);
        var gson=new com.google.gson.Gson();
        var reloaded=gson.fromJson(gson.toJson(data),KeyWarModel.Data.class);
        var fighter=reloaded.wars.get("siege").points.get("p1").members.get(u2.toString());
        yes(KeyWarModel.DEAD.equals(fighter.state),"dead fighter must remain eliminated after persistent JSON reload");
        yes(fighter.sickUntil==302_000,"sickness must persist after restart");
        System.out.println("PASS: one-life, sickness, regions, setup, ceasefire, abandonment, both win paths, JSON reload");
    }
}
