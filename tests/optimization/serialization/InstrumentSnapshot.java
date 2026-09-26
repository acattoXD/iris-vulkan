import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.zip.*;
/** Substitute only environmental source accessors, identically for baseline and candidate. */
public final class InstrumentSnapshot {
 public static void main(String[]args)throws Exception{
  byte[] bytes=Files.readAllBytes(Path.of(args[0]));ClassWriter writer=new ClassWriter(0);
  new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
   @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[]exceptions){
    MethodVisitor method=super.visitMethod(access,name,descriptor,signature,exceptions);
    String helper=switch(name){case "currentModelView"->"testModelView";case "fogParameters"->"testFog";case "capturedFogColor"->"testFogColor";default->null;};
    if(helper!=null){method.visitCode();if(name.equals("currentModelView"))method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKESTATIC,"net/irisshaders/iris/vulkan/NativeSerializationFixture",helper,descriptor,false);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,name.equals("currentModelView")?1:0);method.visitEnd();return null;}
    return new MethodVisitor(Opcodes.ASM9,method){@Override public void visitMethodInsn(int op,String owner,String methodName,String desc,boolean iface){if(owner.equals("net/irisshaders/iris/layer/GbufferPrograms")&&methodName.equals("getCurrentPhase")){owner="net/irisshaders/iris/vulkan/NativeSerializationFixture";methodName="testPhase";}super.visitMethodInsn(op,owner,methodName,desc,iface);}};
   }
  },0);Path destination=Path.of(args[1]);Files.createDirectories(destination.getParent());Files.write(destination,writer.toByteArray());
 }
}
