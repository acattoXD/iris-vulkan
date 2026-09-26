package net.irisshaders.iris.vulkan;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

/** Real 26.3 shader keys and vertex formats, without a game or GPU. */
public final class IrisVulkanIntegratedGlintTest {
 private static int checks;
 private static void check(boolean value,String message){++checks;if(!value)throw new AssertionError(message);}
 public static void main(String[] args) {
  ShaderKey[][] routes={
   {ShaderKey.ENTITIES_SOLID_GLINT,ShaderKey.HAND_CUTOUT_GLINT,ShaderKey.HAND_TRANSLUCENT_GLINT},
   {ShaderKey.ENTITIES_CUTOUT_GLINT,ShaderKey.HAND_CUTOUT_GLINT_DIFFUSE,ShaderKey.HAND_TRANSLUCENT_GLINT_DIFFUSE},
   {ShaderKey.ENTITIES_TRANSLUCENT_GLINT,ShaderKey.HAND_CUTOUT_GLINT_DIFFUSE,ShaderKey.HAND_TRANSLUCENT_GLINT_DIFFUSE},
   {ShaderKey.ENTITIES_CUTOUT_GLINT_SPECIAL,ShaderKey.HAND_CUTOUT_GLINT_SPECIAL,ShaderKey.HAND_TRANSLUCENT_GLINT_SPECIAL},
   {ShaderKey.ENTITIES_TRANSLUCENT_GLINT_SPECIAL,ShaderKey.HAND_CUTOUT_GLINT_SPECIAL,ShaderKey.HAND_TRANSLUCENT_GLINT_SPECIAL},
   {ShaderKey.ENTITIES_CUTOUT_GLINT_ARMOR,ShaderKey.HAND_CUTOUT_GLINT_ARMOR,ShaderKey.HAND_TRANSLUCENT_GLINT_ARMOR}
  };
  String source="#version 450\nvoid main(){gl_Position=vec4(0,0,0,1);}";
  for(var route:routes){
   check(IrisVulkanPhaseContext.mapShaderKey(route[0],WorldRenderingPhase.ENTITIES)==route[0],"entity key retained");
   for(int i=1;i<3;i++){
    ShaderKey key=IrisVulkanPhaseContext.mapShaderKey(route[0],i==1?WorldRenderingPhase.HAND_SOLID:WorldRenderingPhase.HAND_TRANSLUCENT);
    check(key==route[i],"correct hand family for "+route[0]);
    check(key.isGlint(),"foil retained");
    var inputs=new ShaderAttributeInputs(key.getVertexFormat(),key.shouldIgnoreLightmap(),false,key.isGlint(),false,false);
    check(inputs.hasOverlay()&&inputs.hasNormal()&&inputs.hasTex(),"base mesh channels retained");
    check(inputs.isSpecialGlint()==key.name().contains("SPECIAL"),"special UV3 flag inferred from vertex format");
    String transformed=IrisVulkanShaderResources.patchWorldClipDepth(source,key);
    check(transformed.split("gl_Position.z =",-1).length-1==2,"hand reverse-Z plus one compression");
    check(!transformed.contains("iris_NativeHandDraw"),"combined hand uses keyed compression, not legacy dynamic flag");
   }
   String world=IrisVulkanShaderResources.patchWorldClipDepth(source,route[0]);
   check(world.split("gl_Position.z =",-1).length-1==1,"world combinedglint has no hand compression");
  }
  var original=DefaultVertexFormat.ENTITY_GLINT_SPECIAL;
  var aliased=IrisVulkanShaderResources.aliasVanillaVertexFormat(original);
  check(aliased.contains("iris_UV3"),"special glint UV3 has translated attribute name");
  check(aliased.getElement("iris_UV3").offset()==original.getElement("UV3").offset(),"UV3 producer offset unchanged");
  check(aliased.getVertexSize()==original.getVertexSize(),"special glint stride unchanged");
  IrisVulkanGlintShadowTest.main(args);
  System.out.println("IRIS_INTEGRATED_GLINT_PASS: "+checks+" hand/UV3/depth contract checks; integrated shadow bases retained");
 }
}
