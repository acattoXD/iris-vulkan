package net.irisshaders.iris.vulkan;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.management.ManagementFactory;
import java.lang.ref.*;
import java.util.*;

public final class MetadataCacheContract {
 static int checks; static volatile Object sink;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static final class NeverHash {
  @Override public int hashCode(){throw new AssertionError("User hashCode was invoked");}
  @Override public boolean equals(Object other){throw new AssertionError("User equals was invoked");}
 }
 static final class CountMap extends Object2IntOpenHashMap<String> {
  int hashes;
  @Override public int hashCode(){hashes++;return super.hashCode();}
 }
 static final class Backend implements BackendRenderPipeline {public boolean isClosed(){return false;}public void close(){}}
 static WeakReference<?>[] addRetired(IrisVulkanIdentityCache<Object,Object> cache){Object key=new Object(),value=new Object();cache.put(key,value);return new WeakReference<?>[]{new WeakReference<>(key),new WeakReference<>(value)};}
 static void collect(WeakReference<?> reference){for(int i=0;i<20&&reference.get()!=null;i++)System.gc();check(reference.get()==null,"retired referent collected");}
 static List<String> oldSamplers(List<BindGroupLayout> layouts){return BindGroupLayout.flattenUniforms(layouts).stream().filter(u->u.type()==UniformType.COMBINED_IMAGE_SAMPLER).map(BindGroupLayout.UniformDescription::name).toList();}
 static List<BindGroupLayout.UniformDescription> oldBuffers(List<BindGroupLayout> layouts){return BindGroupLayout.flattenUniforms(layouts).stream().filter(u->u.type()!=UniformType.COMBINED_IMAGE_SAMPLER).toList();}
 static long allocations(Runnable task){var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);long id=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(id);task.run();return bean.getThreadAllocatedBytes(id)-before;}
 public static void main(String[]args)throws Exception{
  var cache=new IrisVulkanIdentityCache<Object,Object>();Object first=new NeverHash(),second=new NeverHash();cache.put(first,"a");cache.put(second,"b");
  for(int i=0;i<10000;i++){check(cache.get(first).equals("a"),"identity hit without hash/equals");cache.put(first,"a");}
  check(cache.size()==2&&cache.get(second).equals("b"),"different identities coexist");cache.put(first,"updated");check(cache.get(first).equals("updated"),"existing key update");
  var recordCache=new IrisVulkanIdentityCache<FrontendRenderPipeline,Object>();var indices=new CountMap();for(int i=0;i<24;i++)indices.put("field"+i,i);
  var frontend=new FrontendRenderPipeline("identity-contract",new Backend(),List.of(DefaultVertexFormat.POSITION),indices,List.of(),List.of(ColorTargetState.DEFAULT),false,0);
  Map<FrontendRenderPipeline,Object> old=new WeakHashMap<>();old.put(frontend,"value");indices.hashes=0;for(int i=0;i<10000;i++)sink=old.get(frontend);check(indices.hashes==10000,"old real frontend record traverses uniform map on every lookup");
  recordCache.put(frontend,"value");indices.hashes=0;for(int i=0;i<10000;i++){sink=recordCache.get(frontend);recordCache.put(frontend,"value");}check(indices.hashes==0,"new descriptor cache avoids frontend graph hashing for reads and registrations");
  var retirement=new IrisVulkanIdentityCache<Object,Object>();var weak=addRetired(retirement);collect(weak[0]);check(weak[1].get()!=null,"value alive until queued entry purged");for(int i=0;i<50&&retirement.size()!=0;i++){System.gc();Thread.sleep(5);}
  check(retirement.size()==0,"stale key queue drained");collect(weak[1]);
  var one=BindGroupLayout.builder().withUniform("tex",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Globals",UniformType.UNIFORM_BUFFER).build();
  var two=BindGroupLayout.builder().withUniform("noise",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("u_SectionTimeInfo",UniformType.TEXEL_BUFFER,GpuFormat.R32_SINT).withUniform("tex",UniformType.COMBINED_IMAGE_SAMPLER).build();
  List<BindGroupLayout> layouts=List.of(one,two);var samplers=IrisVulkanLayouts.samplers(layouts);var buffers=IrisVulkanLayouts.buffers(layouts);
  check(samplers.equals(List.of("tex","noise","tex")),"sampler order/duplicates retained");check(buffers.equals(oldBuffers(layouts)),"UBO and typed texel metadata retained");
  for(int i=0;i<10000;i++){check(IrisVulkanLayouts.samplers(layouts)==samplers,"immutable sampler cache hit");check(IrisVulkanLayouts.buffers(layouts)==buffers,"immutable buffer cache hit");}
  try{samplers.add("bad");throw new AssertionError("mutable cached output");}catch(UnsupportedOperationException expected){checks++;}
  var mutable=new ArrayList<>(layouts);var before=IrisVulkanLayouts.samplers(mutable);mutable.clear();mutable.add(one);check(IrisVulkanLayouts.samplers(mutable).equals(List.of("tex")),"mutable caller never gets stale split");check(before.equals(samplers),"old result remains immutable snapshot");
  List<BindGroupLayout> perfLayouts=List.of(BindGroupLayout.builder().withUniform("tex",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("noise",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Globals",UniformType.UNIFORM_BUFFER).withUniform("IrisUniforms",UniformType.UNIFORM_BUFFER).build());
  for(int i=0;i<20000;i++){sink=oldSamplers(perfLayouts);sink=oldBuffers(perfLayouts);sink=IrisVulkanLayouts.samplers(perfLayouts);sink=IrisVulkanLayouts.buffers(perfLayouts);}
  long oldBytes=allocations(()->{for(int i=0;i<100000;i++){sink=oldSamplers(perfLayouts);sink=oldBuffers(perfLayouts);}});
  long newBytes=allocations(()->{for(int i=0;i<100000;i++){sink=IrisVulkanLayouts.samplers(perfLayouts);sink=IrisVulkanLayouts.buffers(perfLayouts);}});
  check(newBytes<oldBytes/20,"cached metadata removes at least95percent of allocation in isolated lookup contract");
  Reference.reachabilityFence(first);Reference.reachabilityFence(second);Reference.reachabilityFence(frontend);
  System.out.println("METADATA_CACHE_PASS: "+checks+" identity/lifetime/immutable/mutable checks; frontend map hashes10000->0; 100000 sampler+buffer query pairs allocatedBytes="+oldBytes+"->"+newBytes+" (not FPS measurement)");
 }
}
