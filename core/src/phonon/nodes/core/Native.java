package phonon.nodes.core;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;

/** Internal access to the fork's existing Kotlin engine; never a second plugin. */
final class Native {
    final JavaPlugin plugin;final Object nodes;final ClassLoader loader;
    Native(JavaPlugin plugin){this.plugin=plugin;loader=plugin.getClass().getClassLoader();nodes=singleton("phonon.nodes.Nodes");}
    Object singleton(String name){try{return Class.forName(name,true,loader).getField("INSTANCE").get(null);}catch(Exception e){throw new IllegalStateException("Missing native class "+name,e);}}
    private static final ClassValue<Map<String, List<Method>>> METHODS = new ClassValue<>() {
        @Override
        protected Map<String, List<Method>> computeValue(Class<?> type) {
            Map<String, List<Method>> methods = new HashMap<>();
            for (Method method : type.getMethods()) {
                String name = method.getName();
                methods.computeIfAbsent(name, ignored -> new ArrayList<>()).add(method);
                int separator = name.indexOf('-');
                if (separator > 0) {
                    methods.computeIfAbsent(name.substring(0, separator), ignored -> new ArrayList<>()).add(method);
                } else if (name.endsWith("$nodes")) {
                    methods.computeIfAbsent(name.substring(0, name.length() - "$nodes".length()), ignored -> new ArrayList<>()).add(method);
                }
            }
            return methods;
        }
    };

    static Object call(Object target, String name, Object... args) {
        if (target == null) return null;
        var methods = METHODS.get(target.getClass());
        Method selected;
        if (name.contains("$nodes")) {
            // Preserve the original precedence between an exact internal name and its public form.
            selected = findMethod(methods.get(name.replace("$nodes", "")), name, args);
        } else {
            selected = findMethod(methods.get(name), name, args);
        }
        if (selected == null) {
            throw new IllegalStateException("Native method missing: " + target.getClass().getName() + "." + name);
        }
        try {
            return selected.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Native operation failed: " + name, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name, e);
        }
    }

    private static Method findMethod(List<Method> candidates, String name, Object[] args) {
        if (candidates == null) return null;
        for (Method method : candidates) {
            if (method.getParameterCount() != args.length) continue;
            String actual = method.getName();
            if (name.contains("$nodes") && !actual.equals(name) && !actual.equals(name.replace("$nodes", ""))) continue;
            boolean compatible = true;
            if (args.length != 0) {
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && !box(types[i]).isInstance(args[i])) {
                        compatible = false;
                        break;
                    }
                }
            }
            if (compatible) return method;
        }
        return null;
    }

    static Class<?> box(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        return type;
    }

    static UUID id(Object o){return o==null?null:(UUID)call(o,"getUuid");}
    static String name(Object o){return o==null?"None":String.valueOf(call(o,"getName"));}
    static Collection<?> collection(Object o,String getter){Object value=call(o,getter);return value instanceof Collection<?> c?c:List.of();}
    @SuppressWarnings("unchecked") Map<String,Object> towns(){return (Map<String,Object>)call(nodes,"getTowns$nodes");}
    @SuppressWarnings("unchecked") Map<String,Object> nations(){return (Map<String,Object>)call(nodes,"getNations$nodes");}
    Object resident(UUID id){return call(nodes,"getResidentFromUUID",id);}
    Object town(UUID player){return call(resident(player),"getTown");}
    Object nation(UUID player){return call(resident(player),"getNation");}
    Object byId(UUID id,boolean nation){for(Object o:(nation?nations():towns()).values())if(id.equals(id(o)))return o;return null;}
    Object named(String name,boolean nation){return (nation?nations():towns()).values().stream().filter(o->name(o).equalsIgnoreCase(name)).findFirst().orElse(null);}
    Object capital(Object nation){return call(nation,"getCapital");}
    boolean leadership(Player p,Object town){Object r=resident(p.getUniqueId());return r!=null&&(Objects.equals(call(town,"getLeader"),r)||collection(town,"getOfficers").contains(r)||collection(town,"getGenerals").contains(r));}
    boolean nationLeader(Player p,Object nation){return nation!=null&&Objects.equals(town(p.getUniqueId()),capital(nation))&&leadership(p,capital(nation));}
    void save(){call(nodes,"setNeedsSave$nodes",true);call(nodes,"saveWorld$nodes",false,false);}
    void dirty(Object town){call(town,"needsUpdate");call(nodes,"setNeedsSave$nodes",true);}
    Object income(Object town){return call(town,"getIncome");}
    @SuppressWarnings("unchecked") Map<Material,Integer> storage(Object town){return (Map<Material,Integer>)call(income(town),"getStorage");}
    void put(Object town,Material material,int amount){call(income(town),"add",material,amount,0);dirty(town);}
    Object territory(Location location) {
        return ((phonon.nodes.Nodes) nodes).getTerritoryFromBlock(location.getBlockX(), location.getBlockZ());
    }
    Object owner(Location l){return call(territory(l),"getTown");}
    static Location spawn(Object town){return (Location)call(town,"getSpawnpoint");}
    boolean enemies(Object a,Object b){return a!=null&&b!=null&&collection(a,"getEnemies").contains(b);}
    void enemy(Object a,Object b,boolean add){Object result=call(nodes,add?"addEnemy":"removeEnemy",a,b);if(result!=null&&result.getClass().getName().contains("Result$Failure"))throw new IllegalArgumentException("Native diplomacy rejected the change: "+result);}
    void ally(Object a,Object b){Object result=call(nodes,"addAlly",a,b);if(result!=null&&result.getClass().getName().contains("Result$Failure"))throw new IllegalArgumentException("Native alliance rejected the change.");}
}
