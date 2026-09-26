package net.irisshaders.iris.vulkan;
import com.mojang.renderpearl.api.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.*;
import com.mojang.renderpearl.backend.api.*;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.lang.reflect.*;
import java.util.*;

public final class GraphicsCompilerContract {
 private static int checks;
 private static Object compiler;
 private static Method compile;
 private static SpvModule shader(String name,String text,ShaderType type) throws Exception {
  try {return (SpvModule)compile.invoke(compiler,name,text,type);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
 }
 private static RenderPipeline pipeline(int pushSize) {
  return RenderPipeline.builder().withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("iris","contract")).withVertexShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("iris","contract")).withFragmentShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("iris","contract"))
   .withColorTargetState(ColorTargetState.DEFAULT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
   .withVertexBinding(0, VertexFormat.builder(0).addAttribute("Position",GpuFormat.RGB32_FLOAT).build())
   .withBindGroupLayout(BindGroupLayout.builder().withUniform("Globals",UniformType.UNIFORM_BUFFER).withUniform("tex",UniformType.COMBINED_IMAGE_SAMPLER).build())
   .withPushConstantSize(pushSize).build();
 }
 private static void check(boolean value,String message){++checks;if(!value)throw new AssertionError(message);}
 private static void reject(String vertex,String fragment, int pushSize, String message) throws Exception {
  try(var v=shader("reject-v",vertex,ShaderType.VERTEX);var f=shader("reject-f",fragment,ShaderType.FRAGMENT)) {
   try{IrisVulkanGraphicsCompiler.prepare(pipeline(pushSize),"reject",v,f,IrisVulkanStorageReflection.reflect(v.spv(),Set.of()),IrisVulkanStorageReflection.reflect(f.spv(),Set.of()));throw new AssertionError("Accepted "+message);}catch(ShaderCompileException expected){++checks;}
  }
 }
 public static void main(String[]args)throws Exception{
  Class<?> type=Class.forName("net.irisshaders.iris.vulkan.IrisVulkanGraphicsCompiler$Compiler");Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);compiler=ctor.newInstance();compile=type.getDeclaredMethod("compile",String.class,String.class,ShaderType.class);compile.setAccessible(true);
  String vertex="""
  #version 450 core
  in vec3 Position;
  out vec2 uv;
  flat out int material;
  layout(std140) uniform Globals { vec4 tint; };
  layout(push_constant) uniform PC { vec4 offset; int section; };
  layout(std430,binding=3) buffer Voxels { uint voxel[]; };
  layout(rgba8) uniform image2D img;
  uniform sampler3D volume;
  void main(){ uv=Position.xy; material=section; gl_Position=vec4(Position,1)+offset; voxel[0]=7u; imageStore(img,ivec2(0),texture(volume,vec3(0))+tint); }
  """;
  String fragment="""
  #version 450 core
  flat in int material;
  in vec2 uv;
  uniform sampler2D tex;
  layout(std430,binding=3) buffer Voxels { uint voxel[]; };
  layout(std140) uniform Globals { vec4 tint; };
  layout(rgba8) uniform image2D img;
  uniform sampler3D volume;
  layout(location=0) out vec4 color;
  void main(){color=texture(tex,uv)*tint+texture(volume,vec3(0))+imageLoad(img,ivec2(0))+float(material+int(voxel[0]));}
  """;
  try(var v=shader("vertex",vertex,ShaderType.VERTEX);var f=shader("fragment",fragment,ShaderType.FRAGMENT)){
   var prepared=IrisVulkanGraphicsCompiler.prepare(pipeline(20),"contract",v,f,IrisVulkanStorageReflection.reflect(v.spv(),Set.of()),IrisVulkanStorageReflection.reflect(f.spv(),Set.of()));
   check(prepared.createInfo().pushConstantsSize()==20,"push ABI");
   check(prepared.createInfo().uniforms().size()==2,"normal descriptors");
   check(prepared.storageBindings().size()==3,"image/SSBO/3D resources retained");
   check(prepared.storageBindings().stream().anyMatch(b->b.kind()==IrisVulkanStorageReflection.Kind.STORAGE_BUFFER&&b.sourceBinding()==3),"pack SSBO index retained");
   check(prepared.createInfo().vertexBuffers().getFirst().stride()==12,"producer stride");
   check(prepared.createInfo().attribBindings().size()==1,"actual used input count");
   for(var input:f.reflect().inputs())check(v.reflect().outputs().stream().anyMatch(o->o.name().equals(input.name())&&o.location()==input.location()),"varying linked by name");
   for(var binding:prepared.storageBindings()){
    for(var module:List.of(v,f))for(var descriptor:module.reflect().descriptors())if(descriptor.name().equals(binding.name())){check(descriptor.binding()==binding.binding(),"advanced binding rewritten");check(descriptor.descriptorSetIndex()==0,"descriptor set");}
   }
  }
  // Rethinking Voxels prepare shaders use built-in writable aliases from a
  // graphics fragment stage. These declarations intentionally cover the
  // float aliases (colorimg3/8) and the unsigned integer alias (colorimg9).
  // The old graphics bridge reflected these as advanced resources but had no
  // allocation/binding path for the aliases at draw time.
  String rethinkingVertex=vertex.replace("layout(rgba8) uniform image2D img;",
    "layout(rgba16f) uniform image2D colorimg3;\n  layout(rgba16f) uniform image2D colorimg8;\n  layout(r32ui) uniform uimage2D colorimg9;");
  rethinkingVertex=rethinkingVertex.replace("imageStore(img,", "imageStore(colorimg3,");
  String rethinkingFragment=fragment.replace("layout(rgba8) uniform image2D img;",
    "layout(rgba16f) uniform image2D colorimg3;\n  layout(rgba16f) uniform image2D colorimg8;\n  layout(r32ui) uniform uimage2D colorimg9;")
    .replace("imageLoad(img,ivec2(0))", "imageLoad(colorimg8,ivec2(0)) + imageLoad(colorimg3,ivec2(0)) + vec4(imageLoad(colorimg9,ivec2(0)))");
  try(var v=shader("rethinking-vertex",rethinkingVertex,ShaderType.VERTEX);var f=shader("rethinking-fragment",rethinkingFragment,ShaderType.FRAGMENT)){
   var reflectedV=IrisVulkanStorageReflection.reflect(v.spv(),Set.of());
   var reflectedF=IrisVulkanStorageReflection.reflect(f.spv(),Set.of());
   var prepared=IrisVulkanGraphicsCompiler.prepare(pipeline(20),"rethinking",v,f,reflectedV,reflectedF);
   check(prepared.storageBindings().stream().filter(b->b.kind()==IrisVulkanStorageReflection.Kind.STORAGE_IMAGE).count()==3,
     "Rethinking aliases reflect as three graphics storage images");
   for(String alias:List.of("colorimg3","colorimg8","colorimg9")){
    check(prepared.storageBindings().stream().anyMatch(b->b.name().equals(alias)&&b.kind()==IrisVulkanStorageReflection.Kind.STORAGE_IMAGE),
      "Rethinking alias retained: "+alias);
    for(var module:List.of(v,f)) for(var descriptor:module.reflect().descriptors()) if(descriptor.name().equals(alias)){
      var binding=prepared.storageBindings().stream().filter(candidate->candidate.name().equals(alias)).findFirst().orElseThrow();
      check(descriptor.binding()==binding.binding()&&descriptor.descriptorSetIndex()==0,"Rethinking alias descriptor rebound: "+alias);
    }
   }
  }
  reject(vertex,fragment,0,"missing push range");
  reject(vertex,fragment.replace("in vec2 uv;","in vec3 uv;").replace("texture(tex,uv)","texture(tex,uv.xy)"),20,"varying width mismatch");
  reject(vertex,fragment.replace("in vec2 uv;","in vec2 wrong;").replace("texture(tex,uv)","texture(tex,wrong)"),20,"missing varying");
  reject(vertex,fragment.replace("uniform sampler2D tex;","uniform sampler2D unknown;").replace("texture(tex,uv)","texture(unknown,uv)"),20,"missing declaration");
  reject(vertex,fragment.replace("uniform sampler3D volume;","uniform sampler2D volume;").replace("texture(volume,vec3(0))","texture(volume,vec2(0))"),20,"cross-stage sampler dimensions");
  String texelVertex="#version 450 core\nin vec3 Position; out vec2 uv; void main(){uv=Position.xy;gl_Position=vec4(Position,1);}";
  String texelFragment="#version 450 core\nin vec2 uv; uniform isamplerBuffer u_SectionTimeInfo; layout(location=0) out vec4 color; void main(){color=vec4(float(texelFetch(u_SectionTimeInfo,3).r),uv,1);}";
  for(boolean correct:new boolean[]{true,false}){
   var sectionLayout=correct?BindGroupLayout.builder().withUniform("u_SectionTimeInfo",UniformType.TEXEL_BUFFER,GpuFormat.R32_SINT).build()
     :BindGroupLayout.builder().withUniform("u_SectionTimeInfo",UniformType.COMBINED_IMAGE_SAMPLER).build();
   var sectionPipeline=RenderPipeline.builder().withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("iris","section-time-contract"))
     .withVertexShader("contract").withFragmentShader("contract").withColorTargetState(ColorTargetState.DEFAULT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
     .withVertexBinding(0,VertexFormat.builder(0).addAttribute("Position",GpuFormat.RGB32_FLOAT).build()).withBindGroupLayout(sectionLayout).build();
   try(var v=shader("section-v",texelVertex,ShaderType.VERTEX);var f=shader("section-f",texelFragment,ShaderType.FRAGMENT)){
    var reflected=f.reflect().descriptors().stream().filter(d->d.name().equals("u_SectionTimeInfo")).findFirst().orElseThrow();
    check(reflected.resourceType()==org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,"isamplerBuffer uses sampled-image reflection category");
    check(reflected.type().dimensions()==org.lwjgl.util.spvc.Spv.SpvDimBuffer,"isamplerBuffer dimension is Buffer");
    try{
     var prepared=IrisVulkanGraphicsCompiler.prepare(sectionPipeline,"section-time",v,f,IrisVulkanStorageReflection.reflect(v.spv(),Set.of()),IrisVulkanStorageReflection.reflect(f.spv(),Set.of()));
     check(correct,"incorrect combined-image declaration rejected");
     check(prepared.createInfo().uniforms().size()==1,"one active texel buffer");
     var uniform=prepared.createInfo().uniforms().getFirst();
     check(uniform.name().equals("u_SectionTimeInfo")&&uniform.type()==UniformType.TEXEL_BUFFER&&uniform.gpuFormat()==GpuFormat.R32_SINT,"backend texel type/format preserved");
     check(prepared.uniformIndices().getInt("u_SectionTimeInfo")==0&&reflected.binding()==0&&reflected.descriptorSetIndex()==0,"dense buffer binding agrees with frontend");
     check(prepared.storageBindings().isEmpty(),"engine texel buffer not misrouted through image/storage suffix");
    }catch(ShaderCompileException expected){if(correct)throw expected;check(expected.getMessage().contains("Texel-buffer/image binding mismatch"),"wrong type fails for correct reason");}
   }
  }
  var features=List.of(IrisVulkanDeviceFeatures.VERTEX_STORES,IrisVulkanDeviceFeatures.FRAGMENT_STORES,IrisVulkanDeviceFeatures.EXTENDED_STORAGE_FORMATS,IrisVulkanDeviceFeatures.ROBUST_BUFFER_ACCESS);
  var original=Set.of(new com.mojang.renderpearl.backend.vulkan.init.VulkanFeature(com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets.VK10_FEATURES_STRUCT,"sampleRateShading"));
  for(int mask=0;mask<16;mask++){
   var selected=IrisVulkanDeviceFeatures.selectSupported(original,(mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0);
   check(selected.containsAll(original),"original required features retained");
   check(original.size()==1,"original feature set not mutated");
   try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
    var nativeFeatures=org.lwjgl.vulkan.VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
    for(var feature:selected)feature.set(nativeFeatures,true,stack);
    boolean[] bits={nativeFeatures.features().vertexPipelineStoresAndAtomics(),nativeFeatures.features().fragmentStoresAndAtomics(),nativeFeatures.features().shaderStorageImageExtendedFormats(),nativeFeatures.features().robustBufferAccess()};
    for(int i=0;i<4;i++)check(bits[i]==((mask&(1<<i))!=0),"correct Features2 member offset");
    check(nativeFeatures.features().sampleRateShading(),"unrelated feature preserved in native struct");
   }
  }
  // Reproduce the exact old overload failure without changing MemoryStack size.
  String expanded="#version 450 core\n/*"+"expanded pack source ".repeat(12000)+"*/\nvoid main(){gl_Position=vec4(0,0,0,1);}";
  int stackBefore=org.lwjgl.system.MemoryStack.stackGet().getPointer();
  var handleField=type.getDeclaredField("compiler");handleField.setAccessible(true);
  var optionsField=type.getDeclaredField("options");optionsField.setAccessible(true);
  try{
   long oldResult=org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(handleField.getLong(compiler),expanded,org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader,"oversized-old","main",optionsField.getLong(compiler));
   if(oldResult!=0)org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(oldResult);
   throw new AssertionError("Old CharSequence overload unexpectedly fit oversized shader on MemoryStack");
  }catch(OutOfMemoryError expected){check(expected.getMessage().contains("Out of stack space"),"reproduced LWJGL stack exhaustion");}
  check(org.lwjgl.system.MemoryStack.stackGet().getPointer()==stackBefore,"failed overload restores temporary stack");
  try(var large=shader("oversized-new",expanded,ShaderType.VERTEX)){
   check(large.spv().remaining()>20,"large shader compiles to real SPIR-V with native UTF8 buffers");
   check(large.type()==ShaderType.VERTEX,"large shader stage retained");
  }
  check(org.lwjgl.system.MemoryStack.stackGet().getPointer()==stackBefore,"new compiler leaves MemoryStack unchanged");
  ((AutoCloseable)compiler).close();
  System.out.println("PASS "+checks+" real shaderc/SPIR-V/reflection checks (Minecraft26.3; no GPU invocation)");
 }
}
