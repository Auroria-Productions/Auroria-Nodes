package phonon.nodes.refinery.legacy1182;

import java.util.*;

/** Pure conversion model mirroring the recipe progress and inventory flow of Phonon's 1.18.2 refinery. */
public final class RefineryMath {
    private RefineryMath() {}
    public record Recipe(String name, Map<String,Integer> inputs, Map<String,Integer> outputs, long timeMs) {
        public Recipe {
            Objects.requireNonNull(name);
            inputs = Map.copyOf(inputs); outputs = Map.copyOf(outputs);
            if (timeMs <= 0 || inputs.isEmpty() || outputs.isEmpty()) throw new IllegalArgumentException("Invalid refinery recipe");
            for (int n:inputs.values()) if(n<=0)throw new IllegalArgumentException("Nonpositive input");
            for (int n:outputs.values()) if(n<=0)throw new IllegalArgumentException("Nonpositive output");
        }
    }
    public static Map<String,Integer> count(List<Map<String,Integer>> stacks) {
        Map<String,Integer> out = new HashMap<>();
        for (var item:stacks) for(var e:item.entrySet())out.merge(e.getKey(),e.getValue(),Integer::sum);
        return out;
    }
    public static boolean contains(Map<String,Integer> available, Map<String,Integer> required) {
        for(var e:required.entrySet()) if(available.getOrDefault(e.getKey(),0)<e.getValue())return false;
        return true;
    }
    /** Tick a recipe; consume once and reset at completion; no offline catch-up production. */
    public static long step(long prior, long delta, Recipe recipe, Map<String,Integer> remaining, Map<String,Integer> produced) {
        if(!contains(remaining,recipe.inputs))return 0;
        long next=Math.max(0,prior)+Math.max(0,delta);
        if(next >= recipe.timeMs){
            for(var e:recipe.inputs.entrySet()) remaining.merge(e.getKey(),-e.getValue(),Integer::sum);
            for(var e:recipe.outputs.entrySet())produced.merge(e.getKey(),e.getValue(),Integer::sum);
            return 0;
        }
        return next;
    }
}
