import java.util.*;
import phonon.nodes.refinery.legacy1182.RefineryMath;
public final class RefineryMathTest {
  private static void expect(boolean test,String msg){if(!test)throw new AssertionError(msg);}
  public static void main(String[] args){
    var recipe = new RefineryMath.Recipe("steel",Map.of("IRON_INGOT",4,"COAL",16),Map.of("NETHERITE_INGOT",1),1800_000);
    var count=new HashMap<>(Map.of("IRON_INGOT",8,"COAL",32));var output=new HashMap<String,Integer>();
    long progress=RefineryMath.step(0,600_000,recipe,count,output);
    expect(progress==600_000 && output.isEmpty(),"premature production");
    progress=RefineryMath.step(progress,1_200_000,recipe,count,output);
    expect(progress==0 && count.get("IRON_INGOT")==4 && count.get("COAL")==16 && output.get("NETHERITE_INGOT")==1,"one batch material exchange");
    progress=RefineryMath.step(0,900_000,recipe,count,output);
    count.put("COAL",0);
    progress=RefineryMath.step(progress,200_000,recipe,count,output);
    expect(progress==0,"progress must reset when a recipe lacks input");
    expect(output.get("NETHERITE_INGOT")==1,"no phantom recipe output");
    System.out.println("PASS: Phonon 1.18.2 refinery recipe model, timers and input consumption");
  }
}
