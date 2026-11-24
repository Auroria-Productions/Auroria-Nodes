package phonon.nodes.keywar;

import java.util.*;

/** Persistent game-state rules; no Bukkit dependencies so results can be tested independently. */
public final class KeyWarModel {
    private KeyWarModel() {}
    public static final String SETUP = "PENDING", LIVE = "LIVE", FINISHED = "FINISHED", CEASEFIRE = "CEASEFIRE";
    public static final String ATTACKERS = "attackers", DEFENDERS = "defenders";
    public static final String ALIVE = "ALIVE", DEAD = "DEAD", ABANDONED = "ABANDONED";

    public static final class Data {
        public int schema = 2;
        public final LinkedHashMap<String, War> wars = new LinkedHashMap<>();
    }
    public static final class War {
        public String id, attacker, defender;
        public final LinkedHashMap<String, Point> points = new LinkedHashMap<>();
        public int sequence = 0;
        public War() {}  // Gson
        public War(String id, String attacker, String defender) {
            this.id = id; this.attacker = attacker; this.defender = defender;
        }
    }
    public static final class Member {
        public String uuid, name, side, state = ALIVE;
        public long sickUntil = 0L;
        public Member() {}
        public Member(UUID id, String name, String side) {
            this.uuid = id.toString(); this.name = name; this.side = side;
        }
        public boolean alive() { return ALIVE.equals(state); }
    }
    public static final class Point {
        public String id, name, type = "Stronghold", proposedBy, world, status = SETUP, winner = "";
        public double x, y, z;
        public int radius = 96;
        public long startsAt = 0, endsAt = 0;
        public String peaceTerms = "";
        public final LinkedHashMap<String, Member> members = new LinkedHashMap<>();
        public final LinkedHashSet<String> ceasefireVotes = new LinkedHashSet<>();
        public Point() {}
        public Point(String id, String name, String proposedBy, String world, double x, double y, double z, int radius) {
            this.id=id; this.name=name; this.proposedBy=proposedBy; this.world=world;
            this.x=x; this.y=y; this.z=z; this.radius=radius;
        }
        public boolean inArea(String dimension, double px, double pz) {
            return Objects.equals(world, dimension) && (px-x)*(px-x)+(pz-z)*(pz-z) <= (double)radius*radius;
        }
        public int alive(String side) {
            int count=0;
            for(Member member: members.values()) if(member.alive() && member.side.equals(side)) count++;
            return count;
        }
        public int eliminated() {
            int count=0;
            for(Member member: members.values()) if (!member.alive()) count++;
            return count;
        }
        public void enrol(UUID id, String name, String side) {
            requireSetup();
            if (!ATTACKERS.equals(side) && !DEFENDERS.equals(side)) throw new IllegalArgumentException("Unknown side");
            members.put(id.toString(), new Member(id, name, side));
        }
        public void remove(UUID id) {
            requireSetup(); members.remove(id.toString());
        }
        public void start(long time) {
            requireSetup();
            if (alive(ATTACKERS) < 1 || alive(DEFENDERS) < 1)
                throw new IllegalStateException("Both nations must enrol at least one fighter");
            status=LIVE; startsAt=time;
        }
        public boolean eliminate(UUID id, String reason, long time, int sicknessSeconds) {
            if (!LIVE.equals(status)) return false;
            Member member=members.get(id.toString());
            if(member == null || !member.alive()) return false;
            if(!DEAD.equals(reason) && !ABANDONED.equals(reason)) throw new IllegalArgumentException("Unknown elimination reason");
            member.state=reason;
            if(DEAD.equals(reason)) member.sickUntil = time + Math.max(0, sicknessSeconds)*1000L;
            evaluate(time);
            return true;
        }
        private void evaluate(long time) {
            if(!LIVE.equals(status)) return;
            if(alive(DEFENDERS) == 0) finish(ATTACKERS, time);
            else if(alive(ATTACKERS) == 0) finish(DEFENDERS, time);
        }
        public boolean vote(String nation, String attackingNation, String defendingNation, long time) {
            if(!LIVE.equals(status)) throw new IllegalStateException("Battle is not live");
            String side = nation.equalsIgnoreCase(attackingNation) ? ATTACKERS :
                nation.equalsIgnoreCase(defendingNation) ? DEFENDERS : "";
            if(side.isEmpty()) throw new IllegalArgumentException("Nation is not in this war");
            ceasefireVotes.add(side);
            if(ceasefireVotes.contains(ATTACKERS) && ceasefireVotes.contains(DEFENDERS)) {
                status=CEASEFIRE; winner="draw"; endsAt=time; return true;
            }
            return false;
        }
        public void finish(String winner, long time) {
            if(!LIVE.equals(status)) throw new IllegalStateException("Battle is not live");
            if(!ATTACKERS.equals(winner) && !DEFENDERS.equals(winner) && !"draw".equals(winner))
                throw new IllegalArgumentException("Unknown outcome");
            this.winner=winner; status=FINISHED; endsAt=time;
        }
        private void requireSetup() {
            if(!SETUP.equals(status)) throw new IllegalStateException("Battle setup is closed");
        }
    }
}
