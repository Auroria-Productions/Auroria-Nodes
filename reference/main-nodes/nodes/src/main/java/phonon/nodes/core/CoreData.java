package phonon.nodes.core;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

final class CoreData {
    final Path file;final YamlConfiguration yaml;
    CoreData(Path file)throws Exception{this.file=file;if(Files.exists(file)){yaml=new YamlConfiguration();yaml.load(file.toFile());}else yaml=new YamlConfiguration();}
    synchronized void save(){
        try{Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"core-",".tmp");
            try{Files.writeString(temp,yaml.saveToString(),StandardCharsets.UTF_8);try(var ch=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){ch.force(true);}if(Files.exists(file))Files.copy(file,Path.of(file+".bak"),StandardCopyOption.REPLACE_EXISTING);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}
            finally{Files.deleteIfExists(temp);}
        }catch(IOException e){throw new IllegalStateException("Could not persist Nodes core state",e);}
    }
}
