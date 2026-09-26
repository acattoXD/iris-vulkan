package net.irisshaders.iris.vulkan;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.custom.*;
import org.joml.*;
import java.nio.*;
import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Runs the actual private per-field serializer against old/new classes with identical inputs. */
public final class NativeSerializationBenchmark {
 private static volatile long sink;
 public static void main(String[]args)throws Throwable{
  int[] suppliers={0};var input=new CustomUniformFixedInputUniformsHolder.Builder();
  input.uniform1b(UniformUpdateFrequency.PER_FRAME,"vBool",()->{suppliers[0]++;return true;});
  input.uniform1i(UniformUpdateFrequency.PER_FRAME,"vInt",()->{suppliers[0]++;return-1234;});
  input.uniform1f(UniformUpdateFrequency.PER_FRAME,"vFloat",()->{suppliers[0]++;return return0();});
  input.uniform2f(UniformUpdateFrequency.PER_FRAME,"vVec2",()->{suppliers[0]++;return new Vector2f(2,3);});
  input.uniform3f(UniformUpdateFrequency.PER_FRAME,"vVec3",()->{suppliers[0]++;return new Vector3f(4,5,6);});
  input.uniform4f(UniformUpdateFrequency.PER_FRAME,"vVec4",()->{suppliers[0]++;return new Vector4f(7,8,9,10);});
  input.uniform2i(UniformUpdateFrequency.PER_FRAME,"vIVec2",()->{suppliers[0]++;return new Vector2i(11,12);});
  input.uniform3i(UniformUpdateFrequency.PER_FRAME,"vIVec3",()->{suppliers[0]++;return new Vector3i(13,14,15);});
  input.uniformMatrix(UniformUpdateFrequency.PER_FRAME,"vMat",()->{suppliers[0]++;return new Matrix4f().translation(16,17,18);});
  var provider=new CustomUniforms.Builder().build(input.build());var fields=List.of(new IrisVulkanUniformSnapshot.Field("vBool","bool"),new IrisVulkanUniformSnapshot.Field("vInt","int"),new IrisVulkanUniformSnapshot.Field("vFloat","float"),new IrisVulkanUniformSnapshot.Field("vVec2","vec2"),new IrisVulkanUniformSnapshot.Field("vVec3","vec3"),new IrisVulkanUniformSnapshot.Field("vVec4","vec4"),new IrisVulkanUniformSnapshot.Field("vIVec2","ivec2"),new IrisVulkanUniformSnapshot.Field("vIVec3","ivec3"),new IrisVulkanUniformSnapshot.Field("vMat","mat4"));
  provider.beginFrame();provider.updateFor(fields.stream().map(IrisVulkanUniformSnapshot.Field::name).toList());IrisVulkanUniformSnapshot.registerActiveCustomUniforms(provider);
  var lookup=MethodHandles.privateLookupIn(IrisVulkanUniformSnapshot.class,MethodHandles.lookup());var writer=lookup.findStatic(IrisVulkanUniformSnapshot.class,"write",MethodType.methodType(void.class,ByteBuffer.class,int.class,IrisVulkanUniformSnapshot.Field.class));
  ByteBuffer data=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());for(int i=0;i<20000;i++)for(int fieldIndex=0;fieldIndex<fields.size();fieldIndex++){var field=fields.get(fieldIndex);writer.invokeExact(data,0,field);sink=data.getLong(0);}
  var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();allocation.setThreadAllocatedMemoryEnabled(true);long id=Thread.currentThread().threadId();long before=allocation.getThreadAllocatedBytes(id),start=System.nanoTime();int iterations=100000;
  for(int i=0;i<iterations;i++)for(int fieldIndex=0;fieldIndex<fields.size();fieldIndex++){var field=fields.get(fieldIndex);writer.invokeExact(data,0,field);sink=data.getLong(0);}
  long elapsed=System.nanoTime()-start,bytes=allocation.getThreadAllocatedBytes(id)-before;IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(provider);
  if(suppliers[0]!=9)throw new AssertionError("Serializer changed supplier count: "+suppliers[0]);
  System.out.println("{\"fieldWrites\":"+(iterations*fields.size())+",\"allocatedBytes\":"+bytes+",\"elapsedNanos\":"+elapsed+",\"supplierCalls\":"+suppliers[0]+",\"scope\":\"isolated per-field CPU serializer; not FPS\"}");
 }
 private static float return0(){return 1.5f;}
}
