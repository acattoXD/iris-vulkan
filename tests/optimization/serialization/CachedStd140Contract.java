package net.irisshaders.iris.uniforms.custom;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.*;
import java.nio.*;
import java.util.*;

public final class CachedStd140Contract {
 private static int checks;
 private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[]args){
  int[] calls={0};float[] value={2};
  var inputs=new CustomUniformFixedInputUniformsHolder.Builder();
  inputs.uniform3f(UniformUpdateFrequency.PER_FRAME,"color",()->{calls[0]++;return new Vector3f(value[0],3,4);});
  inputs.uniformMatrix(UniformUpdateFrequency.PER_FRAME,"matrix",()->{calls[0]++;return new Matrix4f().translation(value[0],3,4);});
  inputs.uniform1i(UniformUpdateFrequency.PER_FRAME,"integer",()->{calls[0]++;return-1234;});
  inputs.uniform3i(UniformUpdateFrequency.PER_FRAME,"intVector",()->{calls[0]++;return new Vector3i(-7,8,9);});
  var provider=new CustomUniforms.Builder().build(inputs.build());var fields=List.of("color","matrix","integer","intVector");provider.beginFrame();provider.updateFor(fields);int updated=calls[0];
  ByteBuffer target=ByteBuffer.allocate(160).order(ByteOrder.nativeOrder());Arrays.fill(target.array(),(byte)0x7f);target.position(137);
  check(provider.writeCachedStd140("color","vec3",target,16),"known vector serialized");
  check(target.position()==137,"caller cursor untouched");check(target.getFloat(16)==2&&target.getFloat(20)==3&&target.getFloat(24)==4&&target.getInt(28)==0x7f7f7f7f,"vec3 writes only its 12-byte payload");
  check(target.getInt(12)==0x7f7f7f7f&&target.getInt(32)==0x7f7f7f7f,"adjacent bytes untouched");
  byte[] prior=target.array().clone();check(!provider.writeCachedStd140("color","ivec3",target,16),"wrong type rejected");check(Arrays.equals(prior,target.array()),"wrong type does not write");
  check(!provider.writeCachedStd140("missing","float",target,0),"missing name rejected");check(Arrays.equals(prior,target.array()),"missing name does not write");
  check(provider.writeCachedStd140("matrix","mat4",target,48),"cached matrix serialized");check(target.getFloat(48+48)==2&&target.getFloat(48+52)==3&&target.getFloat(48+56)==4,"matrix layout preserved");
  check(provider.writeCachedStd140("integer","int",target,0)&&target.getInt(0)==-1234,"signed integer preserved");
  var first=provider.lookup("color").orElseThrow();var second=provider.lookup("color").orElseThrow();
  check(first.value()!=second.value(),"public lookups return independent vectors");((Vector3f)first.value()).set(900);check(((Vector3f)second.value()).x==2,"snapshot does not alias sibling");
  provider.writeCachedStd140("color","vec3",target,16);check(target.getFloat(16)==2,"snapshot mutation cannot change cached data");
  var matrix=provider.lookup("matrix").orElseThrow();((Matrix4f)matrix.value()).identity();provider.writeCachedStd140("matrix","mat4",target,48);check(target.getFloat(96)==2,"matrix snapshot cannot change cache");
  for(int i=0;i<10000;i++)provider.writeCachedStd140("color","vec3",target,16);check(calls[0]==updated,"serialization/public lookup never reevaluate suppliers");
  value[0]=9;provider.beginFrame();provider.updateFor(fields);provider.writeCachedStd140("color","vec3",target,16);check(target.getFloat(16)==9&&((Vector3f)second.value()).x==2,"next frame fresh cache and old snapshot independence");
  check(calls[0]==updated+4,"exactly one new supplier evaluation per next-frame input");
  var otherInputs=new CustomUniformFixedInputUniformsHolder.Builder();otherInputs.uniform3f(UniformUpdateFrequency.PER_FRAME,"color",()->new Vector3f(44));var other=new CustomUniforms.Builder().build(otherInputs.build());other.beginFrame();other.updateFor(List.of("color"));other.writeCachedStd140("color","vec3",target,16);check(target.getFloat(16)==44,"another provider has separate scratch/cache");provider.writeCachedStd140("color","vec3",target,16);check(target.getFloat(16)==9,"returning to prior provider preserves value");
  try{provider.writeCachedStd140("matrix","mat4",target.asReadOnlyBuffer(),48);throw new AssertionError("Read-only matrix target accepted");}catch(ReadOnlyBufferException expected){checks++;}
  check(provider.writeCachedStd140("color","vec3",target,16)&&target.getFloat(16)==9,"scratch remains usable after a rejected target");
  ByteBuffer bigEndian=ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN);provider.writeCachedStd140("matrix","mat4",bigEndian,0);check(bigEndian.getFloat(48)==9,"matrix stores honor caller byte order");
  ByteBuffer adjacent=ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());adjacent.putFloat(12,123.25f);
  check(provider.writeCachedStd140("color","vec3",adjacent,0),"vec3 writes beside existing scalar");
  check(adjacent.getFloat(12)==123.25f,"vec3 preserves scalar in legal std140 offset+12 slot");
  adjacent.putInt(12,0x12345678);check(provider.writeCachedStd140("intVector","ivec3",adjacent,0),"ivec3 writes beside existing scalar");
  check(adjacent.getInt(12)==0x12345678,"ivec3 preserves adjacent scalar");
  ByteBuffer exact=ByteBuffer.allocate(12).order(ByteOrder.nativeOrder());
  check(provider.writeCachedStd140("color","vec3",exact,0)&&exact.getFloat(0)==9&&exact.getFloat(4)==3&&exact.getFloat(8)==4,"vec3 fits exact12-byte heap buffer");
  check(provider.writeCachedStd140("intVector","ivec3",exact,0)&&exact.getInt(0)==-7&&exact.getInt(4)==8&&exact.getInt(8)==9,"ivec3 fits exact12-byte heap buffer");
  System.out.println("CACHED_STD140_PASS: "+checks+" type/padding/cursor/supplier/provider/public-snapshot isolation checks");
 }
}
