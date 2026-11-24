import phonon.nodes.storage.JsonStorageLayout;
import java.nio.file.*;
import java.util.logging.Logger;
import com.google.gson.JsonParser;

public final class JsonStorageLayoutTest {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("milzzy-migration-test-");
    try {
      byte[] original = "{\"nextId\":42,\"events\":[{\"id\":41}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Files.write(root.resolve("calendar.json"), original);
      int count = JsonStorageLayout.prepare(root, Logger.getLogger("migration-test"));
      if (count != 1) throw new AssertionError("Expected exactly one migration, got " + count);
      Path target = root.resolve("data/calendar/calendar.json");
      if (!java.util.Arrays.equals(original, Files.readAllBytes(target))) throw new AssertionError("Lost calendar data");
      if (!Files.exists(root.resolve("calendar.json"))) throw new AssertionError("Old backup removed");
      if (JsonStorageLayout.prepare(root, Logger.getLogger("migration-test")) != 0) throw new AssertionError("Not idempotent");
      for (var entry : JsonStorageLayout.FILES.entrySet()) {
        Path data = root.resolve("data").resolve(entry.getValue());
        if (!Files.isDirectory(data.getParent())) throw new AssertionError("Folder not created: " + data);
        if (Files.exists(data)) JsonParser.parseString(Files.readString(data));
      }
      // A new active data file must take precedence over legacy history on a later boot.
      Files.writeString(target, "{\"nextId\":99,\"events\":[]}");
      JsonStorageLayout.prepare(root, Logger.getLogger("migration-test"));
      if (!Files.readString(target).contains("99")) throw new AssertionError("Overwrote active data");
      System.out.println("PASS: JSON migration, preservation, idempotence, and valid templates");
    } finally {
      try (var walk = Files.walk(root)) { walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {try{Files.deleteIfExists(p);}catch(Exception ignored){}}); }
    }
  }
}
