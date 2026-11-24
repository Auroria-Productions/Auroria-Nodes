package phonon.nodes.map;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import phonon.nodes.Config;

/** Compatibility facade for older NodesMap builds expecting phonon.nodes.map.MapEditApi.
 * All mutations use the modern map plugin's staged-draft safe workflow.
 */
public final class MapEditApi {
    private MapEditApi() {}
    private static Object forward(String method,Class<?>[] parameterTypes,Object... args){
        Plugin map=Bukkit.getPluginManager().getPlugin("milzzynodesmap");
        if(map==null)map=Bukkit.getPluginManager().getPlugin("NodesMap");
        if(map==null||!map.isEnabled())return null;
        try{
            Class<?> bridge=Class.forName("phonon.nodesmap.compat.NodesLink",true,map.getClass().getClassLoader());
            return bridge.getMethod(method,parameterTypes).invoke(null,args);
        }catch(ReflectiveOperationException | LinkageError ignored){return null;}
    }
    public static String worldJson(){
        Object forwarded=forward("worldJson",new Class<?>[0]);
        if(forwarded instanceof String s)return s;
        try{return Files.readString(Config.INSTANCE.getPathWorld(),StandardCharsets.UTF_8);}
        catch(Exception e){return "{\"error\":\"Nodes world JSON unavailable\"}";}
    }
    public static String apply(String body,boolean dryRun,boolean force,String actor){
        Object forwarded=forward("apply",new Class<?>[]{String.class,boolean.class,boolean.class,String.class},body,dryRun,force,actor);
        if(forwarded instanceof String s)return s;
        return "{\"ok\":false,\"error\":\"Enable milzzynodesmap and publish a validated editor draft; direct world writes are disabled\"}";
    }
}
