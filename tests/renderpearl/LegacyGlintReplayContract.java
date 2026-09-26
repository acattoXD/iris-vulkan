package net.irisshaders.iris.vulkan;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.*;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.RenderPipelines;
import java.util.*;

public final class LegacyGlintReplayContract {
 private static int checks;
 private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  var ordinary=DefaultVertexFormat.ENTITY;
  var special=DefaultVertexFormat.ENTITY_GLINT_SPECIAL;
  check(IrisVulkanLegacyGlint.overlayVertexFormat(ordinary)==ordinary,"ordinary source format not rebuilt");
  var remapped=IrisVulkanLegacyGlint.overlayVertexFormat(special);
  check(remapped.getVertexSize()==special.getVertexSize(),"special geometry stride retained");
  check(remapped.getElement("UV0").offset()==special.getElement("UV3").offset(),"overlay UV0 addresses producer UV3");
  check(remapped.getElement("iris_LegacyGlintBaseUV").offset()==special.getElement("UV0").offset(),"base UV remains intact in same buffer");
  check(remapped.getElement("Position").offset()==special.getElement("Position").offset(),"position offset retained");
  check(special.contains("UV3")&&!special.contains("iris_LegacyGlintBaseUV"),"original producer format immutable");
  var originals=List.of(RenderPipelines.ITEM_CUTOUT_GLINT,RenderPipelines.ENTITY_SOLID_GLINT,RenderPipelines.ITEM_TRANSLUCENT_GLINT,
    RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL,RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL,RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT);
  for(var original:originals){
   var overlay=IrisVulkanLegacyGlint.overlayPipeline(original);
   check(original.getShaderDefines().flags().contains("GLINT"),"known integrated producer has GLINT flag");
   check(overlay.getShaders().equals(RenderPipelines.GLINT.getShaders()),"reuses actual legacy glint shaders");
   check(overlay.getDepthStencilState().depthTest()==CompareOp.EQUAL&&!overlay.getDepthStencilState().writeDepth(),"equal nonwriting overlay depth");
   check(overlay.getColorTargetStates().equals(RenderPipelines.GLINT.getColorTargetStates()),"exact legacy blend state retained");
   check(overlay.getVertexFormatBinding(0).getVertexSize()==original.getVertexFormatBinding(0).getVertexSize(),"actual producer stride retained");
   check(overlay.getPrimitiveTopology()==original.getPrimitiveTopology(),"original primitive indexing retained");
   check(IrisPipelines.getPipeline(null,overlay)==ShaderKey.GLINT,"pack ArmorGlint route retained");
   check(IrisVulkanShadowDrawPolicy.shouldSkip(true,overlay),"replayed overlay cannot enter shadows");
   check(!IrisVulkanShadowDrawPolicy.shouldSkip(true,original),"integrated base still casts shadow");
  }
  check(!IrisVulkanLegacyGlint.consumesGlintApi("// mc_sampleGlint()\\n void main(){}"),"comment is not opt-in");
  check(!IrisVulkanLegacyGlint.consumesGlintApi("bool mc_hasGlint(); vec3 mc_sampleGlint(){ return vec3(0); }"),"prototype/definition alone is not consumption");
  check(IrisVulkanLegacyGlint.consumesGlintApi("void main(){ if(mc_hasGlint()) color += mc_sampleGlint(); }"),"explicit consumer opts out");
  check(IrisVulkanLegacyGlint.consumesGlintApi(null,"void main(){ color=mc_sampleGlint(); }"),"fragment consumer opts out");
  check(IrisVulkanLegacyGlint.consumesGlintApi("void main(){ color=mc_sampleGlint(); }",null),"vertex consumer opts out");
  check(IrisVulkanLegacyGlint.shouldReplay(ShaderKey.ENTITIES_CUTOUT_GLINT,false,true,false),"legacy integrated geometry gets overlay");
  check(!IrisVulkanLegacyGlint.shouldReplay(ShaderKey.ENTITIES_CUTOUT_GLINT,false,true,true),"new API consumer never doubled");
  check(!IrisVulkanLegacyGlint.shouldReplay(ShaderKey.ENTITIES_CUTOUT_GLINT,true,true,false),"shadow has no replay");
  check(!IrisVulkanLegacyGlint.shouldReplay(ShaderKey.ENTITIES_CUTOUT_GLINT,false,false,false),"no unrelated texture fallback");
  check(!IrisVulkanLegacyGlint.shouldReplay(ShaderKey.GLINT,false,true,false),"legacy overlay not itself replayed");
  var overlay=IrisVulkanLegacyGlint.overlayPipeline(RenderPipelines.ITEM_CUTOUT_GLINT);
  int[] calls={0};
  IrisVulkanLegacyGlint.replay(null,p->calls[0]++);check(calls[0]==0,"no-op gate");
  long count=IrisVulkanLegacyGlint.replayCount();
  IrisVulkanLegacyGlint.replay(overlay,p->{calls[0]++;check(IrisVulkanLegacyGlint.active(),"sampler override scoped to replay");IrisVulkanLegacyGlint.replay(overlay,nested->calls[0]++);});
  check(calls[0]==1&&IrisVulkanLegacyGlint.replayCount()==count+1,"exactly one successful replay");
  check(!IrisVulkanLegacyGlint.active(),"scope restored after replay");
  try{IrisVulkanLegacyGlint.replay(overlay,p->{throw new IllegalStateException("contract");});throw new AssertionError("exception swallowed");}catch(IllegalStateException expected){check(!IrisVulkanLegacyGlint.active(),"scope restored on failed draw");}
  System.out.println("LEGACY_GLINT_REPLAY_PASS: "+checks+" actual format/pipeline, API opt-out, dispatch and scope checks");
 }
}
