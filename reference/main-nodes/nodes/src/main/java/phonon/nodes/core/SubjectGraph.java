package phonon.nodes.core;
import java.util.*;

/** Nation IDs remain stable across renames; cycles and duplicate overlords are rejected. */
final class SubjectGraph {
    enum Kind {VASSAL,TRIBUTARY,CONQUERED}
    record Link(UUID subject,UUID overlord,Kind kind,double resources,double economy,long expires){
        Link{if(subject==null||overlord==null||subject.equals(overlord)||kind==null||!Double.isFinite(resources)||!Double.isFinite(economy)||resources<0||resources>100||economy<0||economy>100||expires<0)throw new IllegalArgumentException("Invalid subject agreement.");}
    }
    final Map<UUID,Link> links=new HashMap<>();
    void add(Link link){
        if(links.containsKey(link.subject))throw new IllegalArgumentException("This nation already has an overlord.");
        Set<UUID> seen=new HashSet<>();UUID cursor=link.overlord;
        while(cursor!=null){if(cursor.equals(link.subject)||!seen.add(cursor))throw new IllegalArgumentException("A subject relationship cannot form a cycle.");Link parent=links.get(cursor);cursor=parent==null?null:parent.overlord;}
        links.put(link.subject,link);
    }
    List<Link> children(UUID parent){return links.values().stream().filter(l->l.overlord.equals(parent)).toList();}
    Set<UUID> vassalTree(UUID parent){Set<UUID> result=new HashSet<>();Deque<UUID> q=new ArrayDeque<>();q.add(parent);while(!q.isEmpty()){UUID node=q.removeFirst();for(Link link:children(node))if(link.kind==Kind.VASSAL&&result.add(link.subject))q.add(link.subject);}return result;}
    void expire(long now){links.values().removeIf(l->l.expires>0&&l.expires<=now);}
    static double effectiveRate(double rate,double cap,double distance,double minimum,double ramp){
        if(!Double.isFinite(rate)||!Double.isFinite(cap)||!Double.isFinite(distance)||!Double.isFinite(minimum)||!Double.isFinite(ramp)||rate<0||cap<0||distance<0||minimum<0||ramp<0)throw new IllegalArgumentException("Invalid taxation parameters.");
        double efficiency=Math.max(0,1-Math.max(0,distance-minimum)*ramp);return Math.min(rate,cap)/100.0*efficiency;
    }
}
