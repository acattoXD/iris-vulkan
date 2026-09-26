package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import java.lang.reflect.*;
import java.util.*;

/** Execute the production afterShadows callback against recorded compute/graphics boundaries. */
public final class IrisVulkanAfterShadowsBarrierTest {
    private static final String SELF=IrisVulkanAfterShadowsBarrierTest.class.getName().replace('.','/');
    private static final List<String> EVENTS=new ArrayList<>();
    private static int checks,widthCalls,heightCalls;
    public static int width(){widthCalls++;return 960;}
    public static int height(){heightCalls++;return 540;}
    public static void barrier(){EVENTS.add("stageBarrier");}
    public static final class Compute {
        final Map<Integer,String> actions;long count=17;int scans;
        public Compute(Map<Integer,String> actions){this.actions=actions;}
        public long dispatchCount(){return count;}
        public void dispatchStage(ProgramArrayId stage,int index,int width,int height){
            check(stage==ProgramArrayId.ShadowComposite,"Only shadowcomp stage");check(width==960&&height==540,"Cached viewport dimensions");scans++;
            String action=actions.get(index);if(action==null||action.equals("invalid"))return;
            if(action.equals("fail")){EVENTS.add("failure:"+index);throw new IllegalStateException("Injected dispatch failure");}
            EVENTS.add("pre:"+index);EVENTS.add(action.equals("zero")?"zeroGroups:"+index:"dispatch:"+index);EVENTS.add("post:"+index);count++;
        }
    }
    public static final class Executor {public void renderPrepare(){EVENTS.add("prepare");}}
    public static void main(String[] args)throws Exception{
        String original="net/irisshaders/iris/vulkan/IrisVulkanFinalPassRenderer",generated=SELF+"$Subject";
        ClassNode node=read(original),chosen=new ClassNode();chosen.version=node.version;chosen.access=Opcodes.ACC_PUBLIC;chosen.name=original;chosen.superName="java/lang/Object";
        for(FieldNode field:node.fields)if(field.name.equals("compute")||field.name.equals("executor"))chosen.fields.add(field);
        MethodNode callback=node.methods.stream().filter(m->m.name.equals("afterShadows")).findFirst().orElseThrow();
        for(var instruction:callback.instructions)if(instruction instanceof MethodInsnNode call && call.owner.equals(original)
                && (call.name.equals("width")||call.name.equals("height")))call.owner=SELF;
        chosen.methods.add(callback);
        MethodNode ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Lnet/irisshaders/iris/vulkan/IrisVulkanComputeExecutor;Lnet/irisshaders/iris/vulkan/IrisVulkanScreenPassExecutor;)V",null,null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));ctor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,original,"compute","Lnet/irisshaders/iris/vulkan/IrisVulkanComputeExecutor;"));
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));ctor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,original,"executor","Lnet/irisshaders/iris/vulkan/IrisVulkanScreenPassExecutor;"));ctor.instructions.add(new InsnNode(Opcodes.RETURN));chosen.methods.add(ctor);
        Map<String,String> mapping=Map.of(original,generated,"net/irisshaders/iris/vulkan/IrisVulkanComputeExecutor",SELF+"$Compute",
            "net/irisshaders/iris/vulkan/IrisVulkanScreenPassExecutor",SELF+"$Executor","net/irisshaders/iris/vulkan/IrisVulkanStorageResources",SELF);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);chosen.accept(new ClassRemapper(writer,new SimpleRemapper(mapping)));byte[] bytes=writer.toByteArray();
        Class<?> subject=new ClassLoader(IrisVulkanAfterShadowsBarrierTest.class.getClassLoader()){Class<?> define(){return defineClass(generated.replace('/','.'),bytes,0,bytes.length);}}.define();
        run(subject,null,List.of("prepare"),false);
        run(subject,Map.of(),List.of("stageBarrier","prepare"),false);
        run(subject,Map.of(0,"invalid",99,"invalid"),List.of("stageBarrier","prepare"),false);
        run(subject,Map.of(0,"run"),List.of("pre:0","dispatch:0","post:0","prepare"),false);
        run(subject,Map.of(99,"run"),List.of("pre:99","dispatch:99","post:99","prepare"),false);
        run(subject,Map.of(0,"run",37,"run",99,"run"),List.of("pre:0","dispatch:0","post:0","pre:37","dispatch:37","post:37","pre:99","dispatch:99","post:99","prepare"),false);
        run(subject,Map.of(0,"zero"),List.of("pre:0","zeroGroups:0","post:0","prepare"),false);
        run(subject,Map.of(0,"fail"),List.of("failure:0"),true);
        run(subject,Map.of(0,"run",50,"fail"),List.of("pre:0","dispatch:0","post:0","failure:50"),true);
        verifyDispatchBarriers();
        System.out.println("PASS "+checks+" actual callback recording checks: absent/empty/invalid/first/late/multiple/zero-work dispatches, viewport caching and failures; compute pre/post barriers remain in actual compiled dispatch");
    }
    private static void run(Class<?> subject,Map<Integer,String> actions,List<String> expected,boolean fails)throws Exception{
        EVENTS.clear();widthCalls=heightCalls=0;Compute compute=actions==null?null:new Compute(actions);
        Object instance=subject.getConstructor(Compute.class,Executor.class).newInstance(compute,new Executor());boolean failed=false;
        try{subject.getMethod("afterShadows").invoke(instance);}catch(InvocationTargetException error){failed=true;check(error.getCause() instanceof IllegalStateException,"Dispatch failure propagated");}
        check(failed==fails,"Expected failure status");check(EVENTS.equals(expected),"Exact boundary ordering: "+EVENTS);
        check(widthCalls==(compute==null?0:1)&&heightCalls==(compute==null?0:1),"Viewport queried once per stage loop");
        if(compute!=null&&!fails)check(compute.scans==ProgramArrayId.ShadowComposite.getNumPrograms(),"All sparse shadowcomp indices visited");
        if(fails)check(!EVENTS.contains("prepare"),"Do not prepare after an exception");
    }
    private static void verifyDispatchBarriers()throws Exception{
        ClassNode program=read("net/irisshaders/iris/vulkan/IrisVulkanComputeExecutor$Program");
        MethodNode dispatch=program.methods.stream().filter(m->m.name.equals("dispatch")&&m.desc.equals("(II)V")).findFirst().orElseThrow();
        int position=0,first=Integer.MAX_VALUE,last=-1,barriers=0,minDispatch=Integer.MAX_VALUE,maxDispatch=-1;
        for(var instruction:dispatch.instructions){
            if(instruction instanceof MethodInsnNode call){
                if(call.owner.equals("net/irisshaders/iris/vulkan/IrisVulkanStorageResources")&&call.name.equals("barrier")){first=Math.min(first,position);last=position;barriers++;}
                if(call.name.equals("vkCmdDispatch")||call.name.equals("vkCmdDispatchIndirect")){minDispatch=Math.min(minDispatch,position);maxDispatch=Math.max(maxDispatch,position);}
            }position++;
        }
        check(barriers==2,"Retain both actual compute storage barriers");check(first<minDispatch&&last>maxDispatch,"Barriers bracket direct and indirect dispatch sites");
        ClassNode executor=read("net/irisshaders/iris/vulkan/IrisVulkanComputeExecutor");
        MethodNode outer=executor.methods.stream().filter(m->m.name.equals("dispatch")&&!m.desc.equals("(II)V")).findFirst().orElseThrow();
        boolean called=false,increment=false;
        for(var instruction:outer.instructions){
            if(instruction instanceof MethodInsnNode call&&call.owner.endsWith("IrisVulkanComputeExecutor$Program")&&call.name.equals("dispatch"))called=true;
            if(instruction instanceof FieldInsnNode field&&field.name.equals("dispatchCount")&&field.getOpcode()==Opcodes.PUTFIELD){check(called,"Count advances only after Program.dispatch returns");increment=true;}
        }
        check(increment,"Actual completed-dispatch counter exists");
    }
    private static ClassNode read(String name)throws Exception{try(var input=IrisVulkanAfterShadowsBarrierTest.class.getClassLoader().getResourceAsStream(name+".class")){ClassNode node=new ClassNode();new ClassReader(input).accept(node,0);return node;}}
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
}
