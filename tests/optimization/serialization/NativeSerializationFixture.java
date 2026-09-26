package net.irisshaders.iris.vulkan;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.*;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.*;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Same fixture runs against the frozen alpha9 JAR and candidate classes in separate JVMs. */
public final class NativeSerializationFixture {
 public static Matrix4f currentModelView=new Matrix4f();
 public static FogParameters currentFog=FogParameters.NONE;
 public static Vector3f currentFogColor=new Vector3f();
 public static WorldRenderingPhase currentPhase=WorldRenderingPhase.NONE;
 public static Matrix4f testModelView(String name){return new Matrix4f(currentModelView);}
 public static FogParameters testFog(){return currentFog;}
 public static Vector3f testFogColor(){return new Vector3f(currentFogColor);}
 public static WorldRenderingPhase testPhase(){return currentPhase;}
 private static final String[] MATRICES={"gbufferModelView","gbufferModelViewInverse","gbufferProjection","gbufferProjectionInverse","gbufferPreviousModelView","gbufferPreviousProjection","shadowModelView","shadowProjection","shadowModelViewInverse","shadowProjectionInverse","iris_ModelViewMatrix","iris_ModelViewMat","iris_ModelViewMatrixInverse","iris_ModelViewMatInverse","iris_ProjectionMatrix","iris_ProjMat","iris_ProjectionMatrixInverse","iris_ProjMatInverse"};
 private static final Map<String,Integer> calls=new TreeMap<>();
 private static int frame,draw;
 private static float scalar(int provider,String name){calls.merge(provider+":"+name,1,Integer::sum);return frame*7+provider*100+0.25f;}
 private static void count(int provider,String name){calls.merge(provider+":"+name,1,Integer::sum);}
 private static CustomUniforms provider(int id){
  var inputs=new CustomUniformFixedInputUniformsHolder.Builder();
  inputs.uniform1b(UniformUpdateFrequency.PER_FRAME,"probeBool",()->{count(id,"bool");return(frame+id)%2==0;});
  inputs.uniform1i(UniformUpdateFrequency.PER_FRAME,"probeInt",()->{count(id,"int");return-frame*17-id;});
  inputs.uniform1f(UniformUpdateFrequency.PER_FRAME,"probeFloat",()->scalar(id,"float"));
  inputs.uniform2f(UniformUpdateFrequency.PER_FRAME,"probeVec2",()->{count(id,"vec2");return new Vector2f(frame,id);});
  inputs.uniform3f(UniformUpdateFrequency.PER_FRAME,"probeVec3",()->{count(id,"vec3");return new Vector3f(frame+0.25f,id,-0.0f);});
  inputs.uniform4f(UniformUpdateFrequency.PER_FRAME,"probeVec4",()->{count(id,"vec4");return new Vector4f(frame,id,0.5f,-4);});
  inputs.uniform2i(UniformUpdateFrequency.PER_FRAME,"probeIVec2",()->{count(id,"ivec2");return new Vector2i(frame,-id);});
  inputs.uniform3i(UniformUpdateFrequency.PER_FRAME,"probeIVec3",()->{count(id,"ivec3");return new Vector3i(frame,id,-100);});
  inputs.uniformMatrix(UniformUpdateFrequency.PER_FRAME,"probeMatrix",()->{count(id,"matrix");return new Matrix4f().rotateY(frame*0.01f).translate(id,2,3);});
  for(String name:MATRICES)inputs.uniformMatrix(UniformUpdateFrequency.PER_FRAME,name,()->{count(id,name);return new Matrix4f().scaling(777);});
  for(String name:List.of("frameCounter","iris_NativeHandDraw","entityId","renderStage","textureReloadCount"))inputs.uniform1i(UniformUpdateFrequency.PER_FRAME,name,()->{count(id,name);return-999;});
  for(String name:List.of("frameTime","frameTimeCounter","fogStart","fogEnd","iris_FogStart","iris_FogEnd"))inputs.uniform1f(UniformUpdateFrequency.PER_FRAME,name,()->{count(id,name);return-999f;});
  inputs.uniform2i(UniformUpdateFrequency.PER_FRAME,"atlasSize",()->{count(id,"atlasSize");return new Vector2i(-999);});
  inputs.uniform3i(UniformUpdateFrequency.PER_FRAME,"iris_NativeEntityIds",()->{count(id,"iris_NativeEntityIds");return new Vector3i(-999);});
  inputs.uniform3f(UniformUpdateFrequency.PER_FRAME,"fogColor",()->{count(id,"fogColor");return new Vector3f(-999);});
  // This existing lower-priority builtin remains deliberately overrideable by the provider.
  inputs.uniformMatrix(UniformUpdateFrequency.PER_FRAME,"iris_LightmapTextureMatrix",()->{count(id,"lightmap");return new Matrix4f().scaling(id+10);});
  var variables=new CustomUniforms.Builder();variables.addVariable("float","probeDerived","probeFloat * 0.25",true);
  return variables.build(inputs.build());
 }
 private static IrisVulkanUniformSnapshot.Field f(String n,String t){return new IrisVulkanUniformSnapshot.Field(n,t);}
 private static void set(String name,Object value)throws Exception{var field=IrisVulkanUniformSnapshot.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);}
 public static void main(String[]args)throws Exception{
  Path out=Path.of(args[0]);Files.createDirectories(out);CustomUniforms a=provider(1),b=provider(2);
  List<IrisVulkanUniformSnapshot.Field> fields=new ArrayList<>(List.of(f("probeBool","bool"),f("probeInt","int"),f("probeFloat","float"),f("probeVec2","vec2"),f("probeVec3","vec3"),f("probeDerived","float"),f("probeVec4","vec4"),f("probeIVec2","ivec2"),f("probeIVec3","ivec3"),f("probeMatrix","mat4")));
  for(String name:MATRICES)fields.add(f(name,"mat4"));
  for(String name:List.of("frameCounter","iris_NativeHandDraw","entityId","renderStage","textureReloadCount"))fields.add(f(name,"int"));
  for(String name:List.of("frameTime","frameTimeCounter","fogStart","fogEnd","iris_FogStart","iris_FogEnd"))fields.add(f(name,"float"));
  fields.add(f("atlasSize","ivec2"));fields.add(f("iris_NativeEntityIds","ivec3"));fields.add(f("fogColor","vec3"));fields.add(f("iris_LightmapTextureMatrix","mat4"));
  fields=List.copyOf(fields);List<IrisVulkanUniformSnapshot.Field> reordered=List.copyOf(fields.reversed());int captures=0;
  ShadowRenderer.MODELVIEW=new Matrix4f().rotateX(0.5f);ShadowRenderer.PROJECTION=new Matrix4f().setOrtho(-10,10,-10,10,-30,30);
  try(DataOutputStream output=new DataOutputStream(Files.newOutputStream(out.resolve("captures.bin")))){
   for(frame=1;frame<=48;frame++){
    set("vulkanFrameCounter",frame);set("vulkanFrameTime",frame*0.002f);set("vulkanFrameTimeCounter",frame*0.04f);a.beginFrame();b.beginFrame();
    CapturedRenderingState.INSTANCE.setGbufferModelView(new Matrix4f().rotateY(frame*0.02f));
    CapturedRenderingState.INSTANCE.setGbufferProjection(new Matrix4f().setPerspective(1.1f,1.5f,512f,0.05f,true));
    for(int id=1;id<=2;id++){
     CustomUniforms provider=id==1?a:b;IrisVulkanUniformSnapshot.registerActiveCustomUniforms(provider);
     for(draw=1;draw<=4;draw++){
      int entity=frame*100+id*10+draw;CapturedRenderingState.INSTANCE.setCurrentEntity(entity);CapturedRenderingState.INSTANCE.setCurrentBlockEntity(entity+1);CapturedRenderingState.INSTANCE.setCurrentRenderedItem(entity+2);
      var texture=CapturedRenderingState.class.getDeclaredField("textureReloadCount");texture.setAccessible(true);texture.setInt(CapturedRenderingState.INSTANCE,entity+3);
      IrisVulkanUniformSnapshot.setPrimaryTextureSize(draw*128,id*256);currentModelView=new Matrix4f().rotateX(draw*0.15f).rotateY(frame*0.02f);
      currentFog=new FogParameters(0.1f,0.2f,0.3f,1,draw+5,frame+40,200,250,250);currentFogColor.set(draw*0.1f,id*0.2f,frame*0.01f);currentPhase=WorldRenderingPhase.values()[(frame+draw)%WorldRenderingPhase.values().length];
      if((draw&1)==0)IrisVulkanPhaseContext.captureHandMatrices(new Matrix4f().setPerspective(0.8f,1.5f,512f,0.05f,true),currentModelView);else IrisVulkanPhaseContext.clearHandMatrices();
      for(var requested:List.of(fields,reordered)){ByteBuffer bytes=IrisVulkanUniformSnapshot.capture(requested).data();byte[] copy=new byte[bytes.remaining()];bytes.get(copy);output.writeInt(copy.length);output.write(copy);captures++;}
     }
    }
   }
  }finally{IrisVulkanPhaseContext.clearHandMatrices();IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(b);IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(a);}
  Files.writeString(out.resolve("supplier-calls.txt"),calls.toString());
  if(captures!=768)throw new AssertionError("wrong fixture count");
  System.out.println("NATIVE_SERIALIZATION_FIXTURE_PASS captures="+captures+" fields="+fields.size()+" supplierEntries="+calls.size());
 }
}
