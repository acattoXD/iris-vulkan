package net.irisshaders.iris.mixin;
import java.nio.file.*;
import java.util.*;

public final class EarlyBackendSelectionTest {
 private static int checks;
 private static void check(boolean expected,String[] args,Map<String,String> options,String label){checks++;var selected=IrisEarlyBackendSelection.resolve(args,options);if(selected.vulkan()!=expected)throw new AssertionError(label+" -> "+selected);}
 public static void main(String[] args)throws Exception{
  Map<String,String> vulkan=Map.of("version","5023","preferredGraphicsBackend","\"vulkan\"","startedCleanly","true");
  Map<String,String> crashed=Map.of("version","5023","preferredGraphicsBackend","\"vulkan\"","startedCleanly","false");
  Map<String,String> legacy=Map.of("version","4790","preferredGraphicsBackend","\"vulkan\"");
  check(true,new String[0],vulkan,"persisted Vulkan");
  check(false,new String[0],crashed,"crash fallback");
  check(false,new String[0],legacy,"migration reset");
  check(false,new String[0],Map.of(),"fresh instance default");
  check(false,new String[0],Map.of("version","5023","preferredGraphicsBackend","\"default\""),"default is GL first");
  check(false,new String[0],Map.of("version","5023","preferredGraphicsBackend","\"opengl\""),"persisted GL");
  check(false,new String[0],Map.of("version","5023","preferredGraphicsBackend","\"not-vulkan\""),"invalid substring not Vulkan");
  check(false,new String[0],Map.of("version","5023","preferredGraphicsBackend","\"VULKAN\""),"option enum case sensitive");
  check(true,new String[0],Map.of("version","4892","preferredGraphicsBackend","vulkan"),"migration boundary/lenient old string");
  check(false,new String[0],Map.of("version","4891","preferredGraphicsBackend","vulkan"),"older migration boundary");
  check(false,new String[0],Map.of("version","invalid","preferredGraphicsBackend","vulkan"),"invalid data version resets");
  for(Map<String,String> options:List.of(vulkan,crashed,legacy,Map.of("preferredGraphicsBackend","\"default\""))){
   check(true,new String[]{"--graphicsBackend","VULKAN"},options,"explicit Vulkan wins");
   check(true,new String[]{"--graphicsBackend=vulkan"},options,"equals Vulkan wins");
   check(true,new String[]{"-graphicsBackend","Vulkan"},options,"alternate long option");
   check(false,new String[]{"--graphicsBackend","OPENGL"},options,"explicit GL wins");
   check(false,new String[]{"--graphicsBackend","DEFAULT"},options,"forced default wins");
  }
  check(false,new String[]{"--","--graphicsBackend","VULKAN"},Map.of(),"end of options");
  for(String[] invalid:List.of(new String[]{"--graphicsBackend"},new String[]{"--graphicsBackend","invalid"})){
   try{IrisEarlyBackendSelection.resolve(invalid,vulkan);throw new AssertionError("Invalid argument accepted");}catch(IllegalArgumentException expected){checks++;}
  }
  Path dir=Files.createTempDirectory("iris-backend-selection");
  try{
   Files.writeString(dir.resolve("options.txt"),"version:5023\npreferredGraphicsBackend:\"default\"\nstartedCleanly:false\n");
   String before=Files.readString(dir.resolve("options.txt"));
   checks++;if(!IrisEarlyBackendSelection.read(dir,new String[]{"--graphicsBackend","VULKAN"}).vulkan())throw new AssertionError("Actual options reader ignores launch override");
   checks++;if(!before.equals(Files.readString(dir.resolve("options.txt"))))throw new AssertionError("Resolver mutated options");
  }finally{Files.deleteIfExists(dir.resolve("options.txt"));Files.delete(dir);}
  System.out.println("PASS "+checks+" early backend argument/option/migration checks; no Minecraft class initialization");
 }
}
