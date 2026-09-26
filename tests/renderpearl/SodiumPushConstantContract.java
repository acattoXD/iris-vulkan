package net.irisshaders.iris.vulkan;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.lwjgl.util.spvc.Spvc.*;

/** Exact declaration emitted by the shared transformer, passed through the native patch and real shaderc. */
public final class SodiumPushConstantContract {
 private static int checks;
 private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 private static void ok(int value){check(value==SPVC_SUCCESS,"SPIRV-Cross API success");}
 public static void main(String[] args)throws Exception {
  Path output=Path.of(args[0]);Files.createDirectories(output);
  var patch=IrisVulkanShaderResources.class.getDeclaredMethod("patchSodiumNativeVulkanUniforms",String.class,ShaderKey.class);patch.setAccessible(true);
  Class<?> type=Class.forName("net.irisshaders.iris.vulkan.IrisVulkanGraphicsCompiler$Compiler");var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object compiler=constructor.newInstance();var compile=type.getDeclaredMethod("compile",String.class,String.class,ShaderType.class);compile.setAccessible(true);
  for(boolean shadow:new boolean[]{false,true}) {
   var transformed=TransformPatcher.patchSodium("sodium-pc-contract", "#version 120\nvoid main(){gl_Position=gl_ModelViewProjectionMatrix*gl_Vertex;}",null,null,null,
    "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}",AlphaTests.OFF,new Object2ObjectOpenHashMap<>(),shadow);
   String actual=transformed.get(PatchShaderType.VERTEX);
   Files.writeString(output.resolve(shadow?"shared-shadow.vert.glsl":"shared-terrain.vert.glsl"),actual);
   var block=Pattern.compile("layout\\s*\\([^)]*\\)\\s*uniform\\s+iris_SodiumPushConstants\\s*\\{[^}]+}\\s*;").matcher(actual);
   check(block.find(),"shared transformer emitted its real push-constant UBO declaration");
   String fixture="#version 450 core\n"+block.group()+"\nvoid main(){gl_Position=vec4(iris_RegionOffset+vec3(float(iris_CurrentTime)),float(iris_RegionID)+1.0);}";
   ShaderKey key=shadow?ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID:ShaderKey.SODIUM_TERRAIN_SOLID;
   String patched=(String)patch.invoke(null,fixture,key);
   Files.writeString(output.resolve(shadow?"native-shadow.vert.glsl":"native-terrain.vert.glsl"),patched);
   check(!patched.contains("iris_SodiumPushConstants"),"old UBO binding removed");
   check(patched.contains("layout(push_constant) uniform PC"),"native push storage selected");
   check(patched.equals(patch.invoke(null,patched,key)),"patch is idempotent");
   check(fixture.equals(patch.invoke(null,fixture,ShaderKey.ENTITIES_SOLID)),"non-Sodium shaders unchanged");
   try(SpvModule module=(SpvModule)compile.invoke(compiler,"sodium-pc-contract",patched,ShaderType.VERTEX)) {
    check(module.reflect().pushConstants().size()==1,"one actual push block");
    check(module.reflect().pushConstants().getFirst().size()==20,"exact 20-byte range");
    check(module.reflect().descriptors(SPVC_RESOURCE_TYPE_UNIFORM_BUFFER).isEmpty(),"no UBO descriptor survives");
    reflectMembers(module.spv());
   }
  }
  ((AutoCloseable)compiler).close();
  System.out.println("SODIUM_PUSH_CONSTANT_PASS: "+checks+" checks; actual shared terrain/shadow block -> native SPIR-V; offsets0/12/16, size20, no UBO");
 }
 private static void reflectMembers(ByteBuffer bytes) {
  try(MemoryStack stack=MemoryStack.stackPush()){
   var p=stack.mallocPointer(1);ok(spvc_context_create(p));long context=p.get(0);
   try{
    var words=bytes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();ok(spvc_context_parse_spirv(context,words,words.remaining(),p));long ir=p.get(0);
    ok(spvc_context_create_compiler(context,SPVC_BACKEND_NONE,ir,SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,p));long compiler=p.get(0);
    ok(spvc_compiler_create_shader_resources(compiler,p));long resources=p.get(0);var count=stack.mallocPointer(1);
    ok(spvc_resources_get_resource_list_for_type(resources,SPVC_RESOURCE_TYPE_PUSH_CONSTANT,p,count));check(count.get(0)==1,"exact resource count");
    var block=SpvcReflectedResource.create(p.get(0),1).get(0);long type=spvc_compiler_get_type_handle(compiler,block.base_type_id());
    ok(spvc_compiler_get_declared_struct_size(compiler,type,p));check(p.get(0)==20,"SPIRV-Cross declared struct size");
    String[] names={"iris_RegionOffset","iris_CurrentTime","iris_RegionID"};int[] offsets={0,12,16};var offset=stack.mallocInt(1);
    for(int i=0;i<3;i++){check(names[i].equals(spvc_compiler_get_member_name(compiler,block.base_type_id(),i)),"member name preserved");ok(spvc_compiler_type_struct_member_offset(compiler,type,i,offset));check(offset.get(0)==offsets[i],"member offset agrees with native producer");}
   }finally{spvc_context_destroy(context);}
  }
 }
}
