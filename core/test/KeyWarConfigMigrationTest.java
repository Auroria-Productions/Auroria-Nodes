import phonon.nodes.keywar.KeyWarConfigMigration;
import java.nio.file.Files;
import java.nio.file.Path;

public final class KeyWarConfigMigrationTest {
    private static void require(boolean x,String err){if(!x)throw new AssertionError(err);}
    private static void caseRun(String initial, String... checks) throws Exception {
        Path folder=Files.createTempDirectory("keywar-migrate-test-");
        try {
            Path f=folder.resolve("config.yml");Files.writeString(f,initial);
            require(KeyWarConfigMigration.ensure(f),"first migration must update");
            String written=Files.readString(f);
            for(String c:checks)require(written.contains(c),"missing: "+c);
            require(Files.readString(folder.resolve("config.yml.before-keywar")).equals(initial),"backup differs");
            require(!KeyWarConfigMigration.ensure(f),"migration not idempotent");
            require(Files.readString(f).equals(written),"second migration changed file");
        }finally{try(var files=Files.list(folder)){for(Path f:files.toList())Files.delete(f);}Files.delete(folder);}
    }
    public static void main(String[] a) throws Exception {
        caseRun("foo: 1\nbar:\n  a: 2\n", "war:\n  enabled: true\n  keyPointWars:\n    enabled: true", "warSicknessSeconds: 600");
        caseRun("foo: 1\nwar:\n  enabled: false\n  systems:\n    flagWar:\n      enabled: true\nbar: baz\n", "war:\n  enabled: false", "  keyPointWars:\n    enabled: true", "bar: baz");
        caseRun("war:\n  enabled: true\n  keyPointWars:\n    enabled: false\n    defaultRadius: 180\nfoo: done\n", "    enabled: false", "    defaultRadius: 180", "    maxRadius: 256", "foo: done");
        caseRun("war:\n  keyPointWars:\n    enabled: false\n", "  enabled: true", "    enabled: false", "    abandonOnExit: true");
        caseRun("# example\r\nfoo: bar\r\n", "war:\r\n  enabled: true");
        Path folder=Files.createTempDirectory("keywar-invalid-");
        try {
            Path f=folder.resolve("config.yml");Files.writeString(f,"war: false\n");
            try {KeyWarConfigMigration.ensure(f);throw new AssertionError("must reject non-mapping war section");}
            catch(IllegalStateException expected){}
            require(Files.readString(f).equals("war: false\n"),"invalid YAML modified");
        } finally {for(Path f:Files.list(folder).toList())Files.delete(f);Files.delete(folder);}
        System.out.println("PASS: KeyWar config migration preserves existing settings, comments and backups");
    }
}
