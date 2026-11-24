import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.commons.*;

class Audit {
  static Map<String,ClassNode> read(String path) throws Exception {
    Map<String,ClassNode> classes = new HashMap<>();
    try(var jar=new JarFile(path)) {
      for(var e:Collections.list(jar.entries())) if(e.getName().endsWith(".class")) {
        var c=new ClassNode();
        new ClassReader(jar.getInputStream(e)).accept(c,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        classes.put(c.name,c);
      }
    }
    return classes;
  }
  static boolean resolves(Map<String,ClassNode> api,String owner,String name,String desc,boolean method) {
    if(owner==null)return false;
    var c=api.get(owner);
    if(c==null) {
      if(!owner.startsWith("java/"))return false;
      try {
        var type=Class.forName(owner.replace('/','.'));
        if(method) return Arrays.stream(type.getMethods()).anyMatch(m ->
          m.getName().equals(name)&&Type.getMethodDescriptor(m).equals(desc));
        return Arrays.stream(type.getFields()).anyMatch(f ->
          f.getName().equals(name)&&Type.getDescriptor(f.getType()).equals(desc));
      } catch(ReflectiveOperationException e) {return false;}
    }
    if(method?c.methods.stream().anyMatch(m->m.name.equals(name)&&m.desc.equals(desc)):c.fields.stream().anyMatch(f->f.name.equals(name)&&f.desc.equals(desc))) return true;
    if(name.equals("<init>"))return false;
    return resolves(api,c.superName,name,desc,method)||c.interfaces.stream().anyMatch(i->resolves(api,i,name,desc,method));
  }
  public static void main(String[] args)throws Exception {
    var plugin=read(args[0]);
    var api=new HashMap<String,ClassNode>();
    for(int i=1;i<args.length;i++) api.putAll(read(args[i]));
    api.putAll(plugin);
    Map<String,Set<String>> missing=new TreeMap<>();
    for(var c:plugin.values()) if(c.name.startsWith("phonon/")) {
      c.accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
        @Override public String map(String name) {
          if (name.startsWith("net/minecraft/") || name.startsWith("org/bukkit/craftbukkit/")
              || (name.startsWith("org/bukkit/") || name.startsWith("io/papermc/paper/")
                  || name.startsWith("phonon/")) && !api.containsKey(name)) {
            missing.computeIfAbsent("missing type "+name,k->new TreeSet<>()).add(c.name);
          }
          return name;
        }
      }));
      for(var m:c.methods)for(var i:m.instructions) {
      String owner,name,desc; boolean method=i instanceof MethodInsnNode;
      if(i instanceof MethodInsnNode n){owner=n.owner;name=n.name;desc=n.desc;}
      else if(i instanceof FieldInsnNode n){owner=n.owner;name=n.name;desc=n.desc;}
      else continue;
      if(!owner.startsWith("org/bukkit/")&&!owner.startsWith("io/papermc/paper/")&&!owner.startsWith("net/minecraft/")&&!owner.startsWith("com/destroystokyo/paper/")
        &&!owner.startsWith("phonon/")&&!owner.startsWith("kotlin/")
        &&!owner.startsWith("net/kyori/")&&!owner.startsWith("com/github/retrooper/")
        &&!owner.startsWith("net/milkbowl/vault/"))continue;
      if(!resolves(api,owner,name,desc,method)) missing.computeIfAbsent(owner+"."+name+" "+desc,k->new TreeSet<>()).add(c.name+"."+m.name);
    }
    }
    missing.forEach((k,v)->System.out.println(k+" <- "+String.join(", ",v)));
    if (!missing.isEmpty()) System.exit(1);
  }
}
